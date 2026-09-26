package io.brodgar.ambience;

import java.awt.Color;
import java.util.*;
import haven.*;
import haven.render.*;

/* The server's weather and light, drawn our way.
 *
 * The server stays the authority for WHAT the world is doing -- its "wth" set (clouds, rain, snow)
 * and its "light" -- and this decides HOW it looks: a sky with a sun, stars and a handful of separate
 * clouds drawn wherever nothing else was, distance fog of the sky's own colour, and the clouds' shadows
 * on the ground. Rain and snow stay the game's own: they only heap and darken the sky here.
 *
 * The clouds are made up here, not read off the server: how many there are, how big and how dark
 * follows how much cloud and rain the server says there is -- a clear day has none or a stray few, a
 * wet one many, large and grey -- and each one forms, drifts with the wind, and after a while breaks up.
 *
 * Three parts, each switched on its own -- the sky, the clouds, the fog -- and each one off hands its
 * part back to the game: no sky of ours is the game's own background, no clouds of ours the game's own
 * cloud shadows, no fog of ours none at all. Off by default (:amb on switches all three). The previews (:amb time, :amb weather, :amb clouds) stand in for what the
 * server would say, so a sky it is not sending right now can be looked at. Every number the server's
 * arguments are read against is a guess from the game's own code (gfx/fx/clouds v11, gfx/fx/rain v2,
 * gfx/fx/snow v2). */
public class Ambience {
    /* The three parts. Each defaults to the one switch they shared before, `ambience`, so a sky that was
     * on stays on whole. */
    private static final boolean was = Utils.getprefb("ambience", false);
    public static volatile boolean skyon = Utils.getprefb("ambience-sky", was);
    public static volatile boolean cloudson = Utils.getprefb("ambience-clouds", was);
    public static volatile boolean fogon = Utils.getprefb("ambience-fogon", was);
    /* Set when drawing failed: every part goes back to the game for this session, prefs untouched. */
    static volatile boolean failed = false;
    static volatile Double ptime = null;
    static volatile Weather pweather = null;
    /* A number of clouds to show whatever the weather, or -1 for the weather's own (:amb clouds). */
    static volatile int pclouds = -1;
    /* A moon phase to show, 0 new to 0.5 full to 1 new again, or null for the server's (:amb moon). */
    static volatile Double pmoon = null;
    /* The phases' names, by eighths of the server's number: it sends the number, not the name. */
    static final String[] PHASES = {"new moon", "waxing crescent", "first quarter", "waxing gibbous",
				     "full moon", "waning gibbous", "last quarter", "waning crescent"};
    static String phase(double mp) {return(PHASES[Math.floorMod((int)Math.round(mp * 8), 8)]);}
    /* A number kept as a pref, or its default where the pref is not a finite number. */
    private static float prefd(String name, double def) {
	double v = Utils.getprefd(name, def);
	return((float)(Double.isFinite(v) ? v : def));
    }
    public static volatile float fogmul = prefd("ambience-fog", 1.0);

    static final String CLOUDS = "gfx/fx/clouds", RAIN = "gfx/fx/rain", SNOW = "gfx/fx/snow";
    /* How high the clouds' bases stand above the player's ground, in world units (a tile is 11). */
    public static volatile float cloudbase = prefd("ambience-cloudalt", 600);
    /* Near clouds form within SPAWN of the player and break up past DESPAWN. Far ones stand on the
     * horizon, between FARIN and FAROUT, and break up past FARGONE. */
    static final float SPAWN = 3500, DESPAWN = 5000;
    static final float FARIN = 7000, FAROUT = 22000, FARGONE = 30000;
    /* How much cloud every sky has against what the weather alone would give it, near and far alike: by
     * default a fifth more. 0 is a sky with none. */
    public static volatile float cloudamount = prefd("ambience-cloudamount", 1.2);
    /* Whether the night sky has its stars and its moon. The moon's light, and the shadows it casts, stay. */
    public static volatile boolean starsmoon = Utils.getprefb("ambience-stars", true);

    static {
	Console.setscmd("amb", Ambience::command);
    }

    /* The settings, as the Sky & weather page and the console write them: each one kept as a pref. */
    /* A part switched by hand, on the page or with :amb on|off, is also a retry after an error. */
    public static void skyon(boolean on) {
	Utils.setprefb("ambience-sky", skyon = on);
	failed = false;
    }

    public static void cloudson(boolean on) {
	Utils.setprefb("ambience-clouds", cloudson = on);
	failed = false;
    }

    public static void fogon(boolean on) {
	Utils.setprefb("ambience-fogon", fogon = on);
	failed = false;
    }

    /* Whether drawing failed and every part went back to the game (the Sky & weather page says so). */
    public static boolean failed() {return(failed);}

    /* Whether any part is drawn: the one question the tick, the pass and the light preview ask. */
    public static boolean enabled() {
	return(!failed && (skyon || cloudson || fogon));
    }

    private static float finite(String what, float v) {
	if(!Float.isFinite(v))
	    throw(new IllegalArgumentException(what + ": not a finite number: " + v));
	return(v);
    }

    public static void fog(float mul) {
	Utils.setprefd("ambience-fog", fogmul = Math.max(0, finite("fog", mul)));
    }

    public static void cloudalt(float units) {
	Utils.setprefd("ambience-cloudalt", cloudbase = finite("cloudalt", units));
    }

    public static void cloudamount(float mul) {
	Utils.setprefd("ambience-cloudamount", cloudamount = Math.max(0, finite("cloudamount", mul)));
    }

    public static void starsmoon(boolean on) {
	Utils.setprefb("ambience-stars", starsmoon = on);
    }

    /* Is this weather resource one this draws instead of the game? Asked by Performance.withheldWeather,
     * so the game's own renderer of it is held out of the scene while this is on. The clouds alone: the
     * game's rain and snow fall under this sky as they are. */
    public static boolean replaces(String name) {
	return(enabled() && cloudson && !indoors && CLOUDS.equals(name));
    }

    /* What a weather is, in the server's own terms: Clouds' and Rain's and Snow's arguments. */
    static class Weather {
	final String name;
	boolean clouds = false;
	/* The game reads cmin and cmax the other way round from how they sound: its shadow is full where its
	 * noise is under cmin and gone over cmax, so the HIGHER they are, the more cloud. None is below 0. */
	float scale = 1f / 1500, cmin = -0.1f, cmax = 0f, rmin = 1f, rmax = 1f;
	float cvx = 0.001f, cvy = 0.002f;
	float rain = 0, snow = 0;
	Coord3f wind = Coord3f.of(50, 20, -300);

	Weather(String name) {this.name = name;}

	Weather clouds(int div, float cmin, float cmax, float rmin, float rmax) {
	    this.clouds = true;
	    this.scale = 1f / div;
	    this.cmin = cmin; this.cmax = cmax; this.rmin = rmin; this.rmax = rmax;
	    return(this);
	}
	Weather vel(float x, float y) {this.cvx = x; this.cvy = y; return(this);}
	Weather rain(float rate) {this.rain = rate; return(this);}
	Weather snow(float rate) {this.snow = rate; return(this);}
	Weather wind(float x, float y, float z) {this.wind = Coord3f.of(x, y, z); return(this);}

	public String toString() {
	    StringBuilder buf = new StringBuilder(name);
	    if(clouds)
		buf.append(String.format(" clouds(1/%d, c %.2f-%.2f, r %.2f-%.2f, v %.4f,%.4f)", Math.round(1 / scale), cmin, cmax, rmin, rmax, cvx, cvy));
	    if(rain > 0)
		buf.append(String.format(" rain(%.0f/s, wind %.0f,%.0f,%.0f)", rain, wind.x, wind.y, wind.z));
	    if(snow > 0)
		buf.append(String.format(" snow(%.0f/s)", snow));
	    if(!clouds && (rain <= 0) && (snow <= 0))
		buf.append(" clear");
	    return(buf.toString());
	}
    }

    static final Map<String, Weather> presets = new LinkedHashMap<>();
    static {
	presets.put("clear",    new Weather("clear"));
	presets.put("fair",     new Weather("fair").clouds(1500, 0.26f, 0.38f, 0.55f, 1.0f));
	presets.put("cloudy",   new Weather("cloudy").clouds(1500, 0.42f, 0.60f, 0.45f, 1.0f));
	presets.put("overcast", new Weather("overcast").clouds(1500, 0.65f, 0.92f, 0.40f, 1.0f));
	presets.put("rain",     new Weather("rain").clouds(1500, 0.68f, 0.95f, 0.38f, 1.0f).rain(1500));
	presets.put("storm",    new Weather("storm").clouds(1200, 0.75f, 1.00f, 0.28f, 0.9f).vel(0.003f, 0.005f).rain(4500).wind(140, 60, -380));
	presets.put("snow",     new Weather("snow").clouds(1500, 0.55f, 0.80f, 0.50f, 1.0f).snow(1500));
	presets.put("blizzard", new Weather("blizzard").clouds(1200, 0.72f, 1.00f, 0.40f, 1.0f).vel(0.003f, 0.005f).snow(5000).wind(160, 70, -300));
    }

    static float num(Object o) {return(((Number)o).floatValue());}

