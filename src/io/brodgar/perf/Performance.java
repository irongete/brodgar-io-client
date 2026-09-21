package io.brodgar.perf;

import java.util.ArrayList;
import java.util.List;

import haven.Gob;
import haven.OCache;
import haven.Resource;
import haven.UI;
import haven.Utils;
import io.brodgar.session.Sessions;

/**
 * The whole state of the <b>Performance</b> panel (spec 160-performance, task 160.1) — twelve
 * {@code public static volatile} fields, each read from its own preference at class init and moved
 * live by one setter that persists it in the same statement (the shape {@link haven.MapView#recallon}
 * and {@link io.brodgar.ui.ClientPanel} already write in), so a click on the panel and a write from
 * {@code hafen.client():options():performance()} are the same act.
 *
 * <p>{@code flavorGeneration()} and {@code groundGeneration()} are bumped by their setters but consumed
 * by nobody until 160.3 and 160.4 compare a cut's stamp against them. {@code crops} and {@code forage}
 * likewise have no follower until 160.5 gives them {@code replant()}. Every setting is an exact no-op
 * at its default, which is what lets this feature ship its whole surface before a single frame draws
 * differently.
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
        Utils.setprefi("perf-crops", crops = clamp(percent, PLANT_MIN, PLANT_MAX));
    }

    public static void forage(int percent) {
        Utils.setprefi("perf-forage", forage = clamp(percent, PLANT_MIN, PLANT_MAX));
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
}
