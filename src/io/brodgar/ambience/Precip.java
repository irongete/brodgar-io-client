package io.brodgar.ambience;

import java.nio.*;
import java.util.*;
import haven.*;
import haven.render.*;
import haven.render.sl.*;
import static haven.render.sl.Type.*;

/* Rain or snow drawn on the graphics card.
 *
 * The game's own rain and snow move every particle on the CPU every tick and upload all of them every
 * frame. Here a particle is a record written once, when it is born -- where it is aimed, how it moves,
 * when it was born -- into a ring of such records, each one instance of a small model (a raindrop and its
 * splashes, a snowflake's quad), and the vertex program works out from the frame's time where every one
 * of them is and whether it is still there. Per frame, only the records born since the last are uploaded.
 *
 * A slot of the ring is written again only once what it held is gone (death); a ring that is full grows,
 * and is uploaded whole once. The clock is the frame's own (FrameInfo.time) less an epoch of this ring's,
 * moved every so often so that a float still tells the frames apart. */
abstract class Precip implements RenderTree.Node, TickList.Ticking, TickList.TickNode {
    static final int INITCAP = 1024;
    /* How long an epoch lasts, in seconds: a float of the clock is still exact to a few microseconds. */
    static final double REBASE = 600;
    /* A record's birth that is never alive: every test of an age against a life fails on it. */
    static final float NEVER = -1e9f;

    /* Bytes per record, and where in it the birth is (a float, clock time less the epoch). */
    final int stride, birthoff;
    /* The model every record is an instance of: its vertices, and its indices if it has any. */
    final VertexArray.Buffer vbuf;
    final Model.Indices ind;
    final int count;
    final Model.Mode mode;
    final VertexArray.Layout fmt;

    /* The ring, as the CPU keeps it, and when each slot's particle is gone (clock time less the epoch). */
    private ByteBuffer mirror;
    private float[] death;
    /* When the last particle in the ring is gone (clock time less the epoch). */
    private float lastdeath = NEVER;
    int cap, head;
    /* The slots written since the last upload, from dstart on (they are written in turn). */
    private int dstart, dcount;
    /* The ring was made anew (first, or grown): the next upload makes a new buffer of it. */
    private boolean remade = true;
    volatile double epoch;
    private VertexArray.Buffer ibuf;
    private Model model;
    /* The slots the drawn part stands in: told when the model is made anew. */
    private final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
    final Clock clock = new Clock(this);

    Precip(int stride, int birthoff, VertexArray.Layout fmt, Model.Mode mode, ByteBuffer vdata, int count, short[] ind) {
	this.stride = stride;
	this.birthoff = birthoff;
	this.fmt = fmt;
	this.mode = mode;
	this.count = count;
	this.vbuf = new VertexArray.Buffer(vdata, DataBuffer.Usage.STATIC).shared();
	this.ind = (ind == null) ? null : new Model.Indices(ind.length, NumberFormat.UINT16, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(ind)).shared();
	this.epoch = Utils.rtime();
	this.cap = INITCAP;
	this.mirror = ByteBuffer.allocate(cap * stride).order(ByteOrder.nativeOrder());
	this.death = new float[cap];
	for(int i = 0; i < cap; i++) {
	    mirror.putFloat((i * stride) + birthoff, NEVER);
	    death[i] = NEVER;
	}
    }

    /* The vertex program: where each vertex of each instance is at the frame's time. */
    abstract ShaderMacro shader();
    /* Everything the drawn part is drawn with, the clock among it. */
    abstract Pipe.Op states();

    /* The frame's time less this ring's epoch. */
    static final Uniform u_time = new Uniform(FLOAT, "ptime", p -> {
	    Clock c = p.get(Clock.slot);
	    FrameInfo fi = p.get(FrameInfo.slot);
	    double t = (fi == null) ? Utils.rtime() : fi.time;
	    return((float)(t - ((c == null) ? 0 : c.owner.epoch)));
	}, Clock.slot, FrameInfo.slot);

    /* The ring's clock, and the vertex program that reads it. */
    static class Clock extends State {
	static final Slot<Clock> slot = new Slot<>(Slot.Type.GEOM, Clock.class);
	final Precip owner;

	Clock(Precip owner) {this.owner = owner;}

	public ShaderMacro shader() {return(owner.shader());}
	public void apply(Pipe p) {p.put(slot, this);}
    }

    /* The part that is drawn: the model, with ninst the ring's size. */
    private final class Part implements RenderTree.Node, Rendered {
	public void draw(Pipe state, Render out) {
	    Model m = model;
	    if(m != null)
		out.draw(state, m);
	}

	public void added(RenderTree.Slot slot) {
	    synchronized(Precip.this) {slots.add(slot);}
	}

	public void removed(RenderTree.Slot slot) {
	    synchronized(Precip.this) {slots.remove(slot);}
	}
    }
    private final Part part = new Part();

    public void added(RenderTree.Slot slot) {
	slot.add(part, states());
    }

    public TickList.Ticking ticker() {return(this);}