    /* The set the server last sent (Glob.wthargs), read in the terms Clouds, Rain and Snow read it in. */
    static Weather fromserver(Glob glob) {
	Weather w = new Weather("server");
	for(Map.Entry<Indir<Resource>, Object[]> e : glob.wthargs.entrySet()) {
	    String nm;
	    try {
		nm = e.getKey().get().name;
	    } catch(Loading l) {
		continue;
	    }
	    Object[] a = e.getValue();
	    try {
		if(nm.equals(CLOUDS)) {
		    w.clouds(((Number)a[0]).intValue(), num(a[1]) / 100f, num(a[2]) / 100f, num(a[3]) / 100f, num(a[4]) / 100f);
		    if(a.length > 5)
			w.vel(num(a[5]), num(a[6]));
		} else if(nm.equals(RAIN)) {
		    w.rain(num(a[0]));
		    if(a.length > 3)
			w.wind(num(a[1]), num(a[2]), num(a[3]));
		} else if(nm.equals(SNOW)) {
		    w.snow(num(a[0]));
		}
	    } catch(RuntimeException exc) {
		/* A shape this does not know: that part of the weather is not drawn. */
	    }
	}
	return(w);
    }

    /* The hour of a previewed day, as the light a server might send for it: the colours and the angles
     * Glob.blob's "light" branch carries. Only ever a preview; the real light is the server's. */
    public static class Sun {
	public final Color amb, dif, spc;
	public final float elev, ang;

	Sun(Color amb, Color dif, Color spc, float elev, float ang) {
	    this.amb = amb; this.dif = dif; this.spc = spc; this.elev = elev; this.ang = ang;
	}
    }

    private static final float[][] daykeys = {
	/* hour,  ambient rgb,     diffuse rgb */
	{ 0.0f,   22,  26,  48,     38,  48,  92},
	{ 4.5f,   26,  28,  52,     48,  52,  96},
	{ 5.5f,   60,  52,  70,    150, 100,  90},
	{ 6.5f,   95,  80,  75,    255, 160, 100},
	{ 8.0f,  112, 110, 112,    255, 228, 195},
	{12.0f,  122, 122, 126,    255, 250, 238},
	{16.5f,  118, 114, 110,    255, 236, 205},
	{18.5f,  100,  80,  72,    255, 150,  85},
	{19.5f,   60,  48,  64,    170,  90,  90},
	{20.5f,   30,  30,  56,     60,  56, 100},
	{24.0f,   22,  26,  48,     38,  48,  92},
    };

    public static Sun sunpreview() {
	Double t = ptime;
	if(!enabled() || (t == null))
	    return(null);
	float h = (float)(((t % 24) + 24) % 24);
	int i = 0;
	while((i < daykeys.length - 2) && (daykeys[i + 1][0] <= h))
	    i++;
	float[] a = daykeys[i], b = daykeys[i + 1];
	float f = (h - a[0]) / (b[0] - a[0]);
	int[] c = new int[6];
	for(int o = 0; o < 6; o++)
	    c[o] = Math.round(a[o + 1] + ((b[o + 1] - a[o + 1]) * f));
	Color amb = new Color(c[0], c[1], c[2]), dif = new Color(c[3], c[4], c[5]);
	float elev, ang;
	if((h >= 6.0f) && (h <= 19.5f)) {
	    float p = (h - 6.0f) / 13.5f;
	    /* Never below 12 degrees: the shadow map is a 750-unit box, and a grazing sun stretches every
	     * shadow out of it. The colour says sunset; the shadow stays one the map can hold. */
	    elev = (float)Math.toRadians(Math.max(12.0, Math.sin(p * Math.PI) * 58.0));
	    ang = (float)(Math.PI * 0.2 + p * Math.PI);
	} else {
	    /* The moon's light, from where the moon stands (moondir): so the night's shadows fall from it. */
	    float[] m = moondir(((((h - 12) / 24.0) % 1) + 1) % 1);
	    elev = (float)Math.max(Math.toRadians(12.0), Math.asin(Math.max(-1, Math.min(1, m[2]))));
	    ang = (float)Math.atan2(m[1], m[0]);
	}
	return(new Sun(amb, dif, dif, elev, ang));
    }

    /* How dark a previewed hour is: night from half past eight to half past four, a twilight hour at
     * either end. */
    static float previewdark(double t) {
	float h = (float)(((t % 24) + 24) % 24);
	if((h >= 20.5f) || (h <= 4.5f))
	    return(1);
	if(h > 19.5f)
	    return(h - 19.5f);
	if(h < 5.5f)
	    return(5.5f - h);
	return(0);
    }

    /* One frame's worth of what the shaders read, replaced whole every tick. */
    static final class Frame {
	final float[] zen, hor, sun, sdir, ccol;
	final float night, disc, fogmax, fogdens;
	/* How bright the stars are: the night's darkness, or none with the stars switched off. */
	final float stars;
	final Coord3f focus;
	/* The clouds, as SkyPass and AmbShadow read them (see SkyPass.cl, .ci, .pf), and the ground
	 * shadow's run toward the sun per unit of rise. Set before the frame is published. */
	int ncl = 0, nnear = 0;
	float[] cl = new float[SkyPass.MAXCL * 4], ci = new float[SkyPass.MAXCL * 4], cx = new float[SkyPass.MAXCL * 4];
	float[] pf = new float[SkyPass.MAXCL * SkyPass.PUFFS * 4];
	float[] ssh = new float[2];
	/* The height of the ground under the player, which the shadows fall on. */
	float ground = 0;
	/* The moon: where it is, how lit (its phase), its colour, and how much of it shows. */
	float[] mdir = {0, 0, -1}, mcol = {0.9f, 0.9f, 0.85f};
	float mphase = 0.5f, mvis = 0;

	Frame(float[] zen, float[] hor, float[] sun, float[] sdir, float[] ccol,
	      float night, float disc, float fogmax, float fogdens, Coord3f focus) {
	    this.zen = zen; this.hor = hor; this.sun = sun; this.sdir = sdir; this.ccol = ccol;
	    this.night = night; this.disc = disc;
	    this.stars = starsmoon ? night : 0;
	    this.fogmax = fogmax; this.fogdens = fogdens; this.focus = focus;
	}
    }

    static volatile Frame frame = new Frame(new float[] {0.2f, 0.4f, 0.8f}, new float[] {0.6f, 0.7f, 0.9f}, new float[3],
					    new float[] {0, 0, 1}, new float[3],
					    0, 0, 0, 0, Coord3f.o);

    static Frame frame() {return(frame);}

    /* Of one frame's clouds, those a camera sees, as the shaders read them. A cloud whose bounding sphere
     * lies wholly outside the view's sides is met by no pixel's ray, and a shadow that falls outside the
     * view is on no ground that is drawn: neither is handed to the shaders, which test every cloud they
     * are handed at every pixel. Worked out once a frame and camera, from the very matrices the frame is
     * drawn with. */
    static final class Seen {
	final int ncl, nsh;
	/* The lowest base of the clouds seen: a ray that never rises to it meets none of them. */
	final float cbot;
	final float[] cl, ci, cx, pf;
	/* Each shadow: where the cloud's centre stands, less its run toward the sun from height zero, the
	 * shadow's radius, and how much of the sun it takes at its middle. */
	final float[] sh;

	Seen(Frame f, Matrix4f vp) {
	    float[][] sides = planes(vp, false), all = planes(vp, true);
	    int[] vis = new int[f.ncl];
	    int n = 0, ns = 0;
	    float bot = Float.MAX_VALUE;
	    float[] sh = new float[Math.max(f.nnear, 1) * 4];
	    float sshl = (float)Math.hypot(f.ssh[0], f.ssh[1]);
	    for(int k = 0; k < f.ncl; k++) {
		float x = f.cl[k * 4], y = f.cl[(k * 4) + 1], z = f.cl[(k * 4) + 2], r = f.cl[(k * 4) + 3];
		if(inside(sides, x, y, z, r)) {
		    vis[n++] = k;
		    bot = Math.min(bot, f.ci[(k * 4) + 1]);
		}
		if(k < f.nnear) {
		    /* Where the shadow's middle lands on ground at the player's height; the ground round it
		     * stands higher or lower, and moves the shadow along the sun's run by as much. */
		    float foot = f.cx[(k * 4) + 2], rise = z - f.ground;
		    float gx = x - (rise * f.ssh[0]), gy = y - (rise * f.ssh[1]);
		    if(inside(all, gx, gy, f.ground, foot + 150 + (150 * sshl))) {
			sh[(ns * 4) + 0] = x - (z * f.ssh[0]);
			sh[(ns * 4) + 1] = y - (z * f.ssh[1]);
			sh[(ns * 4) + 2] = foot;
			sh[(ns * 4) + 3] = f.ci[k * 4] * (0.5f + (f.ci[(k * 4) + 2] * 0.35f));
			ns++;
		    }
		}
	    }
	    /* Only as much of each array as is used is uploaded (SkyPass registers the upload). */
	    cl = new float[Math.max(n, 1) * 4];
	    ci = new float[Math.max(n, 1) * 4];
	    cx = new float[Math.max(n, 1) * 4];
	    pf = new float[Math.max(n, 1) * SkyPass.PUFFS * 4];
	    for(int i = 0; i < n; i++) {
		int k = vis[i];
		System.arraycopy(f.cl, k * 4, cl, i * 4, 4);
		System.arraycopy(f.ci, k * 4, ci, i * 4, 4);
		System.arraycopy(f.cx, k * 4, cx, i * 4, 4);
		System.arraycopy(f.pf, k * SkyPass.PUFFS * 4, pf, i * SkyPass.PUFFS * 4, SkyPass.PUFFS * 4);
	    }
	    this.ncl = n;
	    this.nsh = ns;
	    this.cbot = (n > 0) ? bot : 1e9f;
	    this.sh = sh;
	}

