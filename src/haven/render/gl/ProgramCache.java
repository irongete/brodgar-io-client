package haven.render.gl;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import haven.Config;

/* addon: THE PROGRAM BINARY CACHE -- a linked shader program, kept on disk in the driver's own binary form and
 * loaded back with glProgramBinary instead of being linked again.
 *
 * Why: measured at login on a GTX 1660 SUPER (NVIDIA 591.86), the GL thread spent 1.0-1.1 s of the first second
 * after the first drawn frame in glLinkProgram -- 96 programs at 10-35 ms each -- and the UI thread waited it
 * out on the frame fence (two stalls of ~300 ms), while every material seen for the first time in play was
 * another 13-17 ms link. The GLSL the client generates is byte-identical from one run to the next (checked
 * over 97 programs), so the driver's own shader cache should have answered and did not; a binary the client
 * keeps for itself does not depend on it. A glProgramBinary of a cached program costs the memcpy, ~0.3 ms.
 *
 * The key is the program's whole input: both sources and the attribute and fragment-output bindings the link
 * is given (GLProgram.cachekey). The folder is the driver's (vendor, version, renderer), so a driver update is a
 * fresh folder and never a rejected binary; a binary the driver does reject -- a corrupt file -- is deleted and
 * the program linked as before. Reads happen where the program is BUILT, on the thread that generated its
 * sources (GLProgram.build), never on the GL thread; a fresh binary is fetched on the GL thread right after the
 * link (a memcpy) and written by a thread of this class's own. Off where the driver reports no binary format
 * (Caps.progbinfmts), where no root was set (setroot -- Client.setupres names savedata/shaders/), or with
 * -Dhaven.progcache=false. A cache: delete the folder and the next login rebuilds it. */
public class ProgramCache {
    public static final Config.Variable<Boolean> enabled = Config.Variable.propb("haven.progcache", true);
    private static final int MAGIC = 0x48504231;   // "HPB1"
    private static volatile Path root = null;
    private static final Map<GLEnvironment, String> dirs = new WeakHashMap<GLEnvironment, String>();
    private static final BlockingQueue<Runnable> writes = new LinkedBlockingQueue<Runnable>();
    private static Thread writer = null;
    private static boolean warned = false;
    /* What a session did: read by nothing yet, kept for the day :stats wants them. */
    public static volatile int hits = 0, misses = 0, rejects = 0;

    public static class Entry {
	public final int format;
	public final ByteBuffer data;   // direct, as the GL bindings require; position 0, limit = length

	Entry(int format, ByteBuffer data) {
	    this.format = format;
	    this.data = data;
	}
    }

    public static void setroot(Path p) {
	root = p;
    }

    public static boolean active(GLEnvironment env) {
	return(enabled.get() && (root != null) && (env.caps.progbinfmts > 0));
    }

    /* One folder per driver: a binary is the driver's own format and version. */
    private static Path dir(GLEnvironment env) {
	String d;
	synchronized(dirs) {
	    d = dirs.get(env);
	    if(d == null) {
		GLEnvironment.Caps c = env.caps;
		d = hex(sha256((c.vendor + "\n" + c.version + "\n" + c.renderer).getBytes(haven.Utils.utf8)), 16);
		dirs.put(env, d);
	    }
	}
	return(root.resolve(d));
    }

    private static Path file(GLEnvironment env, String key) {
	return(dir(env).resolve(key + ".bin"));
    }

    /* The program's whole input, as one name. */
    public static String key(String vsrc, String fsrc, Collection<String> bindings) {
	try {
	    MessageDigest md = MessageDigest.getInstance("SHA-256");
	    md.update(vsrc.getBytes(haven.Utils.utf8));
	    md.update((byte)0);
	    md.update(fsrc.getBytes(haven.Utils.utf8));
	    for(String b : bindings) {
		md.update((byte)0);
		md.update(b.getBytes(haven.Utils.utf8));
	    }
	    return(hex(md.digest(), 64));
	} catch(java.security.NoSuchAlgorithmException e) {
	    throw(new RuntimeException(e));
	}
    }

    /* The cached binary of `key`, or null: absent, unreadable, or not this cache's file. On the building
     * thread. A file that is not what this class writes is left alone and treated as absent. */
    public static Entry load(GLEnvironment env, String key) {
	if(!active(env))
	    return(null);
	Path f = file(env, key);
	try(DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
	    if(in.readInt() != MAGIC)
		return(null);
	    int format = in.readInt();
	    int len = in.readInt();
	    if((len <= 0) || (len > (64 << 20)))
		return(null);
	    byte[] buf = new byte[len];
	    in.readFully(buf);
	    ByteBuffer data = ByteBuffer.allocateDirect(len);
	    data.put(buf);
	    data.rewind();
	    return(new Entry(format, data));
	} catch(NoSuchFileException e) {
	    return(null);
	} catch(IOException e) {
	    return(null);
	}
    }

    /* A binary just fetched from the driver, on the GL thread: copied out here, written by the writer thread. */
    public static void store(GLEnvironment env, String key, int format, ByteBuffer data, int len) {
	if(!active(env))
	    return;
	final byte[] buf = new byte[len];
	data.rewind();
	data.get(buf, 0, len);
	final Path f = file(env, key);
	enqueue(() -> {
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		try {
		    Files.createDirectories(f.getParent());
		    try(DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
			out.writeInt(MAGIC);
			out.writeInt(format);
			out.writeInt(buf.length);
			out.write(buf);
		    }
		    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch(IOException e) {
		    try {Files.deleteIfExists(tmp);} catch(IOException e2) {}
		    warn("could not write " + f + ": " + e);
		}
	    });
    }

    /* A binary the driver would not take: gone, and the program links as it always did. */
    public static void forget(GLEnvironment env, String key) {
	if(!active(env))
	    return;
	final Path f = file(env, key);
	enqueue(() -> {
		try {
		    Files.deleteIfExists(f);
		} catch(IOException e) {
		    warn("could not delete " + f + ": " + e);
		}
	    });
    }

    private static synchronized void enqueue(Runnable r) {
	writes.add(r);
	if(writer == null) {
	    writer = new haven.HackThread(ProgramCache::drain, "Program cache writer");
	    writer.setDaemon(true);
	    writer.setPriority(Thread.MIN_PRIORITY);
	    writer.start();
	}
    }

    private static void drain() {
	try {
	    while(true)
		writes.take().run();
	} catch(InterruptedException e) {
	}
    }

    /* One line, once: a cache that cannot write is a login that links, which is what it always did. */
    private static synchronized void warn(String msg) {
	if(warned)
	    return;
	warned = true;
	System.err.println("Program cache: " + msg);
    }

    private static byte[] sha256(byte[] data) {
	try {
	    return(MessageDigest.getInstance("SHA-256").digest(data));
	} catch(java.security.NoSuchAlgorithmException e) {
	    throw(new RuntimeException(e));
	}
    }

    private static String hex(byte[] b, int chars) {
	StringBuilder buf = new StringBuilder();
	for(int i = 0; (i < b.length) && (buf.length() < chars); i++) {
	    buf.append(Character.forDigit((b[i] >> 4) & 0xf, 16));
	    buf.append(Character.forDigit(b[i] & 0xf, 16));
	}
	return(buf.toString());
    }
}
