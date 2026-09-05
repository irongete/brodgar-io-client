package io.brodgar.addon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.TwoArgFunction;

/**
 * The <b>refusal</b> — what this API says when a name it does not answer is written, and the
 * {@code __index} metamethods that throw it.
 *
 * <p><b>Why it exists.</b> A name a table does not carry reads as plain {@code nil}, so the mistake fails
 * later and elsewhere as <i>"attempt to call a nil value"</i> — a message that names neither the verb nor the
 * file that wrote it. Across a surface this size that is the difference between fixing an addon and hunting
 * one. So an unknown name is not absent: it <b>throws</b>, at the exact line that wrote it, saying what the
 * receiver does answer — and where a live spelling was reached through the wrong door, saying which door.
 *
 * <p><b>The vocabulary is closed.</b> {@link #closedIndex} is the {@code __index} an entity hangs off its
 * methods table and {@link #closedFields} the same for an anonymous shape: a key the table carries costs
 * nothing, and every other key raises listing what the receiver answers. That is the whole of the mechanism,
 * and it needs no row below it to work.
 *
 * <p><b>Three tables sharpen it</b>, because a name that <i>moved</i> deserves better than the generic list
 * — though two of the three carry no rows today, as the paragraph after them says:
 *
 * <ul>
 *   <li>{@link #MOVED} — a section keyed {@code "hafen.<name>"}, a verb {@code "<entity>:<name>"}, a shape's
 *       field {@code "<shape>.<field>"}, each mapped to the message naming its replacement. <b>No row is
 *       declared</b>, so every name reads as plain {@code nil} at the section level and a feature probe
 *       ({@code if hafen.something then}) keeps working.</li>
 *   <li>{@link #eventKey} — an event key is a string ARGUMENT ({@code hafen.event():on("Key", fn)}), so no
 *       field read can carry the refusal and no {@code __index} can be hung off it. The emitter consults this
 *       one at the door, before it decides whether the key is one it answers. <b>No row is declared</b>, so
 *       what an unknown key meets is the emitter's own generic refusal.</li>
 *   <li>{@link #MISPLACED} — <b>live</b> spellings, not moved ones. Where a namespace splits, half its verbs
 *       grow an address and half keep their global spelling, and the mistake goes both ways: a sweep that
 *       addresses {@code hafen.ui():window()} writes code that compiles, runs and is wrong. So a verb that
 *       KEPT its spelling carries a row too, firing from the side it is absent from and saying which half it
 *       is in. Being live, those spellings stay out of {@link #MOVED} — the list a documentation sweep
 *       derives to check that no dead name is written on a page.</li>
 * </ul>
 *
 * <p><b>{@link #MOVED} and {@link #KEYS} carry no rows, and the doors that read them stand unguarded.</b>
 * Nothing generates rows and nothing {@code put}s one there, so every read of those two misses and falls
 * through — to plain {@code nil} for a section name and a dotted verb, to the generic refusal for an event
 * key. That is the decision of {@code 11bf2871c} and it is in force: <b>nothing is published</b>, so a hard
 * cut needs no row, and the row would be a message for a caller that never existed. The doors stay because
 * the day something IS published a moved spelling needs somewhere to be answered, and a table is cheaper to
 * fill than a metamethod is to add back. {@link #MISPLACED} is the one that carries rows, and what it
 * carries is <b>live</b> spellings, not moved ones.
 *
 * <p>A row keys on a <b>name</b>, which is the whole of what a row can carry — a changed argument, return
 * or payload shape has nothing to hang one off, and needs a refusal written inside the verb itself.
 */
final class Refusal {
    private Refusal() {
    }

    /**
     * A section {@code "hafen.<name>"}, a verb {@code "<entity>:<name>"}, a shape's field
     * {@code "<shape>.<field>"} — each mapped to the message naming its replacement. <b>No row is
     * declared</b> (see the class javadoc), so every read of it misses and the three doors below answer
     * plain {@code nil}.
     */
    private static final Map<String, String> MOVED = new HashMap<String, String>();

    /**
     * Moved <b>event keys</b> (spec {@code 041-unified-events}), keyed {@code "<emitter>|<key>"} — the third
     * kind of moved spelling and the one that is not a field read at all: a key is an ARGUMENT to {@code :on},
     * so nothing can hang off reading it and the refusal has to happen where the key is accepted. The emitter
     * checks this table before it checks its own vocabulary, so a moved key would say what it is now instead
     * of falling into the generic "unknown event" refusal. <b>No row is declared</b> (see the class javadoc),
     * so every key the client does not fire meets that generic refusal, moved spellings included.
     */
    private static final Map<String, String> KEYS = new HashMap<String, String>();

    /**
     * <b>Live spellings reached through the wrong door</b> (078.2) — keyed exactly as {@link #MOVED} is, and
     * consulted after it. A verb of a namespace that SPLIT is missing from one of its two section objects,
     * and reading it there would otherwise fail with the generic "has no verb", which says the call is wrong
     * without saying which half the verb is in. <b>Nothing here has moved</b>: every name in this map is a
     * spelling a page is supposed to write, which is why it is not in {@link #MOVED}.
     */
    private static final Map<String, String> MISPLACED = new HashMap<String, String>();

