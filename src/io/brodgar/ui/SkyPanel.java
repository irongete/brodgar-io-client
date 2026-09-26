package io.brodgar.ui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

import haven.CheckBox;
import haven.Coord;
import haven.HSlider;
import haven.Label;
import haven.OptWnd;
import haven.Scrollport;
import haven.UI;
import haven.Widget;

import io.brodgar.ambience.Ambience;
import io.brodgar.perf.Performance;

/**
 * The in-game <b>Sky &amp; weather</b> options panel -- {@link PerformancePanel}'s
 * pattern over {@link Ambience}: every control re-reads its static every frame in {@code tick} and
 * writes through the matching setter, so the console's {@code :amb} moves an open panel. One switch over
 * all of it, and under it four sections, the sky, the clouds, the fog and the rain and snow, each switched
 * on its own and holding its own settings. Below them, the game's own weather effects, each switched on its
 * own: {@link Performance}'s, which reach only what the game draws.
 */
public class SkyPanel extends OptWnd.Panel {
    /* How far the sections stand in under the one switch. */
    private static final int IND = UI.scale(12);

    public SkyPanel(OptWnd opt) {
        opt.super();

        /* The page is built in a body of its own, and stands in a scrolled port the size of the settings
         * view's whole page box when it is taller than that box. */
        Widget body = new Widget(Coord.z);

        Widget prev = toggle(body, Coord.z, "Our sky and weather", () -> Ambience.on, Ambience::on,
                             "Draws the sky and the weather with the client's own renderer instead of the game's:"
                             + " each part below, switched on its own. Off, all of it is the game's own, whatever"
                             + " the parts say. Applies at once.");
        /* Under the one switch, what it being off means; empty while it is on. */
        prev = body.add(new Label("") {
                public void tick(double dt) {
                    super.tick(dt);
                    String t = Ambience.on ? "" : "Off: the game draws the sky and the weather.";
                    if(!t.equals(texts))
                        settext(t);
                }
            }, prev.pos("bl").adds(0, 3));

        prev = body.add(new Label("Sky"), Coord.of(IND, prev.pos("bl").y + UI.scale(10)));
        prev = toggle(body, prev.pos("bl").adds(0, 10), "Sky", () -> Ambience.skyon, Ambience::skyon,
                      "Draws a sky of the client's own over the open horizon: a gradient off the server's light,"
                      + " the sun's disc and glow, and at night the stars and the moon. Off, the game's own"
                      + " background shows. Applies at once.");
        prev = toggle(body, prev.pos("bl").adds(5, 5), "Stars and moon", () -> Ambience.starsmoon, Ambience::starsmoon,
                      "Whether the night sky has its stars and its moon. Off, the moon's light and the shadows"
                      + " it casts stay. Applies at once.");

        prev = body.add(new Label("Clouds"), prev.pos("bl").adds(-5, 15));
        prev = toggle(body, prev.pos("bl").adds(0, 10), "Clouds", () -> Ambience.cloudson, Ambience::cloudson,
                      "Draws separate clouds drifting with the wind, how many and how dark following the"
                      + " server's weather, and their shadows on the ground. Off, the game's own cloud shadows"
                      + " come back. Applies at once.");
        prev = toggle(body, prev.pos("bl").adds(5, 5), "Cloud shadows", () -> Ambience.cloudshadows, Ambience::cloudshadows,
                      "Whether these clouds cast their shadows on the ground. Applies at once.");
        int y = slider(body, prev.pos("bl").y + UI.scale(8), "Cloud amount", 0, 200,
                       () -> Math.round(Ambience.cloudamount * 100), v -> Ambience.cloudamount(v / 100f),
                       v -> (v == 0) ? "None" : (v + " %"),
                       "How many clouds the sky has against what the weather alone would give it: 100 % is"
                       + " as the weather has it. None leaves a clear sky whatever the weather.");
        y = slider(body, y + UI.scale(8), "Cloud height", 200, 1500,
                   () -> Math.round(Ambience.cloudbase), v -> Ambience.cloudalt(v),
                   v -> Math.round(v / 11f) + " tiles",
                   "How high the clouds stand above the ground under you. The clouds already up move"
                   + " with it.");

        prev = body.add(new Label("Fog"), Coord.of(IND, y + UI.scale(15)));
        prev = toggle(body, prev.pos("bl").adds(0, 10), "Fog", () -> Ambience.fogon, Ambience::fogon,
                      "Distance fog the colour of the sky, so the far ground dissolves into the horizon"
                      + " instead of ending at it. Rain and snow thicken it. Applies at once.");
        y = slider(body, prev.pos("bl").y + UI.scale(8), "Density", 0, 200,
               () -> Math.round(Ambience.fogmul * 100), v -> Ambience.fog(v / 100f),
               v -> (v == 0) ? "None" : (v + " %"),
               "How thick the distance fog is against the weather's own: 100 % is as the weather has it.");

        prev = body.add(new Label("Rain and snow"), Coord.of(IND, y + UI.scale(15)));
        prev = toggle(body, prev.pos("bl").adds(0, 10), "Rain and snow", () -> Ambience.precipon, Ambience::precipon,
                      "Draws the rain and the snow the server sends on the graphics card, looking as the game's"
                      + " own: the same drops, splashes and flakes, as many of them. None falls indoors or"
                      + " underground, and a blizzard is not held to the game's 50,000 flakes. Off, the game's"
                      + " own come back. Applies at once.");

        /* The game's own effects, each switched on its own; where a part above is ours, the game's of it is
         * not drawn, and its switch here reaches nothing. */
        prev = body.add(new Label("The game's own weather"), Coord.of(0, prev.pos("bl").y + UI.scale(20)));
        prev = toggle(body, prev.pos("bl").adds(IND, 10), "Cloud shadows", () -> Performance.clouds, Performance::clouds,
                      "The game's own cloud shadows moving over the ground. While the clouds above are ours,"
                      + " the game's are not drawn. Takes effect the next frame.");
        prev = toggle(body, prev.pos("bl").adds(0, 5), "Rain", () -> Performance.rain, Performance::rain,
                      "The game's own rain particles and their splashes. While the rain and snow above are"
                      + " ours, the game's are not drawn. Takes effect the next frame.");
        prev = toggle(body, prev.pos("bl").adds(0, 5), "Snow", () -> Performance.snow, Performance::snow,
                      "The game's own snow particles. While the rain and snow above are ours, the game's are"
                      + " not drawn. Takes effect the next frame.");
        prev = toggle(body, prev.pos("bl").adds(0, 5), "Wet ground", () -> Performance.wetGround, Performance::wetGround,
                      "The sheen the ground takes on after rain: the game's own, under either sky. Takes effect"
                      + " the next frame.");
        prev = toggle(body, prev.pos("bl").adds(0, 5), "Seasonal tint", () -> Performance.seasonTint, Performance::seasonTint,
                      "The seasonal tint of the ground: the game's own, under either sky. Takes effect the next"
                      + " frame.");

        /* Empty unless drawing failed: then every part is the game's until a switch above is thrown again. */
        body.add(new Label("") {
                public void tick(double dt) {
                    super.tick(dt);
                    String t = Ambience.failed() ? "Stopped after an error: switch a part to try again." : "";
                    if(!t.equals(texts))
                        settext(t);
                }
            }, Coord.of(0, prev.pos("bl").y + UI.scale(10)));

        body.pack();
        if(body.sz.y > OptWnd.PAGE.y) {
            Scrollport port = add(new Scrollport(OptWnd.PAGE), 0, 0);
            port.cont.add(body, 0, 0);
        } else {
            add(body, 0, 0);
        }
        pack();
    }

    /* One checkbox at `at` over a switch: `read` every frame, `write` on a click. Not `set`: CheckBox has a
     * public field of that name and type, which would shadow it inside the box. */
    private static Widget toggle(Widget body, Coord at, String name, BooleanSupplier read, Consumer<Boolean> write, String tip) {
        Widget box = body.add(new CheckBox(name) {
                {a = read.getAsBoolean();}
                public void set(boolean val) {write.accept(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = read.getAsBoolean();
                }
            }, at);
        box.settip(tip, true);
        return(box);
    }

    /* One labelled slider at `y`, its value printed after it: `get` read every frame, `set` on a drag.
     * Returns the bottom of the row, the taller of the label and the slider. */
    private static int slider(Widget body, int y, String name, int min, int max, IntSupplier get, IntConsumer set,
                              IntFunction<String> fmt, String tip) {
        Label lbl = body.add(new Label(name), IND + UI.scale(5), y);
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
        Coord end = body.addhlp(Coord.of(IND + UI.scale(110), y), UI.scale(5), sl, dpy);
        sl.settip(tip, true);
        return(Math.max(lbl.pos("bl").y, end.y));
    }
}
