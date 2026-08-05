package io.brodgar.addon;

import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.Coord3f;
import haven.Drawable;
import haven.Glob;
import haven.Gob;
import haven.Indir;
import haven.Loading;
import haven.MapView;
import haven.Message;
import haven.MessageBuf;
import haven.MCache;
import haven.Moving;
import haven.Music;
import haven.ResDrawable;
import haven.Resource;
import haven.SprDrawable;
import haven.UI;
import haven.Widget;
import haven.render.RenderTree;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.List;

import static io.brodgar.addon.AddonManager.*;

/**
 * The custom-rendering subsystem: {@code hafen.ghost()} (client-only {@code .res} props) and
 * {@code hafen.render()} ({@code :sprite()}/{@code :object()} — custom PNG + glTF props standing in the 3D
 * world). Owns the world-entity lifecycle (create/transform/follow/click/teardown) over {@link LuaWorldEntity}.
 * The click seam {@code onGhostClick} (called from {@code haven.MapView}) stays a facade in
 * {@link AddonManager} and delegates here; {@link AddonManager} calls the per-kind teardowns on reload/disable.
 *
 * <p><b>Three collections, one entity vocabulary.</b> Each kind is a {@link LuaCollection} — {@code :add(asset)}
 * or {@code :add(res)} places one and hands back its handle, {@code :list(filter)} reads them, and
 * {@code :remove(x)} ends one (R7: the collection placed it, so the collection ends it). Every handle then
 * speaks the same verbs, each a read/write pair on one name: {@code :position(p [, a])}, {@code :rotate}, {@code
 * :scale}, {@code :alpha}, {@code :tint}, {@code :visible}, {@code :clickable}, {@code :onClick} — plus the one
 * or two its own kind adds. The old {@code {image=, x=, y=, …}} constructors are retired rows naming them.
 *
 * <p><b>This is a SCENE namespace, not a loader</b> (028.1): {@code hafen.render.image}/{@code model} are cut,
 * and the addon's own files — images, fonts and meshes alike — come from the one door {@link AssetApi}
 * ({@code hafen.asset():get(path)}), which also owns the D-017 sandbox resolver and the intern cache. An entity
 * that backs a {@code gob:overlay()} record is built through the same {@code make*} bodies but is never a member
 * of these collections (038.2): an overlay is reached through the gob it is on. Not instantiable.
 */
final class RenderApi {
    private RenderApi() {}

    /**
     * Build {@code hafen.ghost} for {@code owner}: <b>the section object IS the collection</b> (spec §2.1 — a
     * section that contains exactly one thing is that thing). {@code hafen.ghost():add(res)} places a client-only
     * {@code .res} prop and hands back the Ghost, {@code :list(filter)} reads this addon's, and
     * {@code hafen.ghost():remove(g)} ends one — the collection owns it, so R7 puts the ending there rather than
     * on a {@code :destroy()} of its own.
     */
    static void installGhost(LuaTable hafen, final Addon owner) {
        Section.mount(hafen, "ghost", ghostCollection(owner), null);
    }

