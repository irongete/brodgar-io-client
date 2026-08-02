package io.brodgar.addon;

import haven.WItem;
import haven.Widget;

import org.luaj.vm2.LuaValue;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * An adopted <b>widget model</b> (spec {@code 08-widget-replacement.md}, Phase 3b) — the Java half of
 * {@code hafen.ui.adopt(id)}. It wraps a live, <i>server-bound</i> {@link Widget} (identified by its server id,
 * the {@code desc.id} a {@link LuaWidgetObserver} hands out) so an addon can keep the real widget as a hidden
 * <b>model</b> and later present a custom <b>view</b> over it — the "wrap, don't reimplement" pattern (D-009).
 *
 * <p>The golden rule (verified, spec 08): a hidden server widget <b>stays bound to its id</b>, so it keeps
 * receiving {@code uimsg}/{@code addchild} (item adds, count/tooltip updates) and still routes its own
 * {@code wdgmsg} — delivery is by id, not by tree position or visibility. So {@code :hide()} turns the widget
 * into a perfect headless model: invisible + non-interactive, but fully live.
 *
 * <p>The Lua handle ({@link AddonManager#modelHandle}) exposes:
 * <ul>
 *   <li>{@code :hide()} / {@code :show()} — toggle the widget's visibility ({@link Widget#hide}/{@link Widget#show});
 *       {@link #hidden} records whether <i>we</i> hid it, so teardown can restore the native UI.</li>
 *   <li>{@code :visible()} — the widget's current visibility (boolean).</li>
 *   <li>{@code :raw()} — the server widget id (int): the facade-safe escape hatch (no raw Java object crosses
 *       into Lua — principle P1 / D-017). It is a {@code WidgetRef} usable with the id-based API.</li>
 *   <li>{@code :items()} — an array of {@code Item} snapshots (the same shape as {@code hafen.items.inventory}),
 *       read live off the widget's {@link WItem} children (empty for a non-inventory widget). <b>Read-only:</b>
 *       the mutating item verbs (take/drop/transfer/use) are outbound gameplay actions and belong to the gated
 *       actions tier (Phase 4, D-010/D-025) — they are deliberately NOT exposed here.</li>
 *   <li>{@code :onItemAdded(fn)} / {@code :onItemRemoved(fn)} — lifecycle callbacks; {@code fn(item)} gets the
 *       item snapshot. An item add/remove is a {@link WItem} create/{@code cdestroy} (not a {@code uimsg}), so
 *       {@link AddonManager#pollModels} diffs the {@code WItem} children each tick (identity-keyed {@link #items}
 *       cache), exactly like the buff/study adapters.</li>
 *   <li>{@code :onDestroy(fn)} — {@code fn()} fires once when the SERVER destroys the widget (detected by the same
 *       per-tick poll: its id no longer maps to this widget). The view must die with the model.</li>
 * </ul>
 *
 * <p><b>Ownership (P2).</b> The model is bridge-owned: it lives in a flat global list (polled each tick by
 * {@link AddonManager#pollModels}) plus the addon's owned-resource registry ({@link Addon#models}). It is dropped
 * when the server destroys the widget or when the addon is reloaded/disabled; {@link UiApi#teardownModels}
 * additionally <b>un-hides</b> a widget the addon had hidden, so disabling a UI-replacement addon restores the
 * stock window (spec 08). The {@link #alive} flag makes any late handle call or poll a no-op after that.
 *
 * <p><b>Threading.</b> Every operation — adopt, the handle methods, the per-tick poll, and the teardown un-hide —
 * runs on the UI thread while holding the {@code ui} monitor (the tick/draw/input/hotkey paths all do), so the
 * widget-tree reads and the {@code callLua} dispatches never race other Lua or the engine's own tree mutations.
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

    LuaValue onItemAdded, onItemRemoved, onDestroy;   // lifecycle callbacks (null = unset)

    /** Present items -> their last snapshot; identity-keyed ({@link WItem}s are compared by object identity). */
    final Map<WItem, LuaValue> items = new IdentityHashMap<WItem, LuaValue>();

    LuaModel(Addon owner, int id, Widget wdg) {
        this.owner = owner;
        this.id = id;
        this.wdg = wdg;
        this.hideTarget = wdg;                          // adopt: hide the widget itself; replace overrides this
        this.hideTargetOrigVisible = wdg.visible();
    }
}
