package io.brodgar.addon;

import haven.Coord2d;
import haven.Gob;
import haven.GobHealth;
import haven.Moving;

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
import java.util.ArrayList;
import java.util.List;

/**
 * A <b>Gob object</b> — the OOP successor of the flat {@code hafen.gob.*(ref)} accessor (spec
 * {@code 017-gob-oop}, D-044). {@code s:world():gob():get(id)} mints one; {@code gob:position()} /
 * {@code gob:name()} / {@code gob:health()} … read it. It wraps <b>the gob id and nothing else</b>: every
 * method re-resolves against a live {@link haven.OCache} and returns {@code nil} if the gob is gone, so the
 * freshness semantics of D-012 survive verbatim — what changed is that the reference stopped being an argument
 * and became the object.
 *
 * <p><b>Userdata, not a table (P1 / D-017).</b> The handle crosses into Lua as
 * {@code LuaValue.userdataOf(luaGob, mt)} with a <b>per-addon</b> metatable ({@code __index} = the shared
 * methods table, {@code __tostring}, {@code __name}) — the R1 handle pattern of {@code luaj-bridge.md}. The
 * interned object is shared by all of the addon's own code, so it must be <b>immutable from Lua</b> (a LuaTable
 * handle could be scribbled on: {@code gob.position = nil}); userdata with no {@code __newindex} rejects writes,
 * and the raw {@link Gob} never crosses the facade. Field access is methods-only — {@code gob.id} is the
 * function and {@code gob:id()} the number.
 *
 * <p><b>A gob is ONE OBJECT, however many characters are looking at it</b> (spec
 * {@code 079-what-the-address-left-behind}). A gob id is the <b>server's</b> and names the same thing in every
 * session that has loaded it, and every read here answers about that thing: {@code name}, {@code health} and
 * {@code overlay} come off the server's object, and {@code position()} hands back a {@link LuaPosition} whose
 * anchor is a <b>server</b> grid id, so whichever session computes it the answer matches. What each session
 * holds is its own {@link Gob} — placed against its own map, with {@code Gob.rc} in its own frame — so a read
 * still has to say which one computes it, and that is one rule stated once in
 * {@link AddonManager#gobUser(long)}: <b>the session on screen when it holds the object, and otherwise the
 * first that does</b>. It is the rule a bare {@code p:distance()} already had, and with one character logged
 * in it resolves to that one.
 *
 * <p><b>Which characters can see it is a read, not bookkeeping</b> — {@code gob:sessions()} asks the live
 * object caches at the moment of the call, so a session that ends drops out of the answer with nothing having
 * to be notified, and an object nobody holds answers the empty array rather than {@code nil}.
 *
 * <p><b>Identity by interning (D-045), on the id.</b> Each addon's {@link Cache} (held in {@link Addon#gobs})
 * maps {@code id} to a {@link WeakReference} of the handle, with a {@link ReferenceQueue}, so two reads of one
 * id are {@code ==} however they were reached — {@code seen[gob] = true} is reliable, {@code s:player():gob()}
 * is literally the same object as {@code s:world():gob():get(<player id>)}, and so is the Gob two characters
 * standing together each find. That is what lets one object be reported once. Weak <b>values</b> (not a
 * {@code WeakHashMap}: that is weak <i>keys</i>) so an entry dies when the addon drops its last reference; the
 * queue is drained on every access (amortised, no sweep timer) because the key + the dead
 * {@code WeakReference} would otherwise accumulate — a per-tick world sweep sees tens of thousands of ids over
 * a session. The cache is <b>per-addon</b> (never static in {@link AddonManager}): no Lua value crosses a
 * sandbox boundary, and it dies whole with the {@link Addon} on {@code :reload}/disable.
 *
 * <p><b>No pinning.</b> A {@code LuaGob} holds no {@link Gob} reference, so a stashed handle can never keep a
 * despawned gob (or its {@code .res}/overlays) alive — strictly better than {@link LuaWidget}, which has to
 * null its {@code Widget} by hand.
 *
 * <p><b>The one thing a Gob does not do is act.</b> Clicking an object is something a <i>character</i> does,
 * and an object that belongs to no character has nobody to send it as — so the click lives on the world of the
 * session that makes it, {@code s:world():click(gob, button, mods)} ({@link WorldApi}), beside the two other
 * gestures with the pointer.
 *
 * <p><b>Threading.</b> Reads run on the UI thread (addon tick / REPL), each attribute under the gob monitor and
 * {@code Loading}-guarded (a resource-backed read throws before it resolves → {@code nil}), exactly as the flat
 * accessor did. The {@link Cache} map itself is guarded on its own monitor (UI + REPL threads touch it).
 */
