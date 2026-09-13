package io.brodgar.addon;

import haven.Coord;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.brodgar.addon.AddonManager.logAbout;

/**
 * <b>The store's file</b> (146): one SQLite database per addon, {@code savedata/<id>.sqlite}, for the whole
 * client — every account this client logs in with and every character read and write the same one. What
 * {@link StoreApi} keeps in it is the addon's <b>documents</b> (a declared saved variable, one JSON row each)
 * and its <b>remembered placements</b>; the tables an addon declares for itself and the statements it runs
 * come through here too, on the same connection.
 *
 * <p><b>Opened at {@link StoreApi#installStore}</b>, so a document is readable before {@code Load}, and
 * <b>closed by its own teardown step</b> and by the quit's flush, which is what checkpoints the write-ahead
 * log and drops the {@code -wal}/{@code -shm} sidecars beside the file. Every {@code org.sqlite} and
 * {@code java.sql} type lives in {@link Db}, and nothing outside it names one: a runtime without
 * {@code java.sql} fails to link {@code Db} inside {@link #open}'s own guard and lands in the unavailable
 * state below, rather than taking the addon load with it.
 *
 * <p><b>An open that fails leaves the store unavailable, and the addon loads.</b> The native library that
 * could not be extracted, a file that is not a database, a lock another process holds, a folder that
 * cannot be written: each is one log line naming the file and the cause. The addon's documents are then
 * empty and are never written — what is held is not the file, and writing it back would replace the file
 * with nothing — and every other store verb refuses naming the cause ({@link #require}).
 *
 * <p><b>One connection per file, and every verb on it is serialized</b> — the {@link Db} methods hold its
 * monitor. The message thread can be inside an addon's Lua, so two threads can reach one addon's store, and
 * a SQLite connection is not the place to find that out.
 */
final class SqliteApi {
    private SqliteApi() {}

    /** The shape of the client's own tables, recorded in the file's {@code user_version}. */
    static final int SCHEMA = 1;

    /**
     * How long a statement waits on a lock another connection holds, in milliseconds — a second client
     * playing from the same folder. Past it the statement fails with {@code SQLITE_BUSY}, which the caller
     * logs like any other failed write.
     */
    private static final int BUSY_MS = 3000;

    /** The spelling every message quotes. */
    private static final String ACC = "hafen.store()";

    /**
     * Open {@code a}'s file, or leave its store unavailable. From {@link StoreApi#installStore}, before the
     * documents are read. The file is {@code <id>.sqlite} inside {@link StoreApi#saveDir}, built through
     * {@link Inside} like every other file under {@code savedata/}: the id is a name out of a manifest.
     */
    static void open(Addon a) {
        a.db = null;
        Path file;
        try {
            file = Inside.inside(StoreApi.saveDir().toPath(), a.manifest.id + ".sqlite", "store");
        } catch(RuntimeException e) {
            unavailable(a, a.manifest.id + ".sqlite", Refusal.reason(e));
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            a.db = new Db(file);
            a.dbWhy = null;
        } catch(Exception | LinkageError e) {
            // Exception: the driver's own refusals (SQLITE_CANTOPEN, SQLITE_NOTADB, SQLITE_BUSY) and the
            // folder that could not be made. LinkageError: a runtime without java.sql, or a native library
            // that could not be loaded -- Db is the class that links both, and this is where it is first used.
            unavailable(a, file.getFileName().toString(), why(e));
        }
    }

    /** The one log line of the unavailable state, and the cause every refusing verb then quotes. */
    private static void unavailable(Addon a, String file, String why) {
        a.dbWhy = file + " could not be opened: " + why;
        logAbout(a, "store: " + a.dbWhy + " — this addon's store is unavailable for this session: its documents"
            + " are empty and are not written, and every other store verb refuses. Fix the cause and :reload.");
    }

    /** What a failed open is reported as: the driver's message, prefixed by the class when it is not the driver's. */
    private static String why(Throwable e) {
        String msg = e.getMessage();
        if((msg == null) || msg.isEmpty())
            return e.toString();
        return (e instanceof LinkageError) ? e.getClass().getSimpleName() + ": " + msg : msg;
    }

    /**
     * Close {@code a}'s file — the teardown step after {@code "saved variables"}, and the quit's flush. The
     * close checkpoints the write-ahead log into the file and removes the sidecars; a write after it is a
     * {@link Failure} its caller logs.
     */
    static void close(Addon a) {
        if(a == null)
            return;
        Db db = a.db;
        a.db = null;
        if(db != null)
            db.close();
    }

