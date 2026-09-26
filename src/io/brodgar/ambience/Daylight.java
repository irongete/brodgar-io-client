package io.brodgar.ambience;

import java.awt.Color;
import haven.*;

/* The day and the night the improved sky lights the world with.
 *
 * The server says what time of day it is (Astronomy.dt), which phase the moon is in (mp), how far through
 * the year it is (yt) and whether it is night; the game's own light is whatever colours and angle it sends
 * with that. Under the improved sky the scene is lit by this instead: a sun and a moon that stand where the
 * sky draws them, on paths worked out as a real sky's are, seen from a middle latitude.
 *
 * - The sun climbs from the east, stands highest at noon, and sets in the west; its colour warms and its
 *   light fades as it nears the horizon, and the sky's light (the ambient) fades through the twilight into
 *   the night's.
 * - The moon runs behind the sun by its phase, as the real one does: the new moon rides with the sun and is
 *   never seen, the full moon stands across the sky from it and is up all night, a first quarter is highest
 *   at dusk and sets at midnight. Its light is as bright as it is full and as high as it stands, so a night
 *   with no moon up is the darkest the sky gets -- dark, but never black.
 * - The scene's one light, and the shadows it casts, is whichever of the two lights the world more; where
 *   one hands over to the other, both are weak, and the light between them fades to nothing and back.
 * - The seasons move the sun's path: higher and further round in summer, lower in winter.
 *
 * The server's night stays the authority for WHEN it is dark: its night begins and ends as the sun passes
 * DUSK below the horizon here. Where in its day that happens is learnt from its night flag -- every edge
 * the flag is seen to cross is kept, for the next login too -- and the day and the night between are
 * spread over the sun's own arc above and below that height. */
final class Daylight {
    /* The latitude the sky is seen from, and how far the seasons move the sun's path north and south (the
     * real tilt is 23.4 degrees: this is gentler). */
    static final double LAT = Math.toRadians(45), TILT = Math.toRadians(15);
    /* How far below the horizon the sun stands when the server's night begins and ends. */
    static final double DUSK = Math.toRadians(-6);
    /* Never below 12 degrees: the shadow map is a 750-unit box, and a grazing light stretches every shadow
     * out of it. The colour says sunrise; the shadow stays one the map can hold. */
    static final double MINELEV = Math.toRadians(12);
    /* How far the light turns before a new one is put in the scene: every new one is a new shadow map and a
     * new compile of the scene's light (MapView.amblight), and a quarter of a degree is no shadow's jump. */
    static final double STEP = Math.toRadians(0.25);

    /* The night's light with no moon up: starlight, dark but playable. */
    static final float[] NIGHT = {0.085f, 0.095f, 0.175f};
    /* A full moon's light, high up: its direct light, and what it adds to the sky's. */
    static final float[] MOONDIF = {0.32f, 0.36f, 0.48f}, MOONAMB = {0.035f, 0.040f, 0.065f};
    /* The brightest channel of a clear noon's light, ambient and direct together (AMB and HUE at the top). */
    static final float NOON = 1.48f;

    /* The sun's colour by its height in degrees: red at the horizon, white high up. */
    private static final float[][] HUE = {
	{-10, 1.00f, 0.45f, 0.25f},
	{  0, 1.00f, 0.52f, 0.28f},
	{  4, 1.00f, 0.62f, 0.38f},
	{ 10, 1.00f, 0.80f, 0.62f},
	{ 20, 1.00f, 0.92f, 0.82f},
	{ 35, 1.00f, 0.97f, 0.91f},
	{ 90, 1.00f, 0.98f, 0.94f},
    };
    /* The sky's own light by the sun's height: the night's below -18, the twilight's purple, the day's grey. */
    private static final float[][] AMB = {
	{-18, NIGHT[0], NIGHT[1], NIGHT[2]},
	{ -8, 0.12f, 0.12f, 0.22f},
	{ -3, 0.20f, 0.18f, 0.27f},
	{  2, 0.30f, 0.26f, 0.30f},
	{  8, 0.38f, 0.35f, 0.36f},
	{ 18, 0.44f, 0.44f, 0.45f},
	{ 35, 0.48f, 0.48f, 0.50f},
	{ 90, 0.48f, 0.48f, 0.50f},
    };

