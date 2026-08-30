package io.brodgar.addon;

import haven.Coord2d;
import haven.MapView;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

/**
 * <b>What a character has on its cursor</b> — the client's own {@code MapView.Plob}, the translucent building
 * that follows the mouse between the moment the server starts a placement and the click that commits it.
 * {@code s:world():placing()} hands one back, or {@code nil} when that character is placing nothing.
 *
 * <p><b>Why it is not a {@link LuaGob}.</b> A Plob <i>is</i> a {@link haven.Gob} in the engine, but it is not
 * one of the game's objects: it is in no {@link haven.OCache}, so {@link AddonManager#getgob(String, long)}
 * cannot reach it, and every Plob's {@code Gob.id} is {@code -1} — the {@code virtual} sentinel — so there is
 * no id to name it by and {@code LuaGob}, which wraps an id and nothing else, has nothing to wrap. Half of a
 * Gob's vocabulary would be a lie here besides: it has no health, no state bytes, no kin standing in it,
 * nothing to click and no overlay of the game's own. So it is its own kind with its own short vocabulary,
 * which is what lets a refusal name what this thing actually answers.
 *
 * <p><b>It names the cursor, not one placement.</b> There is one handle per addon per session — minted with
 * {@code s:world()} and held beside its collections, so {@code s:world():placing() == s:world():placing()} —
 * and every verb on it re-resolves through {@link AddonManager#placing(String)}. A handle you stash therefore
 * goes on answering about <b>whatever is on that character's cursor</b>, not about the ghost that was there
 * when you took it: put a cabin down and start a barrel, and the same handle reads the barrel.
 * {@code :exists()} is the question "is this character placing anything right now", and it is what every
 * other verb answers {@code nil} for.
 *
 * <p><b>Read-only, and unprotected.</b> Nothing here writes and nothing here reaches the server; committing
 * a placement is {@code s:world():place(p, angle)}, which is protected under {@code world.place} and lives
 * beside this reader in {@link WorldApi}. Reading what is on your own cursor grants nothing that looking at
 * the screen does not.
 *
 * <p><b>Whose cursor.</b> Every session has its own {@code MapView} and the server addresses a placement to
 * one of them, so this is a fact about the character it is asked of rather than about the screen — which is
 * why it reads through {@link AddonManager#view(String)} and not {@code sendView}, and why it answers for a
 * session that is not being drawn. Placing <i>with</i> that character is still the drawn character's own
 * business, and {@code s:world():place} says so.
 *
 * <p><b>Threading.</b> Reads run on the UI thread (addon tick / REPL). Each one re-resolves the Plob and then
 * takes the gob's own monitor for its fields, exactly as {@link LuaGob} does; the Plob is built on the Loader
 * thread, which {@code MapView.addonPlacing()} guards with {@code Future.done()}.
 */
public final class LuaPlacing {
    /** The account whose cursor this handle names — the whole of the handle, as an id is a Gob's. */
    public final String user;

    private LuaPlacing(String user) {
        this.user = user;
    }

    /**
     * {@code tostring(placing)}: {@code Placing(gfx/terobjs/arch/logcabin)}, or the bare {@code Placing} when
     * nothing is on the cursor — a handle that is a picture of nothing prints like one, as {@code Patch} does.
     */
    public String toString() {
        MapView.Plob p = AddonManager.placing(user);
        String n = (p == null) ? null : AddonManager.gobName(p);
        return (n == null) ? "Placing" : ("Placing(" + n + ")");
    }

    /**
     * The Placing handle for {@code (owner, user)} — built <b>once</b>, by {@link WorldApi#world}, which holds
     * it beside that world's collections. There is no intern cache here because there is nothing to key one
     * on: a character has one cursor, so the pair is the whole identity.
     */
    static LuaValue of(Addon owner, String user) {
        return LuaValue.userdataOf(new LuaPlacing(user), buildMeta(owner, user));
    }

