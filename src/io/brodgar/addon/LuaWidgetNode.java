package io.brodgar.addon;

import haven.Widget;

import org.luaj.vm2.LuaValue;

/**
 * A generic, read-only <b>widget-tree node</b> (spec {@code 20-widget-introspection.md}, W1) — the Java half of
 * {@code hafen.ui.root()} / {@code hafen.ui.node(id)} and every {@code WidgetNode} handed out by
 * {@code :children()}/{@code :parent()}. It wraps a live {@link Widget} so an addon can walk <i>any</i> widget's
 * children to arbitrary depth from Lua — the generic structural read that complements the typed adapters of
 * spec {@code 14} (which stay for hot/event-driven + private-field reads). Read-only: to <i>act</i>, read the
 * node's {@code :id()} and pass it to the gated {@code hafen.act.raw} (D-025) — no new action surface here.
 *
 * <p><b>Facade-safe (P1 / D-017).</b> The raw {@link haven.Widget} never crosses into Lua. The Lua handle
 * ({@link AddonManager#nodeHandle}) is a plain table of closures over this object; the table additionally carries
 * this {@code LuaWidgetNode} as an <b>opaque userdata</b> (the {@link #KEY} field) so {@code :same(other)} can
 * {@link #resolve} the other handle back to its wrapped widget and compare by reference. The userdata has no
 * metatable — no Java method is reachable from Lua, and it cannot be forged (the sandbox omits {@code luajava}) —
 * the same round-trip {@link LuaImage}/{@link LuaMarshal} use.
 *
 * <p><b>Transient, not owned (D-041).</b> Unlike models/ghosts/widgets, a {@code WidgetNode} is <b>not</b>
 * registered in the addon's owned-resource registry (P2) — a deep tree would mint thousands of nodes per
 * inspection. It is a <b>{@code GobRef}-style lazy handle</b>: cheap to make, it borrows the engine-owned widget
 * and checks liveness on every access ({@link AddonManager#nodeLive}, via
 * {@link Widget#hasparent(Widget) hasparent(ui.root)}). Once it detects the widget was detached from the tree
 * (destroyed), it <b>nulls its {@link #wdg} reference</b> — so a stashed node can't pin a dead subtree in memory —
 * and all accessors return {@code nil}/empty. No teardown hook, no leak.
 *
 * <p><b>Threading.</b> Every access runs on the UI thread under the {@code ui} monitor (same discipline as
 * {@link LuaModel} and the spec-14 adapters); {@code :children()} copies the child list under
 * {@code synchronized(ui)} before handing the array to Lua, so a walk never races tree mutation.
 */
public final class LuaWidgetNode {
    /** The handle-table field carrying this object as an opaque userdata (read by {@link #resolve}). */
    static final LuaValue KEY = LuaValue.valueOf("__wnode");

    /** The wrapped widget, or {@code null} once {@link AddonManager#nodeLive} detects it left the tree. */
    Widget wdg;

    LuaWidgetNode(Widget w) {
        this.wdg = w;
    }

    /**
     * Resolve a Lua value passed to {@code node:same(other)} back to its {@link LuaWidgetNode}: a
     * {@code WidgetNode} handle table (via its {@link #KEY} userdata field) or the raw backing userdata itself;
     * returns {@code null} for anything else (nil / a foreign value &rarr; {@code :same} is {@code false}).
     */
    static LuaWidgetNode resolve(LuaValue v) {
        if(v == null)
            return null;
        LuaValue u = v.istable() ? v.get(KEY) : v;
        if(u.isuserdata()) {
            Object o = u.touserdata();
            if(o instanceof LuaWidgetNode)
                return (LuaWidgetNode)o;
        }
        return null;
    }
}
