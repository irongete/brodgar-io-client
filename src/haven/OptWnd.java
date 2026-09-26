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

import haven.render.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.*;
import java.awt.event.KeyEvent;

public class OptWnd extends Window {
    public final Panel main;
    public final SettingsPanel settings;   // addon: (115.1) the tabbed view the menu's "Options" entry opens
    public Panel current;

    /* addon: (115.5) THE PAGE BOX -- the one box every page of the settings view is drawn inside, so that
     * picking a subject changes what is drawn and never how big the window is. It is the ceiling of what
     * the view can show rather than any one page's own size: the tallest page is VideoPanel at 395 design
     * pixels and the widest an addon's at 384 -- its two columns plus Scrollbar.width -- and a page with
     * less in it than that leaves the rest of the box empty instead of shrinking the window around itself.
     * It stands here rather than on SettingsPanel because a non-static inner class may hold no constant
     * that is not a compile-time one, and a Coord is not. */
    public static final Coord PAGE = UI.scale(new Coord(410, 410));

    public void chpanel(Panel p) {
	if(current != null)
	    current.hide();
	(current = p).show();
	/* addon: (115.1) the caption follows the panel -- null on the game menu, which carries none. Behind an
	 * equality test because chcap is the ONE caption seam ([title=] selectors hang off it), so a swap that
	 * changes nothing must not re-derive every match on this window and on everything below it. */
	if(!Utils.eq(this.cap, p.cap))
	    chcap(p.cap);
	cresize(p);
    }

    public void cresize(Widget ch) {
	if(ch == current) {
	    Coord cc = this.c.add(this.sz.div(2));
	    pack();
	    move(cc.sub(this.sz.div(2)));
	}
    }

    public class PButton extends Button {
	public final Supplier<Panel> tgt;
	public final int key;
	/* addon: rebuild the panel on every press instead of keeping the first one for the rest of the login.
	 * The keybind panel needs it and nothing else does: its sections are built from the bindings that exist
	 * AT THAT MOMENT (`AddonManager.describeKeyBinds`), so an addon that declares a hotkey later — which is
	 * what any addon with a variable number of things to bind does — was invisible in a panel built before
	 * it spoke. Built once, the panel was a snapshot of the first press. */
	private final boolean fresh;
	private Panel actual = null;

	public PButton(int w, String title, int key, Supplier<Panel> tgt) {
	    this(w, title, key, tgt, false);
	}

	public PButton(int w, String title, int key, Supplier<Panel> tgt, boolean fresh) {
	    super(w, title, false);
	    this.tgt = tgt;
	    this.key = key;
	    this.fresh = fresh;
	}

	public PButton(int w, String title, int key, Panel tgt) {
	    super(w, title, false);
	    this.tgt = null;
	    this.key = key;
	    this.fresh = false;
	    this.actual = tgt;
	}

	public void click() {
	    if(fresh && (actual != null)) {   // addon: the stale snapshot goes before the new one is built
		actual.destroy();
		actual = null;
	    }
	    if(actual == null)
		actual = OptWnd.this.add(tgt.get(), Coord.z);
	    chpanel(actual);
	}

	public boolean keydown(KeyDownEvent ev) {
	    if((this.key != -1) && (ev.c == this.key)) {
		click();
		return(true);
	    }
	    return(super.keydown(ev));
	}
    }

    public class Panel extends Widget {
	/* addon: (115.1) the window's caption while THIS panel is the one showing, or null for a panel that
	 * carries none. Only the panels chpanel swaps between use it; one drawn inside the settings view's
	 * holder is never the window's own subject and leaves it null. */
	public final String cap;

	public Panel() {
	    this(null);
	}

	public Panel(String cap) {
	    this.cap = cap;
	    visible = false;
	    c = Coord.z;
	}
    }

    private void error(String msg) {
	GameUI gui = getparent(GameUI.class);
	if(gui != null)
	    gui.error(msg);
    }

    public class VideoPanel extends Panel {
	private CPanel curcf;

	public VideoPanel(UI ui) {
	    super();
	    resetcf(ui);
	}

	public class CPanel extends Widget {
	    public GSettings prefs;

