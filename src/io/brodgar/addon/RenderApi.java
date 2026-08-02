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
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;

import static io.brodgar.addon.AddonManager.*;

/**
 * The custom-rendering subsystem (V-series ghosts + R-series sprites/objects): {@code hafen.ghost}
 * (client-only world ghosts) and {@code hafen.render} ({@code sprite}/{@code object} — custom PNG + glTF props
 * standing in the 3D world). Owns the world-entity lifecycle (create/transform/follow/click/teardown) over
 * {@link LuaWorldEntity}. The V2 click seam {@code onGhostClick} (called from {@code haven.MapView}) stays a
 * facade in {@link AddonManager} and delegates here; {@link AddonManager} calls the per-kind teardowns on
 * reload/disable.
 *
 * <p><b>This is a SCENE namespace, not a loader</b> (028.1): {@code hafen.render.image}/{@code model} are cut,
 * and the addon's own files — images, fonts and meshes alike — come from the one door {@link AssetApi}
 * ({@code hafen.asset(path)}), which also owns the D-017 sandbox resolver and the intern cache. Not instantiable.
 */
final class RenderApi {
    private RenderApi() {}

    /** Build {@code hafen.ghost} for {@code owner}. From installHafen. */
    static void installGhost(LuaTable hafen, final Addon owner) {
        LuaTable ghost = new LuaTable();
        // hafen.ghost.new{res, x, y [, a] [, sdt] [, alpha] [, tint] [, clickable] [, onClick]} — res = a resource
        // name (e.g. "gfx/terobjs/arch/logcabin"); x,y = world coords; a = facing radians (optional, default 0).
        //   sdt      = {bytes}        -- V3: optional spawn-data bytes (resource variant/state); rarely needed
        //   alpha    = 0.5           -- V3: opacity 0..1 (default 1 = opaque); < 1 = the translucent "ghost" look
        //   tint     = {r=,g=,b=[,a=]} -- V3: colour overlay 0..255 (a = blend strength, default 255)
        //   clickable = true         -- V2: opt-in pick-selectability (default false)
        //   onClick = fn(g,button,x,y) -- V2: fires on click (also via the GhostClicked event)
        //   follow = gob             -- ANCHOR to a gob (id / "player" / "me"): the ghost tracks it every frame
        //   offset = {x=,y=,z=}      -- fixed world offset from the followed gob (z = up)
        // Returns a handle:
        //   :move(x, y [, a])  -- reposition (+ optional facing); DETACHES any :follow anchor
        //   :rotate(a)         -- V3: set facing (radians), keeping position
        //   :setRes(res[,sdt]) -- V3: swap the visual (streams in like new)
        //   :alpha(a)          -- V3: opacity 0..1 (1 = opaque)
        //   :tint(color|nil)   -- V3: colour overlay {r=,g=,b=[,a=]} (nil clears)
        //   :show() / :hide()  -- V3: add / remove the scene slot (keeps the ghost)
        //   :follow(gob[,{x=,y=,z=}]) -- ANCHOR to a gob and auto-track it (like a gob overlay); :follow(nil) detaches
        //   :offset{x=,y=,z=}  -- move it relative to the followed gob (keeps following)
        //   :pos()             -- {x, y, a, scale [, following]} (following = the anchored gob id, if any)
        //   :res()             -- the resource name (string)
        //   :clickable(bool)   -- V2: toggle the pick surface
        //   :destroy()         -- remove now (also automatic on reload/disable)
        // The visual streams in a beat later (the resource resolves on a loader thread, dodging Loading — the
        // Plob / hafen.sound precedent), so the handle works immediately while the prop appears shortly after.
        // Returns nil only if there is no map view yet (not in the world). V2: a CLICK on a clickable ghost is
        // detected client-side and CONSUMED (no server contact ⇒ still SAFE-tier); it fires onClick + the
        // owner-scoped GhostClicked{ghost,button,x,y} event.
        ghost.set("new", new OneArgFunction() {
            public LuaValue call(LuaValue opts) {
                return newGhost(owner, opts);
            }
        });
        ghost.set("list", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                return ghostList(owner, filter);
            }
        });
        hafen.set("ghost", ghost);
    }

    /** Build {@code hafen.render} for {@code owner}. From installHafen. */
    static void installRender(LuaTable hafen, final Addon owner) {
        LuaTable render = new LuaTable();
        // hafen.render is a SCENE namespace only: it stands things in the 3D world. LOADING the addon's own files
        // is hafen.asset(path) — one door for every local file (028.1, D-013 hard cut): hafen.render.image and
        // hafen.render.model are GONE and read as plain nil. An image handle is hafen.asset("icon.png"), a mesh
        // handle hafen.asset("chair.glb"); both are interned, so repeating the load is free.
        // hafen.render.sprite{image, x, y [, a] [, scale] [, alpha] [, tint] [, billboard] [, clickable] [, onClick]}
        // — stand a custom PNG in the 3D world (spec 17 §5, R2). The non-`.res` sibling of hafen.ghost, on the SAME
        // virtual-entity core + gizmo: a Gob with no server id, so it never reaches the server (SAFE-tier, NOT gated,
        // D-034). image = a hafen.asset image handle OR an addon-relative path (auto-loaded + interned, D-017-sandboxed);
        // x,y = world coords (like gob:pos()); a = facing radians (default 0). Options:
        //   scale = 2               -- uniform scale (default 1); fixed = ~1 tile tall, billboard = screen-size ×
        //   alpha = 0.5             -- opacity 0..1 (default 1); combines with the PNG's own transparency
        //   tint  = {r=,g=,b=[,a=]} -- colour overlay 0..255 (a = blend strength)
        //   billboard = false       -- false (default) = a FIXED upright quad (R2a); true = a CAMERA-FACING screen blit (R2b)
        //   clickable = true        -- opt into the V2 pick (fixed sprites only; a billboard has no world mesh → never picked)
        //   onClick = fn(s,btn,x,y) -- per-sprite click callback (also delivered as the owner-scoped SpriteClicked event)
        //   follow = gob            -- ANCHOR to a gob (id / "player" / "me"): the sprite tracks it every frame
        //   offset = {x=,y=,z=}     -- fixed world offset from the followed gob (z = up; e.g. {z=10} floats it overhead)
        // Returns a transform handle (gizmo-compatible), like a ghost but with :image() in place of :res():
        //   :move(x,y[,a]) :rotate(a) :scale(s) :alpha(a) :tint(color|nil) :clickable(bool) :show() :hide() :pos() :image() :destroy()
        //   :follow(gob[, {x=,y=,z=}])  -- ANCHOR to a gob and auto-track it (like a gob overlay); :follow(nil) detaches
        //   :offset{x=,y=,z=}           -- move it relative to the followed gob (it keeps following); a plain :move detaches
        // Returns nil only if there is no map view yet (not in the world). Both forms are resource-free visuals on the
        // shared core, so they get the full transform + look + gizmo for free (a billboard ignores world-rotate/scale).
        render.set("sprite", new OneArgFunction() {
            public LuaValue call(LuaValue opts) {
                return newSprite(owner, opts);
            }
        });
        // hafen.render.object{model, x, y [, a] [, scale] [, alpha] [, tint] [, clickable] [, onClick] [, follow]
        // [, offset]} — stand a custom glTF MODEL in the 3D world (spec 18, R3). The mesh sibling of a sprite/ghost,
        // on the SAME virtual-entity core + gizmo: a Gob with no server id (SAFE-tier, NOT gated, D-034). model = a
        // hafen.asset mesh handle OR an addon-relative path (auto-loaded + interned, D-017-sandboxed); x,y = world
        // coords (like gob:pos()); a = facing radians (default 0). Options mirror hafen.render.sprite:
        //   scale = 2               -- uniform scale (default 1) on top of the baked model→world size
        //   alpha = 0.5             -- opacity 0..1 (default 1); tint = {r=,g=,b=[,a=]} colour overlay 0..255
        //   clickable = true        -- opt into the V2 pick (the mesh renders into the clickmap) → ObjectClicked / onClick
        //   follow = gob / offset = {x=,y=,z=}   -- anchor to a gob and track it every frame (like a sprite)
        // Returns a transform handle (gizmo-compatible), like a sprite but with :mesh() in place of :image():
        //   :move(x,y[,a]) :rotate(a) :scale(s) :alpha(a) :tint(color|nil) :clickable(bool) :show() :hide() :pos() :mesh() :destroy()
        //   :follow(gob[, {x=,y=,z=}])  :offset{x=,y=,z=}
        // Returns nil only if there is no map view yet (not in the world). The glTF origin maps to the gob position, so
        // author a model with its base at Y=0 to stand on the ground.
        render.set("object", new OneArgFunction() {
            public LuaValue call(LuaValue opts) {
                return newObject(owner, opts);
            }
        });
        hafen.set("render", render);
    }

    // ------------------------------------------------------------------ world ghosts (hafen.ghost, V1)

    /**
     * Spawn a client-only world ghost ({@code hafen.ghost.new{res, x, y [, a]}}, spec 16 / V1): validate the
     * options, register a bridge-owned {@link LuaGhost} in the addon's owned-resource registry (P2), and
     * <b>defer</b> the visual to a loader thread — {@code res.get()} throws {@code Loading} until the resource is
     * cached, so, exactly like {@code MapView.Plob} and {@link LuaSound}, {@code glob.loader.defer} re-runs the
     * task when the resource lands, then builds the {@link Gob} + {@link ResDrawable} and adds it to the MapView
     * {@code basic} scene ({@link MapView#addClientGob}). The handle is returned <b>immediately</b> and works while
     * the prop streams in (a {@code :move} before the gob exists just updates the target the create applies). All
     * publish/destroy handoff is guarded by the ghost's monitor so the loader-thread create never races a
     * concurrent {@code :move}/{@code :destroy}. Returns {@code nil} if there is no map view yet (not in the world);
     * throws a {@link LuaError} for a malformed table.
     */
    private static LuaValue newGhost(final Addon owner, LuaValue opts) {
        if(!opts.istable())
            throw new LuaError("hafen.ghost.new{res=..., x=..., y=...} expects an options table");
        LuaValue resv = opts.get("res");
        if(!resv.isstring())
            throw new LuaError("hafen.ghost.new: 'res' must be a resource name string (e.g. \"gfx/terobjs/arch/logcabin\")");
        LuaValue xv = opts.get("x"), yv = opts.get("y");
        if(!xv.isnumber() || !yv.isnumber())
            throw new LuaError("hafen.ghost.new: 'x' and 'y' must be numbers (world coordinates, like gob:pos())");
        final MapView mv = view;
        final Glob g = glob();
        if((mv == null) || (g == null))
            return LuaValue.NIL;                       // not in the world yet — no scene to add to
        LuaValue av = opts.get("a");
        LuaValue clickablev = opts.get("clickable");   // V2: opt-in pick-selectability (default false)
        LuaValue onclickv = opts.get("onClick");       // V2: per-ghost click callback fn(g, button, x, y)
        final String resName = resv.tojstring();
        // remote() = the game/server resource pool (terobjs, gobs, …), with local() as a fallback for
        // client-bundled resources — the pool the engine itself uses for gob drawables (Session/Music/Widget).
        // local() alone would only find the client jar, so a terobj like gfx/terobjs/arch/logcabin never resolves.
        final Indir<Resource> resid = Resource.remote().load(resName);
        final LuaGhost gh = new LuaGhost(owner, resid, resName,
                                         new Coord2d(xv.todouble(), yv.todouble()),
                                         av.isnumber() ? av.todouble() : 0.0);
        gh.sdt = luaSdt(opts.get("sdt"));              // V3: optional spawn-data bytes (null ⇒ MessageBuf.nil)
        gh.alpha = luaAlpha(opts.get("alpha"));        // V3: opacity 0..1 (default 1 = opaque)
        gh.tint = luaTint(opts.get("tint"));           // V3: colour overlay {r=,g=,b=[,a=]}, or null
        gh.scale = luaScale(opts.get("scale"));        // V6: uniform scale (default 1 = original size)
        LuaValue gfollowv = opts.get("follow");        // ANCHOR: follow a gob (a Gob object), optional
        if(!gfollowv.isnil()) {
            gh.followTgt = followTargetId(gfollowv);
            gh.followOff = luaOffset(opts.get("offset"));   // {x=,y=,z=} world offset from the gob (default none)
        }
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
                        gh.slot = mv.addClientGob(gob);      // the // addon: MapView seam (spec 16 §6); MapView now ticks it
                }
            }
        }, null);
        return handle;
    }

    /**
     * Install the handle verbs common to EVERY client-only world entity (a {@link LuaGhost} or {@link LuaSprite}) —
     * {@code :move}/{@code :rotate}/{@code :alpha}/{@code :tint}/{@code :scale}/{@code :show}/{@code :hide}/
     * {@code :pos}/{@code :destroy} — onto the handle table {@code h}, all closing over the shared
     * {@link LuaWorldEntity} state + the {@code *Entity} scene helpers. Each subclass's handle builder
     * ({@link #ghostHandle} / {@link #spriteHandle}) calls this and then adds its own identity/extra verbs
     * ({@code :res}/{@code :setRes}/{@code :clickable} for a ghost, {@code :image} for a sprite). The colon-call
     * convention passes {@code self} as arg1, so a verb reads arg2.. and returns arg1 (the handle) for chaining;
     * every verb is a clean no-op once the entity is dead.
     */
    private static void addEntityHandle(LuaTable h, final LuaWorldEntity e) {
        h.set("move", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue xv = a.arg(2), yv = a.arg(3), av = a.arg(4);
                if(!xv.isnumber() || !yv.isnumber())
                    throw new LuaError(":move(x, y [, a]) expects world coordinates (numbers) — use a COLON call");
                synchronized(e) {
                    if(!e.dead) {
                        e.followTgt = 0;             // a manual move takes control back from any :follow anchor
                        e.rc = new Coord2d(xv.todouble(), yv.todouble());
                        if(av.isnumber())
                            e.a = av.todouble();
                        if(e.gob != null) {
                            detachFollowAttr(e.gob);  // drop the FollowMoving so the manual position sticks
                            e.gob.move(e.rc, e.a);   // live gob → reposition now; else the deferred create applies it
                        }
                    }
                }
                return a.arg1();
            }
        });
        h.set("rotate", new VarArgFunction() {          // V3: set facing (radians), keeping position — Gob.move(rc, a)
            public Varargs invoke(Varargs a) {
                LuaValue av = a.arg(2);
                if(!av.isnumber())
                    throw new LuaError(":rotate(a) expects a facing angle in radians (number) — use a COLON call");
                synchronized(e) {
                    if(!e.dead) {
                        e.a = av.todouble();
                        if(e.gob != null)
                            e.gob.move(e.rc, e.a);
                    }
                }
                return a.arg1();
            }
        });
        h.set("alpha", new VarArgFunction() {           // V3: opacity 0..1 (1 = opaque)
            public Varargs invoke(Varargs a) {
                LuaValue av = a.arg(2);
                if(!av.isnumber())
                    throw new LuaError(":alpha(a) expects a number 0..1 (1 = opaque) — use a COLON call");
                setEntityAlpha(e, clampAlpha(av.todouble()));
                return a.arg1();
            }
        });
        h.set("tint", new VarArgFunction() {            // V3: colour overlay {r=,g=,b=[,a=]}, or nil to clear
            public Varargs invoke(Varargs a) {
                LuaValue cv = a.arg(2);
                if(!cv.isnil() && !cv.istable())
                    throw new LuaError(":tint(color) expects {r=,g=,b=[,a=]} (0..255) or nil — use a COLON call");
                setEntityTint(e, cv.isnil() ? null : luaColor(cv, null));
                return a.arg1();
            }
        });
        h.set("scale", new VarArgFunction() {           // V6: uniform scale (1 = original size)
            public Varargs invoke(Varargs a) {
                LuaValue sv = a.arg(2);
                if(!sv.isnumber())
                    throw new LuaError(":scale(s) expects a positive number (1 = original size) — use a COLON call");
                setEntityScale(e, clampScale(sv.todouble()));
                return a.arg1();
            }
        });
        h.set("show", new VarArgFunction() {            // V3: (re)add the scene slot
            public Varargs invoke(Varargs a) { showEntity(e); return a.arg1(); }
        });
        h.set("hide", new VarArgFunction() {            // V3: remove the scene slot (keeps the entity)
            public Varargs invoke(Varargs a) { hideEntity(e); return a.arg1(); }
        });
        h.set("follow", new VarArgFunction() {          // ANCHOR: track a gob automatically (like a gob overlay)
            public Varargs invoke(Varargs a) {
                LuaValue ref = a.arg(2), offv = a.arg(3);
                if(ref.isnil()) {                        // :follow(nil) → detach, hold current position
                    setEntityFollow(e, 0, null);
                } else {
                    setEntityFollow(e, followTargetId(ref), luaOffset(offv));
                }
                return a.arg1();
            }
        });
        h.set("offset", new VarArgFunction() {          // ANCHOR: the fixed world offset from the followed gob
            public Varargs invoke(Varargs a) {
                LuaValue ov = a.arg(2);
                if(!ov.isnil() && !ov.istable())
                    throw new LuaError(":offset{x=,y=,z=} expects a table of world-unit offsets (or nil to clear) — use a COLON call");
                setEntityOffset(e, ov.isnil() ? null : luaOffset(ov));
                return a.arg1();
            }
        });
        h.set("pos", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable t = new LuaTable();
                synchronized(e) {
                    Coord2d rc = entityWorldPos(e);      // the LIVE position (the followed gob's, while anchored)
                    t.set("x", LuaValue.valueOf(rc.x));
                    t.set("y", LuaValue.valueOf(rc.y));
                    t.set("a", LuaValue.valueOf(e.a));
                    t.set("scale", LuaValue.valueOf((double)e.scale));   // V6: the full transform is {x,y,a,scale}
                    if(e.followTgt != 0)
                        t.set("following", LuaValue.valueOf((double)e.followTgt));   // the anchored gob id, if any
                }
                return t;
            }
        });
        h.set("destroy", new VarArgFunction() {
            public Varargs invoke(Varargs a) { destroyEntity(e); return a.arg1(); }
        });
    }

    /**
     * The Lua handle for a {@link LuaGhost} (V1 + V2 + V3): the shared entity verbs ({@link #addEntityHandle}) plus
     * the ghost-only {@code :res()} / {@code :setRes(res[,sdt])} (V3, swap the {@code .res} model) and
     * {@code :clickable(bool)} (V2 — pick-selectability, which needs the ghost-scoped V2 click dispatch). A
     * mutation before the deferred create has published the gob updates the desired-state the create will apply;
     * afterwards it acts on the live gob.
     */
    private static LuaValue ghostHandle(final LuaGhost gh) {
        LuaTable h = new LuaTable();
        addEntityHandle(h, gh);
        h.set("setRes", new VarArgFunction() {          // V3: swap the visual (streams in like new)
            public Varargs invoke(Varargs a) {
                LuaValue resv = a.arg(2), sdtv = a.arg(3);
                if(!resv.isstring())
                    throw new LuaError("ghost:setRes(res [, sdt]) expects a resource name string — use a COLON call");
                setGhostRes(gh, resv.tojstring(), luaSdt(sdtv));
                return a.arg1();
            }
        });
        h.set("res", new ZeroArgFunction() {
            public LuaValue call() { return (gh.resName == null) ? LuaValue.NIL : LuaValue.valueOf(gh.resName); }
        });
        h.set("clickable", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                setEntityClickable(gh, a.arg(2).toboolean());   // g:clickable(true|false); default (no arg) → false
                return a.arg1();
            }
        });
        return h;
    }

    /**
     * {@code hafen.ghost.list([filter])} — this addon's live ghosts as an array of their (stable) handles.
     * Canonical filter adapted to handles: {@code nil} = all; a <b>string</b> = substring match on the ghost's
     * {@code res} name; a <b>function</b> = called with the ghost <i>handle</i> (so it can call {@code :pos()}
     * etc.), truthy keeps it (errors drop it). Dead/failed ghosts are skipped.
     */
    private static LuaValue ghostList(Addon owner, LuaValue filter) {
        LuaTable out = new LuaTable();
        int i = 0;
        for(LuaGhost gh : owner.ghosts) {              // copy-on-write: a filter fn may create/destroy a ghost
            if(gh.dead || (gh.handle == null))
                continue;
            if(entityMatches(filter, gh))
                out.set(++i, gh.handle);
        }
        return out;
    }

    /**
     * Does world entity {@code e} pass {@code filter}? The canonical filter adapted to handles: {@code nil} = all;
     * a <b>string</b> = substring match on the entity's {@link LuaWorldEntity#visualName()} (a ghost's {@code res}
     * name / a sprite's image path); a <b>function</b> = called with the entity <i>handle</i> (so it can call
     * {@code :pos()} etc.), truthy keeps it (errors drop it). Shared by ghost and (future) sprite listings.
     */
    private static boolean entityMatches(LuaValue filter, LuaWorldEntity e) {
        if((filter == null) || filter.isnil())
            return true;
        if(filter.isfunction()) {
            try {
                return filter.call(e.handle).toboolean();
            } catch(RuntimeException ex) {   // LuaError is a RuntimeException
                return false;
            }
        }
        if(filter.isstring()) {
            String nm = e.visualName();
            return (nm != null) && nm.contains(filter.tojstring());
        }
        return true;
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

    // ---- R3: custom 3D models in the world (hafen.render.object) -----------------------------------------------

    /**
     * {@code hafen.render.object{model, x, y [, a] [, scale] [, alpha] [, tint] [, clickable] [, onClick] [, follow]
     * [, offset]}} (R3a): stand a custom glTF model in the 3D world — the mesh sibling of a sprite/ghost, on the same
     * virtual-entity core (spec 18 §3). Validates the options, resolves the {@code model} (a {@code hafen.asset}
     * mesh handle or an addon-relative path, auto-loaded + cached), builds a {@link GhostGob}, attaches a {@link MeshSprite}
     * ({@code SprDrawable}), and adds it to the MapView {@code basic} scene ({@link MapView#addClientGob}). Everything
     * else — transform, look, {@code follow} anchor, {@code clickable}/{@code onClick}, gizmo — is shared with sprites.
     * Because the geometry is already decoded ({@link Gltf}), there is NO {@code Loading} to dodge — the gob is built
     * and published <b>synchronously</b> on the calling UI thread (mirrors {@link #newSprite}). Registered in the
     * addon's owned-resource registry (P2). Returns {@code nil} if there is no map view (not in the world); throws a
     * {@link LuaError} for a malformed table / a bad {@code model}.
     */
    private static LuaValue newObject(Addon owner, LuaValue opts) {
        if(!opts.istable())
            throw new LuaError("hafen.render.object{model=..., x=..., y=...} expects an options table");
        LuaValue xv = opts.get("x"), yv = opts.get("y");
        LuaValue followv = opts.get("follow");
        boolean hasFollow = !followv.isnil();
        if(!hasFollow && (!xv.isnumber() || !yv.isnumber()))
            throw new LuaError("hafen.render.object: 'x' and 'y' must be numbers (world coordinates, like gob:pos()) — or pass follow=gob instead");
        final MapView mv = view;
        final Glob g = glob();
        if((mv == null) || (g == null))
            return LuaValue.NIL;                       // not in the world yet — no scene to add to
        LuaMesh mesh = resolveObjectMesh(owner, opts.get("model"));   // AFTER the world check (don't parse when not in world)
        LuaValue av = opts.get("a");
        double a = av.isnumber() ? av.todouble() : 0.0;
        Coord2d rc = new Coord2d(xv.optdouble(0.0), yv.optdouble(0.0));   // 0,0 placeholder when following
        LuaObject ob = new LuaObject(owner, mesh, rc, a);
        ob.alpha = luaAlpha(opts.get("alpha"));
        ob.tint = luaTint(opts.get("tint"));
        ob.scale = luaScale(opts.get("scale"));        // uniform scale on top of the baked model→world size
        ob.clickable = opts.get("clickable").toboolean();
        LuaValue onclickv = opts.get("onClick");
        if(onclickv.isfunction())
            ob.onClick = onclickv;
        if(hasFollow) {
            ob.followTgt = followTargetId(followv);
            ob.followOff = luaOffset(opts.get("offset"));
        }
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
            if(ob.dead) { gob.dispose(); return handle; }   // destroyed mid-build (defensive; all UI-thread)
            ob.gob = gob;
            ob.mv = mv;
            applyEntityFollow(ob, gob);                 // if follow= was given, start tracking the gob now
            if(!ob.hidden)
                ob.slot = mv.addClientGob(gob);         // the // addon: MapView seam (spec 16 §6); MapView now ticks it
        }
        return handle;
    }

    /**
     * The Lua handle for a {@link LuaObject} (R3): the shared entity verbs ({@link #addEntityHandle}) plus the
     * object's {@code :mesh()} identity accessor (its addon-relative model path) and {@code :clickable(bool)} (the
     * V2 pick surface, mirroring a sprite/ghost). No {@code :setRes}/{@code :setModel} — an object's mesh is fixed at create (R3a).
     */
    private static LuaValue objectHandle(final LuaObject ob) {
        LuaTable h = new LuaTable();
        addEntityHandle(h, ob);
        h.set("mesh", new ZeroArgFunction() {
            public LuaValue call() { return (ob.meshName == null) ? LuaValue.NIL : LuaValue.valueOf(ob.meshName); }
        });
        h.set("clickable", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                setEntityClickable(ob, a.arg(2).toboolean());   // o:clickable(true|false); default (no arg) → false
                return a.arg1();
            }
        });
        return h;
    }

    /**
     * Resolve the object {@code model=} option to a live {@link LuaMesh}: a {@code hafen.asset} mesh handle (or its
     * raw backing userdata), or an addon-relative <b>path</b> string (auto-loaded + interned through
     * {@link AssetApi#load}, D-017-sandboxed). Throws a {@link LuaError} for anything else / a disposed model.
     * (028.2 makes this <b>handle-only</b> — one flow, D-012.)
     */
    private static LuaMesh resolveObjectMesh(Addon owner, LuaValue modelv) {
        LuaMesh lm = modelv.isstring() ? LuaMesh.resolve(AssetApi.load(owner, modelv.tojstring()))
                                       : LuaMesh.resolve(modelv);                   // a handle or its raw userdata
        if((lm == null) || lm.dead)
            throw new LuaError("hafen.render.object: 'model' must be a hafen.asset mesh handle (hafen.asset(\"chair.glb\")) or an addon-relative path string");
        return lm;
    }

    /** Tear down every object this addon owns (reload/disable/relogin, P2): destroy each (slot removed + Models freed). */
    static void teardownObjects(Addon a) {
        if(a.objects.isEmpty())
            return;
        for(LuaObject ob : new ArrayList<LuaObject>(a.objects))
            destroyEntity(ob);          // removes each from a.objects as it goes (copy-on-write list)
    }

    // ---- R2: custom world sprites (hafen.render.sprite) --------------------------------------------------------

    /**
     * {@code hafen.render.sprite{image, x, y [, a] [, scale] [, alpha] [, tint] [, billboard] [, clickable] [, onClick]}}
     * (R2): stand a custom PNG in the 3D world — the non-{@code .res} sibling of a ghost, on the same virtual-entity
     * core (spec 17 §5). Validates the options, resolves the {@code image} (a {@code hafen.asset} image handle or an
     * addon-relative path, auto-loaded + cached), builds a {@link GhostGob}, attaches the visual, and adds it to the
     * MapView {@code basic} scene ({@link MapView#addClientGob}). The visual is the ONLY thing {@code billboard}
     * selects: {@code false} (default) → a resource-free {@link SprDrawable} quad ({@link SpriteQuad}) standing
     * upright, sized to the image aspect (R2a); {@code true} → a resource-free {@link LuaSpriteBillboard} camera-facing
     * screen blit (R2b). Everything else — transform, look, {@code follow} anchor, gizmo — is shared. Unlike a ghost
     * the texture is already decoded, so there is NO {@code Loading} to dodge — the gob is built and published
     * <b>synchronously</b> on the calling UI thread (the handle's gob is live before it is returned). {@code clickable}
     * (+ per-sprite {@code onClick}) opts a <b>fixed</b> sprite into the V2 pick dispatch (its quad renders into the
     * clickmap); a <b>billboard</b> has no world mesh so it is never picked (the flag is a harmless no-op). Registered
     * in the addon's owned-resource registry (P2). Returns {@code nil} if there is no map view (not in the world);
     * throws a {@link LuaError} for a malformed table.
     */
    private static LuaValue newSprite(Addon owner, LuaValue opts) {
        if(!opts.istable())
            throw new LuaError("hafen.render.sprite{image=..., x=..., y=...} expects an options table");
        LuaValue xv = opts.get("x"), yv = opts.get("y");
        LuaValue followv = opts.get("follow");         // ANCHOR: follow a gob (a Gob object), optional
        boolean hasFollow = !followv.isnil();
        if(!hasFollow && (!xv.isnumber() || !yv.isnumber()))   // x/y are the placement; when following, the gob supplies it
            throw new LuaError("hafen.render.sprite: 'x' and 'y' must be numbers (world coordinates, like gob:pos()) — or pass follow=gob instead");
        boolean billboard = opts.get("billboard").toboolean();   // R2b: true = camera-facing screen blit; false = fixed world quad
        final MapView mv = view;
        final Glob g = glob();
        if((mv == null) || (g == null))
            return LuaValue.NIL;                       // not in the world yet — no scene to add to
        LuaImage img = resolveSpriteImage(owner, opts.get("image"));   // AFTER the world check (don't load when not in world)
        LuaValue av = opts.get("a");
        double a = av.isnumber() ? av.todouble() : 0.0;
        Coord2d rc = new Coord2d(xv.optdouble(0.0), yv.optdouble(0.0));   // 0,0 placeholder when following (the gob overrides)
        LuaSprite sp = new LuaSprite(owner, img, rc, a, billboard);
        sp.alpha = luaAlpha(opts.get("alpha"));        // opacity 0..1 (default 1); combines with the PNG's own alpha
        sp.tint = luaTint(opts.get("tint"));           // colour overlay {r=,g=,b=[,a=]}, or null
        sp.scale = luaScale(opts.get("scale"));        // uniform scale (fixed quad: ~1 tile tall; billboard: screen-size ×)
        sp.clickable = opts.get("clickable").toboolean();   // R2b: opt-in pick (fixed sprites only; a billboard has no world mesh → never picked)
        LuaValue onclickv = opts.get("onClick");       // R2b: per-sprite click callback fn(s, button, x, y) — like a ghost
        if(onclickv.isfunction())
            sp.onClick = onclickv;
        if(hasFollow) {                                // ANCHOR: track a gob every frame (the gob-overlay analog)
            sp.followTgt = followTargetId(followv);
            sp.followOff = luaOffset(opts.get("offset"));   // {x=,y=,z=} world offset from the gob (default none)
        }
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
        if(billboard) {
            gob.setattr(new LuaSpriteBillboard(gob, img));               // camera-facing screen blit (reads the look live each frame)
        } else {
            float[] wh = spriteWorldDims(img.sz);
            gob.setattr(new SprDrawable(gob, SpriteQuad.mill(img.tex, wh[0], wh[1])));   // resource-free textured quad
        }
        gob.move(rc, a);
        synchronized(sp) {
            if(sp.dead) { gob.dispose(); return handle; }   // destroyed mid-build (defensive; all UI-thread) → discard
            sp.gob = gob;
            sp.mv = mv;
            applyEntityFollow(sp, gob);                 // ANCHOR: if follow= was given, start tracking the gob now
            if(!sp.hidden)                              // a sprite hidden before it published stays out of the scene
                sp.slot = mv.addClientGob(gob);         // the // addon: MapView seam (spec 16 §6); MapView now ticks it
        }
        return handle;
    }

    /**
     * The Lua handle for a {@link LuaSprite} (R2): the shared entity verbs ({@link #addEntityHandle}) plus the
     * sprite's {@code :image()} identity accessor (its addon-relative path) and {@code :clickable(bool)} (R2b — the
     * V2 pick surface, mirroring a ghost). {@code :clickable} works on a <b>fixed</b> sprite (its quad renders into
     * the clickmap); on a billboard it is a harmless no-op (no world mesh to pick). No {@code :setRes} — a sprite's
     * visual (its image) is fixed at create.
     */
    private static LuaValue spriteHandle(final LuaSprite sp) {
        LuaTable h = new LuaTable();
        addEntityHandle(h, sp);
        h.set("image", new ZeroArgFunction() {
            public LuaValue call() { return (sp.imgName == null) ? LuaValue.NIL : LuaValue.valueOf(sp.imgName); }
        });
        h.set("clickable", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                setEntityClickable(sp, a.arg(2).toboolean());   // s:clickable(true|false); default (no arg) → false
                return a.arg1();
            }
        });
        return h;
    }

    /**
     * Resolve the sprite {@code image=} option to a live {@link LuaImage}: a {@code hafen.asset} image handle (or
     * its raw backing userdata), or an addon-relative <b>path</b> string (auto-loaded + interned through
     * {@link AssetApi#load}, D-017-sandboxed). Throws a {@link LuaError} for anything else / a disposed image.
     * (028.2 makes this <b>handle-only</b> — one flow, D-012.)
     */
    private static LuaImage resolveSpriteImage(Addon owner, LuaValue imgv) {
        LuaImage li = imgv.isstring() ? LuaImage.resolve(AssetApi.load(owner, imgv.tojstring()))
                                      : LuaImage.resolve(imgv);                    // a handle or its raw userdata
        if((li == null) || li.dead)
            throw new LuaError("hafen.render.sprite: 'image' must be a hafen.asset image handle (hafen.asset(\"icon.png\")) or an addon-relative path string");
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

    // ---- ANCHOR: follow a gob (hafen.render.sprite / hafen.ghost :follow) — the world-space gob-overlay analog ---

    /**
     * Anchor an entity to a target gob ({@code :follow(gob[, offset])}) or detach it ({@code tgt == 0}). While
     * anchored, a {@link FollowMoving} on the gob makes the render tree place the entity at the target's live
     * position + {@code off} <b>every frame</b> — no Lua polling (the {@code hafen.ui.gobOverlay} analog for a
     * world entity). Detaching freezes it at the current followed position. Records the desired state so a
     * still-pending (deferred ghost) create attaches it on publish ({@link #applyEntityFollow}). Under the entity
     * monitor; the attrib attach/detach is done under {@code synchronized(gob)} (the lock the live res-swap uses).
     */
    private static void setEntityFollow(LuaWorldEntity e, long tgt, Coord3f off) {
        synchronized(e) {
            if(e.dead)
                return;
            e.followTgt = tgt;
            e.followOff = off;
            Gob gob = e.gob;
            if(gob == null)
                return;                                  // no live gob yet → the pending create applies the follow
            synchronized(gob) {
                if(tgt != 0) {
                    gob.setattr(new FollowMoving(gob, tgt, off));   // start following — autotick picks it up next frame
                } else {
                    Moving m = gob.getattr(Moving.class);
                    if(m instanceof FollowMoving) {                 // detach: freeze at the last followed point
                        Coord3f cur;
                        try { cur = gob.getc(); } catch(RuntimeException ex) { cur = null; }
                        gob.delattr(Moving.class);
                        if(cur != null)
                            e.rc = new Coord2d(cur.x, cur.y);        // hold there (ground z re-derived at placement)
                        gob.move(e.rc, e.a);
                    }
                }
            }
        }
    }

    /**
     * Update the fixed world offset of an anchored entity ({@code :offset{x=,y=,z=}}). Live on the
     * {@link FollowMoving} (its {@code off} is {@code volatile}) → takes effect next frame with no re-attach; stored
     * on the entity for a still-pending create too. This is the "move it relative to the gob while it keeps
     * following" verb (a manual {@code :move} would instead detach). No-op if dead. Under the entity monitor.
     */
    private static void setEntityOffset(LuaWorldEntity e, Coord3f off) {
        synchronized(e) {
            if(e.dead)
                return;
            e.followOff = off;
            if(e.gob != null) {
                Moving m = e.gob.getattr(Moving.class);
                if(m instanceof FollowMoving)
                    ((FollowMoving)m).off = off;
            }
        }
    }

    /** Drop any {@link FollowMoving} from {@code gob} (a manual {@code :move} detaches the follow). Under {@code synchronized(gob)}. */
    private static void detachFollowAttr(Gob gob) {
        synchronized(gob) {
            if(gob.getattr(Moving.class) instanceof FollowMoving)
                gob.delattr(Moving.class);
        }
    }

    /**
     * Attach the {@link FollowMoving} at (deferred/immediate) create time when the entity is anchored — called by
     * {@code newSprite}/{@code newGhost} once the gob is built (before it enters the scene), so a {@code :follow}
     * that landed before the visual streamed in is honoured. Caller holds the entity monitor; the fresh gob is not
     * yet published, so no {@code synchronized(gob)} is needed.
     */
    private static void applyEntityFollow(LuaWorldEntity e, Gob gob) {
        if(e.followTgt != 0)
            gob.setattr(new FollowMoving(gob, e.followTgt, e.followOff));
    }

    /**
     * Resolve a {@code :follow} / {@code follow=} target to a gob id: a read-API <b>Gob object</b> (D-044). The gob
     * need not be loaded — {@link FollowMoving} re-resolves each frame, and {@code hafen.gob(id)} builds a handle
     * for a gob that hasn't streamed in yet, so the pre-load anchor still works. Raw ids and the old GobRef tokens
     * are refused (that would be the dual style D-013 forbids).
     */
    private static long followTargetId(LuaValue ref) {
        LuaGob h = LuaGob.resolve(ref);
        if(h == null)
            throw new LuaError("follow expects a Gob object (hafen.gob(id) / hafen.player():gob()) — raw ids and the \"player\"/\"me\"/\"partyN\" tokens are gone");
        return h.id;
    }

    /** Parse a {@code {x=,y=,z=}} world-offset table → a {@link Coord3f} (missing components 0), or {@code null} (not a table). */
    private static Coord3f luaOffset(LuaValue v) {
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
    private static float clampAlpha(double a) {
        return (a < 0.0) ? 0f : ((a > 1.0) ? 1f : (float)a);
    }

    /** Parse a ghost {@code scale} option/arg → clamped positive (0.01..100); a non-number defaults to 1 (original size). */
    private static float luaScale(LuaValue v) {
        return v.isnumber() ? clampScale(v.todouble()) : 1f;
    }
    private static float clampScale(double s) {
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