    /* One moment of the sky: where the sun and the moon stand, how they light it, and the scene's light. */
    static final class Sky {
	/* Unit vectors toward the sun and the moon, and their heights in degrees. */
	final float[] sdir, mdir;
	final float sel, mel;
	/* The sun's colour at its height, and how much of its light reaches the ground (0 once it is down). */
	final float[] hue;
	final float sp;
	/* The moon's phase (0 new, 0.5 full), how much of it is lit, and how much of a full moon high up is
	 * lighting the night now. */
	final float mphase, illum, moonlight;
	/* The scene's light: ambient, direct, and where the direct light comes from. */
	final Color amb, dif;
	final float elev, ang;
	/* Whether the scene's light is the moon's. */
	final boolean bymoon;
	/* How bright the scene's light is against a clear noon's, 0..1: what the game's cel shading is dimmed
	 * by (Ambience.celscale). */
	final float cel;

	Sky(float[] sdir, float[] mdir, float[] hue, float sp, float mphase, float illum, float moonlight,
	    Color amb, Color dif, float elev, float ang, boolean bymoon, float cel) {
	    this.sdir = sdir; this.mdir = mdir; this.hue = hue; this.sp = sp;
	    this.sel = deg(sdir[2]); this.mel = deg(mdir[2]);
	    this.mphase = mphase; this.illum = illum; this.moonlight = moonlight;
	    this.amb = amb; this.dif = dif; this.elev = elev; this.ang = ang; this.bymoon = bymoon;
	    this.cel = cel;
	}
    }

    /* The server's day, as its night flag shows it: its day fraction at noon, and where its night ends and
     * begins, as fractions of a day from noon (rise before it, set after). Kept as prefs, since every login
     * learns them afresh otherwise. */
    static double noon = 0.0;
    static double rise = span("ambience-nightend", -0.30, -0.45, -0.05);
    static double set = span("ambience-nightstart", 0.30, 0.05, 0.45);
    private static Boolean lastnight = null;
    /* The server sends the time of day now and then: between, it is carried on at the rate it was seen to
     * run at, game seconds of the world's clock against fractions of a day. Nothing is carried on until two
     * readings have shown the rate. */
    private static Astronomy lastast = null;
    private static double astgt = 0, prevdt = Double.NaN, prevgt = 0, rate = Double.NaN;

    private static double span(String pref, double def, double lo, double hi) {
	double v = Utils.getprefd(pref, def);
	return((Double.isFinite(v) && (v >= lo) && (v <= hi)) ? v : def);
    }

