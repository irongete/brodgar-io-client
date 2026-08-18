package io.brodgar.addon;

import haven.Coord;
import haven.GameUI;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


import static io.brodgar.addon.AddonManager.*;

/**
 * The saved-variables subsystem ({@code hafen.store()}, 1e / D-002 / D-023). One Lua table per manifest-declared
 * saved variable, persisted to JSON under {@code savedata/} (account-scope + per-character
 * {@code <genus>_<char>} scope). {@link AddonManager} drives it via
 * {@link #restorePerChar} (EnterWorld/reload), {@link #loadScope}
 * (account vars at install), {@link #flush} (teardown), and {@link #autosave} (the throttled tick save).
 * Not instantiable.
 *
 * <p><b>The character folder is one session's</b> (073.5, {@code SessionState.charScope}, with the auto-save
 * clock beside it): {@code <genus>_<char>} names the character <i>that</i> login is playing, and a client with
 * two of them has two answers. What its per-character scope will <i>mean</i> once the engine outlives a switch
 * is deliberately left open here — it is indexed like the rest, and nothing about it decided.
 *
 * <p><b>An addon's scope is its own session's</b> ({@link #scopeOf}), never the screen's, and that is what
 * makes the last write land: a relogin destroys the old {@code UI} before {@code init} fires {@code Disable},
 * so a lookup by session would answer {@code null} exactly when the handler's final write has to be persisted.
 * {@link Addon#state()} holds the state object rather than the key, so an addon writes back into the very folder
 * it read from however late its teardown runs.
 */
final class StoreApi {
    private StoreApi() {}

    private static final double SAVE_INTERVAL = 30.0;   // throttled auto-save period (seconds; UI thread)

    /**
     * The character folder this addon's per-character data belongs in — {@code <genus>_<char>} of the session
     * it runs for — or {@code null} before that session is in world (or for an addon with no session at all).
     * The one door: every per-character path here is built through it, so there is one answer to <i>whose
     * character is this</i> and it is the addon's own.
     */
    private static String scopeOf(Addon a) {
        AddonManager.SessionState st = (a == null) ? null : a.state();
        return (st == null) ? null : st.charScope;
    }

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

    private static void forgetPlacements(Addon a) {
        if(a == null)
            return;
        a.placements.clear();
        a.lastPlacementJson = null;
    }

