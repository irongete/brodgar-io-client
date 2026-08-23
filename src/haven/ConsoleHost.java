/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.util.*;
import java.awt.event.KeyEvent;

public abstract class ConsoleHost extends Widget implements Console.Host, ReadLine.Owner {
    public static Text.Foundry cmdfoundry = new Text.Foundry(Text.mono, 12, new java.awt.Color(245, 222, 179));
    // addon: "textentry" font scope (F3c, D-043) -- the console command line is the other ReadLine-backed entry
    // surface, so it follows the same scope as TextEntry. `cmdfoundry` stays the stock foundry; drawcmd() renders
    // through Fonts.foundry("textentry", cmdfoundry) (an override, else stock, cascading through "default") and
    // drops its cached line when Fonts.gen() moves.
    private static Text.Foundry cfnd = null;
    private static int fontgen = -1;
    /** addon: the current foundry for the command line ({@code "textentry"} scope, else stock {@link #cmdfoundry}). */
    static Text.Foundry cmdfont() {
	int g = Fonts.gen();
	if((cfnd == null) || (fontgen != g)) {
	    cfnd = Fonts.foundry("textentry", cmdfoundry);
	    fontgen = g;
	}
	return(cfnd);
    }
    public ReadLine cmdline = null;
    private Text.Line cmdtext = null;
    private String cmdtextf = null;
    private int cmdgen = -1;    // addon: Fonts.gen() at the last cmdtext render
    private List<String> history = new ArrayList<String>();
    private int hpos = history.size();
    private String hcurrent;
    private UI.Grab kg;
    
    public static final KeyBinding kb_histprev = KeyBinding.get("history/prev", KeyMatch.forcode(KeyEvent.VK_UP, 0));
    public static final KeyBinding kb_histnext = KeyBinding.get("history/next", KeyMatch.forcode(KeyEvent.VK_DOWN, 0));

    public void done(ReadLine buf) {
	String line = buf.line();
	history.add(line);
	try {
	    ui.cons.run(this, line);
	} catch(Exception e) {
	    String msg = e.getMessage();
	    if(msg == null)
		msg = e.toString();
	    ui.cons.out.println(msg);
	    error(msg);
	}
	cancelcmd();
    }

    private boolean cmdkey(KeyEvent ev) {
	if(cmdline != null) {
	    if(key_esc.match(ev)) {
		cancelcmd();
	    } else if((ev.getKeyChar() == 8) && (KeyMatch.mods(ev) == 0) && cmdline.empty()) {
		cancelcmd();
	    } else if(kb_histprev.key().match(ev)) {
		if(hpos > 0) {
		    if(hpos == history.size())
			hcurrent = cmdline.line();
		    cmdline = ReadLine.make(this, history.get(--hpos));
		}
	    } else if(kb_histnext.key().match(ev)) {
		if(hpos < history.size()) {
		    if(++hpos == history.size())
			cmdline = ReadLine.make(this, hcurrent);
		    else
			cmdline = ReadLine.make(this, history.get(hpos));
		}
	    } else {
		return(cmdline.key(ev));
	    }
	    return(true);
	} else {
	    return(false);
	}
    }

    public ConsoleHost(Coord sz) {
	super(sz);
    }

    public ConsoleHost() {
    }
    
    public ConsoleHost(UI ui, Coord c, Coord sz) {
	super(ui, c, sz);
    }
    
    public void drawcmd(GOut g, Coord c) {
	if(cmdline != null) {
	    if((cmdtext == null) || !cmdline.lneq(cmdtextf) || (cmdgen != Fonts.gen())) {   // addon: re-render on a font change (F3c)
		/* addon: (102.2) the console line declares "textentry", the scope it is already styled at and the
		 * one Fonts.display refuses outright -- so the command being typed is neither matched by a
		 * catalogue nor reported as a string somebody could translate. Without the pair it would resolve
		 * under "default", where an entry written under "*" reaches everything. */
		Fonts.enter("textentry");
		try {
		    cmdtext = cmdfont().render(":" + (cmdtextf = cmdline.line()));   // addon: was `cmdfoundry.render(...)`
		} finally {
		    Fonts.exit();
		}
		cmdgen = Fonts.gen();   // addon:
	    }
	    int point = cmdline.point(), mark = cmdline.mark();
	    int px = cmdtext.advance(point + 1);
	    if(mark >= 0) {
		int mx = cmdtext.advance(mark + 1);
		g.chcolor(TextEntry.selcol);
		g.frect2(c.add(Math.min(mx, px) + UI.scale(1), UI.scale(2)),
			 c.add(Math.max(mx, px) + UI.scale(1), UI.scale(14)));
		g.chcolor();
	    }
	    g.image(cmdtext.tex(), c);
	    g.line(c.add(px + UI.scale(1), UI.scale(2)), c.add(px + UI.scale(1), UI.scale(14)), UI.scale(1));
	}
    }
    
    public void entercmd() {
	kg = ui.grabkeys(this);
	hpos = history.size();
	cmdline = ReadLine.make(this, "");
    }

    public void cancelcmd() {
	cmdline = null;
	kg.remove();
    }

    public boolean keydown(KeyDownEvent ev) {
	if(cmdkey(ev.awt))
	    return(true);
	return(super.keydown(ev));
    }
    
    public abstract void error(String msg);
}
