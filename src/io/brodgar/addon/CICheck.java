package io.brodgar.addon;

import haven.GOut;
import haven.ICheckBox;
import haven.Tex;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * The adapter behind a checkbox whose faces are PICTURES — a real {@link ICheckBox}, built by the very same
 * {@code hafen.ui():check()} the captioned one comes from (spec {@code 040-ui-controls}, task 040.4), exactly
 * as {@link CtlIButton} completes {@code :button()} the picture way (D-148).
 *
 * <p><b>Four faces, not two or three.</b> Unlike a button — one gesture, up/down/hover — a checkbox has TWO
 * persistent states, each with its own hover: {@code up} (unchecked), {@code down} (checked), {@code hoverUp}
 * and {@code hoverDown} (either state, under the cursor). {@link ICheckBox}'s own constructor takes exactly
 * that quartet as {@link Tex}, not {@link java.awt.image.BufferedImage} — {@code ICheckBox} is a plain
 * {@code Widget} that blits a {@code Tex} each frame, unlike {@link haven.IButton}'s {@code SIWidget} raster —
 * so the faces are resolved through {@link Controls#faceTex}, the {@code Tex}-returning door beside
 * {@link Controls#face} ({@code BufferedImage}) and {@link Controls#sourceTex}.
 *
 * <p><b>The switch is a REBUILD</b>, D-148's rule again: {@code ICheckBox}'s faces are {@code final} and its box
 * is the up image's size, so {@code widget:image(...)} on a checkbox is building-only, legal exactly while
 * {@link Owned#pending() pending}. The rebuild carries the checked state and the {@code :onChange} handler
 * across, exactly as {@link Controls#image} already carries a button's {@code :onPress} handler.
 *
 * <p>Otherwise {@link CCheck}'s shape verbatim: {@code :value()}/{@code :onChange(fn)} over the same
 * {@link haven.ACheckBox#a}/{@code changed} pair, a direct field write on {@code :value(v)} so it never
 * re-enters the handler, and the ownership contract over one {@link Owned.State}.
 */
final class CICheck extends ICheckBox implements Owned.Control, Controls.Value, Controls.Change {
    private final Owned.State own;
    /** Exactly the values {@code :image(…)} was given — what the bare read hands back. */
    private final LuaValue upv, downv, hoverUpv, hoverDownv;

    CICheck(Addon owner, Tex up, Tex down, Tex hoverUp, Tex hoverDown,
            LuaValue upv, LuaValue downv, LuaValue hoverUpv, LuaValue hoverDownv) {
        super(up, down, hoverUp, hoverDown);
        this.own = new Owned.State(owner, this);
        this.upv = upv;
        this.downv = downv;
        this.hoverUpv = hoverUpv;
        this.hoverDownv = hoverDownv;
        this.changed = this::fire;   // replaces the stock wdgmsg consumer; this control sends the server nothing
    }

    public Owned.State own() {
        return own;
    }

    /** {@code c:image()} — the four faces as the caller named them: {up=, down=, hoverUp=, hoverDown=}. */
    LuaValue faces() {
        LuaTable t = new LuaTable();
        t.set("up", upv);
        t.set("down", downv);
        t.set("hoverUp", hoverUpv);
        t.set("hoverDown", hoverDownv);
        return t;
    }

    public LuaValue value() {
        return LuaValue.valueOf(a);
    }

    public void value(LuaValue v) {
        if(!v.isboolean())
            throw new LuaError("widget:value(v) on a checkbox is a BOOLEAN, got " + v.typename());
        this.a = v.toboolean();
    }

    private void fire(boolean val) {
        Controls.fire(this, "Changed", LuaValue.valueOf(val));
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(g);
    }
}
