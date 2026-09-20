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
import java.util.function.*;
import java.security.*;
import java.util.concurrent.atomic.*;

public class Defer extends ThreadGroup {
    private static final Map<ThreadGroup, Defer> groups = new WeakHashMap<ThreadGroup, Defer>();
    private final Queue<Future<?>> queue = new PrioQueue<Future<?>>();
    private final Collection<Thread> pool = new LinkedList<Thread>();
    // addon: (120.3) static and readable in the package rather than private to an instance -- the value is
    //        the machine's and identical in every group. A budget for work that runs HERE is a share of this
    //        pool, and a share is written by reading the pool, never by restating the formula somewhere it
    //        can go quietly out of step with this line.
    static final int maxthreads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    private final AtomicInteger busy = new AtomicInteger(0);
    /* addon: (terrain loading) ONE WORKER IS KEPT FOR URGENT WORK. A future boosted to URGENT or above is
     * something a thread is blocked on -- the camera's own cut at login, a texture a draw list is waiting
     * for -- and before this every worker could be taken by ordinary builds for a second at a time (the
     * first cut meshes of a session run in the interpreter, 500-900 ms each, fifteen at once), so the one
     * future that unblocks the screen queued behind them. A worker leaves the last free slot to urgent
     * work: it takes ordinary work only while at least `reserve` slots would remain. `running` is the
     * count of futures taken and not yet finished, kept under `queue` so the test and the take are one. */
    public static final int URGENT = 10;
    private static final int reserve = (maxthreads >= 4) ? 1 : 0;
    private int running = 0, urgent = 0;
    /* addon: (terrain loading) and while an urgent future is OUTSTANDING -- queued, running, or thrown
     * back by a Loading and waiting to be asked for again -- ordinary work waits too, for at most
     * URGENT_HOLD. The cut under the camera is the last of its neighbours to be ready to build, because it
     * is the one that discovers its textures a stumble at a time, and by then fourteen ordinary builds had
     * started around it and it built beside them: 203-250 ms of CPU for a build that costs 47 alone. The
     * bound is for an urgent future that cannot finish -- a cut whose neighbour grid never comes -- so the
     * rest of the world still loads. Counted on the future the moment it crosses URGENT (`counted`), and
     * uncounted when it is done, whichever way. */
    private int urgentout = 0;
    private long urgentsince = 0;
    static final long URGENT_HOLD = 1000;
    
    public interface Callable<T> {
	public T call() throws InterruptedException;
    }

    public static class CancelledException extends RuntimeException {
	public CancelledException() {
	    super("Execution cancelled");
	}
	
	public CancelledException(Throwable cause) {
	    super(cause);
	}
    }

    public static class DeferredException extends RuntimeException {
	public DeferredException(Throwable cause) {
	    super(cause);
	}
    }

    public static class NotDoneException extends Loading {
	public final transient Future future;

	public NotDoneException(Future future) {
	    this.future = future;
	}

	public NotDoneException(Future future, Loading cause) {
	    super(cause);
	    this.future = future;
	}

	public String getMessage() {
	    if(rec != null) {
		String msg = rec.getMessage();
		if(msg != null)
		    return(msg);
	    }
	    if(future == null)
		return(null);
	    String msg = future.task.toString();
	    if((msg == null) && (future.running == null))
		return("Waiting on job queue...");
	    if(msg == null)
		return(null);
	    if(future.running == null)
		msg = msg + " (queued)";
	    return(msg);
	}

	public void waitfor(Runnable callback, Consumer<Waitable.Waiting> reg) {
	    synchronized(future) {
		if(future.done()) {
		    reg.accept(Waitable.Waiting.dummy);
		    callback.run();
		} else {
		    reg.accept(new Waitable.Checker(callback) {
			    protected Object monitor() {return(future);}
			    protected boolean check() {return(future.done());}
			    protected Waitable.Waiting add() {return(future.wq.add(this));}
			}.addi());
		}
	    }
	}

	public boolean boostprio(int prio) {
	    future.boostprio(prio);
	    return(true);
	}
    }

