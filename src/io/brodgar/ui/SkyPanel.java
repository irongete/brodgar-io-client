package io.brodgar.ui;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

import haven.CheckBox;
import haven.Coord;
import haven.HSlider;
import haven.Label;
import haven.OptWnd;
import haven.UI;
import haven.Widget;

import io.brodgar.ambience.Ambience;

/**
 * The in-game <b>Sky &amp; weather</b> options panel (spike-ambience) -- {@link PerformancePanel}'s
 * pattern over {@link Ambience}: every control re-reads its static every frame in {@code tick} and
 * writes through the matching setter, so the console's {@code :amb} moves an open panel.
 */
public class SkyPanel extends OptWnd.Panel {
    public SkyPanel(OptWnd opt) {
        opt.super();

        Widget prev = add(new Label("Sky"), 0, 0);

        prev = add(new CheckBox("Sky, clouds and fog") {
                {a = Ambience.enabled;}
                public void set(boolean val) {Ambience.enabled(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Ambience.enabled;
                }
            }, prev.pos("bl").adds(0, 10));
        prev.settip("Draws the server's weather our way: a sky with a sun, a moon and stars, separate"
                    + " clouds drifting with the wind and their shadows on the ground, and distance fog the"
                    + " colour of the sky. Rain and snow stay the game's own. Applies at once.", true);

        int y = slider(prev.pos("bl").y + UI.scale(8), "Fog", 0, 200,
                       () -> Math.round(Ambience.fogmul * 100), v -> Ambience.fog(v / 100f),
                       v -> (v == 0) ? "Off" : (v + " %"),
                       "How thick the distance fog is against the weather's own: 100 % is as the weather"
                       + " has it. Rain and snow thicken it.");

        prev = add(new Label("Clouds"), 0, y + UI.scale(15));

        y = slider(prev.pos("bl").y + UI.scale(10), "Cloud amount", 0, 200,
                   () -> Math.round(Ambience.cloudamount * 100), v -> Ambience.cloudamount(v / 100f),
                   v -> (v == 0) ? "None" : (v + " %"),
                   "How many clouds the sky has against what the weather alone would give it: 100 % is"
                   + " as the weather has it. None leaves a clear sky whatever the weather.");

        y = slider(y + UI.scale(8), "Cloud height", 200, 1500,
                   () -> Math.round(Ambience.cloudbase), v -> Ambience.cloudalt(v),
                   v -> Math.round(v / 11f) + " tiles",
                   "How high the clouds stand above the ground under you. The clouds already up move"
                   + " with it.");

        prev = add(new Label("Night"), 0, y + UI.scale(15));

        prev = add(new CheckBox("Stars and moon") {
                {a = Ambience.starsmoon;}
                public void set(boolean val) {Ambience.starsmoon(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Ambience.starsmoon;
                }
            }, prev.pos("bl").adds(0, 10));
        prev.settip("Whether the night sky has its stars and its moon. Off, the moon's light and the"
                    + " shadows it casts stay. Applies at once.", true);

        pack();
    }

    /* One labelled slider at `y`, its value printed after it: `get` read every frame, `set` on a drag.
     * Returns the bottom of the row, the taller of the label and the slider. */
    private int slider(int y, String name, int min, int max, IntSupplier get, IntConsumer set,
                       IntFunction<String> fmt, String tip) {
        Label lbl = add(new Label(name), UI.scale(5), y);
        Label dpy = new Label("");
        HSlider sl = new HSlider(UI.scale(140), min, max, get.getAsInt()) {
                protected void added() {
                    dpy();
                }
                void dpy() {
                    dpy.settext(fmt.apply(this.val));
                }
                public void changed() {
                    set.accept(this.val);
                    dpy();
                }
                public void tick(double dt) {
                    super.tick(dt);
                    int cur = get.getAsInt();
                    if(this.val != cur) {
                        this.val = cur;
                        dpy();
                    }
                }
            };
        Coord end = addhlp(Coord.of(UI.scale(110), y), UI.scale(5), sl, dpy);
        sl.settip(tip, true);
        return(Math.max(lbl.pos("bl").y, end.y));
    }
}
