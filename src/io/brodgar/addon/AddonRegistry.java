package io.brodgar.addon;

import haven.Console;
import haven.GameUI;
import haven.TexI;
import haven.UI;
import haven.Utils;
import haven.Widget;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
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
 * ({@link #isEnabled}/{@link #setEnabled} + the D-027 default-disable of a permission-declaring addon), the addon-layer **reload**
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
    // 074.2: how many times the Lua layer has been BUILT — once at boot, once per reload. Read beside reloadGen
    // by AddonManager.engineReloads(), whose whole claim is that the difference between the two is 1.
    private static volatile int loadGen;
    /**
     * What the user has CONSENTED to, per addon: {@code "<id>=<key>,<key>,…"} rows of catalogue keys they
     * approved in the enable-time dialog. The record is what the default policy compares a manifest against
     * (declared ⊆ consented → the user's choice stands), so a manifest that later asks for MORE is disabled
     * and asked again instead of silently escalating. Additive per addon — asking for less never re-prompts.
     */
    private static final String PREF_CONSENTED = "addons/permissions.consented";

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

    /**
     * Discover the enabled addons, run their files and fire {@code Load} for each — <b>once for the client</b>
     * since 074.2, at boot and on a {@code :reload}. It is handed no session and asks for none: an addon is
     * loaded before any character is, outlives every one of them, and reaches a session through the API rather
     * than by having been loaded into it.
     */
    static void loadAll() {
        loadGen++;
        reloadNeeded = false;         // whatever is on disk now IS the applied enabled set
        autoDisabledWarn.clear();     // a (re)load gives every addon a fresh start (drop session warnings)
        scanAddonDefaults();          // D-027: default-disable any addon asking for permissions the user has not consented to
        File dir = addonDir();
        log("addons dir: " + dir);
        File[] subs = dir.listFiles(File::isDirectory);
        if(subs == null) {
            log("no addons/ directory");
            return;
        }
        // D-006: honor the persisted enabled set (skip disabled). A permission-declaring addon is disabled by default
        // (D-027/D-028) until the user enables it through the AddOns-panel consent dialog (slice 4c); once enabled
        // it loads like any other addon (there is no global switch to also satisfy — D-028).
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
                    fireTo(addon, "Load");                    // the addon's file body just ran
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

    /** Fire {@code Disable}, flush the addon's saved vars, then drop its owned resources. */
    static void teardown(Addon a) {
        try {
            fireTo(a, "Disable");     // the addon's last chance to write its store tables...
        } catch(RuntimeException e) {
            /* isolation is per-handler in callLua; this is just a backstop */
        }
        StoreApi.flush(a);                     // ...then persist them (spec 05: flushed at Disable)
        VrApi.teardownSurfaces(a);    // 044.1: take every widget this addon stood in the world back OUT of its
                                      //   surface first, so the two teardowns below see an ordinary widget on the
                                      //   flat UI. Standing is a re-home, so it has to be undone before anything
                                      //   decides where a widget ends up — the hidden-native restore reads where
                                      //   the widget is, and destroyWidgets disposes what it finds.
        UiApi.teardownHidden(a);      // 029.2/031.2: give back every native widget the addon hid — and its toggle —
                                      //   under the one rule: the window ends up as the user was seeing it, and a
                                      //   substitution ends whole (the stand-in view dies with it, 032.1). BEFORE
                                      //   destroyWidgets: the rule reads the view's visibility, and a destroyed
                                      //   view stands for nothing.
        destroyWidgets(a);            // custom UI vanishes cleanly (2a; before subs, so no dangling callbacks)
        a.teardownWidgetSubs();           // 041.3/041.4: deafen every widget:on() listener + drop every poll
                                          //   registration (engine widgets outlive a :reload — must detach before
                                          //   the Lua layer that owns them rebuilds)
        a.actionSubs.clear();             // 041.2: stop intercepting the outbound wdgmsg stream, and
        a.messageSubs.clear();            //   the inbound uimsg one — where the L2/L3 hook registries were
                                          //   unregistered, and for the same reason: the rest of this
                                          //   teardown can itself make the client send and receive
        HookApi.teardownKeyBinds(a);      // 2e-2: unregister global hotkeys from the GlobKeyEvent dispatch list
        UiApi.teardownSelectorWatches(a);    // 030.2: drop the selector subscriptions (no disappear — reload != destroy)
        HookApi.teardownSlashCommands(a); // A11: drop the addon's live slash handlers (Console dispatchers stay — C1)
        BeltHold.teardownHolds(a);        // 059.4: give back every action-bar slot this addon was HOLDING — the
                                          //   slot goes back to the server's own content, which never changed
        AddonPagina.teardownEntries(a);   // 059.1: take every entry this addon added to the action menu back out
                                          //   of the grid. BEFORE the asset teardown below: an entry draws one of
                                          //   the addon's images, and a cell must stop being laid out before the
                                          //   texture under it is disposed
        VrApi.teardownGhosts(a);            // V1: destroy client-only world ghosts (remove the scene slot + free the sprite)
        VrApi.teardownSprites(a);           // R2: destroy client-only world sprites (remove the slot + free the quad geometry)
        VrApi.teardownObjects(a);           // R3: destroy client-only world objects (remove the slot + free the glTF Models; before the meshes)
        MapImages.teardown(a);                  // 037.4: free the map drawings the client rendered for this addon
                                                //   (grid:image / grid:overlayImage) — they are TexIs like any
                                                //   other image and ride the same registry, so this only has to
                                                //   drop the bounded cache; it runs BEFORE the asset teardown so
                                                //   nothing is left pointing at a disposed texture
        AssetApi.teardownAssets(a);             // 028.1: dispose every loaded asset — images (each TexI's GL texture; after
                                                //   the sprites that sampled it), then meshes (the shared base-colour TexIs;
                                                //   AFTER the objects above, R3b), then the intern cache itself. Fonts own
                                                //   nothing releasable; FontApi.teardownFonts below reverts their overrides.
        LuaGrab.teardownGrabs(a);     // 041.5: release any active mouse-drag grab (drops the UI.Grab + unlinks the widget)
        Gesture.teardown(a);          // 062: ...and every widget:draggable(h)/:resizable(h) binding — ending a
                                      //   gesture running right now, and deafening the last listener on each handle.
                                      //   BEFORE teardownMoved below: what a gesture WROTE is a layout level,
                                      //   and it is that sweep which gives the place back
        LuaWidget.rememberTeardown(a);   // 062: ...and every widget:remember(name) BINDING. What each name holds
                                         //   is left on disk untouched — the flush above wrote it — because a
                                         //   reload is not the user changing their mind about where a window
                                         //   goes. widget:remember(nil) is the only thing that forgets.
        HttpApi.teardownRequests(a);  // N2a: cancel in-flight HTTP requests (result discarded on drain; no callback)
        a.teardownWaitings();         // 042.1: cancel every pending Resolve registration (a value still loading) —
                                      //   same shape as the HTTP requests above, for the same reason
        LocaleApi.teardown(a);        // 102.1: give the client its own words back -- this addon's CATALOGUE
                                      //   leaves the provider stack and every string it displayed reverts to
                                      //   the English the client wrote. Beside teardownFonts below, and for
                                      //   the same reason: both bump the generation every routed site rebuilds on
        FontApi.teardownFonts(a);     // 033.1: drop this addon's STYLESHEET (hafen.ui():sheet()) and its per-widget
                                      //   widget:setFont overrides in one sweep (bumps gen -> stock foundry restored)
        UiApi.teardownMoved(a);       // 036.1: put every native widget this addon laid out back where the user had
                                      //   it — an addon's layout is a LAYER over the client's, so nothing of ours
                                      //   is left behind for GameUI.savewndpos to persist as their preference.
                                      //   AFTER teardownFonts (036.2): the sheet's own pos/size rules have to have
                                      //   stopped resolving first, or re-running the cascade would put them back
        MapApi.teardownOverlays(a);   // 037.3: give back every map/world overlay this addon was HOLDING — the
                                      //   ref count is shared with the client's own checkbox and the server,
                                      //   so an unreleased hold leaves an overlay drawn forever (D-097)
        LuaSound.teardownSounds(a);   // 024.2: silence anything the addon left in the air (a disabled addon making noise is a bug)
        LuaGOut.teardownTexts(a);     // 026.1: drop the addon's cached g:text renderings (frees their GL textures — we own them)
        UiApi.teardownGobOverlays(a); // 038.1: drop everything this addon attached to a game object, wherever it
                                      //   hangs — the ONE sweep of the object cache the feature costs, and the
                                      //   only one left: an overlay's state lives on the gob, so nothing else
                                      //   ever looks for it. The game's own overlays are untouched.
        UiApi.teardownGobScales(a);   // 046.1: put back every game object this addon resized — the same sweep,
                                      //   for the same reason, on the state's other half. A gob's size records
                                      //   who wrote it, so another addon's scale is left alone; nothing an
                                      //   addon that stopped running left distorted stays distorted.
        a.hudOverlays.clear();        // 2b: HUD overlays stop painting immediately (the paint iterates this list)
        LuaWidgetOverlay.teardown(a); // 103.3: ...and every painter this addon hung on a WIDGET comes off the
                                      //   widget as well as off the addon's list — the paint walks the widget's
                                      //   own field, so a record left there goes on painting for an addon that
                                      //   has stopped running, until the widget itself dies
        a.subs.clear();               // 041.1: the whole bus, in one drop — nothing to unsubscribe by hand
        a.timers.clear();
    }

    /**
     * Destroy every custom UI widget/window/control this addon owns (2a, and 040's controls). Widget removal
     * locks on {@code ui}. The list is typed by the {@link Owned} contract, so one path reaches a painted
     * surface and a {@code haven} control the addon built alike.
     */
    private static void destroyWidgets(Addon a) {
        if(a.widgets.isEmpty())
            return;
        List<Owned> ws = new ArrayList<Owned>(a.widgets);
        a.widgets.clear();
        // 072.1: one monitor per widget, taken from the widget — a teardown walks whatever this addon owns, and
        // two of its windows may stand in two different sessions' trees. Nothing here needs the whole list to be
        // one atomic act: each kill is a tree op, and each is serialised against the tick of its own tree.
        for(Owned w : ws) {
            synchronized(LuaWidget.monitor(w.rootw())) {
                try {
                    w.kill();   // stop callbacks + destroy its root (the window chrome, or the widget)
                } catch(RuntimeException e) {
                    /* a half-attached widget: best-effort, never abort teardown */
                }
            }
        }
    }


    // ------------------------------------------------------------- the way out (079.2)

    /**
     * How long the whole {@code Disable} sweep may take when the client is quitting. A wall-clock budget for
     * the <b>set</b> rather than a per-handler one: what has to be bounded is the exit, and an addon that
     * spends the budget is one the addons after it do not get to run in. Override with
     * {@code -Dhaven.addon.quitbudgetms}.
     */
    private static final long QUIT_BUDGET_MS = propLong("haven.addon.quitbudgetms", 2000L);

    /** {@link #shutdown} has run. The exit path is one call on one thread; this is the backstop. */
    private static boolean quitDone;

    /**
     * <b>What the client owes its addons on the way out</b> (079.2) — run from {@code Client.run}'s
     * {@code finally}, <b>before</b> {@code UILoop.dispose()}, because a destroyed {@code UI} is a session
     * whose per-character scope can no longer be named and whose tables are therefore written nowhere.
     *
     * <p><b>The flush and {@code Disable} are separated, because the obvious fix is worse than the defect.</b>
     * Firing {@code Disable} on the way out runs arbitrary addon Lua during shutdown, and a handler that loops
     * would hang the client on exit — lost data traded for a client that will not close. So:
     *
     * <ul>
     *   <li><b>The flush is engine code and always runs.</b> It walks every live session, writes what
     *       {@link StoreApi} holds, and runs nothing an addon wrote.</li>
     *   <li><b>{@code Disable} fires too and cannot delay the exit.</b> It runs on a thread of its own under
     *       {@link #QUIT_BUDGET_MS}, and a set that overruns is <b>abandoned</b> — with a line on the console,
     *       because a quit that silently dropped somebody's {@code Disable} would be this same defect one
     *       layer up.</li>
     * </ul>
     *
     * <p><b>Both run on that one thread in the ordinary case</b>, which is why the flush is inside it rather
     * than beside it: an addon that computes its state at {@code Disable} has that state written by the very
     * next thing that happens. Only when the sweep is abandoned <i>before</i> reaching the flush does this
     * thread run the flush itself, alongside a handler that is still going — which is what abandoning means.
     *
     * <p>The frame loop is <b>still running</b> when this is called — nothing stops ticking or drawing until
     * {@code UILoop.dispose()} returns — so {@link AddonManager#quiesce} closes Lua to every thread but the
     * sweep's before that thread starts, and the sweep itself waits out whatever was already inside
     * ({@link AddonManager#awaitIdle}). <b>This thread takes no lock at all</b>: it waits on a clock and
     * nothing else, which is what makes the exit unblockable by a wedged frame as well as by an addon.
     *
     * <p>The 30-second auto-save stays where it is — neither this nor anything else helps a crash or a kill,
     * and that is the only thing that ever did.
     */
    public static void shutdown() {
        if(quitDone)
            return;
        quitDone = true;
        final List<Addon> cur = new ArrayList<Addon>(addons);
        final long deadline = System.currentTimeMillis() + QUIT_BUDGET_MS;
        // 0 = still in the Disable sweep, 1 = flushing, 2 = done. Read by the abandoning branch below, which
        // must know whether the flush was ever reached and must not start a second one over the first.
        final java.util.concurrent.atomic.AtomicInteger stage = new java.util.concurrent.atomic.AtomicInteger(0);
        Thread sweep = new Thread(new Runnable() {
                public void run() {
                    try {
                        AddonManager.awaitIdle();   // let a tick or a draw already inside Lua finish first
                        for(int i = cur.size() - 1; i >= 0; i--) {     // reverse load order, as a teardown is
                            Addon a = cur.get(i);
                            if(System.currentTimeMillis() >= deadline) {
                                logDiag("shutdown: no budget left for " + ownerName(a) + "'s Disable -- skipped");
                                continue;
                            }
                            try {
                                fireTo(a, "Disable");
                            } catch(RuntimeException e) {
                                /* isolation is per-handler in callLua; this is just a backstop */
                            }
                        }
                    } finally {
                        stage.set(1);
                        flushAll(cur);
                        stage.set(2);
                    }
                }
            }, "addon-shutdown");
        sweep.setDaemon(true);          // the process leaves whether or not this thread ever finishes
        AddonManager.quiesce(sweep);    // ...and it is the only thread addon Lua is open to from here
        sweep.start();                  // THIS thread never takes a lock again: it waits on a clock alone
        try {
            sweep.join(QUIT_BUDGET_MS);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if(sweep.isAlive()) {
            if(stage.get() == 0) {
                logDiag("shutdown: an addon's Disable overran " + QUIT_BUDGET_MS + "ms -- abandoned;"
                        + " the saved variables are written without it");
                flushAll(cur);
            } else {
                logDiag("shutdown: the flush overran " + QUIT_BUDGET_MS + "ms -- the client leaves it running");
            }
        }
    }

    /**
     * The flush the quit always pays: every session that ended and was never drained, then every addon's own
     * write — its remembered placements, its account file and each live session's per-character variables,
     * which is what {@link StoreApi#flush} already walks. Engine code throughout: nothing here calls an addon.
     */
    private static void flushAll(List<Addon> cur) {
        try {
            StoreApi.drainEnded();   // a session that ended on the last frame still owes its own folder
        } catch(RuntimeException e) {
            logDiag("shutdown: could not write the sessions that had ended: " + e);
        }
        for(Addon a : cur) {
            try {
                StoreApi.flush(a);
            } catch(RuntimeException e) {
                logDiag("shutdown: could not flush " + ownerName(a) + ": " + e);
            }
        }
    }

    /** A {@code -D} long, as the addon layer's other tunables read one. */
    private static long propLong(String name, long def) {
        try {
            String v = Utils.getprop(name, null);
            return (v == null) ? def : Long.parseLong(v.trim());
        } catch(RuntimeException e) {
            return def;
        }
    }

    // ------------------------------------------------------------- reload + enabled set (1f-2)

    /**
     * Queue a full addon-layer reload; applied on the next UI-thread tick (see {@link #tick}).
     *
     * <p><b>Queued against the addon layer</b> (074.2), which is what a reload rebuilds. It used to be queued
     * against the session on screen, because that is where the addons lived; now they are the client's, so a
     * {@code :reload} typed on the login screen is as real as one typed in the world, and the pump that drains
     * it is the one that is always running.
     */
    static void queueReload() {
        AddonManager.SessionState st = AddonManager.state(AddonManager.layer());
        if(st == null) {
            log("no addon layer: nothing to reload");
            return;
        }
        st.reloadPending = true;
        log("reload queued");
    }

    /**
     * Reload the addon layer only (D-005) — no relog, the session stays connected. Tears down every
     * loaded addon (Disable → flush saved vars → drop owned resources, in reverse load order),
     * re-scans {@code addons/} and the enabled set, re-runs the enabled addons from disk (firing
     * {@code Load}), and — if already in-world — restores per-character saved vars and re-announces that
     * session with {@code SessionEnteredWorld}, so addons re-initialize as if freshly logged in (the WoW
     * {@code PLAYER_LOGIN} analog). Runs on the UI thread (queued via {@link #queueReload}); every session's
     * tick pump, gob callback and uimsg tap are left in place — only the Lua layer is rebuilt. Per-addon
     * teardown/load is error-isolated so one bad addon cannot abort the reload.
     *
     * <p><b>It takes no session</b> since 074.2, because the thing it rebuilds has none: the addons are the
     * client's. What it still needs a session for is the one thing that is a character's — the per-character
     * saved variables and the {@code SessionEnteredWorld} that follows them — and for that it asks the
     * <b>screen</b>, which is the character the user typed {@code :reload} while looking at. A reload on the
     * login screen rebuilds the layer and announces nothing, which is exactly what a fresh boot there does.
     *
     * <p><b>The screen's session and no other</b>, and that is a boundary rather than an oversight (074.3):
     * {@code SessionEnteredWorld} announces the character the player typed {@code :reload} in front of, and
     * announcing a second session here would be announcing one that did not enter anything. The other
     * sessions stay in the world and say nothing about it; what an addon knows about them after a reload is
     * what it asks for. Their <i>saved variables</i> are not part of that boundary (079.1): the tables are
     * each session's own and are read back in the first time the new addons ask for them, so a background
     * character's data is there whether or not anything was said about it.
     */
    public static synchronized void reload() {
        log("reloading addons...");
        List<Addon> cur = new ArrayList<Addon>(addons);
        for(int i = cur.size() - 1; i >= 0; i--)     // reverse load order
            teardown(cur.get(i));
        addons.clear();
        StoreApi.detach();                           // 074.4/079.1: every session's tables were just flushed
                                                     //   and belong to addons that are going; the ones about to
                                                     //   be built hold nobody until they are asked for
        LuaGOut.clearResourceCache();                // U1/D-039: drop the g:resource name cache on reload
        LuaSound.teardownSounds(AddonManager.consoleOwner);  // 024.2: the REPL survives a reload, its clips do not
        LuaGOut.teardownTexts(AddonManager.consoleOwner);    // 026.1: ...nor does its cached text (same reason)
        UiApi.teardownHidden(AddonManager.consoleOwner);     // 031.1: ...nor do the native windows it hid — with their
                                                             //   toggles now owned too, :reload IS the escape hatch
        UiApi.teardownMoved(AddonManager.consoleOwner);      // 036.1: ...nor the ones it moved, for the same reason.
                                                             //   Its SHEET survives a reload (like its site rules), so
                                                             //   036.2's re-fold hands a widget its own rule back
        MapApi.teardownOverlays(AddonManager.consoleOwner);  // 037.3: ...nor the overlays it was holding — :reload is
                                                             //   the escape hatch for a REPL line that took one
        MapImages.teardown(AddonManager.consoleOwner);       // 037.4: ...nor the map drawings it rendered (each is a
                                                             //   GL texture; the REPL owner has no other teardown)
        AddonPagina.teardownEntries(AddonManager.consoleOwner);   // 059.1: ...nor an action-menu entry a REPL line
                                                             //   added — :reload is its only way back out
        Gesture.teardown(AddonManager.consoleOwner);         // 062: ...nor a widget a REPL line armed for the user
                                                             //   to drag or resize — :reload is its only way back
        LuaWidget.rememberTeardown(AddonManager.consoleOwner);   // 062: ...nor one it asked to be remembered (the
                                                             //   binding; what the name holds is the character's)
        LuaGrab.teardownGrabs(AddonManager.consoleOwner);    // 041.5: ...nor a mouse grab a REPL line started and never
                                                             //   released — without this the pointer stays captured
                                                             //   (no camera pan, no clicks) until :release() is called
                                                             //   by hand, :reload's escape hatch not included
        loadAll();                                   // re-scan disk + enabled set; re-run; fire Load
        AddonManager.SessionState st = AddonManager.state(screen());   // the character on screen, if there is one
        GameUI g = (st == null) ? null : AddonManager.gui(st.ui);
        if(g != null) {
            StoreApi.enterWorld(st, g);              // reload per-char saved vars (the scope is still valid)
            String who = io.brodgar.session.Sessions.nameof(st.ui);   // 074.3: the session, not the addon
            if(who != null)
                fireSession("SessionEnteredWorld", who);
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
            if(!enabled)
                BeltHold.addonDisabled(id);   // 059.5: and its action-bar slots are the player's again, for
                                              //   good — the reload below hands each one back, and no restart
                                              //   brings the button to it
            reloadNeeded = true;
        }
    }

    /**
     * Scan {@link #addonDir()} and apply the declaring-addon default (disabled-by-default, opt-in per addon —
     * D-027/D-028): a discovered addon whose declared permissions are NOT all covered by what the user has
     * consented to is added to the persisted disabled set, so it comes back asking again. Cheap disk I/O (a
     * handful of small manifests); call on a (re)load / panel build, not per frame. The pure policy is
     * {@link #applyPermissionDefaults} (headless-testable).
     */
    private static void scanAddonDefaults() {
        File dir = addonDir();
        File[] subs = dir.listFiles(File::isDirectory);
        if(subs == null)
            return;
        Map<String, Set<Permission>> declares = new LinkedHashMap<String, Set<Permission>>();
        for(File sub : subs) {
            if(!new File(sub, "manifest.json").isFile())
                continue;
            try {
                declares.put(sub.getName(), Manifest.load(sub.toPath()).permissions.granted());
            } catch(Exception e) {
                /* a broken manifest surfaces as an error row elsewhere; no default to apply here */
            }
        }
        Set<String> disabled = disabledSet();
        int disBefore = disabled.size();          // applyPermissionDefaults only ADDS
        applyPermissionDefaults(consentedMap(), disabled, declares);
        if(disabled.size() != disBefore)
            Utils.setprefsl(PREF_DISABLED, disabled);
    }

    /**
     * The pure default policy (no I/O — D-027/D-028), one line: <b>declared ⊆ consented → the user's choice
     * stands; otherwise disable and let the consent dialog ask again.</b> An addon declaring NOTHING is never
     * touched (it is an ordinary read-only addon, enabled like any other), and one that asks for LESS than it
     * was granted never re-prompts — the record is additive per addon, so change detection is a containment
     * test rather than a diff. A never-consented declaration is not contained by an empty record, which is
     * exactly the disabled-by-default a newly discovered addon gets. {@code disabled} is mutated in place
     * (additions only); {@code consented} is read, never written — consent is recorded where it is GIVEN
     * ({@link #grantConsent}). Returns the ids of every declaring addon in {@code declares}. Headless-testable.
     */
    static Set<String> applyPermissionDefaults(Map<String, Set<Permission>> consented, Set<String> disabled,
                                               Map<String, Set<Permission>> declares) {
        Set<String> declaringIds = new LinkedHashSet<String>();
        for(Map.Entry<String, Set<Permission>> e : declares.entrySet()) {
            Set<Permission> declared = e.getValue();
            if((declared == null) || declared.isEmpty())
                continue;                        // declares nothing: never touched
            String id = e.getKey();
            declaringIds.add(id);
            Set<Permission> ok = consented.get(id);
            if((ok == null) || !ok.containsAll(declared))
                disabled.add(id);                // never consented, or now asking for more → ask again
        }
        return declaringIds;
    }

    /**
     * The permissions {@code id} declares that the user has NOT consented to, comma-separated, or {@code null}
     * if there are none (it declares nothing, or everything it asks for is already granted). What the console's
     * enable reports: the enabled bit can be flipped from anywhere, but the grant happens only in the consent
     * dialog, so any other path leaves the addon to be defaulted back to disabled on the next scan.
     */
    public static String consentPending(String id) {
        File dir = new File(addonDir(), id);
        if(!new File(dir, "manifest.json").isFile())
            return null;
        PermissionSet declared;
        try {
            declared = Manifest.load(dir.toPath()).permissions;
        } catch(Exception e) {
            return null;                         // a broken manifest is reported as an error row, not here
        }
        if(declared.isEmpty())
            return null;
        Set<Permission> ok = consentedMap().get(id);
        StringBuilder sb = new StringBuilder();
        for(Permission p : declared.granted()) {
            if((ok != null) && ok.contains(p))
                continue;
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(p.key);
        }
        return (sb.length() == 0) ? null : sb.toString();
    }

    /**
     * What the user has already approved for {@code id} — never {@code null}. The set the consent dialog marks
     * its NEW entries against ({@link PermissionSet#isNew}), so a re-prompt says which line is the escalation
     * instead of re-stating the whole list as if none of it had been seen.
     */
    public static Set<Permission> consentedKeys(String id) {
        Set<Permission> ok = consentedMap().get(id);
        return (ok == null) ? EnumSet.<Permission>noneOf(Permission.class) : ok;
    }

    /** The persisted consent record: addon id → the catalogue keys the user approved for it. */
    private static Map<String, Set<Permission>> consentedMap() {
        Map<String, Set<Permission>> out = new LinkedHashMap<String, Set<Permission>>();
        List<String> rows = Utils.getprefsl(PREF_CONSENTED, new String[0]);
        if(rows == null)
            return out;
        for(String row : rows) {
            int eq = row.indexOf('=');
            if(eq < 0)
                continue;
            Set<Permission> keys = EnumSet.noneOf(Permission.class);
            for(String k : row.substring(eq + 1).split(",")) {
                Permission p = Permission.byKey(k.trim());
                if(p != null)                    // a key this build no longer has grants nothing
                    keys.add(p);
            }
            out.put(row.substring(0, eq), keys);
        }
        return out;
    }

    /** Write the consent record back, one {@code "<id>=<key>,<key>"} row per addon. */
    private static void persistConsent(Map<String, Set<Permission>> consented) {
        List<String> rows = new ArrayList<String>();
        for(Map.Entry<String, Set<Permission>> e : consented.entrySet()) {
            StringBuilder sb = new StringBuilder(e.getKey()).append('=');
            boolean first = true;
            for(Permission p : e.getValue()) {
                if(!first)
                    sb.append(',');
                sb.append(p.key);
                first = false;
            }
            rows.add(sb.toString());
        }
        Utils.setprefsl(PREF_CONSENTED, rows);
    }

    /**
     * Record the user's consent for {@code id} (the keys {@code declared} asks for) and enable the addon — the
     * one door the AddOns panel's consent dialog confirms through. The record is <b>additive</b>: approving a
     * smaller declaration later never narrows it, so an addon that drops a permission is not re-prompted, while
     * one that adds a key is (the new key is not in the record until this runs again).
     */
    public static void grantConsent(String id, PermissionSet declared) {
        if((id == null) || id.isEmpty())
            return;
        Map<String, Set<Permission>> consented = consentedMap();
        Set<Permission> cur = consented.get(id);
        Set<Permission> merged = (cur == null) ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(cur);
        if(merged.addAll(declared.granted()) || (cur == null)) {
            consented.put(id, merged);
            persistConsent(consented);
        }
        setEnabled(id, true);
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
        public final boolean loaded;           // currently running
        public final PermissionSet permissions; // the protected keys it declared (D-027: default-disabled, consent at enable)
        public final List<String> networkHosts; // declared network allowlist (N2a / D-037); empty = no network
        public final String error;             // load/runtime error, or null
        /**
         * Why the {@code manifest.json} itself could not be read — the parser's own message (an unknown
         * permission key lists the whole vocabulary), or {@code null} when the manifest is fine. Distinct from
         * {@link #error}, which also carries a LOADED addon's Lua error: this addon has no manifest at all, so
         * there is nothing to enable and nothing the enabled bit can mean. The panel shows the reason and
         * renders the row unticked.
         */
        public final String manifestError;
        public final String warning;           // e.g. auto-disabled by the CPU watchdog, until the next load; or null

        AddonInfo(String id, String name, String version, String author, String description,
                  int apiVersion, boolean enabled, boolean loaded, PermissionSet permissions,
                  List<String> networkHosts, String error, String manifestError, String warning) {
            this.id = id; this.name = name; this.version = version; this.author = author;
            this.description = description; this.apiVersion = apiVersion; this.enabled = enabled;
            this.loaded = loaded; this.permissions = permissions;
            this.networkHosts = networkHosts; this.error = error; this.manifestError = manifestError;
            this.warning = warning;
        }

        /** Whether this addon declared any protected permission (it is then opt-in, behind the consent dialog). */
        public boolean declaresPermissions() {
            return (permissions != null) && !permissions.isEmpty();
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
        scanAddonDefaults();          // D-027: reflect the default-disable for any new or newly-widened declaration
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
            String mferr = null;
            // Keep the parser's OWN message: it is the only place the reason exists (an unknown permission key
            // lists the whole vocabulary), and the panel is where the author reads it — the terminal log is not
            // an answer to "why is this row broken".
            try { m = Manifest.load(sub.toPath()); } catch(Exception e) { mferr = reason(e); }
            Addon loaded = findLoaded(id);
            String error = (loaded != null) ? loaded.error : mferr;
            out.add(new AddonInfo(id,
                (m != null) ? m.name : id,
                (m != null) ? m.version : null,
                (m != null) ? m.author : null,
                (m != null) ? m.description : null,
                (m != null) ? m.apiVersion : 0,
                !disabled.contains(id),
                loaded != null,
                (m != null) ? m.permissions : PermissionSet.NONE,
                (m != null) ? m.network : java.util.Collections.<String>emptyList(),
                error,
                mferr,
                autoDisabledWarn.get(id)));
        }
        return out;
    }

    /** A thrown manifest problem as one readable line (some exceptions carry no message of their own). */
    private static String reason(Exception e) {
        String msg = e.getMessage();
        return ((msg == null) || msg.isEmpty()) ? e.toString() : msg;
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
    /** How many times the Lua layer has been built — boot plus every reload (074.2, {@code engineReloads}). */
    public static int loadGen() {
        return loadGen;
    }

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
