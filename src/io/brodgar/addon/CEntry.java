package io.brodgar.addon;

import haven.Coord;
import haven.GOut;
import haven.ReadLine;
import haven.TextEntry;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

/**
 * The adapter behind {@code hafen.ui():entry()} — a real {@link TextEntry}, the client's own (spec
 * {@code 040-ui-controls}, task 040.7): its content is WRITTEN through {@code :value(s)}, the ONE door
 * (decision A) — {@code entry:text(s)} is retired, throwing and naming it, through the existing {@link Retired}
 * table. The READ half of {@code :text()} is untouched: it keeps answering best-effort, exactly as it always
 * has on every text-bearing widget ({@code docs/addons/api/ui/widget.md}), because that read is documented as
 * NEVER throwing and a tree-walking introspector ({@code widgetstack}) depends on that for every widget it
 * finds — retiring it too would have broken a published contract this feature does not own. The two
 * notifications are separate names on purpose: {@link #onChange} per keystroke, {@link #onSubmit} once, on Enter.
 *
 * <p><b>{@code :value(v)} does NOT go through {@link ReadLine#setline}.</b> The engine's own {@code Base.setline}
 * (and {@code TextEntry.settext}, which calls it) notifies {@code owner.changed(this)} whenever the line actually
 * differs — the one hook a real edit fires through — so writing a value that way would re-enter
 * {@code :onChange} for a value the addon itself just wrote, exactly the feedback loop D-150's whole roster has
 * to not have. {@link TextEntry#rsettext} is the way out: it replaces the {@link ReadLine} buffer outright
 * (the same thing the constructor does) rather than editing the live one, so it notifies nothing — D-153's direct
 * write, one level further in because this control's state is a buffer object rather than a field.
 *
 * <p><b>The user-driven half is the engine's own two hooks, kept apart.</b> {@link #changed(ReadLine)} is
 * {@link ReadLine.Owner}'s per-edit callback — every real keystroke that changes the buffer, PCLine and EmacsLine
 * alike — and fires {@link #onChange}. {@link #activate(String)} is what {@link TextEntry#done} calls on Enter
 * ({@code Widget.key_act} matching inside {@code ReadLine.key2}) and also what a global-key activation
 * ({@code gkeytype}) calls; overriding it outright (rather than relying on {@code canactivate}, which defaults
 * {@code false} and would otherwise just swallow the gesture) is what turns it into {@link #onSubmit} and keeps
 * this control client-side — nothing here ever calls {@code wdgmsg}.
 *
 * <p>{@link CtlButton}'s shape otherwise: the ownership contract over one {@link Owned.State}, and the one
 * override every adapter owes the engine — {@link #draw(GOut)} skips the paint while {@link Owned#pending()
 * pending}. No {@code resize()} override: {@link TextEntry} is a plain {@code Widget} subclass, not an
 * {@link haven.SIWidget}, and its own {@code resize(int)} already redraws itself.
 */
final class CEntry extends TextEntry implements Owned.Control, Controls.Value, Controls.Change, Controls.Submit {
    /** A default DESIGN width, at the engine's own height ({@code mext}'s); {@code :size(w, h)} overrides it. */
    static final int DEF_W = 160;

    private final Owned.State own;

    CEntry(Addon owner) {
        super(Px.in(DEF_W), "");
        this.own = new Owned.State(owner, this);
    }

    public Owned.State own() {
        return own;
    }

    /**
     * <b>The field's own art</b> (058.4): {@link TextEntry#mext}'s height — the stretched middle the
     * constructor sizes itself to — and {@link TextEntry#wmarg}, the two end caps a narrower field would
     * overlap. The same design pair on every client ({@code 17 x 20}).
     */
    public Coord minsz() {
        return Coord.of(wmarg, mext.sz().y);
    }

    /** {@code e:value()} — the entry's current content. */
    public LuaValue value() {
        return LuaValue.valueOf(text());
    }

    /** {@code e:value(v)} — {@code v} must be a string; replaces the buffer outright, so it does NOT fire :onChange. */
    public void value(LuaValue v) {
        if(!v.isstring() || v.isnumber())   // in LuaJ a number IS a string -- that one is just the wrong type
            throw new LuaError("widget:value(v) on a text entry is a STRING, got " + v.typename());
        rsettext(v.tojstring());
    }

    /** A real edit (any keystroke that changes the buffer, {@code ReadLine.Owner}'s own per-edit hook). */
    public void changed(ReadLine buf) {
        super.changed(buf);
        Controls.fire(this, "Changed", LuaValue.valueOf(text()));
    }

    /** Enter ({@code TextEntry.done}), or a global-key activation ({@code gkeytype}) — fired once, never to the server. */
    public void activate(String text) {
        Controls.fire(this, "Submitted", LuaValue.valueOf(text));
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(g);
    }
}
