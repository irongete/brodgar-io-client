package io.brodgar.perf;

import java.util.ArrayList;
import java.util.List;

import haven.Audio;
import haven.ClipAmbiance;
import haven.Gob;
import haven.OCache;
import haven.RenderLink;
import haven.Resource;
import haven.UI;
import haven.Utils;
import io.brodgar.session.Sessions;

/**
 * The whole state of the <b>Performance</b> panel (spec 160-performance, task 160.1) — thirteen
 * {@code public static volatile} fields, each read from its own preference at class init and moved
 * live by one setter that persists it in the same statement (the shape {@link haven.MapView#recallon}
 * and {@link io.brodgar.ui.ClientPanel} already write in), so a click on the panel and a write from
 * {@code hafen.client():options():performance()} are the same act.
 *
 * <p>{@code flavorGeneration()} is what {@link haven.MCache.Grid#getfo} compares a cut's stamp against
 * (160.3) and {@code groundGeneration()} what {@link haven.MCache.Grid#getcut} compares against for the
 * mesh (160.4); {@code crops} and {@code forage} are read by the three adopted plant factories through
 * {@code sprouts} and followed by {@code replant()} (160.5); {@code flatTerrain} is read by
 * {@link haven.MCache#getfz} (161.1), the one tile-corner read every drawn height goes through. Every
 * setting is an exact no-op at its default, which is what lets this feature ship its whole surface
 * before a single frame draws differently.
 */
public final class Performance {
    private Performance() {}

    public static final int FLAVOR_MIN = 0;
    public static final int FLAVOR_MAX = 100;
    public static final int PLANT_MIN = 1;
    public static final int PLANT_MAX = 100;

    /* 160.2: the two resource names withholding decides by name rather than by kind (a scent trail's
     * smoke is a plume of PLUME over an owner other than CLUE, and is never withheld). */
    public static final String PLUME = "gfx/fx/ismoke";
    public static final String CLUE = "gfx/terobjs/clue";

    public static volatile int flavor = clamp(Utils.getprefi("perf-flavor", 100), FLAVOR_MIN, FLAVOR_MAX);
    public static volatile int crops = clamp(Utils.getprefi("perf-crops", 100), PLANT_MIN, PLANT_MAX);
    public static volatile int forage = clamp(Utils.getprefi("perf-forage", 100), PLANT_MIN, PLANT_MAX);
    public static volatile boolean groundBlend = Utils.getprefb("perf-groundblend", true);
    public static volatile boolean transitions = Utils.getprefb("perf-transitions", true);
    public static volatile boolean treeEffects = Utils.getprefb("perf-treeeffects", true);
    public static volatile boolean smoke = Utils.getprefb("perf-smoke", true);
    public static volatile boolean clouds = Utils.getprefb("perf-clouds", true);
    public static volatile boolean rain = Utils.getprefb("perf-rain", true);
    public static volatile boolean snow = Utils.getprefb("perf-snow", true);
    public static volatile boolean wetGround = Utils.getprefb("perf-wetground", true);
    public static volatile boolean seasonTint = Utils.getprefb("perf-seasontint", true);
    public static volatile boolean flatTerrain = Utils.getprefb("perf-flatterrain", false);

    /* The two generations a lazy cut rebuild compares its own stamp against (160.3, 160.4). Bumped only
     * when the value that feeds it actually changed, so a slider drag that repeats a value -- or a write
     * of a setting that shares no generation -- rebuilds nothing. */
    private static volatile int flavorGeneration = 0;
    private static volatile int groundGeneration = 0;

    public static int flavorGeneration() {return(flavorGeneration);}
    public static int groundGeneration() {return(groundGeneration);}

    private static int clamp(int v, int lo, int hi) {
        return(Math.max(lo, Math.min(hi, v)));
    }

    public static void flavor(int percent) {
        int clamped = clamp(percent, FLAVOR_MIN, FLAVOR_MAX);
        boolean changed = clamped != flavor;
        Utils.setprefi("perf-flavor", flavor = clamped);
        if(changed)
            flavorGeneration++;
    }

    public static void crops(int percent) {
        int clamped = clamp(percent, PLANT_MIN, PLANT_MAX);
        boolean changed = clamped != crops;
        Utils.setprefi("perf-crops", crops = clamped);
        if(changed)
            replant();
    }

    public static void forage(int percent) {
        int clamped = clamp(percent, PLANT_MIN, PLANT_MAX);
        boolean changed = clamped != forage;
        Utils.setprefi("perf-forage", forage = clamped);
        if(changed)
            replant();
    }

    public static void groundBlend(boolean on) {
        boolean changed = on != groundBlend;
        Utils.setprefb("perf-groundblend", groundBlend = on);
        if(changed)
            groundGeneration++;
    }

    public static void transitions(boolean on) {
        boolean changed = on != transitions;
        Utils.setprefb("perf-transitions", transitions = on);
        if(changed)
            groundGeneration++;
    }

    public static void treeEffects(boolean on) {
        Utils.setprefb("perf-treeeffects", treeEffects = on);
    }

    public static void smoke(boolean on) {
        boolean changed = on != smoke;
        Utils.setprefb("perf-smoke", smoke = on);
        if(changed)
            plumes();
    }

    public static void clouds(boolean on) {
        Utils.setprefb("perf-clouds", clouds = on);
    }

    public static void rain(boolean on) {
        Utils.setprefb("perf-rain", rain = on);
    }

    public static void snow(boolean on) {
        Utils.setprefb("perf-snow", snow = on);
    }

