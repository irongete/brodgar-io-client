package io.brodgar.addon;

import haven.Coord;
import haven.Utils;
import haven.Warning;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * <b>The client's own file</b>, {@code savedata/client.sqlite}. What the client keeps for itself lives
 * here and nowhere else: its preferences — every {@code Utils.getpref*}/{@code setpref*}, the table
 * {@code prefs} — and the two records it keeps <i>about</i> an addon, where the user put its windows
 * ({@code placements}) and which action-bar slots hold its menu entries ({@code holds}). An addon's own
 * file ({@link SqliteApi.Db}) holds what the addon stores through {@code hafen.store()}, and nothing of
 * the client's: the rule is by owner. No registry node is ever opened ({@link Prefs}). The panel's Remove
 * deletes every row about the addon with its folder ({@link #forget}); nothing else does.
 *
 * <p><b>Opened once and lazily, on the first call from any thread.</b> The earliest caller is a "Haven
 * resource loader" thread inside {@code Client.setupres()} — {@code Resource.Image} → {@code UI.scale} →
 * {@code Utils.getprefd("uiscale")}, before {@code Client.<init>} — so the path logic lives here and
 * initialises no other class of this package: {@link AddonRegistry}'s static steps must not run inside
 * that image decode. One connection, every access under the instance monitor. A shutdown hook closes it,
 * because the client leaves through {@code System.exit} ({@code Client.main2}) and nothing else would
 * checkpoint the write-ahead log and remove the {@code -wal}/{@code -shm} sidecars.
 *
 * <p><b>Unavailable.</b> An open that fails, or a read or write that fails later, issues one {@link Warning}
 * at ERROR naming the file and the cause — never the addon log, which needs a session — and flips the
 * file unavailable for the session: the preferences answer what they hold and keep writes in memory, and
 * nothing overwrites the file. The client keeps running.
 */
public final class ClientDb {
    /** The shape of the client's tables, recorded in the file's {@code user_version}. */
    static final int SCHEMA = 1;

    /** The file's name inside {@link #dir()}. */
    static final String NAME = "client.sqlite";

    /**
     * How long a statement waits on a lock another connection holds, in milliseconds — a second client
     * playing from the same folder. Past it the statement fails, which flips the file unavailable.
     */
    private static final int BUSY_MS = 3000;

    /** The one instance, opened by the first {@link #get()}. Guarded by the class monitor. */
    private static ClientDb instance;

    /** The root node {@link #prefs()} hands out, built once over the instance. Guarded by the class monitor. */
    private static Prefs prefs;

    /** Where the file is. */
    final Path file;

    /** The connection, or {@code null}: the file is unavailable, or the shutdown hook has closed it. */
    private Conn conn;

    private ClientDb(Path file, Conn conn) {
        this.file = file;
        this.conn = conn;
    }

    // ---- the paths ---------------------------------------------------------------------------------

    /**
     * The {@code savedata/} folder beside the client: {@code -Dhaven.savedatadir}; else the parent of
     * {@code -Dhaven.addondir}; else the jar's own sibling, and a bare {@code savedata} where the jar has no
     * location. Every addon's folder and the client's own file live in it.
     */
    static File dir() {
        String override = System.getProperty("haven.savedatadir");
        if((override != null) && !override.isEmpty())
            return new File(override);
        String addons = System.getProperty("haven.addondir");
        if((addons != null) && !addons.isEmpty()) {
            File parent = new File(addons).getParentFile();
            return new File((parent != null) ? parent : new File("."), "savedata");
        }
        try {
            return Utils.srcpath(ClientDb.class).resolveSibling("savedata").toFile();
        } catch(RuntimeException e) {
            return new File("savedata");
        }
    }

    /**
     * The path of {@code name} in the client's folder — the one that holds {@code savedata/}, beside the
     * jar. Path-only: nothing is opened, which is what lets {@code Warning.issue()} name its
     * {@code haven-errors.log} through here without a preference being read.
     */
    public static Path file(String name) {
        Path dir = dir().toPath().toAbsolutePath();
        Path parent = dir.getParent();
        return ((parent != null) ? parent : dir).resolve(name);
    }

    // ---- the open --------------------------------------------------------------------------------

    /** The client's file, opened on the first call. Never {@code null}; unavailable when the open failed. */
    static synchronized ClientDb get() {
        if(instance == null)
            instance = open();
        return instance;
    }

    private static ClientDb open() {
        Path file = dir().toPath().resolve(NAME);
        Conn c = null;
        try {
            Files.createDirectories(file.getParent());
            c = new Conn(file);
        } catch(Exception | LinkageError e) {
            // Exception: the driver's own refusals (SQLITE_CANTOPEN, SQLITE_NOTADB, SQLITE_BUSY, a newer
            // schema) and the folder that could not be made. LinkageError: a runtime without java.sql, or a
            // native library that could not be loaded -- Conn is the class that links both.
            warn(file, "could not be opened", e);
        }
        ClientDb db = new ClientDb(file, c);
        if(c != null) {
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(db::close, "client.sqlite"));
            } catch(IllegalStateException e) {
                /* already shutting down: the next open recovers the log the way the engine always does */
            }
        }
        return db;
    }

    /** Close the connection: the log is checkpointed into the file and the sidecars go. The shutdown hook. */
    private synchronized void close() {
        Conn c = conn;
        conn = null;
        if(c != null) {
            try {
                c.close();
            } catch(Exception e) {
                /* the process is ending; the next open recovers whatever the close left */
            }
        }
    }

    /**
     * A read or write failed: the one warning, and the file is unavailable from here on. The connection is
     * closed so that nothing else reaches the file this session.
     */
    private void fail(String what, Throwable e) {
        Conn c = conn;
        conn = null;
        warn(file, what, e);
        if(c != null) {
            try {
                c.close();
            } catch(Exception x) {
                /* it failed once already; nothing is held */
            }
        }
    }

    /** The one line of the unavailable state: the file, what failed, and the cause. */
    private static void warn(Path file, String what, Throwable e) {
        new Warning(e, file + " " + what + ": " + why(e) + " — what the client keeps there is held in memory"
            + " for this session and not written").level(Warning.ERROR).issue();
    }

    /** What a failure is reported as: the driver's message, prefixed by the class when it is not the driver's. */
    private static String why(Throwable e) {
        String msg = e.getMessage();
        if((msg == null) || msg.isEmpty())
            return e.toString();
        return (e instanceof LinkageError) ? e.getClass().getSimpleName() + ": " + msg : msg;
    }

    // ---- the preferences ---------------------------------------------------------------------------

    /**
     * <b>The root node every {@code Utils.getpref*}/{@code setpref*} goes through</b> — what
     * {@code Utils.prefs()} installs in place of a registry node. Built once, over every row of
     * {@code prefs}; the same object on every call.
     */
    public static synchronized java.util.prefs.Preferences prefs() {
        if(prefs == null) {
            ClientDb db = get();
            prefs = new Prefs(db, db.readPrefs());
        }
        return prefs;
    }

    /** Every row of {@code prefs}, as the map the node holds — empty when the file is unavailable. */
    private synchronized Map<String, String> readPrefs() {
        Map<String, String> out = new ConcurrentHashMap<String, String>();
        if(conn == null)
            return out;
        try {
            conn.prefs(out);
        } catch(Exception | LinkageError e) {
            fail("could not be read", e);
            out.clear();
        }
        return out;
    }

    /** The file is open: neither unavailable nor closed by the shutdown hook. */
    private synchronized boolean available() {
        return conn != null;
    }

    /** UPSERT one preference; nothing while the file is unavailable or closed. Never throws. */
    private synchronized void put(String key, String value) {
        if(conn == null)
            return;
        try {
            conn.put(key, value);
        } catch(Exception | LinkageError e) {
            fail("could not be written", e);
        }
    }

    /** DELETE one preference; nothing while the file is unavailable or closed. Never throws. */
    private synchronized void remove(String key) {
        if(conn == null)
            return;
        try {
            conn.remove(key);
        } catch(Exception | LinkageError e) {
            fail("could not be written", e);
        }
    }

    // ---- the held slots ----------------------------------------------------------------------------

    /**
     * <b>Every slot held on one character's bar</b> ({@link BeltHold}), by 0-based slot index in slot order
     * &rarr; the identity of the entry placed there ({@code addon/<addon id>/<rel>}). {@code scope} is the
     * character's key, {@code StoreApi}'s {@code <genus>_<char>}. {@code null} when the file is unavailable
     * or the read failed: what the session then holds in memory is not the file, and nothing writes it back.
     */
    static Map<Integer, String> holds(String scope) {
        return get().readHolds(scope);
    }

    /**
     * <b>Replace one character's held slots with {@code rows}</b> — its rows deleted and these written, in
     * one transaction, so a reader never sees the bar half-written. Nothing while the file is unavailable or
     * closed. Never throws.
     */
    static void holds(String scope, Map<Integer, String> rows) {
        get().writeHolds(scope, rows);
    }

    private synchronized Map<Integer, String> readHolds(String scope) {
        if(conn == null)
            return null;
        try {
            return conn.holds(scope);
        } catch(Exception | LinkageError e) {
            fail("could not be read", e);
            return null;
        }
    }

    private synchronized void writeHolds(String scope, Map<Integer, String> rows) {
        if(conn == null)
            return;
        try {
            conn.holds(scope, rows);
        } catch(Exception | LinkageError e) {
            fail("could not be written", e);
        }
    }

    // ---- the panel's Remove ------------------------------------------------------------------------

    /**
     * <b>Forget one addon</b>: every row the client keeps about it goes, and nothing of any other's. The
     * preferences under its option keys ({@code LuaOption.prefKeyPrefix}) and its hotkey assignments —
     * {@code keybind/} + {@code HookApi.keyBindIdPrefix}, and the ids its custom menu entries mint
     * ({@code AddonPagina.bindId}) — are removed through {@code Utils.prefs()}, the node every read goes
     * through, so the map the session holds forgets them with the file; its {@code placements} rows and the
     * {@code holds} rows whose entry is one of its go in one transaction. Every match is a prefix compare on
     * the id followed by {@code /}, never {@code LIKE}: an id may contain {@code _} or {@code %}. The two
     * packed lists the registry keeps — the disabled set and the consent record — are the registry's own
     * writers' to rewrite, and it does before calling here. Runs where nothing of the addon is live: between a
     * reload's teardown and its load, or at boot. Never throws; nothing while the file is unavailable.
     */
    static void forget(String id) {
        String[] prefixes = {
            LuaOption.prefKeyPrefix(id),
            "keybind/" + HookApi.keyBindIdPrefix(id),                       // KeyBinding stores under "keybind/" + id
            "keybind/" + AddonPagina.bindId(AddonPagina.PREFIX + id + "/"),
        };
        Preferences p = Utils.prefs();
        try {
            for(String key : p.keys()) {
                for(String prefix : prefixes) {
                    if(key.startsWith(prefix)) {
                        p.remove(key);
                        break;
                    }
                }
            }
        } catch(BackingStoreException e) {
            /* neither node here throws: the file's answers from its map, the override's from memory */
        }
        get().forgetRows(id);
    }

    private synchronized void forgetRows(String id) {
        if(conn == null)
            return;
        try {
            conn.forget(id, AddonPagina.PREFIX + id + "/");
        } catch(Exception | LinkageError e) {
            fail("could not be written", e);
        }
    }

    // ---- the remembered placements ---------------------------------------------------------------

    /**
     * <b>Where one addon's remembered widgets sit in one scope</b> ({@code widget:remember(name)},
     * {@link StoreApi}), by name &rarr; the place and the box, each half {@code null} where the row holds none.
     * {@code id} is the addon's, {@code scope} the tree's: {@code StoreApi.CLIENT} for the addon's own layer,
     * the character's {@code <genus>_<char>} for a session's own tree. {@code null} when the file is
     * unavailable or the read failed: the set is then read-only for the session, since what it holds is not
     * the file.
     */
    static Map<String, StoreApi.Placement> placements(String id, String scope) {
        return get().readPlacements(id, scope);
    }

    /**
     * <b>Replace one addon's placements in one scope with {@code rows}</b> — its rows deleted and these
     * written, in one transaction. A row with neither half is not written. Nothing while the file is
     * unavailable or closed. Never throws.
     */
    static void placements(String id, String scope, Map<String, StoreApi.Placement> rows) {
        get().writePlacements(id, scope, rows);
    }

    private synchronized Map<String, StoreApi.Placement> readPlacements(String id, String scope) {
        if(conn == null)
            return null;
        try {
            return conn.placements(id, scope);
        } catch(Exception | LinkageError e) {
            fail("could not be read", e);
            return null;
        }
    }

    private synchronized void writePlacements(String id, String scope, Map<String, StoreApi.Placement> rows) {
        if(conn == null)
            return;
        try {
            conn.placements(id, scope, rows);
        } catch(Exception | LinkageError e) {
            fail("could not be written", e);
        }
    }

    /**
     * <b>The preference node over the {@code prefs} table.</b> A root ({@code parent == null}, name
     * {@code ""}): its keys and values are a map loaded whole at the open, and a write goes to the map and
     * through to the file — so a read costs a hash lookup, and a second client writing the same file is not
     * seen, as it never was. Children are in-memory nodes, and nothing asks for one.
     *
     * <p><b>{@code putSpi}/{@code removeSpi} never throw</b>: {@code AbstractPreferences.put} does not swallow,
     * and {@code Utils.setpref*} catches {@code SecurityException} alone. {@code keysSpi} answers the keys.
     * {@code isUserNode()} and {@code toString()} are overridden because the inherited ones compare the root
     * against the platform's own user root node, and on Windows asking for that node <i>creates</i>
     * {@code HKCU\Software\JavaSoft\Prefs}: without them, printing the node would open the registry.
     */
    static final class Prefs extends AbstractPreferences {
        /** The file behind this node, or {@code null} for an in-memory child. */
        private final ClientDb db;
        private final Map<String, String> map;

        Prefs(ClientDb db, Map<String, String> map) {
            super(null, "");
            this.db = db;
            this.map = map;
        }

        private Prefs(Prefs parent, String name) {
            super(parent, name);
            this.db = null;
            this.map = new ConcurrentHashMap<String, String>();
        }

        protected String getSpi(String key) {
            return map.get(key);
        }

        protected void putSpi(String key, String value) {
            map.put(key, value);
            if(db != null)
                db.put(key, value);
        }

        protected void removeSpi(String key) {
            map.remove(key);
            if(db != null)
                db.remove(key);
        }

        protected String[] keysSpi() {
            return map.keySet().toArray(new String[0]);
        }

        protected String[] childrenNamesSpi() {
            return new String[0];
        }

        protected AbstractPreferences childSpi(String name) {
            return new Prefs(this, name);
        }

        protected void removeNodeSpi() {}
        protected void syncSpi() {}
        protected void flushSpi() {}

        @Override
        public boolean isUserNode() {
            return true;
        }

        @Override
        public String toString() {
            if(db == null)
                return "Preferences in memory, node " + absolutePath();
            return "Preferences in " + db.file + (db.available() ? "" : " (unavailable, held in memory)");
        }
    }

    // ---- the connection ----------------------------------------------------------------------------

    /**
     * <b>The open file</b> — the one class here that names an {@code org.sqlite} or {@code java.sql} type,
     * so that a runtime without either fails at {@link #open} and nowhere else. The recipe is
     * {@link SqliteApi.Db}'s without the Lua sandbox: WAL so that a reader never waits on a writer,
     * {@code synchronous=NORMAL} so that a commit costs no fsync on the UI thread (durable at checkpoint;
     * a crash loses the last transactions, never the file), the busy timeout, and the schema recorded in
     * {@code user_version} — a higher number is a newer client's file, and refused. No attach limit and no
     * progress handler: nothing here runs a statement that is not the client's own. Auto-commit: each
     * write is its own transaction.
     */
    private static final class Conn {
        private final java.sql.Connection c;

        Conn(Path file) throws java.sql.SQLException {
            org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
            cfg.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
            cfg.setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.NORMAL);
            cfg.setBusyTimeout(BUSY_MS);
            java.sql.Connection c = cfg.createConnection("jdbc:sqlite:" + file);
            try {
                try(java.sql.Statement st = c.createStatement()) {
                    int have = (int)one(st, "PRAGMA user_version");
                    if(have > SCHEMA)
                        throw new java.sql.SQLException("written by a newer client (schema " + have
                            + ", this client writes " + SCHEMA + ")");
                    st.execute("CREATE TABLE IF NOT EXISTS prefs (key TEXT NOT NULL, value TEXT NOT NULL,"
                        + " PRIMARY KEY (key)) WITHOUT ROWID");
                    st.execute("CREATE TABLE IF NOT EXISTS placements (addon TEXT NOT NULL, scope TEXT NOT NULL,"
                        + " name TEXT NOT NULL, x INTEGER, y INTEGER, w INTEGER, h INTEGER,"
                        + " PRIMARY KEY (addon, scope, name)) WITHOUT ROWID");
                    st.execute("CREATE TABLE IF NOT EXISTS holds (scope TEXT NOT NULL, slot INTEGER NOT NULL,"
                        + " entry TEXT NOT NULL, PRIMARY KEY (scope, slot)) WITHOUT ROWID");
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
            this.c = c;
        }

        /** The one number a {@code PRAGMA} answers. */
        private static long one(java.sql.Statement st, String sql) throws java.sql.SQLException {
            try(java.sql.ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }

        /** Every row of {@code prefs} into {@code into}. */
        void prefs(Map<String, String> into) throws java.sql.SQLException {
            try(java.sql.Statement st = c.createStatement();
                java.sql.ResultSet rs = st.executeQuery("SELECT key, value FROM prefs")) {
                while(rs.next())
                    into.put(rs.getString(1), rs.getString(2));
            }
        }

        void put(String key, String value) throws java.sql.SQLException {
            try(java.sql.PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO prefs (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
        }

        void remove(String key) throws java.sql.SQLException {
            try(java.sql.PreparedStatement ps = c.prepareStatement("DELETE FROM prefs WHERE key = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
        }

        /** Every row of {@code holds} under one character's key, in slot order. */
        Map<Integer, String> holds(String scope) throws java.sql.SQLException {
            Map<Integer, String> out = new TreeMap<Integer, String>();
            try(java.sql.PreparedStatement ps = c.prepareStatement(
                    "SELECT slot, entry FROM holds WHERE scope = ? ORDER BY slot")) {
                ps.setString(1, scope);
                try(java.sql.ResultSet rs = ps.executeQuery()) {
                    while(rs.next())
                        out.put(Integer.valueOf(rs.getInt(1)), rs.getString(2));
                }
            }
            return out;
        }

        /** One character's rows deleted and {@code rows} written, in one transaction. */
        void holds(final String scope, final Map<Integer, String> rows) throws java.sql.SQLException {
            transaction(new Work() {
                public void run() throws java.sql.SQLException {
                    try(java.sql.PreparedStatement del = c.prepareStatement("DELETE FROM holds WHERE scope = ?")) {
                        del.setString(1, scope);
                        del.executeUpdate();
                    }
                    try(java.sql.PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO holds (scope, slot, entry) VALUES (?, ?, ?)")) {
                        for(Map.Entry<Integer, String> e : rows.entrySet()) {
                            ps.setString(1, scope);
                            ps.setInt(2, e.getKey().intValue());
                            ps.setString(3, e.getValue());
                            ps.executeUpdate();
                        }
                    }
                }
            });
        }

        /** Every row of {@code placements} under one addon and one scope, by name. A row with neither half is not a placement. */
        Map<String, StoreApi.Placement> placements(String id, String scope) throws java.sql.SQLException {
            Map<String, StoreApi.Placement> out = new LinkedHashMap<String, StoreApi.Placement>();
            try(java.sql.PreparedStatement ps = c.prepareStatement(
                    "SELECT name, x, y, w, h FROM placements WHERE addon = ? AND scope = ? ORDER BY name")) {
                ps.setString(1, id);
                ps.setString(2, scope);
                try(java.sql.ResultSet rs = ps.executeQuery()) {
                    while(rs.next()) {
                        StoreApi.Placement p = new StoreApi.Placement();
                        p.pos = coord(rs, 2, 3);
                        p.size = coord(rs, 4, 5);
                        if((p.pos != null) || (p.size != null))
                            out.put(rs.getString(1), p);
                    }
                }
            }
            return out;
        }

        /** One addon's rows of one scope deleted and {@code rows} written, in one transaction. */
        void placements(final String id, final String scope, final Map<String, StoreApi.Placement> rows)
            throws java.sql.SQLException {
            transaction(new Work() {
                public void run() throws java.sql.SQLException {
                    try(java.sql.PreparedStatement del = c.prepareStatement(
                            "DELETE FROM placements WHERE addon = ? AND scope = ?")) {
                        del.setString(1, id);
                        del.setString(2, scope);
                        del.executeUpdate();
                    }
                    try(java.sql.PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO placements (addon, scope, name, x, y, w, h) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                        for(Map.Entry<String, StoreApi.Placement> e : rows.entrySet()) {
                            StoreApi.Placement p = e.getValue();
                            if((p.pos == null) && (p.size == null))
                                continue;
                            ps.setString(1, id);
                            ps.setString(2, scope);
                            ps.setString(3, e.getKey());
                            half(ps, 4, p.pos);
                            half(ps, 6, p.size);
                            ps.executeUpdate();
                        }
                    }
                }
            });
        }

        /**
         * One addon's {@code placements} rows and the {@code holds} rows whose entry starts with
         * {@code entryPrefix} deleted, in one transaction. {@code ?1} twice is one parameter, so the prefix is
         * bound once and compared by its own length.
         */
        void forget(final String id, final String entryPrefix) throws java.sql.SQLException {
            transaction(new Work() {
                public void run() throws java.sql.SQLException {
                    try(java.sql.PreparedStatement del = c.prepareStatement("DELETE FROM placements WHERE addon = ?")) {
                        del.setString(1, id);
                        del.executeUpdate();
                    }
                    try(java.sql.PreparedStatement del = c.prepareStatement(
                            "DELETE FROM holds WHERE substr(entry, 1, length(?1)) = ?1")) {
                        del.setString(1, entryPrefix);
                        del.executeUpdate();
                    }
                }
            });
        }

        /** Two integer columns as one half of a placement, or {@code null} when either is {@code NULL}. */
        private static Coord coord(java.sql.ResultSet rs, int xcol, int ycol) throws java.sql.SQLException {
            int x = rs.getInt(xcol);
            if(rs.wasNull())
                return null;
            int y = rs.getInt(ycol);
            return rs.wasNull() ? null : Coord.of(x, y);
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

        /** Something to run inside one transaction. */
        private interface Work {
            void run() throws java.sql.SQLException;
        }

        /**
         * {@code work} inside one transaction: the connection's auto-commit is switched off for its extent and
         * back on after, whether it committed or rolled back.
         */
        private void transaction(Work work) throws java.sql.SQLException {
            c.setAutoCommit(false);
            boolean done = false;
            try {
                work.run();
                c.commit();
                done = true;
            } finally {
                if(!done) {
                    try {
                        c.rollback();
                    } catch(java.sql.SQLException x) {
                        /* the write already failed; the caller reports that one */
                    }
                }
                c.setAutoCommit(true);
            }
        }

        /** Close the connection: the log is checkpointed into the file and the sidecars go. */
        void close() throws java.sql.SQLException {
            c.close();
        }
    }
}