    /**
     * <b>{@code a}'s open file, or {@code null}</b> — what every read and write of the store goes through.
     * {@code null} is the unavailable state ({@link Addon#dbWhy} says why) or a file the teardown closed.
     *
     * <p><b>An owner that outlives its teardown gets its file back on the next use.</b> The {@code :lua} REPL
     * owner is torn down by every {@code :reload} — its Step walk closes the file like any addon's — and is
     * never rebuilt, so without this its store would be closed for good after the first reload. It is the one
     * owner whose manifest is {@link Manifest#internal()}, and the one for which "closed" means "reopen".
     */
    static Db db(Addon a) {
        Db db = a.db;
        if((db == null) && (a.dbWhy == null) && a.manifest.internal()) {
            synchronized(a) {                       // two threads of the REPL's Lua must not open it twice
                db = a.db;
                if(db == null) {
                    open(a);
                    db = a.db;
                }
            }
        }
        return db;
    }

    /**
     * <b>The file a verb needs</b>, or a {@link LuaError} naming why there is none. {@code call} is the
     * verb as the message quotes it ({@code hafen.store():info()}).
     */
    static Db require(Addon a, String call) {
        Db db = db(a);
        if(db == null)
            throw new LuaError(call + " — this addon's store is unavailable: " + ((a.dbWhy != null) ? a.dbWhy
                : "the file is closed") + ". Its documents are empty and are not written, and nothing here"
                + " answers until the file opens: fix the cause and :reload.");
        return db;
    }

    /** {@code hafen.store():info()} — {@code {file, bytes}}: where the file is, and how big the database is. */
    static LuaValue info(Addon a) {
        Db db = require(a, ACC + ":info()");
        LuaTable t = new LuaTable();
        t.set("file", LuaValue.valueOf(db.file.toString()));
        t.set("bytes", LuaValue.valueOf((double)db.bytes()));
        return t;
    }

    /**
     * <b>A failure out of the driver</b>, unchecked, carrying its message. {@link Db} raises it in place of
     * every {@code SQLException}, so no caller outside {@link Db} names a {@code java.sql} type.
     */
    static final class Failure extends RuntimeException {
        Failure(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /**
     * <b>One addon's open file.</b> The only class here that names an {@code org.sqlite} or {@code java.sql}
     * type; every method holds the monitor, so the connection sees one caller at a time.
     *
     * <p>The two tables of the client's own carry the {@code hafen_} prefix, which is what keeps them apart
     * from anything an addon declares. A <b>scope</b> is a row key: {@code ""} for the addon's own documents
     * and placements, the character's key ({@link StoreApi}'s {@code <genus>_<char>}) for that character's.
     */
    static final class Db {
        /** Where the file is — what {@code :info().file} answers. */
        final Path file;
        private final org.sqlite.SQLiteConnection conn;

        /** Something to run inside one transaction. */
        private interface Work {
            void run() throws java.sql.SQLException;
        }

        Db(Path file) throws java.sql.SQLException {
            org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
            cfg.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);     // readers never wait on a writer
            cfg.setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.NORMAL);  // durable at checkpoint; a crash loses
                                                                                 //   the last transactions, never the file
            cfg.enforceForeignKeys(true);
            cfg.enableLoadExtension(false);                                  // load_extension() is "not authorized"
            cfg.setBusyTimeout(BUSY_MS);
            org.sqlite.SQLiteConnection c = (org.sqlite.SQLiteConnection)cfg.createConnection("jdbc:sqlite:" + file);
            try {
                // No second database can be attached to this connection -- which is also what refuses VACUUM,
                // so the vacuum verb raises it inside its own lock and puts it back.
                c.setLimit(org.sqlite.SQLiteLimits.SQLITE_LIMIT_ATTACHED, 0);
                try(java.sql.Statement st = c.createStatement()) {
                    int have = (int)one(st, "PRAGMA user_version");
                    if(have > SCHEMA)
                        throw new java.sql.SQLException("written by a newer client (schema " + have
                            + ", this client writes " + SCHEMA + ")");
                    st.execute("CREATE TABLE IF NOT EXISTS hafen_documents (scope TEXT NOT NULL, name TEXT NOT NULL,"
                        + " json TEXT NOT NULL, PRIMARY KEY (scope, name)) WITHOUT ROWID");
                    st.execute("CREATE TABLE IF NOT EXISTS hafen_placements (scope TEXT NOT NULL, name TEXT NOT NULL,"
                        + " x INTEGER, y INTEGER, w INTEGER, h INTEGER, PRIMARY KEY (scope, name)) WITHOUT ROWID");
                    if(have < SCHEMA)
                        st.execute("PRAGMA user_version = " + SCHEMA);
                }
            } catch(java.sql.SQLException | RuntimeException e) {
                try {
                    c.close();
                } catch(java.sql.SQLException x) {
                    /* the open already failed; nothing is held */
                }
                throw e;
            }
            this.file = file;
            this.conn = c;
        }