	    public CPanel(GSettings gprefs) {
		this.prefs = gprefs;
		Widget prev;
		int marg = UI.scale(5);
		prev = add(new CheckBox("Render shadows") {
			{a = prefs.lshadow.val;}

			public void set(boolean val) {
			    try {
				GSettings np = prefs.update(null, prefs.lshadow, val);
				ui.setgprefs(prefs = np);
			    } catch(GSettings.SettingException e) {
				error(e.getMessage());
				return;
			    }
			    a = val;
			}
		    }, Coord.z);
		prev = add(new CheckBox("Cull off-screen terrain") {   // rts:
			{a = prefs.cullterrain.val;}

			public void set(boolean val) {
			    try {
				GSettings np = prefs.update(null, prefs.cullterrain, val);
				ui.setgprefs(prefs = np);
			    } catch(GSettings.SettingException e) {
				error(e.getMessage());
				return;
			    }
			    a = val;
			}
		    }, prev.pos("bl").adds(0, 5));
		prev = add(new Label("Render scale"), prev.pos("bl").adds(0, 5));
		{
		    Label dpy = new Label("");
		    final int steps = 4;
		    addhlp(prev.pos("bl").adds(0, 2), UI.scale(5),
			   prev = new HSlider(UI.scale(160), -2 * steps, 1 * steps, (int)Math.round(steps * Math.log(prefs.rscale.val) / Math.log(2.0f))) {
			       protected void added() {
				   dpy();
			       }
			       void dpy() {
				   dpy.settext(String.format("%.2f\u00d7", Math.pow(2, this.val / (double)steps)));
			       }
			       public void changed() {
				   try {
				       float val = (float)Math.pow(2, this.val / (double)steps);
				       ui.setgprefs(prefs = prefs.update(null, prefs.rscale, val));
				   } catch(GSettings.SettingException e) {
				       error(e.getMessage());
				       return;
				   }
				   dpy();
			       }
			   },
			   dpy);
		}
		prev = add(new CheckBox("Vertical sync") {
			{a = prefs.vsync.val;}

			public void set(boolean val) {
			    try {
				GSettings np = prefs.update(null, prefs.vsync, val);
				ui.setgprefs(prefs = np);
			    } catch(GSettings.SettingException e) {
				error(e.getMessage());
				return;
			    }
			    a = val;
			}
		    }, prev.pos("bl").adds(0, 5));
		prev = add(new Label("Framerate limit (active window)"), prev.pos("bl").adds(0, 5));
		{
		    Label dpy = new Label("");
		    final int max = 250;
		    addhlp(prev.pos("bl").adds(0, 2), UI.scale(5),
			   prev = new HSlider(UI.scale(160), 1, max, (prefs.hz.val == Float.POSITIVE_INFINITY) ? max : prefs.hz.val.intValue()) {
			       protected void added() {
				   dpy();
			       }
			       void dpy() {
				   if(this.val == max)
				       dpy.settext("None");
				   else
				       dpy.settext(Integer.toString(this.val));
			       }
			       public void changed() {
				   try {
				       if(this.val > 10)
					   this.val = (this.val / 2) * 2;
				       float val = (this.val == max) ? Float.POSITIVE_INFINITY : this.val;
				       ui.setgprefs(prefs = prefs.update(null, prefs.hz, val));
				   } catch(GSettings.SettingException e) {
				       error(e.getMessage());
				       return;
				   }
				   dpy();
			       }
			   },
			   dpy);
		}
		prev = add(new Label("Framerate limit (background window)"), prev.pos("bl").adds(0, 5));
		{
		    Label dpy = new Label("");
		    final int max = 250;
		    addhlp(prev.pos("bl").adds(0, 2), UI.scale(5),
			   prev = new HSlider(UI.scale(160), 1, max, (prefs.bghz.val == Float.POSITIVE_INFINITY) ? max : prefs.bghz.val.intValue()) {
			       protected void added() {
				   dpy();
			       }
			       void dpy() {
				   if(this.val == max)
				       dpy.settext("None");
				   else
				       dpy.settext(Integer.toString(this.val));
			       }
			       public void changed() {
				   try {
				       if(this.val > 10)
					   this.val = (this.val / 2) * 2;
				       float val = (this.val == max) ? Float.POSITIVE_INFINITY : this.val;
				       ui.setgprefs(prefs = prefs.update(null, prefs.bghz, val));
				   } catch(GSettings.SettingException e) {
				       error(e.getMessage());
				       return;
				   }
				   dpy();
			       }
			   },
			   dpy);
		}
		prev = add(new Label("Lighting mode"), prev.pos("bl").adds(0, 5));
		{
		    boolean[] done = {false};
		    RadioGroup grp = new RadioGroup(this) {
			    public void changed(int btn, String lbl) {
				if(!done[0])
				    return;
				try {
				    ui.setgprefs(prefs = prefs
						 .update(null, prefs.lightmode, GSettings.LightMode.values()[btn])
						 .update(null, prefs.maxlights, 0));
				} catch(GSettings.SettingException e) {
				    error(e.getMessage());
				    return;
				}
				resetcf(ui);
			    }
			};
		    prev = grp.add("Global", prev.pos("bl").adds(5, 2));
		    prev.settip("Global lighting supports fewer light sources, and scales worse in " +
				"performance per additional light source, than zoned lighting, but " +
				"has lower baseline performance requirements.", true);
		    prev = grp.add("Zoned", prev.pos("bl").adds(0, 2));
		    prev.settip("Zoned lighting supports far more light sources than global " +
				"lighting with better performance, but may have higher performance " +
				"requirements in cases with few light sources, and may also have " +
				"issues on old graphics hardware.", true);
		    grp.check(prefs.lightmode.val.ordinal());
		    done[0] = true;
		}
		prev = add(new Label("Light-source limit"), prev.pos("bl").adds(0, 5).x(0));
		{
		    Label dpy = new Label("");
		    int val = prefs.maxlights.val, max = 32;
		    if(val == 0) {    /* XXX: This is just ugly. */
			if(prefs.lightmode.val == GSettings.LightMode.ZONED)
			    val = Lighting.LightGrid.defmax;
			else
			    val = Lighting.SimpleLights.defmax;
		    }
		    if(prefs.lightmode.val == GSettings.LightMode.SIMPLE)
			max = 4;
		    addhlp(prev.pos("bl").adds(0, 2), UI.scale(5),
			   prev = new HSlider(UI.scale(160), 1, max, val / 4) {
			       protected void added() {
				   dpy();
			       }
			       void dpy() {
				   dpy.settext(Integer.toString(this.val * 4));
			       }
			       public void changed() {dpy();}
			       public void fchanged() {
				   try {
				       ui.setgprefs(prefs = prefs.update(null, prefs.maxlights, this.val * 4));
				   } catch(GSettings.SettingException e) {
				       error(e.getMessage());
				       return;
				   }
				   dpy();
			       }
			       {
				   settip("The light-source limit means different things depending on the " +
					  "selected lighting mode. For Global lighting, it limits the total "+
					  "number of light-sources globally. For Zoned lighting, it limits the " +
					  "total number of overlapping light-sources at any point in space.",
					  true);
			       }
			   },
			   dpy);
		}
		prev = add(new Label("Frame sync mode"), prev.pos("bl").adds(0, 5).x(0));
		{
		    boolean[] done = {false};
		    RadioGroup grp = new RadioGroup(this) {
			    public void changed(int btn, String lbl) {
				if(!done[0])
				    return;
				try {
				    ui.setgprefs(prefs = prefs.update(null, prefs.syncmode, GSettings.SyncMode.values()[btn]));
				} catch(GSettings.SettingException e) {
				    error(e.getMessage());
				    return;
				}
			    }
			};
		    prev = add(new Label("\u2191 Better performance, worse latency"), prev.pos("bl").adds(5, 2));
		    prev = grp.add("One-frame overlap", prev.pos("bl").adds(0, 2));
		    prev = grp.add("Tick overlap", prev.pos("bl").adds(0, 2));
		    prev = grp.add("CPU-sequential", prev.pos("bl").adds(0, 2));
		    prev = grp.add("GPU-sequential", prev.pos("bl").adds(0, 2));
		    prev = add(new Label("\u2193 Worse performance, better latency"), prev.pos("bl").adds(0, 2));
		    grp.check(prefs.syncmode.val.ordinal());
		    done[0] = true;
		}
		/* XXXRENDER
		composer.add(new CheckBox("Antialiasing") {
			{a = cf.fsaa.val;}

			public void set(boolean val) {
			    try {
				cf.fsaa.set(val);
			    } catch(GLSettings.SettingException e) {
				error(e.getMessage());
				return;
			    }
			    a = val;
			    cf.dirty = true;
			}
		    });
		composer.add(new Label("Anisotropic filtering"));
		if(cf.anisotex.max() <= 1) {
		    composer.add(new Label("(Not supported)"));
		} else {
		    final Label dpy = new Label("");
		    composer.addRow(
			    new HSlider(UI.scale(160), (int)(cf.anisotex.min() * 2), (int)(cf.anisotex.max() * 2), (int)(cf.anisotex.val * 2)) {
			    protected void added() {
				dpy();
			    }
			    void dpy() {
				if(val < 2)
				    dpy.settext("Off");
				else
				    dpy.settext(String.format("%.1f\u00d7", (val / 2.0)));
			    }
			    public void changed() {
				try {
				    cf.anisotex.set(val / 2.0f);
				} catch(GLSettings.SettingException e) {
				    error(e.getMessage());
				    return;
				}
				dpy();
				cf.dirty = true;
			    }
			},
			dpy
		    );
		}
		*/
		add(new Button(UI.scale(200), "Reset to defaults", false).action(() -> {
			    ui.setgprefs(GSettings.defaults());
			    curcf.destroy();
			    curcf = null;
		}), prev.pos("bl").adds(0, 5));
		pack();
	    }
	}

