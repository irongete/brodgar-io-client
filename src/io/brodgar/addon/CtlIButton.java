package io.brodgar.addon;

import haven.Coord;
import haven.GOut;
import haven.IButton;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.awt.image.BufferedImage;

/**
 * The adapter behind a button whose face is a <b>picture</b> — a real {@link IButton}, the class the client's own
 * close boxes, {@code +}/{@code -} steppers and toolbar buttons are, built by the very same
 * {@code hafen.ui():button()} the captioned one comes from (spec {@code 040-ui-controls}, task 040.2).
 *
 * <p><b>One builder, two engine classes, and the SETTER chooses.</b> {@code :text(s)} completes the builder as a
 * {@link haven.Button} and {@code :image(up, down[, hover])} as this — because {@code Button} and {@code IButton}
 * are one control to a Lua author and two classes to the client, and the difference between them is only whether
 * the face is text or pictures. Keeping the client's {@code I} prefix out of the API is D-061: the vocabulary
 * comes from the engine's <i>meaning</i>, not its class list. {@code :type()} still reads {@code "IButton"}, which
 * is where a reader who wants the engine's own name finds it (D-146 — {@link LuaWidget#typeName} climbs past this
 * adapter exactly as it climbs past {@link CtlButton}).
 *
 * <p><b>The faces are final, so the switch is a REBUILD</b> — {@link IButton#up}/{@code down}/{@code hover} are
 * {@code final} fields sized at construction, and the widget's own box is the picture's size. That is D-113 (a
 * setter that changes how the visual is BUILT rebuilds it) meeting D-119's arming rule: the rebuild is legal
 * exactly while the control is still {@link Owned#pending() pending}, and {@link UiApi#rebuild} is the one place
 * it happens. It is otherwise {@link CtlButton}'s shape verbatim: the ownership contract over one
 * {@link Owned.State}, {@link #click()} forwarding to Lua, and the two overrides the engine is owed.
 *
 * <p><b>It sends the server nothing.</b> The stock {@code IButton(up, down, hover)} constructor wires
 * {@code action = () -> wdgmsg("activate")}; this one takes the {@code Runnable} overload with {@code null} and
 * overrides {@code click()} outright, so a control is client-side by construction.
 */
final class CtlIButton extends IButton implements Owned.Control, Controls.Press {
    private final Owned.State own;
    /**
     * The up face's pixel size — the box {@link #checkhit}'s raster read is valid inside. NOT {@code sz}, which
     * {@code widget:size(w, h)} may have grown past the picture.
     */
    private final Coord face;
    /** Exactly the values {@code :image(…)} was given (a handle, or a resource name) — what the bare read hands back. */
    private final LuaValue upv, downv, hoverv;
    /** {@code :onPress(fn)}. Volatile: the engine fires it from the input pass while Lua may be replacing it. */
    private volatile LuaValue onPress;

    CtlIButton(Addon owner, BufferedImage up, BufferedImage down, BufferedImage hover,
               LuaValue upv, LuaValue downv, LuaValue hoverv) {
        super(up, down, hover, (java.lang.Runnable)null);
        this.own = new Owned.State(owner, this);
        this.face = sz;     // IButton sizes itself to the up image; widget:size(w, h) may move sz off it later
        this.upv = upv;
        this.downv = downv;
        this.hoverv = hoverv;
    }

    public Owned.State own() {
        return own;
    }

    /** {@code b:image()} — the three faces as the caller named them: {@code {up =, down =, hover =}}. */
    LuaValue faces() {
        LuaTable t = new LuaTable();
        t.set("up", upv);
        t.set("down", downv);
        t.set("hover", hoverv);
        return t;
    }

    public LuaValue onPress() {
        return onPress;
    }

    public void onPress(LuaValue fn) {
        this.onPress = fn;
    }

    /** The button fired — a release inside the picture, or the keyboard. Through the one Lua callback bridge. */
    public void click() {
        LuaValue fn = onPress;
        if(!own.dead() && (fn != null))
            AddonManager.callLua(own.owner, Addon.C_WIDGET, fn);
    }

    /**
     * The hit test, bounded to the FACE and not merely to the box. {@code IButton.checkhit} samples the up
     * image's alpha at the cursor, having only checked the cursor against {@code sz} — so a
     * {@code widget:size(w, h)} wider than the picture would read outside the raster and raise from the input
     * pass, on a mouse move, with no addon on the stack. The engine never meets that case (nothing resizes an
     * {@code IButton}); the API hands it out, so the adapter closes it.
     */
    public boolean checkhit(Coord c) {
        return c.isect(Coord.z, face) && super.checkhit(c);
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(g);
    }

    public void resize(Coord sz) {
        super.resize(sz);
        redraw();           // SIWidget caches the rasterised face; a resize without this keeps the old picture
    }
}
