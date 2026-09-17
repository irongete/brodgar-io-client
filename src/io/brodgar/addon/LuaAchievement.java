package io.brodgar.addon;

import haven.Steam;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * An <b>Achievement object</b> — one Steam achievement ({@code hafen.steam():achievement():get(name)}).
 *
 * <p><b>The intern key is the achievement API name</b> (D-094), matching Steam's identifier (e.g.
 * {@code "paginae/exp/swanlake"}).
 */
public final class LuaAchievement {
    /** The achievement API name, this handle's identity. */
    public final String name;

    private LuaAchievement(String name) {
        this.name = name;
    }

    public String toString() {
        return "Achievement(" + name + ")";
    }

    /** An interned Achievement object for {@code name} in {@code owner}'s env. */
    static LuaValue of(Addon owner, String name) {
        return owner.achievements.of(name);
    }

    /** The {@code LuaAchievement} behind a Lua value, or {@code null} for anything else. */
    static LuaAchievement resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaAchievement) ? (LuaAchievement)o : null;
    }

    // ---- per-addon intern cache + metatable --------------------------------------------------------

    static final class Cache {
        private final Map<String, Ref> live = new HashMap<String, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
        }

        synchronized LuaValue of(String name) {
            drain();
            if(name == null)
                return LuaValue.NIL;
            Ref r = live.get(name);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(name);
            }
            LuaValue v = LuaValue.userdataOf(new LuaAchievement(name), meta());
            live.put(name, new Ref(v, name, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref br = (Ref)r;
                if(live.get(br.key) == br)
                    live.remove(br.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta();
            return mt;
        }
    }

    private static final class Ref extends WeakReference<LuaValue> {
        final String key;

        Ref(LuaValue v, String key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Achievement metatable -----------------------------------------------------------------

    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("achievement", methods(),
            "a Steam achievement"));
        mt.set("__name", LuaValue.valueOf("Achievement"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaAchievement h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Achievement(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // name() — the Steam achievement API identifier string.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "name").name);
            }
        });
        // unlocked() — true if achieved, false if locked or stats not loaded. The ONE spelling: `achieved`
        // was an alias of it, and the API grammar has no aliases, so that name is a Refusal.MOVED row now.
        m.set("unlocked", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaAchievement h = handle(self, "unlocked");
                Steam s = Steam.get();
                if(s == null)
                    return LuaValue.FALSE;
                Boolean st = s.isAchieved(h.name);
                return (st != null && st.booleanValue()) ? LuaValue.TRUE : LuaValue.FALSE;
            }
        });
        // exists() — whether this achievement is in the loaded Steam schema. The schema is the list of names
        // the stats callback read off Steam; isAchieved() cannot answer this, since steamworks4j hands back
        // its default (false) for a name Steam has not got, which read as "exists" for any string once the
        // stats were in. False without Steam and until the stats have loaded.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaAchievement h = handle(self, "exists");
                Steam s = Steam.get();
                if((s == null) || !s.isStatsLoaded())
                    return LuaValue.FALSE;
                return LuaValue.valueOf(s.getAchievementNames().contains(h.name));
            }
        });
        // info() — snapshot table: {name = "...", unlocked = true/false}
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaAchievement h = handle(self, "info");
                LuaTable t = new LuaTable();
                t.set("name", LuaValue.valueOf(h.name));
                Steam s = Steam.get();
                boolean unl = false;
                if(s != null) {
                    Boolean st = s.isAchieved(h.name);
                    if(st != null && st.booleanValue())
                        unl = true;
                }
                t.set("unlocked", LuaValue.valueOf(unl));
                return t;
            }
        });
        return m;
    }

    private static LuaAchievement handle(LuaValue self, String method) {
        LuaAchievement h = resolve(self);
        if(h == null)
            throw new LuaError("ach:" + method + "(): expected an Achievement object"
                + " (what hafen.steam():achievement():get(name) or :list() hands back, got a "
                + self.typename() + ")");
        return h;
    }
}
