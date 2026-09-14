package io.brodgar.addon;

import haven.Coord;
import haven.GameUI;
import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaNumber;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;


import static io.brodgar.addon.AddonManager.*;

/**
 * The documents subsystem (1e / D-002 / D-023; 146; 147). One Lua table — a <b>document</b> — per name an
 * addon asks {@code :get} for, kept as a JSON row in the addon's own store file, {@code savedata/<id>/<id>.sqlite}
 * ({@link SqliteApi}): the client scope's rows are keyed by nobody, a character's by that character's key.
 * <b>A document exists when {@code get(name)} first names it, and the door is its scope</b> (147): nothing is
 * declared, the row is read the moment it is asked for ({@link #document}), and the tables handed out are the
 * whole list a save walks. {@link AddonManager} drives it via {@link #enterWorld} (a session learns its
 * character and refills what it holds from that character's rows), {@link #sessionEnded}/{@link #drainEnded} (a session died, and its documents go
 * back to the file), {@link #rescope} (the layer's tick: the screen changed, and where every remembered
 * widget stands is written), {@link #flush} (teardown) and {@link #autosave} (the throttled tick save). Not
 * instantiable.
 *
 * <p><b>The section SPLITS by scope</b> (078.3), because the addon's own documents and a character's are not
 * the same thing. The client scope is the addon's — one set of rows for the whole client, whichever account
 * or character is up — and is {@code hafen.store()}, built by {@link #installStore}; a per-character scope is
 * one character's own rows and is {@code session:store()}, built by {@link #store}. The door IS the scope, so
 * the two halves carry the same verbs and one name asked through both is two documents.
 *
 * <p><b>The per-character tables ARE the session's</b> (079.1), held in that session's own
 * {@link AddonManager.SessionState} as one {@link CharStore} per addon, and there are as many sets as there
 * are logins. So {@code s:store()} answers about the character it names — the one on screen or any other —
 * which is what every other section on a Session does, and two characters write two sets of rows without
 * either seeing the other's keys. What a session cannot answer is a character it has not got: a login still
 * connecting or sitting on the character list has no key at all, and that is a refusal ({@link #session})
 * rather than a table that would take writes and never save them.
 *
 * <p><b>Every session knows its own character key</b> (073.5, {@code SessionState.charScope}): a client
 * with two logins has two answers, and each is that login's own. A {@link CharStore} <b>holds</b> the key
 * it was loaded for rather than looking it up, and that is what makes the last write land: a session's
 * documents are written back when it stops playing that character — including the case where it stopped by
 * <i>ending</i>, which destroys its {@code UI} before anything on the tick notices. Asking a dead session for
 * its key answers {@code null} exactly when the data has to be persisted; holding the string answers it.
 *
 * <p><b>A table is refilled, never replaced</b>, so a reference an addon cached at load time is still the one
 * being written to the file. A session that changes character writes the outgoing one's documents back and
 * reads the incoming one's into the very same tables.
 *
 * <p><b>The remembered placements are addressed the same way, and are the client's rows</b>
 * ({@code widget:remember(name)}, 062; 092.8, A-088; 150). A placement is filed under the scope of <b>the
 * tree the widget stands in</b>: a session's own window ({@code s:ui():find("@ChatUI")}) under that
 * character's key, and a window the addon built itself under the <b>client</b> scope — the layer is the
 * addon's and outlives every character, so its windows belong to nobody in particular. Until 092 there was
 * one set for the client, holding whoever was on screen, which wrote a background character's window into
 * the drawn character's rows and read it back out of them. The rows are the client's own file's
 * ({@link ClientDb#placements}), keyed by the addon and the scope: where the user put a window is a record
 * the client keeps <i>about</i> the addon, and it is <b>written when the gesture lands</b> ({@link #land},
 * {@link #forget}) and when a remembered widget is about to go ({@code LuaWidget.rememberCapture}) — never by
 * the timer or by a store's flush, which write the addon's documents and nothing of the client's.
 *
 * <p><b>The held action-bar slots are the client's rows too</b> (150, {@link BeltHold}), in the same file
 * ({@link ClientDb#holds}), keyed by the character — written by their own tick and never by a write of this
 * class.
 */
final class StoreApi {
    private StoreApi() {}

    private static final double SAVE_INTERVAL = 30.0;   // throttled auto-save period (seconds; UI thread)

    /**
     * <b>One session's per-character documents, for one addon</b> (079.1) — the live tables
     * {@code s:store():get(name)} hands back, and the character key they came from.
     *
     * <p>Minted on demand and kept in {@link AddonManager.SessionState#charStores}, so it dies with the
     * session that owns it and with the addon that asked for it, and neither can reach the other's.
     */
    static final class CharStore {
        /** Per-character name &rarr; the live table, one per name {@code :get} has been asked for. Refilled in place; never replaced. */
        final LuaTable vars = new LuaTable();
        /**
         * The {@code <genus>_<char>} key these tables were read from, or {@code null} while they hold
         * nobody. <b>Held, not looked up</b>: this is the key the write goes back to, and the session that
         * has to be written is often one that has just ended and can no longer be asked.
         */
        String scope;
        /** The JSON last written (or read in) for each document of this scope, by name, so an unchanged row is not rewritten. */
        final Map<String, String> last = new LinkedHashMap<String, String>();
        /**
         * <b>Read-only once a read fails</b> — set when the store is unavailable, or one of this character's rows
         * was there and could not be read. See {@link StoreApi#read}: what is held is then empty because
         * the client could not read it, not because the character has saved nothing, and writing that back
         * destroys the only copy.
         */
        boolean readOnly;
    }

    /**
     * <b>The character last seen on screen</b> — {@code <genus>_<char>}, or {@code null} for the login screen
     * and the beat between one session ending and the next being drawn.
     *
     * <p>092.8: it no longer says which scope the placements hold, because they hold as many as there are
     * trees with a remembered widget in them. All it does now is let {@link #rescope} notice that the screen
     * changed, which is a good moment to put where every remembered widget stands in the client's file: a
     * tab is often followed by the session being closed.
     *
     * <p>Written by {@link #rescope} and {@link #detach} alone, both on the layer's step, which is also the
     * only place it is read.
     */
    private static String placeScope;

