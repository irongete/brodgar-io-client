package io.brodgar.rts;

import haven.render.FragColor;
import haven.render.Pipe;
import haven.render.State;
import haven.render.sl.Expression;
import haven.render.sl.ShaderMacro;
import haven.render.sl.Uniform;

import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/**
 * Draws everything below it in the render tree without colour.
 *
 * <p>A translucent sheet laid over ground cannot do this. Alpha blending interpolates toward one
 * colour, so a white sheet moves every fragment toward white by the same fraction and the hues
 * underneath survive it — the ground goes pale and stays green, brown and blue. Taking the colour
 * out is a per-fragment operation on the fragment's own channels, and the only place that can be
 * done is the fragment shader.
 *
 * <p><b>Why a state above the materials reaches them.</b> Each tileset brings its own
 * {@code Material}, so there is no colour to overwrite from outside — but a shader program in this
 * engine is not compiled per material. It is compiled from the <i>composed</i> {@code Pipe} of the
 * slot being drawn, and every {@link State} in that composition contributes its
 * {@link ShaderMacro}. So a state installed once at a subtree's root is compiled into the program
 * of every material under it, and {@code FragColor.fragcol(...).mod(fn, order)} appends {@code fn}
 * to the chain that produces the final fragment colour. {@code BaseColor} and {@code haven.ColorMask}
 * are the same mechanism; this one runs after both.
 *
 * <p><b>What it costs is one shader program.</b> No extra geometry, no extra draw, no extra pass —
 * the cuts below it were being drawn anyway and are now drawn by a program with three more
 * instructions in it. The program is compiled once, on the first frame that needs it. The amount is
 * a {@link Uniform} rather than a compiled-in constant, so changing it does not rebuild the program
 * either: push a new instance with {@code Slot.ostate} and the next frame reads the new value.
 */
public class Greyscale extends State {
    public static final Slot<Greyscale> slot = new Slot<Greyscale>(Slot.Type.DRAW, Greyscale.class);
    public static final Uniform u_amount = new Uniform(FLOAT, "desat", p -> p.get(slot).amount, slot);

    /** How far toward grey: 0 leaves the colour alone, 1 takes all of it out. */
    public final float amount;

    /* Rec. 709 luma. The three weights are not a style choice: the eye's response to the primaries is
     * roughly this ratio, so a flat average turns a green field and a red roof into the same grey and
     * this does not. Ordered last (nothing else in the client mods above 100), so what is desaturated
     * is the finished colour rather than an intermediate one. */
    private static final ShaderMacro sh = prog -> {
	FragColor.fragcol(prog.fctx).mod(in -> {
		Expression grey = dot(pick(in, "rgb"), vec3(0.2126, 0.7152, 0.0722));
		return(vec4(mix(pick(in, "rgb"), vec3(grey), u_amount.ref()), pick(in, "a")));
	    }, 1000);
    };

    public Greyscale(float amount) {
	this.amount = amount;
    }

    public ShaderMacro shader() {return(sh);}

    public void apply(Pipe buf) {buf.put(slot, this);}

    public String toString() {return(String.format("#<greyscale %s>", amount));}
}
