package io.brodgar.addon;

import haven.GobIcon;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An <b>IconCat object</b> — one <b>minimap icon category</b> ({@link GobIcon.Settings}, {@code GameUI.iconconf}):
 * a kind of gob icon (a boar, a fir tree, a player) with two flags, {@code show} (draw it on the minimap) and
 * {@code notify} (a sound + a chat line when one appears). It is the same registry the in-client "Icon settings"
 * window edits, so a write here is the write its checkboxes make. Spec {@code 037-map-database}, task 037.1.
 *
 * <p><b>It replaces {@code hafen.radar}, name and shape both.</b> The engine has no "radar" — it has
 * {@link GobIcon.Settings} (D-061) — and the icons are part of the <i>map</i>, so this is
 * {@code hafen.map():icon()}, a relation of the map rather than a namespace beside it (D-066). The two
 * filter-mutators ({@code setVisible}/{@code setNotify}) are gone: <b>arity is the verb on the entity</b> —
 * {@code cat:show()} reads, {@code cat:show(v)} writes and returns self, so writes chain.
 *
 * <p><b>Two questions, two verbs.</b> {@code hafen.map():icon():get(res)} addresses one category by its
 * resource name and {@code :list(filter)}/{@code :find(filter)} search by the name a player sees. That pair
 * replaces a callable that split its one argument <i>by shape</i> — a string with a {@code /} meant a
 * resource — which was correct only for as long as no display name ever contained a slash.
 *
 * <p><b>A category IS a resource.</b> The engine's key is {@code Setting.ID} = (resource name, sub-id), and a
 * resource whose own code publishes icon <i>variants</i> therefore has more than one {@code Setting} — but the
 * sub-id is an opaque {@code Object[]} that no string can address, and to a player those variants are one thing
 * on the minimap. So the resource name is the identity: a read answers true when <b>any</b> of that resource's
 * settings carries the flag, a write sets <b>all</b> of them, which is exactly what {@code hafen.radar}'s
 * filter-mutators already did and leaves the round trip exact.
 *
 * <p><b>Wraps only the resource name.</b> Every method re-resolves through one funnel —
 * {@link MapApi#iconconf()}{@code .settings} — so a stashed handle tracks the live registry and goes
 * {@code :exists() == false} if it is ever dropped. This is not optional bookkeeping: the loader thread
 * <b>swaps the settings map wholesale</b> and mints fresh {@code Setting} objects as new icons resolve, so a
 * handle interned on Java identity would silently write to an orphan after the first swap (D-063 — the key is
 * what the engine publishes).
 *
 * <p><b>The writes are unprotected</b>, as {@code hafen.radar}'s were: they change a client-local display setting,
 * nothing the server sees. They persist per character through {@code Settings.dsave()}, debounced, exactly as
 * the settings window's checkboxes do — so a broad sweep rewrites configuration the user set by hand.
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045): the handle crosses as
 * {@code LuaValue.userdataOf(luaIconCat, mt)} so Lua cannot scribble on it, and the {@link Cache} on the owning
 * {@link Addon} (weak values + a {@link ReferenceQueue} drained on every access) makes
 * {@code hafen.map():icon():get(res)} hand back one object every time and {@code seen[cat] = true} reliable.
 *
 * <p><b>Threading.</b> Every read/write runs on the UI thread (addon tick / REPL / timer / console command),
 * matching the settings window. {@code Settings.settings} is replaced wholesale by the loader thread, so a
 * local reference to it is a stable snapshot to iterate; the individual booleans are written on the UI thread
 * with no torn read. The {@link Cache} map is guarded on its own monitor (UI + REPL threads touch it).
 */
public final class LuaIconCat {
    /** The icon resource name — the identity of the category. */
    public final String res;
    /**
     * <b>The login this category was read in</b> (audit2 B05). The registry is one character's — its
     * {@code dsave()} writes that character's own settings — so a handle read while one character was on
     * screen goes on editing that character's icons after the player tabs away, instead of quietly moving to
     * whoever is drawn when the write happens.
     */
    public final String user;

    private LuaIconCat(String user, String res) {
        this.user = user;
        this.res = res;
    }

    /** {@code tostring(cat)} (also the {@code __tostring} answer): {@code IconCat(<res>)}. */
    public String toString() {
        return "IconCat(" + res + ")";
    }

    /** An interned IconCat object for {@code res} in {@code user}'s registry — the one way one reaches Lua. */
    static LuaValue of(Addon owner, String user, String res) {
        return owner.iconCats.of(user, res);
    }

