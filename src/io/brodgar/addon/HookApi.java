package io.brodgar.addon;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import haven.Console;
import haven.KeyBinding;
import haven.KeyMatch;
import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

/**
 * The interception subsystem. Owns the two ways an addon reaches into client behaviour beyond the read API
 * that are not a widget's own {@code :on(key, fn)} or the bus's (everything else moved off, see below):
 * <ul>
 *   <li><b>slash commands</b> ({@code hafen.slash}) — WoW-style {@code :name} console commands (A11);</li>
 *   <li><b>global hotkeys</b> ({@code hafen.client:options():keybindings()}) — remappable keys over the
 *       {@link KeyBinding} registry ({@link #dispatchKey}); the Lua surface is {@link KeybindingsOptions}.</li>
 * </ul>
 *
 * <p><b>What moved off this class.</b> The two message streams left in 041.2: what were the L2 (outbound
 * {@code wdgmsg}) and L3 (inbound {@code uimsg}) hook levels are now {@code hafen.event():action():on(msg, fn)}
 * and {@code hafen.event():message():on(msg, fn)}, over {@link Subs}, dispatched by
 * {@link AddonManager#dispatchAction}/{@link AddonManager#dispatchMessage}. The L1 input hooks left in 041.3:
 * {@code hafen.hook():input(target, ev, fn)} — three magic string tokens, an API limit rather than an engine
 * one — is now {@code handle:on("MouseDown"/"MouseUp"/"MouseMove"/"Wheel", fn)} on any widget, over
 * {@link WidgetSubs}. The V5 mouse grab left in 041.5: {@code hafen.hook():grab{move=, up=}} is now
 * {@code hafen.ui():mouse():grab()}, an emitter ({@link LuaGrab}) over {@link LuaMouseGrab}'s own {@link Subs}
 * — {@code hafen.hook()} itself is deleted, since grab was its last remaining verb. All three moved because a
 * subscription belongs where its address is (spec {@code 041} §R2): a {@code "click"}, a {@code MouseDown} or
 * the pointer itself has no fixed home a table of magic tokens or config keys could enumerate.
 *
 * <p>The engine seams stay in {@link AddonManager} (the {@code haven} core calls them by name —
 * {@code onWdgmsg}/{@code onMessage}/{@code onGlobKey}); the keybind <i>panel</i> API
 * ({@code describeKeyBinds}/{@code KeyBindGroup}/{@code KeyBindEntry}, used by {@code haven.OptWnd}) also
 * stays there and reads {@link #keyBinds}. All members static; not instantiable.
 */
final class HookApi {
    private HookApi() {}

    // -- addon slash commands (A11): WoW-style :command. slashHandlers = name -> current live handler;
    // slashDispatched = names whose ONE engine-lifetime Console dispatcher is installed (grows only — never
    // reset per session, so :reload swaps the handler with no duplicate/leaked command, coverage-gaps C1).
    private static final Map<String, LuaSlashCommand> slashHandlers = new ConcurrentHashMap<String, LuaSlashCommand>();
    private static final Set<String> slashDispatched = ConcurrentHashMap.newKeySet();

    // -- global hotkeys (spec 07 "Input" / Phase 2e-2): a flat list matched by KeyMatch per unconsumed
    // keypress. Package-private so AddonManager.describeKeyBinds (the OptWnd panel facade) can read it.
    static final List<LuaKeyBind> keyBinds = new CopyOnWriteArrayList<LuaKeyBind>();

