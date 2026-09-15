package io.brodgar.addon;

import haven.HackThread;
import io.brodgar.session.Control;
import io.brodgar.session.Sessions;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code hafen.session()} — <b>the logins this client holds, and the address a character is named by</b>
 * (spec {@code 076-the-session-is-the-address}). The client can hold several sessions at once and draws one
 * of them, so "the character" is a question with more than one answer, and this is where an addon says
 * which one it means.
 *
 * <p><b>The section object IS the collection</b> (uniform grammar §2.1 — a section containing exactly one
 * thing <i>is</i> that thing): {@code :list}/{@code :count}/{@code :find} enumerate the membership,
 * {@code :get(user)} addresses one by its account name, and {@code :current()} is the distinguished member —
 * the session on screen, which is what {@code Sessions.anchormember()} answers and {@code nil} on the login
 * screen. The members are {@link LuaSession} refs, interned per addon.
 *
 * <p><b>{@code :get} always hands back an object</b>, the deliberate asymmetry {@code s:world():gob():get(id)}
 * and {@code hafen.kin():get(id)} already have: the account name is the whole of the ref, so a name read out
 * of a saved file can be held before that account logs in and after it goes, and {@code :exists()} is the
 * liveness test. There is no miss to report.
 *
 * <p><b>{@code :current(s)} is the screen, read and written</b> (081.1). Arity is the verb, and it sits on
 * the collection because there is one screen however many logins there are: {@code :current()} answers which
 * session holds it and {@code :current(s)} hands it to {@code s}. The write goes through
 * {@link io.brodgar.session.Control#take} rather than {@link Sessions#anchor}, so the screen, the RTS
 * selection and the camera move together — the same one gesture {@code :session anchor} and every way the
 * switcher window offers spell.
 *
 * <p><b>And {@code :current(nil)} is the login screen</b>, the write that answers the read's own nil: the
 * client's own login screen is live behind every session, so handing it the screen is how an account with
 * <b>no saved token</b> is logged in — the door {@code :session add} has not got, and what the switcher
 * window's <i>New session</i> button presses.
 *
 * <p><b>It is unprotected.</b> The protected tier is for an action whose effect leaves the client; taking the
 * screen changes which widget tree is drawn and nothing else, and the server is never told. A consent line
 * for a redraw would make the word mean nothing.
 *
 * <p><b>{@code :remove(s)} ends a login</b> (087.3, <b>D2</b>): where a collection exists, the verb that
 * destroys a member is the collection's, and this one was the exception. It is the same act
 * {@link LuaSession}'s {@code :close()} performs and carries the same {@code session.close} key, gated as
 * the first statement of {@code removeMember} (D-213) and giving the same refusal for an account the client
 * holds no session for. {@code :close()} stays beside it: ending one login reads best on the login.
 *
 * <p><b>{@code :add(user)} starts a login, and {@code :saved()} names the accounts it can start one for.</b>
 * The collection holds the logins, so beginning one is its verb as ending one is, and it is the same act
 * {@code :session add USER} performs: {@link Sessions#add} with the token the login screen saved for that
 * account, on a thread of its own, because authentication and the session handshake are two blocking
 * network round-trips and the client must keep drawing through them. The verb hands back the account's
 * Session object at once — the one {@code :get(user)} mints — and the connection answers through it:
 * {@code :exists()} turns true and {@code SessionAdded} fires once the server has taken the login,
 * {@code SessionEnteredWorld} once the character is in the world, and a login that fails is said on the
 * addon's own log line and leaves the object as {@code :get(user)} would, {@code :exists()} false. It
 * carries the {@code session.add} key, gated as the first statement (D-213): a login leaves the client
 * exactly as the logout behind {@code session.close} does. {@code :saved()} is <b>names and nothing
 * else</b>: {@link Sessions#savedusers} reads the list the login screen keeps beside its tokens and never a
 * token, so no verb here can hand one to Lua.
 *
 * <p><b>{@code :forget(user)} drops a remembered login</b> — the login screen's own <i>Forget me</i> button,
 * {@link Sessions#forgetuser}: the token goes and the name leaves {@code :saved()}. It is named with the
 * client's word, as {@code kin:forget()} is, and it is not {@code :remove}: {@code :remove(s)} ends a
 * <i>login</i>, and {@code :saved()} is an array of names rather than a collection with members to remove.
 * Client-local — the auth server is never told and a session the account has open is untouched — and
 * protected all the same, behind {@code session.forget}, for what it destroys: a credential the player
 * saved, which no server restores and which costs them a password to earn back.
 */
public final class SessionApi {
    private SessionApi() {
    }

    /**
     * Build {@code hafen.session()} for {@code owner}. From {@code installHafen}, once per addon: the
     * collection object is minted here and handed back by identity, so {@code hafen.session()} allocates
     * nothing and {@code hafen.session() == hafen.session()}.
     */
    static void installSession(LuaTable hafen, final Addon owner) {
        LuaTable extra = new LuaTable();
        // current() / current(s) — the session on screen, read and written. A distinguished member is a verb
        // on its collection rather than a second accessor (§2.3), and this is the one an addon reaches for
        // when it means "the character the player is looking at" — which changes under a stored variable, so
        // it is taken inside the handler rather than kept. The write is on the collection for the same reason
        // the read is: there is ONE screen however many logins the client holds, so it is not a property each
        // member carries.
        extra.set("current", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue me = a.arg1();
                LuaCollection.receiver(me, "hafen.session()", "current");
                if(!Args.passed(a, 2)) {                   // the read arity
                    Sessions.Member m = Sessions.anchormember();
                    return (m == null) ? LuaValue.NIL : LuaSession.of(owner, m.user);
                }
                LuaValue want = a.arg(2);
                // THE LOGIN SCREEN (:current(nil)). The explicit nil has a meaning here rather than raising,
                // the way mouse():cursor(nil) does: it is the write that answers the read's own nil, so the
                // two arities spell one property between them and there is nothing the screen can hold that
                // this cannot name. It is also the only door to an account with NO SAVED TOKEN -- the client's
                // own login screen is live behind every session, and logging in there hands the client another
                // session like any other -- which is what the switcher window's "New session" button presses.
                //   It fires no SessionSelected: that family's payload IS a session, and no session was
                // picked. hafen.session():current() reads nil while the login screen holds the screen.
                if(want.isnil()) {
                    Control.take(null);
                    return me;
                }
                // The write. Control.take and never Sessions.anchor: the screen, the selection and the
                // camera are one gesture, and a switch that left the previous character selected would send
                // the next order to somebody off screen. Naming the session already drawn is a no-op inside
                // anchor(), so it fires no SessionSelected -- which is what the bus already promises about
                // tabbing, said once for both ways of arriving there.
                LuaSession h = LuaSession.resolve(want);
                if(h == null)
                    throw new LuaError("hafen.session():current(session): session must be a Session object"
                        + " — what hafen.session():get(user), :find(filter) and :list() hand back"
                        + " (got a " + want.typename() + "). hafen.session():current(nil) goes to the login"
                        + " screen, and hafen.session():current() reads who has it");
                Sessions.Member m = Sessions.byuser(h.user);
                if(m == null)
                    throw new LuaError("hafen.session():current(session): the client holds no session for"
                        + " the account '" + h.user + "' — s:exists() is the test, and there is no"
                        + " screen to give a session that is not logged in");
                // A SESSION WITH NO SCREEN OF ITS OWN (084.5). Sessions.anchor says so on the console and
                // returns, so without this refusal the write would report nothing and fire nothing -- a
                // silence an addon could only find by reading :current() back and comparing. It is NOT
                // :exists(): the member is in the list, its Session is live and it is answering the server.
                // It is the gap Sessions.Member.run leaves, where `ui` is cleared before the outgoing UI is
                // taken down and the incoming one has not been built -- a character handoff, and the beat
                // after a login is registered. ASKED OF Control.take (audit2 B06), which is the only place
                // the answer is not already stale: `ui` is cleared on the session's own thread, so a field
                // read here and an act on the next line are two different instants.
                if(!Control.take(m))
                    throw new LuaError("hafen.session():current(session): the account '" + h.user + "' has no"
                        + " screen of its own yet — it is still arriving, or between the character it left"
                        + " and the one it is taking — so there is nothing to hand the screen to and no"
                        + " SessionSelected would follow. Its SessionEnteredWorld is the moment it has one:"
                        + " write the screen from there, or read hafen.session():current() to see who holds"
                        + " it now");
                return me;
            }
        });
        // saved() — the accounts the login screen remembered, as a plain array of account names, which is what
        // :add(user) can log in. NAMES AND NOTHING ELSE: Sessions.savedusers() reads the list the login screen
        // keeps beside its tokens (saved-tokens@host), and the token itself is a separate pref this layer never
        // reads, so there is no verb through which one reaches Lua. A plain array rather than a collection,
        // as hafen.asset():files() is: a saved account is a string, not an object with verbs of its own -- the
        // object is the Session, which :add(user) and :get(user) mint. Empty, never nil, when none is saved.
        extra.set("saved", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "hafen.session()", "saved");
                Args.only(a, 0, "hafen.session():saved");
                LuaTable t = new LuaTable();
                int n = 0;
                for(String user : Sessions.savedusers())
                    t.set(++n, LuaValue.valueOf(user));
                return t;
            }
        });
        // forget(user) — DROP a remembered login: the login screen's own "Forget me" button (LoginScreen.forget,
        // which is Bootstrap.settoken(user, confname, null)), reached through Sessions.forgetuser so the one
        // place that knows which auth server the list is kept under stays beside savedusers(). The token goes
        // and the name leaves :saved(), so the account's next login is by password and the next :add(user)
        // refuses. Client-local -- the auth server is never told, and a session the account has open is
        // untouched -- and protected all the same, for what it destroys (map.marker's reason): a credential
        // the player saved, which no server restores. The gate comes before the argument is looked at
        // (D-213), and an account with nothing saved is refused rather than silently forgotten twice.
        // Synchronous: two pref writes, so :saved() reads the removal back at once. Hands the collection
        // back, as :remove(s) does, so it chains.
        extra.set("forget", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue me = a.arg1();
                LuaCollection.receiver(me, "hafen.session()", "forget");
                AddonManager.requirePermission(AddonManager.current(), Permission.SESSION_FORGET);
                Args.only(a, 1, "hafen.session():forget");
                LuaValue v = Args.str(a, 2, "hafen.session():forget", "user",
                                      "the ACCOUNT name, as the login screen knows it");
                String user = v.tojstring();
                if(user.isEmpty())
                    throw new LuaError("hafen.session():forget(user): user must not be empty (the ACCOUNT"
                        + " name, as the login screen knows it)");
                if(!Sessions.savedusers().contains(user))
                    throw new LuaError("hafen.session():forget(user): the client has no saved login for the"
                        + " account '" + user + "' — hafen.session():saved() lists the accounts it has one"
                        + " for, and there is nothing to forget");
                Sessions.forgetuser(user);
                return me;
            }
        });
        Section.mount(hafen, "session", LuaCollection.create("hafen.session()", new LuaCollection.Source() {
            /* The membership in the order it was joined, which is the order `:session list` prints. A copy,
             * so a handler that drops a session mid-iteration does not walk the list it is changing. */
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(Sessions.Member m : Sessions.members())
                    out.add(LuaSession.of(owner, m.user));
                return out;
            }

            /* A string filter matches the ACCOUNT, never the character: the account is what addresses a
             * session, and the character it is playing changes under the same name. */
            public String needle(LuaValue member) {
                LuaSession h = LuaSession.resolve(member);
                return (h == null) ? null : h.user;
            }

            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public String keyName() {
                return "user";
            }

            /* TSTRING and not isstring(): in LuaJ a number IS a string and a numeric string IS a number, so
             * the type is the only test that both refuses 42 and accepts an account literally called "42". */
            public LuaValue getMember(LuaValue key) {
                if(key.type() != LuaValue.TSTRING)
                    throw new LuaError("hafen.session():get(user): user must be a string — the ACCOUNT name"
                        + " the session logged in as, not the character it is playing");
                return LuaSession.of(owner, key.tojstring());
            }

            /** Any account name is addressable, logged in or not: s:exists() is the question. */
            public LuaCollection.Missing missing() {
                return LuaCollection.Missing.MINT;
            }

            /** The collection holds the logins, so starting one is <b>its</b> verb, as ending one is. */
            public boolean creatable() {
                return true;
            }

            /*
             * add(user) -- START a login for an account the login screen remembered, which is the same act
             * `:session add USER` performs, and hand back its Session object at once.
             *
             * The gate is the FIRST statement (D-213), before the argument is looked at, for the reason
             * remove(s) gives: a refusal that ran the checks first would tell an addon which accounts this
             * client remembers, and a caller who forgot the key has one thing to fix rather than two.
             *
             * The two refusals a caller can act on are given HERE, on the calling thread, where they can be
             * caught: no saved login for that account (hafen.session():saved() is the list), and a session the
             * client already holds for it (one account is one session, the rule Sessions.add keeps). What
             * only the network can answer -- a rejected token, an unreachable server, a second add of the
             * same account that is still connecting -- comes back on the connect thread and is said on the
             * addon's own log line, exactly as the console command says it on the anchor's notice.
             *
             * OFF THE UI THREAD, on the console command's own kind of thread: Sessions.add is authentication
             * and the session handshake, two blocking network round-trips, and the client must keep drawing
             * through them. So the verb is asynchronous, exactly as remove(s) is: it returns before the member
             * joins the list, and :exists(), SessionAdded and SessionEnteredWorld are what answer, on later
             * ticks. The Session handed back is the one :get(user) mints -- the account name is the whole of
             * the ref -- so an addon holds it through the connection rather than looking it up afterwards.
             *
             * No character is named: Sessions.add(user, null) plays whichever the server offers first, which
             * is the one door the verb has -- picking a character is what the login screen's list is for.
             */
            public LuaValue addMember(Varargs a) {
                AddonManager.requirePermission(AddonManager.current(), Permission.SESSION_ADD);
                Args.only(a, 1, "hafen.session():add");
                // The type, not isstring(): a NUMBER answers isstring() in LuaJ, and an account literally
                // called "42" is a string the login screen remembers like any other.
                LuaValue v = Args.str(a, 2, "hafen.session():add", "user",
                                      "the ACCOUNT name, as the login screen knows it");
                final String user = v.tojstring();
                if(user.isEmpty())
                    throw new LuaError("hafen.session():add(user): user must not be empty (the ACCOUNT name,"
                        + " as the login screen knows it)");
                if(!Sessions.savedusers().contains(user))
                    throw new LuaError("hafen.session():add(user): the client has no saved login for the"
                        + " account '" + user + "' — hafen.session():saved() lists the accounts it has one for,"
                        + " and logging an account in once on the login screen with \"Remember me\" ticked is"
                        + " what saves one");
                if(Sessions.byuser(user) != null)
                    throw new LuaError("hafen.session():add(user): the client already holds a session for the"
                        + " account '" + user + "' — one account is one session, and hafen.session():get(user)"
                        + " is that one");
                new HackThread(new Runnable() {
                    public void run() {
                        try {
                            Sessions.add(user, null);
                        } catch(Exception e) {
                            AddonManager.log(owner, "hafen.session():add(\"" + user + "\") failed: "
                                             + e.getMessage());
                        }
                    }
                }, "session-connect").start();
                return LuaSession.of(owner, user);
            }

            /**
             * The collection holds the logins, so ending one is <b>its</b> verb (087.3, D2) — the rule every
             * other collection that can destroy a member already keeps.
             */
            public boolean destroyable() {
                return true;
            }

            /*
             * remove(s) -- END that login, which is the same act s:close() performs on the member and is
             * gated by the same key. Both doors stay: the collection's is where a reader of any other
             * collection looks for it, and the member's is where ending ONE login reads best.
             *
             * The gate is the FIRST statement (D-213), before the argument is even looked at: a refusal that
             * ran the resolution first would tell an addon which accounts this client holds, and a caller
             * who forgot the key has one thing to fix rather than two reported one at a time.
             *
             * A STRING is refused rather than taken as the key. hafen.session():get(user) mints a Session
             * for any name, so a string here would be an ending whose receiver was never checked against
             * anything -- and every other use site of a session takes the object.
             *
             * ASYNCHRONOUS, exactly as s:close() is: drop() returns before the member leaves the list, so
             * :exists() and SessionRemoved are what answer, a tick or more later. LuaCollection hands the
             * COLLECTION back, so removals chain.
             */
            public void removeMember(LuaValue x) {
                AddonManager.requirePermission(AddonManager.current(), Permission.SESSION_CLOSE,
                                              "hafen.session():remove(s)");
                LuaSession h = LuaSession.resolve(x);
                if(h == null)
                    throw new LuaError("hafen.session():remove(s): s must be a Session object — what"
                        + " hafen.session():get(user), :find(filter) and :list() hand back. An account name"
                        + " is not the key here: hafen.session():remove(hafen.session():get(\"alice\")) is"
                        + " the call (got a " + x.typename() + ")");
                Sessions.Member m = Sessions.byuser(h.user);
                if(m == null)
                    throw new LuaError("hafen.session():remove(s): " + LuaSession.noSession(h.user));
                m.drop();
            }
        }, extra), null);
    }
}
