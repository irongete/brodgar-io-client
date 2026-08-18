package io.brodgar.addon;

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
 * <p><b>{@code :current()} reads and never writes.</b> Taking the screen is a gesture of the player's, not
 * an addon's, so the verb refuses an argument rather than swallowing one — arity is the verb everywhere else
 * in this API, and a silently ignored argument is the one way it stops being.
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
        // current() — the session on screen, or nil on the login screen. A distinguished member is a verb on
        // its collection rather than a second accessor (§2.3), and this is the one an addon reaches for when
        // it means "the character the player is looking at" — which changes under a stored variable, so it is
        // taken inside the handler rather than kept.
        extra.set("current", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "current");
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.session():current(…) takes no arguments: it READS which"
                        + " session is on screen. Taking the screen is the player's own gesture.");
                Sessions.Member m = Sessions.anchormember();
                return (m == null) ? LuaValue.NIL : LuaSession.of(owner, m.user);
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
        }, extra), null);
    }
}
