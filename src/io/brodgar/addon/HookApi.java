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
 * The interception + input subsystem. Owns the four ways an addon reaches into client behaviour beyond the
 * read API:
 * <ul>
 *   <li><b>L1 input hooks</b> ({@code hafen.hook():input}) — {@link Widget#listen} pre-hooks on mapview/gameui/root;</li>
 *   <li><b>mouse grab</b> ({@code hafen.hook():grab}) — a modal drag capture (V5, the gizmo primitive);</li>
 *   <li><b>slash commands</b> ({@code hafen.slash}) — WoW-style {@code :name} console commands (A11);</li>
 *   <li><b>global hotkeys</b> ({@code hafen.client:options():keybindings()}) — remappable keys over the
 *       {@link KeyBinding} registry ({@link #dispatchKey}); the Lua surface is {@link KeybindingsOptions}.</li>
 * </ul>
 *
 * <p><b>The two message streams left in 041.2.</b> What were the L2 (outbound {@code wdgmsg}) and L3 (inbound
 * {@code uimsg}) hook levels are now {@code hafen.event():action():on(msg, fn)} and
 * {@code hafen.event():message():on(msg, fn)} — the same choke points and the same precedence, over the one
 * {@link Subs} mechanism, dispatched by {@link AddonManager#dispatchAction}/{@link AddonManager#dispatchMessage}.
 * They moved because a subscription with no object to hang off belongs on the bus (spec {@code 041} §R2): a
 * {@code "click"} can come from any widget and a {@code "set"} can go to any widget.
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
     * Build {@code hafen.hook} / {@code hafen.slash} for {@code owner}. From {@code installHafen}. Both are
     * plain section objects: the section is called and every verb is a colon call on it, so the receiver is
     * argument 1 and a hook's own arguments start at 2.
     */
    static void install(LuaTable hafen, final Addon owner) {
        LuaTable hook = new LuaTable();
        // input(target, event, fn) — L1: a keyboard/mouse gesture on mapview/gameui/root, before the client.
        hook.set("input", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "hook", "input");
                return newInputHook(owner, a.arg(2), a.arg(3), a.arg(4));
            }
        });
        // action(msg, fn) and message(msg, fn) are GONE (041.2): the two message streams are doors of the bus
        // now, hafen.event():action():on(msg, fn) and hafen.event():message():on(msg, fn). Both spellings are
        // rows in Retired, so the old call throws naming its replacement rather than reading nil.
        // grab{move=fn, up=fn} — take the mouse for a modal drag.
        hook.set("grab", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "hook", "grab");
                return newMouseGrab(owner, a.arg(2));
            }
        });
        Section.install(hafen, "hook", hook);

        LuaTable slash = new LuaTable();
        // register(name, fn) — route the console command :name to fn(args).
        slash.set("register", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "slash", "register");
                return newSlashCommand(owner, a.arg(2), a.arg(3));
            }
        });
        Section.install(hafen, "slash", slash);
    }

    // ================================================================= L1 input hooks (hafen.hook():input, 2c)

    private static LuaValue newInputHook(final Addon owner, LuaValue target, LuaValue event, LuaValue fn) {
        if(!event.isstring() || !fn.isfunction())
            throw new LuaError("hafen.hook():input(target, event, fn) expects (target, string, function)");
        Class<? extends Widget.Event> cls = eventClass(event.tojstring());
        if(cls == null)
            throw new LuaError("hafen.hook():input: unknown event '" + event.tojstring()
                               + "' (expected mousedown / mouseup / mousemove / mousewheel)");
        String tok = target.isstring() ? target.tojstring().toLowerCase() : null;
        if(!isKnownTarget(tok))
            throw new LuaError("hafen.hook():input: target must be \"mapview\", \"gameui\", or \"root\" (got "
                               + (target.isnil() ? "nil" : target.tojstring()) + ")");
        Widget w = hookTarget(tok);
        if(w == null)
            throw new LuaError("hafen.hook():input: the " + tok
                               + " is not up yet — register this hook in EnterWorld");
        final LuaInputHook h = new LuaInputHook(owner, w, event.tojstring(), fn);
        listenHook(w, cls, h);
        owner.hooks.add(h);
        LuaTable handle = new LuaTable();
        handle.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                removeHook(owner, h);
                return LuaValue.NIL;
            }
        });
        return handle;
    }

    /** Map an input-hook event name to its {@link Widget.Event} class (2c supports the mouse gestures). */
    private static Class<? extends Widget.Event> eventClass(String name) {
        if(name.equals("mousedown"))  return Widget.MouseDownEvent.class;
        if(name.equals("mouseup"))    return Widget.MouseUpEvent.class;
        if(name.equals("mousemove"))  return Widget.MouseMoveEvent.class;
        if(name.equals("mousewheel")) return Widget.MouseWheelEvent.class;
        return null;
    }

    /** Is {@code tok} a recognized hook-target token? (Distinguishes "unknown target" from "not up yet".) */
    static boolean isKnownTarget(String tok) {
        return "mapview".equals(tok) || "map".equals(tok)
            || "gameui".equals(tok)  || "hud".equals(tok)
            || "root".equals(tok);
    }

    /** Resolve a (lower-cased, already-known) hook-target token to the live {@link Widget}, or null if not up. */
    static Widget hookTarget(String tok) {
        if("mapview".equals(tok) || "map".equals(tok))
            return AddonManager.view;
        if("gameui".equals(tok) || "hud".equals(tok))
            return AddonManager.gui();
        if("root".equals(tok)) {
            UI u = AddonManager.ui;
            return (u == null) ? null : u.root;
        }
        return null;
    }

    /**
     * Register {@code h} as a typed listener on {@code w}. {@link Widget#listen} wants an
     * {@code EventHandler<? super E>}; a {@link LuaInputHook} is {@code EventHandler<Widget.Event>} (it works
     * for any concrete event type), so we widen the class token's <i>compile-time</i> type — the runtime
     * {@link Class} is unchanged, so listener matching ({@code t.isInstance}) still keys on the real subclass.
     */
    @SuppressWarnings("unchecked")
    private static void listenHook(Widget w, Class<? extends Widget.Event> cls, LuaInputHook h) {
        w.listen((Class<Widget.Event>)(Class<?>)cls, h);
    }

    /** Remove one input hook: stop it firing, deafen the target, drop it from the registry (handle :remove()). */
    private static void removeHook(Addon owner, LuaInputHook h) {
        h.alive = false;
        try {
            h.target.deafen(h);
        } catch(RuntimeException e) {
            /* target already gone (its listener list went with it): harmless */
        }
        owner.hooks.remove(h);
    }

    /** Deafen + drop every input hook this addon owns (teardown on reload/disable, P2). */
    static void teardownHooks(Addon a) {
        for(LuaInputHook h : a.hooks) {
            h.alive = false;
            try {
                h.target.deafen(h);
            } catch(RuntimeException e) {
                /* target already destroyed; best-effort, never abort teardown */
            }
        }
        a.hooks.clear();
    }

    // ================================================================= mouse grab (hafen.hook():grab, V5)

    /**
     * {@code hafen.hook():grab{move=fn, up=fn}} (V5) — start a modal mouse-drag capture: a {@link LuaMouseGrab} widget
     * on {@code ui.root} that forwards mouse move/up to Lua while the grab captures the drag (so the MapView neither
     * pans nor clicks). Returns a handle {@code { :release() }}; bridge-owned for teardown. Nil if the UI is not up.
     */
    private static LuaValue newMouseGrab(final Addon owner, LuaValue handlers) {
        if(!handlers.istable())
            throw new LuaError("hafen.hook():grab{move=fn, up=fn} expects a handlers table");
        UI u = AddonManager.ui;
        if((u == null) || (u.root == null))
            return LuaValue.NIL;                        // no UI yet
        LuaValue mv = handlers.get("move"), up = handlers.get("up");
        final LuaMouseGrab g = new LuaMouseGrab(owner, mv.isfunction() ? mv : null, up.isfunction() ? up : null);
        owner.mouseGrabs.add(g);
        u.root.add(g);                                  // add() synchronizes on ui; visible -> receives broadcast moves
        g.arm(u);                                       // ui.grabmouse(this) — capture the terminating up wherever it lands
        LuaTable handle = new LuaTable();
        handle.set("release", new ZeroArgFunction() {
            public LuaValue call() {
                g.release();
                return LuaValue.NIL;
            }
        });
        return handle;
    }

    /** Release every active mouse grab this addon owns (teardown on reload/disable, P2). */
    static void teardownMouseGrabs(Addon a) {
        for(LuaMouseGrab g : a.mouseGrabs)
            g.release();               // drops the UI.Grab + marks dead; the widget unlinks on its next (or the last) tick
        a.mouseGrabs.clear();
    }

    // ================================================================= slash commands (hafen.slash, A11)

    private static LuaValue newSlashCommand(final Addon owner, LuaValue name, LuaValue fn) {
        if(!name.isstring() || !fn.isfunction())
            throw new LuaError("hafen.slash():register(name, fn) expects (string, function)");
        final String cmd = name.tojstring();
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
                    UI u = AddonManager.ui;
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
