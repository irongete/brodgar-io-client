package io.brodgar.addon;

import haven.UI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * The <b>grab</b> — {@code hafen.ui():mouse():grab()}'s Lua handle (spec {@code 041-unified-events} §2.2),
 * replacing {@code hafen.hook():grab{move=fn, up=fn}}:
 *
 * <pre>
 *   local g = hafen.ui():mouse():grab()   -- bare; no config table (design/25 R4 — a builder is bare)
 *   g:on("Move", function(ev) end)        -- ev:x() ev:y() ev:shift() ev:ctrl() ev:alt()
 *   g:on("Up",   function(ev) end)        -- …plus ev:button(); fires once and auto-releases
 *   g:release()                           -- end it early
 * </pre>
 *
 * <p><b>An emitter, not a config table.</b> A grab constructs something with a lifetime — you hold it and
 * {@code :release()} it — so R4 of <a href="../../../../specs/addons/design/25-uniform-api.md">design/25</a>
 * applies squarely: no {@code opts} table survives on anything that is constructed. And once it is a thing
 * you hold, R2 answers the address: <i>¿tienes el objeto? {@code obj:on(...)}</i>. Its {@code :on(key, fn)} is
 * the SAME door every other emitter answers through — {@link LuaMouseGrab#subs} is a plain {@link Subs}, so
 * {@code :on} hands back the ordinary {@link LuaSub} and {@code sub:off()} works exactly as it does anywhere
 * else. The key set is CLOSED to {@code "Move"}/{@code "Up"} (D-125): anything else throws naming them.
 *
 * <p><b>The widget does the capturing; this is only the Lua-facing wrapper</b> (041.5 split it out of
 * {@link LuaMouseGrab}, which used to hold two bare callbacks and call {@link AddonManager#callLua} itself).
 * Per-addon shared metatable, like {@link LuaSub}/{@link LuaEvent}: the methods resolve {@code self} by
 * {@code touserdata()} rather than by closing over one instance, so minting a grab costs one userdata and
 * nothing else.
 */
public final class LuaGrab {
    /** The widget half — arm/release, the engine listener, and the {@link Subs} this delegates every verb to. */
    private final LuaMouseGrab widget;
    /** This grab as Lua holds it — minted once, handed back by identity. */
    private LuaValue self;

    private LuaGrab(LuaMouseGrab widget) {
        this.widget = widget;
    }

    /** {@code tostring(g)} → {@code Grab}, or {@code Grab(released)} once it has ended. */
    public String toString() {
        return "Grab" + (widget.alive ? "" : "(released)");
    }

    /**
     * {@code hafen.ui():mouse():grab()} — mint the capturing widget, arm it on the ADDON LAYER's own root, and
     * hand back its Lua wrapper. {@code nil} if the layer is not up yet (there is nothing to capture on).
     *
     * <p><b>The layer, not the session on screen</b> (audit2 B01). A grab is the addon's own thing and
     * {@link AddonManager#layer()} promises exactly this — "a window in it keeps its place, its focus and any
     * grab it holds across a character switch, because nothing about it moves" — where a grab armed on the
     * drawn session's tree was stranded in a tree that is no longer drawn the moment the player tabbed. The
     * layer is offered every pointer event before the session beneath it, and both trees are resized to the
     * same screen, so the grab captures exactly what it captured before and reports the same coordinates.
     */
    static LuaValue create(Addon owner) {
        UI u = AddonManager.layer();
        if((u == null) || (u.root == null))
            return LuaValue.NIL;
        LuaMouseGrab g = new LuaMouseGrab(owner);
        owner.mouseGrabs.add(g);
        u.root.add(g);           // add() synchronizes on ui; visible -> receives broadcast moves
        g.arm(u);                // ui.grabmouse(this) — capture the terminating up wherever it lands
        return new LuaGrab(g).handle(owner);
    }

    /** Release every active grab this addon owns (teardown on reload/disable) — the Lua surface's own sweep,
     * over the same {@link Addon#mouseGrabs} list the widget half always kept. {@code a} is {@code null} when
     * the {@code :lua} REPL owner has never been minted (the console was never used this session) — every
     * sibling teardown call in {@code AddonRegistry.reload()} already guards that; this one did not. */
    static void teardownGrabs(Addon a) {
        if(a == null)
            return;
        for(LuaMouseGrab g : a.mouseGrabs)
            g.release();               // drops the UI.Grab + marks dead; the widget unlinks on its next (or the last) tick
        a.mouseGrabs.clear();
    }

    /** This grab as Lua holds it, minted on the first ask. */
    private LuaValue handle(Addon owner) {
        if(self == null)
            self = LuaValue.userdataOf(this, meta(owner));
        return self;
    }

    /** The receiver of a colon call, or the error that says a dot call passed the wrong self. */
    private static LuaGrab self(LuaValue v, String method) {
        LuaGrab g = null;
        if((v != null) && v.isuserdata()) {
            Object o = v.touserdata();
            if(o instanceof LuaGrab)
                g = (LuaGrab)o;
        }
        if(g == null)
            throw new LuaError("grab:" + method + "() — use a COLON call on the grab hafen.ui():mouse():grab()"
                + " handed back (grab:" + method + "(…))");
        return g;
    }

    /**
     * The per-addon metatable, built once and cached on the {@link Addon} — per addon like every other
     * metatable in the bridge (D-017). The key set on {@code :on} is closed, listed in the refusal.
     */
    private static LuaValue meta(Addon owner) {
        if(owner.grabMeta != null)
            return owner.grabMeta;
        LuaTable m = new LuaTable();
        m.set("on", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaGrab g = self(a.arg1(), "on");
                LuaValue keyArg = Args.required(a, 2, "grab:on", "key");
                LuaValue fnArg = Args.required(a, 3, "grab:on", "fn");
                if((keyArg.type() != LuaValue.TSTRING) || !fnArg.isfunction())   // the TYPE: 42 answers isstring()
                    throw new LuaError("grab:on(key, fn) expects (string, function)");
                String key = keyArg.tojstring();
                if(!"Move".equals(key) && !"Up".equals(key))
                    throw new LuaError("grab:on(key, fn): a grab has no event '" + key + "' — it has: Move, Up");
                return g.widget.subs.on(key, fnArg);
            }
        });
        m.set("release", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                self(a.arg1(), "release").widget.release();
                return a.arg1();          // the receiver: every ending chains
            }
        });
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("grab", m,
            "a grab"));
        mt.set("__name", LuaValue.valueOf("Grab"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue v) {
                LuaGrab g = ((v != null) && v.isuserdata() && (v.touserdata() instanceof LuaGrab))
                    ? (LuaGrab)v.touserdata() : null;
                return LuaValue.valueOf((g == null) ? "Grab(?)" : g.toString());
            }
        });
        owner.grabMeta = mt;
        return mt;
    }
}