	public void draw(GOut g) {
	    if((curcf == null) || (ui.gprefs != curcf.prefs))
		resetcf(ui);
	    super.draw(g);
	}

	private void resetcf(UI ui) {
	    if(curcf != null)
		curcf.destroy();
	    curcf = add(new CPanel(ui.gprefs), 0, 0);
	    pack();
	}
    }

    public class AudioPanel extends Panel {
	public AudioPanel(UI ui) {
	    Audio.Root sys = ui.audio.sys;
	    prev = add(new Label("Master audio volume"), 0, 0);
	    prev = add(new HSlider(UI.scale(200), 0, 1000, (int)(sys.volume() * 1000)) {
		    public void changed() {
			sys.volume(val / 1000.0);
		    }
		}, prev.pos("bl").adds(0, 2));
	    prev = add(new Label("Interface sound volume"), prev.pos("bl").adds(0, 15));
	    prev = add(new HSlider(UI.scale(200), 0, 1000, 0) {
		    protected void attach(UI ui) {
			super.attach(ui);
			val = (int)(ui.audio.aui.volume * 1000);
		    }
		    public void changed() {
			ui.audio.aui.setvolume(val / 1000.0);
		    }
		}, prev.pos("bl").adds(0, 2));
	    prev = add(new Label("In-game event volume"), prev.pos("bl").adds(0, 5));
	    prev = add(new HSlider(UI.scale(200), 0, 1000, 0) {
		    protected void attach(UI ui) {
			super.attach(ui);
			val = (int)(ui.audio.pos.volume * 1000);
		    }
		    public void changed() {
			ui.audio.pos.setvolume(val / 1000.0);
		    }
		}, prev.pos("bl").adds(0, 2));
	    prev = add(new Label("Ambient volume"), prev.pos("bl").adds(0, 5));
	    prev = add(new HSlider(UI.scale(200), 0, 1000, 0) {
		    protected void attach(UI ui) {
			super.attach(ui);
			val = (int)(ui.audio.amb.volume * 1000);
		    }
		    public void changed() {
			ui.audio.amb.setvolume(val / 1000.0);
		    }
		}, prev.pos("bl").adds(0, 2));
	    prev = add(new Label("Audio latency"), prev.pos("bl").adds(0, 15));
	    {
		Label dpy = new Label("");
		addhlp(prev.pos("bl").adds(0, 2), UI.scale(5),
		       prev = new HSlider(UI.scale(160), 128, Math.round(Audio.SAMPLE_RATE / 4), sys.bufsize()) {
			       protected void added() {
				   dpy();
			       }
			       void dpy() {
				   dpy.settext(Math.round((this.val * 1000) / Audio.SAMPLE_RATE) + " ms");
			       }
			       public void changed() {
				   sys.bufsize(val);
				   dpy();
			       }
			   }, dpy);
		prev.settip("Sets the size of the audio buffer. Smaller sizes are better, " +
			    "but larger sizes can fix issues with broken sound.", true);
	    }
	    pack();
	}
    }

    public class InterfacePanel extends Panel {
	public InterfacePanel() {
	    Widget prev = add(new Label("Interface scale (requires restart)"), 0, 0);
	    {
		Label dpy = new Label("");
		final double gran = 0.05;
		final double smin = 1, smax = Math.floor(UI.maxscale() / gran) * gran;
		final int steps = (int)Math.round((smax - smin) / gran);
		addhlp(prev.pos("bl").adds(0, 2), UI.scale(5),
		       prev = new HSlider(UI.scale(160), 0, steps, (int)Math.round(steps * (UI.scale(1.0) - smin) / (smax - smin))) {
			       protected void added() {
				   dpy();
			       }
			       void dpy() {
				   dpy.settext(String.format("%.2f\u00d7", smin + (((double)this.val / steps) * (smax - smin))));
			       }
			       public void changed() {
				   double val = smin + (((double)this.val / steps) * (smax - smin));
				   Utils.setprefd("uiscale", val);
				   dpy();
			       }
			   },
		       dpy);
	    }
	    prev = add(new Label("Object fine-placement granularity"), prev.pos("bl").adds(0, 5));
	    {
		Label pos = add(new Label("Position"), prev.pos("bl").adds(5, 2));
		Label ang = add(new Label("Angle"), pos.pos("bl").adds(0, 2));
		int x = Math.max(pos.pos("ur").x, ang.pos("ur").x);
		{
		    Label dpy = new Label("");
		    final double smin = 1, smax = Math.floor(UI.maxscale() / 0.25) * 0.25;
		    final int steps = (int)Math.round((smax - smin) / 0.25);
		    int ival = (int)Math.round(MapView.plobpgran);
		    addhlp(Coord.of(x + UI.scale(5), pos.c.y), UI.scale(5),
			   prev = new HSlider(UI.scale(155) - x, 2, 17, (ival == 0) ? 17 : ival) {
				   protected void added() {
				       dpy();
				   }
				   void dpy() {
				       dpy.settext((this.val == 17) ? "\u221e" : Integer.toString(this.val));
				   }
				   public void changed() {
				       Utils.setprefd("plobpgran", MapView.plobpgran = ((this.val == 17) ? 0 : this.val));
				       dpy();
				   }
			       },
			   dpy);
		}
		{
		    Label dpy = new Label("");
		    final double smin = 1, smax = Math.floor(UI.maxscale() / 0.25) * 0.25;
		    final int steps = (int)Math.round((smax - smin) / 0.25);
		    int[] vals = {4, 5, 6, 8, 9, 10, 12, 15, 18, 20, 24, 30, 36, 40, 45, 60, 72, 90, 120, 180, 360};
		    int ival = 0;
		    for(int i = 0; i < vals.length; i++) {
			if(Math.abs((MapView.plobagran * 2) - vals[i]) < Math.abs((MapView.plobagran * 2) - vals[ival]))
			    ival = i;
		    }
		    addhlp(Coord.of(x + UI.scale(5), ang.c.y), UI.scale(5),
			   prev = new HSlider(UI.scale(155) - x, 0, vals.length - 1, ival) {
				   protected void added() {
				       dpy();
				   }
				   void dpy() {
				       dpy.settext(String.format("%d\u00b0", 360 / vals[this.val]));
				   }
				   public void changed() {
				       Utils.setprefd("plobagran", MapView.plobagran = (vals[this.val] / 2.0));
				       dpy();
				   }
			       },
			   dpy);
		}
	    }
	    pack();
	}
    }

    // addon: was a class-init `static final Text` -- baked at class load, it could never follow a font override
    // (the F3e lesson), so it renders per use through the "tooltip" scope now (F3d, D-043).
    private static Text kbtt() {
	Fonts.enter("tooltip");   // addon: (102.3) the pair -- display reads the SITE's scope
	try {
	    return(Widget.tipfoundry().render("$col[255,255,0]{Escape}: Cancel input\n" +
					      "$col[255,255,0]{Backspace}: Revert to default\n" +
					      "$col[255,255,0]{Delete}: Disable keybinding", 0));
	} finally {
	    Fonts.exit();   // addon:
	}
    }
    public class BindingPanel extends Panel {
	private int addbtn(Widget cont, String nm, KeyBinding cmd, int y) {
	    return(cont.addhl(new Coord(0, y), cont.sz.x,
			      new Label(nm), new SetButton(UI.scale(175), cmd))
		   + UI.scale(2));
	}

