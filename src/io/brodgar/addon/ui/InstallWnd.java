package io.brodgar.addon.ui;

import haven.Button;
import haven.Coord;
import haven.Label;
import haven.UI;
import haven.Widget;
import haven.Window;

import io.brodgar.addon.AddonRegistry;
import io.brodgar.addon.AddonRegistry.AddonInfo;
import io.brodgar.addon.PermissionSet;
import io.brodgar.addon.registry.Entry;
import io.brodgar.addon.registry.Registry;
import io.brodgar.addon.registry.Semver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Install, with what it needs</b> (168) -- the window an <b>Install</b> press opens for an addon whose
 * manifest names dependencies, a bundle above all. It asks the hub for every addon the manifest's
 * {@code dependencies} name that is not here at the version it needs, and for what those need in turn, then
 * says what the press will do: what it installs with the addon, what is here already, and what it cannot
 * install -- an id the hub does not carry, a hub version older than the one needed, a folder put in
 * {@code addons/} by hand at an older version, which is the player's and never replaced.
 *
 * <p><b>Install</b> enables the addon and every dependency that is on its way or here and disabled — for a
 * bundle, every addon it includes — all at once: where any of them declares a permission the user has not
 * approved, one {@link PermissionConsentWnd} asks for all of them first, and Cancel there installs nothing.
 * Then every package is staged ({@link AddonRegistry#install}): the consent is recorded before they land, so
 * the reload that applies the installs finds everything enabled and loads the addon whole. <b>Cancel</b> here
 * installs nothing. An addon that needs nothing is installed at once, with no window ({@link #install}).
 *
 * <p><b>Update</b> on the Installed tab opens the same window ({@link #update}), so a new version that needs an
 * addon the old one did not — one added to a bundle — installs it with the update. An update keeps every box
 * as it is: it enables only the addons it adds, the ones not here at all, and only while the addon is enabled,
 * one dialog asking for their permissions — of a disabled addon they land disabled — and an addon the player
 * turned off stays off. An update with nothing to install, and nothing it cannot install, opens no window.
 */
final class InstallWnd extends Window {
    private static final int WRAP = UI.scale(360);
    /** The most rounds of lookups one install takes: a bundle of bundles of libraries is three. */
    private static final int MAX_ROUNDS = 5;

    private final Entry target;
    private final boolean update;             // an Update press on the Installed tab, not an Install
    private final Map<String, AddonInfo> onDisk = new HashMap<String, AddonInfo>();   // every addon in addons/, by id
    private final Map<String, String> needed = new LinkedHashMap<String, String>();   // every dependency met: id -> min, or null
    private final Map<String, Entry> installing = new LinkedHashMap<String, Entry>(); // the hub's items it installs, by id
    private final List<String> here = new ArrayList<String>();       // ids already here at a version that does
    private final List<String> problems = new ArrayList<String>();   // one sentence for each that cannot be installed
    private final List<String> queue = new ArrayList<String>();      // ids to ask the hub for, next round
    private List<String> asking = new ArrayList<String>();           // the ids the request in flight asks for
    private Registry.Request<List<Entry>> request;
    private int rounds = 0;
    private final Label text;
    private final Button install, cancel;

    /** An Install press: at once for an addon that needs nothing, else through this window, on the screen's root. */
    static void install(Widget from, Entry e) {
        open(from, e, false);
    }

    /** An Update press, to the hub's {@code e}: the same, and no window where it would have nothing to say. */
    static void update(Widget from, Entry e) {
        open(from, e, true);
    }

    private static void open(Widget from, Entry e, boolean update) {
        if(e.dependencies.isEmpty()) {
            AddonRegistry.install(e);
            return;
        }
        Widget root = from.ui.root;
        InstallWnd w = new InstallWnd(e, update);
        if(update && w.settled()) {
            w.apply(root);
            return;
        }
        root.adda(w, root.sz.div(2), 0.5, 0.5).raise();
    }

    private InstallWnd(Entry e, boolean update) {
        super(Coord.z, (update ? "Update " : "Install ") + e.name + "?", true);
        target = e;
        this.update = update;
        for(AddonInfo ai : AddonRegistry.describeAddons())
            onDisk.put(ai.id, ai);
        text = add(new Label("Looking up what " + e.name + " needs…", WRAP), Coord.z);
        install = add(new Button(UI.scale(150), update ? "Update" : "Install", false).action(this::confirm), Coord.z);
        cancel = add(new Button(UI.scale(150), "Cancel", false).action(this::destroy), Coord.z);
        install.disable(true);
        reqclose(this::destroy);                  // client-side widget: the X = Cancel
        consider(e.dependencies);
        ask();
        layout();
    }

    // ------------------------------------------------------------- what it needs

    /**
     * Sort a list of dependencies, each met once: here at a version that does, waiting on the hub's answer,
     * or a sentence saying why it cannot be installed.
     */
    private void consider(List<Entry.Dependency> deps) {
        for(Entry.Dependency d : deps) {
            if(needed.containsKey(d.id) || d.id.equals(target.id))
                continue;
            needed.put(d.id, d.min);
            AddonRegistry.Pending p = AddonRegistry.pending(d.id);
            AddonInfo ai = onDisk.get(d.id);
            if((p != null) && p.removal) {
                problems.add(label(d.id) + " is marked for removal: install it again after Reload UI.");
            } else if(p != null) {
                if(atLeast(p.version, d.min))
                    here.add(d.id);
                else
                    problems.add(label(d.id) + " " + p.version + " is staged, and " + target.name + " needs " + d.min + ".");
            } else if(ai == null) {
                queue.add(d.id);
            } else if(atLeast(ai.version, d.min)) {
                here.add(d.id);
            } else if(ai.hub != null) {
                queue.add(d.id);                  // the hub put it here: the newer one replaces it
            } else {
                problems.add(label(d.id) + " is in addons/ by hand at " + ai.version + ", and " + target.name
                    + " needs " + d.min + ". Replace it yourself.");
            }
        }
    }

    /** The next round of lookups, or the end of them. */
    private void ask() {
        if(queue.isEmpty()) {
            finish();
            return;
        }
        if(rounds >= MAX_ROUNDS) {
            for(String id : queue)
                problems.add(id + " is more than " + MAX_ROUNDS + " dependencies deep, and was not looked up.");
            queue.clear();
            finish();
            return;
        }
        rounds++;
        asking = new ArrayList<String>(queue);
        queue.clear();
        request = Registry.lookup(asking);
    }

    public void tick(double dt) {
        super.tick(dt);
        if((request == null) || !request.done())
            return;
        Registry.Request<List<Entry>> r = request;
        request = null;
        if(r.error() != null) {
            text.settext("The hub did not answer: " + r.error(), WRAP);
            layout();
            return;
        }
        Map<String, Entry> found = new HashMap<String, Entry>();
        for(Entry e : r.result())
            found.put(e.id, e);
        for(String id : asking) {
            Entry e = found.get(id);
            String min = needed.get(id);
            if(e == null) {
                problems.add(id + " is not on the hub.");
            } else if(!atLeast(e.version, min)) {
                problems.add(e.name + " is at " + e.version + " on the hub, and " + target.name + " needs " + min + ".");
            } else {
                installing.put(id, e);
                consider(e.dependencies);         // what it needs in turn
            }
        }
        ask();
    }

    /** What the press will do, and the button that does it. */
    private void finish() {
        StringBuilder sb = new StringBuilder();
        sb.append(target.name).append(target.bundle ? " includes " : " needs ").append(needed.size())
          .append((needed.size() == 1) ? " addon." : " addons.");
        if(!installing.isEmpty()) {
            sb.append("\n\nIt installs them with it:");
            for(Entry e : installing.values()) {
                AddonInfo ai = onDisk.get(e.id);
                sb.append("\n- ").append(e.name).append(" ").append(e.version);
                if(ai != null)
                    sb.append(", replacing ").append(ai.version);
                if(!e.permissions.isEmpty())
                    sb.append(" -- asks for permissions");
            }
        }
        if(!here.isEmpty()) {
            sb.append("\n\nAlready here:");
            for(String id : here)
                sb.append("\n- ").append(label(id));
        }
        if(!problems.isEmpty()) {
            sb.append("\n\nNot installed, so ").append(target.name)
              .append(target.bundle ? " loads without them:" : " will not load until it is:");
            for(String p : problems)
                sb.append("\n- ").append(p);
        }
        AddonInfo mine = onDisk.get(target.id);
        boolean adds = false;
        for(String id : installing.keySet())
            adds = adds || !onDisk.containsKey(id);
        String tail;
        if(!update)
            tail = "It is enabled with all of them, one dialog asking first for any permission they declare. ";
        else if((mine == null) || !mine.enabled)
            tail = target.name + " is disabled, so nothing is enabled: ticking it enables them with it. ";
        else if(adds)
            tail = "What it adds is enabled with it, one dialog asking first for any permission they declare. ";
        else
            tail = "";
        sb.append("\n\n").append(tail).append("Reload UI applies it all.");
        text.settext(sb.toString(), WRAP);
        install.disable(false);
        layout();
    }

    // ------------------------------------------------------------- the press

    /** The press. The window goes first: the dialog asking for the permissions takes its place. */
    private void confirm() {
        Widget root = ui.root;
        destroy();
        apply(root);
    }

    /**
     * What the press does: enable what it enables, all at once, then stage the packages
     * ({@link PermissionConsentWnd#enable}, which asks first in one dialog where a permission needs it, and on
     * Cancel stages nothing). An install enables the addon and every dependency that is on its way or here and
     * disabled. An update enables only the addons it adds, the ones not here at all, and only while the addon
     * itself is enabled; of a disabled addon they land disabled. Every other box stays as it is.
     */
    private void apply(Widget root) {
        AddonInfo mine = onDisk.get(target.id);
        if(update && ((mine == null) || !mine.enabled)) {
            // What an update of a disabled addon adds lands disabled too -- a new addon is otherwise enabled by
            // default -- and ticking the addon enables it with the rest.
            for(String id : installing.keySet()) {
                if(!onDisk.containsKey(id))
                    AddonRegistry.setEnabled(id, false);
            }
            stage();
            return;
        }
        List<PermissionConsentWnd.Step> steps = new ArrayList<PermissionConsentWnd.Step>();
        for(String id : needed.keySet()) {
            Entry e = installing.get(id);
            AddonInfo ai = onDisk.get(id);
            if((e != null) && (!update || (ai == null)))
                step(steps, e);
            else if(!update && disabledHere(id))
                steps.add(new PermissionConsentWnd.Step(ai.id, ai.name, ai.permissions, ai.networkHosts));
        }
        if(!update)
            step(steps, target);
        PermissionConsentWnd.enable(root, update ? ("what " + target.name + " " + target.version + " adds") : target.name,
                                    steps, this::stage);
    }

    /** Stage every install, the addon's own last. */
    private void stage() {
        for(Entry e : installing.values())
            AddonRegistry.install(e);
        AddonRegistry.install(target);
    }

    /** A dependency here at a version that does, disabled, and with a manifest that parses: an install enables it. */
    private boolean disabledHere(String id) {
        AddonInfo ai = onDisk.get(id);
        return here.contains(id) && (ai != null) && !ai.enabled && (ai.manifestError == null);
    }

    /**
     * Whether an update has nothing to say: no lookup still out, nothing to install but the addon itself, and
     * nothing it cannot install. An update like that is applied without the window.
     */
    private boolean settled() {
        return (request == null) && installing.isEmpty() && problems.isEmpty();
    }

    /** The step that enables a hub item, from what its manifest declares; none where this client cannot read a key of it. */
    private static void step(List<PermissionConsentWnd.Step> steps, Entry e) {
        try {
            steps.add(new PermissionConsentWnd.Step(e.id, e.name, PermissionSet.parse(e.permissions), e.hosts));
        } catch(IllegalArgumentException x) {
            // a key this client does not know: the addon is refused at load all the same, so nothing is enabled
        }
    }

    // ------------------------------------------------------------- odds and ends

    /** An id as the window names it: the name and version on disk where it is here, else the id. */
    private String label(String id) {
        AddonInfo ai = onDisk.get(id);
        if(ai == null)
            return id;
        return (ai.version == null) ? ai.name : (ai.name + " " + ai.version);
    }

    /** {@code version} is at least {@code min}: always where no minimum is named, never where either is not a version. */
    private static boolean atLeast(String version, String min) {
        if(min == null)
            return true;
        if((version == null) || !Semver.valid(version) || !Semver.valid(min))
            return false;
        return Semver.compare(version, min) >= 0;
    }

    private void layout() {
        text.c = Coord.z;
        install.c = text.pos("bl").adds(0, 12);
        cancel.c = install.pos("ur").adds(10, 0);
        pack();
    }
}
