package io.brodgar.addon;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.GobHealth;
import haven.MapView;
import haven.Moving;
import haven.OCache;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * A <b>Gob object</b> — the OOP successor of the flat {@code hafen.gob.*(ref)} accessor (spec
 * {@code 017-gob-oop}, D-044). {@code s:world():gob():get(id)} mints one; {@code gob:position()} /
 * {@code gob:name()} / {@code gob:health()} … read it. It wraps <b>an id and the session that reads it</b>: every
 * method re-resolves against that session's {@link haven.OCache} through {@link AddonManager#getgob(String, long)}
 * and returns {@code nil} if the gob is gone, so the
 * freshness semantics of D-012 survive verbatim — what changed is that the reference stopped being an argument
 * and became the object.
 *
 * <p><b>Userdata, not a table (P1 / D-017).</b> The handle crosses into Lua as
 * {@code LuaValue.userdataOf(luaGob, mt)} with a <b>per-addon</b> metatable ({@code __index} = the shared
 * methods table, {@code __tostring}, {@code __name}) — the R1 handle pattern of {@code luaj-bridge.md}. The
 * interned object is shared by all of the addon's own code, so it must be <b>immutable from Lua</b> (a LuaTable
 * handle could be scribbled on: {@code gob.position = nil}); userdata with no {@code __newindex} rejects writes,
 * and the raw {@link Gob} never crosses the facade. Field access is methods-only — {@code gob.id} is the
 * function, {@code gob:id()} the number, and a retired spelling ({@code gob.pos}) throws naming its replacement.
 *
 * <p><b>It wraps the SESSION beside the id</b> (spec {@code 076-the-session-is-the-address}). A gob id is the
 * <b>server's</b> and names the same object in every session that has loaded it, but the {@link Gob} is not
 * shared: each {@link OCache} holds its own, placed against its own session's map, and {@code Gob.rc} is in
 * that session's frame. So the id says <i>which object</i> and the session says <i>whose copy of it</i> —
 * which is what makes a read taken on the session an addon named be about that character rather than about
 * whoever holds the screen.
 *
 * <p><b>Identity by interning (D-045), on the pair.</b> Each addon's {@link Cache} (held in
 * {@link Addon#gobs}) maps {@code account} to {@code id} to a {@link WeakReference} of the handle, with a
 * {@link ReferenceQueue}, so two reads of one id <b>in one session</b> are {@code ==} and
 * {@code seen[gob] = true} is reliable, and {@code s:player():gob()} is literally the same object as
 * {@code s:world():gob():get(<player id>)}. Across two sessions the same id is two handles, and
 * {@code :id()} is the identity that crosses them: Lua table keys use primitive equality rather than
 * {@code __eq}, so one object per pair is the only arrangement whose two equalities cannot disagree. Two
 * levels of map rather than one composite key, because the composite would be an allocation on the hottest
 * path in the layer. Weak <b>values</b> (not a {@code WeakHashMap}: that is weak
 * <i>keys</i>) so an entry dies when the addon drops its last reference; the queue is drained on every access
 * (amortised, no sweep timer) because the key + the dead {@code WeakReference} would otherwise
 * accumulate — a per-tick world sweep sees tens of thousands of ids over a session. The cache is
 * <b>per-addon</b> (never static in {@link AddonManager}): no Lua value crosses a sandbox boundary, and it dies
 * whole with the {@link Addon} on {@code :reload}/disable.
 *
 * <p><b>No pinning.</b> A {@code LuaGob} holds no {@link Gob} reference, so a stashed handle can never keep a
 * despawned gob (or its {@code .res}/overlays) alive — strictly better than {@link LuaWidget}, which has to
 * null its {@code Widget} by hand.
 *
 * <p><b>Threading.</b> Reads run on the UI thread (addon tick / REPL), each attribute under the gob monitor and
 * {@code Loading}-guarded (a resource-backed read throws before it resolves → {@code nil}), exactly as the flat
 * accessor did. The {@link Cache} map itself is guarded on its own monitor (UI + REPL threads touch it).
 */
public final class LuaGob {
    /** The gob id — the object this handle names, in every session that has loaded it. */
    public final long id;
    /** The account whose session this handle reads it through — the other half of the pair it is interned on. */
    public final String user;

    private LuaGob(String user, long id) {
        this.user = user;
        this.id = id;
    }

    /**
     * {@code tostring(gob)} (also the {@code __tostring} answer): {@code Gob(<id>)}.
     *
     * <p>The id alone, and the session left out: the id is what names the object and is the same number in
     * every session that has loaded it, so two of them printed side by side compare the thing that crosses.
     * Which session a handle reads through is in the addon's own hand — {@code s} is what it called
     * {@code :world()} on — and never in a string it would have to parse back out.
     */
    public String toString() {
        return "Gob(" + id + ")";
    }

    /**
     * An interned Gob object for {@code id} <b>as session {@code user} sees it</b>, in {@code owner}'s env —
     * the one way a Gob reaches Lua.
     */
    static LuaValue of(Addon owner, String user, long id) {
        return owner.gobs.of(user, id);
    }

    /**
     * Resolve a Lua value handed to a Gob consumer ({@code gob:click}, {@code follow=}, {@code :same}-
     * style comparisons) back to its {@code LuaGob}; {@code null} for anything that is not a Gob object (nil, a
     * raw id, a token string — the hard cut accepts none of those, D-044).
     */
    static LuaGob resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaGob) ? (LuaGob)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /**
     * One addon's Gob interning cache and metatable (its {@link Addon#gobs}). Weak values + a
     * {@link ReferenceQueue} drained on every access; the metatable and methods table are built once, lazily.
     * Holds its {@link Addon} because {@code gob:kin()} has to mint the <b>owner's</b> interned Kin (020.2).
     */
    static final class Cache {
        private final Addon owner;
        /** {@code account} to {@code id} to handle. Two levels, so the per-call key is the {@code Long} it was. */
        private final Map<String, Map<Long, Ref>> live = new HashMap<String, Map<Long, Ref>>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code (user, id)} — a hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(String user, long id) {
            drain();
            Map<Long, Ref> byid = live.get(user);
            if(byid == null)
                live.put(user, byid = new HashMap<Long, Ref>());
            Long key = Long.valueOf(id);
            Ref r = byid.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                byid.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaGob(user, id), meta());
            byid.put(key, new Ref(v, user, key, dead));
            return v;
        }

        /**
         * Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). An
         * account's inner map goes with its last entry, so a session that has ended leaves nothing behind
         * once the addon has let go of its handles.
         */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref gr = (Ref)r;
                Map<Long, Ref> byid = live.get(gr.user);
                if(byid == null)
                    continue;
                if(byid.get(gr.key) == gr)     // not already replaced by a fresh handle for the same pair
                    byid.remove(gr.key);
                if(byid.isEmpty())
                    live.remove(gr.user);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    /** A weak handle reference that remembers both map keys, so the {@link ReferenceQueue} drain can unmap it. */
    private static final class Ref extends WeakReference<LuaValue> {
        final String user;
        final Long key;

        Ref(LuaValue v, String user, Long key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.user = user;
            this.key = key;
        }
    }

    // ---- the metatable ---------------------------------------------------------------------------

    /** The per-addon metatable: {@code __index} = the methods table, plus {@code __tostring}/{@code __name}. */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("gob", methods(owner)));
        mt.set("__name", LuaValue.valueOf("Gob"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaGob h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Gob(?)" : h.toString());
            }
        });
        return mt;
    }

    /**
     * The method set. Each reader re-resolves the gob and answers {@code nil} when it is gone — so a stashed
     * handle tracks a moving gob and goes quiet when it despawns (D-012's freshness, kept). {@code :id()} is the
     * exception: it answers from the handle alone, so it still works inside a {@code GobRemoved} handler.
     */
    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        m.set("id", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf((double)handle(self, "id").id);
            }
        });
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(gob(self, "exists") != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch (the old hafen.gob.info shape), for logging/serialising.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return AddonManager.gobSnapshot(gob(self, "info"));
            }
        });
        // position() — where the gob is, as a Position (039.2): computable (p:offset(dx, dy) crosses grid
        // boundaries) and durable (p:info() is the {gridId, x, y} form hafen.store keeps). nil once the gob is
        // gone, or before it has a position at all.
        //   076.3: Gob.rc is in the gob's OWN session's frame, so the durable anchor is derived through THAT
        // session's map, here, where the session is known — and what is handed back is an ordinary Position
        // with no session in it. A place is answerable in whichever session you ask; a frame-relative pair of
        // numbers is not, so the value that escapes the session is the grid id and the offset inside it.
        m.set("position", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaGob h = handle(self, "position");
                Gob g = AddonManager.getgob(h.user, h.id);
                if(g == null)
                    return LuaValue.NIL;
                Coord2d rc;
                synchronized(g) { rc = g.rc; }
                return LuaPosition.of(owner, h.user, rc);
            }
        });
        m.set("facing", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "facing");
                if(g == null)
                    return LuaValue.NIL;
                synchronized(g) {
                    return LuaValue.valueOf(g.a);
                }
            }
        });
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "name");
                String n = (g == null) ? null : AddonManager.gobName(g);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        m.set("health", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "health");
                if(g == null)
                    return LuaValue.NIL;
                GobHealth h = g.getattr(GobHealth.class);
                return (h == null) ? LuaValue.NIL : LuaValue.valueOf(h.hp);
            }
        });
        m.set("moving", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "moving");
                if(g == null)
                    return LuaValue.NIL;
                return LuaValue.valueOf(g.getattr(Moving.class) != null);
            }
        });
        m.set("speed", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "speed");
                if(g == null)
                    return LuaValue.NIL;
                Moving mv = g.getattr(Moving.class);
                if(mv == null)
                    return LuaValue.NIL;
                try {
                    return LuaValue.valueOf(mv.getv());
                } catch(RuntimeException e) {   // Loading etc. — speed unavailable this frame
                    return LuaValue.NIL;
                }
            }
        });
        m.set("speech", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "speech");
                String s = (g == null) ? null : AddonManager.gobSpeech(g);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s);
            }
        });
        m.set("icon", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "icon");
                String s = (g == null) ? null : AddonManager.gobIcon(g);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s);
            }
        });
        // overlay() — THE ONE WAY to attach anything to a game object, and the one way to read what is already
        // attached (038.1), as the collection of everything on this gob (039.3):
        //   gob:overlay():list(filter)   -- every overlay: yours, then the GAME's own (ov:native())
        //   gob:overlay():get(key)       -- that one, or nil
        //   gob:overlay():add(key)       -- attach a BARE one, or REPLACE what that key already named
        //   gob:overlay():remove(key)    -- remove it
        // What it draws is said by the setters on the Overlay :add hands back, in ONE of the two spaces (038.2).
        // SCREEN space, at the gob's projected point: :draw(fn) (fn(g, gob, sx, sy)) or :text(s) with :color and
        // a two-number :offset. The 3D WORLD, anchored to the gob every frame: :image(asset), :model(asset) or
        // :ghost(res), with a three-number :offset in world units (z = up) and :scale/:alpha/:tint/:rotate. An
        // overlay says ONE thing: a second, different kind is refused naming the first. The game's own overlays
        // are READ-ONLY: an :add onto a native key, or a :remove of one, raises naming the key.
        // The collection is a VIEW — derived from the gob on every call, holding nothing — so it cannot outlive
        // the gob, and the state it reads lives on the gob itself: nothing is searched and nothing is swept.
        //   076.3: an overlay is DRAWN, and there is one screen. A session that is not drawn has no render
        // tree, so an overlay attached to one of its gobs is never ticked and never appears — and the
        // screen-space half is projected through the drawn view besides. So this answers for the session on
        // screen and refuses for any other, naming it, rather than handing back a collection that draws
        // nothing. What IS addressable on a background session's gob is every read, and gob:scale.
        m.set("overlay", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaGob h = handle(a.arg1(), "overlay");
                if(!AddonManager.drawn(h.user))
                    throw new LuaError("gob:overlay(): that gob was read through a session that is not on"
                        + " screen, and an overlay is drawn — a session the client is not drawing has no"
                        + " scene to draw it into. Read the gob through hafen.session():current() to draw"
                        + " at it.");
                if(Args.passed(a, 2))
                    throw new LuaError("gob:overlay(key[, spec]) is now a COLLECTION: gob:overlay():get(key)"
                        + " reads one, gob:overlay():add(key) attaches one and its setters say what it draws"
                        + " (:draw/:text/:image/:model/:ghost), gob:overlay():remove(key) removes one, and"
                        + " gob:overlay():list() is every one of them");
                return LuaOverlay.collection(owner, h.id);
            }
        });
        // scale() / scale(k) -- how big the game object is DRAWN, and the handle's first WRITE (046.1). Bare
        // reads the factor (1 for a gob nobody scaled, nil once the gob is gone); one number writes it and
        // hands the GOB back, so gob:scale(2):name() is one chain. It is the read/write pair every hafen.vr()
        // entity answers, on the same footing gob:overlay() stands on: client-local, purely visual, unprotected —
        // nothing goes on the wire and nothing about what the gob IS changes. The size is applied in place
        // (T·R·S), so the object's feet stay where they were and it still turns and moves normally, and it
        // ENDS WITH THE LOADED OBJECT: walk far enough to unload it and it comes back its original size.
        m.set("scale", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaGob h = handle(self, "scale");
                LuaValue sv = Args.written(a, 2, "gob:scale", "k");
                // That session's own Gob: a size set on one nobody is looking at is applied to the object
                // it names and is there when that session takes the screen.
                Gob g = AddonManager.getgob(h.user, h.id);
                if(sv == null)
                    return (g == null) ? LuaValue.NIL : LuaValue.valueOf((double)GobScale.value(g));
                float k = scaleArg(sv);
                // A gob that is gone takes the write and does nothing with it: every method here answers nil
                // once the gob is gone and none of them throws, and the first write is no exception to that.
                if(g != null)
                    GobScale.apply(g, owner, k);
                return self;
            }
        });
        // click([button [, mods]]) — click the game object, and the handle's first SERVER write (048.1): exactly
        // the MapView "click" a left/right-click on this gob sends, so the client stays server-authoritative.
        // button 1 = left (default; select/interact), 3 = right (the radial menu); mods = a modifier bitfield
        // (0 default; Shift=1 Ctrl=2 Alt=4, matching the keybind syntax). It sends the bare gob-click encoding
        // {…, 0, gobid, gobrc, 0, -1} — a generic "the WHOLE object", faithful for world objects
        // (trees/containers/…); a specific sub-mesh or composite body part is not targeted (deferred).
        //   PROTECTED by the per-addon "gob.click" permission, and the gate runs FIRST — before the gob is even
        // looked up (D-213), so an addon that never declared it is told that rather than "no such gob". Unlike
        // every read here, a gob that is GONE throws: a click is a message about a specific object, and there is
        // no such thing as sending it to nothing. Hands the Gob back, so a click chains.
        m.set("click", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                AddonManager.requirePermission(owner, Permission.GOB_CLICK);
                LuaGob h = handle(self, "click");
                // 076.3: the send goes to the character on SCREEN and to no other — a walk is the whole of
                // what a background session takes, which is the client's own line rather than this API's.
                MapView mv = AddonManager.sendView(h.user, "gob:click");
                Gob g = AddonManager.getgob(h.user, h.id);
                if(g == null)
                    throw new LuaError("gob:click: this gob is gone — it left view or despawned"
                        + " (gob:exists() is false). Nothing was sent.");
                Coord2d rc;
                synchronized(g) { rc = g.rc; }              // OCache discipline: copy under the gob lock
                if(rc == null)
                    throw new LuaError("gob:click: the gob has no position yet");
                Coord pc = (mv.ui != null) ? mv.ui.mc : Coord.z;   // dummy screen coord, like MiniMap.mvclick
                mv.wdgmsg("click", clickGobArgs(pc, a.arg(2).optint(1), a.arg(3).optint(0),
                                                (int)g.id, rc.floor(OCache.posres)));
                // 047.3: the same token the real click records in MapView.Click.hit — and here the gob is not
                // correlated but KNOWN, this being addon code that named it. lcc is untouched by a programmatic
                // click, so a menu the server opens in reply matches on the press point exactly as it does for a
                // mouse click, and a player press in between moves lcc and invalidates it, which is the point.
                ClickToken.note(g.id, (mv.ui != null) ? mv.ui.lcc : null);
                return self;
            }
        });
        m.set("isPlayer", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "isPlayer");
                // nil only when the gob is GONE; a gob whose name hasn't resolved yet is simply not a player.
                return (g == null) ? LuaValue.NIL : LuaValue.valueOf(AddonManager.gobIsPlayer(g));
            }
        });
        // kin() — the O(1) half of the Kin <-> Gob link (020.2): the SERVER marks a kinned player's gob with
        // the `ui/obj/buddy` attrib, which carries the buddy id, so this is a single attribute read. nil is
        // AMBIGUOUS on purpose: not on that character's roster / the gob is gone / it is not a player at all.
        // The Kin it hands back is THIS gob's session's (077.2): a buddy id counts inside one roster, and
        // this gob is the one that session can see, so the mark on it is a number in that same roster.
        m.set("kin", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaGob h = handle(self, "kin");
                Gob g = gob(self, "kin");
                Integer bid = LuaKin.buddyId(g);
                return (bid == null) ? LuaValue.NIL : LuaKin.of(owner, h.user, bid.intValue());
            }
        });
        // distance([other]) — world distance to another Gob; `other` defaults to the player.
        m.set("distance", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue other) {
                LuaGob ha = handle(self, "distance");
                Gob a = AddonManager.getgob(ha.user, ha.id);
                Gob b;
                if(other.isnil()) {
                    b = AddonManager.playerGob(ha.user);   // the character whose eyes this gob was seen with
                } else {
                    LuaGob h = resolve(other);
                    if(h == null)
                        throw new LuaError("gob:distance([other]) -- 'other' must be a Gob object (s:world():gob():get(id)), or nil for that session's own character");
                    // Gob.rc is its session's frame, so subtracting two of them across sessions measures
                    // nothing. Two characters standing together still have two frames; the offset between
                    // them is the client's own business and is not arithmetic an addon should be handed.
                    if(!h.user.equals(ha.user))
                        throw new LuaError("gob:distance(other): the two gobs were read through different"
                            + " sessions, and each session's coordinates are relative to where it logged in"
                            + " — so there is no distance between them. Read both through one session.");
                    b = AddonManager.getgob(h.user, h.id);
                }
                if((a == null) || (b == null))
                    return LuaValue.NIL;
                Coord2d ra, rb;
                synchronized(a) { ra = a.rc; }
                synchronized(b) { rb = b.rc; }
                if((ra == null) || (rb == null))
                    return LuaValue.NIL;
                return LuaValue.valueOf(ra.dist(rb));
            }
        });
        return m;
    }

    /**
     * The full MapView {@code "click"} args for a generic click on the gob {@code (gobId, gobRc)} — the
     * {@code {pc, mc, button, mods}} prefix extended with {@link haven.Gob.GobClick#clickargs}'
     * {@code {0, gobid, gobrc, 0, -1}} (no overlay, no specific sub-mesh). {@code mc} = the gob's own floored
     * position, as a click landing on its base would carry. Pure/testable — it holds no live state, so the wire
     * shape can be asserted without a session.
     */
    static Object[] clickGobArgs(Coord pc, int button, int mods, int gobId, Coord gobRc) {
        return new Object[] {pc, gobRc, button, mods, 0, gobId, gobRc, 0, -1};
    }

    /**
     * The factor of a {@code gob:scale(k)} write, or a refusal that names the rule it broke. Three rules, and
     * each one is a different way to lose the object: a non-number is not a size at all, a non-finite one has
     * no matrix, {@code 0} collapses the model to a point and a negative mirrors it (flipping every triangle's
     * winding, so the thing renders inside out). The vr siblings clamp the same range because their factor
     * arrives inside an options table where a refusal has nowhere to land; here it is a direct argument on a
     * direct verb, and the loudest failure is the one at the call site that caused it.
     */
    private static float scaleArg(LuaValue v) {
        if(!v.isnumber())
            throw new LuaError("gob:scale(k): k must be a number, got " + v.typename());
        double k = v.todouble();
        if(Double.isNaN(k) || Double.isInfinite(k))
            throw new LuaError("gob:scale(k): k must be a finite number, got " + k);
        if(k <= 0.0)
            throw new LuaError("gob:scale(k): k must be greater than 0, got " + k + " — 0 collapses the object"
                + " to a point and a negative one turns it inside out. gob:scale(1) is the original size");
        return (float)k;
    }

    // ---- self resolution ------------------------------------------------------------------------

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaGob handle(LuaValue self, String method) {
        LuaGob h = resolve(self);
        if(h == null)
            throw new LuaError("gob:" + method + "() -- use a COLON call on a Gob object (s:world():gob():get(id),"
            + " s:world():gob():nearest(...), s:player():gob(), where s is a Session)");
        return h;
    }

    /**
     * The LIVE gob behind a method's {@code self} — re-resolved every call <b>in the handle's own session</b>,
     * {@code null} once it is gone (and while that session is not one the client holds).
     */
    private static Gob gob(LuaValue self, String method) {
        LuaGob h = handle(self, method);
        return AddonManager.getgob(h.user, h.id);
    }
}
