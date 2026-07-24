package io.brodgar.addon;

import org.luaj.vm2.LuaValue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A <b>widget replacer</b> (spec {@code 08-widget-replacement.md}, Phase 3c) — the Java half of
 * {@code hafen.ui.replace(type, opts, fn)}. It is the high-level sugar over 3a (observe) + 3b (adopt): watch for a
 * server widget matching a <b>descriptor</b> (D-024), then adopt the real widget as a hidden {@link LuaModel} and
 * hand the addon's builder {@code fn(model)} a chance to draw a custom <b>view</b> over it — the "wrap, don't
 * reimplement" pattern (D-009). The real widget stays server-bound (so its {@code uimsg}/{@code wdgmsg} keep
 * working), just hidden; the addon's view reads {@code model:items()} and delegates.
 *
 * <p>Matching happens on two paths, both funnelling through {@link AddonManager}:
 * <ul>
 *   <li><b>Creation</b> — when a new server widget is placed ({@code UI.AddWidget.run} → {@link
 *       AddonManager#onWidgetPlaced}), its full descriptor is checked against every replacer (the same dispatch a
 *       3a observer rides). This catches a target opened <i>after</i> the replacer is registered.</li>
 *   <li><b>Scan</b> — at registration ({@link AddonManager#newReplacer}) the live widget tree is swept once for an
 *       <i>already-open</i> match — the {@code :reload} case, where the target (e.g. the main inventory) was
 *       created before the freshly-rebuilt addon layer existed, so no creation event will fire for it.</li>
 * </ul>
 *
 * <p>The matching criteria (from {@code opts}): {@link #type} (the server type string, required — e.g. {@code
 * "inv"}); {@link #context} (a semantic selector — {@code "main"} = the main inventory, {@code GameUI.maininv});
 * {@link #caption} (an exact window title, for titled containers); and {@link #matchFn} (an escape-hatch predicate
 * {@code match(desc)->truthy}, given the best-effort descriptor). Type + context pre-filter; caption + matchFn
 * refine.
 *
 * <p><b>Ownership (P2).</b> Bridge-owned: registered in a flat global list in {@link AddonManager} plus the addon's
 * {@link Addon#replacers}. {@link #handled} guards against re-firing for a widget id already replaced; {@link
 * #active} tracks the live models this replacer created (so {@code :remove()} can restore the native window +
 * destroy the view). Teardown (reload/disable) marks it dead and stops matching; the adopted models are un-hidden
 * by {@link AddonManager}'s model teardown and the views destroyed with the addon's other widgets. The {@link
 * #alive} flag makes a dispatch that races teardown a no-op.
 */
public final class LuaReplacer {
    final Addon owner;
    final String type;        // the server type string to match (e.g. "inv")
    String context;           // semantic selector, e.g. "main" (the main inventory) — nullable
    String caption;           // exact window caption to match (titled containers) — nullable
    LuaValue matchFn;         // optional predicate match(desc) -> truthy — nullable
    LuaValue builderFn;       // the view builder fn(model) -> view handle (required)
    boolean alive = true;     // false once torn down (reload/disable) or :remove()d

    /** Server widget ids this replacer has already replaced (so a re-scan/placement does not double-fire). */
    final Set<Integer> handled = ConcurrentHashMap.newKeySet();
    /** Live models this replacer created (for {@code :remove()} — restore the native window + destroy the view). */
    final List<LuaModel> active = new CopyOnWriteArrayList<LuaModel>();

    LuaReplacer(Addon owner, String type) {
        this.owner = owner;
        this.type = type;
    }

    boolean handled(int id) {
        return handled.contains(Integer.valueOf(id));
    }
}
