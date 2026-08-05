package io.brodgar.addon;

import haven.Button;
import haven.Coord;
import haven.GOut;

import org.luaj.vm2.LuaValue;

/**
 * The adapter behind {@code hafen.ui():button()} — a real {@link Button}, the very class the client's own
 * windows are built from, carrying the ownership contract and the Lua {@code :onPress} callback.
 *
 * <p><b>This class is the convention every later control copies</b> (spec {@code 040-ui-controls}, task 040.1),
 * and it is four things and nothing else:
 * <ol>
 *   <li><b>{@code Ctl<Control>}, in the bridge's own package.</b> A sub-package was the other candidate and is
 *       worse: {@link AddonManager#callLua}, {@link Addon#widgets}, {@link LuaWidget}'s dispatch and
 *       {@link Owned} itself are all package-private, and a control adapter needs every one of them — so a
 *       {@code control.*} package would have made the whole bridge's internals public to buy a directory.</li>
 *   <li><b>{@code implements Owned.Control} over one {@link Owned.State} field.</b> That is the entire
 *       ownership half: provenance, teardown and the arming rule all come from the mixin's defaults.</li>
 *   <li><b>One override per Lua callback the engine wants as an override.</b> Here that is {@link #click()},
 *       {@link Button}'s own activation — which fires from the keyboard as well as the mouse, and which
 *       {@code Button.mouseup} calls <i>last</i>, after releasing its grab, so an {@code :onPress} that destroys
 *       its own window is safe.</li>
 *   <li><b>The two overrides every adapter owes the engine rather than Lua</b>: {@link #draw(GOut)} skips the
 *       paint while the control is still {@link Owned#pending() pending}, so a control configured across five
 *       lines is never drawn half-built; and {@link #resize(Coord)} calls {@code redraw()}, because
 *       {@code SIWidget} caches its rasterised face and {@code Widget.resize} does not invalidate it — without
 *       it {@code :size(w, h)} would move the box and leave the old picture in it.</li>
 * </ol>
 *
 * <p><b>{@code :type()} still reads {@code "Button"}.</b> {@link LuaWidget#typeName} climbs past a control
 * adapter exactly as it climbs past an anonymous subclass, so every selector, role and stylesheet key that
 * names the engine's class keeps matching a control an addon built. The adapter is an implementation detail of
 * the bridge and never a name in the API.
 *
 * <p><b>It sends the server nothing.</b> The stock {@code Button(int, String)} constructor wires
 * {@code action = () -> wdgmsg("activate")}; this one takes the {@code Runnable} overload with {@code null} and
 * overrides {@code click()} outright, so a control is client-side by construction and acting on the game stays
 * the gated {@code hafen.act} tier.
 */
final class CtlButton extends Button implements Owned.Control {
    /** The client's own look with no caption: a plain button, at a width {@code :size(w, h)} overrides. */
    static final int DEF_W = 100;

    private final Owned.State own;
    /** {@code :onPress(fn)}. Volatile: the engine fires it from the input pass while Lua may be replacing it. */
    private volatile LuaValue onPress;

    CtlButton(Addon owner, int w) {
        // lg = false explicitly: the stock two-argument constructor derives it from the width against the
        // button's own (UI-scaled) images, so the SAME default width would build the plain short button on one
        // client and the tall decorated one on another. A default must look the same everywhere.
        super(w, "", false, (Runnable)null);
        this.own = new Owned.State(owner, this);
    }

    public Owned.State own() {
        return own;
    }

    /** The installed {@code :onPress} handler, or {@code null} — what {@code b:onPress()} reads back. */
    LuaValue onPress() {
        return onPress;
    }

    void onPress(LuaValue fn) {
        this.onPress = fn;
    }

    /**
     * The button fired ({@link Button#click}) — a mouse release inside its box, or the keyboard. Forwarded
     * through {@link AddonManager#callLua}, so it is watchdog-armed, error-isolated and CPU-accounted exactly
     * like every other addon callback.
     */
    public void click() {
        LuaValue fn = onPress;
        if(!own.dead() && (fn != null))
            AddonManager.callLua(own.owner, Addon.C_WIDGET, fn);
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