    public class Future<T> implements Runnable, Prioritized, haven.Future<T> {
	public final Callable<T> task;
	private final Waitable.Queue wq = new Waitable.Queue();
	private int prio = -1;
	private T val;
	private volatile String state = "";
	private Throwable exc = null;
	private Loading lastload = null;
	private volatile Thread running = null;
	private boolean takenurgent = false;   // addon: (terrain loading) how take() classed it, fixed there
	private boolean counted = false;       // addon: (terrain loading) in `urgentout` -- see boostprio and chstate
	
	private Future(Callable<T> task) {
	    this.task = task;
	}

	public void cancel() {
	    synchronized(this) {
		if(running != null) {
		    running.interrupt();
		} else if(state != "done") {
		    exc = new CancelledException();
		    chstate("done");
		}
	    }
	}

	private void chstate(String nst) {
	    boolean uncount = false;
	    synchronized(this) {
		this.state = nst;
		wq.wnotify();
		if((nst == "done") && counted) {   // addon: (terrain loading)
		    counted = false;
		    uncount = true;
		}
	    }
	    if(uncount) {
		synchronized(queue) {
		    if(--urgentout == 0)
			queue.notifyAll();   // the ordinary work that yielded to it
		}
	    }
	}

	public void run() {
	    synchronized(this) {
		if(state == "done")
		    return;
		running = Thread.currentThread();
	    }
	    try {
		busy.getAndIncrement();
		val = task.call();;
		lastload = null;
		chstate("done");
	    } catch(InterruptedException exc) {
		this.exc = new CancelledException(exc);
		chstate("done");
	    } catch(Loading exc) {
		exc.boostprio(prio);
		lastload = exc;
	    } catch(Throwable exc) {
		this.exc = exc;
		chstate("done");
	    } finally {
		synchronized(this) {
		    if(state != "done")
			chstate("resched");
		    running = null;
		}
		busy.getAndDecrement();
		/* XXX: This is a race; a cancelling thread could have
		 * gotten the thread reference via running and then
		 * interrupt this thread after interrupted()
		 * returns. There is no obvious elegant solution,
		 * though, and the risk should be quite low. Fix if
		 * possible. */
		Thread.interrupted();
	    }
	}
	
	public T get(int prio) {
	    synchronized(this) {
		boostprio(prio);
		if(state == "done") {
		    if(exc != null)
			throw(new DeferredException(exc));
		    return(val);
		}
		if(state == "resched") {
		    defer(this);
		    state = "";
		}
		throw(new NotDoneException(this, lastload));
	    }
	}
	
	public T get() {
	    return(get(5));
	}
	
	public boolean done(int prio) {
	    synchronized(this) {
		boostprio(prio);
		if(state == "resched") {
		    defer(this);
		    state = "";
		}
		return(state == "done");
	    }
	}
	
	public boolean done() {
	    return(done(5));
	}
	
	public int priority() {
	    return(prio);
	}
	
	public void boostprio(int prio) {
	    boolean count = false;
	    synchronized(this) {
		if(this.prio < prio) {
		    this.prio = prio;
		    if((prio >= URGENT) && !counted && (state != "done")) {
			counted = true;
			count = true;
		    }
		}
	    }
	    /* addon: (terrain loading) a future that just became urgent is counted outstanding, and wakes the
	     * reserved worker now rather than at its next one-second poll. Outside the future's monitor. */
	    if(count) {
		synchronized(queue) {
		    if(urgentout++ == 0)
			urgentsince = System.currentTimeMillis();
		    queue.notifyAll();
		}
	    }
	}
    }

    /* addon: (terrain loading) the next future for a worker, or null to keep waiting: the highest priority
     * queued, unless it is ordinary work and taking it would leave fewer than `reserve` slots free. Called
     * with `queue` held. */
    private Future<?> take() {
	Future<?> f = queue.peek();
	if(f == null)
	    return(null);
	f.takenurgent = f.priority() >= URGENT;
	if(!f.takenurgent) {
	    if(running >= maxthreads - reserve)
		return(null);
	    /* addon: (terrain loading) and none at all while urgent work runs. Measured at login with the
	     * code warm: the cut the screen waits for costs 47 ms of CPU built alone and 300-400 built
	     * beside thirteen others on sixteen logical cores of eight -- the pool's own parallelism was
	     * multiplying the one build that mattered. The others wait the ~100 ms it takes. */
	    if(urgent > 0)
		return(null);
	    if((urgentout > 0) && (System.currentTimeMillis() - urgentsince < URGENT_HOLD))
		return(null);
	} else {
	    urgent++;
	}
	queue.remove(f);   // identity: Future has no equals() of its own
	running++;
	return(f);
    }

