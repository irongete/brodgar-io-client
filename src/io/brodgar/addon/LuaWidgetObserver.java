package io.brodgar.addon;

import haven.Widget;

import org.luaj.vm2.LuaValue;

/**
 * A <b>widget-creation observer</b> (spec {@code 08-widget-replacement.md}, Phase 3a) — the Java half of
 * {@code hafen.ui.onWidgetCreate(fn)}. Every server-created widget flows through {@code UI.NewWidget.run}
 * (construction + id binding) and then {@code UI.AddWidget.run} (placement into a parent). The two core edits
 * there let the bridge watch that stream: {@code NewWidget.run} records the widget's server <i>type name</i>
 * (e.g. {@code "inv"}, {@code "epry"}, {@code "wnd"}) and {@code AddWidget.run} calls
 * {@link AddonManager#onWidgetPlaced} once the widget is in the tree — the first moment the <b>full</b>
 * descriptor exists (placement supplies the {@code place}-string + parent that pure creation lacks).
 *
 * <p>The {@code desc} handed to {@code fn(desc)} (the targeting descriptor, D-024):
 * <ul>
 *   <li>{@code desc.id} — the server widget id (int); the stable handle for the later adopt/replace slices.</li>
 *   <li>{@code desc.type} — the registered server type name (string; {@code nil} if the widget was built from a
 *       {@link Widget.Factory} directly rather than a type string, or created before this observer registered).</li>
 *   <li>{@code desc.place} — the placement string the server used ({@code pargs[0]}; e.g. {@code "inv"},
 *       {@code "equ"}, {@code "misc"}), or {@code nil} when the first placement argument is not a string (e.g. an
 *       item added to an inventory at a grid {@code Coord}).</li>
 *   <li>{@code desc.caption} — best-effort window title: the {@code cap} of the widget when it is itself a
 *       {@link haven.Window} (so a titled container like a cupboard reports its caption), else {@code nil}
 *       (a bare widget such as {@code Inventory} is wrapped by the engine in a titled window it does not own,
 *       so identify those by {@code type}/{@code place}).</li>
 *   <li>{@code desc.parentType} — the parent widget's class simple name (string; {@code "GameUI"} for every
 *       HUD-placed window — the {@code place}-routed inventory/equipment/character-sheet/etc.).</li>
 * </ul>
 *
 * <p>This slice is <b>observe-only</b>: the return value of {@code fn} is ignored (adopting the real widget as a
 * hidden model + presenting a custom view is Phase 3b/3c). The Lua call goes through {@link AddonManager#callLua}
 * — watchdog-armed (D-018), error-isolated, CPU-accounted. The addon only ever sees an opaque {@code :remove()}
 * handle; the bridge owns the observer and unregisters it on reload/disable (principle P2). The {@link #alive}
 * flag makes a dispatch that races teardown a no-op.
 *
 * <p><b>Threading.</b> {@link AddonManager#onWidgetPlaced} runs inside {@code AddWidget.run}'s
 * {@code synchronized(ui)} block — on a Loader thread, but under the same monitor the tick and draw hold — so
 * the observer's Lua never races any other Lua (the same discipline as an L3 {@link LuaMessageHook}). Keep
 * handlers light: they run inline with server widget placement while holding the UI monitor.
 */
public final class LuaWidgetObserver {
    final Addon owner;
    final LuaValue fn;     // the Lua handler fn(desc)
    boolean alive = true;

    LuaWidgetObserver(Addon owner, LuaValue fn) {
        this.owner = owner;
        this.fn = fn;
    }

    /**
     * Build the {@code desc} for one placed widget and run this observer's {@code fn(desc)}. Called by
     * {@link AddonManager#onWidgetPlaced} from inside {@code AddWidget.run}'s {@code synchronized(ui)} block. A
     * {@code null} field is left absent (Lua {@code nil}) rather than a placeholder, so the addon tests it the
     * idiomatic way ({@code if desc.caption then ... end}).
     */
    void invoke(int id, String type, String place, String caption, String parentType) {
        AddonManager.callLua(owner, Addon.C_HOOK, fn, AddonManager.descTable(id, type, place, caption, parentType));
    }
}
