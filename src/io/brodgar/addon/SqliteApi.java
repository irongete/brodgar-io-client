package io.brodgar.addon;

import haven.Coord;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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
 * {@code java.sql} fails to link {@link Db} inside {@link #open}'s own guard and lands in the unavailable
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
 *
 * <p><b>The declared tables</b> (146.2) are the addon's own record: {@code hafen.store():table(name)} is a
 * bare {@link Declaration}, configured by {@code :column}, {@code :key} and {@code :index} and dispatched
 * by {@code :create()}, which is the one call that touches the file and answers the interned {@link Table}.
 * A Table's rows go in and come out <b>typed by its live declaration</b> — the only place the Lua types
 * exist: a {@code boolean} is an {@code INTEGER} in the file and a {@code json} a {@code TEXT}, and the
 * file itself says nothing about which column is which. So a declaration is re-read every load, and the
 * {@code :create()} that re-declares an existing table replaces the Table's declaration in place and adds
 * what the file lacks, which is how a record evolves.
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
                + " tables (your documents and remembered placements live there) — " + NAME_RULE
                + ", under any other prefix";
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
            // The store's own rule for what a document may hold, walked here for the same reason flush()
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

    /** The clause a {@code :list}, {@code :count} or {@code :find} was handed, or nothing; a function is refused naming SQL. */
    private static String clause(Decl d, Varargs a, String verb) {
        if(!Args.passed(a, 2) || a.arg(2).isnil())
            return "";
        LuaValue c = a.arg(2);
        if(c.type() != LuaValue.TSTRING)
            throw new LuaError(verb + ": the clause is SQL — what follows FROM " + d.table + ", such as"
                + " \"WHERE kind = ? ORDER BY x\", with one value per ? after it — got " + c.typename());
        return " " + c.tojstring();
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
        } catch(Db.Arity e) {
            throw new LuaError(verb + ": the statement has " + e.want + ((e.want == 1) ? " ?" : " ?s") + " and "
                + e.got + ((e.got == 1) ? " value was" : " values were") + " passed — one value per ?");
        } catch(Failure e) {
            throw new LuaError(verb + ": " + Refusal.reason(e));
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
         * Run a statement that answers rows, reading at most {@code max} of them ({@code -1} for all). The
         * binds are {@link SqliteApi#bindable}'s kinds: {@code Long}, {@code Double}, {@code String},
         * {@code Integer} or {@code null}; their count is held to the statement's.
         */
        synchronized Rows select(String sql, Object[] binds, int max) {
            try(java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, binds);
                try(java.sql.ResultSet rs = ps.executeQuery()) {
                    java.sql.ResultSetMetaData md = rs.getMetaData();
                    String[] cols = new String[md.getColumnCount()];
                    for(int i = 0; i < cols.length; i++)
                        cols[i] = md.getColumnLabel(i + 1);
                    Rows out = new Rows(cols);
                    while(((max < 0) || (out.rows.size() < max)) && rs.next()) {
                        Object[] row = new Object[cols.length];
                        for(int i = 0; i < cols.length; i++)
                            row[i] = rs.getObject(i + 1);
                        out.rows.add(row);
                    }
                    return out;
                }
            } catch(java.sql.SQLException e) {
                throw new Failure(e.getMessage(), e);
            }
        }

        /** Run a statement that answers no rows, and hand back how many it changed. */
        synchronized long change(String sql, Object[] binds) {
            try(java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, binds);
                return ps.executeUpdate();
            } catch(java.sql.SQLException e) {
                throw new Failure(e.getMessage(), e);
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
