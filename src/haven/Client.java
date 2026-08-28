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
import java.io.*;
import java.nio.file.*;
import haven.render.*;
import haven.iosys.*;
import haven.iosys.tk.*;
import haven.iosys.audio.*;
import java.awt.image.BufferedImage;

public class Client implements Console.Directory {
    public static final Config.Variable<Boolean> initfullscreen = Config.Variable.propb("haven.fullscreen", false);
    public final Toolkit tk;
    public final Windeye wnd;
    private final EventQueue queue = new EventQueue(this);
    private UILoop loop;
    private Thread mt;

    public Client(Toolkit tk) {
	this.tk = tk;
	this.wnd = tk.window();
	wnd.title("Haven & Hearth");
	Coord fsz = Utils.getprefc("mainwnd/locksize", null);
	if(fsz == null)
	    wnd.sizing(new Windeye.Sizing().minsize(UI.scale(800, 600)).normsize(Utils.getprefc("mainwnd/size", UI.scale(1024, 768))));
	else
	    wnd.sizing(new Windeye.Sizing().fixsize(fsz));
	if(initfullscreen.get())
	    wnd.state(Windeye.State.EXCLUSIVE);
	else if(Utils.getprefb("mainwnd/max", false))
	    wnd.state(Windeye.State.MAXIMIZED);
	try(InputStream icon = Client.class.getResourceAsStream("icon.png")) {
	    wnd.icon(javax.imageio.ImageIO.read(icon));
	} catch(IOException e) {
	    throw(new Error(e));
	}
	wnd.add(queue);
	wnd.show(true);
    }

    public static class EventQueue implements Toolkit.EventListener {
	public final Client cl;
	private final List<Toolkit.Event> pending = new ArrayList<>();
	private Toolkit.MouseMoveEvent mousemv;

	public EventQueue(Client cl) {
	    this.cl = cl;
	}

	public void event(Toolkit.Event ev) {
	    if(ev instanceof Toolkit.CloseRequest) {
		Thread mt = cl.mt;
		if(mt != null)
		    mt.interrupt();
	    } else {
		synchronized(this) {
		    if(ev instanceof Toolkit.MouseMoveEvent) {
			mousemv = (Toolkit.MouseMoveEvent)ev;
		    } else {
			pending.add(ev);
		    }
		}
	    }
	}

	private static int buttonid(MouseBtn btn) {
	    if(btn == MouseBtn.Std.LEFT)
		return(1);
	    else if(btn == MouseBtn.Std.MIDDLE)
		return(2);
	    else if(btn == MouseBtn.Std.RIGHT)
		return(3);
	    return(0);
	}

	/* addon: (074.1) which tree took the press of each mouse button, so that its RELEASE goes to the same
	 * one. Layer-first is the rule for a press, and it would be wrong for the release that ends a drag: a
	 * camera pan begun on the world and let go over an addon window must still reach the tree that is
	 * panning, or the drag never ends. 0 = nobody pressed it, 1 = the layer, 2 = the session. */
	private final int[] btnowner = new int[4];

