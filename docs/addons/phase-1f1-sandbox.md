# Phase 1f-1 — Lua sandbox (strict env + instruction watchdog)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **49/49 headless
> checks** (withheld surfaces absent, whitelisted stdlib present, watchdog aborts an infinite loop in
> both the addon env and the console, bounded loops don't false-trip) + LuaJ parse of the `hello`
> harness under the sandbox. Measured: the default 10 M-instruction cap aborts a tight `while true do
> end` in **~36 ms**. **In-game verified ✅** — the `hello` self-check logged
> `withheld={io,require,load,loadfile,dofile,debug,luajava,package,os.execute}; safe-stdlib=true;
> os.execute usable=false`, and `:lua while true do end` aborted with the `addon watchdog: instruction
> budget exceeded` error (client kept running, no freeze).
> **Design:** [specs/addons/12-security-and-permissions.md](../../specs/addons/12-security-and-permissions.md)
> (sandbox + threat model), [specs/addons/04-engine.md](../../specs/addons/04-engine.md)
> (per-addon env, watchdog), decisions **D-017** (strict default sandbox) + **D-018** (watchdog:
> instruction hard-stop + soft per-tick budget).

The first slice of **1f**. Until now every addon ran in a full `JsePlatform.standardGlobals()` — which
bundles `io`, `os.execute`, **`luajava`** (arbitrary Java reflection/instantiation), and the arbitrary
code loaders. That is the opposite of the sandbox the design calls for: a shared/hostile addon could
read and write any file, run shell commands, or reach straight into `haven.*` through `luajava`,
bypassing the whole `hafen.*` facade (P1). This slice replaces that with a **strict, constructive
whitelist** (D-017) and adds the **instruction hard-stop watchdog** (D-018, layer 1) so a runaway loop
can no longer freeze the client.

## What an addon can and cannot reach now (D-017)

**Exposed** (the safe stdlib):

```
string.*   table.*   math.*
os.time  os.clock  os.date  os.difftime
select  pairs  ipairs  next  type  tostring  tonumber
pcall  xpcall  error  assert  unpack   (+ table.unpack)
```

**Withheld** (absent, not merely hidden):

```
io                         -- no arbitrary file access (saved data goes through hafen.store only)
luajava                    -- no Java reflection / class loading (would bypass the hafen.* facade, P1)
debug                      -- no debug table reachable from Lua
require / package / module -- no unrestricted module loading
load / loadfile / dofile / loadstring   -- no arbitrary code / path loading
os.execute / os.exit / os.getenv / os.remove / os.rename / os.tmpname / os.setlocale
```

Referencing a withheld global from Lua simply yields `nil` (Lua has no strict-global error by default),
so a guarded call is caught cleanly:

```lua
local ok = pcall(function() return os.execute("...") end)   -- ok == false; os.execute is nil
```

## The watchdog (D-018, layer 1)

Lua runs on the UI/render thread, so `while true do end` in an addon would hang the whole client. Each
addon environment now carries an **instruction hard-stop**: every callback (event handler, `OnUpdate`,
timer) and every addon file body is given a fresh budget of instructions; if a single call exceeds it,
the watchdog raises a Lua error that the engine's per-callback error isolation catches — the addon's
callback dies, the frame and every other addon live on.

- **Default cap:** 10,000,000 instructions per call. Measured abort of a tight infinite loop: ~36 ms
  (about two frames) — imperceptible for legitimate work, near-instant for a runaway.
- **Configurable:** `-Dhaven.addon.insncap=<n>` (a value `<= 0` disables the hard stop).
- **Try it in-game:** `:lua while true do end` in the console aborts with an `addon watchdog:
  instruction budget exceeded` error instead of freezing. (Never bake such a loop into an addon.)

## How it works (implementation)

New file **`src/io/brodgar/addon/Sandbox.java`**:

- **`Sandbox.create()`** — builds the addon env **constructively**: `new Globals()`, then load only
  the safe libraries (`JseBaseLib`, `PackageLib`, `TableLib`, `StringLib`, `JseMathLib`, `JseOsLib`)
  and the compiler (`LoadState` + `LuaC`). `PackageLib` is loaded because the stdlib modules register
  themselves in `package.loaded` on load — but `require`/`package`/`module` are then stripped, so the
  *libraries* are kept and the require *machinery* is dropped. The dangerous entries riding inside
  otherwise-safe libs (`os.execute`… and the base `load*`) are set to `nil`. `io`, `luajava`, `debug`,
  `coroutine`, and `bit32` are simply never loaded — **absent by construction**, nothing to forget to
  strip.
- **`Sandbox.Watchdog extends DebugLib`** — installed as `Globals.debuglib` **without** `load()`-ing
  it. LuaJ calls `onInstruction` on every VM instruction whenever `debuglib != null` (verified in
  `LuaClosure.execute`), so the watchdog decrements a per-call counter and throws when it underflows —
  yet no `debug` table is ever reachable from Lua. `onCall`/`onReturn`/`traceback` are overridden to
  no-ops (this watchdog is assigned, not loaded, so its internal `globals` is uninitialized; the
  default `traceback` would NPE via the error-hook path — an empty traceback is also honest since no
  call-stack is kept). Addon errors are still reported with LuaJ's `chunkname:line` prefix.
- **`Sandbox.arm(g)`** — resets the instruction budget to a full cap; the engine calls it immediately
  before **every** entry into Lua.

Wiring in the engine (small, `// addon:`-style edits, no `haven` changes):

- `AddonManager.loadAll` — `Sandbox.create()` replaces `JsePlatform.standardGlobals()` for addon envs.
- `AddonManager.callLua` — `Sandbox.arm(owner.env)` before each handler/timer/event call.
- `Addon.run` — `Sandbox.arm(env)` before each file body executes.
- `AddonManager.console` / `eval` — the `:lua` REPL keeps **full** `standardGlobals()` (see below) but
  is watchdog-armed per evaluation, so a stray `while true do end` in the console is aborted too.

## The `:lua` REPL is deliberately not sandboxed

The console is the **operator's own trusted tool**, not shared addon code — the sandbox exists to
constrain what *addons* can do, not what the user types at their own console. So the REPL keeps the
full standard library (including `luajava`, handy for poking the engine while developing). It still
gets the watchdog, purely as typo protection against an accidental infinite loop. This is why testing
the API from `:lua` can do things a real addon cannot — expected, and documented here so it is not
mistaken for a sandbox hole.

## `hello` demo

Bumped to **v0.11.0**. A new `OnLoad` handler self-checks the sandbox from inside the live client and
logs, e.g.:

```
sandbox: withheld={io,require,load,loadfile,dofile,debug,luajava,package,os.execute}; safe-stdlib=true; os.execute usable=false
```

proving the dangerous surface is gone while the safe stdlib remains, and that a shell-exec attempt is
genuinely unusable (caught by `pcall`), not merely absent. The watchdog is left to the manual `:lua`
test above (baking a freeze into the harness would be wrong).

## Deferred (later 1f slices / follow-ups)

- **Watchdog soft budget + auto-disable (D-018, layer 2):** a per-addon per-tick wall-clock budget
  (~4 ms) that auto-disables a repeat offender with a panel warning. Naturally pairs with the AddOns
  options panel (where the warning/error state is surfaced) and the runtime-disable machinery.
- **Controlled `require`:** an addon-folder-only `require` (D-017 "if provided … only within the
  addon's own folder"). Withheld entirely for now (addons are single-file so far).
- **Per-env string metatable:** LuaJ's string metatable is a process-global static
  (`LuaString.s_metatable`); a hostile addon calling `getmetatable("")` could tamper with string
  handling for everyone. Outside D-017's explicit list; a later hardening pass.
- The rest of **1f**: Reload UI + enabled-set (`:reload`, teardown), then the AddOns options panel.
