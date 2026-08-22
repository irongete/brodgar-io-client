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
import java.util.concurrent.ConcurrentHashMap;
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
 * <p><b>The remembered placements are addressed too</b> ({@code widget:remember(name)}, 062; 092.8, A-088).
 * A placement is filed under the scope of <b>the tree the widget stands in</b>: a session's own window
 * ({@code s:ui():find("@ChatUI")}) under that character's folder, and a window the addon built itself under
 * the <b>account</b> — the layer is the addon's and outlives every character, so its windows belong to nobody
 * in particular. Until 092 there was one set for the client, holding whoever was on screen, which wrote a
 * background character's window into the drawn character's folder and read it back out of it.
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
     * <b>The character last seen on screen</b> — {@code <genus>_<char>}, or {@code null} for the login screen
     * and the beat between one session ending and the next being drawn.
     *
     * <p>092.8: it no longer says which folder the placements hold, because they hold as many as there are
     * trees with a remembered widget in them. All it does now is let {@link #rescope} notice that the screen
     * changed, which is a good moment to put what is owed on disk: a tab is often followed by the session
     * being closed, and the auto-save runs every 30 seconds.
     *
     * <p>Written by {@link #rescope} and {@link #detach} alone, both on the UI thread, which is also the only
     * thread that reads it (P5).
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
        // file (the same rule Json.writePos states for a position with no anchor) -- and since 084.5 they LOG
        // the first value they degrade, which is the half that was missing: not refusing is not a reason to
        // say nothing, and an addon that never calls flush() was otherwise never told at all.
        // list() — the ACCOUNT-scope names this addon declared, in manifest order. The per-character half is
        // s:store():list(), for the reason :get is split the same way: scope is the manifest's answer, and
        // each verb is about the file it names.
        store.set("list", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "store", "list");
                return names(owner, true);
            }
        });
        //   092.8: ...and the ACCOUNT's remembered placements, which are the windows the addon built ITSELF:
        // those stand in the layer, which belongs to no character, so this is the call that names their file.
        // A session's own widget files under that character and is written by that session's flush -- one
        // call, one file, which is the rule this pair already followed for the variables.
        store.set("flush", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "store", "flush");
                carriable(owner, owner.store, true, ACC);
                try {
                    LuaWidget.rememberCapture(owner);
                    writePlacements(owner, ACCOUNT);
                } catch(RuntimeException e) {
                    log(owner, "store: could not save remembered placements: " + e);
                }
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
        // It writes the places this addon remembers for widget:remember(name) in THIS character's folder as
        // well (092.8) -- the widgets of this session's own tree. Before, only the session on screen wrote
        // them anywhere, because there was one set of them and it was the screen's. The windows the addon
        // built itself stand in the layer and are hafen.store():flush()'s: one call, one file.
        // list() — the PER-CHARACTER names this addon declared, in manifest order (094, A-111). The account
        // half is hafen.store():list(); the two together are the whole declaration, and neither was readable
        // from Lua before although the refusal on a misspelt :get already printed it.
        m.set("list", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "store", "list", SS);
                return names(owner, false);
            }
        });
        m.set("flush", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "store", "flush", SS);
                AddonManager.SessionState st = session(user, "flush()");
                CharStore cs = charStore(st, owner);
                carriable(owner, cs.vars, false, SS);
                try {
                    LuaWidget.rememberCapture(owner);   // 062: where every remembered widget stands now
                    writePlacements(owner, st.charScope);
                } catch(RuntimeException e) {
                    log(owner, "store: could not save remembered placements: " + e);
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

    /**
     * <b>The names this addon declared in one scope</b> (094, A-111), as a plain string array — what
     * {@code hafen.store():list()} and {@code s:store():list()} answer, each about its own half.
     *
     * <p>The set is closed at load and {@code nameArg}'s refusal already enumerates it: the bridge could
     * produce the list and only an error message could see it, so an addon could not ask what it declared and
     * a debug dump of its own saved state was written by hand and went stale with the manifest. A list of
     * NAMES, so it stays an array rather than becoming a collection — the rule {@code pag:categories()} and
     * {@code item:slots()} already follow.
     */
    private static LuaValue names(Addon a, boolean account) {
        LuaTable t = new LuaTable();
        int n = 0;
        for(Manifest.SavedVar sv : a.manifest.savedVariables) {
            if(sv.account == account)
                t.set(++n, LuaValue.valueOf(sv.name));
        }
        return t;
    }

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
     * <b>The screen changed</b> — put what the remembered widgets are owed on disk (092.8). Run from the
     * layer's own tick and from {@link #enterWorld}. When the screen still holds what it held last tick this
     * is one map read and one string compare.
     *
     * <p><b>It used to be a swap</b>, and that was the defect: one set of placements followed the screen, so
     * a tab wrote the outgoing character's set back and read the incoming character's in — over widgets that
     * might be standing in neither of their trees. Now every set is filed under its own tree's folder and
     * nothing has to move when the player tabs; what is left is that a tab is a <i>good moment to write</i>,
     * since it is often followed by that session being closed and the auto-save runs every 30 seconds.
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
                LuaWidget.rememberCapture(a);   // 062: where every remembered widget stands right now...
                writePlacements(a);             //   ...each into the folder of the tree it stands in
            } catch(RuntimeException e) {
                log(a, "store: could not save remembered placements: " + e);
            }
        }
    }

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
        String bad = uncarriable(a, src, account);
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
    private static String uncarriable(Addon a, LuaTable src, boolean account) {
        if(src == null)
            return null;
        for(Manifest.SavedVar sv : a.manifest.savedVariables) {
            if(sv.account != account)
                continue;                       // 078.3: a flush answers for the scope it was asked on
            LuaValue v = src.get(sv.name);
            if(v.istable()) {
                String bad = uncarriable((LuaTable)v, "\"" + sv.name + "\"", Collections.newSetFromMap(
                                             new IdentityHashMap<LuaValue, Boolean>()));
                if(bad != null)
                    return bad;
            }
        }
        return null;
    }

    private static String uncarriable(LuaTable t, String path, Set<LuaValue> seen) {
        if(!seen.add(t))
            return null;                        // a cycle: the writer breaks it, and one visit reads it all
        for(LuaValue k : t.keys()) {
            LuaValue v = t.get(k);
            String at = path + "." + k.tojstring();
            if(v.istable()) {
                String bad = uncarriable((LuaTable)v, at, seen);
                if(bad != null)
                    return bad;
            } else if(!v.isnil() && !v.isboolean() && !(v instanceof LuaNumber) && !(v instanceof LuaString)
                      && (LuaPosition.resolve(v) == null)) {
                return at + " holds a " + v.typename();
            }
        }
        return null;
    }

    /**
     * <b>The write nobody asked for says what it degraded</b> (084.5), and goes on writing. {@link #flush()}
     * refuses a value a saved variable cannot hold; the timer and the teardown must not, because a write the
     * addon did not ask for cannot be allowed to cost it the rest of its file — but the silence that bought
     * was the whole defect: the forgiving serializer writes a function as a quoted {@code tostring}, which
     * reads back a week later as data-shaped garbage in an addon that never called {@code flush()} and was
     * therefore never told.
     *
     * <p><b>The FIRST value only, and only where the file actually changes.</b> One line names the path, which
     * is what an author acts on; a line per key would bury it, and the callers reach this after the
     * unchanged-content check, so a store left alone says nothing on every one of the timer's laps.
     */
    private static void degraded(Addon a, LuaTable src, boolean account, String how) {
        String bad = uncarriable(a, src, account);
        if(bad != null)
            log(a, "store: " + bad + ", which is saved as text and reads back as text — a saved variable may"
                + " hold only tables, strings, numbers, booleans and Positions. This write was the timer's or"
                + " the teardown's, so it was made anyway; " + how + ":flush() refuses it instead");
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
     * <b>One scope's remembered placements for one addon</b> (092.8) — the names, and the last JSON written
     * for them so an unchanged file is not rewritten. One of these per folder the addon has a remembered
     * widget in: a character's, or the account's.
     */
    static final class PlaceSet {
        final Map<String, Placement> byName = new ConcurrentHashMap<String, Placement>();
        String lastJson;
    }

    /**
     * <b>The scope of the addon's own layer</b> — the folder {@link #accountFile} already writes into, so a
     * remembered window of the layer sits beside the account variables it belongs with. It is the literal
     * folder name rather than a sentinel, because that is what it is.
     */
    static final String ACCOUNT = "account";

    /**
     * <b>The scope a widget's placement is filed under</b> (092.8, A-088) — the whole of the fix, and the one
     * question the old code never asked.
     *
     * <ul>
     *   <li><b>A widget standing in a session's own tree</b> — {@code s:ui():find("@ChatUI")}, the case
     *       {@code native.md} documents — is filed under <b>that character's</b> folder. Where the user
     *       dragged that character's chat window is a fact about that character.</li>
     *   <li><b>A widget the addon built itself</b> stands in the layer, which belongs to no session and
     *       outlives every character, so it is filed under the <b>account</b>: the layer is drawn over
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
            return ACCOUNT;
        AddonManager.SessionState st = AddonManager.state(u);
        return (st == null) ? null : st.charScope;
    }

    /** Is there anywhere to remember {@code w}'s placement? False before its character is in world. */
    static boolean placementScope(Widget w) {
        return scopeOf(w) != null;
    }

    /**
     * <b>One scope's placements for one addon</b>, minted on the first ask and <b>read off disk then</b>. The
     * lazy load is what lets a set exist per tree with nothing having to notice a tree appearing: the first
     * {@code widget:remember(name)} in that tree is the notice.
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
     * they held. A half arrives {@code null} when this addon has no level of that kind on the widget, and a
     * half that is not written is not erased — an addon that stops resizing a window has not decided the user
     * never sized it.
     */
    static void land(Addon a, Widget w, String name, Coord pos, Coord size) {
        String scope = scopeOf(w);
        if((scope == null) || ((pos == null) && (size == null)))
            return;
        Map<String, Placement> m = set(a, scope).byName;
        Placement p = m.get(name);
        if(p == null)
            m.put(name, p = new Placement());
        if(pos != null)
            p.pos = pos;
        if(size != null)
            p.size = size;
    }

    /** {@code widget:remember(nil)}: the record is <b>deleted</b>, on disk in the same call. */
    static void forget(Addon a, Widget w, String name) {
        String scope = scopeOf(w);
        if(scope == null)
            return;
        PlaceSet ps = set(a, scope);
        if(ps.byName.remove(name) != null)
            writePlacements(a, scope, ps);
    }

    /** The per-scope placement file, beside the addon's own {@code <id>.json} in that same folder. */
    private static File placementFile(Addon a, String scope) {
        return new File(new File(saveDir(), scope), a.manifest.id + ".layout.json");
    }

    /** Read one scope's placements off disk into a freshly minted set. */
    private static void loadPlacements(Addon a, String scope, PlaceSet ps) {
        String text = readFile(placementFile(a, scope));
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
                            ps.byName.put(e.getKey(), p);
                    }
                }
            } catch(RuntimeException e) {
                log(a, "store: could not read " + placementFile(a, scope).getName() + ": " + e);
            }
        }
        ps.lastJson = placementsJson(ps);   // prime the write-skip cache, as a scope load does
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

    /**
     * <b>Write every scope this addon has placements loaded for</b> (092.8), each into its own folder. A set is
     * only ever minted for a tree something was actually remembered in, so this walks nothing on a client
     * whose addons build no windows.
     */
    private static void writePlacements(Addon a) {
        for(Map.Entry<String, PlaceSet> e : a.placeSets.entrySet())
            writePlacements(a, e.getKey(), e.getValue());
    }

    /**
     * <b>One named scope's placements</b> (092.8) — what an asked-for {@code flush()} writes, each verb
     * naming the file it is about: {@code hafen.store():flush()} the account's, {@code s:store():flush()}
     * that character's. Nothing at all when this addon has remembered nothing in that scope.
     */
    private static void writePlacements(Addon a, String scope) {
        PlaceSet ps = a.placeSets.get(scope);
        if(ps != null)
            writePlacements(a, scope, ps);
    }

    /** One scope's half of {@link #writePlacements(Addon)}, skipped when the file would not change. */
    private static void writePlacements(Addon a, String scope, PlaceSet ps) {
        if(ps.byName.isEmpty() && (ps.lastJson == null))
            return;                                     // this addon remembers nothing here and never did
        String out = placementsJson(ps);
        if(out.equals(ps.lastJson))
            return;
        if(writeFile(placementFile(a, scope), out))
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

    /** Serialize the account scope and write the file if it differs from the last write. */
    private static void writeAccount(Addon a) {
        String out = scopeJson(a, a.store, true);
        if((out == null) || out.equals(a.lastAccountJson))
            return;                                     // no account vars, or unchanged → skip disk I/O
        degraded(a, a.store, true, ACC);                // 084.5: say what is about to be written as text
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
        degraded(a, cs.vars, false, SS);                // 084.5: say what is about to be written as text
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
