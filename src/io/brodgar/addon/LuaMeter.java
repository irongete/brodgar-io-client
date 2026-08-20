package io.brodgar.addon;

import haven.AddonWidgets;
import haven.GameUI;
import haven.IMeter;
import haven.LayerMeter;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>Meter object</b> — one bar in the HUD's meter slot (spec {@code 027-meters-oop}), the OOP successor of
 * the flat {@code vitals()} snapshot the player used to carry. Built on exactly the mechanism {@link LuaGob} (017),
 * {@link LuaKin} (020), {@link LuaSlot} (021), {@link LuaPagina} (023), {@link LuaSound} (024) and
 * {@link LuaBuff} (025) established; <b>the section object IS the meter slot</b> (uniform grammar §2.1):
 * {@code s:meter()} is the collection of every HUD meter and {@code s:meter():find(needle)} is one of
 * them.
 *
 * <p><b>There is no fixed vitals triple.</b> The HUD's {@code place == "meter"} slot takes an arbitrary
 * number of {@link IMeter}s laid out in a 3-wide grid; the old reader hard-coded the first three as
 * {@code hp}/{@code stamina}/{@code energy} <i>by tree position</i> and dropped the rest. A meter is
 * identified here by what the engine actually publishes about it — its {@link IMeter#bg} <b>resource
 * name</b> — so the lookup is a substring search over server-published names, never a client-side
 * dictionary of what the genre calls them. {@code :res()} is how an addon author reads the real names off a
 * live client.
 *
 * <p><b>The handle wraps the {@link IMeter} widget and nothing else.</b> Every read goes through it live, so
 * a stashed Meter tracks its own bar as the server pushes {@code "set"}/{@code "col"} updates — interned
 * userdata is an <i>identity</i>, not a record. {@code :info()} is the one snapshot escape hatch.
 *
 * <p><b>The intern key is the widget object itself</b> (identity), the {@link LuaBuff} shape: the res name is
 * not guaranteed unique across the slot, and a meter has a lifetime, so an {@link IdentityHashMap} with
 * <b>weak values</b> + a {@link ReferenceQueue} drained on every access — never a {@code WeakHashMap}, which
 * is weak on the wrong axis. The strong keys are bounded: an entry outlives its meter only until the next
 * access drains it, and an {@link IMeter} the addon still holds a handle to is <i>meant</i> to stay readable
 * — that is what makes a {@code MeterRemoved} payload worth having.
 *
 * <p><b>Removed but still readable.</b> {@code Widget.destroy()} unlinks a meter; it clears neither {@code bg}
 * nor its segment list. So a Meter whose widget is gone keeps answering {@code :res()}/{@code :value()}/… and
 * reports {@code :exists()} <b>false</b> (and {@code :index()} nil) — the same property {@link LuaBuff} has and
 * {@link LuaSound} deliberately does not (D-060).
 *
 * <p><b>Every read is {@code Loading}-guarded and may answer {@code nil}.</b> {@code bg.get()} throws until the
 * resource is cached, so a brand-new meter is routinely nameless for a beat, and the values stream in as
 * individual {@code "set"} uimsgs after enter-world. That is normal, never an error into Lua — and it is why
 * {@code :list()} includes a still-loading meter anyway (identity is the widget, not the name).
 *
 * <p><b>No verb.</b> Meters are server-pushed presentation; there is nothing to write (spec 027, out of scope).
 */
public final class LuaMeter {
    /** The meter widget this handle addresses — the whole state of a handle. */
    public final IMeter wdg;

    private LuaMeter(IMeter wdg) {
        this.wdg = wdg;
    }

    /** {@code tostring(meter)} (also the {@code __tostring} answer): {@code Meter(<resname>)}. */
    public String toString() {
        String r = res(wdg);
        return "Meter(" + ((r == null) ? "?" : r) + ")";
    }

    /** An interned Meter object for {@code m} in {@code owner}'s env — the one way a Meter reaches Lua. */
    static LuaValue of(Addon owner, IMeter m) {
        return owner.meters.of(m);
    }

    /** The {@code LuaMeter} behind a Lua value, or {@code null} for anything that is not a Meter object. */
    static LuaMeter resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaMeter) ? (LuaMeter)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /**
     * One addon's Meter interning cache and metatable (its {@link Addon#meters}). Weak values + a
     * {@link ReferenceQueue} drained on every access; the metatable is built once, lazily. Keyed by the
     * {@link IMeter} widget's <b>identity</b> — see the class comment.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<IMeter, Ref> live = new IdentityHashMap<IMeter, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code m} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(IMeter m) {
            drain();
            if(m == null)
                return LuaValue.NIL;
            Ref r = live.get(m);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(m);
            }
            LuaValue v = LuaValue.userdataOf(new LuaMeter(m), meta());
            live.put(m, new Ref(v, m, dead));
            return v;
        }

        /**
         * Drop the map entries whose handle Lua has released. Mandatory on every access: the keys are
         * STRONG, so skipping it would pin every destroyed {@link IMeter} widget for the session.
         */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref mr = (Ref)r;
                if(live.get(mr.key) == mr)      // not already replaced by a fresh handle for the same widget
                    live.remove(mr.key);
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
        final IMeter key;

        Ref(LuaValue v, IMeter key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Meter metatable -----------------------------------------------------------------------

    /**
     * The per-addon metatable: {@code __index} = the methods table through {@link Retired#closedIndex} (so
     * an unknown verb throws naming what this type does answer), plus {@code __tostring}/{@code __name}.
     */
    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("meter", methods(),
            "a meter is one bar in the HUD meter slot: it answers :res() :index() :value() :color()"
            + " :segments() :exists() and :info()"));
        mt.set("__name", LuaValue.valueOf("Meter"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaMeter h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Meter(?)" : h.toString());
            }
        });
        return mt;
    }

    /**
     * The method set. Every reader re-reads through the widget and answers {@code nil} when the value is not
     * (yet) published or is still {@code Loading}; {@code :exists()} always answers.
     */
    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // res() — the meter's background resource name, its identity and the thing s:meter():find(needle)
        // searches. SERVER-published, so it is never hard-coded here; nil for a beat while it loads.
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String r = res(handle(self, "res").wdg);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // index() — the meter's 1-based position in the HUD meter list (its layout order), nil once the
        // meter is gone. A position, not an identity: use :res() to tell meters apart.
        m.set("index", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Integer i = index(handle(self, "index").wdg);
                return (i == null) ? LuaValue.NIL : LuaValue.valueOf(i.intValue());
            }
        });
        // value() — the FIRST bar segment's fraction, 0..1 (what the old vitals snapshot returned). nil
        // while the meter has no segments yet. For a multi-segment bar see :segments().
        m.set("value", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Double v = value(handle(self, "value").wdg);
                return (v == null) ? LuaValue.NIL : LuaValue.valueOf(v.doubleValue());
            }
        });
        // color() — the first segment's colour as {r,g,b,a} 0..255. The server changes it on its own (the
        // "col" uimsg), so it is a real state change, not decoration. nil while there are no segments.
        m.set("color", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LayerMeter.Meter s = segment(handle(self, "color").wdg, 0);
                return ((s == null) || (s.c == null)) ? LuaValue.NIL : AddonManager.color(s.c);
            }
        });
        // segments() — the whole bar as a 1-based array of {value=0..1, color={r,g,b,a}}. A vital bar is one
        // segment; the engine's meter type is genuinely multi-segment and the old snapshot threw that away.
        m.set("segments", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return segments(handle(self, "segments").wdg);
            }
        });
        // exists() — is this meter still in the HUD meter slot? False once it is destroyed, and false across
        // a relog. The reads keep working either way, which is what makes a stashed MeterRemoved payload useful.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(exists(handle(self, "exists").wdg));
            }
        });
        // info() — the one SNAPSHOT escape hatch ({res,index,value,color,segments}), for logging/serialising.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(handle(self, "info").wdg);
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaMeter handle(LuaValue self, String method) {
        LuaMeter h = resolve(self);
        if(h == null)
            throw new LuaError("meter:" + method + "() — use a COLON call on a Meter object"
                + " (s:meter():find(needle), s:meter():list()[i])");
        return h;
    }

    // ---- the reads (all Loading-guarded) -----------------------------------------------------------

    /**
     * The meters currently in the HUD's meter slot, in layout order — the engine's own {@code GameUI.meters}
     * list, filtered to {@link IMeter} ({@link AddonWidgets#hudMeters}). The single scan {@code :list()},
     * the lookup, {@code :index()}, {@code :exists()} and the {@code MeterAdapter} all share.
     */
    static List<IMeter> hud(String user) {
        GameUI g = AddonManager.gameui(user);
        return (g == null) ? new ArrayList<IMeter>() : AddonWidgets.hudMeters(g);
    }

    /**
     * The meter slot <b>a bar is standing in</b>, walked up from the widget itself rather than named by an
     * account. A Meter handle wraps the widget, and the widget already knows whose HUD it hangs in, so
     * {@code :exists()} and {@code :index()} answer about that character's slot however many sessions are
     * live. Empty once the bar is unlinked, which is exactly what makes those two report its absence.
     */
    private static List<IMeter> slotOf(IMeter m) {
        GameUI g = (m == null) ? null : m.getparent(GameUI.class);
        return (g == null) ? new ArrayList<IMeter>() : AddonWidgets.hudMeters(g);
    }

    /** Is {@code m} in its own session's HUD meter slot right now? — {@code :exists()}. */
    static boolean exists(IMeter m) {
        if(m == null)
            return false;
        for(IMeter c : slotOf(m)) {
            if(c == m)
                return true;
        }
        return false;
    }

    /** The 1-based HUD position of {@code m}, or {@code null} if it is no longer there — {@code :index()}. */
    static Integer index(IMeter m) {
        if(m == null)
            return null;
        List<IMeter> hud = slotOf(m);
        for(int i = 0; i < hud.size(); i++) {
            if(hud.get(i) == m)
                return Integer.valueOf(i + 1);
        }
        return null;
    }

    /** The meter's background resource name — its server-published identity — or {@code null} (Loading). */
    static String res(IMeter m) {
        return (m == null) ? null : AddonManager.resIdent(m.bg);
    }

    /** Bar segment {@code i} of a meter, or {@code null} (none published yet / out of range). */
    private static LayerMeter.Meter segment(IMeter m, int i) {
        if(m == null)
            return null;
        try {
            List<LayerMeter.Meter> ms = AddonWidgets.meters(m);
            return ((ms == null) || (ms.size() <= i)) ? null : ms.get(i);
        } catch(RuntimeException e) {
            return null;
        }
    }

    /** The first bar segment's fraction (0..1), or {@code null} (empty / still resolving) — {@code :value()}. */
    static Double value(IMeter m) {
        LayerMeter.Meter s = segment(m, 0);
        return (s == null) ? null : Double.valueOf(s.a);
    }

    /** The whole bar as a 1-based array of {@code {value,color}} — {@code :segments()}. Never nil (may be empty). */
    static LuaValue segments(IMeter m) {
        LuaTable out = new LuaTable();
        if(m == null)
            return out;
        List<LayerMeter.Meter> ms;
        try {
            ms = AddonWidgets.meters(m);
        } catch(RuntimeException e) {
            return out;
        }
        if(ms == null)
            return out;
        for(int i = 0; i < ms.size(); i++) {
            LayerMeter.Meter s = ms.get(i);
            if(s == null)
                continue;
            LuaTable e = new LuaTable();
            e.set("value", LuaValue.valueOf(s.a));
            if(s.c != null)
                e.set("color", AddonManager.color(s.c));
            out.set(i + 1, e);
        }
        return out;
    }

    /**
     * A Meter snapshot — {@code meter:info()}, the escape hatch for logging/serialising:
     * {@code {res,index,value,color,segments}}. Expressed over the same accessors the methods use, so there
     * is one source of truth per field; an absent value is simply an unset key.
     */
    static LuaValue snapshot(IMeter m) {
        if(m == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String res = res(m);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        Integer idx = index(m);
        if(idx != null)
            t.set("index", LuaValue.valueOf(idx.intValue()));
        Double v = value(m);
        if(v != null)
            t.set("value", LuaValue.valueOf(v.doubleValue()));
        LayerMeter.Meter s = segment(m, 0);
        if((s != null) && (s.c != null))
            t.set("color", AddonManager.color(s.c));
        t.set("segments", segments(m));
        return t;
    }

    // ---- the collection ----------------------------------------------------------------------------

    /**
     * {@code s:meter()} — the HUD's bars, as the {@link LuaCollection} the section object IS:
     * {@code :list(filter)} is a fresh 1-based array of (interned) Meter objects in HUD order,
     * {@code :find(needle)} the first that matches, {@code :count(filter)} how many. Legitimately empty for a
     * beat after {@code SessionEnteredWorld} — the meters stream in; {@code MeterAdded} (027.2) is the
     * honest signal.
     *
     * <p><b>There is no {@code :get}</b>: a meter has no key. A string filter matches the
     * <i>server-published</i> background resource name as a substring, so {@code "hp"} is not a key this code
     * knows — it is a substring that happens to identify a bar on this server, and {@code :res()} is how to
     * list the real ones.
     */
    static LuaValue collection(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.M, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<IMeter> hud = hud(user);
                List<LuaValue> out = new ArrayList<LuaValue>(hud.size());
                for(int i = 0; i < hud.size(); i++)
                    out.add(of(owner, hud.get(i)));
                return out;
            }

            // A meter whose resource has not resolved yet is listed (identity is the widget, not the name) and
            // simply matches no string filter, rather than refusing the filter for everybody.
            public String needle(LuaValue member) {
                LuaMeter h = resolve(member);
                String res = (h == null) ? null : res(h.wdg);
                return (res == null) ? "" : res;
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }
        }, null);
    }
}
