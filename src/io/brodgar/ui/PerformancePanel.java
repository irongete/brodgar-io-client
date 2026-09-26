package io.brodgar.ui;

import haven.CheckBox;
import haven.Coord;
import haven.GSettings;
import haven.GameUI;
import haven.HSlider;
import haven.Label;
import haven.MapView;
import haven.OptWnd;
import haven.Scrollport;
import haven.UI;
import haven.Utils;
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

        /* The page is built in a body of its own, and stands in a scrolled port the size of
         * the settings view's whole page box when it is taller than that box. */
        Widget body = new Widget(Coord.z);

        Widget prev = body.add(new Label("Rendering"), 0, 0);

        /* The two Video settings that trade looks for speed, as they were there: GSettings, applied through
         * ui.setgprefs and re-read every frame, so a change made anywhere else moves the box. Read in tick
         * alone: the box is built into a body not yet in the tree, where ui is still null, and the first
         * tick comes before the first draw. */
        prev = body.add(new CheckBox("Render shadows") {
                public void set(boolean val) {
                    try {
                        ui.setgprefs(ui.gprefs.update(null, ui.gprefs.lshadow, val));
                        a = val;
                    } catch(GSettings.SettingException e) {
                        error(this, e);
                    }
                }
                public void tick(double dt) {
                    super.tick(dt);
                    a = ui.gprefs.lshadow.val;
                }
            }, prev.pos("bl").adds(0, 10));
        prev.settip("Whether the sun and the moon cast shadows. Off, the scene is drawn once less every"
                    + " frame, into the shadow map.", true);

        prev = body.add(new CheckBox("Cull off-screen terrain") {
                public void set(boolean val) {
                    try {
                        ui.setgprefs(ui.gprefs.update(null, ui.gprefs.cullterrain, val));
                        a = val;
                    } catch(GSettings.SettingException e) {
                        error(this, e);
                    }
                }
                public void tick(double dt) {
                    super.tick(dt);
                    a = ui.gprefs.cullterrain.val;
                }
            }, prev.pos("bl").adds(0, 5));
        prev.settip("Leaves out the ground the camera cannot see. Ground left out casts no shadow either, so a"
                    + " hill behind the camera may lose the shadow it throws into view. Applies the next frame.", true);

        /* The view distance: MapView's two recall statics, written live-and-persisted in one statement the
         * way hafen.client():options():client() writes them. */
        prev = body.add(new CheckBox("View distance") {
                {a = MapView.recallon;}
                public void set(boolean val) {Utils.setprefb("recallon", MapView.recallon = val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = MapView.recallon;
                }
            }, prev.pos("bl").adds(0, 5));
        prev.settip("Draws the ground your character has already explored past what the server streams, out of"
                    + " the client's own map database, under every camera. Nothing is asked of the server for"
                    + " it, and nothing stands on it.", true);

        Label rangeLbl = body.add(new Label("Range"), prev.pos("bl").adds(5, 5));
        Coord rangeEnd;
        {
            Label dpy = new Label("");
            /* The knob stands at the panel's floor when a 1 was written from Lua; the label says the 1. */
            HSlider sl = new HSlider(UI.scale(140), MapView.recallrangepanel, MapView.recallrangemax,
                                     Math.max(MapView.recallrange, MapView.recallrangepanel)) {
                    protected void added() {
                        dpy();
                    }
                    int said = -1;
                    void dpy() {
                        int r = MapView.recallrange;
                        if(r != said)
                            dpy.settext(r + ((r == 1) ? " grid" : " grids"));
                        said = r;
                    }
                    public void changed() {
                        Utils.setprefi("recallrange", MapView.recallrange = this.val);
                        dpy();
                    }
                    public void tick(double dt) {
                        super.tick(dt);
                        int shown = Math.max(MapView.recallrange, MapView.recallrangepanel);
                        if(this.val != shown)
                            this.val = shown;
                        dpy();
                    }
                };
            rangeEnd = body.addhlp(Coord.of(rangeLbl.pos("ur").x + UI.scale(5), rangeLbl.c.y), UI.scale(5), sl, dpy);
            sl.settip("How far around the camera explored ground is drawn, in grids. A grid is 100 tiles across."
                      + " A larger range reaches further and costs more memory and more meshes to build; what is"
                      + " drawn is bounded by the view and by the client's own mesh budget either way.", true);
        }

        prev = body.add(new Label("Ground"),
                        Coord.of(0, Math.max(rangeLbl.pos("bl").y, rangeEnd.y) + UI.scale(15)));

        Label flavorLbl = body.add(new Label("Flavor objects"), prev.pos("bl").adds(5, 10));
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
            flavorEnd = body.addhlp(Coord.of(flavorLbl.pos("ur").x + UI.scale(5), flavorLbl.c.y), UI.scale(5), sl, dpy);
            sl.settip("How many of the flavor objects a tile seeds are drawn: the tufts, pebbles and flowers"
                      + " scattered over its ground. At 0, the pieces that make ambient sound are still"
                      + " drawn. Ground already on screen is rebuilt as it comes back into view.", true);
        }

        Label blendLbl = body.add(new Label("Blend ground textures"),
                             Coord.of(flavorLbl.c.x, Math.max(flavorLbl.pos("bl").y, flavorEnd.y) + UI.scale(8)));
        Coord blendEnd;
        {
            Label dpy = new Label("");
            HSlider sl = new HSlider(UI.scale(140), Performance.BLEND_MIN, Performance.BLEND_MAX,
                                     Performance.groundBlend) {
                    protected void added() {
                        dpy();
                    }
                    void dpy() {
                        dpy.settext((this.val == Performance.BLEND_MIN) ? "Off" : String.valueOf(this.val));
                    }
                    public void changed() {
                        Performance.groundBlend(this.val);
                        dpy();
                    }
                    public void tick(double dt) {
                        super.tick(dt);
                        if(this.val != Performance.groundBlend) {
                            this.val = Performance.groundBlend;
                            dpy();
                        }
                    }
                };
            blendEnd = body.addhlp(Coord.of(blendLbl.pos("ur").x + UI.scale(5), blendLbl.c.y), UI.scale(5), sl, dpy);
            sl.settip("How softly the ground blends its texture variants: the number of smoothing passes."
                      + " Fewer passes build the ground faster and leave harder edges between variants, which"
                      + " draw fewer layers. Off, every tile draws its tileset's base texture alone. Ground"
                      + " already on screen is rebuilt as it comes back into view.", true);
        }

        prev = body.add(new CheckBox("Tile transitions") {
                {a = Performance.transitions;}
                public void set(boolean val) {Performance.transitions(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.transitions;
                }
            }, Coord.of(0, Math.max(blendLbl.pos("bl").y, blendEnd.y) + UI.scale(8)));
        prev.settip("Whether the skirts between two tile types are drawn. Off, tile borders are hard edges."
                    + " Ground already on screen is rebuilt as it comes back into view.", true);

        prev = body.add(new CheckBox("Flat terrain") {
                {a = Performance.flatTerrain;}
                public void set(boolean val) {Performance.flatTerrain(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.flatTerrain;
                }
            }, prev.pos("bl").adds(0, 5));
        prev.settip("Draws the whole world at one height, so nothing is hidden behind a hill. Cliffs stand"
                    + " on the plane at their real height; water keeps its depth. Not a performance setting: the same"
                    + " ground is drawn at another height. Applies live, cut by cut.", true);

        prev = body.add(new Label("Plants"), prev.pos("bl").adds(0, 15));

        Label cropLbl = body.add(new Label("Crop density"), prev.pos("bl").adds(5, 10));
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
            cropEnd = body.addhlp(Coord.of(cropLbl.pos("ur").x + UI.scale(5), cropLbl.c.y), UI.scale(5), sl, dpy);
            sl.settip("How many of a crop tile's sprouts are drawn, field crops and trellis crops alike. At"
                      + " least one sprout is always drawn, so the growth stage stays readable. Plants in"
                      + " view are re-created on a change.", true);
        }

        Label forageLbl = body.add(new Label("Forageable density"),
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
            forageEnd = body.addhlp(Coord.of(forageLbl.pos("ur").x + UI.scale(5), forageLbl.c.y), UI.scale(5), sl, dpy);
            sl.settip("The same, for forageables that grow as a clump. At least one sprout is always"
                      + " drawn. Plants in view are re-created on a change.", true);
        }

        prev = body.add(new Label("Objects"),
                   Coord.of(0, Math.max(forageLbl.pos("bl").y, forageEnd.y) + UI.scale(15)));

        prev = body.add(new CheckBox("Tree effects") {
                {a = Performance.treeEffects;}
                public void set(boolean val) {Performance.treeEffects(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.treeEffects;
                }
            }, prev.pos("bl").adds(0, 10));
        prev.settip("Whether trees and bushes sway in the wind. Off, they stand still; their random tilt"
                    + " stays. Takes effect the next tick.", true);

        prev = body.add(new CheckBox("Smoke plumes") {
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

        prev = body.add(new Label("Weather"), prev.pos("bl").adds(0, 15));

        prev = body.add(new CheckBox("Cloud shadows") {
                {a = Performance.clouds;}
                public void set(boolean val) {Performance.clouds(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.clouds;
                }
            }, prev.pos("bl").adds(0, 10));
        prev.settip("Cloud shadows moving over the ground, the game's or, with Sky & weather on, its clouds'."
                    + " Takes effect the next frame.", true);

        prev = body.add(new CheckBox("Rain") {
                {a = Performance.rain;}
                public void set(boolean val) {Performance.rain(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.rain;
                }
            }, prev.pos("bl").adds(0, 5));
        prev.settip("Rain particles and their splashes. Takes effect the next frame.", true);

        prev = body.add(new CheckBox("Snow") {
                {a = Performance.snow;}
                public void set(boolean val) {Performance.snow(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.snow;
                }
            }, prev.pos("bl").adds(0, 5));
        prev.settip("Snow particles. Takes effect the next frame.", true);

        prev = body.add(new CheckBox("Wet ground") {
                {a = Performance.wetGround;}
                public void set(boolean val) {Performance.wetGround(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.wetGround;
                }
            }, prev.pos("bl").adds(0, 5));
        prev.settip("The sheen the ground takes on after rain. Takes effect the next frame.", true);

        prev = body.add(new CheckBox("Seasonal tint") {
                {a = Performance.seasonTint;}
                public void set(boolean val) {Performance.seasonTint(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Performance.seasonTint;
                }
            }, prev.pos("bl").adds(0, 5));
        prev.settip("The seasonal tint of the ground. Takes effect the next frame.", true);

        body.pack();
        if(body.sz.y > OptWnd.PAGE.y) {
            Scrollport port = add(new Scrollport(OptWnd.PAGE), 0, 0);
            port.cont.add(body, 0, 0);
        } else {
            add(body, 0, 0);
        }
        pack();
    }

    /* A setting the environment refused, said where the client says such things. */
    private static void error(Widget w, GSettings.SettingException e) {
        GameUI gui = w.getparent(GameUI.class);
        if(gui != null)
            gui.error(e.getMessage());
    }
}
