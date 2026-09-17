package io.brodgar.addon;

import haven.Steam;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * {@code hafen.steam()} — <b>the Steamworks subsystem and Steam achievements</b>.
 *
 * <p>Exposes Steam status and achievements to addons. When the client is running under Steam,
 * {@code :available()} answers {@code true}, and {@code :achievement()} provides a standard
 * {@link LuaCollection} over all 99 Steam achievements.
 */
public final class SteamApi {
    private SteamApi() {
    }

    private static class PendingEvent {
        final String type;
        final String arg;

        PendingEvent(String type, String arg) {
            this.type = type;
            this.arg = arg;
        }
    }

    private static final Queue<PendingEvent> pendingEvents = new ConcurrentLinkedQueue<PendingEvent>();
    private static boolean listenerRegistered = false;

    private static synchronized void ensureListener() {
        if(listenerRegistered)
            return;
        Steam s = Steam.get();
        if(s == null)
            return;
        s.add(new Steam.Listener() {
            public void callback(String id, Object[] args) {
                if("onUserStatsReceived".equals(id)) {
                    pendingEvents.add(new PendingEvent("stats_loaded", null));
                } else if("onUserAchievementStored".equals(id)) {
                    if((args != null) && (args.length >= 3) && (args[2] instanceof String)) {
                        pendingEvents.add(new PendingEvent("achievement_unlocked", (String)args[2]));
                    }
                }
            }
        });
        listenerRegistered = true;
    }

    /**
     * Drain queued Steam events onto the bus. Called from {@link AddonManager#layerStep} each frame,
     * executing safely on the UI thread holding no locks.
     */
    static void drain() {
        ensureListener();
        PendingEvent ev;
        while((ev = pendingEvents.poll()) != null) {
            if("stats_loaded".equals(ev.type)) {
                AddonManager.fire("SteamStatsLoaded");
            } else if("achievement_unlocked".equals(ev.type)) {
                AddonManager.fireAchievementUnlocked(ev.arg);
            }
        }
    }

    /**
     * Install {@code hafen.steam()} onto {@code hafen} for {@code owner}.
     */
    static void installSteam(LuaTable hafen, final Addon owner) {
        ensureListener();

        final LuaValue achievementColl = buildAchievementCollection(owner);

        LuaTable methods = new LuaTable();

        // available() — true if Steamworks is initialized and connected.
        methods.set("available", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "steam", "available");
                return LuaValue.valueOf(Steam.get() != null);
            }
        });

        // ready() — true if stats and achievements have finished loading from Steam.
        methods.set("ready", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "steam", "ready");
                Steam s = Steam.get();
                return LuaValue.valueOf((s != null) && s.isStatsLoaded());
            }
        });

        // user() — current Steam persona / display name, or nil. The ONE spelling: `username` was an alias of
        // it, and the API grammar has no aliases (one canonical way, a replaced spelling throws naming its
        // replacement), so that name is a Refusal.MOVED row now.
        methods.set("user", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "steam", "user");
                Steam s = Steam.get();
                if(s == null)
                    return LuaValue.NIL;
                String name = s.displayname();
                return (name == null) ? LuaValue.NIL : LuaValue.valueOf(name);
            }
        });

        // id() — Steam 32-bit account ID, or nil if invalid / not running.
        methods.set("id", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "steam", "id");
                Steam s = Steam.get();
                if(s == null)
                    return LuaValue.NIL;
                int id = s.userid();
                return (id == -1) ? LuaValue.NIL : LuaValue.valueOf(id);
            }
        });

        // refresh() — requests an asynchronous re-fetch of stats from Steam servers.
        methods.set("refresh", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "steam", "refresh");
                Steam s = Steam.get();
                if(s != null)
                    s.requestStats();
                return self;
            }
        });

        // achievement() — the collection of Steam achievements.
        methods.set("achievement", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "steam", "achievement");
                return achievementColl;
            }
        });

        Section.install(hafen, "steam", methods);
    }

    private static LuaValue buildAchievementCollection(final Addon owner) {
        final LuaCollection.Source src = new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                Steam s = Steam.get();
                if(s == null || !s.isStatsLoaded())
                    return out;
                for(String nm : s.getAchievementNames()) {
                    out.add(LuaAchievement.of(owner, nm));
                }
                return out;
            }

            public int size() {
                Steam s = Steam.get();
                return (s == null || !s.isStatsLoaded()) ? 0 : s.getNumAchievements();
            }

            public boolean named() {
                return true;
            }

            public String needle(LuaValue member) {
                LuaAchievement h = LuaAchievement.resolve(member);
                return (h == null) ? null : h.name;
            }

            public boolean addressable() {
                return true;
            }

            public String keyName() {
                return "name";
            }

            public LuaValue getMember(LuaValue key) {
                if(!key.isstring())
                    return LuaValue.NIL;
                String nm = key.tojstring();
                Steam s = Steam.get();
                if(s == null)
                    return LuaValue.NIL;
                return LuaAchievement.of(owner, nm);
            }
        };

        LuaTable extra = new LuaTable();
        // unlocked([filter]) — array of Achievement handles that are achieved.
        extra.set("unlocked", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaCollection.receiver(self, "hafen.steam():achievement()", "unlocked");
                LuaValue filter = a.arg(2);
                LuaTable out = new LuaTable();
                Steam s = Steam.get();
                if(s == null || !s.isStatsLoaded())
                    return out;
                int idx = 0;
                List<String> names = s.getAchievementNames();
                for(String nm : names) {
                    Boolean ach = s.isAchieved(nm);
                    if(ach != null && ach.booleanValue()) {
                        LuaValue h = LuaAchievement.of(owner, nm);
                        if(LuaCollection.keeps(filter, h, src, "hafen.steam():achievement()", "unlocked"))
                            out.set(++idx, h);
                    }
                }
                return out;
            }
        });

        return LuaCollection.create("hafen.steam():achievement()", src, extra);
    }
}
