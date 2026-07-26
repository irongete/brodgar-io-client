package io.brodgar.addon;

import haven.Console;
import haven.TexI;
import haven.UI;
import haven.Utils;
import haven.Widget;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;


import static io.brodgar.addon.AddonManager.*;

/**
 * The addon **registry + load/enable/reload management** — distinct from the {@code hafen.*} runtime bridge.
 * Owns **discovery + loading** from {@code addons/} on disk ({@link #loadAll}), the persisted **enabled set**
 * ({@link #isEnabled}/{@link #setEnabled} + the D-027 write-addon default-disable), the addon-layer **reload**
 * ({@link #reload} — teardown-all + re-load, no relog), per-addon **teardown** ({@link #teardown}, orchestrating
 * every subsystem's cleanup), and the **AddOns options-panel API** ({@link #describeAddons}/{@link #liveStatus}/
 * {@link AddonInfo} — consumed by {@code io.brodgar.addon.ui.AddonPanel}). {@link AddonManager} keeps the runtime
 * (tick/seams/event bus / the shared read substrate); it calls {@link #loadAll} on init, {@link #reload} on the
 * queued tick, and {@link #teardown} when unloading. Not instantiable.
 */
public final class AddonRegistry {
    private AddonRegistry() {}

    private static final String PREF_DISABLED = "addons/disabled";
    private static volatile boolean reloadNeeded;        // enabled set changed since the last (re)load
    private static volatile int reloadGen;               // bumped by each completed reload() (the AddOns panel watches it)
    private static final String PREF_ACTIONS_SEEN = "addons/actions.seen";    // write-addon ids we have applied the default-disable to

    // ------------------------------------------------------------- discovery + loading

    /** Default: the {@code addons/} folder beside the client jar. {@code -Dhaven.addondir} overrides. */
    static File addonDir() {
        String override = System.getProperty("haven.addondir");
        if((override != null) && !override.isEmpty())
            return new File(override);
        try {
            return Utils.srcpath(AddonManager.class).resolveSibling("addons").toFile();
        } catch(RuntimeException e) {
            return new File("addons");
        }
    }

    static void loadAll() {
        reloadNeeded = false;         // whatever is on disk now IS the applied enabled set
        autoDisabledWarn.clear();     // a (re)load gives every addon a fresh start (drop session warnings)
        scanAddonDefaults();          // D-027: default-disable newly-discovered write addons; refresh the write-addon cache
        File dir = addonDir();
        log("addons dir: " + dir);
        File[] subs = dir.listFiles(File::isDirectory);
        if(subs == null) {
            log("no addons/ directory");
            return;
        }
        // D-006: honor the persisted enabled set (skip disabled). A write-declaring addon is disabled by default
        // (D-027/D-028) until the user enables it through the AddOns-panel consent dialog (slice 4c); once enabled
        // it loads like any other addon (there is no global actions switch to also satisfy — D-028).
        Set<String> disabled = disabledSet();
        for(File sub : subs) {
            if(!new File(sub, "manifest.json").isFile())
                continue;
            if(disabled.contains(sub.getName())) {
                log("skipping disabled addon '" + sub.getName() + "'");
                continue;
            }
            try {
                Manifest m = Manifest.load(sub.toPath());
                Globals g = Sandbox.create();   // D-017 stdlib whitelist + D-018 instruction watchdog
                Addon addon = new Addon(m, sub.toPath(), g);
                installHafen(g, addon);
                LuaTable ad = new LuaTable();
                ad.set("id", LuaValue.valueOf(m.id));
                ad.set("dir", LuaValue.valueOf(sub.getAbsolutePath()));
                g.set("ADDON", ad);
                addon.run();
                addons.add(addon);
                if(addon.error == null) {
                    fireTo(addon, "OnLoad");                  // the addon's file body just ran
                    log("loaded " + m.id + " v" + m.version);
                } else {
                    log("error in " + m.id + ": " + addon.error);
                }
            } catch(Exception e) {
                log("failed to load '" + sub.getName() + "': " + e.getMessage());
            }
        }
        log(addons.size() + " addon(s) loaded");
    }

