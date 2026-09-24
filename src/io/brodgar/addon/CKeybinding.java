package io.brodgar.addon;

import haven.Coord;
import haven.GOut;
import haven.KeyBinding;
import haven.OptWnd;
import haven.Widget;

import java.awt.event.KeyEvent;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

/**
 * The adapter behind {@code hafen.ui():keybinding()} — the client's own key button, {@link OptWnd.SetButton}, the very
 * class each row of Options ▸ Game ▸ Keybindings ends in, joined by {@code widget:bind(binding)} to one of the hotkeys
 * its addon declared (spec {@code 163-keybinding-control}). The user presses it, then a key, and the key is assigned
 * exactly as on that row: the capture, Escape, Backspace, Delete and the exclusive, persisted edit are
 * {@code SetButton}'s and {@code KeyMatch.Capture}'s own, inherited unchanged.
 *
 * <p>{@link CtlButton}'s shape: the ownership contract over one {@link Owned.State}, the pending draw, the disabled
 * face, the {@code resize} redraw, the art's {@link #minsz}. What it adds is the binding: {@code cmd} is the
 * {@link KeyBinding} it is joined to, {@code null} while none, and a press starts a capture only while that binding's
 * hotkey is live in its addon ({@link #live}). {@code :type()} reads {@code "SetButton"}: {@link LuaWidget#typeName}
 * climbs past an {@link Owned.Control}.
 *
 * <p>The press: {@code Changed} fires once per press that moves the key, with the key it now shows ({@code nil} for
 * unbound), after {@code Capture.keydown} has closed the grab, so a handler may unbind or destroy the button. A capture
 * ends by itself, nothing assigned and nothing fired, as soon as the button stops answering ({@link #answers}):
 * disabled, unbound, hidden, or its hotkey ended.
 */
final class CKeybinding extends OptWnd.SetButton implements Owned.Control, Controls.Value, Controls.Change {
    /** The Keybindings panel's own width for a key button ({@code BindingPanel.addbtn}'s {@code UI.scale(175)}), DESIGN px. */
    static final int DEF_W = 175;

    /** {@code widget:value(v)} on a key button — yours ({@link #value(LuaValue)}) and the client's ({@link LuaWidget}). */
    static final String VALUE_REFUSAL = "widget:value(v) on a key button is refused: the key it shows is its"
        + " binding's — binding:key(key) under client.settings writes it, and a press on the button is the user's"
        + " own edit. widget:value() reads the key it shows.";

    /** {@code widget:text(s)} on a key button ({@link Controls#text}). */
    static final String TEXT_REFUSAL = "widget:text(s) on a key button is refused: its caption is the key it shows,"
        + " and it follows the binding — a line beside it is a label: hafen.ui():label():text(s)";

    private final Owned.State own;

    /** What {@code Button.disable} was last told — {@code dis} is private there, and {@code disable} always redraws. */
    private boolean disabled;

    /** A press that moved the key, fired as Changed once the grab has closed — Java null while none, NIL for unbound. */
    private LuaValue moved;

    CKeybinding(Addon owner, int w) {
        super(w, null);
        this.own = new Owned.State(owner, this);
    }

    public Owned.State own() {
        return own;
    }

    /** The button's own art, as {@link CtlButton#minsz}: the two caps' widths, and {@code hs} (never the tall face). */
    public Coord minsz() {
        return Coord.of(bl.getWidth() + br.getWidth(), lg ? hl : hs);
    }

    // ------------------------------------------------------------------ the binding (widget:bind)

    /** Is the hotkey this button is joined to live in its addon right now? {@code false} while unbound. */
    boolean live() {
        KeyBinding kb = cmd;
        if(kb == null)
            return false;
        for(LuaKeyBind h : own.owner.keybinds) {
            if(h.alive && (h.binding == kb))
                return true;
        }
        return false;
    }

    /** Would a press here reach the key: joined to a live hotkey, enabled through every Owned above it, and shown? */
    boolean answers() {
        return live() && Owned.effective(this) && tvisible();
    }

    /** {@code widget:bind()} — the Binding it is joined to, the object {@code keybindings:binding():get(name)} hands out. */
    LuaValue bound() {
        KeyBinding kb = cmd;
        return (kb == null) ? LuaValue.NIL : LuaBinding.of(own.owner, kb.id);
    }

