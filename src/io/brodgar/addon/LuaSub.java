package io.brodgar.addon;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * A <b>Sub object</b> — what {@code X:on(key, fn)} hands back, on every emitter in the API (spec
 * {@code 041-unified-events} §R1):
 *
 * <pre>
 *   local sub = hafen.event():on("GobAdded", function(gob) end)
 *   sub:off()                                  -- idempotent
 * </pre>
 *
 * <p><b>One verb, and one spelling of it.</b> Before 041 an addon ended a subscription three ways
 * ({@code sub:off()}, {@code handle:remove()}, or by overwriting a single callback slot); {@code :off()} is
 * the one left, wherever the subscription came from. Since 086.1 that is <i>every</i> {@code :on} in the API:
 * a slash command, a hotkey and a selector watch are subscriptions too, and each hands one of these back.
 *
 * <p><b>{@code :key()} says what it was registered under</b> — a bus event, a message name, a widget key, a
 * command name, a hotkey's name, {@code "appear"}. It is the {@link #key} field a {@link Subs} already
 * addressed this sub by, and it is what makes a set of subscriptions filterable.
 *
 * <p><b>The handle IS the entry.</b> This object is both what Lua holds and what {@link Subs} stores in its
 * handler list, so removal is by identity from a copy-on-write list — which makes {@code :off()} idempotent
 * <i>by construction</i> rather than by a flag: a second call, or one after the emitter died, finds nothing
 * and returns. The {@link #alive} flag is the other half, for the fire that is already walking its snapshot
 * when the sub ends: it is skipped rather than called.
 *
 * <p><b>Nothing to remember on teardown.</b> A subscription is owned by its addon and the whole {@link Subs}
 * is dropped on {@code :reload}/disable, so an addon never has to unsubscribe by hand — the same P2 contract
 * every other owned resource has.
 */
public final class LuaSub {
    /** The emitter this subscription lives in — where {@code :off()} removes it from. */
    private final Subs subs;
    /** The key it is on ({@code "GobAdded"}), which is also its address inside {@link Subs}. */
    final String key;
    /** The Lua handler. */
    final LuaValue fn;
    /**
     * Live until {@code :off()} or teardown. Read by the fire loop, which may be walking a snapshot that
     * still contains a sub that has just ended (a handler unsubscribing another one, or itself).
     */
    volatile boolean alive = true;
    /**
     * What the emitter registered <b>alongside</b> this subscription — a {@link LuaSelectorWatch}, a
     * {@link LuaSlashCommand}, a {@link LuaKeyBind} — or {@code null} on the bus and the two streams, where a
     * subscription is the whole of what was registered (086.1). Opaque here: only the {@link Subs} that made
     * it reads it back, from its {@link Subs.Ended} hook.
     */
    Object tag;
    /** This object as Lua holds it — minted once, handed back by identity. */
    private LuaValue self;

    LuaSub(Subs subs, String key, LuaValue fn) {
        this.subs = subs;
        this.key = key;
        this.fn = fn;
    }

    /** {@code tostring(sub)} → {@code Sub(GobAdded)}. */
    public String toString() {
        return "Sub(" + key + (alive ? "" : ", off") + ")";
    }

    /** This subscription as Lua holds it, minted on the first ask (from {@link Subs#on}). */
    LuaValue handle() {
        if(self == null)
            self = LuaValue.userdataOf(this, meta(subs.owner));
        return self;
    }

    /** The {@code LuaSub} behind a Lua value, or {@code null} for anything that is not one. */
    static LuaSub resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaSub) ? (LuaSub)o : null;
    }

    /** The receiver of a colon call, or the error that says a dot call passed the wrong self. */
    private static LuaSub self(LuaValue v, String method) {
        LuaSub s = resolve(v);
        if(s == null)
            throw new org.luaj.vm2.LuaError("sub:" + method + "() — use a COLON call on the subscription"
                + " X:on(key, fn) handed back (sub:" + method + "())");
        return s;
    }

    /**
     * The per-addon metatable, built once and cached on the {@link Addon} — per addon like every other
     * metatable in the bridge, so no Lua value crosses a sandbox boundary (D-017). A Sub's vocabulary is
     * CLOSED (one verb), so an unknown one throws naming it rather than reading {@code nil}.
     */
    private static LuaValue meta(Addon owner) {
        if(owner.subMeta != null)
            return owner.subMeta;
        LuaTable m = new LuaTable();
        m.set("off", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaSub s = self(a.arg1(), "off");
                s.alive = false;
                s.subs.off(s);
                return LuaValue.NIL;
            }
        });
        // key() — what this subscription was registered UNDER: a bus event name, a message name, a widget
        // key, and since 086.1 a slash command's name, a hotkey's name and a selector watch's event. One
        // question with one answer, wherever the subscription came from — and what a collection of
        // subscriptions filters on.
        m.set("key", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), "key").key);
            }
        });
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("sub", m,
            "a subscription answers :off() (idempotent) and :key() (what it was registered under)"));
        mt.set("__name", LuaValue.valueOf("Sub"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue v) {
                LuaSub s = resolve(v);
                return LuaValue.valueOf((s == null) ? "Sub(?)" : s.toString());
            }
        });
        owner.subMeta = mt;
        return mt;
    }
}
