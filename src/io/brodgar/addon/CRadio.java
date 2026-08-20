package io.brodgar.addon;

import haven.Coord;
import haven.GOut;
import haven.RadioGroup;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.HashSet;
import java.util.LinkedHashMap;

/**
 * The adapter behind {@code hafen.ui():radio()} — ONE control, not a group object plus N buttons (spec
 * {@code 040-ui-controls}, task 040.5): {@code :rows{…}} builds a real {@link RadioGroup} of the client's own
 * {@code RadioButton}s, stacked downward from this control's own {@code :position}, one row height apart;
 * {@code RadioGroup}/{@code RadioButton} never surface in Lua — {@code :value(label)} is the one door to which
 * row is checked.
 *
 * <p><b>This control IS its own parent.</b> {@link RadioGroup}'s constructor takes the {@link Widget} its
 * buttons are added to, and that widget is {@code this}: a plain container with no single client counterpart
 * of its own (there is no engine class named "a radio", only the coordinator plus its buttons), so
 * {@code :type()} climbs past it to {@code "Widget"} exactly as it climbs past any other adapter (040.1).
 *
 * <p><b>{@code :value(v)} does NOT go through {@link RadioGroup#check}.</b> The engine's own
 * {@code check(RadioButton)} always calls the group's {@code changed(int, String)} hook — the only place a
 * user pick and a programmatic write could be told apart — so a write here flips the two
 * {@code RadioGroup.RadioButton#changed(boolean)} calls (the old one off, the new one on) directly, bypassing
 * {@code check()} and its hook. That is D-153's rule again, first pinned for {@link CCheck}: a direct write
 * never re-enters {@code :onChange}. The USER-driven half is untouched — {@code RadioButton.mousedown} still
 * calls the group's own {@code check(this)}, which is exactly what the overridden {@code changed(int, String)}
 * hook below exists to catch.
 *
 * <p><b>Re-{@code :rows{}} replaces the whole set.</b> {@code RadioGroup} keeps no way to remove a button once
 * added, so every existing one is destroyed and a fresh {@code RadioGroup} takes its place; the tree mutation
 * runs under the UI lock, the same discipline {@link Controls#text} follows for a live-resizing control.
 */
final class CRadio extends Widget implements Owned.Control, Controls.Value, Controls.Change, Controls.Rows {
    private final Owned.State own;
    private final LinkedHashMap<String, RadioGroup.RadioButton> byLabel
        = new LinkedHashMap<String, RadioGroup.RadioButton>();
    private RadioGroup group;
    private String checkedLabel;
    /** Exactly the table {@code :rows(t)} was last given — what the bare read hands back. */
    private LuaValue lastRows;

    CRadio(Addon owner) {
        this.own = new Owned.State(owner, this);
        this.group = newGroup();
    }

    public Owned.State own() {
        return own;
    }

    private RadioGroup newGroup() {
        return new RadioGroup(this) {
            /** A REAL user click only: RadioButton.mousedown -> check(this) -> here. A :value(v) write never
             *  reaches this — it flips the two buttons directly instead (see #value(LuaValue) below). */
            public void changed(int btn, String lbl) {
                checkedLabel = lbl;
                Controls.fire(CRadio.this, "Changed", LuaValue.valueOf(lbl));
            }
        };
    }

    /** {@code c:rows()} — exactly the table last given, or {@code null} before the first one. */
    public LuaValue rows() {
        return lastRows;
    }

    /**
     * {@code c:rows(t)} — an array of unique string labels; replaces the whole set of rows (040.5). Validated
     * BEFORE anything is torn down, so a bad table leaves the existing rows exactly as they were.
     */
    public void rows(LuaValue t) {
        if(!t.istable())
            throw new LuaError("widget:rows(t) on a radio is an ARRAY of row labels, got " + t.typename());
        int n = t.length();
        String[] labels = new String[n];
        HashSet<String> seen = new HashSet<String>();
        for(int i = 1; i <= n; i++) {
            // Args.str, which asks the TYPE: the hand-rolled test refused a label like "061.8", because in
            // LuaJ a string that scans as a number answers isnumber() — and a row so labelled is then
            // unreachable by :value(label) as well.
            String label = Args.str(t.get(i), "widget:rows", "row " + i,
                                    "a radio's rows are STRING labels").tojstring();
            if(!seen.add(label))
                throw new LuaError("widget:rows(t) on a radio: \"" + label + "\" is repeated — row labels must"
                    + " be unique, since :value(label) is how one is chosen");
            labels[i - 1] = label;
        }
        synchronized(LuaWidget.monitor(this)) {
            for(RadioGroup.RadioButton rb : byLabel.values())
                rb.destroy();
            byLabel.clear();
            checkedLabel = null;
            group = newGroup();
            int y = 0;
            for(String label : labels) {
                RadioGroup.RadioButton rb = group.add(label, Coord.of(0, y));
                byLabel.put(label, rb);
                y += rb.sz.y;              // one ROW HEIGHT apart -- the button's own natural height
            }
            pack();                        // :size() covers the whole stack (Widget.contentsz over the children)
        }
        lastRows = t;
    }

    /** {@code r:value()} — the checked row's label, or {@code null} if none is (an empty control, or unset). */
    public LuaValue value() {
        return (checkedLabel == null) ? null : LuaValue.valueOf(checkedLabel);
    }

    /** {@code r:value(v)} — {@code v} names one of {@link #rows}'s labels; an unknown one is refused naming them. */
    public void value(LuaValue v) {
        String label = Args.str(v, "widget:value", "v",
                                "the LABEL of one of this radio's rows").tojstring();
        RadioGroup.RadioButton rb = byLabel.get(label);
        if(rb == null)
            throw new LuaError("widget:value(v) on a radio — no row named \"" + label + "\"; this radio's rows"
                + " are " + rowNames());
        synchronized(LuaWidget.monitor(this)) {
            RadioGroup.RadioButton old = (checkedLabel != null) ? byLabel.get(checkedLabel) : null;
            if((old != null) && (old != rb))
                old.changed(false);
            rb.changed(true);
            checkedLabel = label;
        }
    }

    private String rowNames() {
        if(byLabel.isEmpty())
            return "empty — :rows{} was never given any";
        StringBuilder sb = new StringBuilder();
        for(String label : byLabel.keySet()) {
            if(sb.length() > 0)
                sb.append(", ");
            sb.append('"').append(label).append('"');
        }
        return sb.toString();
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(g);
    }
}
