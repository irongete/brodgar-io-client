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
 * {@code hafen.map.icons}, a relation of the map rather than a namespace beside it (D-066). The two
 * filter-mutators ({@code setVisible}/{@code setNotify}) are gone: <b>arity is the verb on the entity</b>
 * ({@code cat:show()} reads, {@code cat:show(v)} writes and returns self), and <b>arity is the verb on the
 * namespace</b> too (D-056), built exactly as {@link LuaKin} and {@link LuaPagina} built theirs.
 *
 * <p><b>The argument splits by SHAPE, the {@link LuaPagina} rule verbatim.</b> A string containing a {@code /}
 * is a <b>resource name</b> — the identity — and answers <b>one</b> category, {@code nil} for a resource the
 * registry does not carry. No argument, a function, or any other string is the canonical
 * {@code filter} and answers the <b>array</b> of matching categories (a display name never contains a
 * {@code /}, so the two forms cannot collide).
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
 * <p><b>The writes are ungated</b>, as {@code hafen.radar}'s were: they change a client-local display setting,
 * nothing the server sees. They persist per character through {@code Settings.dsave()}, debounced, exactly as
 * the settings window's checkboxes do — so a broad sweep rewrites configuration the user set by hand.
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045): the handle crosses as
 * {@code LuaValue.userdataOf(luaIconCat, mt)} so Lua cannot scribble on it, and the {@link Cache} on the owning
 * {@link Addon} (weak values + a {@link ReferenceQueue} drained on every access) makes
 * {@code hafen.map.icons(res) == hafen.map.icons(res)} and {@code seen[cat] = true} reliable.
 *
 * <p><b>Threading.</b> Every read/write runs on the UI thread (addon tick / REPL / timer / slash command),
 * matching the settings window. {@code Settings.settings} is replaced wholesale by the loader thread, so a
 * local reference to it is a stable snapshot to iterate; the individual booleans are written on the UI thread
 * with no torn read. The {@link Cache} map is guarded on its own monitor (UI + REPL threads touch it).
 */
public final class LuaIconCat {
    /** The icon resource name — the whole state of a handle, and its identity. */
    public final String res;

    private LuaIconCat(String res) {
        this.res = res;
    }

    /** {@code tostring(cat)} (also the {@code __tostring} answer): {@code IconCat(<res>)}. */
    public String toString() {
        return "IconCat(" + res + ")";
    }