    static {
        String twoTrees = " — your window and the client's window are not the same thing, and they stand in"
            + " two trees";
        uiKept("window", "builds a window of YOURS, in the addon layer above every session" + twoTrees);
        uiKept("widget", "builds a bare container of YOURS, in the addon layer" + twoTrees);
        uiKept("overlay", "is the collection of the HUD painters YOUR addon installed" + twoTrees);
        uiKept("sheet", "is a declaration of rules owned by your addon, applied live to whatever matches in"
               + " every session at once — a theme is not one character's");
        uiKept("mouse", "is the POINTER, and there is one pointer however many characters are logged in");
        uiKept("hit", "hit-tests a point on the SCREEN, and there is one coordinate space");
        uiKept("tipAt", "asks who would speak for a point on the SCREEN");
        uiKept("scale", "is the device factor the client is running at");
        for(String c : new String[] {"button", "label", "entry", "check", "radio", "slider", "scroll",
                                     "scrollbar", "dropdown", "menu", "listbox", "table", "grid", "image",
                                     "progress", "separator"})
            uiKept(c, "mints a control of YOURS, in the addon layer" + twoTrees);
        // 111.1: the console splits by DIRECTION rather than by tree. Registering a command is client-wide
        // (Console.setscmd is static, so one name answers from every character) and stays here; SAYING a line
        // is one character's, because UI.cons is a WidgetConsole per UI and `:lo` closes the session whose
        // tree holds it. Nothing moved — this is the half that never existed on this door, and without the
        // row an author meets `has no verb 'run'`, which says the call is wrong without saying what is right.
        MISPLACED.put("hafen.console():run",
                      "hafen.console() has no verb 'run': a console line belongs to a CHARACTER, so"
                      + " s:console():run(line) is the verb and the session is the address —"
                      + " hafen.session():current():console():run(\"lo\") says it at the character on screen."
                      + " hafen.console() is the commands your addon REGISTERS, which are client-wide.");
    }

    /**
     * One verb of {@code hafen.ui} that <b>kept</b> its global spelling (078.2). The row is keyed the same way
     * a moved one is, and fires from the other side: the verb is absent from the session's own section, so
     * {@code s:ui():window()} lands here instead of on a bare "has no verb". This is the half a sweep gets
     * wrong — moving too much compiles, runs, and is wrong — so the refusal says which half the verb is in.
     */
    private static void uiKept(String verb, String why) {
        MISPLACED.put("hafen.ui():" + verb, "s:ui():" + verb + "(…) does not exist: hafen.ui():" + verb
            + "(…) " + why
            + ". The session's half of hafen.ui is the widgets THE CLIENT put up — :match, :matchAll, :on,"
            + " :root, :node, :inventory and :equipment.");
    }

    /**
     * The message for a moved event key on {@code emitter} ({@code "hafen.event()"}), or {@code null} when
     * that spelling was never one. Consulted by the emitter's {@code :on} before its own key set.
     */
    static String eventKey(String emitter, String key) {
        return KEYS.get(emitter + "|" + key);
    }

    /**
     * The message for a spelling this table answers for, or {@code null} when the name was never registered:
     * a moved one first ({@link #MOVED}), then a live one read through the wrong door ({@link #MISPLACED}).
     */
    static String message(String name) {
        String m = MOVED.get(name);
        return (m != null) ? m : MISPLACED.get(name);
    }

    /**
     * The {@code __index} for the {@code hafen} table itself: a moved <b>section</b> name throws naming its
     * replacement, and every other miss reads as plain {@code nil} (so a feature probe still works).
     */
    static LuaValue hafenIndex() {
        return index("hafen");
    }

    /**
     * The {@code __index} for one section's callable table: a moved <b>verb</b> of that section throws
     * naming its replacement, every other miss reads as plain {@code nil}. This is what makes the cut visible
     * from Lua at all — a dotted spelling is a field read, and the refusal hangs off the read.
     */
    static LuaValue sectionIndex(String section) {
        return index("hafen." + section);
    }

