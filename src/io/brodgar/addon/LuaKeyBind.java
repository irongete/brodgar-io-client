package io.brodgar.addon;

import haven.KeyBinding;
import haven.KeyMatch;
import haven.Widget;

import org.luaj.vm2.LuaValue;

/**
 * A <b>global hotkey</b> binding (spec {@code 07-ui-and-drawing.md} "Input") — the Java half of
 * {@code hafen.client():options():keybindings():on(name, fn)}, Phase 2e-2. It pairs a client
 * {@link KeyBinding} (remappable + persisted in the client prefs under {@code keybind/addon/<id>/<name>}) with
 * the addon's Lua handler.
 *
 * <p>Dispatch is the engine's built-in global-hotkey seam, so it needs <b>zero core edit</b> (exactly as 2c's
 * input hooks reuse {@link Widget#listen}): {@link haven.UI#keydown} fires a {@link Widget.GlobKeyEvent} — but
 * only after an unconsumed focused {@code KeyDownEvent}, so a hotkey <b>never fires while a text field has
 * focus</b> — and that event walks the widget tree calling {@link Widget#globtype} on each widget. The
 * addon-root widget ({@link AddonRoot#globtype}) overrides {@code globtype} to match every registered
 * {@code LuaKeyBind} against the event and, on a match, run the handler through {@link AddonManager#callLua}
 * (watchdog-armed, error-isolated, CPU-accounted). Being an early child of {@code ui.root}, the addon-root is
 * walked <b>last</b>, so a client binding on the same key is offered the press first.
 *
 * <p>The walk is the order, not the verdict: the key the user <b>assigns</b> here is claimed in
 * {@link KeyBinding}, and every other binding yields that exact key+modifiers for as long as the claim
 * stands — including one whose own match ignores a modifier the press carries, which is how the action
 * menu's Shift-agnostic hotkeys answered an assigned {@code Shift+B}. On a key nobody assigned it, an addon
 * hotkey is still the fallback, never a hijack.
 *
 * <p>The addon never sees this object: it names the action, holds the {@link LuaSub} that
 * {@code keybindings:on} handed back — this object hangs off that sub's {@link LuaSub#tag} (086.1) — and ends
 * it with {@code sub:off()}; the bridge owns the bind for teardown ({@link Addon#keybinds}, principle P2). The {@link #alive} flag makes a
 * dispatch that races teardown a no-op. The {@link KeyBinding} itself is process-global and persistent (that is
 * how the client remembers a re-mapped key), so teardown drops this handler wrapper and never the binding or
 * the user's assignment — but it does {@link KeyBinding#release} the <b>claim</b>, so while nothing here
 * answers the key answers to whoever else wants it, and {@link HookApi#newKeyBind} takes it back on the next
 * declaration. A {@code :reload} is that release and that hold with no press possible in between.
 */
public final class LuaKeyBind {
    final Addon owner;
    final String name;          // the addon-local binding name (for diagnostics)
    final KeyBinding binding;   // the client binding (remappable, persisted); binding.key() is the live match
    final LuaValue fn;          // the Lua handler fn()
    boolean alive = true;

    LuaKeyBind(Addon owner, String name, KeyBinding binding, LuaValue fn) {
        this.owner = owner;
        this.name = name;
        this.binding = binding;
        this.fn = fn;
    }

    /**
     * Does this binding's current key match the pressed key? {@link KeyBinding#key()} is the user's re-mapped
     * key if set, else the default supplied at {@code bind()} time; a {@link KeyMatch#nil} default (unbound)
     * never matches. {@link KeyMatch#match(Widget.KbdEvent)} handles the modifier + char/code comparison.
     */
    boolean matches(Widget.GlobKeyEvent ev) {
        KeyMatch km = binding.key();
        return (km != null) && km.match(ev);
    }
}
