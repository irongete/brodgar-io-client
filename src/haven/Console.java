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
    private static final Map<String, Command> scommands = new TreeMap<String, Command>();
    private final Map<String, Command> commands = new TreeMap<String, Command>();
    private final Collection<Directory> dirs = new LinkedList<Directory>();
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
    }
    
    public void setcmd(String name, Command cmd) {
	synchronized(commands) {
	    commands.put(name, cmd);
	}
    }
    
    public Map<String, Command> findcmds() {
	Map<String, Command> ret = new TreeMap<String, Command>();
	synchronized(scommands) {
	    ret.putAll(scommands);
	}
	synchronized(commands) {
	    ret.putAll(commands);
	}
	synchronized(dirs) {
	    for(Directory dir : dirs) {
		Map<String, Command> cmds = dir.findcmds();
		ret.putAll(cmds);
	    }
	}
	return(ret);
    }
    
    public void add(Directory dir) {
	synchronized(dirs) {
	    dirs.add(dir);
	}
    }
    
    public Command findcmd(String name) {
	return(findcmds().get(name));
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
}
