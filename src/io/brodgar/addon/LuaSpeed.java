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
 * (uniform grammar §2.1): {@code s:speed()} is the speeds that character can pick right now,
 * {@code s:speed():current()} the one it is on, and {@code s:speed():set(x)} the verb that picks one.
 *
 * <p><b>The collection enumerates what you can pick; {@code :get} addresses a speed by its key.</b> That one
 * sentence is the whole of the wrinkle here: {@code :list()} is <b>all four</b> (A-079) and
 * {@code s:speed():available(filter)} is the partition {@code :set} accepts, while {@code :get(key)} reaches
 * any of them by the 1-based index {@code 1..4} {@code sp:index()} answers, or by whole (case-insensitive)
 * display name, selectable or not — so "is sprint unlocked yet?" has an address to ask about. The old
 * {@code :max()} was a bound every caller turned back into this range by hand.
 *
 * <p><b>Wraps the account and the index</b> ({@link LuaSlot} is the exact model — the other cache keyed by a
 * small int rather than by an object). Every read re-resolves through <b>that character's</b> live
 * {@link Speedget}, so a stashed Speed tracks the server locking and unlocking it, and {@code :info()} is the
 * one snapshot escape hatch. {@code :available()}/{@code :exists()} answer {@code false} rather than throwing
 * while the selector is absent, and {@code :name()} answers from {@link Speedget#tips}, which is static and
 * therefore known before any selector is.
 *
 * <p><b>A speed is unlocked on one character at a time</b> (077.3), which is why the account is half the
 * handle. The selector is a widget under one login's HUD and both fields read off it — {@code cur} and
 * {@code max} — are that character's, so sprint unlocked here says nothing about the alt, and
 * {@code :current()} names two different members on two sessions. Two levels of intern map, on
 * {@code (account, index)}: the {@link LuaGob} shape.
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045), identical to the rest of the series: the handle
 * crosses as {@code LuaValue.userdataOf(luaSpeed, mt)} so Lua cannot scribble on it, and the {@link Cache}
 * lives on the owning {@link Addon} ({@link Addon#speeds}), never statically — so
 * {@code s:speed():get(2) == s:speed():get(2)}, {@code s:speed():current() == sp} is the "am I on this one"
 * test, and the cache dies whole with the {@link Addon} on {@code :reload}.
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
 *
 * <p><b>It keeps the ONE key it has, addressed or not</b> (077.3): a key names the action, not the target,
 * and the player could have tabbed to that character and clicked that icon. The send needs no anchor either —
 * {@code Speedget.set} goes through the widget's own tree, so it reaches the session it was addressed at.
 */
public final class LuaSpeed {
    /** How many speeds there are — the selector is a fixed crawl/walk/run/sprint four. */
    public static final int SPEEDS = 4;

    /** The account whose selector this speed is on — half the address, and what makes the index one fact. */
    public final String user;
    /** The wire index of this speed ({@code 0} crawl … {@code 3} sprint), on that character's selector. */
    public final int index;

    private LuaSpeed(String user, int index) {
        this.user = user;
        this.index = index;
    }

    /** {@code tostring(sp)} (also the {@code __tostring} answer): {@code Speed(2 Run)}. */
    public String toString() {
        String n = speedName(index);
        return "Speed(" + index + ((n == null) ? "" : " " + n) + ")";
    }

    /** An interned Speed object for {@code index} <b>on {@code user}'s selector</b>, in {@code owner}'s env. */
    static LuaValue of(Addon owner, String user, int index) {
        return owner.speeds.of(user, index);
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
     * One addon's Speed interning cache and metatable (its {@link Addon#speeds}), keyed by the <b>account
     * plus</b> the wire index: a selector is one character's, so speed 3 on two characters is two different
     * facts and must have two handles. Two levels of map, the {@link LuaGob} shape. Weak values + a
     * {@link ReferenceQueue} drained on every access; the metatable is built once, lazily. The key space is
     * four wide per account, so the weakness buys nothing here — the shape is kept identical to
     * {@link LuaSlot.Cache} on purpose: one interning idiom, one place to get it right.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Map<Integer, Ref>> live = new HashMap<String, Map<Integer, Ref>>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code (user, index)} — a cache hit, or a freshly minted (inserted) one. */
        synchronized LuaValue of(String user, int index) {
            drain();
            Map<Integer, Ref> byidx = live.get(user);
            if(byidx == null)
                live.put(user, byidx = new HashMap<Integer, Ref>());
            Integer key = Integer.valueOf(index);
            Ref r = byidx.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                byidx.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaSpeed(user, index), meta());
            byidx.put(key, new Ref(v, user, key, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref sr = (Ref)r;
                Map<Integer, Ref> byidx = live.get(sr.user);
                if(byidx == null)
                    continue;
                if(byidx.get(sr.key) == sr)     // not already replaced by a fresh handle for the same index
                    byidx.remove(sr.key);
                if(byidx.isEmpty())
                    live.remove(sr.user);
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
        final String user;
        final Integer key;

        Ref(LuaValue v, String user, Integer key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.user = user;
            this.key = key;
        }
    }

    // ---- the Speed metatable -----------------------------------------------------------------------

    /**
     * The per-addon metatable: {@code __index} = the methods table through {@link Refusal#closedIndex} (so
     * an unknown verb throws naming what this type does answer), plus {@code __tostring}/{@code __name}.
     */
    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("speed", methods(),
            "one movement speed",
            "picking one is s:speed():set(x)"));
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
     * error, it is the state the HUD is in for a beat after {@code SessionEnteredWorld}.
     */
    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // index() — the 1-based position in s:speed():list() (1=crawl 2=walk 3=run 4=sprint), the number
        // :get(n) and :set(n) take, so :list()[n] == :get(n) holds (090, A-071). Always answers.
        m.set("index", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "index").index + 1);
            }
        });
        // wire() — the raw number the selector's own message carries (0=crawl 1=walk 2=run 3=sprint).
        m.set("wire", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "wire").index);
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
        // is exactly the predicate s:speed():list() filters on, so a member of that list is always true.
        m.set("available", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSpeed h = handle(self, "available");
                return LuaValue.valueOf(available(h.user, h.index));
            }
        });
        // exists() — is the speed selector up at all? False before the HUD streams it in and after a logout;
        // the four speeds themselves are static client facts, so this asks about the selector, not the speed.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(speedget(handle(self, "exists").user) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch (the documented Speed table shape), for logging/serialising.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSpeed h = handle(self, "info");
                return snapshot(h.user, h.index);
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaSpeed handle(LuaValue self, String method) {
        LuaSpeed h = resolve(self);
        if(h == null)
            throw new LuaError("speed:" + method + "() — use a COLON call on a Speed object ("
                + CharApi.SP + ":get(key), " + CharApi.SP + ":list()[i], " + CharApi.SP + ":current())");
        return h;
    }

    // ---- the reads ---------------------------------------------------------------------------------
    // The speed selector is a Speedget widget (crawl/walk/run/sprint) the server places under the HUD. It has
    // no named GameUI field, so we locate it with the 1d-1 Locator (a children(Class) subtree walk from the
    // HUD) — the same way vitals finds its IMeters. Both fields we read (cur = the selected speed, max = the
    // highest currently selectable one) are public ints, so this is a zero-haven-edit read.

    /**
     * That character's movement-speed widget, or {@code null} before its HUD has streamed one in.
     *
     * <p>Under that tree's own monitor (audit2 B06): it is a recursive walk of the whole HUD subtree and a
     * Loader thread re-links it under the same monitor, so an unguarded walk could miss the widget entirely
     * or follow a {@code next} that had just been re-pointed.
     */
    static Speedget speedget(String user) {
        GameUI g = AddonManager.gameui(user);          // THAT session's HUD, not the drawn one's
        if(g == null)
            return null;
        synchronized(LuaWidget.monitor(g)) {
            for(Speedget s : g.children(Speedget.class))   // recursive subtree walk; take the first
                return s;
        }
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

    /** Can that character pick speed {@code n} now? False with no selector, and false for a locked speed. */
    static boolean available(String user, int n) {
        Speedget s = speedget(user);
        return (s != null) && (n >= 0) && (n < SPEEDS) && (n <= s.max);
    }

    /** The speed that character is on, or {@code -1} with no selector (or a {@code cur} out of range). */
    private static int current(String user) {
        Speedget s = speedget(user);
        if((s == null) || (s.cur < 0) || (s.cur >= SPEEDS))
            return -1;
        return s.cur;
    }

    /** A Speed snapshot — the documented {@code Speed} table shape: {@code index name available current}. */
    private static LuaValue snapshot(String user, int n) {
        LuaTable t = new LuaTable();
        t.set("index", LuaValue.valueOf(n + 1));   // 090: the same number sp:index() answers
        t.set("wire", LuaValue.valueOf(n));        //      and the raw one sp:wire() does
        String name = speedName(n);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        t.set("available", LuaValue.valueOf(available(user, n)));
        t.set("current", LuaValue.valueOf(current(user) == n));
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

    /**
     * The selectable speeds as {@code "Crawl (1), Walk (2)"} — what a refused {@code :set} lists back.
     *
     * <p><b>1-based, because that is what {@code :set} takes.</b> 090 made the key the position
     * {@code sp:index()} answers and this message kept counting from the internal 0, so a caller told
     * <i>"you can pick Walk (2)"</i> who wrote {@code :set(1)} got <b>Crawl</b> — a refusal that handed back
     * a number selecting the wrong thing. A message that names an index has to name the one the verb beside
     * it accepts.
     */
    private static String selectable(String user) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < SPEEDS; i++) {
            if(!available(user, i))
                continue;
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(speedName(i)).append(" (").append(i + 1).append(")");
        }
        return (sb.length() == 0) ? "nothing at all" : sb.toString();
    }

    // ---- the collection ----------------------------------------------------------------------------

    /**
     * {@code s:speed()} — the speeds <b>that character</b> can pick, as the {@link LuaCollection} the section
     * object IS: {@code :list(filter)} is a fresh 1-based array of (interned) Speed objects in crawl→sprint
     * order, {@code :count}/{@code :find} the usual pair, {@code :get(key)} one speed by index or display
     * name, and the section's own two verbs — {@code :current()} and the protected {@code :set(x)} — ride in
     * the {@code extra} table.
     *
     * <p><b>{@code :list()} is empty in two different situations</b>, and both are states rather than errors:
     * before the selector streams in (there is nothing to pick from yet) and when {@code Speedget.max} is
     * negative (the server has locked every speed). {@code :get} still answers a Speed in the second case,
     * because a locked speed is a speed; it answers {@code nil} in the first, because with no selector there
     * is nothing to address.
     */
    static LuaValue collection(final Addon owner, final String user) {
        LuaTable extra = new LuaTable();
        // current() — the speed you are ON, as the very member :list() holds, so `s:speed():current() == sp`
        // is the identity test and there is no second "am I on this one" verb. nil with no selector.
        // The write arity is GONE (060): :current() addresses a member, and a member address is not a property.
        // available(filter) -- the SELECTABLE speeds (091, A-079). :list() is all four now, so the
        // partition that used to BE :list() needs its own name, the way s:char():skill():buyable() does.
        extra.set("available", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), CharApi.SP, "available");
                final LuaValue filter = a.arg(2);
                return LuaCollection.create(CharApi.SP + ":available()", new LuaCollection.Source() {
                    public List<LuaValue> members() {
                        List<LuaValue> out = new ArrayList<LuaValue>(SPEEDS);
                        Speedget s = speedget(user);
                        if(s == null)
                            return out;
                        int max = Math.min(s.max, SPEEDS - 1);
                        for(int i = 0; i <= max; i++) {
                            LuaValue member = of(owner, user, i);
                            if(LuaCollection.keeps(filter, member, this, CharApi.SP, "available"))
                                out.add(member);
                        }
                        return out;
                    }

                    public boolean named() {
                        return true;
                    }

                    public String needle(LuaValue member) {
                        LuaSpeed h = resolve(member);
                        return (h == null) ? null : speedName(h.index);
                    }

                    public String noGet() {
                        return "the selectable speeds are a partition of the four:"
                            + " " + CharApi.SP + ":get(key) addresses any of them, selectable or not";
                    }
                }, null);
            }
        });
        extra.set("current", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), CharApi.SP, "current");
                if(Args.passed(a, 2))
                    throw new LuaError(CharApi.SP + ":current() takes no argument — picking a speed is "
                        + CharApi.SP + ":set(speed|index|name), under the \"speed.set\" permission."
                        + " :current() ADDRESSES the member you are on, and a member address is not a"
                        + " property to write: " + CharApi.SP + ":set(" + CharApi.SP + ":get(\"Run\"))");
                int cur = current(user);
                return (cur < 0) ? LuaValue.NIL : of(owner, user, cur);
            }
        });
        // set(x) — the one protected verb (D-027/D-028, "speed.set"), gated as the FIRST statement (D-213).
        // Takes what :get takes, or the Speed object itself, and returns the COLLECTION so writes chain. The
        // send is the client's own Speedget.set (D-009 -> wdgmsg("set", n)) — exactly what a click sends, so
        // the server has the last word; the read-back is a round trip through its uimsg("cur").
        extra.set("set", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue me = a.arg1();
                AddonManager.requirePermission(AddonManager.current(), Permission.SPEED_SET);
                LuaCollection.receiver(me, CharApi.SP, "set");
                int n = demand(Args.required(a, 2, CharApi.SP + ":set", "speed"));
                // That character's own selector sends it: Speedget.set walks the widget's own tree to that
                // session, so a speed picked on a character nobody is looking at reaches the right server.
                Speedget s = speedget(user);
                if(s == null)
                    throw new LuaError(CharApi.SP + ":set(speed): no speed selector (that character is not"
                        + " in the world yet)");
                if(!available(user, n))
                    throw new LuaError(CharApi.SP + ":set(speed): " + speedName(n) + " (" + (n + 1) + ") is"
                        + " not selectable right now — you can pick " + selectable(user) + ", which is what "
                        + CharApi.SP + ":available() hands back (:list() is all four, and sp:available() says"
                        + " which of them can be picked)");
                // Speedget.set is the client's own selection (D-009 -> wdgmsg("set", n)), so the index
                // it will carry is what Wire's "set" row reads.
                Wire.send(owner, user, CharApi.SP + ":set", s, "set", new Object[] {Integer.valueOf(n)},
                          () -> s.set(n));
                return me;
            }
        });
        return LuaCollection.create(CharApi.SP, new LuaCollection.Source() {
            /** ALL FOUR, crawl→sprint (A-079): empty only with no selector at all. Which of them can be
             *  picked right now is {@code sp:available()}, and the partition is {@code s:speed():available()}. */
            public List<LuaValue> members() {
                // 091/A-079: ALL FOUR, always. :list() was the SELECTABLE ones, so it grew as the character
                // unlocked them -- a collection that answered a different question from every other :list()
                // in the API. Which of them can be picked right now is sp:available(), and the partition is
                // s:speed():available(filter).
                List<LuaValue> out = new ArrayList<LuaValue>(SPEEDS);
                for(int i = 0; i < SPEEDS; i++)
                    out.add(of(owner, user, i));
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
             * All four, selectable or not — the collection enumerates every speed and {@code :get}
             * addresses one by its key. A key of the right shape that names no speed is a plain
             * {@code nil} miss (an index outside {@code 1..4}, an unknown name); a key of the wrong TYPE is
             * an error, because there is no reading of it that could ever hit.
             */
            public LuaValue getMember(LuaValue key) {
                Integer n;
                if(key.type() == LuaValue.TNUMBER) {   // by TYPE, so "2" is a name and not a position
                    // 090: the 1-based position sp:index() answers
                    int i = Args.integer(key, CharApi.SP + ":get", "key", "the 1-based position sp:index()"
                                         + " answers, 1..4 (1=crawl 2=walk 3=run 4=sprint)");
                    n = ((i < 1) || (i > SPEEDS)) ? null : Integer.valueOf(i - 1);
                } else if(key.isstring()) {
                    n = byName(key.tojstring());
                } else {
                    throw new LuaError(CharApi.SP + ":get(key): the key is the 1-based position sp:index()"
                        + " answers, 1..4 (1=crawl 2=walk 3=run 4=sprint), or a whole display name"
                        + " (\"Run\", case-insensitive), got " + key.typename());
                }
                if((n == null) || (speedget(user) == null))
                    return LuaValue.NIL;            // no selector: there is nothing to address yet
                return of(owner, user, n.intValue());
            }
        }, extra);
    }

    /**
     * The index {@code x} names, for {@code :set} — a Speed object, the 1-based index {@code 1..4} or a whole
     * (case-insensitive) display name. Unlike {@code :get}'s miss this <b>raises</b>: {@code :get} is a
     * lookup that may find nothing, while a {@code :set} whose argument names no speed is a write that was
     * meant to happen and did not.
     */
    private static int demand(LuaValue x) {
        LuaSpeed h = resolve(x);
        if(h != null)
            return h.index;
        if(x.type() == LuaValue.TNUMBER) {          // by TYPE, so "2" is a name and not a position
            // 090: the 1-based position sp:index() answers
            int n = Args.integer(x, CharApi.SP + ":set", "speed", "the 1-based position sp:index() answers,"
                                 + " 1..4 (1=crawl 2=walk 3=run 4=sprint)");
            if((n < 1) || (n > SPEEDS))
                throw new LuaError(CharApi.SP + ":set(speed): the index is the 1-based position sp:index()"
                    + " answers, 1..4 (1=crawl 2=walk 3=run 4=sprint), got " + n);
            return n - 1;
        }
        if(x.isstring()) {
            Integer n = byName(x.tojstring());
            if(n == null)
                throw new LuaError(CharApi.SP + ":set(speed): no speed is called \"" + x.tojstring()
                    + "\" — the names are " + names() + ", and the 1..4 position works too");
            return n.intValue();
        }
        throw new LuaError(CharApi.SP + ":set(speed): expected a Speed object (" + CharApi.SP + ":get(key)),"
            + " the 1..4 position or a display name, got " + x.typename());
    }
}
