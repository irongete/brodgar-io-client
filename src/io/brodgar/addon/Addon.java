package io.brodgar.addon;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One loaded addon: its {@link Manifest}, folder, Lua environment, and load status. Per-addon
 * environments give each addon its own globals (sandbox hardening arrives in a later phase).
 *
 * <p>The {@link #subs} and {@link #timers} lists are the addon's <b>owned-resource registry</b>
 * (principle P2): everything it creates through the facade is tracked here so the engine can tear
 * it down cleanly on reload/disable. They are copy-on-write because a running handler may
 * unsubscribe or cancel while the engine iterates them.
 */
public final class Addon {
    public final Manifest manifest;
    public final Path dir;
    public final Globals env;
    public String error;   // null if the addon loaded cleanly

    /** Live event subscriptions owned by this addon (see {@link AddonManager.Sub}). */
    public final List<AddonManager.Sub> subs = new CopyOnWriteArrayList<AddonManager.Sub>();
    /** Live timers owned by this addon (see {@link AddonManager.Timer}). */
    public final List<AddonManager.Timer> timers = new CopyOnWriteArrayList<AddonManager.Timer>();

    /**
     * The {@code hafen.store} proxy table (saved variables, Phase 1e). Holds one Lua table per
     * declared saved variable plus the {@code flush} function. Populated in
     * {@link AddonManager#installHafen}; the engine reads it on flush. {@code null} until installed.
     */
    public LuaTable store;
    /** Write-skip caches: the last JSON serialized for each scope, so an unchanged flush skips disk I/O. */
    public String lastCharJson, lastAccountJson;

    Addon(Manifest manifest, Path dir, Globals env) {
        this.manifest = manifest;
        this.dir = dir;
        this.env = env;
    }

    /** Run the addon's Lua files in manifest order. On the first failure, record it and stop. */
    void run() {
        for(String file : manifest.files) {
            try {
                Path fp = dir.resolve(file);
                String src = new String(Files.readAllBytes(fp), StandardCharsets.UTF_8);
                LuaValue chunk = env.load(src, "@" + manifest.id + "/" + file);
                Sandbox.arm(env);   // watchdog the file body too (D-018) — reset the budget per file
                chunk.call();
            } catch(Exception e) {
                error = file + ": " + e.getMessage();
                return;
            }
        }
    }
}