    /**
     * Throttled auto-save of every addon`s saved vars (from this session's tick). flush() skips unchanged
     * files. The throttle is the ticking session's own, and each addon writes under the scope of the session
     * <i>it</i> runs for — which is the same one while one session holds the addon layer.
     */
    static void autosave(AddonManager.SessionState st, double clock) {
        if(clock - st.storeLastAutoSave >= SAVE_INTERVAL) {
            st.storeLastAutoSave = clock;
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
     *
     * <p>073.5: it is handed <b>the session it is about and that session's own HUD</b> — the caller has both
     * (the tick that saw the world come up, the reload that found the HUD in its own tree), and reading the
     * screen instead would file one login's saved data under whichever character is being looked at.
     */
    static void restorePerChar(AddonManager.SessionState st, GameUI g) {
        if((st == null) || (g == null))
            return;
        st.charScope = scopeKey(g.genus, g.chrid);
        if(st.charScope == null)
            return;
        for(Addon a : addons) {
            loadScope(a, false);
            loadPlacements(a);                  // 062: ...and where this character last left what each addon
        }                                       //   remembers, so widget:remember(name) has it to put back
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
        File dir = account ? new File(saveDir(), "account") : new File(saveDir(), scopeOf(a));
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
     *
     * <p>073.5: it takes <b>the session whose character the file is about</b>, which every caller holds —
     * these are the layer's own files, not an addon's, so there is no manifest to resolve them through.
     */
    static String readClientFile(AddonManager.SessionState st, String name) {
        if((st == null) || (st.charScope == null))
            return null;
        return readFile(new File(clientDir(st), name));
    }

    /** Write one of the layer's own per-character files. {@code false} when no character is known yet. */
    static boolean writeClientFile(AddonManager.SessionState st, String name, String text) {
        if((st == null) || (st.charScope == null))
            return false;
        return writeFile(new File(clientDir(st), name), text);
    }

    private static File clientDir(AddonManager.SessionState st) {
        return new File(new File(saveDir(), st.charScope), "client");
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
        if(!account && (scopeOf(a) == null))
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

    /**
     * Write an addon's changed data to disk: what it <b>remembers</b> ({@code widget:remember(name)}), and its
     * saved variables in both scopes. Skips unchanged files.
     *
     * <p><b>The remembered placements go first, and they go whatever the manifest declares.</b> An addon that
     * only hands a window to the user declares no saved variables at all — the whole point of the verb being
     * that it needs no declaration and no handler — so the early return that used to stand at the top of this
     * method would have made its file the one thing here that is silently never written.
     */
    static void flush(Addon a) {
        if(a == null)
            return;
        try {
            LuaWidget.rememberCapture(a);               // 062: where every remembered widget stands right now...
            writePlacements(a);                         //   ...saved beside the store file, needing no declaration
        } catch(RuntimeException e) {
            log(a, "store: could not save remembered placements: " + e);
        }
        if((a.store == null) || a.manifest.savedVariables.isEmpty())
            return;
        try {
            writeScope(a, true);                        // account (always resolvable)
            if(scopeOf(a) != null)
                writeScope(a, false);                   // per-char (only once in-world)
        } catch(RuntimeException e) {
            log(a, "store: flush failed: " + e);
        }
    }

    // ---- the remembered placement, a slot beside the store rather than inside it (062) ----------------

    /**
     * <b>One remembered placement</b> — the place and the box one name holds for {@code widget:remember(name)},
     * in the DESIGN pixels the layout level speaks. A half no level stands on is {@code null} and is absent
     * from the file: an addon that only lets the user drag a window has nothing to say about its box, and
     * saying it anyway would pin a size the user never chose.
     */
    static final class Placement {
        Coord pos;
        Coord size;
    }

    /**
     * Is there a character to remember a placement <b>for</b>? A placement is per character, like a
     * per-character saved variable and for the same reason, so before {@code EnterWorld} there is nothing to
     * put back — and {@code widget:remember(name)} says so rather than applying an empty record.
     */
    static boolean placementScope(Addon a) {
        return scopeOf(a) != null;
    }

    /** What is saved under {@code name} for this character, or {@code null} (no character, or nothing saved). */
    static Placement placement(Addon a, String name) {
        return (scopeOf(a) == null) ? null : a.placements.get(name);
    }

    /**
     * Where a remembered widget <b>stands now</b>: write the halves given and leave the others holding what
     * they held. A half arrives {@code null} when this addon has no level of that kind on the widget, and a
     * half that is not written is not erased — an addon that stops resizing a window has not decided the user
     * never sized it.
     */
    static void land(Addon a, String name, Coord pos, Coord size) {
        if((scopeOf(a) == null) || ((pos == null) && (size == null)))
            return;
        Placement p = a.placements.get(name);
        if(p == null)
            a.placements.put(name, p = new Placement());
        if(pos != null)
            p.pos = pos;
        if(size != null)
            p.size = size;
    }

    /** {@code widget:remember(nil)}: the record is <b>deleted</b>, on disk in the same call. */
    static void forget(Addon a, String name) {
        if(a.placements.remove(name) != null)
            writePlacements(a);
    }

    /** The per-character placement file, beside the addon's own {@code <id>.json}. */
    private static File placementFile(Addon a) {
        return new File(new File(saveDir(), scopeOf(a)), a.manifest.id + ".layout.json");
    }

    /** Load this character's placements for one addon, replacing whatever the last character left. */
    private static void loadPlacements(Addon a) {
        forgetPlacements(a);
        if(scopeOf(a) == null)
            return;
        String text = readFile(placementFile(a));
        if(text != null) {
            try {
                Object root = Json.parse(text);
                if(root instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>)root;
                    for(Map.Entry<String, Object> e : m.entrySet()) {
                        Placement p = new Placement();
                        p.pos = readCoord(e.getValue(), "pos");
                        p.size = readCoord(e.getValue(), "size");
                        if((p.pos != null) || (p.size != null))
                            a.placements.put(e.getKey(), p);
                    }
                }
            } catch(RuntimeException e) {
                log(a, "store: could not read " + placementFile(a).getName() + ": " + e);
            }
        }
        a.lastPlacementJson = placementsJson(a);   // prime the write-skip cache, as loadScope does
    }

    /** One {@code {"x": …, "y": …}} half of a parsed record, or {@code null} if it is absent or malformed. */
    private static Coord readCoord(Object rec, String half) {
        if(!(rec instanceof Map))
            return null;
        Object o = ((Map<?, ?>)rec).get(half);
        if(!(o instanceof Map))
            return null;
        Object x = ((Map<?, ?>)o).get("x"), y = ((Map<?, ?>)o).get("y");
        if(!(x instanceof Double) || !(y instanceof Double))
            return null;
        return Coord.of((int)Math.round((Double)x), (int)Math.round((Double)y));
    }

    /** Write one addon's placements, if they changed and there is a character to write them for. */
    private static void writePlacements(Addon a) {
        if((scopeOf(a) == null) || (a.placements.isEmpty() && (a.lastPlacementJson == null)))
            return;                                     // this addon remembers nothing and never did
        String out = placementsJson(a);
        if(out.equals(a.lastPlacementJson))
            return;
        if(writeFile(placementFile(a), out))
            a.lastPlacementJson = out;
    }

    /**
     * {@code {"<name>": {"pos": {x, y}, "size": {x, y}}, …}}, names in order — the order is what makes the
     * write-skip comparison above answer on the content rather than on a hash walk.
     */
    private static String placementsJson(Addon a) {
        List<String> names = new ArrayList<String>(a.placements.keySet());
        Collections.sort(names);
        StringBuilder b = new StringBuilder("{");
        for(String nm : names) {
            Placement p = a.placements.get(nm);
            if(b.length() > 1)
                b.append(',');
            b.append(Json.write(LuaValue.valueOf(nm))).append(":{");
            if(p.pos != null)
                b.append("\"pos\":").append(xyJson(p.pos));
            if((p.pos != null) && (p.size != null))
                b.append(',');
            if(p.size != null)
                b.append("\"size\":").append(xyJson(p.size));
            b.append('}');
        }
        return b.append('}').toString();
    }

    private static String xyJson(Coord c) {
        return "{\"x\":" + c.x + ",\"y\":" + c.y + "}";
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
