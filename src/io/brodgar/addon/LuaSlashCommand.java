package io.brodgar.addon;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * An addon <b>slash command</b> (gap subsystem A11, spec {@code api-reference.md} &rarr; "hafen.slash") — the Java
 * half of {@code hafen.slash():on(name, fn)}. It routes the console command {@code :name} to a Lua handler,
 * the WoW {@code SlashCmdList} pattern at Haven's {@link haven.Console}.
 *
 * <p><b>Reload-safety (coverage-gaps C1).</b> {@link haven.Console#setscmd} has <b>no unregister</b>, so
 * registering one console command per addon slash command and re-running that registration on every {@code :reload}
 * would leak / duplicate commands. Instead {@link AddonManager} installs a <b>single engine-lifetime dispatcher</b>
 * per command name that forever routes to the <i>current</i> {@link LuaSlashCommand} in
 * {@code AddonManager.slashHandlers}; reload/disable only swaps or drops that entry (it never touches
 * {@code Console} again). So this object is cheap and disposable — a reload builds a fresh one and the dispatcher
 * picks it up; teardown marks it {@link #alive}=false and drops it, after which the still-installed dispatcher just
 * reports "no addon handles :name".
 *
 * <p>The handler {@code fn(args)} receives {@code args} = a 1-based Lua table of the whitespace-split arguments
 * after the command name ({@link haven.Utils#splitwords}, so {@code "quoted words"} group and {@code \\} escapes),
 * the command name itself excluded. The call goes through {@link AddonManager#callLua} — watchdog-armed (D-018),
 * error-isolated, CPU-accounted. The addon only ever sees the {@link LuaSub} its registration handed back — this
 * object hangs off that sub's {@link LuaSub#tag} (086.1) — and the bridge owns the command and drops it on
 * reload/disable (principle P2). The {@link #alive} flag makes a dispatch that races
 * teardown a no-op.
 *
 * <p><b>Threading.</b> The in-game {@code ":"} console dispatches on the UI thread (input handling), the same
 * thread as the tick/draw, so a slash handler never races other Lua — matching the {@code :lua} REPL (a terminal
 * stdin build dispatches on the reader thread, the same trust/threading profile the REPL already accepts).
 */
public final class LuaSlashCommand {
    final Addon owner;
    final String name;     // the console command name this routes (":name"); also the key in slashHandlers
    final LuaValue fn;     // the Lua handler fn(args)
    boolean alive = true;

    LuaSlashCommand(Addon owner, String name, LuaValue fn) {
        this.owner = owner;
        this.name = name;
        this.fn = fn;
    }

    /**
     * Build the {@code args} table for one {@code :name a b c} invocation (the split words <i>after</i> the name,
     * 1-based) and run this command's {@code fn(args)}. Called by {@link AddonManager#dispatchSlash}.
     * {@code words[0]} is the command name itself; {@code words[1..]} are the arguments handed to the addon.
     */
    void invoke(String[] words) {
        LuaTable args = new LuaTable();
        for(int i = 1; i < words.length; i++)        // words[0] is the command name — hand the addon only the args
            args.set(i, LuaValue.valueOf(words[i]));
        AddonManager.callLua(owner, Addon.C_HOOK, fn, args);
    }
}
