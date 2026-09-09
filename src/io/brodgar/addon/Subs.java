package io.brodgar.addon;

import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.List;
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
 * <p><b>Threading.</b> A fire reaches Lua from whichever thread raised the event — the layer's step, a
 * Loader thread carrying an inbound message, the render-query callback that resolved a click, the frame's own
 * draw. {@code docs/addons/api/threading.md} is the table. Nothing here moves threads: what serializes one
 * addon's handlers against the rest of its Lua is the addon's own lock, taken inside
 * {@link AddonManager#callLua}, and {@link #fire} holds it across the whole key so the handlers of one event
 * are one entry rather than a queue of them. The maps are concurrent and the handler lists copy-on-write
 * because a running handler may subscribe or {@code sub:off()} while the fire walks them — it sees the
 * snapshot it started with, and a handler killed mid-walk is skipped by its {@link LuaSub#alive} flag.
 */
public final class Subs {
    /**
     * <b>Every message on this stream</b> — the key {@code hafen.event():action():on("*", fn)} and
     * {@code hafen.event():message():on("*", fn)} reserve on the two emitters whose key set is OPEN (spec
     * {@code 082-the-whole-stream}). It cannot collide with a message name: a {@code wdgmsg} name is a
     * protocol identifier, so no message is ever called {@code *}, and the same character already means
     * <i>any</i> in a {@link Selector} and in a permission group.
     *
     * <p><b>The constant lives here, the MEANING does not.</b> {@link #fire} and {@link #has} know nothing
     * about it: a wildcard is a second list the two stream dispatches look up beside the named one
     * ({@link AddonManager#dispatchAction}), never a rule inside the mechanism. Giving {@code "*"} a meaning
     * in {@code fire} would give it one on every emitter, a widget's per-frame {@code Draw} included, and the
     * two streams are the only emitters whose key set is open in the first place.
     */
    public static final String WILD = "*";

    /**
     * How this emitter charges a handler's Lua time, per key — one of {@link Addon#C_EVENT}… Per KEY rather
     * than per emitter because a widget's {@code Draw} is {@code draw} while its {@code MouseDown} is
     * {@code widgets}, and the two live in one {@code Subs}.
     */
    public interface Cats {
        int cat(String key);
    }

    /**
     * Notified when a key's live-handler list has just gone from one to none — a widget's {@link Subs} (041.3)
     * uses this to deafen the ONE engine {@code EventHandler} it installed for that key once nobody addresses
     * it any more. {@code null} for every emitter that has no engine-side listener to release (the bus, the
     * two message streams): the hook is optional precisely because most emitters have nothing to do here.
     */
    interface Idle {
        void idle(String key);
    }

    /**
     * Notified when <b>ONE</b> subscription ends — from {@link #off} and from {@link #clear} — so the emitter
     * can release whatever it registered engine-side <i>alongside that one sub</i> (086.1). The per-SUB
     * sibling of {@link Idle}, and the three registries that came in with 086 need exactly this and not that:
     * several selector watches share the key {@code "Added"}, so a per-key hook fires when the last of them
     * goes, which is not when one of them is removed. {@code null} where nothing engine-side hangs off an
     * individual sub — the bus, the two message streams, a widget's own keys.
     */
    interface Ended {
        void ended(LuaSub s);
    }

    /**
     * The shared cancel flag of one fire — {@code ev:preventDefault()} sets it, and it is read once the last
     * handler has run. OR accumulation: any handler cancels, and every handler still runs.
     */
    public static final class Cancel {
        private boolean prevented;

        /**
         * <b>Whether this fire is over</b> (audit2 B14, ev-03) — set by the dispatcher when the last handler
         * has run and it has read {@link #prevented()}.
         *
         * <p>An {@code ev} is an ordinary Lua value, so a handler may stash it in a table and reach for it a
         * minute later; {@code ev:preventDefault()} then set a flag nobody was still reading and answered as
         * if it had stopped something. A fire that has been answered cannot be un-answered, and the verbs
         * that imply a cancel refuse rather than pretend. A {@code Cancel} whose dispatcher never finishes
         * it is simply never finished, which is what every site that does not bracket its fire already got.
         */
        private boolean done;

        /** {@code ev:preventDefault()} — from this moment the fire is cancelled, whoever else runs. */
        public void prevent() {
            prevented = true;
        }

        /** Did any handler of this fire cancel it? */
        public boolean prevented() {
            return prevented;
        }

        /** The dispatcher has read {@link #prevented()}: nothing said after this can change the outcome. */
        public void finish() {
            done = true;
        }

        /** Is the fire this cancel belongs to already over? — see {@link #done}. */
        public boolean done() {
            return done;
        }
    }

    /** The addon every handler here belongs to — the owner {@link AddonManager#callLua} charges. */
    final Addon owner;
    /** What each key costs, by category (see {@link Cats}). */
    private final Cats cats;
    /** Notified when a key empties out, or {@code null} — see {@link Idle}. */
    private final Idle idle;
    /** Notified when one subscription ends, or {@code null} — see {@link Ended}. */
    private final Ended ended;
    /** {@code key → the handlers on it}, in registration order. */
    private final Map<String, CopyOnWriteArrayList<LuaSub>> byKey =
        new ConcurrentHashMap<String, CopyOnWriteArrayList<LuaSub>>();
    /**
     * Does anyone here listen to {@link #WILD}? A cached answer to {@code has(WILD)}, written only on the
     * three cold paths that can change it ({@link #on}, {@link #off}, {@link #clear}) and read on the hot one
     * ({@link AddonManager#dispatchAction}'s gate, once per addon per message). That is the whole reason it is
     * a field rather than a second map lookup: the gate is paid by every addon on every message, including
     * the ones that named their key and want nothing to do with a wildcard.
     *
     * <p>Volatile, and for the same reason the handler lists are copy-on-write: a subscription may be made or
     * ended from inside a running handler, on whichever thread that fire is on.
     */
    private volatile boolean wild;

    /** An emitter whose every key costs the same category (the bus: {@code events}). */
    Subs(Addon owner, final int cat) {
        this(owner, flat(cat), null, null);
    }

    /** As above, plus a widget's {@link Idle} hook. */
    Subs(Addon owner, final int cat, Idle idle) {
        this(owner, flat(cat), idle, null);
    }

    /** As above, plus a registry's {@link Ended} hook (a console command, a hotkey, a selector watch). */
    Subs(Addon owner, final int cat, Ended ended) {
        this(owner, flat(cat), null, ended);
    }

    /** An emitter whose keys cost different categories (a widget: {@code draw} and {@code widgets}). */
    Subs(Addon owner, Cats cats) {
        this(owner, cats, null, null);
    }

    /** As above, plus a widget's {@link Idle} hook (released once a key's last live handler is gone). */
    Subs(Addon owner, Cats cats, Idle idle) {
        this(owner, cats, idle, null);
    }

    /** The one constructor the five above reach: what a key costs, and the two optional release hooks. */
    Subs(Addon owner, Cats cats, Idle idle, Ended ended) {
        this.owner = owner;
        this.cats = cats;
        this.idle = idle;
        this.ended = ended;
    }

    /** The {@link Cats} of an emitter whose every key costs the same. */
    private static Cats flat(final int cat) {
        return new Cats() {
            public int cat(String key) {
                return cat;
            }
        };
    }

    /**
     * Register {@code fn} on {@code key} and hand back the {@link LuaSub} handle Lua holds — this is the one
     * place a subscription is created. The key is validated by the CALLER (a closed key set throws naming
     * what it does answer, an open one takes any string), because what the vocabulary is depends on the
     * emitter and not on the mechanism.
     */
    LuaValue on(String key, LuaValue fn) {
        return add(key, fn).handle();
    }

    /**
     * {@link #on} with the {@link LuaSub} itself in hand rather than its Lua value — what an emitter with an
     * {@link Ended} hook registers through, so it can hang what it registered engine-side on {@link LuaSub#tag}
     * before anything can fire. Order matters at exactly one site: {@code s:ui():on} scans the live tree from
     * inside its own registration, so the sub has to exist first (086.1).
     */
    LuaSub add(String key, LuaValue fn) {
        LuaSub s = new LuaSub(this, key, fn);
        list(key).add(s);
        if(WILD.equals(key))
            wild = true;
        return s;
    }

    /**
     * Does anyone here listen to <b>every</b> message ({@link #WILD})? Read by the two stream dispatches, in
     * front of the {@code has(msg)} lookup they already did — see {@link #wild the field} for why it is
     * cached. It answers for THIS emitter only, so a wildcard one addon holds changes nothing for another.
     */
    public boolean wild() {
        return wild;
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
     * Every subscription here that is still live, in no particular order across keys and in registration
     * order within one (086.2). {@link #byKey} is private and two collections now need to read it:
     * {@code hafen.console()} is a collection of one of these emitters, and {@code hafen.event():list(filter)}
     * is the concatenation of three.
     *
     * <p><b>A snapshot, walked off the copy-on-write lists and never sorted.</b> A subscription may be made
     * or ended from inside a running handler, on whichever thread that fire is on, so the list handed back
     * is the caller's own: a sub that ends while Lua is walking the array is skipped by {@link LuaSub#alive}
     * at the next read rather than removed from under it.
     */
    List<LuaSub> live() {
        List<LuaSub> out = new ArrayList<LuaSub>();
        for(CopyOnWriteArrayList<LuaSub> l : byKey.values()) {
            for(LuaSub s : l) {
                if(s.alive)
                    out.add(s);
            }
        }
        return out;
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
        // audit2 B06: ONE entry for the whole key, not one per handler. Two handlers on the same event are
        // one addon reacting to one moment, so another thread's Lua may not land between them; each call
        // below re-enters the lock this took, which is what a ReentrantLock is for.
        if(!AddonManager.enterLua(owner))
            return false;                    // the door is shut, or this entry may not wait for it
        try {
            for(LuaSub s : l) {
                if(!s.alive) {
                    l.remove(s);     // a sub that ended mid-walk: drop it here rather than sweeping later
                    continue;
                }
                AddonManager.callLua(owner, cat, s.fn, args);
            }
        } finally {
            AddonManager.leaveLua(owner);
        }
        return (c != null) && c.prevented();
    }

    /**
     * End one subscription ({@code sub:off()}) — by identity, and idempotent: a second call finds nothing.
     * Notifies {@link #ended} for the subscription that went, so a registry can release what it registered
     * beside it, and {@link #idle} once the key it was on has no live handler left, so a widget's
     * {@link Subs} can deafen the engine listener nobody needs any more. Both fire only on a sub that was
     * actually there, which is what keeps a second {@code off()} from releasing anything twice.
     */
    void off(LuaSub s) {
        CopyOnWriteArrayList<LuaSub> l = byKey.get(s.key);
        boolean gone = (l != null) && l.remove(s);
        if(WILD.equals(s.key))
            wild = has(WILD);         // recomputed, never decremented: two wildcards, one ended, still one
        // The per-SUB hook first — it releases what THIS subscription registered engine-side (086.1) — then
        // the per-KEY one, which is a different question: whether anybody addresses the key at all any more.
        if(gone && (ended != null))
            ended.ended(s);
        if(gone && (idle != null) && l.isEmpty())
            idle.idle(s.key);
    }

    /**
     * Drop every subscription this emitter holds (teardown: {@code :reload}/disable, or the emitter dying).
     * Each is marked dead as well as dropped, so a fire already walking its snapshot when teardown ran stops
     * calling into an env that is being rebuilt — the same guard {@link LuaSub#alive} gives one {@code off()}.
     */
    public void clear() {
        for(CopyOnWriteArrayList<LuaSub> l : byKey.values()) {
            for(LuaSub s : l) {
                s.alive = false;
                if(ended != null)
                    ended.ended(s);   // 086.1: teardown releases each sub's own engine-side registration,
            }                         //   which is what makes clear() the WHOLE teardown of a registry
        }
        byKey.clear();
        wild = false;
    }
}
