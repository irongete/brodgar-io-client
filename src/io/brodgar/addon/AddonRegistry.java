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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.prefs.Preferences;


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
     * What the user has CONSENTED to, per addon: {@code "<id>=<key>,<key>;<host>,<host>"} rows — the catalogue
     * keys they approved in the enable-time dialog, and <b>the hosts that dialog showed them</b>. The record is
     * what the default policy compares a manifest against (declared ⊆ consented → the user's choice stands), so
     * a manifest that later asks for MORE is disabled and asked again instead of silently escalating. Additive
     * per addon — asking for less never re-prompts.
     *
     * <p>The hosts are here because the dialog says them: the network key's line reads <i>"fetch data from the
     * servers it lists: api.example.com"</i>, so the host list is half of what the user answered and a record
     * keeping only the other half lets an addon rewrite its own {@code network.hosts} with nothing to compare
     * against. A row with <b>no {@code ;}</b> reads as <b>no hosts recorded</b>, which is the honest reading of
     * a row that carries none: a grant with no host in it authorises no host, and the addon is asked again.
     *
     * <p>All three fields are escaped by {@link #enc}, so a delimiter <i>inside</i> an id or a host is data
     * and not a boundary.
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
        loadErrors.clear();           // ...and so does the record of what threw last time
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
        // The consent record, read ONCE for the whole load: it is what BOTH gates ask (the manifest is only
        // the request), so every Addon is handed the keys and the hosts the user approved for it.
        // audit2 B08 (pm-03): the keys travel with the hosts. They used to be read off the manifest at the
        // call, so the two halves of one consent came from two sources and a widened key list was caught
        // only by the enable-time scan.
        Map<String, Consent> consented = consentedMap();
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
                Consent c = consented.get(sub.getName());
                Addon addon = new Addon(m, sub.toPath(), g,
                                        (c == null) ? Collections.<String>emptyList() : c.hosts,
                                        (c == null) ? EnumSet.<Permission>noneOf(Permission.class) : c.keys);
                installHafen(g, addon);
                LuaTable ad = new LuaTable();
                ad.set("id", LuaValue.valueOf(m.id));
                ad.set("dir", LuaValue.valueOf(sub.getAbsolutePath()));
                g.set("ADDON", ad);
                addon.run();
                // audit2 B14 (lc-04): AN ADDON WHOSE FILE BODY THREW IS NOT LOADED. It used to be added to
                // the live set first and asked about its error afterwards, so a half-run body stayed in
                // `addons` with whatever it had registered before the throw -- its timers firing, its
                // handlers running, its widgets drawn -- and no `Load` ever fired for any of it. Nothing on
                // the tick path asks `.error`, and nothing should have to: the set is the addons that
                // loaded. A failed one is torn down, so what it did register before the throw is released
                // by the same sweep a disable runs, and its row still reports the error to the panel.
                if(addon.error == null) {
                    addons.add(addon);
                    fireTo(addon, "Load");                    // the addon's file body just ran
                    log("loaded " + m.id + " v" + m.version);
                } else {
                    log("error in " + m.id + ": " + addon.error);
                    loadErrors.put(sub.getName(), addon.error);   // ...the panel still says what threw
                    teardown(addon);
                }
            } catch(Exception e) {
                log("failed to load '" + sub.getName() + "': " + Refusal.reason(e));
            }
        }
        log(addons.size() + " addon(s) loaded");
    }

    /**
     * <b>What an addon's file body threw</b>, by id — the record that outlives the {@link Addon} itself
     * (audit2 B14, lc-04). A body that threw is not loaded, so the failed addon is not in {@link #addons}
     * and nothing there can be asked why; the panel's row and {@link #liveStatus} read this instead. Cleared
     * by every (re)load, like {@link AddonManager#autoDisabledWarn} beside it, because a load that succeeds
     * is the answer to "why did it fail".
     */
    private static final Map<String, String> loadErrors = new java.util.concurrent.ConcurrentHashMap<String, String>();

    /**
     * <b>One thing an addon owned, and the release of it</b> — a step of {@link #teardown}, carrying the name
     * the log gives it when it is the step that failed. A step is <i>registered</i> rather than written into a
     * statement sequence, and that is the whole of what makes a teardown finish: {@link #guarded} runs each one
     * on its own, so no subsystem can take the thirty after it down with it.
     */
    private static final class Step {
        final String name;                 // what a failure is named by; nothing else reads it
        final Consumer<Addon> run;

        Step(String name, Consumer<Addon> run) {
            this.name = name;
            this.run = run;
        }
    }

    /**
     * <b>Everything an addon owns, in the order it is given back</b> — the teardown itself, as data. The order
     * is load-bearing and each step says why it sits where it does; what is <b>not</b> load-bearing is any
     * step's success, which is exactly why this is a list walked one entry at a time rather than a sequence of
     * statements where the first throw ends the rest.
     *
     * <p><b>One walk for every owner</b>, the {@code :lua} REPL included: {@link AddonManager#consoleOwner} is
     * an {@link Addon} and owns what an addon owns, so a reload gives its console lines back exactly what a
     * disable gives an addon back. There is one answer to "what does a teardown release" rather than one for
     * an addon and a shorter one for the console.
     */
    private static final List<Step> STEPS = Collections.unmodifiableList(Arrays.asList(
        // The addon's last chance to write its store tables... (isolation is per-handler in callLua; this
        // step's own guard is the backstop)
        new Step("Disable", a -> fireTo(a, "Disable")),
        // ...then persist them (spec 05: flushed at Disable)
        new Step("saved variables", StoreApi::flush),
        // 044.1: take every widget this addon stood in the world back OUT of its surface first, so the two
        //   teardowns below see an ordinary widget on the flat UI. Standing is a re-home, so it has to be
        //   undone before anything decides where a widget ends up — the hidden-native restore reads where the
        //   widget is, and destroyWidgets disposes what it finds.
        new Step("standing widgets", VirtualApi::teardownSurfaces),
        // ...and beside it, every widget of the CLIENT's this addon TOOK into a surface of its own
        //   (widget:parent(p)). Same reason as the step above, and the same order: destroyWidgets below
        //   disposes recursively, so a minimap still inside one of our panels would go down with it. BEFORE
        //   the moved-windows step, which restores where it stands: this one only answers what it hangs under,
        //   and that one has to have the last word.
        new Step("re-homed widgets", UiApi::teardownRehomed),
        // 029.2/031.2: give back every native widget the addon hid — and its toggle — under the one rule: the
        //   window ends up as the user was seeing it, and a substitution ends whole (the stand-in view dies
        //   with it, 032.1). BEFORE destroyWidgets: the rule reads the view's visibility, and a destroyed view
        //   stands for nothing.
        new Step("hidden windows", UiApi::teardownHidden),
        // 116.1: ...and the radial menu it hid, on the same rule — a ring an addon took out of the paint is
        //   painted again the moment that addon stops running, however much of its second is left.
        new Step("hidden radial menu", FlowerMenuApi::teardown),
        // custom UI vanishes cleanly (2a; before subs, so no dangling callbacks)
        new Step("widgets", AddonRegistry::destroyWidgets),
        // 041.3/041.4: deafen every widget:on() listener + drop every poll registration (engine widgets
        //   outlive a :reload — must detach before the Lua layer that owns them rebuilds)
        new Step("widget subscriptions", Addon::teardownWidgetSubs),
        // 041.2: stop intercepting the outbound wdgmsg stream, and the inbound uimsg one — where the L2/L3
        //   hook registries were unregistered, and for the same reason: the rest of this teardown can itself
        //   make the client send and receive
        new Step("action subscriptions", a -> a.actionSubs.clear()),
        new Step("message subscriptions", a -> a.messageSubs.clear()),
        // 2e-2: unregister global hotkeys from the GlobKeyEvent dispatch list
        new Step("key binds", HookApi::teardownKeyBinds),
        // 030.2: drop the selector subscriptions (no disappear — reload != destroy)
        new Step("selector watches", UiApi::teardownSelectorWatches),
        // A11: drop the addon's live console handlers (Console dispatchers stay — C1)
        new Step("console commands", HookApi::teardownConsoleCommands),
        // 059.4: give back every action-bar slot this addon was HOLDING — the slot goes back to the server's
        //   own content, which never changed
        new Step("belt holds", BeltHold::teardownHolds),
        // 059.1: take every entry this addon added to the action menu back out of the grid. BEFORE the asset
        //   step below: an entry draws one of the addon's images, and a cell must stop being laid out before
        //   the texture under it is disposed
        new Step("menu entries", AddonPagina::teardownEntries),
        // V1: destroy client-only world ghosts (remove the scene slot + free the sprite)
        new Step("ghosts", VirtualApi::teardownGhosts),
        // R2: destroy client-only world sprites (remove the slot + free the quad geometry)
        new Step("sprites", VirtualApi::teardownSprites),
        // R3: destroy client-only world objects (remove the slot + free the glTF Models; before the meshes)
        new Step("objects", VirtualApi::teardownObjects),
        // 118: take every patch off the ground (its overlay out of the MCache it was registered in)
        new Step("patches", VirtualApi::teardownPatches),
        // 037.4: free the map drawings the client rendered for this addon (grid:image / grid:overlayImage) —
        //   they are TexIs like any other image and ride the same registry, so this only has to drop the
        //   bounded cache; it runs BEFORE the asset step so nothing is left pointing at a disposed texture
        new Step("map images", MapImages::teardown),
        // 028.1: dispose every loaded asset — images (each TexI's GL texture; after the sprites that sampled
        //   it), then meshes (the shared base-colour TexIs; AFTER the objects above, R3b), then the intern
        //   cache itself. Fonts own nothing releasable; the fonts step below reverts their overrides.
        new Step("assets", AssetApi::teardownAssets),
        // 041.5: release any active mouse-drag grab (drops the UI.Grab + unlinks the widget)
        new Step("mouse grabs", LuaGrab::teardownGrabs),
        // 062: ...and every widget:draggable(h)/:resizable(h) binding — ending a gesture running right now,
        //   and deafening the last listener on each handle. BEFORE the moved-windows step below: what a
        //   gesture WROTE is a layout level, and it is that sweep which gives the place back
        new Step("gestures", Gesture::teardown),
        // 062: ...and every widget:remember(name) BINDING. What each name holds is left on disk untouched —
        //   the flush above wrote it — because a reload is not the user changing their mind about where a
        //   window goes. widget:remember(nil) is the only thing that forgets.
        new Step("remembered placements", LuaWidget::rememberTeardown),
        // N2a: cancel every in-flight HTTP request — the connection is closed under the worker, so a torn-down
        //   addon's request stops reaching the host rather than merely losing its callback
        new Step("http requests", HttpApi::teardownRequests),
        // 042.1: cancel every pending Resolve registration (a value still loading) — same shape as the HTTP
        //   requests above, for the same reason
        new Step("pending resolves", Addon::teardownWaitings),
        // 102.1: give the client its own words back -- this addon's CATALOGUE leaves the provider stack and
        //   every string it displayed reverts to the English the client wrote. Beside the fonts step below,
        //   and for the same reason: both bump the generation every routed site rebuilds on
        new Step("locale", LocaleApi::teardown),
        // 033.1: drop this addon's STYLESHEET (hafen.ui():sheet()) and its per-widget widget:setFont overrides
        //   in one sweep (bumps gen -> stock foundry restored)
        new Step("fonts", FontApi::teardownFonts),
        // 036.1: put every native widget this addon laid out back where the user had it — an addon's layout is
        //   a LAYER over the client's, so nothing of ours is left behind for GameUI.savewndpos to persist as
        //   their preference. AFTER the fonts step (036.2): the sheet's own pos/size rules have to have
        //   stopped resolving first, or re-running the cascade would put them back
        new Step("moved windows", UiApi::teardownMoved),
        // 128.3: ...and beside it, the drag listeners the disposal seam never reaches — a dead anchor target
        //   whose tree had already gone is drained by nothing, so it is the one layout record a widget's own
        //   death cannot take out
        new Step("drag listeners", Layout::teardown),
        // 037.3: give back every map/world overlay this addon was HOLDING — the ref count is shared with the
        //   client's own checkbox and the server, so an unreleased hold leaves an overlay drawn forever (D-097)
        new Step("map overlays", MapApi::teardownOverlays),
        // 024.2: silence anything the addon left in the air (a disabled addon making noise is a bug)
        new Step("sounds", LuaSound::teardownSounds),
        // 026.1: drop the addon's cached g:text renderings (frees their GL textures — we own them)
        new Step("texts", LuaGOut::teardownTexts),
        // B01: ...and its g:resource caches beside them — the name lookups and the LINEAR-sampled texture
        //   copies, which are ours too. They were one set for the client, dropped by a full :reload alone; per
        //   addon a disable frees what that addon drew and nothing else
        new Step("resources", LuaGOut::dropResources),
        // 038.1: drop everything this addon attached to a game object, wherever it hangs — the ONE sweep of
        //   the object cache the feature costs, and the only one left: an overlay's state lives on the gob, so
        //   nothing else ever looks for it. The game's own overlays are untouched.
        new Step("gob overlays", UiApi::teardownGobOverlays),
        // 105: the pointer is one, and an addon that has stopped running does not hold it
        new Step("cursor", UiApi::teardownCursor),
        // 046.1: put back every game object this addon resized — the same sweep, for the same reason, on the
        //   state's other half. A gob's size records who wrote it, so another addon's scale is left alone;
        //   nothing an addon that stopped running left distorted stays distorted.
        //   114.3: ...and every object it hid is drawn again, in the same walk.
        new Step("gob scales", UiApi::teardownGobScales),
        // 2b: HUD overlays stop painting immediately (the paint iterates this list)
        new Step("hud overlays", a -> a.hudOverlays.clear()),
        // 103.3: ...and every painter this addon hung on a WIDGET comes off the widget as well as off the
        //   addon's list — the paint walks the widget's own field, so a record left there goes on painting for
        //   an addon that has stopped running, until the widget itself dies
        new Step("widget overlays", LuaWidgetOverlay::teardown),
        // 041.1: the whole bus, in one drop — nothing to unsubscribe by hand
        new Step("subscriptions", a -> a.subs.clear()),
        // audit2 B14 (tm-06): each timer is MARKED DEAD before the list is dropped. `alive` is the flag
        //   every read of a timer handle keys on, and clearing the list alone left it true on a timer this
        //   very step had just dropped -- a handle held past a teardown answering "still ticking" about
        //   something with nothing left to tick it.
        new Step("timers", a -> {
            for(AddonManager.Timer t : a.timers)
                t.alive = false;
            a.timers.clear();
        })));

    /**
     * <b>Fire {@code Disable}, flush the addon's saved vars, then drop its owned resources</b> — every step of
     * {@link #STEPS}, whatever any one of them does. Takes {@code null}, because there may never have been a
     * {@code :lua} line and {@link AddonManager#consoleOwner} is minted by the first one.
     */
    static void teardown(Addon a) {
        if(a == null)
            return;
        for(Step s : STEPS)
            guarded(a, s);
    }

    /**
     * <b>Run one step, and let nothing out of it.</b> The addon is going away and every step after this one
     * still has something to give back, so a step that fails is a line in the log and no more — which is also
     * the only place such a failure can be seen, since by here there is no addon left to tell.
     *
     * <p>{@code Throwable} rather than {@code RuntimeException}: a teardown is the last chance to hand
     * something back, and an {@code Error} out of one subsystem must not leave the client holding the other
     * forty. Nothing is rethrown, because there is no caller a teardown failure means anything to.
     */
    private static void guarded(Addon a, Step s) {
        try {
            s.run.accept(a);
        } catch(Throwable t) {
            logAbout(a, "teardown: " + s.name + " failed: " + Refusal.reason(t));
        }
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
        // 0 = still in the Disable sweep, 1 = flushing, 2 = done. THE CLAIM ON THE FLUSH, and it is a
        // compare-and-set on both sides (audit2 B06): the abandoning branch below used to read 0 and then
        // flush while this thread's finally set 1 and flushed too, so an abandoned quit wrote every store
        // file from two threads at once. Whichever thread moves it off 0 is the one that writes.
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
                        if(stage.compareAndSet(0, 1)) {
                            flushAll(cur);
                            stage.set(2);
                        }
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
            if(stage.compareAndSet(0, 1)) {
                logDiag("shutdown: an addon's Disable overran " + QUIT_BUDGET_MS + "ms -- abandoned;"
                        + " the saved variables are written without it");
                flushAll(cur);
                stage.set(2);
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
     * <p><b>Queued against the addon layer</b> (074.2), which is what a reload rebuilds — not against the
     * session on screen, because the addons are the client's rather than a character's. So a {@code :reload}
     * typed on the login screen is as real as one typed in the world, and the pump that drains it is the one
     * that is always running.
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
     * {@code Load}), and then — for every session that is in the world — restores that character's saved
     * vars and announces it with {@code SessionEnteredWorld}, so addons re-initialize as if freshly logged
     * in (the WoW {@code PLAYER_LOGIN} analog). Runs on the UI thread (queued via {@link #queueReload});
     * every session's tick pump, gob callback and uimsg tap are left in place — only the Lua layer is
     * rebuilt. Per-addon teardown/load is error-isolated so one bad addon cannot abort the reload.
     *
     * <p><b>It takes no session</b> since 074.2, because the thing it rebuilds has none: the addons are the
     * client's. What it still needs sessions for is the one thing that is a character's — the per-character
     * saved variables and the {@code SessionEnteredWorld} that follows them — and for that it asks
     * {@link AddonManager#allStates()} rather than any one login. With nothing in the world it rebuilds the
     * layer and announces nothing, which is what a fresh boot on the login screen does.
     *
     * <p><b>Every session in the world, and the screen's first</b> (124.1): the announcement means
     * <i>re-initialize for this session</i>, and which character the player happened to be looking at when
     * they typed {@code :reload} is not a property of a session at all. So the walk is
     * {@link #announceEnteredWorld}, taken over every state behind that method's three gates — and the
     * screen's is taken first, so one login is announced exactly as it would be alone and the rule is purely
     * additive. No session is announced twice, and an addon that holds something per login has no catch-up
     * walk of {@code hafen.session():list()} to write.
     */
    public static synchronized void reload() {
        log("reloading addons...");
        List<Addon> cur = new ArrayList<Addon>(addons);
        for(int i = cur.size() - 1; i >= 0; i--)     // reverse load order
            teardown(cur.get(i));
        teardown(AddonManager.consoleOwner);         // ...and the :lua REPL owner, by the same walk. It survives a
                                                     //   reload and what it owns does not: a console line's window,
                                                     //   its label on a game object, its clips, its hotkey and its
                                                     //   held overlay all go, which is what makes :reload the
                                                     //   escape hatch for a line typed at the console. The REPL is
                                                     //   an Addon, so it is torn down as one rather than by a
                                                     //   shorter list that drifts from this one
        addons.clear();
        StoreApi.detach();                           // 074.4/079.1: every session's tables were just flushed
                                                     //   and belong to addons that are going; the ones about to
                                                     //   be built hold nobody until they are asked for
        loadAll();                                   // re-scan disk + enabled set; re-run; fire Load
        // 124.1: the screen's state first and every other in the world after it. The order is fixed here
        //   rather than left to the map so that one login is announced the way it always was, and the list
        //   is taken before anything fires: a handler may log a character in or out, and this walk is about
        //   the world the reload found.
        AddonManager.SessionState scr = AddonManager.state(screen());   // the character on screen, if there is one
        List<AddonManager.SessionState> order = new ArrayList<AddonManager.SessionState>();
        if(scr != null)
            order.add(scr);
        for(AddonManager.SessionState st : AddonManager.allStates()) {
            if(st != scr)
                order.add(st);
        }
        for(AddonManager.SessionState st : order)
            announceEnteredWorld(st);
        reloadGen++;                                 // notify any live AddOns panel to rebuild its rows
        log("reload complete (" + addons.size() + " addon[s] active)");
    }

    /**
     * <b>One session re-enters the world</b> (124.1) — what {@link #reload} does for each state it walks,
     * behind the three gates that decide whether there is anything to announce:
     *
     * <ul>
     *   <li>the {@code UI} is not destroyed — {@code AddonManager.sweepStates()} runs on a tick, so an
     *       entry whose session has ended can still be in the map when a reload walks it;</li>
     *   <li>it has a {@link GameUI} — the session is in the world, and this is also what leaves out the
     *       addon layer's own state, which is no session and has no HUD;</li>
     *   <li>{@code Sessions.nameof} names an account — a member that has gone answers {@code null}, and the
     *       account name is the whole of the payload.</li>
     * </ul>
     *
     * <p>Nothing at all for a state that fails one, which is what makes every entry in the map safe to hand
     * it. {@code enterWorldPending} is deliberately left alone: arming it would have the tick fire a second
     * announcement for the same session a frame later.
     */
    private static void announceEnteredWorld(AddonManager.SessionState st) {
        if((st == null) || st.ui.destroyed)
            return;
        GameUI g = AddonManager.gui(st.ui);
        if(g == null)
            return;
        String who = io.brodgar.session.Sessions.nameof(st.ui);   // 074.3: the session, not the addon
        if(who == null)
            return;
        StoreApi.enterWorld(st, g);                  // this character's saved vars (the scope is still valid)
        fireSession("SessionEnteredWorld", who);
    }

    /**
     * The persisted disabled set, held in memory.
     *
     * <p>{@code Utils.getprefsl} is {@code java.util.prefs}, which on Windows is the REGISTRY: one
     * {@code disabledSet()} was a {@code WindowsRegQueryValueEx} plus a UTF-8 decode of the packed list plus
     * a fresh {@code LinkedHashSet}. That is nothing once and everything per frame — {@code isEnabled} reads
     * the whole list to answer about one id, {@code liveStatus} calls it, and {@code AddonPanel.Row.tick}
     * calls that for every row of every frame. With the panel built once (it is hidden and not destroyed, so
     * it goes on ticking) a profile measured ~2600 registry reads a second, a quarter of everything the UI
     * thread did in Java.
     *
     * <p>Volatile and immutable: the reload thread and the UI thread both read it, and handing out a shared
     * mutable set is how a caller's {@code add} would silently become the persisted state. {@link
     * #writeDisabled} is the one door that writes, so the cache cannot drift from the preference — the one
     * thing it does not see is a SECOND client instance writing the same node, which is read once at startup
     * as it always was and not polled for afterwards.
     */
    private static volatile Set<String> disabledCache = null;

    /** The persisted set of disabled addon ids (client-scope). An addon runs unless it is in here. */
    private static Set<String> disabledSet() {
        Set<String> d = disabledCache;
        if(d == null) {
            List<String> l = Utils.getprefsl(PREF_DISABLED, new String[0]);
            d = freeze((l == null) ? new LinkedHashSet<String>() : new LinkedHashSet<String>(l));
            disabledCache = d;
        }
        return d;
    }

    /** A mutable copy for a caller that is about to change the set and hand it to {@link #writeDisabled}. */
    private static Set<String> disabledCopy() {
        return new LinkedHashSet<String>(disabledSet());
    }

    /** Persist the disabled set and refresh the cache from the same value — the only place either happens. */
    private static void writeDisabled(Set<String> d) {
        Utils.setprefsl(PREF_DISABLED, d);
        disabledCache = freeze(new LinkedHashSet<String>(d));
    }

    /** Insertion order is the persisted order, so the copy stays a LinkedHashSet. */
    private static Set<String> freeze(Set<String> d) {
        return Collections.unmodifiableSet(d);
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
        Set<String> d = disabledCopy();
        boolean changed = enabled ? d.remove(id) : d.add(id);
        if(changed) {
            writeDisabled(d);
            if(!enabled)
                BeltHold.addonDisabled(id);   // 059.5: and its action-bar slots are the player's again, for
                                              //   good — the reload below hands each one back, and no restart
                                              //   brings the button to it
            reloadNeeded = true;
        }
    }

    /**
     * Scan {@link #addonDir()} and apply the declaring-addon default (disabled-by-default, opt-in per addon —
     * D-027/D-028): a discovered addon whose declared permissions — <b>or the hosts those permissions take</b>
     * — are NOT all covered by what the user has consented to is added to the persisted disabled set, so it
     * comes back asking again. Cheap disk I/O (a handful of small manifests); call on a (re)load / panel build,
     * not per frame. The pure policy is {@link #applyPermissionDefaults} (headless-testable).
     */
    private static void scanAddonDefaults() {
        File dir = addonDir();
        File[] subs = dir.listFiles(File::isDirectory);
        if(subs == null)
            return;
        Map<String, Set<Permission>> declares = new LinkedHashMap<String, Set<Permission>>();
        Map<String, List<String>> hosts = new LinkedHashMap<String, List<String>>();
        for(File sub : subs) {
            if(!new File(sub, "manifest.json").isFile())
                continue;
            try {
                // ONE load per manifest, both halves off the same object: the hosts are the network key's
                // argument, so reading them costs no disk I/O this scan was not already paying.
                Manifest m = Manifest.load(sub.toPath());
                declares.put(sub.getName(), m.permissions.granted());
                hosts.put(sub.getName(), m.network);
            } catch(Exception e) {
                /* a broken manifest surfaces as an error row elsewhere; no default to apply here */
            }
        }
        Set<String> disabled = disabledCopy();
        int disBefore = disabled.size();          // applyPermissionDefaults only ADDS
        applyPermissionDefaults(consentedMap(), disabled, declares, hosts);
        if(disabled.size() != disBefore)
            writeDisabled(disabled);
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
     *
     * <p><b>Two containments, one sentence.</b> {@code hosts} is each addon's declared {@code network} block,
     * and it is tested the same way: the hosts an addon asks for must be covered by the hosts the user
     * approved ({@link Manifest#hostsUncovered}), or it is disabled and asked again. They are what the dialog
     * showed on the network key's own line, so they are half of what the user answered — and the half an addon
     * can rewrite between one load and the next. A manifest carrying hosts always grants a network key
     * ({@link Manifest#load} refuses one that does not), so a host in this map is always a host that was on
     * the screen. Adding one asks again; dropping one asks nothing, exactly as for a key.
     */
    static Set<String> applyPermissionDefaults(Map<String, Consent> consented, Set<String> disabled,
                                               Map<String, Set<Permission>> declares,
                                               Map<String, List<String>> hosts) {
        Set<String> declaringIds = new LinkedHashSet<String>();
        for(Map.Entry<String, Set<Permission>> e : declares.entrySet()) {
            Set<Permission> declared = e.getValue();
            if((declared == null) || declared.isEmpty())
                continue;                        // declares nothing: never touched
            String id = e.getKey();
            declaringIds.add(id);
            Consent ok = consented.get(id);
            List<String> want = (hosts == null) ? null : hosts.get(id);
            if((ok == null) || !ok.keys.containsAll(declared)
               || !Manifest.hostsUncovered(ok.hosts, want).isEmpty())
                disabled.add(id);                // never consented, or now asking for more → ask again
        }
        return declaringIds;
    }

    /**
     * What {@code id} declares that the user has NOT consented to — <b>the keys, and the hosts those keys
     * take</b> — comma-separated, or {@code null} if there is none (it declares nothing, or everything it asks
     * for is already granted). What the console's enable reports: the enabled bit can be flipped from anywhere,
     * but the grant happens only in the consent dialog, so any other path leaves the addon to be defaulted back
     * to disabled on the next scan. A host is listed here for the reason it is tested in
     * {@link #applyPermissionDefaults}: it is half of what the user answered, so an enable the scan is about to
     * undo has to say so whichever half is the reason.
     */
    public static String consentPending(String id) {
        File dir = new File(addonDir(), id);
        if(!new File(dir, "manifest.json").isFile())
            return null;
        Manifest m;
        try {
            m = Manifest.load(dir.toPath());
        } catch(Exception e) {
            return null;                         // a broken manifest is reported as an error row, not here
        }
        if(m.permissions.isEmpty())
            return null;                         // no key ⇒ no host either: Manifest.load refuses hosts alone
        Consent ok = consentedMap().get(id);
        StringBuilder sb = new StringBuilder();
        for(Permission p : m.permissions.granted()) {
            if((ok != null) && ok.keys.contains(p))
                continue;
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(p.key);
        }
        for(String host : Manifest.hostsUncovered((ok == null) ? null : ok.hosts, m.network)) {
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(host);
        }
        return (sb.length() == 0) ? null : sb.toString();
    }

    /**
     * What the user has already approved for {@code id} — never {@code null}. The set the consent dialog marks
     * its NEW entries against ({@link PermissionSet#isNew}), so a re-prompt says which line is the escalation
     * instead of re-stating the whole list as if none of it had been seen.
     */
    public static Set<Permission> consentedKeys(String id) {
        Consent ok = consentedMap().get(id);
        return (ok == null) ? EnumSet.<Permission>noneOf(Permission.class) : ok.keys;
    }

    /**
     * The hosts the user has already approved for {@code id} — never {@code null}. The other half of
     * {@link #consentedKeys}, and the list the consent dialog marks a NEW host against
     * ({@link PermissionSet#describe}): the dialog shows the manifest's hosts, so it needs the record's to say
     * which of them the user has not seen. It is the same list the gate reads ({@link Addon#hostGranted}), so
     * a host marked NEW there is exactly a host a request is refused for until it is approved.
     */
    public static List<String> consentedHosts(String id) {
        Consent ok = consentedMap().get(id);
        return (ok == null) ? Collections.<String>emptyList() : ok.hosts;
    }

    /**
     * One addon's row in the consent record: the catalogue keys the user approved, and the hosts the dialog
     * showed them when they did. Both halves, because both halves are what the dialog said and what the user
     * answered — a record keeping the keys alone is a record of half the decision. Immutable; the merge that
     * makes the record additive happens in {@link #grantConsent}.
     */
    static final class Consent {
        final Set<Permission> keys;
        final List<String> hosts;

        Consent(Set<Permission> keys, List<String> hosts) {
            this.keys = Collections.unmodifiableSet(keys);
            this.hosts = Collections.unmodifiableList(hosts);
        }
    }

    /** The digits {@link #enc} spends on an escape. */
    private static final String HEX = "0123456789ABCDEF";

    /**
     * The row codec: percent-encode {@code %} and the row's own three delimiters — {@code =}, {@code ,} and
     * {@code ;} — and nothing else. It is applied to <b>all three fields</b>, the id, every key and every
     * host, so there is one encoder and no field is the exception that rots: a key is a closed {@code [a-z.]}
     * catalogue today, and a field left unescaped because of what its vocabulary happens to be is a field that
     * breaks the day the vocabulary moves.
     *
     * <p>Because the delimiters are escaped, the parse in {@link #consentedMap} stays exactly as cheap as it
     * was: the first literal {@code =} is still the id boundary and the first literal {@code ;} still the
     * key/host boundary. And because nothing else is touched, an ordinary row is <b>byte-identical</b> to what
     * was written before this codec — a folder name and a domain name carry none of the four — so the record
     * needs no migration and no new pref name. What changes is the row that could not be written at all: an id
     * containing {@code =} recorded nothing and re-prompted for ever, because {@link #consentedMap} cut it at
     * its own {@code =} and read the tail as keys. It round-trips now.
     */
    private static String enc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for(int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if((c == '%') || (c == '=') || (c == ',') || (c == ';'))
                sb.append('%').append(HEX.charAt((c >> 4) & 0xf)).append(HEX.charAt(c & 0xf));
            else
                sb.append(c);
        }
        return sb.toString();
    }

    /**
     * {@link #enc}'s inverse, and tolerant where it has to be: the record is a preference a user can edit by
     * hand, so a {@code %} that is not followed by two hex digits reads as the literal {@code %} it is. One
     * malformed row may not cost the whole record, and the whole record is every grant the user ever gave.
     */
    private static String dec(String s) {
        if(s.indexOf('%') < 0)
            return s;                        // the ordinary row: nothing to undo
        StringBuilder sb = new StringBuilder(s.length());
        for(int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int hi, lo;
            if((c == '%') && ((i + 2) < s.length())
               && ((hi = Character.digit(s.charAt(i + 1), 16)) >= 0)
               && ((lo = Character.digit(s.charAt(i + 2), 16)) >= 0)) {
                sb.append((char)((hi << 4) | lo));
                i += 2;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** The persisted consent record: addon id → the keys and the hosts the user approved for it. */
    private static Map<String, Consent> consentedMap() {
        Map<String, Consent> out = new LinkedHashMap<String, Consent>();
        List<String> rows = Utils.getprefsl(PREF_CONSENTED, new String[0]);
        if(rows == null)
            return out;
        for(String row : rows) {
            int eq = row.indexOf('=');
            if(eq < 0)
                continue;
            String tail = row.substring(eq + 1);
            int semi = tail.indexOf(';');        // no ';' ⇒ no hosts recorded (a row written before the field)
            Set<Permission> keys = EnumSet.noneOf(Permission.class);
            for(String k : ((semi < 0) ? tail : tail.substring(0, semi)).split(",")) {
                Permission p = Permission.byKey(dec(k.trim()));
                if(p != null)                    // a key this build no longer has grants nothing
                    keys.add(p);
            }
            // A host has no catalogue to be unknown against, so an unrecognised one is KEPT: dropping it
            // would silently narrow a grant the user did give, which is the one direction a read may not take.
            List<String> hosts = new ArrayList<String>();
            if(semi >= 0) {
                for(String h : tail.substring(semi + 1).split(",")) {
                    String host = dec(h.trim());
                    if(!host.isEmpty())
                        hosts.add(host);
                }
            }
            out.put(dec(row.substring(0, eq)), new Consent(keys, hosts));
        }
        return out;
    }

    /**
     * The byte budget the whole consent record has, and why it is measured before it is written.
     * {@code Utils.setprefsl} frames the rows as NUL-separated UTF-8 and hands the bytes to
     * {@code Utils.setprefb}, which is {@code Preferences.putByteArray} — and that Base64s the array into one
     * preference value capped at {@code MAX_VALUE_LENGTH}, refusing an array longer than three quarters of
     * that with an {@code IllegalArgumentException}. {@code Utils.setpref*} catches only
     * {@code SecurityException}, so the refusal escapes raw from whoever wrote the value. This record is the
     * one place addon-authored strings reach it — an id is a folder name and a host is a manifest line, both
     * written by the addon — so the bound is taken here, from the store's own constant.
     */
    static final int PREF_VALUE_BYTES = Preferences.MAX_VALUE_LENGTH * 3 / 4;

    /**
     * What {@code Utils.setprefsl} will hand the store for {@code rows}: each row's UTF-8 plus the NUL that
     * separates it from the next. The framing is measured rather than assumed, because the budget is a byte
     * count and a row is a string — one non-ASCII character in an id costs more than one byte of it.
     */
    private static int prefslBytes(List<String> rows) {
        int n = 0;
        for(String row : rows)
            n += row.getBytes(StandardCharsets.UTF_8).length + 1;
        return n;
    }

    /**
     * Encode the consent record, one {@code "<id>=<key>,<key>;<host>,<host>"} row per addon, every field
     * through {@link #enc} so a delimiter inside one is data. An addon with no hosts writes no {@code ;} — the
     * row is exactly what it would have been without the field, and reads back as the nothing it recorded.
     * The rows are handed back rather than written so that {@link #grantConsent} can measure them against
     * {@link #PREF_VALUE_BYTES} first: this is the whole record, so one addon's grant is written only as part
     * of every other addon's.
     */
    private static List<String> consentRows(Map<String, Consent> consented) {
        List<String> rows = new ArrayList<String>();
        for(Map.Entry<String, Consent> e : consented.entrySet()) {
            StringBuilder sb = new StringBuilder(enc(e.getKey())).append('=');
            boolean first = true;
            for(Permission p : e.getValue().keys) {
                if(!first)
                    sb.append(',');
                sb.append(enc(p.key));
                first = false;
            }
            if(!e.getValue().hosts.isEmpty()) {
                sb.append(';');
                first = true;
                for(String h : e.getValue().hosts) {
                    if(!first)
                        sb.append(',');
                    sb.append(enc(h));
                    first = false;
                }
            }
            rows.add(sb.toString());
        }
        return rows;
    }

    /**
     * Record the user's consent for {@code id} — the keys {@code declared} asks for <b>and the {@code hosts} the
     * dialog showed beside them</b> — and enable the addon: the one door the AddOns panel's consent dialog
     * confirms through. Both halves are recorded here because both halves were on screen, and the network gate
     * reads the hosts back ({@link Addon#hostGranted}) rather than the manifest that may since have changed.
     *
     * <p>The record is <b>additive</b>, and equally so in both fields: approving a smaller declaration later
     * never narrows it, so an addon that drops a permission or a host is not re-prompted, while one that adds
     * either is (the addition is not in the record until this runs again).
     *
     * <p><b>The write is bounded, and a grant that does not fit is refused rather than thrown.</b> The whole
     * record lives in one preference value with {@link #PREF_VALUE_BYTES} to spend, and this is what puts
     * addon-authored strings into it, so the candidate record is encoded and measured before anything is
     * written. Over the budget, the grant is refused naming the addon and the limit and the addon stays
     * disabled — the only branch that neither loses the write (a grant the store never took is a grant the
     * next load asks for again, which is what a disabled addon does) nor drops another addon's row to make
     * room (that would revoke a grant the user did give, to record one they gave now).
     */
    public static void grantConsent(String id, PermissionSet declared, List<String> hosts) {
        if((id == null) || id.isEmpty())
            return;
        Map<String, Consent> consented = consentedMap();
        Consent cur = consented.get(id);
        // Not EnumSet.copyOf: it refuses an empty collection, and a recorded row whose every key this build
        // has since dropped parses to exactly that.
        Set<Permission> keys = EnumSet.noneOf(Permission.class);
        if(cur != null)
            keys.addAll(cur.keys);
        Set<String> merged = new LinkedHashSet<String>((cur == null) ? Collections.<String>emptyList() : cur.hosts);
        boolean grew = keys.addAll(declared.granted());
        if(hosts != null)
            grew = merged.addAll(hosts) || grew;
        if(grew || (cur == null)) {
            consented.put(id, new Consent(keys, new ArrayList<String>(merged)));
            List<String> rows = consentRows(consented);
            int bytes = prefslBytes(rows);
            if(bytes > PREF_VALUE_BYTES) {
                log("'" + id + "' stays disabled: recording its consent takes the record to " + bytes
                    + " bytes, past the " + PREF_VALUE_BYTES + " one preference value holds");
                return;                  // refused: nothing written, and nothing enabled
            }
            Utils.setprefsl(PREF_CONSENTED, rows);
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
                status = "v" + a.manifest.version;
            else if(loadErrors.containsKey(id))
                status = "error";
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
            try { m = Manifest.load(sub.toPath()); } catch(Exception e) { mferr = Refusal.reason(e); }
            Addon loaded = findLoaded(id);
            String error = (loaded != null) ? loaded.error
                : ((mferr != null) ? mferr : loadErrors.get(id));
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
            return "loaded v" + a.manifest.version;
        String err = loadErrors.get(id);
        if(err != null)
            return "error: " + err;
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
