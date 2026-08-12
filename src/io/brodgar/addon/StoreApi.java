package io.brodgar.addon;

import haven.GameUI;
import haven.UI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;


import static io.brodgar.addon.AddonManager.*;

/**
 * The saved-variables subsystem ({@code hafen.store()}, 1e / D-002 / D-023). One Lua table per manifest-declared
 * saved variable, persisted to JSON under {@code savedata/} (account-scope + per-character
 * {@code <genus>_<char>} scope). Owns the
 * session save state ({@code charScope}/{@code lastAutoSave}); {@link AddonManager} drives it via
 * {@link #resetSession} (init), {@link #restorePerChar} (EnterWorld/reload), {@link #loadScope}
 * (account vars at install), {@link #flush} (teardown), and {@link #autosave} (the throttled tick save).
 * Not instantiable.
 */
final class StoreApi {
    private StoreApi() {}

    private static volatile String charScope;   // "<genus>_<char>" once in-world, else null
    private static double lastAutoSave;          // engine-clock time of the last throttled flush
    private static final double SAVE_INTERVAL = 30.0;   // throttled auto-save period (seconds; UI thread)

    /**
     * Build {@code hafen.store()} for {@code owner} + load its account-scope vars (before Load). From
     * installHafen.
     *
     * <p><b>This is the one section whose ACCESS PATTERN changed, not just its spelling.</b> A saved variable
     * was a declared <i>field</i> ({@code hafen.store.cfg.foo = 1}) and is now {@code hafen.store():get("cfg")}.
     * What {@code :get} hands back is the <b>live persisted table itself</b>, never a copy: a copy would keep
     * accepting writes and quietly stop saving them, which is the exact class of silent failure the uniform
     * grammar exists to delete. The table object is also stable for the addon's whole life — a restore refills
     * it in place — so a reference cached at load time is still the one being written to disk an hour later.
     *
     * <p>The names are the <i>addon's own</i>, so the refusal that catches the old spelling cannot live in the
     * static {@link Retired} table: {@link #index} builds it per owner from the manifest.
     */
    static void installStore(LuaTable hafen, final Addon owner) {
        LuaTable vars = new LuaTable();
        for(Manifest.SavedVar sv : owner.manifest.savedVariables) {
            if(vars.get(sv.name).istable())
                continue;                                // duplicate name in the manifest: keep the first
            vars.set(sv.name, new LuaTable());           // always a usable (possibly empty) table
        }
        owner.store = vars;

        LuaTable store = new LuaTable();
        // get(name) — the LIVE table for one declared saved variable. A name the manifest does not declare is
        // a typo with no future meaning (the set is closed at load), so it throws listing what IS declared
        // rather than answering nil and failing one index later with nothing to name.
        store.set("get", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "store", "get");
                LuaValue nm = Args.required(a, 2, "hafen.store():get", "name");
                if(nm.type() != LuaValue.TSTRING)
                    throw new LuaError("hafen.store():get(name): name must be a string (a saved variable"
                        + " declared in manifest.json)");
                LuaValue t = owner.store.get(nm.tojstring());
                if(!t.istable())
                    throw new LuaError("hafen.store():get(\"" + nm.tojstring() + "\"): this addon declares no"
                        + " saved variable of that name. Declared: " + declared(owner)
                        + " — add it to \"saved_variables\" in manifest.json");
                return t;
            }
        });
        store.set("flush", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "store", "flush");
                flush(owner);
                return self;
            }
        });
        LuaValue obj = Section.object("store", store);
        Section.mount(hafen, "store", obj,
                      "hafen.store.<name> is now hafen.store():get(\"<name>\")", new LuaTable(), index(owner));
        loadScope(owner, true);                          // account-scope vars: ready before Load
    }

    /** The declared saved-variable names, quoted, for the message a misspelt {@code :get} raises. */
    private static String declared(Addon a) {
        StringBuilder b = new StringBuilder();
        for(Manifest.SavedVar sv : a.manifest.savedVariables)
            b.append((b.length() > 0) ? ", " : "").append('"').append(sv.name).append('"');
        return (b.length() > 0) ? b.toString() : "(none)";
    }

    /**
     * The {@code __index} of {@code hafen.store}'s callable table. A <b>declared</b> name throws naming
     * {@code :get} — {@code hafen.store.cfg.foo = 1} is the spelling the whole corpus used, and left to read
     * {@code nil} it would fail as <i>"attempt to index a nil value"</i> one character later. Everything else
     * falls through to the static {@link Retired} rows ({@code hafen.store.flush}) and then to plain
     * {@code nil}, so a feature probe still works.
     */
    private static LuaValue index(final Addon owner) {
        final LuaValue rest = Retired.sectionIndex("store");
        return new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue key) {
                if(key.type() == LuaValue.TSTRING) {
                    String nm = key.tojstring();
                    for(Manifest.SavedVar sv : owner.manifest.savedVariables) {
                        if(sv.name.equals(nm))
                            throw new LuaError("hafen.store." + nm + " is now hafen.store():get(\"" + nm
                                + "\") — what it hands back is the same live table, so writing into it still"
                                + " persists");
                    }
                }
                return rest.call(self, key);
            }
        };
    }

    /** Session init: forget the per-char scope + reset the auto-save clock (from AddonManager.init). */
    static void resetSession() {
        charScope = null;
        lastAutoSave = 0;
    }

    /** Throttled auto-save of every addon`s saved vars (from the tick). flush() skips unchanged files. */
    static void autosave(double clock) {
        if(clock - lastAutoSave >= SAVE_INTERVAL) {
            lastAutoSave = clock;
            for(Addon a : addons)
                flush(a);
        }
    }

    static File saveDir() {
        String override = System.getProperty("haven.savedatadir");
        if((override != null) && !override.isEmpty())
            return new File(override);
        File addons = AddonRegistry.addonDir();
        File parent = addons.getParentFile();
        return new File((parent != null) ? parent : new File("."), "savedata");
    }

    /**
     * Capture the per-character scope folder ({@code <genus>_<char>}) now that the HUD is up, and load
     * every addon's per-character saved variables into its {@code hafen.store} <b>before</b>
     * {@code EnterWorld} fires (so handlers see restored data). Called once per world entry.
     */
    static void restorePerChar() {
        GameUI g = gui();
        if(g == null)
            return;
        charScope = scopeKey(g.genus, g.chrid);
        if(charScope == null)
            return;
        for(Addon a : addons)
            loadScope(a, false);
    }

    /** Build the {@code <genus>_<char>} folder name (path-sanitized), or {@code null} if no character. */
    private static String scopeKey(String genus, String chrid) {
        if((chrid == null) || chrid.isEmpty())
            return null;
        String g = sanitize((genus == null) ? "" : genus);
        String c = sanitize(chrid);
        return g.isEmpty() ? c : (g + "_" + c);
    }

    /** Replace filesystem-hostile characters so a genus/char string is a safe single path segment. */
    private static String sanitize(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for(int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z')) ||
                     ((c >= '0') && (c <= '9')) || (c == '.') || (c == '-') ? c : '_');
        }
        return b.toString().trim();
    }

    /** The on-disk JSON file for one addon + scope (may not exist yet). */
    private static File storeFile(Addon a, boolean account) {
        File dir = account ? new File(saveDir(), "account") : new File(saveDir(), charScope);
        return new File(dir, a.manifest.id + ".json");
    }

    /**
     * <b>The layer's own per-character files</b> — state the client keeps for a character that belongs to no
     * addon, such as the action-bar slots an addon's entry is {@link BeltHold held} in. They sit in a
     * {@code client/} folder <i>inside</i> the character's scope directory, so no addon's {@code <id>.json} can
     * collide with one whatever the addon is called: a manifest {@code id} is a folder name beside them, and a
     * folder never collides with a file one level down.
     *
     * <p>{@code null} until a character is known ({@link #restorePerChar}), because "per character" has no
     * meaning before that — the caller keeps its own state and writes it once the scope exists.
     */
    static String readClientFile(String name) {
        if(charScope == null)
            return null;
        return readFile(new File(clientDir(), name));
    }

    /** Write one of the layer's own per-character files. {@code false} when no character is known yet. */
    static boolean writeClientFile(String name, String text) {
        if(charScope == null)
            return false;
        return writeFile(new File(clientDir(), name), text);
    }

    private static File clientDir() {
        return new File(new File(saveDir(), charScope), "client");
    }

    /**
     * Load one scope's saved variables from disk into the addon's {@code hafen.store} tables (filling
     * them in place, preserving table identity). Missing/malformed files leave the tables as-is. After
     * loading, the write-skip cache is primed with the canonical serialization of what we now hold, so
     * an unchanged first flush writes nothing.
     */
    private static void loadScope(Addon a, boolean account) {
        if((a.store == null) || !hasScope(a, account))
            return;
        if(!account && (charScope == null))
            return;                                     // per-char load needs a known character
        String text = readFile(storeFile(a, account));
        if(text != null) {
            try {
                Object root = Json.parse(text);
                if(root instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>)root;
                    for(Manifest.SavedVar sv : a.manifest.savedVariables) {
                        if(sv.account != account)
                            continue;
                        LuaValue cur = a.store.get(sv.name);
                        LuaTable tgt;
                        if(cur.istable()) {
                            tgt = (LuaTable)cur;
                            clearTable(tgt);            // refill in place → the addon's ref stays valid
                        } else {
                            tgt = new LuaTable();
                            a.store.set(sv.name, tgt);
                        }
                        fillTable(tgt, m.get(sv.name), a);  // object or array; absent/scalar → left empty
                    }
                }
            } catch(RuntimeException e) {
                log(a, "store: could not read " + storeFile(a, account).getName() + ": " + e);
            }
        }
        String canon = scopeJson(a, account);          // prime the write-skip cache
        if(account) a.lastAccountJson = canon; else a.lastCharJson = canon;
    }

    /** Write an addon's changed saved variables to disk (both scopes). Skips unchanged files. */
    static void flush(Addon a) {
        if((a == null) || (a.store == null) || a.manifest.savedVariables.isEmpty())
            return;
        try {
            writeScope(a, true);                        // account (always resolvable)
            if(charScope != null)
                writeScope(a, false);                   // per-char (only once in-world)
        } catch(RuntimeException e) {
            log(a, "store: flush failed: " + e);
        }
    }

    /** Serialize one scope's vars and write the file if it differs from the last write. */
    private static void writeScope(Addon a, boolean account) {
        String out = scopeJson(a, account);
        if(out == null)
            return;                                     // this addon declares no vars of this scope
        String last = account ? a.lastAccountJson : a.lastCharJson;
        if(out.equals(last))
            return;                                     // unchanged since the last write → skip disk I/O
        if(writeFile(storeFile(a, account), out)) {
            if(account) a.lastAccountJson = out; else a.lastCharJson = out;
        }
    }

    /**
     * Serialize one scope's saved vars as a JSON object {@code {name: table, …}} (reusing the compact
     * REPL writer), or {@code null} if the addon declares no vars of this scope. A non-table value at a
     * declared name is written as {@code {}} (the contract is "a table per name").
     */
    private static String scopeJson(Addon a, boolean account) {
        LuaTable wrap = new LuaTable();
        boolean any = false;
        for(Manifest.SavedVar sv : a.manifest.savedVariables) {
            if(sv.account != account)
                continue;
            any = true;
            LuaValue v = a.store.get(sv.name);
            wrap.set(sv.name, v.istable() ? v : new LuaTable());
        }
        return any ? Json.write(wrap) : null;
    }

    /** Does the addon declare at least one saved variable of the given scope? */
    private static boolean hasScope(Addon a, boolean account) {
        for(Manifest.SavedVar sv : a.manifest.savedVariables)
            if(sv.account == account)
                return true;
        return false;
    }

    /**
     * Fill a Lua table <b>in place</b> from a parsed-JSON object (string keys) or array (1-based),
     * delegating each value to the canonical {@link LuaMarshal#jsonToLua} marshal (D-013). Filling in
     * place (rather than replacing the table) preserves the addon's cached {@code hafen.store} table
     * reference. Anything but a Map/List is a no-op (a missing/scalar value leaves the table empty).
     *
     * <p>The marshal is handed {@code owner} so a saved <b>place</b> comes back as a Position rather than as
     * the {@code {gridId, x, y}} table it is written as — the read half of what makes a Position storable.
     */
    private static void fillTable(LuaTable t, Object o, Addon owner) {
        if(o instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>)o;
            for(Map.Entry<String, Object> e : m.entrySet())
                t.set(e.getKey(), LuaMarshal.jsonToLua(e.getValue(), owner));   // null value → absent key
        } else if(o instanceof List) {
            int i = 1;
            for(Object e : (List<?>)o)
                t.set(i++, LuaMarshal.jsonToLua(e, owner));  // our writes never put null in an array (no holes)
        }
    }

    /** Remove every key from a Lua table (keys() is a snapshot, so this is safe). */
    private static void clearTable(LuaTable t) {
        for(LuaValue k : t.keys())
            t.set(k, LuaValue.NIL);
    }

    /** Read a UTF-8 file, or {@code null} if it is absent/unreadable. */
    private static String readFile(File f) {
        if((f == null) || !f.isFile())
            return null;
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch(Exception e) {
            log("store: could not read " + f + ": " + e);
            return null;
        }
    }

    /**
     * Write text to a file atomically (temp file + move), creating parent dirs. Returns whether it
     * succeeded (a failure — e.g. a read-only install — is logged, not thrown).
     */
    private static boolean writeFile(File f, String text) {
        try {
            File parent = f.getParentFile();
            if(parent != null)
                parent.mkdirs();
            Path dst = f.toPath();
            Path tmp = dst.resolveSibling(f.getName() + ".tmp");
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch(Exception atomicUnsupported) {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch(Exception e) {
            log("store: could not write " + f + ": " + e);
            return false;
        }
    }
}
