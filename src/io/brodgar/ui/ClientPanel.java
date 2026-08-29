package io.brodgar.ui;

import haven.CheckBox;
import haven.Coord;
import haven.HSlider;
import haven.Label;
import haven.MapView;
import haven.OptWnd;
import haven.UI;
import haven.Utils;
import haven.Widget;

import io.brodgar.prof.Prof;

/**
 * The in-game <b>Client</b> options panel (spec 019-profiling, task 019.1) — a clone of the
 * {@code OptWnd.VoiceChatPanel} / {@code AddonPanel} pattern, and the home for client-wide toggles: the
 * profiling switch behind {@link Prof#arm(boolean)}, and the <b>Remembered ground</b> section over the three
 * statics on {@link MapView}.
 *
 * <p>This is a <b>client</b> panel, not an addon panel, which is why it sits in {@code io.brodgar.ui} rather
 * than beside {@code addon.ui.AddonPanel} (the addon manager) — the settings it edits are the client's, and no
 * addon has to be loaded for them to mean anything. Like that panel it needs no package-private {@code haven}
 * access, and extends the non-static {@code OptWnd.Panel} through the qualified {@code opt.super()} /
 * {@code opt.new PButton(...)} forms.
 *
 * <p>Every control here <b>re-reads its own switch every frame</b> instead of caching it, so
 * {@code hafen.client():options():client():profiling(true)} — or a write from anywhere else — visibly moves an
 * open panel's box or slider with no listener to register and nothing to leak when the panel closes.
 *
 * <p>Each write moves the live field <b>and</b> persists it in one statement, which is the shape
 * {@code MapView.recallon} and its neighbours are declared in: neither half can be done without the other, so
 * a setting cannot apply and then fail to survive a restart.
 */
public class ClientPanel extends OptWnd.Panel {
    public ClientPanel(OptWnd opt) {
        opt.super();
        Widget prev = add(new Label("Client"), 0, 0);
        prev = add(new CheckBox("Enable profiling") {
                {a = Prof.armed();}
                public void set(boolean val) {Prof.arm(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Prof.armed();   // follow a write from Lua (or anywhere else) while the panel is open
                }
            }, prev.pos("bl").adds(0, 10));
        prev.settip("Arms the client's profiler: frame, CPU, GPU, addon and widget timings, readable from an"
                    + " addon through hafen.client:profiling(). Same switch as the :profile console command."
                    + " Off costs nothing; leave it off unless you are measuring something.", true);

        prev = add(new Label("Remembered ground"), prev.pos("bl").adds(0, 15));
        prev = add(new CheckBox("Draw remembered ground") {
                {a = MapView.recallon;}
                public void set(boolean val) {Utils.setprefb("recallon", MapView.recallon = val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = MapView.recallon;
                }
            }, prev.pos("bl").adds(0, 5));
        prev.settip("Draws the ground your character has already explored, out of the client's own map"
                    + " database, wherever the RTS camera is looking. Nothing is asked of the server for it,"
                    + " and it is drawn without colour so that remembered ground is never mistaken for live"
                    + " ground.", true);

        Label range = add(new Label("Range"), prev.pos("bl").adds(5, 5));
        Coord rowend;
        {
            Label dpy = new Label("");
            HSlider sl = new HSlider(UI.scale(140), MapView.recallrangemin, MapView.recallrangemax,
                                     MapView.recallrange) {
                    protected void added() {
                        dpy();
                    }
                    void dpy() {
                        dpy.settext(this.val + ((this.val == 1) ? " grid" : " grids"));
                    }
                    public void changed() {
                        Utils.setprefi("recallrange", MapView.recallrange = this.val);
                        dpy();
                    }
                    public void tick(double dt) {
                        super.tick(dt);
                        if(this.val != MapView.recallrange) {
                            this.val = MapView.recallrange;
                            dpy();
                        }
                    }
                };
            rowend = addhlp(Coord.of(range.pos("ur").x + UI.scale(5), range.c.y), UI.scale(5), sl, dpy);
            sl.settip("How far around the camera remembered ground is drawn, in grids. A grid is 100 tiles"
                      + " across. A larger range reaches further and costs more to build; what is drawn is"
                      + " bounded by the view and by the client's own mesh budget either way.", true);
        }

        // Off the ROW's own bottom rather than the label's: the slider is the taller of the two and
        // addhlp answers where the row actually ends.
        prev = add(new CheckBox("Grey wash") {
                {a = MapView.recallgrey;}
                public void set(boolean val) {Utils.setprefb("recallgrey", MapView.recallgrey = val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = MapView.recallgrey;
                }
            }, Coord.of(0, Math.max(range.pos("bl").y, rowend.y) + UI.scale(8)));
        prev.settip("Takes the colour out of remembered ground, so that what you are looking at is plainly a"
                    + " memory rather than the world as it is now. Turn it off and remembered ground is drawn"
                    + " in the colours it was recorded in.", true);
        pack();
    }
}