    /** Fire {@code OnDisable}, flush the addon's saved vars, then drop its owned resources. */
    static void teardown(Addon a) {
        try {
            fireTo(a, "OnDisable");   // the addon's last chance to write its store tables...
        } catch(RuntimeException e) {
            /* isolation is per-handler in callLua; this is just a backstop */
        }
        StoreApi.flush(a);                     // ...then persist them (spec 05: flushed at OnDisable)
        destroyWidgets(a);            // custom UI vanishes cleanly (2a; before subs, so no dangling callbacks)
        UiApi.teardownModels(a);            // 3b: drop adopted models + un-hide any native widget the addon had hidden
        HookApi.teardownHooks(a);         // 2c: deafen input hooks (engine widgets outlive a :reload — must detach)
        HookApi.teardownActionHooks(a);   // 2d: unregister action hooks from the outbound-wdgmsg dispatch map
        HookApi.teardownMessageHooks(a);  // 2e-1: unregister message hooks from the inbound-uimsg dispatch map
        HookApi.teardownKeyBinds(a);      // 2e-2: unregister global hotkeys from the GlobKeyEvent dispatch list
        UiApi.teardownWidgetObservers(a);   // 3a: unregister widget-creation observers from the placement dispatch list
        UiApi.teardownReplacers(a);         // 3c: stop the replacers matching (models un-hidden above, views destroyed above)
        HookApi.teardownSlashCommands(a); // A11: drop the addon's live slash handlers (Console dispatchers stay — C1)
        RenderApi.teardownGhosts(a);            // V1: destroy client-only world ghosts (remove the scene slot + free the sprite)
        RenderApi.teardownSprites(a);           // R2: destroy client-only world sprites (remove the slot + free the quad geometry)
        RenderApi.teardownObjects(a);           // R3: destroy client-only world objects (remove the slot + free the glTF Models; before meshes)
        RenderApi.teardownImages(a);            // R1: dispose custom images (frees each TexI's GL texture — no leak; after sprites)
        RenderApi.teardownMeshes(a);            // R3: drop custom models (frees the CPU geometry; after the objects that used them)
        HookApi.teardownMouseGrabs(a);// V5: release any active mouse-drag grab (drops the UI.Grab + unlinks the widget)
        HttpApi.teardownRequests(a);  // N2a: cancel in-flight HTTP requests (result discarded on drain; no callback)
        FontApi.teardownFonts(a);     // F1: revert this addon's font overrides on every scope (bumps gen -> stock foundry restored)
        a.hudOverlays.clear();        // 2b: HUD overlays stop painting immediately (the paint iterates this list)
        a.gobOverlays.clear();        // 2b: gob overlays stop painting immediately
        a.subs.clear();
        a.timers.clear();
        if(!UiApi.anyGobOverlays())   // no addon wants gob overlays now → detach the idle attribs (reload-safe)
            UiApi.detachGobOverlays();
    }

    /** Destroy every custom UI widget/window this addon owns (2a). Widget removal locks on {@code ui}. */
    private static void destroyWidgets(Addon a) {
        if(a.widgets.isEmpty())
            return;
        UI u = ui;
        List<LuaWidget> ws = new ArrayList<LuaWidget>(a.widgets);
        a.widgets.clear();
        Runnable kill = () -> {
            for(LuaWidget w : ws) {
                try {
                    w.kill();   // stop callbacks + destroy its root (the window chrome, or the widget)
                } catch(RuntimeException e) {
                    /* a half-attached widget: best-effort, never abort teardown */
                }
            }
        };
        if(u != null) {
            synchronized(u) { kill.run(); }
        } else {
            kill.run();
        }
    }

    // ------------------------------------------------------------- reload + enabled set (1f-2)

