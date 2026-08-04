package io.brodgar.addon;

import java.util.HashMap;
import java.util.Map;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.TwoArgFunction;

/**
 * The <b>retired-name table</b> (spec {@code 039-uniform-api} §2.10): every spelling the uniform grammar
 * replaced, mapped to a message that <b>names its replacement</b>, and the {@code __index} metamethods that
 * throw it.
 *
 * <p><b>Why it exists.</b> Deleting a field from a Lua table makes it read as plain {@code nil}, so the old
 * spelling fails later and elsewhere as <i>"attempt to call a nil value"</i> — a message that names neither
 * the verb nor the file that used it. Across a surface this size that is the difference between porting an
 * addon and hunting one. So a retired name is not absent: it is a field read that <b>throws</b>, at the exact
 * line that wrote it, saying what to write instead.
 *
 * <p><b>Two levels, one table.</b> A retired <i>section</i> is keyed {@code "hafen.<name>"} and hangs off the
 * {@code hafen} table's own {@code __index}; a retired <i>verb</i> is keyed {@code "hafen.<section>.<name>"}
 * and hangs off that section's callable table. Anything not in the table reads as plain {@code nil}, so a
 * feature probe ({@code if hafen.something then}) keeps working and only a name that genuinely moved is loud.
 *
 * <p>The entries are pure data, generated from the feature's before/after inventory, so coverage is
 * mechanical rather than remembered: a spelling that moved with no row here is a porting error nobody is
 * told about.
 */
final class Retired {
    private Retired() {
    }

    /** {@code "hafen.events"} / {@code "hafen.time.clock"} → the message naming the replacement. */
    private static final Map<String, String> NAMES = new HashMap<String, String>();

    static {
        // ---- sections whose NAME changed (§2.3: the three surviving plurals go singular) ----------------
        put("hafen.events", "hafen.events is now hafen.event() — subscribe with hafen.event():on(name, fn)");

        // ---- the eight verb-only sections: every dotted verb is now a colon call on the section ---------
        section("time", "clock", "dayFraction", "isNight", "season", "moon", "yearFraction");
        section("slash", "register");
        section("json", "parse", "encode");
        section("hook", "input", "action", "message", "grab");
        section("timer", "after", "every");

        // http keeps its verb names but loses its options table, so the message says both halves.
        put("hafen.http.get", "hafen.http.get(url, opts, cb) is now hafen.http():get(url, cb) — opts.headers"
            + " and opts.timeout are setters on the request it hands back: req:header(name, value),"
            + " req:timeout(ms)");
        put("hafen.http.post", "hafen.http.post(url, body, opts, cb) is now hafen.http():post(url, body, cb)"
            + " — opts.headers and opts.timeout are setters on the request it hands back:"
            + " req:header(name, value), req:timeout(ms)");
    }

    /** Register the plain {@code hafen.<section>.<verb>(…)} → {@code hafen.<section>():<verb>(…)} rows. */
    private static void section(String section, String... verbs) {
        for(String verb : verbs) {
            put("hafen." + section + "." + verb,
                "hafen." + section + "." + verb + "(…) is now hafen." + section + "():" + verb + "(…)");
        }
    }

    private static void put(String name, String message) {
        NAMES.put(name, message);
    }

    /** The message for a retired spelling, or {@code null} when the name was never registered. */
    static String message(String name) {
        return NAMES.get(name);
    }

    /**
     * The {@code __index} for the {@code hafen} table itself: a retired <b>section</b> name throws naming its
     * replacement, and every other miss reads as plain {@code nil} (so a feature probe still works).
     */
    static LuaValue hafenIndex() {
        return index("hafen");
    }

    /**
     * The {@code __index} for one section's callable table: a retired <b>verb</b> of that section throws
     * naming its replacement, every other miss reads as plain {@code nil}. This is what makes the cut visible
     * from Lua at all — {@code hafen.time.clock} is a field read, and the refusal hangs off the read.
     */
    static LuaValue sectionIndex(String section) {
        return index("hafen." + section);
    }

    /** The shared metamethod: {@code prefix + "." + key} in the table throws, anything else reads nil. */
    private static LuaValue index(final String prefix) {
        return new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue key) {
                if(key.isstring()) {
                    String msg = NAMES.get(prefix + "." + key.tojstring());
                    if(msg != null)
                        throw new LuaError(msg);
                }
                return LuaValue.NIL;
            }
        };
    }

    /** Hang {@link #hafenIndex()} on the {@code hafen} table (from {@code installHafen}, once per env). */
    static void install(LuaTable hafen) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, hafenIndex());
        hafen.setmetatable(mt);
    }
}