	/* addon: a section heading. The first section sits at the top of the port and every later one a gap
	 * below the rows before it. "First" is read off y rather than counted, so a section hidden below leaves
	 * the next one at the top rather than a gap down from nothing. */
	private int addhead(Widget cont, String nm, int y) {
	    return(cont.adda(new Label(nm), cont.sz.x / 2, (y == 0) ? 0 : y + UI.scale(10), 0.5, 0.0).pos("bl").adds(0, 5).y);
	}

	/* addon: whether the client's own section `id` is painted. An addon standing in for a piece of the client
	 * (its own action bars, with hotkeys of its own) takes the client's rows off this panel
	 * (keybindings:section(id):visible(false)) so the user finds the addon's rows instead. The bindings under
	 * a hidden section are untouched and keep firing; only the rows are gone, and they are back when no addon
	 * holds them. The ids are LuaKeybindSection.IDS, in the order the sections stand below. */
	private boolean shown(String id) {
	    return(!io.brodgar.addon.AddonManager.keybindSectionHidden(id));
	}

	public BindingPanel() {
	    super();
	    /* addon: (115.5) the port is the page box less what stands under it, so this page fills the box
	     * rather than sitting in a corner of it: the button below is built first to be measured, and the
	     * rows inside spread to the port's own width, cont.sz.x being what addhl lays each one across. */
	    PointBind pb = new PointBind(UI.scale(200));
	    Scrollport scroll = add(new Scrollport(PAGE.sub(0, pb.sz.y + UI.scale(10))), 0, 0);
	    Widget cont = scroll.cont;
	    Widget prev;
	    int y = 0;
	    if(shown("menu")) {
		y = addhead(cont, "Main menu", y);
		y = addbtn(cont, "Inventory", GameUI.kb_inv, y);
		y = addbtn(cont, "Equipment", GameUI.kb_equ, y);
		y = addbtn(cont, "Character sheet", GameUI.kb_chr, y);
		y = addbtn(cont, "Map window", GameUI.kb_map, y);
		y = addbtn(cont, "Kith & Kin", GameUI.kb_bud, y);
		y = addbtn(cont, "Options", GameUI.kb_opt, y);
		y = addbtn(cont, "Search actions", GameUI.kb_srch, y);
		y = addbtn(cont, "Toggle chat", GameUI.kb_chat, y);
		y = addbtn(cont, "Quick chat", ChatUI.kb_quick, y);
		y = addbtn(cont, "Take screenshot", GameUI.kb_shoot, y);
		y = addbtn(cont, "Minimap icons", GameUI.kb_ico, y);
		y = addbtn(cont, "Toggle UI", GameUI.kb_hide, y);
		y = addbtn(cont, "Log out", GameUI.kb_logout, y);
		y = addbtn(cont, "Switch character", GameUI.kb_switchchr, y);
	    }
	    if(shown("map")) {
		y = addhead(cont, "Map options", y);
		y = addbtn(cont, "Display claims", GameUI.kb_claim, y);
		y = addbtn(cont, "Display villages", GameUI.kb_vil, y);
		y = addbtn(cont, "Display realms", GameUI.kb_rlm, y);
		y = addbtn(cont, "Display grid-lines", MapView.kb_grid, y);
	    }
	    if(shown("camera")) {
		y = addhead(cont, "Camera control", y);
		y = addbtn(cont, "Rotate left", MapView.kb_camleft, y);
		y = addbtn(cont, "Rotate right", MapView.kb_camright, y);
		y = addbtn(cont, "Zoom in", MapView.kb_camin, y);
		y = addbtn(cont, "Zoom out", MapView.kb_camout, y);
		y = addbtn(cont, "Reset", MapView.kb_camreset, y);
	    }
	    if(shown("mapwnd")) {
		y = addhead(cont, "Map window", y);
		y = addbtn(cont, "Reset view", MapWnd.kb_home, y);
		y = addbtn(cont, "Place marker", MapWnd.kb_mark, y);
		y = addbtn(cont, "Toggle markers", MapWnd.kb_hmark, y);
		y = addbtn(cont, "Compact mode", MapWnd.kb_compact, y);
	    }
	    if(shown("speed")) {
		y = addhead(cont, "Walking speed", y);
		y = addbtn(cont, "Increase speed", Speedget.kb_speedup, y);
		y = addbtn(cont, "Decrease speed", Speedget.kb_speeddn, y);
		for(int i = 0; i < 4; i++)
		    y = addbtn(cont, String.format("Set speed %d", i + 1), Speedget.kb_speeds[i], y);
	    }
	    // addon: the action bar's own keys. They were raw key codes inside the two belt widgets' globtype
	    // overrides, so they were in no panel and could not be moved off the row they claimed.
	    if(shown("actionbar")) {
		y = addhead(cont, "Action bar", y);
		for(int i = 0; i < GameUI.kb_belt.length; i++)
		    y = addbtn(cont, String.format("Button %d", i + 1), GameUI.kb_belt[i], y);
		for(int i = 0; i < GameUI.kb_beltpg.length; i++)
		    y = addbtn(cont, String.format("Go to page %d", i + 1), GameUI.kb_beltpg[i], y);
	    }
	    if(shown("combat")) {
		y = addhead(cont, "Combat actions", y);
		for(int i = 0; i < Fightsess.kb_acts.length; i++)
		    y = addbtn(cont, String.format("Combat action %d", i + 1), Fightsess.kb_acts[i], y);
		y = addbtn(cont, "Switch targets", Fightsess.kb_relcycle, y);
	    }
	    // addon: dynamic per-addon hotkey sections (WoW-style; Phase 2e-3). One section per addon that
	    // registered a hotkey (keybindings:register; none registered -> no section); the client's SetButton captures
	    // and persists each re-map exactly like a built-in binding, so nothing else is needed here.
	    for(io.brodgar.addon.AddonManager.KeyBindGroup grp : io.brodgar.addon.AddonManager.describeKeyBinds()) {
		y = addhead(cont, grp.addon, y);
		for(io.brodgar.addon.AddonManager.KeyBindEntry e : grp.binds)
		    y = addbtn(cont, e.name, e.binding, y);
	    }
	    prev = adda(pb, scroll.pos("bl").adds(0, 10).x(scroll.sz.x / 2), 0.5, 0.0);
	    pack();
	}
    }