	/* The view's planes, out of its combined projection and camera, each facing in and of unit normal:
	 * its four sides -- which meet at the eye, so they also shut out all that is behind it -- and, with
	 * far, the far plane. The sky's rays go on past the far plane, so the clouds are held to the sides. */
	static float[][] planes(Matrix4f vp, boolean far) {
	    float[] m = vp.m;
	    float[][] ret = new float[far ? 5 : 4][];
	    int[][] rows = {{0, 1}, {0, -1}, {1, 1}, {1, -1}, {2, -1}};
	    for(int i = 0; i < ret.length; i++) {
		int r = rows[i][0], s = rows[i][1];
		float a = m[3] + (s * m[r]), b = m[7] + (s * m[4 + r]), c = m[11] + (s * m[8 + r]), d = m[15] + (s * m[12 + r]);
		float l = (float)Math.sqrt((a * a) + (b * b) + (c * c));
		ret[i] = new float[] {a / l, b / l, c / l, d / l};
	    }
	    return(ret);
	}

	static boolean inside(float[][] pl, float x, float y, float z, float r) {
	    for(float[] p : pl) {
		if((p[0] * x) + (p[1] * y) + (p[2] * z) + p[3] < -r)
		    return(false);
	    }
	    return(true);
	}
    }

    private static Frame seenf = null;
    private static Matrix4f seenp = null, seenc = null;
    private static Seen seen = null;
    static synchronized Seen seen(Matrix4f prj, Matrix4f cam) {
	Frame f = frame;
	if((seen == null) || (seenf != f) || !prj.equals(seenp) || !cam.equals(seenc)) {
	    seen = new Seen(f, prj.mul(cam));
	    seenf = f;
	    seenp = new Matrix4f(prj.m.clone());
	    seenc = new Matrix4f(cam.m.clone());
	}
	return(seen);
    }

    static float clamp(float x, float a, float b) {return(Math.max(a, Math.min(b, x)));}
    static float smooth(float a, float b, float x) {
	float t = clamp((x - a) / (b - a), 0, 1);
	return(t * t * (3 - 2 * t));
    }
    static float lum(float[] c) {return((c[0] * 0.2126f) + (c[1] * 0.7152f) + (c[2] * 0.0722f));}
    static float[] mix(float[] a, float[] b, float f) {
	return(new float[] {a[0] + ((b[0] - a[0]) * f), a[1] + ((b[1] - a[1]) * f), a[2] + ((b[2] - a[2]) * f)});
    }
    static float[] mul(float[] a, float f) {return(new float[] {a[0] * f, a[1] * f, a[2] * f});}
    static float[] rgb(float[] c) {return(new float[] {c[0], c[1], c[2]});}

    static Frame compute(DirLight l, Coord3f focus, Weather w, View v, Astronomy ast) {
	float[] amb = rgb(l.amb), dif = rgb(l.dif);
	float ld = lum(dif), la = lum(amb);
	float day = smooth(0.08f, 0.45f, ld);
	float mx = Math.max(0.001f, Math.max(dif[0], Math.max(dif[1], dif[2])));
	float warm = clamp((((dif[0] - dif[2]) / mx) * 1.4f) - 0.2f, 0, 1);
	float bright = Math.min(1.15f, 0.35f + (ld * 0.8f));

	float[] dayzen = {0.22f * bright, 0.42f * bright, 0.80f * bright};
	float[] dayhor = {0.64f * bright, 0.75f * bright, 0.90f * bright};
	float[] warmhor = {(dif[0] / mx) * 0.95f * bright, (((dif[1] / mx) * 0.75f) + 0.05f) * bright, (((dif[2] / mx) * 0.6f) + 0.08f) * bright};
	dayhor = mix(dayhor, warmhor, warm * 0.85f);
	dayzen = mix(dayzen, new float[] {0.30f * bright, 0.30f * bright, 0.55f * bright}, warm * 0.5f);
	float[] nightzen = {0.012f + (amb[0] * 0.12f), 0.018f + (amb[1] * 0.12f), 0.045f + (amb[2] * 0.18f)};
	float[] nighthor = {0.035f + (amb[0] * 0.30f), 0.050f + (amb[1] * 0.30f), 0.090f + (amb[2] * 0.35f)};
	float[] zen = mix(nightzen, dayzen, day), hor = mix(nighthor, dayhor, day);

	/* A grey sky only under a heavy cover or rain: a few clouds on a fine day leave it blue. */
	float cover = v.cover(), wet = v.wet(w), flake = v.flake(w);
	float ovc = smooth(0.45f, 0.9f, cover) * (0.55f + (0.45f * (1 - v.rmin)));
	if((w.rain > 0) || (w.snow > 0))
	    ovc = Math.max(ovc, 0.45f + (0.4f * Math.max(wet, flake)));
	/* Rain darkens the whole sky; snow only greys it. */
	float gloom = Math.max(wet, flake * 0.4f);
	float g = lum(hor) * 0.9f;
	float[] grey = {g, g, g * 1.04f};
	hor = mul(mix(hor, grey, ovc * 0.85f), 1 - (0.30f * gloom));
	zen = mul(mix(zen, mul(grey, 0.85f), ovc * 0.85f), 1 - (0.35f * gloom));
	float dark = 1 - smooth(0.05f, 0.30f, ld + (la * 0.5f));
	if(ptime != null)
	    dark = Math.max(dark, previewdark(ptime));
	else if((ast != null) && ast.night)
	    dark = Math.max(dark, 0.7f);
	float night = dark * (1 - ovc);
	/* At night the light's own direction is the moon's light, not a sun: no disc and little glow there,
	 * the moon is drawn where it stands. */
	float[] sun = mul(dif, 1.1f * (1 - (ovc * 0.9f)) * (1 - (0.85f * smooth(0.3f, 0.7f, dark))));
	float disc = 2.5f * (1 - ovc) * (1 - ovc) * (1 - smooth(0.3f, 0.6f, dark)) * (1 - v.nightfade);
	/* A previewed hour outside the day's arc is lit by the moon (sunpreview): that light has no disc. */
	if(ptime != null) {
	    double ph = ((ptime % 24) + 24) % 24;
	    if((ph < 6.0) || (ph > 19.5))
		disc = 0;
	}
	float[] sdir = {l.dir[0], l.dir[1], l.dir[2]};

	/* The clouds' own colour: the sky's light on them, greyed by the weather. */
	float[] ccol = {Math.min(1, (amb[0] * 0.9f) + (dif[0] * 0.75f)), Math.min(1, (amb[1] * 0.9f) + (dif[1] * 0.75f)), Math.min(1, (amb[2] * 0.9f) + (dif[2] * 0.75f))};
	ccol = mul(mix(ccol, mul(grey, 1.1f), ovc * 0.5f), 1 - (0.3f * gloom));

	float fogdens = (0.00008f + (wet * 0.0005f) + (flake * 0.0008f)) * fogmul;
	float fogmax = Math.min(1, fogmul);

	Frame ret = new Frame(zen, hor, sun, sdir, ccol,
			      night, disc, fogmax, fogdens, focus);
	v.write(ret);
	float dz = Math.max(sdir[2], 0.05f);
	ret.ssh = new float[] {sdir[0] / dz, sdir[1] / dz};
	moon(ret, ast, ovc, v);
	return(ret);
    }

    /* The moon as the game's own calendar has it (Cal): always across the sky from the sun, round the day
     * with it -- highest at midnight -- lit by its phase and tinted by the colour the server gives it. Its
     * path is a fixed arc, rising in the east and 55 degrees up at its highest; the server says nothing
     * of where it stands, only when it is night, and where midnight falls in its day is learnt from that
     * (View.astro). It shows at night alone, and the sun's disc by day alone: never the two at once. A
     * previewed hour moves it too. */
    /* Where the moon stands at a fraction of the day: across from the sun, highest (55 degrees) at 0.5. */
    static float[] moondir(double dt) {
	double h = (Math.PI * 2 * dt) + Math.PI, top = Math.toRadians(55);
	float c = (float)Math.cos(h), sn = (float)Math.sin(h);
	return(new float[] {-sn, (float)Math.cos(top) * c, (float)Math.sin(top) * c});
    }

    static void moon(Frame f, Astronomy ast, float ovc, View v) {
	double dt;
	if(ptime != null)
	    dt = ((((ptime - 12) / 24) % 1) + 1) % 1;
	else if(ast != null)
	    dt = ast.dt + (0.5 - v.midnight);
	else
	    return;
	double mp = (pmoon != null) ? pmoon : ((ast != null) ? ast.mp : 0.5);
	f.mdir = moondir(dt);
	f.mphase = (float)(((mp % 1) + 1) % 1);
	Color mc = (ast != null) ? ast.mc : null;
	if(mc != null)
	    f.mcol = new float[] {mc.getRed() / 255f, mc.getGreen() / 255f, mc.getBlue() / 255f};
	/* Only at night, as the server has it, and only up; a heavy cover hides it, as the clouds do. */
	f.mvis = starsmoon ? (smooth(-0.02f, 0.06f, f.mdir[2]) * v.nightfade * (1 - (0.7f * ovc))) : 0;
    }

    /* The camera, out of the matrices the shaders are given. */
    static float[] eyeof(Matrix4f cam) {
	Matrix4f inv = cam.invert();
	return(new float[] {inv.m[12] / inv.m[15], inv.m[13] / inv.m[15], inv.m[14] / inv.m[15]});
    }

    static float farof(Matrix4f prj) {
	float a = prj.m[10], b = prj.m[14];
	if(prj.m[11] != 0) {
	    if(Math.abs(a + 1) < 1e-6f)
		return(2000);
	    return(b / (a + 1));
	}
	float fn = -2 / a;
	return(((-b * fn) + fn) / 2);
    }

