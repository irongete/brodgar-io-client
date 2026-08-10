package io.brodgar.addon.ui;

import haven.Button;
import haven.Coord;
import haven.Label;
import haven.Scrollport;
import haven.UI;
import haven.Widget;
import haven.Window;

import io.brodgar.addon.Permission;
import io.brodgar.addon.PermissionSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The <b>enable-time permission consent dialog</b> (task 4c; {@code decisions.md} D-027, and the
 * "exact wording and placement of the permission notice" the security spec
 * {@code 12-security-and-permissions.md} left open). When the user ticks the enable checkbox of an
 * addon that declared any protected permission in the {@link AddonPanel}, this confirm appears
 * first and spells out what the addon will be able to do on the player's behalf. The addon is enabled
 * (persisted; applied on reload, D-006) <b>only</b> if the user clicks <b>Enable</b> — <b>Cancel</b> or
 * the close box leaves it disabled, and confirming records what was consented to, so a manifest that
 * later asks for MORE comes back and asks again.
 *
 * <p><b>It enumerates</b> (050.2): one plain-language line per DECLARED ENTRY, read off the
 * {@link Permission} catalogue, with a group ({@code item.*}) rendered as the one line it is written as
 * rather than expanded into its members. That is the whole point of the catalogue replacing a single coarse
 * tier — <i>the permission the user grants is the list they read</i> — so this window says what an addon
 * asked for instead of making one blanket statement that over-warns about a movement addon and under-warns
 * about one sending raw widget messages. The lines live in a {@link Scrollport} sized to its content up to
 * {@link #MAXLIST}, so a declaration of one entry costs one line of chrome and one of twenty-two still fits
 * on screen.
 *
 * <p><b>A re-prompt names the escalation.</b> An addon whose manifest widens is disabled and asked again
 * (the consent record is per addon, {@code AddonRegistry.grantConsent}); the entries the user has not
 * approved before are marked <b>NEW</b> ({@link PermissionSet#isNew}), so the second dialog reads as a
 * request for something more rather than as a repeat of one they already dismissed — which is the failure
 * mode of any dialog that shows the same text twice.
 *
 * <p>Pure {@code haven}-public composition (a {@link Window} of {@link Label}s + {@link Button}s), so it
 * lives in the addon package like {@link AddonPanel}. It is a <b>top-level floating window</b> (added to
 * {@code ui.root}, centered and raised), so it drags freely like any window rather than being clipped
 * inside the AddOns panel; the {@link AddonPanel} closes it explicitly when that panel leaves the screen
 * (see {@code AddonPanel.tick}), so nothing is left orphaned. Being a client-side widget with no server
 * binding, its close box is redirected to a plain {@code destroy()} (there is no {@code wdgmsg("close")}
 * to send — cf. the 2a {@code hafen.ui():window()}).
 */
public class PermissionConsentWnd extends Window {
    private static final int WRAP = UI.scale(340);
    /** How tall the enumerated list may grow before it scrolls — about a dozen single-line entries. */
    private static final int MAXLIST = UI.scale(240);
    /** What every enumerated entry opens with, so the list reads as a list and never as another paragraph. */
    private static final String BULLET = "- ";

    /**
     * @param addonName the addon's display name (for the title + notice).
     * @param declared  what its manifest asked for — one line is rendered per {@link PermissionSet#entries()}.
     * @param consented what the user already approved for this addon; every entry not covered by it is marked
     *                  NEW. Empty on a first prompt, where nothing is marked (it is all new, and saying so on
     *                  every line would say nothing).
     * @param onConfirm run once, on the UI thread, if the user clicks <b>Enable</b> (record the consent +
     *                  persist-enable the addon + refresh the panel). Never run on Cancel / close.
     */
    public PermissionConsentWnd(String addonName, PermissionSet declared, Set<Permission> consented,
                                Runnable onConfirm) {
        super(Coord.z, "Enable " + addonName + "?", true);
        // Build the lines first: a Label sizes itself in its constructor, so their total height is what decides
        // how tall the list box is — and that has to be known BEFORE anything below it is positioned.
        List<Label> lines = new ArrayList<Label>();
        boolean anyNew = false;
        int lh = 0;
        for(String entry : declared.entries()) {
            boolean isnew = !consented.isEmpty() && PermissionSet.isNew(entry, consented);
            anyNew = anyNew || isnew;
            Label l = new Label(BULLET + (isnew ? "NEW: " : "") + PermissionSet.describe(entry)
                                + "  (" + entry + ")", WRAP - UI.scale(34));   // room for the indent + the bar
            lines.add(l);
            lh += l.sz.y + UI.scale(3);
        }
        Widget prev = add(new Label("“" + addonName + "” asks to act on your behalf. If you enable it, it will"
            + " be able to:", WRAP), 0, 0);
        // Exactly as tall as its content up to MAXLIST — the slack is what Scrollport's own bar arithmetic
        // needs to report "nothing to scroll" when everything already fits, so a two-entry declaration shows
        // no live scrollbar and a twenty-two-entry one does.
        Scrollport list = add(new Scrollport(new Coord(WRAP, Math.min(lh + UI.scale(12), MAXLIST))),
                              prev.pos("bl").adds(0, 6));
        int y = 0;
        for(Label l : lines) {
            list.cont.add(l, new Coord(UI.scale(4), y));
            y += l.sz.y + UI.scale(3);
        }
        prev = list;
        if(anyNew)
            prev = add(new Label("You have enabled this addon before, but it is now asking for the entries"
                + " marked NEW.", WRAP), prev.pos("bl").adds(0, 8));
        prev = add(new Label("Enable it only if you trust it — you are granting exactly these permissions, to"
            + " this addon alone. You can disable it again here in the AddOns panel at any time.", WRAP),
            prev.pos("bl").adds(0, 8));
        Button enable = add(new Button(UI.scale(150), "Enable", false).action(() -> { destroy(); onConfirm.run(); }),
            prev.pos("bl").adds(0, 12));
        add(new Button(UI.scale(150), "Cancel", false).action(this::destroy), enable.pos("ur").adds(10, 0));
        reqclose(this::destroy);   // client-side widget: the X = Cancel (no server "close" to send)
        pack();
    }
}