	public void dispatch(UI layer, UI ui) {
	    List<Toolkit.Event> evs;
	    Toolkit.MouseMoveEvent mousemv;
	    synchronized(this) {
		mousemv = this.mousemv;
		this.mousemv = null;
		evs = new ArrayList<>(pending);
		pending.clear();
	    }
	    /* addon: (074.1) A MOVE IS NOT A CLAIM, so both trees get every one of them. It is how a widget
	     * un-hovers when the pointer leaves it and how each tree learns where the pointer is, and the
	     * client's own dispatch already broadcasts it to every child rather than stopping at the first. */
	    if(mousemv != null) {
		java.awt.event.MouseEvent awt = AWTCompat.mkawt(mousemv);
		synchronized(layer) {layer.mousemove(awt, mousemv.wndc());}
		synchronized(ui) {ui.mousemove(awt, mousemv.wndc());}
	    }
	    for(Toolkit.Event ev : evs) {
		if(ev instanceof Toolkit.MouseDownEvent) {
		    Toolkit.MouseDownEvent e = (Toolkit.MouseDownEvent)ev;
		    int btn = buttonid(e.button());
		    if(btn > 0) {
			java.awt.event.MouseEvent awt = AWTCompat.mkawt(e);
			boolean took;
			synchronized(layer) {took = layer.mousedown(awt, e.wndc(), btn);}
			if(!took) {
			    /* addon: (074.1) the press went to the session, so the layer above it gives up
			     * the focus -- otherwise one click on an addon window keeps Escape and Tab in
			     * the layer for the rest of the client's life. */
			    synchronized(layer) {layer.root.clearfocus();}
			    synchronized(ui) {ui.mousedown(awt, e.wndc(), btn);}
			}
			btnowner[btn] = took ? 1 : 2;
		    }
		} else if(ev instanceof Toolkit.MouseUpEvent) {
		    Toolkit.MouseUpEvent e = (Toolkit.MouseUpEvent)ev;
		    int btn = buttonid(e.button());
		    if(btn > 0) {
			java.awt.event.MouseEvent awt = AWTCompat.mkawt(e);
			int owner = btnowner[btn];
			btnowner[btn] = 0;
			boolean took = false;
			if(owner != 2)
			    synchronized(layer) {took = layer.mouseup(awt, e.wndc(), btn);}
			if(!took && (owner != 1))
			    synchronized(ui) {ui.mouseup(awt, e.wndc(), btn);}
		    }
		} else if(ev instanceof Toolkit.MouseWheelEvent) {
		    Toolkit.MouseWheelEvent e = (Toolkit.MouseWheelEvent)ev;
		    if(e.axis() == Toolkit.MouseWheelEvent.Axis.VERT) {
			java.awt.event.MouseEvent awt = AWTCompat.mkawt(e);
			boolean took;
			synchronized(layer) {took = layer.mousewheel(awt, e.wndc(), e.amount(), e.subamount());}
			if(!took)
			    synchronized(ui) {ui.mousewheel(awt, e.wndc(), e.amount(), e.subamount());}
		    }
		} else if(ev instanceof Toolkit.KeyDownEvent) {
		    java.awt.event.KeyEvent awt = AWTCompat.mkawt((Toolkit.KeyEvent)ev);
		    boolean took;
		    /* addon: WHICH KEYS ARE DOWN (binding:down()). Both edges of every key pass through
		     * here and nowhere else, and a hotkey is an edge, so this is the one place the LEVEL
		     * can be kept. Recorded before the dispatch and for every key, taken or not: a key
		     * held down is held down whichever tree the press went to. */
		    io.brodgar.addon.KeyHeld.down(awt);
		    // addon: (074.1) the layer is offered the FOCUSED key alone -- see UI.keydown(ev, glob)
		    synchronized(layer) {took = layer.keydown(awt, false);}
		    if(!took)
			synchronized(ui) {ui.keydown(awt);}
		    Debug.keyevent(awt);
		} else if(ev instanceof Toolkit.KeyUpEvent) {
		    java.awt.event.KeyEvent awt = AWTCompat.mkawt((Toolkit.KeyEvent)ev);
		    boolean took;
		    io.brodgar.addon.KeyHeld.up(awt);          // addon: the other edge of binding:down()
		    synchronized(layer) {took = layer.keyup(awt);}
		    if(!took)
			synchronized(ui) {ui.keyup(awt);}
		    Debug.keyevent(awt);
		}
		ui.lastevent = Utils.rtime();
	    }
	}
    }

    public static class ClientLoop extends UILoop {
	public final Client cl;

	private ClientLoop(Client cl) {
	    super(cl.wnd);
	    this.cl = cl;
	}

	public UI newui(UI.Runner fun) {
	    UI ui = super.newui(fun);
	    ui.cons.add(cl);
	    return(ui);
	}

	/* rts: (071.1) a game session's UI is built here now rather than by newui, so the client's own
	 * console directory has to be added here too -- otherwise :session, :q, :fs and :sz would exist
	 * only on the login screen, which is exactly the UI nobody is looking at while a session is up. */
	public UI bgui(UI.Runner fun) {
	    UI ui = super.bgui(fun);
	    ui.cons.add(cl);
	    return(ui);
	}

