package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * One <b>petal</b> of an open radial menu (091, A-078).
 *
 * <p><b>Why it is an object.</b> {@code s:flowermenu():list()} was the only {@code :list()} in the API whose
 * members could not be passed back to anything: to pick one you re-spelled its caption or counted its
 * position, where {@code s:speed():set(sp)} and {@code hafen.virtual():ghost():remove(g)} both take the member.
 * The page defended it &mdash; the ring is fixed the instant it opens and lives about a second, so there is
 * nothing for a handle to track. The counter-evidence is that a {@code Buff} and a {@code Craft} are both
 * objects and both shorter-lived than a menu: "too short-lived for a handle" is not a rule this API keeps.
 *
 * <p><b>It re-resolves, so the lifetime objection is answered the way the API answers it everywhere.</b> A
 * Petal wraps {@code (user, index)} and asks the open menu again on every call, so one held past the close
 * reports {@code :exists()} false rather than acting on a ring that is gone. The position <b>is</b> its
 * identity here &mdash; it is the number sent on the wire and the {@code 1}&ndash;{@code 9} key &mdash; and
 * it is the 1-based one A-071 made the rule.
 */
final class LuaPetal {
    private final String user;
    /** The 0-based wire position; {@code :index()} answers the 1-based one. */
    private final int i;

    private LuaPetal(String user, int i) {
        this.user = user;
        this.i = i;
    }

    public String toString() {
        return "Petal(" + (i + 1) + ")";
    }

    static LuaValue of(Addon owner, String user, int i) {
        return LuaValue.userdataOf(new LuaPetal(user, i), meta(owner));
    }

    static LuaPetal resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaPetal) ? (LuaPetal)o : null;
    }

    /** What a <b>string</b> filter matches: the caption the ring paints. */
    static String needle(LuaValue member) {
        LuaPetal h = resolve(member);
        return (h == null) ? null : FlowerMenuApi.petalLabel(h.user, h.i);
    }

    private static LuaPetal handle(LuaValue self, String method) {
        LuaPetal h = resolve(self);
        if(h == null)
            throw new LuaError("petal:" + method + "() — use a COLON call on a Petal"
                + " (session:flowermenu():list()[n], or :find(label))");
        return h;
    }

    private static LuaValue meta(final Addon owner) {
        if(owner.petalMeta != null)
            return owner.petalMeta;
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("petal", methods(owner),
            "one petal of the radial menu",
            ":select() picks it"));
        mt.set("__name", LuaValue.valueOf("Petal"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaPetal h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Petal(?)" : h.toString());
            }
        });
        owner.petalMeta = mt;
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // label() — the caption the ring paints on it. nil once the menu is gone.
        m.set("label", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaPetal h = handle(self, "label");
                String s = FlowerMenuApi.petalLabel(h.user, h.i);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s);
            }
        });
        // index() — its 1-based place on the ring, which is the number the wire carries and the 1..9 key.
        m.set("index", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "index").i + 1);
            }
        });
        // exists() — is this petal still on an open ring? False the moment the menu closes.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaPetal h = handle(self, "exists");
                return LuaValue.valueOf(FlowerMenuApi.petalLabel(h.user, h.i) != null);
            }
        });
        // select() — the PROTECTED pick, needing no re-spelling of the caption. Same key as the section's.
        m.set("select", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue me = a.arg1();
                LuaPetal h = handle(me, "select");
                AddonManager.requirePermission(owner, Permission.FLOWERMENU_SELECT);
                if(Args.passed(a, 2))
                    throw new LuaError("petal:select() takes no arguments — it picks THIS petal, which is"
                        + " what holding one is for; session:flowermenu():select(label|n) is the other door");
                FlowerMenuApi.selectPetal(owner, h.user, h.i);
                return me;
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaPetal h = handle(self, "info");
                String s = FlowerMenuApi.petalLabel(h.user, h.i);
                LuaTable t = new LuaTable();
                t.set("index", LuaValue.valueOf(h.i + 1));
                if(s != null)
                    t.set("label", LuaValue.valueOf(s));
                return t;
            }
        });
        return m;
    }
}