    /* addon: (163.1) THE KEY BUTTON, a member of OptWnd rather than of BindingPanel. Upstream nests it in the panel,
     * an inner class, so it could only be built inside an Options window; as a static member it can stand anywhere --
     * hafen.ui():keybinding() builds this very class on an addon's own page. `cmd` is no longer final and may be null:
     * a button bound to nothing shows the key it last showed and writes nothing, and one an addon re-binds takes the
     * new binding. follow() is the display half of draw(), callable at once. BindingPanel.addbtn builds it as before. */
    public static class SetButton extends KeyMatch.Capture {
	public KeyBinding cmd;   // addon: (163.1) was final, and never null

	public SetButton(int w, KeyBinding cmd) {
	    super(w, (cmd == null) ? null : cmd.key());   // addon: (163.1) bound to nothing, it reads None
	    this.cmd = cmd;
	}

	public void set(KeyMatch key) {
	    super.set(key);
	    if(cmd != null)   // addon: (163.1)
		cmd.set(key);
	}

	/* addon: (163.1) show the binding's key WITHOUT writing it -- the test draw() made inline, by identity as
	 * before: KeyBinding.key() hands out one cached wrapper per key, so a new object means a new key. */
	public void follow() {
	    if((cmd != null) && (cmd.key() != key))
		super.set(cmd.key());
	}

	public void draw(GOut g) {
	    follow();   // addon: (163.1) was the same test, inline
	    super.draw(g);
	}

	protected KeyMatch mkmatch(KeyEvent ev) {
	    return(KeyMatch.forevent(ev, ~((cmd == null) ? 0 : cmd.modign)));   // addon: (163.1) null-safe
	}

	protected boolean handle(KeyEvent ev) {
	    if(ev.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
		if(cmd != null) {   // addon: (163.1) bound to nothing, there is no default to go back to
		    cmd.set(null);
		    super.set(cmd.key());
		}
		return(true);
	    }
	    return(super.handle(ev));
	}

	public Object tooltip(Coord c, Widget prev) {
	    return(kbtt().tex());   // addon: (F3d)
	}
    }


    public static class PointBind extends Button implements CursorQuery.Handler {
	public static final String msg = "Bind other elements...";
	public static final Resource curs = Resource.local().loadwait("gfx/hud/curs/wrench");
	private UI.Grab mg, kg;
	private KeyBinding cmd;

	public PointBind(int w) {
	    super(w, msg, false);
	    // addon: (102.3) was a RichText baked in the CONSTRUCTOR, under "default" and once: it followed
	    // neither a font override nor a catalogue. settip(text, rich) is the live shape -- KeyboundTip
	    // renders on demand, inside the "tooltip" pair, and re-renders when Fonts.gen() moves.
	    settip("Bind a key to an element not listed above, such as an action-menu " +
		   "button. Click the element to bind, and then press the key to bind to it. " +
		   "Right-click to stop rebinding.", true);
	}

	public void click() {
	    if(mg == null) {
		change("Click element...");
		mg = ui.grabmouse(this);
	    } else if(kg != null) {
		kg.remove();
		kg = null;
		change(msg);
	    }
	}

	private boolean handle(KeyEvent ev) {
	    switch(ev.getKeyCode()) {
	    case KeyEvent.VK_SHIFT: case KeyEvent.VK_CONTROL: case KeyEvent.VK_ALT:
	    case KeyEvent.VK_META: case KeyEvent.VK_WINDOWS:
		return(false);
	    }
	    int code = ev.getKeyCode();
	    if(code == KeyEvent.VK_ESCAPE) {
		return(true);
	    }
	    if(code == KeyEvent.VK_BACK_SPACE) {
		cmd.set(null);
		return(true);
	    }
	    if(code == KeyEvent.VK_DELETE) {
		cmd.set(KeyMatch.nil);
		return(true);
	    }
	    KeyMatch key = KeyMatch.forevent(ev, ~cmd.modign);
	    if(key != null)
		cmd.set(key);
	    return(true);
	}

	public boolean mousedown(MouseDownEvent ev) {
	    if(!ev.grabbed)
		return(super.mousedown(ev));
	    Coord gc = ui.mc;
	    if(ev.b == 1) {
		this.cmd = KeyBinding.Bindable.getbinding(ui.root, gc);
		return(true);
	    }
	    if(ev.b == 3) {
		mg.remove();
		mg = null;
		change(msg);
		return(true);
	    }
	    return(false);
	}

	public boolean mouseup(MouseUpEvent ev) {
	    if(mg == null)
		return(super.mouseup(ev));
	    Coord gc = ui.mc;
	    if(ev.b == 1) {
		if((this.cmd != null) && (KeyBinding.Bindable.getbinding(ui.root, gc) == this.cmd)) {
		    mg.remove();
		    mg = null;
		    kg = ui.grabkeys(this);
		    change("Press key...");
		} else {
		    this.cmd = null;
		}
		return(true);
	    }
	    if(ev.b == 3)
		return(true);
	    return(false);
	}

	public boolean getcurs(CursorQuery ev) {
	    return(ev.grabbed ? ev.set(curs) : false);
	}

	public boolean keydown(KeyDownEvent ev) {
	    if(!ev.grabbed)
		return(super.keydown(ev));
	    if(handle(ev.awt)) {
		kg.remove();
		kg = null;
		cmd = null;
		change("Click another element...");
		mg = ui.grabmouse(this);
	    }
	    return(true);
	}
    }

    public class CameraPanel extends Panel {
	private final CamSelector cam;

	/* addon: (066.2) the camera picker. Its items ARE MapView's registry keys -- the very names
	 * :cam takes -- so no camera gets a second, prettier spelling that the console and the
	 * refusals do not know. */
	public class CamSelector extends SDropBox<String, Widget> {
	    private CamSelector() {
		/* The row height is the item font's own, as SListWidget.TextItem renders with it and a
		 * shorter row clips the text rather than shrinking it. */
		super(UI.scale(200), UI.scale(120), CharWnd.attrfont().height());
	    }

	    protected List<String> items() {return(MapView.camnames());}

	    protected Widget makeitem(String nm, int idx, Coord sz) {
		/* makeitem(null, …) is a real call: SDropBox.change passes whatever it is handed for
		 * the closed box, and before the first sync that is nothing. */
		return(SListWidget.TextItem.of(sz, () -> (nm == null) ? "(unknown)" : nm));
	    }

	    /* The camera in force, which the defcam pref does not answer while a mode has swapped one
	     * in without writing it. Before login there is no map view and the pref is all there is.
	     * It answers the REGISTRY's own instance of the name, never the equal one the pref store
	     * hands back: SDropBox compares its selection by reference (`item != sel`, `sel == item`),
	     * so an equal-but-distinct string shows in the closed box and highlights no row. Before login, a
	     * pref naming no camera the registry has -- none at all, for a new player -- reads as `default`,
	     * the camera MapView.restorecam brings the session up on; logged in, it is the camera in force. */
	    private String current() {
		MapView mv = mapview();
		String nm = (mv == null) ? Utils.getpref("defcam", null) : mv.camname();
		String dflt = null;
		for(String cand : MapView.camnames()) {
		    if(cand.equals(nm))
			return(cand);
		    if(cand.equals("default"))
			dflt = cand;
		}
		return((mv == null) ? dflt : null);
	    }

