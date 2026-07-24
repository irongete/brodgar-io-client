package haven;

import java.util.List;

/**
 * addon: package-scoped accessors for the AddOn widget-tree read adapters
 * ({@code io.brodgar.addon}, spec {@code 14-widget-tree-reads.md}).
 *
 * <p>Much high-value client state lives in {@link GameUI} widget trees whose fields are
 * {@code private}/{@code protected} (audit B5), so the bridge cannot read them from its own package.
 * Rather than raw reflection, the adapters go through this single {@code haven}-package helper — the
 * same trick {@link SpeakerIcon} uses for the buddy label. It localizes the one non-zero-edit read
 * surface in one place: upstream churn breaks this file, not every adapter, and Lua never gets
 * reflection (decision D-017).
 *
 * <p>All methods are pure reads, tolerate {@code null}, and never throw {@code Loading} — a partial
 * read is reported as {@code null} to the adapter.
 */
public final class AddonWidgets {
    private AddonWidgets() {
    }

    /**
     * The bar segments of a {@link LayerMeter} (the {@code protected meters} list), backing
     * {@code hafen.player.vitals}. Each {@link LayerMeter.Meter} carries a fraction {@code a} (0..1)
     * and a colour; a vital bar (hp/stamina/energy) has a single segment. Never {@code null}.
     */
    public static List<LayerMeter.Meter> meters(LayerMeter m) {
        return (m == null) ? java.util.Collections.<LayerMeter.Meter>emptyList() : m.meters;
    }
}
