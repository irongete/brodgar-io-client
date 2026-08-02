package io.brodgar.addon;

import haven.Widget;

/**
 * One live <b>widget replacement</b> (spec {@code 08-widget-replacement.md}, Phase 3c) — {@code hafen.ui.replace}'s
 * internal bookkeeping. It pairs a live, <i>server-bound</i> {@link Widget} (by its server id) with the native
 * window that was hidden to make room and with the addon's own view, so the whole thing can be undone exactly:
 * "wrap, don't reimplement" (D-009).
 *
 * <p>The golden rule (verified, spec 08): a hidden server widget <b>stays bound to its id</b>, so it keeps
 * receiving {@code uimsg}/{@code addchild} (item adds, count/tooltip updates) and still routes its own
 * {@code wdgmsg} — delivery is by id, not by tree position or visibility. That is what makes a hidden window a
 * perfect headless model: invisible + non-interactive, but fully live.
 *
 * <p><b>Nothing of this reaches Lua any more (029.3).</b> The builder {@code fn(model)} is handed the
 * {@link LuaWidget} <b>entity</b> for the replaced widget — {@code :items()}, {@code :onItemAdded}/
 * {@code :onItemRemoved}/{@code :onDestroy}, {@code :hide()}/{@code :show()} and every read live there, and answer
 * for ANY container, not just a replaced one. The bespoke model handle (and {@code hafen.ui.adopt}, and
 * {@code :raw()}) are gone: this class is now purely the undo record.
 *
 * <p><b>Ownership (P2).</b> The model is bridge-owned: it lives in a flat global list (checked each tick by
 * {@link UiApi#pollModels} for a server destroy) plus the addon's owned-resource registry ({@link Addon#models}).
 * It is dropped when the server destroys the widget or when the addon is reloaded/disabled;
 * {@link UiApi#teardownModels} additionally <b>un-hides</b> the window the addon hid, so disabling a
 * UI-replacement addon restores the stock window (spec 08). The {@link #alive} flag makes any late poll a no-op.
 *
 * <p><b>Threading.</b> Every operation — the replace itself, the per-tick poll, and the teardown un-hide — runs on
 * the UI thread while holding the {@code ui} monitor (the tick/draw/input/hotkey paths all do), so the widget-tree
 * reads and the {@code callLua} dispatches never race other Lua or the engine's own tree mutations.
 */
public final class LuaModel {
    final Addon owner;
    final int id;          // the server widget id (the desc.id the observer handed out)
    final Widget wdg;      // the adopted, server-bound widget
    boolean alive = true;  // false once the server destroys it or the addon is torn down
    boolean hidden;        // did WE hide {@link #hideTarget}? (teardown restores only what we hid)

    /**
     * The widget that {@code :hide()}/{@code :show()} and teardown actually toggle. For {@code hafen.ui.adopt}
     * (3b) it is {@link #wdg} itself (hide the grid). For {@code hafen.ui.replace} (3c) it is the <b>native
     * window</b> wrapping the widget (the "Inventory" {@code Hidewnd} around {@code maininv}), so replacing hides
     * the whole stock window, not just its content. {@link #hideTargetOrigVisible} is its visibility before we hid
     * it, so teardown restores it exactly (a window hidden-by-default is put back to hidden, not shown).
     */
    Widget hideTarget;
    boolean hideTargetOrigVisible;

    /**
     * {@code hafen.ui.replace} only: the addon's custom view (the content behind the {@link LuaWidget} entity its
     * {@code fn(model)} builder returned — 029.2; it used to be that builder's table handle), and the replacer that
     * created this model. The view is auto-destroyed and the replacer notified when the model dies (server-destroy
     * in {@link UiApi#pollModels}, or {@code :remove()}). {@code null} if the builder returned no view.
     */
    AddonWidget replaceView;
    LuaReplacer fromReplace;

    LuaModel(Addon owner, int id, Widget wdg) {
        this.owner = owner;
        this.id = id;
        this.wdg = wdg;
        this.hideTarget = wdg;                          // adopt: hide the widget itself; replace overrides this
        this.hideTargetOrigVisible = wdg.visible();
    }
}
