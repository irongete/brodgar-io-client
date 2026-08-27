package io.brodgar.addon.ui;

import haven.Button;
import haven.CharWnd;
import haven.CheckBox;
import haven.Coord;
import haven.HSlider;
import haven.Label;
import haven.OptWnd;
import haven.RichText;
import haven.SDropBox;
import haven.SListWidget;
import haven.TextEntry;
import haven.UI;
import haven.Widget;

import io.brodgar.addon.AddonManager;
import io.brodgar.addon.LuaOption;

import java.util.List;

/**
 * <b>One addon's declared options</b>, drawn (spec {@code 115-the-addon-declares-its-options}) — what the
 * AddOns tab of the settings window puts in its holder when a row is picked. One row per option, in
 * declaration order, drawn with the client's own control for that kind: a {@link CheckBox}, an
 * {@link HSlider}, an {@link SDropBox}, a {@link TextEntry}, a {@link Button} or a {@link Label}.
 *
 * <p><b>The panel and the value are one fact.</b> Every control <b>re-reads its option each frame</b> and
 * writes back through {@code LuaOption.set}, the one write path Lua's own {@code opt:value(v)} takes — so a
 * write from Lua moves an open control with no event plumbing and no listener to leak, and a move of the
 * control fires that option's {@code Changed} exactly as a Lua write does. Writing the value already held is
 * not a change, which is what lets a control write back what it just read without a loop.
 *
 * <p><b>A programmatic write takes each control's silent path</b>, because every one of them notifies on the
 * ordinary one and the notification is what this panel is trying not to re-enter: {@code ACheckBox.a} rather
 * than {@code set(boolean)}, {@code HSlider.val} rather than a drag, {@code TextEntry.rsettext} rather than
 * {@code settext}, and — for the dropdown, which has no lower seam at all, {@code change(I)} setting
 * {@code sel} <i>and</i> rebuilding the closed box — {@code super.change(I)} with only this class's own
 * notify wrapper skipped.
 *
 * <p>It extends {@code OptWnd.Panel} (a non-static inner class) from this package through the qualified
 * {@code opt.super()} form, and carries <b>no caption</b>: it is drawn inside the settings view's holder, so
 * the window's subject is that view's and not this page's.
 */
public class AddonOptionsPanel extends OptWnd.Panel {
    /** The caption column, the control column, and how wide a control is drawn. */
    private static final int CAPW = UI.scale(150), CTLX = UI.scale(158), CTLW = UI.scale(215);
    /** The gap between two rows, and the room a number row's readout takes beside its slider. */
    private static final int ROWGAP = UI.scale(8), NUMW = UI.scale(44);

    /** Built: until then a child's own repack is the page being assembled, not a line an addon rewrote. */
    private boolean built = false;

    public AddonOptionsPanel(OptWnd opt, AddonManager.OptionGroup group) {
        opt.super();
        Widget prev = add(new Label(group.addon), 0, 0);
        int y = prev.pos("bl").adds(0, 10).y;
        for(AddonManager.OptionEntry e : group.options)
            y = row(e.option, y) + ROWGAP;
        pack();
        built = true;
    }

    /** One option's row: the caption on the left, the control it is drawn as on the right. */
    private int row(LuaOption o, int y) {
        Widget ctl = add(control(o), new Coord(CTLX, y));
        tip(ctl, o);
        int h = ctl.sz.y;
        // A button carries its caption on its face and a bare label row declares none, so the column stands
        // empty for those two rather than repeating the one and inventing the other.
        if(!o.label.isEmpty()) {
            Label cap = new Label(o.label, CAPW);
            tip(add(cap, new Coord(0, y + Math.max(0, (ctl.sz.y - cap.sz.y) / 2))), o);
            h = Math.max(h, cap.sz.y);
        }
        return y + h;
    }

    /** The hover text the row declared, if it declared one. */
    private static void tip(Widget w, LuaOption o) {
        if(o.tooltip != null)
            // Rich, and quoted: settip(_, false) renders on one unwrapped line, so a long tooltip becomes a
            // texture wider than the GL maximum and the upload fails from the hover. quote() keeps a
            // tooltip full of { } $ literal.
            w.settip(RichText.Parser.quote(o.tooltip), true);
    }

    /** The client's own control for this kind of row, wired to read and write the option. */
    private Widget control(LuaOption o) {
        switch(o.kind) {
        case BOOLEAN: return new Check(o);
        case NUMBER:  return new Num(o);
        case CHOICE:  return new Drop(o);
        case TEXT:    return new Entry(o);
        case BUTTON:  return new Button(CTLW, o.label.isEmpty() ? o.name : o.label).action(o::press);
        default:      return new Line(o);
        }
    }

    /* ---- the six controls ------------------------------------------------------------------------- */