    private static final AtomicInteger threadno = new AtomicInteger(0);
    private class Worker extends HackThread {
	private Worker() {
	    super(Defer.this, null, "Worker thread #" + threadno.getAndIncrement());
	    setDaemon(true);
	    setPriority((Thread.NORM_PRIORITY + Thread.MIN_PRIORITY) / 2);
	}
	
	public void run() {
	    try {
		while(true) {
		    Future<?> f;
		    try {
			long start = System.currentTimeMillis();
			synchronized(queue) {
			    while((f = take()) == null) {   // addon: (terrain loading) was queue.poll()
				if(System.currentTimeMillis() - start > 5000)
				    return;
				queue.wait(1000);
			    }
			}
		    } catch(InterruptedException e) {
			return;
		    }
		    /* addon: (terrain loading) urgent work runs at a normal thread priority and above: the pool
		     * sits at below-normal (see the constructor), and fifteen interpreted builds on sixteen logical
		     * cores gave the one build the screen waited for 1.35-2.5 wall-clock seconds per CPU second. */
		    boolean urg = f.takenurgent;
		    if(urg)
			setPriority(Thread.NORM_PRIORITY + 1);
		    try {
			f.run();
		    } finally {
			if(urg)
			    setPriority((Thread.NORM_PRIORITY + Thread.MIN_PRIORITY) / 2);
			synchronized(queue) {
			    running--;   // addon: (terrain loading)
			    if(urg) {
				urgent--;
				queue.notifyAll();   // the ordinary work that waited on it
			    }
			}
		    }
		    f = null;
		}
	    } finally {
		synchronized(queue) {
		    pool.remove(this);
		    if((pool.size() < 1) && !queue.isEmpty()) {
			Thread n = new Worker();
			n.start();
			pool.add(n);
		    }
		}
	    }
	}
    }

    public Defer(ThreadGroup parent) {
	super(parent, "DPC threads");
    }

    private void defer(final Future<?> f) {
	synchronized(queue) {
	    boolean e = queue.isEmpty();
	    queue.add(f);
	    queue.notify();
	    if((pool.isEmpty() || !e) && (pool.size() < maxthreads)) {
		Thread n = new Worker();
		n.start();
		pool.add(n);
	    }
	}
    }

    public <T> Future<T> defer(Callable<T> task) {
	Future<T> f = new Future<T>(task);
	defer(f);
	return(f);
    }

    private static Defer getgroup() {
	ThreadGroup tg = Thread.currentThread().getThreadGroup();
	if(tg instanceof Defer)
	    return((Defer)tg);
	Defer d;
	synchronized(groups) {
	    if((d = groups.get(tg)) == null)
		groups.put(tg, d = new Defer(tg));
	}
	return(d);
    }

    public static <T> Future<T> later(Callable<T> task) {
	Defer d = getgroup();
	return(d.defer(task));
    }

    public static <T> Future<T> later(Runnable task, T result) {
	return(later(() -> {
		    task.run();
		    return(result);
		}));
    }

    public String stats() {
	synchronized(queue) {
	    return(String.format("%d %d/%d", queue.size(), busy.get(), pool.size()));
	}
    }

    public static String gstats() {
	return(getgroup().stats());
    }

    /* addon: the same three numbers stats() formats, as numbers (spec 019, task 019.3) -- one call under
     * one lock, so they are mutually consistent. Snapshot-time only. */
    public int[] statcounts() {
	synchronized(queue) {
	    return(new int[] {queue.size(), busy.get(), pool.size()});
	}
    }

    public static int[] gstatcounts() {
	return(getgroup().statcounts());
    }
}
