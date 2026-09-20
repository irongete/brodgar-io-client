package io.brodgar.addon;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.luaj.vm2.Prototype;
import org.luaj.vm2.compiler.LuaC;

/**
 * <b>The part of the addon boot that runs the same on any thread, done on one of its own while the client
 * makes its window.</b> The boot itself ({@link AddonManager#boot}) runs on the UI thread's first frame, and
 * the client's main thread waits for that frame before it can show the login screen; measured at 760–990 ms,
 * of which three quarters were work that touches nothing of the addons': the runtime's classes read out of
 * the jar and defined (`UiApi.installUi` was the sample), LuaJ parsing the addons' files, and the static art
 * of {@code Window} and {@code Button} decoded one picture at a time because the first addon's file body
 * built a button. This thread does those three, and the boot then finds them done.
 *
 * <p><b>Nothing here is the addons' own.</b> (1) The runtime's classes are <i>loaded</i>, never initialised
 * ({@code Class.forName(name, false, …)}): no static initialiser of anyone's runs. (2) A file is compiled to
 * a {@link Prototype} with the very {@link LuaC} the sandbox installs ({@code Sandbox.create}), reentrant and
 * bound to no {@code Globals}; the boot wraps it in a closure over the addon's own environment, which is
 * exactly what {@code Globals.load} did, and only if the file's bytes still hash to what was compiled — a
 * file that changed, and a {@code :reload}, compile as they always did. (3) The client's own widget classes
 * that a class of this layer extends are initialised once the toolkit exists (their static initialisers are
 * pictures, fonts and {@code UI.scale}, which needs it), on a thread that is not the UI thread — as
 * {@code LoginWarmup} already initialises {@code Text} and {@code RichText} beside the main thread building
 * the login screen. What the boot executes, and in what order, is untouched.
 *
 * <p>Started by {@code Client.main2} right after {@code setupres()}; {@link #toolkitReady} from the same
 * method once {@code Toolkit.instance()} has returned. The boot asks {@link #prototype} per file and waits
 * for the compile phase if it is still running — never longer than the compile would have taken on its own
 * thread — and calls {@link #done} when it has loaded, after which nothing is kept. A thread that dies
 * releases every waiter through its {@code finally}; the boot then does everything itself, as before.
 */
public final class BootPrepare {
    private static final CountDownLatch toolkit = new CountDownLatch(1);
    private static final CountDownLatch compiled = new CountDownLatch(1);
    private static final Map<Path, Chunk> chunks = new ConcurrentHashMap<Path, Chunk>();
    private static volatile boolean started = false, over = false;

    private static final class Chunk {
        final byte[] hash;
        final Prototype proto;
        Chunk(byte[] hash, Prototype proto) {
            this.hash = hash;
            this.proto = proto;
        }
    }

    private BootPrepare() {}

    /** Once, from {@code Client.main2}, before the toolkit and the window are made. */
    public static synchronized void start() {
        if(started)
            return;
        started = true;
        Thread t = new haven.HackThread(BootPrepare::run, "Addon boot preparation");
        t.setDaemon(true);
        t.setPriority((Thread.NORM_PRIORITY + Thread.MIN_PRIORITY) / 2);
        t.start();
    }

    /** From {@code Client.main2}, the toolkit just made: the widget classes may be initialised now. */
    public static void toolkitReady() {
        toolkit.countDown();
    }

