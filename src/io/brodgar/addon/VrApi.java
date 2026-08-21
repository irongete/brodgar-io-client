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
import haven.MiniMap;
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

import io.brodgar.addon.AddonManager.SessionState;

import static io.brodgar.addon.AddonManager.*;

/**
 * {@code hafen.vr()} — <b>the one section for client-only things standing in the 3D world</b> (043): the
 * {@code .res} props {@code hafen.ghost()} used to be, and the custom PNG + glTF props {@code hafen.render()}
 * used to be, under one name. What separates these from the gobs in {@code s:world()} is not where they
 * are — both are in the world — but <b>whose</b> they are: nothing here ever reaches the server. Owns the
 * world-entity lifecycle (create/transform/follow/click/teardown) over {@link LuaWorldEntity}. The click seam
 * {@code onGhostClick} (called from {@code haven.MapView}) stays a facade in {@link AddonManager} and delegates
 * here; {@link AddonManager} calls the per-kind teardowns on reload/disable.
 *
 * <p><b>Three collections, one entity vocabulary.</b> Each kind is a {@link LuaCollection} — {@code :add(what,
 * p)} places one and hands back its handle, {@code :list(filter)} reads them, and {@code :remove(x)} ends one
 * (R7: the collection placed it, so the collection ends it). Every handle then speaks the same verbs, each a
 * read/write pair on one name: {@code :position(p [, a])}, {@code :rotate}, {@code :scale}, {@code :alpha},
 * {@code :tint}, {@code :visible}, {@code :clickable}, {@code :onClick} — plus the one or two its own kind
 * adds. Every retired spelling, {@code hafen.ghost} and {@code hafen.render} included, is a {@link Retired} row
 * naming what replaced it.
 *
 * <p><b>The dispatch is a table of collections, not a branch</b> — {@link #installVr} registers each kind by
 * name, so a fourth ({@code :widget()}, 044) is one more line rather than a shape to re-open.
 *
 * <p><b>This is a SCENE section, not a loader</b> (028.1): the addon's own files — images, fonts and meshes
 * alike — come from the one door {@link AssetApi} ({@code hafen.asset():get(path)}), which also owns the D-017
 * sandbox resolver and the intern cache. Since 043.3 there is <b>no second door</b>: {@code gob:overlay()}'s
 * world kinds are gone, so every client-only thing standing in the world is created here, listed here and ended
 * here — a gob it is anchored to shows it read-only ({@link #anchoredMembers}) and nothing more. Not instantiable.
 */
final class VrApi {
    private VrApi() {}

