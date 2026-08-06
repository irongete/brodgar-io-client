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

/**
 * A <b>client-side</b> {@link Widget} whose lifecycle callbacks forward to an addon's Lua functions —
 * the Java half of {@code hafen.ui():widget()} / {@code hafen.ui():window()} (spec
 * {@code 07-ui-and-drawing.md}, Phase 2a). It is a leaf widget: {@link #draw} and {@link #tick} call the
 * addon's matching callback ({@code onDraw}/{@code onTick}) through {@link AddonManager#callLua}, so every
 * forward is <b>watchdog-armed</b> (D-018 layer 1), <b>error-isolated</b> (a Lua error is logged, never
 * thrown into the render/tick loop), and CPU-accounted exactly like an event handler. <b>Mouse input is not
 * one of these slots</b> (041.3): {@code MouseDown}/{@code MouseUp}/{@code MouseMove}/{@code Wheel} reach an
 * AddonWidget the same way they reach any other widget, through {@code widget:on(key, fn)} and the
 * {@link Widget#listen} pre-hook {@link WidgetSubs} installs — one door for a widget you built and one you
 * merely found, which a fixed callback slot could never be.
 *
 * <p><b>Built bare, and every callback is a setter</b> (spec {@code 039-uniform-api} §2.5). The thirteen
 * keys of the old {@code opts} table are chained setters on the Widget entity, so a callback is no longer
 * fixed at construction: the slots live in a <b>copy-on-write volatile array</b> because the tick and draw
 * passes read them while Lua writes them, and one volatile reference is cheaper to get right than eight
 * volatile fields.
 *
 * <p><b>Attached INERT until its arming tick</b> (§2.5, and D-112 applied one level up). A bare widget exists
 * for the length of the statement that builds it, with the client's own defaults and no title. It is in the
 * tree from the first instant — so every lookup, {@code hafen.ui():at(x, y)} included, answers on it exactly
 * as it did when the constructor took a table — but it <b>does not draw</b> until {@link UiApi#armPending()}
 * arms it on the next {@link AddonManager#tick}. Holding it out of the tree instead was tried and is worse:
 * it would make "find the widget I just built" quietly stop working, which is a capability, to buy a
 * guarantee about painting that skipping the draw already gives. A window's chrome is skipped the same way,
 * by an anonymous {@link haven.Window} subclass built in {@link UiApi} — anonymous so {@code w:type()} still
 * climbs to {@code Window} and every selector that names one keeps matching.
 *
 * <p><b>Not bound to a server id</b> — an AddonWidget cannot {@code wdgmsg} the server (its
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
 * <p><b>Drop target (D-038).</b> An AddonWidget {@link DropTarget implements DropTarget}, so the client's own
 * drag gesture can drop a "thing" onto it: the engine walks the widget tree and calls {@link #dropthing}
 * on the first target under the cursor. v1 delivers a menu-grid action ({@link MenuGrid.Pagina}) as the
 * <b>neutral descriptor</b> {@code {kind="pagina", res="<name>"}} to the addon's {@code onDrop(x, y, drop)}
 * callback (widget-local px); a truthy return consumes the drop. A resource name is plain data (already all
 * over the read API), so this stays <b>ungated</b>; firing the dropped action is out of scope (the deferred
 * menu-ability primitive).
 *
 * <p><b>D-040's per-callback {@code mods} table is retired with the slots it rode on</b> (041.3): the input
 * {@code ev} {@code widget:on("MouseDown"/…, fn)} hands over does not carry modifier state (EXAMPLES §1.1) —
 * a later task in this feature puts it on the mouse entity instead, readable at any time rather than only
 * from inside a callback that happened to be handed it.
 *
 * <p><b>{@link Owned} since 040.1, and nothing here changed to say so.</b> Provenance used to be <i>is this
 * widget an {@code AddonWidget} of mine?</i>; it is now <i>does this widget carry the ownership contract?</i>,
 * and this class already had every method the contract asks for. What that buys is a second kind of owned
 * widget — a {@code haven} control an addon built ({@link CtlButton} and its siblings) — answering the owned
 * verbs without this one growing a wrapper role it should not have.
 */
final class AddonWidget extends Widget implements DropTarget, Owned {
    /**
     * The callback setters, in slot order — the Lua verb names, which are also the keys the retired
     * {@code opts} table used. {@link #slot(String)} is the only mapping between the two.
     */
    static final String[] CALLBACKS = {
        "onDraw", "onTick", "onDrop", "onClose",
    };
    static final int ON_DRAW = 0, ON_TICK = 1, ON_DROP = 2, ON_CLOSE = 3;

    private final Addon owner;
    /** The callback slots. Copy-on-write: the draw/tick passes read this reference, Lua setters replace it. */
    private volatile LuaValue[] cb = new LuaValue[CALLBACKS.length];
    private volatile FontHandle defaultFont;       // :font(h) — the default font for this widget's g:text draws
    private volatile LuaValue fontVal = LuaValue.NIL;   // ...and the handle itself, so :font() reads back what was set
    private final LuaGOut gwrap = new LuaGOut();   // the shared GOut draw wrapper `g`, bound per draw
    private Widget root = this;     // the widget to destroy on kill(): the window chrome, or this
    private boolean dead;           // set on teardown so a late tick/draw callback is a no-op
    private volatile boolean pending = true;   // built, not yet drawing — armed on the next AddonManager tick

