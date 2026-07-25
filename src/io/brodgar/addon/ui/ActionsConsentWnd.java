package io.brodgar.addon.ui;

import haven.Button;
import haven.Coord;
import haven.Label;
import haven.UI;
import haven.Widget;
import haven.Window;

/**
 * The <b>enable-time write-actions consent dialog</b> (task 4c; {@code decisions.md} D-027, and the
 * "exact wording and placement of the write-actions permission notice" the security spec
 * {@code 12-security-and-permissions.md} left open). When the user ticks the enable checkbox of an
 * addon that declared the {@code "actions"} permission in the {@link AddonPanel}, this confirm appears
 * first and spells out that the addon will be able to act on the player's behalf. The addon is enabled
 * (persisted; applied on reload, D-006) <b>only</b> if the user clicks <b>Enable</b> — <b>Cancel</b> or
 * the close box leaves it disabled.
 *
 * <p>This is the base for a future per-category breakdown ("moves your character / interacts with
 * objects / …") derived from the addon's declared permission list; today the sole declared category is
 * the coarse {@code "actions"}, so the notice is the single general statement below.
 *
 * <p>Pure {@code haven}-public composition (a {@link Window} of {@link Label}s + {@link Button}s), so it
 * lives in the addon package like {@link AddonPanel}. It is a <b>top-level floating window</b> (added to
 * {@code ui.root}, centered and raised), so it drags freely like any window rather than being clipped
 * inside the AddOns panel; the {@link AddonPanel} closes it explicitly when that panel leaves the screen
 * (see {@code AddonPanel.tick}), so nothing is left orphaned. Being a client-side widget with no server
 * binding, its close box is redirected to a plain {@code destroy()} (there is no {@code wdgmsg("close")}
 * to send — cf. the 2a {@code hafen.ui.window}).
 */
public class ActionsConsentWnd extends Window {
    private static final int WRAP = UI.scale(340);

    /**
     * @param addonName the addon's display name (for the title + notice).
     * @param onConfirm run once, on the UI thread, if the user clicks <b>Enable</b> (persist-enable the
     *                  addon + refresh the panel). Never run on Cancel / close.
     */
    public ActionsConsentWnd(String addonName, Runnable onConfirm) {
        super(Coord.z, "Enable " + addonName + "?", true);
        Widget prev = add(new Label("“" + addonName + "” wants permission to act on your behalf.", WRAP), 0, 0);
        prev = add(new Label("If you enable it, it will be able to move your character, use items, and interact" +
            " with the world — the same actions you can take by clicking, done automatically.", WRAP),
            prev.pos("bl").adds(0, 8));
        prev = add(new Label("Enable it only if you trust it — you are granting the permission to this addon" +
            " alone. You can disable it again here in the AddOns panel at any time.", WRAP),
            prev.pos("bl").adds(0, 8));
        Button enable = add(new Button(UI.scale(150), "Enable", false).action(() -> { destroy(); onConfirm.run(); }),
            prev.pos("bl").adds(0, 12));
        add(new Button(UI.scale(150), "Cancel", false).action(this::destroy), enable.pos("ur").adds(10, 0));
        reqclose(this::destroy);   // client-side widget: the X = Cancel (no server "close" to send)
        pack();
    }
}