    /** {@code hafen.ghost()} — this addon's client-only world props, keyless (a ghost has no name of its own). */
    private static LuaValue ghostCollection(final Addon owner) {
        return LuaCollection.create("hafen.ghost()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                return entityMembers(owner.ghosts);
            }

            public String needle(LuaValue member) {
                return visualNameOf(owner.ghosts, member);
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean creatable() {
                return true;
            }

            public LuaValue addMember(Varargs a) {
                LuaValue rv = Args.required(a, 2, "hafen.ghost():add", "res");
                if(!rv.isstring() || rv.isnumber())
                    throw new LuaError("hafen.ghost():add(res, p) expects a resource NAME string (e.g."
                        + " \"gfx/terobjs/arch/logcabin\"), got " + rv.typename() + " — an image or a model this"
                        + " addon ships is hafen.render():sprite():add(asset, p) / :object():add(asset, p)");
                LuaTable spec = placement(a, "hafen.ghost():add");
                return born(makeGhost(owner, spec, rv.tojstring(), 0, null), "hafen.ghost():add");
            }

            public boolean destroyable() {
                return true;
            }

            public void removeMember(LuaValue x) {
                destroyEntity(memberArg(owner.ghosts, x, "hafen.ghost():remove", "ghost"));
            }
        }, null);
    }

    /**
     * Build {@code hafen.render} for {@code owner}: a section with two collections, {@code :sprite()} and
     * {@code :object()}, each minted once and handed back by identity (§2.3). The old table constructors are
     * retired rows naming them.
     */
    static void installRender(LuaTable hafen, final Addon owner) {
        final LuaValue sprites = spriteCollection(owner);
        final LuaValue objects = objectCollection(owner);
        LuaTable m = new LuaTable();
        m.set("sprite", collectionVerb("sprite", sprites));
        m.set("object", collectionVerb("object", objects));
        Section.install(hafen, "render", m);
    }

    /** {@code hafen.render():sprite()} / {@code :object()} — the collection itself, never a per-call view. */
    private static LuaValue collectionVerb(final String nm, final LuaValue coll) {
        return new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "render", nm);
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.render():" + nm + "() takes no arguments — it IS the collection,"
                        + " and hafen.render():" + nm + "():add(asset) puts one in the world");
                return coll;
            }
        };
    }

    /** {@code hafen.render():sprite()} — this addon's custom-PNG world sprites. */
    private static LuaValue spriteCollection(final Addon owner) {
        return LuaCollection.create("hafen.render():sprite()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                return entityMembers(owner.sprites);
            }

            public String needle(LuaValue member) {
                return visualNameOf(owner.sprites, member);
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean creatable() {
                return true;
            }

            public LuaValue addMember(Varargs a) {
                LuaValue img = Args.required(a, 2, "hafen.render():sprite():add", "image");
                LuaTable spec = placement(a, "hafen.render():sprite():add");
                spec.set("image", img);
                return born(makeSprite(owner, spec, 0, null), "hafen.render():sprite():add");
            }

            public boolean destroyable() {
                return true;
            }

            public void removeMember(LuaValue x) {
                destroyEntity(memberArg(owner.sprites, x, "hafen.render():sprite():remove", "sprite"));
            }
        }, null);
    }

    /** {@code hafen.render():object()} — this addon's glTF world models. */
    private static LuaValue objectCollection(final Addon owner) {
        return LuaCollection.create("hafen.render():object()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                return entityMembers(owner.objects);
            }

            public String needle(LuaValue member) {
                return visualNameOf(owner.objects, member);
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean creatable() {
                return true;
            }

            public LuaValue addMember(Varargs a) {
                LuaValue mdl = Args.required(a, 2, "hafen.render():object():add", "model");
                LuaTable spec = placement(a, "hafen.render():object():add");
                spec.set("model", mdl);
                return born(makeObject(owner, spec, 0, null), "hafen.render():object():add");
            }

            public boolean destroyable() {
                return true;
            }

            public void removeMember(LuaValue x) {
                destroyEntity(memberArg(owner.objects, x, "hafen.render():object():remove", "object"));
            }
        }, null);
    }

    /**
     * The live, freely-placed entities of one registry as their handles — the members of its collection. An
     * entity that backs a {@code gob:overlay()} record is <b>not</b> among them (038.2): an overlay is reached
     * through the gob it is on, and a second door handing out a handle carrying an ending would let an addon kill
     * the visual behind a record that still reads as attached.
     */
    private static List<LuaValue> entityMembers(List<? extends LuaWorldEntity> reg) {
        List<LuaValue> out = new ArrayList<LuaValue>();
        for(LuaWorldEntity e : reg) {              // copy-on-write: a filter fn may create/destroy one
            if(e.dead || (e.handle == null) || e.asOverlay)
                continue;
            out.add(e.handle);
        }
        return out;
    }

    /**
     * The place a {@code :add(thing, p)} stands its entity, as the argument spec {@code §2.5} calls
     * positional: <b>a required argument stays on the constructor where the thing is meaningless without
     * it</b>. For a thing standing in the 3D world that is not a matter of taste — the scene resolves the
     * TILE under a gob the moment it is added, so an entity with no place cannot enter the scene at all: at
     * the world origin the engine raises <i>waiting for map data</i>, which is not a state a builder may
     * pass through. So a place is not a setter with a default; it is half of what an entity IS.
     */
    private static LuaTable placement(Varargs a, String verb) {
        Coord2d rc = LuaPosition.worldArg(a, 3, verb, "p");
        LuaTable spec = new LuaTable();
        spec.set("x", LuaValue.valueOf(rc.x));
        spec.set("y", LuaValue.valueOf(rc.y));
        return spec;
    }

    /**
     * The entity a creation produced, or a {@link LuaError} naming when one can be made at all. <b>A creation
     * RAISES where a removal is inert</b> (D-114): the caller is about to chain a setter onto what comes back, so
     * answering {@code nil} turns the very next {@code :position(p)} into <i>attempt to index a nil value</i> one
     * line later — which is the failure the retired-name table exists to prevent.
     */
    private static LuaValue born(LuaWorldEntity e, String where) {
        if(e == null)
            throw new LuaError(where + ": there is no map view yet — a thing standing in the 3D world needs the"
                + " scene, so place it once you are in the world (OnEnterWorld)");
        return e.handle;
    }

    /**
     * The entity a {@code :remove(x)} names: its own handle, and nothing else. It must belong to <i>this</i>
     * registry, so removing another addon's — or an overlay's — is a refusal rather than a silent miss.
     */
    private static LuaWorldEntity memberArg(List<? extends LuaWorldEntity> reg, LuaValue x, String verb, String what) {
        for(LuaWorldEntity e : reg) {
            if(!e.asOverlay && (e.handle != null) && (e.handle == x))
                return e;
        }
        throw new LuaError(verb + "(x) expects a " + what + " this addon placed — the value it hands back from"
            + " :add(), or one out of :list()");
    }

    /**
     * What a <b>string</b> filter matches on a member of one of these collections: the entity's visual name — a
     * ghost's {@code .res}, a sprite's image path, an object's model path. Resolved by walking the registry,
     * which is the addon's own handful of entities and needs no second index.
     */
    private static String visualNameOf(List<? extends LuaWorldEntity> reg, LuaValue member) {
        for(LuaWorldEntity e : reg) {
            if(e.handle == member)
                return e.visualName();
        }
        return null;
    }

    // ------------------------------------------------------------------ world ghosts (hafen.ghost)

    /**
     * Build a ghost and publish it — the body of {@link #newGhost}, shared with the world-space half of
     * {@code gob:overlay(key, {ghost = res})} (038.2). {@code tgt != 0} anchors it to that gob id (a
     * {@link FollowMoving}, applied at publish so a still-streaming visual is anchored the moment it lands);
     * {@code tgt == 0} is the fixed placement {@code hafen.ghost():add(res)} makes. Returns {@code null} when there is
     * no map view (not in the world). Every other option is read from {@code opts} exactly as before.
     */
    private static LuaGhost makeGhost(final Addon owner, LuaValue opts, final String resName, long tgt, Coord3f off) {
        final MapView mv = view;
        final Glob g = glob();
        if((mv == null) || (g == null))
            return null;                               // not in the world yet — no scene to add to
        LuaValue av = opts.get("a");
        LuaValue clickablev = opts.get("clickable");   // V2: opt-in pick-selectability (default false)
        LuaValue onclickv = opts.get("onClick");       // V2: per-ghost click callback fn(g, button, x, y)
        // remote() = the game/server resource pool (terobjs, gobs, …), with local() as a fallback for
        // client-bundled resources — the pool the engine itself uses for gob drawables (Session/Music/Widget).
        // local() alone would only find the client jar, so a terobj like gfx/terobjs/arch/logcabin never resolves.
        final Indir<Resource> resid = Resource.remote().load(resName);
        final LuaGhost gh = new LuaGhost(owner, resid, resName,
                                         new Coord2d(opts.get("x").optdouble(0.0), opts.get("y").optdouble(0.0)),
                                         av.isnumber() ? av.todouble() : 0.0);
        gh.sdt = luaSdt(opts.get("sdt"));              // V3: optional spawn-data bytes (null ⇒ MessageBuf.nil)
        gh.alpha = luaAlpha(opts.get("alpha"));        // V3: opacity 0..1 (default 1 = opaque)
        gh.tint = luaTint(opts.get("tint"));           // V3: colour overlay {r=,g=,b=[,a=]}, or null
        gh.scale = luaScale(opts.get("scale"));        // V6: uniform scale (default 1 = original size)
        gh.followTgt = tgt;                            // 038.2: the ANCHOR, now reachable only through gob:overlay
        gh.followOff = off;
        gh.asOverlay = (tgt != 0);
        gh.clickable = clickablev.toboolean();         // V2: nil/false → not clickable; true → clickable
        if(onclickv.isfunction())
            gh.onClick = onclickv;
        owner.ghosts.add(gh);
        LuaValue handle = ghostHandle(gh);
        gh.handle = handle;
        g.loader.defer(new Runnable() {
            public void run() {
                // Read the DESIRED res/sdt fresh each run so a :setRes that landed before we published is honoured
                // (and so a Loading re-run picks up a swapped resource). Guarded by the ghost monitor.
                Indir<Resource> res; Message sdt; String rnm;
                synchronized(gh) {
                    if(gh.dead)
                        return;                        // destroyed before we ran → nothing to build
                    res = gh.res; sdt = gh.sdt; rnm = gh.resName;
                }
                try {
                    res.get();                         // Loading → the loader re-runs this task when it resolves
                } catch(Loading l) {
                    throw(l);
                } catch(RuntimeException e) {
                    UI u = ui;
                    if(u != null)
                        u.error(clampMsg("addon: ghost resource '" + rnm + "' could not be loaded"));
                    synchronized(gh) { gh.failed = true; }
                    return;
                }
                // Build the gob + drawable OUTSIDE the ghost lock (no scene mutation yet), then publish atomically.
                Coord2d rc0; double a0;
                synchronized(gh) {
                    if(gh.dead) return;
                    rc0 = gh.rc; a0 = gh.a;
                }
                GhostGob gob = new GhostGob(g, rc0);      // V2/V3: a Gob subclass whose obstate adds the click surface + look
                gob.a = a0;
                gob.setattr(new ResDrawable(gob, res, (sdt == null) ? MessageBuf.nil : sdt));  // res cached now → no Loading here
                synchronized(gh) {
                    if(gh.dead) { gob.dispose(); return; }   // destroyed mid-build → discard (never added to scene)
                    gob.clickable = gh.clickable;            // V2: reflect opt-in clickability BEFORE the gob enters the scene
                    gob.alpha = gh.alpha;                    // V3: reflect the desired look before the first scene add
                    gob.tint = gh.tint;
                    gob.scale = gh.scale;                    // V6: reflect the desired scale before the first scene add
                    gob.move(gh.rc, gh.a);                   // apply any :move that landed while we were building
                    gh.gob = gob;
                    gh.mv = mv;
                    applyEntityFollow(gh, gob);              // ANCHOR: if follow= was given, start tracking the gob now
                    if(!gh.hidden)                           // V3: a ghost hidden before it published stays out of the scene
                        addToScene(gh, mv);                  // the // addon: MapView seam; pending when the tile is not here yet
                }
            }
        }, null);
        return gh;
    }

    /**
     * The Lua handle of a client-only world entity — <b>one vocabulary for all three kinds</b> (a
     * {@link LuaGhost}, a {@link LuaSprite}, a {@link LuaObject}), plus whatever {@code extra} verbs its own kind
     * adds ({@code :res} for a ghost, {@code :image}/{@code :billboard} for a sprite, {@code :mesh} for an
     * object). Every property here is a read/write pair on ONE name (§2.2): {@code :scale()} reads and
     * {@code :scale(2)} writes and hands back the handle, so a placement is one statement.
     *
     * <p>The handle table itself is left <b>empty</b> and every name is answered by the metatable, which is what
     * lets a retired spelling ({@code :pos}, {@code :move}, {@code :show}, {@code :hide}, {@code :destroy}) throw
     * naming its replacement instead of reading as plain {@code nil} and failing one line later.
     */
    private static LuaValue entityHandle(final LuaWorldEntity e, final String kind, LuaTable extra) {
        LuaTable m = new LuaTable();
        // position() -> a Position, the one type a place in the world has; position(p [, a]) moves it there, and
        // the optional second argument is the facing, because "put it there facing that way" is one act. It is
        // the read/write pair the old :pos()/:move(x, y, a) split across two names and two shapes.
        m.set("position", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                if(!Args.passed(a, 2))
                    return overlayPosition(e.owner, e);
                Coord2d rc = LuaPosition.worldArg(a, 2, kind + ":position", "p");
                Double ang = Args.passed(a, 3)
                    ? Double.valueOf(number(a, 3, kind + ":position", "a")) : null;
                moveEntity(e, rc, ang);
                return self;
            }
        });
        // rotate() / rotate(a) -- the facing in radians, kept while the thing stays where it is.
        m.set("rotate", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue av = Args.written(a, 2, kind + ":rotate", "a");
                if(av == null) {
                    synchronized(e) { return LuaValue.valueOf(e.a); }
                }
                moveEntity(e, null, Double.valueOf(number(a, 2, kind + ":rotate", "a")));
                return self;
            }
        });
        m.set("scale", new VarArgFunction() {           // uniform scale (1 = original size)
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue sv = Args.written(a, 2, kind + ":scale", "s");
                if(sv == null) {
                    synchronized(e) { return LuaValue.valueOf((double)e.scale); }
                }
                setEntityScale(e, clampScale(number(a, 2, kind + ":scale", "s")));
                return self;
            }
        });
        m.set("alpha", new VarArgFunction() {           // opacity 0..1 (1 = opaque)
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue av = Args.written(a, 2, kind + ":alpha", "a");
                if(av == null) {
                    synchronized(e) { return LuaValue.valueOf((double)e.alpha); }
                }
                setEntityAlpha(e, clampAlpha(number(a, 2, kind + ":alpha", "a")));
                return self;
            }
        });
        // tint(nil) STAYS legal: "no tint" is a real value rather than an accident, and it is one of the two nils
        // the whole API documents a meaning for.
        m.set("tint", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                if(!Args.passed(a, 2)) {
                    synchronized(e) { return colorValue(e.tint); }
                }
                setEntityTint(e, a.arg(2).isnil() ? null : colorArg(a, 2, kind + ":tint"));
                return self;
            }
        });
        // visible() / visible(b) -- a boolean property is a property. :show()/:hide() were two spellings of one
        // write and are retired rows naming this.
        m.set("visible", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue bv = Args.written(a, 2, kind + ":visible", "b");
                if(bv == null) {
                    synchronized(e) { return LuaValue.valueOf(!e.hidden && !e.dead); }
                }
                if(bv.toboolean())
                    showEntity(e);
                else
                    hideEntity(e);
                return self;
            }
        });
        m.set("clickable", new VarArgFunction() {       // opt into the client-side pick (never reaches the server)
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue bv = Args.written(a, 2, kind + ":clickable", "b");
                if(bv == null) {
                    synchronized(e) { return LuaValue.valueOf(e.clickable); }
                }
                setEntityClickable(e, bv.toboolean());
                return self;
            }
        });
        m.set("onClick", new VarArgFunction() {         // fn(handle, button, x, y) -- the per-entity click callback
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue fn = Args.written(a, 2, kind + ":onClick", "fn");
                if(fn == null) {
                    synchronized(e) { return (e.onClick == null) ? LuaValue.NIL : e.onClick; }
                }
                if(!fn.isfunction())
                    throw new LuaError(kind + ":onClick(fn) expects a function fn(" + kind
                        + ", button, x, y), got " + fn.typename());
                synchronized(e) { e.onClick = fn; }
                return self;
            }
        });
        // exists() -- is it still in the world? False once the collection removed it, and false after a teardown.
        m.set("exists", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                synchronized(e) { return LuaValue.valueOf(!e.dead); }
            }
        });
        if(extra != null) {
            LuaValue k = LuaValue.NIL;
            while(true) {
                Varargs n = extra.next(k);
                k = n.arg1();
                if(k.isnil())
                    break;
                m.set(k, n.arg(2));
            }
        }
        LuaTable h = new LuaTable();
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex(kind, m));
        h.setmetatable(mt);
        return h;
    }

    /** A required number argument, refused by name rather than silently coerced to zero. */
    private static double number(Varargs a, int i, String verb, String param) {
        LuaValue v = Args.required(a, i, verb, param);
        if(!v.isnumber())
            throw new LuaError(verb + ": " + param + " must be a number, got " + v.typename());
        return v.todouble();
    }

    /**
     * Move an entity and/or turn it: {@code rc} null keeps where it stands, {@code ang} null keeps its facing.
     * A live gob is repositioned now; one whose visual is still streaming in just has its desired transform
     * updated, and the create applies it at publish.
     */
    private static void moveEntity(LuaWorldEntity e, Coord2d rc, Double ang) {
        synchronized(e) {
            if(e.dead)
                return;                                // gone: a write to something that ended is a moment, not a mistake
            if(rc != null)
                e.rc = rc;
            if(ang != null)
                e.a = ang.doubleValue();
            if(e.gob != null)
                e.gob.move(e.rc, e.a);
        }
    }

    /**
     * The Lua handle for a {@link LuaGhost}: the shared entity verbs plus {@code :res()} / {@code :res(name
     * [, spawnData])} — one name for the read and the write of the {@code .res} model it draws, where a
     * {@code :res()} read and a {@code setRes} write used to be two.
     */
    private static LuaValue ghostHandle(final LuaGhost gh) {
        LuaTable x = new LuaTable();
        x.set("res", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue rv = Args.written(a, 2, "ghost:res", "res");
                if(rv == null)
                    return (gh.resName == null) ? LuaValue.NIL : LuaValue.valueOf(gh.resName);
                if(!rv.isstring() || rv.isnumber())
                    throw new LuaError("ghost:res(res [, spawnData]) expects a resource NAME string, got "
                        + rv.typename());
                setGhostRes(gh, rv.tojstring(), luaSdt(a.arg(3)));   // swaps the visual; it streams in like new
                return self;
            }
        });
        return entityHandle(gh, "ghost", x);
    }

    /**
     * Destroy one world entity now (its {@code :destroy()}, and teardown): flip {@link LuaWorldEntity#dead} + hand
     * off the slot/gob under the entity's monitor (so a still-pending deferred create sees {@code dead} and discards
     * its un-added gob instead of leaking it), then remove the scene slot ({@link MapView#removeClientGob}, which
     * swallows {@code SlotRemoved} for an already-torn-down scene) and dispose the gob's visual — all OUTSIDE the
     * entity lock (no lock-ordering with the render tree's own lock). {@link LuaWorldEntity#unregister()} drops it
     * from its addon's registry (ghosts / sprites). Shared by ghosts and sprites. Idempotent.
     */
    private static void destroyEntity(LuaWorldEntity e) {
        RenderTree.Slot slot; Gob gob; MapView mv;
        synchronized(e) {
            if(e.dead)
                return;
            e.dead = true;
            slot = e.slot; e.slot = null;
            gob  = e.gob;  e.gob  = null;
            mv   = e.mv;   e.mv   = null;
        }
        e.unregister();
        if(mv != null) {
            mv.removeClientGob(gob, slot);            // drops it from the MapView tick list + removes the slot (swallows SlotRemoved)
        } else if(slot != null) {
            try { slot.remove(); } catch(RuntimeException ex) { /* scene already gone (relog) */ }
        }
        if(gob != null) {
            try { gob.dispose(); } catch(RuntimeException ex) { /* best-effort: free the visual */ }
        }
    }

    /**
     * Add a freshly built entity to the scene, or leave it <b>pending</b> when the ground under it has not
     * streamed in yet. The scene resolves the tile under a gob as it is added, so a place the character has
     * walked but the client has not re-streamed raises {@code Loading} there — which is a <i>not yet</i>,
     * not a <i>no</i>, and the same kick-the-load model every other read in this API follows. So the entity
     * is kept, {@link #armPending} retries it on each addon tick, and the caller's chain goes on working.
     * Caller holds the entity monitor.
     */
    private static void addToScene(LuaWorldEntity e, MapView mv) {
        try {
            e.slot = mv.addClientGob(e.gob);
        } catch(Loading l) {
            e.pending = true;                          // the tile is not here yet: retried on the next tick
        }
    }

    /**
     * Retry every pending entity's scene add, once per addon tick (from {@code AddonManager.tick}). A place
     * whose ground arrives is added exactly once; one that never arrives simply stays out of the scene, which
     * is what an addon asking for a place it cannot see should get.
     */
    static void armPending(Addon a) {
        armPending(a.ghosts);
        armPending(a.sprites);
        armPending(a.objects);
    }

    private static void armPending(List<? extends LuaWorldEntity> reg) {
        for(LuaWorldEntity e : reg) {
            synchronized(e) {
                if(!e.pending || e.dead || e.hidden || (e.gob == null) || (e.mv == null))
                    continue;
                try {
                    e.slot = e.mv.addClientGob(e.gob);
                    e.pending = false;
                    e.gob.move(e.rc, e.a);
                } catch(RuntimeException ex) {
                    /* still loading, or the scene is gone — try again next tick */
                }
            }
        }
    }

    /** Tear down every ghost this addon owns (reload/disable/relogin, P2): destroy each (slot removed + visual freed). */
    static void teardownGhosts(Addon a) {
        if(a.ghosts.isEmpty())
            return;
        for(LuaGhost gh : new ArrayList<LuaGhost>(a.ghosts))
            destroyEntity(gh);          // removes each from a.ghosts as it goes (copy-on-write list)
    }

    /** Tear down every sprite this addon owns (reload/disable/relogin, P2): destroy each (slot removed + quad freed). */
    static void teardownSprites(Addon a) {
        if(a.sprites.isEmpty())
            return;
        for(LuaSprite sp : new ArrayList<LuaSprite>(a.sprites))
            destroyEntity(sp);          // removes each from a.sprites as it goes (copy-on-write list)
    }

    // ---- custom 3D models in the world (hafen.render():object()) -------------------------------------------

    /**
     * Build an object and publish it — the body of {@code hafen.render():object():add}, shared with the world-space half of
     * {@code gob:overlay(key, {model = asset})} (038.2). {@code tgt != 0} anchors it to that gob id; {@code 0} is
     * the fixed placement. Returns {@code null} when there is no map view (not in the world).
     */
    private static LuaObject makeObject(Addon owner, LuaValue opts, long tgt, Coord3f off) {
        final MapView mv = view;
        final Glob g = glob();
        if((mv == null) || (g == null))
            return null;                               // not in the world yet — no scene to add to
        LuaMesh mesh = resolveObjectMesh(opts.get("model"));   // AFTER the world check (don't validate when not in world)
        LuaValue av = opts.get("a");
        double a = av.isnumber() ? av.todouble() : 0.0;
        Coord2d rc = new Coord2d(opts.get("x").optdouble(0.0), opts.get("y").optdouble(0.0));   // 0,0 placeholder when anchored
        LuaObject ob = new LuaObject(owner, mesh, rc, a);
        ob.alpha = luaAlpha(opts.get("alpha"));
        ob.tint = luaTint(opts.get("tint"));
        ob.scale = luaScale(opts.get("scale"));        // uniform scale on top of the baked model→world size
        ob.clickable = opts.get("clickable").toboolean();
        LuaValue onclickv = opts.get("onClick");
        if(onclickv.isfunction())
            ob.onClick = onclickv;
        ob.followTgt = tgt;                            // 038.2: the ANCHOR, now reachable only through gob:overlay
        ob.followOff = off;
        ob.asOverlay = (tgt != 0);
        owner.objects.add(ob);
        LuaValue handle = objectHandle(ob);
        ob.handle = handle;
        // Build the gob + visual, then publish atomically. No defer: the glTF geometry is already parsed (R3), so
        // nothing here throws Loading. The MeshSprite adds one Model per primitive; the shared core supplies
        // transform/look/gizmo, exactly like a sprite's quad.
        GhostGob gob = new GhostGob(g, rc);
        gob.a = a;
        gob.alpha = ob.alpha; gob.tint = ob.tint; gob.scale = ob.scale;   // reflect the look before the first scene add
        gob.clickable = ob.clickable;                  // a clickable object's mesh renders into the clickmap → V2-pickable
        gob.setattr(new SprDrawable(gob, MeshSprite.mill(mesh)));    // resource-free glTF-model visual (R3b: shared textures + per-material states)
        gob.move(rc, a);
        synchronized(ob) {
            if(ob.dead) { gob.dispose(); return ob; }   // destroyed mid-build (defensive; all UI-thread)
            ob.gob = gob;
            ob.mv = mv;
            applyEntityFollow(ob, gob);                 // ANCHOR: an overlay's model starts tracking its gob now
            if(!ob.hidden)
                addToScene(ob, mv);                     // the // addon: MapView seam; pending when the tile is not here yet
        }
        return ob;
    }

    /**
     * The Lua handle for a {@link LuaObject}: the shared entity verbs plus {@code :mesh()}, its model's
     * addon-relative path. There is no write half — an object's geometry is milled at create, and swapping it is
     * {@code hafen.render():object():add(other)} on a fresh one.
     */
    private static LuaValue objectHandle(final LuaObject ob) {
        LuaTable x = new LuaTable();
        x.set("mesh", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("object:mesh() reads the model path and does not write it — an object's"
                        + " geometry is milled when it is placed, so another model is another object:"
                        + " hafen.render():object():add(asset)");
                return (ob.meshName == null) ? LuaValue.NIL : LuaValue.valueOf(ob.meshName);
            }
        });
        return entityHandle(ob, "object", x);
    }

    /**
     * Resolve the object {@code model=} option to a live {@link LuaMesh}: a {@code hafen.asset} mesh handle, or
     * its raw backing userdata. <b>Handle-only</b> (028.2 — one flow, D-012): a <b>path string</b> is refused
     * with an error naming {@code hafen.asset} as the way in, because interning makes repeating the load free,
     * so a shortcut here would only buy a second way to say the same thing. The three failures are
     * distinguishable: a path string, a disposed mesh, anything else.
     */
    private static LuaMesh resolveObjectMesh(LuaValue modelv) {
        if(modelv.isstring() && !modelv.isnumber())    // in LuaJ a number IS a string — that one is just a wrong type
            throw new LuaError("hafen.render():object():add(model): the argument is a hafen.asset mesh HANDLE, not a path string — load it"
                + " once with hafen.asset():get(\"" + modelv.tojstring() + "\") and pass the handle (it is interned, so"
                + " repeating the load is free)");
        LuaMesh lm = LuaMesh.resolve(modelv);          // a handle or its raw userdata
        if(lm == null)
            throw new LuaError("hafen.render():object():add(model): the argument must be a hafen.asset mesh handle"
                + " (hafen.asset():get(\"chair.glb\")), got " + modelv.typename());
        if(lm.dead)
            throw new LuaError("hafen.render():object():add(model): that mesh has been disposed — after a :dispose(), hafen.asset():get(path)"
                + " loads the file again as a NEW asset");
        return lm;
    }

    /** Tear down every object this addon owns (reload/disable/relogin, P2): destroy each (slot removed + Models freed). */
    static void teardownObjects(Addon a) {
        if(a.objects.isEmpty())
            return;
        for(LuaObject ob : new ArrayList<LuaObject>(a.objects))
            destroyEntity(ob);          // removes each from a.objects as it goes (copy-on-write list)
    }

    // ---- custom world sprites (hafen.render():sprite()) ------------------------------------------------------

    /**
     * Build a sprite and publish it — the body of {@code hafen.render():sprite():add}, shared with the world-space half of
     * {@code gob:overlay(key, {image = asset})} (038.2). {@code tgt != 0} anchors it to that gob id (a
     * {@link FollowMoving} applied before the gob enters the scene); {@code tgt == 0} is the fixed placement.
     * Returns {@code null} when there is no map view (not in the world).
     */
    private static LuaSprite makeSprite(Addon owner, LuaValue opts, long tgt, Coord3f off) {
        boolean billboard = opts.get("billboard").toboolean();   // R2b: true = camera-facing screen blit; false = fixed world quad
        final MapView mv = view;
        final Glob g = glob();
        if((mv == null) || (g == null))
            return null;                               // not in the world yet — no scene to add to
        LuaImage img = resolveSpriteImage(opts.get("image"));   // AFTER the world check (don't validate when not in world)
        LuaValue av = opts.get("a");
        double a = av.isnumber() ? av.todouble() : 0.0;
        Coord2d rc = new Coord2d(opts.get("x").optdouble(0.0), opts.get("y").optdouble(0.0));   // 0,0 placeholder when anchored
        LuaSprite sp = new LuaSprite(owner, img, rc, a, billboard);
        sp.alpha = luaAlpha(opts.get("alpha"));        // opacity 0..1 (default 1); combines with the PNG's own alpha
        sp.tint = luaTint(opts.get("tint"));           // colour overlay {r=,g=,b=[,a=]}, or null
        sp.scale = luaScale(opts.get("scale"));        // uniform scale (fixed quad: ~1 tile tall; billboard: screen-size ×)
        sp.clickable = opts.get("clickable").toboolean();   // R2b: opt-in pick (fixed sprites only; a billboard has no world mesh → never picked)
        LuaValue onclickv = opts.get("onClick");       // R2b: per-sprite click callback fn(s, button, x, y) — like a ghost
        if(onclickv.isfunction())
            sp.onClick = onclickv;
        sp.followTgt = tgt;                            // 038.2: the ANCHOR, now reachable only through gob:overlay
        sp.followOff = off;
        sp.asOverlay = (tgt != 0);
        owner.sprites.add(sp);
        LuaValue handle = spriteHandle(sp);
        sp.handle = handle;
        // Build the gob + visual OUTSIDE the sprite lock (no scene mutation yet), then publish atomically. No defer:
        // the TexI is already decoded (R1), so nothing here throws Loading. The visual is the ONLY thing that differs
        // between a fixed quad (SpriteQuad, a resource-free SprDrawable) and a camera-facing billboard
        // (LuaSpriteBillboard, a resource-free Drawable+Render2D) — the shared core supplies transform/look/gizmo.
        GhostGob gob = new GhostGob(g, rc);
        gob.a = a;
        gob.alpha = sp.alpha; gob.tint = sp.tint; gob.scale = sp.scale;   // reflect the look before the first scene add
        gob.clickable = sp.clickable;                  // R2b: a fixed sprite's quad renders into the clickmap → V2-pickable (see onGhostClick)
        gob.setattr(spriteVisual(gob, img, billboard));                  // the one miller, shared with :billboard(b)
        gob.move(rc, a);
        synchronized(sp) {
            if(sp.dead) { gob.dispose(); return sp; }   // destroyed mid-build (defensive; all UI-thread) → discard
            sp.gob = gob;
            sp.mv = mv;
            applyEntityFollow(sp, gob);                 // ANCHOR: an overlay's sprite starts tracking its gob now
            if(!sp.hidden)                              // a sprite hidden before it published stays out of the scene
                addToScene(sp, mv);                     // the // addon: MapView seam; pending when the tile is not here yet
        }
        return sp;
    }

    /**
     * The Lua handle for a {@link LuaSprite}: the shared entity verbs plus {@code :image()} (its image's
     * addon-relative path) and {@code :billboard()} / {@code :billboard(b)}.
     *
     * <p><b>{@code :billboard} is a CONSTRUCTION property, so writing it REBUILDS the visual</b> — the flag picks
     * which drawable is milled (an upright world quad or a camera-facing screen blit) and nothing can change that
     * in place. Refusing it after the fact would make the natural reading order illegal, and deferring the whole
     * build would move <i>there is no map view</i> off the call site that caused it; so the rebuild is the
     * property's implementation rather than a rule the caller has to know.
     */
    private static LuaValue spriteHandle(final LuaSprite sp) {
        LuaTable x = new LuaTable();
        x.set("image", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("sprite:image() reads the image path and does not write it — the texture"
                        + " is sampled when the sprite is placed, so another image is another sprite:"
                        + " hafen.render():sprite():add(asset)");
                return (sp.imgName == null) ? LuaValue.NIL : LuaValue.valueOf(sp.imgName);
            }
        });
        x.set("billboard", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue bv = Args.written(a, 2, "sprite:billboard", "b");
                if(bv == null) {
                    synchronized(sp) { return LuaValue.valueOf(sp.billboard); }
                }
                setSpriteBillboard(sp, bv.toboolean());
                return self;
            }
        });
        return entityHandle(sp, "sprite", x);
    }

    /**
     * {@code sprite:billboard(b)} — swap between the two visuals a sprite can have, in place. The gob keeps its
     * identity, its transform and its scene slot; only the {@code Drawable} is replaced, under the gob's own
     * monitor (the lock the engine's live res-swap holds, because {@code slots} is a plain list shared with the
     * {@code ctick} path). No-op when unchanged, dead, or still waiting for its gob.
     */
    private static void setSpriteBillboard(LuaSprite sp, boolean on) {
        synchronized(sp) {
            if(sp.dead || (sp.billboard == on))
                return;
            sp.billboard = on;
            Gob g = sp.gob;
            if(g == null)
                return;                                // not published yet — the create reads the flag
            Drawable dr = spriteVisual(g, sp.img, on);
            synchronized(g) {
                g.setattr(dr);                         // swaps the Drawable attrib: old slots removed, new added
            }
            refreshEntityScene(sp);                    // obstate reads the look fresh on the re-add
        }
    }

    /**
     * The one place a sprite's visual is milled, so the create and {@code :billboard(b)} cannot disagree: a fixed
     * upright quad sized to the image aspect, or a camera-facing screen blit that reads the look live each frame.
     */
    private static Drawable spriteVisual(Gob gob, LuaImage img, boolean billboard) {
        if(billboard)
            return new LuaSpriteBillboard(gob, img);
        float[] wh = spriteWorldDims(img.sz);
        return new SprDrawable(gob, SpriteQuad.mill(img.tex, wh[0], wh[1]));
    }

    /**
     * Resolve the sprite {@code image=} option to a live {@link LuaImage}: a {@code hafen.asset} image handle, or
     * its raw backing userdata. <b>Handle-only</b> (028.2 — one flow, D-012), exactly like
     * {@link #resolveObjectMesh}: a <b>path string</b> is refused with an error naming {@code hafen.asset} as the
     * way in. The three failures are distinguishable: a path string, a disposed image, anything else.
     */
    private static LuaImage resolveSpriteImage(LuaValue imgv) {
        if(imgv.isstring() && !imgv.isnumber())        // in LuaJ a number IS a string — that one is just a wrong type
            throw new LuaError("hafen.render():sprite():add(image): the argument is a hafen.asset image HANDLE, not a path string — load it"
                + " once with hafen.asset():get(\"" + imgv.tojstring() + "\") and pass the handle (it is interned, so"
                + " repeating the load is free)");
        LuaImage li = LuaImage.resolve(imgv);          // a handle or its raw userdata
        if(li == null)
            throw new LuaError("hafen.render():sprite():add(image): the argument must be a hafen.asset image handle"
                + " (hafen.asset():get(\"icon.png\")), got " + imgv.typename());
        if(li.dead)
            throw new LuaError("hafen.render():sprite():add(image): that image has been disposed — after a :dispose(), hafen.asset():get(path)"
                + " loads the file again as a NEW asset");
        return li;
    }

    /**
     * The world size {@code {w, h}} in map units of a fixed sprite, aspect-preserved from the image's pixel size:
     * the height is one tile ({@link MCache#tilesz}) and the width follows the image aspect, so the sprite stands
     * ~1 tile tall at {@code scale=1}; the uniform {@code :scale} (obstate) then adjusts both. A degenerate (zero)
     * pixel dimension falls back to a square tile. Pure — headless-testable.
     */
    private static float[] spriteWorldDims(Coord isz) {
        float base = (float)MCache.tilesz.y;               // ≈ 1 tile tall at scale 1
        float w = ((isz != null) && (isz.x > 0) && (isz.y > 0)) ? base * ((float)isz.x / (float)isz.y) : base;
        return new float[] { w, base };
    }

    /**
     * Toggle an entity's pick surface ({@code g:clickable(bool)}, V2 — ghosts only in R2a). The MapView click-list
     * decides membership <b>at slot-add time</b> — a later ancestor-state change does <i>not</i> re-run its
     * {@code Clickable} filter — so a live entity is toggled by removing and re-adding it to the scene
     * ({@link #refreshEntityScene}), where {@link GhostGob#obstate} is applied fresh and reads the updated
     * {@link GhostGob#clickable}. An entity whose deferred create has not published its gob yet just records the
     * desired state (the create applies it before the gob enters the scene). No-op when unchanged/dead.
     */
    private static void setEntityClickable(LuaWorldEntity e, boolean on) {
        synchronized(e) {
            if(e.dead || (e.clickable == on))
                return;
            e.clickable = on;
            if(e.gob instanceof GhostGob)
                ((GhostGob)e.gob).clickable = on;      // read by obstate on the next scene (re)add
            refreshEntityScene(e);
        }
    }

    /**
     * Set an entity's opacity ({@code g:alpha(a)}, V3): {@code 1} = opaque (no extra render state), {@code < 1} =
     * translucent. Like {@link #setEntityClickable}, the change is applied by re-adding the scene slot (obstate's
     * output is not part of {@code GobState.equals}, so the normal update path won't re-apply it). No-op if
     * unchanged/dead. Under the entity monitor.
     */
    private static void setEntityAlpha(LuaWorldEntity e, float alpha) {
        synchronized(e) {
            if(e.dead || (e.alpha == alpha))
                return;
            e.alpha = alpha;
            if(e.gob instanceof GhostGob)
                ((GhostGob)e.gob).alpha = alpha;       // read by obstate on the next scene (re)add
            refreshEntityScene(e);
        }
    }

    /**
     * Set an entity's colour-overlay tint ({@code g:tint(color)}, V3); {@code null} clears it. Applied by re-adding
     * the scene slot, like {@link #setEntityAlpha}. Under the entity monitor.
     */
    private static void setEntityTint(LuaWorldEntity e, java.awt.Color tint) {
        synchronized(e) {
            if(e.dead)
                return;
            e.tint = tint;
            if(e.gob instanceof GhostGob)
                ((GhostGob)e.gob).tint = tint;         // read by obstate on the next scene (re)add
            refreshEntityScene(e);
        }
    }

    /**
     * Set an entity's uniform scale ({@code g:scale(s)}, V6): {@code 1} = original size. Applied by re-adding the
     * scene slot like {@link #setEntityAlpha} (obstate's scaling {@code Location} is not part of
     * {@code GobState.equals}, so the normal update path won't re-apply it). No-op if unchanged/dead. Under the
     * entity monitor.
     */
    private static void setEntityScale(LuaWorldEntity e, float scale) {
        synchronized(e) {
            if(e.dead || (e.scale == scale))
                return;
            e.scale = scale;
            if(e.gob instanceof GhostGob)
                ((GhostGob)e.gob).scale = scale;       // read by obstate on the next scene (re)add
            refreshEntityScene(e);
        }
    }

    /**
     * Remove the entity from the scene ({@code g:hide()}, V3) — drops the scene slot (so it stops rendering/ticking)
     * but <b>keeps</b> the gob so {@code :show()} can re-add it. Marks {@link LuaWorldEntity#hidden} so a hide that
     * lands before the deferred create published keeps the prop out of the scene. No-op if already hidden/dead.
     * Under the entity monitor.
     */
    private static void hideEntity(LuaWorldEntity e) {
        synchronized(e) {
            if(e.dead || e.hidden)
                return;
            e.hidden = true;
            if((e.gob != null) && (e.mv != null) && (e.slot != null)) {
                try { e.mv.removeClientGob(e.gob, e.slot); }
                catch(RuntimeException ex) { /* scene gone (relog) — flag set, no scene op */ }
                e.slot = null;
            }
        }
    }

    /**
     * (Re)add the entity to the scene ({@code g:show()}, V3) — the inverse of {@link #hideEntity}. No-op if not
     * hidden/dead. Under the entity monitor.
     */
    private static void showEntity(LuaWorldEntity e) {
        synchronized(e) {
            if(e.dead || !e.hidden)
                return;
            e.hidden = false;
            if((e.gob != null) && (e.mv != null) && (e.slot == null)) {
                try {
                    e.slot = e.mv.addClientGob(e.gob);
                    e.gob.move(e.rc, e.a);             // re-assert position/facing after the re-add
                } catch(RuntimeException ex) {
                    /* scene gone (relog) — flag cleared, no scene op */
                }
            }
        }
    }

    /**
     * Swap a ghost's visual ({@code g:setRes(res[,sdt])}, V3). Records the new desired res/sdt (so a still-pending
     * create uses them) and, if the gob is already live, <b>defers</b> building the new {@link ResDrawable} —
     * {@code res.get()} throws {@code Loading} until cached, the same reason {@code new} defers — then {@code setattr}s
     * it on the gob under {@code synchronized(gob)} (the exact lock the engine's own live res-swap {@code $cres.apply}
     * holds; the gob's {@code slots} list is a plain {@code ArrayList} shared with the {@code ctick} path). A newer
     * {@code :setRes} that swapped {@code gh.res} in the meantime wins — this task drops its stale drawable.
     */
    private static void setGhostRes(final LuaGhost gh, final String resName, final MessageBuf sdt) {
        final Glob g = glob();
        final Indir<Resource> rid = Resource.remote().load(resName);
        boolean live;
        synchronized(gh) {
            if(gh.dead)
                return;
            gh.res = rid; gh.resName = resName; gh.sdt = sdt;
            gh.failed = false;
            live = (gh.gob != null);
        }
        if(!live || (g == null))
            return;                                    // no live gob yet → the pending create will use the new res
        g.loader.defer(new Runnable() {
            public void run() {
                Indir<Resource> res; Message sd; GhostGob gob; String nm;
                synchronized(gh) {
                    if(gh.dead)
                        return;
                    gob = (gh.gob instanceof GhostGob) ? (GhostGob)gh.gob : null;
                    res = gh.res; sd = gh.sdt; nm = gh.resName;
                }
                if((gob == null) || (res != rid))
                    return;                            // gob gone, or a newer :setRes superseded this one
                try {
                    res.get();                         // Loading → the loader re-runs when it resolves
                } catch(Loading l) {
                    throw(l);
                } catch(RuntimeException e) {
                    UI u = ui;
                    if(u != null)
                        u.error(clampMsg("addon: ghost resource '" + nm + "' could not be loaded"));
                    return;                            // keep the old visual (non-fatal)
                }
                ResDrawable dr = new ResDrawable(gob, res, (sd == null) ? MessageBuf.nil : sd);  // built outside the lock
                synchronized(gh) {
                    if(gh.dead || (gh.gob != gob) || (gh.res != res)) { dr.dispose(); return; }
                    synchronized(gob) {
                        gob.setattr(dr);               // swaps the Drawable attrib; old sprite's slots removed, new added
                    }
                }
            }
        }, null);
    }

    /**
     * Re-apply a live entity's render state (clickable/alpha/tint/scale) by removing and re-adding its scene slot so
     * {@link GhostGob#obstate} runs fresh — needed because neither the click-list membership nor obstate's colour
     * output propagates through the normal {@code Gob.updated()}/{@code updstate()} path. No-op if the gob is not
     * currently in the scene (pending create / hidden) — obstate reads the updated fields when it is (re)added.
     * <b>Caller must hold the entity monitor</b> (same entity→tree lock order the deferred create uses; no new hazard).
     */
    private static void refreshEntityScene(LuaWorldEntity e) {
        if((e.gob != null) && (e.mv != null) && (e.slot != null)) {
            try {
                e.mv.removeClientGob(e.gob, e.slot);
                e.slot = e.mv.addClientGob(e.gob);
                e.gob.move(e.rc, e.a);                 // re-assert position/facing after the re-add
            } catch(RuntimeException ex) {
                /* the entity's scene is gone (e.g. a REPL entity changed after a relog) — fields set, no scene op */
            }
        }
    }

    // ---- ANCHOR: the world-space half of gob:overlay (038.2) ---------------------------------------------------

    /**
     * Build the world-space half of {@code gob:overlay(key, spec)} (038.2): a client-only entity — a sprite
     * ({@code image}), an object ({@code model}) or a ghost ({@code ghost}) — anchored to {@code tgt} by a
     * {@link FollowMoving}, so the render tree places it at the gob's live interpolated position + {@code off}
     * every frame with no Lua polling. The spec table doubles as the entity's own options table, so
     * {@code scale}/{@code alpha}/{@code tint}/{@code a}/{@code billboard} all mean what they mean everywhere else.
     *
     * <p>The entity is registered in its addon's owned-resource registry exactly like any other, so {@code :reload}
     * and disable free it through the teardown that already exists — but it is flagged {@link
     * LuaWorldEntity#asOverlay}, so it never appears in {@code hafen.ghost.list()}: an overlay is reached through
     * {@code gob:overlay}, and a second door handing out a raw handle with {@code :destroy()} on it would let an
     * addon kill the visual behind a record that still reads as attached.
     */
    static LuaWorldEntity overlayEntity(Addon owner, long tgt, LuaValue spec, String kind, Coord3f off) {
        if(!spec.get("clickable").isnil() || !spec.get("onClick").isnil())
            throw new LuaError("gob:overlay(key, spec): 'clickable'/'onClick' are not overlay properties — the thing"
                + " under an overlay is the GOB, and a click on a gob is the client's own (hafen.act.clickGob)");
        LuaWorldEntity e;
        if(kind.equals("image"))
            e = makeSprite(owner, spec, tgt, off);
        else if(kind.equals("model"))
            e = makeObject(owner, spec, tgt, off);
        else
            e = makeGhost(owner, spec, spec.get("ghost").tojstring(), tgt, off);
        if(e == null)
            throw new LuaError("gob:overlay(key, spec): there is no map view yet — a world-space overlay needs the"
                + " 3D scene, so attach it once you are in the world (OnEnterWorld / GobAdded)");
        return e;
    }

    /** Destroy the entity behind a world-space overlay — the record's own end (replace / remove / the gob's death). */
    static void destroyOverlayEntity(LuaWorldEntity e) {
        destroyEntity(e);
    }

    /**
     * {@code overlay:tint/:alpha/:scale/:rotate} on a world-space overlay — the look and facing verbs the entity
     * already has, composed onto the Overlay object (plan §3) rather than re-implemented, so absorbing
     * {@code follow=} takes nothing away. Position is NOT among them: an overlay's position is its gob's, and the
     * only thing an addon sets is the {@code offset} its spec names.
     */
    static void overlayTint(LuaWorldEntity e, java.awt.Color c) { setEntityTint(e, c); }
    static void overlayAlpha(LuaWorldEntity e, double a)        { setEntityAlpha(e, clampAlpha(a)); }
    static void overlayScale(LuaWorldEntity e, double s)        { setEntityScale(e, clampScale(s)); }

    /**
     * {@code overlay:offset(x, y, z)} on a world-space overlay — move the anchored visual relative to its gob
     * <b>in place</b>, which is what makes the offset a property like every other rather than a reason to detach
     * and re-attach. Both halves are written: the entity's own desired offset (read by a create that has not
     * published yet) and the live {@link FollowMoving}, whose {@code off} is volatile precisely so the placement
     * pass can pick it up on the next frame without a lock.
     */
    static void overlayOffset(LuaWorldEntity e, Coord3f off) {
        synchronized(e) {
            if(e.dead)
                return;
            e.followOff = off;
            Gob g = e.gob;
            if(g == null)
                return;
            Moving mv = g.getattr(Moving.class);
            if(mv instanceof FollowMoving)
                ((FollowMoving)mv).off = off;
        }
    }

    /** {@code overlay:rotate(a)} — the entity keeps its OWN facing while it follows (FollowMoving supplies only the point). */
    static void overlayRotate(LuaWorldEntity e, double a) {
        synchronized(e) {
            if(e.dead)
                return;
            e.a = a;
            if(e.gob != null)
                e.gob.move(e.rc, e.a);
        }
    }

    /**
     * {@code overlay:position()} — where a world-space overlay actually is, as a {@link LuaPosition}: the gob's
     * live interpolated point plus the overlay's own offset. One position verb, one type (039.2/039.3), so the
     * answer goes straight to {@code hafen.act():moveTo} or into {@code hafen.store} without conversion; the
     * facing and size that used to ride in the same table are {@code ov:rotate()} and {@code ov:scale()}.
     */
    static LuaValue overlayPosition(Addon owner, LuaWorldEntity e) {
        Coord2d rc;
        synchronized(e) {
            rc = entityWorldPos(e);
        }
        return (rc == null) ? LuaValue.NIL : LuaPosition.of(owner, rc);
    }

    /**
     * Attach the {@link FollowMoving} at (deferred/immediate) create time when the entity is anchored — called by
     * {@code makeSprite}/{@code makeObject}/{@code makeGhost} once the gob is built (before it enters the scene),
     * so an overlay whose visual streams in a beat later is anchored the moment it lands. Caller holds the entity
     * monitor; the fresh gob is not yet published, so no {@code synchronized(gob)} is needed.
     */
    private static void applyEntityFollow(LuaWorldEntity e, Gob gob) {
        if(e.followTgt != 0)
            gob.setattr(new FollowMoving(gob, e.followTgt, e.followOff));
    }

    /** Parse a {@code {x=,y=,z=}} world-offset table → a {@link Coord3f} (missing components 0), or {@code null} (not a table). */
    static Coord3f luaOffset(LuaValue v) {
        if((v == null) || !v.istable())
            return null;
        float x = (float)v.get("x").optdouble(0.0);
        float y = (float)v.get("y").optdouble(0.0);
        float z = (float)v.get("z").optdouble(0.0);
        return new Coord3f(x, y, z);
    }

    /**
     * The entity's LIVE world position (a {@link Coord2d}) for {@code :pos()}: while anchored, the followed gob's
     * current position (+ offset) via {@code gob.getc()}; otherwise the entity's own {@code rc}. Caller holds the
     * entity monitor.
     */
    private static Coord2d entityWorldPos(LuaWorldEntity e) {
        if((e.followTgt != 0) && (e.gob != null)) {
            try {
                Coord3f c = e.gob.getc();
                if(c != null)
                    return new Coord2d(c.x, c.y);
            } catch(RuntimeException ex) { /* placement still loading → fall back to rc */ }
        }
        return e.rc;
    }

    /** Parse a ghost {@code alpha} option/arg → clamped 0..1; a non-number defaults to 1 (opaque). */
    private static float luaAlpha(LuaValue v) {
        return v.isnumber() ? clampAlpha(v.todouble()) : 1f;
    }
    static float clampAlpha(double a) {
        return (a < 0.0) ? 0f : ((a > 1.0) ? 1f : (float)a);
    }

    /** Parse a ghost {@code scale} option/arg → clamped positive (0.01..100); a non-number defaults to 1 (original size). */
    private static float luaScale(LuaValue v) {
        return v.isnumber() ? clampScale(v.todouble()) : 1f;
    }
    static float clampScale(double s) {
        return (s < 0.01) ? 0.01f : ((s > 100.0) ? 100f : (float)s);   // never 0/negative (would collapse/invert the mesh)
    }

    /** Parse a ghost {@code tint} option/arg → a {@link java.awt.Color}, or {@code null} for none (nil / not a table). */
    private static java.awt.Color luaTint(LuaValue v) {
        return (v == null) || !v.istable() ? null : luaColor(v, null);
    }

    /**
     * Parse a ghost {@code sdt} option/arg → a {@link MessageBuf} of raw bytes, or {@code null} (⇒ {@code MessageBuf.nil})
     * when omitted. Accepts a 1-based Lua array of byte values (0..255); a non-table is treated as "none".
     */
    private static MessageBuf luaSdt(LuaValue v) {
        if((v == null) || !v.istable())
            return null;
        int n = v.length();
        byte[] b = new byte[n];
        for(int i = 0; i < n; i++)
            b[i] = (byte)(v.get(i + 1).toint() & 0xff);
        return new MessageBuf(b);
    }

    /**
     * V2: {@code MapView.Click.hit} resolved a click to virtual gob {@code cg}, BEFORE its {@code wdgmsg("click",
     * …)}. If {@code cg} is a <b>clickable</b> client world entity — a ghost <i>or</i> a fixed {@link LuaSprite}
     * (R2b generalized the dispatch beyond ghosts) — fire its owner-scoped click event ({@code GhostClicked{ghost,
     * …}} / {@code SpriteClicked{sprite, …}}, keyed by {@link LuaWorldEntity#clickEvent()}/{@link
     * LuaWorldEntity#clickKey()}; owner-scoped because the handle is private to its addon, not a global {@link #fire})
     * and its per-entity {@code onClick(handle, button, x, y)}, then return {@code true} so the caller CONSUMES the
     * click — no {@code wdgmsg}, so nothing reaches the server (client-only ⇒ still SAFE-tier, D-032). Returns {@code
     * false} for any non-entity / non-clickable gob, so a normal click proceeds. (A billboard sprite has no world
     * mesh, so it never renders into the clickmap and never reaches here — only fixed sprites are pickable.) {@code x,
     * y} = the world coord the click resolved to. Reached under {@code synchronized(ui)} (like the L3 message hook),
     * so {@link #callLua} is safe with no extra thread guard; the entity lock is released before dispatch so a handler
     * may re-entrantly {@code :destroy()}/{@code :move()} it.
     */
    static boolean onGhostClick(Gob cg, int button, Coord2d mc) {
        if(cg == null)
            return false;
        LuaWorldEntity e = findEntityByGob(cg);
        if(e == null)
            return false;
        LuaValue handle, onClick;
        synchronized(e) {
            if(e.dead || !e.clickable)
                return false;
            handle  = e.handle;
            onClick = e.onClick;
        }
        if(handle == null)
            return false;
        LuaValue bt = LuaValue.valueOf(button);
        LuaValue xv = LuaValue.valueOf((mc == null) ? 0 : mc.x);
        LuaValue yv = LuaValue.valueOf((mc == null) ? 0 : mc.y);
        LuaTable ev = new LuaTable();
        ev.set(e.clickKey(), handle);                  // "ghost" / "sprite" (constant per subclass; no lock needed)
        ev.set("button", bt);
        ev.set("x", xv);
        ev.set("y", yv);
        fireTo(e.owner, e.clickEvent(), ev);           // owner-scoped: an entity belongs to exactly one addon
        if((onClick != null) && onClick.isfunction())
            callLua(e.owner, Addon.C_HOOK, onClick, handle, bt, xv, yv);
        return true;                                   // consume — client-only detection, no server wdgmsg
    }

    /** Find the live world entity (ghost or sprite) whose gob is {@code cg}, across all addons + the REPL owner (V2 click dispatch). */
    private static LuaWorldEntity findEntityByGob(Gob cg) {
        for(Addon a : addons) {
            LuaWorldEntity e = findEntityIn(a, cg);
            if(e != null)
                return e;
        }
        Addon c = consoleOwner;
        return (c == null) ? null : findEntityIn(c, cg);
    }

    private static LuaWorldEntity findEntityIn(Addon a, Gob cg) {
        for(LuaGhost gh : a.ghosts) {
            Gob g;
            synchronized(gh) { g = gh.dead ? null : gh.gob; }
            if(g == cg)
                return gh;
        }
        for(LuaSprite sp : a.sprites) {
            Gob g;
            synchronized(sp) { g = sp.dead ? null : sp.gob; }
            if(g == cg)
                return sp;
        }
        for(LuaObject ob : a.objects) {           // R3: a clickable glTF object is pickable too (its mesh is in the clickmap)
            Gob g;
            synchronized(ob) { g = ob.dead ? null : ob.gob; }
            if(g == cg)
                return ob;
        }
        return null;
    }
}