        /** The one number a {@code PRAGMA} answers. */
        private static long one(java.sql.Statement st, String sql) throws java.sql.SQLException {
            try(java.sql.ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }

        /** Run {@code w} as one transaction: committed when it returns, rolled back when it throws. */
        private void transaction(Work w) {
            try {
                conn.setAutoCommit(false);
                try {
                    w.run();
                    conn.commit();
                } catch(java.sql.SQLException | RuntimeException e) {
                    try {
                        conn.rollback();
                    } catch(java.sql.SQLException x) {
                        /* the failure below is the one to report */
                    }
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch(java.sql.SQLException e) {
                throw new Failure(e.getMessage(), e);
            }
        }

        // ---- the documents: one JSON row per declared saved variable, keyed by scope and name --------

        /** The JSON of one document, or {@code null} when nothing has been saved under that name. */
        synchronized String document(String scope, String name) {
            try(java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT json FROM hafen_documents WHERE scope = ? AND name = ?")) {
                ps.setString(1, scope);
                ps.setString(2, name);
                try(java.sql.ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } catch(java.sql.SQLException e) {
                throw new Failure(e.getMessage(), e);
            }
        }

        /** Write {@code name → JSON} rows of one scope, all in one transaction, each replacing what it had. */
        synchronized void documents(final String scope, final Map<String, String> rows) {
            transaction(new Work() {
                public void run() throws java.sql.SQLException {
                    try(java.sql.PreparedStatement ps = conn.prepareStatement(
                            "INSERT OR REPLACE INTO hafen_documents (scope, name, json) VALUES (?, ?, ?)")) {
                        for(Map.Entry<String, String> e : rows.entrySet()) {
                            ps.setString(1, scope);
                            ps.setString(2, e.getKey());
                            ps.setString(3, e.getValue());
                            ps.executeUpdate();
                        }
                    }
                }
            });
        }

        // ---- the remembered placements: a row per name, each half nullable -------------------------

        /** Every placement saved under one scope, by name. A row with neither half is not a placement. */
        synchronized Map<String, StoreApi.Placement> placements(String scope) {
            Map<String, StoreApi.Placement> out = new LinkedHashMap<String, StoreApi.Placement>();
            try(java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT name, x, y, w, h FROM hafen_placements WHERE scope = ? ORDER BY name")) {
                ps.setString(1, scope);
                try(java.sql.ResultSet rs = ps.executeQuery()) {
                    while(rs.next()) {
                        StoreApi.Placement p = new StoreApi.Placement();
                        p.pos = coord(rs, 2, 3);
                        p.size = coord(rs, 4, 5);
                        if((p.pos != null) || (p.size != null))
                            out.put(rs.getString(1), p);
                    }
                }
            } catch(java.sql.SQLException e) {
                throw new Failure(e.getMessage(), e);
            }
            return out;
        }

        /** Two integer columns as one half of a placement, or {@code null} when either is {@code NULL}. */
        private static Coord coord(java.sql.ResultSet rs, int xcol, int ycol) throws java.sql.SQLException {
            int x = rs.getInt(xcol);
            if(rs.wasNull())
                return null;
            int y = rs.getInt(ycol);
            return rs.wasNull() ? null : Coord.of(x, y);
        }

        /** Replace one scope's placements with {@code rows}: its rows are deleted and these written, in one transaction. */
        synchronized void placements(final String scope, final Map<String, StoreApi.Placement> rows) {
            transaction(new Work() {
                public void run() throws java.sql.SQLException {
                    try(java.sql.PreparedStatement del = conn.prepareStatement(
                            "DELETE FROM hafen_placements WHERE scope = ?")) {
                        del.setString(1, scope);
                        del.executeUpdate();
                    }
                    try(java.sql.PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO hafen_placements (scope, name, x, y, w, h) VALUES (?, ?, ?, ?, ?, ?)")) {
                        for(Map.Entry<String, StoreApi.Placement> e : rows.entrySet()) {
                            StoreApi.Placement p = e.getValue();
                            if((p.pos == null) && (p.size == null))
                                continue;
                            ps.setString(1, scope);
                            ps.setString(2, e.getKey());
                            half(ps, 3, p.pos);
                            half(ps, 5, p.size);
                            ps.executeUpdate();
                        }
                    }
                }
            });
        }

        /** Bind one half of a placement to two integer parameters, {@code NULL} when the half is not held. */
        private static void half(java.sql.PreparedStatement ps, int at, Coord c) throws java.sql.SQLException {
            if(c == null) {
                ps.setNull(at, java.sql.Types.INTEGER);
                ps.setNull(at + 1, java.sql.Types.INTEGER);
            } else {
                ps.setInt(at, c.x);
                ps.setInt(at + 1, c.y);
            }
        }

        // ---- the file itself --------------------------------------------------------------------

        /**
         * The database's size in bytes — its pages times the page size, which counts what is committed to
         * the write-ahead log as well as the file, and the free pages {@code :vacuum()} would give back.
         */
        synchronized long bytes() {
            try(java.sql.Statement st = conn.createStatement()) {
                return one(st, "PRAGMA page_count") * one(st, "PRAGMA page_size");
            } catch(java.sql.SQLException e) {
                throw new Failure(e.getMessage(), e);
            }
        }

        /** Close the connection: the log is checkpointed into the file and the sidecars go. */
        synchronized void close() {
            try {
                conn.close();
            } catch(java.sql.SQLException e) {
                throw new Failure(e.getMessage(), e);
            }
        }
    }
}