	private AudioSystem.SinkLine audiosink = null;
	protected AudioSystem.SinkLine audiosink() {
	    if(audiosink == null) {
		try {
		    audiosink = AudioSystem.instance().sinkline(Audio.defspec());
		} catch(Unavailable a) {
		    new Warning(a, "could not open an audio sink line").issue();
		    audiosink = DummyAudio.DummySink.instance;
		}
	    }
	    return(audiosink);
	}

	protected void dispatch(UI layer, UI ui) {
	    /* addon: a key held while the window loses focus has its RELEASE delivered to whatever took
	     * the focus, so binding:down() would report it down for ever -- and an addon walking a
	     * character on a held key would walk it away with nothing left to stop it. Alt-tab lets go. */
	    if(!wnd.focused())
		io.brodgar.addon.KeyHeld.clear();
	    cl.queue.dispatch(layer, ui);
	}

	protected boolean bgmode() {
	    Windeye.Visibility v = wnd.visible();
	    if(v == Windeye.Visibility.UNKNOWN)
		return(!wnd.focused());
	    return(v == Windeye.Visibility.NONE);
	}
    }

    private UI newui(UI.Runner fun) {
	return(loop.newui(fun));
    }

    /**
     * The client's own runner chain: a login screen, and then another one.
     *
     * <p>rts: (071.1) it no longer plays the session it logs in. A {@code RemoteUI} reached here is
     * handed to {@code Sessions}, which builds its UI through {@code UILoop.bgui} — replacing nothing,
     * destroying nothing, taking no {@code uilock} — and runs it on a thread of its own, exactly as a
     * session added with a saved token is run. Running it here instead would put it in the slot
     * {@code newui} replaces, which is what used to make the first session different from every other
     * one.
     *
     * <p>This loop then goes back to a {@code Bootstrap} and waits on the login screen, as it does
     * after a logout today — and it must go on <b>never returning</b>: {@code Client.run}'s
     * {@code while(task != null)} ends the client the moment it does, so a session ending would close
     * the program instead of showing the login screen again.
     */
    public class Main implements UI.Runner {
	public UI.Runner run(UI ui) throws InterruptedException {
	    UI.Runner fun = null;
	    while(true) {
		if(fun == null)
		    fun = new Bootstrap();
		if(fun instanceof RemoteUI) {
		    try {
			io.brodgar.session.Sessions.adopt((RemoteUI)fun);
		    } catch(RuntimeException e) {
			/* The session is closed and the account is back on the login screen, which is the
			 * one place a login that did not take can be retried from. */
			new Warning(e, "session: could not take over the session just logged in").issue();
		    }
		    fun = null;
		    continue;
		}
		String t= fun.title();
		if(t == null)
		    wnd.title("Haven & Hearth");
		else
		    wnd.title("Haven & Hearth \u2013 " + t);
		fun = fun.run(newui(fun));
	    }
	}
    }

    private void savewndstate() {
	switch(wnd.state()) {
	case MAXIMIZED:
	    Utils.setprefb("mainwnd/max", true);
	    break;
	case NORMAL:
	    Utils.setprefc("mainwnd/size", wnd.size());
	    Utils.setprefb("mainwnd/max", false);
	    break;
	}
    }

    public void run(UI.Runner task) {
	if(mt != null) throw(new IllegalStateException());
	mt = Thread.currentThread();
	UILoop loop = this.loop = new ClientLoop(this);
	loop.start();
	try {
	    try {
		while(task != null)
		    task = task.run(newui(task));
	    } catch(InterruptedException e) {
	    } finally {
		newui(null);
	    }
	    savewndstate();
	} finally {
	    /* addon: (079.2) what an addon wrote goes to disk BEFORE the loop stops. It fires Disable on a
	     * thread of its own under a wall-clock budget and flushes every session's saved variables, and it
	     * can neither delay this exit nor prevent it -- see AddonRegistry.shutdown. It is here rather than
	     * one line down because a destroyed UI is a session whose per-character scope can no longer be
	     * named, and a flush that cannot name a scope writes nothing and reports success. */
	    io.brodgar.addon.AddonRegistry.shutdown();
	    loop.dispose();
	    this.loop = null;
	    mt = null;
	}
    }