    /**
     * The {@code __index} for one <b>entity</b>'s metatable: a live verb answers, a moved one throws naming
     * its replacement, and <b>anything else throws too</b>, naming what this type does answer. The moved rows
     * are keyed {@code "<entity>:<verb>"} ({@code "gob:pos"}), which is how they are spelled at the call site
     * that has to be fixed.
     *
     * <p><b>Why an object has no third outcome, where a section has.</b> A section reads a miss as plain
     * {@code nil} so a feature probe ({@code if hafen.something then}) keeps working — but a probe is asked of
     * the {@code hafen} table, never of an object already in hand. On an object a name that is not a verb is a
     * typo, and answering it with silence (and "attempt to call a nil value" one character later) is the worst
     * answer available: it names neither the verb nor the line that wrote it. So the vocabulary is
     * <b>closed</b>, and the refusal carries two things: the {@code blurb} written beside the methods table,
     * saying what the type is for, and <b>the verb list built from that very table</b> at refusal time. The
     * sentence is what a reader needs and cannot be derived; the list can, so nothing keeps a copy of it in
     * step by hand and the message cannot name a verb the receiver has lost or miss one it has gained.
     *
     * <p>The same shape {@link Section} and {@link LuaCollection} already have, one level down: it throws on a
     * FIELD read ({@code x.nosuch}) exactly as on a call ({@code x:nosuch()}), because LuaJ routes both here.
     * {@code __name}/{@code __tostring} are exempt — a metamethod is {@code rawget} off the metatable.
     */
    static LuaValue closedIndex(String entity, LuaTable methods, String blurb) {
        return closedIndex(entity, methods, blurb, null);
    }

    /**
     * {@link #closedIndex} plus a {@code note} printed after the verb list — for the one thing about a type
     * that the list cannot say: which verb dispatches a builder, why a neighbouring verb is absent, what an
     * argument means. Everything a reader can read off the vocabulary belongs in the vocabulary.
     */
    static LuaValue closedIndex(final String entity, final LuaTable methods, final String blurb,
                                final String note) {
        return new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue key) {
                LuaValue m = methods.rawget(key);
                if(!m.isnil())
                    return m;
                if(key.isstring()) {
                    String msg = MOVED.get(entity + ":" + key.tojstring());
                    if(msg != null)
                        throw new LuaError(msg);
                }
                String verbs = members(methods, ":", "()");
                throw new LuaError(entity + " has no verb '" + key.tojstring() + "' — " + blurb
                    + (verbs.isEmpty() ? "" : ": it answers " + verbs)
                    + ((note == null) ? "" : "; " + note));
            }
        };
    }

    /**
     * The members of {@code t}, alphabetical, as an English list — {@code ":a() :b() and :c()"} for a
     * vocabulary, {@code ".w and .h"} for a shape. Built <b>at refusal time off the very table the refusal
     * guards</b>, which is the whole point: a hand-written copy drifts the moment a verb is added or dropped,
     * and a message that names a verb the receiver has not got sends the reader somewhere there is nothing.
     * Alphabetical because the order has to come from somewhere and a table's own is a hash order.
     *
     * <p>It costs nothing until something is already going wrong: this runs on the refusal path only.
     */
    /**
     * The vocabulary a methods table carries, as {@code ":a() :b() and :c()"} — the door for a receiver that
     * builds its own {@code __index} ({@link Section}, {@link LuaCollection}) rather than hanging
     * {@link #closedIndex} off one. Same list, same source of truth, so the two shapes of refusal cannot
     * disagree about what a type answers.
     */
    static String vocabulary(LuaTable methods) {
        return members(methods, ":", "()");
    }

    private static String members(LuaTable t, String prefix, String suffix) {
        List<String> names = new ArrayList<String>();
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs n = t.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            if(k.isstring())
                names.add(k.tojstring());
        }
        Collections.sort(names);
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < names.size(); i++) {
            if(i > 0)
                sb.append((i == names.size() - 1) ? " and " : " ");
            sb.append(prefix).append(names.get(i)).append(suffix);
        }
        return sb.toString();
    }

    /**
     * The {@code __index} of an anonymous <b>shape</b> table (085.3) — the data-table sibling of
     * {@link #closedIndex}. LuaJ consults {@code __index} only for a key the table does <b>not</b> carry, so a
     * real field costs nothing and every other key raises: one this API moved by name ({@code size.x} &rarr;
     * "a size is {w=, h=}") with that name, and any other with <b>the fields the table in hand actually
     * carries</b>, read off it rather than off a list written beside it.
     *
     * <p>Hang it off <b>one</b> metatable per shape, built once as a static and shared by every table of that
     * shape: a per-call metatable would double the allocation on a path ({@code w:size()} inside a draw
     * callback) whose whole cost is meant to be two field writes. {@code pairs}, {@code next} and
     * {@link Json#write} walk the raw fields and never reach here.
     */
    static LuaValue closedFields(final String shape) {
        return new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue key) {
                if(key.isstring()) {
                    String msg = MOVED.get(shape + "." + key.tojstring());
                    if(msg != null)
                        throw new LuaError(msg);
                }
                String fields = self.istable() ? members(self.checktable(), ".", "") : "";
                throw new LuaError("a " + shape + " table has no field '" + key.tojstring() + "'"
                    + (fields.isEmpty() ? "" : " — a " + shape + " carries " + fields));
            }
        };
    }

    /** The shared metamethod: {@code prefix + "." + key} in the table throws, anything else reads nil. */
    private static LuaValue index(final String prefix) {
        return new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue key) {
                if(key.isstring()) {
                    String msg = MOVED.get(prefix + "." + key.tojstring());
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