    /* The fog's reach, per frame: it begins a share of the way from the player out to the far plane, so
     * the ground the camera is looking at is never fogged, and it is whole just short of the far plane,
     * so the edge where the drawn ground ends is never seen. */
    static float[] fogparams(Matrix4f prj, Matrix4f cam) {
	Frame f = frame;
	float far = farof(prj);
	float[] e = eyeof(cam);
	float dx = e[0] - f.focus.x, dy = e[1] - f.focus.y, dz = e[2] - f.focus.z;
	float fd = (float)Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
	if(!fogon)
	    return(new float[] {0, 1, 0, 0});
	float end = far * 0.96f;
	float start = Math.min(fd + ((end - fd) * 0.40f), end * 0.9f);
	/* And none of it from high up: a camera pulled far back is looking down at the map, not out over it. */
	float high = 1 - smooth(350, 1000, dz);
	return(new float[] {start, end, f.fogmax * high, f.fogdens * high});
    }

    static float fognear(Matrix4f cam) {
	Frame f = frame;
	float[] e = eyeof(cam);
	float dx = e[0] - f.focus.x, dy = e[1] - f.focus.y, dz = e[2] - f.focus.z;
	return((float)Math.sqrt((dx * dx) + (dy * dy) + (dz * dz)) * 0.85f);
    }

    /* The game's own cloud texture: the TexR layer of gfx/fx/clouds. Here it only ruffles the clouds' edges. */
    private static Indir<Resource> cloudres = null;
    private static TexRender cloudtex = null;
    static TexRender cloudtex() {
	if(cloudtex == null) {
	    try {
		if(cloudres == null)
		    cloudres = Resource.remote().load(CLOUDS);
		cloudtex = cloudres.get().layer(TexR.class).tex();
	    } catch(Loading l) {
		return(null);
	    }
	}
	return(cloudtex);
    }

    /* The kinds of cloud. SMALL: flat fair-weather puffs. CUMULUS: the ordinary heaped one. TOWER: a
     * narrow base heaped up twice as high, darker. BANK: a long, low, flat bank, the grey of a wet day.
     * HIGH: a patch of small thin puffs far above the rest. */
    static final int SMALL = 0, CUMULUS = 1, TOWER = 2, BANK = 3, HIGH = 4;
    static final String[] KINDS = {"small", "cumulus", "tower", "bank", "high"};

    /* Which kind the weather forms next: fine weather small ones and the odd high patch, a cloudier sky
     * more heaped ones and towers, rain towers and long dark banks, snow banks. Made up, and meant to be. */
    static int kind(Random rnd, float cover, float wet, float flake) {
	float fine = (1 - Math.min(1, cover * 2.5f)) * (1 - Math.max(wet, flake));
	float[] w = new float[5];
	w[SMALL] = (0.45f * fine) + (0.10f * (1 - fine) * (1 - wet));
	w[CUMULUS] = (0.30f * fine) + (0.35f * (1 - fine));
	w[TOWER] = (0.05f * fine) + (0.20f * (1 - fine)) + (0.25f * wet);
	w[BANK] = (0.15f * cover) + (0.50f * wet) + (0.60f * flake);
	w[HIGH] = (0.20f * fine) + (0.12f * (1 - fine) * (1 - wet));
	float sum = 0;
	for(float x : w) sum += x;
	float r = rnd.nextFloat() * sum;
	for(int i = 0; i < w.length; i++) {
	    if((r -= w[i]) < 0)
		return(i);
	}
	return(CUMULUS);
    }

    /* One cloud: its kind, where it is, how high above the clouds' base height, how big, how formed (its
     * balls, as offsets from its foot), what its edge is like, how far into its life, and how visible. */
    static class Cloud {
	final int kind;
	double x, y;
	float zoff, size, dark, darkoff, op, opmax, seed;
	/* How far in from the rim its balls turn solid, how ragged the noise makes their edge, and the
	 * noise's scale: a thin high cloud is soft and torn, a tower's edge crisp. */
	float soft, rag, nscale;
	final float[] puffs = new float[SkyPass.PUFFS * 4];
	/* Each ball's height over its width: under 1 an egg lying down, 1 a ball. */
	final float[] squash = new float[SkyPass.PUFFS];
	/* Its bounding sphere's centre, as an offset from its foot, and radius; and its footprint's radius. */
	float bx, by, bz, brad, foot;
	double age, life;
	boolean dying, fast;

	Cloud(Random rnd, int kind, float size) {
	    this.kind = kind;
	    this.size = size;
	    this.seed = rnd.nextFloat() * 10;
	    this.life = 360 + (rnd.nextDouble() * 900);
	    this.darkoff = (rnd.nextFloat() - 0.5f) * 0.12f;
	    float stretch;
	    switch(kind) {
	    case SMALL:
		zoff = -300 + (rnd.nextFloat() * 600); opmax = 0.9f; soft = 0.9f; rag = 1.0f; nscale = 1.2f;
		stretch = 1.6f + (rnd.nextFloat() * 0.6f);
		break;
	    case TOWER:
		zoff = -300 + (rnd.nextFloat() * 400); opmax = 0.97f; soft = 0.7f; rag = 0.8f; nscale = 0.9f;
		darkoff += 0.06f;
		stretch = 1.1f + (rnd.nextFloat() * 0.3f);
		break;
	    case BANK:
		zoff = -350 + (rnd.nextFloat() * 550); opmax = 0.95f; soft = 1.0f; rag = 1.1f; nscale = 0.8f;
		darkoff += 0.12f;
		stretch = 2.4f + (rnd.nextFloat() * 1.2f);
		break;
	    case HIGH:
		zoff = 1000 + (rnd.nextFloat() * 1200); opmax = 0.45f + (rnd.nextFloat() * 0.2f); soft = 1.4f; rag = 1.4f; nscale = 1.7f;
		darkoff -= 0.1f;
		stretch = 1.4f + (rnd.nextFloat() * 0.8f);
		break;
	    default:
		zoff = -200 + (rnd.nextFloat() * 900); opmax = 0.95f; soft = 0.85f; rag = 1.0f; nscale = 1.0f;
		stretch = 1.3f + (rnd.nextFloat() * 0.5f);
		break;
	    }
	    float rot = rnd.nextFloat() * (float)(Math.PI * 2), cr = (float)Math.cos(rot), sr = (float)Math.sin(rot);
	    /* Its own character, over its kind's: how squat or tall, how far it leans, how flat its lower balls
	     * lie; and its balls' sizes, which differ in every cloud (ballsizes). */
	    float tallk = 0.75f + (0.6f * rnd.nextFloat());
	    float lean = rnd.nextFloat() * 0.3f, la = rnd.nextFloat() * (float)(Math.PI * 2);
	    float lx = (float)Math.cos(la) * lean, ly = (float)Math.sin(la) * lean;
	    float flat = 0.55f + (0.4f * rnd.nextFloat());
	    float[] sizes = ballsizes(rnd);
	    float z0 = Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
	    for(int j = 0; j < SkyPass.PUFFS; j++) {
		float[] q = puff(rnd, kind, j, size, stretch);
		float z = q[2] * tallk;
		puffs[(j * 4) + 0] = (q[0] * cr) - (q[1] * sr) + (lx * z);
		puffs[(j * 4) + 1] = (q[0] * sr) + (q[1] * cr) + (ly * z);
		puffs[(j * 4) + 2] = z;
		puffs[(j * 4) + 3] = q[3] * sizes[j];
		z0 = Math.min(z0, z);
		z1 = Math.max(z1, z);
	    }
	    /* No ball grows past BIGGEST times the cloud's middling one: a head may stand out, never swallow
	     * its own cloud (a heap's centre ball, already its largest, dealt a big size did). */
	    float[] rs = new float[SkyPass.PUFFS];
	    for(int j = 0; j < SkyPass.PUFFS; j++)
		rs[j] = puffs[(j * 4) + 3];
	    Arrays.sort(rs);
	    float cap = BIGGEST * rs[SkyPass.PUFFS / 2];
	    for(int j = 0; j < SkyPass.PUFFS; j++)
		puffs[(j * 4) + 3] = Math.min(puffs[(j * 4) + 3], cap);
	    /* The lowest balls the flattest, the top ones round. */
	    int lo = 0;
	    float bot = Float.MAX_VALUE;
	    for(int j = 0; j < SkyPass.PUFFS; j++) {
		float h = (z1 > z0) ? ((puffs[(j * 4) + 2] - z0) / (z1 - z0)) : 1;
		squash[j] = flat + ((1 - flat) * smooth(0, 0.6f, h));
		float b = puffs[(j * 4) + 2] - (puffs[(j * 4) + 3] * squash[j]);
		if(b < bot) {bot = b; lo = j;}
	    }
	    /* And the whole set down through its base, the lowest egg's bottom four tenths of it under the
	     * base: the cut there (SkyPass.clouds) flattens the belly. */
	    float sink = bot + (0.4f * puffs[(lo * 4) + 3] * squash[lo]);
	    for(int j = 0; j < SkyPass.PUFFS; j++)
		puffs[(j * 4) + 2] -= sink;
	    bounds();
	}

	/* The largest a cloud's ball may be, against the middling one of the same cloud. */
	static final float BIGGEST = 1.3f;

	/* The sizes of a cloud's balls against its kind's usual, dealt out in a shuffled order: one to three
	 * large, three to five small, the rest middling -- so that every cloud has big heads and small ones,
	 * and no two neighbours need match. The spread is modest: the kinds' own radii differ already. */
	private static float[] ballsizes(Random rnd) {
	    float[] ret = new float[SkyPass.PUFFS];
	    int big = 1 + rnd.nextInt(3), small = 3 + rnd.nextInt(3);
	    for(int i = 0; i < ret.length; i++) {
		if(i < big)
		    ret[i] = 1.15f + (0.2f * rnd.nextFloat());
		else if(i < (big + small))
		    ret[i] = 0.55f + (0.2f * rnd.nextFloat());
		else
		    ret[i] = 0.85f + (0.2f * rnd.nextFloat());
	    }
	    for(int i = ret.length - 1; i > 0; i--) {
		int o = rnd.nextInt(i + 1);
		float t = ret[i]; ret[i] = ret[o]; ret[o] = t;
	    }
	    return(ret);
	}

