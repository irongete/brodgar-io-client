package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaInteger;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static io.brodgar.addon.AddonManager.logAbout;

/**
 * <b>The store's file</b> (146): one SQLite database per addon, {@code savedata/<id>/<id>.sqlite} — a folder
 * of the addon's own, so the file and its sidecars stand together and nothing else's stands beside them —
 * for the whole client: every account this client logs in with and every character read and write the same one. What
 * {@link StoreApi} keeps in it is the addon's <b>vars</b> (one JSON row each, named at {@code :var}); the
 * tables an addon declares for itself and the statements it runs come through here too, on the same
 * connection. Nothing of the client's is in it: where the user put the addon's windows and which action-bar
 * slots hold its entries are rows of the client's own file ({@link ClientDb}).
 *
 * <p><b>Opened at {@link StoreApi#installStore}</b>, so a var is readable before {@code Load}, and
 * <b>closed by its own teardown step</b> and by the quit's flush, which is what checkpoints the write-ahead
 * log and drops the {@code -wal}/{@code -shm} sidecars beside the file. Every {@code org.sqlite} and
 * {@code java.sql} type lives in {@link Db}, and nothing outside it names one: a runtime without
 * {@code java.sql} fails to link {@link Db} inside {@link #open}'s own guard and lands in the unavailable
 * state below, rather than taking the addon load with it.
 *
 * <p><b>An open that fails leaves the store unavailable, and the addon loads.</b> The native library that
 * could not be extracted, a file that is not a database, a lock another process holds, a folder that
 * cannot be written: each is one log line naming the file and the cause. The addon's vars are then
 * empty and are never written — what is held is not the file, and writing it back would replace the file
 * with nothing — and every other store verb refuses naming the cause ({@link #require}).
 *
 * <p><b>One connection per file, and every verb on it is serialized</b> — the {@link Db} methods hold its
 * monitor. The message thread can be inside an addon's Lua, so two threads can reach one addon's store, and
 * a SQLite connection is not the place to find that out.
 *
 * <p><b>The declared tables</b> (146.2) are the addon's own record: {@code hafen.store():table(name)} is a
 * bare {@link Declaration}, configured by {@code :column}, {@code :key} and {@code :index} and dispatched
 * by {@code :create()}, which is the one call that touches the file and answers the interned {@link Table}.
 * A Table's rows go in and come out <b>typed by its live declaration</b> — the only place the Lua types
 * exist: a {@code boolean} is an {@code INTEGER} in the file and a {@code json} a {@code TEXT}, and the
 * file itself says nothing about which column is which. So a declaration is re-read every load, and the
 * {@code :create()} that re-declares an existing table replaces the Table's declaration in place and adds
 * what the file lacks, which is how a record evolves.
 *
 * <p><b>The statements</b> (146.3) are SQL for what only SQL says — an aggregate, a join, a bulk write:
 * {@code hafen.store():exec(sql, ...)} runs one that changes the file and answers how many rows it changed,
 * {@code hafen.store():query(sql, ...)} runs one that answers rows and answers them keyed by column, each
 * cell as the wire reads it ({@link #raw}) because no declaration types a statement's columns. A
 * {@code ?} binds what {@link #bindable} takes, the count held to the statement's. <b>What a statement
 * answers decides the verb</b>, known from the compiled statement before it runs, so a statement handed to
 * the wrong verb is refused naming the right one and runs nothing. <b>The {@link Scan}</b> reads the text
 * first: a second statement after a {@code ;} (the driver runs the first and drops the rest with nothing
 * said), a {@code CREATE TABLE}/{@code CREATE INDEX} that has a builder, a name of the client's own, and the
 * keywords whose refusal has a verb to name. It is not the sandbox — the engine is, with no second database
 * attachable and no extension loadable — it is what picks the message. The one thing it holds on its own
 * (146.7) is the client's cells: a {@code PRAGMA} that writes {@code user_version} or the connection's
 * {@code journal_mode}, {@code synchronous} or {@code foreign_keys} would undo what {@link Db}'s open set,
 * and the engine would let it. A Table's clause ({@code :list}, {@code :count}, {@code :find}) goes through
 * the same scan as a statement's tail: a second statement, a {@code hafen_} name and {@code load_extension}
 * are refused there too, naming the Table verb.
 *
 * <p><b>The transaction, the two bounds and the vacuum</b> (146.4). {@code hafen.store():transaction(fn, ...)}
 * brackets what {@code fn} runs — committed when it returns, rolled back when it raises, the error out of
 * the call — and holds the connection's monitor for the whole of it, so a second thread's verb waits at the
 * door rather than landing inside the bracket; it does not nest, and {@code :vacuum()} refuses inside it.
 * Every statement of the addon's own runs under a <b>deadline</b> ({@link #TIMEOUT_MS}, a
 * {@code ProgressHandler} that interrupts the step once it has passed) and a reading one under a <b>row
 * cap</b> ({@link #MAX_ROWS}), each a refusal naming the fix, because one bridge call is what the watchdog
 * charges one for. {@code hafen.store():vacuum()} rebuilds the file inside the same lock, raising the attach
 * limit for the one statement that needs it and putting it back.
 */
final class SqliteApi {
    private SqliteApi() {}

    /**
     * The shape of the client's own table, recorded in the file's {@code user_version}. A file below it was
     * written when the client kept its placements and holds in the addon's file, or named the vars' table
     * {@code hafen_documents}: at the open the two tables are dropped (they are the client's rows, and live
     * in {@link ClientDb} now), the vars' table is renamed in place, and the number is raised.
     */
    static final int SCHEMA = 4;

    /**
     * How long one statement of the addon's own may run, in milliseconds, before it is stopped —
     * {@code -Dhaven.addon.sqlite.timeout}. A deadline per statement, armed as it starts and dropped as it
     * ends, so the Lua between two statements of a transaction is not counted. Past it the statement fails
     * naming the timeout, its own changes undone by the engine; inside a transaction the engine undoes the
     * whole bracket, and the bracket says so ({@link Db#bracket}).
     */
    static final int TIMEOUT_MS = prop("haven.addon.sqlite.timeout", 5000);

    /**
     * The most rows one call reads — {@code -Dhaven.addon.sqlite.maxrows} — through {@code :query},
     * {@code :list} and {@code :find}. Past it the call fails naming {@code LIMIT}: a result that size is a
     * table the frame cannot afford to build, and a clause can page it.
     */
    static final int MAX_ROWS = prop("haven.addon.sqlite.maxrows", 50000);

