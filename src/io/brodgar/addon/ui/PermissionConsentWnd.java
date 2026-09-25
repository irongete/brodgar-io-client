package io.brodgar.addon.ui;

import haven.Button;
import haven.Coord;
import haven.Label;
import haven.Scrollport;
import haven.UI;
import haven.Widget;
import haven.Window;

import io.brodgar.addon.AddonRegistry;
import io.brodgar.addon.Permission;
import io.brodgar.addon.PermissionSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The <b>enable-time permission consent dialog</b> (task 4c; {@code decisions.md} D-027, and the
 * "exact wording and placement of the permission notice" the security spec
 * {@code 12-security-and-permissions.md} left open). When the user enables addons that declared a protected
 * permission — ticking one in the {@link AddonPanel}, or installing one ({@link InstallWnd}) — this confirm
 * appears first and spells out what each will be able to do on the player's behalf. The addons are enabled
 * (persisted; applied on reload, D-006) <b>only</b> if the user clicks <b>Enable</b> — <b>Cancel</b> or the
 * close box leaves them disabled, and confirming records what each was consented to, so a manifest that
 * later asks for MORE comes back and asks again.
 *
 * <p><b>One dialog for everything one action enables</b> (168). Ticking an addon enables the disabled ones it
 * needs as well, and installing a bundle enables every addon it includes: each of them that asks for
 * something the user has not approved is listed here under its own name, with its own lines, and the one
 * answer covers them all. Enable grants each exactly its own ({@link AddonRegistry#grantConsent}) and enables
 * every addon the action names; the ones that ask for nothing are named under the list and enabled with
 * them. Where none of them asks for anything, {@link #enable} enables them all at once and opens nothing.
 *
 * <p><b>It enumerates</b> (050.2): one plain-language line per DECLARED ENTRY, read off the
 * {@link Permission} catalogue, with a group ({@code item.*}) rendered as the one line it is written as
 * rather than expanded into its members. That is the whole point of the catalogue replacing a single coarse
 * tier — <i>the permission the user grants is the list they read</i> — so this window says what an addon
 * asked for instead of making one blanket statement that over-warns about a movement addon and under-warns
 * about one sending raw widget messages. The lines live in a {@link Scrollport} sized to its content up to
 * {@link #MAXLIST}, so a declaration of one entry costs one line of chrome and a bundle's twenty still fit on
 * screen.
 *
 * <p><b>A re-prompt names the escalation.</b> An addon whose manifest widens is disabled and asked again
 * (the consent record is per addon, {@code AddonRegistry.grantConsent}); the entries the user has not
 * approved before are marked <b>NEW</b> ({@link PermissionSet#isNew}), so the second dialog reads as a
 * request for something more rather than as a repeat of one they already dismissed — which is the failure
 * mode of any dialog that shows the same text twice.
 *
 * <p><b>A host widens too</b>, and is marked where it is written: the {@code network} block is the network
 * key's argument, so an addon that adds a host to it has asked for something more without touching a key,
 * and the line carrying that host is marked like any other entry — with the added host itself marked inside
 * it ({@link PermissionSet#describe}). A dialog that answered a new host by re-printing the approved ones
 * would put the one thing being asked about in the one place the user has already read.
 *
 * <p>Pure {@code haven}-public composition (a {@link Window} of {@link Label}s + {@link Button}s), so it
 * lives in the addon package like {@link AddonPanel}. It is a <b>top-level floating window</b> (added to
 * {@code ui.root}, centered and raised), so it drags freely like any window rather than being clipped
 * inside the AddOns panel. One asks at a time: a new action drops the dialog an older one left open, and the
 * {@link AddonPanel} drops it ({@link #close}) when that panel leaves the screen, so nothing is left orphaned.
 * Being a client-side widget with no server binding, its close box is redirected to a plain answer of Cancel
 * (there is no {@code wdgmsg("close")} to send — cf. the 2a {@code hafen.ui():window()}).
 */
public class PermissionConsentWnd extends Window {
    private static final int WRAP = UI.scale(340);
    /** How tall the enumerated list may grow before it scrolls — about a dozen single-line entries. */
    private static final int MAXLIST = UI.scale(240);
    /** What every enumerated entry opens with, so the list reads as a list and never as another paragraph. */
    private static final String BULLET = "- ";

    /** One addon an action enables: its id and name, and what its manifest declares — the keys, and the hosts they take. */
    static final class Step {
        final String id, name;
        final PermissionSet declared;
        final List<String> hosts;

        Step(String id, String name, PermissionSet declared, List<String> hosts) {
            this.id = id;
            this.name = name;
            this.declared = declared;
            this.hosts = hosts;
        }
    }

    private static PermissionConsentWnd open;     // the dialog on screen, or null

    /**
     * Enable every step's addon, all at once. The ones that declare something the user has not approved are
     * asked for first, in one dialog titled {@code Enable <subject>?}: Enable grants each its own, enables them
     * all and runs {@code then}; Cancel enables none and runs nothing. Where none asks for anything, they are
     * enabled at once, with no dialog, and {@code then} runs. {@code then} may be null.
     */
    static void enable(Widget root, String subject, List<Step> steps, Runnable then) {
        close();
        List<Step> asking = new ArrayList<Step>();
        List<String> silent = new ArrayList<String>();
        for(Step s : steps) {
            if(s.declared.isEmpty() || approved(s))
                silent.add(s.name);
            else
                asking.add(s);
        }
        Runnable accept = () -> {
            for(Step s : steps) {
                if(asking.contains(s))
                    AddonRegistry.grantConsent(s.id, s.declared, s.hosts);   // records the consent, and enables
                else
                    AddonRegistry.setEnabled(s.id, true);
            }
            if(then != null)
                then.run();
        };
        if(asking.isEmpty()) {
            accept.run();
            return;
        }
        open = root.adda(new PermissionConsentWnd(subject, asking, silent, accept), root.sz.div(2), 0.5, 0.5);
        open.raise();
    }

    /** Drop the dialog on screen, unanswered: nothing is enabled and nothing runs. */
    static void close() {
        if((open != null) && (open.parent != null))
            open.destroy();                       // destroy() is not the close box: no Cancel runs, nor anything else
        open = null;
    }

    /** Everything the step declares is in the addon's consent record already: the keys and, as written, the hosts. */
    private static boolean approved(Step s) {
        return AddonRegistry.consentedKeys(s.id).containsAll(s.declared.granted())
            && AddonRegistry.consentedHosts(s.id).containsAll(s.hosts);
    }

    /**
     * @param subject what the action enables, for the title: an addon's name, or a bundle's.
     * @param asking  the addons that declare what the user has not approved, each listed with its own lines. For
     *                each, the entries its consent record does not cover are marked NEW — none on a first prompt,
     *                where all of it is new and saying so on every line would say nothing — and so are the hosts
     *                it declares that the record does not cover (093.4).
     * @param silent  the names of the addons enabled with them that ask for nothing, or nothing new.
     * @param accept  run once, on the UI thread, if the user clicks <b>Enable</b>. Never run on Cancel / close.
     */
    private PermissionConsentWnd(String subject, List<Step> asking, List<String> silent, Runnable accept) {
        super(Coord.z, "Enable " + subject + "?", true);
        // Build the list first: a Label sizes itself in its constructor, so the total height is what decides how
        // tall the list box is — and that has to be known BEFORE anything below it is positioned.
        List<Label> items = new ArrayList<Label>();
        List<Integer> indents = new ArrayList<Integer>();
        boolean anyNew = false;
        int lh = 0;
        for(Step s : asking) {
            Set<Permission> consented = AddonRegistry.consentedKeys(s.id);
            List<String> known = AddonRegistry.consentedHosts(s.id);
            // A FIRST prompt marks nothing. The one guard covers both levels, which is why the host marking is
            // handed null rather than the empty record.
            boolean reprompt = !consented.isEmpty();
            Label head = new Label("“" + s.name + "” asks to act on your behalf. If you enable it, it will be able to:",
                                   WRAP - UI.scale(24));   // room for the bar
            items.add(head);
            indents.add(UI.scale(4));
            lh += head.sz.y + UI.scale(3);
            for(String entry : s.declared.entries()) {
                boolean isnew = reprompt && PermissionSet.isNew(entry, consented, s.hosts, known);
                anyNew = anyNew || isnew;
                Label l = new Label(BULLET + (isnew ? PermissionSet.NEW + ": " : "")
                                    + PermissionSet.describe(entry, s.hosts, reprompt ? known : null)
                                    + "  (" + entry + ")", WRAP - UI.scale(42));   // room for the indent + the bar
                items.add(l);
                indents.add(UI.scale(12));
                lh += l.sz.y + UI.scale(3);
            }
            lh += UI.scale(6);                    // the room between one addon and the next
        }
        // Exactly as tall as its content up to MAXLIST — the slack is what Scrollport's own bar arithmetic
        // needs to report "nothing to scroll" when everything already fits, so a two-entry declaration shows
        // no live scrollbar and a bundle's long list does.
        Scrollport list = add(new Scrollport(new Coord(WRAP, Math.min(lh + UI.scale(12), MAXLIST))), Coord.z);
        int y = 0;
        for(int i = 0; i < items.size(); i++) {
            Label l = items.get(i);
            if((i > 0) && (indents.get(i) < indents.get(i - 1)))
                y += UI.scale(6);                 // a new addon's sentence, under the last one's lines
            list.cont.add(l, new Coord(indents.get(i), y));
            y += l.sz.y + UI.scale(3);
        }
        Widget prev = list;
        boolean one = (asking.size() == 1);
        if(!silent.isEmpty())
            prev = add(new Label("Enabled with " + (one ? "it" : "them") + ", asking for nothing: "
                + String.join(", ", silent) + ".", WRAP), prev.pos("bl").adds(0, 8));
        if(anyNew)
            prev = add(new Label(one ? "You have enabled this addon before, but it is now asking for what is marked NEW."
                                     : "You have enabled some of these before, but they are now asking for what is"
                                       + " marked NEW.", WRAP), prev.pos("bl").adds(0, 8));
        prev = add(new Label(one ? "Enable it only if you trust it — you are granting exactly these permissions, to"
                                   + " this addon alone. You can disable it again here in the AddOns panel at any time."
                                 : "Enable them only if you trust them — you are granting exactly these permissions,"
                                   + " each to its own addon alone. You can disable any of them again here in the"
                                   + " AddOns panel at any time.", WRAP), prev.pos("bl").adds(0, 8));
        Button enable = add(new Button(UI.scale(150), "Enable", false).action(() -> { close(); accept.run(); }),
            prev.pos("bl").adds(0, 12));
        add(new Button(UI.scale(150), "Cancel", false).action(PermissionConsentWnd::close), enable.pos("ur").adds(10, 0));
        reqclose(PermissionConsentWnd::close);    // client-side widget: the X = Cancel (no server "close" to send)
        pack();
    }
}