	    /* change(I) is the ONLY way to set what the closed box shows -- it both writes sel and
	     * rebuilds that widget -- so a re-sync runs super's half and none of the pick below. */
	    private void sync() {
		super.change(current());
		dcshow();
	    }

	    public void change(String nm) {
		/* Read before super.change writes it. Clicking the row you are already on installs
		 * nothing: makecam would build a fresh camera, and the view would jump back to where a
		 * pan started for no gesture the user made. */
		boolean same = (nm == this.sel);
		super.change(nm);
		dcshow();
		if((nm == null) || same)
		    return;
		MapView mv = mapview();
		if(mv != null) {
		    mv.setcam(nm);
		} else {
		    /* This panel exists at login too, with no map view to install onto: write the
		     * preference alone, and the session that comes up reads it through restorecam. */
		    Utils.setpref("defcam", nm);
		    Utils.setprefb("camargs", Utils.serialize(new String[0]));
		}
	    }
	}

	private MapView mapview() {
	    GameUI gui = getparent(GameUI.class);
	    return((gui == null) ? null : gui.map);
	}

	/* cam: the default camera's own options, shown only while it is the one picked. Null while the
	 * constructor has yet to build it -- the selector exists first.
	 *
	 * Re-packed on every change: contentsz() skips a hidden child, so a panel packed while the group
	 * was hidden is too short for it, and its children draw clipped to it -- the group would show and
	 * be cut away whole. */
	private Widget dcopts = null;

	private void dcshow() {
	    if(dcopts == null)
		return;
	    dcopts.show("default".equals(cam.sel));
	    pack();
	}

	/* `write`, never `set`: ACheckBox has a public Consumer<Boolean> field of that very name, and inside
	 * the anonymous subclass the inherited field shadows the parameter -- so `set.accept` compiles and
	 * runs the box's own default, which ticks it and writes nothing. */
	private CheckBox dcbox(String text, boolean val, java.util.function.Consumer<Boolean> write) {
	    return(new CheckBox(text) {
		    {a = val;}
		    public void set(boolean nval) {write.accept(nval); a = nval;}
		});
	}

	private Widget dcbuild() {
	    Widget w = new Widget(Coord.z);
	    Widget prev = w.add(new Label("Default camera"), 0, 0);
	    prev = w.add(dcbox("Proportional zoom", MapView.dcamzoom,
			       v -> Utils.setprefb("dcamzoom", MapView.dcamzoom = v)), prev.pos("bl").adds(0, 10));
	    prev.settip("Each wheel notch moves the camera by the same share of its distance, so zooming is as fine " +
			"up close as far out. Off, every notch moves it a fixed 25 units.", true);
	    prev = w.add(new Label("Zoom smoothness"), prev.pos("bl").adds(0, 8));
	    Label zdpy = new Label("");
	    w.addhlp(prev.pos("bl").adds(0, 2), UI.scale(5),
		     prev = new HSlider(UI.scale(160), 0, 40, MapView.dcamzsmooth) {
			     protected void added() {
				 dpy();
			     }
			     void dpy() {
				 zdpy.settext((this.val == 0) ? "Off" : String.format("%.2f s", this.val / 100.0));
			     }
			     public void changed() {
				 Utils.setprefi("dcamzsmooth", MapView.dcamzsmooth = this.val);
				 dpy();
			     }
			 }, zdpy);
	    prev.settip("How gently the camera glides to a new distance -- after a wheel notch, and when an object " +
			"comes between it and the character (it moves back out a little slower still). Off, it jumps " +
			"there at once; the further right, the slower and softer the glide. The ground always stops " +
			"the camera at once.", true);
	    prev = w.add(new Label("Field of view (vertical)"), prev.pos("bl").adds(0, 8));
	    Label dpy = new Label("");
	    w.addhlp(prev.pos("bl").adds(0, 2), UI.scale(5),
		     prev = new HSlider(UI.scale(160), 20, 110, MapView.dcamfov) {
			     protected void added() {
				 dpy();
			     }
			     void dpy() {
				 dpy.settext(this.val + "°");
			     }
			     public void changed() {
				 Utils.setprefi("dcamfov", MapView.dcamfov = this.val);
				 dpy();
			     }
			 }, dpy);
	    prev.settip("How much the camera sees from top to bottom. The sides follow the shape of the screen, " +
			"so a wider screen sees more to the sides. 31° is what the other cameras show on a 16:9 screen.", true);
	    prev = w.add(dcbox("Collide with the ground", MapView.dcamgnd,
			       v -> Utils.setprefb("dcamgnd", MapView.dcamgnd = v)), prev.pos("bl").adds(0, 10));
	    prev.settip("A hill between the character and the camera pulls the camera in, instead of letting it " +
			"pass through the ground.", true);
	    prev = w.add(dcbox("Collide with objects", MapView.dcamobj,
			       v -> Utils.setprefb("dcamobj", MapView.dcamobj = v)), prev.pos("bl").adds(0, 8));
	    prev.settip("Trees, walls, palisades and buildings -- whatever a character cannot walk through -- pull " +
			"the camera in, instead of letting it pass through them.", true);
	    prev = w.add(dcbox("First person at the closest zoom", MapView.dcamfp,
			       v -> Utils.setprefb("dcamfp", MapView.dcamfp = v)), prev.pos("bl").adds(0, 8));
	    prev.settip("Zooming all the way in puts the eye in the character's head and hides the character. " +
			"One notch out brings the camera back.", true);
	    prev = w.add(dcbox("Tilt below the horizon", MapView.dcamup,
			       v -> Utils.setprefb("dcamup", MapView.dcamup = v)), prev.pos("bl").adds(0, 8));
	    prev.settip("Dragging past level lowers the camera behind the character to look up. With ground " +
			"collision on, it comes to rest against the character's head; off, it goes under the ground.", true);
	    w.pack();
	    return(w);
	}

	/* The panel is built once and kept, so the box is re-read every time it is shown rather than
	 * only when it is made -- :cam and the RTS mode both move the camera behind its back. */
	public void show() {
	    cam.sync();
	    super.show();
	}

	public CameraPanel() {
	    Widget prev;
	    prev = add(new Label("Camera"), 0, 0);
	    cam = add(new CamSelector(), prev.pos("bl").adds(0, 10));
	    cam.settip("The camera the world is drawn through. These are the names the :cam command takes.", true);
	    prev = add(new Label("Camera axis inversion"), cam.pos("bl").adds(0, 20));
	    prev = add(new CheckBox("Invert horizontal axis (left/right)") {
		    {a = MapView.invcamx;}
		    public void set(boolean val) {Utils.setprefb("invcamx", MapView.invcamx = val); a = val;}
		}, prev.pos("bl").adds(0, 10));
	    prev.settip("Reverses the horizontal mouse-drag direction when rotating the camera.", true);
	    prev = add(new CheckBox("Invert vertical axis (up/down)") {
		    {a = MapView.invcamy;}
		    public void set(boolean val) {Utils.setprefb("invcamy", MapView.invcamy = val); a = val;}
		}, prev.pos("bl").adds(0, 8));
	    prev.settip("Reverses the vertical mouse-drag direction when tilting the camera. " +
			"Only affects cameras that support tilting (not the ortho camera).", true);
	    dcopts = add(dcbuild(), prev.pos("bl").adds(0, 20));
	    dcshow();
	    pack();
	}
    }