    public static void wetGround(boolean on) {
        Utils.setprefb("perf-wetground", wetGround = on);
    }

    public static void seasonTint(boolean on) {
        Utils.setprefb("perf-seasontint", seasonTint = on);
    }

    /* 161.1: the drawn cuts re-mesh lazily through the ground stamp, as transitions do. The placers
     * that cache an object's height (Gob.BasePlace, LinePlace, PlanePlace) key that cache on their
     * map's chseq, which only a grid's arrival bumps -- so every session's map is told the ground
     * moved, and Gob.Placed.autotick puts each object on the plane on its next tick rather than when
     * the next grid happens to arrive.
     * 161.2: the flavor stamp moves too -- the cliff's lip is a served flavor (gfx/tiles/flavor/ridge-edge)
     * built from the cut's ridge parts, and Tileset.Flavor.Terrain.getfz answers the drawn height; a
     * flavor built for the relief would float over the flat ridge, and the other way round. */
    public static void flatTerrain(boolean on) {
        boolean changed = on != flatTerrain;
        Utils.setprefb("perf-flatterrain", flatTerrain = on);
        if(changed) {
            groundGeneration++;
            flavorGeneration++;
            replace();
        }
    }

    /* 160.2: is this weather resource withheld right now? Checked by name in Glob.weather() (the draw
     * half) and Glob.ctick() (the simulate half); a name none of the five switches names is never
     * withheld. */
    public static boolean withheldWeather(Resource res) {
        String name = res.name;
        if("gfx/fx/clouds".equals(name))
            return(!clouds);
        if("gfx/fx/rain".equals(name))
            return(!rain);
        if("gfx/fx/snow".equals(name))
            return(!snow);
        if("gfx/fx/wet".equals(name))
            return(!wetGround);
        if("gfx/fx/seasonmap".equals(name))
            return(!seasonTint);
        return(false);
    }

    /* 160.2: is this plume overlay withheld right now? ownerRes is the gob's own drawable's resource
     * name, overlayRes the plume sprite's -- either may be null (no drawable, no sprite yet, a Loading
     * on the resource), in which case it is not a plume this decides. */
    public static boolean withheldPlume(String ownerRes, String overlayRes) {
        return(!smoke && PLUME.equals(overlayRes) && !CLUE.equals(ownerRes));
    }

    /* 160.5: how many of a plant's count sprouts are drawn at percent -- at least one, so the growth
     * stage stays readable, and every one of them at PLANT_MAX, which keeps the adopted factories on
     * upstream's own code path at the default. Read by haven.res.lib.plants.GrowingPlant,
     * TrellisPlant and haven.res.lib.gplant.GaussianPlant. */
    public static int sprouts(int count, int percent) {
        return((percent >= PLANT_MAX) ? count : Math.max(1, (int)Math.round(count * percent / 100.0)));
    }

    /* 160.3: does this flavor object's resource carry ambient sound? A piece that does is seeded at the
     * tileset's own probability whatever the flavor setting (a summer meadow at 0 keeps its crickets):
     * a ClipAmbiance.Desc layer ("clamb"), an Audio.Clip layer with id "amb", or a render link whose
     * target is an AmbientLink. Answered once per Tileset.SpriteFlavor and cached there. */
    public static boolean ambient(Resource res) {
        if(res.layer(ClipAmbiance.Desc.class) != null)
            return(true);
        if(res.layer(Audio.clip, "amb") != null)
            return(true);
        for(RenderLink.Res link : res.layers(RenderLink.Res.class)) {
            if(link.l instanceof RenderLink.AmbientLink)
                return(true);
        }
        return(false);
    }

    /* 160.2: re-decide every live plume against the setting just written -- symmetric with
     * Gob.Overlay.init()'s own gate on one not yet in a tree, so a burning kiln already on screen
     * follows a write with no relog and no server round trip. Collected under each session's OCache
     * monitor and applied outside it: Gob.plumes() defers onto that gob's own loader task, and a gob
     * cannot be walked and deferred at once without risking the OCache monitor nested inside it. */
    private static void plumes() {
        for(Sessions.Member m : Sessions.members()) {
            UI ui = m.ui;
            if((ui == null) || (ui.sess == null))
                continue;
            OCache oc = ui.sess.glob.oc;
            List<Gob> gobs = new ArrayList<Gob>();
            synchronized(oc) {
                for(Gob gob : oc)
                    gobs.add(gob);
            }
            for(Gob gob : gobs)
                gob.plumes();
        }
    }

    /* 161.1: tell every session's map its heights moved, so a placer that caches on MCache.chseq
     * re-reads the ground on its next tick. */
    private static void replace() {
        for(Sessions.Member m : Sessions.members()) {
            UI ui = m.ui;
            if((ui == null) || (ui.sess == null))
                continue;
            ui.sess.glob.map.heightschanged();
        }
    }

    /* 160.5: re-create the drawable of every plant in view after a write of crops or forage -- the
     * sprout count is fixed when the sprite is created, so a changed amount is a new sprite. The same
     * walk as plumes(): collected under each session's OCache monitor, applied outside it through
     * Gob.replant(), which defers onto the gob's own loader task. */
    private static void replant() {
        for(Sessions.Member m : Sessions.members()) {
            UI ui = m.ui;
            if((ui == null) || (ui.sess == null))
                continue;
            OCache oc = ui.sess.glob.oc;
            List<Gob> gobs = new ArrayList<Gob>();
            synchronized(oc) {
                for(Gob gob : oc)
                    gobs.add(gob);
            }
            for(Gob gob : gobs)
                gob.replant();
        }
    }
}
