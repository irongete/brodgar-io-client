/* Preprocessed source code */
package haven.res.ui.obj.buddy;

import haven.*;
import haven.render.*;
import java.util.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import static haven.PUtils.*;

/* addon: (F4, D-043) local copy of the resource's own source (`haven.Resource get-code ui/obj/buddy`), kept so
 * the adopted class set matches the resource one-to-one -- the edit for the "world.nick" font scope is in this
 * package's InfoPart (the foundry) and Info (the re-compose on a generation move). ONE line of this file is the
 * fork's, tagged where it stands: the kin colour is read through `BuddyWnd.gcolor`, because the palette is eight
 * long and the group off the wire is not. */
@haven.FromResource(name = "ui/obj/buddy", version = 4)
public class Buddy extends GAttrib implements InfoPart {
    public final int id;
    public final Info info;
    private int bseq = -1;
    private BuddyWnd bw = null;
    private BuddyWnd.Buddy b = null;
    private int rgrp;
    private String rnm;

    public Buddy(Gob gob, int id) {
	super(gob);
	this.id = id;
	info = Info.add(gob, this);
    }

    public static void parse(Gob gob, Message dat) {
	int fl = dat.uint8();
	if((fl & 1) != 0)
	    gob.setattr(new Buddy(gob, dat.int32()));
	else
	    gob.delattr(Buddy.class);
    }

    public void dispose() {
	super.dispose();
	info.remove(this);
    }

    public BuddyWnd.Buddy buddy() {
	return(b);
    }

    public void draw(CompImage cmp, RenderContext ctx) {
	BuddyWnd.Buddy b = null;
	if(bw == null) {
	    if(ctx instanceof PView.WidgetContext) {
		GameUI gui = ((PView.WidgetContext)ctx).widget().getparent(GameUI.class);
		if(gui != null) {
		    if(gui.buddies == null)
			throw(new Loading());
		    bw = gui.buddies;
		}
	    }
	}
	if(bw != null)
	    b = bw.find(id);
	if(b != null) {
	    Color col = BuddyWnd.gcolor(rgrp = b.group);   // addon: a group above the palette
	    cmp.add(InfoPart.rendertext(rnm = b.name, col), Coord.z);
	}
	this.b = b;
    }

    public void ctick(double dt) {
	super.ctick(dt);
	if((bw != null) && (bw.serial != bseq)) {
	    bseq = bw.serial;
	    if((bw.find(id) != b) || ((b != null) && ((rnm != b.name) || (rgrp != b.group))))
		info.dirty();
	}
    }

    public boolean auto() {return(true);}
    public int order() {return(-10);}
}