    /** An interned IconCat object for {@code res} in {@code owner}'s env — the one way one reaches Lua. */
    static LuaValue of(Addon owner, String res) {
        return owner.iconCats.of(res);
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

        /** The interned handle for {@code res} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(String res) {
            drain();
            Ref r = live.get(res);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(res);
            }
            LuaValue v = LuaValue.userdataOf(new LuaIconCat(res), meta());
            live.put(res, new Ref(v, res, dead));
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

    /** The per-addon metatable: {@code __index} = the methods table, plus {@code __tostring}/{@code __name}. */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods(owner));
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
                return LuaValue.valueOf(!settingsFor(handle(self, "exists").res).isEmpty());
            }
        });
        // name() — the icon's TOOLTIP (its display name), falling back to the resource name; nil once the
        // category is gone. Never throws: a Loading icon, or a custom mapicon whose name() blows up, falls back.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                List<GobIcon.Setting> sets = settingsFor(handle(self, "name").res);
                return sets.isEmpty() ? LuaValue.NIL : LuaValue.valueOf(catName(sets));
            }
        });
        m.set("show", flag(false));
        m.set("notify", flag(true));
        // info() — the one SNAPSHOT escape hatch (the old RadarCategory shape), for logging/serialising.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String res = handle(self, "info").res;
                List<GobIcon.Setting> sets = settingsFor(res);
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
                String res = handle(self, verb).res;
                if(v.isnil()) {
                    List<GobIcon.Setting> sets = settingsFor(res);
                    return sets.isEmpty() ? LuaValue.NIL : LuaValue.valueOf(anyFlag(sets, notifyFlag));
                }
                if(!v.isboolean())
                    throw new LuaError("cat:" + verb + "(on): on must be true or false");
                if(setIn(MapApi.iconconf(), res, v.toboolean(), notifyFlag) == 0)
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
                + " (hafen.map.icons(res), hafen.map.icons()[n])");
        return h;
    }

    /** Every {@link GobIcon.Setting} the registry carries under {@code res} (a resource may publish variants). */
    private static List<GobIcon.Setting> settingsFor(String res) {
        return settingsIn(MapApi.iconconf(), res);
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
    private static String catName(List<GobIcon.Setting> sets) {
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

    // ---- the collection --------------------------------------------------------------------------

    /**
     * {@code hafen.map.icons([filter])} — a fresh array of the interned IconCat objects the filter matches,
     * one per <b>resource</b> (the identity), in resource-name order so two calls agree. Empty before the HUD
     * is up; never throws. The filter is the canonical one: {@code nil} = all, a <b>string</b> = a substring of
     * the display name (evaluated Java-side), a <b>function</b> = a predicate called with the <b>IconCat
     * object</b> (the {@code hafen.world} shape, not a snapshot) — an error in it drops the entry.
     */
    private static LuaValue collection(Addon owner, LuaValue filter) {
        LuaTable out = new LuaTable();
        GobIcon.Settings conf = MapApi.iconconf();
        if(conf == null)
            return out;
        Map<GobIcon.Setting.ID, GobIcon.Setting> m = conf.settings;
        if(m == null)
            return out;
        Set<String> seen = new HashSet<String>();
        List<String> names = new ArrayList<String>();
        for(GobIcon.Setting set : m.values()) {
            if((set == null) || (set.id == null) || (set.id.res == null))
                continue;
            if(seen.add(set.id.res))
                names.add(set.id.res);
        }
        Collections.sort(names);
        int i = 0;
        for(String res : names) {
            LuaValue cat = of(owner, res);
            if(matches(filter, res, cat))
                out.set(++i, cat);
        }
        return out;
    }

    /** Does the category under {@code res} pass a collection filter? See {@link #collection}. */
    private static boolean matches(LuaValue filter, String res, LuaValue cat) {
        if((filter == null) || filter.isnil())
            return true;
        if(filter.isfunction()) {
            try {
                return filter.call(cat).toboolean();
            } catch(RuntimeException e) {   // LuaError is a RuntimeException
                return false;
            }
        }
        if(filter.isstring())
            return catName(settingsFor(res)).contains(filter.tojstring());
        return true;
    }

    /**
     * {@code hafen.map.icons} itself: a <b>callable table</b> ({@code __call}) with the {@link LuaPagina}
     * shape split — a string with a {@code /} is a resource name and answers ONE category ({@code nil} for a
     * resource the registry does not carry), anything else is the filter and answers the array. Indexing it
     * (the old {@code categories}/{@code setVisible}/{@code setNotify} fields) reads as plain {@code nil}:
     * the hard cut (D-013) is visible from Lua.
     */
    static LuaValue factory(final Addon owner) {
        LuaTable icons = new LuaTable();
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.CALL, new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue key = a.arg(2);            // arg1 = the callable table itself
                if(key.isnumber())                  // BEFORE isstring(): in LuaJ a number IS a string
                    throw new LuaError("hafen.map.icons(key): a category has no index — its identity is its icon"
                        + " resource name (\"gfx/terobjs/mm/boar\"). No argument = every category.");
                if(key.isstring() && (key.tojstring().indexOf('/') >= 0)) {
                    String res = key.tojstring();
                    return settingsFor(res).isEmpty() ? LuaValue.NIL : of(owner, res);
                }
                return collection(owner, key);      // nil / a name substring / a predicate
            }
        });
        icons.setmetatable(mt);
        return icons;
    }
}
