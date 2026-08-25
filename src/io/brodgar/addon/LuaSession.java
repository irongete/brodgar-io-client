package io.brodgar.addon;

import haven.GameUI;
import io.brodgar.session.Sessions;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * A <b>Session object</b> — one of the logins this client holds, and the <b>address</b> an addon names a
 * character by (spec {@code 076-the-session-is-the-address}). {@code hafen.session():get(user)} mints one,
 * {@code hafen.session():current()} is the one on screen, and the collection over them is {@link SessionApi}.
 *
 * <p><b>It wraps the account name and nothing else</b>, exactly as {@link LuaGob} wraps only the gob id:
 * every verb re-resolves through {@link Sessions#byuser} on the call, so a handle kept across a character
 * switch, a relogin or the session ending stays meaningful. That is what makes the ref a
 * {@code SessionRemoved} handler is given still answer {@code :user()} while {@code :exists()} is
 * {@code false} — the name IS the ref, so there is nothing left to resolve and nothing to go stale.
 *
 * <p><b>The account, and not the character.</b> {@code Sessions.Member.chr} is the name {@code :session add}
 * asked for and may be absent, and picking another character keeps the session alive — the server hands it a
 * new world rather than ending it. So the key is the account, and {@code :character()} reads
 * {@link GameUI#chrid} off <b>that</b> session's own HUD, which is what that login is actually playing.
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045), the {@link LuaGob} mechanism verbatim: the handle
 * crosses as {@code LuaValue.userdataOf(luaSession, mt)} so Lua cannot scribble on it, and the {@link Cache}
 * on the owning {@link Addon} (weak values + a {@link ReferenceQueue} drained on every access) makes
 * {@code :get(u) == :get(u)} and {@code seen[s] = true} reliable. Never static: no Lua value crosses a
 * sandbox boundary, and the cache dies whole with the {@link Addon} on {@code :reload}.
 *
 * <p><b>What hangs on it.</b> {@code s:world()} and {@code s:player()} (076.3) were the first two namespaces
 * addressed through a Session rather than off {@code hafen}, 077.1 adds the six read-only sections of the
 * character sheet — {@code s:char()}, {@code s:meter()}, {@code s:buff()}, {@code s:study()},
 * {@code s:quest()} and {@code s:wound()} — and 077.2 the two rosters, {@code s:kin()} and
 * {@code s:party()}, and 077.3 the four that ACT — {@code s:actionbar()}, {@code s:speed()},
 * {@code s:craft()} and {@code s:menugrid()}, the last two of which report a WINDOW the game put up rather
 * than a fact about a body, and answer on a session nobody is looking at because that session keeps its
 * {@code GameUI}. 077.4 closes the family with {@code s:fight()} and {@code s:flowermenu()} — the second of
 * which looks screen-shaped and is not: the section IS the open menu, and a menu is a widget in one session's
 * tree rather than the right-click that raised it. 078.2 adds {@code s:ui()}, which is not a namespace moving
 * but half of one SPLITTING: the client's own widgets stand in one character's tree and are reached here, while
 * the windows an addon BUILDS stay {@code hafen.ui():window()} in the layer above every session. 078.3 adds
 * {@code s:store()}, the second half-namespace and the last of the sequence: a character's saved variables are
 * that character's own folder and are reached here, while an account's are the addon's one file and stay
 * {@code hafen.store()}. 110.2 adds {@code s:chat()}, the channels one character holds: the chat is a window
 * of one login's HUD, so two characters have two Party channels and two private conversations with the same
 * person, and the line {@code ch:send(text)} says goes out of the login it was addressed at.
 * Each is minted once per {@code (addon, session)} and kept on the handle — see
 * {@link #worldObj}. Every verb under them reads the session named rather than the one on
 * screen; the ones that are inherently the screen's say so where they are defined ({@code screenToWorld},
 * {@code worldToScreen}) and the ones that <b>send</b> go through {@link AddonManager#sendView}, because a
 * walk is the whole of what a character nobody is looking at takes.
 *
 * <p><b>The one verb here that is not a namespace and not a read</b> (081.2): {@code :close()} ends this
 * login, behind the {@code session.close} permission. It is the only write a Session carries — the screen is
 * the collection's ({@code hafen.session():current(s)}), because there is one screen however many logins
 * there are, while ending a login is about the one it names. {@code hafen.session():remove(s)} ends the same
 * login from the collection (087.3, D2), behind the same key; the two doors share {@link #noSession} so one
 * mistake reads as one problem however it was reached.
 *
 * <p><b>Threading.</b> Every read runs on the UI thread. {@code Sessions.members()} copies the membership
 * list, and a member's {@code ui} is null in the gaps ({@code Sessions.Member.run} clears it while the UI is
 * taken down and during a character handoff), so every verb answers {@code nil}-shaped there rather than
 * throwing. The {@link Cache} map is guarded on its own monitor (UI + REPL threads touch it).
 */
public final class LuaSession {
    /** The account name — the whole address, and the one thing that survives the session. */
    public final String user;

    /**
     * <b>The namespaces that hang on this session</b> (076.3, 077), minted lazily and held here rather than
     * on the {@link Addon}: they belong to a {@code (addon, session)} pair, and this handle <i>is</i> that
     * pair. So {@code s:world() == s:world()} and {@code s:meter() == s:meter()} come out of interning the
     * handle and need no cache of their own — and when the addon drops its last reference to {@code s}, the
     * whole bundle goes with it, because the handle is weakly held (see {@link Cache}).
     *
     * <p>They are <b>not</b> discarded when the session ends. A handle held past the end still answers
     * {@code :user()}, and its {@code :world()} answers {@code nil}-shaped rather than throwing — every verb
     * re-resolves the member, so there is no stale state for an ended session to leave behind.
     */
    private LuaValue worldObj, playerObj;
    /** The character sheet's six read-only namespaces (077.1), interned on the handle exactly as above. */
    private LuaValue charObj, meterObj, buffObj, studyObj, questObj, woundObj;
    /** The two rosters (077.2) — the first namespaces here to carry protected verbs. */
    private LuaValue kinObj, partyObj;
    /** The four that ACT (077.3): the bar, the speed selector, the open recipe and the action menu. */
    private LuaValue actionbarObj, speedObj, craftObj, menugridObj;
    /** The last two (077.4): the combat schools with the fight, and the radial menu this session has open. */
    private LuaValue fightObj, flowermenuObj;
    /** The client's own widgets (078.2) — the half of {@code ui} that names a tree rather than the layer. */
    private LuaValue uiObj;
    /** This character's saved variables (078.3) — the half of {@code store} that names a folder of its own. */
    private LuaValue storeObj;
    /** This character's chat (110.2): the channels it holds, and the one it has on screen. */
    private LuaValue chatObj;

    private LuaSession(String user) {
        this.user = user;
    }

    /** {@code tostring(s)} (also the {@code __tostring} answer): {@code Session(<user>)}. */
    public String toString() {
        return "Session(" + user + ")";
    }

    /** An interned Session object for {@code user} in {@code owner}'s env — the one way one reaches Lua. */
    static LuaValue of(Addon owner, String user) {
        return owner.sessions.of(user);
    }

    /**
     * The refusal <b>both</b> doors onto a logout give for an account the client holds no session for —
     * {@code s:close()} and {@code hafen.session():remove(s)} alike (087.3). One sentence, because one
     * mistake reading as two different problems depending on which verb found it is exactly the asymmetry
     * that putting the ending on the collection was meant to remove. Each caller prepends its own spelling;
     * everything after the colon is this.
     */
    static String noSession(String user) {
        return "the client holds no session for the account '" + user + "' — s:exists() is the test, and a"
            + " session that has already ended has nothing left to close";
    }

    /** The {@code LuaSession} behind a Lua value, or {@code null} for anything that is not a Session. */
    static LuaSession resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaSession) ? (LuaSession)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /**
     * One addon's Session interning cache and metatable (its {@link Addon#sessions}). Weak values + a
     * {@link ReferenceQueue} drained on every access — <i>not</i> a {@code WeakHashMap}, which is weak
     * <i>keys</i>; the metatable is built once, lazily. Holds its {@link Addon} because the verbs a Session
     * grows mint the <b>owner's</b> own interned objects.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Ref> live = new HashMap<String, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code user} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(String user) {
            drain();
            Ref r = live.get(user);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(user);
            }
            LuaValue v = LuaValue.userdataOf(new LuaSession(user), meta());
            live.put(user, new Ref(v, user, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref sr = (Ref)r;
                if(live.get(sr.key) == sr)     // not already replaced by a fresh handle for the same account
                    live.remove(sr.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    /** A weak handle reference that remembers its map key, so the {@link ReferenceQueue} drain can unmap it. */
    private static final class Ref extends WeakReference<LuaValue> {
        final String key;

        Ref(LuaValue v, String key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Session metatable --------------------------------------------------------------------

    /**
     * The per-addon metatable: {@code __index} = the methods table through {@link Refusal#closedIndex} (so
     * an unknown verb throws naming what this type does answer), plus {@code __tostring}/{@code __name}.
     */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("session", methods(owner),
            "one logged-in character"));
        mt.set("__name", LuaValue.valueOf("Session"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Session(?)" : h.toString());
            }
        });
        return mt;
    }

    /**
     * The method set. {@code :user()} answers from the handle alone and so survives the session ending;
     * everything else re-resolves the member and answers {@code nil}-shaped once it is gone.
     */
    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // user() — the account this session logged in as. The key, the ref, and the one read that never
        // fails: a handler dropping its tables for a session that has just ended needs it AFTER it ended.
        m.set("user", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "user").user);
            }
        });
        // character() — what this login is PLAYING, read off that session's own HUD (GameUI.chrid) rather
        // than the name `:session add` asked for: picking another character keeps the session alive, so the
        // requested name and the played one part company. nil until this session's HUD is up.
        m.set("character", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                GameUI g = gameui(handle(self, "character"));
                return ((g == null) || (g.chrid == null)) ? LuaValue.NIL : LuaValue.valueOf(g.chrid);
            }
        });
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(Sessions.byuser(handle(self, "exists").user) != null);
            }
        });
        // world() — THIS character's world: the objects it can see, the terrain it is standing on, the grids it
        // has streamed. Two characters in different places see different objects, not because there are two
        // worlds but because each looks out of its own eyes, and this is where an addon says whose eyes.
        m.set("world", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "world");
                if(h.worldObj == null)
                    h.worldObj = WorldApi.world(owner, h.user);
                return h.worldObj;
            }
        });
        // player() — THIS login's character, as the Player object: its own Gob, its cursor, and the walk. It is
        // never nil, because the address exists whether or not the session behind it does; what answers nil is
        // s:player():gob(), before that session is in the world and after it has gone.
        m.set("player", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "player");
                if(h.playerObj == null)
                    h.playerObj = CharApi.player(owner, h.user);
                return h.playerObj;
            }
        });
        // char() — THIS character's sheet: its attributes, its learning points, what it is carrying, what it
        // has eaten, what it knows. Every one of those is a number about one character, and this is where an
        // addon says whose.
        m.set("char", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "char");
                if(h.charObj == null)
                    h.charObj = CharApi.chr(owner, h.user);
                return h.charObj;
            }
        });
        // meter() — THIS character's HUD bars. The section object IS the meter slot: two characters have two
        // slots, and a bar read through the wrong one is a health figure for the wrong body.
        m.set("meter", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "meter");
                if(h.meterObj == null)
                    h.meterObj = CharApi.meters(owner, h.user);
                return h.meterObj;
            }
        });
        // buff() — THIS character's buff bar. The section object IS the bar.
        m.set("buff", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "buff");
                if(h.buffObj == null)
                    h.buffObj = CharApi.buffs(owner, h.user);
                return h.buffObj;
            }
        });
        // study() — THIS character's study window: the curiosities in it and the totals across them.
        m.set("study", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "study");
                if(h.studyObj == null)
                    h.studyObj = CharApi.study(owner, h.user);
                return h.studyObj;
            }
        });
        // quest() — THIS character's quest log. The section object IS the log, over both tabs.
        m.set("quest", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "quest");
                if(h.questObj == null)
                    h.questObj = CharApi.quests(owner, h.user);
                return h.questObj;
            }
        });
        // wound() — THIS character's wounds. The section object IS the list, in the window's own tree order.
        m.set("wound", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "wound");
                if(h.woundObj == null)
                    h.woundObj = CharApi.wounds(owner, h.user);
                return h.woundObj;
            }
        });
        // kin() — THIS character's kin roster. The section object IS the roster, and the buddy ids in it are
        // that roster's own: id 7 on two characters is two different people, which is why a Kin handle carries
        // the account beside the id. The five protected verbs are addressable and keep the one key each has —
        // a key names the ACTION, not the target, and the player could have tabbed here and done it.
        m.set("kin", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "kin");
                if(h.kinObj == null)
                    h.kinObj = CharApi.kin(owner, h.user);
                return h.kinObj;
            }
        });
        // party() — the party THIS character is in. The section object IS the roster. Two of your characters
        // in one party are two Party objects (a party hangs off the session's own Glob), each holding the
        // colours and the last-known positions the server sent that login.
        m.set("party", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "party");
                if(h.partyObj == null)
                    h.partyObj = CharApi.party(owner, h.user);
                return h.partyObj;
            }
        });
        // actionbar() — THIS character's hotbar. A slot index names one bar: slot 11 on two characters is two
        // different buttons, and pressing the wrong one is an ability fired on the wrong body. The two
        // protected verbs keep the one key each has and send through this session's own widgets.
        m.set("actionbar", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "actionbar");
                if(h.actionbarObj == null)
                    h.actionbarObj = CharApi.actionbar(owner, h.user);
                return h.actionbarObj;
            }
        });
        // speed() — THIS character's movement speed. The section object IS the collection of the speeds it can
        // pick right now, which is that login's own: sprint unlocked here says nothing about the alt.
        m.set("speed", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "speed");
                if(h.speedObj == null)
                    h.speedObj = CharApi.speed(owner, h.user);
                return h.speedObj;
            }
        });
        // craft() — the recipe THIS character has open. A window the game put up rather than a fact about a
        // body: a session nobody is looking at keeps its GameUI, so its recipe window is open and answers,
        // and :make() presses that window's own button. nil where that character has nothing open.
        m.set("craft", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "craft");
                if(h.craftObj == null)
                    h.craftObj = CharApi.craft(owner, h.user);
                return h.craftObj;
            }
        });
        // menugrid() — the action menu THIS character carries. The section object IS the catalogue, and it is
        // one login's: two characters know different actions, through two grids. An entry an addon adds goes
        // into the grid it was addressed at, so the same id may stand in each character's menu.
        m.set("menugrid", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "menugrid");
                if(h.menugridObj == null)
                    h.menugridObj = CharApi.menugrid(owner, h.user);
                return h.menugridObj;
            }
        });
        // fight() — THIS character's combat schools, and the fight it is in. A school is configured on one
        // character and a fight is fought by one body: the deck read here is that character's own layout, and
        // :target() is who IT is fighting, resolved in its own object cache.
        m.set("fight", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "fight");
                if(h.fightObj == null)
                    h.fightObj = CharApi.fight(owner, h.user);
                return h.fightObj;
            }
        });
        // flowermenu() — the radial menu THIS character has open. It looks like the screen's, because a
        // right-click is a mouse gesture and there is one mouse — but the section IS the open menu, and a menu
        // is a widget in one session's tree. So a ring left up on a character the player tabbed away from is
        // still open, still readable, and still selectable; every other session answers the same nothing it
        // answers with none up.
        m.set("flowermenu", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "flowermenu");
                if(h.flowermenuObj == null)
                    h.flowermenuObj = FlowerMenuApi.flowermenu(owner, h.user);
                return h.flowermenuObj;
            }
        });
        // ui() — the widgets the client put up for THIS character. The half of a namespace that SPLITS rather
        // than moves: hafen.ui():window() builds something of yours, in the layer above every session, and
        // keeps its global spelling — this reaches a window the game placed in one character's own tree. Two
        // trees, so a lookup here never finds anything you built, and a background session keeps its whole
        // tree: its Inventory is open, findable and readable while the player is looking at another character.
        m.set("ui", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "ui");
                if(h.uiObj == null)
                    h.uiObj = UiApi.ui(owner, h.user);
                return h.uiObj;
            }
        });
        // store() — the saved variables of THIS character. The second half-namespace, and it splits for the
        // same reason ui does: an account's saved variables are the ADDON's, one file whichever character is
        // up, and keep hafen.store(); a character's are that character's own folder and are reached here. Which
        // half a name is in is the manifest's declaration rather than a verb, so each half refuses the other's
        // names. The tables are the named session's own (079.1), so this answers for a character nobody is
        // looking at exactly as it does for the drawn one — two logins are two folders.
        m.set("store", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "store");
                if(h.storeObj == null)
                    h.storeObj = StoreApi.store(owner, h.user);
                return h.storeObj;
            }
        });
        // chat() — the chat THIS character holds. The chat is a window of one login's HUD, so two characters
        // have two of them: two Party channels, and two private conversations with the same person. A global
        // would have to answer for whichever session is being DRAWN, which is the wrong character's lines
        // rather than a labelling problem — so the address goes in the call, as it does for every other
        // section here. The section object IS the collection of the channels.
        m.set("chat", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "chat");
                if(h.chatObj == null)
                    h.chatObj = ChatApi.chat(owner, h.user);
                return h.chatObj;
            }
        });
        // close() — END this session: the one protected verb a Session carries ("session.close", D-027/D-028),
        // gated as the FIRST statement (D-213). Sessions.Member.drop() closes the Session, so RemoteUI.run
        // unwinds through its own cleanup instead of being torn out from under itself — which is exactly what
        // `:session drop` does, and is why this is protected while taking the screen is not: a logout leaves
        // the client. It is ASYNCHRONOUS: drop() returns before the member leaves the list, so :exists() and
        // SessionRemoved are what answer, a tick or more later. Closing the session ON SCREEN is allowed —
        // relinquish/reclaim hand the screen to another live session, or to the login screen when there is
        // none left. Returns self, so writes chain.
        m.set("close", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                AddonManager.requirePermission(owner, Permission.SESSION_CLOSE);
                LuaSession h = handle(self, "close");
                Sessions.Member mem = Sessions.byuser(h.user);
                if(mem == null)
                    throw new LuaError("session:close(): " + noSession(h.user));
                mem.drop();
                return self;
            }
        });
        // info() — the one SNAPSHOT escape hatch, and always a table: a Session that does not exist is
        // exactly what a handler wants to log, so there is nothing to answer nil with.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "info");
                Sessions.Member mem = Sessions.byuser(h.user);
                LuaTable t = new LuaTable();
                t.set("user", LuaValue.valueOf(h.user));
                GameUI g = (mem == null) ? null : mem.gameui();
                if((g != null) && (g.chrid != null))
                    t.set("character", LuaValue.valueOf(g.chrid));
                t.set("exists", LuaValue.valueOf(mem != null));
                t.set("current", LuaValue.valueOf((mem != null) && (mem == Sessions.anchormember())));
                return t;
            }
        });
        return m;
    }

    // ---- self resolution -------------------------------------------------------------------------

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaSession handle(LuaValue self, String method) {
        LuaSession h = resolve(self);
        if(h == null)
            throw new LuaError("session:" + method + "() — use a COLON call on a Session object"
                + " (hafen.session():current(), hafen.session():get(user), hafen.session():list()[n])");
        return h;
    }

    /** This session's own HUD, or {@code null} while it has none (connecting, on the character list, gone). */
    private static GameUI gameui(LuaSession h) {
        Sessions.Member mem = Sessions.byuser(h.user);
        return (mem == null) ? null : mem.gameui();
    }
}