    public void dispose() {
	wnd.dispose();
    }

    private Windeye.State prevfsstate = Windeye.State.NORMAL;
    private Map<String, Console.Command> cmdmap = new TreeMap<String, Console.Command>();
    {
	cmdmap.put("q", (cons, args) -> {
	    mt.interrupt();
	});
	cmdmap.put("fs", (cons, args) -> {
	    if(args.length >= 2) {
		Windeye wnd = Client.this.wnd;
		if(Utils.parsebool(args[1])) {
		    if(wnd.state() != Windeye.State.EXCLUSIVE) {
			prevfsstate = wnd.state();
			wnd.state(Windeye.State.EXCLUSIVE);
		    }
		} else {
		    wnd.state(prevfsstate);
		}
	    }
	});
	// rts: the sessions' one console door (F0, specs/rts/plan.md). There is no UI for this yet, and
	// deliberately so: F0 exists to measure what a second live session costs, not to dress it up.
	cmdmap.put("session", new Console.Command() {
		public void run(Console cons, String[] args) throws Exception {
		    String sub = (args.length > 1) ? args[1] : "list";
		    if(sub.equals("add")) {
			if(args.length < 3)
			    throw(new Exception("usage: session add USER [CHAR]"));
			final String user = args[2];
			/* The rest of the line IS the character name: Haven names have spaces in them
			 * ("Irongeta World 16.1"), and a word-split arg[3] would silently ask for a
			 * character that does not exist. Quoting still works -- it just is not needed. */
			StringBuilder nm = new StringBuilder();
			for(int i = 3; i < args.length; i++) {
			    if(nm.length() > 0)
				nm.append(' ');
			    nm.append(args[i]);
			}
			final String chr = (nm.length() > 0) ? nm.toString() : null;
			io.brodgar.session.Sessions.say("connecting %s%s...", user, (chr == null) ? "" : (" as " + chr));
			/* Off the UI thread: authentication and the session handshake are both blocking
			 * network round-trips, and the client must keep drawing through them. */
			new HackThread(() -> {
				try {
				    io.brodgar.session.Sessions.add(user, chr);
				    io.brodgar.session.Sessions.say("%s connected", user);
				} catch(Exception e) {
				    io.brodgar.session.Sessions.say("%s failed: %s", user, e.getMessage());
				}
			    }, "session-connect").start();
		    } else if(sub.equals("drop")) {
			if(args.length < 3)
			    throw(new Exception("usage: session drop USER|all"));
			if(args[2].equals("all")) {
			    io.brodgar.session.Sessions.dropall();
			    io.brodgar.session.Sessions.say("dropped all");
			} else if(io.brodgar.session.Sessions.drop(args[2])) {
			    io.brodgar.session.Sessions.say("dropped %s", args[2]);
			} else {
			    io.brodgar.session.Sessions.say("no such session: %s", args[2]);
			}
		    } else if(sub.equals("list")) {
			List<io.brodgar.session.Sessions.Member> ms = io.brodgar.session.Sessions.members();
			if(ms.isEmpty())
			    io.brodgar.session.Sessions.say("empty");
			for(io.brodgar.session.Sessions.Member m : ms)
			    io.brodgar.session.Sessions.say("%s", m.status());
		    } else if(sub.equals("where")) {
			/* rts: (109.1) where each session actually is: its base in the map database, whether
			 * that base has been proved, and what the anchoring rests on. One line per member and
			 * the anchor's own among them -- the anchor's base is one half of every difference
			 * between two frames, so a report that left it out would leave out the one number the
			 * others are measured against. */
			List<io.brodgar.session.Sessions.Member> ws = io.brodgar.session.Sessions.members();
			if(ws.isEmpty())
			    io.brodgar.session.Sessions.say("empty");
			for(io.brodgar.session.Sessions.Member m : ws)
			    io.brodgar.session.Sessions.say("%s", m.where());
		    } else if(sub.equals("anchor")) {
			/* rts: (F5) go to another session -- Control.take, the same one gesture the Alt-click
			 * and the cycle key spell: its screen, its selection alone, its camera. */
			if(args.length < 3)
			    throw(new Exception("usage: session anchor USER"));
			/* rts: (071.3) `main` named the session the client's own runner chain kept, and there
			 * is no such session: every one is named by the account it logged in as. Refused by
			 * name rather than left to fall through the account lookup below, which would answer
			 * "no such session: main" and send the maintainer looking for an account called that. */
			if(args[2].equals("main"))
			    throw(new Exception("session anchor main: name an ACCOUNT instead -- `:session list` says which are live"));
			io.brodgar.session.Sessions.Member am = null;
			for(io.brodgar.session.Sessions.Member m : io.brodgar.session.Sessions.members()) {
			    if(m.user.equals(args[2]))
				am = m;
			}
			if(am == null)
			    io.brodgar.session.Sessions.say("no such session: %s", args[2]);
			else
			    io.brodgar.session.Control.take(am);
		    } else if(sub.equals("users")) {
			List<String> us = io.brodgar.session.Sessions.savedusers();
			    if(us.isEmpty())
				io.brodgar.session.Sessions.say("no saved tokens -- log the account in once on the login screen");
			    for(String u : us)
			    io.brodgar.session.Sessions.say("saved token for %s", u);
		    } else {
			throw(new Exception("usage: session add|drop|list|where|anchor|users"));
		    }
		}
	    });
	cmdmap.put("sz", (cons, args) -> {
	    if(args.length >= 3) {
		Coord sz = Coord.of(Integer.parseInt(args[1]),
				    Integer.parseInt(args[2]));
		Windeye wnd = Client.this.wnd;
		if((args.length >= 4) && args[3].equals("lock")) {
		    Utils.setprefc("mainwnd/locksize", sz);
		    wnd.sizing(new Windeye.Sizing().fixsize(sz));
		} else {
		    Utils.setprefc("mainwnd/locksize", null);
		    wnd.sizing(new Windeye.Sizing().minsize(UI.scale(800, 600)).normsize(sz));
		}
	    }
	});
	cmdmap.put("window", (cons, args) -> {
	    if(!Client.this.tk.sharedenvs())
		throw(new Exception("Toolkit does not support multiple windows."));
	    Client cl = new Client(Client.this.tk);
	    Thread th = new HackThread(() -> {
		try {
		    cl.run(cl.new Main());
		} finally {
		    cl.dispose();
		}
	    }, "Haven alt-window thread");
	    th.start();
	});
    }
    public Map<String, Console.Command> findcmds() {
	return(cmdmap);
    }