    /** Queue a full addon-layer reload; applied on the next UI-thread tick (see {@link #tick}). */
    static void queueReload() {
        reloadPending = true;
        log("reload queued");
    }

    /**
     * Reload the addon layer only (D-005) — no relog, the session stays connected. Tears down every
     * loaded addon (OnDisable → flush saved vars → drop owned resources, in reverse load order),
     * re-scans {@code addons/} and the enabled set, re-runs the enabled addons from disk (firing
     * {@code OnLoad}), and — if already in-world — restores per-character saved vars and re-fires
     * {@code OnEnterWorld} so addons re-initialize as if freshly logged in (the WoW {@code PLAYER_LOGIN}
     * analog). Runs on the UI thread (queued via {@link #queueReload}); the tick pump, gob callback and
     * uimsg tap are <b>session-scoped</b> and left in place — only the Lua layer is rebuilt. Per-addon
     * teardown/load is error-isolated so one bad addon cannot abort the reload.
     */
    public static synchronized void reload() {
        if(ui == null) {
            log("reload: no active session");
            return;
        }
        log("reloading addons...");
        List<Addon> cur = new ArrayList<Addon>(addons);
        for(int i = cur.size() - 1; i >= 0; i--)     // reverse load order
            teardown(cur.get(i));
        addons.clear();
        LuaGOut.clearResourceCache();                // U1/D-039: drop the g:resource name cache on reload
        loadAll();                                   // re-scan disk + enabled set; re-run; fire OnLoad
        if(gui() != null) {                          // already in-world → re-init as a fresh login
            StoreApi.restorePerChar();                        // reload per-char saved vars (charScope still valid)
            fire("OnEnterWorld");
        }
        reloadGen++;                                 // notify any live AddOns panel to rebuild its rows
        log("reload complete (" + addons.size() + " addon[s] active)");
    }

    /** The persisted set of disabled addon ids (client-scope). An addon runs unless it is in here. */
    private static Set<String> disabledSet() {
        List<String> l = Utils.getprefsl(PREF_DISABLED, new String[0]);
        return (l == null) ? new LinkedHashSet<String>() : new LinkedHashSet<String>(l);
    }

    /** Is an addon enabled? (i.e. NOT in the persisted disabled set — the default for a new addon.) */
    public static boolean isEnabled(String id) {
        return !disabledSet().contains(id);
    }

    /**
     * Persist an addon's enabled state. Per D-006 (WoW model) this does NOT load/unload it live — the
     * change takes effect on the next {@link #reload} / login; {@code reloadNeeded} then flags a pending
     * reload. Idempotent: a no-op change writes nothing.
     */
    public static void setEnabled(String id, boolean enabled) {
        if((id == null) || id.isEmpty())
            return;
        Set<String> d = disabledSet();
        boolean changed = enabled ? d.remove(id) : d.add(id);
        if(changed) {
            Utils.setprefsl(PREF_DISABLED, d);
            reloadNeeded = true;
        }
    }

    /**
     * Scan {@link #addonDir()} and apply the write-addon default (disabled-by-default, opt-in per addon —
     * D-027/D-028): a discovered addon that declares the {@code "actions"} permission and has NOT been seen before
     * is added to the persisted disabled set (and to a persisted "seen" set so it is defaulted exactly once — a
     * later scan then respects whatever the user has since chosen, i.e. the enable made through the consent
     * dialog). Cheap disk I/O (a handful of small manifests); call on a (re)load / panel build, not per frame. The
     * pure policy is {@link #applyActionsDefaults} (headless-testable).
     */
    private static void scanAddonDefaults() {
        File dir = addonDir();
        File[] subs = dir.listFiles(File::isDirectory);
        if(subs == null)
            return;
        Map<String, Boolean> declares = new LinkedHashMap<String, Boolean>();
        for(File sub : subs) {
            if(!new File(sub, "manifest.json").isFile())
                continue;
            try {
                declares.put(sub.getName(), Manifest.load(sub.toPath()).usesActions());
            } catch(Exception e) {
                /* a broken manifest surfaces as an error row elsewhere; no default to apply here */
            }
        }
        List<String> seenL = Utils.getprefsl(PREF_ACTIONS_SEEN, new String[0]);
        Set<String> seen = (seenL == null) ? new LinkedHashSet<String>() : new LinkedHashSet<String>(seenL);
        Set<String> disabled = disabledSet();
        int seenBefore = seen.size(), disBefore = disabled.size();   // applyActionsDefaults only ADDS to both
        applyActionsDefaults(seen, disabled, declares);
        if(seen.size() != seenBefore)
            Utils.setprefsl(PREF_ACTIONS_SEEN, seen);
        if(disabled.size() != disBefore)
            Utils.setprefsl(PREF_DISABLED, disabled);
    }