    /**
     * <b>Sessions whose {@code UI} died with per-character tables still in them</b> (079.1). Filled by
     * {@link #sessionEnded} from the dying session's own thread, which may do nothing more than that: the
     * tables are the addon's Lua and reading them is an entry the step makes, holding the addon's own lock and
     * no tree monitor. Drained by {@link #drainEnded}
     * on the layer's tick, which writes each one back under the key its {@link CharStore} holds.
     */
    private static final Queue<AddonManager.SessionState> ended =
        new ConcurrentLinkedQueue<AddonManager.SessionState>();

    /**
     * Build {@code hafen.store()} for {@code owner}, open its file and load its client-scope documents
     * (before Load). From installHafen.
     *
     * <p><b>This is the one section whose ACCESS PATTERN changed, not just its spelling.</b> A saved variable
     * was a declared <i>field</i> ({@code hafen.store.cfg.foo = 1}) and is now {@code hafen.store():get("cfg")}.
     * What {@code :get} hands back is the <b>live persisted table itself</b>, never a copy: a copy would keep
     * accepting writes and quietly stop saving them, which is the exact class of silent failure the uniform
     * grammar exists to delete. The table object is also stable for the addon's whole life — a restore refills
     * it in place — so a reference cached at load time is still the one being written an hour later.
     *
     * <p><b>Only the CLIENT half is built here</b> (079.1). A per-character table belongs to one session and
     * is minted in that session's own {@link CharStore}, so this table holds exactly the client-scope names
     * this addon has asked for and there is nothing here for a second character to overwrite.
     *
     * <p><b>The file is opened first</b> (146, {@link SqliteApi#open}), and nothing is read from it yet (147):
     * a document is read the moment {@code :get} first names it, so a launch reads exactly what the addon
     * asks for. An open that fails is the unavailable state — every document is empty and never written, and
     * the verbs that need the file refuse naming the cause.
     */
    static void installStore(LuaTable hafen, final Addon owner) {
        owner.store = new LuaTable();                    // filled one name at a time, by :get (147)

        LuaTable store = new LuaTable();
        // get(name) — the LIVE table of one CLIENT-scope document, the addon's own. The door is the scope
        // (147): the same name through s:store():get is another character's row, and a name nobody has saved
        // under yet is an empty table, read now and held for the addon's life.
        store.set("get", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "store", "get");
                String name = nameArg(a, ACC);
                return document(owner, owner.store, CLIENT, owner.lastClientDocs, name, true, null);
            }
        });
        // flush() — the one write an addon ASKS for, and therefore the one that can answer. It writes the
        // CLIENT scope, which is the half this section IS; the other half is session:store():flush(), and
        // splitting them is what keeps each call about the rows it names. It refuses a value a saved variable
        // cannot hold, naming where it sits, BEFORE writing anything; the timer and the teardown go on
        // writing whatever they find, because a write nobody asked for must not cost an addon the rest of its
        // data (the same rule Json.writePos states for a position with no anchor) -- and since 084.5 they LOG
        // the first value they degrade, which is the half that was missing: not refusing is not a reason to
        // say nothing, and an addon that never calls flush() was otherwise never told at all.
        //   146: and it refuses first of all when there is no file to write -- the unavailable state is the
        // one case an asked-for write cannot land, and silence there is the silent failure this verb exists
        // to refuse.
        // list() — the CLIENT-scope names that EXIST: a row in the file, or a table handed out this session
        // (147), sorted. The per-character half is s:store():list(), for the reason :get is split the same
        // way: each verb is about the rows of the scope it is reached through.
        store.set("list", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "store", "list");
                return names(owner, owner.store, CLIENT);
            }
        });
        //   150: it writes the documents and nothing else. The remembered placements are the client's rows,
        // in the client's own file, written when the gesture lands -- nothing of the client's rides an
        // addon's flush.
        store.set("flush", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "store", "flush");
                SqliteApi.require(owner, ACC + ":flush()");
                carriable(owner, owner.store, ACC);
                try {
                    writeClient(owner);
                } catch(RuntimeException e) {
                    logAbout(owner, "store: flush failed: " + e);
                }
                return self;
            }
        });
        // info() — {file, bytes}: where this addon's file is and how big the database is (146). The one
        // snapshot the store hands out; everything else here is live.
        store.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "store", "info");
                return SqliteApi.info(owner);
            }
        });
        // table(name) — a bare declaration of one of this addon's own tables (146.2): :column, :key and
        // :index configure it, and :create() is the dispatch that touches the file and answers the interned
        // Table. It is the CLIENT half's verb because a table is the file's, whichever character is up — a
        // character is a column of it where the rows are one character's.
        store.set("table", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "store", "table");
                return SqliteApi.table(owner, a);
            }
        });
        // exec(sql, ...) / query(sql, ...) — one statement of this addon's own, with one value per ? after
        // it (146.3): :exec runs one that changes the file and answers how many rows it changed, :query one
        // that answers rows and answers them keyed by column. The file's verbs, so the CLIENT half's: where
        // the rows are one character's, the character is a value bound to a ?.
        store.set("exec", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "store", "exec");
                return SqliteApi.exec(owner, a);
            }
        });
        store.set("query", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "store", "query");
                return SqliteApi.query(owner, a);
            }
        });
        // transaction(fn, ...) — one bracket around what fn runs (146.4): committed when it returns, rolled
        // back when it raises with the error out of the call, answering what fn answers. vacuum() — the file
        // rebuilt in place, the store back. Both the file's verbs, so the CLIENT half's.
        store.set("transaction", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "store", "transaction");
                return SqliteApi.transaction(owner, a);
            }
        });
        store.set("vacuum", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "store", "vacuum");
                return SqliteApi.vacuum(owner, a);
            }
        });
        LuaValue obj = Section.object("store", store);
        Section.mount(hafen, "store", obj,
                      "hafen.store.<name> is now hafen.store():get(\"<name>\") for a client-scope name and"
                      + " hafen.session():current():store():get(\"<name>\") for a per-character one");
        SqliteApi.open(owner);                           // the file: before any document is asked for
    }

    /** How the client half is reached, and the spelling its messages quote. */
    private static final String ACC = "hafen.store()";

    /** How the per-character half is reached (078.3), and the spelling every one of its messages quotes. */
    private static final String SS = "session:store()";

    /**
     * Build the {@code store} section object for {@code (owner, user)} — <b>the documents of one
     * character</b>, reached as {@code session:store()} (078.3).
     *
     * <p><b>The addon's own documents and a character's are not the same thing</b>, which is why this
     * namespace SPLITS rather than moves. The client scope is the <i>addon's</i> — one set of rows for the
     * whole client, whichever account or character is up — and keeps its global spelling; a per-character
     * scope is one character's own rows, so it grows an address and is reached here. The door is the scope
     * (147): both halves carry the same verbs, and one name through both is two documents.
     *
     * <p><b>The address is the whole of the answer</b> (079.1): the tables are the named session's own, so
     * this reads and writes that character's rows whether or not anyone is looking at it, and a second
     * character reached through a second Session is a second key. What it refuses is a session with no
     * character to have documents for — one that has ended, and one that has not reached the world yet.
     *
     * <p>Minted once per {@code (addon, session)} and hung on the interned Session handle, the shape
     * {@code WorldApi.world} established — so {@code s:store() == s:store()}.
     */
    static LuaValue store(final Addon owner, final String user) {
        LuaTable m = new LuaTable();
        // :get(name) — the LIVE table of one PER-CHARACTER document, that character's own, read from under
        // its key the first time it is named (147). The session is the guard: no character, no document.
        m.set("get", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "store", "get", SS);
                String name = nameArg(a, SS);
                CharStore cs = charStore(session(user, "get(\"" + name + "\")"), owner);
                return document(owner, cs.vars, cs.scope, cs.last, name, false, cs);
            }
        });
        // :flush() — write THIS character's documents now, and nothing else (150: a remembered placement is
        // the client's row, written when the gesture lands). The client half is hafen.store():flush().
        // Refuses an unavailable store and an uncarriable value first, exactly as that one does.
        // list() — the PER-CHARACTER names that exist for THIS character (094, A-111; 147): a row under its
        // key, or a table handed out this session, sorted. The client half is hafen.store():list().
        m.set("list", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "store", "list", SS);
                CharStore cs = charStore(session(user, "list()"), owner);
                return names(owner, cs.vars, cs.scope);
            }
        });
        m.set("flush", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "store", "flush", SS);
                SqliteApi.require(owner, SS + ":flush()");
                AddonManager.SessionState st = session(user, "flush()");
                CharStore cs = charStore(st, owner);
                carriable(owner, cs.vars, SS);
                try {
                    writeChar(owner, cs);
                } catch(RuntimeException e) {
                    logAbout(owner, "store: flush failed: " + e);
                }
                return self;
            }
        });
        return Section.object("store", m, SS);
    }

    /**
     * The document name a {@code :get} was handed — a non-empty string, and nothing else is asked of it (147):
     * there is no declaration to hold it against, so a name is what the addon calls its document, and it is
     * a row key in the addon's own file. Shared by both halves, so both refuse the same way.
     */
    private static String nameArg(Varargs a, String how) {
        String name = Args.str(a, 2, how + ":get", "name",
                               "what you call the document — a row key in your file, yours to choose").tojstring();
        if(name.isEmpty())
            throw new LuaError(how + ":get(name): name must not be empty — a document's name is what you call"
                + " it, and it exists when :get first names it");
        return name;
    }

    /**
     * <b>The live table of one document, read the first time it is named</b> (147) — the whole of what
     * {@code :get} does, through either door. The table under {@code name} if {@code vars} already holds one;
     * else a new table filled from the row {@link #read} finds (empty when there is none), set into
     * {@code vars}, its JSON primed into {@code last} so an unchanged document is never rewritten, and
     * answered. A scope that has no key yet ({@code null}: a session between two characters) hands out the
     * empty table unread — {@link #loadChar} fills it the moment the key is known, in place.
     *
     * <p>The read runs on whichever thread asked — an inbound handler's, the REPL's — and is safe there:
     * {@link SqliteApi.Db#document} is synchronized on the connection like every primitive.
     */
    private static LuaTable document(Addon a, LuaTable vars, String scope, Map<String, String> last, String name,
                                     boolean client, CharStore cs) {
        LuaValue have = vars.get(name);
        if(have.istable())
            return (LuaTable)have;
        LuaTable tgt = new LuaTable();
        vars.set(name, tgt);
        if(scope != null) {
            read(a, tgt, scope, name, client, cs);
            last.put(name, Json.write(tgt));        // what is held now, so the next write skips it unchanged
        }
        return tgt;
    }

    /**
     * <b>The session a per-character verb was addressed to</b> (079.1) — the one guard this half has, and the
     * whole of it. Every live session in the world answers here, drawn or not, because the tables are that
     * session's own. Two states cannot: a session that is not live has had its documents written and dropped,
     * and one that has not reached the world has no key to have any under. Both would otherwise be an empty
     * table that takes writes and never saves them, so both are said out loud, and the message says which.
     */
    private static AddonManager.SessionState session(String user, String call) {
        AddonManager.SessionState st = AddonManager.state(AddonManager.sessionui(user));
        if(st == null)
            throw new LuaError(SS + ":" + call + " — \"" + user + "\" is not a live session, so it has no"
                + " saved variables in memory: its own were written to the file when it ended. hafen.session()"
                + " lists the sessions the client holds, and s:exists() is the test.");
        if(st.charScope == null)
            throw new LuaError(SS + ":" + call + " — that session has no character yet (it is connecting, or"
                + " on the character list), so it has no per-character documents at all: its variables arrive"
                + " with SessionEnteredWorld.");
        return st;
    }

    /**
     * <b>This session's per-character tables for one addon</b>, minted on the first ask — holding no table yet
     * (147): each document is read when {@code :get} names it, from under the key this store holds.
     */
    private static CharStore charStore(AddonManager.SessionState st, Addon a) {
        CharStore have = st.charStores.get(a);
        if(have != null)
            return have;
        // audit2 B06: ONE STORE PER (SESSION, ADDON), whichever thread asks first. The lock is per
        // session-state and taken only on the first ask for one addon's tables; the key is set before the
        // store is published, so a :get that follows reads from under it.
        synchronized(st.charStores) {
            have = st.charStores.get(a);
            if(have != null)
                return have;
            CharStore mk = new CharStore();
            mk.scope = st.charScope;          // null until the session has a character: enterWorld sets it
            st.charStores.put(a, mk);
            return mk;
        }
    }

    /**
     * <b>The names that exist in one scope</b> (094, A-111; 147), sorted, as a plain string array — what
     * {@code hafen.store():list()} and {@code s:store():list()} answer, each about its own half. A name
     * exists when it has a row in the file ({@link SqliteApi.Db#names}) or a table handed out this session
     * ({@code vars}' keys — a document written and not yet flushed is one of them); the two are unioned. A
     * scope with no key yet, or a file that is unavailable, contributes its live keys alone. A list of NAMES,
     * so it stays an array rather than becoming a collection — the rule {@code pag:categories()} and
     * {@code item:slots()} already follow.
     */
    private static LuaValue names(Addon a, LuaTable vars, String scope) {
        java.util.TreeSet<String> all = new java.util.TreeSet<String>();
        for(String nm : liveNames(vars))
            all.add(nm);
        SqliteApi.Db db = SqliteApi.db(a);
        if((db != null) && (scope != null)) {
            try {
                all.addAll(db.names(scope));
            } catch(RuntimeException e) {
                logAbout(a, "store: could not read the document names from " + db.file.getFileName() + ": "
                    + Refusal.reason(e) + " — :list() answers the ones held this session");
            }
        }
        LuaTable t = new LuaTable();
        int n = 0;
        for(String nm : all)
            t.set(++n, LuaValue.valueOf(nm));
        return t;
    }

    /** The names {@code vars} holds a table under — the documents {@code :get} has handed out in one scope. */
    private static List<String> liveNames(LuaTable vars) {
        List<String> out = new ArrayList<String>();
        for(LuaValue k : vars.keys()) {
            if((k.type() == LuaValue.TSTRING) && vars.get(k).istable())
                out.add(k.tojstring());
        }
        return out;
    }

    /**
     * Throttled auto-save from this session's tick: every addon's client-scope documents, the places the
     * screen's character has its windows in, and <b>this session's own</b> per-character documents. The
     * write skips unchanged rows. The throttle is the ticking session's own, so each login pays for its own
     * character.
     */
    static void autosave(AddonManager.SessionState st, double clock) {
        if(clock - st.storeLastAutoSave >= SAVE_INTERVAL) {
            st.storeLastAutoSave = clock;
            for(Addon a : addons)
                save(a, st);
        }
    }

    /** The {@code savedata/} folder beside the client, where every addon's file lives — {@link ClientDb#dir}, beside the client's own. */
    static File saveDir() {
        return ClientDb.dir();
    }

    /**
     * <b>A session learns which character it is playing</b>, and reads that character's documents in —
     * the rows under its {@code <genus>_<char>} key, now that the HUD is up. Called once per world entry, and
     * once more by a {@code :reload} for every session that is in the world.
     *
     * <p>073.5: it is handed <b>the session it is about and that session's own HUD</b> — the caller has both
     * (the tick that saw the world come up, the reload that found the HUD in its own tree), and reading the
     * screen instead would file one login's rows under whichever character is being looked at.
     *
     * <p>079.1: and it <b>refills</b>, for that session and no other, which is what makes the documents of a
     * character nobody is looking at be that character's. Whatever the tables held first goes back where it
     * came from: a session picking a second character keeps its {@code UI} and comes through here again, so
     * the outgoing character's data is written before the incoming character's is read into the very same
     * tables (147: the ones this session has asked for — a name not yet asked is read when it is).
     * {@link #rescope} is called on the way out — a session entering the world <i>as</i> the screen has its
     * documents and the remembered places written before {@code SessionEnteredWorld} fires, which is where
     * the docs send an addon to read them.
     */
    static void enterWorld(AddonManager.SessionState st, GameUI g) {
        if((st == null) || (g == null))
            return;
        String want = scopeKey(g.genus, g.chrid);
        unloadChar(st);                     // whatever these tables held goes back under the key it came from
        st.charScope = want;
        if(want != null) {
            for(Addon a : addons) {
                CharStore cs = charStore(st, a);
                if(cs.scope == null) {      // minted before this session had a character: fill it now
                    cs.scope = want;
                    loadChar(a, cs);
                }
            }
        }
        rescope();
    }

    /**
     * <b>The screen changed</b> — where every remembered widget stands goes into the client's file (092.8;
     * 150). Run from the layer's own tick and from {@link #enterWorld}. When the screen still holds what it
     * held last tick this is one map read and one string compare.
     *
     * <p><b>It is not a swap</b>, and that is the point: one set of placements following the screen would make
     * a tab write the outgoing character's set back and read the incoming character's in — over widgets that
     * might be standing in neither of their trees. Every set is filed under its own tree's scope and nothing
     * has to move when the player tabs; what is left is that a tab is a <i>good moment to write</i>, since it
     * is often followed by that session being closed, and a place the addon wrote itself ({@code
     * widget:position(x, y)} on a window it built) lands in no gesture.
     *
     * <p><b>Derived, not notified</b>, and that is still deliberate: the screen changes for four different
     * reasons — a tab, a session reaching the world, a session ending, a relogin replacing a {@code UI} under
     * the same slot — and three of them happen on threads that are not this one. A seam per reason is three
     * chances to miss one and no way to tell; a comparison on the tick cannot be missed.
     */
    static void rescope() {
        AddonManager.SessionState st = AddonManager.state(AddonManager.screen());
        String want = (st == null) ? null : st.charScope;
        if((want == null) ? (placeScope == null) : want.equals(placeScope))
            return;
        placeScope = want;
        for(Addon a : addons) {
            try {
                LuaWidget.rememberCapture(a);   // 062: where every remembered widget stands right now, each
                                                //   written under the scope of the tree it stands in (150)
            } catch(RuntimeException e) {
                logAbout(a, "store: could not save remembered placements: " + e);
            }
        }
    }

    /**
     * <b>A session's {@code UI} died with its per-character tables still in it</b> (079.1) — from
     * {@link AddonManager#uiDestroyed}, on the dying session's own thread, which is why this only files it.
     * The tables are the addon's Lua, so the write is
     * {@link #drainEnded}'s, on the layer's next tick — and it still lands, because each {@link CharStore}
     * holds the key it was loaded for rather than asking a session that no longer exists.
     */
    static void sessionEnded(AddonManager.SessionState st) {
        if((st != null) && !st.charStores.isEmpty())
            ended.add(st);
    }

    /** Write back and empty the tables of every session that has ended since the last layer tick. */
    static void drainEnded() {
        for(AddonManager.SessionState st = ended.poll(); st != null; st = ended.poll())
            unloadChar(st);
    }

    /**
     * Write one session's per-character tables back where they came from and empty them. The client scope is
     * untouched: it is the same for every character, so nothing about it changes when a session does, and it
     * goes on being flushed with the addon.
     *
     * <p>The tables are <b>emptied rather than replaced</b>, for the reason {@link #loadChar} refills in
     * place: a reference an addon cached at load time has to go on being the live one.
     *
     * <p><b>Emptying is unconditional and writing is not.</b> A set of tables holding no key has nowhere
     * to be written — but there can still be something to empty, because an addon that wrote into a session's
     * tables before it reached the world wrote them for nobody. Carried instead of dropped, those keys would
     * arrive in that character's tables and be saved into their rows as if they had always been theirs.
     */
    private static void unloadChar(AddonManager.SessionState st) {
        for(Map.Entry<Addon, CharStore> e : st.charStores.entrySet()) {
            Addon a = e.getKey();
            CharStore cs = e.getValue();
            if(cs.scope != null) {
                try {
                    writeChar(a, cs);
                } catch(RuntimeException ex) {
                    logAbout(a, "store: could not save this character's variables: " + ex);
                }
            }
            cs.scope = null;
            cs.last.clear();
            cs.readOnly = false;
            for(String nm : liveNames(cs.vars))
                clearTable((LuaTable)cs.vars.get(nm));
        }
    }

    /**
     * <b>The client holds nobody's addons</b> — a {@code :reload}, between the teardown that flushed every
     * addon and the load that builds new ones. What the sessions hold is dropped rather than written: those
     * tables are already written and belong to addons that no longer exist, and writing the new addons' empty
     * ones over a character's rows is the one way a reload could cost the user their settings. A session
     * that ended and has not been drained yet is drained first, for the same reason — its data is still owed
     * under its own key.
     */
    static void detach() {
        drainEnded();
        for(AddonManager.SessionState st : AddonManager.allStates())
            st.charStores.clear();
        placeScope = null;
    }

    /**
     * <b>What a document may hold</b>, checked over one scope's live tables before an asked-for
     * {@code flush()} writes. A function, a widget handle or any other live thing is written by the forgiving
     * serializer as a quoted {@code tostring} and read back as that string — data-shaped garbage, discovered
     * a week later by the addon that trusted it. Here it is discovered at the call.
     *
     * <p>It names the <b>path</b> and not just the variable, because a bad value is nearly always nested: the
     * addon put a widget in the table it saves its layout from, and the name of the variable alone would send
     * it looking through the whole thing.
     */
    private static void carriable(Addon a, LuaTable src, String how) {
        String bad = uncarriable(src);
        if(bad != null)
            throw new LuaError(how + ":flush(): " + bad + ", and a saved variable may hold only tables,"
                + " strings, numbers, booleans and Positions — save what describes the thing (a resource"
                + " name, a colour's three numbers) and rebuild it on load");
    }

    /**
     * <b>The first value in this scope a saved variable cannot hold</b>, as the clause both its readers
     * quote — {@code "layout".win holds a function} — or {@code null} when every one of them is carriable.
     * One walk and one sentence, because the two doors say the same fact with different consequences: an
     * asked-for {@link #carriable} flush refuses it, and the timer and the teardown {@link #degraded} log it
     * and go on writing.
     */
    private static String uncarriable(LuaTable src) {
        if(src == null)
            return null;
        for(String nm : liveNames(src)) {   // 078.3: a flush answers for the scope it was asked on, and
            String bad = uncarriable((LuaTable)src.get(nm), "\"" + nm + "\"", Collections.newSetFromMap(
                                         new IdentityHashMap<LuaValue, Boolean>()));   //   src IS that scope
            if(bad != null)
                return bad;
        }
        return null;
    }

    /**
     * The walk itself, over one table: the first value under {@code path} the store cannot hold, as the clause
     * the callers quote, or {@code null}. Package-visible because a {@code json} column of a declared table
     * ({@link SqliteApi}) holds exactly what a document holds, and is refused in the same words.
     */
    static String uncarriable(LuaTable t, String path, Set<LuaValue> seen) {
        // audit2 B14 (st-05): A CYCLE IS THE FIRST THING A STORE CANNOT HOLD, and it used to be the one
        // thing this walk answered `null` for. The writer breaks a cycle into the literal string "<cycle>",
        // so the table came back from disk as text where a table had been — and because this returned
        // null, flush() accepted it and degraded() said nothing, which degraded()'s own javadoc names as
        // the whole defect it exists for. `seen` is now the ANCESTOR set, removed on the way back out (the
        // shape Json.writeTab has): a table reached twice down two branches is ordinary sharing and is
        // walked twice, and only a table reached from inside itself is the cycle.
        if(!seen.add(t))
            return path + " is inside itself (a reference cycle)";
        try {
            for(LuaValue k : t.keys()) {
                LuaValue v = t.get(k);
                // audit2 B14 (st-06): THE KEY IS ASKED TOO. This walk type-checked the value alone and built
                // the path with k.tojstring(), so a function, a table or a boolean KEY passed both the flush
                // refusal and the degraded log — and reached the writer, which spelled it as a heap address
                // and put that in the file. A JSON object's names are strings; a number key spells one too.
                int kt = k.type();
                if((kt != LuaValue.TSTRING) && (kt != LuaValue.TNUMBER))
                    return path + " is keyed by a " + k.typename()
                        + " (a saved table's keys are strings or numbers)";
                String at = path + "." + k.tojstring();
                if(v.istable()) {
                    String bad = uncarriable((LuaTable)v, at, seen);
                    if(bad != null)
                        return bad;
                } else if(!v.isnil() && !v.isboolean() && !(v instanceof LuaNumber) && !(v instanceof LuaString)
                          && (LuaPosition.resolve(v) == null)) {
                    return at + " holds a " + v.typename();
                } else if((v instanceof LuaNumber) && !Double.isFinite(v.todouble())) {
                    // A NaN or an infinity IS a LuaNumber, so the kind test above waves it through — and JSON
                    // has no spelling for one, so the writer puts a bare null there and the read back drops the
                    // KEY, which is a saved variable deleted rather than degraded. Named here, where every other
                    // thing a store cannot hold is named.
                    return at + " holds " + (Double.isNaN(v.todouble()) ? "nan" : "an infinity");
                }
            }
        } finally {
            seen.remove(t);
        }
        return null;
    }

    /**
     * <b>The write nobody asked for says what it degraded</b> (084.5), and goes on writing. {@link #flush()}
     * refuses a value a saved variable cannot hold; the timer and the teardown must not, because a write the
     * addon did not ask for cannot be allowed to cost it the rest of its data — but the silence that bought
     * was the whole defect: the forgiving serializer writes a function as a quoted {@code tostring}, which
     * reads back a week later as data-shaped garbage in an addon that never called {@code flush()} and was
     * therefore never told.
     *
     * <p><b>The FIRST value only, and only where a row actually changes.</b> One line names the path, which
     * is what an author acts on; a line per key would bury it, and the callers reach this after the
     * unchanged-content check, so a store left alone says nothing on every one of the timer's laps.
     */
    private static void degraded(Addon a, LuaTable src, String how) {
        String bad = uncarriable(src);
        if(bad != null)
            logAbout(a, "store: " + bad + ", which is saved as text and reads back as text — a saved variable may"
                + " hold only tables, strings, numbers, booleans and Positions. This write was the timer's or"
                + " the teardown's, so it was made anyway; " + how + ":flush() refuses it instead");
    }

    /**
     * Build the {@code <genus>_<char>} key, or {@code null} if there is no character. The key is a <b>row
     * key</b> in every addon's file and nothing else (149); its spelling is {@link #sanitize}'s, kept exactly
     * as it is because every row already written is under the key it spells.
     */
    private static String scopeKey(String genus, String chrid) {
        if((chrid == null) || chrid.isEmpty())
            return null;
        String g = sanitize((genus == null) ? "" : genus);
        String c = sanitize(chrid);
        return g.isEmpty() ? c : (g + "_" + c);
    }

    /** Reduce a genus/char string to letters, digits, {@code .} and {@code -}: the spelling every row key has. */
    private static String sanitize(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for(int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z')) ||
                     ((c >= '0') && (c <= '9')) || (c == '.') || (c == '-') ? c : '_');
        }
        return b.toString().trim();
    }

    /**
     * <b>A session's tables learn a character</b> (147): every table {@code cs.vars} already holds — the
     * documents this session has asked for — is emptied and refilled <b>in place</b> from under the key
     * {@code cs} now holds, so a reference an addon cached stays the same object and is now that character's.
     * The write-skip cache is reset to what is held, and the read-only mark to clean: it is this load's own
     * reads that decide it again. A name not yet asked for is read when it is.
     */
    private static void loadChar(Addon a, CharStore cs) {
        if(cs.scope == null)
            return;
        cs.readOnly = false;
        cs.last.clear();
        for(String nm : liveNames(cs.vars)) {
            LuaTable tgt = (LuaTable)cs.vars.get(nm);
            clearTable(tgt);                    // refill in place → the addon's ref stays valid
            read(a, tgt, cs.scope, nm, false, cs);
            cs.last.put(nm, Json.write(tgt));
        }
    }

    /**
     * Read one document's row into {@code tgt}, which arrives empty. A row that is not there is a clean read —
     * nothing has been saved under that name yet and an empty table is the whole truth.
     *
     * <p><b>A row that is there and cannot be read is not</b>, and neither is a store that is unavailable: the
     * table is left empty, an empty document is what the addon then goes on to write, and the write replaces
     * the row, so the recovery both pages call harmless would replace the only copy of the data with nothing.
     * Either marks the whole <b>scope</b> read-only for the rest of the session ({@code a.clientReadOnly} or
     * {@code cs.readOnly} — see {@link #writeClient} and {@link #writeChar}), and it is a load that succeeds
     * that lifts it: the next launch or a {@code :reload} for the client scope, {@link #loadChar} for a
     * character's.
     */
    private static void read(Addon a, LuaTable tgt, String scope, String name, boolean client, CharStore cs) {
        SqliteApi.Db db = SqliteApi.db(a);
        if(db == null) {
            readOnly(a, client, cs);            // unavailable: empty, and never written (SqliteApi.open said why)
            return;
        }
        String text;
        try {
            text = db.document(scope, name);
        } catch(RuntimeException e) {
            logAbout(a, "store: could not read \"" + name + "\" from " + db.file.getFileName() + ": "
                + Refusal.reason(e) + " — this addon's " + (client ? ACC : SS) + " is READ-ONLY for this"
                + " session, and the rows are left as they are");
            readOnly(a, client, cs);
            return;
        }
        if(text == null)
            return;                             // nothing saved under this name yet: empty is the whole truth
        try {
            Object root = Json.parse(text);
            // audit2 B14 (st-08): A WELL-FORMED ROW THAT IS NOT A DOCUMENT IS A FAILED LOAD. An object or
            // an array is a table; a scalar or a null matches nothing here, would load nothing and say
            // nothing, and the addon would then write an empty document over its own data on the next
            // flush. The same read-only answer an unreadable row gets, for the same reason.
            if(!(root instanceof Map) && !(root instanceof List)) {
                logAbout(a, "store: \"" + name + "\" in " + db.file.getFileName() + " is not a document"
                    + " (it holds " + ((root == null) ? "null" : "a single value") + ") — this addon's "
                    + (client ? ACC : SS) + " is READ-ONLY for this session, and the row is left as it is");
                readOnly(a, client, cs);
                return;
            }
            fillTable(tgt, root, a);            // object or array
        } catch(RuntimeException e) {
            logAbout(a, "store: could not read \"" + name + "\" from " + db.file.getFileName() + ": "
                + Refusal.reason(e) + " — this addon's " + (client ? ACC : SS) + " is READ-ONLY for this"
                + " session, and the row is left as it is");
            readOnly(a, client, cs);
        }
    }

    /** Mark the scope a failed {@link #read} belongs to read-only: the client's on the addon, a character's on its store. */
    private static void readOnly(Addon a, boolean client, CharStore cs) {
        if(client)
            a.clientReadOnly = true;
        else if(cs != null)
            cs.readOnly = true;
    }

    /**
     * Write an addon's changed documents to its file: its client-scope documents, and <b>every session's</b>
     * per-character documents — the live ones, and those of a session that ended and has not been drained
     * yet, because the step that closes the file comes right after this one and the drain would find it
     * closed. Skips unchanged rows. This is the teardown's write — the addon is going, so every character it
     * holds tables for is written. Nothing of the client's (150): where the addon's remembered widgets stand
     * is the client's row, written by the teardown's own placements step and by the gesture, never here.
     */
    static void flush(Addon a) {
        if((a == null) || (a.store == null))
            return;
        try {
            writeClient(a);                             // the client scope (always resolvable)
            for(AddonManager.SessionState st : AddonManager.allStates())
                writeChar(a, st.charStores.get(a));     // ...and each character this addon has tables for
            for(AddonManager.SessionState st : ended)
                writeChar(a, st.charStores.get(a));     // ...ended and not drained: written now, drained later
        } catch(RuntimeException e) {
            logAbout(a, "store: flush failed: " + e);
        }
    }

    /**
     * The auto-save's write: the client scope as {@link #flush} writes it, and <b>one</b> session's
     * per-character documents — the one whose tick this is. Every session ticks, so every character's rows
     * are written by its own login rather than by whichever one got there first. Documents alone (150): a
     * remembered placement is written when the gesture lands, and rides no timer.
     */
    private static void save(Addon a, AddonManager.SessionState st) {
        if((a == null) || (a.store == null))
            return;
        try {
            writeClient(a);
            writeChar(a, st.charStores.get(a));
        } catch(RuntimeException e) {
            logAbout(a, "store: flush failed: " + e);
        }
    }

    // ---- the remembered placement: the client's row about this addon, in the client's file (062; 150) ----

    /**
     * <b>One remembered placement</b> — the place and the box one name holds for {@code widget:remember(name)},
     * in the DESIGN pixels the layout level speaks. A half no level stands on is {@code null} and is
     * {@code NULL} in the row: an addon that only lets the user drag a window has nothing to say about its
     * box, and saying it anyway would pin a size the user never chose.
     */
    static final class Placement {
        Coord pos;
        Coord size;
    }

    /**
     * <b>One scope's remembered placements for one addon</b> (092.8) — the names, and the last JSON built
     * for them so an unchanged set is not rewritten. One of these per scope the addon has a remembered
     * widget in: a character's, or the client's.
     */
    static final class PlaceSet {
        final Map<String, Placement> byName = new ConcurrentHashMap<String, Placement>();
        String lastJson;
        /**
         * <b>The client's file's flag</b> (150): the file was unavailable when this set was read, so what it
         * holds is not the file, and nothing is written back — the same rule the document scopes keep
         * ({@link StoreApi#loadInto}). The client's file never comes back within a session, so the flag never
         * clears; the next launch reads again.
         */
        boolean readOnly;
    }

    /**
     * <b>The scope of the addon's own layer</b> — the row key the client scope's documents are written
     * under, and the scope a remembered window of the layer is filed under in the client's file. The empty
     * string, because these rows are nobody's: every character's key is a name, and this is the absence of
     * one.
     */
    static final String CLIENT = "";

    /**
     * <b>The scope a widget's placement is filed under</b> (092.8, A-088) — the whole of the fix, and the one
     * question the old code never asked.
     *
     * <ul>
     *   <li><b>A widget standing in a session's own tree</b> — {@code s:ui():find("@ChatUI")}, the case
     *       {@code native.md} documents — is filed under <b>that character's</b> key. Where the user
     *       dragged that character's chat window is a fact about that character.</li>
     *   <li><b>A widget the addon built itself</b> stands in the layer, which belongs to no session and
     *       outlives every character, so it is filed under the <b>client</b> scope: the layer is drawn over
     *       whichever session is up, and its windows are the addon's rather than anybody's.</li>
     * </ul>
     *
     * <p>{@code null} when there is nothing to file under yet — a tree that is not a session and not the
     * layer, or a session that has not reached the world, which is the case {@code widget:remember} logs.
     */
    static String scopeOf(Widget w) {
        if(w == null)
            return null;
        UI u = w.ui;
        if(u == null)
            return null;
        if(u == AddonManager.layer())
            return CLIENT;
        AddonManager.SessionState st = AddonManager.state(u);
        return (st == null) ? null : st.charScope;
    }

    /** Is there anywhere to remember {@code w}'s placement? False before its character is in world. */
    static boolean placementScope(Widget w) {
        return scopeOf(w) != null;
    }

    /**
     * <b>One scope's placements for one addon</b>, minted on the first ask and <b>read off the file then</b>.
     * The lazy load is what lets a set exist per tree with nothing having to notice a tree appearing: the
     * first {@code widget:remember(name)} in that tree is the notice.
     */
    private static PlaceSet set(Addon a, String scope) {
        PlaceSet have = a.placeSets.get(scope);
        if(have != null)
            return have;
        PlaceSet mk = new PlaceSet();
        PlaceSet prev = a.placeSets.putIfAbsent(scope, mk);
        if(prev != null)
            return prev;
        loadPlacements(a, scope, mk);
        return mk;
    }

    /** What is saved under {@code name} for {@code w}'s own tree, or {@code null} (no scope, or nothing saved). */
    static Placement placement(Addon a, Widget w, String name) {
        String scope = scopeOf(w);
        return (scope == null) ? null : set(a, scope).byName.get(name);
    }

    /**
     * Where a remembered widget <b>stands now</b>: write the halves given and leave the others holding what
     * they held, <b>and put the scope's rows in the client's file in the same call</b> (150) — a gesture is
     * rare, the file is always open, and the row is what a {@code :reload} reads back. A half arrives
     * {@code null} when this addon has no level of that kind on the widget, and a half that is not written is
     * not erased — an addon that stops resizing a window has not decided the user never sized it.
     */
    static void land(Addon a, Widget w, String name, Coord pos, Coord size) {
        String scope = scopeOf(w);
        if((scope == null) || ((pos == null) && (size == null)))
            return;
        PlaceSet ps = set(a, scope);
        Map<String, Placement> m = ps.byName;
        Placement p = m.get(name);
        if(p == null)
            m.put(name, p = new Placement());
        if(pos != null)
            p.pos = pos;
        if(size != null)
            p.size = size;
        writePlacements(a, scope, ps);
    }

    /** {@code widget:remember(nil)}: the record is <b>deleted</b>, in the file in the same call. */
    static void forget(Addon a, Widget w, String name) {
        String scope = scopeOf(w);
        if(scope == null)
            return;
        PlaceSet ps = set(a, scope);
        if(ps.byName.remove(name) != null)
            writePlacements(a, scope, ps);
    }

    /**
     * Read one scope's placements of this addon off the client's file into a freshly minted set (150,
     * {@link ClientDb#placements}). A file that is unavailable makes the set read-only for the session,
     * exactly as a document scope's failed read does ({@link #loadInto}): what is held is empty because the
     * client could not read it, and writing that back is a whole replacement of the only copy. The warning
     * is {@link ClientDb}'s own, issued once.
     */
    private static void loadPlacements(Addon a, String scope, PlaceSet ps) {
        Map<String, Placement> rows = ClientDb.placements(a.manifest.id, scope);
        if(rows == null)
            ps.readOnly = true;
        else
            ps.byName.putAll(rows);
        ps.lastJson = placementsJson(ps);   // prime the write-skip cache, as a scope load does
    }

    /**
     * <b>One scope's placements of this addon into the client's file</b> (150) — its rows replaced in one
     * transaction, skipped when they would not change. From {@link #land} and {@link #forget}, which is to
     * say from the gesture, from {@code widget:remember(nil)} and from every {@code LuaWidget.rememberCapture}.
     */
    private static void writePlacements(Addon a, String scope, PlaceSet ps) {
        if(ps.byName.isEmpty() && (ps.lastJson == null))
            return;                                     // this addon remembers nothing here and never did
        String out = placementsJson(ps);
        if(out.equals(ps.lastJson))
            return;
        if(ps.readOnly)
            return;                                     // the file was unavailable: what is held is not the file
        ClientDb.placements(a.manifest.id, scope, ps.byName);
        ps.lastJson = out;
    }

    /**
     * {@code {"<name>": {"pos": {x, y}, "size": {x, y}}, …}}, names in order — the order is what makes the
     * write-skip comparison above answer on the content rather than on a hash walk.
     */
    private static String placementsJson(PlaceSet ps) {
        List<String> names = new ArrayList<String>(ps.byName.keySet());
        Collections.sort(names);
        StringBuilder b = new StringBuilder("{");
        for(String nm : names) {
            Placement p = ps.byName.get(nm);
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

    /** Serialize the client scope and write the rows that differ from the last write. */
    private static void writeClient(Addon a) {
        if(a.store == null)
            return;
        Map<String, String> changed = changed(scopeRows(a.store), a.lastClientDocs);
        if(changed.isEmpty())
            return;                                     // no client documents, or unchanged → no row touched
        if(a.clientReadOnly)
            return;                                     // the load failed: what is held is not the file
        SqliteApi.Db db = SqliteApi.db(a);
        if(db == null) {
            logAbout(a, "store: the file is closed, so the client scope's changes were not written");
            return;
        }
        degraded(a, a.store, ACC);                      // 084.5: say what is about to be written as text
        db.documents(CLIENT, changed);
        a.lastClientDocs.putAll(changed);
    }

    /** Serialize one session's per-character scope and write the rows that differ, under the key that set holds. */
    private static void writeChar(Addon a, CharStore cs) {
        if((cs == null) || (cs.scope == null))
            return;                                     // no tables for this session, or they hold nobody
        Map<String, String> changed = changed(scopeRows(cs.vars), cs.last);
        if(changed.isEmpty())
            return;
        if(cs.readOnly)
            return;                                     // the load failed: what is held is not the file
        SqliteApi.Db db = SqliteApi.db(a);
        if(db == null) {
            logAbout(a, "store: the file is closed, so this character's changes were not written");
            return;
        }
        degraded(a, cs.vars, SS);                       // 084.5: say what is about to be written as text
        db.documents(cs.scope, changed);
        cs.last.putAll(changed);
    }

    /**
     * Serialize one scope's live documents as {@code name → JSON} rows (reusing the compact REPL writer) —
     * the tables {@code :get} has handed out in that scope (147). Empty when nothing has been asked for: a
     * document nobody named this session has no table here and no row touched.
     */
    private static Map<String, String> scopeRows(LuaTable src) {
        Map<String, String> rows = new LinkedHashMap<String, String>();
        for(String nm : liveNames(src))
            rows.put(nm, Json.write(src.get(nm)));
        return rows;
    }

    /** The rows of {@code now} whose JSON differs from what {@code last} holds for that name — what gets written. */
    private static Map<String, String> changed(Map<String, String> now, Map<String, String> last) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for(Map.Entry<String, String> e : now.entrySet()) {
            if(!e.getValue().equals(last.get(e.getKey())))
                out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * Fill a Lua table <b>in place</b> from a parsed-JSON object (string keys) or array (1-based),
     * delegating each value to the canonical {@link LuaMarshal#jsonToLua} marshal (D-013). Filling in
     * place (rather than replacing the table) preserves the addon's cached store-table reference.
     * Anything but a Map/List is a no-op (a missing/scalar value leaves the table empty).
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
}
