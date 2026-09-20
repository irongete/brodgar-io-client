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

/* addon: (login) WHAT THE FIRST SECONDS OF A SESSION PAY FOR THE FIRST TIME, PAID AT THE LOGIN SCREEN
 * INSTEAD, where the client sits idle for seconds before any grid arrives.
 *
 * The cut mesh: the first cut of a session is built by the interpreter. Measured at login, the cut under
 * the camera -- the one the screen stays black for -- cost 265-563 ms of CPU, and the same build 16-31 ms
 * once the JIT had seen the code; two thirds of the black screen was that one build. So the code is
 * exercised here, on ground of no session's: a cache no server fills (the doors 068 opened for the
 * remembered ground), one grid of three common tilesets over stepped heights (transitions and ridges are
 * code too), and the middle cut built a few times over, until the hot loops have crossed the compile
 * thresholds. What it loads it loads from the resource pool, where the session finds it again.
 *
 * The text engine: the first rich-text layout of the process initialises Java2D's shaper (HarfBuzz through
 * the foreign-function API since JDK 23), 150-300 ms measured, and at login that render was an item
 * tooltip's, on the UI thread, between the map's arrival and the camera's first tick. One line here.
 *
 * A failure is a warm-up that did not happen: nothing depends on it. */
public class LoginWarmup {
    private static final String[] tilesets = {"gfx/tiles/grass", "gfx/tiles/field", "gfx/tiles/paving/ballbrick"};
    private static final int builds = 4;
    private static boolean started = false;

    public static synchronized void start() {
	if(started)
	    return;
	started = true;
	Thread t = new HackThread(LoginWarmup::run, "Login warm-up");
	t.setDaemon(true);
	t.setPriority((Thread.NORM_PRIORITY + Thread.MIN_PRIORITY) / 2);
	t.start();
    }

    private static void run() {
	long t0 = System.currentTimeMillis();
	try {
	    RichText.render("warm-up", 100);
	    MCache mc = new MCache(null);
	    for(int i = 0; i < tilesets.length; i++)
		AddonWidgets.settileset(mc, i, new Resource.Spec(Resource.remote(), tilesets[i], -1));
	    int[] tiles = new int[MCache.cmaps.x * MCache.cmaps.y];
	    float[] z = new float[tiles.length];
	    Random rnd = new Random(1);
	    for(int y = 0, i = 0; y < MCache.cmaps.y; y++) {
		for(int x = 0; x < MCache.cmaps.x; x++, i++) {
		    tiles[i] = ((x / 7 + y / 5) % 3 == 0) ? 1 : (((x % 23) < 6) && ((y % 17) < 5)) ? 2 : 0;
		    z[i] = ((y / 9) % 2 == 0 ? 0 : 12) + (x / 13) * 3 + rnd.nextFloat() * 2;
		}
	    }
	    AddonWidgets.putgrid(mc, Coord.z, 1, tiles, z);
	    /* The middle cuts: every read a build makes across its own edge stays inside this grid. */
	    Coord[] cuts = {Coord.of(1, 1), Coord.of(2, 1), Coord.of(1, 2), Coord.of(2, 2)};
	    for(int b = 0; b < builds; b++) {
		Coord ul = cuts[b % cuts.length].mul(MCache.cutsz);
		for(int attempt = 0; attempt < 200; attempt++) {
		    try {
			MapMesh m = MapMesh.build(mc, new Random(b), ul, MCache.cutsz);
			m.dispose();
			break;
		    } catch(Loading l) {
			l.waitfor();
		    }
		}
	    }
	} catch(Throwable e) {
	    /* One line, not silence: a warm-up that gives up is a login that pays what it was meant to spare. */
	    System.err.println("Login warm-up gave up after " + (System.currentTimeMillis() - t0) + " ms: " + e);
	}
    }
}