    // Named-key table for parseKeyMatch (F1..F12, arrows, Home/End/…). Built once (VK_* are compile-time consts).
    private static final Map<String, Integer> KEYCODES = new HashMap<String, Integer>();
    static {
        for(int i = 1; i <= 12; i++)                       // F1..F12 (VK_F1..VK_F12 are consecutive)
            KEYCODES.put("F" + i, KeyEvent.VK_F1 + (i - 1));
        KEYCODES.put("SPACE",     KeyEvent.VK_SPACE);
        KEYCODES.put("ENTER",     KeyEvent.VK_ENTER);
        KEYCODES.put("RETURN",    KeyEvent.VK_ENTER);
        KEYCODES.put("TAB",       KeyEvent.VK_TAB);
        KEYCODES.put("ESC",       KeyEvent.VK_ESCAPE);
        KEYCODES.put("ESCAPE",    KeyEvent.VK_ESCAPE);
        KEYCODES.put("BACKSPACE", KeyEvent.VK_BACK_SPACE);
        KEYCODES.put("DELETE",    KeyEvent.VK_DELETE);
        KEYCODES.put("DEL",       KeyEvent.VK_DELETE);
        KEYCODES.put("INSERT",    KeyEvent.VK_INSERT);
        KEYCODES.put("INS",       KeyEvent.VK_INSERT);
        KEYCODES.put("HOME",      KeyEvent.VK_HOME);
        KEYCODES.put("END",       KeyEvent.VK_END);
        KEYCODES.put("PAGEUP",    KeyEvent.VK_PAGE_UP);
        KEYCODES.put("PGUP",      KeyEvent.VK_PAGE_UP);
        KEYCODES.put("PAGEDOWN",  KeyEvent.VK_PAGE_DOWN);
        KEYCODES.put("PGDN",      KeyEvent.VK_PAGE_DOWN);
        KEYCODES.put("UP",        KeyEvent.VK_UP);
        KEYCODES.put("DOWN",      KeyEvent.VK_DOWN);
        KEYCODES.put("LEFT",      KeyEvent.VK_LEFT);
        KEYCODES.put("RIGHT",     KeyEvent.VK_RIGHT);
    }

