# Runtime & Sandbox

This document details the addon execution model: the lifecycle hooks, sandbox boundaries, execution budgets, and console management commands.

## Addon Lifecycle

Addons are loaded once when the game client launches and reloaded dynamically whenever `:reload` is executed.

| Lifecycle Phase | When It Occurs | Available Context |
|---|---|---|
| **File Evaluation** | When client launches or upon `:reload`. | Global `hafen` table is available; persistent addon storage can be initialized; no character sessions exist yet. |
| **`Load` Event** | After all files in `manifest.json` have finished executing. | General client initialization. |
| **`SessionEnteredWorld`** | When a character completes login and reaches the game world. | Character HUD, player stats, world objects, and character-scoped vars (`session:store():var(...)`) are ready. |
| **`Disable` Event** | When the addon is disabled, during `:reload`, or before the client exits. | Final cleanup and flushing pending state writes. |

### Multi-Session Handling

Addons run at the client level, not per character login. If multiple characters are logged in concurrently, your addon remains loaded in memory and receives events across all active sessions.

* Use `hafen.session():current()` to access the character currently displayed on screen.
* Use `hafen.session():list()` to iterate through all active logins.
* Listen for `SessionSelected` to track when the player switches between characters.

```lua
-- Track active character switches
hafen.event():on("SessionSelected", function(session)
  local active_name = session:character() or "Unknown"
  hafen.log():write("Switched view to character: " .. active_name)
end)
```

## Lua Sandbox

Addon code executes in a sandboxed Lua 5.2 environment (via LuaJ).

### Available Standard Libraries
* `string`, `table`, `math`
* `os` (safe clock functions only: `os.time`, `os.clock`, `os.date`, `os.difftime`)
* Safe built-in globals: `pairs`, `ipairs`, `next`, `type`, `tostring`, `tonumber`, `pcall`, `xpcall`, `error`, `assert`, `select`.
* Globals provided by the client:
  * `hafen`: Root access to all game APIs.
  * `ADDON.id`: String containing the addon ID.
  * `ADDON.dir`: Absolute path to the addon's folder.

### Blocked Libraries & Features
* `io`, `os.execute`, `os.exit`, `os.getenv`, `os.remove`, `os.rename`, `os.tmpname`.
* `require`, `package`, `module`.
* `load`, `loadfile`, `dofile`, `loadstring`.
* `debug`, `coroutine`.
* Direct Java reflection or bridge access.

To load external files shipped with your addon (such as images, custom sounds, or static data), use the [`hafen.asset`](api/asset/README.md) API.

## Watchdog & Execution Budgets

To keep client frame rates responsive, the engine enforces two safety limits on addon code:

1. **Instruction Limit per Callback**:
   Each individual callback (event listener, timer, or draw cycle) is capped at **10,000,000 Lua instructions**. If a callback exceeds this limit (e.g. an infinite loop), it is immediately terminated with an error, preventing the client from freezing.
2. **Sustained Tick Budget**:
   If an addon consumes more than approximately **10 milliseconds** of Lua processing time per game frame continuously for **30 consecutive frames**, the client will automatically disable it to safeguard game performance.

Performance metrics can be monitored in real time using [`hafen.client():profiling()`](api/client/profiling/README.md).

## In-Game Console Commands

Press `:` at any time in the client to open the command prompt:

| Command | Action |
|---|---|
| `:reload` | Re-reads all enabled addons from disk and restarts the addon subsystem without disconnecting your game session. |
| `:addons` | Prints a status list of all discovered addons in the terminal and game console. |
| `:addons enable <id>` | Marks an addon as enabled (takes effect on the next `:reload`). |
| `:addons disable <id>` | Marks an addon as disabled (takes effect on the next `:reload`). |
| `:lua <expression>` | Evaluates arbitrary Lua code in the live game environment and prints the returned result as formatted JSON. |

### Using `:lua` for Prototyping

The `:lua` command is un-sandboxed and allows you to test expressions against the live game state immediately:

```text
:lua hafen.session():current():world():gob():count("terobjs/tree")
:lua hafen.session():current():ui():inventory():items():count()
```