    /**
     * The prototype compiled for {@code path}, if this thread compiled one and the file's bytes are still
     * {@code bytes}; {@code null} otherwise, and the caller compiles as it always did. Waits for the compile
     * phase while it is still running.
     */
    static Prototype prototype(Path path, byte[] bytes) {
        if(!started || over)
            return null;
        try {
            compiled.await();
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        Chunk c = chunks.remove(path.toAbsolutePath().normalize());
        if((c == null) || !Arrays.equals(c.hash, sha256(bytes)))
            return null;
        return c.proto;
    }

    /** The boot has loaded: whatever was compiled and not asked for is dropped. */
    static void done() {
        over = true;
        chunks.clear();
    }

    private static void run() {
        long t0 = System.currentTimeMillis();
        int nclasses = 0, nfiles = 0, ninit = 0;
        try {
            // The compile first: it is the one thing the boot waits for. The classes are an accelerator
            // the boot never waits on, loaded beside it if it has already begun.
            try {
                nfiles = compileFiles();
            } finally {
                compiled.countDown();
            }
            nclasses = loadClasses();
            toolkit.await();
            ninit = initWidgetClasses();
        } catch(Throwable e) {
            /* One line, not silence: a preparation that gives up is a boot that does the work itself. */
            System.err.println("Addon boot preparation gave up after " + (System.currentTimeMillis() - t0) + " ms: " + e);
        } finally {
            compiled.countDown();
        }
    }

    /* ---- 1. the runtime's classes, loaded and not initialised ---------------------------------------------- */

    private static int loadClasses() {
        int n = 0;
        n += loadClasses(AddonManager.class, "io/brodgar/addon/");
        n += loadClasses(LuaC.class, "org/luaj/vm2/");
        return n;
    }

    /** Every class under {@code prefix} wherever {@code anchor} was loaded from: a jar, or a directory of classes. */
    private static int loadClasses(Class<?> anchor, String prefix) {
        Path src;
        try {
            src = haven.Utils.srcpath(anchor);
        } catch(RuntimeException e) {
            return 0;
        }
        List<String> names = new ArrayList<String>();
        try {
            if(Files.isDirectory(src)) {
                Path root = src.resolve(prefix);
                if(Files.isDirectory(root)) {
                    try(java.util.stream.Stream<Path> walk = Files.walk(root)) {
                        for(Iterator<Path> i = walk.iterator(); i.hasNext();) {
                            String rel = src.relativize(i.next()).toString().replace(File.separatorChar, '/');
                            if(rel.endsWith(".class"))
                                names.add(rel);
                        }
                    }
                }
            } else if(Files.isRegularFile(src)) {
                try(java.util.jar.JarFile jar = new java.util.jar.JarFile(src.toFile())) {
                    for(Enumeration<java.util.jar.JarEntry> e = jar.entries(); e.hasMoreElements();) {
                        String nm = e.nextElement().getName();
                        if(nm.startsWith(prefix) && nm.endsWith(".class"))
                            names.add(nm);
                    }
                }
            }
        } catch(IOException e) {
            return 0;
        }
        ClassLoader cl = anchor.getClassLoader();
        int n = 0;
        for(String nm : names) {
            String cn = nm.substring(0, nm.length() - 6).replace('/', '.');
            try {
                Class.forName(cn, false, cl);
                n++;
            } catch(Throwable e) {
                /* A class that cannot be loaded here cannot be loaded by the boot either; it is its report to make. */
            }
        }
        return n;
    }

    /* ---- 2. every addon's files, compiled ----------------------------------------------------------------- */

    private static int compileFiles() {
        File dir = AddonRegistry.addonDir();
        File[] subs = dir.listFiles(File::isDirectory);
        if(subs == null)
            return 0;
        int n = 0;
        for(File sub : subs) {
            if(!new File(sub, "manifest.json").isFile())
                continue;
            Manifest m;
            try {
                m = Manifest.load(sub.toPath());
            } catch(Exception e) {
                continue;   // the boot reports it
            }
            for(String file : m.files) {
                try {
                    Path fp = Inside.inside(sub.toPath(), file, "manifest 'files'");
                    byte[] bytes = Files.readAllBytes(fp);
                    Prototype p = LuaC.instance.compile(new ByteArrayInputStream(bytes), "@" + m.id + "/" + file);
                    chunks.put(fp.toAbsolutePath().normalize(), new Chunk(sha256(bytes), p));
                    n++;
                } catch(Throwable e) {
                    /* A file that does not compile here does not compile in the boot either, which reports it. */
                }
            }
        }
        return n;
    }

    /* ---- 3. the client's widget classes this layer builds on, initialised ----------------------------------- */

    /**
     * Every {@code haven} class that a {@code Widget} of this package extends — {@code Button} under
     * {@code CtlButton}, {@code Window} under an addon window, and the rest — derived from the classes loaded
     * above, so a builder added later is covered without a list to keep. Only {@code haven}'s: a class of this
     * layer is never initialised here.
     */
    private static int initWidgetClasses() {
        ClassLoader cl = AddonManager.class.getClassLoader();
        Set<Class<?>> todo = new LinkedHashSet<Class<?>>();
        List<String> names = new ArrayList<String>();
        try {
            Path src = haven.Utils.srcpath(AddonManager.class);
            if(Files.isRegularFile(src)) {
                try(java.util.jar.JarFile jar = new java.util.jar.JarFile(src.toFile())) {
                    for(Enumeration<java.util.jar.JarEntry> e = jar.entries(); e.hasMoreElements();) {
                        String nm = e.nextElement().getName();
                        if(nm.startsWith("io/brodgar/addon/") && nm.endsWith(".class"))
                            names.add(nm.substring(0, nm.length() - 6).replace('/', '.'));
                    }
                }
            } else if(Files.isDirectory(src)) {
                Path root = src.resolve("io/brodgar/addon");
                try(java.util.stream.Stream<Path> walk = Files.walk(root)) {
                    for(Iterator<Path> i = walk.iterator(); i.hasNext();) {
                        String rel = src.relativize(i.next()).toString().replace(File.separatorChar, '/');
                        if(rel.endsWith(".class"))
                            names.add(rel.substring(0, rel.length() - 6).replace('/', '.'));
                    }
                }
            }
        } catch(IOException | RuntimeException e) {
            return 0;
        }
        for(String cn : names) {
            try {
                Class<?> c = Class.forName(cn, false, cl);
                if(!haven.Widget.class.isAssignableFrom(c))
                    continue;
                for(Class<?> s = c.getSuperclass(); s != null; s = s.getSuperclass()) {
                    if(s.getName().startsWith("haven.")) {
                        todo.add(s);
                        break;
                    }
                }
            } catch(Throwable e) {
            }
        }
        int n = 0;
        for(Class<?> c : todo) {
            try {
                Class.forName(c.getName(), true, cl);
                n++;
            } catch(Throwable e) {
                /* Its initialiser failing here fails the same way for whoever touches it first; nothing to add. */
            }
        }
        return n;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch(java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
