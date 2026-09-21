package io.brodgar.ui;

import haven.CheckBox;
import haven.Coord;
import haven.HSlider;
import haven.Label;
import haven.OptWnd;
import haven.UI;
import haven.Widget;

import io.brodgar.perf.Performance;

/**
 * The in-game <b>Performance</b> options panel (spec 160-performance, task 160.1) — a clone of
 * {@link ClientPanel}'s pattern over {@link Performance} rather than {@link haven.MapView}: every
 * control re-reads its static every frame in {@code tick} and writes through the matching setter in
 * {@code set}/{@code changed}, so a Lua write moves an open panel and a click is the same act as a
 * write from {@code hafen.client():options():performance()}.
 *
 * <p>First entry of {@link haven.OptWnd.SettingsPanel#gamepanels()}, which is why the Options window
 * opens on it.
 */
public class PerformancePanel extends OptWnd.Panel {
    public PerformancePanel(OptWnd opt) {
        opt.super();

        Widget prev = add(new Label("Ground"), 0, 0);

        Label flavorLbl = add(new Label("Flavor objects"), prev.pos("bl").adds(5, 10));
        Coord flavorEnd;
        {
            Label dpy = new Label("");
            HSlider sl = new HSlider(UI.scale(140), Performance.FLAVOR_MIN, Performance.FLAVOR_MAX,
                                     Performance.flavor) {
                    protected void added() {
                        dpy();
                    }
                    void dpy() {
                        dpy.settext(this.val + " %");
                    }
                    public void changed() {
                        Performance.flavor(this.val);
                        dpy();
                    }
                    public void tick(double dt) {
                        super.tick(dt);
                        if(this.val != Performance.flavor) {
                            this.val = Performance.flavor;
                            dpy();
                        }
                    }
                };
            flavorEnd = addhlp(Coord.of(flavorLbl.pos("ur").x + UI.scale(5), flavorLbl.c.y), UI.scale(5), sl, dpy);
            sl.settip("How many of the flavor objects a tile seeds are drawn: the tufts, pebbles and flowers"
                      + " scattered over its ground. At 0, the pieces that make ambient sound are still"
                      + " drawn. Ground already on screen is rebuilt as it comes back into view.", true);
        }

        prev = add(new CheckBox("Blend ground textures") {
                {a = Performance.groundBlend;}
                public void set(boolean val) {Performance.groundBlend(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.groundBlend;
                }
            }, Coord.of(0, Math.max(flavorLbl.pos("bl").y, flavorEnd.y) + UI.scale(8)));
        prev.settip("Whether the ground blends its texture variants by noise. Off, every tile draws its"
                    + " tileset's base texture alone: one layer of ground where there were up to several."
                    + " Ground already on screen is rebuilt as it comes back into view.", true);

        prev = add(new CheckBox("Tile transitions") {
                {a = Performance.transitions;}
                public void set(boolean val) {Performance.transitions(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.transitions;
                }
            }, prev.pos("bl").adds(0, 5));
        prev.settip("Whether the skirts between two tile types are drawn. Off, tile borders are hard edges."
                    + " Ground already on screen is rebuilt as it comes back into view.", true);

        prev = add(new CheckBox("Flat terrain") {
                {a = Performance.flatTerrain;}
                public void set(boolean val) {Performance.flatTerrain(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.flatTerrain;
                }
            }, prev.pos("bl").adds(0, 5));
        prev.settip("Draws the whole world at one height, so nothing is hidden behind a hill. Cliffs stand"
                    + " as walls one tile high; water keeps its depth. Not a performance setting: the same"
                    + " ground is drawn at another height. Applies live, cut by cut.", true);

        prev = add(new Label("Plants"), prev.pos("bl").adds(0, 15));

        Label cropLbl = add(new Label("Crop density"), prev.pos("bl").adds(5, 10));
        Coord cropEnd;
        {
            Label dpy = new Label("");
            HSlider sl = new HSlider(UI.scale(140), Performance.PLANT_MIN, Performance.PLANT_MAX,
                                     Performance.crops) {
                    protected void added() {
                        dpy();
                    }
                    void dpy() {
                        dpy.settext(this.val + " %");
                    }
                    public void changed() {
                        Performance.crops(this.val);
                        dpy();
                    }
                    public void tick(double dt) {
                        super.tick(dt);
                        if(this.val != Performance.crops) {
                            this.val = Performance.crops;
                            dpy();
                        }
                    }
                };
            cropEnd = addhlp(Coord.of(cropLbl.pos("ur").x + UI.scale(5), cropLbl.c.y), UI.scale(5), sl, dpy);
            sl.settip("How many of a crop tile's sprouts are drawn, field crops and trellis crops alike. At"
                      + " least one sprout is always drawn, so the growth stage stays readable. Plants in"
                      + " view are re-created on a change.", true);
        }

        Label forageLbl = add(new Label("Forageable density"),
                              Coord.of(0, Math.max(cropLbl.pos("bl").y, cropEnd.y) + UI.scale(8)));
        Coord forageEnd;
        {
            Label dpy = new Label("");
            HSlider sl = new HSlider(UI.scale(140), Performance.PLANT_MIN, Performance.PLANT_MAX,
                                     Performance.forage) {
                    protected void added() {
                        dpy();
                    }
                    void dpy() {
                        dpy.settext(this.val + " %");
                    }
                    public void changed() {
                        Performance.forage(this.val);
                        dpy();
                    }
                    public void tick(double dt) {
                        super.tick(dt);
                        if(this.val != Performance.forage) {
                            this.val = Performance.forage;
                            dpy();
                        }
                    }
                };
            forageEnd = addhlp(Coord.of(forageLbl.pos("ur").x + UI.scale(5), forageLbl.c.y), UI.scale(5), sl, dpy);
            sl.settip("The same, for forageables that grow as a clump. At least one sprout is always"
                      + " drawn. Plants in view are re-created on a change.", true);
        }

        prev = add(new Label("Objects"),
                   Coord.of(0, Math.max(forageLbl.pos("bl").y, forageEnd.y) + UI.scale(15)));

        prev = add(new CheckBox("Tree effects") {
                {a = Performance.treeEffects;}
                public void set(boolean val) {Performance.treeEffects(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.treeEffects;
                }
            }, prev.pos("bl").adds(0, 10));
        prev.settip("Whether trees and bushes sway in the wind. Off, they stand still; their random tilt"
                    + " stays. Takes effect the next tick.", true);

        prev = add(new CheckBox("Smoke plumes") {
                {a = Performance.smoke;}
                public void set(boolean val) {Performance.smoke(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.smoke;
                }
            }, prev.pos("bl").adds(0, 5));
        prev.settip("Whether smoke plumes are drawn: kilns, furnaces, ovens, chimneys, fires. Applies at"
                    + " once, without the server re-sending anything. A scent trail's smoke is never"
                    + " withheld.", true);

        prev = add(new Label("Weather"), prev.pos("bl").adds(0, 15));

        prev = add(new CheckBox("Cloud shadows") {
                {a = Performance.clouds;}
                public void set(boolean val) {Performance.clouds(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.clouds;
                }
            }, prev.pos("bl").adds(0, 10));
        prev.settip("Cloud shadows moving over the ground. Takes effect the next frame.", true);

        prev = add(new CheckBox("Rain") {
                {a = Performance.rain;}
                public void set(boolean val) {Performance.rain(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.rain;
                }
            }, prev.pos("bl").adds(0, 5));
        prev.settip("Rain particles and their splashes. Takes effect the next frame.", true);

        prev = add(new CheckBox("Snow") {
                {a = Performance.snow;}
                public void set(boolean val) {Performance.snow(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.snow;
                }
            }, prev.pos("bl").adds(0, 5));
        prev.settip("Snow particles. Takes effect the next frame.", true);

        prev = add(new CheckBox("Wet ground") {
                {a = Performance.wetGround;}
                public void set(boolean val) {Performance.wetGround(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.wetGround;
                }
            }, prev.pos("bl").adds(0, 5));
        prev.settip("The sheen the ground takes on after rain. Takes effect the next frame.", true);

        prev = add(new CheckBox("Seasonal tint") {
                {a = Performance.seasonTint;}
                public void set(boolean val) {Performance.seasonTint(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.seasonTint;
                }
            }, prev.pos("bl").adds(0, 5));
        prev.settip("The seasonal tint of the ground. Takes effect the next frame.", true);

        pack();
    }
}