    /**
     * The pure write-addon default policy (no I/O — D-027/D-028): for each entry in {@code declares} that is a
     * write addon (value {@code true}) and NOT already in {@code seen}, mark it seen and add it to {@code disabled}
     * (disabled-by-default — write addons are opt-in per addon; enabling one goes through the consent dialog). A
     * write addon already in {@code seen} is left to the user's enable/disable choice; read addons are ignored
     * entirely. {@code seen} and {@code disabled} are mutated in place (additions only). Returns the ids of ALL
     * write addons in {@code declares}. Headless-testable.
     */
    static Set<String> applyActionsDefaults(Set<String> seen, Set<String> disabled, Map<String, Boolean> declares) {
        Set<String> writeIds = new LinkedHashSet<String>();
        for(Map.Entry<String, Boolean> e : declares.entrySet()) {
            if(!Boolean.TRUE.equals(e.getValue()))
                continue;
            String id = e.getKey();
            writeIds.add(id);
            if(seen.add(id))          // first time we've seen this addon AS a write addon → default it disabled
                disabled.add(id);
        }
        return writeIds;
    }

    /** The loaded addon with this id, or {@code null} if none is loaded (disabled, missing, or errored). */
    private static Addon findLoaded(String id) {
        for(Addon a : addons)
            if((a.manifest != null) && a.manifest.id.equals(id))
                return a;
        return null;
    }

    /** List every discovered addon (a folder with a manifest) and its status: version / disabled / error. */
    static void listAddons() {
        File dir = addonDir();
        File[] subs = dir.listFiles(File::isDirectory);
        if(subs == null) {
            log("no addons/ directory");
            return;
        }
        Set<String> disabled = disabledSet();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for(File sub : subs) {
            if(!new File(sub, "manifest.json").isFile())
                continue;
            String id = sub.getName();
            Addon a = findLoaded(id);
            String status;
            if(a != null)
                status = (a.error == null) ? ("v" + a.manifest.version) : "error";
            else
                status = disabled.contains(id) ? "disabled" : "not loaded";
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(id).append(" [").append(status).append("]");
            n++;
        }
        if(n == 0) {
            log("no addons found");
            return;
        }
        log("addons: " + sb + (reloadNeeded ? "  (changes pending — run :reload to apply)" : ""));
    }

    // ------------------------------------------------------------- AddOns options panel API (1f-3)

    /**
     * A snapshot of one discovered addon for the AddOns options panel (spec 10): its manifest metadata
     * plus its live state. Immutable; built by {@link #describeAddons()}.
     */
    public static final class AddonInfo {
        public final String id, name, version, author, description;
        public final int apiVersion;
        public final boolean enabled;          // persisted enabled state (the checkbox) — NOT the live-loaded state
        public final boolean loaded;           // currently running this session
        public final boolean declaresActions;  // declares the "actions" write permission (D-027: default-disabled, master-gated)
        public final List<String> networkHosts; // declared network allowlist (N2a / D-037); empty = no network
        public final String error;             // load/runtime error, or null
        public final String warning;           // session warning (e.g. auto-disabled by the CPU watchdog), or null

