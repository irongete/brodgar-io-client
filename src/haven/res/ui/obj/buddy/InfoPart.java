/* Preprocessed source code */
package haven.res.ui.obj.buddy;

import haven.*;
import haven.render.*;
import java.util.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import static haven.PUtils.*;

@haven.FromResource(name = "ui/obj/buddy", version = 4)
public interface InfoPart {
    public static final Text.Foundry fnd = new Text.Foundry(Text.sans.deriveFont(Font.BOLD, UI.scale(12f))).aa(true);

    public void draw(CompImage cmp, RenderContext ctx);
    public default int order() {return(0);}
    public default boolean auto() {return(false);}

    /* addon: (F4, D-043) the "world.nick" font scope -- the floating kin names above characters. The label is
     * composed by PUBLISHED CODE (this class ships inside the `ui/obj/buddy` resource), so there was no call site
     * in the fork to route: this is a LOCAL COPY of the resource's own source (doc/resource-code; see the
     * @FromResource annotation above, which is what makes the engine's ResClassLoader prefer it -- name+version
     * must match), exactly as F3d did for `ui/tt/slots-alt`. The single edit is the foundry: every part that draws
     * its text through rendertext() below follows the scope, and an unset scope still cascades to a "default"
     * override. The stock `fnd` field is left untouched for any other caller; resolution happens per render (which
     * is per re-compose, not per frame) and takes the provider's fast path while no override exists. */
    public static Text.Foundry fnd() {
	Fonts.stock("world.nick", "font", fnd);   // addon: (065.17) the face a floating name is set in
	return(Fonts.foundry("world.nick", fnd));
    }

    public static BufferedImage rendertext(String str, Color col) {
	return(rasterimg(blurmask2(fnd().render(str, col).img.getRaster(), UI.rscale(1.0), UI.rscale(1.0), Color.BLACK)));   // addon: (F4) was fnd.render(...)
    }
}

/* >objdelta: Buddy */
