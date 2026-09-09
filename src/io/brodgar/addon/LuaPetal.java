package io.brodgar.addon;

import haven.FlowerMenu;

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
 * Petal holds the <b>ring it was minted from</b> and its position on that ring, and asks that ring again on
 * every call, so one held past the close reports {@code :exists()} false rather than acting on a menu that
 * is gone. The position is the 1-based one A-071 made the rule, and it is the {@code 1}&ndash;{@code 9} key
 * the menu is picked with; {@code :wire()} is the 0-based number the menu itself sends.
 *
 * <p><b>The ring is half the identity, not decoration.</b> A Petal that held only {@code (user, index)} read
 * and <i>picked</i> from whichever menu happened to be up when it was asked: a right-click puts a new one up
 * about a second later, and a petal stashed from the last one answered {@code :exists()} true against it and
 * committed that other ring's petal at the same position. So the menu is carried, every verb checks that it
 * is still the one open, and a petal of a closed ring reads nothing and picks nothing.
 */
final class LuaPetal {
    final String user;
    /** The ring this petal is on. Half its identity: a position means nothing without the menu it is on. */
    final FlowerMenu menu;
    /** The 0-based wire position; {@code :index()} answers the 1-based one and {@code :wire()} this one. */
    final int i;

    private LuaPetal(String user, FlowerMenu menu, int i) {
        this.user = user;
        this.menu = menu;
        this.i = i;
    }

    public String toString() {
        return "Petal(" + (i + 1) + ")";
    }

    /**
     * The interned Petal at wire position {@code i} of the ring {@code menu} (audit2 B10, B11). {@code (menu,
     * index)} <b>is</b> its identity — the page calls a petal "an object like every other member of a set
     * here" — so two reads of one petal of one ring are {@code ==}, where they used to be two userdata that
     * no {@code seen[p]} could tell apart.
     *
     * <p>The key spells the ring by its identity hash, which separates the menus a client holds at once —
     * only one is ever open in a tree — and the menu the entry holds is compared as well, so the one reading
     * two rings could ever share is re-minted rather than answered wrong.
     */
    static LuaValue of(final Addon owner, final String user, final FlowerMenu menu, final int i) {
        final String key = user + "@" + Integer.toHexString(System.identityHashCode(menu)) + "@" + i;
        LuaValue v = owner.petals.of(key, () -> LuaValue.userdataOf(new LuaPetal(user, menu, i), meta(owner)));
        LuaPetal h = resolve(v);
        if((h != null) && (h.menu == menu))
            return v;
        owner.petals.drop(key);
        return owner.petals.of(key, () -> LuaValue.userdataOf(new LuaPetal(user, menu, i), meta(owner)));
    }

    static LuaPetal resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaPetal) ? (LuaPetal)o : null;
    }

    /** What a <b>string</b> filter matches: the caption its own ring paints, or nothing once it has closed. */
    static String needle(LuaValue member) {
        return label(resolve(member));
    }

    /**
     * The caption this petal's own ring paints on it, or {@code null} — <b>the one liveness test</b> every
     * verb here goes through. It answers nothing unless the menu the petal was minted from is still the one
     * open on that character, which is what keeps a petal held past the close from reading another ring.
     */
    private static String label(LuaPetal h) {
        return ((h == null) || (FlowerMenuApi.open(h.user) != h.menu))
            ? null : FlowerMenuApi.petalLabel(h.user, h.i);
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
        // label() — the caption its own ring paints on it. nil once that ring has closed.
        m.set("label", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String s = label(handle(self, "label"));
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s);
            }
        });
        // index() — its 1-based place on the ring, and the 1..9 key the menu is picked with. A property of
        // the object, so it answers whether or not the ring is still up.
        m.set("index", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "index").i + 1);
            }
        });
        // wire() — the 0-based number the menu itself sends for this petal (FlowerMenu.Petal.num), which is
        // NOT :index(): the wire counts from zero here and this API counts from one, so the server's own
        // number gets its own verb rather than one verb quietly meaning two things.
        m.set("wire", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "wire").i);
            }
        });
        // exists() — is THIS petal's ring still the open one? False the moment that menu closes, whatever
        // has gone up since.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(label(handle(self, "exists")) != null);
            }
        });
        // select() — the PROTECTED pick, needing no re-spelling of the caption. Same key as the section's.
        m.set("select", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue me = a.arg1();
                LuaPetal h = handle(me, "select");
                AddonManager.requirePermission(AddonManager.current(), Permission.FLOWERMENU_SELECT);
                if(Args.passed(a, 2))
                    throw new LuaError("petal:select() takes no arguments — it picks THIS petal, which is"
                        + " what holding one is for; session:flowermenu():select(label|n) is the other door");
                // ...on ITS OWN ring, which is what carrying one is for. A menu lives about a second, so a
                // petal held a moment too long would otherwise commit whatever the next ring put at the
                // same position — a pick the caller never asked for and cannot see.
                if(FlowerMenuApi.open(h.user) != h.menu)
                    throw new LuaError("petal:select(): the ring this petal is on has closed — petal:exists()"
                        + " is the test, and session:flowermenu():list() is what is open now");
                FlowerMenuApi.selectPetal(owner, h.user, h.menu, h.i);
                return me;
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaPetal h = handle(self, "info");
                String s = label(h);
                LuaTable t = new LuaTable();
                t.set("index", LuaValue.valueOf(h.i + 1));
                t.set("wire", LuaValue.valueOf(h.i));
                if(s != null)
                    t.set("label", LuaValue.valueOf(s));
                return t;
            }
        });
        return m;
    }
}
