package io.brodgar.addon;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
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

/**
 * The interception subsystem. Owns the two ways an addon reaches into client behaviour beyond the read API
 * that are not a widget's own {@code :on(key, fn)} or the bus's (everything else moved off, see below):
 * <ul>
 *   <li><b>console commands</b> ({@code hafen.console}) — the {@code :name} commands the client's own
 *       {@link Console} dispatches (A11);</li>
 *   <li><b>global hotkeys</b> ({@code hafen.client():options():keybindings()}) — remappable keys over the
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

    // -- addon console commands (A11): the client's own :command. consoleHandlers = name -> current live handler;
    // consoleDispatched = names whose Console dispatcher is installed. Both are the CONSOLE's namespace rather
    // than any addon's — a command name is one word for the client, and whoever registered it last answers it.
    // audit2 B01: the dispatcher set no longer grows only. It is installed with the first handler for a name
    // and taken back out with the last (Console.unsetscmd), so a :reload still swaps the handler with no
    // duplicate, and a name nobody handles any more stops answering "no addon handles :name" and goes back to
    // being a word the console does not know.
    private static final Map<String, LuaConsoleCommand> consoleHandlers = new ConcurrentHashMap<String, LuaConsoleCommand>();
    private static final Set<String> consoleDispatched = ConcurrentHashMap.newKeySet();

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
     * Build {@code hafen.console} for {@code owner}. From {@code installHafen}. {@code hafen.hook} is not
     * mounted at all any more (041.5): input, action, message and grab have all moved elsewhere, and a section
     * with nothing left in it is not kept around as an empty shell — reading {@code hafen.hook} throws
     * naming where each half went ({@link Refusal}).
     *
     * <p><b>The section object IS the collection of this addon's commands</b> (086.2, §2.1: a section that
     * contains exactly one thing is that thing), mounted the way {@code hafen.timer()} is. Its members are
     * {@link Addon#consoleSubs}' own live {@link LuaSub}s — a command is a subscription, so what
     * {@code :list()} hands you is the very value {@code :on} handed you — and its key is the command name,
     * which is what {@code :get(name)} addresses and what a string filter matches.
     *
     * <p><b>This half is client-wide, and the other half is not</b> (111.1). {@link Console#setscmd} is
     * static, so a command registered here answers from every character and there is nothing to re-register
     * on a switch. SAYING a line is one character's — {@link #console(Addon, String)} — so
     * {@code run} is absent from this door and reading it throws naming {@code s:console():run(line)}
     * ({@link Refusal}) rather than the generic <i>has no verb</i>.
     */
    static void install(LuaTable hafen, final Addon owner) {
        LuaTable verbs = new LuaTable();
        // on(name, fn) — route the console command :name to fn(args). It is a SUBSCRIPTION like every other
        // :on in the API (086.1): the Sub it hands back answers :key() (the command name) and :off(), and
        // :off() drops the handler while the engine's own dispatcher stays installed forever (C1).
        verbs.set("on", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "hafen.console()", "on");
                return newConsoleCommand(owner, a);
            }
        });
        Section.mount(hafen, "console", LuaCollection.create("hafen.console()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(LuaSub s : owner.consoleSubs.live())
                    out.add(s.handle());
                return out;
            }

            // A command HAS a name — it is the word typed after the colon, and it is the sub's own key —
            // so a string filter is a substring match on it rather than the refusal a nameless kind gives.
            public boolean named() {
                return true;
            }

            public String needle(LuaValue member) {
                LuaSub s = LuaSub.resolve(member);
                return (s == null) ? null : s.key;
            }

            public boolean addressable() {
                return true;
            }

            public String keyName() {
                return "name";
            }

            public LuaValue getMember(LuaValue key) {
                if(!key.isstring())
                    return LuaValue.NIL;
                String nm = key.tojstring();
                for(LuaSub s : owner.consoleSubs.live()) {
                    if(s.key.equals(nm))
                        return s.handle();
                }
                return LuaValue.NIL;
            }

            // NIL, not MINT: a command you never registered is not a thing to hand back an object for —
            // there is nothing for it to be a handle TO, since the registration IS the subscription.
            public LuaCollection.Missing missing() {
                return LuaCollection.Missing.NIL;
            }
        }, verbs), null);
    }

    /** {@code s:console()} — how the section is reached, and so how every one of its messages spells itself. */
    private static final String CONS = "s:console()";
    /** Its one verb, as an author writes it — what {@link Args} and the gate open their refusals with. */
    private static final String RUN = CONS + ":run";

    /**
     * Build the <b>per-session</b> console section for {@code (owner, user)} — <b>that character's own
     * command line</b>, reached as {@code s:console()}. Minted once per {@code (addon, session)} and hung on
     * the interned Session handle, the shape every other session-addressed section has, so
     * {@code s:console() == s:console()}.
     *
     * <p><b>Why a line is one character's.</b> {@code UI.cons} is a {@code WidgetConsole} per {@link UI}:
     * {@code :lo} is {@code setcmd} on it and its body is {@code sess.close()}, {@code :gl} writes that tree's
     * own graphics prefs, and its {@code findcmd} walks <b>that</b> {@code UI}'s widget tree before it ever
     * reaches {@link Console}'s static table — so which {@code UI} a line runs on decides both which commands
     * resolve and what they act on. A line is therefore said <i>at</i> a login exactly as {@code channel:send}
     * is, and this section is the address.
     *
     * <p><b>The other half of the section stays client-wide</b> ({@link #install}): {@link Console#setscmd} is
     * static, so a command an addon registers answers from every character. Registering is
     * {@code hafen.console():on}; running is here, and reading {@code hafen.console():run} throws naming this
     * verb ({@link Refusal}) rather than the bare <i>has no verb</i>.
     *
     * <p><b>A command that fails is not the caller's error.</b> The body mirrors {@code ConsoleHost.done}
     * exactly — which is copied rather than called, being an instance method of a widget owning a
     * {@code ReadLine}, and no line is read here: catch every throwable, take {@link Refusal#reason} with a
     * {@code toString()} fallback, and write to <b>both</b> of the console's own exits, {@code cons.out} (that
     * character's System log once its HUD is up) and {@link UI#error} (its on-screen notice). It catches
     * {@code Exception} and not {@code Throwable}, which is what leaves {@code :die}'s {@code Error}
     * propagating exactly as it does from a typed line.
     *
     * <p><b>No monitor is taken, deliberately.</b> The console already nests them — a typed line runs inside
     * the drawn {@code UI}'s monitor and reaches into another session's — and anchor-then-member is the only
     * direction anything in this tree takes. Synchronising on the target would invent a second one.
     * {@link Console#run(Console.Host, String)} locks nothing, so a line runs on its caller's thread exactly
     * as a typed one does.
     */
    static LuaValue console(final Addon owner, final String user) {
        LuaTable m = new LuaTable();
        // run(line) — run one line through THAT character's console, exactly as though the user had typed it
        // there. The colon is the console's OPENER and is never part of the line. Returns the section, so
        // writes chain. The gate is the FIRST statement (D-213), so an addon that declared nothing hears about
        // its manifest even when its argument was wrong too.
        m.set("run", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                AddonManager.requirePermission(AddonManager.current(), Permission.CONSOLE_RUN);
                LuaValue self = a.arg1();
                Section.self(self, "console", "run", CONS);
                String line = Args.str(a, 2, RUN, "line",
                                       "the command as it is typed, without the opening colon").tojstring();
                String cmd = line.trim();
                String empty = RUN + ": there is no command in that line — line is the word the console"
                    + " dispatches and the rest of the line, as in " + RUN + "(\"lo\")";
                if(cmd.isEmpty())
                    throw new LuaError(empty);
                if(cmd.charAt(0) == ':') {
                    String rest = cmd.substring(1).trim();
                    if(rest.isEmpty())
                        throw new LuaError(empty);
                    throw new LuaError(RUN + ": the colon OPENS the console line and is never part of the"
                        + " line itself — write " + RUN + "(\"" + rest + "\")");
                }
                UI u = AddonManager.sessionui(user);
                if((u == null) || (u.root == null))
                    throw new LuaError(RUN + ": the client holds no console for the account '" + user
                        + "' — s:exists() is the test, and a session between trees (connecting, on the"
                        + " character list, gone) has no widget tree to run a line in");
                try {
                    u.cons.run(u.root, line);
                } catch(Throwable t) {
                    // ConsoleHost.done, verbatim: the console owns a refusal channel and it is the one the
                    // user reads. Raising here instead would reprint it behind the CALLER's addon tag, so
                    // `zzz` would read as the addon's mistake rather than the console's answer.
                    //   audit2 B14 (co-07): AND AN Error. This used to catch Exception alone, so `:die`'s
                    // own Error unwound out of the console and into the CALLING addon's Lua -- past a
                    // channel console.md promises raises nothing to catch. Two kinds are still not ours,
                    // for AddonManager.called's reasons: ThreadDeath belongs to whoever raised it, and
                    // anything caught while this thread is interrupted belongs to the quit.
                    if((t instanceof ThreadDeath) || Thread.currentThread().isInterrupted()) {
                        if(t instanceof Error)
                            throw (Error)t;
                        throw new LuaError(RUN + ": " + Refusal.reason(t));
                    }
                    String msg = Refusal.reason(t);
                    u.cons.out.println(msg);
                    u.error(msg);
                }
                return self;
            }
        });
        return Section.object("console", m, CONS);
    }

    // ================================================================= (the target tokens are gone, 048.6)
    // This file used to carry a shared token lookup — "mapview"/"map", "gameui"/"hud", "root" → a live Widget.
    // It was L1's own until the widget-input door left in 041.3, after which hafen.act():raw was its one
    // remaining caller; 048.6 replaced raw with widget:send(msg, ...), where the receiver IS the target, so the
    // vocabulary had nothing left to address and was DELETED rather than followed to LuaWidget. Each token is an
    // ordinary handle: s:ui():match("@MapView"), s:ui():match("@GameUI"), s:ui():root().

    // ================================================================= console commands (hafen.console, A11)

    private static LuaValue newConsoleCommand(final Addon owner, Varargs a) {
        // Args first, and one argument at a time: "expects (string, function)" named neither which of the
        // two was missing nor which was the wrong kind, and a missing one is the commoner mistake.
        final String cmd = Args.str(a, 2, "hafen.console():on", "name",
                                    "the word typed after the colon").tojstring();
        LuaValue fn = Args.required(a, 3, "hafen.console():on", "fn");
        if(!fn.isfunction())
            throw new LuaError("hafen.console():on: fn must be a function — it is called with the rest"
                + " of the line, got " + fn.typename());
        if((cmd.length() == 0) || hasWhitespace(cmd))
            throw new LuaError("hafen.console():on: name must be a non-empty word with no spaces (got '" + cmd + "')");
        if(isReservedCommand(cmd))
            throw new LuaError("hafen.console():on: ':" + cmd + "' is a reserved engine command");
        final LuaConsoleCommand h = new LuaConsoleCommand(owner, cmd, fn);
        synchronized(consoleDispatched) {
            if(!consoleDispatched.contains(cmd)) {
                // First time we see this name: refuse if a client command already owns it (our dispatcher would
                // otherwise clobber a static command, or be silently shadowed by an instance/dir command), then
                // install the ONE engine-lifetime dispatcher that forever routes to consoleHandlers.get(cmd) (C1).
                //   audit2 B08 (co-01): THE CONSOLE'S OWN RECORD ANSWERS THIS, not a session's console. The
                // check used to walk from the drawn UI to its Console and call findcmd -- an instance method,
                // so it needed a session, and there is none while the addon layer loads, which is exactly
                // when an addon registers its commands. The null was swallowed and the claim went through:
                // `:die`, `:gc`, `:lo`, `:gl`, `:act` and `:belt` were all claimable, and since findcmd reads
                // the STATIC map first, the claim then shadowed the client's own for the life of the client.
                // Console.declares reads a process-wide record of every name the client has declared, which
                // is answerable with no console in hand and cannot be null.
                if(Console.declares(cmd))
                    throw new LuaError("hafen.console():on: ':" + cmd + "' is already a client command");
                Console.setscmd(cmd, new Console.Command() {
                    public void run(Console cons, String[] args) {
                        dispatchConsole(cmd, args);
                    }
                });
                consoleDispatched.add(cmd);   // ...and endConsoleCommand takes both back out with the last handler
            } else {
                // A dispatcher already exists for this name — a :reload re-register (same addon) or a takeover by a
                // different addon. If a LIVE handler owned by a different addon holds it, note the reassignment
                // (last registration wins, WoW-like); a same-owner re-register (the reload case) is silent.
                LuaConsoleCommand cur = consoleHandlers.get(cmd);
                if((cur != null) && cur.alive && (cur.owner != owner)) {
                    AddonManager.log("console command ':" + cmd + "' reassigned from '" + idOf(cur.owner)
                                     + "' to '" + idOf(owner) + "'");
                    // audit2 B14 (co-03): AND THE LOSER IS ENDED, not merely out-voted. Registration is
                    // last-wins, and the previous owner's Sub used to stay `alive` in its own :list() and
                    // :get() with the dispatcher permanently unable to reach it -- a subscription that reads
                    // live and can never fire, which is co-04's shape one door over. Its Ended hook is what
                    // ends it, so the loser's own bookkeeping (consoleCommands, consoleSubs) is dropped by
                    // the same path :off() uses; the dispatcher stays standing because the map below is
                    // about to name the winner and endConsoleCommand only unsets a name nobody holds.
                    for(LuaSub old : cur.owner.consoleSubs.live()) {
                        if(old.key.equals(cmd))
                            cur.owner.consoleSubs.off(old);
                    }
                }
            }
            consoleHandlers.put(cmd, h);   // last registration wins (the current live handler the dispatcher routes to)
        }
        owner.consoleCommands.add(h);
        // audit2 B10 (co-04): ONE live subscription per name per addon. Registration is last-wins, and the
        // registry above already keeps only the last handler -- but the subscription behind the earlier one
        // stayed alive and readable, so a second :on("greet") in one addon left two Subs where one command
        // was, and sub:off() on the newer of them killed :greet while the older still listed as alive. The
        // owner's own earlier registration is ENDED here, after the new handler is installed (so the Ended
        // hook finds the name still claimed and leaves the engine dispatcher standing) and before the new
        // Sub is made (so nothing can end a registration the registry has not finished making).
        for(LuaSub old : owner.consoleSubs.live()) {
            if(old.key.equals(cmd)) {
                old.alive = false;
                owner.consoleSubs.off(old);
            }
        }
        // 086.1: the command IS a subscription — the Sub is the handle, its key is the command name, and its
        // Ended hook (Addon.consoleSubs) is what endConsoleCommand below runs. Built last, so nothing can end a
        // registration the registry has not finished making.
        LuaSub sub = owner.consoleSubs.add(cmd, fn);
        sub.tag = h;
        return sub.handle();
    }

    /**
     * Run the current handler for console command {@code :name}. Installed ONCE per name (engine-lifetime) and
     * routes to {@code consoleHandlers.get(name)} — the live handler — so it survives {@code :reload} with no
     * re-registration (C1). If no addon currently owns the name, it replies with a friendly notice. {@code invoke}
     * routes through {@link AddonManager#callLua} (watchdog-armed, error-isolated, CPU-accounted).
     */
    private static void dispatchConsole(String name, String[] words) {
        LuaConsoleCommand h = consoleHandlers.get(name);
        if((h == null) || !h.alive) {
            // audit2 B14 (co-11): TO THE CONSOLE, which is the channel that asked. This went to the addon
            // log -- a line the user reads in the chat, tagged as the client's, about a word they typed at
            // a prompt that then said nothing at all. The failure path fifteen lines up already writes to
            // cons.out + UI.error with a comment saying that is "the console's own refusal channel and it
            // is the one the user reads"; a word nobody answers is the same kind of answer.
            UI u = AddonManager.screen();
            String msg = "no addon currently handles :" + name;
            if((u != null) && (u.cons != null)) {
                u.cons.out.println(msg);
                u.error(msg);
            } else {
                AddonManager.log(msg);       // pre-HUD: there is no console to answer in
            }
            return;
        }
        h.invoke(words);
    }

    /**
     * End one console command — the {@link Subs.Ended} hook of {@link Addon#consoleSubs} (086.1), run by
     * {@code sub:off()} and by the teardown below alike. It drops the live handler, drops the command from the
     * owner, and — audit2 B01 — takes the engine's {@link Console} dispatcher back out with the LAST handler
     * for that name ({@link Console#unsetscmd}), so a word no addon answers any more is a word the console
     * does not know rather than one that replies "no addon handles :name" for the life of the client.
     *
     * <p><b>The last handler, not this one.</b> Registration is last-wins, so ending a handler another addon
     * has already taken the name over from must leave the dispatcher exactly where it is; the map decides,
     * and the map is the thing the dispatcher routes through.
     */
    static void endConsoleCommand(Addon owner, LuaConsoleCommand h) {
        if(h == null)
            return;
        h.alive = false;
        synchronized(consoleDispatched) {
            consoleHandlers.remove(h.name, h);   // only if h is STILL the current handler (a later addon may own it now)
            if(!consoleHandlers.containsKey(h.name) && consoleDispatched.remove(h.name))
                Console.unsetscmd(h.name);
        }
        owner.consoleCommands.remove(h);
    }

    /** Drop every console command this addon owns (teardown on reload/disable, P2) — and with the last handler
     *  for a name, its dispatcher ({@link #endConsoleCommand}). */
    static void teardownConsoleCommands(Addon a) {
        a.consoleSubs.clear();          // 086.1: one drop, and each sub's Ended clears its own live handler
        a.consoleCommands.clear();      //   (belt: a command with no sub behind it cannot exist)
    }

    /** Is {@code name} one of the addon engine's own console commands (which live in the same static map)? */
    private static boolean isReservedCommand(String name) {
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
     * Declare one addon hotkey — the body of {@code keybindings:on(name, fn)}. The binding starts
     * <b>unbound</b> (D-047): the addon names an action, the user assigns the key in Options ▸ Keybindings.
     */
    /**
     * The {@link KeyBinding} id one addon hotkey is registered and remembered under — {@code Utils.setpref}
     * then stores it as {@code "keybind/" + this}. Spelled once (audit2 B14, cl-03) so the door that checks
     * the length and the door that builds the key cannot mean two different strings.
     */
    static String keyBindId(Addon owner, String name) {
        return "addon/" + owner.manifest.id + "/" + name;
    }

    static LuaKeyBind newKeyBind(final Addon owner, String name, LuaValue fn) {
        // KeyBinding.get() is a process-global registry: it returns the SAME binding across reloads/sessions, so
        // a user's assignment (persisted in the client prefs) survives; KeyMatch.nil applies only on first create.
        KeyBinding kbnd = KeyBinding.get(keyBindId(owner, name), KeyMatch.nil);
        kbnd.hold();   // the claim on the user's assignment lives as long as a handler does; teardown released it
        LuaKeyBind h = new LuaKeyBind(owner, name, kbnd, fn);
        keyBinds.add(h);
        owner.keybinds.add(h);
        return h;
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

    /**
     * Remove one hotkey: stop it firing, drop it from the global dispatch list, and let go of the key it was
     * holding off every other binding. The KeyBinding and the user's assignment stay — only the claim goes,
     * and {@link #newKeyBind} takes it back when a handler exists again.
     */
    private static void removeKeyBind(Addon owner, LuaKeyBind h) {
        h.alive = false;
        keyBinds.remove(h);
        owner.keybinds.remove(h);
        h.binding.release();
    }

    /**
     * Drop every hotkey {@code owner} registered under {@code name} (the body of
     * the {@link Subs.Ended} hook of {@link Addon#keySubs}). Silent when the addon has no such hotkey — ending
     * a subscription twice, or naming a binding the addon does not own, is a no-op rather than an error.
     */
    static void removeKeyBindsNamed(Addon owner, String name) {
        for(LuaKeyBind h : owner.keybinds) {          // copy-on-write: safe to remove while iterating
            if(h.name.equals(name))
                removeKeyBind(owner, h);
        }
    }

    /**
     * Mark dead + unregister every hotkey this addon owns (teardown on reload/disable, P2). The
     * {@link KeyBinding} registry entries are process-global + persistent and are deliberately left intact —
     * that is how the client remembers a re-mapped addon key across reloads — but their <b>claim</b> on the
     * key is not: {@link KeyBinding#release} hands it back to every other binding, so a menu hotkey the
     * assignment was holding off answers again the moment this addon stops answering.
     *
     * <p><b>A reload survives it and a disable does not, without either being told apart here.</b>
     * {@code AddonRegistry.reload()} tears every addon down and then re-loads the enabled ones on the same UI
     * thread, so an addon that comes back re-declares its hotkeys and {@link #newKeyBind} takes the claim
     * straight back with no press possible in between. An addon that is disabled, deleted or killed by the
     * watchdog never re-declares, and its key is simply free.
     */
    static void teardownKeyBinds(Addon a) {
        a.keySubs.clear();            // 086.1: one drop, and each sub's Ended unregisters its own hotkey
        for(LuaKeyBind h : a.keybinds) {          // belt: a hotkey left firing into a torn-down env is the
            h.alive = false;                      //   one failure this sweep must not have
            keyBinds.remove(h);
            h.binding.release();                  // ...and the key it held goes back to whoever else wants it
        }
        a.keybinds.clear();
    }

    /**
     * The global-hotkey dispatch (spec 07 "Input" / Phase 2e-2) — the body behind {@link AddonManager#onGlobKey},
     * called from {@link AddonRoot#globtype}. Runs the handler of the first addon hotkey
     * ({@code keybindings:on}) whose current key matches and returns whether the key was <b>consumed</b>. The addon-root is walked last,
     * so a client binding on the same key wins — an addon hotkey is the fallback, never a hijack.
     *
     * <p><b>The BINDING consumes the press, not the handler's outcome</b> (audit2 B14, cl-10), and that is a
     * decision rather than an oversight. {@link AddonManager#callLua} contains a handler that throws, so
     * asking it whether the press "worked" would answer no for a bug in the addon — and the client would
     * then run its own binding for that key, so a typo in an addon would make a hotkey do two things at
     * once, intermittently. A key the user assigned to an addon action belongs to that addon while the
     * assignment stands. {@code keybindings.md} says this, and says which thread and tree the handler runs
     * on beside it.
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
