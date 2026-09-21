package io.brodgar.perf;

import haven.Utils;

/**
 * The whole state of the <b>Performance</b> panel (spec 160-performance, task 160.1) — twelve
 * {@code public static volatile} fields, each read from its own preference at class init and moved
 * live by one setter that persists it in the same statement (the shape {@link haven.MapView#recallon}
 * and {@link io.brodgar.ui.ClientPanel} already write in), so a click on the panel and a write from
 * {@code hafen.client():options():performance()} are the same act.
 *
 * <p>Nothing here reads a consumer's seam yet: {@code flavorGeneration()} and {@code groundGeneration()}
 * are bumped by their setters but consumed by nobody until 160.3 and 160.4 compare a cut's stamp
 * against them, and the seven remaining setters have no follower at all until 160.2 and 160.5 give
 * {@code smoke}, {@code crops} and {@code forage} one. Every setting is an exact no-op at its default,
 * which is what lets this task ship the whole surface before a single frame draws differently.
 */
public final class Performance {
    private Performance() {}

    public static final int FLAVOR_MIN = 0;
    public static final int FLAVOR_MAX = 100;
    public static final int PLANT_MIN = 1;
    public static final int PLANT_MAX = 100;

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
        Utils.setprefb("perf-smoke", smoke = on);
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
}
