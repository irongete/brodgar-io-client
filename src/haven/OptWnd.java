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

    /* A full showcase of the io.brodgar.voice.Voice API — every control routes
     * through Voice.* (never a session handle), and the live state is read the same
     * way. Copy the bits you want into your own client's UI. */
    public class VoiceChatPanel extends Panel {
	private final Label status, hearing, heard, speaking, event;
	private volatile String lastEvent = "—";

	// Event API: callbacks fire on a background thread, so we only stash the
	// latest here and render it from tick() on the UI thread.
	private final io.brodgar.voice.VoiceListener vl = new io.brodgar.voice.VoiceListener() {
		public void onConnectionState(boolean connected) {lastEvent = connected ? "connected" : "disconnected";}
		public void onAudibleSetChanged(java.util.Set<Long> gobs) {lastEvent = "audible set (" + gobs.size() + ")";}
		public void onHeardByChanged(java.util.Set<Long> gobs) {lastEvent = "heard-by set (" + gobs.size() + ")";}
		public void onError(String code, String msg, boolean fatal) {lastEvent = "error: " + code;}
	    };

	public VoiceChatPanel() {
	    Widget prev;
	    prev = add(new Label("Brodgar.io Proximity Voice Chat"), 0, 0);

	    prev = add(new CheckBox("Enable voice chat") {
		    {a = io.brodgar.voice.Voice.isEnabled();}
		    public void set(boolean val) {io.brodgar.voice.Voice.setEnabled(val); a = val;}
		}, prev.pos("bl").adds(0, 10));

	    // -- Microphone (you -> others) --
	    prev = add(new Label("Microphone"), prev.pos("bl").adds(0, 15));
	    prev = add(new CheckBox("Open mic (off = push-to-talk)") {
		    {a = io.brodgar.voice.Voice.isOpenMic();}
		    public void set(boolean val) {io.brodgar.voice.Voice.setOpenMic(val); a = val;}
		}, prev.pos("bl").adds(0, 2));
	    prev = add(new CheckBox("Mute my microphone") {
		    {a = io.brodgar.voice.Voice.isMicMuted();}
		    public void set(boolean val) {io.brodgar.voice.Voice.setMicMuted(val); a = val;}
		}, prev.pos("bl").adds(0, 2));
	    prev = add(new Label("Open-mic threshold (lower = more sensitive)"), prev.pos("bl").adds(0, 8));
	    {
		Label dpy = new Label("");
		addhlp(prev.pos("bl").adds(0, 2), UI.scale(5),
		       prev = new HSlider(UI.scale(160), 0, 1000, (int)io.brodgar.voice.Voice.micSensitivity()) {
			       protected void added() {dpy();}
			       void dpy() {dpy.settext(Integer.toString(this.val));}
			       public void changed() {io.brodgar.voice.Voice.setMicSensitivity(this.val); dpy();}
			   }, dpy);
	    }

	    // -- Playback (others -> you) --
	    prev = add(new Label("Playback"), prev.pos("bl").adds(0, 15));
	    prev = add(new CheckBox("Deafen (silence others)") {
		    {a = io.brodgar.voice.Voice.isDeafened();}
		    public void set(boolean val) {io.brodgar.voice.Voice.setDeafened(val); a = val;}
		}, prev.pos("bl").adds(0, 2));
	    prev = add(new Label("Master volume"), prev.pos("bl").adds(0, 8));
	    {
		Label dpy = new Label("");
		addhlp(prev.pos("bl").adds(0, 2), UI.scale(5),
		       prev = new HSlider(UI.scale(160), 0, 400, (int)(io.brodgar.voice.Voice.masterVolume() * 100)) {
			       protected void added() {dpy();}
			       void dpy() {dpy.settext(this.val + "%");}
			       public void changed() {io.brodgar.voice.Voice.setMasterVolume(this.val / 100f); dpy();}
			   }, dpy);
	    }

	    // -- Live state (read API + events) --
	    status = add(new Label(""), prev.pos("bl").adds(0, 15));
	    hearing = add(new Label(""), status.pos("bl").adds(0, 4));
	    heard = add(new Label(""), hearing.pos("bl").adds(0, 2));
	    speaking = add(new Label(""), heard.pos("bl").adds(0, 2));
	    event = add(new Label(""), speaking.pos("bl").adds(0, 6));

	    io.brodgar.voice.Voice.addListener(vl);
	    pack();
	}

	public void tick(double dt) {
	    super.tick(dt);
	    if(status == null)
		return;
	    status.settext("Status: " + (io.brodgar.voice.Voice.isConnected() ? "connected" : "not connected"));
	    hearing.settext("Hearing: " + io.brodgar.voice.Voice.audible().size());
	    heard.settext("Heard by: " + io.brodgar.voice.Voice.heardBy().size());
	    speaking.settext("Speaking: " + io.brodgar.voice.Voice.speakingGobs().size());
	    event.settext("last event: " + lastEvent);
	}

	public void destroy() {
	    io.brodgar.voice.Voice.removeListener(vl);
	    super.destroy();
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

	public BindingPanel() {
	    super();
	    Scrollport scroll = add(new Scrollport(UI.scale(new Coord(300, 300))), 0, 0);
	    Widget cont = scroll.cont;
	    Widget prev;
	    int y = 0;
	    y = cont.adda(new Label("Main menu"), cont.sz.x / 2, y, 0.5, 0.0).pos("bl").adds(0, 5).y;
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
	    y = cont.adda(new Label("Map options"), cont.sz.x / 2, y + UI.scale(10), 0.5, 0.0).pos("bl").adds(0, 5).y;
	    y = addbtn(cont, "Display claims", GameUI.kb_claim, y);
	    y = addbtn(cont, "Display villages", GameUI.kb_vil, y);
	    y = addbtn(cont, "Display realms", GameUI.kb_rlm, y);
	    y = addbtn(cont, "Display grid-lines", MapView.kb_grid, y);
	    y = cont.adda(new Label("Camera control"), cont.sz.x / 2, y + UI.scale(10), 0.5, 0.0).pos("bl").adds(0, 5).y;
	    y = addbtn(cont, "Rotate left", MapView.kb_camleft, y);
	    y = addbtn(cont, "Rotate right", MapView.kb_camright, y);
	    y = addbtn(cont, "Zoom in", MapView.kb_camin, y);
	    y = addbtn(cont, "Zoom out", MapView.kb_camout, y);
	    y = addbtn(cont, "Reset", MapView.kb_camreset, y);
	    y = cont.adda(new Label("Map window"), cont.sz.x / 2, y + UI.scale(10), 0.5, 0.0).pos("bl").adds(0, 5).y;
	    y = addbtn(cont, "Reset view", MapWnd.kb_home, y);
	    y = addbtn(cont, "Place marker", MapWnd.kb_mark, y);
	    y = addbtn(cont, "Toggle markers", MapWnd.kb_hmark, y);
	    y = addbtn(cont, "Compact mode", MapWnd.kb_compact, y);
	    y = cont.adda(new Label("Walking speed"), cont.sz.x / 2, y + UI.scale(10), 0.5, 0.0).pos("bl").adds(0, 5).y;
	    y = addbtn(cont, "Increase speed", Speedget.kb_speedup, y);
	    y = addbtn(cont, "Decrease speed", Speedget.kb_speeddn, y);
	    for(int i = 0; i < 4; i++)
		y = addbtn(cont, String.format("Set speed %d", i + 1), Speedget.kb_speeds[i], y);
	    // addon: the action bar's own keys. They were raw key codes inside the two belt widgets' globtype
	    // overrides, so they were in no panel and could not be moved off the row they claimed.
	    y = cont.adda(new Label("Action bar"), cont.sz.x / 2, y + UI.scale(10), 0.5, 0.0).pos("bl").adds(0, 5).y;
	    for(int i = 0; i < GameUI.kb_belt.length; i++)
		y = addbtn(cont, String.format("Button %d", i + 1), GameUI.kb_belt[i], y);
	    for(int i = 0; i < GameUI.kb_beltpg.length; i++)
		y = addbtn(cont, String.format("Go to page %d", i + 1), GameUI.kb_beltpg[i], y);
	    y = cont.adda(new Label("Combat actions"), cont.sz.x / 2, y + UI.scale(10), 0.5, 0.0).pos("bl").adds(0, 5).y;
	    for(int i = 0; i < Fightsess.kb_acts.length; i++)
		y = addbtn(cont, String.format("Combat action %d", i + 1), Fightsess.kb_acts[i], y);
	    y = addbtn(cont, "Switch targets", Fightsess.kb_relcycle, y);
	    y = cont.adda(new Label("Voice chat"), cont.sz.x / 2, y + UI.scale(10), 0.5, 0.0).pos("bl").adds(0, 5).y;
	    y = addbtn(cont, "Push to talk", io.brodgar.voice.Voice.kb_ptt, y);
	    // addon: dynamic per-addon hotkey sections (WoW-style; Phase 2e-3). One section per addon that
	    // registered a hotkey (keybindings:register; none registered -> no section); the client's SetButton captures
	    // and persists each re-map exactly like a built-in binding, so nothing else is needed here.
	    for(io.brodgar.addon.AddonManager.KeyBindGroup grp : io.brodgar.addon.AddonManager.describeKeyBinds()) {
		y = cont.adda(new Label(grp.addon), cont.sz.x / 2, y + UI.scale(10), 0.5, 0.0).pos("bl").adds(0, 5).y;
		for(io.brodgar.addon.AddonManager.KeyBindEntry e : grp.binds)
		    y = addbtn(cont, e.name, e.binding, y);
	    }
	    prev = adda(new PointBind(UI.scale(200)), scroll.pos("bl").adds(0, 10).x(scroll.sz.x / 2), 0.5, 0.0);
	    pack();
	}

	public class SetButton extends KeyMatch.Capture {
	    public final KeyBinding cmd;

	    public SetButton(int w, KeyBinding cmd) {
		super(w, cmd.key());
		this.cmd = cmd;
	    }

	    public void set(KeyMatch key) {
		super.set(key);
		cmd.set(key);
	    }

	    public void draw(GOut g) {
		if(cmd.key() != key)
		    super.set(cmd.key());
		super.draw(g);
	    }

	    protected KeyMatch mkmatch(KeyEvent ev) {
		return(KeyMatch.forevent(ev, ~cmd.modign));
	    }

	    protected boolean handle(KeyEvent ev) {
		if(ev.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
		    cmd.set(null);
		    super.set(cmd.key());
		    return(true);
		}
		return(super.handle(ev));
	    }

	    public Object tooltip(Coord c, Widget prev) {
		return(kbtt().tex());   // addon: (F3d)
	    }
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
	     * so an equal-but-distinct string shows in the closed box and highlights no row. A name the
	     * registry does not have is null -- a dead pref reads as no selection, not as a camera. */
	    private String current() {
		MapView mv = mapview();
		String nm = (mv == null) ? Utils.getpref("defcam", null) : mv.camname();
		for(String cand : MapView.camnames()) {
		    if(cand.equals(nm))
			return(cand);
		}
		return(null);
	    }

	    /* change(I) is the ONLY way to set what the closed box shows -- it both writes sel and
	     * rebuilds that widget -- so a re-sync runs super's half and none of the pick below. */
	    private void sync() {
		super.change(current());
	    }

	    public void change(String nm) {
		/* Read before super.change writes it. Clicking the row you are already on installs
		 * nothing: makecam would build a fresh camera, and the view would jump back to where a
		 * pan started for no gesture the user made. */
		boolean same = (nm == this.sel);
		super.change(nm);
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
			"Only affects cameras that support tilting (not the default ortho camera).", true);
	    pack();
	}
    }

    /* addon: (115.1) ONE ROW of a settings list -- the name the list draws, and the panel the holder shows
     * when it is picked. `fresh` is PButton's own flag moved onto the row: a panel built from what exists AT
     * THAT MOMENT (the keybindings) is rebuilt on every visit, so anything declared since the last one is
     * listed; everything else is built once and kept, exactly as PButton.click has always done. */
    public class PanelEntry {
	public final String name;
	private final Supplier<Panel> tgt;
	private final boolean fresh;
	private Panel actual = null;

	public PanelEntry(String name, Supplier<Panel> tgt, boolean fresh) {
	    this.name = name;
	    this.tgt = tgt;
	    this.fresh = fresh;
	}

	public PanelEntry(String name, Supplier<Panel> tgt) {
	    this(name, tgt, false);
	}
    }

    /* addon: (115.1) THE SETTINGS VIEW -- a tab strip over a list of subjects on the left and the panel the
     * picked one draws on the right. It is one of the panels chpanel swaps between, and it carries the
     * window's whole caption while it shows.
     *
     * Nothing in here binds Escape: the list IS the navigation, so no panel carries a Back any more, and
     * Escape reaches Window.keydown and closes the window as it does on every other window in the client. */
    public class SettingsPanel extends Panel {
	private final Tabs tabs;
	private final List<Subject> subjects = new ArrayList<Subject>();

	public SettingsPanel() {
	    super("Options");
	    tabs = new Tabs(Coord.z, Coord.z, this);
	    Subject game = new Subject(tabs.add(), gamepanels());
	    // The AddOns tab is the addons that declared an option of their own; it stands empty until one has.
	    Subject addons = new Subject(tabs.add(), new ArrayList<PanelEntry>());
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
	}

	private List<PanelEntry> gamepanels() {
	    List<PanelEntry> ret = new ArrayList<PanelEntry>();
	    ret.add(new PanelEntry("Interface settings", () -> new InterfacePanel()));
	    ret.add(new PanelEntry("Video settings", () -> new VideoPanel(ui)));
	    ret.add(new PanelEntry("Audio settings", () -> new AudioPanel(ui)));
	    // addon: `true` — rebuilt on every visit, so a hotkey an addon declared since the last one is listed.
	    ret.add(new PanelEntry("Keybindings", () -> new BindingPanel(), true));
	    ret.add(new PanelEntry("Camera", () -> new CameraPanel()));
	    ret.add(new PanelEntry("Voice Chat Integration", () -> new VoiceChatPanel()));
	    // addon: client-wide toggles (spec 019, task 019.1) — today just the profiling master switch.
	    ret.add(new PanelEntry("Client", () -> new io.brodgar.ui.ClientPanel(OptWnd.this)));
	    return(ret);
	}

	/* Every box in the view, bottom up, then the window around it. Called whenever what is in a holder
	 * changes size -- a swap, and a panel that repacks itself under one. */
	private void relayout() {
	    for(Subject s : subjects)
		s.holder.pack();
	    tabs.pack();       // every tab to the union of them, so the window does not jump between the two
	    pack();
	    OptWnd.this.cresize(this);
	}

	/* One tab: the list of subjects, and the holder its pick draws in. */
	public class Subject {
	    public final Tabs.Tab tab;
	    public final PanelList list;
	    public final Widget holder;
	    private final List<PanelEntry> entries;
	    private Panel shown = null;

	    private Subject(Tabs.Tab tab, List<PanelEntry> entries) {
		this.tab = tab;
		this.entries = entries;
		this.list = tab.add(new PanelList(this, UI.scale(new Coord(190, 320))), Coord.z);
		this.holder = tab.add(new Widget(Coord.z) {
			/* A panel inside a holder may repack itself long after it was built -- VideoPanel.resetcf
			 * throws its whole column away every time a graphics preference moves -- and Widget.cresize
			 * is a no-op, so without this the window keeps the box the FIRST build came out at. */
			public void cresize(Widget ch) {
			    relayout();
			}
		    }, list.pos("ur").adds(10, 0));
		subjects.add(this);
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
		relayout();
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
