package io.brodgar.addon;

import haven.Gob;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An <b>Overlay object</b> — one thing attached to a game object, whether an addon attached it
 * ({@code gob:overlay():add(key)}) or the game itself did ({@code Gob.ols}: the fire's flame, the crop's growth
 * stage, a curiosity's sparkle). Spec {@code 038-gob-overlays}, restructured by {@code 039-uniform-api} §2.3/§2.5.
 *
 * <p><b>A collection, and a builder.</b> {@code gob:overlay()} is the {@link LuaCollection} of everything on the
 * gob ({@link #collection}); {@code :add(key)} attaches a <b>bare</b> overlay and hands back this object, whose
 * setters say what it draws and how it looks. There is no spec table any more, and so no one-shot parse: an
 * overlay's configuration is its own state, {@code ov:text("…")} relabels a live one, and a record that has not
 * yet named a kind draws nothing at all — which is how a half-configured overlay never paints.
 *
 * <p><b>Two spaces, one read.</b> {@code gob:overlay():list()} answers every overlay on the gob and each one says
 * which it is: {@code ov:native()} is {@code true} for the game's own and {@code false} for the addon's. The
 * native ones are <b>read-only</b> — an {@code :add} onto a native key and a {@code :remove} of one both raise,
 * naming the key, rather than silently doing nothing to something the addon does not own.
 *
 * <p><b>The key is the identity, and a native one is the RESOURCE NAME.</b> A {@code Gob.Overlay} carries an
 * {@code id} only when the server chose to give it one, and an id is a number no other part of this API speaks —
 * so the name a Lua caller can address is the resource, exactly as an icon category collapses its sub-id
 * (D-093). Two of the game's overlays sharing one resource therefore collapse to one key, and 038.1
 * <b>measured</b> how often rather than assuming: 13 of 33 gobs carrying overlays had two of one resource.
 * So a native Overlay is the <b>UNION</b> of them, on 037.3's mask precedent, and the multiplicity that
 * would otherwise be lost is published — {@code ov:count()}. Nothing else is lost, because a native overlay
 * is read-only: there is no operation a finer identity would enable.
 *
 * <p><b>Nothing crosses but the answer.</b> A handle holds the gob id, the key and the native flag, and
 * re-resolves through the {@link LuaGobOverlay} attrib (or through {@code Gob.ols}) on every call — so a stashed
 * handle tracks the gob, and {@code ov:exists()} goes false when the overlay, or the gob under it, is gone.
 * Interned per (addon, gob id, native, key), the {@link LuaGob} shape one level down.
 */
public final class LuaOverlay {
    /** The gob this overlay is attached to. */
    public final long gob;
    /** The key: the addon's own for its overlays, the resource name for the game's. */
    public final String key;
    /** Is this one of the game's own overlays (read-only) rather than the addon's? */
    public final boolean nat;

    private LuaOverlay(long gob, String key, boolean nat) {
        this.gob = gob;
        this.key = key;
        this.nat = nat;
    }

    /** {@code tostring(ov)}: {@code Overlay(<key>@<gobId>)}, with a {@code *} on the game's own. */
    public String toString() {
        return "Overlay(" + (nat ? "*" : "") + key + "@" + Long.toString(gob) + ")";
    }

    /** An interned Overlay object in {@code owner}'s env — the one way one reaches Lua. */
    static LuaValue of(Addon owner, long gob, String key, boolean nat) {
        return owner.gobOverlayObjs.of(gob, key, nat);
    }

    /** The {@code LuaOverlay} behind a Lua value, or {@code null} for anything that is not an Overlay object. */
    static LuaOverlay resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaOverlay) ? (LuaOverlay)o : null;
    }

    /**
     * Every overlay on the gob, this addon's own first (in attach order) then the game's own. Another addon's
     * overlays are not in it (keys are per addon, so two addons' {@code "tag"} on one gob neither collide nor
     * see each other), and a departed gob simply has none.
     */
    private static List<LuaValue> members(Addon owner, long gobId) {
        List<LuaValue> out = new ArrayList<LuaValue>();
        Gob g = AddonManager.getgob(gobId);
        if(g == null)
            return out;
        LuaGobOverlay store = LuaGobOverlay.on(g);
        if(store != null) {
            for(String k : store.keys(owner))
                out.add(of(owner, gobId, k, false));
        }
        for(String k : LuaGobOverlay.nativeKeys(g))
            out.add(of(owner, gobId, k, true));
        return out;
    }

    /**
     * {@code gob:overlay()} — the collection of everything attached to one game object, and the one door to
     * every one of them: {@code :list(filter)} reads them all, {@code :get(key)} names one, {@code :add(key)}
     * attaches a bare one whose setters say what it draws, and {@code :remove(keyOrOverlay)} ends one.
     *
     * <p>It is a <b>view</b>: it is derived from the gob on every call and holds nothing between them, so it
     * cannot outlive the gob and needs no pruning of its own. A string filter matches the <b>key</b>, which for
     * the game's own overlays is their resource name.
     */
    static LuaValue collection(final Addon owner, final long gobId) {
        return LuaCollection.create("gob:overlay()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                return LuaOverlay.members(owner, gobId);
            }

            public String needle(LuaValue member) {
                LuaOverlay h = resolve(member);
                return (h == null) ? null : h.key;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                String k = keyArg(key, "gob:overlay():get");
                Gob g = AddonManager.getgob(gobId);
                if(g == null)
                    return LuaValue.NIL;
                LuaGobOverlay store = LuaGobOverlay.on(g);
                if((store != null) && (store.get(owner, k) != null))
                    return of(owner, gobId, k, false);
                return LuaGobOverlay.findNative(g, k) ? of(owner, gobId, k, true) : LuaValue.NIL;
            }

            public boolean creatable() {
                return true;
            }

            // add(key) — attach a BARE overlay and hand it back, for its setters to say what it draws. The same
            // key twice is a REPLACE: the old record ends (its world entity with it) and the removal is reported
            // beside the add, so a handler keeping its own set stays balanced — the key survives, but the thing
            // under it is a different one.
            public LuaValue addMember(Varargs a) {
                String k = keyArg(Args.required(a, 2, "gob:overlay():add", "key"), "gob:overlay():add");
                Gob g = AddonManager.getgob(gobId);
                if(g == null)
                    throw new LuaError("gob:overlay():add(\"" + k + "\"): that gob is gone, so there is nothing"
                        + " to attach it to -- gob:exists() is the test, and an overlay dies with its gob");
                if(LuaGobOverlay.findNative(g, k))
                    throw new LuaError("gob:overlay():add(\"" + k + "\"): that key names one of the GAME's own"
                        + " overlays, which are read-only -- pick a key of your own (a native key is a resource"
                        + " name; gob:overlay():list() lists them)");
                LuaGobOverlay.Attach old = LuaGobOverlay.ensure(g).put(new LuaGobOverlay.Attach(owner, k, gobId));
                if(old != null) {
                    old.dispose();
                    AddonManager.queueGobOverlay(false, gobId, k, owner);
                }
                AddonManager.queueGobOverlay(true, gobId, k, owner);
                return of(owner, gobId, k, false);
            }

            public boolean destroyable() {
                return true;
            }

            // remove(keyOrOverlay) — end one of yours. Removing what is not there is INERT: a key you never
            // attached, one already removed, or one that died with its gob is a moment, not a mistake.
            public void removeMember(LuaValue x) {
                String k;
                LuaOverlay h = resolve(x);
                if(h != null) {
                    if(h.gob != gobId)
                        throw new LuaError("gob:overlay():remove(ov): that Overlay hangs on a different gob"
                            + " (ov:gob() says which)");
                    if(h.nat)
                        throw new LuaError("gob:overlay():remove(\"" + h.key + "\"): that is one of the GAME's"
                            + " own overlays, which are read-only");
                    k = h.key;
                } else {
                    k = keyArg(x, "gob:overlay():remove");
                }
                Gob g = AddonManager.getgob(gobId);
                if(g == null)
                    return;                          // it died with its gob: the removal already happened
                if(LuaGobOverlay.findNative(g, k))
                    throw new LuaError("gob:overlay():remove(\"" + k + "\"): that key names one of the GAME's own"
                        + " overlays, which are read-only");
                LuaGobOverlay store = LuaGobOverlay.on(g);
                if(store == null)
                    return;
                LuaGobOverlay.Attach old = store.remove(owner, k);
                if(old != null) {
                    old.dispose();                   // a world-space record owns a live entity; the map does not
                    AddonManager.queueGobOverlay(false, gobId, k, owner);
                }
                LuaGobOverlay.prune(g);
            }
        }, null);
    }

    /** A key argument: a string, and never a number — LuaJ counts a number as a string, so coerce nothing. */
    private static String keyArg(LuaValue kv, String verb) {
        if(!kv.isstring() || kv.isnumber())
            throw new LuaError(verb + "(key): the key must be a string -- it is YOUR name for this overlay"
                + " (keys are per addon), and a native one is the overlay's resource name");
        return kv.tojstring();
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's Overlay interning cache and metatable ({@link Addon#gobOverlayObjs}); the {@link LuaMask} shape. */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Ref> live = new HashMap<String, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for (gob, key, native) — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(long gob, String key, boolean nat) {
            drain();
            String ck = Long.toString(gob) + (nat ? "\0*\0" : "\0-\0") + key;
            Ref r = live.get(ck);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(ck);
            }
            LuaValue v = LuaValue.userdataOf(new LuaOverlay(gob, key, nat), meta());
            live.put(ck, new Ref(v, ck, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref or = (Ref)r;
                if(live.get(or.key) == or)
                    live.remove(or.key);
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
        final String key;

        Ref(LuaValue v, String key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Overlay metatable ---------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("overlay", methods(owner)));
        mt.set("__name", LuaValue.valueOf("Overlay"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOverlay h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Overlay(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // key() — what it answers to; answers from the handle alone, so it still reads after the gob is gone.
        m.set("key", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "key").key);
            }
        });
        // gob() — the Gob it is attached to (D-066: the relation, not a stored id).
        m.set("gob", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaGob.of(owner, handle(self, "gob").gob);
            }
        });
        // native() — is this one of the GAME's overlays (read-only) rather than one of ours?
        m.set("native", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "native").nat);
            }
        });
        // res() — WHAT it is drawn from: the key itself for a native one (a native key IS its resource name), the
        // .res name / asset path for a world-space one of ours, and nil for a screen-space one (it draws Lua, not
        // a resource — which is exactly what distinguishes the two spaces from the outside).
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOverlay h = handle(self, "res");
                if(h.nat)
                    return LuaValue.valueOf(h.key);
                LuaGobOverlay.Attach a = mine(owner, h);
                String nm = ((a == null) || (a.ent == null)) ? null : a.ent.visualName();
                return (nm == null) ? LuaValue.NIL : LuaValue.valueOf(nm);
            }
        });
        // kind() — "draw" / "text" (screen space) or "image" / "model" / "ghost" (the 3D world) for one of ours;
        // nil for a native one (the game's overlays are read-only and say only that they are the game's), and nil
        // for one of ours that has not yet said what it draws.
        m.set("kind", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaGobOverlay.Attach a = mine(owner, handle(self, "kind"));
                String k = (a == null) ? null : a.kind();
                return (k == null) ? LuaValue.NIL : LuaValue.valueOf(k);
            }
        });

        // ---- what it draws: the five kind setters (039.3) -----------------------------------------------
        // An overlay is attached BARE and says what it draws here — the spec table is gone, and with it its
        // one-shot parse: :text("…") on a live overlay relabels it. It says ONE thing, so a second, different
        // kind is refused naming the first, exactly as a spec naming two fields used to be.
        m.set("draw", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue fn = Args.written(a, 2, "overlay:draw", "fn");
                LuaGobOverlay.Attach rec = writable(owner, self, "draw");
                if(fn == null)
                    return (rec == null) ? LuaValue.NIL : nilOr(rec.draw);
                if(!fn.isfunction())
                    throw new LuaError("overlay:draw(fn) expects a function draw(g, gob, sx, sy), got "
                        + fn.typename());
                if(rec != null) {
                    becomes(rec, "draw", "draw");
                    rec.draw = fn;
                    rec.kind = "draw";
                }
                return self;
            }
        });
        m.set("text", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue sv = Args.written(a, 2, "overlay:text", "s");
                LuaGobOverlay.Attach rec = writable(owner, self, "text");
                if(sv == null)
                    return ((rec == null) || (rec.text == null)) ? LuaValue.NIL : LuaValue.valueOf(rec.text);
                if(!sv.isstring())
                    throw new LuaError("overlay:text(s) expects a string label, got " + sv.typename());
                if(rec != null) {
                    becomes(rec, "text", "text");
                    rec.text = sv.tojstring();
                    rec.kind = "text";
                }
                return self;
            }
        });
        m.set("image", visualSetter(owner, "image"));
        m.set("model", visualSetter(owner, "model"));
        // ghost(res) — a game .res model, named rather than handed over as an asset handle.
        m.set("ghost", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue rv = Args.written(a, 2, "overlay:ghost", "res");
                LuaGobOverlay.Attach rec = writable(owner, self, "ghost");
                if(rv == null)
                    return ((rec == null) || (rec.res == null)) ? LuaValue.NIL : LuaValue.valueOf(rec.res);
                if(!rv.isstring() || rv.isnumber())
                    throw new LuaError("overlay:ghost(res) expects a .res name, got " + rv.typename());
                if(rec != null) {
                    becomes(rec, "ghost", "ghost");
                    build(rec, "ghost", LuaValue.NIL, rv.tojstring());
                }
                return self;
            }
        });

        // ---- how it looks ------------------------------------------------------------------------------
        // color(r, g, b[, a]) — the label's colour, on a SCREEN-space overlay. Positional components, and a
        // colour VALUE passes straight back through, so ov:color(other:color()) is one call.
        m.set("color", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaGobOverlay.Attach rec = writable(owner, self, "color");
                if(!Args.passed(a, 2))
                    return (rec == null) ? LuaValue.NIL : colorValue(rec.color);
                if((rec != null) && rec.world())
                    throw new LuaError("overlay:color(): that is a WORLD-space overlay (kind '" + rec.kind()
                        + "') -- :color is the label colour of a screen-space one; the world kinds take :tint");
                java.awt.Color c = colorArg(a, 2, "overlay:color");
                if(rec != null)
                    rec.color = c;
                return self;
            }
        });
        // offset(x, y) / offset(x, y, z) — where it sits relative to the gob: screen PIXELS from the projected
        // point on a screen-space overlay, world UNITS (z = up) on a world-space one. Live on both: a world
        // overlay's anchor is updated in place rather than re-attached.
        m.set("offset", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaGobOverlay.Attach rec = writable(owner, self, "offset");
                if(!Args.passed(a, 2)) {
                    if(rec == null)
                        return LuaValue.NIL;
                    LuaTable t = new LuaTable();
                    t.set("x", LuaValue.valueOf(rec.offX));
                    t.set("y", LuaValue.valueOf(rec.offY));
                    if(!"draw".equals(rec.kind()) && !"text".equals(rec.kind()))
                        t.set("z", LuaValue.valueOf(rec.offZ));
                    return t;
                }
                double x = numberArg(a, 2, "overlay:offset", "x");
                double y = numberArg(a, 3, "overlay:offset", "y");
                double z = Args.passed(a, 4) ? numberArg(a, 4, "overlay:offset", "z") : 0.0;
                if(rec != null) {
                    rec.offX = x; rec.offY = y; rec.offZ = z;
                    LuaWorldEntity e = rec.ent;
                    if(e != null)
                        RenderApi.overlayOffset(e, rec.worldOffset());
                }
                return self;
            }
        });
        // The look and facing of a WORLD-space overlay (038.2's composed verbs, now with their reads). They are
        // refused on a screen-space one naming the kinds — a screen overlay is painted by the addon, or is a
        // label with its own colour — and are remembered on the record, so a rebuilt visual comes back the same.
        // Once the overlay is gone they are clean no-ops: the thing they would act on is not there.
        m.set("scale", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue sv = Args.written(a, 2, "overlay:scale", "s");
                LuaGobOverlay.Attach rec = worldOnly(owner, self, "scale");
                if(sv == null)
                    return (rec == null) ? LuaValue.NIL : LuaValue.valueOf(rec.scale);
                if(!sv.isnumber())
                    throw new LuaError("overlay:scale(s) expects a positive number (1 = original size)");
                if(rec != null) {
                    rec.scale = RenderApi.clampScale(sv.todouble());
                    if(rec.ent != null)
                        RenderApi.overlayScale(rec.ent, rec.scale);
                }
                return self;
            }
        });
        m.set("alpha", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue av = Args.written(a, 2, "overlay:alpha", "a");
                LuaGobOverlay.Attach rec = worldOnly(owner, self, "alpha");
                if(av == null)
                    return (rec == null) ? LuaValue.NIL : LuaValue.valueOf(rec.alpha);
                if(!av.isnumber())
                    throw new LuaError("overlay:alpha(a) expects a number 0..1 (1 = opaque)");
                if(rec != null) {
                    rec.alpha = RenderApi.clampAlpha(av.todouble());
                    if(rec.ent != null)
                        RenderApi.overlayAlpha(rec.ent, rec.alpha);
                }
                return self;
            }
        });
        // tint(nil) STAYS legal: "no tint" is a real value, not an accident — one of the two nils the whole API
        // documents a meaning for.
        m.set("tint", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaGobOverlay.Attach rec = worldOnly(owner, self, "tint");
                if(!Args.passed(a, 2))
                    return (rec == null) ? LuaValue.NIL : colorValue(rec.tint);
                java.awt.Color c = a.arg(2).isnil() ? null : colorArg(a, 2, "overlay:tint");
                if(rec != null) {
                    rec.tint = c;
                    if(rec.ent != null)
                        RenderApi.overlayTint(rec.ent, c);
                }
                return self;
            }
        });
        m.set("rotate", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue av = Args.written(a, 2, "overlay:rotate", "a");
                LuaGobOverlay.Attach rec = worldOnly(owner, self, "rotate");
                if(av == null)
                    return (rec == null) ? LuaValue.NIL : LuaValue.valueOf(rec.rotate);
                if(!av.isnumber())
                    throw new LuaError("overlay:rotate(a) expects a facing angle in radians");
                if(rec != null) {
                    rec.rotate = av.todouble();
                    if(rec.ent != null)
                        RenderApi.overlayRotate(rec.ent, rec.rotate);
                }
                return self;
            }
        });
        // billboard(b) and spawnData(sdt) are the two CONSTRUCTION properties: they pick which visual is milled,
        // so setting one after the overlay already stands rebuilds it. Set them in the same chain as the kind and
        // it is built once — which is what the chained form is for.
        m.set("billboard", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue bv = Args.written(a, 2, "overlay:billboard", "b");
                LuaGobOverlay.Attach rec = worldOnly(owner, self, "billboard");
                if(bv == null)
                    return (rec == null) ? LuaValue.NIL : LuaValue.valueOf(rec.billboard);
                if(!bv.isboolean())
                    throw new LuaError("overlay:billboard(b) expects true or false -- true is a camera-facing"
                        + " blit, false the upright quad");
                if(rec != null) {
                    boolean had = rec.billboard;
                    rec.billboard = bv.toboolean();
                    try {
                        rec.materialise();
                    } catch(RuntimeException e) {
                        rec.billboard = had;
                        throw e;
                    }
                }
                return self;
            }
        });
        m.set("spawnData", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue sv = Args.written(a, 2, "overlay:spawnData", "spawnData");
                LuaGobOverlay.Attach rec = worldOnly(owner, self, "spawnData");
                if(sv == null)
                    return ((rec == null) || (rec.spawnData == null)) ? LuaValue.NIL : rec.spawnData;
                if(rec != null) {
                    LuaValue had = rec.spawnData;
                    rec.spawnData = sv;
                    try {
                        rec.materialise();
                    } catch(RuntimeException e) {
                        rec.spawnData = had;
                        throw e;
                    }
                }
                return self;
            }
        });
        // position() — where the thing actually IS, as a Position: the gob's live interpolated point plus this
        // overlay's offset. World-space only, and nil once it is gone. Its facing and size are :rotate()/:scale().
        m.set("position", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaGobOverlay.Attach rec = worldOnly(owner, self, "position");
                LuaWorldEntity e = (rec == null) ? null : rec.ent;
                return (e == null) ? LuaValue.NIL : RenderApi.overlayPosition(owner, e);
            }
        });
        // count() — how many engine overlays this one entity stands for: 1 for ours, and for a native one the
        // number the game has of that resource on that gob. A NATIVE OVERLAY IS A UNION, because the key is
        // the resource name and a gob may carry several of one resource (038.1 measured 13 of 33 such gobs) —
        // so the multiplicity keying by name would lose is published here instead. nil once it is gone.
        m.set("count", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOverlay h = handle(self, "count");
                Gob g = AddonManager.getgob(h.gob);
                if(g == null)
                    return LuaValue.NIL;
                if(h.nat) {
                    int n = LuaGobOverlay.countNative(g, h.key);
                    return (n == 0) ? LuaValue.NIL : LuaValue.valueOf(n);
                }
                LuaGobOverlay store = LuaGobOverlay.on(g);
                return ((store == null) || (store.get(owner, h.key) == null)) ? LuaValue.NIL : LuaValue.valueOf(1);
            }
        });
        // exists() — is it still attached? False once removed, and false once the gob itself is gone.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(rec(owner, handle(self, "exists")) != null);
            }
        });
        // info() — the snapshot escape hatch, for logging: {key, native, res?, kind?}.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOverlay h = handle(self, "info");
                Object r = rec(owner, h);
                if(r == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("key", LuaValue.valueOf(h.key));
                t.set("native", LuaValue.valueOf(h.nat));
                if(h.nat) {
                    t.set("res", LuaValue.valueOf(h.key));
                    t.set("count", LuaValue.valueOf(LuaGobOverlay.countNative(AddonManager.getgob(h.gob), h.key)));
                } else {
                    LuaGobOverlay.Attach a = (LuaGobOverlay.Attach)r;
                    t.set("count", LuaValue.valueOf(1));
                    if(a.kind() != null) {                         // absent while the overlay is still bare
                        t.set("kind", LuaValue.valueOf(a.kind()));
                        t.set("world", LuaValue.valueOf(a.world()));   // the 3D world, or the gob's screen point
                    }
                    if((a.ent != null) && (a.ent.visualName() != null))
                        t.set("res", LuaValue.valueOf(a.ent.visualName()));
                }
                return t;
            }
        });
        return m;
    }

    /**
     * What backs a handle right now, or {@code null} — the record for one of ours, a marker object for a native
     * one, and {@code null} for either once the overlay (or the gob) is gone. One resolution behind
     * {@code :exists()} and {@code :info()}, so the two can never disagree.
     */
    private static Object rec(Addon owner, LuaOverlay h) {
        Gob g = AddonManager.getgob(h.gob);
        if(g == null)
            return null;
        if(h.nat)
            return LuaGobOverlay.findNative(g, h.key) ? h : null;
        LuaGobOverlay store = LuaGobOverlay.on(g);
        return (store == null) ? null : store.get(owner, h.key);
    }

    /** This addon's record behind a handle, or {@code null} (a native one, a removed one, or a departed gob). */
    private static LuaGobOverlay.Attach mine(Addon owner, LuaOverlay h) {
        Object r = h.nat ? null : rec(owner, h);
        return (r instanceof LuaGobOverlay.Attach) ? (LuaGobOverlay.Attach)r : null;
    }

    /**
     * The record a verb on one of OUR overlays acts on — {@code null} once the overlay (or its gob) is gone,
     * which makes every setter a clean no-op there: the thing it would act on is not there, and that is a
     * moment, not a mistake. The game's own overlays are read-only, so they are refused naming the key: a verb
     * that can never mean anything here is a refusal, not a silent one.
     */
    private static LuaGobOverlay.Attach writable(Addon owner, LuaValue self, String method) {
        LuaOverlay h = handle(self, method);
        if(h.nat)
            throw new LuaError("overlay:" + method + "(): the game's own overlays are READ-ONLY -- '" + h.key
                + "' is one of them (gob:overlay():list() says which, ov:native())");
        return mine(owner, h);
    }

    /**
     * As {@link #writable}, for a verb that belongs to the <b>world-space</b> kinds only. A record that has not
     * yet said what it draws passes — it may still become one of them — and a screen-space one is refused naming
     * the kinds, because a label carries its own colour and a {@code draw} callback paints itself.
     */
    private static LuaGobOverlay.Attach worldOnly(Addon owner, LuaValue self, String method) {
        LuaGobOverlay.Attach rec = writable(owner, self, method);
        if((rec != null) && (rec.kind() != null) && !rec.world())
            throw new LuaError("overlay:" + method + "(): that is a SCREEN-space overlay (kind '" + rec.kind()
                + "') -- :scale/:alpha/:tint/:rotate/:billboard/:spawnData/:position belong to the world-space"
                + " kinds ('image', 'model', 'ghost'); a 'draw' overlay paints itself and a 'text' one carries"
                + " its own colour");
        return rec;
    }

    /**
     * Refuse a <b>second, different</b> kind. An overlay says ONE thing — the same rule a spec naming two fields
     * used to break, one setter at a time — and the way to make it say another is to replace it, which is what
     * {@code :add(key)} on the same key already means.
     */
    private static void becomes(LuaGobOverlay.Attach rec, String kind, String method) {
        String had = rec.kind();
        if((had != null) && !had.equals(kind))
            throw new LuaError("overlay:" + method + "(): this overlay already draws '" + had + "', and an"
                + " overlay says ONE thing -- gob:overlay():add(\"" + rec.key + "\") again to replace it");
    }

    /**
     * Point a world-space record at a visual and (re)build it. The record's own fields are restored if the build
     * raises, so a bad asset handle or a missing map view leaves the overlay exactly as it was — 038's property
     * that a failed attach changes nothing, kept now that the configuration arrives one setter at a time.
     */
    private static void build(LuaGobOverlay.Attach rec, String kind, LuaValue visual, String res) {
        String hadKind = rec.kind, hadRes = rec.res;
        LuaValue hadVisual = rec.visual;
        rec.kind = kind;
        rec.visual = visual;
        rec.res = res;
        try {
            rec.materialise();
        } catch(RuntimeException e) {
            rec.kind = hadKind;
            rec.visual = hadVisual;
            rec.res = hadRes;
            throw e;
        }
    }

    /** {@code :image(asset)} / {@code :model(asset)} — one shape, two kinds: the visual is an asset handle. */
    private static LuaValue visualSetter(final Addon owner, final String kind) {
        return new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue av = Args.written(a, 2, "overlay:" + kind, "asset");
                LuaGobOverlay.Attach rec = writable(owner, self, kind);
                if(av == null)
                    return ((rec == null) || !kind.equals(rec.kind()) || (rec.visual == null))
                        ? LuaValue.NIL : rec.visual;
                if(rec != null) {
                    becomes(rec, kind, kind);
                    build(rec, kind, av, null);
                }
                return self;
            }
        };
    }

    /** A colour argument: positional components, or a colour value handed straight back from another read. */
    private static java.awt.Color colorArg(Varargs a, int i, String verb) {
        LuaValue v = a.arg(i);
        if(v.istable()) {
            java.awt.Color c = AddonManager.luaColor(v, null);
            if(c == null)
                throw new LuaError(verb + "(color): a colour value is {r, g, b[, a]} (0..255)");
            return c;
        }
        LuaTable t = new LuaTable();
        int n = 0;
        for(int j = i; (j <= a.narg()) && a.arg(j).isnumber(); j++)
            t.set(++n, a.arg(j));
        java.awt.Color c = AddonManager.luaColor(t, null);
        if(c == null)
            throw new LuaError(verb + "(r, g, b[, a]) expects three or four numbers 0..255, or a colour value"
                + " read back from the API");
        return c;
    }

    /** A colour read: the {@code {r, g, b, a}} value every colour read in this API hands back. */
    private static LuaValue colorValue(java.awt.Color c) {
        if(c == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set(1, LuaValue.valueOf(c.getRed()));
        t.set(2, LuaValue.valueOf(c.getGreen()));
        t.set(3, LuaValue.valueOf(c.getBlue()));
        t.set(4, LuaValue.valueOf(c.getAlpha()));
        return t;
    }

    /** A required number argument, refused by name rather than coerced to zero. */
    private static double numberArg(Varargs a, int i, String verb, String param) {
        LuaValue v = Args.required(a, i, verb, param);
        if(!v.isnumber())
            throw new LuaError(verb + ": " + param + " must be a number, got " + v.typename());
        return v.todouble();
    }

    /** A stored Lua value read back, or {@code nil} when nothing was stored. */
    private static LuaValue nilOr(LuaValue v) {
        return (v == null) ? LuaValue.NIL : v;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaOverlay handle(LuaValue self, String method) {
        LuaOverlay h = resolve(self);
        if(h == null)
            throw new LuaError("overlay:" + method + "() -- use a COLON call on an Overlay object"
                + " (gob:overlay(key) / gob:overlay())");
        return h;
    }
}