    static double wrap(double x) {return(x - Math.floor(x + 0.5));}
    static double frac(double x) {return(x - Math.floor(x));}
    static float deg(float z) {return((float)Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, z)))));}

    /* A new reading of the server's astronomy: its day learnt from the night flag, and the clock's rate. */
    static void observe(Astronomy ast, double gt) {
	if((ast == null) || (ast == lastast))
	    return;
	lastast = ast;
	astgt = gt;
	if(Double.isFinite(prevdt)) {
	    double ddt = frac(ast.dt - prevdt), dgt = gt - prevgt;
	    if((dgt > 5) && (ddt > 0) && (ddt < 0.2)) {
		double r = ddt / dgt;
		rate = Double.isFinite(rate) ? (rate + ((r - rate) * 0.3)) : r;
	    }
	}
	prevdt = ast.dt;
	prevgt = gt;
	/* The game's calendar (Cal) draws the sun at its top at 0 and the moon at its top at 0.5: so noon is
	 * 0, unless the server's night is seen to stand round 0 instead. */
	if(ast.night)
	    noon = (Math.abs(ast.dt - 0.5) < 0.25) ? 0.0 : 0.5;
	double y = wrap(ast.dt - noon);
	boolean edge = (lastnight != null) && (lastnight != ast.night);
	lastnight = ast.night;
	if(ast.night) {
	    if((y >= rise) && (y <= set)) {
		if(y > 0)
		    set = Math.max(0.05, y);
		else
		    rise = Math.min(-0.05, y);
	    }
	} else {
	    if(y > set)
		set = Math.min(0.45, y);
	    else if(y < rise)
		rise = Math.max(-0.45, y);
	}
	if(edge) {
	    if(ast.night)
		Utils.setprefd("ambience-nightstart", set);
	    else
		Utils.setprefd("ambience-nightend", rise);
	}
    }

    /* The server's time of day now, carried on from its last reading. */
    static double dtnow(Astronomy ast, double gt) {
	if(!Double.isFinite(rate) || (ast != lastast))
	    return(ast.dt);
	return(ast.dt + Math.max(0, Math.min(0.05, (gt - astgt) * rate)));
    }

    /* The sun's hour angle at a fraction of a day from noon: the server's day spread over the sun's arc
     * above DUSK, its night over the arc below. */
    static double hour(double y, double decl) {
	double c = (Math.sin(DUSK) - (Math.sin(LAT) * Math.sin(decl))) / (Math.cos(LAT) * Math.cos(decl));
	double h0 = Math.acos(Math.max(-1, Math.min(1, c)));
	double day = set - rise;
	if((y >= rise) && (y <= set))
	    return(-h0 + (((y - rise) / day) * 2 * h0));
	double yy = (y < rise) ? (y + 1) : y;
	return(h0 + (((yy - set) / (1 - day)) * ((2 * Math.PI) - (2 * h0))));
    }

    /* Toward a body at an hour angle and declination, as the world's axes have it: east is +x, and the sun
     * stands highest toward +y. */
    static float[] dir(double h, double decl) {
	double up = (Math.sin(LAT) * Math.sin(decl)) + (Math.cos(LAT) * Math.cos(decl) * Math.cos(h));
	double north = (Math.cos(LAT) * Math.sin(decl)) - (Math.sin(LAT) * Math.cos(decl) * Math.cos(h));
	double east = -Math.cos(decl) * Math.sin(h);
	return(new float[] {(float)east, (float)-north, (float)up});
    }

    /* Where the year is, 0 at spring's start: the server's year fraction, or its season where the two
     * disagree. */
    static double season(Astronomy ast) {
	if(Ambience.pyear != null)
	    return(frac(Ambience.pyear));
	if(ast == null)
	    return(0.125);
	double s = frac(ast.yt);
	if(ast.seasonPublished && ((int)Math.floor(s * 4) != ast.is) && (ast.is >= 0) && (ast.is < 4))
	    s = (ast.is + 0.5) / 4;
	return(s);
    }

    static float[] table(float[][] t, float x) {
	if(x <= t[0][0])
	    return(new float[] {t[0][1], t[0][2], t[0][3]});
	for(int i = 1; i < t.length; i++) {
	    if(x <= t[i][0]) {
		float f = (x - t[i - 1][0]) / (t[i][0] - t[i - 1][0]);
		return(new float[] {t[i - 1][1] + ((t[i][1] - t[i - 1][1]) * f),
				    t[i - 1][2] + ((t[i][2] - t[i - 1][2]) * f),
				    t[i - 1][3] + ((t[i][3] - t[i - 1][3]) * f)});
	    }
	}
	float[] l = t[t.length - 1];
	return(new float[] {l[1], l[2], l[3]});
    }

    static float[] hue(float el) {return(table(HUE, el));}

    static Color col(float[] c) {
	return(new Color(Math.round(Ambience.clamp(c[0], 0, 1) * 255), Math.round(Ambience.clamp(c[1], 0, 1) * 255),
			 Math.round(Ambience.clamp(c[2], 0, 1) * 255)));
    }

    static float q(double a) {return((float)(Math.round(a / STEP) * STEP));}

    /* The sky now, or null where the server has said nothing of its time yet. A previewed hour, moon and
     * year (:amb time, moon, year) stand in for the server's. ovc and gloom are the weather's: how overcast
     * the sky is, and how dark the rain or snow makes it. */
    static Sky at(Astronomy ast, double gt, float ovc, float gloom) {
	observe(ast, gt);
	double y;
	if(Ambience.ptime != null)
	    y = wrap((Ambience.ptime - 12) / 24);
	else if(ast != null)
	    y = wrap(dtnow(ast, gt) - noon);
	else
	    return(null);
	double mp = frac((Ambience.pmoon != null) ? Ambience.pmoon : ((ast != null) ? ast.mp : 0.5));
	double decl = TILT * Math.sin(2 * Math.PI * (season(ast) - 0.125));
	double hs = hour(y, decl);
	float[] sdir = dir(hs, decl);
	float[] mdir = dir(hs - (2 * Math.PI * mp), decl * Math.cos(2 * Math.PI * mp));
	float sel = deg(sdir[2]), mel = deg(mdir[2]);

	float[] hue = hue(sel);
	float sp = Ambience.smooth(-1, 8, sel);
	float illum = (float)((1 - Math.cos(2 * Math.PI * mp)) / 2);
	/* The moon lights the world as the sun leaves it: by day its light is nothing beside the sun's. */
	float mup = Ambience.smooth(-1, 15, mel) * (1 - Ambience.smooth(-6, 2, sel));
	float moonlight = illum * mup;

	float clear = (1 - (0.8f * ovc)) * (1 - (0.3f * gloom));
	float[] sdif = Ambience.mul(hue, sp * clear), mdif = Ambience.mul(MOONDIF, moonlight * clear);
	float[] amb = table(AMB, sel);
	amb = new float[] {amb[0] + (MOONAMB[0] * moonlight), amb[1] + (MOONAMB[1] * moonlight), amb[2] + (MOONAMB[2] * moonlight)};
	/* Without dark nights, what the moon falls short of a full one high up is made up as the sky's light:
	 * no night is darker than a full moon's, though only the moon casts shadows. */
	if(!Ambience.darknights) {
	    float nightw = 1 - Ambience.smooth(-6, 2, sel);
	    float miss = Math.max(0, nightw - moonlight);
	    for(int i = 0; i < 3; i++)
		amb[i] += miss * (MOONAMB[i] + (MOONDIF[i] * 0.5f * clear));
	}
	/* Under cloud, the direct light that is lost is spread round as the sky's. */
	float lost = Ambience.lum(Ambience.mul(hue, sp)) * (1 - clear) * 0.3f;
	amb = Ambience.mul(new float[] {amb[0] + lost, amb[1] + lost, amb[2] + lost}, 1 - (0.25f * gloom));

	float ls = Ambience.lum(sdif), lm = Ambience.lum(mdif);
	boolean bymoon = (lm > ls) || ((ls <= 0) && (mel > sel));
	float[] dif, from;
	if(bymoon) {
	    dif = Ambience.mul(mdif, (lm > 0) ? (1 - (ls / lm)) : 0);
	    from = mdir;
	} else {
	    dif = Ambience.mul(sdif, (ls > 0) ? (1 - (lm / ls)) : 0);
	    from = sdir;
	}
	/* The brightest channel of the light a face turned to it gets, against a clear noon's. */
	float cel = Math.max(0.05f, Math.min(1, Math.max(amb[0] + dif[0], Math.max(amb[1] + dif[1], amb[2] + dif[2])) / NOON));
	float elev = q(Math.max(MINELEV, Math.asin(Math.max(-1, Math.min(1, from[2])))));
	float ang = q(Math.atan2(from[1], from[0]));
	return(new Sky(sdir, mdir, hue, sp, (float)mp, illum, moonlight, col(amb), col(dif), elev, ang, bymoon, cel));
    }

    static String describe(Sky d) {
	if(d == null)
	    return("no time of day yet");
	return(String.format("sun %.1f deg, moon %.1f deg (%.0f%% lit), lit by the %s at %.0f%% of noon, night from %.3f to %.3f of the day (noon at %.1f)",
			     d.sel, d.mel, d.illum * 100, d.bymoon ? "moon" : "sun", d.cel * 100, frac(noon + set), frac(noon + rise), noon));
    }
}
