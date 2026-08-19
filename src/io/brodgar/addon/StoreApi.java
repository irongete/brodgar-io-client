package io.brodgar.addon;

import haven.Coord;
import haven.GameUI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaNumber;
import org.luaj.vm2.LuaString;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;


import static io.brodgar.addon.AddonManager.*;

/**
 * The saved-variables subsystem (1e / D-002 / D-023). One Lua table per manifest-declared saved variable,
 * persisted to JSON under {@code savedata/} (account scope + per-character {@code <genus>_<char>} scope).
 * {@link AddonManager} drives it via {@link #enterWorld} (a session learns its character and reads that
 * character's variables in), {@link #sessionEnded}/{@link #drainEnded} (a session died, and its variables go
 * back to disk), {@link #rescope} (the layer's tick, and the whole of when the remembered placements move),
 * {@link #flush} (teardown) and {@link #autosave} (the throttled tick save). Not instantiable.
 *
 * <p><b>The section SPLITS by scope</b> (078.3), because an account's saved variables and a character's are
 * not the same thing. The account scope is the addon's — one file for the client, whichever character is up —
 * and is {@code hafen.store()}, built by {@link #installStore}; a per-character scope is one character's own
 * folder and is {@code session:store()}, built by {@link #store}. Scope is declared in the manifest rather
 * than chosen at the call, so the two halves carry the same two verbs and each refuses a name belonging to the
 * other, naming the door it does have.
 *
 * <p><b>The per-character tables ARE the session's</b> (079.1), held in that session's own
 * {@link AddonManager.SessionState} as one {@link CharStore} per addon, and there are as many sets as there
 * are logins. So {@code s:store()} answers about the character it names — the one on screen or any other —
 * which is what every other section on a Session does, and two characters write two folders without either
 * seeing the other's keys. What a session cannot answer is a character it has not got: a login still
 * connecting or sitting on the character list has no folder at all, and that is a refusal ({@link #session})
 * rather than a table that would take writes and never save them.
 *
 * <p><b>Every session knows its own character folder</b> (073.5, {@code SessionState.charScope}): a client
 * with two logins has two answers, and each is that login's own. A {@link CharStore} <b>holds</b> the folder
 * it was loaded for rather than looking it up, and that is what makes the last write land: a session's
 * variables are written back when it stops playing that character — including the case where it stopped by
 * <i>ending</i>, which destroys its {@code UI} before anything on the tick notices. Asking a dead session for
 * its folder answers {@code null} exactly when the data has to be persisted; holding the folder string
 * answers it.
 *
 * <p><b>A table is refilled, never replaced</b>, so a reference an addon cached at load time is still the one
 * being written to disk. A session that changes character writes the outgoing one's variables back and reads
 * the incoming one's into the very same tables.
 *
 * <p><b>What still follows the SCREEN is the remembered placements</b> ({@code widget:remember(name)}, 062).
 * A placement is where a window sits, the windows an addon builds stand in the layer above every session, and
 * the layer is drawn wherever the player is looking — so there is one set of them, holding the character on
 * screen, moved by {@link #rescope}.
 */
final class StoreApi {
    private StoreApi() {}

    private static final double SAVE_INTERVAL = 30.0;   // throttled auto-save period (seconds; UI thread)

    /**
     * <b>One session's per-character saved variables, for one addon</b> (079.1) — the live tables
     * {@code s:store():get(name)} hands back, and the folder they came from.
     *
     * <p>Minted on demand and kept in {@link AddonManager.SessionState#charStores}, so it dies with the
     * session that owns it and with the addon that declared it, and neither can reach the other's.
     */
    static final class CharStore {
        /** Declared per-character name &rarr; the live table. Refilled in place; never replaced. */
        final LuaTable vars = new LuaTable();
        /**
         * The {@code <genus>_<char>} folder these tables were read from, or {@code null} while they hold
         * nobody. <b>Held, not looked up</b>: this is the folder the write goes back to, and the session that
         * has to be written is often one that has just ended and can no longer be asked.
         */
        String scope;
        /** The last JSON written for this scope, so an unchanged file is not rewritten. */
        String lastJson;
    }