    AddonWidget(Addon owner, Coord sz) {
        super(sz);
        this.owner = owner;
    }

    /** The slot of a callback verb, or {@code -1} — the one place the four names are matched. */
    static int slot(String name) {
        for(int i = 0; i < CALLBACKS.length; i++) {
            if(CALLBACKS[i].equals(name))
                return i;
        }
        return -1;
    }

    /** The function in a slot, or {@code null} — what {@code w:onDraw()} reads back. */
    LuaValue callback(int i) {
        return cb[i];
    }

    /** Install a callback ({@code w:onDraw(fn)}). Copy-on-write, so a reader never sees a torn array. */
    void callback(int i, LuaValue fn) {
        LuaValue[] n = cb.clone();
        n[i] = fn;
        cb = n;
    }

    /** The widget's default font handle as Lua set it ({@code w:font()}), and the resolved half behind it. */
    LuaValue font() {
        return fontVal;
    }

    void font(LuaValue h) {
        this.defaultFont = FontHandle.resolve(h);   // a non-handle resolves to null: the stock font
        this.fontVal = h;
    }

    // ---------------------------------------------------------------- pending: built, not yet drawing

    /** Is this widget still waiting for its arming tick? (Built and in the tree, but painting nothing.) */
    public boolean pending() {
        return pending;
    }

    /** Cleared by {@link UiApi#armPending()} on the first tick after the statement that built it. */
    public void armed() {
        this.pending = false;
    }

    /**
     * The addon this widget belongs to — the owner attribution behind {@code p:widgets()} (019.5). An AddonWidget
     * is the only widget in the tree that knows which addon put it there, which is what lets the same cost
     * appear itemised in {@code :widgets()} and rolled up in that addon's {@code :addons()} row instead of the
     * two views competing.
     */
    public Addon profOwner() {
        return owner;
    }

    /** Record the top-level widget that owns this content (a window's chrome) so {@link #kill} removes it. */
    void root(Widget root) {
        this.root = (root != null) ? root : this;
    }

    /**
     * The top-level widget this content lives under — the window chrome, or this widget itself for a bare
     * {@code hafen.ui():widget()}. It is the widget the Lua entity is interned on (029.2), so
     * {@link LuaWidget#ownedContent} recognises an OWNED entity by matching it: {@code w} is owned by an addon
     * exactly when {@code w} is (or directly contains) that addon's content whose {@code root} is {@code w}.
     * Derived, not stored on the handle — the intern cache is weak on both axes, so a re-minted entity must be
     * able to rediscover its own provenance.
     */
    public Widget rootw() {
        return root;
    }

    /** The content widget itself ({@link Owned#widget()}) — for a surface, this very widget. */
    public Widget widget() {
        return this;
    }

    /** Already torn down? (guards a double kill from close-button + teardown.) */
    public boolean dead() {
        return dead;
    }

    /** Mark torn-down (no further callbacks) and remove this widget's root from the tree. Bridge-only. */
    public void kill() {
        if(dead)
            return;
        dead = true;
        pending = false;
        root.destroy();
    }

    // ---------------------------------------------------------------- lifecycle forwards

    public void tick(double dt) {
        super.tick(dt);
        LuaValue fn = cb[ON_TICK];
        if(!dead && (fn != null))
            AddonManager.callLua(owner, Addon.C_WIDGET, fn, LuaValue.valueOf(dt));
    }

    public void draw(GOut g) {
        if(pending)     // built this frame and not armed yet: a half-configured widget paints NOTHING (§2.5)
            return;
        LuaValue fn = cb[ON_DRAW];
        if(!dead && (fn != null)) {
            LuaTable gt = gwrap.bind(g, owner, defaultFont);   // F2: g:text with no per-call font uses this widget's :font()
            try {
                AddonManager.callLua(owner, Addon.C_DRAW, fn, gt, LuaValue.valueOf(sz.x), LuaValue.valueOf(sz.y));
            } finally {
                gwrap.unbind();   // invalidate the wrapper outside the callback (no stashing)
            }
        }
        super.draw(g);   // draw any child widgets (none for a leaf; future-proofing)
    }

    // mousedown/mouseup/mousemove/mousewheel are GONE (041.3): the four input keys are now delivered through
    // the SAME door every widget answers them on — widget:on("MouseDown"/…, fn), a Widget.listen pre-hook
    // installed by WidgetSubs — rather than a slot only an AddonWidget had. Widget's own defaults (false / a
    // no-op) apply here now, which is correct: a leaf with no children has nothing else to do with an event
    // its listener did not consume.

    /**
     * The chrome close button ({@code :onClose(fn)}). Wired once by the builder, but read here, so a handler
     * installed after the window was built is the one that runs.
     */
    void closed() {
        LuaValue fn = cb[ON_CLOSE];
        if(fn != null)
            AddonManager.callLua(owner, Addon.C_WIDGET, fn);
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
        LuaValue fn = cb[ON_DROP];
        if(dead || (fn == null))
            return false;
        LuaValue drop = dropDescriptor(thing);
        if(drop == null)
            return false;   // not a kind we deliver → let the engine dispatch it elsewhere
        return AddonManager.callLua(owner, Addon.C_WIDGET, fn, ci(cc.x), ci(cc.y), drop).arg1().toboolean();
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

    private static LuaValue ci(int v) {
        return LuaValue.valueOf(v);
    }
}