    /** A bound read off a launch property, clamped into what an {@code int} holds and never below 1 ({@code Json}'s rule). */
    private static int prop(String name, int def) {
        long v = def;
        try {
            String raw = haven.Utils.getprop(name, null);
            if(raw != null)
                v = Long.parseLong(raw.trim());
        } catch(RuntimeException e) {
            return def;
        }
        if(v < 1L)
            return 1;
        return (v > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int)v;
    }

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
     * vars are read. The file is {@code <id>/<id>.sqlite} inside {@link StoreApi#saveDir} — the addon's
     * own folder, made here if it is not there — built through {@link Inside} like every other file under
     * {@code savedata/}: the id is a name out of a manifest.
     */
    static void open(Addon a) {
        a.db = null;
        Path file;
        try {
            file = Inside.inside(StoreApi.saveDir().toPath(), relative(a), "store");
        } catch(RuntimeException e) {
            unavailable(a, relative(a), Refusal.reason(e));
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
            unavailable(a, relative(a), why(e));
        }
    }

    /** The file's path under {@code savedata/}: the addon's own folder, and the file named by its id inside it. */
    private static String relative(Addon a) {
        return a.manifest.id + "/" + a.manifest.id + ".sqlite";
    }

    /** The one log line of the unavailable state, and the cause every refusing verb then quotes. */
    private static void unavailable(Addon a, String file, String why) {
        a.dbWhy = file + " could not be opened: " + why;
        logAbout(a, "store: " + a.dbWhy + " — this addon's store is unavailable for this session: its vars"
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
                : "the file is closed") + ". Its vars are empty and are not written, and nothing here"
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

    // ==== the declared tables (146.2) ================================================================

    /**
     * <b>The types a column takes</b>, and the affinity each is stored under. {@code boolean} and
     * {@code json} exist in the declaration alone: the file holds an {@code INTEGER} and a {@code TEXT}, and
     * the declaration is what reads them back as {@code true} and as the table that was put.
     */
    enum Type {
        TEXT("text", "TEXT"), INTEGER("integer", "INTEGER"), REAL("real", "REAL"),
        BOOLEAN("boolean", "INTEGER"), JSON("json", "TEXT");

        /** The word a declaration writes. */
        final String word;
        /** The affinity the column is created with. */
        final String affinity;

        Type(String word, String affinity) {
            this.word = word;
            this.affinity = affinity;
        }

        /** The type {@code word} names, or {@code null} — the words are lower case, as every name here is. */
        static Type of(String word) {
            for(Type t : values()) {
                if(t.word.equals(word))
                    return t;
            }
            return null;
        }

        /** The words, for the refusal that names what is allowed. */
        static final String WORDS = "text, integer, real, boolean or json";
    }

    /** One declared column: its name as the declaration spelled it, and its type. */
    static final class Column {
        final String name;
        final Type type;

        Column(String name, Type type) {
            this.name = name;
            this.type = type;
        }
    }

    /**
     * <b>One table's declaration</b> — what a {@link Declaration} accumulates, and what its {@link Table}
     * then holds live. The columns are in declaration order, which is the order {@code :put} binds them and
     * {@code CREATE TABLE} writes them; the key is in {@code :key} order, which is the order {@code :get} and
     * {@code :remove} take their values.
     *
     * <p><b>Names are compared two ways, and each is the rule of the side it faces.</b> Against the file a
     * name is case-insensitive, because SQLite folds identifiers — {@code Kind} and {@code kind} are one
     * column there, and a declaration that spells the file's column differently still means it. Against a
     * Lua row a name is exact, because Lua tables are: a row keyed {@code Kind} does not name the column
     * {@code kind}, and is refused naming the columns there are.
     */
    static final class Decl {
        final String table;
        final List<Column> columns = new ArrayList<Column>();
        final List<String> key = new ArrayList<String>();
        final List<List<String>> indexes = new ArrayList<List<String>>();
        /** The statements a Table runs, built once at {@link #freeze} — the declaration is fixed by then. */
        String putSql, getSql, removeSql, selectSql, countSql;

        Decl(String table) {
            this.table = table;
        }

        /** The column {@code nm} names by the file's rule (case-insensitive), or {@code null}. */
        Column column(String nm) {
            for(Column c : columns) {
                if(c.name.equalsIgnoreCase(nm))
                    return c;
            }
            return null;
        }

        /** The column {@code nm} names by Lua's rule (exact), or {@code null}. */
        Column exact(String nm) {
            for(Column c : columns) {
                if(c.name.equals(nm))
                    return c;
            }
            return null;
        }

        /** {@code grid, x, y, kind, seen and flags} — the columns, for a refusal that names them. */
        String columnList() {
            List<String> names = new ArrayList<String>();
            for(Column c : columns)
                names.add(c.name);
            return english(names);
        }

        /** {@code (grid, x, y)} — the key, for a refusal that names it. */
        String keyList() {
            return "(" + join(key, ", ") + ")";
        }

        /** Build the statements, once the declaration is what {@code :create()} dispatched. */
        void freeze() {
            StringBuilder cols = new StringBuilder(), marks = new StringBuilder(), where = new StringBuilder();
            for(Column c : columns) {
                cols.append((cols.length() > 0) ? ", " : "").append(q(c.name));
                marks.append((marks.length() > 0) ? ", " : "").append('?');
            }
            for(String k : key)
                where.append((where.length() > 0) ? " AND " : "").append(q(k)).append(" = ?");
            putSql = "INSERT OR REPLACE INTO " + q(table) + " (" + cols + ") VALUES (" + marks + ") RETURNING *";
            getSql = "SELECT * FROM " + q(table) + " WHERE " + where;
            removeSql = "DELETE FROM " + q(table) + " WHERE " + where;
            selectSql = "SELECT * FROM " + q(table);
            countSql = "SELECT count(*) FROM " + q(table);
        }
    }

    /** A declaration being built: its {@link Decl}, and whether {@code :create()} has dispatched it. */
    static final class Declaration {
        final Decl decl;
        boolean created;

        Declaration(String table) {
            decl = new Decl(table);
        }
    }

    /**
     * <b>One of the addon's own tables</b>, interned per addon by name ({@link Addon#storeTables}) so that
     * two {@code :create()}s of one name answer one object. It holds no connection: every verb asks
     * {@link #require} for the file, so a Table outlives a close and a reopen. What it holds is the
     * <b>live declaration</b>, replaced by every {@code :create()} that re-declares the table — the one place
     * the Lua types of its columns exist.
     */
    static final class Table implements LuaCollection.NotASequence {
        final Addon owner;
        volatile Decl decl;

        Table(Addon owner, Decl decl) {
            this.owner = owner;
            this.decl = decl;
        }

        public String iterationRefused(String fn) {
            return fn + " is refused on the table " + decl.table + ": it is a collection of rows, not a Lua"
                + " table — :list(clause, ...) is the array, and you walk that";
        }
    }

    /** What a name of the addon's own may be spelled with — an identifier SQL needs no quoting rule for. */
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** The rule every name refusal ends with. */
    private static final String NAME_RULE = "a name is letters, digits and underscores, starting with a letter"
        + " or an underscore";

    /** The spelling of the builder's verbs in its messages. */
    private static final String DECL = "declaration";

    /** The spelling of the Table's verbs in its messages. */
    private static final String TBL = "table";

    /**
     * {@code hafen.store():table(name)} — a bare <b>declaration</b> of one of {@code a}'s own tables. The name
     * is checked here, where it is written; the file is touched by {@code :create()} alone. The unavailable
     * state refuses at this door as every other store verb does, so the author meets it at the first line
     * rather than at the dispatch.
     */
    static LuaValue table(Addon a, Varargs args) {
        Args.only(args, 1, ACC + ":table");
        String nm = Args.str(args, 2, ACC + ":table", "name", "the table's name in the file").tojstring();
        require(a, ACC + ":table(name)");
        String why = tableNameRefused(nm);
        if(why != null)
            throw new LuaError(ACC + ":table(name): " + why);
        return declaration(a, nm);
    }

    /** Why {@code nm} cannot name a table of the addon's own, or {@code null} when it can. */
    private static String tableNameRefused(String nm) {
        String lower = nm.toLowerCase(Locale.ROOT);
        if(lower.startsWith("hafen_"))
            return "\"" + nm + "\" is not a name you can declare: the hafen_ prefix is the client's own"
                + " table (your vars live there: hafen_vars) — " + NAME_RULE + ", under any other"
                + " prefix";
        if(lower.startsWith("sqlite_"))
            return "\"" + nm + "\" is not a name you can declare: the sqlite_ prefix is SQLite's own — "
                + NAME_RULE + ", under any other prefix";
        if(!IDENT.matcher(nm).matches())
            return "\"" + nm + "\" is not a name a table can have — " + NAME_RULE;
        return null;
    }

    /** Why {@code nm} cannot name a column, or {@code null} when it can. */
    private static String columnNameRefused(String nm) {
        return IDENT.matcher(nm).matches() ? null : "\"" + nm + "\" is not a name a column can have — " + NAME_RULE;
    }

    /** {@code "ident"} — every identifier in a statement here is quoted, so a column called {@code order} works. */
    private static String q(String ident) {
        return "\"" + ident + "\"";
    }

    private static String join(List<String> names, String sep) {
        StringBuilder b = new StringBuilder();
        for(String n : names)
            b.append((b.length() > 0) ? sep : "").append(n);
        return b.toString();
    }

    /** {@code a, b and c} — for a refusal that lists names. */
    private static String english(List<String> names) {
        StringBuilder b = new StringBuilder();
        for(int i = 0; i < names.size(); i++) {
            if(i > 0)
                b.append((i == names.size() - 1) ? " and " : ", ");
            b.append(names.get(i));
        }
        return (b.length() > 0) ? b.toString() : "(nothing)";
    }

    /** Are two column lists the same columns in the same order, by the file's rule? */
    private static boolean sameNames(List<String> a, List<String> b) {
        if(a.size() != b.size())
            return false;
        for(int i = 0; i < a.size(); i++) {
            if(!a.get(i).equalsIgnoreCase(b.get(i)))
                return false;
        }
        return true;
    }

    // ---- the builder --------------------------------------------------------------------------

    /**
     * The declaration object: {@code :column(name, type)}, {@code :key(col, ...)}, {@code :index(col, ...)}
     * and {@code :create()}, each validated as it is written. A builder is dispatched once — every setter is
     * legal until {@code :create()} and none after — and what it answers is the Table.
     */
    private static LuaValue declaration(final Addon owner, String nm) {
        final Declaration b = new Declaration(nm);
        final LuaValue h = LuaValue.userdataOf(b);
        LuaTable m = new LuaTable();
        // column(name, type) -- one column. A repeated name is refused by the file's rule (SQLite folds case,
        // so "Kind" beside "kind" would fail there with a duplicate); a type outside the five is refused
        // naming them.
        m.set("column", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 2, DECL + ":column");
                String col = Args.str(a, 2, DECL + ":column", "name", "the column's name").tojstring();
                String word = Args.str(a, 3, DECL + ":column", "type", Type.WORDS).tojstring();
                open(b, "column(name, type)");
                String why = columnNameRefused(col);
                if(why != null)
                    throw new LuaError(DECL + ":column(name, type): " + why);
                if(b.decl.column(col) != null)
                    throw new LuaError(DECL + ":column(name, type): \"" + col + "\" is already a column of "
                        + b.decl.table + " — it declares " + b.decl.columnList() + ", and a column is declared once");
                Type t = Type.of(word);
                if(t == null)
                    throw new LuaError(DECL + ":column(name, type): \"" + word + "\" is not a type a column takes"
                        + " — the types are " + Type.WORDS);
                b.decl.columns.add(new Column(col, t));
                return h;
            }
        });
        // key(col, ...) -- what identifies a row, in the order :get and :remove take it. Declared once: a
        // second key is not a correction but a second identity, and the first is named.
        m.set("key", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                open(b, "key(col, ...)");
                if(!b.decl.key.isEmpty())
                    throw new LuaError(DECL + ":key(col, ...): the key of " + b.decl.table + " is declared once,"
                        + " and it is " + b.decl.keyList());
                List<String> cols = columns(b.decl, a, DECL + ":key(col, ...)");
                for(String c : cols) {
                    if(b.decl.column(c).type == Type.JSON)
                        throw new LuaError(DECL + ":key(col, ...): \"" + c + "\" is a json column, and a json"
                            + " column cannot be part of the key — it is compared as text, and two spellings"
                            + " of one document differ. Key the row by what identifies it");
                }
                b.decl.key.addAll(cols);
                return h;
            }
        });
        // index(col, ...) -- one index over declared columns. The same columns twice is refused: the second
        // could only have been meant as the first.
        m.set("index", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                open(b, "index(col, ...)");
                List<String> cols = columns(b.decl, a, DECL + ":index(col, ...)");
                for(List<String> have : b.decl.indexes) {
                    if(sameNames(have, cols))
                        throw new LuaError(DECL + ":index(col, ...): an index over (" + join(cols, ", ")
                            + ") is already declared on " + b.decl.table);
                }
                b.decl.indexes.add(cols);
                return h;
            }
        });
        // create() -- THE DISPATCH: the one call that touches the file. A table absent from it is created;
        // one present gains the columns and indexes it lacks and keeps every column nobody declares (it is
        // data); a key that differs from the file's is refused naming both. What it answers is the interned
        // Table, whose live declaration is this one from now on.
        m.set("create", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, DECL + ":create");
                open(b, "create()");
                if(b.decl.key.isEmpty())
                    throw new LuaError(DECL + ":create(): " + b.decl.table + " has no key — :key(col, ...)"
                        + " names the columns that identify a row, and a table has one");
                Db db = require(owner, DECL + ":create()");
                LuaValue t = create(owner, db, b.decl);
                b.created = true;
                return t;
            }
        });
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("declaration", m,   // the literal is what tools/ reads
            "a table you are declaring with " + ACC + ":table(name)",
            "it is dispatched by :create(), which answers the Table: every setter is legal until then and"
            + " none after"));
        mt.set("__name", LuaValue.valueOf("Declaration"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf("Declaration(" + b.decl.table + (b.created ? ", created" : "") + ")");
            }
        });
        h.setmetatable(mt);
        return h;
    }

    /** A setter refuses once the declaration has been dispatched: the Table it answered is the thing to use. */
    private static void open(Declaration b, String verb) {
        if(b.created)
            throw new LuaError(DECL + ":" + verb + ": this declaration was created — the Table :create() answered"
                + " is the one to use, and " + ACC + ":table(name) is where another declaration comes from");
    }

    /**
     * The column names a {@code :key} or {@code :index} was handed: at least one, each a string naming a
     * column already declared, none twice. Answered in the declaration's own spelling.
     */
    private static List<String> columns(Decl d, Varargs a, String verb) {
        if(!Args.passed(a, 2))
            throw new LuaError(verb + ": at least one column is required");
        List<String> out = new ArrayList<String>();
        for(int i = 2; i <= a.narg(); i++) {
            LuaValue v = a.arg(i);
            if(v.isnil())
                throw Args.nilRefused(verb, "col");
            String nm = Args.str(v, verb, "col", "the name of a declared column").tojstring();
            Column c = d.column(nm);
            if(c == null)
                throw new LuaError(verb + ": \"" + nm + "\" is not a column of " + d.table + " — it declares "
                    + d.columnList() + "; :column(name, type) comes before the key and the indexes that name it");
            for(String have : out) {
                if(have.equalsIgnoreCase(c.name))
                    throw new LuaError(verb + ": \"" + nm + "\" is named twice");
            }
            out.add(c.name);
        }
        return out;
    }

    /**
     * <b>The dispatch</b>: put {@code d} in the file, or bring the file's table up to it, and answer the
     * interned Table with {@code d} as its live declaration. One critical section on the file, so a second
     * thread's statement cannot land between the read of what is there and the {@code ALTER} that follows it.
     */
    private static LuaValue create(final Addon owner, Db db, final Decl d) {
        synchronized(db) {
            try {
                List<Object[]> stored = db.select("SELECT name, type, pk FROM pragma_table_info(?)",
                                                  new Object[] {d.table}, -1).rows;
                if(stored.isEmpty()) {
                    db.ddl(createSql(d));
                } else {
                    // The stored key, in its own order: pragma_table_info's pk is 0 off the key and the
                    // 1-based position in it otherwise.
                    String[] byPos = new String[stored.size()];
                    int n = 0;
                    for(Object[] r : stored) {
                        int pk = ((Number)r[2]).intValue();
                        if(pk > 0) {
                            byPos[pk - 1] = (String)r[0];
                            n++;
                        }
                    }
                    List<String> storedKey = new ArrayList<String>();
                    for(int i = 0; i < n; i++)
                        storedKey.add(byPos[i]);
                    if(!sameNames(storedKey, d.key))
                        throw new LuaError(DECL + ":create(): " + d.table + " is in the file with the key ("
                            + join(storedKey, ", ") + "), and this declaration says " + d.keyList() + " — a key"
                            + " is what identifies a row and does not change in place: declare the key the file"
                            + " has, or a table under another name");
                    for(Column c : d.columns) {
                        boolean have = false;
                        for(Object[] r : stored)
                            have |= c.name.equalsIgnoreCase((String)r[0]);
                        if(!have)
                            db.ddl("ALTER TABLE " + q(d.table) + " ADD COLUMN " + q(c.name) + " " + c.type.affinity);
                    }
                }
                for(List<String> idx : d.indexes)
                    db.ddl(indexSql(d, idx));
            } catch(Failure e) {
                throw new LuaError(DECL + ":create(): " + Refusal.reason(e));
            }
        }
        d.freeze();
        // Interned by the file's rule for the name: "Nodes" and "nodes" are one table there, so they are
        // one Table here. The mint carries d; a hit is given d as well, so either way the live declaration
        // is the one just dispatched.
        LuaValue h = owner.storeTables.of(d.table.toLowerCase(Locale.ROOT), () -> tableHandle(owner, d));
        ((Table)h.touserdata()).decl = d;
        return h;
    }

    /** {@code CREATE TABLE} for a declaration: the affinities, {@code NOT NULL} on the key, the key. */
    private static String createSql(Decl d) {
        StringBuilder b = new StringBuilder("CREATE TABLE " + q(d.table) + " (");
        for(Column c : d.columns) {
            b.append(q(c.name)).append(' ').append(c.type.affinity);
            // SQLite lets a PRIMARY KEY column of an ordinary table hold NULL; a row without its key is
            // refused at :put, and the file says the same.
            for(String k : d.key) {
                if(k.equalsIgnoreCase(c.name))
                    b.append(" NOT NULL");
            }
            b.append(", ");
        }
        StringBuilder key = new StringBuilder();
        for(String k : d.key)
            key.append((key.length() > 0) ? ", " : "").append(q(k));
        return b.append("PRIMARY KEY (").append(key).append("))").toString();
    }

    /** {@code CREATE INDEX IF NOT EXISTS <table>_<cols>}: absent from the file, it is made; present, left. */
    private static String indexSql(Decl d, List<String> cols) {
        StringBuilder names = new StringBuilder();
        for(String c : cols)
            names.append((names.length() > 0) ? ", " : "").append(q(c));
        return "CREATE INDEX IF NOT EXISTS " + q(d.table + "_" + join(cols, "_")) + " ON " + q(d.table) + " ("
            + names + ")";
    }

    // ---- the Table ----------------------------------------------------------------------------

    /**
     * The Table object over {@code d}: the collection grammar over rows, where the filter is a SQL clause.
     * {@code :put(row)} upserts by key and answers the row as stored; {@code :get(k, ...)} and
     * {@code :remove(k, ...)} take the key in {@code :key} order; {@code :list}, {@code :count} and
     * {@code :find} take what follows {@code FROM <table>} and one value per {@code ?}.
     */
    private static LuaValue tableHandle(final Addon owner, Decl d) {
        final Table tbl = new Table(owner, d);
        final LuaValue h = LuaValue.userdataOf(tbl);
        LuaTable m = new LuaTable();
        // put(row) -- INSERT OR REPLACE, typed in by the declaration and typed out by it: what comes back is
        // the row as the file now holds it, a boolean as true and a json column as its table. A column the row
        // leaves out is NULL in the file, which is an absent key on the way back.
        m.set("put", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 1, TBL + ":put");
                LuaValue row = Args.required(a, 2, TBL + ":put", "row");
                if(!row.istable())
                    throw new LuaError(TBL + ":put(row): row must be a table keyed by column name, got "
                        + row.typename());
                Decl decl = tbl.decl;
                Object[] binds = encodeRow(decl, (LuaTable)row);
                Db db = require(owner, TBL + ":put(row)");
                Db.Rows rs = run(db, decl.putSql, binds, 1, TBL + ":put(row)");
                return rs.rows.isEmpty() ? LuaValue.NIL : decodeRow(owner, decl, rs, 0);
            }
        });
        // get(k, ...) -- one row by its key, or nil.
        m.set("get", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Decl decl = tbl.decl;
                Object[] binds = keyBinds(decl, a, TBL + ":get");
                Db db = require(owner, TBL + ":get(" + join(decl.key, ", ") + ")");
                Db.Rows rs = run(db, decl.getSql, binds, 1, TBL + ":get(" + join(decl.key, ", ") + ")");
                return rs.rows.isEmpty() ? LuaValue.NIL : decodeRow(owner, decl, rs, 0);
            }
        });
        // remove(k, ...) -- the row with that key is gone, whether or not it was there; the Table chains.
        m.set("remove", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Decl decl = tbl.decl;
                Object[] binds = keyBinds(decl, a, TBL + ":remove");
                Db db = require(owner, TBL + ":remove(" + join(decl.key, ", ") + ")");
                try {
                    db.change(decl.removeSql, binds);
                } catch(Failure e) {
                    throw new LuaError(TBL + ":remove(" + join(decl.key, ", ") + "): " + Refusal.reason(e));
                }
                return h;
            }
        });
        // list(clause, ...) -- every row the clause keeps, as a plain array.
        m.set("list", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Decl decl = tbl.decl;
                String verb = TBL + ":list(clause, ...)";
                Db.Rows rs = run(require(owner, verb), decl.selectSql + clause(decl, a, verb), binds(a, verb),
                                 -1, verb);
                LuaTable out = new LuaTable();
                for(int i = 0; i < rs.rows.size(); i++)
                    out.set(i + 1, decodeRow(owner, decl, rs, i));
                return out;
            }
        });
        // count(clause, ...) -- how many, counted by the file rather than by reading them.
        m.set("count", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Decl decl = tbl.decl;
                String verb = TBL + ":count(clause, ...)";
                Db.Rows rs = run(require(owner, verb), decl.countSql + clause(decl, a, verb), binds(a, verb),
                                 1, verb);
                return rs.rows.isEmpty() ? LuaValue.ZERO : raw(rs.rows.get(0)[0]);
            }
        });
        // find(clause, ...) -- the first row the clause keeps, or nil: the same statement as :list, read one
        // row deep and let go.
        m.set("find", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Decl decl = tbl.decl;
                String verb = TBL + ":find(clause, ...)";
                Db.Rows rs = run(require(owner, verb), decl.selectSql + clause(decl, a, verb), binds(a, verb),
                                 1, verb);
                return rs.rows.isEmpty() ? LuaValue.NIL : decodeRow(owner, decl, rs, 0);
            }
        });
        // info() -- {name, columns, key, indexes}: the live declaration as a plain table (146.6). The one
        // snapshot a Table hands out, a fresh copy every call: it reads the declaration the Table holds, not
        // the file, so it answers what the latest :create() dispatched and needs no connection.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, TBL + ":info");
                return info(tbl.decl);
            }
        });
        LuaTable mt = new LuaTable();
        final LuaValue closed = Refusal.closedIndex("table", m,   // the literal is what tools/ reads
            "one of your addon's own tables, declared through " + ACC + ":table(name)",
            "a row is a plain table keyed by column name, and NULL is an absent key");
        mt.set(LuaValue.INDEX, new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue key) {
                if(key.type() == LuaValue.TNUMBER)
                    throw new LuaError("the table " + tbl.decl.table + " is a collection of rows, not an array:"
                        + " :list(clause, ...) is the array and you index that");
                return closed.call(self, key);
            }
        });
        mt.set("__len", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                throw new LuaError("# is refused on the table " + tbl.decl.table + ": :count(clause, ...) is"
                    + " how many, :list(clause, ...) is the array");
            }
        });
        mt.set("__name", LuaValue.valueOf("Table"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf("Table(" + tbl.decl.table + ")");
            }
        });
        h.setmetatable(mt);
        return h;
    }

    /**
     * {@code nodes:info()} — {@code d} as a plain table: {@code name}, {@code columns} an array of
     * {@code {name, type}} in declaration order with {@code type} the word the declaration wrote, {@code key}
     * an array of column names in {@code :key} order, {@code indexes} an array of such arrays. Built fresh on
     * every call, so what the reader assigns into it reaches nothing.
     */
    static LuaTable info(Decl d) {
        LuaTable t = new LuaTable();
        t.set("name", LuaValue.valueOf(d.table));
        LuaTable columns = new LuaTable();
        for(Column c : d.columns) {
            LuaTable col = new LuaTable();
            col.set("name", LuaValue.valueOf(c.name));
            col.set("type", LuaValue.valueOf(c.type.word));
            columns.set(columns.length() + 1, col);
        }
        t.set("columns", columns);
        t.set("key", names(d.key));
        LuaTable indexes = new LuaTable();
        for(List<String> idx : d.indexes)
            indexes.set(indexes.length() + 1, names(idx));
        t.set("indexes", indexes);
        return t;
    }

    /** A list of column names as a Lua array. */
    private static LuaTable names(List<String> names) {
        LuaTable t = new LuaTable();
        for(String n : names)
            t.set(t.length() + 1, LuaValue.valueOf(n));
        return t;
    }

    // ---- typing: the row in, the row out ----------------------------------------------------

    /**
     * A {@code :put} row as the binds of {@link Decl#putSql}, one per declared column in declaration order.
     * Three refusals, in this order: a key that names no column (naming the columns), a value not of its
     * column's type (naming the type), and a key column the row has not got (naming the key). The order is
     * what makes each message the nearest one to the mistake.
     */
    private static Object[] encodeRow(Decl d, LuaTable row) {
        String verb = TBL + ":put";
        for(LuaValue k : row.keys()) {
            if(k.type() != LuaValue.TSTRING)
                throw new LuaError(verb + "(row): a row is keyed by column name, and this one has a "
                    + k.typename() + " key");
            if(d.exact(k.tojstring()) == null)
                throw new LuaError(verb + "(row): \"" + k.tojstring() + "\" is not a column of " + d.table
                    + " — it declares " + d.columnList());
        }
        Object[] out = new Object[d.columns.size()];
        int i = 0;
        for(Column c : d.columns) {
            LuaValue v = row.get(c.name);
            out[i++] = v.isnil() ? null : encode(c, v, verb);
        }
        for(String k : d.key) {
            if(row.get(k).isnil())
                throw new LuaError(verb + "(row): the key of " + d.table + " is " + d.keyList() + " and the row"
                    + " has no " + k + " — every key column is required");
        }
        return out;
    }

    /**
     * The key values a {@code :get} or {@code :remove} was handed, typed by their columns: as many as the
     * key has columns, in {@code :key} order, none {@code nil}.
     */
    private static Object[] keyBinds(Decl d, Varargs a, String verb) {
        String call = verb + "(" + join(d.key, ", ") + ")";
        int got = a.narg() - 1;
        if(got != d.key.size())
            throw new LuaError(call + ": the key of " + d.table + " is " + d.keyList() + " — " + d.key.size()
                + ((d.key.size() == 1) ? " value" : " values") + ", got " + got);
        Object[] out = new Object[got];
        for(int i = 0; i < got; i++) {
            Column c = d.column(d.key.get(i));
            LuaValue v = a.arg(i + 2);
            if(v.isnil())
                throw Args.nilRefused(call, c.name);
            out[i] = encode(c, v, call);
        }
        return out;
    }

    /**
     * One Lua value as the bind its column takes: a {@code text} is a string, an {@code integer} a whole
     * number, a {@code real} a finite one, a {@code boolean} {@code true} or {@code false}, a {@code json} a
     * table the store can hold — each refused by type, naming the column and its type.
     */
    private static Object encode(Column c, LuaValue v, String verb) {
        String hint = "the column is " + c.type.word;
        switch(c.type) {
        case TEXT:
            return Args.str(v, verb, c.name, hint).tojstring();
        case INTEGER:
            return Long.valueOf(Args.integer(v, verb, c.name, hint, -Args.EXACT, Args.EXACT));
        case REAL:
            return Double.valueOf(Args.num(v, verb, c.name, hint).todouble());
        case BOOLEAN:
            return Long.valueOf(Args.bool(v, verb, c.name, hint) ? 1L : 0L);
        case JSON:
            if(!v.istable())
                throw new LuaError(verb + ": " + c.name + " must be a table (" + hint + "), got " + v.typename());
            // The store's own rule for what a var may hold, walked here for the same reason flush()
            // walks it: a function or a widget handle would be written as text and read back as text.
            String bad = StoreApi.uncarriable((LuaTable)v, c.name, Collections.newSetFromMap(
                                                  new IdentityHashMap<LuaValue, Boolean>()));
            if(bad != null)
                throw new LuaError(verb + ": " + bad + " — a json column holds tables, strings, numbers,"
                    + " booleans and Positions");
            return Json.write(v);
        }
        throw new IllegalStateException(c.type.toString());
    }

    /**
     * A value bound to a {@code ?} of a clause or a statement: a string is {@code TEXT}, a whole number
     * {@code INTEGER}, any other number {@code REAL}, a boolean {@code 1} or {@code 0}, {@code nil}
     * {@code NULL}. Anything else is refused naming those.
     */
    static Object bindable(LuaValue v, String verb, int n) {
        switch(v.type()) {
        case LuaValue.TNIL:
            return null;
        case LuaValue.TBOOLEAN:
            return Long.valueOf(v.toboolean() ? 1L : 0L);
        case LuaValue.TSTRING:
            return v.tojstring();
        case LuaValue.TNUMBER: {
            double d = Args.num(v, verb, "value " + n, null).todouble();
            return ((d == Math.rint(d)) && (Math.abs(d) <= Args.EXACT)) ? (Object)Long.valueOf((long)d)
                : (Object)Double.valueOf(d);
        }
        default:
            throw new LuaError(verb + ": value " + n + " cannot be bound to a ? — a ? takes a string, a number,"
                + " a boolean or nil, got " + v.typename());
        }
    }

    /**
     * The clause a {@code :list}, {@code :count} or {@code :find} was handed, or nothing; a function is refused
     * naming SQL, and the text is scanned as a statement's tail is ({@link Scan#clause}), the Table verb as
     * the receiver of the refusal.
     */
    private static String clause(Decl d, Varargs a, String verb) {
        if(!Args.passed(a, 2) || a.arg(2).isnil())
            return "";
        LuaValue c = a.arg(2);
        if(c.type() != LuaValue.TSTRING)
            throw new LuaError(verb + ": the clause is SQL — what follows FROM " + d.table + ", such as"
                + " \"WHERE kind = ? ORDER BY x\", with one value per ? after it — got " + c.typename());
        String text = c.tojstring();
        Scan.clause(text, verb);
        return " " + text;
    }

    /** The values after the clause, as binds. */
    private static Object[] binds(Varargs a, String verb) {
        int n = Math.max(0, a.narg() - 2);
        Object[] out = new Object[n];
        for(int i = 0; i < n; i++)
            out[i] = bindable(a.arg(i + 3), verb, i + 1);
        return out;
    }

    /** Run a reading statement for a verb, phrasing the driver's refusals as the verb's. */
    private static Db.Rows run(Db db, String sql, Object[] binds, int max, String verb) {
        try {
            return db.select(sql, binds, max);
        } catch(Failure e) {
            throw refused(e, verb);
        }
    }

    /**
     * A failure out of the file as {@code verb}'s own refusal: the arity, the deadline and the row cap in the
     * API's words, the rest as the driver said it.
     */
    private static LuaError refused(Failure e, String verb) {
        if(e instanceof Db.Arity) {
            Db.Arity a = (Db.Arity)e;
            return new LuaError(verb + ": the statement has " + a.want + ((a.want == 1) ? " ?" : " ?s") + " and "
                + a.got + ((a.got == 1) ? " value was" : " values were") + " passed — one value per ?");
        }
        if(e instanceof Db.Timeout)
            return new LuaError(verb + ": the statement ran for " + TIMEOUT_MS + " ms, which is the timeout one"
                + " statement gets (-Dhaven.addon.sqlite.timeout, in milliseconds), and was stopped — the file"
                + " is as it was before it. An index over what the WHERE reads, a tighter clause or a LIMIT is"
                + " the fix");
        if(e instanceof Db.Cap)
            return new LuaError(verb + ": the statement answers more than " + MAX_ROWS + " rows, which is the"
                + " most one call reads (-Dhaven.addon.sqlite.maxrows) — put a LIMIT on it, and page with OFFSET"
                + " or a WHERE over the key; :count(clause, ...) and count(*) are how many without reading them");
        return new LuaError(verb + ": " + Refusal.reason(e));
    }

    // ==== statements (146.3) =========================================================================

    /** The spelling of the two statement verbs in their messages. */
    private static final String EXEC = ACC + ":exec(sql, ...)", QUERY = ACC + ":query(sql, ...)";

    /**
     * {@code hafen.store():exec(sql, ...)} — one statement that changes the file, and how many rows it
     * changed: what it inserted, updated or deleted, its triggers and cascades included, and {@code 0} for
     * one that changes no row (a {@code DROP}, a {@code PRAGMA}). A statement that answers rows is refused
     * naming {@code :query}, before it runs.
     */
    static LuaValue exec(Addon a, Varargs args) {
        String sql = Args.str(args, 2, ACC + ":exec", "sql", "one SQL statement, with one value per ? after it")
            .tojstring();
        Object[] binds = binds(args, EXEC);
        Db db = require(a, EXEC);
        Scan.check(sql, EXEC);
        return LuaInteger.valueOf(statement(db, sql, binds, false, EXEC).changed);
    }

    /**
     * {@code hafen.store():query(sql, ...)} — one statement that answers rows, as a plain array of plain
     * tables keyed by column label, each cell as the wire reads it and a {@code NULL} an absent key. A
     * statement that answers none is refused naming {@code :exec}, before it runs.
     */
    static LuaValue query(Addon a, Varargs args) {
        String sql = Args.str(args, 2, ACC + ":query", "sql", "one SQL statement, with one value per ? after it")
            .tojstring();
        Object[] binds = binds(args, QUERY);
        Db db = require(a, QUERY);
        Scan.check(sql, QUERY);
        Db.Rows rs = statement(db, sql, binds, true, QUERY).rows;
        LuaTable out = new LuaTable();
        for(int i = 0; i < rs.rows.size(); i++)
            out.set(i + 1, rawRow(rs, i));
        return out;
    }

    /** Run one statement of the addon's own for a verb, phrasing what the file refuses as the verb's. */
    private static Db.Answer statement(Db db, String sql, Object[] binds, boolean rows, String verb) {
        try {
            return db.statement(sql, binds, rows, -1);
        } catch(Db.Kind e) {
            throw new LuaError(verb + ": " + (rows
                ? "this statement answers no rows, and :query answers rows — " + EXEC + " runs a statement"
                  + " that changes the file, and answers how many rows it changed"
                : "this statement answers rows, and :exec answers how many rows a statement changed — " + QUERY
                  + " runs it and answers them") + (e.ran ? "; it ran" : "; it did not run"));
        } catch(Failure e) {
            throw refused(e, verb);
        }
    }

    /**
     * One row of a statement's answer as a plain table keyed by column label — the alias where the statement
     * gave one, the column's name where it did not — each cell as the wire reads it, a {@code NULL} an absent
     * key. Two columns under one label keep the last, which is SQL's own answer; an alias is the fix.
     */
    private static LuaTable rawRow(Db.Rows rs, int i) {
        LuaTable t = new LuaTable();
        Object[] r = rs.rows.get(i);
        for(int c = 0; c < rs.columns.length; c++) {
            LuaValue v = raw(r[c]);
            if(!v.isnil())
                t.set(rs.columns[c], v);
        }
        return t;
    }

    // ==== the transaction and the vacuum (146.4) =====================================================

    /** The spelling of the two verbs in their messages. */
    private static final String TX = ACC + ":transaction(fn, ...)", VAC = ACC + ":vacuum()";

    /**
     * {@code hafen.store():transaction(fn, ...)} — run {@code fn(...)} inside one transaction: committed when
     * it returns, rolled back when it raises with its error out of the call, and what it answers answered.
     * The bracket holds the file's monitor for the whole of {@code fn} ({@link Db#bracket}), so a second
     * thread's verb waits at the door; a second bracket on the same thread is refused naming this one, since
     * what it would run is inside the open one already.
     */
    static Varargs transaction(Addon a, Varargs args) {
        final LuaValue fn = Args.required(args, 2, ACC + ":transaction", "fn");
        if(!fn.isfunction())
            throw new LuaError(TX + ": fn must be a function — what runs inside the transaction, with the values"
                + " after it as its arguments — got " + fn.typename());
        Db db = require(a, TX);
        final Varargs rest = args.subargs(3);
        try {
            return db.bracket(() -> fn.invoke(rest));
        } catch(Db.Open e) {
            throw new LuaError(TX + ": a transaction is open already — :transaction(fn, ...) does not nest: what"
                + " this fn would run is inside the open bracket as it is, and lands with it when the outer fn"
                + " returns. Call the inner fn directly");
        } catch(Db.Broken e) {
            throw new LuaError(TX + ": a statement inside fn ran past the timeout and was stopped, and that"
                + " ends the transaction — it is rolled back, and nothing fn ran after catching the error is"
                + " kept. Let that error out of fn, or keep the statement under the timeout"
                + " (-Dhaven.addon.sqlite.timeout, in milliseconds)");
        } catch(Failure e) {
            throw refused(e, TX);
        }
    }

    /**
     * {@code hafen.store():vacuum()} — rebuild the file, giving back the pages its deleted rows held, and
     * answer the store. Inside the file's own lock; refused inside a transaction naming it, because the
     * engine cannot vacuum inside one. Under no deadline: it is the client's own statement, sized by the
     * file, and stopping it half-way would be the one outcome nobody meant.
     */
    static LuaValue vacuum(Addon a, Varargs args) {
        LuaValue self = Args.only(args, 0, ACC + ":vacuum");
        Db db = require(a, VAC);
        try {
            db.vacuum();
        } catch(Db.Open e) {
            throw new LuaError(VAC + ": a transaction is open — the file cannot be rebuilt inside"
                + " :transaction(fn, ...); call :vacuum() after the bracket, from outside fn");
        } catch(Failure e) {
            throw refused(e, VAC);
        }
        return self;
    }

    /**
     * <b>The scan</b> of a statement's text, before it runs. It reads the tokens in order — the bare words
     * lower-cased, every quoted identifier ({@code "…"}, {@code `…`}, {@code […]}), each {@code ;}, and the
     * rest as the punctuation it is — skipping whitespace, both kinds of comment, and the inside of a
     * {@code '…'} literal, which is data. What it refuses is what the engine would run and the API does not
     * mean: a second statement, the two {@code CREATE}s that have a builder, the client's own {@code hafen_}
     * names, a transaction word and a {@code VACUUM} that have a verb. What the engine refuses on its own —
     * {@code ATTACH} beyond the limit, {@code load_extension} switched off — it refuses first, so the
     * message names the sandbox rather than quoting the driver.
     */
    static final class Scan {
        /** What a token is: a bare word, a quoted identifier, a {@code ;}, or a piece of punctuation. */
        private enum T { WORD, QUOTED, SEMI, OTHER }

        /**
         * The tokens in order — a word lower-cased, a quoted identifier as spelled, a piece of punctuation as
         * its one character and a literal or a number as {@code ""}.
         */
        private final List<T> kinds = new ArrayList<T>();
        private final List<String> texts = new ArrayList<String>();
        /** The bare words in order, lower-cased: keywords, function names and unquoted identifiers alike. */
        final List<String> words = new ArrayList<String>();
        /** Every quoted identifier, as spelled between its quotes. */
        final List<String> quoted = new ArrayList<String>();
        /** A token followed the {@code ;} that ended the statement. */
        boolean second;

        /** The first words of a transaction statement — the six the engine has, each a refusal here. */
        private static final List<String> TRANSACTION =
            java.util.Arrays.asList("begin", "commit", "end", "rollback", "savepoint", "release");

        /**
         * The cells the open reads and sets ({@link Db#Db}) — the file's {@code user_version}, which records
         * the shape of the client's own tables and decides whether a client opens the file at all, and the
         * connection's {@code journal_mode}, {@code synchronous} and {@code foreign_keys}. A {@code PRAGMA}
         * that writes one is refused, since the engine would let it through: a {@code user_version} of 9 is
         * a file no client opens again, and {@code journal_mode = DELETE} is a reader waiting on every write.
         */
        private static final List<String> CELLS =
            java.util.Arrays.asList("user_version", "journal_mode", "synchronous", "foreign_keys");

        Scan(String sql) {
            int n = sql.length(), i = 0;
            while(i < n) {
                char c = sql.charAt(i);
                if(Character.isWhitespace(c)) {
                    i++;
                } else if((c == '-') && (i + 1 < n) && (sql.charAt(i + 1) == '-')) {
                    int e = sql.indexOf('\n', i);
                    i = (e < 0) ? n : e + 1;
                } else if((c == '/') && (i + 1 < n) && (sql.charAt(i + 1) == '*')) {
                    int e = sql.indexOf("*/", i + 2);
                    i = (e < 0) ? n : e + 2;
                } else if(c == ';') {
                    token(T.SEMI, "");
                    i++;
                } else if(c == '\'') {
                    token(T.OTHER, "");
                    i = closing(sql, i, '\'');      // a literal: its text is data, not read
                } else if((c == '"') || (c == '`')) {
                    int e = closing(sql, i, c);
                    token(T.QUOTED, sql.substring(i + 1, Math.max(i + 1, Math.min(e - 1, n))));
                    i = e;
                } else if(c == '[') {
                    int e = sql.indexOf(']', i + 1);
                    token(T.QUOTED, sql.substring(i + 1, (e < 0) ? n : e));
                    i = (e < 0) ? n : e + 1;
                } else if(identStart(c)) {
                    int s = i;
                    while((i < n) && identChar(sql.charAt(i)))
                        i++;
                    token(T.WORD, sql.substring(s, i).toLowerCase(Locale.ROOT));
                } else if(Character.isDigit(c)) {
                    while((i < n) && (identChar(sql.charAt(i)) || (sql.charAt(i) == '.')))
                        i++;                        // a number, and the letters of its own (1e5, 0x1f)
                    token(T.OTHER, "");
                } else {
                    token(T.OTHER, String.valueOf(c));  // an operator, a bracket, a comma, a ?
                    i++;
                }
            }
            second = tail(bodyEnd());
        }

        private void token(T kind, String text) {
            kinds.add(kind);
            texts.add(text);
            if(kind == T.WORD)
                words.add(text);
            else if(kind == T.QUOTED)
                quoted.add(text);
        }

        /** Is token {@code i} the bare word {@code w}? */
        private boolean word(int i, String w) {
            return (i < kinds.size()) && (kinds.get(i) == T.WORD) && texts.get(i).equals(w);
        }

        /** Is token {@code i} a name — a bare word or a quoted identifier? */
        private boolean isName(int i) {
            return (i < kinds.size()) && ((kinds.get(i) == T.WORD) || (kinds.get(i) == T.QUOTED));
        }

        /** Is token {@code i} the piece of punctuation {@code p}? */
        private boolean punct(int i, String p) {
            return (i < kinds.size()) && (kinds.get(i) == T.OTHER) && texts.get(i).equals(p);
        }

        /**
         * The cell a {@code PRAGMA} statement writes, lower-cased, or {@code null} for every other statement
         * and for a {@code PRAGMA} that only reads. The name may be schema-qualified ({@code main.user_version})
         * and quoted; a write is the name followed by anything but the {@code ;} that ends the statement —
         * {@code = value} or {@code (value)}, both of which the engine takes.
         */
        private String pragmaWrite() {
            if(!word(0, "pragma"))
                return null;
            int k = 1;
            if(isName(k) && punct(k + 1, "."))
                k += 2;
            if(!isName(k))
                return null;
            boolean writes = (k + 1 < kinds.size()) && (kinds.get(k + 1) != T.SEMI);
            return writes ? texts.get(k).toLowerCase(Locale.ROOT) : null;
        }

        /**
         * The token just past the one statement's own body — {@code 0} for every statement but a trigger,
         * whose body is {@code BEGIN stmt; stmt; … END} with its {@code ;}s inside it: the {@code END} that
         * closes it is the one not paired with a {@code CASE}.
         */
        private int bodyEnd() {
            int k = 1;
            while(word(k, "temp") || word(k, "temporary"))
                k++;
            if(!word(0, "create") || !word(k, "trigger"))
                return 0;
            while((k < kinds.size()) && !word(k, "begin"))
                k++;
            int depth = 0;
            for(k++; k < kinds.size(); k++) {
                if(word(k, "case")) {
                    depth++;
                } else if(word(k, "end")) {
                    if(depth == 0)
                        return k + 1;
                    depth--;
                }
            }
            return kinds.size();                    // no END closes it: the driver says so
        }

        /** From token {@code i} on: a {@code ;} ends the statement, and any token after that is a second one. */
        private boolean tail(int i) {
            for(; i < kinds.size(); i++) {
                if(kinds.get(i) == T.SEMI)
                    return i + 1 < kinds.size();
            }
            return false;
        }

        /** The index just past the {@code q}-quoted run opening at {@code i}; a doubled {@code q} inside is one, an unterminated run ends the text. */
        private static int closing(String sql, int i, char q) {
            int n = sql.length();
            i++;
            while(i < n) {
                if(sql.charAt(i) == q) {
                    if((i + 1 < n) && (sql.charAt(i + 1) == q)) {
                        i += 2;
                        continue;
                    }
                    return i + 1;
                }
                i++;
            }
            return n;
        }

        private static boolean identStart(char c) {
            return Character.isLetter(c) || (c == '_') || (c >= 0x80);
        }

        private static boolean identChar(char c) {
            return identStart(c) || Character.isDigit(c) || (c == '$');
        }

        /** Refuse {@code sql} for {@code verb}, or let it through to the file. */
        static void check(String sql, String verb) {
            Scan s = new Scan(sql);
            if(s.words.isEmpty() && s.quoted.isEmpty())
                throw new LuaError(verb + ": the statement is empty — one SQL statement, such as"
                    + " \"SELECT count(*) FROM nodes WHERE kind = ?\", with one value per ? after it");
            if(s.second)
                throw new LuaError(verb + ": there is a second statement after the \";\" — one statement per"
                    + " call: the driver would run the first and drop the rest with nothing said. Run each"
                    + " in a call of its own");
            String first = s.words.isEmpty() ? "" : s.words.get(0);
            if(first.equals("create")) {
                // CREATE [TEMP | TEMPORARY] [UNIQUE] <what>: a table and an index have a builder; a view, a
                // trigger and a virtual table have none, and run here.
                int k = 1;
                while((k < s.words.size()) && (s.words.get(k).equals("temp") || s.words.get(k).equals("temporary")
                                               || s.words.get(k).equals("unique")))
                    k++;
                String what = (k < s.words.size()) ? s.words.get(k) : "";
                if(what.equals("table"))
                    throw new LuaError(verb + ": CREATE TABLE is refused here — a table of your own is declared"
                        + " through " + ACC + ":table(name), whose :column(name, type), :key(col, ...) and"
                        + " :index(col, ...) give its rows the types they come back with, and whose :create()"
                        + " makes it. A virtual table (CREATE VIRTUAL TABLE) has no columns to type, and runs"
                        + " here");
                if(what.equals("index"))
                    throw new LuaError(verb + ": CREATE INDEX is refused here — an index is declared on the"
                        + " table's declaration, " + ACC + ":table(name) … :index(col, ...), and its :create()"
                        + " makes it, on a table already in the file too");
            } else if(first.equals("attach") || first.equals("detach")) {
                throw new LuaError(verb + ": " + first.toUpperCase(Locale.ROOT) + " is refused: this connection"
                    + " is a sandbox with one file, your addon's own, and no other database is attached to it —"
                    + " every addon's data is its own file");
            } else if(first.equals("vacuum")) {
                throw new LuaError(verb + ": VACUUM is refused here — :vacuum() compacts the file in place,"
                    + " inside the store's own lock; VACUUM INTO would write a second file, and this connection"
                    + " is a sandbox with one");
            } else if(TRANSACTION.contains(first)) {
                throw new LuaError(verb + ": " + first.toUpperCase(Locale.ROOT) + " is refused: a transaction"
                    + " is :transaction(fn, ...), which brackets what fn runs — committed when fn returns,"
                    + " rolled back when it raises. A bracket a statement opened would outlive the frame");
            } else if(first.equals("pragma")) {
                String cell = s.pragmaWrite();
                if((cell != null) && CELLS.contains(cell))
                    throw new LuaError(verb + ": PRAGMA " + cell + " is the client's own cell — the client sets"
                        + " user_version, journal_mode, synchronous and foreign_keys as it opens the file, and"
                        + " a write to one of them is refused: it would undo the open, or leave a file no client"
                        + " opens again. A PRAGMA that reads answers through " + QUERY);
            }
            s.names(verb);
        }

        /**
         * Refuse a Table's clause for {@code verb}, or let it through to the file. A clause is what follows
         * {@code FROM <table>}, so the first-keyword refusals of {@link #check} have nothing to read and an empty
         * clause is the whole table; what still holds is the rest — a second statement after the {@code ;},
         * and every name.
         */
        static void clause(String sql, String verb) {
            Scan s = new Scan(sql);
            if(s.second)
                throw new LuaError(verb + ": there is a second statement after the \";\" — one statement per"
                    + " call: the clause is the tail of one SELECT, and the driver would run that and drop the"
                    + " rest with nothing said. Run the second in a call of :exec or :query of its own");
            s.names(verb);
        }

        /** Every bare word and every quoted identifier through {@link #name}. */
        private void names(String verb) {
            for(String w : words)
                name(w, verb);
            for(String q : quoted)
                name(q.toLowerCase(Locale.ROOT), verb);
        }

        /** Refuse an identifier of the client's own, or the one function the sandbox has switched off. */
        private static void name(String w, String verb) {
            if(w.startsWith("hafen_"))
                throw new LuaError(verb + ": \"" + w + "\" is under the hafen_ prefix, which is the client's own"
                    + " table — hafen_vars holds your vars, reached through " + ACC + ":var(name)"
                    + " and never through a statement. A table of yours is declared under another prefix");
            if(w.equals("load_extension"))
                throw new LuaError(verb + ": load_extension is refused: this connection is a sandbox, and no"
                    + " extension is loaded on it — the functions a statement has are SQLite's own");
        }
    }

    /**
     * One row of a result as a plain Lua table: a column the declaration names is typed by it, a column it
     * does not (one nobody declares any more, or one a clause joined in) comes as the file holds it, and a
     * {@code NULL} is an absent key.
     */
    private static LuaTable decodeRow(Addon owner, Decl d, Db.Rows rs, int i) {
        LuaTable t = new LuaTable();
        Object[] r = rs.rows.get(i);
        for(int c = 0; c < rs.columns.length; c++) {
            Column col = d.column(rs.columns[c]);
            LuaValue v = (col == null) ? raw(r[c]) : decode(owner, col.type, r[c]);
            if(!v.isnil())
                t.set((col == null) ? rs.columns[c] : col.name, v);
        }
        return t;
    }

    /**
     * A cell as its column's type reads it: a {@code boolean} is {@code true} or {@code false}, a
     * {@code json} the table that was put (a Position inside it comes back a Position), a number a number.
     * What the file holds that the type cannot read — text in an integer column, a json cell that does not
     * parse — comes as it is, exactly as an undeclared column does: one cell must not cost the row.
     */
    private static LuaValue decode(Addon owner, Type t, Object cell) {
        if(cell == null)
            return LuaValue.NIL;
        switch(t) {
        case TEXT:
            return (cell instanceof byte[]) ? LuaValue.valueOf(new String((byte[])cell, StandardCharsets.UTF_8))
                : LuaValue.valueOf(cell.toString());
        case INTEGER:
        case REAL: {
            Double n = number(cell);
            if(n == null)
                return raw(cell);
            double d = n.doubleValue();
            return ((d == Math.rint(d)) && (Math.abs(d) <= Args.EXACT)) ? LuaInteger.valueOf((long)d)
                : LuaValue.valueOf(d);
        }
        case BOOLEAN: {
            Double n = number(cell);
            return (n == null) ? raw(cell) : LuaValue.valueOf(n.doubleValue() != 0);
        }
        case JSON:
            if(cell instanceof String) {
                try {
                    return LuaMarshal.jsonToLua(Json.parse((String)cell), owner);
                } catch(RuntimeException e) {
                    return raw(cell);           // not a document: the text itself
                }
            }
            return raw(cell);
        }
        throw new IllegalStateException(t.toString());
    }

    /** A cell as a number, where it is one or spells one; else {@code null}. */
    private static Double number(Object cell) {
        if(cell instanceof Number)
            return Double.valueOf(((Number)cell).doubleValue());
        if(cell instanceof String) {
            try {
                return Double.valueOf(Double.parseDouble(((String)cell).trim()));
            } catch(NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * A cell as the wire reads it, with no declaration to say more: an integer a number (exact to
     * {@code 2^53}), a real a number, text a string, a blob its bytes as a string, {@code NULL} nil.
     */
    static LuaValue raw(Object cell) {
        if(cell == null)
            return LuaValue.NIL;
        if(cell instanceof Integer)
            return LuaValue.valueOf(((Integer)cell).intValue());
        if(cell instanceof Long)
            return LuaInteger.valueOf(((Long)cell).longValue());
        if(cell instanceof Number)
            return LuaValue.valueOf(((Number)cell).doubleValue());
        if(cell instanceof byte[])
            return LuaValue.valueOf(new String((byte[])cell, StandardCharsets.UTF_8));
        return LuaValue.valueOf(cell.toString());
    }

    /**
     * <b>A failure out of the driver</b>, unchecked, carrying its message. {@link Db} raises it in place of
     * every {@code SQLException}, so no caller outside {@link Db} names a {@code java.sql} type.
     */
    static class Failure extends RuntimeException {
        Failure(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /**
     * <b>One addon's open file.</b> The only class here that names an {@code org.sqlite} or {@code java.sql}
     * type; every method holds the monitor, so the connection sees one caller at a time.
     *
     * <p>The client's own table carries the {@code hafen_} prefix, which is what keeps it apart from anything
     * an addon declares: {@code hafen_vars}, and no other. A <b>scope</b> is a row key: {@code ""} for
     * the addon's own vars, the character's key ({@link StoreApi}'s {@code <genus>_<char>}) for that
     * character's. Where the user put the addon's windows and which action-bar slots hold its entries are not
     * here: they are the client's rows, in the client's own file ({@link ClientDb#placements},
     * {@link ClientDb#holds}).
     *
     * <p><b>A transaction is driven through the engine, never through JDBC's auto-commit</b>: {@code BEGIN},
     * {@code COMMIT} and {@code ROLLBACK} go through {@code DB.exec}, which is the one door the driver's own
     * auto-commit bookkeeping does not stand in — {@code setAutoCommit(false)} refuses inside a bracket the
     * engine already holds, and {@code setAutoCommit(true)} commits it under the caller, both verified. The
     * statements a bracket runs are prepared as usual; the driver's after-step {@code begin;}/{@code commit;}
     * pair fails to open a second transaction inside ours and leaves it be, which is also verified.
     */
    static final class Db {
        /** Where the file is — what {@code :info().file} answers. */
        final Path file;
        private final org.sqlite.SQLiteConnection conn;

        /**
         * When the running statement of the addon's own is to be stopped, in {@code System.nanoTime()} terms
         * — armed by {@link #arm} as one starts, {@link Long#MAX_VALUE} between statements and under the
         * client's own. The progress handler reads it on the engine's thread, which is this one.
         */
        private volatile long deadline = Long.MAX_VALUE;

        /**
         * A bracket ({@link #bracket}) is open, and the engine is inside its transaction. Read and written
         * under the monitor, which the bracket holds for its whole extent — so it is only ever {@code true}
         * for the thread inside the bracket.
         */
        private boolean open;

        /**
         * A statement inside the open bracket was stopped by the deadline, which ends the bracket: an
         * interrupted write inside an explicit transaction is rolled back by the engine with the whole
         * transaction (verified), and {@link #failure} opens another at once so that what {@code fn} runs
         * after catching the error lands in no file; an interrupted read leaves the transaction open, and the
         * bracket ends it the same way so that the rule is one. Either way the bracket rolls back at
         * {@code fn}'s return and refuses, rather than commit half.
         */
        private boolean broken;

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
                    if(have < SCHEMA) {
                        // A file an earlier schema wrote: the client's placements and holds were rows of it.
                        // They are the client's own file's now, so the two tables go; nothing is carried over.
                        st.execute("DROP TABLE IF EXISTS hafen_placements");
                        st.execute("DROP TABLE IF EXISTS hafen_holds");
                        // The vars' table was hafen_documents: the addon's own rows, so they are kept -- the
                        // table is renamed under them, before the create below finds the new name taken.
                        if(one(st, "SELECT count(*) FROM sqlite_master WHERE type = 'table'"
                               + " AND name = 'hafen_documents'") > 0)
                            st.execute("ALTER TABLE hafen_documents RENAME TO hafen_vars");
                        st.execute("PRAGMA user_version = " + SCHEMA);
                    }
                    st.execute("CREATE TABLE IF NOT EXISTS hafen_vars (scope TEXT NOT NULL, name TEXT NOT NULL,"
                        + " json TEXT NOT NULL, PRIMARY KEY (scope, name)) WITHOUT ROWID");
                }
                // The deadline: polled every 1000 virtual-machine steps of whatever statement is running,
                // and a 1 stops it with SQLITE_INTERRUPT -- the statement's own changes undone, the
                // connection as usable as before, verified. Between statements nothing is armed.
                org.sqlite.ProgressHandler.setHandler(c, 1000, new org.sqlite.ProgressHandler() {
                    protected int progress() {
                        return (System.nanoTime() > deadline) ? 1 : 0;
                    }
                });
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

        /** The one number a {@code PRAGMA} or a {@code count(*)} answers. */
        private static long one(java.sql.Statement st, String sql) throws java.sql.SQLException {
            try(java.sql.ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }

        /**
         * Run {@code w} as one transaction: committed when it returns, rolled back when it throws. Inside an
         * open bracket it runs bare — the bracket is the transaction, and {@code w}'s rows land or go with it.
         */
        private void transaction(Work w) {
            if(open) {
                try {
                    w.run();
                } catch(java.sql.SQLException e) {
                    throw failure(e);
                }
                return;
            }
            begin();
            try {
                w.run();
            } catch(java.sql.SQLException | RuntimeException e) {
                rollback();
                throw (e instanceof java.sql.SQLException) ? failure((java.sql.SQLException)e) : (RuntimeException)e;
            }
            commit();
        }

        // ---- the bracket: BEGIN, COMMIT and ROLLBACK through the engine's own door ------------------

        /** A statement the bracket runs through the engine: {@code BEGIN}, {@code COMMIT}, {@code ROLLBACK}, {@code VACUUM}. */
        private void engine(String sql) throws java.sql.SQLException {
            conn.getDatabase().exec(sql, false);
        }

        private void begin() {
            try {
                engine("BEGIN");
            } catch(java.sql.SQLException e) {
                throw failure(e);
            }
        }

        private void commit() {
            try {
                engine("COMMIT");
            } catch(java.sql.SQLException e) {
                rollback();
                throw failure(e);
            }
        }

        /** Roll the open transaction back — silently where there is none to roll back, which is the engine having done it first. */
        private void rollback() {
            try {
                engine("ROLLBACK");
            } catch(java.sql.SQLException e) {
                /* "cannot rollback - no transaction is active": an interrupted write already ended it */
            }
        }

        /** A second bracket while one is open, or a vacuum inside one — phrased by the verb that asked. */
        static final class Open extends Failure {
            Open() {
                super("a transaction is open", null);
            }
        }

        /** The bracket was rolled back by the engine before {@code fn} returned ({@link #broken}). */
        static final class Broken extends Failure {
            Broken() {
                super("the transaction was rolled back by the engine", null);
            }
        }

        /**
         * <b>The bracket</b>: {@code BEGIN}, then {@code body} — which is the addon's {@code fn}, so this is
         * the one place the monitor is held across Lua — then {@code COMMIT} when it returns with what it
         * answered, {@code ROLLBACK} and the error out when it throws. A body that runs a second bracket meets
         * {@link Open} at the door, on this same thread; a second thread's verb waits at the monitor until the
         * bracket is over, so nothing of another caller's lands inside it. Every statement inside runs under
         * its own deadline; a write the deadline stops ends the engine's transaction too, which the bracket
         * reports as {@link Broken} at {@code body}'s return rather than committing what came after.
         */
        synchronized <T> T bracket(java.util.function.Supplier<T> body) {
            if(open)
                throw new Open();
            begin();
            open = true;
            broken = false;
            T out;
            try {
                out = body.get();
            } catch(RuntimeException | Error e) {
                open = false;
                rollback();
                throw e;
            }
            open = false;
            if(broken) {
                rollback();
                throw new Broken();
            }
            commit();
            return out;
        }

        /**
         * Rebuild the file — {@code VACUUM}, which the attach limit refuses (verified: the rebuild attaches a
         * temporary database), so the limit is raised to one for this statement and put back after it.
         * Refused inside a bracket: the engine cannot vacuum inside a transaction.
         */
        synchronized void vacuum() {
            if(open)
                throw new Open();
            try {
                conn.setLimit(org.sqlite.SQLiteLimits.SQLITE_LIMIT_ATTACHED, 1);
                try {
                    engine("VACUUM");
                } finally {
                    conn.setLimit(org.sqlite.SQLiteLimits.SQLITE_LIMIT_ATTACHED, 0);
                }
            } catch(java.sql.SQLException e) {
                throw failure(e);
            }
        }

        // ---- the deadline and the row cap ----------------------------------------------------------

        /** The statement about to run is one of the addon's own: stop it {@link #TIMEOUT_MS} from now. */
        private void arm() {
            deadline = System.nanoTime() + TIMEOUT_MS * 1000000L;
        }

        /** The statement is over: nothing is under the deadline. */
        private void disarm() {
            deadline = Long.MAX_VALUE;
        }

        /** The statement ran past its deadline and was stopped. */
        static final class Timeout extends Failure {
            Timeout(Throwable cause) {
                super("the statement ran past the timeout", cause);
            }
        }

        /** The statement answers more rows than one call reads. */
        static final class Cap extends Failure {
            Cap() {
                super("the statement answers more than " + MAX_ROWS + " rows", null);
            }
        }

        /**
         * A driver failure as the {@link Failure} its caller phrases: the interrupt, which nothing but the
         * deadline raises on this connection, is a {@link Timeout} — and inside a bracket it marks the bracket
         * {@link #broken} — and the rest carry the driver's message.
         *
         * <p>An interrupted write took the engine's transaction with it, so a {@code BEGIN} is issued here,
         * before the error reaches Lua: what {@code fn} runs after catching it is then inside a transaction
         * again — one the bracket rolls back at its end — rather than landing in the file on its own. After
         * an interrupted read the engine's transaction is still open and the {@code BEGIN} fails, which is
         * the same state.
         */
        private Failure failure(java.sql.SQLException e) {
            if((e instanceof org.sqlite.SQLiteException)
               && (((org.sqlite.SQLiteException)e).getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_INTERRUPT)) {
                if(open) {
                    broken = true;
                    try {
                        engine("BEGIN");
                    } catch(java.sql.SQLException x) {
                        /* "cannot start a transaction within a transaction": the read left it open */
                    }
                }
                return new Timeout(e);
            }
            return new Failure(e.getMessage(), e);
        }

        // ---- the vars: one JSON row per var, keyed by scope and name (147: read when asked) -------------

        /** The names saved under one scope, in the file's own order — what {@code :list()} unions with the live tables (147). */
        synchronized List<String> names(String scope) {
            List<String> out = new ArrayList<String>();
            try(java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT name FROM hafen_vars WHERE scope = ?")) {
                ps.setString(1, scope);
                try(java.sql.ResultSet rs = ps.executeQuery()) {
                    while(rs.next())
                        out.add(rs.getString(1));
                }
            } catch(java.sql.SQLException e) {
                throw new Failure(e.getMessage(), e);
            }
            return out;
        }

        /** The JSON of one var, or {@code null} when nothing has been saved under that name. */
        synchronized String var(String scope, String name) {
            try(java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT json FROM hafen_vars WHERE scope = ? AND name = ?")) {
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
        synchronized void vars(final String scope, final Map<String, String> rows) {
            transaction(new Work() {
                public void run() throws java.sql.SQLException {
                    try(java.sql.PreparedStatement ps = conn.prepareStatement(
                            "INSERT OR REPLACE INTO hafen_vars (scope, name, json) VALUES (?, ?, ?)")) {
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

        // ---- statements: what the declared tables run, on the addon's own tables -------------------

        /**
         * A statement's result: the column labels, and each row's cells as the driver hands them —
         * {@code Integer} or {@code Long} for an integer, {@code Double}, {@code String}, {@code byte[]} for a
         * blob, {@code null} for {@code NULL}.
         */
        static final class Rows {
            final String[] columns;
            final List<Object[]> rows = new ArrayList<Object[]>();

            Rows(String[] columns) {
                this.columns = columns;
            }
        }

        /** The {@code ?} count of a statement and the values bound to it disagree — phrased by the verb that ran it. */
        static final class Arity extends Failure {
            final int want, got;

            Arity(int want, int got) {
                super(want + " parameters, " + got + " values", null);
                this.want = want;
                this.got = got;
            }
        }

        /**
         * Run a statement that answers rows, reading at most {@code max} of them ({@code -1} for all, up to
         * the row cap). The binds are {@link SqliteApi#bindable}'s kinds: {@code Long}, {@code Double},
         * {@code String}, {@code Integer} or {@code null}; their count is held to the statement's. Under the
         * deadline, as every statement of the addon's own is.
         */
        synchronized Rows select(String sql, Object[] binds, int max) {
            arm();
            try(java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, binds);
                try(java.sql.ResultSet rs = ps.executeQuery()) {
                    return read(rs, max);
                }
            } catch(java.sql.SQLException e) {
                throw failure(e);
            } finally {
                disarm();
            }
        }

        /**
         * At most {@code max} rows of {@code rs}, the cells as the driver hands them. {@code -1} is "all", and
         * all is at most {@link #MAX_ROWS}: one row past that is a {@link Cap}. A positive {@code max} is a
         * verb reading that deep on purpose ({@code :find} one row), and stops there with nothing said.
         */
        private static Rows read(java.sql.ResultSet rs, int max) throws java.sql.SQLException {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            String[] cols = new String[md.getColumnCount()];
            for(int i = 0; i < cols.length; i++)
                cols[i] = md.getColumnLabel(i + 1);
            Rows out = new Rows(cols);
            while(((max < 0) || (out.rows.size() < max)) && rs.next()) {
                if((max < 0) && (out.rows.size() >= MAX_ROWS))
                    throw new Cap();
                Object[] row = new Object[cols.length];
                for(int i = 0; i < cols.length; i++)
                    row[i] = rs.getObject(i + 1);
                out.rows.add(row);
            }
            return out;
        }

        // ---- statements of the addon's own (146.3): what a statement answers decides the verb -----

        /** What one statement answered: its rows, or — {@code rows == null} — how many rows it changed. */
        static final class Answer {
            final Rows rows;
            final long changed;

            Answer(Rows rows, long changed) {
                this.rows = rows;
                this.changed = changed;
            }
        }

        /**
         * The statement answers the other kind — rows where the verb answers a count, none where it answers
         * rows — phrased by the verb that ran it. {@code ran} says whether it ran before that was seen: it is
         * seen before the step wherever the driver's metadata says what the compiled statement answers, which
         * is every statement so far, and after it where {@code execute()} is the first to say.
         */
        static final class Kind extends Failure {
            final boolean ran;

            Kind(boolean ran) {
                super("the statement answers the other kind", null);
                this.ran = ran;
            }
        }

        /**
         * Run one statement of the addon's own, wanting {@code rows} or a change count. The binds are
         * {@link SqliteApi#bindable}'s kinds, the count held; a statement of the other kind is a
         * {@link Kind}, refused before it runs wherever the compiled statement says what it answers.
         *
         * <p><b>The change count is the engine's, counted for this statement alone</b>: the difference in
         * {@code total_changes()} across the step, which is what this statement inserted, updated or deleted
         * — its triggers and cascades included, and a virtual table module's own rows ({@code CREATE VIRTUAL
         * TABLE … USING fts5} writes three) — and {@code 0} for a statement that changes no row. The
         * driver's own {@code getUpdateCount()} is {@code changes()} read after the step, which is the count
         * of the last INSERT, UPDATE or DELETE whichever statement that was: a {@code DROP TABLE} would
         * answer the row count of the write before it.
         */
        synchronized Answer statement(String sql, Object[] binds, boolean rows, int max) {
            arm();
            try(java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, binds);
                if(answersRows(ps) != rows)
                    throw new Kind(false);
                long before = conn.getDatabase().total_changes();
                if(ps.execute() != rows)
                    throw new Kind(true);
                if(!rows)
                    return new Answer(null, conn.getDatabase().total_changes() - before);
                try(java.sql.ResultSet rs = ps.getResultSet()) {
                    return new Answer(read(rs, max), 0);
                }
            } catch(java.sql.SQLException e) {
                throw failure(e);
            } finally {
                disarm();
            }
        }

        /**
         * Does the compiled statement answer a result set? Known before it runs: the driver's metadata for a
         * prepared statement is its column list, read at prepare — and {@code execute()} answers exactly
         * "that list is not empty". A statement with no columns has nothing for the driver to count, and it
         * refuses to, which is the same answer.
         */
        private static boolean answersRows(java.sql.PreparedStatement ps) {
            try {
                return ps.getMetaData().getColumnCount() > 0;
            } catch(java.sql.SQLException e) {
                return false;
            }
        }

        /** Run a statement that answers no rows, and hand back how many it changed. */
        synchronized long change(String sql, Object[] binds) {
            arm();
            try(java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, binds);
                return ps.executeUpdate();
            } catch(java.sql.SQLException e) {
                throw failure(e);
            } finally {
                disarm();
            }
        }

        /** Run one DDL statement of the client's own building ({@code CREATE}, {@code ALTER}). */
        synchronized void ddl(String sql) {
            try(java.sql.Statement st = conn.createStatement()) {
                st.execute(sql);
            } catch(java.sql.SQLException e) {
                throw new Failure(e.getMessage(), e);
            }
        }

        /** Bind {@code binds} to the statement's {@code ?}s, the count held, each by its Java kind. */
        private static void bind(java.sql.PreparedStatement ps, Object[] binds) throws java.sql.SQLException {
            int want = ps.getParameterMetaData().getParameterCount();
            if(want != binds.length)
                throw new Arity(want, binds.length);
            for(int i = 0; i < binds.length; i++) {
                Object o = binds[i];
                if(o == null)
                    ps.setNull(i + 1, java.sql.Types.NULL);
                else if(o instanceof Long)
                    ps.setLong(i + 1, ((Long)o).longValue());
                else if(o instanceof Integer)
                    ps.setInt(i + 1, ((Integer)o).intValue());
                else if(o instanceof Double)
                    ps.setDouble(i + 1, ((Double)o).doubleValue());
                else
                    ps.setString(i + 1, o.toString());
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