        AddonInfo(String id, String name, String version, String author, String description,
                  int apiVersion, boolean enabled, boolean loaded, boolean declaresActions,
                  List<String> networkHosts, String error, String warning) {
            this.id = id; this.name = name; this.version = version; this.author = author;
            this.description = description; this.apiVersion = apiVersion; this.enabled = enabled;
            this.loaded = loaded; this.declaresActions = declaresActions;
            this.networkHosts = networkHosts; this.error = error; this.warning = warning;
        }

        /** Whether this addon declared a non-empty {@code network} allowlist (shows the network badge). */
        public boolean declaresNetwork() {
            return (networkHosts != null) && !networkHosts.isEmpty();
        }
    }

    /**
     * Every discovered addon (a folder under {@link #addonDir()} with a {@code manifest.json}), sorted by
     * id, as {@link AddonInfo} for the AddOns panel. Reads each manifest fresh from disk so disabled /
     * not-loaded addons still show name/version/author. Call on panel build/reload (it does disk I/O), not
     * per frame — use {@link #liveStatus(String)} for the cheap per-frame status refresh.
     */
    public static List<AddonInfo> describeAddons() {
        scanAddonDefaults();          // D-027: reflect the write-addon default (+ refresh the cache) for any new addon
        List<AddonInfo> out = new ArrayList<AddonInfo>();
        File dir = addonDir();
        File[] subs = dir.listFiles(File::isDirectory);
        if(subs == null)
            return out;
        java.util.Arrays.sort(subs, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        Set<String> disabled = disabledSet();
        for(File sub : subs) {
            if(!new File(sub, "manifest.json").isFile())
                continue;
            String id = sub.getName();
            Manifest m = null;
            try { m = Manifest.load(sub.toPath()); } catch(Exception e) { /* keep an id-only row */ }
            Addon loaded = findLoaded(id);
            String error = (loaded != null) ? loaded.error : ((m == null) ? "manifest error" : null);
            out.add(new AddonInfo(id,
                (m != null) ? m.name : id,
                (m != null) ? m.version : null,
                (m != null) ? m.author : null,
                (m != null) ? m.description : null,
                (m != null) ? m.apiVersion : 0,
                !disabled.contains(id),
                loaded != null,
                (m != null) && m.usesActions(),
                (m != null) ? m.network : java.util.Collections.<String>emptyList(),
                error,
                autoDisabledWarn.get(id)));
        }
        return out;
    }

    /**
     * A short live status string for one addon id, cheap enough to call each frame (no manifest I/O): the
     * session auto-disable warning if any, else loaded-version / error / disabled / not-loaded. Backs the
     * per-row status label the AddOns panel refreshes on tick.
     */
    public static String liveStatus(String id) {
        String w = autoDisabledWarn.get(id);
        if(w != null)
            return "auto-disabled (" + w + ")";
        Addon a = findLoaded(id);
        if(a != null)
            return (a.error == null) ? ("loaded v" + a.manifest.version) : ("error: " + a.error);
        if(!isEnabled(id))
            return "disabled";
        return "not loaded";
    }

    /** Whether the enabled set has changed since the last (re)load (a reload is pending to apply it). */
    public static boolean reloadNeeded() {
        return reloadNeeded;
    }

    /** A counter bumped by each completed {@link #reload}, so a live AddOns panel can detect a rebuild. */
    public static int reloadGen() {
        return reloadGen;
    }

    /** Queue an addon-layer reload from the AddOns panel's "Reload UI" button (applied on the next tick). */
    public static void requestReload() {
        queueReload();
    }

    /** Open the addons folder in the OS file browser (AddOns panel convenience). Best-effort, non-fatal. */
    public static void openAddonsFolder() {
        try {
            java.awt.Desktop.getDesktop().open(addonDir());
        } catch(Exception e) {
            log("could not open addons folder: " + e);
        }
    }

}