    /* The clock time of now, and the epoch moved on when it has lasted long enough: every birth and death
     * moves back by as much, and the ring is uploaded whole. */
    double now() {
	double now = Utils.rtime();
	if(now - epoch >= REBASE) {
	    synchronized(this) {
		float d = (float)(now - epoch);
		for(int i = 0; i < cap; i++) {
		    int o = (i * stride) + birthoff;
		    float b = mirror.getFloat(o);
		    if(b != NEVER)
			mirror.putFloat(o, b - d);
		    if(death[i] != NEVER)
			death[i] -= d;
		}
		if(lastdeath != NEVER)
		    lastdeath -= d;
		epoch = now;
		dstart = 0;
		dcount = cap;
	    }
	}
	return(now);
    }

    /* A slot for a particle gone at `until` (clock time), to be written with put*. The ring grows when the
     * next slot's particle is still there. Called under this object's monitor. */
    int alloc(double now, double until) {
	float n = (float)(now - epoch);
	if(death[head] > n)
	    grow();
	int s = head;
	head = (head + 1) % cap;
	death[s] = (float)(until - epoch);
	lastdeath = Math.max(lastdeath, death[s]);
	if(!remade) {
	    if(dcount == 0)
		dstart = s;
	    dcount = Math.min(dcount + 1, cap);
	}
	return(s);
    }

    /* Twice the size: the old ring keeps its slots, the new half is empty, and writing goes on there. */
    private void grow() {
	int ncap = cap * 2;
	ByteBuffer nm = ByteBuffer.allocate(ncap * stride).order(ByteOrder.nativeOrder());
	mirror.clear();
	nm.put(mirror);
	float[] nd = Arrays.copyOf(death, ncap);
	for(int i = cap; i < ncap; i++) {
	    nm.putFloat((i * stride) + birthoff, NEVER);
	    nd[i] = NEVER;
	}
	head = cap;
	cap = ncap;
	mirror = nm;
	death = nd;
	remade = true;
	dcount = 0;
    }

    void putf(int s, int off, float v) {mirror.putFloat((s * stride) + off, v);}
    void puti(int s, int off, int v) {mirror.putInt((s * stride) + off, v);}
    void birth(int s, double t) {putf(s, birthoff, (float)(t - epoch));}

    /* Whether nothing in the ring is still there. */
    synchronized boolean gone(double now) {
	return(lastdeath <= (float)(now - epoch));
    }

    /* How many particles are there now. */
    synchronized int live(double now) {
	float n = (float)(now - epoch);
	int c = 0;
	for(int i = 0; i < cap; i++) {
	    if(death[i] > n)
		c++;
	}
	return(c);
    }

    private FillBuffer fillall(VertexArray.Buffer buf, Environment env) {
	FillBuffer fb = env.fillbuf(buf);
	synchronized(this) {
	    ByteBuffer src = mirror.duplicate();
	    src.clear();
	    src.limit(Math.min(src.capacity(), buf.size()));
	    fb.push().put(src);
	}
	return(fb);
    }

    private void upload(Render g, int from, int n) {
	g.update(ibuf, (DataBuffer.PartFiller<VertexArray.Buffer>)(buf, env, f, t) -> {
		FillBuffer fb = env.fillbuf(buf, f, t);
		ByteBuffer src = mirror.duplicate();
		src.clear();
		src.position(f).limit(t);
		fb.push().put(src);
		return(fb);
	    }, from * stride, (from + n) * stride);
    }

    /* Once a frame, before it is drawn: a ring made anew is a new buffer (filled whole when the engine
     * first prepares it), else only what was written since is uploaded. */
    public void autogtick(Render g) {
	synchronized(this) {
	    if(remade) {
		Model old = model;
		ibuf = new VertexArray.Buffer(cap * stride, DataBuffer.Usage.STATIC, this::fillall);
		VertexArray va = new VertexArray(fmt, vbuf, ibuf);
		model = new Model(mode, va, ind, 0, count, cap);
		remade = false;
		dcount = 0;
		for(RenderTree.Slot slot : slots)
		    slot.update();
		if(old != null)
		    old.dispose();
		return;
	    }
	    if(dcount == 0)
		return;
	    if(dcount >= cap) {
		g.update(ibuf, this::fillall);
	    } else {
		int n1 = Math.min(dcount, cap - dstart);
		upload(g, dstart, n1);
		if(n1 < dcount)
		    upload(g, 0, dcount - n1);
	    }
	    dcount = 0;
	}
    }

    /* Taken out of the scene: the buffers go with it; the model's own vertices stay for a later one. */
    public void removed(RenderTree.Slot slot) {
	synchronized(this) {
	    if(model != null) {
		model.dispose();
		model = null;
		ibuf = null;
		remade = true;
	    }
	}
    }

    static VertexArray.Layout.Input in(Attribute a, int nc, NumberFormat nf, int buf, int off, int stride, boolean inst) {
	return(new VertexArray.Layout.Input(a, new VectorFormat(nc, nf), buf, off, stride, inst));
    }
}
