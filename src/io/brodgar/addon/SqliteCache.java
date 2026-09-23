package io.brodgar.addon;

import haven.Config;
import haven.Console;
import haven.HashDirCache;
import haven.ResCache;
import haven.Warning;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * <b>The SQLite store</b>: a {@link ResCache} over one SQLite file, one blob per entry. Two instances exist
 * when the client runs with {@code -Dhaven.store=sqlite} — {@code savedata/map.sqlite} behind
 * {@code ResCache.global} (the recorded map, the minimap icon settings) and {@code savedata/rescache.sqlite}
 * behind {@code Resource.setcache} (the resource cache) — and none otherwise, when {@code HashDirCache} under
 * {@code %APPDATA%} stays what it was. Each store keeps its own data: the client imports nothing either way;
 * the launcher's {@code DataMigrator} copies the map and the minimap icons of the known worlds into
 * {@code map.sqlite} once, when its Options switch to SQLite.
 *
 * <p><b>The switch is here.</b> {@link #global()} and {@link #resources()} answer the two seams in
 * {@code haven} ({@code ResCache.StupidJavaCodeContainer.makeglobal}, {@code Client.setupres}), and
 * {@link #sqlite()} the third: {@code GameUI} puts the map in {@code HashDirCache.get(MapFile.mapbase)} when
 * {@code haven.mapbase} is set — every shipped {@code haven-config.properties} sets it — and in sqlite mode
 * that variable means nothing, so the map stays {@code ResCache.global}'s. The class loads in both modes and
 * its static block registers {@code :store} in both — the command that prints the store in force through
 * {@code cons.out}, which {@code GameUI.added} re-points at the System log.
 *
 * <p><b>One writer, a few readers.</b> Every write — a {@link #store} stream's close, the sweep's deletes —
 * goes through one connection under the instance's monitor, one transaction each. A read never touches it:
 * {@link #fetch} borrows one of up to {@value #READERS} reader connections of the file's own, runs its query
 * and hands it back, and waits only while all of them are out. In WAL mode a reader blocks neither another
 * reader nor the writer, and each query is its own read transaction, opened after any put's commit — so a
 * grid one thread has just saved is what the next fetch on any thread reads. The readers open on a daemon
 * thread as the store is made, never inside the pool's lock, and a reader that fails is dropped. Why a pool
 * and not the writer: the map's loaders are the {@code Defer} pool, a thread per core, and a fetch queued
 * behind them on one monitor costs hundreds of microseconds with tails of tens of milliseconds where the
 * query itself costs ten. Why six and not one per thread: past about a dozen connections reading at once
 * the log's shared-memory locks contend — on Windows they are {@code LockFileEx} calls on the {@code -shm},
 * backed off with {@code Sleep} — and the same read costs tens of milliseconds; a wait for a free reader
 * costs microseconds. A {@code ResultSet} is never held across a return: the bytes are copied out and a
 * stream over the copy handed back, which keeps the log checkpointable. A {@link #store} stream buffers in
 * memory and writes on its (idempotent) {@code close()}, so a stream never closed writes nothing —
 * {@code StreamTee.setncwe} closes the fork only after EOF, and an aborted download leaves no entry. A
 * zero-length blob (the segment tombstone {@code MapFile.segments} writes) is stored and read back as zero
 * bytes, never as a miss. A miss is {@link FileNotFoundException}, and only a miss.
 *
 * <p><b>One table per world.</b> {@code MapFile} keys its rows under {@code GameUI.mapfilename()}, which puts
 * the server's genus — the world — first: {@code map/<genus>/grid-…}. A row whose name has that shape lives in
 * the table {@code map-<genus>}, made by the writer the first time the world is seen; every other row
 * ({@code res/}, {@code data/}, a {@code map/} key with no genus) lives in {@code entries}. The tables are alike
 * and the name stays whole, so a world's map is one table to dump, restore or drop with any SQLite tool, and
 * nothing else changes: a fetch routes by the same rule and answers a miss for a world without a table before
 * asking. The layout is the file's {@code user_version}: a file at another one, older or newer, is refused —
 * nothing is released, so nothing converts.
 *
 * <p><b>Unavailable.</b> A file that cannot be opened (no driver or {@code java.sql}, an unwritable folder, a
 * file at another schema) is one {@link Warning} naming the file and the reason, and a {@code null} store:
 * the client runs without it, as it does when {@code HashDirCache.create()} answers {@code null}. A shutdown
 * hook closes the readers and then the writer, because the client leaves through {@code System.exit} and
 * nothing else would checkpoint the log and remove the {@code -wal}/{@code -shm} sidecars — the last
 * connection to close does that, and a reader still borrowed at that moment closes on its release, after
 * which the next open recovers the log the way the engine always does.
 *
 * <p><b>The cache follows the pack.</b> {@code brodgar-res.jar} answers before the cache, so a {@code res/} row
 * the pack holds at the same or a newer version is dead weight. {@link #sweep()} drops those: it reads every
 * {@code res/} row's id, name and the two bytes after the 16-byte {@code "Haven Resource 1"} signature — the
 * little-endian {@code uint16} version, never the blob — probes the pack per row the way {@code JarSource.get}
 * does, and deletes the row when the pack's version is ≥ the row's. A daemon thread runs it from
 * {@link #resources()} in sqlite mode, {@code :store sweep} runs it now, and {@code :store} prints the last
 * run's counts. Files mode never sweeps: enumerating the folder is minutes.
 */
public final class SqliteCache implements ResCache {
    /** The file's shape, recorded in its {@code user_version}: 0 is a new file, this is the one written, and any
     *  other — older or newer — is refused, naming it. (1 kept every row in {@code entries}.) */
    static final int SCHEMA = 2;

    /** The columns every table has. */
    private static final String COLUMNS = " (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, data BLOB NOT NULL,"
        + " mtime INTEGER NOT NULL)";

    /** The two files, inside {@link ClientDb#dir()}. */
    static final String MAP = "map.sqlite", RES = "rescache.sqlite";

    /** How long a statement waits on a lock another connection holds — a second client on the same folder. */
    private static final int BUSY_MS = 3000;

    /** {@code -Dhaven.store}: {@code files} (the default, and the value when absent) or {@code sqlite}. */
    public static final Config.Variable<String> mode = Config.Variable.prop("haven.store", "files");

    /** {@link #mode} resolved once: {@code null} until asked. Guarded by the class monitor. */
    private static Boolean sqlite;

    /** What each of the two seams opened, or why it could not. Guarded by the class monitor. */
    private static final Slot map = new Slot(MAP), res = new Slot(RES);

    private static final class Slot {
        final String name;
        SqliteCache cache;
        String why;
        boolean tried;

        Slot(String name) {
            this.name = name;
        }
    }

    /** The last finished sweep's counts, {@code -1} while none has finished; whether one runs now. Guarded by
     *  the class monitor; {@link #SWEEP} serialises the runs themselves. */
    private static int examined = -1, dropped = -1;
    private static boolean sweeping = false;
    private static final Object SWEEP = new Object();

    static {
        // :store        print the store in force -- one line per file in sqlite mode, the folder in files mode
        // :store sweep  drop every res/ entry the pack holds at the same or a newer version, then print
        Console.setscmd("store", (cons, args) -> {
            if(args.length > 1) {
                if(!"sweep".equals(args[1])) {
                    line(cons.out, "store: no such argument '" + args[1] + "' — :store, or :store sweep");
                    cons.out.flush();
                    return;
                }
                if(!sqlite())
                    line(cons.out, "sweep: files mode never sweeps");
                else
                    sweep();
            }
            report(cons.out);
        });
    }

    // ---- the switch --------------------------------------------------------------------------------

    /** Whether {@link #mode} selects this class. A value that is neither store warns once and means files. */
    public static synchronized boolean sqlite() {
        if(sqlite == null) {
            String v = mode.get();
            if("sqlite".equals(v)) {
                sqlite = Boolean.TRUE;
            } else {
                if(!"files".equals(v))
                    new Warning("haven.store=" + v + " names no store: the accepted values are files (the default)"
                        + " and sqlite; running with files").level(Warning.ERROR).issue();
                sqlite = Boolean.FALSE;
            }
        }
        return sqlite.booleanValue();
    }

    /** What {@code ResCache.global} is: {@code map.sqlite} in sqlite mode, else {@code HashDirCache.create()}. */
    public static ResCache global() {
        if(!sqlite())
            return HashDirCache.create();
        return open(map);
    }

    /** What {@code Resource.setcache} gets: {@code rescache.sqlite} in sqlite mode, else {@code ResCache.global}. */
    public static ResCache resources() {
        if(!sqlite())
            return ResCache.global;
        SqliteCache cache = open(res);
        if(cache != null) {
            Thread t = new Thread(SqliteCache::sweep, "rescache-sweep");
            t.setDaemon(true);
            t.start();
        }
        return cache;
    }

    private static synchronized SqliteCache open(Slot slot) {
        if(!slot.tried) {
            slot.tried = true;
            Path file = ClientDb.dir().toPath().resolve(slot.name);
            try {
                Files.createDirectories(file.getParent());
                slot.cache = new SqliteCache(file);
            } catch(Exception | LinkageError e) {
                // Exception: the driver's own refusals (SQLITE_CANTOPEN, SQLITE_NOTADB, SQLITE_BUSY, a newer
                // schema) and the folder that could not be made. LinkageError: a runtime without java.sql, or
                // a native library that could not be loaded -- the constructor is what links both.
                slot.why = why(e);
                new Warning(e, file + " could not be opened: " + slot.why
                    + " — the client runs without this store for the session").level(Warning.ERROR).issue();
            }
        }
        return slot.cache;
    }

    /** What a failure is reported as: the driver's message, prefixed by the class when it is not the driver's. */
    private static String why(Throwable e) {
        String msg = e.getMessage();
        if((msg == null) || msg.isEmpty())
            return e.toString();
        return (e instanceof LinkageError) ? e.getClass().getSimpleName() + ": " + msg : msg;
    }

    // ---- the instance ------------------------------------------------------------------------------

    /** Where the file is. */
    public final Path file;

    /** The writer: the one connection that writes, or {@code null} once the shutdown hook has closed it.
     *  Guarded by the instance monitor. */
    private java.sql.Connection conn;
    private final java.util.Map<String, java.sql.PreparedStatement> storeSts = new java.util.HashMap<>();

    /** The tables the file has, by name. The writer adds one as it creates it; a reader consults it before
     *  asking, so a name whose table does not exist is a miss without a query. */
    private final java.util.Set<String> tables = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** How many reader connections a file keeps: enough for the loaders, few enough that the log's locks
     *  never contend (the class comment has the measured reasons). */
    static final int READERS = 6;

    /** The readers not in use. {@link #opened} counts every reader alive, borrowed or not, and {@link #closing}
     *  is set once by the shutdown hook. All three are guarded by {@code free}'s monitor. */
    private final java.util.ArrayDeque<Reader> free = new java.util.ArrayDeque<>();
    private int opened = 0;
    private boolean closing = false;

    /**
     * Open (or create) {@code file} as the writer, with the recipe of {@link #connect}: {@code user_version}
     * checked (a new file is stamped, any other version refused), {@code entries} made. {@code WITHOUT ROWID} on the name would put a 1.7 KB blob into the
     * index B-tree's cell and read 5× slower, so the key is a rowid and the name unique. The readers start
     * opening on their own thread once the writer is up.
     */
    private SqliteCache(Path file) throws java.sql.SQLException {
        this.file = file;
        java.sql.Connection c = connect(file);
        try {
            try(java.sql.Statement st = c.createStatement()) {
                int have = (int)one(st, "PRAGMA user_version");
                if(have > SCHEMA)
                    throw new java.sql.SQLException("written by a newer client (schema " + have
                        + ", this client writes " + SCHEMA + ")");
                if((have != 0) && (have < SCHEMA))
                    throw new java.sql.SQLException("written by an older client (schema " + have
                        + ", this client writes " + SCHEMA + "); nothing converts it: delete it, or convert it yourself");
                st.execute("CREATE TABLE IF NOT EXISTS entries" + COLUMNS);
                if(have == 0)
                    st.execute("PRAGMA user_version = " + SCHEMA);
                try(java.sql.ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'"
                    + " AND (name = 'entries' OR name GLOB 'map-*')")) {
                    while(rs.next())
                        tables.add(rs.getString(1));
                }
            }
        } catch(java.sql.SQLException | RuntimeException e) {
            try {
                c.close();
            } catch(java.sql.SQLException x) {
                /* the open already failed; nothing is held */
            }
            throw e;
        }
        this.conn = c;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, file.getFileName().toString()));
        } catch(IllegalStateException e) {
            /* already shutting down: the next open recovers the log the way the engine always does */
        }
        prewarm();
    }

    /** The one recipe every connection to a file follows, {@code ClientDb.Conn}'s: WAL, {@code synchronous=NORMAL},
     *  the busy timeout. */
    private static java.sql.Connection connect(Path file) throws java.sql.SQLException {
        org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
        cfg.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
        cfg.setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.NORMAL);
        cfg.setBusyTimeout(BUSY_MS);
        return cfg.createConnection("jdbc:sqlite:" + file);
    }

    /** The table {@code name} lives in: {@code map-<genus>} for {@code map/<genus>/…}, else {@code entries}. */
    static String table(String name) {
        if(name.startsWith("map/")) {
            int end = name.indexOf('/', 4);
            if(end > 4)
                return "map-" + name.substring(4, end);
        }
        return "entries";
    }

    /** The table as SQL names it: quoted, since a genus is the server's string. */
    private static String quote(String table) {
        return "\"" + table.replace("\"", "\"\"") + "\"";
    }

    /** The one number a {@code PRAGMA} answers. */
    private static long one(java.sql.Statement st, String sql) throws java.sql.SQLException {
        try(java.sql.ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** The shutdown hook: the idle readers, then the writer. The last connection to close checkpoints the log
     *  into the file and removes the sidecars; a reader out on loan closes on its release. */
    private void close() {
        java.util.List<Reader> idle;
        synchronized(free) {
            closing = true;
            idle = new java.util.ArrayList<>(free);
            opened -= free.size();
            free.clear();
            free.notifyAll();
        }
        for(Reader r : idle)
            r.close();
        synchronized(this) {
            java.sql.Connection c = conn;
            conn = null;
            if(c != null) {
                try {
                    c.close();
                } catch(Exception e) {
                    /* the process is ending; the next open recovers whatever the close left */
                }
            }
        }
    }

    // ---- the readers -------------------------------------------------------------------------------

    /** One reader: a connection of its own and a fetch statement per table, prepared as first asked. Used by
     *  one thread at a time. */
    private static final class Reader {
        final java.sql.Connection conn;
        private final java.util.Map<String, java.sql.PreparedStatement> fetch = new java.util.HashMap<>();

        Reader(java.sql.Connection conn) {
            this.conn = conn;
        }

        java.sql.PreparedStatement fetch(String table) throws java.sql.SQLException {
            java.sql.PreparedStatement st = fetch.get(table);
            if(st == null)
                fetch.put(table, st = conn.prepareStatement("SELECT data FROM " + quote(table) + " WHERE name = ?"));
            return st;
        }

        void close() {
            try {
                conn.close();
            } catch(Exception e) {
                /* dropped, or the process is ending; nothing is held */
            }
        }
    }

    /**
     * A reader for one query: a free one, else a new one while fewer than {@link #READERS} exist — opened
     * outside the monitor, since an open takes milliseconds and every other borrow and release would wait
     * behind it — else the next one released. An {@link IOException} once the shutdown hook has run.
     */
    private Reader borrow() throws IOException {
        synchronized(free) {
            while(true) {
                if(closing)
                    throw new IOException(file + " is closed");
                Reader r = free.poll();
                if(r != null)
                    return r;
                if(opened < READERS) {
                    opened++;   // the slot is taken now; the open itself runs without the monitor
                    break;
                }
                try {
                    free.wait();
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException(file + ": interrupted waiting for a reader");
                }
            }
        }
        try {
            return new Reader(connect(file));
        } catch(java.sql.SQLException e) {
            synchronized(free) {
                opened--;
                free.notify();
            }
            throw new IOException(file + ": no reader: " + why(e), e);
        }
    }

    /** Hand a reader back — or close it, when it failed or the store is closing. */
    private void release(Reader r, boolean ok) {
        synchronized(free) {
            if(ok && !closing) {
                free.push(r);
                free.notify();
                return;
            }
            opened--;
        }
        r.close();
    }

    /** Open the readers now, on a daemon thread, so that no fetch of the first burst has to: an open costs
     *  milliseconds, and many times that inside the burst. A fetch that comes first opens its own; the thread
     *  stops at the cap either way, and at the first open that fails. */
    private void prewarm() {
        Thread t = new Thread(() -> {
            while(true) {
                synchronized(free) {
                    if(closing || (opened >= READERS))
                        return;
                    opened++;
                }
                Reader r;
                try {
                    r = new Reader(connect(file));
                } catch(java.sql.SQLException e) {
                    synchronized(free) {
                        opened--;
                        free.notify();
                    }
                    return;
                }
                release(r, true);
            }
        }, file.getFileName() + "-readers");
        t.setDaemon(true);
        t.start();
    }

    // ---- reading and writing -----------------------------------------------------------------------

    /**
     * The entry's bytes, as a stream over a copy. A miss is {@link FileNotFoundException}: what
     * {@code CacheSource}, {@code Pool.handle}, {@code GobIcon.Settings.load} and {@code MapFile}'s callers key
     * on. Any other failure is an {@link IOException} that is not one.
     */
    public InputStream fetch(String name) throws IOException {
        byte[] data;
        String table = table(name);
        if(!tables.contains(table))
            throw new FileNotFoundException(name);
        Reader r = borrow();
        boolean ok = false;
        try {
            java.sql.PreparedStatement st = r.fetch(table);
            st.setString(1, name);
            try(java.sql.ResultSet rs = st.executeQuery()) {
                if(!rs.next()) {
                    ok = true;   // a miss is an answer; the reader is fine
                    throw new FileNotFoundException(name);
                }
                data = rs.getBytes(1);
                // data is NOT NULL, so a null here is the driver's spelling of a zero-length blob
                if(data == null)
                    data = new byte[0];
            }
            ok = true;
        } catch(java.sql.SQLException e) {
            throw new IOException(file + ": " + name + ": " + why(e), e);
        } finally {
            release(r, ok);
        }
        return new ByteArrayInputStream(data);
    }

    /**
     * A stream that buffers in memory and upserts the entry on {@code close()}, once. Nothing is written
     * until then, and nothing at all for a stream never closed.
     */
    public OutputStream store(final String name) throws IOException {
        synchronized(this) {
            if(conn == null)
                throw new IOException(file + " is closed");
        }
        return new ByteArrayOutputStream() {
            private boolean closed = false;

            public void close() throws IOException {
                if(closed)
                    return;
                closed = true;
                put(name, toByteArray());
            }
        };
    }

    /** UPSERT one entry, into its table — made now when the world is new. Silently nothing once the shutdown
     *  hook has closed the file: the process is leaving. */
    private synchronized void put(String name, byte[] data) throws IOException {
        if(conn == null)
            return;
        try {
            String table = table(name);
            java.sql.PreparedStatement st = storeSts.get(table);
            if(st == null) {
                if(!tables.contains(table)) {
                    try(java.sql.Statement ddl = conn.createStatement()) {
                        ddl.execute("CREATE TABLE IF NOT EXISTS " + quote(table) + COLUMNS);
                    }
                    tables.add(table);   // after the commit: a reader that sees it will find it
                }
                st = conn.prepareStatement("INSERT INTO " + quote(table) + " (name, data, mtime) VALUES (?, ?, ?)"
                    + " ON CONFLICT (name) DO UPDATE SET data = excluded.data, mtime = excluded.mtime");
                storeSts.put(table, st);
            }
            st.setString(1, name);
            st.setBytes(2, data);
            st.setLong(3, System.currentTimeMillis());
            st.executeUpdate();
        } catch(java.sql.SQLException e) {
            throw new IOException(file + ": " + name + ": " + why(e), e);
        }
    }

    /** How many entries the file holds over all its tables, or -1 once closed. */
    synchronized long count() {
        java.util.Map<String, Long> counts = counts();
        if(counts == null)
            return -1;
        long n = 0;
        for(Long c : counts.values())
            n += c;
        return n;
    }

    /** The entry count of every table, by name and in name order, or {@code null} once closed. */
    synchronized java.util.Map<String, Long> counts() {
        if(conn == null)
            return null;
        java.util.Map<String, Long> ret = new java.util.TreeMap<>();
        try(java.sql.Statement st = conn.createStatement()) {
            for(String table : tables)
                ret.put(table, one(st, "SELECT count(*) FROM " + quote(table)));
        } catch(java.sql.SQLException e) {
            return null;
        }
        return ret;
    }

    /** The database's size in bytes as this connection sees it (pages × page size), or -1 once closed. */
    synchronized long size() {
        if(conn == null)
            return -1;
        try(java.sql.Statement st = conn.createStatement()) {
            return one(st, "PRAGMA page_count") * one(st, "PRAGMA page_size");
        } catch(java.sql.SQLException e) {
            return -1;
        }
    }

    /** What {@code CacheSource.cachedesc} puts in every {@code LoadException}. */
    public String toString() {
        return "SqliteCache(" + file + ")";
    }

    // ---- the sweep ---------------------------------------------------------------------------------

    /** The signature every resource blob opens with; the version is the little-endian {@code uint16} after it. */
    private static final byte[] SIG = "Haven Resource 1".getBytes(haven.Utils.ascii);

    /** One {@code res/} row as the sweep reads it: the id, the name and the version bytes, never the blob. */
    private static final class Row {
        final long id;
        final String name;
        final byte[] ver;

        Row(long id, String name, byte[] ver) {
            this.id = id;
            this.name = name;
            this.ver = ver;
        }
    }

    /**
     * Drop every {@code res/} entry of the resource store that the pack holds at the same or a newer version.
     * Runs are serialised on {@link #SWEEP}; the read runs on a reader and the deletes hold the instance
     * monitor, never across the pack probes, so the loaders keep fetching meanwhile. Nothing to do without the store, or
     * in files mode. One stderr line per run; the counts are {@code :store}'s {@code sweep:} line.
     */
    static void sweep() {
        SqliteCache cache;
        synchronized(SqliteCache.class) {
            cache = sqlite() ? res.cache : null;
        }
        if(cache == null)
            return;
        synchronized(SWEEP) {
            synchronized(SqliteCache.class) {
                sweeping = true;
            }
            long start = System.currentTimeMillis();
            int n = 0, m = 0;
            try {
                java.util.List<Row> rows = cache.resRows();
                n = rows.size();
                java.util.List<Row> dead = new java.util.ArrayList<>();
                for(Row row : rows) {
                    int have = version(row.ver);
                    if((have >= 0) && (packVersion(row.name) >= have))
                        dead.add(row);
                }
                m = cache.drop(dead);
                System.err.println(cache.file.getFileName() + ": sweep examined " + n + " res/ entries, dropped "
                    + m + " the pack holds (" + (System.currentTimeMillis() - start) + " ms)");
            } catch(IOException e) {
                new Warning(e, cache.file + ": the sweep failed: " + why(e)).issue();
            } finally {
                synchronized(SqliteCache.class) {
                    examined = n;
                    dropped = m;
                    sweeping = false;
                }
            }
        }
    }

    /** The little-endian {@code uint16} in two bytes, or {@code -1} for fewer — a blob no resource parses. */
    private static int version(byte[] ver) {
        if((ver == null) || (ver.length < 2))
            return -1;
        return (ver[0] & 0xff) | ((ver[1] & 0xff) << 8);
    }

    /**
     * The pack's version of {@code name} ({@code res/} stripped): the same lookup as {@code JarSource.get}
     * over {@code brodgar-res}, reading the signature and the two bytes after it. {@code -1} when the pack
     * lacks the name, is not on the classpath, or the entry is not a resource.
     */
    private static int packVersion(String name) {
        if(!name.startsWith("res/"))
            return -1;
        try(InputStream in = haven.Resource.class.getResourceAsStream("/brodgar-res/" + name.substring(4) + ".res")) {
            if(in == null)
                return -1;
            byte[] head = new byte[SIG.length + 2];
            int got = 0;
            while(got < head.length) {
                int r = in.read(head, got, head.length - got);
                if(r < 0)
                    break;
                got += r;
            }
            if(got < head.length)
                return -1;
            for(int i = 0; i < SIG.length; i++) {
                if(head[i] != SIG[i])
                    return -1;
            }
            return version(new byte[] {head[SIG.length], head[SIG.length + 1]});
        } catch(IOException e) {
            return -1;
        }
    }

    /** Every {@code res/} row's id, name and version bytes, copied out on a reader: the writer keeps taking
     *  the tee's puts meanwhile. */
    private java.util.List<Row> resRows() throws IOException {
        java.util.List<Row> rows = new java.util.ArrayList<>();
        Reader r = borrow();
        boolean ok = false;
        try(java.sql.Statement st = r.conn.createStatement();
            java.sql.ResultSet rs = st.executeQuery("SELECT id, name, substr(data, " + (SIG.length + 1)
                + ", 2) FROM entries WHERE name LIKE 'res/%'")) {
            while(rs.next())
                rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getBytes(3)));
            ok = true;
        } catch(java.sql.SQLException e) {
            throw new IOException(why(e), e);
        } finally {
            release(r, ok);
        }
        return rows;
    }

    /**
     * Delete the rows, each only while it still carries the version bytes the sweep judged: a row the tee
     * rewrote meanwhile holds a version the pack lacked, and stays. One transaction. The count deleted.
     */
    private synchronized int drop(java.util.List<Row> dead) throws IOException {
        if((conn == null) || dead.isEmpty())
            return 0;
        int m = 0;
        try {
            conn.setAutoCommit(false);
            try(java.sql.PreparedStatement st = conn.prepareStatement("DELETE FROM entries WHERE id = ? AND substr(data, "
                + (SIG.length + 1) + ", 2) = ?")) {
                for(Row row : dead) {
                    st.setLong(1, row.id);
                    st.setBytes(2, row.ver);
                    m += st.executeUpdate();
                }
                conn.commit();
            } catch(java.sql.SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch(java.sql.SQLException e) {
            throw new IOException(why(e), e);
        }
        return m;
    }

    // ---- :store ------------------------------------------------------------------------------------

    /**
     * The store in force, one line each: {@code store: sqlite|files}; in sqlite mode {@code map:} and
     * {@code res:} with the path, entry count and size (or {@code not open: <why>}), one {@code map-<genus>:}
     * line per world table between them, and the sweep's state; in files mode {@code data:} with the folder and
     * the {@code HashDirCache} identity.
     */
    static void report(PrintWriter out) {
        if(!sqlite()) {
            line(out, "store: files");
            Path home = Config.localdir();
            ResCache g = ResCache.global;
            java.net.URI mapbase = haven.MapFile.mapbase.get();
            line(out, "data: " + ((home != null) ? home.resolve("data") : "<no local directory>") + " — "
                + ((g instanceof HashDirCache) ? "HashDirCache " + ((HashDirCache)g).id : "not open")
                + ((mapbase != null) ? ", the map in HashDirCache " + mapbase : ""));
            out.flush();
            return;
        }
        line(out, "store: sqlite");
        line(out, "map: " + line(map));
        for(String world : worlds(map))
            line(out, world);
        line(out, "res: " + line(res));
        line(out, "sweep: " + sweepLine());
        out.flush();
    }

    /** {@code none} before a sweep finished ({@code running} while the first is on), else the last one's counts. */
    private static synchronized String sweepLine() {
        if(examined < 0)
            return sweeping ? "running" : "none";
        return "examined " + examined + ", dropped " + dropped;
    }

    /** One line. Never {@code println}: on Windows it ends in CRLF, and the System-log writer of
     *  {@code GameUI.added} splits on LF alone, so the CR would reach every reader of the line. */
    private static void line(PrintWriter out, String text) {
        out.print(text + "\n");
    }

    /** One {@code map-<genus>: <n> entries} line per world table of the slot's file; none without the file. */
    private static synchronized java.util.List<String> worlds(Slot slot) {
        java.util.List<String> ret = new java.util.ArrayList<>();
        java.util.Map<String, Long> counts = (slot.cache == null) ? null : slot.cache.counts();
        if(counts != null) {
            for(java.util.Map.Entry<String, Long> e : counts.entrySet()) {
                if(!e.getKey().equals("entries"))
                    ret.add(e.getKey() + ": " + e.getValue() + " entries");
            }
        }
        return ret;
    }

    private static synchronized String line(Slot slot) {
        if(slot.cache == null)
            return ClientDb.dir().toPath().resolve(slot.name) + " — not open: "
                + ((slot.why != null) ? slot.why : "never opened");
        return slot.cache.file + " — " + slot.cache.count() + " entries, "
            + String.format(java.util.Locale.ROOT, "%.1f MB", slot.cache.size() / 1048576.0);
    }
}
