package io.brodgar.addon;

import haven.Coord;
import haven.Coord2d;
import haven.GItem;
import haven.GameUI;
import haven.Gob;
import haven.MapView;
import haven.OCache;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * The <b>Hand</b> — {@code hafen.player():hand()}, the cursor you are carrying something on (048.2), and
 * {@code nil} whenever you are not ({@code GameUI.vhand == null}):
 *
 * <pre>
 *   local h = hafen.player():hand()
 *   if h then
 *     h:item()                     -- the Item on the cursor (every ordinary Item read answers on it)
 *     h:use(target [, mods])       -- PROTECTED: apply what you are holding to an Item, a Position or a Gob
 *   end
 * </pre>
 *
 * <p><b>Why the cursor is an object and not just its Item.</b> The grammar's rule is that <i>a thing which
 * holds exactly one thing IS that thing</i>, and the hand escapes it because it carries a verb of its own that
 * cannot live on Item. {@code itemact} is dispatched through {@link haven.DTarget.Interact}, an
 * {@code ItemEvent} whose {@code src} <b>is</b> the {@code ItemDrag}: in the client the gesture originates
 * from the held item and the message carries no reference to it, so the same bytes on the wire mean <i>use
 * whatever is on the cursor</i>. Put {@code use} on an arbitrary Item and {@code someInventoryItem:use(p)}
 * would send an action about something else entirely.
 *
 * <p><b>And {@code nil} on an empty cursor is the capability, not a formality.</b> {@code if h then h:use(x)
 * end} is a guard no caller could write before: the two verbs this replaces ({@code hafen.act():useItemOn} and
 * {@code hafen.act():item(x, "itemact")}) both fired the held-item gesture with <b>nothing held</b> — a
 * message the client itself cannot produce, sent blind into a state that has no meaning. A Hand held across a
 * drop refuses for the same reason: the read ({@code :item()}) answers nil, and the write RAISES (D-217).
 *
 * <p><b>{@code target} dispatches by TYPE</b>, and the three arms are the three the client has: another Item
 * ({@code GItem "itemact"} with the modifiers, exactly {@link haven.WItem#iteminteract}), a Position (the
 * MapView {@code "itemact"} on bare ground) and a Gob (the same, <b>extended with
 * {@link haven.ClickData#clickargs}</b> as {@link haven.MapView#iteminteract} does when the hit resolves to an
 * object). That third arm is the one message no addon could send before this task: <i>apply the waterskin to
 * that plant</i>.
 *
 * <p><b>{@code :use()} with no target RAISES</b>, naming the three, and never falls back to <i>activate the
 * held item</i>. That reading is a plausible author guess with no message behind it — every held-item action
 * in the protocol targets something — and {@code hafen.player():hand():item():use()} is the one way to say it.
 *
 * <p><b>A per-addon singleton</b> (cached on {@link Addon#handObj}), the same shape {@link LuaMouse} and the
 * Player object have: there is exactly one cursor, the object wraps no engine value at all, and every verb
 * re-reads {@code GameUI.vhand} on the call — so {@code hafen.player():hand() == hafen.player():hand()} and
 * there is nothing to go stale or tear down. Userdata with a per-addon metatable, immutable from Lua.
 */
final class LuaHand {
    private LuaHand() {
    }

    /** The one spelling every refusal names, so a caller reads the string it has to fix. */
    private static final String USE = "hafen.player():hand():use";

    /** The opaque instance behind a Hand userdata (facade-safe: no Java object of the engine's crosses). */
    private static final class HandMark {
        public String toString() { return "Hand"; }
    }

    /**
     * {@code hafen.player():hand()} — this addon's one Hand object while something is on the cursor, and
     * {@code nil} while nothing is. Minted lazily and cached, so the object is {@code ==} itself across
     * takes; what changes between them is only what {@link #held()} answers.
     */
    static LuaValue of(final Addon owner) {
        if(held() == null)
            return LuaValue.NIL;
        if(owner.handObj == null)
            owner.handObj = LuaValue.userdataOf(new HandMark(), buildMeta(owner));
        return owner.handObj;
    }

    /** The item widget on the cursor, or {@code null} when the cursor is empty (or the HUD is not up). */
    private static GItem held() {
        GameUI g = AddonManager.gui();
        return ((g == null) || (g.vhand == null)) ? null : g.vhand.item;
    }

    // ---- the metatable ---------------------------------------------------------------------------

    /** The per-addon metatable: {@code __index} = the methods table, plus {@code __tostring}/{@code __name}. */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("hand", methods(owner)));
        mt.set("__name", LuaValue.valueOf("Hand"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf("Hand");
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // item() — the Item on the cursor, interned exactly as a container's :items() members are, so every
        // ordinary Item read answers on it. nil once the cursor is empty: a Hand held across a drop is not a
        // stale object, it is a cursor that is now carrying nothing.
        m.set("item", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                handle(self, "item");
                GItem it = held();
                return (it == null) ? LuaValue.NIL : LuaItem.of(owner, it);
            }
        });
        // use(target [, mods]) — apply what you are holding TO something. mods optional (0 default; Shift=1
        // Ctrl=2 Alt=4), and unlike the verb this replaces the modifiers are yours to state rather than
        // hardcoded 0. PROTECTED by the per-addon "player.hand.use" permission, and the gate runs FIRST — before the
        // target is looked at and before the cursor is (D-213). Hands the Hand back, so a use chains.
        m.set("use", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                AddonManager.requirePermission(owner, Permission.PLAYER_HAND_USE);
                handle(self, "use");
                if(!Args.passed(a, 2) || a.arg(2).isnil())
                    throw new LuaError(USE + "(target, mods): target is required — you apply what you are"
                        + " holding TO something: an Item, a Position or a Gob. There is no message for"
                        + " \"use the held item on nothing\", and this is not how you activate what you"
                        + " hold — that is hafen.player():hand():item():use().");
                LuaValue target = a.arg(2);
                int mods = a.arg(3).optint(0);
                if(held() == null)
                    throw new LuaError(USE + ": nothing is on the cursor — hafen.player():hand() is nil while"
                        + " it is empty, so read it again rather than holding a Hand across a drop."
                        + " Nothing was sent.");
                // (a) another ITEM: the GItem's own "itemact", exactly what WItem.iteminteract sends.
                LuaItem ti = LuaItem.resolve(target);
                if(ti != null) {
                    GItem g = LuaItem.live(ti);
                    if(g == null)
                        throw new LuaError(USE + ": the target item is gone — it was moved, used or consumed"
                            + " (item:exists() is false). Nothing was sent: an item that has left is not the"
                            + " item that took its place. Re-read the container and retry.");
                    g.wdgmsg("itemact", itemArgs(mods));
                    return self;
                }
                // (b) a GOB: the MapView "itemact" EXTENDED with the object's own click args — the one
                // message no addon could send before 048.2 (MapView.iteminteract does it when the hit
                // resolves to an object; act():useItemOn could only ever aim at bare ground).
                LuaGob tg = LuaGob.resolve(target);
                if(tg != null) {
                    MapView mv = view();
                    Gob gb = AddonManager.getgob(tg.id);
                    if(gb == null)
                        throw new LuaError(USE + ": that gob is gone — it left view or despawned"
                            + " (gob:exists() is false). Nothing was sent.");
                    Coord2d grc;
                    synchronized(gb) { grc = gb.rc; }   // OCache discipline: copy under the gob lock
                    if(grc == null)
                        throw new LuaError(USE + ": that gob has no position yet");
                    mv.wdgmsg("itemact", gobArgs(pc(mv), mods, (int)gb.id, grc.floor(OCache.posres)));
                    return self;
                }
                // (c) a POSITION: the MapView "itemact" on bare ground.
                if(LuaPosition.resolve(target) != null) {
                    Coord2d rc = LuaPosition.worldArg(a, 2, USE, "target");
                    MapView mv = view();
                    mv.wdgmsg("itemact", groundArgs(pc(mv), rc.floor(OCache.posres), mods));
                    return self;
                }
                throw new LuaError(USE + "(target, mods): target must be an Item (a member of a container's"
                    + " :items()), a Position (gob:position(), hafen.world():position(x, y)) or a Gob"
                    + " (hafen.world():gob():nearest(...)), got " + target.typename());
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot call passes the wrong self). */
    private static HandMark handle(LuaValue self, String method) {
        if((self != null) && self.isuserdata()) {
            Object o = self.touserdata();
            if(o instanceof HandMark)
                return (HandMark)o;
        }
        throw new LuaError("hand:" + method + "() — use a COLON call on a Hand object"
            + " (hafen.player():hand(), which is nil while nothing is on the cursor)");
    }

    /** The live map view, or a refusal naming the verb — every world-aimed arm needs one. */
    private static MapView view() {
        MapView m = AddonManager.view;
        if(m == null)
            throw new LuaError(USE + ": no map view (not in the world yet)");
        return m;
    }

    /** The screen coord an {@code "itemact"} carries: the current mouse, a dummy for a programmatic action. */
    private static Coord pc(MapView m) {
        return (m.ui != null) ? m.ui.mc : Coord.z;
    }

    // ---- the three wire shapes, pure so they are testable without a session ------------------------

    /** The {@link GItem} {@code "itemact"} args ({@code {mods}}) — apply the held item onto that item. */
    static Object[] itemArgs(int mods) {
        return new Object[] {mods};
    }

    /** The MapView {@code "itemact"} args for bare GROUND ({@code {pc, mc, mods}}). */
    static Object[] groundArgs(Coord pc, Coord mc, int mods) {
        return new Object[] {pc, mc, mods};
    }

    /**
     * {@link #groundArgs} extended with {@link haven.Gob.GobClick#clickargs}' {@code {0, gobid, gobrc, 0, -1}}
     * (no overlay, no specific sub-mesh) — the whole object, which is what a programmatic aim can honestly
     * claim. {@code mc} is the gob's own floored position, as a hit landing on its base would carry.
     */
    static Object[] gobArgs(Coord pc, int mods, int gobId, Coord gobRc) {
        return new Object[] {pc, gobRc, mods, 0, gobId, gobRc, 0, -1};
    }
}