    /**
     * Build {@code hafen.slash} for {@code owner}. From {@code installHafen}. {@code hafen.hook} is not
     * mounted at all any more (041.5): input, action, message and grab have all moved elsewhere, and a section
     * with nothing left in it is not kept around as an empty shell — reading {@code hafen.hook} throws
     * naming where each half went ({@link Retired}).
     */
    static void install(LuaTable hafen, final Addon owner) {
        LuaTable slash = new LuaTable();
        // register(name, fn) — route the console command :name to fn(args).
        slash.set("register", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "slash", "register");
                return newSlashCommand(owner, a);
            }
        });
        Section.install(hafen, "slash", slash);
    }

    // ================================================================= (the target tokens are gone, 048.6)
    // This file used to carry a shared token lookup — "mapview"/"map", "gameui"/"hud", "root" → a live Widget.
    // It was L1's own until the widget-input door left in 041.3, after which hafen.act():raw was its one
    // remaining caller; 048.6 replaced raw with widget:send(msg, ...), where the receiver IS the target, so the
    // vocabulary had nothing left to address and was DELETED rather than followed to LuaWidget. Each token is an
    // ordinary handle: s:ui():find("@MapView"), s:ui():find("@GameUI"), s:ui():root().

    // ================================================================= slash commands (hafen.slash, A11)

    private static LuaValue newSlashCommand(final Addon owner, Varargs a) {
        // Args first, and one argument at a time: "expects (string, function)" named neither which of the
        // two was missing nor which was the wrong kind, and a missing one is the commoner mistake.
        final String cmd = Args.str(a, 2, "hafen.slash():register", "name",
                                    "the word typed after the colon").tojstring();
        LuaValue fn = Args.required(a, 3, "hafen.slash():register", "fn");
        if(!fn.isfunction())
            throw new LuaError("hafen.slash():register: fn must be a function — it is called with the rest"
                + " of the line, got " + fn.typename());
        if((cmd.length() == 0) || hasWhitespace(cmd))
            throw new LuaError("hafen.slash():register: name must be a non-empty word with no spaces (got '" + cmd + "')");
        if(isReservedSlash(cmd))
            throw new LuaError("hafen.slash():register: ':" + cmd + "' is a reserved engine command");
        final LuaSlashCommand h = new LuaSlashCommand(owner, cmd, fn);
        synchronized(slashDispatched) {
            if(!slashDispatched.contains(cmd)) {
                // First time we see this name: refuse if a client command already owns it (our dispatcher would
                // otherwise clobber a static command, or be silently shadowed by an instance/dir command), then
                // install the ONE engine-lifetime dispatcher that forever routes to slashHandlers.get(cmd) (C1).
                boolean exists = false;
                try {
                    UI u = AddonManager.screen();
                    exists = (u != null) && (u.cons != null) && (u.cons.findcmd(cmd) != null);
                } catch(RuntimeException e) {
                    /* best-effort collision check — proceed if the console can't be queried right now */
                }
                if(exists)
                    throw new LuaError("hafen.slash():register: ':" + cmd + "' is already a client command");
                Console.setscmd(cmd, new Console.Command() {
                    public void run(Console cons, String[] args) {
                        dispatchSlash(cmd, args);
                    }
                });
                slashDispatched.add(cmd);
            } else {
                // A dispatcher already exists for this name — a :reload re-register (same addon) or a takeover by a
                // different addon. If a LIVE handler owned by a different addon holds it, note the reassignment
                // (last registration wins, WoW-like); a same-owner re-register (the reload case) is silent.
                LuaSlashCommand cur = slashHandlers.get(cmd);
                if((cur != null) && cur.alive && (cur.owner != owner))
                    AddonManager.log("slash ':" + cmd + "' reassigned from '" + idOf(cur.owner)
                                     + "' to '" + idOf(owner) + "'");
            }
            slashHandlers.put(cmd, h);   // last registration wins (the current live handler the dispatcher routes to)
        }
        owner.slashCommands.add(h);
        LuaTable handle = new LuaTable();
        handle.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                removeSlashCommand(owner, h);
                return LuaValue.NIL;
            }
        });
        return handle;
    }

    /**
     * Run the current handler for console command {@code :name}. Installed ONCE per name (engine-lifetime) and
     * routes to {@code slashHandlers.get(name)} — the live handler — so it survives {@code :reload} with no
     * re-registration (C1). If no addon currently owns the name, it replies with a friendly notice. {@code invoke}
     * routes through {@link AddonManager#callLua} (watchdog-armed, error-isolated, CPU-accounted).
     */
    private static void dispatchSlash(String name, String[] words) {
        LuaSlashCommand h = slashHandlers.get(name);
        if((h == null) || !h.alive) {
            AddonManager.log("no addon currently handles :" + name);
            return;
        }
        h.invoke(words);
    }

    /** Remove one slash command: drop the live handler + drop it from the owner (the engine dispatcher stays, C1). */
    private static void removeSlashCommand(Addon owner, LuaSlashCommand h) {
        h.alive = false;
        slashHandlers.remove(h.name, h);   // only if h is STILL the current handler (a later addon may own it now)
        owner.slashCommands.remove(h);
    }

    /** Drop every slash command this addon owns (teardown on reload/disable, P2). Console dispatchers stay (C1). */
    static void teardownSlashCommands(Addon a) {
        for(LuaSlashCommand h : a.slashCommands) {
            h.alive = false;
            slashHandlers.remove(h.name, h);
        }
        a.slashCommands.clear();
    }

    /** Is {@code name} one of the addon engine's own console commands (which live in the same static map)? */
    private static boolean isReservedSlash(String name) {
        return name.equals("lua") || name.equals("addons") || name.equals("reload");
    }

    /** True if {@code s} contains any whitespace (a console command name is a single whitespace-split word). */
    private static boolean hasWhitespace(String s) {
        for(int i = 0; i < s.length(); i++) {
            if(Character.isWhitespace(s.charAt(i)))
                return true;
        }
        return false;
    }

    // ============================================ global hotkeys (hafen.client:options():keybindings(), 2e-2)

    /**
     * Declare one addon hotkey — the body of {@code keybindings:register(name, fn)}. The binding starts
     * <b>unbound</b> (D-047): the addon names an action, the user assigns the key in Options ▸ Keybindings.
     */
    static void newKeyBind(final Addon owner, String name, LuaValue fn) {
        // KeyBinding.get() is a process-global registry: it returns the SAME binding across reloads/sessions, so
        // a user's assignment (persisted in the client prefs) survives; KeyMatch.nil applies only on first create.
        KeyBinding kbnd = KeyBinding.get("addon/" + owner.manifest.id + "/" + name, KeyMatch.nil);
        LuaKeyBind h = new LuaKeyBind(owner, name, kbnd, fn);
        keyBinds.add(h);
        owner.keybinds.add(h);
    }

    /**
     * Parse a hotkey description ("F5", "Ctrl+M", "Shift+Alt+Left", "None") into a {@link KeyMatch}. The last
     * {@code "+"}-separated token is the key; the earlier tokens are modifiers (Ctrl/Control/Ctl, Shift, Alt/Meta,
     * case-insensitive). A named key (see {@link #KEYCODES}) resolves via {@link KeyMatch#forcode}; any single
     * character resolves via {@link KeyMatch#forchar}. {@code "None"}/empty → {@link KeyMatch#nil}. Modifier
     * matching is exact (no mods → the bare key only). Returns {@code null} if it cannot be parsed.
     */
    static KeyMatch parseKeyMatch(String desc) {
        if(desc == null)
            return null;
        String s = desc.trim();
        if(s.isEmpty() || s.equalsIgnoreCase("none"))
            return KeyMatch.nil;
        String[] parts = s.split("\\+");
        String keytok = parts[parts.length - 1].trim();
        if(keytok.isEmpty())                          // e.g. a trailing '+' with no key
            return null;
        int mods = 0;
        for(int i = 0; i < parts.length - 1; i++) {
            String m = parts[i].trim().toLowerCase();
            if(m.equals("ctrl") || m.equals("control") || m.equals("ctl") || m.equals("c"))
                mods |= KeyMatch.C;
            else if(m.equals("shift") || m.equals("s"))
                mods |= KeyMatch.S;
            else if(m.equals("alt") || m.equals("meta") || m.equals("m"))
                mods |= KeyMatch.M;
            else
                return null;                          // unknown modifier token
        }
        Integer code = KEYCODES.get(keytok.toUpperCase());
        if(code != null)
            return KeyMatch.forcode(code, mods);
        if(keytok.length() == 1)
            return KeyMatch.forchar(keytok.charAt(0), mods);
        return null;                                  // unknown multi-character key name
    }

    /** Remove one hotkey: stop it firing + drop it from the global dispatch list. */
    private static void removeKeyBind(Addon owner, LuaKeyBind h) {
        h.alive = false;
        keyBinds.remove(h);
        owner.keybinds.remove(h);
    }

    /**
     * Drop every hotkey {@code owner} registered under {@code name} (the body of
     * {@code keybindings:unregister(name)}). Silent when the addon has no such hotkey — unregistering twice, or
     * naming a client binding the addon does not own, is a no-op rather than an error.
     */
    static void removeKeyBindsNamed(Addon owner, String name) {
        for(LuaKeyBind h : owner.keybinds) {          // copy-on-write: safe to remove while iterating
            if(h.name.equals(name))
                removeKeyBind(owner, h);
        }
    }

    /**
     * Mark dead + unregister every hotkey this addon owns (teardown on reload/disable, P2). The {@link KeyBinding}
     * registry entries are process-global + persistent and are deliberately left intact (that is how the client
     * remembers a re-mapped addon key across reloads) — teardown drops only the Lua-handler wrapper.
     */
    static void teardownKeyBinds(Addon a) {
        for(LuaKeyBind h : a.keybinds) {
            h.alive = false;
            keyBinds.remove(h);
        }
        a.keybinds.clear();
    }

    /**
     * The global-hotkey dispatch (spec 07 "Input" / Phase 2e-2) — the body behind {@link AddonManager#onGlobKey},
     * called from {@link AddonRoot#globtype}. Runs the handler of the first addon hotkey
     * ({@code keybindings:register}) whose current key matches and returns whether the key was <b>consumed</b>. The addon-root is walked last,
     * so a client binding on the same key wins — an addon hotkey is the fallback, never a hijack.
     */
    static boolean dispatchKey(Widget.GlobKeyEvent ev) {
        if(keyBinds.isEmpty())
            return false;                            // fast path: no addon hotkeys anywhere
        for(LuaKeyBind kb : keyBinds) {              // copy-on-write: a hotkey may :remove() itself here
            if(kb.alive && kb.matches(ev)) {
                AddonManager.callLua(kb.owner, Addon.C_HOOK, kb.fn);
                return true;                         // consume: the addon bound this key
            }
        }
        return false;
    }

    /** An addon`s id for a log line (owner/manifest are non-null on the live paths; guarded for safety). */
    static String idOf(Addon a) {
        return ((a != null) && (a.manifest != null)) ? a.manifest.id : "?";
    }
}
