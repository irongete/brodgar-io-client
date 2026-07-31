package io.brodgar.addon;

import haven.Coord;
import haven.DropTarget;
import haven.GOut;
import haven.Indir;
import haven.MenuGrid;
import haven.Resource;
import haven.Widget;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * A <b>client-side</b> {@link Widget} whose lifecycle callbacks forward to an addon's Lua functions —
 * the Java half of {@code hafen.ui.widget} / {@code hafen.ui.window} (spec {@code 07-ui-and-drawing.md},
 * Phase 2a). It is a leaf widget: {@link #draw}, {@link #tick}, and the mouse handlers each call the
 * addon's matching callback ({@code onDraw}/{@code onTick}/{@code onClick}/{@code onMouseUp}/
 * {@code onMouseMove}/{@code onWheel}) through {@link AddonManager#callLua}, so every forward is
 * <b>watchdog-armed</b> (D-018 layer 1), <b>error-isolated</b> (a Lua error is logged, never thrown into
 * the render/tick loop), and CPU-accounted exactly like an event handler.
 *
 * <p><b>Not bound to a server id</b> — a LuaWidget cannot {@code wdgmsg} the server (its
 * {@code wdgmsg} falls through to {@code ui.root} and is dropped). That is correct for custom UI; game
 * interaction goes through {@code hafen.act} (Phase 4). The addon never sees this object: it holds an
 * opaque handle built in {@link AddonManager}, and the bridge owns the widget for teardown
 * (registered in {@link Addon}'s owned-resource registry, destroyed on reload/disable, principle P2).
 *
 * <p>The draw callback receives {@link #gwrap}, the shared {@link LuaGOut} wrapper over the live
 * {@link GOut} — bound for the duration of {@code onDraw} and inert otherwise, so an addon that stashes
 * {@code g} and uses it later cannot corrupt the client's draw pipeline. Coordinates are the widget's own
 * pixel space (top-left = {@code 0,0}); {@code onDraw(g, w, h)} gets the widget size. UI scaling
 * ({@code UI.scale}) is <b>not</b> applied in 2a — sizes and draw coords are raw pixels (a later slice may
 * add a scale option).
 *
 * <p><b>Drop target (D-038).</b> A LuaWidget {@link DropTarget implements DropTarget}, so the client's own
 * drag gesture can drop a "thing" onto it: the engine walks the widget tree and calls {@link #dropthing}
 * on the first target under the cursor. v1 delivers a menu-grid action ({@link MenuGrid.Pagina}) as the
 * <b>neutral descriptor</b> {@code {kind="pagina", res="<name>"}} to the addon's {@code onDrop(x, y, drop)}
 * callback (widget-local px); a truthy return consumes the drop. A resource name is plain data (already all
 * over the read API), so this stays <b>ungated</b>; firing the dropped action is out of scope (the deferred
 * menu-ability primitive).
 *
 * <p><b>Modifiers (D-040).</b> Each mouse callback carries a trailing {@code mods = {shift, ctrl, alt}}
 * table (from {@code ui.modflags()}, via {@link AddonManager#modsTable}) so an addon can branch on the
 * modifier state at press time (e.g. Shift+drag). Additive and back-compatible — a handler that ignores
 * the extra argument is unaffected.
 */
public final class LuaWidget extends Widget implements DropTarget {
    private final Addon owner;
    private final LuaValue onDraw, onTick, onClick, onMouseUp, onMouseMove, onWheel, onDrop;
    private final FontHandle defaultFont;          // F2: opts.font — the default font for this widget's g:text draws
    private final LuaGOut gwrap = new LuaGOut();   // the shared GOut draw wrapper `g`, bound per draw
    private Widget root = this;     // the widget to destroy on kill(): the window chrome, or this
    private boolean dead;           // set on teardown so a late tick/draw callback is a no-op

    LuaWidget(Addon owner, Coord sz, LuaValue opts) {
        super(sz);
        this.owner = owner;
        this.onDraw      = fn(opts, "onDraw");
        this.onTick      = fn(opts, "onTick");
        this.onClick     = fn(opts, "onClick");
        this.onMouseUp   = fn(opts, "onMouseUp");
        this.onMouseMove = fn(opts, "onMouseMove");
        this.onWheel     = fn(opts, "onWheel");
        this.onDrop      = fn(opts, "onDrop");
        this.defaultFont = FontHandle.resolve(opts.get("font"));   // F2: nil/typo/non-handle => null (stock font)
    }

    /**
     * The addon this widget belongs to — the owner attribution behind {@code p:widgets()} (019.5). A LuaWidget
     * is the only widget in the tree that knows which addon put it there, which is what lets the same cost
     * appear itemised in {@code :widgets()} and rolled up in that addon's {@code :addons()} row instead of the
     * two views competing.
     */
    Addon profOwner() {
        return owner;
    }

    /** An optional callback from the opts table, or {@code null} if the key is absent / not a function. */
    private static LuaValue fn(LuaValue opts, String key) {
        LuaValue v = opts.get(key);
        return v.isfunction() ? v : null;
    }

    /** Record the top-level widget that owns this content (a window's chrome) so {@link #kill} removes it. */
    void root(Widget root) {
        this.root = (root != null) ? root : this;
    }

    /** Already torn down? (guards a double kill from close-button + teardown.) */
    boolean dead() {
        return dead;
    }

    /** Mark torn-down (no further callbacks) and remove this widget's root from the tree. Bridge-only. */
    void kill() {
        if(dead)
            return;
        dead = true;
        root.destroy();
    }

    // ---------------------------------------------------------------- lifecycle forwards

    public void tick(double dt) {
        super.tick(dt);
        if(!dead && (onTick != null))
            AddonManager.callLua(owner, Addon.C_WIDGET, onTick, LuaValue.valueOf(dt));
    }

    public void draw(GOut g) {
        if(!dead && (onDraw != null)) {
            LuaTable gt = gwrap.bind(g, defaultFont);   // F2: g:text with no per-call font uses this widget's font=
            try {
                AddonManager.callLua(owner, Addon.C_DRAW, onDraw, gt, LuaValue.valueOf(sz.x), LuaValue.valueOf(sz.y));
            } finally {
                gwrap.unbind();   // invalidate the wrapper outside the callback (no stashing)
            }
        }
        super.draw(g);   // draw any child widgets (none for a leaf; future-proofing)
    }

    public boolean mousedown(MouseDownEvent ev) {
        if(!dead && (onClick != null)
           && AddonManager.callLua(owner, Addon.C_WIDGET, onClick, ci(ev.c.x), ci(ev.c.y), ci(ev.b), mods()).arg1().toboolean())
            return true;   // a truthy return consumes the click (preventDefault)
        return super.mousedown(ev);
    }

    public boolean mouseup(MouseUpEvent ev) {
        if(!dead && (onMouseUp != null)
           && AddonManager.callLua(owner, Addon.C_WIDGET, onMouseUp, ci(ev.c.x), ci(ev.c.y), ci(ev.b), mods()).arg1().toboolean())
            return true;
        return super.mouseup(ev);
    }

    public void mousemove(MouseMoveEvent ev) {
        super.mousemove(ev);
        if(!dead && (onMouseMove != null))
            AddonManager.callLua(owner, Addon.C_WIDGET, onMouseMove, ci(ev.c.x), ci(ev.c.y), mods());
    }

    public boolean mousewheel(MouseWheelEvent ev) {
        if(!dead && (onWheel != null)
           && AddonManager.callLua(owner, Addon.C_WIDGET, onWheel, ci(ev.c.x), ci(ev.c.y), ci(ev.a), mods()).arg1().toboolean())
            return true;
        return super.mousewheel(ev);
    }

    // ---------------------------------------------------------------- drop target (D-038)

    /**
     * A "thing" was dropped over this widget (the engine's own drag-drop dispatch). {@code cc} is
     * widget-local (the {@link DropTarget.Drop} event derives child-local coords as it propagates). v1
     * delivers a menu-grid {@link MenuGrid.Pagina} as a neutral descriptor; a truthy Lua return consumes
     * it. Any other kind of thing (e.g. an inventory item, a different path) returns {@code false} so the
     * engine keeps looking for a handler.
     */
    public boolean dropthing(Coord cc, Object thing) {
        if(dead || (onDrop == null))
            return false;
        LuaValue drop = dropDescriptor(thing);
        if(drop == null)
            return false;   // not a kind we deliver → let the engine dispatch it elsewhere
        return AddonManager.callLua(owner, Addon.C_WIDGET, onDrop, ci(cc.x), ci(cc.y), drop).arg1().toboolean();
    }

    /**
     * Build the neutral drop descriptor for {@code onDrop} (D-038), or {@code null} for a thing v1 does not
     * deliver. A menu-grid action &rarr; {@code {kind="pagina", res="<name>"}}. The {@code res} is included
     * only for a <b>resource-based</b> pagina (its {@code id} is the resource {@link Indir} itself) and only
     * once resolved — an id-only pagina ({@code fl&2}) has no stable resource name, so it carries {@code kind}
     * alone (usable in-session, not reliably persistable). Loading is swallowed (res absent until ready).
     */
    private static LuaValue dropDescriptor(Object thing) {
        if(thing instanceof MenuGrid.Pagina) {
            MenuGrid.Pagina pag = (MenuGrid.Pagina)thing;
            LuaTable d = new LuaTable();
            d.set("kind", LuaValue.valueOf("pagina"));
            if(pag.id instanceof Indir) {          // resource-based (stable) vs. id-only (no stable res name)
                String nm = resName(pag.res);
                if(nm != null)
                    d.set("res", LuaValue.valueOf(nm));
            }
            return d;
        }
        return null;
    }

    /** The resource name of an {@code Indir<Resource>}, or {@code null} (Loading / unresolved / null). */
    private static String resName(Indir<Resource> res) {
        try {
            Resource r = (res == null) ? null : res.get();
            return (r == null) ? null : r.name;
        } catch(RuntimeException e) {   // Loading etc.
            return null;
        }
    }

    /** The {@code {shift,ctrl,alt}} modifier table at callback time (empty if no UI is attached yet). */
    private LuaTable mods() {
        return AddonManager.modsTable((ui != null) ? ui.modflags() : 0);
    }

    private static LuaValue ci(int v) {
        return LuaValue.valueOf(v);
    }
}
