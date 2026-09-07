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
 * <p><b>What it means, since 043.3: WHAT IS DRAWN AT THIS GOB.</b> An addon's own overlays are screen-space
 * painters at the gob's projected point — {@code :draw(fn)} and {@code :text(s)}, and nothing else. The three
 * kinds it does not draw are the ones standing in the world: such a thing is not an engine overlay but a client
 * gob of its own, so it belongs to {@code hafen.virtual()}, where it is created, listed and ended.
 *
 * <p><b>Three origins, one read.</b> {@code gob:overlay():list()} still answers everything drawn at the gob, and
 * each member says what it is. The addon's own records are writable. The game's own ({@code ov:native()}) are
 * <b>read-only</b>. And a {@code hafen.virtual()} entity this addon anchored to the gob is surfaced here <b>read-only
 * too</b> ({@code ov:native()} is {@code false}, {@code ov:kind()} names its collection) — so "what is at this
 * gob?" keeps one complete answer, while the thing itself is still addressed through the collection that owns it.
 * Every write through such an entry raises, naming that collection; so does an {@code :add} onto a native or a
 * {@code hafen.virtual()} key, and a {@code :remove} of either.
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
    /** This addon's own record — the only writable kind. */
    static final int MINE = 0;
    /** One of the game's own overlays ({@code Gob.ols}), read-only and keyed by its resource name. */
    static final int NATIVE = 1;
    /** A {@code hafen.virtual()} entity this addon anchored to the gob: read-only here, addressed through its collection. */
    static final int VIRTUAL = 2;

    /** The gob this overlay is attached to. */
    public final long gob;
    /** The key: the addon's own for its records, the resource name for the game's, {@code virtual#<n>} for an entity. */
    public final String key;
    /** Where the thing behind this handle lives: {@link #MINE}, {@link #NATIVE} or {@link #VIRTUAL}. */
    public final int src;

    private LuaOverlay(long gob, String key, int src) {
        this.gob = gob;
        this.key = key;
        this.src = src;
    }

    /** Is this one of the game's own overlays? {@code ov:native()} — and a {@code hafen.virtual()} entity is NOT. */
    public boolean nat() {
        return src == NATIVE;
    }

    /** {@code tostring(ov)}: {@code Overlay(<key>@<gobId>)}, {@code *} on the game's own, {@code ~} on a virtual entity. */
    public String toString() {
        String mark = (src == NATIVE) ? "*" : ((src == VIRTUAL) ? "~" : "");
        return "Overlay(" + mark + key + "@" + Long.toString(gob) + ")";
    }

    /** An interned Overlay object in {@code owner}'s env — the one way one reaches Lua. */
    static LuaValue of(Addon owner, long gob, String key, int src) {
        return owner.gobOverlayObjs.of(gob, key, src);
    }

    /** The {@code LuaOverlay} behind a Lua value, or {@code null} for anything that is not an Overlay object. */
    static LuaOverlay resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaOverlay) ? (LuaOverlay)o : null;
    }

    /**
     * Everything drawn at the gob, in three groups: this addon's own records first (in attach order), then the
     * {@code hafen.virtual()} entities it anchored there (in placement order), then the game's own. Another addon's
     * are in none of them — keys are per addon, so two addons' {@code "tag"} on one gob neither collide nor see
     * each other, and the same is true of what each has standing there — and a departed gob simply has nothing.
     */
    private static List<LuaValue> members(Addon owner, long gobId) {
        List<LuaValue> out = new ArrayList<LuaValue>();
        Gob g = AddonManager.anygob(gobId);
        if(g == null)
            return out;
        LuaGobOverlay store = LuaGobOverlay.on(g);
        if(store != null) {
            for(String k : store.keys(owner))
                out.add(of(owner, gobId, k, MINE));
        }
        for(LuaWorldEntity e : VirtualApi.anchoredMembers(owner, g))
            out.add(of(owner, gobId, e.overlayKey(), VIRTUAL));
        for(String k : LuaGobOverlay.nativeKeys(g))
            out.add(of(owner, gobId, k, NATIVE));
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

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                String k = keyArg(key, "gob:overlay():get");
                Gob g = AddonManager.anygob(gobId);
                if(g == null)
                    return LuaValue.NIL;
                LuaGobOverlay store = LuaGobOverlay.on(g);
                if((store != null) && (store.get(owner, k) != null))
                    return of(owner, gobId, k, MINE);
                if(VirtualApi.anchoredAt(owner, g, k) != null)
                    return of(owner, gobId, k, VIRTUAL);
                return LuaGobOverlay.findNative(g, k) ? of(owner, gobId, k, NATIVE) : LuaValue.NIL;
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
                Gob g = AddonManager.anygob(gobId);
                if(g == null)
                    throw new LuaError("gob:overlay():add(\"" + k + "\"): that gob is gone, so there is nothing"
                        + " to attach it to -- gob:exists() is the test, and an overlay dies with its gob");
                if(LuaGobOverlay.findNative(g, k))
                    throw new LuaError("gob:overlay():add(\"" + k + "\"): that key names one of the GAME's own"
                        + " overlays, which are read-only -- pick a key of your own (a native key is a resource"
                        + " name; gob:overlay():list() lists them)");
                LuaWorldEntity ent = VirtualApi.anchoredAt(owner, g, k);
                if(ent != null)
                    throw new LuaError("gob:overlay():add(\"" + k + "\"): that key names a hafen.virtual():" + ent.kind()
                        + "() standing at this gob, which is listed here read-only -- pick a key of your own"
                        + " (a virtual key is generated, so it always looks like \"virtual#7\")");
                // 080.1: onto EVERY live session's copy of the object, so what is attached to the object is
                // drawn whichever character is looking at it. One record, shared by the copies — the setters
                // that say what it draws act on the one thing, and the event is fired once, off this copy.
                LuaGobOverlay.Attach rec = new LuaGobOverlay.Attach(owner, k);
                LuaGobOverlay.Attach old = LuaGobOverlay.attach(g, gobId, rec);
                // 092.7: ...and onto the copy of a character that loads the object afterwards. The record is
                // the one every copy shares, so a later ov:text(...) relabels what that session is handed too.
                GobIntent.overlay(gobId, rec);
                if(old != null)
                    AddonManager.queueGobOverlay(false, g, k, owner);
                AddonManager.queueGobOverlay(true, g, k, owner);
                return of(owner, gobId, k, MINE);
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
                    if(h.nat())
                        throw new LuaError("gob:overlay():remove(\"" + h.key + "\"): that is one of the GAME's"
                            + " own overlays, which are read-only");
                    k = h.key;
                } else {
                    k = keyArg(x, "gob:overlay():remove");
                }
                Gob g = AddonManager.anygob(gobId);
                if(g == null)
                    return;                          // it died with its gob: the removal already happened
                if(LuaGobOverlay.findNative(g, k))
                    throw new LuaError("gob:overlay():remove(\"" + k + "\"): that key names one of the GAME's own"
                        + " overlays, which are read-only");
                LuaWorldEntity ent = VirtualApi.anchoredAt(owner, g, k);
                if(ent != null)
                    throw new LuaError("gob:overlay():remove(\"" + k + "\"): that is a hafen.virtual():" + ent.kind()
                        + "() standing at this gob, listed here read-only -- the collection placed it, so the"
                        + " collection ends it: hafen.virtual():" + ent.kind() + "():remove(x), with the handle :add"
                        + " gave you (or one out of hafen.virtual():" + ent.kind() + "():list())");
                // ...and off every copy, the twin of the walk :add takes — one removal, reported once. AFTER
                // the two refusals above, so a key this addon never attached takes neither half (092.7).
                GobIntent.dropOverlay(gobId, owner, k);   // ...and a session loading it later is not handed it
                if(LuaGobOverlay.detach(g, gobId, owner, k) != null)
                    AddonManager.queueGobOverlay(false, g, k, owner);
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

        /** The interned handle for (gob, key, origin) — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(long gob, String key, int src) {
            drain();
            String ck = Long.toString(gob) + "\0" + src + "\0" + key;
            Ref r = live.get(ck);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(ck);
            }
            LuaValue v = LuaValue.userdataOf(new LuaOverlay(gob, key, src), meta());
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
        mt.set(LuaValue.INDEX, Refusal.closedIndex("overlay", methods(owner),
            "one overlay on a gob"));
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
        // native() — is this one of the GAME's overlays (read-only) rather than one of ours? A hafen.virtual() entity
        // this addon stood at the gob answers FALSE: it is yours, it is simply not addressed from here.
        m.set("native", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "native").nat());
            }
        });
        // res() — WHAT it is drawn from: the key itself for a native one (a native key IS its resource name), the
        // .res name / asset path for a hafen.virtual() entity standing here, and nil for one of our own records (it
        // draws Lua, not a resource — which is exactly what tells a painter from a thing, from the outside).
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOverlay h = handle(self, "res");
                if(h.src == NATIVE)
                    return LuaValue.valueOf(h.key);
                LuaWorldEntity e = virtual(owner, h);
                String nm = (e == null) ? null : e.visualName();
                return (nm == null) ? LuaValue.NIL : LuaValue.valueOf(nm);
            }
        });
        // kind() — "draw" / "text" for one of our own records; "ghost" / "sprite" / "object" for a hafen.virtual()
        // entity standing here, which is also the collection that owns it; nil for a native one (the game's
        // overlays are read-only and say only that they are the game's), and nil for one of ours that has not yet
        // said what it draws.
        m.set("kind", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOverlay h = handle(self, "kind");
                if(h.src == VIRTUAL) {
                    LuaWorldEntity e = virtual(owner, h);
                    return (e == null) ? LuaValue.NIL : LuaValue.valueOf(e.kind());
                }
                LuaGobOverlay.Attach a = mine(owner, h);
                String k = (a == null) ? null : a.kind();
                return (k == null) ? LuaValue.NIL : LuaValue.valueOf(k);
            }
        });

        // ---- what it draws: the two kind setters (039.3, cut to two by 043.3) ---------------------------
        // An overlay is attached BARE and says what it draws here — there is no spec table and so no one-shot
        // parse: :text("…") on a live overlay relabels it. It says ONE thing, so a second, different kind is
        // refused naming the first.
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

        // ---- how it looks, and where it sits -------------------------------------------------------------
        // color(c) — the label's colour, as the TABLE a colour is: {200, 210, 220} or {r=,g=,b=[,a=]}. The read
        // hands back the keyed shape every colour reader in this API does, so ov:color(other:color()) is one call.
        m.set("color", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaGobOverlay.Attach rec = writable(owner, self, "color");
                if(!Args.passed(a, 2))
                    return (rec == null) ? LuaValue.NIL : AddonManager.color(rec.color);
                java.awt.Color c = colorArg(a, 2, "overlay:color");
                if(rec != null)
                    rec.color = c;
                return self;
            }
        });
        // font(h) — the face the label is blitted in, and the one dressing that IS part of the text cache's
        // key: a label and a g:text of the same string in the same font are one entry. A face carrying an
        // outline therefore puts the edge in that one raster, which is how a label at a gob wears one for
        // the price of one blit. A value that is not a handle is refused rather than resolving to the stock
        // font, which is a label silently in the wrong face.
        m.set("font", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaGobOverlay.Attach rec = writable(owner, self, "font");
                LuaValue hv = Args.written(a, 2, "overlay:font", "h");
                if(hv == null) {
                    LuaValue cur = (rec == null) ? null : rec.fontVal;
                    return (cur == null) ? LuaValue.NIL : cur;
                }
                FontHandle fh = FontHandle.resolve(hv);
                if(fh == null)
                    throw new LuaError("overlay:font(h) expects a font handle — hafen.font():get(\"serif\") or"
                        + " hafen.asset():get(\"fonts/mine.ttf\") — got " + hv.typename());
                if(rec != null) {
                    rec.font = fh;
                    rec.fontVal = hv;
                }
                return self;
            }
        });
        // offset(x, y) — where it sits relative to the gob's projected point, in screen PIXELS. ONE meaning
        // since 043.3: the third number used to switch the whole verb into world units for the world kinds, and
        // those left, so a third argument is refused naming the verb that moves a thing in the world.
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
                    return t;
                }
                if(Args.passed(a, 4))
                    throw new LuaError("overlay:offset(x, y): an overlay is painted at the gob's projected point,"
                        + " so its offset is SCREEN PIXELS and takes two numbers. The vertical world form is a"
                        + " property of its own -- overlay:height(z), in world units up the gob, 15 by default"
                        + " and 0 the ground under it. A horizontal one moves a thing standing IN the world:"
                        + " hafen.virtual():sprite():add(asset, gob) (or :object() / :ghost()), whose own"
                        + " :offset(x, y, z) is in world units");
                double x = numberArg(a, 2, "overlay:offset", "x");
                double y = numberArg(a, 3, "overlay:offset", "y");
                if(rec != null) {
                    rec.offX = x; rec.offY = y;
                }
                return self;
            }
        });
        // height(z) — WHERE UP THE GOB the projected point is taken, in WORLD units: 15 by default (just above
        // the head, the client's own label height), 0 the ground the object stands on. It is the other half of
        // where a record lands, and the only half in world units — the projection is done at this height, and
        // :offset then moves the result by pixels. Per record, so one gob carries a label at its feet and one
        // over its head at once, each projected at its own.
        m.set("height", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaGobOverlay.Attach rec = writable(owner, self, "height");
                if(!Args.passed(a, 2))
                    return (rec == null) ? LuaValue.NIL : LuaValue.valueOf(rec.height);
                double z = numberArg(a, 2, "overlay:height", "z");
                if(rec != null)
                    rec.height = z;
                return self;
            }
        });
        // count() — how many engine overlays this one entity stands for: 1 for ours and for a hafen.virtual() entity
        // standing here, and for a native one the number the game has of that resource on that gob. A NATIVE
        // OVERLAY IS A UNION, because the key is the resource name and a gob may carry several of one resource
        // (038.1 measured 13 of 33 such gobs) — so the multiplicity keying by name would lose is published here
        // instead. nil once it is gone.
        m.set("count", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOverlay h = handle(self, "count");
                Gob g = AddonManager.anygob(h.gob);
                if(g == null)
                    return LuaValue.NIL;
                if(h.src == NATIVE) {
                    int n = LuaGobOverlay.countNative(g, h.key);
                    return (n == 0) ? LuaValue.NIL : LuaValue.valueOf(n);
                }
                return (rec(owner, h) == null) ? LuaValue.NIL : LuaValue.valueOf(1);
            }
        });
        // exists() — is it still attached? False once removed, and false once the gob itself is gone.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(rec(owner, handle(self, "exists")) != null);
            }
        });
        // info() — the snapshot escape hatch, for logging: {key, native, count, height?, res?, kind?, world?}.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOverlay h = handle(self, "info");
                Object r = rec(owner, h);
                if(r == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("key", LuaValue.valueOf(h.key));
                t.set("native", LuaValue.valueOf(h.nat()));
                if(h.src == NATIVE) {
                    t.set("res", LuaValue.valueOf(h.key));
                    t.set("count", LuaValue.valueOf(LuaGobOverlay.countNative(AddonManager.anygob(h.gob), h.key)));
                } else if(h.src == VIRTUAL) {
                    LuaWorldEntity e = (LuaWorldEntity)r;
                    t.set("count", LuaValue.valueOf(1));
                    t.set("kind", LuaValue.valueOf(e.kind()));
                    t.set("world", LuaValue.TRUE);                 // it stands in the 3D world, not at a screen point
                    if(e.visualName() != null)
                        t.set("res", LuaValue.valueOf(e.visualName()));
                } else {
                    LuaGobOverlay.Attach a = (LuaGobOverlay.Attach)r;
                    t.set("count", LuaValue.valueOf(1));
                    t.set("height", LuaValue.valueOf(a.height));   // where up the gob it is projected from
                    if(a.kind() != null) {                         // absent while the overlay is still bare
                        t.set("kind", LuaValue.valueOf(a.kind()));
                        t.set("world", LuaValue.FALSE);            // painted at the gob's projected screen point
                    }
                }
                return t;
            }
        });
        return m;
    }

    /**
     * What backs a handle right now, or {@code null} — the record for one of ours, the entity for a
     * {@code hafen.virtual()} one, a marker object for a native one, and {@code null} for any of them once the thing
     * (or the gob) is gone. One resolution behind {@code :exists()} and {@code :info()}, so the two can never
     * disagree.
     */
    private static Object rec(Addon owner, LuaOverlay h) {
        Gob g = AddonManager.anygob(h.gob);
        if(g == null)
            return null;
        if(h.src == NATIVE)
            return LuaGobOverlay.findNative(g, h.key) ? h : null;
        if(h.src == VIRTUAL)
            return VirtualApi.anchoredAt(owner, g, h.key);
        LuaGobOverlay store = LuaGobOverlay.on(g);
        return (store == null) ? null : store.get(owner, h.key);
    }

    /** This addon's record behind a handle, or {@code null} (a native one, a virtual one, a removed one, a departed gob). */
    private static LuaGobOverlay.Attach mine(Addon owner, LuaOverlay h) {
        Object r = (h.src == MINE) ? rec(owner, h) : null;
        return (r instanceof LuaGobOverlay.Attach) ? (LuaGobOverlay.Attach)r : null;
    }

    /** The {@code hafen.virtual()} entity behind a handle, or {@code null} (any other origin, or one already gone). */
    private static LuaWorldEntity virtual(Addon owner, LuaOverlay h) {
        Object r = (h.src == VIRTUAL) ? rec(owner, h) : null;
        return (r instanceof LuaWorldEntity) ? (LuaWorldEntity)r : null;
    }

    /**
     * The record a verb on one of OUR overlays acts on — {@code null} once the overlay (or its gob) is gone,
     * which makes every setter a clean no-op there: the thing it would act on is not there, and that is a
     * moment, not a mistake.
     *
     * <p>The other two origins are <b>read-only here and are refused</b>, each naming where the thing does answer
     * to a write: the game's own overlays answer to nothing, and a {@code hafen.virtual()} entity answers to the
     * collection that placed it. A verb that can never mean anything on this handle is a refusal, not a silent
     * one — that is the whole reason the entry is listed at all.
     */
    private static LuaGobOverlay.Attach writable(Addon owner, LuaValue self, String method) {
        LuaOverlay h = handle(self, method);
        if(h.src == NATIVE)
            throw new LuaError("overlay:" + method + "(): the game's own overlays are READ-ONLY -- '" + h.key
                + "' is one of them (gob:overlay():list() says which, ov:native())");
        if(h.src == VIRTUAL) {
            LuaWorldEntity e = virtual(owner, h);
            String kind = (e == null) ? "sprite" : e.kind();
            throw new LuaError("overlay:" + method + "(): that entry is a hafen.virtual():" + kind + "() you stood at"
                + " this gob, listed here READ-ONLY so \"what is at this gob?\" has one complete answer. You"
                + " address it through the collection that owns it: the handle hafen.virtual():" + kind + "():add(what,"
                + " gob) gave you, or one out of hafen.virtual():" + kind + "():list()");
        }
        return mine(owner, h);
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

    /** A colour argument — the API's one spelling, shared with every other colour property. */
    private static java.awt.Color colorArg(Varargs a, int i, String verb) {
        return AddonManager.colorArg(a, i, verb);
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
