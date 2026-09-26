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
import haven.RadioGroup;
import haven.Scrollport;
import haven.UI;
import haven.Widget;

import io.brodgar.ambience.Ambience;
import io.brodgar.perf.Performance;

/**
 * The in-game <b>Sky &amp; weather</b> options panel -- {@link PerformancePanel}'s
 * pattern over {@link Ambience}: every control re-reads its static every frame in {@code tick} and
 * writes through the matching setter, so the console's {@code :amb} moves an open panel. At the top, a
 * choice between the client's own sky and weather and the game's own; under it, the settings of the one
 * picked, and only those.
 */
public class SkyPanel extends OptWnd.Panel {
    public SkyPanel(OptWnd opt) {
        opt.super();

        /* The page is built in a body of its own, and stands in a scrolled port the size of the settings
         * view's whole page box when it is taller than that box. */
        Widget body = new Widget(Coord.z);

        /* The one switch, as a choice between the two: under it, the settings of the one picked. */
        Widget prev = body.add(new Label("Sky and weather effects"), Coord.z);
        RadioGroup mode = new RadioGroup(body) {
                public void changed(int btn, String lbl) {
                    boolean ours = (btn == 0);
                    if(Ambience.on != ours)
                        Ambience.on(ours);
                }
            };
        prev = mode.add("Improved sky and weather effects", prev.pos("bl").adds(5, 10));
        prev.settip("Draws the sky and the weather with the client's own renderer instead of the game's: a sky"
                    + " with its sun, stars and moon, drifting clouds, distance fog, and rain and snow on the"
                    + " graphics card. Each part below is switched on its own. Applies at once.", true);
        prev = mode.add("The game's own weather effects", prev.pos("bl").adds(0, 5));
        prev.settip("The game draws the sky and the weather as it always has. Applies at once.", true);
        mode.check(Ambience.on ? 0 : 1);
        int top = prev.pos("bl").y + UI.scale(15);

        Widget ours = body.add(ours(), Coord.of(0, top));
        Widget game = body.add(game(), Coord.of(0, top));

        /* The port is decided by the taller of the two; the body then takes the size of the one shown, so
         * the bar scrolls only what is there. */
        body.pack();
        Scrollport port;
        if(body.sz.y > OptWnd.PAGE.y) {
            port = add(new Scrollport(OptWnd.PAGE), 0, 0);
            port.cont.add(body, 0, 0);
        } else {
            port = null;
            add(body, 0, 0);
        }
        pack();
        ours.show(Ambience.on);
        game.show(!Ambience.on);
        body.pack();

        /* Kept to Ambience.on every frame, so the console's :amb moves an open page. */
        body.add(new Widget(Coord.z) {
                boolean shown = Ambience.on;
                public void tick(double dt) {
                    super.tick(dt);
                    if(shown != Ambience.on) {
                        shown = Ambience.on;
                        mode.check(shown ? 0 : 1);
                        ours.show(shown);
                        game.show(!shown);
                        body.pack();
                        if(port != null)
                            port.bar.ch(-port.bar.val);
                    }
                }
            }, Coord.z);
    }

    /* The client's own sky and weather: four sections, the sky, the clouds, the fog and the rain and snow,
     * each switched on its own and holding its own settings, and the game's ground effects, which stay the
     * game's under either sky. */
    private static Widget ours() {
        Widget body = new Widget(Coord.z);
        Widget prev = body.add(new Label("Sky"), Coord.z);
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

        prev = body.add(new Label("Fog"), Coord.of(0, y + UI.scale(15)));
        prev = toggle(body, prev.pos("bl").adds(0, 10), "Fog", () -> Ambience.fogon, Ambience::fogon,
                      "Distance fog the colour of the sky, so the far ground dissolves into the horizon"
                      + " instead of ending at it. Rain and snow thicken it. Applies at once.");
        y = slider(body, prev.pos("bl").y + UI.scale(8), "Density", 0, 200,
               () -> Math.round(Ambience.fogmul * 100), v -> Ambience.fog(v / 100f),
               v -> (v == 0) ? "None" : (v + " %"),
               "How thick the distance fog is against the weather's own: 100 % is as the weather has it.");

        prev = body.add(new Label("Rain and snow"), Coord.of(0, y + UI.scale(15)));
        prev = toggle(body, prev.pos("bl").adds(0, 10), "Rain and snow", () -> Ambience.precipon, Ambience::precipon,
                      "Draws the rain and the snow the server sends on the graphics card, looking as the game's"
                      + " own: the same drops, splashes and flakes, as many of them. None falls indoors or"
                      + " underground, and a blizzard is not held to the game's 50,000 flakes. Off, the game's"
                      + " own come back. Applies at once.");

        prev = body.add(new Label("Ground"), prev.pos("bl").adds(0, 15));
        prev = ground(body, prev.pos("bl").adds(0, 10));

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
        return(body);
    }

    /* The game's own weather effects, each switched on its own: Performance's, which reach only what the
     * game draws. */
    private static Widget game() {
        Widget body = new Widget(Coord.z);
        Widget prev = toggle(body, Coord.z, "Cloud shadows", () -> Performance.clouds, Performance::clouds,
                             "The game's own cloud shadows moving over the ground. Takes effect the next frame.");
        prev = toggle(body, prev.pos("bl").adds(0, 5), "Rain", () -> Performance.rain, Performance::rain,
                      "The game's own rain particles and their splashes. Takes effect the next frame.");
        prev = toggle(body, prev.pos("bl").adds(0, 5), "Snow", () -> Performance.snow, Performance::snow,
                      "The game's own snow particles. Takes effect the next frame.");
        ground(body, prev.pos("bl").adds(0, 5));
        body.pack();
        return(body);
    }

    /* The game's ground effects, drawn by the game under either sky. Returns the last box. */
    private static Widget ground(Widget body, Coord at) {
        Widget prev = toggle(body, at, "Wet ground", () -> Performance.wetGround, Performance::wetGround,
                             "The sheen the ground takes on after rain: the game's own, under either sky. Takes"
                             + " effect the next frame.");
        return(toggle(body, prev.pos("bl").adds(0, 5), "Seasonal tint", () -> Performance.seasonTint, Performance::seasonTint,
                      "The seasonal tint of the ground: the game's own, under either sky. Takes effect the next"
                      + " frame."));
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
        Label lbl = body.add(new Label(name), UI.scale(5), y);
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
        Coord end = body.addhlp(Coord.of(UI.scale(110), y), UI.scale(5), sl, dpy);
        sl.settip(tip, true);
        return(Math.max(lbl.pos("bl").y, end.y));
    }
}