public final class LuaGob {
    /** The gob id — the object this handle names, in every session that has loaded it, and the whole ref. */
    public final long id;

    private LuaGob(long id) {
        this.id = id;
    }

    /**
     * {@code tostring(gob)} (also the {@code __tostring} answer): {@code Gob(<id>)}.
     *
     * <p>The id is the whole of the handle and the whole of the print: it is the number the server names the
     * object by, so two of them printed side by side compare the thing that crosses.
     */
    public String toString() {
        return "Gob(" + id + ")";
    }

    /** An interned Gob object for {@code id} in {@code owner}'s env — the one way a Gob reaches Lua. */
    static LuaValue of(Addon owner, long id) {
        return owner.gobs.of(id);
    }

    /**
     * Resolve a Lua value handed to a Gob consumer ({@code s:world():click}, {@code follow=}, {@code :same}-
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
        /** {@code id} to handle — one level, because the id is the whole key an object is named by. */
        private final Map<Long, Ref> live = new HashMap<Long, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code id} — a hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(long id) {
            drain();
            Long key = Long.valueOf(id);
            Ref r = live.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaGob(id), meta());
            live.put(key, new Ref(v, key, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref gr = (Ref)r;
                if(live.get(gr.key) == gr)     // not already replaced by a fresh handle for the same id
                    live.remove(gr.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    /** A weak handle reference that remembers its map key, so the {@link ReferenceQueue} drain can unmap it. */
    private static final class Ref extends WeakReference<LuaValue> {
        final Long key;

        Ref(LuaValue v, Long key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the metatable ---------------------------------------------------------------------------

    /**
     * The per-addon metatable: {@code __index} = the methods table through {@link Refusal#closedIndex} (so
     * an unknown verb throws naming what this type does answer), plus {@code __tostring}/{@code __name}.
     */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("gob", methods(owner),
            "a gob is one thing in the world"));
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
        // sessions() — which of your characters can see this object RIGHT NOW, as a COLLECTION of Sessions
        // in the order they joined (079.3; a collection since 091/A-073, whose rule is that a relation whose
        // members are objects is one). :get is refused naming hafen.session(), which is where a session is
        // looked up by name — this is an answer about one object rather than a set to address into.
        //   A LIVE READ. The object caches are asked at the moment of the call and nothing is kept, so a
        // character that logs out is simply not in the next answer and nothing had to be notified — which is
        // what makes "it left one of several sessions" need no event at all. Empty, never nil: an object
        // nobody holds is exactly the case a GobRemoved handler asks about.
        m.set("sessions", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                final LuaGob h = handle(self, "sessions");
                return LuaCollection.create("gob:sessions()", new LuaCollection.Source() {
                    public List<LuaValue> members() {
                        List<LuaValue> out = new ArrayList<LuaValue>();
                        for(String user : AddonManager.gobUsers(h.id))
                            out.add(LuaSession.of(owner, user));
                        return out;
                    }

                    /** A Session is named by its account, so a string filter is a substring of it. */
                    public boolean named() {
                        return true;
                    }

                    public String needle(LuaValue member) {
                        LuaSession s = LuaSession.resolve(member);
                        return (s == null) ? null : s.user;
                    }

                    public String noGet() {
                        return "the sessions holding a gob are addressed by account through"
                            + " hafen.session():get(user); here gob:sessions():find(needle) is the search";
                    }
                }, null);
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
        //   Gob.rc is in ONE session's frame, so the durable anchor is derived through the session this read
        // resolves through, here, where it is known — and what is handed back is an ordinary Position with no
        // session in it, anchored on a SERVER grid id. So two characters looking at one object compute the
        // same place out of two different frames, which is what lets the object itself be the handle.
        m.set("position", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaGob h = handle(self, "position");
                String user = AddonManager.gobUser(h.id);
                if(user == null)
                    return LuaValue.NIL;
                Gob g = AddonManager.getgob(user, h.id);
                if(g == null)
                    return LuaValue.NIL;
                Coord2d rc;
                synchronized(g) { rc = g.rc; }
                return LuaPosition.of(owner, user, rc);
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
        // sdt() — the state bytes the SERVER sent with the gob's resource (113.1): a crop's stage, a
        // gate's leaf, a stockpile's count, as the honest 1-based 0..255 read. What the bytes MEAN is
        // that resource's own published code, never this client's to decode -- see docs/client/resources.md
        // for the OD_RES -> ResDrawable.$cres -> sdt path. nil once the gob is gone or its body is
        // COMPOSED rather than resource-drawn (a player); the empty array is a resource-drawn gob the
        // server sent no state for, and the two are not the same answer.
        m.set("sdt", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaGob h = handle(a.arg1(), "sdt");
                if(Args.passed(a, 2))
                    throw new LuaError("gob:sdt() takes no arguments — arity is the verb here, so call"
                        + " it with no argument to read the state bytes");
                return AddonManager.gobSdt(AddonManager.anygob(h.id));
            }
        });
        // hitbox() -- the ground the object stands on (113.2): every obst (collision) ring the resource
        // carries bar its `build` box, PLUS a rectangle per neg layer (addon: a resource with no obst at
        // all can still carry one of those -- gfx/terobjs/log among them; the two are different facts in
        // the same units and this verb does not distinguish them, which is why
        // docs/addons/api/gob.md#the-ground-it-stands-on says so out loud). An array of polygons, each an
        // array of Positions, rotated by the object's facing and anchored at its place -- the same
        // rotation the client already does to place the object on the terrain. nil once the gob is gone,
        // its resource has not resolved, or it carries neither layer. NOT what gob:scale(k) draws: the
        // footprint is the game's own and does not move when the drawn size does -- see
        // docs/client/resources.md for both layers.
        m.set("hitbox", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaGob h = handle(a.arg1(), "hitbox");
                if(Args.passed(a, 2))
                    throw new LuaError("gob:hitbox() takes no arguments — arity is the verb here, so"
                        + " call it with no argument to read the collision footprint");
                return AddonManager.gobHitbox(owner, h.id);
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
        //   079.3: what is attached is attached to the OBJECT, keyed on its id, and the client draws whichever
        // character has the screen — so an overlay appears whenever the character being drawn can see the
        // object it is on, and there is no session here to be right or wrong about.
        m.set("overlay", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaGob h = handle(a.arg1(), "overlay");
                if(Args.passed(a, 2))
                    throw new LuaError("gob:overlay(key[, spec]) is now a COLLECTION: gob:overlay():get(key)"
                        + " reads one, gob:overlay():add(key) attaches one and its setters say what it draws"
                        + " (:draw/:text/:image/:model/:ghost), gob:overlay():remove(key) removes one, and"
                        + " gob:overlay():list() is every one of them");
                return LuaOverlay.collection(owner, h.id);
            }
        });
        // scale() / scale(k) -- how big the game object is DRAWN (046.1), the elder of this handle's two
        // writes, the other being visible(b) below. Bare
        // reads the factor (1 for a gob nobody scaled, nil once the gob is gone); one number writes it and
        // hands the GOB back, so gob:scale(2):name() is one chain. It is the read/write pair every hafen.vr()
        // entity answers, on the same footing gob:overlay() stands on: client-local, purely visual, unprotected --
        // nothing goes on the wire and nothing about what the gob IS changes. The size is applied in place
        // (T·R·S), so the object's feet stay where they were and it still turns and moves normally, and it
        // ENDS WITH THE LOADED OBJECT: walk far enough to unload it and it comes back its original size.
        //   THE WRITE IS ADDRESSED AT THE OBJECT (080.1). A Gob is per object cache, so the size is applied to
        // EVERY live session's copy of it: one object is drawn the same whichever character is looking at it,
        // and tabbing to another character does not find it its old size. A session that does not hold the
        // object is skipped -- a state, not a fault, like every other answer about who can see a thing. The
        // READ still resolves through one copy, like every reader here (the character on screen when it can
        // see the object, and otherwise whichever of yours can), because they all now agree.
        //   092.7 (A-087): and it REACHES A SESSION THAT LOADS THE OBJECT LATER. The walk above is who holds it
        // at the moment of the write; GobIntent is the same write recorded against the object, applied to each
        // copy as it arrives. The size still ends with the object -- when it leaves its LAST session, which is
        // when GobRemoved fires -- so walking out of range and back is still the original size.
        m.set("scale", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaGob h = handle(self, "scale");
                LuaValue sv = Args.written(a, 2, "gob:scale", "k");
                if(sv == null) {
                    Gob g = gob(self, "scale");
                    return (g == null) ? LuaValue.NIL : LuaValue.valueOf((double)GobScale.value(g));
                }
                float k = scaleArg(sv);
                // A gob that is gone takes the write and does nothing with it: every method here answers nil
                // once the gob is gone and none of them throws, and the write is no exception to that. Nobody
                // holds it, so the walk is empty and that IS doing nothing with it.
                for(Gob g : AddonManager.gobCopies(h.id))
                    GobScale.apply(g, owner, k);
                // 092.7: ...and a character that loads the object AFTERWARDS draws it the same. gobCopies is
                // who holds it now, which is a snapshot; the intent is recorded against the object's id and
                // re-applied by the gob drain when another session's copy arrives (GobIntent).
                GobIntent.scale(h.id, owner, k);
                return self;
            }
        });
        // visible() / visible(b) -- an object the client DRAWS, or does not (114.3). A boolean property is a
        // property, so one name carries both directions: bare reads whether it is drawn (nil once the gob is
        // gone), and true/false writes it and hands the GOB back, so gob:visible(false):name() is one chain.
        //   IT IS NOT A SIZE OF ZERO. Hiding used to mean gob:scale(0.001) -- a sub-pixel model that is still
        // in the tree, still ticked and still drawn. This withholds the Drawable itself, so the object has no
        // geometry at all: nothing is rasterised for it and the pick pass, which tests the drawn scene, cannot
        // find it either. Everything else about the object is untouched -- it is still placed, still moves,
        // still answers every read, and what is ATTACHED at it (its own overlays, and yours) still draws.
        //   The same footing as the size: client-local, purely visual, unprotected. Written to EVERY live
        // copy of the object (080.1), recorded against the object so a character that loads it later draws it
        // hidden too (092.7), dropped when the object leaves its last session, and put back in every session
        // by teardown when the addon goes away (UiApi.teardownGobScales).
        //   It COMPOSES with gob:scale(k): the size lives on a GobScale attrib and this on the Gob itself, so
        // hiding an object does not forget how big it was drawn and showing it brings that size back.
        m.set("visible", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaGob h = handle(self, "visible");
                LuaValue bv = Args.written(a, 2, "gob:visible", "b");
                if(bv == null) {
                    Gob g = gob(self, "visible");
                    return (g == null) ? LuaValue.NIL : LuaValue.valueOf(!g.addoninvis);
                }
                // A bare adjective takes a bare boolean. LuaJ would coerce anything at all through
                // toboolean(), and 0 is TRUE in Lua -- so gob:visible(0) reading as "show it" is the one
                // silent wrong answer this verb can give, and it is refused naming the argument instead.
                if(!bv.isboolean())
                    throw new LuaError("gob:visible(b): b must be true or false, got " + bv.typename());
                boolean vis = bv.toboolean();
                // A gob that is gone takes the write and does nothing with it, like every other verb here:
                // nobody holds it, so the walk is empty and that IS doing nothing with it.
                for(Gob g : AddonManager.gobCopies(h.id))
                    g.addonvisible(vis);
                GobIntent.visible(h.id, owner, vis);
                return self;
            }
        });
        m.set("player", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "isPlayer");
                // nil only when the gob is GONE; a gob whose name hasn't resolved yet is simply not a player.
                return (g == null) ? LuaValue.NIL : LuaValue.valueOf(AddonManager.gobIsPlayer(g));
            }
        });
        // kin() — the O(1) half of the Kin <-> Gob link (020.2): the SERVER marks a kinned player's gob with
        // the `ui/obj/buddy` attrib, which carries the buddy id, so this is a single attribute read. nil is
        // AMBIGUOUS on purpose: not on that character's roster / the gob is gone / it is not a player at all.
        // A buddy id counts inside ONE roster, so the Kin this hands back is the roster of the session the
        // read resolved through, and the mark it read is a number in that same roster. Ask a named character
        // whether it knows somebody with s:kin(), which is the addressed door.
        m.set("kin", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaGob h = handle(self, "kin");
                String user = AddonManager.gobUser(h.id);
                if(user == null)
                    return LuaValue.NIL;
                Integer bid = LuaKin.buddyId(AddonManager.getgob(user, h.id));
                return (bid == null) ? LuaValue.NIL : LuaKin.of(owner, user, bid.intValue());
            }
        });
        // party() — 094 (A-110): the PartyMember this gob is, in the session that read it, or nil. The
        // missing inverse: member:gob() crossed to the world and nothing came back, while the kin pair went
        // both ways (gob:kin() and kin:gob()) -- so a reader who had learned one guessed gob:party() and got
        // a silent nil from the methodIndex, then had to find s:party():get(gob:id()) instead.
        //   A Gob is per object and a party is per session, so the answer is read in the session this handle
        // resolves through, which is the same one gob:kin() answers in.
        m.set("party", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaGob h = handle(self, "party");
                String user = AddonManager.gobUser(h.id);
                if(user == null)
                    return LuaValue.NIL;
                return (LuaPartyMember.member(user, h.id) == null)
                    ? LuaValue.NIL : LuaPartyMember.of(owner, user, h.id);
            }
        });
        // distance([other]) — world distance to another Gob; `other` defaults to the character measuring.
        //   Gob.rc is one session's frame, so subtracting two of them across sessions measures nothing: the
        // pair is measured inside ONE character's world, the screen's when it holds both and otherwise the
        // first that does. Two objects no single character can see have no distance between them and answer
        // nil — the same nothing an object that has despawned answers.
        m.set("distance", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue other) {
                LuaGob ha = handle(self, "distance");
                if(other.isnil()) {
                    String user = AddonManager.gobUser(ha.id);
                    if(user == null)
                        return LuaValue.NIL;
                    // the character whose eyes this object is being seen with
                    return between(AddonManager.getgob(user, ha.id), AddonManager.playerGob(user));
                }
                LuaGob hb = resolve(other);
                if(hb == null)
                    throw new LuaError("gob:distance([other]) -- 'other' must be a Gob object"
                        + " (s:world():gob():get(id)), or nil for the character measuring");
                String user = AddonManager.gobUser(ha.id, hb.id);
                if(user == null)
                    return LuaValue.NIL;
                return between(AddonManager.getgob(user, ha.id), AddonManager.getgob(user, hb.id));
            }
        });
        return m;
    }

    /** World distance between two of one session's gobs, or {@code nil} while either has no position. */
    private static LuaValue between(Gob a, Gob b) {
        if((a == null) || (b == null))
            return LuaValue.NIL;
        Coord2d ra, rb;
        synchronized(a) { ra = a.rc; }
        synchronized(b) { rb = b.rc; }
        if((ra == null) || (rb == null))
            return LuaValue.NIL;
        return LuaValue.valueOf(ra.dist(rb));
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
     * The LIVE gob behind a method's {@code self} — re-resolved every call, in whichever session
     * {@link AddonManager#gobUser(long)} names, and {@code null} once no session holds it at all.
     */
    private static Gob gob(LuaValue self, String method) {
        return AddonManager.anygob(handle(self, method).id);
    }
}