    /** The {@code LuaIconCat} behind a Lua value, or {@code null} for anything that is not an IconCat object. */
    static LuaIconCat resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaIconCat) ? (LuaIconCat)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /**
     * One addon's IconCat interning cache and metatable (its {@link Addon#iconCats}). Weak values + a
     * {@link ReferenceQueue} drained on every access; the metatable is built once, lazily. Nothing to tear
     * down — a handle holds only the resource name.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Ref> live = new HashMap<String, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code res} in {@code user} — a hit, or a freshly minted (inserted) one. */
        synchronized LuaValue of(String user, String res) {
            drain();
            String key = user + "\0" + res;
            Ref r = live.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaIconCat(user, res), meta());
            live.put(key, new Ref(v, key, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref cr = (Ref)r;
                if(live.get(cr.key) == cr)      // not already replaced by a fresh handle for the same res
                    live.remove(cr.key);
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

    // ---- the IconCat metatable ---------------------------------------------------------------------

    /**
     * The per-addon metatable: {@code __index} = the methods table through {@link Refusal#closedIndex} (so
     * an unknown verb throws naming what this type does answer), plus {@code __tostring}/{@code __name}.
     */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("cat", methods(owner),
            "an icon category is one minimap icon type"));
        mt.set("__name", LuaValue.valueOf("IconCat"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaIconCat h = resolve(self);
                return LuaValue.valueOf((h == null) ? "IconCat(?)" : h.toString());
            }
        });
        return mt;
    }

    /**
     * The method set. {@code :res()} answers from the handle alone (so a stashed handle still names itself);
     * everything else re-resolves in the live registry and answers {@code nil} when the resource is not in it.
     * {@code :show}/{@code :notify} are <b>arity as the verb</b> — no argument reads, an argument writes and
     * returns <b>self</b> so they chain.
     */
    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // res() — the icon resource name, and this category's IDENTITY (the intern key).
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "res").res);
            }
        });
        // exists() — is this resource still in the registry? The registry grows as the character sees new
        // icon types, so a handle for a resource not yet seen answers false and starts answering later.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaIconCat h = handle(self, "exists");
                return LuaValue.valueOf(!settingsFor(h.user, h.res).isEmpty());
            }
        });
        // name() — the icon's TOOLTIP (its display name), falling back to the resource name; nil once the
        // category is gone. Never throws: a Loading icon, or a custom mapicon whose name() blows up, falls back.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaIconCat h = handle(self, "name");
                List<GobIcon.Setting> sets = settingsFor(h.user, h.res);
                return sets.isEmpty() ? LuaValue.NIL : LuaValue.valueOf(catName(sets));
            }
        });
        m.set("show", flag(false));
        m.set("notify", flag(true));
        // info() — the one SNAPSHOT escape hatch (the old RadarCategory shape), for logging/serialising.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaIconCat h = handle(self, "info");
                String res = h.res;
                List<GobIcon.Setting> sets = settingsFor(h.user, res);
                if(sets.isEmpty())
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("res", LuaValue.valueOf(res));
                t.set("name", LuaValue.valueOf(catName(sets)));
                t.set("show", LuaValue.valueOf(anyFlag(sets, false)));
                t.set("notify", LuaValue.valueOf(anyFlag(sets, true)));
                return t;
            }
        });
        return m;
    }

    /**
     * One flag verb ({@code show} or {@code notify}), arity as the verb: no argument reads (a boolean, or
     * {@code nil} for a resource the registry does not carry), an argument writes every setting under that
     * resource and returns <b>self</b>. A write to an unknown resource is an <b>error</b>, not a silent
     * no-op: nothing else in the API would tell you the flag never landed.
     */
    private static TwoArgFunction flag(final boolean notifyFlag) {
        final String verb = notifyFlag ? "notify" : "show";
        return new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue v) {
                LuaIconCat h = handle(self, verb);
                String res = h.res;
                if(v.isnil()) {
                    List<GobIcon.Setting> sets = settingsFor(h.user, res);
                    return sets.isEmpty() ? LuaValue.NIL : LuaValue.valueOf(anyFlag(sets, notifyFlag));
                }
                if(!v.isboolean())
                    throw new LuaError("cat:" + verb + "(on): on must be true or false");
                if(setIn(MapApi.iconconf(h.user), res, v.toboolean(), notifyFlag) == 0)
                    throw new LuaError("cat:" + verb + "(on): no such icon category — the registry carries no \""
                        + res + "\" (it is empty before the HUD is up, and grows as new icon types are seen)");
                return self;
            }
        };
    }

    // ---- resolution against the live registry ------------------------------------------------------

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaIconCat handle(LuaValue self, String method) {
        LuaIconCat h = resolve(self);
        if(h == null)
            throw new LuaError("cat:" + method + "() — use a COLON call on an IconCat object"
                + " (hafen.map():icon():get(res), hafen.map():icon():list()[n])");
        return h;
    }

    /** Every {@link GobIcon.Setting} <b>that login's</b> registry carries under {@code res} (a resource may
     *  publish variants). */
    static List<GobIcon.Setting> settingsFor(String user, String res) {
        return settingsIn(MapApi.iconconf(user), res);
    }

    /**
     * Testable core of {@link #settingsFor}: the settings under {@code res} in a given registry (null-safe),
     * so the whole entity can be exercised headlessly without a live {@code GameUI}.
     */
    static List<GobIcon.Setting> settingsIn(GobIcon.Settings conf, String res) {
        List<GobIcon.Setting> out = new ArrayList<GobIcon.Setting>();
        if((conf == null) || (res == null))
            return out;
        Map<GobIcon.Setting.ID, GobIcon.Setting> m = conf.settings;   // swapped wholesale by the loader
        if(m == null)
            return out;
        for(GobIcon.Setting set : m.values()) {
            if((set != null) && (set.id != null) && res.equals(set.id.res))
                out.add(set);
        }
        return out;
    }

    /** Is the flag on for ANY of a resource's settings — "is this kind drawn/announced at all". */
    private static boolean anyFlag(List<GobIcon.Setting> sets, boolean notifyFlag) {
        for(GobIcon.Setting set : sets) {
            if(notifyFlag ? set.notify : set.show)
                return true;
        }
        return false;
    }

    /**
     * Set {@code show} (or {@code notify}) on every setting under {@code res}; persist if any actually
     * changed. Returns how many settings the resource had — {@code 0} meaning "no such category". Operates on
     * a given {@link GobIcon.Settings} (null-safe) so it is exercisable headlessly.
     */
    static int setIn(GobIcon.Settings conf, String res, boolean value, boolean notifyFlag) {
        List<GobIcon.Setting> sets = settingsIn(conf, res);
        boolean changed = false;
        for(GobIcon.Setting set : sets) {
            if(notifyFlag) {
                if(set.notify != value) { set.notify = value; changed = true; }
            } else {
                if(set.show != value) { set.show = value; changed = true; }
            }
        }
        if(changed)
            conf.dsave();   // debounced persist, exactly like the icon-settings checkboxes (andsave -> dsave)
        return sets.size();
    }

    /** A category's display name (the icon tooltip), falling back to the resource name; never throws. */
    static String catName(List<GobIcon.Setting> sets) {
        for(GobIcon.Setting set : sets) {
            try {
                if(set.icon != null) {
                    String nm = set.icon.name();
                    if(nm != null)
                        return nm;
                }
            } catch(RuntimeException e) {   // Loading, or a custom mapicon name() that blows up → keep looking
            }
        }
        return sets.isEmpty() ? "" : sets.get(0).id.res;
    }

    // ---- the collection ------------------------------------------------------------------------

    /**
     * {@code hafen.map():icon()} — the registry, as a collection of interned IconCat objects, one per
     * <b>resource</b> (the identity), in resource-name order so two calls agree. Empty before the HUD is up
     * and growing as the character sees new icon types.
     *
     * <p><b>The verb says which you meant, so nothing has to guess.</b> The old callable split its argument
     * <i>by shape</i> — a string with a {@code /} was a resource name, anything else a filter — which worked
     * only because a display name never contains a slash. {@code :get(res)} beside {@code :list(filter)}
     * deletes the heuristic outright: two questions, two verbs, and no rule about slashes to remember.
     */
    static LuaValue collection(final Addon owner) {
        return LuaCollection.create("hafen.map():icon()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                // hafen.map() is the character on screen's map, and each handle records WHICH character it
                // was read as, so a stashed one keeps editing that character's registry (audit2 B05).
                String user = AddonManager.drawnUser();
                GobIcon.Settings conf = MapApi.iconconf(user);
                Map<GobIcon.Setting.ID, GobIcon.Setting> m = (conf == null) ? null : conf.settings;
                if(m == null)
                    return out;
                Map<String, List<GobIcon.Setting>> byRes = new HashMap<String, List<GobIcon.Setting>>();
                List<String> names = new ArrayList<String>();
                for(GobIcon.Setting set : m.values()) {
                    if((set == null) || (set.id == null) || (set.id.res == null))
                        continue;
                    List<GobIcon.Setting> sets = byRes.get(set.id.res);
                    if(sets == null) {
                        byRes.put(set.id.res, sets = new ArrayList<GobIcon.Setting>());
                        names.add(set.id.res);
                    }
                    sets.add(set);
                }
                grouped = byRes;
                Collections.sort(names);
                for(String res : names)
                    out.add(of(owner, user, res));
                return out;
            }

            /** The registry of the last {@link #members()} grouped by resource — one walk of the map, then
             *  every needle of that call is a hash lookup. */
            private Map<String, List<GobIcon.Setting>> grouped = Collections.emptyMap();

            // A string filter matches the DISPLAY name (the icon's tooltip), which is what a person reading a
            // list of them would type; :get(res) is how you address one, and it takes the resource name.
            public String needle(LuaValue member) {
                LuaIconCat h = resolve(member);
                if(h == null)
                    return null;
                List<GobIcon.Setting> sets = grouped.get(h.res);
                return catName((sets != null) ? sets : settingsFor(h.user, h.res));
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                if(key.type() != LuaValue.TSTRING)   // in LuaJ a NUMBER also answers isstring()
                    throw new LuaError("hafen.map():icon():get(res): a category has no index — its identity"
                        + " is its icon RESOURCE name (\"gfx/terobjs/mm/boar\"). To search by the name a"
                        + " player sees, use :find(needle) or :list(needle).");
                String res = key.tojstring();
                String user = AddonManager.drawnUser();
                return settingsFor(user, res).isEmpty() ? LuaValue.NIL : of(owner, user, res);
            }

            /** A category's identity is its icon RESOURCE name; the player-facing name is a search. */
            public String keyName() {
                return "res";
            }
        }, null);
    }
}