    public static final Config.Variable<Boolean> nopreload = Config.Variable.propb("haven.nopreload", false);
    public static void setupres() {
	if(ResCache.global != null)
	    Resource.setcache(ResCache.global);
	if(Resource.resurl.get() != null)
	    Resource.addurl(Resource.resurl.get());
	if(ResCache.global != null) {
	    /*
	    try {
		Resource.loadlist(Resource.remote(), ResCache.global.fetch("tmp/allused"), -10);
	    } catch(IOException e) {}
	    */
	}
	if(!nopreload.get()) {
	    try {
		InputStream pls;
		pls = Resource.class.getResourceAsStream("res-preload");
		if(pls != null)
		    Resource.loadlist(Resource.remote(), pls, -5);
		pls = Resource.class.getResourceAsStream("res-bgload");
		if(pls != null)
		    Resource.loadlist(Resource.remote(), pls, -10);
	    } catch(IOException e) {
		throw(new Error(e));
	    }
	}
    }

    public static class ConnectionError extends RuntimeException {
	public ConnectionError(String mesg) {
	    super(mesg);
	}
    }

    public static Session connect(Object[] args) {
	Session.User acct;
	byte[] cookie;
	NamedSocketAddress gameserv = (Bootstrap.gameserv.get() != null) ?
	    Bootstrap.gameserv.get() :
	    new NamedSocketAddress(Bootstrap.authserv.get().host, Bootstrap.gameport.get());
	if((Bootstrap.authuser.get() != null) && (Bootstrap.authck.get() != null)) {
	    acct = new Session.User(Bootstrap.authuser.get());
	    cookie = Bootstrap.authck.get();
	} else {
	    String username;
	    if(Bootstrap.authuser.get() != null) {
		username = Bootstrap.authuser.get();
	    } else {
		if((username = Utils.getpref("tokenname@" + Bootstrap.authserv.get().host, null)) == null)
		    throw(new ConnectionError("no explicit or saved username for host: " + Bootstrap.authserv.get().host));
	    }
	    String token = Utils.getpref("savedtoken-" + username + "@" + Bootstrap.authserv.get().host, null);
	    if(token == null)
		throw(new ConnectionError("no saved token for user: " + username));
	    try {
		AuthClient cl = new AuthClient(Bootstrap.authserv.get());
		try {
		    try {
			acct = new AuthClient.TokenCred(username, Utils.hex.dec(token)).tryauth(cl);
		    } catch(AuthClient.Credentials.AuthException e) {
			throw(new ConnectionError("authentication with saved token failed"));
		    }
		    cookie = cl.getcookie();
		    List<NamedSocketAddress> hosts = cl.gethosts(gameserv);
		    if(!hosts.isEmpty())
			gameserv = hosts.get(0);
		} finally {
		    cl.close();
		}
	    } catch(IOException e) {
		throw(new RuntimeException(e));
	    }
	}
	try {
	    return(Session.connect(new java.net.InetSocketAddress(java.net.InetAddress.getByName(gameserv.host), gameserv.port), acct, Connection.encrypt.get(), cookie, args));
	} catch(Connection.SessionError e) {
	    throw(new ConnectionError(e.getMessage()));
	} catch(InterruptedException exc) {
	    throw(new RuntimeException(exc));
	} catch(IOException e) {
	    throw(new RuntimeException(e));
	}
    }

