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

import java.awt.Color;

public class Astronomy {
    public final double dt, mp, yt, sp, sd;
    public final double years, ym, md;
    public final boolean night;
    public final Color mc;
    public final int is;
    /* addon: whether the server actually SENT the season index, or `is` is Glob's own default of 1.
     * The number cannot say it -- 1 is a legal index and it is also what an absent field becomes -- and
     * hafen.time():season() has to answer nil for what the server does not publish rather than a confident
     * "summer". Nothing in the client's own calendar reads this: Cal takes `is` either way. */
    public final boolean seasonPublished;
	
    public Astronomy(double dt, double mp, double yt, boolean night, Color mc, int is, double sp, double sd, double years, double ym, double md) {
	this(dt, mp, yt, night, mc, is, true, sp, sd, years, ym, md);
    }

    /* addon: the same, told whether `is` came off the wire -- see `seasonPublished`. */
    public Astronomy(double dt, double mp, double yt, boolean night, Color mc, int is, boolean seasonPublished, double sp, double sd, double years, double ym, double md) {
	this.seasonPublished = seasonPublished;
	this.dt = dt;
	this.mp = mp;
	this.yt = yt;
	this.night = night;
	this.mc = mc;
	this.is = is;
	this.sp = sp;
	this.sd = sd;
	this.years = years;
	this.ym = ym;
	this.md = md;
    }
}