    /* addon: (115.1) ONE ROW of a settings list -- the name the list draws, and the panel the holder shows
     * when it is picked. `fresh` is PButton's own flag moved onto the row: a panel built from what exists AT
     * THAT MOMENT (the keybindings) is rebuilt on every visit, so anything declared since the last one is
     * listed; everything else is built once and kept, exactly as PButton.click has always done. */
    public class PanelEntry {
	public final String name;
	/* addon: (115.3) what this row IS, across a re-read of the list -- the addon's id on the AddOns tab,
	 * and the name itself where the list is fixed. The entry objects are minted fresh every time the
	 * AddOns list is re-read, so the selection cannot be remembered by identity. */
	public final String key;
	private final Supplier<Panel> tgt;
	private final boolean fresh;
	private Panel actual = null;

	public PanelEntry(String key, String name, Supplier<Panel> tgt, boolean fresh) {
	    this.key = key;
	    this.name = name;
	    this.tgt = tgt;
	    this.fresh = fresh;
	}

	public PanelEntry(String name, Supplier<Panel> tgt, boolean fresh) {
	    this(name, name, tgt, fresh);
	}

	public PanelEntry(String name, Supplier<Panel> tgt) {
	    this(name, tgt, false);
	}
    }

    /* addon: (115.1) THE SETTINGS VIEW -- a tab strip over a list of subjects on the left and the panel the
     * picked one draws on the right. It is one of the panels chpanel swaps between, and it carries the
     * window's whole caption while it shows.
     *
     * Back, under the tabs, returns to the game menu, and Escape presses it: the same button in the same
     * place as the AddOns manager's, so both destinations of the game menu leave it the same way. */
    public class SettingsPanel extends Panel {
	private final Tabs tabs;
	private final List<Subject> subjects = new ArrayList<Subject>();
	/* addon: (115.3, 140.1) the addons holding a page of their own. Unlike the Game tab's fixed seven
	 * this list is a census of what is loaded and what it has said so far, so it is re-read rather than
	 * built: on the way in, and on either counter below moving under it. */
	private final Subject addons;
	private int builtGen = Integer.MIN_VALUE, builtOpts = Integer.MIN_VALUE;

	public SettingsPanel() {
	    super("Options");
	    tabs = new Tabs(Coord.z, Coord.z, this);
	    Subject game = new Subject(tabs.add(), gamepanels());
	    addons = new Subject(tabs.add(), new ArrayList<PanelEntry>());
	    Widget gb = add(tabs.new TabButton(UI.scale(120), "Game", game.tab), 0, 0);
	    add(tabs.new TabButton(UI.scale(120), "AddOns", addons.tab), gb.pos("ur").adds(5, 0));
	    /* The tab bodies go under their buttons, which is why Tabs is built with a placeholder c: a
	     * TabButton needs its Tab, and a Tab is placed where Tabs was told, so one of the two comes second. */
	    Coord tc = gb.pos("bl").adds(0, 8);
	    tabs.c = tc;
	    for(Subject s : subjects)
		s.tab.move(tc);
	    /* The view opens on something rather than on an empty right-hand side. It is a real selection, so the
	     * row is highlighted and the list reads back what is drawn beside it. */
	    if(!game.entries.isEmpty())
		game.list.change(game.entries.get(0));
	    /* addon: (115.5) the whole layout, once. Every box below this is a declared one, so there is
	     * nothing left that moves for a later pack to follow; Tabs.pack puts both tabs on the union of
	     * them, which is now the same box twice. */
	    tabs.pack();
	    // addon: the way back to the game menu, where AddonPanel puts its own: under the tabs, Escape its key.
	    add(new PButton(UI.scale(200), "Back", 27, main), game.tab.pos("bl").adds(0, 8));
	    pack();
	}

	private List<PanelEntry> gamepanels() {
	    List<PanelEntry> ret = new ArrayList<PanelEntry>();
	    // addon: 160.1 -- first, so the settings view opens on entries.get(0) and so on this panel.
	    ret.add(new PanelEntry("Performance", () -> new io.brodgar.ui.PerformancePanel(OptWnd.this)));
	    ret.add(new PanelEntry("Interface settings", () -> new InterfacePanel()));
	    ret.add(new PanelEntry("Video settings", () -> new VideoPanel(ui)));
	    ret.add(new PanelEntry("Sky & weather", () -> new io.brodgar.ui.SkyPanel(OptWnd.this)));   // addon: ambience (spike)
	    ret.add(new PanelEntry("Audio settings", () -> new AudioPanel(ui)));
	    // addon: `true` — rebuilt on every visit, so a hotkey an addon declared since the last one is listed.
	    ret.add(new PanelEntry("Keybindings", () -> new BindingPanel(), true));
	    ret.add(new PanelEntry("Camera", () -> new CameraPanel()));
	    // addon: client-wide toggles (spec 019, task 019.1) — today just the profiling master switch.
	    ret.add(new PanelEntry("Client", () -> new io.brodgar.ui.ClientPanel(OptWnd.this)));
	    return(ret);
	}

	/* addon: (115.3, 140.1) one row per addon holding a page, in the order the addons were loaded. `fresh`
	 * on every one: the page is the addon's own fill, run from what it has said at the moment it is opened
	 * rather than at the moment it was listed. */
	private List<PanelEntry> addonpanels() {
	    List<PanelEntry> ret = new ArrayList<PanelEntry>();
	    for(io.brodgar.addon.AddonManager.OptionGroup g : io.brodgar.addon.AddonManager.describePages()) {
		final io.brodgar.addon.AddonManager.OptionGroup grp = g;
		ret.add(new PanelEntry(g.id, g.addon,
					() -> new io.brodgar.addon.ui.AddonOptionsPanel(OptWnd.this, grp), true));
	    }
	    return(ret);
	}

	private void refreshAddons() {
	    builtGen = io.brodgar.addon.AddonRegistry.reloadGen();
	    builtOpts = io.brodgar.addon.AddonManager.optionsGen();
	    addons.reset(addonpanels());
	}

	/* Re-read on the way in: the view is opened from the game menu every time, so this is the moment the
	 * census is worth taking. */
	public void show() {
	    super.show();
	    refreshAddons();
	}