	/* Ball j of a cloud of this kind, before its turn: x (the stretched axis), y, height of its centre
	 * over the base, radius. The constructor sets the whole down through the base afterwards. */
	private static float[] puff(Random rnd, int kind, int j, float s, float e) {
	    float a = rnd.nextFloat() * (float)(Math.PI * 2);
	    switch(kind) {
	    case SMALL:
		if(j < 8) {
		    a = (j * (float)(Math.PI * 2) / 8) + ((rnd.nextFloat() - 0.5f) * 0.6f);
		    float rad = s * (0.3f + (0.45f * rnd.nextFloat())), r = s * (0.24f + (0.12f * rnd.nextFloat()));
		    return(new float[] {(float)Math.cos(a) * rad * e, (float)Math.sin(a) * rad, r * 0.5f, r});
		} else if(j < 11) {
		    float rad = s * 0.3f * rnd.nextFloat(), r = s * (0.3f + (0.08f * rnd.nextFloat()));
		    return(new float[] {(float)Math.cos(a) * rad * e, (float)Math.sin(a) * rad, s * (0.2f + (0.12f * rnd.nextFloat())), r});
		}
		return(new float[] {(rnd.nextFloat() - 0.5f) * s * 0.3f, (rnd.nextFloat() - 0.5f) * s * 0.2f, s * (0.38f + (0.1f * rnd.nextFloat())), s * 0.26f});
	    case TOWER:
		if(j < 5) {
		    a = (j * (float)(Math.PI * 2) / 5) + ((rnd.nextFloat() - 0.5f) * 0.7f);
		    float rad = s * (0.3f + (0.2f * rnd.nextFloat())), r = s * (0.34f + (0.1f * rnd.nextFloat()));
		    return(new float[] {(float)Math.cos(a) * rad * e, (float)Math.sin(a) * rad, r * 0.55f, r});
		} else {
		    float lv = (j - 5) / 6f;
		    float rad = s * 0.4f * (1 - (lv * 0.35f)) * (0.4f + (0.6f * rnd.nextFloat())), r = s * ((0.46f - (lv * 0.1f)) + (0.06f * rnd.nextFloat()));
		    return(new float[] {(float)Math.cos(a) * rad * e, (float)Math.sin(a) * rad, s * (0.42f + (lv * 1.15f)), r});
		}
	    case BANK:
		if(j < 9) {
		    float along = ((j / 8f) - 0.5f) * 2;
		    float r = s * (0.3f + (0.12f * rnd.nextFloat()));
		    return(new float[] {(along * s * e * 0.75f) + ((rnd.nextFloat() - 0.5f) * s * 0.2f), (rnd.nextFloat() - 0.5f) * s * 0.6f, r * 0.45f, r});
		} else {
		    float along = (rnd.nextFloat() * 2) - 1, r = s * (0.28f + (0.08f * rnd.nextFloat()));
		    return(new float[] {along * s * e * 0.6f, (rnd.nextFloat() - 0.5f) * s * 0.3f, s * (0.25f + (0.1f * rnd.nextFloat())), r});
		}
	    case HIGH: {
		float rad = (float)Math.sqrt(rnd.nextFloat()) * s * 0.8f, r = s * (0.24f + (0.12f * rnd.nextFloat()));
		return(new float[] {(float)Math.cos(a) * rad * e, (float)Math.sin(a) * rad, (r * 0.4f) + (rnd.nextFloat() * s * 0.08f), r});
	    }
	    default:
		if(j < 6) {
		    a = (j * (float)(Math.PI * 2) / 6) + ((rnd.nextFloat() - 0.5f) * 0.7f);
		    float rad = s * (0.4f + (0.3f * rnd.nextFloat())), r = s * (0.32f + (0.14f * rnd.nextFloat()));
		    return(new float[] {(float)Math.cos(a) * rad * e, (float)Math.sin(a) * rad, r * 0.55f, r});
		} else if(j < 7) {
		    return(new float[] {0, 0, s * 0.3f, s * 0.5f});
		} else if(j < 10) {
		    float rad = s * 0.35f * rnd.nextFloat(), r = s * (0.32f + (0.1f * rnd.nextFloat()));
		    return(new float[] {(float)Math.cos(a) * rad * e * 0.9f, (float)Math.sin(a) * rad, s * (0.5f + (0.2f * rnd.nextFloat())), r});
		}
		float rad = s * 0.2f * rnd.nextFloat();
		return(new float[] {(float)Math.cos(a) * rad, (float)Math.sin(a) * rad, s * (0.85f + (0.2f * rnd.nextFloat())), s * (0.26f + (0.08f * rnd.nextFloat()))});
	    }
	}

	/* Its bounding sphere -- centred at half its height over the base, which the shader reads its height
	 * off -- and the radius of its footprint, which its shadow is the size of. */
	private void bounds() {
	    float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE, top = 0;
	    for(int j = 0; j < SkyPass.PUFFS; j++) {
		float px = puffs[(j * 4) + 0], py = puffs[(j * 4) + 1], pz = puffs[(j * 4) + 2], r = puffs[(j * 4) + 3];
		x0 = Math.min(x0, px - r); x1 = Math.max(x1, px + r);
		y0 = Math.min(y0, py - r); y1 = Math.max(y1, py + r);
		top = Math.max(top, pz + (r * squash[j]));
	    }
	    bx = (x0 + x1) / 2; by = (y0 + y1) / 2; bz = top / 2;
	    brad = 0; foot = 0;
	    for(int j = 0; j < SkyPass.PUFFS; j++) {
		float px = puffs[(j * 4) + 0] - bx, py = puffs[(j * 4) + 1] - by, pz = puffs[(j * 4) + 2] - bz, r = puffs[(j * 4) + 3];
		brad = Math.max(brad, (float)Math.sqrt((px * px) + (py * py) + (pz * pz)) + r);
		foot = Math.max(foot, (float)Math.hypot(px, py) + (r * 0.7f));
	    }
	}
    }

    /* What one map view holds while this is on. */
    static class View {
	private static final Object cskey = new Object();
	RenderTree.Slot sky;
	/* The server's cloud numbers, eased as the game's Clouds eases them. */
	float cmin = -0.1f, cmax = 0f, rmin = 1f, rmax = 1f, scale = 1f / 1500, cvx = 0.001f, cvy = 0.002f;
	final List<Cloud> clouds = new ArrayList<>();
	/* The far ring's own clouds: out on the horizon, larger, slower to come and go, and without shadow. */
	final List<Cloud> far = new ArrayList<>();
	double farcool = 0;
	final Random rnd = new Random();
	boolean seeded = false;
	double lastgt = -1, spawncool = 0;
	float gz = Float.NaN;
	/* 0 by day, 1 by night, eased over a quarter of a minute either way; and the server's day fraction
	 * at midnight, as its own night flag shows it: 0.5 until a night says otherwise. */
	float nightfade = 0;
	double midnight = 0.5;
	boolean fadeset = false;

	void astro(Astronomy ast, double dt) {
	    boolean n;
	    if(ptime != null)
		n = previewdark(ptime) > 0.5f;
	    else
		n = (ast != null) && ast.night;
	    if((ptime == null) && (ast != null) && ast.night)
		midnight = (Math.abs(ast.dt - 0.5) < 0.25) ? 0.5 : 0.0;
	    float t = n ? 1 : 0;
	    /* Switched on at night, it is night already: no fade in from nothing. */
	    if(!fadeset) {
		nightfade = t;
		fadeset = true;
	    }
	    nightfade += (t - nightfade) * (float)Math.min(1, dt / 15);
	}

	private static float approach(float x, float t, float a) {
	    float n = x + ((t - x) * a);
	    return((Math.abs(t - n) < 0.003f) ? t : n);
	}

	/* The game's Clouds eases a change over two seconds; this eases toward the weather the same way,
	 * and toward an empty sky when the clouds go. */
	void ease(Weather w, double dt) {
	    float a = (float)Math.min(1.0, dt * 1.2);
	    float tcmin = w.clouds ? w.cmin : -0.1f, tcmax = w.clouds ? w.cmax : 0f;
	    float trmin = w.clouds ? w.rmin : 1f, trmax = w.clouds ? w.rmax : 1f;
	    cmin = approach(cmin, tcmin, a);
	    cmax = approach(cmax, Math.max(tcmax, cmin + 0.01f), a);
	    if(cmax <= cmin)
		cmax = cmin + 0.01f;
	    rmin = approach(rmin, trmin, a);
	    rmax = approach(rmax, trmax, a);
	    if(w.clouds) {
		scale = w.scale;
		cvx = w.cvx;
		cvy = w.cvy;
	    }
	}

	/* How much of the sky the server's numbers would cloud, 0 to 1: the game's shadow, averaged over
	 * the spread of values its noise takes. */
	float cover() {
	    if(cmax <= 0.005f)
		return(0);
	    float c = 0;
	    for(float n = 0.2f; n < 0.81f; n += 0.1f)
		c += 1 - smooth(cmin, cmax, n);
	    return(c / 7);
	}
	float wet(Weather w) {return(Math.min(1, w.rain / 4000f));}
	float flake(Weather w) {return(Math.min(1, w.snow / 4000f));}
	/* How much the rain, or the snow, heaps the sky: any at all counts for a good deal, and a downpour
	 * for all of it. 0 when there is none. */
	float rainy(Weather w) {return((w.rain > 0) ? (0.35f + (0.65f * smooth(0, 0.5f, wet(w)))) : 0);}
	float snowy(Weather w) {return((w.snow > 0) ? (0.3f + (0.7f * smooth(0, 0.5f, flake(w)))) : 0);}
	/* How far round the player clouds form: closer in under rain, so more of them are overhead. */
	float spawnr = SPAWN;
	/* How closed a sky the weather wants, 0 to 1 (closed()), as populate last found it. */
	float close = 0;

