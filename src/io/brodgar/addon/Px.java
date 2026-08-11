package io.brodgar.addon;

import haven.Coord;
import haven.ScaledTex;
import haven.TexI;
import haven.UI;

/**
 * <b>The unit at the widget boundary.</b> Every number {@code hafen.ui} takes or gives for a coordinate or a
 * size is a <b>design pixel</b> — the space the client's own art is authored in — and this class is the one
 * place that converts to and from the <b>device</b> pixels {@code haven} lays out and draws in.
 *
 * <p><b>One seam, and it is greppable.</b> Nothing else in the bridge converts a coordinate that <i>crosses</i>
 * into Lua: {@code grep -rn "UI\.scale\|UI\.unscale" src/io/brodgar/addon/} answers this file, {@code FontHandle}
 * (a <i>type</i> size, which was always design), {@code CScrollport} (a wheel step, which is device by nature)
 * and {@code UiApi.fitc} (the client's own graspability margin, re-derived in the client's own space, below this
 * seam and never above it). A crossing that skips this class is the bug it exists to make visible.
 *
 * <p><b>The round-trip is exact, and that is the whole guarantee.</b> {@code UI.scalef} is clamped to
 * {@code >= 1.0} ({@code UI.loadscale}), so for every {@code n} and every scale {@code s >= 1}:
 * {@code in(n) = round(n·s)} is within {@code 0.5} of {@code n·s}, hence {@code in(n)/s} is within
 * {@code 0.5/s <= 0.5} of {@code n} — and strictly within it whenever {@code s > 1}, since the rounding error
 * is then strictly under {@code 0.5·s}. So {@code out(in(n)) == n}: <b>a number an addon writes reads back
 * unchanged, at every scale.</b>
 *
 * <p>The residual is honest and documented: the inverse does <i>not</i> hold. A <i>native</i> widget's device
 * position is not generally a whole number of design pixels, so reading one and writing it straight back may
 * shift it by under a design pixel. That is the price of naming one unit, and it is paid only where an addon
 * hands the client's own coordinate back to the client.
 */
final class Px {
    private Px() {}

    /** Design &rarr; device: what an addon wrote, in the space {@code haven} lays out in. */
    static int in(int n) {
        return UI.scale(n);
    }

    /** Design &rarr; device, both axes. */
    static Coord in(Coord c) {
        return (c == null) ? null : UI.scale(c);
    }

    /**
     * Design &rarr; device, unrounded — for the draw surface's fractional lengths ({@code g:poly}'s vertices,
     * {@code g:line}'s width), which reach the GPU as floats and have no pixel to round to. A whole design
     * pixel still lands on the same number {@link #in(int)} gives it, so the two never disagree about a corner.
     */
    static double in(double n) {
        return UI.scale(n);
    }

    /**
     * Design &rarr; device for a whole <b>raster</b>: the same texture, viewed at the size it is drawn. An
     * addon's own PNG is authored in design pixels, so this is what makes {@code g:image} cover the
     * {@code img:size()} the addon read — and the client's own {@code .res} art needs it not at all, being
     * device-sized from the moment it loaded ({@code Resource.Image.scaled()}).
     */
    static ScaledTex<TexI> in(TexI tex) {
        return (tex == null) ? null : UI.scale(tex);
    }

    /** Device &rarr; design: what the client has, in the space the API speaks. */
    static int out(int n) {
        return UI.unscale(n);
    }

    /** Device &rarr; design, both axes. */
    static Coord out(Coord c) {
        return (c == null) ? null : UI.unscale(c);
    }

    /**
     * The running device factor ({@code hafen.ui():scale()}) — {@code 1.0} on an unscaled client, {@code >= 1.0}
     * always. It is the factor <b>in force</b>, read off the live {@code UI}, and not the persisted preference
     * {@code hafen.client():options():interface():scale()} answers: the pref defaults to {@code 1.0} where the
     * client's own default is derived from the display's density, and it is not clamped to the display's maximum.
     */
    static double factor() {
        return UI.scale(1.0);
    }
}
