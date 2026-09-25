package io.brodgar.camera;

import haven.Coord2d;
import haven.Coord3f;
import haven.Drawable;
import haven.FastMesh;
import haven.Gob;
import haven.OCache;
import haven.RenderLink;
import haven.Resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * <b>What stands between a camera's pivot and its eye</b> -- the world's objects, as the camera sees them.
 *
 * <p>An object is a <b>prism</b>: its footprint (every {@code obst} ring the resource carries bar its
 * {@code build} box -- what the server collides against, so what a player cannot walk through either) stood up
 * from the ground it is placed on to the top of its model ({@link FastMesh#pbounds}). That is a tree's trunk to
 * its crown, a palisade's run to its tips, a house's walls to its ridge. It is not the model itself: a tree's
 * foliage stands outside its trunk's footprint, so the eye may pass through leaves, and nothing under
 * {@link #MINTOP} counts at all, so the clutter underfoot does not shove the camera about.
 *
 * <p>Only {@code obst}: a {@code neg} box is walkable ground a thing merely lies on (a felled log), and a
 * creature carries none, so the characters and animals around the player never pull the eye in.
 */
public final class Obstruction {
    private Obstruction() {}

    /** A model whose top is lower than this is clutter, not an obstruction. */
    private static final float MINTOP = 8f;
    /** The top of an object whose resource carries a footprint but no mesh of its own to measure. */
    private static final float DEFTOP = 20f;

    private static final class Shape {
        final Coord2d[][] rings;
        final float top;
        final double reach;

        Shape(Coord2d[][] rings, float top) {
            this.rings = rings;
            this.top = top;
            double r = 0;
            for(Coord2d[] ring : rings) {
                for(Coord2d p : ring)
                    r = Math.max(r, Math.hypot(p.x, p.y));
            }
            this.reach = r;
        }
    }

    /** No shape: a resource with no footprint, or one too low to count. Cached like any other answer. */
    private static final Shape NONE = new Shape(new Coord2d[0][], 0);

    private static final Map<Resource, Shape> shapes = Collections.synchronizedMap(new WeakHashMap<Resource, Shape>());

    private static Shape shape(Resource res) {
        Shape s = shapes.get(res);
        if(s == null) {
            s = measure(res);
            shapes.put(res, s);
        }
        return(s);
    }

    private static Shape measure(Resource res) {
        List<Coord2d[]> rings = new ArrayList<Coord2d[]>();
        for(Resource.Obstacle obst : res.layers(Resource.obst)) {
            if("build".equals(obst.id))
                continue;
            for(Coord2d[] ring : obst.p) {
                if(ring.length >= 3)
                    rings.add(ring);
            }
        }
        if(rings.isEmpty())
            return(NONE);
        float top = top(res);
        if(Float.isNaN(top)) {
            /* A thin wrapper render-linking its mesh from another resource: measure that one. */
            for(RenderLink.Res link : res.layers(RenderLink.Res.class)) {
                if(link.l instanceof RenderLink.MeshMat) {
                    Resource mesh = ((RenderLink.MeshMat)link.l).mesh.get();
                    if(mesh != null)
                        top = top(mesh);
                }
            }
        }
        if(Float.isNaN(top))
            top = DEFTOP;
        if(top < MINTOP)
            return(NONE);
        return(new Shape(rings.toArray(new Coord2d[0][]), top));
    }

    private static float top(Resource res) {
        float top = Float.NaN;
        for(FastMesh.MeshRes mr : res.layers(FastMesh.MeshRes.class)) {
            if(mr.m == null)
                continue;
            float z = mr.m.pbounds().z;
            top = Float.isNaN(top) ? z : Math.max(top, z);
        }
        return(top);
    }

    /**
     * How far along the segment from {@code from} in the unit direction {@code dir} -- world coordinates, z up --
     * the eye may stand, up to {@code want}, and still be {@code margin} short of every object in the way.
     * {@code want} when nothing is. An object the pivot itself stands inside is no obstruction -- the eye cannot
     * be kept out of it -- and neither is {@code skip}, the player the camera is looking at.
     */
    public static float room(OCache oc, Coord3f from, Coord3f dir, float want, Gob skip, float margin) {
        if(want <= 0)
            return(want);
        double ex = from.x + (dir.x * want), ey = from.y + (dir.y * want);
        List<Gob> near = new ArrayList<Gob>();
        synchronized(oc) {
            for(Gob g : oc) {
                if((g == skip) || (g.rc == null))
                    continue;
                /* A generous first cut on the raw position: nothing is reached further out than this. */
                if(segdist(g.rc.x, g.rc.y, from.x, from.y, ex, ey) < 200)
                    near.add(g);
            }
        }
        float best = want;
        for(Gob g : near) {
            try {
                Drawable d = g.getattr(Drawable.class);
                if(d == null)
                    continue;
                Resource res = d.getres();
                if(res == null)
                    continue;
                Shape s = shape(res);
                if((s == NONE) || (segdist(g.rc.x, g.rc.y, from.x, from.y, ex, ey) > s.reach))
                    continue;
                Coord3f c = g.getc();
                float hit = enter(s, c, g.a, from, dir, best);
                if(hit < best)
                    best = hit;
            } catch(RuntimeException e) {
                /* Loading, or an object mid-change: it is not in the way this frame. */
            }
        }
        return((best < want) ? Math.max(0f, best - margin) : want);
    }

    /** Where the segment first enters the object's prism, or {@code limit} when it does not before then. */
    private static float enter(Shape s, Coord3f c, double a, Coord3f from, Coord3f dir, float limit) {
        /* Into the object's own frame: gob:hitbox() turns a ring point by +a, so the ray turns by -a. */
        double cs = Math.cos(a), sn = Math.sin(a);
        double rx = from.x - c.x, ry = from.y - c.y;
        double ox = (rx * cs) + (ry * sn), oy = (ry * cs) - (rx * sn);
        double dx = (dir.x * cs) + (dir.y * sn), dy = (dir.y * cs) - (dir.x * sn);
        /* The slab of the ray between the object's foot and its top. */
        double z0 = c.z, z1 = c.z + s.top;
        double tz0, tz1;
        if(Math.abs(dir.z) < 1e-6) {
            if((from.z < z0) || (from.z > z1))
                return(limit);
            tz0 = Double.NEGATIVE_INFINITY;
            tz1 = Double.POSITIVE_INFINITY;
        } else {
            double ta = (z0 - from.z) / dir.z, tb = (z1 - from.z) / dir.z;
            tz0 = Math.min(ta, tb);
            tz1 = Math.max(ta, tb);
        }
        float best = limit;
        for(Coord2d[] ring : s.rings) {
            /* Where the ray crosses the ring's edges; between crossings it alternates inside and out. */
            List<Double> ts = new ArrayList<Double>();
            for(int i = 0; i < ring.length; i++) {
                Coord2d p = ring[i], q = ring[(i + 1) % ring.length];
                double ex = q.x - p.x, ey = q.y - p.y;
                double den = (dx * ey) - (dy * ex);
                if(Math.abs(den) < 1e-12)
                    continue;
                double wx = p.x - ox, wy = p.y - oy;
                double t = ((wx * ey) - (wy * ex)) / den;
                double u = ((wx * dy) - (wy * dx)) / den;
                if((u >= 0) && (u < 1) && (t > 0) && (t < limit))
                    ts.add(t);
            }
            boolean in = inside(ring, ox, oy);
            if(in && (tz0 <= 0) && (tz1 >= 0))
                return(limit);   // the pivot is inside this object: it is no obstruction
            Collections.sort(ts);
            double start = in ? 0 : Double.NaN;
            for(int i = 0; i <= ts.size(); i++) {
                double t = (i < ts.size()) ? ts.get(i) : limit;
                if(in) {
                    /* An inside span [start, t]: the first of it that is also within the slab. */
                    double lo = Math.max(start, tz0), hi = Math.min(t, tz1);
                    if((lo <= hi) && (lo < best))
                        best = (float)lo;
                } else {
                    start = t;
                }
                in = !in;
            }
        }
        return(best);
    }

    private static boolean inside(Coord2d[] ring, double x, double y) {
        boolean in = false;
        for(int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            Coord2d p = ring[i], q = ring[j];
            if(((p.y > y) != (q.y > y)) && (x < (((q.x - p.x) * (y - p.y)) / (q.y - p.y)) + p.x))
                in = !in;
        }
        return(in);
    }

    private static double segdist(double px, double py, double ax, double ay, double bx, double by) {
        double vx = bx - ax, vy = by - ay;
        double l2 = (vx * vx) + (vy * vy);
        double t = (l2 <= 0) ? 0 : Math.max(0, Math.min(1, (((px - ax) * vx) + ((py - ay) * vy)) / l2));
        return(Math.hypot(px - (ax + (vx * t)), py - (ay + (vy * t))));
    }
}