	/* How closed a sky the weather wants: 0 under a fair or a merely cloudy one, most of the way under
	 * an overcast, all but a break or two in the rain, and wholly in a downpour or thick snow. Where it
	 * is, the clouds grow, crowd in and run into one another until no sky shows between them. */
	float closed(float cover, float wet, float flake) {
	    float c = smooth(0.5f, 0.95f, cover) * 0.85f;
	    c = Math.max(c, wet * (0.8f + (0.2f * smooth(0.6f, 1.0f, wet))));
	    c = Math.max(c, flake * 0.95f);
	    return(clamp(c, 0, 1));
	}

	/* On a day the server gives no cloud and no rain, a few stray ones or none: a number that changes
	 * every twenty minutes of real time. */
	int stray() {
	    long slot = (long)(System.currentTimeMillis() / 1200000L);
	    int h = new Random(slot * 0x9E3779B97F4A7C15L).nextInt(100);
	    return((h < 25) ? 0 : (h < 55) ? 1 : (h < 80) ? 2 : 3);
	}

	/* How many clouds the weather wants, how big and how dark: made up, and meant to be. */
	/* The clouds switched off: none near, none on the horizon -- but how closed the sky is still follows
	 * the weather, since the sky's own colour reads it. */
	void noclouds(Weather w) {
	    clouds.clear();
	    far.clear();
	    close = closed(cover(), rainy(w), snowy(w));
	}

	void populate(Weather w, Coord3f focus, double dt, double gt) {
	    float cover = cover(), wet = rainy(w), flake = snowy(w);
	    int target;
	    if(pclouds >= 0) {
		target = pclouds;
	    } else if(!w.clouds && (w.rain <= 0) && (w.snow <= 0)) {
		target = stray();
	    } else {
		/* Rain fills the sky whatever the cloud numbers say: fifteen or so in a drizzle, the most there
		 * can be in a downpour. Snow nearly as much. */
		target = Math.round(2 + (cover * 9));
		if(w.rain > 0)
		    target = Math.max(target, Math.round(10 + (wet * 14)));
		if(w.snow > 0)
		    target = Math.max(target, Math.round(8 + (flake * 10)));
	    }
	    close = closed(cover, wet, flake);
	    if(pclouds < 0) {
		/* A sky closing over takes every cloud there can be. */
		target = Math.max(target, Math.round(SkyPass.NEAR * smooth(0.3f, 0.9f, close)));
		target = Math.round(target * cloudamount);
	    }
	    target = Math.max(0, Math.min(SkyPass.NEAR, target));
	    /* Rain draws its clouds in overhead; a closed sky needs them out to the disc's edge as well. */
	    spawnr = SPAWN * (1 - (0.25f * Math.max(wet, flake) * (1 - close)));
	    float basesize = 420 * (0.85f + (cover * 0.6f) + (wet * 0.4f) + (flake * 0.2f));
	    /* And more than twice the size, so that they run into one another. */
	    float size = basesize * (1 + (1.2f * close));
	    float dark = clamp((cover * 0.4f) + (wet * 0.6f) + (flake * 0.15f), 0, 0.9f);

	    /* The wind: the game's clouds move their texture by (cvx, cvy) a game second, so the world under
	     * them moves the other way by that over their scale. A clear sky keeps a gentle breeze. */
	    float wx = -cvx / Math.max(scale, 1e-5f), wy = -cvy / Math.max(scale, 1e-5f);
	    float wl = (float)Math.hypot(wx, wy);
	    if(wl > 12) {wx *= 12 / wl; wy *= 12 / wl;}
	    double dgt = ((lastgt < 0) || (gt < lastgt) || (gt - lastgt > 120)) ? 0 : (gt - lastgt);
	    lastgt = gt;
	    if(Float.isNaN(gz))
		gz = focus.z;
	    else
		gz += (focus.z - gz) * (float)Math.min(1, dt / 10);

	    for(Cloud c : clouds) {
		c.x += wx * dgt;
		c.y += wy * dgt;
		c.age += dt;
		if(Math.hypot(c.x - focus.x, c.y - focus.y) > DESPAWN) {
		    c.dying = true;
		    c.fast = true;
		}
		if(c.age > c.life)
		    c.dying = true;
		float rate = (float)(dt / (c.fast ? 8 : 45));
		c.op = c.dying ? Math.max(0, c.op - rate) : Math.min(1, c.op + rate);
		c.dark += (clamp(dark + c.darkoff, 0, 0.95f) - c.dark) * (float)Math.min(1, dt / 20);
	    }
	    /* A sky closing over breaks up the fine weather's little clouds, which the big ones replace. */
	    if(close > 0.5f) {
		for(Cloud c : clouds) {
		    if(c.size < (size * 0.45f))
			c.dying = true;
		}
	    }
	    clouds.removeIf(c -> c.dying && (c.op <= 0));
	    /* The far ring grows less: its clouds are already big, and would otherwise tower over everything. */
	    populatefar(w, focus, dt, wx, wy, dgt, basesize * (1 + (1.0f * close)), clamp((cover * 0.4f) + (wet * 0.6f) + (flake * 0.15f), 0, 0.9f), cover, wet, flake);

	    int alive = 0;
	    for(Cloud c : clouds)
		if(!c.dying) alive++;
	    if(alive > target) {
		for(Cloud c : clouds) {
		    if(!c.dying) {c.dying = true; break;}
		}
	    }
	    spawncool -= dt;
	    if(!seeded) {
		/* Switched on: the sky is already as the weather has it, not forming from nothing. */
		for(int i = 0; i < target; i++) {
		    Cloud c = spawn(focus, size, dark, cover, wet, flake, false);
		    c.op = 1;
		    c.age = rnd.nextDouble() * c.life * 0.6;
		}
		seeded = true;
	    } else if((alive < target) && (spawncool <= 0) && (clouds.size() < SkyPass.NEAR)) {
		spawn(focus, size, dark, cover, wet, flake, true).op = 0;
		/* Rain gathers its clouds faster than a fine day drifts one in, and a closing sky faster still. */
		spawncool = (((w.rain > 0) || (w.snow > 0)) ? 2 : 4) * (1 - (0.5f * close));
	    }
	}

	/* THE FAR RING: a few big clouds out on the horizon whatever the weather, more as it clouds over
	 * and rains. They drift as the near ones do, live longer, come and go more slowly, and stand clear
	 * of one another; one that forms while this is running forms far upwind. */
	void populatefar(Weather w, Coord3f focus, double dt, float wx, float wy, double dgt, float size, float dark,
			 float cover, float wet, float flake) {
	    int target;
	    if(!w.clouds && (w.rain <= 0) && (w.snow <= 0))
		target = 4;
	    else
		target = Math.round(4 + (cover * 6) + (Math.max(wet, flake) * 4));
	    target = Math.max(target, Math.round(SkyPass.FAR * close));
	    target = Math.max(0, Math.min(SkyPass.FAR, Math.round(target * cloudamount)));
	    for(Cloud c : far) {
		c.x += wx * dgt;
		c.y += wy * dgt;
		c.age += dt;
		if(Math.hypot(c.x - focus.x, c.y - focus.y) > FARGONE) {
		    c.dying = true;
		    c.fast = true;
		}
		if(c.age > c.life)
		    c.dying = true;
		float rate = (float)(dt / (c.fast ? 10 : 90));
		c.op = c.dying ? Math.max(0, c.op - rate) : Math.min(1, c.op + rate);
		c.dark += (clamp(dark + c.darkoff, 0, 0.95f) - c.dark) * (float)Math.min(1, dt / 20);
	    }
	    if(close > 0.5f) {
		for(Cloud c : far) {
		    if(c.size < (size * 1.8f * 0.45f))
			c.dying = true;
		}
	    }
	    far.removeIf(c -> c.dying && (c.op <= 0));
	    int alive = 0;
	    for(Cloud c : far)
		if(!c.dying) alive++;
	    if(alive > target) {
		for(Cloud c : far) {
		    if(!c.dying) {c.dying = true; break;}
		}
	    }
	    farcool -= dt;
	    if(far.isEmpty() && (alive == 0) && (farcool <= 0) && (target > 0) && !farseeded) {
		for(int i = 0; i < target; i++) {
		    Cloud c = spawnfar(focus, size, dark, cover, wet, flake, false);
		    c.op = 1;
		    c.age = rnd.nextDouble() * c.life * 0.6;
		}
		farseeded = true;
	    } else if((alive < target) && (farcool <= 0) && (far.size() < SkyPass.FAR)) {
		spawnfar(focus, size, dark, cover, wet, flake, true).op = 0;
		farcool = 10 * (1 - (0.6f * close));
	    }
	}
	boolean farseeded = false;

	/* Indoors or underground (Ambience.VOID), the grid it was last worked out under, and when again. */
	boolean inside = false;
	long igrid = 0;
	double icool = 0;

