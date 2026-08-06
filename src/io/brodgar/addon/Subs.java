package io.brodgar.addon;

import org.luaj.vm2.LuaValue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One emitter's <b>subscriptions</b> — the single mechanism every notification in the API is delivered
 * through (spec {@code 041-unified-events} §R1): a keyed multimap {@code key → handlers}, a {@link #fire}
 * that walks it through {@link AddonManager#callLua}, and the {@code hasSub} gate ({@link #has}) that keeps
 * a busy stream free for the addons that are not listening.
 *
 * <p><b>Everything reduces to this.</b> {@code hafen.event():on(key, fn)}, the two message streams, a
 * widget's own keys and a mouse grab all own one of these; nothing else in the area has subscription logic.
 * Who owns which is what decides the <i>address</i> an addon writes — <i>¿tienes el objeto?
 * {@code obj:on(...)}; ¿no? {@code hafen.event()}</i> — and it is the same rule one level down as
 * <a href="../../../../specs/addons/decisions/architecture-api.md">D-100</a>: the state belongs on the
 * THING, so a listener on a native widget costs that widget's record and nothing global.
 *
 * <p><b>N subscribers, registration order, and no return value read.</b> Two handlers on one key both fire,
 * in the order they were registered <i>within one addon</i> (between addons it is undefined). {@link #fire}
 * never short-circuits: cancelling is {@code ev:preventDefault()}, accumulated with OR across the handlers
 * of one fire ({@link Cancel}), so every handler still runs and the outcome never depends on registration
 * order — the only defensible answer once inter-addon order is undefined.
 *
 * <p><b>The profiling category is carried HERE, per emitter and key</b>, never inferred from the mechanism.
 * After 041 every callback in the client is a {@code Subs.fire}, so a single charge inside {@code fire} would
 * collapse {@link Addon#CATS}' five-way split (D-052) to one category and silently flatten
 * {@code p:addons()}. A bus fire charges {@code events}, a widget's {@code Draw} charges {@code draw}, its
 * input keys charge {@code widgets} — which is what each of them costs today, unchanged.
 *
 * <p><b>Threading.</b> Every fire is on the UI thread or under {@code synchronized(ui)}, exactly as before;
 * nothing here moves threads. The maps are concurrent and the handler lists copy-on-write because a running
 * handler may subscribe or {@code sub:off()} while the fire walks them — it sees the snapshot it started
 * with, and a handler killed mid-walk is skipped by its {@link LuaSub#alive} flag.
 */
public final class Subs {
    /**
     * How this emitter charges a handler's Lua time, per key — one of {@link Addon#C_EVENT}… Per KEY rather
     * than per emitter because a widget's {@code Draw} is {@code draw} while its {@code MouseDown} is
     * {@code widgets}, and the two live in one {@code Subs}.
     */
    public interface Cats {
        int cat(String key);
    }

    /**
     * The shared cancel flag of one fire — {@code ev:preventDefault()} sets it, and it is read once the last
     * handler has run. OR accumulation: any handler cancels, and every handler still runs.
     */
    public static final class Cancel {
        private boolean prevented;

        /** {@code ev:preventDefault()} — from this moment the fire is cancelled, whoever else runs. */
        public void prevent() {
            prevented = true;
        }

        /** Did any handler of this fire cancel it? */
        public boolean prevented() {
            return prevented;
        }
    }

    /** The addon every handler here belongs to — the owner {@link AddonManager#callLua} charges. */
    final Addon owner;
    /** What each key costs, by category (see {@link Cats}). */
    private final Cats cats;
    /** {@code key → the handlers on it}, in registration order. */
    private final Map<String, CopyOnWriteArrayList<LuaSub>> byKey =
        new ConcurrentHashMap<String, CopyOnWriteArrayList<LuaSub>>();

    /** An emitter whose every key costs the same category (the bus: {@code events}). */
    Subs(Addon owner, final int cat) {
        this(owner, new Cats() {
            public int cat(String key) {
                return cat;
            }
        });
    }

    /** An emitter whose keys cost different categories (a widget: {@code draw} and {@code widgets}). */
    Subs(Addon owner, Cats cats) {
        this.owner = owner;
        this.cats = cats;
    }

    /**
     * Register {@code fn} on {@code key} and hand back the {@link LuaSub} handle Lua holds — this is the one
     * place a subscription is created. The key is validated by the CALLER (a closed key set throws naming
     * what it does answer, an open one takes any string), because what the vocabulary is depends on the
     * emitter and not on the mechanism.
     */
    LuaValue on(String key, LuaValue fn) {
        LuaSub s = new LuaSub(this, key, fn);
        list(key).add(s);
        return s.handle();
    }

    /** The handler list for {@code key}, created on the first subscription to it. */
    private CopyOnWriteArrayList<LuaSub> list(String key) {
        CopyOnWriteArrayList<LuaSub> l = byKey.get(key);
        if(l == null) {
            CopyOnWriteArrayList<LuaSub> fresh = new CopyOnWriteArrayList<LuaSub>();
            l = byKey.putIfAbsent(key, fresh);
            if(l == null)
                l = fresh;
        }
        return l;
    }

    /**
     * Is anyone still listening to {@code key}? The {@code hasSub} gate (spec §2.1, kept): a per-addon event
     * payload is minted only for an owner that actually subscribes, so a busy spawn stream costs nothing for
     * the addons that do not listen.
     */
    public boolean has(String key) {
        CopyOnWriteArrayList<LuaSub> l = byKey.get(key);
        if(l == null)
            return false;
        for(LuaSub s : l) {
            if(s.alive)
                return true;
        }
        return false;
    }

    /** Fire {@code key} with nothing to cancel — the shape every uncancelable key uses. */
    boolean fire(String key, LuaValue... args) {
        return fire(key, null, args);
    }

    /**
     * Fire {@code key}: every live handler runs, in registration order, each through
     * {@link AddonManager#callLua} (watchdog-armed, error-isolated, charged to this key's category). A
     * handler's return value is never read — cancelling is {@code c.prevent()} from inside the event object
     * the caller handed the handlers.
     *
     * @return whether any handler cancelled ({@code false} when {@code c} is null).
     */
    boolean fire(String key, Cancel c, LuaValue... args) {
        CopyOnWriteArrayList<LuaSub> l = byKey.get(key);
        if(l == null)
            return false;
        int cat = cats.cat(key);
        for(LuaSub s : l) {
            if(!s.alive) {
                l.remove(s);     // a sub that ended mid-walk: drop it here rather than sweeping later
                continue;
            }
            AddonManager.callLua(owner, cat, s.fn, args);
        }
        return (c != null) && c.prevented();
    }

    /** End one subscription ({@code sub:off()}) — by identity, and idempotent: a second call finds nothing. */
    void off(LuaSub s) {
        CopyOnWriteArrayList<LuaSub> l = byKey.get(s.key);
        if(l != null)
            l.remove(s);
    }

    /**
     * Drop every subscription this emitter holds (teardown: {@code :reload}/disable, or the emitter dying).
     * Each is marked dead as well as dropped, so a fire already walking its snapshot when teardown ran stops
     * calling into an env that is being rebuilt — the same guard {@link LuaSub#alive} gives one {@code off()}.
     */
    public void clear() {
        for(CopyOnWriteArrayList<LuaSub> l : byKey.values()) {
            for(LuaSub s : l)
                s.alive = false;
        }
        byKey.clear();
    }
}