    /** A boolean row. The caption is the row's own column, so the box itself carries none. */
    private static final class Check extends CheckBox {
        private final LuaOption o;

        Check(LuaOption o) {
            super("");
            this.o = o;
            this.a = o.bool();
        }

        /* The click's own path: set(boolean) is what ACheckBox.click runs. The option is written, and `a`
         * is then re-read FROM it rather than assumed -- a write the option refuses leaves the box where it
         * was, in the same frame as the click. */
        public void set(boolean val) {
            o.set(val);
            this.a = o.bool();
        }

        public void tick(double dt) {
            super.tick(dt);
            this.a = o.bool();   // ACheckBox.a: the silent path, and the read that follows a Lua write
        }
    }

    /** A number row: the slider, and the number it is on. */
    private static final class Num extends Widget {
        Num(final LuaOption o) {
            super(Coord.of(CTLW, 0));
            final Label read = new Label(Integer.toString(o.num()));
            HSlider sl = add(new HSlider(CTLW - NUMW, o.lo, o.hi, o.num()) {
                    /* changed() is the drag's own hook, called after HSlider.update has already written
                     * `val` -- so this reports the step rather than deciding it. */
                    public void changed() {
                        o.set(val);
                    }

                    public void tick(double dt) {
                        super.tick(dt);
                        val = o.num();   // HSlider.val: a public field, and the write that notifies nothing
                        read.settext(Integer.toString(val));
                    }
                }, Coord.z);
            add(read, Coord.of(CTLW - NUMW + UI.scale(4), Math.max(0, (sl.sz.y - read.sz.y) / 2)));
            resize(Coord.of(CTLW, Math.max(sl.sz.y, read.sz.y)));
        }
    }

    /** A choice row. */
    private static final class Drop extends SDropBox<String, Widget> {
        private final LuaOption o;

        Drop(LuaOption o) {
            /* The row height is the item font's own: a shorter row clips its text rather than shrinking it,
             * the sizing the client's own camera picker uses. */
            super(CTLW, UI.scale(120), CharWnd.attrfont().height());
            this.o = o;
            sync();
        }

        protected List<String> items() {
            return o.choices;
        }

        protected Widget makeitem(String nm, int idx, Coord sz) {
            /* makeitem(null, …) is a real call: change() passes whatever it is handed for the closed box. */
            return SListWidget.TextItem.of(sz, () -> (nm == null) ? "" : nm);
        }

        /* change(I) is the ONLY way to move what the closed box shows -- it writes `sel` AND rebuilds that
         * widget -- so a re-sync runs super's half and none of the pick below. */
        private void sync() {
            super.change(o.str());
        }

        public void change(String nm) {
            boolean same = (nm == this.sel);   // read it before super.change writes it
            super.change(nm);
            if((nm != null) && !same)
                o.set(nm);
        }

        public void tick(double dt) {
            super.tick(dt);
            /* By identity, which is what SDropBox itself compares by -- LuaOption.str() answers the element
             * of the declared list, so an unmoved value never rebuilds the closed box. */
            if(o.str() != this.sel)
                sync();
        }
    }

    /** A text row. */
    private static final class Entry extends TextEntry {
        private final LuaOption o;

        Entry(LuaOption o) {
            super(CTLW, o.str());
            this.o = o;
        }

        /* Every edit, as it is made: TextEntry.changed() is what the ReadLine buffer calls when a keystroke
         * actually moved the line. A refusal (a line past the preference store's cap) leaves the option
         * where it was, and the tick below puts the field back on it. */
        protected void changed() {
            super.changed();
            if(o == null)
                return;    // the base constructor's own rsettext, before this field is assigned
            try {
                o.set(text());
            } catch(RuntimeException e) {
                // the option keeps what it had; the next tick re-reads it into the field
            }
        }

        public void tick(double dt) {
            super.tick(dt);
            if(!text().equals(o.str()))
                rsettext(o.str());   // rsettext, not settext: the silent one of the pair
        }
    }

    /** A label row: the line the addon states, re-read as it is drawn. */
    private static final class Line extends Label {
        private final LuaOption o;

        Line(LuaOption o) {
            super(o.text(), CTLW);
            this.o = o;
        }

        public void tick(double dt) {
            super.tick(dt);
            settext(o.text(), wrapw());   // the wrapping half of the pair; equal text returns early
        }
    }

    /* A control inside this page may repack itself -- a label row's line is the addon's to rewrite, and a
     * longer one is a taller widget -- and Widget.cresize is a no-op, so without this the page keeps the box
     * its first build came out at and the window below it never re-fits. */
    public void cresize(Widget ch) {
        if(!built || (parent == null))
            return;
        pack();
        parent.cresize(this);
    }
}
