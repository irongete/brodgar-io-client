/* Preprocessed source code */
package haven.res.ui.music;

import haven.*;
import haven.render.*;
import java.awt.Color;
import java.util.*;
import java.io.*;
import java.awt.event.KeyEvent;
import haven.Audio.CS;

/* >wdg: MusicWnd */
/* addon: local copy of the resource's own source (`haven.Resource get-code ui/music`), adopted to show the
 * whole instrument. The published window draws ONE octave -- twelve keys, ZSXDCVGBHNJM -- and reaches the
 * other two the server accepts by holding Shift (up) or Ctrl (down), so a player never sees the keyboard
 * they are actually playing, and a key's octave is in a modifier rather than under a finger. This copy
 * draws all three octaves side by side, thirty-six keys, each under its own letter --
 * 1234567890QW / ERTYUIOPASDF / GHJKLZXCVBNM, low to high -- and drops the modifiers.
 *   What leaves the window is unchanged: `play(key, t)` and `stop(key, t)` with `key` 0..35, `t` seconds
 * since the window opened plus `latcomp`, and the polyphony the server handed the constructor kept by
 * releasing the oldest note past it. The key art, the tips' font and the timing are the resource's own.
 *   The @FromResource line above is what makes the engine prefer this copy, and it is version-PINNED: when
 * the server publishes a newer `ui/music` this file is ignored and the served one-octave window runs
 * again. */
@haven.FromResource(name = "ui/music", version = 35)
public class MusicWnd extends Window {
    public static final Tex[] tips;
    public static final Map<Integer, Integer> keys;
    /* The white keys' semitones over three octaves, and their positions on the row; the black keys' the
     * same, positioned by the white key each sits between. */
    public static final int[] nti = {0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24, 26, 28, 29, 31, 33, 35};
    public static final int[] shi = {1, 3, 6, 8, 10, 13, 15, 18, 20, 22, 25, 27, 30, 32, 34};
    public static final int[] ntp = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20};
    public static final int[] shp = {0, 1, 3, 4, 5, 7, 8, 10, 11, 12, 14, 15, 17, 18, 19};
    public static final Tex[] ikeys;
    public final boolean[] cur = new boolean[12 * 3];
    public final int[] act;
    public final double start;
    public double latcomp = 0.15;
    public int actn;

    static {
	/* One letter per key, low to high: the digits' row, then the row under it, then the bottom one.
	 * Read across the keyboard it is the same shape the piano has, three octaves wide. */
	String tc = "1234567890QWERTYUIOPASDFGHJKLZXCVBNM";
	int[] codes = {
	    KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4, KeyEvent.VK_5, KeyEvent.VK_6,
	    KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9, KeyEvent.VK_0, KeyEvent.VK_Q, KeyEvent.VK_W,
	    KeyEvent.VK_E, KeyEvent.VK_R, KeyEvent.VK_T, KeyEvent.VK_Y, KeyEvent.VK_U, KeyEvent.VK_I,
	    KeyEvent.VK_O, KeyEvent.VK_P, KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D, KeyEvent.VK_F,
	    KeyEvent.VK_G, KeyEvent.VK_H, KeyEvent.VK_J, KeyEvent.VK_K, KeyEvent.VK_L, KeyEvent.VK_Z,
	    KeyEvent.VK_X, KeyEvent.VK_C, KeyEvent.VK_V, KeyEvent.VK_B, KeyEvent.VK_N, KeyEvent.VK_M,
	};
	Map<Integer, Integer> km = new HashMap<Integer, Integer>();
	for(int i = 0; i < codes.length; i++)
	    km.put(codes[i], i);
	Tex[] il = new Tex[4];
	for(int i = 0; i < 4; i++) {
	    il[i] = Resource.classres(MusicWnd.class).layer(Resource.imgc, i).tex();
	}
	Text.Foundry fnd = new Text.Foundry(Text.fraktur.deriveFont(java.awt.Font.BOLD, 16)).aa(true);
	Tex[] tl = new Tex[tc.length()];
	for(int i = 0; i < nti.length; i++) {
	    int ki = nti[i];
	    tl[ki] = fnd.render(tc.substring(ki, ki + 1), new Color(0, 0, 0)).tex();
	}
	for(int i = 0; i < shi.length; i++) {
	    int ki = shi[i];
	    tl[ki] = fnd.render(tc.substring(ki, ki + 1), new Color(255, 255, 255)).tex();
	}
	keys = km;
	ikeys = il;
	tips = tl;
    };

    public MusicWnd(String name, int maxpoly) {
	super(ikeys[0].sz().mul(nti.length, 1), name, true);
	this.act = new int[maxpoly];
	this.start = System.currentTimeMillis() / 1000.0;
    }

    public static Widget mkwidget(UI ui, Object[] args) {
	String nm = (String)args[0];
	int maxpoly = (Integer)args[1];
	return(new MusicWnd(nm, maxpoly));
    }

    protected void added() {
	super.added();
	ui.grabkeys(this);
    }

    public void cdraw(GOut g) {
	boolean[] cact = new boolean[cur.length];
	for(int i = 0; i < actn; i++)
	    cact[act[i]] = true;
	for(int i = 0; i < nti.length; i++) {
	    Coord c = new Coord(ikeys[0].sz().x * ntp[i], 0);
	    boolean a = cact[nti[i]];
	    g.image(ikeys[a?1:0], c);
	    g.image(tips[nti[i]], c.add((ikeys[0].sz().x - tips[nti[i]].sz().x) / 2, ikeys[0].sz().y - tips[nti[i]].sz().y - (a?9:12)));
	}
	int sho = ikeys[0].sz().x - (ikeys[2].sz().x / 2);
	for(int i = 0; i < shi.length; i++) {
	    Coord c = new Coord(ikeys[0].sz().x * shp[i] + sho, 0);
	    boolean a = cact[shi[i]];
	    g.image(ikeys[a?3:2], c);
	    g.image(tips[shi[i]], c.add((ikeys[2].sz().x - tips[shi[i]].sz().x) / 2, ikeys[2].sz().y - tips[shi[i]].sz().y - (a?9:12)));
	}
    }

    public boolean keydown(KeyDownEvent ev) {
	double now = (ev.awt.getWhen() / 1000.0) + latcomp;
	Integer keyp = keys.get(ev.code);
	if(keyp != null) {
	    int key = keyp;
	    if(!cur[key]) {
		if(actn >= act.length) {
		    wdgmsg("stop", act[0], (float)(now - start));
		    for(int i = 1; i < actn; i++)
			act[i - 1] = act[i];
		    actn--;
		}
		wdgmsg("play", key, (float)(now - start));
		cur[key] = true;
		act[actn++] = key;
	    }
	    return(true);
	}
	super.keydown(ev);
	return(true);
    }

    private void stopnote(double now, int key) {
	if(cur[key]) {
	    outer: for(int i = 0; i < actn; i++) {
		if(act[i] == key) {
		    wdgmsg("stop", key, (float)(now - start));
		    for(actn--; i < actn; i++)
			act[i] = act[i + 1];
		    break outer;
		}
	    }
	    cur[key] = false;
	}
    }

    public boolean keyup(KeyUpEvent ev) {
	double now = (ev.awt.getWhen() / 1000.0) + latcomp;
	Integer keyp = keys.get(ev.code);
	if(keyp != null) {
	    stopnote(now, keyp);
	    return(true);
	}
	return(super.keyup(ev));
    }
}
