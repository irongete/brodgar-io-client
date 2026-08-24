package io.brodgar.addon;

import haven.Coord;
import haven.DropTarget;
import haven.Fonts;
import haven.GOut;
import haven.Indir;
import haven.MenuGrid;
import haven.Resource;
import haven.Widget;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * A <b>client-side</b> {@link Widget} whose lifecycle notifications forward to an addon's Lua functions —
 * the Java half of {@code hafen.ui():widget()} / {@code hafen.ui():window()} (spec
 * {@code 07-ui-and-drawing.md}, Phase 2a). It is a leaf widget: {@link #draw} and {@link #tick} fire
 * {@code "Draw"}/{@code "Update"} on this widget's own {@link WidgetSubs} (041.4) — the SAME address every
 * OTHER key answers, {@code widget:on(key, fn)}, over {@link AddonManager#callLua} underneath, so every
 * forward is <b>watchdog-armed</b> (D-018 layer 1), <b>error-isolated</b> (a Lua error is logged, never
 * thrown into the render/tick loop), CPU-accounted, and — unlike the single slot this used to be — answers
 * N SUBSCRIBERS, in registration order (R1). <b>Mouse input is not one of these keys either</b> (041.3):
 * {@code MouseDown}/{@code MouseUp}/{@code MouseMove}/{@code Wheel} reach an AddonWidget the same way they
 * reach any other widget, through the {@link Widget#listen} pre-hook {@link WidgetSubs} installs — one door
 * for a widget you built and one you merely found, which a fixed callback slot could never be.
 *
 * <p><b>Nothing is stored on this class any more</b> (041.4). Every one of {@code "Draw"}/{@code "Update"}/
 * {@code "Drop"}/{@code "Close"} used to be a chained-setter slot in a copy-on-write array; now each fire
 * looks its {@link WidgetSubs} up WITHOUT minting one ({@link Addon#widgetSubsOrNull}), so a widget nobody
 * subscribed to costs one map lookup a tick/draw and nothing else — the same {@code hasSub} gate every other
 * emitter in the API has.
 *
 * <p><b>Attached INERT until its arming tick</b> (§2.5, and D-112 applied one level up). A bare widget exists
 * for the length of the statement that builds it, with the client's own defaults and no title. It is in the
 * tree from the first instant — so every lookup, {@code hafen.ui():hit(x, y)} included, answers on it exactly
 * as it did when the constructor took a table — but it <b>does not draw</b> until {@link UiApi#armPending()}
 * arms it on the next {@link AddonManager#tick}. Holding it out of the tree instead was tried and is worse:
 * it would make "find the widget I just built" quietly stop working, which is a capability, to buy a
 * guarantee about painting that skipping the draw already gives. A window's chrome is skipped the same way,
 * by an anonymous {@link haven.Window} subclass built in {@link UiApi} — anonymous so {@code w:type()} still
 * climbs to {@code Window} and every selector that names one keeps matching.
 *
 * <p><b>Not bound to a server id</b> — an AddonWidget cannot {@code wdgmsg} the server (its
 * {@code wdgmsg} falls through to {@code ui.root} and is dropped). That is correct for custom UI; game
 * interaction goes through {@code widget:send(msg, ...)} on a <b>bound</b> widget. The addon never sees this
 * object: it holds an
 * opaque handle built in {@link AddonManager}, and the bridge owns the widget for teardown
 * (registered in {@link Addon}'s owned-resource registry, destroyed on reload/disable, principle P2).
 *
 * <p>The {@code "Draw"} ev's {@code :g()} is {@link #gwrap}, the shared {@link LuaGOut} wrapper over the live
 * {@link GOut} — bound for the duration of one fire and inert otherwise, so an addon that stashes {@code ev}
 * (or {@code ev:g()}) and uses it later cannot corrupt the client's draw pipeline. Coordinates are the
 * widget's own pixel space (top-left = {@code 0,0}); {@code ev:w()}/{@code :h()} give the widget size. The
 * unit is the <b>design pixel</b> ({@link Px}), converted at the Lua edge and nowhere below it, so a surface
 * measures the same on every client whatever the user's interface scale.
 *
 * <p><b>Drop target (D-038).</b> An AddonWidget {@link DropTarget implements DropTarget}, so the client's own
 * drag gesture can drop a "thing" onto it: the engine walks the widget tree and calls {@link #dropthing}
 * on the first target under the cursor. v1 delivers a menu-grid action ({@link MenuGrid.Pagina}) as the
 * <b>neutral descriptor</b> {@code {kind="pagina", res="<name>"}}, wrapped (041.4) in an {@code ev} fired on
 * {@code "Drop"} — {@code :x()}/{@code :y()} (widget-local px), {@code :thing()} the descriptor,
 * {@code :preventDefault()} in place of the old truthy-return consume (R3). A resource name is plain data
 * (already all over the read API), so this stays <b>unprotected</b>; firing the dropped action is out of scope
 * (the deferred menu-ability primitive). An entry an addon added reports the identity it was given, never its
 * stand-in resource — see {@link #dropDescriptor}.
 *
 * <p><b>The input event carries no modifier state</b> (041.3): the
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
    private final Addon owner;
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

    /**
     * {@code widget:on("Update"/"Draw"/"Drop"/"Close", fn)} — a surface's own four notifications (041.4, re-spelled
     * off the single {@code onTick(fn)}/{@code onDraw(fn)}/{@code onDrop(fn)}/{@code onClose(fn)} slots this
     * widget used to carry): each looks up its {@link WidgetSubs} WITHOUT minting one
     * ({@link Addon#widgetSubsOrNull}), so an unlistened surface costs one map lookup a tick/draw and nothing
     * else. N subscribers, in registration order, exactly like every other key in the API.
     *
     * <p><b>Keyed on {@link #rootw()}, never on {@code this}.</b> {@code hafen.ui():window()}'s Lua handle is
     * interned on the CHROME ({@link UiApi#attach}, {@code c.rootw()}) — {@code win:on("Draw", fn)} therefore
     * registers the {@link WidgetSubs} under {@code win}, one level above this content widget. A bare
     * {@code hafen.ui():widget()} has {@link #root} default to {@code this}, so the two keys coincide there;
     * for a window they do not, and looking up {@code this} would silently see nobody subscribed.
     */
    public void tick(double dt) {
        super.tick(dt);
        if(dead)
            return;
        WidgetSubs s = owner.widgetSubsOrNull(rootw());
        if((s != null) && s.subs.has("Update"))
            s.subs.fire("Update", LuaValue.valueOf(dt));
    }

    public void draw(GOut g) {
        if(pending)     // built this frame and not armed yet: a half-configured widget paints NOTHING (§2.5)
            return;
        /* addon: (107) the surface this widget WEARS -- its own stock under every rule that names it,
         * resolved once here and painted in the order Frame uses for the client's own panels: the
         * background under the contents, the frame over them. Null is the answer for a widget nobody has
         * named and whose addon declared no stock, and then this draws exactly what it always drew.
         *   Deliberately NOT routed through a Fonts scope: a widget an addon built is a widget, not one of
         * the places the client draws, so no site key falls back into it and a bare one stays bare. */
        Fonts.Chrome ch = Sheet.chromeOf(this, null);
        if((ch != null) && ch.bg())
            ch.drawbg(g, Coord.z, sz);
        if(!dead) {
            WidgetSubs s = owner.widgetSubsOrNull(rootw());
            if((s != null) && s.subs.has("Draw")) {
                LuaTable gt = gwrap.bind(g, owner, defaultFont);   // F2: g:text with no per-call font uses :font()
                try {
                    LuaValue ev = LuaEvent.draw(owner, gt, sz.x, sz.y);   // R4: g/w/h, so an ev — never :preventDefault()
                    s.subs.fire("Draw", ev);   // two handlers both paint: the same g, walked in registration order
                } finally {
                    gwrap.unbind();   // invalidate the wrapper outside the callback (no stashing)
                }
            }
        }
        super.draw(g);   // draw any child widgets (none for a leaf; future-proofing)
        if((ch != null) && ch.border())
            ch.drawborder(g, Coord.z, sz);   // addon: (107) over the contents, as a panel's frame is
    }

    // mousedown/mouseup/mousemove/mousewheel are GONE (041.3): the four input keys are now delivered through
    // the SAME door every widget answers them on — widget:on("MouseDown"/…, fn), a Widget.listen pre-hook
    // installed by WidgetSubs — rather than a slot only an AddonWidget had. Widget's own defaults (false / a
    // no-op) apply here now, which is correct: a leaf with no children has nothing else to do with an event
    // its listener did not consume.

    /**
     * The chrome close button ({@code widget:on("Close", fn)}). Wired once by the builder, but read here, so a
     * handler installed after the window was built still runs — nothing to say, uncancelable.
     */
    void closed() {
        if(dead)
            return;
        WidgetSubs s = owner.widgetSubsOrNull(rootw());
        if(s != null)
            s.subs.fire("Close");
    }

    // ---------------------------------------------------------------- drop target (D-038)

    /**
     * A "thing" was dropped over this widget (the engine's own drag-drop dispatch). {@code cc} is
     * widget-local (the {@link DropTarget.Drop} event derives child-local coords as it propagates). v1
     * delivers a menu-grid {@link MenuGrid.Pagina} as a neutral descriptor, wrapped in an {@code ev} answering
     * {@code :x()}/{@code :y()}/{@code :thing()}/{@code :preventDefault()} (R3: consuming the drop is
     * {@code preventDefault()} now, not a truthy return). Any other kind of thing (e.g. an inventory item, a
     * different path) is not delivered at all, so the engine keeps looking for a handler.
     */
    public boolean dropthing(Coord cc, Object thing) {
        if(dead)
            return false;
        LuaValue drop = dropDescriptor(thing);
        if(drop == null)
            return false;   // not a kind we deliver → let the engine dispatch it elsewhere
        WidgetSubs s = owner.widgetSubsOrNull(rootw());
        if((s == null) || !s.subs.has("Drop"))
            return false;
        Subs.Cancel c = new Subs.Cancel();
        LuaValue ev = LuaEvent.drop(owner, c, cc.x, cc.y, drop);
        return s.subs.fire("Drop", c, ev);
    }

    /**
     * Build the neutral drop descriptor for {@code onDrop} (D-038), or {@code null} for a thing v1 does not
     * deliver. A menu-grid action &rarr; {@code {kind="pagina", res="<name>"}}. The {@code res} is included
     * only for a <b>resource-based</b> pagina (its {@code id} is the resource {@link Indir} itself) and only
     * once resolved — an id-only pagina ({@code fl&2}) has no stable resource name, so it carries {@code kind}
     * alone (usable in-session, not reliably persistable). Loading is swallowed (res absent until ready).
     *
     * <p><b>A custom entry names itself</b> (059.3), like everywhere else: its backing {@link Resource} is a
     * stand-in shared by every one of them, so reading {@code pag.res} here would report the paging arrow to
     * every widget in the client. Its {@code addon/<addon id>/<id>} identity is stable across a relog and is
     * exactly what {@code pag:res()} and {@code s:menugrid():get(key)} speak.
     */
    private static LuaValue dropDescriptor(Object thing) {
        if(thing instanceof MenuGrid.Pagina) {
            MenuGrid.Pagina pag = (MenuGrid.Pagina)thing;
            LuaTable d = new LuaTable();
            d.set("kind", LuaValue.valueOf("pagina"));
            if(pag instanceof AddonPagina) {
                d.set("res", LuaValue.valueOf(((AddonPagina)pag).id));
                return d;
            }
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
}
