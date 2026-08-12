package io.brodgar.addon;

import haven.GameUI;
import haven.Speedget;

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
 * A <b>Speed object</b> — one of the four movement speeds the HUD's selector offers (spec
 * {@code 060-speed-collection}), and the collection they form. <b>The section object IS that collection</b>
 * (uniform grammar §2.1): {@code hafen.speed()} is the speeds you can pick right now,
 * {@code hafen.speed():current()} the one you are on, and {@code hafen.speed():set(x)} the verb that picks one.
 *
 * <p><b>The collection enumerates what you can pick; {@code :get} addresses a speed by its key.</b> That one
 * sentence is the whole of the wrinkle here, and it is deliberate: {@code :list()} is <i>exactly</i> the
 * selectable speeds — so everything it hands you is something {@code :set} accepts — while {@code :get(key)}
 * reaches all four by index {@code 0..3} or by whole (case-insensitive) display name, selectable or not, so
 * "is sprint unlocked yet?" has an address to ask about. The old {@code :max()} was a bound every caller
 * turned back into this range by hand.
 *
 * <p><b>Wraps only the index</b> ({@link LuaSlot} is the exact model — the other cache keyed by a small int
 * rather than by an object). Every read re-resolves through the live {@link Speedget}, so a stashed Speed
 * tracks the server locking and unlocking it, and {@code :info()} is the one snapshot escape hatch.
 * {@code :available()}/{@code :exists()} answer {@code false} rather than throwing while the selector is
 * absent, and {@code :name()} answers from {@link Speedget#tips}, which is static and therefore known before
 * any selector is.
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045), identical to the rest of the series: the handle
 * crosses as {@code LuaValue.userdataOf(luaSpeed, mt)} so Lua cannot scribble on it, and the {@link Cache}
 * lives on the owning {@link Addon} ({@link Addon#speeds}), never statically — so
 * {@code hafen.speed():get(2) == hafen.speed():get(2)}, {@code hafen.speed():current() == sp} is the "am I on
 * this one" test, and the cache dies whole with the {@link Addon} on {@code :reload}.
 *
 * <p><b>{@code Speedget.max} can be negative</b> — both {@code Speedget.mousewheel} and
 * {@code Speedget.globtype} guard on {@code max >= 0}. That is a live selector with an <i>empty</i>
 * {@code :list()} and a {@code :set} that refuses everything, and it is left as it is rather than clamped to
 * zero: it is the honest answer the old {@code :max()} never gave.
 *
 * <p><b>{@code :set} is the one protected verb</b> ({@code speed.set}, D-027/D-028). It drives the client's
 * own {@link Speedget#set} (wrap-not-reimplement, D-009 → {@code wdgmsg("set", n)}), which is exactly what
 * clicking or hotkeying that speed sends, so the server stays authoritative on whether a speed is allowed.
 * The read-back is a <b>round trip</b>: {@code Speedget.cur} only moves when the server sends its
 * {@code uimsg("cur")}, so {@code :current()} still names the old speed on the next line.
 */
public final class LuaSpeed {
    /** How many speeds there are — the selector is a fixed crawl/walk/run/sprint four. */
    public static final int SPEEDS = 4;

    /** The wire index of this speed ({@code 0} crawl … {@code 3} sprint) — the whole state of a handle. */
    public final int index;

    private LuaSpeed(int index) {
        this.index = index;
    }

    /** {@code tostring(sp)} (also the {@code __tostring} answer): {@code Speed(2 Run)}. */
    public String toString() {
        String n = speedName(index);
        return "Speed(" + index + ((n == null) ? "" : " " + n) + ")";
    }

    /** An interned Speed object for {@code index} in {@code owner}'s env — the one way a Speed reaches Lua. */
    static LuaValue of(Addon owner, int index) {
        return owner.speeds.of(index);
    }

    /** The {@code LuaSpeed} behind a Lua value, or {@code null} for anything that is not a Speed object. */
    static LuaSpeed resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaSpeed) ? (LuaSpeed)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /**
     * One addon's Speed interning cache and metatable (its {@link Addon#speeds}). Weak values + a
     * {@link ReferenceQueue} drained on every access; the metatable is built once, lazily. The key space is
     * four wide, so the weakness buys nothing here — the shape is kept identical to {@link LuaSlot.Cache}
     * on purpose: one interning idiom, one place to get it right.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<Integer, Ref> live = new HashMap<Integer, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code index} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(int index) {
            drain();
            Integer key = Integer.valueOf(index);
            Ref r = live.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaSpeed(index), meta());
            live.put(key, new Ref(v, key, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref sr = (Ref)r;
                if(live.get(sr.key) == sr)      // not already replaced by a fresh handle for the same index
                    live.remove(sr.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta();
            return mt;
        }
    }

    /** A weak handle reference that remembers its map key, so the {@link ReferenceQueue} drain can unmap it. */
    private static final class Ref extends WeakReference<LuaValue> {
        final Integer key;

        Ref(LuaValue v, Integer key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Speed metatable -----------------------------------------------------------------------

    /** The per-addon metatable: {@code __index} = the methods table, plus {@code __tostring}/{@code __name}. */
    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("speed", methods()));
        mt.set("__name", LuaValue.valueOf("Speed"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSpeed h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Speed(?)" : h.toString());
            }
        });
        return mt;
    }

    /**
     * The method set. {@code :index()} answers from the handle alone; the other four re-read the live
     * selector, and none of them throws while it is absent — a speed that cannot be picked yet is not an
     * error, it is the state the HUD is in for a beat after {@code EnterWorld}.
     */
    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // index() — the wire number this Speed addresses (0=crawl 1=walk 2=run 3=sprint), which is what the
        // selector's own message carries. Always answers, selector or no selector.
        m.set("index", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "index").index);
            }
        });
        // name() — the display name, from the widget's own resource tooltips (Speedget.tips). Those are
        // STATIC, so a name is known before any selector exists.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String n = speedName(handle(self, "name").index);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // available() — can this speed be picked right now? (n <= Speedget.max, the server's own lock.) This
        // is exactly the predicate hafen.speed():list() filters on, so a member of that list is always true.
        m.set("available", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(available(handle(self, "available").index));
            }
        });
        // exists() — is the speed selector up at all? False before the HUD streams it in and after a logout;
        // the four speeds themselves are static client facts, so this asks about the selector, not the speed.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                handle(self, "exists");
                return LuaValue.valueOf(speedget() != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch (the documented Speed table shape), for logging/serialising.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(handle(self, "info").index);
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaSpeed handle(LuaValue self, String method) {
        LuaSpeed h = resolve(self);
        if(h == null)
            throw new LuaError("speed:" + method + "() — use a COLON call on a Speed object"
                + " (hafen.speed():get(key), hafen.speed():list()[i], hafen.speed():current())");
        return h;
    }

    // ---- the reads ---------------------------------------------------------------------------------
    // The speed selector is a Speedget widget (crawl/walk/run/sprint) the server places under the HUD. It has
    // no named GameUI field, so we locate it with the 1d-1 Locator (a children(Class) subtree walk from the
    // HUD) — the same way vitals finds its IMeters. Both fields we read (cur = the selected speed, max = the
    // highest currently selectable one) are public ints, so this is a zero-haven-edit read. All calls run on
    // the UI thread (addon tick / REPL / slash command).

    /** The (unique) movement-speed widget under the HUD, or {@code null} before it has streamed in. */
    static Speedget speedget() {
        GameUI g = AddonManager.gui();
        if(g == null)
            return null;
        for(Speedget s : g.children(Speedget.class))   // recursive subtree walk; take the first
            return s;
        return null;
    }

    /**
     * The display name of speed {@code n}, or {@code null} out of range.
     *
     * <p><b>{@link Speedget#tips} is harvested from the icon's ON variant</b> — {@code Resource.tooltip} of
     * {@code gfx/hud/meter/rmeter/<name>-on} — so what the resource actually says is <i>"Run On"</i>, where
     * the trailing word names the artwork's state and not the speed. It is trimmed here, and trimmed off a
     * {@code :get}/{@code :set} key the same way, so the name of speed 2 is {@code "Run"} both when you read
     * it and when you address by it. (The client's own hover text keeps the raw tip: <i>"Selected speed:
     * Run On"</i>.)
     */
    static String speedName(int n) {
        String[] tips = Speedget.tips;                 // "Crawl On"/"Walk On"/"Run On"/"Sprint On"
        if((tips == null) || (n < 0) || (n >= tips.length))
            return null;
        return trimOn(tips[n]);
    }

    /** {@code "Run On"} → {@code "Run"}: the icon variant's state is not part of a speed's name. */
    private static String trimOn(String s) {
        if(s == null)
            return null;
        String t = s.trim();
        if((t.length() > 3) && t.regionMatches(true, t.length() - 3, " on", 0, 3))
            t = t.substring(0, t.length() - 3).trim();
        return t;
    }

    /** Can speed {@code n} be picked right now? False with no selector, and false for a locked speed. */
    static boolean available(int n) {
        Speedget s = speedget();
        return (s != null) && (n >= 0) && (n < SPEEDS) && (n <= s.max);
    }

    /** The speed the character is on, or {@code -1} with no selector (or a {@code cur} out of range). */
    private static int current() {
        Speedget s = speedget();
        if((s == null) || (s.cur < 0) || (s.cur >= SPEEDS))
            return -1;
        return s.cur;
    }

    /** A Speed snapshot — the documented {@code Speed} table shape: {@code index name available current}. */
    private static LuaValue snapshot(int n) {
        LuaTable t = new LuaTable();
        t.set("index", LuaValue.valueOf(n));
        String name = speedName(n);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        t.set("available", LuaValue.valueOf(available(n)));
        t.set("current", LuaValue.valueOf(current() == n));
        return t;
    }

    /**
     * The index of the speed whose display name is {@code s} (whole, case-insensitive), or {@code null}. The
     * key goes through {@link #trimOn} too, so a reader who copied the client's own hover text
     * ({@code "Run On"}) addresses the same speed as one who wrote what {@code sp:name()} answers.
     */
    private static Integer byName(String s) {
        if(s == null)
            return null;
        String want = trimOn(s);
        for(int i = 0; i < SPEEDS; i++) {
            String n = speedName(i);
            if((n != null) && n.equalsIgnoreCase(want))
                return Integer.valueOf(i);
        }
        return null;
    }

    /** Every display name, comma-separated ({@code "Crawl, Walk, Run, Sprint"}) — for the refusals. */
    private static String names() {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < SPEEDS; i++) {
            String n = speedName(i);
            if(n == null)
                continue;
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(n);
        }
        return sb.toString();
    }

    /** The selectable speeds as {@code "Crawl (0), Walk (1)"} — what a refused {@code :set} lists back. */
    private static String selectable() {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < SPEEDS; i++) {
            if(!available(i))
                continue;
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(speedName(i)).append(" (").append(i).append(")");
        }
        return (sb.length() == 0) ? "nothing at all" : sb.toString();
    }

    // ---- the collection ----------------------------------------------------------------------------

    /**
     * {@code hafen.speed()} — the speeds you can pick, as the {@link LuaCollection} the section object IS:
     * {@code :list(filter)} is a fresh 1-based array of (interned) Speed objects in crawl→sprint order,
     * {@code :count}/{@code :find} the usual pair, {@code :get(key)} one speed by index or display name, and
     * the section's own two verbs — {@code :current()} and the protected {@code :set(x)} — ride in the
     * {@code extra} table.
     *
     * <p><b>{@code :list()} is empty in two different situations</b>, and both are states rather than errors:
     * before the selector streams in (there is nothing to pick from yet) and when {@code Speedget.max} is
     * negative (the server has locked every speed). {@code :get} still answers a Speed in the second case,
     * because a locked speed is a speed; it answers {@code nil} in the first, because with no selector there
     * is nothing to address.
     */
    static LuaValue collection(final Addon owner) {
        LuaTable extra = new LuaTable();
        // current() — the speed you are ON, as the very member :list() holds, so `hafen.speed():current() == sp`
        // is the identity test and there is no second "am I on this one" verb. nil with no selector.
        // The write arity is GONE (060): :current() addresses a member, and a member address is not a property.
        extra.set("current", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "current");
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.speed():current(n) is retired — picking a speed is"
                        + " hafen.speed():set(speed|index|name), under the \"speed.set\" permission."
                        + " :current() ADDRESSES the member you are on, and a member address is not a"
                        + " property to write: hafen.speed():set(hafen.speed():get(\"Run\"))");
                int cur = current();
                return (cur < 0) ? LuaValue.NIL : of(owner, cur);
            }
        });
        // set(x) — the one protected verb (D-027/D-028, "speed.set"), gated as the FIRST statement (D-213).
        // Takes what :get takes, or the Speed object itself, and returns the COLLECTION so writes chain. The
        // send is the client's own Speedget.set (D-009 -> wdgmsg("set", n)) — exactly what a click sends, so
        // the server has the last word; the read-back is a round trip through its uimsg("cur").
        extra.set("set", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue me = a.arg1();
                AddonManager.requirePermission(owner, Permission.SPEED_SET);
                LuaCollection.receiver(me, "set");
                int n = demand(Args.required(a, 2, "hafen.speed():set", "speed"));
                Speedget s = speedget();
                if(s == null)
                    throw new LuaError("hafen.speed():set(speed): no speed selector (not in the world yet)");
                if(!available(n))
                    throw new LuaError("hafen.speed():set(speed): " + speedName(n) + " (" + n + ") is not"
                        + " selectable right now — you can pick " + selectable() + ", which is what"
                        + " hafen.speed():list() hands back");
                s.set(n);
                return me;
            }
        });
        return LuaCollection.create("hafen.speed()", new LuaCollection.Source() {
            /** Exactly the selectable ones, crawl→sprint: empty with no selector, empty with max &lt; 0. */
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>(SPEEDS);
                Speedget s = speedget();
                if(s == null)
                    return out;
                int max = Math.min(s.max, SPEEDS - 1);
                for(int i = 0; i <= max; i++)
                    out.add(of(owner, i));
                return out;
            }

            public String needle(LuaValue member) {
                LuaSpeed h = resolve(member);
                return (h == null) ? null : speedName(h.index);
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            /**
             * All four, selectable or not — the collection enumerates what you can pick, {@code :get}
             * addresses a speed by its key. A key of the right shape that names no speed is a plain
             * {@code nil} miss (an index outside {@code 0..3}, an unknown name); a key of the wrong TYPE is
             * an error, because there is no reading of it that could ever hit.
             */
            public LuaValue getMember(LuaValue key) {
                Integer n;
                if(key.isnumber()) {                // BEFORE isstring(): in LuaJ a number IS a string
                    int i = key.toint();
                    n = ((i < 0) || (i >= SPEEDS)) ? null : Integer.valueOf(i);
                } else if(key.isstring()) {
                    n = byName(key.tojstring());
                } else {
                    throw new LuaError("hafen.speed():get(key): the key is an index 0..3 (0=crawl 1=walk"
                        + " 2=run 3=sprint) or a whole display name (\"Run\", case-insensitive), got "
                        + key.typename());
                }
                if((n == null) || (speedget() == null))
                    return LuaValue.NIL;            // no selector: there is nothing to address yet
                return of(owner, n.intValue());
            }
        }, extra);
    }

    /**
     * The index {@code x} names, for {@code :set} — a Speed object, an index {@code 0..3} or a whole
     * (case-insensitive) display name. Unlike {@code :get}'s miss this <b>raises</b>: {@code :get} is a
     * lookup that may find nothing, while a {@code :set} whose argument names no speed is a write that was
     * meant to happen and did not.
     */
    private static int demand(LuaValue x) {
        LuaSpeed h = resolve(x);
        if(h != null)
            return h.index;
        if(x.isnumber()) {                          // BEFORE isstring(): in LuaJ a number IS a string
            int n = x.toint();
            if((n < 0) || (n >= SPEEDS))
                throw new LuaError("hafen.speed():set(speed): the index must be 0..3 (0=crawl 1=walk 2=run"
                    + " 3=sprint), got " + n);
            return n;
        }
        if(x.isstring()) {
            Integer n = byName(x.tojstring());
            if(n == null)
                throw new LuaError("hafen.speed():set(speed): no speed is called \"" + x.tojstring()
                    + "\" — the names are " + names() + ", and an index 0..3 works too");
            return n.intValue();
        }
        throw new LuaError("hafen.speed():set(speed): expected a Speed object (hafen.speed():get(key)), an"
            + " index 0..3 or a display name, got " + x.typename());
    }
}