    /**
     * The hotkey {@code v} names — a live one of {@code owner}'s — or the refusal naming what to do instead
     * (spec 163, criterion 3). Touches nothing, so a refused bind changes nothing. The order is the plan's: not a
     * Binding, then the addon's own prefix (live, else ended), then another addon's, then the client's, then a
     * handle taken before {@code keybindings:on} declared the name.
     */
    static KeyBinding hotkey(Addon owner, LuaValue v) {
        LuaBinding h = LuaBinding.resolve(v);
        if(h == null) {
            LuaOption o = LuaOption.resolve(v);
            if(o != null)
                throw new LuaError("widget:bind(binding): a key button joins one of your hotkeys, and '" + o.name
                    + "' is an option — hafen.ui():check(), :slider(), :dropdown(), :radio() or :entry() shows an"
                    + " option, and a key button takes the Binding keybindings:binding():get(name) hands you once"
                    + " keybindings:on(name, fn) has declared it");
            throw new LuaError("widget:bind(binding): a key button joins one of your hotkeys, the Binding"
                + " keybindings:binding():get(name) hands you once keybindings:on(name, fn) has declared it — got "
                + v.typename());
        }
        String id = h.id;
        String mine = HookApi.keyBindIdPrefix(owner.manifest.id);
        if(id.startsWith(mine)) {
            for(LuaKeyBind k : owner.keybinds) {
                if(k.alive && k.binding.id.equals(id))
                    return k.binding;
            }
            String name = id.substring(mine.length());
            throw new LuaError("widget:bind(binding): your hotkey '" + name + "' has ended — sub:off() ended it, or"
                + " your addon has not declared it since it reloaded. keybindings:on(\"" + name + "\", fn) declares it"
                + " again, and this Binding answers it at once.");
        }
        if(id.startsWith("addon/"))
            throw new LuaError("widget:bind(binding): '" + id + "' is another addon's hotkey — the user assigns its key"
                + " in Options ▸ Game ▸ Keybindings, or on that addon's own page. A key button joins a hotkey your own"
                + " addon declared with keybindings:on(name, fn).");
        if(KeyBinding.get(id) != null)
            throw new LuaError("widget:bind(binding): '" + id + "' is one of the client's own bindings — the user"
                + " assigns its key in Options ▸ Game ▸ Keybindings, and binding:key(key) under client.settings is the"
                + " write your code makes. A key button joins a hotkey your own addon declared with"
                + " keybindings:on(name, fn).");
        throw new LuaError("widget:bind(binding): the Binding '" + id + "' names no hotkey of yours — it was taken"
            + " before keybindings:on declared it, so it is the registry id as written. Take it after keybindings:on(\""
            + id + "\", fn): keybindings:binding():get(\"" + id + "\") then answers your own hotkey.");
    }

    /** {@code widget:bind(binding)}, {@code kb} from {@link #hotkey}: join it, and show its key at once. Caller holds the monitor. */
    void bind(KeyBinding kb) {
        cancel();           // 163.2: a capture belongs to the binding it began on
        this.cmd = kb;
        follow();
    }

    /** {@code widget:bind(nil)}: joined to nothing, the key it shows left shown. Caller holds the monitor. */
    void unbind() {
        cancel();           // 163.2: unbound, the capture ends and nothing is assigned
        this.cmd = null;
    }

    // ------------------------------------------------------------------ the value

    /** {@code widget:value()} — the key it shows, spelled as {@code binding:key()} spells it; {@code nil} for unbound. */
    public LuaValue value() {
        return LuaBinding.keyName(key);
    }

    /** {@code widget:value(v)} — refused: the key is its binding's, and {@code binding:key(key)} is the write. */
    public void value(LuaValue v) {
        throw new LuaError(VALUE_REFUSAL);
    }

    // ------------------------------------------------------------------ what the engine asks

    /** A press starts a capture only while the button answers; while one is open, a press is what closes it. */
    public void click() {
        if(capturing() || answers())
            super.click();
    }

    /**
     * A capture that no longer answers ends by itself; then the key the binding holds is shown, drawn or hidden, so
     * {@code :value()} is never a frame behind.
     */
    public void tick(double dt) {
        super.tick(dt);
        if(capturing() && !answers())
            cancel();
        follow();
    }

    /**
     * A key pressed while capturing. Where the button no longer answers, the capture ends and nothing is assigned —
     * answering false, because the grab is already closed. Otherwise the client's own handling runs, and a key that
     * moved is kept for {@link #keydown} to fire once the grab has closed.
     */
    protected boolean handle(KeyEvent ev) {
        if(!answers()) {
            cancel();
            return(false);
        }
        KeyBinding kb = cmd;
        LuaValue before = LuaBinding.keyName(kb.key());
        boolean done = super.handle(ev);
        LuaValue after = LuaBinding.keyName(kb.key());
        if(!after.eq_b(before))
            moved = after;
        return(done);
    }

    /** {@code Changed} fires here, after {@code Capture.keydown} has closed the grab, so a handler may unbind or destroy the button. */
    public boolean keydown(KeyDownEvent ev) {
        boolean took = super.keydown(ev);
        LuaValue key = moved;
        if(key != null) {
            moved = null;
            Controls.fire(this, LuaOption.CHANGED, key);
        }
        return(took);
    }

    /** {@code widget:tooltip(s)} replaces the client's own tip ({@code kbtt}); {@code ""} nulls the field and brings it back. */
    public Object tooltip(Coord c, Widget prev) {
        return (tooltip != null) ? tooltip : super.tooltip(c, prev);
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        boolean dis = !Owned.effective(this);   // the client's disabled face follows the EFFECTIVE state (139.3)
        if(dis != disabled) {
            disabled = dis;
            disable(dis);
        }
        AddonText.enter();
        try {
            super.draw(Owned.dim(this, g));
        } finally {
            AddonText.exit();
        }
    }

    public void resize(Coord sz) {
        super.resize(sz);
        redraw();           // SIWidget caches the rasterised face; a resize without this keeps the old picture
    }
}
