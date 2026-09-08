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

public class Console {
    private static final Map<String, Command> scommands = new HashMap<String, Command>();
    private final Map<String, Command> commands = new HashMap<String, Command>();
    private final Collection<Directory> dirs = new LinkedList<Directory>();
    /* addon: (audit2 B08, co-01) every command name this process has declared -- static, instance and
     * directory alike. `findcmd` is the only other answer to "is this name taken", and it is an INSTANCE
     * method: an addon claiming a name has no console to ask while the addon layer loads, which is exactly
     * when the claim is made, and the check that guessed one from the drawn session read null and proceeded.
     * A static command installed over the client's own then wins for the life of the process, because
     * `findcmd` reads `scommands` first. This is recorded as each declaration happens, so the answer is the
     * client's whether or not a session holds a console yet. Names only: nothing here dispatches. */
    private static final Set<String> declared = new HashSet<String>();
    private final ThreadLocal<Host> host = new ThreadLocal<>();
    private final ThreadLocal<String> rawtext = new ThreadLocal<>();   // addon: raw command line, quotes intact
    public PrintWriter out;

    {
	clearout();
    }

    public static interface Command {
	public void run(Console cons, String[] args) throws Exception;
    }

    public static interface Directory {
	public Map<String, Command> findcmds();
    }

    public static interface Host {
    }

    public static void setscmd(String name, Command cmd) {
	synchronized(scommands) {
	    scommands.put(name, cmd);
	}
	declare(name);   // addon: (audit2 B08) see `declared`
    }

    /* addon: (audit2 B08, co-01) whether ANY command in this process answers to `name` -- the question a
     * would-be claimant has to ask, answerable with no console in hand. See `declared`. */
    public static boolean declares(String name) {
	synchronized(declared) {
	    return(declared.contains(name));
	}
    }

    /* addon: (audit2 B08) record one declared name. */
    private static void declare(String name) {
	synchronized(declared) {
	    declared.add(name);
	}
    }

    // addon: TAKE a static command back out -- the counterpart setscmd never had. `scommands` is a
    // process-wide map with no removal, so a command installed for something that has since gone on
    // answering: an addon's :name kept a dispatcher for the life of the client after the addon was disabled,
    // replying "no addon handles :name" where the console should have said the word is unknown -- and, for a
    // name the client itself declares later, shadowing it for good. Unknown names are inert.
    public static void unsetscmd(String name) {
	synchronized(scommands) {
	    scommands.remove(name);
	}
	synchronized(declared) {   // addon: (audit2 B08) ...and the name is claimable again
	    declared.remove(name);
	}
    }

    public void setcmd(String name, Command cmd) {
	synchronized(commands) {
	    commands.put(name, cmd);
	}
	declare(name);   // addon: (audit2 B08) see `declared`
    }

    public Command findcmd(String name) {
	Command ret;
	synchronized(scommands) {
	    if((ret = scommands.get(name)) != null)
		return(ret);
	}
	synchronized(commands) {
	    if((ret = commands.get(name)) != null)
		return(ret);
	}
	synchronized(dirs) {
	    for(Directory dir : dirs) {
		if((ret = dir.findcmds().get(name)) != null)
		    return(ret);
	    }
	}
	return(null);
    }

    public void add(Directory dir) {
	synchronized(dirs) {
	    dirs.add(dir);
	}
	/* addon: (audit2 B08) record the names, so `declares` can answer with no console in hand -- see
	 * `declared`. Both guards are real, and neither is hypothetical: `UILoop`'s constructor calls
	 * `newui`, which adds the client's own directory before the subclass field holding it has been
	 * assigned, so `dir` IS null for the login screen's console; and a directory reached that early can
	 * answer null for a map it fills later. A directory with nothing to say declares nothing, which is
	 * the whole of what this has to do about it -- registering it is still upstream's business, and
	 * asking it for names is only ours. */
	Map<String, Command> cmds = (dir == null) ? null : dir.findcmds();
	if(cmds != null) {
	    for(String name : cmds.keySet())
		declare(name);
	}
    }

    public void run(Host host, String[] args) throws Exception {
	if(args.length < 1)
	    return;
	Command cmd = findcmd(args[0]);
	if(cmd == null)
	    throw(new Exception(args[0] + ": no such command"));
	Host ph = this.host.get();
	try {
	    this.host.set(host);
	    cmd.run(this, args);
	} finally {
	    this.host.set(ph);
	}
    }

    public void run(Host host, String cmdl) throws Exception {
	String prev = rawtext.get();
	try {
	    rawtext.set(cmdl);                        // addon: keep the raw line for rawcmd()
	    run(host, Utils.splitwords(cmdl));
	} finally {
	    rawtext.set(prev);
	}
    }

    /* addon: the raw, unsplit command line of the command currently running (quotes intact), or
     * null when invoked with pre-split args. Lets a command (e.g. :lua) read its own literal text. */
    public String rawcmd() {
	return(rawtext.get());
    }

    public Host host() {
	return(host.get());
    }

    public void clearout() {
	out = new PrintWriter(new Writer() {
		public void write(char[] b, int o, int c) {}
		public void close() {}
		public void flush() {}
	    });
    }

    static {
	setscmd("die", (cons, args) -> {
	    throw(new Error("Triggered death"));
	});
	setscmd("sleep", (cons, args) -> {
	    long ms = (long)(Double.parseDouble(args[1]) * 1000);
	    try {
		Thread.sleep(ms);
	    } catch(InterruptedException e) {
		Thread.currentThread().interrupt();
		throw(new RuntimeException(e));
	    }
	});
	setscmd("lockdie", (cons, args) -> {
	    Object m1 = new Object(), m2 = new Object();
	    int[] sync = {0};
	    new HackThread(() -> {
		    try {
			synchronized(m2) {
			    synchronized(sync) {
				while(sync[0] != 1)
				    sync.wait();
				sync[0] = 2;
				sync.notifyAll();
			    }
			    synchronized(m1) {
				synchronized(sync) {
				    sync[0] = 3;
				    sync.notifyAll();
				}
			    }
			}
		    } catch(InterruptedException e) {}
	    }, "Deadlocker").start();
	    try {
		synchronized(m1) {
		    synchronized(sync) {
			sync[0] = 1;
			sync.notifyAll();
			while(sync[0] != 2)
			    sync.wait();
		    }
		    synchronized(m2) {
			synchronized(sync) {
			    sync[0] = 3;
			    sync.notifyAll();
			}
		    }
		}
	    } catch(InterruptedException e) {}
	});
	setscmd("threads", (cons, args) -> {
	    Utils.dumptg(null, cons.out);
	});
	setscmd("gc", (cons, args) -> {
	    System.gc();
	});
    }
}
