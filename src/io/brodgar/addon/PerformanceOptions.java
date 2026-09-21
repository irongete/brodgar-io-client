package io.brodgar.addon;

import io.brodgar.perf.Performance;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * The Performance options subsystem (spec 160-performance, task 160.1) —
 * {@code hafen.client():options():performance()}, the Lua side of the Options ▸ Game ▸ <b>Performance</b>
 * panel ({@link io.brodgar.ui.PerformancePanel}). A clone of {@link ClientOptions}'s shape: the handle
 * lives here, the engine state on {@link io.brodgar.perf.Performance}.
 *
 * <p>Every write goes through the matching {@link Performance} setter, exactly the call the panel's own
 * control makes, so a Lua write and a click are indistinguishable: both move the live field, persist it
 * and — where the setting has a follower — run it.
 */
public final class PerformanceOptions {
    private PerformanceOptions() {}

    /** Create the performance options subsystem handle. */
    public static LuaValue create(final Addon owner) {
        LuaValue handle = OptionsHandle.open("Options(performance)");
        return OptionsHandle.close(handle, "performance", methods(owner, handle),
            "the performance options", "each of them reads with no argument and writes with one");
    }

    private static LuaTable methods(final Addon owner, final LuaValue handle) {
        LuaTable m = new LuaTable();

        m.set("flavor", new OptionsMethod(owner, handle, "performance:flavor") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Performance.flavor);
            }
            protected void onWrite(LuaValue value) {
                Performance.flavor((int)Args.integer(value, verb(), "percent",
                    "how many of the flavor objects are drawn, in percent",
                    Performance.FLAVOR_MIN, Performance.FLAVOR_MAX));
            }
        });
        m.set("crops", new OptionsMethod(owner, handle, "performance:crops") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Performance.crops);
            }
            protected void onWrite(LuaValue value) {
                Performance.crops((int)Args.integer(value, verb(), "percent",
                    "how many of a crop tile's sprouts are drawn, in percent",
                    Performance.PLANT_MIN, Performance.PLANT_MAX));
            }
        });
        m.set("forage", new OptionsMethod(owner, handle, "performance:forage") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Performance.forage);
            }
            protected void onWrite(LuaValue value) {
                Performance.forage((int)Args.integer(value, verb(), "percent",
                    "how many of a forageable clump's sprouts are drawn, in percent",
                    Performance.PLANT_MIN, Performance.PLANT_MAX));
            }
        });
        m.set("groundBlend", new OptionsMethod(owner, handle, "performance:groundBlend") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Performance.groundBlend);
            }
            protected void onWrite(LuaValue value) {
                Performance.groundBlend(bool(value, "on",
                    "whether the ground blends its texture variants by noise"));
            }
        });
        m.set("transitions", new OptionsMethod(owner, handle, "performance:transitions") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Performance.transitions);
            }
            protected void onWrite(LuaValue value) {
                Performance.transitions(bool(value, "on",
                    "whether the skirts between two tile types are drawn"));
            }
        });
        m.set("treeEffects", new OptionsMethod(owner, handle, "performance:treeEffects") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Performance.treeEffects);
            }
            protected void onWrite(LuaValue value) {
                Performance.treeEffects(bool(value, "on", "whether trees and bushes sway in the wind"));
            }
        });
        m.set("smoke", new OptionsMethod(owner, handle, "performance:smoke") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Performance.smoke);
            }
            protected void onWrite(LuaValue value) {
                Performance.smoke(bool(value, "on", "whether smoke plumes are drawn"));
            }
        });
        m.set("clouds", new OptionsMethod(owner, handle, "performance:clouds") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Performance.clouds);
            }
            protected void onWrite(LuaValue value) {
                Performance.clouds(bool(value, "on", "whether cloud shadows cross the ground"));
            }
        });
        m.set("rain", new OptionsMethod(owner, handle, "performance:rain") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Performance.rain);
            }
            protected void onWrite(LuaValue value) {
                Performance.rain(bool(value, "on", "whether rain particles and their splashes are drawn"));
            }
        });
        m.set("snow", new OptionsMethod(owner, handle, "performance:snow") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Performance.snow);
            }
            protected void onWrite(LuaValue value) {
                Performance.snow(bool(value, "on", "whether snow particles are drawn"));
            }
        });
        m.set("wetGround", new OptionsMethod(owner, handle, "performance:wetGround") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Performance.wetGround);
            }
            protected void onWrite(LuaValue value) {
                Performance.wetGround(bool(value, "on", "whether the ground shines after rain"));
            }
        });
        m.set("seasonTint", new OptionsMethod(owner, handle, "performance:seasonTint") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Performance.seasonTint);
            }
            protected void onWrite(LuaValue value) {
                Performance.seasonTint(bool(value, "on", "whether the ground carries its seasonal tint"));
            }
        });
        m.set("flatTerrain", new OptionsMethod(owner, handle, "performance:flatTerrain") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Performance.flatTerrain);
            }
            protected void onWrite(LuaValue value) {
                Performance.flatTerrain(bool(value, "on", "whether the terrain is drawn flat"));
            }
        });

        return m;
    }
}