    private static void main2(String[] args) {
	Utils.initlocale();
	Config.cmdline(args);
	haven.error.ErrorHandler.setprop("jar.config", Config.confid);
	setupres();
	Client cl = new Client(Toolkit.instance());
	try {
	    UI.Runner main = null;
	    if(Bootstrap.replay.get() != null) {
		try {
		    Transport.Playback player = new Transport.Playback(Files.newBufferedReader(Bootstrap.replay.get(), Utils.utf8));
		    main = new RemoteUI(new Session(player, new Session.User("Playback")));
		    player.start();
		} catch(IOException e) {
		    System.err.println("hafen: " + e.getMessage());
		    System.exit(1);
		}
	    } else if(Bootstrap.servargs.get() != null) {
		try {
		    main = new RemoteUI(connect(Bootstrap.servargs.get()));
		} catch(ConnectionError e) {
		    System.err.println("hafen: " + e.getMessage());
		    System.exit(1);
		}
	    } else {
		main = cl.new Main();
	    }
	    cl.run(main);
	} finally {
	    cl.dispose();
	}
	System.exit(0);
    }

    public static void main(String[] args) {
	/* Set up the error handler as early as humanly possible. */
	ThreadGroup g = new ThreadGroup("Haven main group");
	String ed = Utils.getprop("haven.errorurl", "");
	if(ed.equals("stderr")) {
	    g = new haven.error.SimpleHandler("Haven main group", true);
	} else if(!ed.equals("")) {
	    try {
		final haven.error.ErrorHandler hg = new haven.error.ErrorHandler(new java.net.URI(ed).toURL());
		hg.sethandler(new haven.error.ErrorGui(null) {
			public void errorsent() {
			    hg.interrupt();
			}
		    });
		g = hg;
		new DeadlockWatchdog(hg).start();
	    } catch(java.net.MalformedURLException | java.net.URISyntaxException e) {
	    }
	}
	Thread main = new HackThread(g, () -> main2(args), "Haven main thread");
	main.start();
    }
}