	/* Whether the ground round the player is mostly void: the 3x3 grids round it, averaged, at least half
	 * of it. Worked out again when the player's grid changes (a door or a ladder re-bases the map) and
	 * once a second, as the grids round it arrive. A grid or a tileset not loaded yet leaves it as it was. */
	boolean inside(MCache map, Coord3f cc, double dt) {
	    Coord gc = new Coord2d(cc).floor(MCache.tilesz).div(MCache.cmaps);
	    /* Only grids the cache already holds: getgrid() on an absent one puts a map request on the wire. */
	    if(!map.tileheld(gc.mul(MCache.cmaps)))
		return(inside);
	    try {
		MCache.Grid mid = map.getgrid(gc);
		if((mid.id == igrid) && ((icool -= dt) > 0))
		    return(inside);
		float sum = 0;
		int n = 0;
		for(int y = -1; y <= 1; y++) {
		    for(int x = -1; x <= 1; x++) {
			Coord ngc = gc.add(x, y);
			if(!map.tileheld(ngc.mul(MCache.cmaps)))
			    continue;
			MCache.Grid g;
			try {
			    g = map.getgrid(ngc);
			} catch(Loading l) {
			    continue;
			}
			sum += voidshare(map, g);
			n++;
		    }
		}
		igrid = mid.id;
		icool = 1;
		return(inside = (sum / n) >= 0.5f);
	    } catch(Loading l) {
		return(inside);
	    }
	}

	/* The sky seeded afresh the next time it is drawn, as when switched on. */
	void reset() {
	    clouds.clear();
	    far.clear();
	    seeded = farseeded = false;
	    spawncool = farcool = 0;
	    lastgt = -1;
	    gz = Float.NaN;
	}

	Cloud spawnfar(Coord3f focus, float size, float dark, float cover, float wet, float flake, boolean upwind) {
	    int kind = kind(rnd, cover, wet, flake);
	    /* A small one would be a speck out there: the far ring heaps them. */
	    if(kind == SMALL)
		kind = CUMULUS;
	    float sz = size * (1.8f + (1.4f * rnd.nextFloat()));
	    Cloud c = new Cloud(rnd, kind, sz);
	    c.dark = clamp(dark + c.darkoff, 0, 0.95f);
	    c.life *= 2.5;
	    c.soft *= 1 - (0.3f * close);
	    /* Low, as the clouds on a real horizon are: half the near ones' base height, and a little over;
	     * a high veil out there no higher than the near clouds' tops. */
	    c.zoff = (kind == HIGH) ? (200 + (rnd.nextFloat() * 400)) : ((-cloudbase * 0.5f) + (rnd.nextFloat() * 200));
	    double bx = 0, by = 0;
	    for(int tries = 0; tries < 16; tries++) {
		double a, d;
		if(upwind) {
		    a = Math.atan2(cvy, cvx) + ((rnd.nextDouble() - 0.5) * Math.PI);
		    d = FAROUT - (rnd.nextDouble() * 4000);
		} else {
		    /* A closed sky brings the ring in, to meet the near clouds at the disc's edge. */
		    double in = FARIN - (2500 * close);
		    a = rnd.nextDouble() * Math.PI * 2;
		    d = in + (rnd.nextDouble() * (FAROUT - in));
		}
		bx = focus.x + (Math.cos(a) * d);
		by = focus.y + (Math.sin(a) * d);
		boolean clear = true;
		for(Cloud o : far) {
		    if(Math.hypot(o.x - bx, o.y - by) < ((1.4 - (0.9 * close)) * (o.foot + c.foot))) {
			clear = false;
			break;
		    }
		}
		if(clear)
		    break;
	    }
	    c.x = bx;
	    c.y = by;
	    far.add(c);
	    return(c);
	}

	/* A new cloud, of the kind the weather forms, somewhere in the disc round the player and clear of
	 * the others near its own height -- one far above or below may pass over it, which is what layers
	 * the sky; one that forms while this is running forms upwind, so that it drifts over rather than
	 * away. */
	Cloud spawn(Coord3f focus, float size, float dark, float cover, float wet, float flake, boolean upwind) {
	    int kind = kind(rnd, cover, wet, flake);
	    float[][] range = {{0.55f, 0.85f}, {0.8f, 1.2f}, {0.85f, 1.2f}, {0.9f, 1.4f}, {0.7f, 1.0f}};
	    float sz = size * (range[kind][0] + ((range[kind][1] - range[kind][0]) * rnd.nextFloat()));
	    Cloud c = new Cloud(rnd, kind, sz);
	    c.dark = clamp(dark + c.darkoff, 0, 0.95f);
	    /* A closing sky's clouds are denser, crisp to their rims. */
	    c.soft *= 1 - (0.3f * close);
	    /* How close to one another they may stand: well apart on a fine day, run together in a storm. */
	    double gap = 1.6 - (1.1 * close), hole = 0.3 * (1 - close);
	    double bx = 0, by = 0;
	    for(int tries = 0; tries < 12; tries++) {
		double a, d;
		if(upwind) {
		    double wa = Math.atan2(cvy, cvx);
		    a = wa + ((rnd.nextDouble() - 0.5) * Math.PI);
		    d = (spawnr * 0.7) + (rnd.nextDouble() * spawnr * 0.3);
		} else {
		    a = rnd.nextDouble() * Math.PI * 2;
		    d = spawnr * (hole + ((1 - hole) * Math.sqrt(rnd.nextDouble())));
		}
		bx = focus.x + (Math.cos(a) * d);
		by = focus.y + (Math.sin(a) * d);
		boolean clear = true;
		for(Cloud o : clouds) {
		    if((Math.abs(o.zoff - c.zoff) < 400) && (Math.hypot(o.x - bx, o.y - by) < (gap * (o.foot + c.foot)))) {
			clear = false;
			break;
		    }
		}
		if(clear)
		    break;
	    }
	    c.x = bx;
	    c.y = by;
	    clouds.add(c);
	    return(c);
	}

	/* The clouds as the shaders read them: a bounding sphere, the look of each, its edge, and its balls.
	 * No cloud's base comes within 150 of the ground under the player, whatever its kind's offset. */
	void write(Frame f) {
	    /* Nearest the player first, each ring: the shader stops at the first opaque ones. */
	    Comparator<Cloud> near = Comparator.comparingDouble(c -> Math.hypot(c.x - f.focus.x, c.y - f.focus.y));
	    clouds.sort(near);
	    far.sort(near);
	    int nn = Math.min(clouds.size(), SkyPass.NEAR), nf = Math.min(far.size(), SkyPass.FAR);
	    int n = nn + nf;
	    float ground = Float.isNaN(gz) ? f.focus.z : gz;
	    for(int k = 0; k < n; k++) {
		Cloud c = (k < nn) ? clouds.get(k) : far.get(k - nn);
		float base = Math.max(ground + cloudbase + c.zoff, ground + 150);
		f.cl[(k * 4) + 0] = (float)c.x + c.bx;
		f.cl[(k * 4) + 1] = (float)c.y + c.by;
		f.cl[(k * 4) + 2] = base + c.bz;
		f.cl[(k * 4) + 3] = c.brad;
		f.ci[(k * 4) + 0] = c.op * c.op * (3 - (2 * c.op)) * c.opmax;
		f.ci[(k * 4) + 1] = base;
		f.ci[(k * 4) + 2] = c.dark;
		f.ci[(k * 4) + 3] = c.seed;
		f.cx[(k * 4) + 0] = c.soft;
		f.cx[(k * 4) + 1] = c.rag;
		f.cx[(k * 4) + 2] = c.foot;
		f.cx[(k * 4) + 3] = c.nscale;
		for(int j = 0; j < SkyPass.PUFFS; j++) {
		    int o = ((k * SkyPass.PUFFS) + j) * 4;
		    f.pf[o + 0] = (float)c.x + c.puffs[(j * 4) + 0];
		    f.pf[o + 1] = (float)c.y + c.puffs[(j * 4) + 1];
		    f.pf[o + 2] = base + c.puffs[(j * 4) + 2];
		    f.pf[o + 3] = (float)Math.floor(Math.max(c.puffs[(j * 4) + 3], 1)) + Math.min(c.squash[j], 0.999f);
		}
	    }
	    f.ncl = n;
	    f.nnear = nn;
	    f.ground = ground;
	}

	/* Every add below compiles its slot on the spot, and a slot that samples the cloud texture throws
	 * Loading until the texture has decoded -- which only starts when something first asks for it. A
	 * failed add rolls itself back (RenderTree.TreeSlot.add), so each one is simply tried again next
	 * tick, as MapView.updweather does with the game's own weather. */
	void sync(MapView mv, Weather w) {
	    TexRender ct = cloudtex();
	    if(ct == null)
		return;
	    if(sky == null) {
		/* Not before the driver has said it takes the passes' programs (SkyPass.probe): a refusal is the
		 * error path in tick, which switches the ambience off for the session; no answer yet is a frame
		 * or two more of the game's own sky. */
		SkyPass.Probe pr = SkyPass.probe(mv.ui.getenv());
		if(pr.state == 2)
		    throw(new RuntimeException("the graphics driver will not take the sky's shaders -- " + pr.why));
		if(pr.state != 1)
		    return;
		try {
		    sky = mv.drawadd(new SkyPass());
		} catch(Loading e) {
		}
	    }
	    /* The performance panel's weather switches still hold: off there is off here too. */
	    try {
		/* One instance, whose numbers are uniforms over the frame: installed once, never re-pushed. */
		if(!clouds.isEmpty() && io.brodgar.perf.Performance.clouds)
		    mv.basic(cskey, AmbShadow.instance);
		else
		    mv.basic(cskey, null);
	    } catch(Loading e) {
	    }
	}

	String census() {
	    int[] n = new int[KINDS.length];
	    for(Cloud c : clouds)
		n[c.kind]++;
	    StringBuilder buf = new StringBuilder();
	    for(int i = 0; i < n.length; i++) {
		if(n[i] > 0)
		    buf.append((buf.length() > 0) ? ", " : "").append(n[i]).append(' ').append(KINDS[i]);
	    }
	    if(!far.isEmpty())
		buf.append((buf.length() > 0) ? ", " : "").append(far.size()).append(" on the horizon");
	    return((buf.length() > 0) ? buf.toString() : "none");
	}