	public void tick(double dt) {
	    super.tick(dt);
	    /* addon: only while the view is on screen. This panel ticks hidden -- inside the window GameUI
	     * builds hidden, from the session's first frame -- and the two counters below start out unequal,
	     * so that first tick re-read the census, put the first addon's page in the holder and had the
	     * layer's next step fill it: 200-240 ms of Lua on the UI thread at login, for a page nobody had
	     * opened. show() takes the census on the way in, so what a :reload leaves stale in a view that is
	     * not showing is rebuilt by the show() that brings it back. */
	    if(!tvisible())
		return;
	    /* Two counters, one compare each. A :reload rebuilds every Addon under this view, so the options a
	     * page is drawing and writing through belong to objects nothing holds any more; and a declaration
	     * is a row this list has not got, whenever in an addon's life it was made. */
	    if((io.brodgar.addon.AddonRegistry.reloadGen() != builtGen)
	       || (io.brodgar.addon.AddonManager.optionsGen() != builtOpts))
		refreshAddons();
	}

	/* One tab: the list of subjects, and the holder its pick draws in. */
	public class Subject {
	    public final Tabs.Tab tab;
	    public final PanelList list;
	    public final Widget holder;
	    private final List<PanelEntry> entries = new ArrayList<PanelEntry>();
	    private Panel shown = null;

	    private Subject(Tabs.Tab tab, List<PanelEntry> entries) {
		this.tab = tab;
		this.entries.addAll(entries);
		this.list = tab.add(new PanelList(this, Coord.of(UI.scale(190), PAGE.y)), Coord.z);
		/* addon: (115.5) the holder is the page box and nothing but it. A panel inside one may repack
		 * itself long after it was built -- VideoPanel.resetcf throws its whole column away every time a
		 * graphics preference moves -- and that now changes what is drawn in the box rather than the box,
		 * so nothing here follows it and Widget.cresize's no-op is the right answer. */
		this.holder = tab.add(new Widget(PAGE), list.pos("ur").adds(10, 0));
		subjects.add(this);
	    }

	    /* addon: (115.3) put a whole new list of subjects in, keeping the row the user was on where the
	     * same subject is still there to be on. Every built panel goes first: an SListBox diffs items() by
	     * identity and rebuilds its rows on its own, but the panels hanging off the old entries are ours,
	     * and one of them may be drawing an addon that is no longer loaded. */
	    private void reset(List<PanelEntry> ne) {
		String cur = (list.sel == null) ? null : list.sel.key;
		for(PanelEntry e : entries) {
		    if(e.actual != null) {
			if(shown == e.actual)
			    shown = null;
			e.actual.destroy();
			e.actual = null;
		    }
		}
		entries.clear();
		entries.addAll(ne);
		PanelEntry pick = null;
		for(PanelEntry e : entries) {
		    if(e.key.equals(cur))
			pick = e;
		}
		if((pick == null) && !entries.isEmpty())
		    pick = entries.get(0);
		list.sel = null;
		if(pick != null)
		    list.change(pick);
	    }

	    private void show(PanelEntry e) {
		if(e == null)
		    return;
		if(e.fresh && (e.actual != null)) {   // the stale snapshot goes before the new one is built
		    if(shown == e.actual)
			shown = null;
		    e.actual.destroy();
		    e.actual = null;
		}
		if(shown != null)
		    shown.hide();
		if(e.actual == null)
		    e.actual = holder.add(e.tgt.get(), Coord.z);
		(shown = e.actual).show();
	    }
	}

	/* The subject list. Its rows are objects rather than strings, because a row IS the panel it names. */
	public class PanelList extends SListBox<PanelEntry, Widget> {
	    private final Subject subj;

	    private PanelList(Subject subj, Coord sz) {
		/* The row height is the item font's own, as a shorter row clips its label rather than shrinking
		 * it -- the sizing CameraPanel's camera picker uses, one control along. */
		super(sz, CharWnd.attrf.height() + UI.scale(2));
		this.subj = subj;
	    }

	    protected List<PanelEntry> items() {return(subj.entries);}

	    protected Widget makeitem(PanelEntry e, int idx, Coord sz) {
		/* A Label rather than an SListWidget.TextItem: a navigation row is read back BY NAME -- by the
		 * eye, and by widget:text() from an addon -- and a TextItem's caption is a private raster with no
		 * reader at all. A Label says its own text, and re-renders when a font override moves. */
		ItemWidget<PanelEntry> row = new ItemWidget<PanelEntry>(this, sz, e);
		Label lbl = new Label(e.name, CharWnd.attrf);
		row.add(lbl, UI.scale(5), (sz.y - lbl.sz.y) / 2);
		return(row);
	    }

	    /* change(I) is where a row click and a programmatic selection BOTH land, so the panel swap hangs off
	     * it rather than off the mouse. */
	    public void change(PanelEntry e) {
		super.change(e);
		subj.show(e);
	    }

	    /* A click on empty space below the rows clears the selection on every other list in the client. This
	     * one is the window's navigation and has no "no subject" to show, so the row you are on stands. */
	    protected boolean unselect(int button) {
		return(true);
	    }
	}
    }

    public OptWnd(boolean gopts) {
	/* addon: (115.1) NO CAPTION. This window opens on the game menu, which is a menu and not a subject;
	 * chpanel writes the caption of whichever panel is showing, and the menu's is null. */
	super(Coord.z, null, true);
	main = add(new Panel());
	settings = add(new SettingsPanel());

	int y = 0;
	/* addon: (115.1) THE GAME MENU. Two destinations, the session entries, and the way out -- the client's
	 * own settings are one entry down, inside the tabbed view, rather than a column of eight buttons with
	 * the log-out sitting underneath them. */
	y = main.add(new PButton(UI.scale(200), "Options", 'o', settings), 0, y).pos("bl").adds(0, 5).y;
	// addon: AddOns manager panel (spec 10 / 1f-3) — the whole panel lives in io.brodgar.addon.ui.
	y = main.add(new PButton(UI.scale(200), "AddOns", 'd', () -> new io.brodgar.addon.ui.AddonPanel(OptWnd.this, main)), 0, y).pos("bl").adds(0, 5).y;
	y += UI.scale(20);
	if(gopts) {
	    if((SteamStore.steamsvc.get() != null) && (Steam.get() != null)) {
		y = main.add(new Button(UI.scale(200), "Visit store", false).action(() -> {
			    SteamStore.launch(ui.sess);
		}), 0, y).pos("bl").adds(0, 5).y;
	    }
	    y = main.add(new Button(UI.scale(200), "Switch character", false).action(() -> {
			getparent(GameUI.class).act("lo", "cs");
	    }), 0, y).pos("bl").adds(0, 5).y;
	    y = main.add(new Button(UI.scale(200), "Log out", false).action(() -> {
			getparent(GameUI.class).act("lo");
	    }), 0, y).pos("bl").adds(0, 5).y;
	}
	y = main.add(new Button(UI.scale(200), "Close", false).action(() -> {
		    OptWnd.this.hide();
	}), 0, y).pos("bl").adds(0, 5).y;
	this.main.pack();

	chpanel(this.main);
    }

    public OptWnd() {
	this(true);
    }

    public void reqclose() {
	hide();
    }

    public void show() {
	chpanel(main);
	super.show();
    }
}
