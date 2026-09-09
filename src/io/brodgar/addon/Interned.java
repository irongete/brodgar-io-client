package io.brodgar.addon;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * <b>The one intern cache</b> — the single place a key becomes the object an addon holds for it, and the
 * only mechanism in this package that mints one lazily.
 *
 * <p><b>Why one.</b> Interning is the grammar's identity rule ({@code docs/addons/api/conventions.md}):
 * a read hands back the same object every call, so {@code ==} is the identity test and one works as a table
 * key. Every entity here kept that promise with a cache of its own, and the copies drifted: two of them
 * ({@link LuaWidget}, {@link LuaRule}) locked a {@code WeakHashMap}, five minted on an unguarded field or
 * a plain map ({@code LuaSub.self}, {@code LuaOption.self}, {@code PlayerMark.handObj}, the two font maps,
 * {@code Addon.widgetSubs}), and four more did not intern at all ({@code LuaPetal}, {@code LuaFep},
 * {@code LuaHunger}, {@code LuaFepEntry}), so the promise held or failed by which door a reader came
 * through. One implementation is one answer: the check and the mint are one act under this object's lock,
 * so two threads that ask at once get one object and the second never sees a half-built one.
 *
 * <p><b>Three strengths, because a cache is not a registry.</b> What an entry may hold alive is the one
 * thing these caches legitimately differ on, so it is the constructor's only choice:
 *
 * <ul>
 *   <li>{@link #identity()} — <b>weak keys and weak values</b>. An identity map over something the engine
 *       (or another addon) owns: a widget, a font face, a tab widget. {@code WeakHashMap} keys by identity
 *       for free where the key class overrides neither {@code equals} nor {@code hashCode}, and the value
 *       is held behind a {@link WeakReference} so an entry whose value reaches its own key — a userdata
 *       over the key itself, which most of them are — is still weakly unreachable. Nothing here pins
 *       anything, which is the D-041 no-pin rule this shape was written for; a value collected while its
 *       key is alive is simply minted again on the next lookup.</li>
 *   <li>{@link #keyed()} — <b>a strong key, a weak value, and a {@link ReferenceQueue} sweep</b>. The key
 *       is a name the addon spelled ({@code "user@3"}, a resource, a food event) rather than an object,
 *       so nothing collects it: the queue is what drops the entry once Lua has let the value go.</li>
 *   <li>{@link #held()} — <b>strong on both axes</b>. Not an identity map at all but a record the client
 *       owns and retires by hand: one addon's subscriptions on one widget, the built-in fonts it has
 *       named. What ends an entry is {@link #drop} or {@link #clear}, and the field says so
 *       ({@code tools/widgetstate.py} asks every widget-keyed one of them for that note).</li>
 * </ul>
 *
 * <p><b>Per addon, never static.</b> Every instance lives on an {@link Addon} and dies whole with it on
 * {@code :reload}/disable, because no Lua value crosses a sandbox boundary (D-017) — a static cache would
 * hand one addon's userdata to another and outlive the reload.
 *
 * <p><b>The mint runs under the lock</b>, which is what makes the check and the insert one act. So a mint
 * must be what these all are: an allocation and, at most, a read of the tree it is minted from. It must
 * not take another monitor, and it must not call back into this cache — the first inverts a lock order,
 * the second is a mint of a mint. Everything a caller wants to do to what came out (tearing a record
 * down, firing at it) is done to the value this hands back, outside the lock, which is why {@link #drop}
 * answers with what it dropped instead of returning {@code void}.
 */
final class Interned<K, V> {
    /** The entries: {@code V} itself when values are strong, else a {@link Ref} to it. */
    private final Map<K, Object> live;
    /** Where a collected value announces itself, or {@code null} when the map's own keys sweep it. */
    private final ReferenceQueue<V> dead;
    private final boolean weakValues;

    private Interned(Map<K, Object> live, boolean weakValues, ReferenceQueue<V> dead) {
        this.live = live;
        this.weakValues = weakValues;
        this.dead = dead;
    }

    /**
     * Weak keys <b>and</b> weak values — the identity map over an object somebody else owns. No queue: a
     * dead key takes its entry with it, and a dead value under a live key is replaced by the next lookup.
     */
    static <K, V> Interned<K, V> identity() {
        return new Interned<K, V>(new WeakHashMap<K, Object>(), true, null);
    }

    /** A strong key, a weak value, and the {@link ReferenceQueue} that unmaps the key when the value goes. */
    static <K, V> Interned<K, V> keyed() {
        return new Interned<K, V>(new LinkedHashMap<K, Object>(), true, new ReferenceQueue<V>());
    }

    /** Strong on both axes, in insertion order: a record this addon owns and retires by hand. */
    static <K, V> Interned<K, V> held() {
        return new Interned<K, V>(new LinkedHashMap<K, Object>(), false, null);
    }

    /**
     * <b>The interned value for {@code key}</b> — a hit, or what {@code mint} makes, inserted. The whole of
     * the interning promise: two calls with one key are one object, from any thread, and a mint that
     * answers {@code null} is not cached (a read that found nothing must be free to find something later).
     */
    synchronized V of(K key, Supplier<V> mint) {
        drain();
        V v = value(live.get(key));
        if(v != null)
            return v;
        v = mint.get();
        if(v != null)
            live.put(key, hold(key, v));
        return v;
    }

    /** What is interned for {@code key}, or {@code null} — the read that mints nothing. */
    synchronized V get(K key) {
        drain();
        return value(live.get(key));
    }

    /**
     * <b>Retire one entry</b>, and hand back what it held so the caller can end it outside this lock (that
     * is the whole reason this is not {@code void}: a {@code WidgetSubs} deafens engine listeners as it
     * goes, which is not work to do under a cache's monitor).
     */
    synchronized V drop(K key) {
        drain();
        V v = value(live.remove(key));
        return v;
    }

    /** Retire one entry and say nothing about it — {@link #drop} where the caller wants no value back. */
    synchronized void remove(K key) {
        drain();
        live.remove(key);
    }

    /** Retire the lot — teardown. What each value needs done to it is the caller's, over {@link #values}. */
    synchronized void clear() {
        live.clear();
        drain();
    }

    /**
     * <b>Move an entry to the key that replaced it</b> and hand back the value that moved, or {@code null}
     * where there was nothing to move. One caller: the face setter's rebuild ({@link UiApi#rebuild}), where
     * the widget under a handle Lua is holding is swapped mid-statement — moving the entry is what keeps a
     * fresh lookup of the new widget from minting a second object for what is still one widget.
     */
    synchronized V rekey(K from, K to) {
        drain();
        V v = value(live.remove(from));
        if(v != null)
            live.put(to, hold(to, v));
        return v;
    }

    /**
     * Everything interned here right now, in insertion order where the map keeps one — the members of a
     * collection whose identity IS this cache ({@code hafen.font():list()}), and what a teardown walks.
     * Sweeps the entries whose value has gone as it reads them.
     */
    synchronized List<V> values() {
        drain();
        List<V> out = new ArrayList<V>(live.size());
        for(Iterator<Map.Entry<K, Object>> it = live.entrySet().iterator(); it.hasNext(); ) {
            V v = value(it.next().getValue());
            if(v == null)
                it.remove();
            else
                out.add(v);
        }
        return out;
    }

    /**
     * The keys interned here right now, as a snapshot — what a <b>backstop sweep</b> walks ({@code UiApi.prune},
     * where a whole tree died and no disposal drain reached its widgets). A snapshot rather than a live view,
     * because what the caller does with a key is come back here and {@link #drop} it.
     */
    synchronized List<K> keys() {
        drain();
        return new ArrayList<K>(live.keySet());
    }

    /**
     * The key {@code v} is interned under, or {@code null} — the one reverse question a caller asks (what
     * a built-in font is called, for the string filter on that collection). By identity, never by
     * {@code equals}: the value IS the answer's subject, not a copy of it.
     */
    synchronized K keyOf(V v) {
        drain();
        for(Map.Entry<K, Object> e : live.entrySet()) {
            if(value(e.getValue()) == v)
                return e.getKey();
        }
        return null;
    }

    /** An entry, at this cache's value strength. */
    private Object hold(K key, V v) {
        return weakValues ? new Ref<K, V>(v, (dead == null) ? null : key, dead) : (Object)v;
    }

    /** What an entry holds, or {@code null} once a weak one has been collected. */
    @SuppressWarnings("unchecked")
    private V value(Object o) {
        if(o == null)
            return null;
        return weakValues ? ((Ref<K, V>)o).get() : (V)o;
    }

    /** Drop the entries whose value Lua has released — the key and the dead reference would leak otherwise. */
    @SuppressWarnings("unchecked")
    private void drain() {
        if(dead == null)
            return;
        Reference<? extends V> r;
        while((r = dead.poll()) != null) {
            Ref<K, V> gone = (Ref<K, V>)r;
            if(live.get(gone.key) == gone)     // not already replaced by a fresh value for the same key
                live.remove(gone.key);
        }
    }

    /**
     * A weak value that remembers its map key, so the {@link ReferenceQueue} drain can unmap it. The key is
     * {@code null} where the map's own keys are weak: a strong reference to it here would pin the very key
     * the {@code WeakHashMap} is there to let go.
     */
    private static final class Ref<K, V> extends WeakReference<V> {
        final K key;

        Ref(V v, K key, ReferenceQueue<V> q) {
            super(v, q);
            this.key = key;
        }
    }
}