    /** Resolve a Lua value back to its {@code LuaPlacing}; {@code null} for anything that is not one. */
    static LuaPlacing resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaPlacing) ? (LuaPlacing)o : null;
    }

    // ---- the Placing metatable ---------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner, final String user) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("placing", methods(owner, user),
            "a Placing is the ghost on this character's cursor, and it only reads"));
        mt.set("__name", LuaValue.valueOf("Placing"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "tostring").toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner, final String user) {
        LuaTable m = new LuaTable();
        // name() — the resource being placed, the same spelling gob:name() answers with. nil while the
        // resource has not resolved, which is a real moment here: the Plob is deferred onto the Loader and
        // the server names it before the client has it.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapView.Plob p = plob(self, "name");
                String n = (p == null) ? null : AddonManager.gobName(p);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // position() — where the ghost is right now, as a Position, so it goes straight into
        // hafen.virtual():patch():add(ring, p) and into p:distance(). It moves with the mouse, so this is
        // read per frame rather than kept.
        m.set("position", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapView.Plob p = plob(self, "position");
                if(p == null)
                    return LuaValue.NIL;
                Coord2d rc;
                synchronized(p) { rc = p.rc; }
                if(rc == null)
                    return LuaValue.NIL;
                return LuaPosition.of(owner, user, rc);
            }
        });
        // facing() — its angle in radians, which the mouse wheel turns. The same verb, the same units and
        // the same zero as gob:facing(): a ghost and the object it becomes are pointed the same way.
        m.set("facing", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapView.Plob p = plob(self, "facing");
                if(p == null)
                    return LuaValue.NIL;
                synchronized(p) {
                    return LuaValue.valueOf(p.a);
                }
            }
        });
        // hitbox() — the ground the finished thing will stand on, in the shape and the units gob:hitbox()
        // answers in: an array of rings, each an array of Positions, turned by the ghost's own facing and
        // placed where it currently sits. So a ring goes into hafen.virtual():patch() unchanged, exactly as a
        // real object's does.
        //   It is the resource's obst/neg footprint and NOT its `build` box — the clearance the client
        // checks before it will let you put the thing down. The two are different questions and
        // gob:hitbox() has always answered the first; this verb answers the same one, so a ghost and the
        // object it becomes wear the same box.
        m.set("hitbox", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return AddonManager.hitboxOf(owner, user, plob(self, "hitbox"));
            }
        });
        // exists() — is this character placing anything at all? The one question the other verbs' nil
        // answers, asked directly, and the liveness test a handle kept across a placement needs.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                handle(self, "exists");
                return LuaValue.valueOf(AddonManager.placing(user) != null);
            }
        });
        // info() — the snapshot every live object in this API carries, keys spelled the way the verbs are.
        //   NO hitbox key, for the reason gob:info() has none: a snapshot holds numbers and strings rather
        // than objects, and a footprint rebuilt on every call would be paid by every read that only wanted
        // the name. Read :hitbox() when you want it. nil when nothing is being placed, so `if pl:info()` is
        // the same question :exists() is.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapView.Plob p = plob(self, "info");
                if(p == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("exists", LuaValue.TRUE);
                String n = AddonManager.gobName(p);
                if(n != null)
                    t.set("name", LuaValue.valueOf(n));
                Coord2d rc;
                double a;
                synchronized(p) {
                    rc = p.rc;
                    a = p.a;
                }
                t.set("facing", LuaValue.valueOf(a));
                if(rc != null) {
                    LuaValue pos = LuaPosition.of(owner, user, rc);
                    if(!pos.isnil()) {
                        LuaValue pi = pos.get("info").call(pos);
                        if(!pi.isnil())
                            t.set("position", pi);
                    }
                }
                return t;
            }
        });
        return m;
    }

    // ---- resolution --------------------------------------------------------------------------------

    /** The live Plob behind a method's {@code self} — re-resolved every call, like every read here. */
    private static MapView.Plob plob(LuaValue self, String method) {
        return AddonManager.placing(handle(self, method).user);
    }

    private static LuaPlacing handle(LuaValue self, String method) {
        LuaPlacing h = resolve(self);
        if(h == null)
            throw new LuaError("placing:" + method + "() — use a COLON call on a Placing object"
                + " (session:world():placing())");
        return h;
    }
}