    /**
     * Build {@code hafen.vr()} for {@code owner}: a section whose verbs are its <b>collections</b>, each minted
     * once and handed back by identity (§2.3). {@code hafen.vr():ghost():add(res, p)} places a client-only
     * {@code .res} prop and hands back the Ghost, {@code :list(filter)} reads this addon's, and
     * {@code hafen.vr():ghost():remove(g)} ends one — the collection owns it, so R7 puts the ending there rather
     * than on a {@code :destroy()} of its own; {@code :sprite()} and {@code :object()} answer the same verbs over
     * the addon's own PNGs and glTF models.
     *
     * <p>The kinds are <b>registered</b> rather than branched on, so 044's {@code :widget()} is one more
     * {@link #collection} line and nothing here has to be re-opened to admit it.
     *
     * <p><b>Two verbs read and write the section as a whole</b> (043.4), and both are the answer to a question a
     * per-kind collection cannot be asked: {@code :list(filter)} is <i>everything you have stood in the world</i>,
     * across the kinds and in the order you stood it, and {@code :visible(b)} takes the lot off screen and puts it
     * back. They span the registered kinds through {@link #allEntities}, so the fourth joins them for free.
     */
    static void installVr(LuaTable hafen, final Addon owner) {
        LuaTable m = new LuaTable();
        collection(m, "ghost", ghostCollection(owner));
        collection(m, "sprite", spriteCollection(owner));
        collection(m, "object", objectCollection(owner));
        collection(m, "widget", widgetCollection(owner));
        // list(filter) — every entity this addon has standing, all three kinds at once, as the plain 1-based
        // array a collection's own :list() hands back. The filter is the canonical one (§2.3, and the very
        // function the per-kind lists use), so it means here exactly what it means one verb down.
        m.set("list", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "vr", "list");
                LuaValue filter = a.arg(2);
                LuaTable t = new LuaTable();
                int n = 0;
                for(LuaWorldEntity e : allEntities(owner)) {
                    if(LuaCollection.keeps(filter, e.handle, true, e.visualName(), "hafen.vr()", "list"))
                        t.set(++n, e.handle);
                }
                return t;
            }
        });
        // visible() / visible(b) — the section switch, a property like every other: arity is the verb, and the
        // write hands back the section so it chains. It is this ADDON's section; nobody else's entities move.
        m.set("visible", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                Section.self(self, "vr", "visible");
                LuaValue bv = Args.written(a, 2, "hafen.vr():visible", "b");
                if(bv == null)
                    return LuaValue.valueOf(!owner.vrHidden);
                setSectionVisible(owner, bv.toboolean());
                return self;
            }
        });
        // pointer(key, x, y [, a]) — put the pointer on whatever is STANDING at a screen point (044.4). The key
        // is one of the four a widget already answers to, so there is one input vocabulary and not two; x, y are
        // screen pixels in DESIGN space (058.2), the very numbers hafen.ui():mouse() and x:screen(wx, wy) report
        // — this verb is that one's inverse, so the two must read the same pair; a is the button (MouseDown/MouseUp,
        // default 1) or the wheel's amount. It hands back whether a panel took it — false meaning the point was
        // on none, which is the moment the client's own world click goes on exactly as it always did. This is
        // the client's path from the map view INWARD and stops there: it cannot move the character and it never
        // reaches the server, so it is unprotected like the rest of the section.
        m.set("pointer", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "vr", "pointer");
                String key = pointerKey(Args.required(a, 2, "hafen.vr():pointer", "key"));
                int x = (int)Math.round(number(a, 3, "hafen.vr():pointer", "x"));
                int y = (int)Math.round(number(a, 4, "hafen.vr():pointer", "y"));
                int arg = Args.passed(a, 5) ? (int)Math.round(number(a, 5, "hafen.vr():pointer", "a")) : 1;
                Coord p = Px.in(Coord.of(x, y));       // design → device, at the edge (058.2)
                return LuaValue.valueOf(SurfaceInput.pointer(key, p.x, p.y, arg));
            }
        });
        Section.install(hafen, "vr", m);
    }

    /**
     * <b>Everything this addon has standing in the world</b>, whatever kind — the one walk behind
     * {@code hafen.vr():list()} and {@code hafen.vr():visible(b)}. It reads the registered kinds' registries and
     * nothing else, so 044's fourth collection is covered by both verbs the moment its registry is added here.
     *
     * <p><b>In creation order</b>, by {@link LuaWorldEntity#eid} — the serial every entity already carries for its
     * read-only key at a gob. Concatenating the three registries would answer the same SET, but grouped by an
     * implementation detail; "the order you stood them" is a contract a reader can predict, and the sort is over
     * an addon's handful of entities.
     */
    private static List<LuaWorldEntity> allEntities(Addon owner) {
        List<LuaWorldEntity> out = new ArrayList<LuaWorldEntity>();
        collectLive(out, owner.ghosts);
        collectLive(out, owner.sprites);
        collectLive(out, owner.objects);
        collectLive(out, owner.surfaces);
        java.util.Collections.sort(out, BY_BIRTH);
        return out;
    }

    /** The live, published members of one registry (copy-on-write: a filter fn may create or destroy one). */
    private static void collectLive(List<LuaWorldEntity> out, List<? extends LuaWorldEntity> reg) {
        for(LuaWorldEntity e : reg) {
            if(!e.dead && (e.handle != null))
                out.add(e);
        }
    }

    /** Creation order across the kinds: the {@code eid} serial, which is monotonic for the client's life. */
    private static final java.util.Comparator<LuaWorldEntity> BY_BIRTH =
        new java.util.Comparator<LuaWorldEntity>() {
            public int compare(LuaWorldEntity x, LuaWorldEntity y) {
                return (x.eid < y.eid) ? -1 : ((x.eid > y.eid) ? 1 : 0);
            }
        };

    /**
     * {@code hafen.vr():visible(b)} — take this addon's whole section off screen, or put it back. <b>Destroys
     * nothing</b>: each entity keeps its gob, its transform and its handle, and only its scene slot goes, so
     * {@code :exists()} stays true and every verb still answers throughout.
     *
     * <p><b>Showing restores what was visible, not everything.</b> The switch is a second boolean beside the
     * entity's own {@link LuaWorldEntity#hidden} rather than a write over it, so an entity the addon had hidden
     * individually is skipped by both halves of this loop and simply stays hidden — and a {@code :visible(b)}
     * written while the section is off is remembered and takes effect when it comes back. Neither boolean is read
     * at draw time: the slot is added and removed here, exactly as {@link #hideEntity}/{@link #showEntity} do it
     * one entity at a time.
     */
    private static void setSectionVisible(Addon owner, boolean on) {
        if(owner.vrHidden == !on)
            return;                                    // already there: a no-op write touches no scene
        owner.vrHidden = !on;                          // set FIRST, so an entity published mid-loop reads it
        for(LuaWorldEntity e : allEntities(owner)) {
            synchronized(e) {
                if(e.dead || e.hidden)
                    continue;                          // its own state governs: an individually hidden one stays out
                if(shows(e))                           // ...and so does the ground under a free one (044.9)
                    attachScene(e);
                else
                    detachScene(e);
            }
        }
    }

    /** {@code hafen.vr():ghost()} — this addon's client-only world props, keyless (a ghost has no name of its own). */
    private static LuaValue ghostCollection(final Addon owner) {
        return LuaCollection.create("hafen.vr():ghost()", new LuaCollection.Source() {
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
                LuaValue rv = Args.required(a, 2, "hafen.vr():ghost():add", "res");
                if(!rv.isstring() || rv.isnumber())
                    throw new LuaError("hafen.vr():ghost():add(res, p) expects a resource NAME string (e.g."
                        + " \"gfx/terobjs/arch/logcabin\"), got " + rv.typename() + " — an image or a model this"
                        + " addon ships is hafen.vr():sprite():add(asset, p) / :object():add(asset, p)");
                Anchor an = anchorArg(a, "hafen.vr():ghost():add");
                return born(makeGhost(owner, an.spec(), rv.tojstring(), an.tgt, an.place), "hafen.vr():ghost():add");
            }

            public boolean destroyable() {
                return true;
            }

            public void removeMember(LuaValue x) {
                destroyEntity(memberArg(owner.ghosts, x, "hafen.vr():ghost():remove", "ghost"));
            }

            public String noGet() {
                return "a ghost has no key: hafen.vr():ghost():add(res, p) hands you the ghost it stands,"
                    + " and hafen.vr():ghost():find(filter) finds one you already put up";
            }
        }, null);
    }

    /**
     * Register one kind under {@code hafen.vr()}: the verb hands back <b>the collection itself</b>, never a
     * per-call view, so {@code hafen.vr():ghost() == hafen.vr():ghost()} and a section called in a draw callback
     * allocates nothing. Every kind goes through here, which is what keeps the section open to a fourth.
     */
    private static void collection(LuaTable m, final String nm, final LuaValue coll) {
        m.set(nm, new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "vr", nm);
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.vr():" + nm + "() takes no arguments — it IS the collection,"
                        + " and hafen.vr():" + nm + "():add(what, p) stands one in the world");
                return coll;
            }
        });
    }

    /** {@code hafen.vr():sprite()} — this addon's custom-PNG world sprites. */
    private static LuaValue spriteCollection(final Addon owner) {
        return LuaCollection.create("hafen.vr():sprite()", new LuaCollection.Source() {
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
                LuaValue img = Args.required(a, 2, "hafen.vr():sprite():add", "image");
                Anchor an = anchorArg(a, "hafen.vr():sprite():add");
                LuaTable spec = an.spec();
                spec.set("image", img);
                return born(makeSprite(owner, spec, an.tgt, an.place), "hafen.vr():sprite():add");
            }

            public boolean destroyable() {
                return true;
            }

            public void removeMember(LuaValue x) {
                destroyEntity(memberArg(owner.sprites, x, "hafen.vr():sprite():remove", "sprite"));
            }

            public String noGet() {
                return "a sprite has no key: hafen.vr():sprite():add(image, p) hands you the sprite it stands,"
                    + " and hafen.vr():sprite():find(filter) finds one you already put up";
            }
        }, null);
    }

    /** {@code hafen.vr():object()} — this addon's glTF world models. */
    private static LuaValue objectCollection(final Addon owner) {
        return LuaCollection.create("hafen.vr():object()", new LuaCollection.Source() {
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
                LuaValue mdl = Args.required(a, 2, "hafen.vr():object():add", "model");
                Anchor an = anchorArg(a, "hafen.vr():object():add");
                LuaTable spec = an.spec();
                spec.set("model", mdl);
                return born(makeObject(owner, spec, an.tgt, an.place), "hafen.vr():object():add");
            }

            public boolean destroyable() {
                return true;
            }

            public void removeMember(LuaValue x) {
                destroyEntity(memberArg(owner.objects, x, "hafen.vr():object():remove", "object"));
            }

            public String noGet() {
                return "an object has no key: hafen.vr():object():add(model, p) hands you the object it stands,"
                    + " and hafen.vr():object():find(filter) finds one you already put up";
            }
        }, null);
    }

    /**
     * {@code hafen.vr():widget()} — <b>this addon's own UI surfaces, standing in the world</b> (044). The fourth
     * collection, and the one that is not a picture: what it stands is a {@link haven.Widget} the addon already
     * has, so its {@code Draw}, its controls, its stylesheet rules and its callbacks are the ones it always had
     * — only where they are drawn changes. Keyed by nothing, filtered by the widget's caption (or its type when
     * it has none).
     *
     * <p><b>Both anchors</b> (044.2), through the very {@link #anchorArg} the other three kinds read:
     * {@code :add(w, p)} stands it at a point and it holds there, {@code :add(w, gob)} makes it follow that game
     * object and die with it. Nothing here says which — the anchor is an argument, so the fourth kind gained the
     * second form by passing {@code an.tgt} on.
     */
    private static LuaValue widgetCollection(final Addon owner) {
        return LuaCollection.create("hafen.vr():widget()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                return entityMembers(owner.surfaces);
            }

            public String needle(LuaValue member) {
                return visualNameOf(owner.surfaces, member);
            }

            /** These have a name (the widget's caption), so a string filter is a substring test over it. */
            public boolean named() {
                return true;
            }

            public boolean creatable() {
                return true;
            }

            public LuaValue addMember(Varargs a) {
                LuaValue wv = Args.required(a, 2, "hafen.vr():widget():add", "w");
                Anchor an = anchorArg(a, "hafen.vr():widget():add");
                return born(makeWidget(owner, an.spec(), wv, an.tgt, an.place), "hafen.vr():widget():add");
            }

            public boolean destroyable() {
                return true;
            }

            public void removeMember(LuaValue x) {
                destroyEntity(memberArg(owner.surfaces, x, "hafen.vr():widget():remove", "panel"));
            }

            public String noGet() {
                return "a panel has no key: hafen.vr():widget():add(w, p) hands you the panel it stands,"
                    + " and hafen.vr():widget():find(filter) finds one you already put up";
            }
        }, null);
    }

    /**
     * The live entities of one registry as their handles — the members of its collection. Since 043.3 that is
     * <b>all</b> of them: an anchored one is no longer an overlay's hidden visual, so there is nothing to hide
     * from the collection that placed it. The gob it follows shows it too, read-only, and that read is the copy
     * ({@link #anchoredMembers}) — this is the original.
     */
    private static List<LuaValue> entityMembers(List<? extends LuaWorldEntity> reg) {
        List<LuaValue> out = new ArrayList<LuaValue>();
        for(LuaWorldEntity e : reg) {              // copy-on-write: a filter fn may create/destroy one
            if(e.dead || (e.handle == null))
                continue;
            out.add(e.handle);
        }
        return out;
    }

    /**
     * Where a {@code :add(what, anchor)} stands its entity — <b>the anchor is an ARGUMENT, not a choice of door</b>
     * (043.2). A {@link LuaPosition} stands the thing at that point and it holds it; a {@link LuaGob} makes it
     * <i>follow</i> that game object, which is the same {@link FollowMoving} the world-space half of
     * {@code gob:overlay()} has always used — so the two forms differ by one field, not by a second creator.
     *
     * <p>An anchored entity still carries a point ({@link #rc}): it is the target's position at create, so the
     * gob it enters the scene beside is the right one from the very first frame rather than the world origin, and
     * so {@code :position()} has something truthful to fall back on while the target is still streaming.
     */
    private static final class Anchor {
        /**
         * Where it stands (free), or the target's position when it was anchored (followed). <b>Null when the
         * place is legal but this session cannot locate it</b> (045.2) — ground recorded in another segment,
         * and every overworld place while the player is in a cave. The entity is still created: it holds
         * {@link #place}, waits, and enters the scene by itself when that ground resolves.
         */
        final Coord2d rc;
        /** The gob id it follows, or {@code 0} when it stands where it was put. */
        final long tgt;
        /**
         * <b>The durable place a free entity will hold</b> (045.1) — the grid id and the offset within it that
         * {@link #rc} above was derived from. {@code null} when the entity follows a gob, whose place is the
         * gob's and is nothing of its own to keep.
         */
        final LuaPosition.Anchor place;

        Anchor(Coord2d rc, long tgt, LuaPosition.Anchor place) {
            this.rc = rc;
            this.tgt = tgt;
            this.place = place;
        }

        /**
         * The options table the {@code make*} bodies read the placement out of (their one shared shape). The
         * two keys are <b>absent</b> for a place this session cannot locate (045.2), which is how
         * {@link #optPlace} tells "here" from "not here yet" — an absent coordinate, never a zero, because
         * {@code 0, 0} is a real point in the world and would stand the thing at the map origin.
         */
        LuaTable spec() {
            LuaTable t = new LuaTable();
            if(rc != null) {
                t.set("x", LuaValue.valueOf(rc.x));
                t.set("y", LuaValue.valueOf(rc.y));
            }
            return t;
        }
    }

    /**
     * The anchor a {@code :add(what, anchor)} was given, as the argument spec {@code §2.5} calls positional:
     * <b>a required argument stays on the constructor where the thing is meaningless without it</b>. For a thing
     * standing in the 3D world that is not a matter of taste — the scene resolves the TILE under a gob the moment
     * it is added, so an entity with no place cannot enter the scene at all: at the world origin the engine raises
     * <i>waiting for map data</i>, which is not a state a builder may pass through. So an anchor is not a setter
     * with a default; it is half of what an entity IS.
     *
     * <p>Exactly two things are a place for one of these: a <b>Position</b> and a <b>Gob</b>. Anything else is
     * refused naming <i>both</i> — the mistake is not knowing which shape is wanted, so a message that names one
     * of them teaches half the verb.
     *
     * <p><b>The Position branch asks for a DURABLE place</b> (045.1), which is one of the two doors that changed
     * — {@link LuaPosition#anchorArg} rather than {@code worldArg}: what a thing standing in the world keeps is
     * the anchor, and a raw coordinate over ground nobody has recorded has none. The coordinate is then derived
     * from that anchor here, so what enters the scene and what the entity holds cannot disagree at birth.
     *
     * <p><b>And a place this session cannot LOCATE is no longer a refusal</b> (045.2): the derivation may
     * answer null — a place recorded in another segment, or any overworld place while the player is in a cave
     * — and the entity is created anyway, holding the place and waiting for it. The two questions the two
     * doors ask are therefore different in kind: <i>can this place be held at all</i> is answered now and
     * refused now, while <i>where is it this session</i> is answered again every time the world moves.
     */
    private static Anchor anchorArg(Varargs a, String verb) {
        LuaValue v = Args.required(a, 3, verb, "anchor");
        LuaGob lg = LuaGob.resolve(v);
        if(lg != null) {
            Gob g = anygob(lg.id);   // 079.3: the object, wherever it is held -- gob:exists()'s own question
            if(g == null)
                throw new LuaError(verb + "(what, gob): that gob is gone — it had already left the object cache"
                    + " when this call ran, so there is nothing to follow (read gob:exists() first, or place it"
                    + " at a point with " + verb + "(what, p))");
            Coord2d rc;
            synchronized(g) { rc = g.rc; }
            return new Anchor((rc == null) ? Coord2d.z : rc, lg.id, null);
        }
        if(LuaPosition.resolve(v) == null)
            throw new LuaError(verb + ": the anchor is a Position OR a Gob — a Position (gob:position(),"
                + " session:world():position(x, y)) stands it at that point, a Gob"
                + " (session:world():gob():get(id), session:player():gob()) makes it follow that object. Got "
                + v.typename());
        LuaPosition.Anchor place = LuaPosition.anchorArg(a, 3, verb, "p");
        return new Anchor(LuaPosition.worldOf(place, null), 0L, place);   // 045.2: null ⇒ not here yet, and that is legal
    }

    /**
     * The point one of the {@code make*} bodies is to stand its entity at, or <b>null</b> when the place it was
     * given cannot be located this session (045.2) — {@link Anchor#spec()} leaves the two keys absent for
     * exactly that case. Never a defaulted zero: the map origin is a real place, and defaulting to it would
     * put a thing that is merely waiting into the middle of the world.
     */
    private static Coord2d optPlace(LuaValue opts) {
        // The two keys are written together by Anchor#spec() or not at all, so they are read together, and
        // by TYPE — never isnumber()/optdouble(), which coerce a string that merely scans as a number
        // (Args states that rule once, for the whole bridge).
        LuaValue x = opts.get("x"), y = opts.get("y");
        if((x.type() != LuaValue.TNUMBER) || (y.type() != LuaValue.TNUMBER))
            return null;
        return new Coord2d(x.todouble(), y.todouble());
    }

    /** The point a fresh gob is built at — its place, or the origin as a placeholder it never stands at (045.2). */
    private static Coord2d birthPoint(Coord2d rc) {
        return (rc == null) ? Coord2d.z : rc;
    }

    // ---- ANCHORED, AND FREE: the index that lets an anchored entity die with its gob (043.2) ------------------

    // 075.3: BOTH INDEXES ARE THE CLIENT'S, one set however many characters are logged in. 073.4 had put them
    // under SessionState on the reasoning that a gob id "means a different object in the next session" and that
    // a free entity stands "in one session's coordinate frame"; both halves are wrong. A gob id is the SERVER'S
    // — docs/client/multi-session.md records one object observed by two sessions — and a free entity holds a
    // grid id and an offset within it (045.1), which is the server's own naming of a place and means the same
    // patch of ground to every character standing on it. What is per session is not the entity but the ARITHMETIC
    // that turns its place into a coordinate, and that is re-run against whichever session is drawn
    // (LuaPosition.worldOf), answering nothing when that character cannot see that ground.
    //
    // The anchored map was written for one reader, anchorGone: D-102 says the end of a derived thing rides the
    // event its source already raises, and the client already raises GobRemoved — but the record that made that
    // O(1) for a gob:overlay() lived ON THE GOB, and an entity hafen.vr():sprite():add(img, gob) placed has no
    // such record. One id->entities map restores the O(1) without restoring the thing D-100 deleted: it is
    // written only when an anchored entity is created or destroyed, and read only on an event about that one
    // gob. 043.3 gave it a second reader of exactly the same shape (anchoredMembers), and neither walks it per
    // frame. The free list is its mirror for the other anchor (044.9): a free entity has no gob to be reached
    // through, and the one thing that has to reach it is the ground moving under it, which is drainGround and
    // nothing else. Each is guarded by itself (creates run on the UI thread, a teardown may sweep from a
    // session-bind thread).

    /** Gob id &rarr; the entities anchored to that object, whichever character stood them there (043.2, 075.3). */
    private static final java.util.Map<Long, List<LuaWorldEntity>> anchored =
        new java.util.HashMap<Long, List<LuaWorldEntity>>();
    /** Every entity standing at a PLACE of its own — a grid id and an offset in it (044.9, 045.1, 075.3). */
    private static final List<LuaWorldEntity> free = new ArrayList<LuaWorldEntity>();

    /**
     * Index a freshly created entity (075.3): by target id when it follows a gob, in the flat free list when it
     * stands where it was put. A free one also has the ground under it read once, right here, so a create over
     * ground that is not drawn simply does not enter the scene (044.9) — and the cut arriving is what puts it
     * there, rather than a bounded {@link Resolve} retry chain on the {@code Loading} the add would have thrown.
     */
    private static void entityRegister(UI u, LuaWorldEntity e, LuaPosition.Anchor place) {
        e.ui = u;                                      // the tree whose scene it stands in RIGHT NOW (075.3)
        if(e.followTgt != 0) {
            Long k = Long.valueOf(e.followTgt);
            synchronized(anchored) {
                List<LuaWorldEntity> l = anchored.get(k);
                if(l == null)
                    anchored.put(k, l = new ArrayList<LuaWorldEntity>());
                l.add(e);
            }
            return;
        }
        synchronized(e) {
            // 045.1: the free half of the index is also where the free half of the PLACE lands — one line after
            // the split that already says which anchor this is, so "a free entity holds a durable place" is a
            // fact of construction rather than a rule four creators have to remember.
            e.anchorGrid = place.id;
            e.agx = place.x;
            e.agy = place.y;
            e.grounded = groundDrawn(e.rc);
        }
        synchronized(free) { free.add(e); }
    }

    /** Drop an entity from the index — every ending goes through {@link #destroyEntity}, so this is its one caller. */
    private static void entityUnregister(LuaWorldEntity e) {
        if(e.followTgt == 0) {
            synchronized(free) { free.remove(e); }
            return;
        }
        Long k = Long.valueOf(e.followTgt);
        synchronized(anchored) {
            List<LuaWorldEntity> l = anchored.get(k);
            if(l == null)
                return;
            l.remove(e);
            if(l.isEmpty())
                anchored.remove(k);
        }
    }

    /**
     * <b>An anchored entity dies with its gob</b> (D-102, generalized from the overlay to the free anchor):
     * called from the tick that drains the client's own {@code OCache} removal, just before {@code GobRemoved}
     * reaches Lua, so a handler already reads {@code :exists() == false}. A free entity is untouched — it was
     * never derived from anything, so nothing ends it but its own collection.
     *
     * <p><b>...and the gob is gone when NO session has it any more</b> (075.3). A removal is one character's
     * {@code OCache} dropping an object, which is what walking out of view does — and with two characters
     * logged in, the one who walked away must not end a thing standing on an object the other is looking
     * straight at. So the id is checked against every live session before anything is destroyed. The lookup
     * that comes first is the O(1) one: an id nothing is anchored to costs a map miss and returns.
     */
    static void anchorGone(long id) {
        Long k = Long.valueOf(id);
        synchronized(anchored) {
            if(!anchored.containsKey(k))
                return;                                // nothing of ours was standing there — the common case
        }
        if(seenAnywhere(id))
            return;                                    // another character still has that object in view
        List<LuaWorldEntity> l;
        synchronized(anchored) {
            l = anchored.remove(k);
        }
        if(l == null)
            return;
        for(LuaWorldEntity e : l) {
            try {
                destroyEntity(e);
            } catch(RuntimeException ex) {
                /* best-effort: one bad entity never stops the rest from being freed */
            }
        }
    }

    /**
     * <b>What this addon has standing at that gob</b> (043.3) — the entities {@code :add(what, gob)} anchored
     * there, in creation order, for the read-only entries {@code gob:overlay():list()} shows beside the addon's own
     * screen-space records and the game's own overlays. Another addon's are not in it, exactly as another addon's
     * overlay records are not: a collection is per addon, all the way down.
     *
     * <p>"What is at this gob?" therefore keeps ONE complete answer even though the thing itself now lives in
     * {@code hafen.vr()} — you read it there and you address it through the collection that owns it.
     */
    static List<LuaWorldEntity> anchoredMembers(Addon owner, Gob g) {
        List<LuaWorldEntity> out = new ArrayList<LuaWorldEntity>();
        if(g == null)
            return out;
        // 075.3: the id is the SERVER'S, so the same object read through either character's OCache finds the
        // same entities standing on it — which is what "one object observed by two sessions" has to mean.
        synchronized(anchored) {
            List<LuaWorldEntity> l = anchored.get(Long.valueOf(g.id));
            if(l == null)
                return out;
            for(LuaWorldEntity e : l) {
                if((e.owner == owner) && !e.dead && (e.handle != null))
                    out.add(e);
            }
        }
        return out;
    }

    /** The one this addon has standing at that gob under {@code key} ({@link LuaWorldEntity#overlayKey()}), or null. */
    static LuaWorldEntity anchoredAt(Addon owner, Gob g, String key) {
        for(LuaWorldEntity e : anchoredMembers(owner, g)) {
            if(e.overlayKey().equals(key))
                return e;
        }
        return null;
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
                + " scene, so place it once you are in the world (SessionEnteredWorld)");
        return e.handle;
    }

    /**
     * The entity a {@code :remove(x)} names: its own handle, and nothing else. It must belong to <i>this</i>
     * registry, so removing another addon's is a refusal rather than a silent miss.
     */
    private static LuaWorldEntity memberArg(List<? extends LuaWorldEntity> reg, LuaValue x, String verb, String what) {
        for(LuaWorldEntity e : reg) {
            if((e.handle != null) && (e.handle == x))
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

    // ---------------------------------------------------------- world ghosts (hafen.vr():ghost())

    /**
     * Build a ghost and publish it — the body of {@code hafen.vr():ghost():add}. {@code tgt != 0} anchors it to
     * that gob id (a {@link FollowMoving}, applied at publish so a still-streaming visual is anchored the moment
     * it lands); {@code tgt == 0} stands it where it was put. Since 043.2 the anchor is an ARGUMENT, and since
     * 043.3 that is the only way one is anchored at all — {@code gob:overlay()}'s world kinds are gone, so there
     * is no second creator and no flag saying which one called. Returns {@code null} when there is no map view
     * (not in the world). Every other option is read from {@code opts} exactly as before.
     */
    private static LuaGhost makeGhost(final Addon owner, LuaValue opts, final String resName, long tgt,
                                     LuaPosition.Anchor place) {
        final MapView mv = screenView();
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
        final LuaGhost gh = new LuaGhost(owner, resid, resName, optPlace(opts),   // 045.2: null ⇒ waiting for its place
                                         av.isnumber() ? av.todouble() : 0.0);
        gh.sdt = luaSdt(opts.get("sdt"));              // V3: optional spawn-data bytes (null ⇒ MessageBuf.nil)
        gh.alpha = luaAlpha(opts.get("alpha"));        // V3: opacity 0..1 (default 1 = opaque)
        gh.tint = luaTint(opts.get("tint"));           // V3: colour overlay {r=,g=,b=[,a=]}, or null
        gh.scale = luaScale(opts.get("scale"));        // V6: uniform scale (default 1 = original size)
        gh.followTgt = tgt;                            // 043.2: the ANCHOR, an argument of :add(what, gob)
        gh.clickable = clickablev.toboolean();         // V2: nil/false → not clickable; true → clickable
        if(onclickv.isfunction())
            gh.onClick = onclickv;
        owner.ghosts.add(gh);
        entityRegister(mv.ui, gh, place);              // 043.2: so it dies with the gob it follows (D-102),
                                                       //   or 044.9: with the ground under it when it is free
        LuaValue handle = ghostHandle(gh);
        gh.handle = handle;
        gh.streaming = true;                           // 075.3: a create is on its way — the scene pass leaves it alone
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
                    UI u = screen();
                    if(u != null)
                        u.error(clampMsg("addon: ghost resource '" + rnm + "' could not be loaded"));
                    synchronized(gh) { gh.failed = true; gh.streaming = false; }
                    return;
                }
                // Build the gob + drawable OUTSIDE the ghost lock (no scene mutation yet), then publish atomically.
                Coord2d rc0; double a0;
                synchronized(gh) {
                    if(gh.dead) return;
                    // 045.1: the place may have stopped resolving while the resource streamed in (the map was
                    // dropped under it), and since 045.2 it may never have resolved at all. The gob is still
                    // built — at the origin, as a placeholder it never stands at — because shows() is false
                    // with no coordinate, so it cannot enter the scene from here; the ground drain moves it
                    // and puts it in when the place resolves.
                    rc0 = birthPoint(gh.rc);
                    a0 = gh.a;
                }
                GhostGob gob = new GhostGob(g, rc0);      // V2/V3: a Gob subclass whose obstate adds the click surface + look
                gob.a = a0;
                gob.setattr(new ResDrawable(gob, res, (sdt == null) ? MessageBuf.nil : sdt));  // res cached now → no Loading here
                synchronized(gh) {
                    gh.streaming = false;                    // 075.3: whatever happens below, the create is done streaming
                    if(gh.dead) { gob.dispose(); return; }   // destroyed mid-build → discard (never added to scene)
                    gob.clickable = gh.clickable;            // V2: reflect opt-in clickability BEFORE the gob enters the scene
                    gob.alpha = gh.alpha;                    // V3: reflect the desired look before the first scene add
                    gob.tint = gh.tint;
                    gob.scale = gh.scale;                    // V6: reflect the desired scale before the first scene add
                    if(gh.rc != null)
                        gob.move(gh.rc, gh.a);               // apply any :move that landed while we were building
                    gh.gob = gob;
                    gh.mv = mv;
                    applyEntityFollow(gh, gob);              // ANCHOR: if follow= was given, start tracking the gob now
                    if(shows(gh))                            // hidden before it published — its own, or its whole section's — stays out
                        addToScene(gh, mv);                  // the // addon: MapView seam; Resolve retries when the tile is not here yet
                }
                // 075.3: it published into the scene that was drawn when the create STARTED, and a resource
                // takes as long as it takes — so ask the pass to check which scene that is now. One walk of
                // the free list, on the create and never again: the flag is what keeps this an event.
                groundDirty = true;
            }
        }, null);
        return gh;
    }

    /**
     * The Lua handle of a client-only world entity — <b>one vocabulary for all three kinds</b> (a
     * {@link LuaGhost}, a {@link LuaSprite}, a {@link LuaObject}), plus whatever {@code extra} verbs its own kind
     * adds ({@code :res} for a ghost, {@code :image}/{@code :facing} for a sprite, {@code :mesh} for an
     * object). Every property here is a read/write pair on ONE name (§2.2): {@code :scale()} reads and
     * {@code :scale(2)} writes and hands back the handle, so a placement is one statement.
     *
     * <p>The handle table itself is left <b>empty</b> and every name is answered by the metatable, which is what
     * lets a retired spelling ({@code :pos}, {@code :move}, {@code :show}, {@code :hide}, {@code :destroy}) throw
     * naming its replacement instead of reading as plain {@code nil} and failing one line later — and, since the
     * vocabulary is closed ({@link Retired#closedIndex}), lets a name that was never a verb throw too.
     *
     * <p><b>The receiver and the collection are two names, not one</b> (084.6). For the three picture kinds
     * they are the same word; a standing widget is spelled {@code panel} as a receiver — so its refusals are its
     * own and not the flat {@link LuaWidget}'s, which answers to {@code widget} — while the collection that
     * placed it is still {@code hafen.vr():widget()}. Every sentence naming the collection therefore takes
     * {@code sect}, and every sentence naming the thing in hand takes {@code kind}.
     *
     * @param sect       the collection verb that placed it: {@code hafen.vr():<sect>()}.
     * @param extraVocab the kind's own verbs, spelled as the tail of the shared sentence the refusal carries —
     *                   written here beside the {@code extra} table it describes, so the two cannot drift apart.
     */
    private static LuaValue entityHandle(final LuaWorldEntity e, final String kind, final String sect,
                                         LuaTable extra, final String extraVocab) {
        LuaTable m = new LuaTable();
        // position() -> a Position, the one type a place in the world has; position(p [, a]) moves it there, and
        // the optional second argument is the facing, because "put it there facing that way" is one act. It is
        // the read/write pair the old :pos()/:move(x, y, a) split across two names and two shapes.
        //
        // The READ answers where the thing actually is, which for an anchored one is the gob's live point. The
        // WRITE is refused there (043.2): a FollowMoving owns the point and re-supplies it every frame, so the
        // write would be a silent no-op — the one outcome an API this size should never hand back.
        m.set("position", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                if(!Args.passed(a, 2))
                    return entityPosition(e.owner, e);
                if(e.followTgt != 0)
                    throw new LuaError(kind + ":position(p): this " + kind + " follows a gob, so its place is"
                        + " that gob's and setting it would be undone on the next frame. Where it sits RELATIVE"
                        + " to that gob is " + kind + ":offset(x, y, z) (world units, z up); its own facing is"
                        + " still " + kind + ":rotate(a); a " + kind + " that stands still is placed with"
                        + " hafen.vr():" + sect + "():add(what, p)");
                // 045.1: the SECOND of the two doors that ask for a durable place — a thing is moved to a
                // place it can go on holding, or it is not moved. The coordinate follows from the anchor,
                // and since 045.2 it may not be here yet: the same door :add uses, so moving something to
                // the far side of the world is the same act as placing it there, and it waits the same way.
                LuaPosition.Anchor place = LuaPosition.anchorArg(a, 2, kind + ":position", "p");
                Coord2d rc = LuaPosition.worldOf(place, null);
                Double ang = Args.passed(a, 3)
                    ? Double.valueOf(number(a, 3, kind + ":position", "a")) : null;
                moveEntity(e, place, rc, ang);
                return self;
            }
        });
        // offset() / offset(x, y, z) -- where an ANCHORED thing sits relative to the gob it follows, in world
        // units with z up (so 18 floats it about 1.6 tiles over the head). It is the world half of what
        // ov:offset(x, y, z) used to be: 043.3 took the world kinds out of gob:overlay(), and this is the verb
        // they brought with them, on the handle that owns the thing rather than on a record about it. Refused on
        // a FREE one naming :position(p) -- an offset from nothing is not a place, and the pair mirrors
        // :position's own refusal so each of the two anchors has exactly one verb that means "where".
        m.set("offset", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                if(e.followTgt == 0)
                    throw new LuaError(kind + ":offset(): this " + kind + " stands where it was put, so it is"
                        + " offset from nothing -- its place is " + kind + ":position(p). An offset is what a "
                        + kind + " placed with hafen.vr():" + sect + "():add(what, gob) sits at relative to that"
                        + " gob");
                if(!Args.passed(a, 2)) {
                    Coord3f off;
                    synchronized(e) { off = e.followOff; }
                    LuaTable t = new LuaTable();
                    t.set("x", LuaValue.valueOf((off == null) ? 0.0 : (double)off.x));
                    t.set("y", LuaValue.valueOf((off == null) ? 0.0 : (double)off.y));
                    t.set("z", LuaValue.valueOf((off == null) ? 0.0 : (double)off.z));
                    return t;
                }
                float x = (float)number(a, 2, kind + ":offset", "x");
                float y = (float)number(a, 3, kind + ":offset", "y");
                float z = Args.passed(a, 4) ? (float)number(a, 4, kind + ":offset", "z") : 0f;
                setEntityOffset(e, new Coord3f(x, y, z));
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
                moveEntity(e, null, null, Double.valueOf(number(a, 2, kind + ":rotate", "a")));
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
                    synchronized(e) { return AddonManager.color(e.tint); }
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
        // drawn() -- is it IN THE SCENE right now? Read-only, because every way of writing it already has a
        // name: :visible(b) is yours, hafen.vr():visible(b) is your section, and the ground under a free one
        // is the world's (044.9). This is what those three come to, plus the moment before a visual has
        // finished streaming in -- the one honest answer to "why can I not see it?". It says nothing about
        // where the camera is pointing: a panel standing behind you is drawn and merely culled, which is a
        // different question and p:surfaces() counts it.
        m.set("drawn", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError(kind + ":drawn() reads whether it is in the scene and does not write it"
                        + " -- taking it out and putting it back is " + kind + ":visible(b), and the ground"
                        + " under a free one coming and going is the world's answer, not a setting");
                synchronized(e) { return LuaValue.valueOf(!e.dead && (e.slot != null)); }
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
        // Userdata over the entity, the one shape every handle in the API has: e.tint = nil is refused —
        // an addon cannot delete its own way of putting a thing back — and tostring(e) names the kind and
        // what it is a picture of, so a log line of an addon's own entities reads.
        LuaValue h = LuaValue.userdataOf(e);
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex(kind, m,
            "a " + kind + " in the world answers :position() :offset() :rotate() :scale() :alpha() :tint() "
            + ":visible() :clickable() :onClick() :exists() :drawn()" + extraVocab));
        final String printed = Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
        mt.set("__name", LuaValue.valueOf(printed));
        mt.set("__tostring", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                String nm = e.visualName();
                return LuaValue.valueOf(printed + "(" + ((nm == null) ? "" : nm) + ")");
            }
        });
        h.setmetatable(mt);
        return h;
    }

    /** The four input keys, in the one spelling {@code widget:on(key, fn)} already uses (044.4). */
    private static final String POINTER_KEYS = "\"MouseDown\", \"MouseUp\", \"MouseMove\" or \"Wheel\"";

    /** The {@code key} of {@code hafen.vr():pointer(key, x, y)}, refused by name rather than ignored. */
    private static String pointerKey(LuaValue kv) {
        if(!kv.isstring())
            throw new LuaError("hafen.vr():pointer(key, x, y): key is one of " + POINTER_KEYS + ", got "
                + kv.typename());
        String s = kv.tojstring();
        if(s.equals("MouseDown") || s.equals("MouseUp") || s.equals("MouseMove") || s.equals("Wheel"))
            return s;
        throw new LuaError("hafen.vr():pointer(\"" + s + "\", x, y): the pointer says one of " + POINTER_KEYS
            + " — the same four keys widget:on(key, fn) answers to");
    }

    /** A required number argument, refused by name rather than silently coerced to zero. */
    private static double number(Varargs a, int i, String verb, String param) {
        LuaValue v = Args.required(a, i, verb, param);
        if(!v.isnumber())
            throw new LuaError(verb + ": " + param + " must be a number, got " + v.typename());
        return v.todouble();
    }

    /**
     * Move an entity and/or turn it: {@code place}/{@code rc} null keeps where it stands, {@code ang} null keeps
     * its facing. A live gob is repositioned now; one whose visual is still streaming in just has its desired
     * transform updated, and the create applies it at publish.
     *
     * <p><b>The place and the coordinate are written together</b> (045.1) — {@code place} is what the entity
     * keeps and {@code rc} is that place resolved in this session, so a mover that had only one of the two would
     * be writing half a position. {@code :rotate(a)} passes neither and is untouched by any of it.
     *
     * <p><b>{@code place} is therefore what says a MOVE happened</b>, not {@code rc} (045.2): a place this
     * session cannot locate resolves to a null coordinate, and writing that null is the whole point — the
     * entity is now somewhere else, that somewhere is not here, and it leaves the scene until it is.
     */
    private static void moveEntity(LuaWorldEntity e, LuaPosition.Anchor place, Coord2d rc, Double ang) {
        synchronized(e) {
            if(e.dead)
                return;                                // gone: a write to something that ended is a moment, not a mistake
            if(place != null) {
                e.anchorGrid = place.id;
                e.agx = place.x;
                e.agy = place.y;
                e.rc = rc;                             // 045.2: null ⇒ moved to a place that is not here yet
            }
            if(ang != null)
                e.a = ang.doubleValue();
            if((e.gob != null) && (e.rc != null))      // 045.1: no coordinate ⇒ nothing to move it to (and it is out of the scene)
                e.gob.move(e.rc, e.a);
            // 044.9: it may have been put down on ground that is drawn, or off the far edge of it. Asked
            // AFTER the gob has been moved, because attaching it reads the map where the gob now is; and
            // asked by the write itself rather than waited for, so a :position(p) onto drawn ground is in
            // the scene by the time it returns. Keyed on `place`, so a move to a place with no coordinate
            // this session takes it OUT by the same line that would have put it in (045.2).
            if((place != null) && (e.followTgt == 0)) {
                boolean g = groundDrawn(e.rc);
                if(g != e.grounded) {
                    e.grounded = g;
                    if(shows(e))
                        attachScene(e);
                    else
                        detachScene(e);
                }
            }
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
        return entityHandle(gh, "ghost", "ghost", x, " and :res()");
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
        entityUnregister(e);                          // 043.2/044.9: and out of whichever index it was in
        if(mv != null) {
            mv.removeClientGob(gob, slot);            // drops it from the MapView tick list + removes the slot (swallows SlotRemoved)
        } else if(slot != null) {
            try { slot.remove(); } catch(RuntimeException ex) { /* scene already gone (relog) */ }
        }
        if(gob != null) {
            try { gob.dispose(); } catch(RuntimeException ex) { /* best-effort: free the visual */ }
        }
        // ...and whatever this KIND has to undo beyond the scene (044.1): a standing widget puts its widget back
        // where it stood from and frees its surface. Last, and outside the monitor, so it runs on a thing that is
        // already out of the world — the same order the slot/gob teardown above uses.
        try { e.destroyed(); } catch(RuntimeException ex) { log("entity teardown error: " + ex); }
    }

    /**
     * A scene add's blocker is not always the SAME thing on every retry (see {@link Resolve}'s class doc,
     * the 042.12 finding): unlike {@code MCache.LoadingMap} ("this one tile hasn't streamed"),
     * {@code MapView.addClientGob} can also throw {@code Defer.NotDoneException} ("finalizing THIS texture
     * right now") for a <i>different</i>, unrelated GL upload on every retry while the render backend is
     * busy — observed in-game needing well over {@code Resolve}'s default bound of 8 during a heavy load.
     * The pre-042.12 {@code armPending} polled every tick with no bound at all, so this keeps that
     * resilience while staying a real retry-on-notify chain, never a poll (each step still only runs
     * because a specific texture's own decode completed).
     */
    private static final int SCENE_ADD_MAX_RETRIES = 128;

    /**
     * Add a freshly built entity to the scene, or register for whatever it is waiting on when it has not yet
     * (042.12) — a tile that hasn't streamed in ({@code MCache.LoadingMap}) or a texture still finalizing on
     * the render backend ({@code Defer.NotDoneException}, see {@link #SCENE_ADD_MAX_RETRIES}). Either way
     * {@code l} <b>is</b> the {@link haven.Waitable} that says when the blocker clears. {@link Resolve#on}
     * registers {@link #retryAdd} on it: marshalled onto the tick (never inline, even though
     * {@code Loading.waitfor} can fire its notify synchronously — {@link Resolve} only ever enqueues from
     * there), owned by the entity's addon so {@code :reload}/disable cancels a still-pending add cleanly,
     * and re-registering itself if the retry throws a <i>further</i>, possibly unrelated {@code Loading}. A
     * blocker that never clears simply leaves the entity out of the scene — the correct answer for an addon
     * asking for a place it cannot see. Caller holds the entity monitor; {@code Resolve.on} does not invoke
     * the callback while still inside it (see above).
     */
    private static void addToScene(final LuaWorldEntity e, final MapView mv) {
        try {
            e.slot = mv.addClientGob(e.gob);
        } catch(Loading l) {
            Resolve.on(l, e.owner, () -> retryAdd(e), SCENE_ADD_MAX_RETRIES);
        }
    }

    /**
     * The {@link Resolve} retry body for a pending scene add (042.12) — runs on the UI thread via the tick's
     * resolve drain, never inline. Re-checks the entity is still live, visible and attached before touching
     * the scene: the cancel/notify race (plan.md gotcha 10) means a {@code :reload} or a {@code :hide()} can
     * land between the register and the notify, and an entity destroyed/hidden/detached in that window must
     * not be added when the notify finally arrives. A further {@code Loading} (a second tile the placement
     * still needs) propagates out so {@link Resolve} re-registers on it — not caught here.
     */
    private static void retryAdd(LuaWorldEntity e) throws Loading {
        synchronized(e) {
            if(e.dead || !shows(e) || (e.gob == null) || (e.mv == null) || (e.rc == null) || (e.slot != null))
                return;                                // gone, hidden again (its own or its section's), detached
                                                       //   while we waited, or already in: 075.3 rehomes an
                                                       //   entity into another scene, so a notify may arrive
                                                       //   for an add a rebuild has already made
            e.slot = e.mv.addClientGob(e.gob);
            e.gob.move(e.rc, e.a);                      // apply any :position/:rotate that landed while pending
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

    // ---- custom 3D models in the world (hafen.vr():object()) -------------------------------------------

    /**
     * Build an object and publish it — the body of {@code hafen.vr():object():add}. {@code tgt != 0} anchors it to
     * that gob id; {@code 0} stands it where it was put (043.2). Returns {@code null} when there is no map view
     * (not in the world).
     */
    private static LuaObject makeObject(Addon owner, LuaValue opts, long tgt, LuaPosition.Anchor place) {
        final MapView mv = screenView();
        final Glob g = glob();
        if((mv == null) || (g == null))
            return null;                               // not in the world yet — no scene to add to
        LuaMesh mesh = resolveObjectMesh(opts.get("model"));   // AFTER the world check (don't validate when not in world)
        LuaValue av = opts.get("a");
        double a = av.isnumber() ? av.todouble() : 0.0;
        Coord2d rc = optPlace(opts);                   // 045.2: null ⇒ the place is not locatable this session
        LuaObject ob = new LuaObject(owner, mesh, rc, a);
        ob.alpha = luaAlpha(opts.get("alpha"));
        ob.tint = luaTint(opts.get("tint"));
        ob.scale = luaScale(opts.get("scale"));        // uniform scale on top of the baked model→world size
        ob.clickable = opts.get("clickable").toboolean();
        LuaValue onclickv = opts.get("onClick");
        if(onclickv.isfunction())
            ob.onClick = onclickv;
        ob.followTgt = tgt;                            // 043.2: the ANCHOR, an argument of :add(what, gob)
        owner.objects.add(ob);
        entityRegister(mv.ui, ob, place);              // 043.2/044.9/045.1: dies with its gob, or holds its own place
        LuaValue handle = objectHandle(ob);
        ob.handle = handle;
        // Build the gob + visual, then publish atomically. No defer: the glTF geometry is already parsed (R3), so
        // nothing here throws Loading. The MeshSprite adds one Model per primitive; the shared core supplies
        // transform/look/gizmo, exactly like a sprite's quad.
        GhostGob gob = new GhostGob(g, birthPoint(rc));
        gob.a = a;
        gob.alpha = ob.alpha; gob.tint = ob.tint; gob.scale = ob.scale;   // reflect the look before the first scene add
        gob.clickable = ob.clickable;                  // a clickable object's mesh renders into the clickmap → V2-pickable
        gob.setattr(new SprDrawable(gob, MeshSprite.mill(mesh)));    // resource-free glTF-model visual (R3b: shared textures + per-material states)
        if(rc != null)
            gob.move(rc, a);                           // 045.2: nothing to move it to yet — attachScene does it when there is
        synchronized(ob) {
            if(ob.dead) { gob.dispose(); return ob; }   // destroyed mid-build (defensive; all UI-thread)
            ob.gob = gob;
            ob.mv = mv;
            applyEntityFollow(ob, gob);                 // ANCHOR: an overlay's model starts tracking its gob now
            if(shows(ob))                               // 043.4: and its whole section has to be showing too
                addToScene(ob, mv);                     // the // addon: MapView seam; Resolve retries when the tile is not here yet
        }
        return ob;
    }

    /**
     * The Lua handle for a {@link LuaObject}: the shared entity verbs plus {@code :mesh()}, its model's
     * addon-relative path. There is no write half — an object's geometry is milled at create, and swapping it is
     * {@code hafen.vr():object():add(other)} on a fresh one.
     */
    private static LuaValue objectHandle(final LuaObject ob) {
        LuaTable x = new LuaTable();
        x.set("mesh", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("object:mesh() reads the model path and does not write it — an object's"
                        + " geometry is milled when it is placed, so another model is another object:"
                        + " hafen.vr():object():add(asset)");
                return (ob.meshName == null) ? LuaValue.NIL : LuaValue.valueOf(ob.meshName);
            }
        });
        return entityHandle(ob, "object", "object", x, " and :mesh()");
    }

    /**
     * Resolve the object {@code model=} option to a live {@link LuaMesh}: a {@code hafen.asset} mesh handle.
     * <b>Handle-only</b> (028.2 — one flow, D-012): a <b>path string</b> is refused
     * with an error naming {@code hafen.asset} as the way in, because interning makes repeating the load free,
     * so a shortcut here would only buy a second way to say the same thing. The three failures are
     * distinguishable: a path string, a disposed mesh, anything else.
     */
    private static LuaMesh resolveObjectMesh(LuaValue modelv) {
        if(modelv.isstring() && !modelv.isnumber())    // in LuaJ a number IS a string — that one is just a wrong type
            throw new LuaError("hafen.vr():object():add(model): the argument is a hafen.asset mesh HANDLE, not a path string — load it"
                + " once with hafen.asset():get(\"" + modelv.tojstring() + "\") and pass the handle (it is interned, so"
                + " repeating the load is free)");
        LuaMesh lm = LuaMesh.resolve(modelv);          // the handle, and nothing shaped like one
        if(lm == null)
            throw new LuaError("hafen.vr():object():add(model): the argument must be a hafen.asset mesh handle"
                + " (hafen.asset():get(\"chair.glb\")), got " + modelv.typename());
        if(lm.dead)
            throw new LuaError("hafen.vr():object():add(model): that mesh has been disposed — after a :dispose(), hafen.asset():get(path)"
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

    // ---- custom world sprites (hafen.vr():sprite()) ------------------------------------------------------

    /**
     * Build a sprite and publish it — the body of {@code hafen.vr():sprite():add}. {@code tgt != 0} anchors it to
     * that gob id (a {@link FollowMoving} applied before the gob enters the scene); {@code tgt == 0} stands it
     * where it was put (043.2). Returns {@code null} when there is no map view (not in the world).
     */
    private static LuaSprite makeSprite(Addon owner, LuaValue opts, long tgt, LuaPosition.Anchor place) {
        final MapView mv = screenView();
        final Glob g = glob();
        if((mv == null) || (g == null))
            return null;                               // not in the world yet — no scene to add to
        LuaImage img = resolveSpriteImage(opts.get("image"));   // AFTER the world check (don't validate when not in world)
        LuaValue av = opts.get("a");
        double a = av.isnumber() ? av.todouble() : 0.0;
        Coord2d rc = optPlace(opts);                   // 045.2: null ⇒ the place is not locatable this session
        LuaSprite sp = new LuaSprite(owner, img, rc, a, FIXED);   // a sprite is placed upright; :facing(mode) re-mills it
        sp.alpha = luaAlpha(opts.get("alpha"));        // opacity 0..1 (default 1); combines with the PNG's own alpha
        sp.tint = luaTint(opts.get("tint"));           // colour overlay {r=,g=,b=[,a=]}, or null
        sp.scale = luaScale(opts.get("scale"));        // uniform scale ("fixed": ~1 tile tall; "screen": screen-size ×)
        sp.clickable = opts.get("clickable").toboolean();   // R2b: opt-in pick (a "screen" sprite has no world mesh → never picked)
        LuaValue onclickv = opts.get("onClick");       // R2b: per-sprite click callback fn(s, button, x, y) — like a ghost
        if(onclickv.isfunction())
            sp.onClick = onclickv;
        sp.followTgt = tgt;                            // 043.2: the ANCHOR, an argument of :add(what, gob)
        owner.sprites.add(sp);
        entityRegister(mv.ui, sp, place);              // 043.2/044.9/045.1: dies with its gob, or holds its own place
        LuaValue handle = spriteHandle(sp);
        sp.handle = handle;
        // Build the gob + visual OUTSIDE the sprite lock (no scene mutation yet), then publish atomically. No defer:
        // the TexI is already decoded (R1), so nothing here throws Loading. The visual is the ONLY thing the facing
        // mode changes — an upright quad (SpriteQuad on a SprDrawable), the same quad turned to the viewer
        // (CameraFacing) or a screen blit (LuaSpriteBillboard) — the shared core supplies transform/look/gizmo.
        GhostGob gob = new GhostGob(g, birthPoint(rc));
        gob.a = a;
        gob.alpha = sp.alpha; gob.tint = sp.tint; gob.scale = sp.scale;   // reflect the look before the first scene add
        gob.clickable = sp.clickable;                  // R2b: a world-quad sprite renders into the clickmap → V2-pickable (see onGhostClick)
        gob.setattr(sp.visual(gob, sp.facing));        // the kind's one miller, shared with :facing(mode)
        if(rc != null)
            gob.move(rc, a);                           // 045.2: nothing to move it to yet — attachScene does it when there is
        synchronized(sp) {
            if(sp.dead) { gob.dispose(); return sp; }   // destroyed mid-build (defensive; all UI-thread) → discard
            sp.gob = gob;
            sp.mv = mv;
            applyEntityFollow(sp, gob);                 // ANCHOR: an overlay's sprite starts tracking its gob now
            if(shows(sp))                               // hidden before it published — its own, or its whole section's — stays out
                addToScene(sp, mv);                     // the // addon: MapView seam; Resolve retries when the tile is not here yet
        }
        return sp;
    }

    /**
     * The Lua handle for a {@link LuaSprite}: the shared entity verbs plus {@code :image()} (its image's
     * addon-relative path) and the shared {@code :facing()} / {@code :facing(mode)}.
     */
    private static LuaValue spriteHandle(final LuaSprite sp) {
        LuaTable x = new LuaTable();
        x.set("image", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("sprite:image() reads the image path and does not write it — the texture"
                        + " is sampled when the sprite is placed, so another image is another sprite:"
                        + " hafen.vr():sprite():add(asset)");
                return (sp.imgName == null) ? LuaValue.NIL : LuaValue.valueOf(sp.imgName);
            }
        });
        x.set("facing", facingVerb(sp, "sprite"));
        return entityHandle(sp, "sprite", "sprite", x, ", :image() and :facing()");
    }

    /** The three ways a flat entity — a sprite, a standing widget — can meet the viewer (044.3). */
    static final String FIXED = "fixed", CAMERA = "camera", SCREEN = "screen";

    /**
     * <b>{@code <entity>:facing()} / {@code :facing(mode)}</b>, one verb for both kinds that have one, because
     * both pick from the same three modes over the same shared core. Reads the mode string; writes it and hands
     * back the handle, like every other property (§2.2).
     *
     * <p><b>It is a CONSTRUCTION property, so writing it REBUILDS the visual.</b> The mode picks <i>which</i>
     * drawable is milled — a world quad, a world quad the camera turns, or a screen blit — and nothing can
     * change that in place. Refusing it after the fact would make the natural reading order illegal, and
     * deferring the build would move <i>there is no map view</i> off the call site that caused it; so the
     * rebuild is the property's implementation rather than a rule the caller has to know.
     */
    private static VarArgFunction facingVerb(final LuaWorldEntity e, final String kind) {
        return new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue mv = Args.written(a, 2, kind + ":facing", "mode");
                if(mv == null) {
                    synchronized(e) { return LuaValue.valueOf(e.facing); }
                }
                setEntityFacing(e, facingArg(mv, kind));
                return self;
            }
        };
    }

    /**
     * The {@code mode} of {@code <entity>:facing(mode)}, refused by name. Three modes since 044.3, and the
     * third is the one the spatial framing asks for: {@code "camera"} is still <b>world geometry</b> — real
     * world size, perspective, occlusion, shrinking with distance — and merely turns to the screen plane,
     * which is precisely what a {@code "screen"} blit is not and why the two were never the same word.
     */
    private static String facingArg(LuaValue mv, String kind) {
        if(!mv.isstring() || mv.isnumber())
            throw new LuaError(kind + ":facing(mode) expects a mode STRING — " + MODES + ", got "
                + mv.typename());
        String s = mv.tojstring();
        if(FIXED.equals(s) || CAMERA.equals(s) || SCREEN.equals(s))
            return s;
        throw new LuaError(kind + ":facing(\"" + s + "\"): a " + kind + " faces " + MODES);
    }

    /** The three modes as one phrase, so every refusal names all of them and says what each one is. */
    private static final String MODES =
        "\"fixed\" (a world quad at its own :rotate angle), \"camera\" (a world quad that turns to the viewer,"
        + " in yaw and pitch, keeping its world size) or \"screen\" (a constant-size blit over the scene)";

    /**
     * {@code <entity>:facing(mode)} — swap between the three visuals a flat entity can have, in place. The gob
     * keeps its identity, its transform and its scene slot; only the {@code Drawable} is replaced, under the
     * gob's own monitor (the lock the engine's live res-swap holds, because {@code slots} is a plain list
     * shared with the {@code ctick} path). No-op when unchanged, dead, or still waiting for its gob.
     *
     * <p>For a standing widget the <b>surface is untouched</b>: the offscreen pass, the texture and the widget
     * inside it are the same objects before and after, so a swap costs a quad, not a re-stand.
     */
    private static void setEntityFacing(LuaWorldEntity e, String mode) {
        synchronized(e) {
            if(e.dead || e.facing.equals(mode))
                return;
            e.facing = mode;
            Gob g = e.gob;
            if(g == null)
                return;                                // not published yet — the create reads the mode
            Drawable dr = e.visual(g, mode);
            synchronized(g) {
                g.setattr(dr);                         // swaps the Drawable attrib: old slots removed, new added
            }
            refreshEntityScene(e);                     // obstate reads the look fresh on the re-add
        }
    }

    /**
     * Resolve the sprite {@code image=} option to a live {@link LuaImage}: a {@code hafen.asset} image handle.
     * <b>Handle-only</b> (028.2 — one flow, D-012), exactly like
     * {@link #resolveObjectMesh}: a <b>path string</b> is refused with an error naming {@code hafen.asset} as the
     * way in. The three failures are distinguishable: a path string, a disposed image, anything else.
     */
    private static LuaImage resolveSpriteImage(LuaValue imgv) {
        if(imgv.isstring() && !imgv.isnumber())        // in LuaJ a number IS a string — that one is just a wrong type
            throw new LuaError("hafen.vr():sprite():add(image): the argument is a hafen.asset image HANDLE, not a path string — load it"
                + " once with hafen.asset():get(\"" + imgv.tojstring() + "\") and pass the handle (it is interned, so"
                + " repeating the load is free)");
        LuaImage li = LuaImage.resolve(imgv);          // the handle, and nothing shaped like one
        if(li == null)
            throw new LuaError("hafen.vr():sprite():add(image): the argument must be a hafen.asset image handle"
                + " (hafen.asset():get(\"icon.png\")), got " + imgv.typename());
        if(li.dead)
            throw new LuaError("hafen.vr():sprite():add(image): that image has been disposed — after a :dispose(), hafen.asset():get(path)"
                + " loads the file again as a NEW asset");
        return li;
    }

    /**
     * The world size {@code {w, h}} in map units of a fixed sprite, aspect-preserved from the image's pixel size:
     * the height is one tile ({@link MCache#tilesz}) and the width follows the image aspect, so the sprite stands
     * ~1 tile tall at {@code scale=1}; the uniform {@code :scale} (obstate) then adjusts both. A degenerate (zero)
     * pixel dimension falls back to a square tile. Pure — headless-testable.
     */
    static float[] spriteWorldDims(Coord isz) {
        float base = (float)MCache.tilesz.y;               // ≈ 1 tile tall at scale 1
        float w = ((isz != null) && (isz.x > 0) && (isz.y > 0)) ? base * ((float)isz.x / (float)isz.y) : base;
        return new float[] { w, base };
    }

    // ---- widgets standing in the world (hafen.vr():widget()) ---------------------------------------------

    /**
     * Build a standing widget and publish it — the body of {@code hafen.vr():widget():add}. Three things happen,
     * and only the third is new: the widget is <b>re-homed</b> into a {@link WidgetSurface} (an invisible root
     * under {@code ui.root}, so it keeps ticking, keeps existing and stops being hit-tested on the flat UI); a
     * virtual gob is stood at the anchor with a {@link SurfaceQuad} sampling that surface; and the entity that
     * ties the two together joins the same registry, teardown and scene lifecycle the other three kinds use.
     *
     * <p><b>Both anchors, and the same one line as every other kind</b> (044.2): {@code tgt != 0} makes the
     * surface follow that gob — the {@link FollowMoving} the three picture kinds already use, applied before the
     * gob enters the scene — and {@code tgt == 0} stands it where it was put. A widget standing on a gob has no
     * place of its own, so its {@code :position(p)} is refused and its {@code :offset(x, y, z)} is the verb that
     * means "where", exactly as for a sprite: the anchor is an argument (043.2), not a second kind.
     *
     * <p>Returns {@code null} when there is no map view (not in the world) — checked before anything is touched,
     * so a call made too early leaves the widget exactly where it was.
     */
    private static LuaWidgetEntity makeWidget(Addon owner, LuaValue opts, LuaValue wv, long tgt,
                                              LuaPosition.Anchor place) {
        final MapView mv = screenView();
        final Glob g = glob();
        final UI u = (mv == null) ? null : mv.ui;      // 078.4: the SCENE's own tree — the surface stands where it draws
        if((mv == null) || (g == null) || (u == null) || (u.root == null))
            return null;                               // not in the world yet — no scene to add to
        Widget content = standable(owner, wv);         // AFTER the world check (don't re-home when there is no scene)
        Coord2d rc = optPlace(opts);                   // 045.2: null ⇒ the place is not locatable this session
        WidgetSurface surf = new WidgetSurface(u, owner, content.sz);   // 073.2: the tree it is about to stand in
        LuaWidgetEntity we = new LuaWidgetEntity(owner, surf, content, rc, 0.0);
        surf.ent = we;                                 // 044.4: the surface asks the entity whether it takes the pointer
        we.clickable = true;                           // ...and it does, by default — a window on screen takes clicks
        we.prevParent = content.parent;                // where it stood from, so :remove puts it back
        // A COPY, deliberately: haven.Coord is mutable and the client hands the SAME shared Coord.z object to
        // many widgets at once (GameUI.maininv's own c is literally that object), so a record that kept the
        // reference would be a record something else could move. What is being remembered here is two numbers.
        we.prevPos = new Coord(content.c);
        we.followTgt = tgt;                            // 043.2: the ANCHOR, an argument of :add(what, gob)
        owner.surfaces.add(we);
        entityRegister(mv.ui, we, place);              // 044.2/044.9/045.1: dies with its gob, or holds its own place
        we.handle = widgetHandle(we);
        synchronized(u) {
            u.root.add(surf, Coord.z);                 // in the tree: liveness, ticking and focus all keep resolving
            WidgetSurface.reparent(u, content, surf, Coord.z);
        }
        GhostGob gob = new GhostGob(g, birthPoint(rc));
        gob.alpha = we.alpha; gob.tint = we.tint; gob.scale = we.scale;   // the look, before the first scene add
        // NO GobClick (044.4): a standing widget is not in the world pick at all. Its clicks are resolved
        // before the pick pass is ever started, off the quad's projected corners, and a pointer that MISSES
        // the panel must reach the world beneath it — which it does because the quad puts nothing in the
        // clickmap to stop it. The pick and the panel therefore never compete for the same click.
        gob.setattr(we.visual(gob, we.facing));        // the kind's one miller, shared with :facing(mode)
        if(rc != null)
            gob.move(rc, 0.0);                         // 045.2: nothing to move it to yet — attachScene does it when there is
        synchronized(we) {
            if(we.dead) { gob.dispose(); return we; }   // ended mid-build (defensive; all UI-thread)
            we.gob = gob;
            we.mv = mv;
            applyEntityFollow(we, gob);                 // ANCHOR: a surface stood on a gob starts tracking it now
            if(shows(we))                               // its own visibility, and its whole section's
                addToScene(we, mv);
        }
        return we;
    }

    /**
     * The Lua handle for a {@link LuaWidgetEntity}: the shared entity verbs plus {@code :widget()}, the Widget
     * that is standing — the identity accessor every kind has ({@code :res()}, {@code :image()},
     * {@code :mesh()}) — and the shared {@code :facing(mode)}, because a surface is flat and so meets the
     * viewer one of the same three ways a sprite does (044.3). There is no write half to {@code :widget()},
     * for the same reason an object's mesh has none: standing another widget is another
     * {@code hafen.vr():widget():add(w, p)}.
     *
     * <p><b>Its receiver is {@code panel}, and the flat one's is {@code widget}</b> (084.6). Two types cannot
     * share a receiver spelling and keep {@link Retired}'s promise, because the twenty-odd rows keyed
     * {@code widget:<verb>} fire on whichever of the two is in hand and only one of the two can be right:
     * {@code widget:pos} says a widget lives on the screen and this is not a Position, which is the wrong fix
     * for a thing standing in the world, whose {@code :position()} <b>is</b> a Position. So the panel answers
     * to a name of its own, the rows that mean the flat widget stay where they are, and the rows that mean
     * both are stated twice. The collection is untouched: {@code hafen.vr():widget()} still places it.
     */
    private static LuaValue widgetHandle(final LuaWidgetEntity we) {
        LuaTable x = new LuaTable();
        x.set("widget", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("panel:widget() reads the Widget that is standing and does not write it"
                        + " — standing another one is hafen.vr():widget():add(w, p), and taking this one back is"
                        + " hafen.vr():widget():remove(x)");
                return LuaWidget.of(we.owner, we.content);
            }
        });
        x.set("facing", facingVerb(we, "panel"));
        // screen(x, y) — where a pixel of this panel is drawn, in the screen coordinates the pointer itself
        // reports (044.4). The exact inverse of what a click does, and the same corner map both ways, so
        // "where is my OK button on screen" and "what did the player click" can never disagree. Two numbers,
        // like every screen point in this API; nil when the panel is not being drawn or is behind the camera.
        // Both pairs are DESIGN pixels (058.2) — the widget-local one is what widget:size() and ev:x() speak,
        // the screen one what hafen.ui():mouse() and hafen.vr():pointer() do — and the map between them stays
        // device, so the conversion is the two edges of this verb and nothing in between.
        x.set("screen", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                int wx = (int)Math.round(number(a, 2, "panel:screen", "x"));
                int wy = (int)Math.round(number(a, 3, "panel:screen", "y"));
                Coord w = Px.in(Coord.of(wx, wy));
                Coord p = Px.out(SurfaceInput.screenOf(we, w.x, w.y));
                return (p == null) ? LuaValue.NIL
                    : LuaValue.varargsOf(LuaValue.valueOf(p.x), LuaValue.valueOf(p.y));
            }
        });
        // onClick is refused HERE and nowhere else (044.4). Every other kind is a picture, so "the thing was
        // clicked" is all there is to say about it; a widget is not a picture, and the answer to a click on one
        // is the widget's OWN MouseDown, at the pixel it landed on, in the handler the addon already wrote for
        // the flat UI. Two ways to hear about the same click would be exactly the dual API this area refuses.
        x.set("onClick", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                throw new LuaError("panel:onClick(fn): a standing widget's clicks are the WIDGET's own —"
                    + " subscribe with panel:widget():on(\"MouseDown\", fn) (or \"MouseUp\", \"MouseMove\","
                    + " \"Wheel\"), the same line you would write for it on the flat UI, and read the pixel"
                    + " off ev:x()/ev:y(). panel:clickable(false) makes the whole panel click-through"
                    + " instead");
            }
        });
        return entityHandle(we, "panel", "widget", x, ", :facing() and :screen()");
    }

    /**
     * The widget a {@code hafen.vr():widget():add(w, p)} names, or a refusal that says which of the four things
     * went wrong: it is not a Widget at all, it is already standing (naming the addon that holds it), it is
     * inside something that is already standing, or it is (or contains) the 3D view itself. The last two are
     * one refusal in two spellings — a surface within a surface, which the spec refuses outright rather than
     * deferring. Nothing else can enclose a standing panel: standing <b>moves</b> the widget into its surface,
     * so the only widget left containing one is the root the surface hangs off, and that is the second spelling.
     *
     * <p><b>Provenance is no longer one of them</b> (044.6). Standing the client's own windows is what this
     * feature exists for, and it is the same family of write as {@code widget:position(x, y)},
     * {@code widget:visible(false)} and {@code widget:replace(view)}: a layer over the client's state, never a
     * write into it — so it is <b>unprotected</b>, and {@link LuaWidgetEntity#destroyed()} gives the window back
     * where it stood from when the entity ends. What a borrowed widget does NOT bring with it is the hide
     * record's toggle ownership (D-069): standing does not hide anything, so the client's own toggle goes on
     * doing exactly the right thing to a window that is standing in the world, and the menu tick goes on
     * telling the truth about it.
     */
    private static Widget standable(Addon owner, LuaValue wv) {
        LuaWidget lw = LuaWidget.resolve(wv);
        Widget w = (lw == null) ? null : LuaWidget.live(lw);
        if(w == null)
            throw new LuaError("hafen.vr():widget():add(w, anchor) expects a Widget — one you built"
                + " (hafen.ui():window(), hafen.ui():widget(), or a control builder), or one of the client's"
                + " own (s:ui():find(…), s:ui():inventory(), …). Got " + wv.typename());
        if(w.parent instanceof WidgetSurface)
            throw new LuaError("hafen.vr():widget():add(w, anchor): " + LuaWidget.typeName(w) + " is already"
                + " standing in the world, held by the addon \""
                + AddonManager.ownerName(((WidgetSurface)w.parent).owner) + "\" — one widget stands in one"
                + " place. Take it back with hafen.vr():widget():remove(x) first. One standing at a point"
                + " moves with widget:position(p); one standing on a gob is where that gob is");
        for(Widget a = w.parent; a != null; a = a.parent) {
            if(a instanceof WidgetSurface)
                throw new LuaError("hafen.vr():widget():add(w, anchor): that widget is INSIDE one that is already"
                    + " standing, and a surface does not stand on another surface. Two panels in the world are"
                    + " two hafen.vr():widget():add(w, p), each on its own anchor");
        }
        // ...and the world does not stand inside itself: a surface is drawn from the very frame that then draws
        // the scene the surface is standing in, so a widget with the MapView under it (the HUD, ui.root) would
        // be a picture of the world containing a picture of the world. Point at ONE window.
        MapView mv = screenView();
        if((mv != null) && ((w == mv) || mv.hasparent(w)))
            throw new LuaError("hafen.vr():widget():add(w, anchor): " + LuaWidget.typeName(w) + " is (or"
                + " contains) the 3D view itself, and the world cannot stand inside itself — the panel is"
                + " drawn from the same frame that draws the scene it stands in. Stand one window, not the"
                + " whole interface");
        return w;
    }

    /**
     * The world size {@code {w, h}} in map units of a standing widget, from its pixel size: {@link #PX_PER_TILE}
     * pixels to the tile, so a 200&times;140 window stands two tiles wide and keeps its aspect exactly. The
     * uniform {@code :scale} adjusts it from there. Pure — headless-testable.
     */
    static float[] surfaceWorldDims(Coord sz) {
        float per = (float)MCache.tilesz.y / PX_PER_TILE;
        float w = ((sz == null) || (sz.x <= 0)) ? per : (sz.x * per);
        float h = ((sz == null) || (sz.y <= 0)) ? per : (sz.y * per);
        return new float[] { w, h };
    }

    /**
     * How big a standing widget is in the world, at {@code :scale(1)} — a hundred pixels to the tile. Chosen
     * rather than derived: a window is authored in pixels for a screen, and the one number that turns those
     * into world units is what decides whether a panel reads as a signpost or as a billboard. A hundred puts a
     * default {@code hafen.ui():window()} at about two tiles across, which is a thing you walk up to.
     */
    private static final float PX_PER_TILE = 100f;

    /**
     * <b>The widget that was standing has been destroyed</b> (044.6) — the fifth consumer of the widget-removal
     * seam ({@code AddonManager.drainRemovedWidgets}), and the answer to the one composition the spec calls out:
     * the server destroys the window an addon replaced, {@code widget:replace(view)}'s own death test ends the
     * substitution, and the stand-in it destroys is the very widget standing in the world. A panel whose content
     * is gone shows nothing and can show nothing again, so the entity ends with it — the surface is freed and the
     * handle reports {@code :exists()} false, exactly as {@code :remove} would have left it.
     *
     * <p>It is the same answer for every other way a content widget can die: a borrowed window the server
     * destroys (a chest closed, a container walked away from), and an owned one the addon destroys itself. The
     * put-back in {@link LuaWidgetEntity#destroyed()} finds a widget that is no longer in the surface and
     * correctly restores nothing — there is nothing left to give back.
     *
     * <p>Costs an empty-list test per live addon per removed widget: standing is rare and the registries are
     * per-addon tiny, and the seam already runs one frame's worth of removals at a time (D-106).
     */
    static void dispatchStandingRemoved(Widget w) {
        if(w == null)
            return;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            endStandingOf(as.get(i), w);
        endStandingOf(AddonManager.consoleOwner, w);   // the :lua REPL stands widgets too, and owns them the same
    }

    /**
     * <b>A widget has announced its removal</b> (044.8) — flag any standing entity over it as ending with its
     * content, called from the removal TAP rather than from the drain that follows it.
     *
     * <p>The two are not the same moment, and the gap between them is where a real fault lived. A
     * {@link haven.Window} announces its removal as its fade begins — the path {@code UI.destroy} takes, so
     * every container the server closes — and only unlinks when that fade ends. Re-clicking an open cupboard
     * makes the server close and immediately reopen it, so the NEW window's {@code appear} reaches the addon
     * inside that gap: the addon takes its panel down, {@code :remove} runs the ordinary put-back, and a dead,
     * emptied window is dropped onto the flat UI, owned by nobody and collected by no teardown. Ten clicks,
     * ten windows. Flagging at the tap makes <i>it is on its way out</i> true for every door at once —
     * {@code :remove}, {@code :reload}, disable and the drain alike.
     *
     * <p>Free when nothing stands (the live-surface count is the fast path), and flag-only, so it is safe on
     * whichever thread reached {@code remove()}.
     */
    static void markContentGone(Widget w) {
        // 073.2: the fast path asks its own tree — a session with nothing standing pays the same one read it
        // paid before, and a removal on a Loader thread never has to know which session holds the screen.
        if((w == null) || (WidgetSurface.liveCount(w.ui) == 0))
            return;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            markGoneIn(as.get(i), w);
        markGoneIn(AddonManager.consoleOwner, w);
    }

    /** Flag {@code a}'s standing entity over {@code w}, if it holds one. */
    private static void markGoneIn(Addon a, Widget w) {
        if((a == null) || a.surfaces.isEmpty())
            return;
        for(LuaWidgetEntity we : a.surfaces) {         // copy-on-write: safe to walk off the UI thread
            if(we.content == w)
                we.contentGone = true;
        }
    }

    /**
     * End {@code a}'s standing entity over {@code w}, if it holds one — and mark it as ending because the
     * <b>content</b> is gone (044.8), which is the one ending that must NOT put the widget back: there is
     * nothing to put back, and a {@link haven.Window} says it is going while it is still linked, so the
     * put-back's own guards cannot tell (see {@link LuaWidgetEntity#contentGone}). Belt and braces — the tap
     * above has normally flagged it already, one drain earlier.
     */
    private static void endStandingOf(Addon a, Widget w) {
        if((a == null) || a.surfaces.isEmpty())
            return;
        for(LuaWidgetEntity we : new ArrayList<LuaWidgetEntity>(a.surfaces)) {
            if(we.content == w) {
                we.contentGone = true;
                destroyEntity(we);
            }
        }
    }

    /** Tear down every standing widget this addon owns (reload/disable/relogin, P2): each goes back where it was. */
    static void teardownSurfaces(Addon a) {
        if(a.surfaces.isEmpty())
            return;
        for(LuaWidgetEntity we : new ArrayList<LuaWidgetEntity>(a.surfaces))
            destroyEntity(we);          // removes each from a.surfaces as it goes (copy-on-write list)
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
            if(e instanceof LuaWidgetEntity)
                return;                                // 044.4: a panel's pointer gate, not a pick surface —
                                                       // nothing in the scene to re-add, so nothing flickers
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
     * <b>Should this entity be in the scene right now?</b> Three independent booleans, ANDed: what the entity
     * itself was told ({@code <entity>:visible(b)}), what its whole section was told
     * ({@code hafen.vr():visible(b)}, 043.4), and whether the ground under a free one is drawn at all (044.9).
     * Keeping them apart is the restore rule — neither of the other two overwrites the entity's own answer, so
     * showing the section back puts back exactly what was visible, and a walk to the far side of the map changes
     * nothing the addon wrote. Read by every publish, by both switches and by the ground drain; never at draw time.
     */
    private static boolean shows(LuaWorldEntity e) {
        return !e.hidden && !e.owner.vrHidden && e.grounded;
    }

    // ---- THE GROUND UNDER A FREE ENTITY (044.9) --------------------------------------------------------------

    /**
     * <b>Is the ground under this point drawn?</b> — the whole of 044.9's test, and the client already knows the
     * answer. A client-only gob is in no {@code OCache}, which is exactly the premise an anchored entity's death
     * rests on (D-102): nothing removes it. So a free entity placed at a POINT went on drawing over ground that
     * had gone, hanging in the void until it left the screen — and walking back found it still there.
     *
     * <p><b>The terrain's own per-cut map IS the test</b> ({@code MapView.grounddrawn}, one {@code // addon:}
     * accessor): a cut is in that map exactly while its mesh is in the scene, so <i>is the ground under it
     * drawn</i> is asked of the very structure that draws the ground, and there is no second rule to keep in step
     * with what the player can see. Answering {@code true} with no map view is deliberate — an entity is never
     * taken out of a scene that is not there, and every publish path already refuses to place one before the
     * world is up.
     */
    private static boolean groundDrawn(Coord2d rc) {
        if(rc == null)
            return false;                              // 045.1: no coordinate this session ⇒ no ground under it (rc == null ⇒ !grounded)
        MapView mv = screenView();
        if(mv == null)
            return true;
        try {
            return mv.grounddrawn(rc);
        } catch(RuntimeException ex) {
            return true;                               // scene mid-teardown: never a reason to hide anything
        }
    }

    /** Raised by the terrain's cut map changing, or by the session coordinate space moving; drained on the addon tick. */
    private static volatile boolean groundDirty;

    /**
     * 075.3: raised when <b>which characters can see an object something is standing on</b> changed — the gob
     * entered or left an {@code OCache}. The anchored half of {@link #drainGround}, kept apart from
     * {@link #groundDirty} because the ground moving says nothing about an object and walking both lists on
     * every cut boundary would be a fold over things that could not have changed.
     */
    private static volatile boolean anchorsDirty;

    /**
     * An object came into, or went out of, some character's view. Called for every gob the client adds or
     * removes, so it is an O(1) miss on the map for all but the handful of ids something is standing on — and
     * for those it is a flag, drained on the tick like everything else in this layer (D-106).
     */
    static void anchorSeen(long id) {
        synchronized(anchored) {
            if(!anchored.containsKey(Long.valueOf(id)))
                return;
        }
        anchorsDirty = true;
    }

    /**
     * How many times {@link #drainGround} has actually walked the entities — cumulative for the client's life,
     * and the number that makes "this is an event, not a poll" a thing a suite can ASSERT rather than a claim
     * (045.2). It moves a handful of times while walking and not at all while standing still; a tap that had
     * lost its guard would move it every frame. UI thread only (the addon tick), so a plain long.
     */
    private static long passes;

    /**
     * {@code p:entities()}: free entities standing right now, how many of them are waiting, and {@link #passes}.
     * <b>One figure for the whole client</b>, which since 075.3 is what the list itself is: the profiler reports
     * what the client is carrying, and there is nothing per session left to break it down by.
     */
    static int freeCount() {
        synchronized(free) { return free.size(); }
    }

    /** Of those, how many hold a place the drawn session cannot locate — the ones waiting for their ground (045.2). */
    static int waitingCount() {
        List<LuaWorldEntity> l;
        synchronized(free) { l = new ArrayList<LuaWorldEntity>(free); }
        int n = 0;
        for(LuaWorldEntity e : l) {
            synchronized(e) {
                if(!e.dead && (e.rc == null))
                    n++;
            }
        }
        return n;
    }

    /** How many re-derivation passes the drain has run (see {@link #passes}). */
    static long regroundPasses() {
        return passes;
    }

    /**
     * The terrain's cut map changed — a cut entered the scene or left it. Called from the two mutation points in
     * {@code MapView.MapRaster.Grid.tick} (via {@link AddonManager#groundChanged}), on the UI thread but inside
     * the render tick, so it does no more than raise a flag; {@link #drainGround} does the work on the addon
     * tick, the same shape every other off-moment tap in this layer has (D-106).
     *
     * <p><b>This is the event, and there is no sweep.</b> It fires when the player crosses a cut boundary or a
     * grid streams in or out — a handful of times a minute while walking, and not at all while standing still.
     */
    static void groundChanged() {
        groundDirty = true;
    }

    /**
     * <b>The session coordinate space itself moved</b> (045.2) — the second of the drain's two sources, and the
     * one the first cannot cover. A free entity's coordinate is derived through {@code MiniMap.sessloc}, and
     * that location is re-resolved a frame or more <i>after</i> the terrain's cuts have come back: an entity
     * whose ground returned while the player stood still would otherwise wait for a cut change that never comes.
     *
     * <p><b>The equality test is the whole of it.</b> {@code MiniMap.tick} mints a fresh {@code Location} every
     * frame, so notifying on the assignment alone would raise the flag sixty times a second and turn the drain
     * into the per-frame poll 042 deleted. What matters is the segment and the tile origin, which change only
     * when the server drops the map — a handful of times an hour. Flag only, drained on the addon tick (D-106).
     *
     * <p>It listens to <b>one</b> minimap: the very instance {@code MapApi.sessloc()} reads. The map window
     * carries a second one, ticking the same locator against the same file, and letting both through would let
     * the earlier of the two consume the change for the later — the memo would already match by the time the
     * instance the derivation actually reads had been assigned.
     *
     * <p><b>The memo is the minimap's own session's</b> (073.4): a segment id and a tile origin are a coordinate
     * space, and each login has its own — remembering one client-wide would let one session's re-base answer for
     * another's. The seam is handed the {@code MiniMap}, so {@code mm.ui} is the session and nothing has to be
     * guessed. The guard above still asks the DRAWN corner minimap, because 073 indexes the caches and changes
     * no read; what it buys is that when that read takes a session, the state it writes already names one.
     */
    static void sessionRebased(MiniMap mm, MiniMap.Location loc) {
        if((loc == null) || (mm != MapApi.minimap()))
            return;
        SessionState st = state(mm.ui);
        if(st == null)
            return;
        synchronized(st.vrSessLock) {
            if(st.vrSessSeen && (st.vrSessSeg == loc.seg.id) && loc.tc.equals(st.vrSessTc))
                return;                                // same place, a new object: the frame said nothing
            st.vrSessSeen = true;
            st.vrSessSeg = loc.seg.id;
            st.vrSessTc = loc.tc;
        }
        groundDirty = true;
    }

    /**
     * <b>The scene the entities are standing in</b> (075.3) — what {@link #drainGround} last re-homed them to,
     * so that the screen moving to another character is noticed by the one pass that can act on it. Compared by
     * identity and never dereferenced; a destroyed view is simply not the one being drawn any more.
     */
    private static MapView scene;

    /**
     * Re-ask, for every entity standing in the world, the two questions the world answers about it: <b>which
     * scene is it drawn in</b>, and <b>can the character looking at that scene see its place at all</b>. Run on
     * the layer's tick (the client's, since 075.3 there is one set of entities and one scene), and only when
     * something moved. An entity whose answer changed is attached or detached exactly as a {@code :visible(b)}
     * would be — the same two helpers, so there is one way in and out of the scene and not a fourth.
     *
     * <p><b>Three sources, and each says which list it is about.</b> The cut map changing is the frequent one
     * — a handful of times a minute while walking — and it only ever changes the ground, so it walks the free
     * entities alone. An object entering or leaving a character's view ({@link #anchorSeen}) is about the
     * anchored ones alone. The screen moving to another character is about all of them, and is the rare one:
     * every entity's gob has to be rebuilt in the scene now being drawn ({@link #rehome}), and an anchored one
     * has to find its object in the new character's {@code OCache}.
     */
    static void drainGround() {
        MapView mv = screenView();
        boolean moved = (mv != scene);                 // 075.3: the screen is on another character's world
        if(moved)
            scene = mv;
        boolean ground = groundDirty || moved, anchors = anchorsDirty || moved;
        if(!ground && !anchors)
            return;
        groundDirty = false;
        anchorsDirty = false;
        List<LuaWorldEntity> l = new ArrayList<LuaWorldEntity>();
        if(ground) {
            synchronized(free) { l.addAll(free); }
        }
        if(anchors) {
            synchronized(anchored) {
                for(List<LuaWorldEntity> v : anchored.values())
                    l.addAll(v);
            }
        }
        if(l.isEmpty())
            return;
        passes++;                                      // 045.2: what p:entities() reports, and the poll test
        for(LuaWorldEntity e : l) {
            try {
                reground(e, mv);
            } catch(RuntimeException ex) {
                /* best-effort: one bad entity never stops the rest from being re-asked */
            }
        }
    }

    /**
     * Re-read where one entity stands and put it in or out of the drawn scene. Takes the entity monitor itself;
     * a no-op when nothing about the answer changed, which is what it is on all but the handful of ticks a cut
     * actually appears or disappears on.
     *
     * <p><b>The scene first</b> (075.3). An entity is a client-only gob in one {@code MapView}, and a gob's
     * placement reads the {@code Glob} it was built against — so an entity being drawn for another character is
     * an entity rebuilt in that character's scene, not the same gob shown twice. Everything below is then asked
     * of the session that is actually looking.
     *
     * <p><b>Then the coordinate</b> (045.1). The entity holds a durable place; the session coordinate is a
     * cache of where that place is <i>right now</i>, and the server re-bases the whole coordinate space whenever
     * it drops the map — which is the same moment every cut leaves the scene, so the event that says the ground
     * moved is the event that says the numbers did. Deriving it here and nowhere else keeps the two in one step:
     * the coordinate is refreshed, then the ground is asked about the refreshed one. A place the drawn session
     * cannot locate leaves {@code rc} null, and null is not drawn — which is the whole of the answer for a
     * character standing somewhere else entirely.
     *
     * <p><b>An anchored one is asked the same question about its object</b>: the id is the server's, so the
     * drawn character's {@code OCache} either has that object in view or does not, and a thing standing on an
     * object nobody present can see is not drawn. It is not ended — that is {@link #anchorGone}, and only when
     * no session at all can see it.
     */
    private static void reground(LuaWorldEntity e, MapView mv) {
        if(e.mv != mv)
            rehome(e, mv);                             // takes the monitor itself, and the scene locks outside it
        if(e.mv != mv) {
            // It stands in a scene that is not the one being drawn, which for a standing widget is where it
            // stays (see rehome). Out of that scene while another character holds the screen, so that
            // :drawn() means one thing for every kind: is it in the scene you are looking at. An entity whose
            // create is still streaming in is in no scene yet and is left to that create.
            synchronized(e) {
                if(!e.dead && (e.mv != null))
                    setGrounded(e, false);
            }
            return;
        }
        Glob g = globOf(mv);
        synchronized(e) {
            if(e.dead)
                return;
            if(e.followTgt != 0) {
                Gob t = (g == null) ? null : g.oc.getgob(e.followTgt);
                if(t != null)
                    e.rc = t.rc;                       // this character's numbers for the object it follows
                setGrounded(e, t != null);
                return;
            }
            // A free entity holds the server's grid id, so its world coordinate is derived per
            // session — and the one it is drawn in is the one on screen (null asks that).
            Coord2d rc = LuaPosition.worldOf(e.anchorGrid, e.agx, e.agy, null);
            if((rc != null) && !rc.equals(e.rc)) {
                e.rc = rc;
                if(e.gob != null)
                    e.gob.move(e.rc, e.a);             // the space moved under it: same place, new numbers
            } else if(rc == null) {
                e.rc = null;
            }
            setGrounded(e, groundDrawn(e.rc));
        }
    }

    /**
     * Record what the world just answered about this entity's place and make its scene membership match. Not a
     * setter an addon can reach: {@link LuaWorldEntity#grounded} is what the WORLD says, beside the two things
     * the addon says, and {@link #shows} ANDs all three. Idempotent — both helpers check the slot — so it is
     * also what puts a freshly {@link #rehome}d entity back on screen. Caller holds the entity monitor.
     */
    private static void setGrounded(LuaWorldEntity e, boolean g) {
        e.grounded = g;
        if(shows(e))
            attachScene(e);
        else
            detachScene(e);
    }

    /**
     * <b>Stand this entity in the scene being drawn now</b> (075.3) — out of the {@code MapView} it was in, and
     * back in as a fresh gob built against the {@code Glob} of the session that owns the new one. The visual is
     * rebuilt rather than moved because a {@code Gob} holds its {@code Glob} for the life of the object and
     * asks it for the tile under itself every frame ({@code Gob.placer}, {@code getmapstate}): the same gob
     * added to another character's scene would be placed against the map of the character who is not looking.
     * Every kind builds its own drawable the one way it already knows how ({@link LuaWorldEntity#visual(Gob)}) —
     * a {@code .res} model, a quad over a texture, a milled mesh — so a kind says what it looks like in one
     * place and a rebuild cannot drift from a create.
     *
     * <p><b>A standing widget does not travel</b>, and is the one kind that does not: its picture is a widget in
     * one session's tree, drawn by that tree's own offscreen pass and hit-tested through it, so a panel handed
     * to another character's scene would be a frozen texture nothing could click. It stays where it was stood
     * and is drawn while that character is on screen — see {@code docs/addons/api/vr/widgets.md}.
     *
     * <p>{@code mv} may be {@code null} — the login screen, or a session whose world has not come up. Then the
     * entity simply has no visual until one is: it goes on holding its place, and the next pass builds it.
     */
    private static void rehome(LuaWorldEntity e, MapView mv) {
        if(e instanceof LuaWidgetEntity)
            return;
        Gob old; RenderTree.Slot slot; MapView omv;
        synchronized(e) {
            if(e.dead || e.streaming)
                return;                                // a create is still bringing one in; it raises the flag when it lands
            old = e.gob;   e.gob = null;
            slot = e.slot; e.slot = null;
            omv = e.mv;    e.mv = null; e.ui = null;
        }
        // Out of the old scene and freed OUTSIDE the entity monitor, the order destroyEntity uses and for its
        // reason: no lock-ordering between an entity and the render tree's own lock.
        if(omv != null)
            omv.removeClientGob(old, slot);
        else if(slot != null) {
            try { slot.remove(); } catch(RuntimeException ex) { /* scene already gone */ }
        }
        if(old != null) {
            try { old.dispose(); } catch(RuntimeException ex) { /* best-effort: free the visual */ }
        }
        Glob g = globOf(mv);
        if((mv == null) || (g == null))
            return;                                    // nothing is drawing a world right now
        Coord2d rc; double a;
        synchronized(e) {
            if(e.dead)
                return;
            rc = e.rc; a = e.a;
        }
        GhostGob gob = new GhostGob(g, birthPoint(rc));
        gob.a = a;
        Drawable d;
        try {
            d = e.visual(gob);                         // the kind's own miller: a .res model, a quad, a milled mesh
        } catch(Loading l) {
            // The resource is not in the pool right now (a ghost after a cache flush). Retry on the very thing
            // that is missing, exactly as a scene add does, and stand nothing in the meantime.
            gob.dispose();
            Resolve.on(l, e.owner, () -> rehome(e, screenView()), SCENE_ADD_MAX_RETRIES);
            return;
        } catch(RuntimeException ex) {
            gob.dispose();
            return;                                    // an unbuildable visual is not a reason to lose the entity
        }
        synchronized(e) {
            if(e.dead) { gob.dispose(); return; }
            gob.alpha = e.alpha; gob.tint = e.tint; gob.scale = e.scale;   // the look, before the first scene add
            gob.clickable = e.clickable;
            gob.setattr(d);
            if(e.rc != null)
                gob.move(e.rc, e.a);
            e.gob = gob;
            e.mv = mv;
            e.ui = mv.ui;
            applyEntityFollow(e, gob);                 // ANCHOR: it tracks its object in THIS session's OCache
            // Not added to the scene here: the caller asks the world about the place first, and putting it in
            // is what that answer does (setGrounded). An entity whose ground has gone would otherwise be added
            // and taken straight back out in the same pass.
        }
    }

    /** The {@code Glob} behind a scene, or {@code null} — a view of a tree with no session left in it. */
    private static Glob globOf(MapView mv) {
        if((mv == null) || (mv.ui == null) || (mv.ui.sess == null))
            return null;
        return mv.ui.sess.glob;
    }

    /**
     * <b>Does any live session still have that object in view?</b> (075.3) — asked before an anchored entity is
     * ended, because a gob leaving one character's {@code OCache} is that character walking away and not the
     * object ceasing to exist. Walks the handful of states the client holds; only ever reached for an id
     * something is actually anchored to.
     */
    private static boolean seenAnywhere(long id) {
        for(SessionState st : AddonManager.allStates()) {
            UI u = st.ui;
            if((u == null) || u.destroyed || (u.sess == null))
                continue;
            try {
                if(u.sess.glob.oc.getgob(id) != null)
                    return true;
            } catch(RuntimeException ex) {
                /* a session being taken down answers nothing, which is not a yes */
            }
        }
        return false;
    }

    /**
     * Put a live entity's gob into the scene. Shared by both visibility switches and by {@link #showEntity}, so the
     * per-entity verb and the section-wide one cannot drift apart. No-op when the visual has not published yet (the
     * create reads the flags when it does) or when it is already in. Caller holds the entity monitor.
     */
    private static void attachScene(LuaWorldEntity e) {
        if((e.gob == null) || (e.mv == null) || (e.slot != null) || (e.rc == null))
            return;                                    // 045.1: rc == null ⇒ !grounded ⇒ !shows(e), stated here too
        try {
            // 044.9: position/facing FIRST, then the add. Adding a gob builds its Placement, and building
            // one reads the map at the gob's CURRENT point — so a re-attach that follows a :position(p) has
            // to have moved the gob already, or the add reads the ground it came FROM and throws Loading
            // there (which is exactly what the first in-game round caught: a panel moved onto your own
            // ground stayed out, waiting on the tile 400 tiles away it had just left).
            e.gob.move(e.rc, e.a);
            addToScene(e, e.mv);                       // Resolve still retries a texture mid-upload
        } catch(RuntimeException ex) {
            /* scene gone (relog) — the entity simply stays out */
        }
    }

    /** Take a live entity's gob out of the scene — the inverse of {@link #attachScene}. Caller holds the monitor. */
    private static void detachScene(LuaWorldEntity e) {
        if((e.gob == null) || (e.mv == null) || (e.slot == null))
            return;
        try { e.mv.removeClientGob(e.gob, e.slot); }
        catch(RuntimeException ex) { /* scene gone (relog) — flag set, no scene op */ }
        e.slot = null;
    }

    /**
     * Remove the entity from the scene ({@code <entity>:visible(false)}, V3) — drops the scene slot (so it stops
     * rendering/ticking) but <b>keeps</b> the gob so showing it re-adds it. Marks {@link LuaWorldEntity#hidden} so a
     * hide that lands before the deferred create published keeps the prop out of the scene. No-op if already
     * hidden/dead. Under the entity monitor.
     */
    private static void hideEntity(LuaWorldEntity e) {
        synchronized(e) {
            if(e.dead || e.hidden)
                return;
            e.hidden = true;
            detachScene(e);
        }
    }

    /**
     * (Re)add the entity to the scene ({@code <entity>:visible(true)}, V3) — the inverse of {@link #hideEntity}.
     * No-op if not hidden/dead. <b>Independent of the section switch</b> (043.4): the entity's own answer is
     * recorded either way, and it only enters the scene if its section is showing too — so this verb keeps working
     * while {@code hafen.vr():visible(false)} is in force, and what it wrote is what the section restores.
     * Under the entity monitor.
     */
    private static void showEntity(LuaWorldEntity e) {
        synchronized(e) {
            if(e.dead || !e.hidden)
                return;
            e.hidden = false;
            if(shows(e))
                attachScene(e);
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
                    UI u = screen();
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
                if(e.rc != null)
                    e.gob.move(e.rc, e.a);             // re-assert position/facing after the re-add
            } catch(RuntimeException ex) {
                /* the entity's scene is gone (e.g. a REPL entity changed after a relog) — fields set, no scene op */
            }
        }
    }

    // ---- ANCHOR: what an entity that FOLLOWS a gob can still be told (043.2/043.3) ------------------------------

    /**
     * {@code <entity>:offset(x, y, z)} on an anchored entity — move it relative to the gob it follows <b>in
     * place</b>, which is what makes the offset a property like every other rather than a reason to place it
     * again. Both halves are written: the entity's own desired offset (read by a create that has not published
     * yet) and the live {@link FollowMoving}, whose {@code off} is volatile precisely so the placement pass can
     * pick it up on the next frame without a lock.
     */
    private static void setEntityOffset(LuaWorldEntity e, Coord3f off) {
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

    /**
     * {@code <entity>:position()} — where the thing actually is, as a {@link LuaPosition}: its own point when it
     * stands still, and the followed gob's live interpolated point plus this entity's offset when it is anchored.
     * One position verb, one type (039.2/039.3), so the answer goes straight to {@code s:player():move} or into
     * {@code hafen.store} without conversion; the facing and size that used to ride in the same table are
     * {@code :rotate()} and {@code :scale()}.
     *
     * <p><b>A free entity answers with the place it HOLDS</b> (045.1) — the anchor form, the grid id and the
     * offset within it — while an anchored one answers with the gob's live coordinate, because that is what its
     * place is. One Position type carries both forms and every verb reads either (039), so this is not two
     * answers: it is the same answer told from the side that is true for that entity. It is also what makes the
     * asymmetry a reader can rely on — {@code :info()} reads the same across a walk into a cave and back, while
     * {@code :x()} is only ever this session's answer and is free to differ (or to be nil, in the cave).
     */
    static LuaValue entityPosition(Addon owner, LuaWorldEntity e) {
        long grid; double gx, gy;
        Coord2d rc = null;
        boolean stands;
        synchronized(e) {
            stands = (e.followTgt == 0);
            grid = e.anchorGrid; gx = e.agx; gy = e.agy;
            if(!stands)
                rc = entityWorldPos(e);
        }
        if(stands)
            return LuaPosition.ofAnchor(owner, grid, gx, gy);
        return (rc == null) ? LuaValue.NIL : LuaPosition.of(owner, AddonManager.drawnUser(), rc);
    }

    /**
     * Attach the {@link FollowMoving} at (deferred/immediate) create time when the entity is anchored — called by
     * {@code makeSprite}/{@code makeObject}/{@code makeGhost} once the gob is built (before it enters the scene),
     * so an entity whose visual streams in a beat later is anchored the moment it lands. Caller holds the entity
     * monitor; the fresh gob is not yet published, so no {@code synchronized(gob)} is needed.
     */
    private static void applyEntityFollow(LuaWorldEntity e, Gob gob) {
        if(e.followTgt != 0)
            gob.setattr(new FollowMoving(gob, e.followTgt, e.followOff));
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
     * (R2b generalized the dispatch beyond ghosts) — fire its owner-scoped click event (a {@link LuaEvent},
     * objectified 041.7: {@code :ghost()}/{@code :sprite()} :button() :x() :y()), keyed by
     * {@link LuaWorldEntity#clickEvent()}/{@link LuaWorldEntity#clickKey()}; owner-scoped because the handle is
     * private to its addon, not a global {@link #fire})
     * and its per-entity {@code onClick(handle, button, x, y)}, then return {@code true} so the caller CONSUMES the
     * click — no {@code wdgmsg}, so nothing reaches the server (client-only ⇒ still SAFE-tier, D-032). Returns {@code
     * false} for any non-entity / non-clickable gob, so a normal click proceeds. (A {@code "screen"} sprite has no world
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
        double wx = (mc == null) ? 0 : mc.x, wy = (mc == null) ? 0 : mc.y;
        LuaValue ev = LuaEvent.clicked(e.owner, handle, e.clickKey(), button, wx, wy);
        fireTo(e.owner, e.clickEvent(), ev);           // owner-scoped: an entity belongs to exactly one addon
        if((onClick != null) && onClick.isfunction()) {
            callLua(e.owner, Addon.C_HOOK, onClick, handle, LuaValue.valueOf(button), LuaValue.valueOf(wx),
                LuaValue.valueOf(wy));
        }
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
        // NOT a.surfaces (044.4): a standing widget left the world pick entirely when its clicks became the
        // widget's own. Its quad carries no GobClick, so it cannot reach here — and the same fact is what lets
        // a pointer that missed the panel fall through to whatever is behind it.
        return null;
    }
}