	void off(MapView mv) {
	    if(sky != null) {sky.remove(); sky = null;}
	    mv.basic(cskey, null);
	}
    }

    /* Indoors and underground, the ground round the player is VOID: a house, a dungeon or any other instance
     * stands in the black gfx/tiles/nil ground, and a mine level is solid rock (a CaveTile, whose own ground
     * is that nil) round its tunnels. Measured over the maintainer's map database (2026-09-26, 203k grids):
     * the 3x3 grids round a house's or an instance's grid average at least half void in every case, round a
     * mine's in 99.8%, round an overworld one in 0.13%. */
    static final String VOID = "gfx/tiles/nil";
    /* Whether the drawn map view is indoors: the clouds go back to the game there (replaces). */
    static volatile boolean indoors = false;

    /* The share of one grid's tiles that are void. Throws Loading while a tileset has not arrived. */
    static float voidshare(MCache map, MCache.Grid g) {
	byte[] memo = new byte[64];
	int n = 0;
	for(int t : g.tiles) {
	    if(t >= memo.length)
		memo = Arrays.copyOf(memo, Math.max(t + 1, memo.length * 2));
	    if(memo[t] == 0) {
		Tileset set = map.tileset(t);
		boolean v = (set != null) && (VOID.equals(set.getres().name) || (map.tiler(t) instanceof haven.resutil.CaveTile));
		memo[t] = (byte)(v ? 1 : 2);
	    }
	    if(memo[t] == 1)
		n++;
	}
	return((float)n / g.tiles.length);
    }

    // retained: until the map view is disposed (disposed(), from MapView.dispose); tick drops it earlier when
    //   the ambience is switched off, and the error path when it switches itself off. The weak key frees
    //   nothing once View.sky is set: a render slot keeps its parent, basic, whose parent conf holds the
    //   view's own WidgetContext in its state -- the value reaches its key.
    private static final Map<MapView, View> views = new WeakHashMap<>();

    /* The map view is going away (MapView.dispose, on the thread tearing its UI down, never during its own
     * tick). Only the entry goes: the sky's slot is the view's tree's, and PView.dispose removes it, which
     * frees the clouds' target (SkyPass.removed). */
    public static void disposed(MapView mv) {
	synchronized(views) {views.remove(mv);}
    }

    /* Every tick of the drawn map view, after the game has composed its own weather (MapView.tick). */
    public static void tick(MapView mv, Glob glob, double dt) {
	View v;
	synchronized(views) {
	    v = views.get(mv);
	}
	if(!enabled()) {
	    if(v != null) {
		v.off(mv);
		synchronized(views) {views.remove(mv);}
	    }
	    return;
	}
	if(v == null) {
	    v = new View();
	    synchronized(views) {views.put(mv, v);}
	}
	DirLight l = mv.amblight;
	if(l == null)
	    return;
	Coord3f cc;
	try {
	    cc = mv.getcc();
	} catch(Loading e) {
	    return;
	}
	if(v.inside(glob.map, cc, dt)) {
	    /* Indoors or underground: nothing of ours -- round a house and a mine level the game draws black --
	     * and the sky seeded afresh on the way out: the map was re-based, the clouds up stand elsewhere. */
	    v.off(mv);
	    v.reset();
	    indoors = true;
	    return;
	}
	indoors = false;
	Coord3f focus = cc.invy();
	Weather w = (pweather != null) ? pweather : fromserver(glob);
	v.ease(w, dt);
	v.astro(glob.ast, dt);
	if(cloudson)
	    v.populate(w, focus, dt, glob.globtime());
	else
	    v.noclouds(w);
	frame = compute(l, focus, w, v, glob.ast);
	try {
	    v.sync(mv, w);
	} catch(Loading e) {
	    throw(e);
	} catch(RuntimeException e) {
	    /* The ambience must never take the client down: what it cannot draw, it stops drawing, for this
	     * session, and says why on the console. The pref is left as it was. */
	    new Exception("ambience: switched off after an error", e).printStackTrace();
	    failed = true;
	    try {
		v.off(mv);
	    } catch(RuntimeException e2) {
		e2.printStackTrace();
	    }
	    synchronized(views) {views.remove(mv);}
	}
    }

    private static void say(Console cons, String msg) {
	if(cons.out != null)
	    cons.out.println(msg);
    }

    /* A number off the console, finite, or the command is refused before anything is set. */
    private static double arg(String[] args) {
	if(args.length < 3)
	    throw(new RuntimeException("usage: amb " + args[1] + " <number>"));
	double v = Double.parseDouble(args[2]);
	if(!Double.isFinite(v))
	    throw(new RuntimeException("amb " + args[1] + ": not a finite number: " + args[2]));
	return(v);
    }

    private static void command(Console cons, String[] args) {
	String sub = (args.length > 1) ? args[1] : "";
	switch(sub) {
	case "on":
	case "off":
	    skyon(sub.equals("on"));
	    cloudson(sub.equals("on"));
	    fogon(sub.equals("on"));
	    failed = false;
	    say(cons, "ambience " + sub);
	    return;
	case "time":
	    if((args.length < 3) || args[2].equals("server")) {
		ptime = null;
		say(cons, "ambience: the light is the server's");
	    } else {
		ptime = arg(args);
		say(cons, "ambience: previewing the light of " + args[2] + "h");
	    }
	    return;
	case "weather":
	    if((args.length < 3) || args[2].equals("server")) {
		pweather = null;
		say(cons, "ambience: the weather is the server's");
	    } else {
		Weather w = presets.get(args[2]);
		if(w == null)
		    throw(new RuntimeException("no such weather: " + args[2] + " -- one of " + String.join(", ", presets.keySet()) + ", server"));
		pweather = w;
		say(cons, "ambience: previewing " + w);
	    }
	    return;
	case "clouds":
	    if((args.length < 3) || args[2].equals("auto")) {
		pclouds = -1;
		say(cons, "ambience: as many clouds as the weather has");
	    } else {
		pclouds = Math.max(0, Math.min(SkyPass.MAXCL, Integer.parseInt(args[2])));
		say(cons, "ambience: " + pclouds + " clouds, whatever the weather");
	    }
	    return;
	case "cloudalt":
	    cloudalt((float)arg(args));
	    say(cons, String.format("ambience: cloud bases %.0f above the ground", cloudbase));
	    return;
	case "moon":
	    if((args.length < 3) || args[2].equals("server")) {
		pmoon = null;
		say(cons, "ambience: the moon is the server's");
	    } else {
		pmoon = arg(args);
		say(cons, "ambience: previewing the " + phase(pmoon) + " (" + args[2] + ")");
	    }
	    return;
	case "fog":
	    fog((float)arg(args));
	    say(cons, "ambience: fog x" + fogmul);
	    return;
	case "":
	    say(cons, "ambience: sky " + (skyon ? "on" : "off") + ", clouds " + (cloudson ? "on" : "off") +
		", fog " + (fogon ? "on" : "off") + (failed ? " (switched off after an error)" : "") + (indoors ? " (indoors: nothing drawn)" : "") + ", light: " + ((ptime == null) ? "server" : ("preview " + ptime + "h")) +
		", weather: " + ((pweather == null) ? "server" : ("preview " + pweather.name)) +
		", clouds: " + ((pclouds < 0) ? "auto" : Integer.toString(pclouds)) +
		String.format(" x%.2f at %.0f, fog x%.2f, stars and moon %s", cloudamount, cloudbase, fogmul, starsmoon ? "on" : "off"));
	    List<Map.Entry<MapView, View>> es;
	    synchronized(views) {es = new ArrayList<>(views.entrySet());}
	    for(Map.Entry<MapView, View> e : es)
		say(cons, "the server says: " + describe(e.getKey()) + "; clouds up: " + e.getValue().census() +
		    String.format("; sky closed %.2f", e.getValue().close));
	    return;
	default:
	    throw(new RuntimeException("usage: amb [on|off|clouds auto|clouds <n>|moon <0-1>|moon server|cloudalt <units>|time <hour>|time server|weather <" +
				       String.join("|", presets.keySet()) + "|server>|fog <x>]"));
	}
    }

    private static String describe(MapView mv) {
	Glob glob;
	try {
	    glob = mv.ui.sess.glob;
	} catch(NullPointerException e) {
	    return("(no session)");
	}
	StringBuilder buf = new StringBuilder(fromserver(glob).toString());
	synchronized(glob) {
	    buf.append(String.format("; light amb %s dif %s ang %.2f elev %.2f", rgbs(glob.lightamb), rgbs(glob.lightdif), glob.lightang, glob.lightelev));
	    Astronomy a = glob.ast;
	    if(a != null)
		buf.append(String.format("; day %.3f%s, moon %.3f (%s) colour %s", a.dt, a.night ? " night" : "", a.mp, phase(a.mp), rgbs(a.mc)));
	    View v;
	    synchronized(views) {v = views.get(mv);}
	    if(v != null)
		buf.append(String.format("; midnight at %.1f of the day, night %.2f", v.midnight, v.nightfade));
	    /* The server's "sky", which nothing draws: is it sent outdoors only? Look here in a cave. */
	    buf.append("; sky " + resname(glob.sky1) + " / " + resname(glob.sky2) + String.format(" blend %.2f", glob.skyblend));
	}
	return(buf.toString());
    }

    private static String rgbs(Color c) {
	return((c == null) ? "none" : String.format("%d,%d,%d", c.getRed(), c.getGreen(), c.getBlue()));
    }

    private static String resname(Indir<Resource> r) {
	if(r == null)
	    return("none");
	try {
	    return(r.get().name);
	} catch(Loading l) {
	    return("(loading)");
	}
    }
}