    /**
     * <b>The character folder the remembered placements are holding right now</b> — {@code <genus>_<char>} of
     * the session on screen, or {@code null} when no character is being looked at (the login screen, a session
     * that has not reached the world yet, or the beat between one ending and the next being drawn). A
     * placement is where a window stands, and the windows an addon builds stand in the layer over whichever
     * session is drawn, so there is one answer to <i>whose screen is this</i>.
     *
     * <p>Written by {@link #rescope} and {@link #detach} alone, both on the UI thread, which is also the only
     * thread that reads it — a placement is written on a tick, on a teardown or in a Lua verb, and Lua runs
     * there and nowhere else (P5).
     */
    private static String placeScope;

    /**
     * <b>Sessions whose {@code UI} died with per-character tables still in them</b> (079.1). Filled by
     * {@link #sessionEnded} from the dying session's own thread, which may do nothing more than that: the
     * tables are Lua, and Lua is read on the UI thread and nowhere else (P5). Drained by {@link #drainEnded}
     * on the layer's tick, which writes each one back into the folder its {@link CharStore} holds.
     */
    private static final Queue<AddonManager.SessionState> ended =
        new ConcurrentLinkedQueue<AddonManager.SessionState>();

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
     * <p><b>Only the ACCOUNT half is built here</b> (079.1). A per-character table belongs to one session and
     * is minted in that session's own {@link CharStore}, so this table holds exactly the names the manifest
     * declares {@code "scope": "account"} and there is nothing here for a second character to overwrite.
     *
     * <p>The names are the <i>addon's own</i>, so the refusal that catches the old spelling cannot live in the
     * static {@link Retired} table: {@link #index} builds it per owner from the manifest.
     */
    static void installStore(LuaTable hafen, final Addon owner) {
        LuaTable vars = new LuaTable();
        for(Manifest.SavedVar sv : owner.manifest.savedVariables) {
            if(!sv.account || vars.get(sv.name).istable())
                continue;                                // per character (a session's), or a duplicate name
            vars.set(sv.name, new LuaTable());           // always a usable (possibly empty) table
        }
        owner.store = vars;

        LuaTable store = new LuaTable();
        // get(name) — the LIVE table for one declared ACCOUNT-scope saved variable. A per-character name is
        // refused naming the session it is reached through (078.3): scope is declared in the manifest rather
        // than chosen at the call, so which half a name is in is the MANIFEST's answer and this is the only
        // place that knows it. A name the manifest does not declare at all is a typo with no future meaning
        // (the set is closed at load), so it throws listing what IS declared rather than answering nil and
        // failing one index later with nothing to name.
        store.set("get", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "store", "get");
                Manifest.SavedVar sv = nameArg(owner, a, ACC);
                if(!sv.account)
                    throw new LuaError(ACC + ":get(\"" + sv.name + "\") — \"" + sv.name + "\" is declared PER"
                        + " CHARACTER, and a character's saved variables are reached through the session whose"
                        + " character they are: hafen.session():current():store():get(\"" + sv.name + "\") is"
                        + " the character on screen, and hafen.session():get(user):store() is any other. "
                        + ACC + " is the ACCOUNT scope — one file for the client, whichever character is up —"
                        + " and a variable joins it by being declared {\"name\": \"" + sv.name + "\","
                        + " \"scope\": \"account\"} in manifest.json");
                return owner.store.get(sv.name);
            }
        });
        // flush() — the one write an addon ASKS for, and therefore the one that can answer. It writes the
        // ACCOUNT scope, which is the half this section IS; the other half is session:store():flush(), and
        // splitting them is what keeps each call about the file it names. It refuses a value a saved variable
        // cannot hold, naming where it sits, BEFORE writing anything; the timer and the teardown go on
        // writing whatever they find, because a write nobody asked for must not cost an addon the rest of its
        // file (the same rule Json.writePos states for a position with no anchor).
        store.set("flush", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "store", "flush");
                carriable(owner, owner.store, true, ACC);
                try {
                    writeAccount(owner);
                } catch(RuntimeException e) {
                    log(owner, "store: flush failed: " + e);
                }
                return self;
            }
        });
        LuaValue obj = Section.object("store", store);
        Section.mount(hafen, "store", obj,
                      "hafen.store.<name> is now hafen.store():get(\"<name>\") for an account-scope name and"
                      + " hafen.session():current():store():get(\"<name>\") for a per-character one",
                      new LuaTable(), index(owner));
        loadAccount(owner);                              // account-scope vars: ready before Load
    }

    /** How the account half is reached, and the spelling its messages quote. */
    private static final String ACC = "hafen.store()";

    /** How the per-character half is reached (078.3), and the spelling every one of its messages quotes. */
    private static final String SS = "session:store()";

    /**
     * Build the {@code store} section object for {@code (owner, user)} — <b>the saved variables of one
     * character</b>, reached as {@code session:store()} (078.3).
     *
     * <p><b>An account's saved variables and a character's are not the same thing</b>, which is why this
     * namespace SPLITS rather than moves. The account scope is the <i>addon's</i> — one file for the client,
     * whichever character is up — and keeps its global spelling; a per-character scope is one character's own
     * folder, so it grows an address and is reached here. Which half a name is in is not a verb but the
     * manifest's own declaration, so both halves carry the same two verbs and each refuses a name belonging
     * to the other, naming where it is reached instead.
     *
     * <p><b>The address is the whole of the answer</b> (079.1): the tables are the named session's own, so
     * this reads and writes that character's folder whether or not anyone is looking at it, and a second
     * character reached through a second Session is a second folder. What it refuses is a session with no
     * character to have variables for — one that has ended, and one that has not reached the world yet.
     *
     * <p>Minted once per {@code (addon, session)} and hung on the interned Session handle, the shape
     * {@code WorldApi.world} established — so {@code s:store() == s:store()}.
     */
    static LuaValue store(final Addon owner, final String user) {
        LuaTable m = new LuaTable();
        // :get(name) — the LIVE table for one declared PER-CHARACTER saved variable, that character's own. An
        // account-scope name is refused naming hafen.store(), which is the same mistake from the other side.
        m.set("get", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "store", "get", SS);
                Manifest.SavedVar sv = nameArg(owner, a, SS);
                if(sv.account)
                    throw new LuaError(SS + ":get(\"" + sv.name + "\") — \"" + sv.name + "\" is declared"
                        + " account scope, and an account's saved variables are the ADDON's rather than a"
                        + " character's: one file for the client whichever character is up, so it is reached"
                        + " without an address — " + ACC + ":get(\"" + sv.name + "\")");
                return charStore(session(user, "get(\"" + sv.name + "\")"), owner).vars.get(sv.name);
            }
        });
        // :flush() — write THIS character's saved variables now. The account half is hafen.store():flush().
        // Refuses an uncarriable value first, exactly as that one does.
        //
        // It writes the places this addon remembers for widget:remember(name) as well WHEN this session is
        // the one on screen, because that is whose character those places are: a remembered window stands in
        // the layer, drawn over whichever session that is.
        m.set("flush", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "store", "flush", SS);
                AddonManager.SessionState st = session(user, "flush()");
                CharStore cs = charStore(st, owner);
                carriable(owner, cs.vars, false, SS);
                if(st == AddonManager.state(AddonManager.screen())) {
                    try {
                        LuaWidget.rememberCapture(owner);   // 062: where every remembered widget stands now
                        writePlacements(owner);
                    } catch(RuntimeException e) {
                        log(owner, "store: could not save remembered placements: " + e);
                    }
                }
                try {
                    writeChar(owner, cs);
                } catch(RuntimeException e) {
                    log(owner, "store: flush failed: " + e);
                }
                return self;
            }
        });
        return Section.object("store", m, SS);
    }

    /**
     * The declared-variable name a {@code :get} was handed — a string, and one this addon's manifest names.
     * Shared by both halves, so a misspelt name is answered the same way whichever door it came through.
     */
    private static Manifest.SavedVar nameArg(Addon owner, Varargs a, String how) {
        LuaValue nm = Args.required(a, 2, how + ":get", "name");
        if(nm.type() != LuaValue.TSTRING)
            throw new LuaError(how + ":get(name): name must be a string (a saved variable declared in"
                + " manifest.json)");
        Manifest.SavedVar sv = declaredVar(owner, nm.tojstring());
        if(sv == null)
            throw new LuaError(how + ":get(\"" + nm.tojstring() + "\"): this addon declares no saved variable"
                + " of that name. Declared: " + declared(owner)
                + " — add it to \"saved_variables\" in manifest.json");
        return sv;
    }

    /** This addon's declaration of {@code name}, or {@code null}. The first wins, as {@link #installStore} does. */
    private static Manifest.SavedVar declaredVar(Addon a, String name) {
        for(Manifest.SavedVar sv : a.manifest.savedVariables) {
            if(sv.name.equals(name))
                return sv;
        }
        return null;
    }

    /**
     * <b>The session a per-character verb was addressed to</b> (079.1) — the one guard this half has, and the
     * whole of it. Every live session in the world answers here, drawn or not, because the tables are that
     * session's own. Two states cannot: a session that is not live has had its variables written and dropped,
     * and one that has not reached the world has no folder to have any in. Both would otherwise be an empty
     * table that takes writes and never saves them, so both are said out loud, and the message says which.
     */
    private static AddonManager.SessionState session(String user, String call) {
        AddonManager.SessionState st = AddonManager.state(AddonManager.sessionui(user));
        if(st == null)
            throw new LuaError(SS + ":" + call + " — \"" + user + "\" is not a live session, so it has no"
                + " saved variables in memory: its own were written to disk when it ended. hafen.session()"
                + " lists the sessions the client holds, and s:exists() is the test.");
        if(st.charScope == null)
            throw new LuaError(SS + ":" + call + " — that session has no character yet (it is connecting, or"
                + " on the character list), so it has no per-character folder at all: its variables arrive"
                + " with SessionEnteredWorld.");
        return st;
    }

    /**
     * <b>This session's per-character tables for one addon</b>, minted on the first ask. The tables exist
     * before the folder does, so an addon loaded while a session is already in world gets that character's
     * data read in here rather than an empty set it would write over the file.
     */
    private static CharStore charStore(AddonManager.SessionState st, Addon a) {
        CharStore have = st.charStores.get(a);
        if(have != null)
            return have;
        CharStore mk = new CharStore();
        for(Manifest.SavedVar sv : a.manifest.savedVariables) {
            if(!sv.account && !mk.vars.get(sv.name).istable())
                mk.vars.set(sv.name, new LuaTable());    // always a usable (possibly empty) table
        }
        CharStore prev = st.charStores.putIfAbsent(a, mk);
        if(prev != null)
            return prev;
        if(st.charScope != null) {
            mk.scope = st.charScope;
            loadChar(a, mk);
        }
        return mk;
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
                    Manifest.SavedVar sv = declaredVar(owner, nm);
                    if(sv != null)
                        throw new LuaError("hafen.store." + nm + " is now "
                            + (sv.account ? ACC + ":get(\"" + nm + "\")"
                                          : "hafen.session():current():store():get(\"" + nm + "\")")
                            + " — what it hands back is the same live table, so writing into it still persists"
                            + (sv.account ? "" : ". A bare name in \"saved_variables\" is PER CHARACTER, and a"
                                + " character's saved variables are reached through the session whose"
                                + " character they are"));
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
     * Throttled auto-save from this session's tick: every addon's account file, the places the screen's
     * character has its windows in, and <b>this session's own</b> per-character variables. The write skips
     * unchanged files. The throttle is the ticking session's own, so each login pays for its own character.
     */
    static void autosave(AddonManager.SessionState st, double clock) {
        if(clock - st.storeLastAutoSave >= SAVE_INTERVAL) {
            st.storeLastAutoSave = clock;
            for(Addon a : addons)
                save(a, st);
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
     * <b>A session learns which character it is playing</b>, and reads that character's saved variables in —
     * its {@code <genus>_<char>} folder, now that the HUD is up. Called once per world entry, and once more
     * by a {@code :reload} for the session on screen.
     *
     * <p>073.5: it is handed <b>the session it is about and that session's own HUD</b> — the caller has both
     * (the tick that saw the world come up, the reload that found the HUD in its own tree), and reading the
     * screen instead would file one login's folder under whichever character is being looked at.
     *
     * <p>079.1: and it <b>loads</b>, for that session and no other, which is what makes the variables of a
     * character nobody is looking at be that character's. Whatever the tables held first goes back where it
     * came from: a session picking a second character keeps its {@code UI} and comes through here again, so
     * the outgoing character's data is written before the incoming character's is read into the very same
     * tables. The remembered placements follow the screen, so {@link #rescope} is called on the way out — a
     * session entering the world <i>as</i> the screen has both in place before {@code SessionEnteredWorld}
     * fires, which is where the docs send an addon to read them.
     */
    static void enterWorld(AddonManager.SessionState st, GameUI g) {
        if((st == null) || (g == null))
            return;
        String want = scopeKey(g.genus, g.chrid);
        unloadChar(st);                     // whatever these tables held goes back to the folder it came from
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
     * <b>Bring the remembered placements into step with the session on screen</b> — the one door, run from
     * the layer's own tick and from {@link #enterWorld}. When the screen already holds what they hold this is
     * one map read and one string compare; otherwise the outgoing character's placements are written back
     * where they came from and the incoming character's are read in.
     *
     * <p><b>Derived, not notified</b>, and that is deliberate: the screen changes for four different reasons
     * — a tab, a session reaching the world, a session ending, a relogin replacing a {@code UI} under the same
     * slot — and three of them happen on threads that are not this one. A seam per reason is three chances to
     * miss one and no way to tell; a comparison on the tick cannot be missed, and answers all four the same.
     *
     * <p><b>A session that ends is one of those four.</b> Its {@code UI} is destroyed from its own thread, so
     * by the time this runs there is nothing left to ask — which is why the folder is held in
     * {@link #placeScope} rather than looked up: the write below lands in the folder of the character whose
     * placements are actually loaded, whether that session was tabbed away from or ended outright.
     */
    static void rescope() {
        AddonManager.SessionState st = AddonManager.state(AddonManager.screen());
        String want = (st == null) ? null : st.charScope;
        if((want == null) ? (placeScope == null) : want.equals(placeScope))
            return;
        for(Addon a : addons) {
            if(placeScope != null) {
                try {
                    LuaWidget.rememberCapture(a);   // 062: where every remembered widget stands as this
                    writePlacements(a);             //   character goes off screen — the save timer's capture
                } catch(RuntimeException e) {
                    log(a, "store: could not save remembered placements: " + e);
                }
            }
            forgetPlacements(a);
        }
        placeScope = want;
        if(placeScope == null)
            return;                        // nobody on screen: nothing to put back, and nothing held for it
        for(Addon a : addons)
            loadPlacements(a);             // 062: where this character last left what each addon remembers,
    }                                      //   so widget:remember(name) has it to put back

    /**
     * <b>A session's {@code UI} died with its per-character tables still in it</b> (079.1) — from
     * {@link AddonManager#uiDestroyed}, on the dying session's own thread, which is why this only files it.
     * The tables are Lua and are read on the UI thread and nowhere else (P5), so the write is
     * {@link #drainEnded}'s, on the layer's next tick — and it still lands, because each {@link CharStore}
     * holds the folder it was loaded for rather than asking a session that no longer exists.
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
     * Write one session's per-character tables back where they came from and empty them. The account scope is
     * untouched: it is the same for every character on the account, so nothing about it changes when a
     * session does, and it goes on being flushed with the addon.
     *
     * <p>The tables are <b>emptied rather than replaced</b>, for the reason {@link #loadChar} refills in
     * place: a reference an addon cached at load time has to go on being the live one.
     *
     * <p><b>Emptying is unconditional and writing is not.</b> A set of tables holding no folder has nowhere
     * to be written — but there can still be something to empty, because an addon that wrote into a session's
     * tables before it reached the world wrote them for nobody. Carried instead of dropped, those keys would
     * arrive in that character's tables and be saved into their file as if they had always been theirs.
     */
    private static void unloadChar(AddonManager.SessionState st) {
        for(Map.Entry<Addon, CharStore> e : st.charStores.entrySet()) {
            Addon a = e.getKey();
            CharStore cs = e.getValue();
            if(cs.scope != null) {
                try {
                    writeChar(a, cs);
                } catch(RuntimeException ex) {
                    log(a, "store: could not save this character's variables: " + ex);
                }
            }
            cs.scope = null;
            cs.lastJson = null;
            for(Manifest.SavedVar sv : a.manifest.savedVariables) {
                if(sv.account)
                    continue;
                LuaValue t = cs.vars.get(sv.name);
                if(t.istable())
                    clearTable((LuaTable)t);
            }
        }
    }

    /**
     * <b>The client holds nobody's addons</b> — a {@code :reload}, between the teardown that flushed every
     * addon and the load that builds new ones. What the sessions hold is dropped rather than written: those
     * tables are already written and belong to addons that no longer exist, and writing the new addons' empty
     * ones over a character's files is the one way a reload could cost the user their settings. A session
     * that ended and has not been drained yet is drained first, for the same reason — its data is still owed
     * to its own folder.
     */
    static void detach() {
        drainEnded();
        for(AddonManager.SessionState st : AddonManager.allStates())
            st.charStores.clear();
        placeScope = null;
    }

    /**
     * <b>What a saved variable may hold</b>, checked over one addon's declared tables before an asked-for
     * {@code flush()} writes. A function, a widget handle or any other live thing is written by the forgiving
     * serializer as a quoted {@code tostring} and read back as that string — data-shaped garbage, discovered
     * a week later by the addon that trusted it. Here it is discovered at the call.
     *
     * <p>It names the <b>path</b> and not just the variable, because a bad value is nearly always nested: the
     * addon put a widget in the table it saves its layout from, and the name of the variable alone would send
     * it looking through the whole thing.
     */
    private static void carriable(Addon a, LuaTable src, boolean account, String how) {
        if(src == null)
            return;
        for(Manifest.SavedVar sv : a.manifest.savedVariables) {
            if(sv.account != account)
                continue;                       // 078.3: a flush answers for the scope it was asked on
            LuaValue v = src.get(sv.name);
            if(v.istable())
                carriable((LuaTable)v, "\"" + sv.name + "\"", how, Collections.newSetFromMap(
                              new IdentityHashMap<LuaValue, Boolean>()));
        }
    }

    private static void carriable(LuaTable t, String path, String how, Set<LuaValue> seen) {
        if(!seen.add(t))
            return;                             // a cycle: the writer breaks it, and one visit reads it all
        for(LuaValue k : t.keys()) {
            LuaValue v = t.get(k);
            String at = path + "." + k.tojstring();
            if(v.istable()) {
                carriable((LuaTable)v, at, how, seen);
            } else if(!v.isnil() && !v.isboolean() && !(v instanceof LuaNumber) && !(v instanceof LuaString)
                      && (LuaPosition.resolve(v) == null)) {
                throw new LuaError(how + ":flush(): " + at + " holds a " + v.typename() + ", and a saved"
                    + " variable may hold only tables, strings, numbers, booleans and Positions — save what"
                    + " describes the thing (a resource name, a colour's three numbers) and rebuild it on load");
            }
        }
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

    /** The on-disk JSON file for one addon's account scope (may not exist yet). */
    private static File accountFile(Addon a) {
        return new File(new File(saveDir(), "account"), a.manifest.id + ".json");
    }

    /** The on-disk JSON file for one addon under one character's folder (may not exist yet). */
    private static File charFile(Addon a, String scope) {
        return new File(new File(saveDir(), scope), a.manifest.id + ".json");
    }

    /**
     * <b>The layer's own per-character files</b> — state the client keeps for a character that belongs to no
     * addon, such as the action-bar slots an addon's entry is {@link BeltHold held} in. They sit in a
     * {@code client/} folder <i>inside</i> the character's scope directory, so no addon's {@code <id>.json} can
     * collide with one whatever the addon is called: a manifest {@code id} is a folder name beside them, and a
     * folder never collides with a file one level down.
     *
     * <p>{@code null} until a character is known ({@link #enterWorld}), because "per character" has no
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

    /** One addon's account-scope saved variables, read in at install and primed for the write-skip. */
    private static void loadAccount(Addon a) {
        if((a.store == null) || !hasScope(a, true))
            return;
        loadInto(a, a.store, true, accountFile(a));
        a.lastAccountJson = scopeJson(a, a.store, true);
    }

    /** One session's per-character saved variables for one addon, read in from the folder it holds. */
    private static void loadChar(Addon a, CharStore cs) {
        if((cs.scope == null) || !hasScope(a, false))
            return;
        loadInto(a, cs.vars, false, charFile(a, cs.scope));
        cs.lastJson = scopeJson(a, cs.vars, false);
    }

    /**
     * Load one scope's saved variables from disk into the tables that hold them, filling them in place so
     * that table identity is preserved. A missing or malformed file leaves the tables as they are. The
     * caller primes the write-skip cache with the canonical serialization of what is now held, so an
     * unchanged first flush writes nothing.
     */
    private static void loadInto(Addon a, LuaTable dst, boolean account, File f) {
        String text = readFile(f);
        if(text == null)
            return;
        try {
            Object root = Json.parse(text);
            if(root instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>)root;
                for(Manifest.SavedVar sv : a.manifest.savedVariables) {
                    if(sv.account != account)
                        continue;
                    LuaValue have = dst.get(sv.name);
                    LuaTable tgt;
                    if(have.istable()) {
                        tgt = (LuaTable)have;
                        clearTable(tgt);            // refill in place → the addon's ref stays valid
                    } else {
                        tgt = new LuaTable();
                        dst.set(sv.name, tgt);
                    }
                    fillTable(tgt, m.get(sv.name), a);  // object or array; absent/scalar → left empty
                }
            }
        } catch(RuntimeException e) {
            log(a, "store: could not read " + f.getName() + ": " + e);
        }
    }

    /**
     * Write an addon's changed data to disk: what it <b>remembers</b> ({@code widget:remember(name)}), its
     * account file, and <b>every live session's</b> per-character variables. Skips unchanged files. This is
     * the teardown's write — the addon is going, so every character it holds tables for is written.
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
            writeAccount(a);                            // account (always resolvable)
            for(AddonManager.SessionState st : AddonManager.allStates())
                writeChar(a, st.charStores.get(a));     // ...and each character this addon has tables for
        } catch(RuntimeException e) {
            log(a, "store: flush failed: " + e);
        }
    }

    /**
     * The auto-save's write: the placements and the account file as {@link #flush} writes them, and <b>one</b>
     * session's per-character variables — the one whose tick this is. Every session ticks, so every
     * character's file is written by its own login rather than by whichever one got there first.
     */
    private static void save(Addon a, AddonManager.SessionState st) {
        if(a == null)
            return;
        try {
            LuaWidget.rememberCapture(a);
            writePlacements(a);
        } catch(RuntimeException e) {
            log(a, "store: could not save remembered placements: " + e);
        }
        if((a.store == null) || a.manifest.savedVariables.isEmpty())
            return;
        try {
            writeAccount(a);
            writeChar(a, st.charStores.get(a));
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
     * per-character saved variable, and the character is the one on SCREEN — a remembered window stands in
     * the layer, drawn over whichever session that is. So before any character is in world there is nothing
     * to put back, and {@code widget:remember(name)} says so rather than applying an empty record.
     */
    static boolean placementScope() {
        return placeScope != null;
    }

    /** What is saved under {@code name} for this character, or {@code null} (no character, or nothing saved). */
    static Placement placement(Addon a, String name) {
        return (placeScope == null) ? null : a.placements.get(name);
    }

    /**
     * Where a remembered widget <b>stands now</b>: write the halves given and leave the others holding what
     * they held. A half arrives {@code null} when this addon has no level of that kind on the widget, and a
     * half that is not written is not erased — an addon that stops resizing a window has not decided the user
     * never sized it.
     */
    static void land(Addon a, String name, Coord pos, Coord size) {
        if((placeScope == null) || ((pos == null) && (size == null)))
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
        return new File(new File(saveDir(), placeScope), a.manifest.id + ".layout.json");
    }

    /** Load this character's placements for one addon, replacing whatever the last character left. */
    private static void loadPlacements(Addon a) {
        forgetPlacements(a);
        if(placeScope == null)
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
        a.lastPlacementJson = placementsJson(a);   // prime the write-skip cache, as a scope load does
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
        if((placeScope == null) || (a.placements.isEmpty() && (a.lastPlacementJson == null)))
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

    /** Serialize the account scope and write the file if it differs from the last write. */
    private static void writeAccount(Addon a) {
        String out = scopeJson(a, a.store, true);
        if((out == null) || out.equals(a.lastAccountJson))
            return;                                     // no account vars, or unchanged → skip disk I/O
        if(writeFile(accountFile(a), out))
            a.lastAccountJson = out;
    }

    /** Serialize one session's per-character scope and write it into the folder that set of tables holds. */
    private static void writeChar(Addon a, CharStore cs) {
        if((cs == null) || (cs.scope == null))
            return;                                     // no tables for this session, or they hold nobody
        String out = scopeJson(a, cs.vars, false);
        if((out == null) || out.equals(cs.lastJson))
            return;
        if(writeFile(charFile(a, cs.scope), out))
            cs.lastJson = out;
    }

    /**
     * Serialize one scope's saved vars as a JSON object {@code {name: table, …}} (reusing the compact
     * REPL writer), or {@code null} if the addon declares no vars of this scope. A non-table value at a
     * declared name is written as {@code {}} (the contract is "a table per name").
     */
    private static String scopeJson(Addon a, LuaTable src, boolean account) {
        LuaTable wrap = new LuaTable();
        boolean any = false;
        for(Manifest.SavedVar sv : a.manifest.savedVariables) {
            if(sv.account != account)
                continue;
            any = true;
            LuaValue v = src.get(sv.name);
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
