# Phase 0 — Engine spike (`:lua` REPL + `hafen.gob.pos`)

> **Status:** ✅ Implemented & verified in-game (`:lua hafen.gob.pos().x` shows the player's X).
> **Design:** [specs/addons/15-implementation-plan.md](../../specs/addons/15-implementation-plan.md) (Phase 0).

The minimal proof that a Lua VM (LuaJ) runs on the client's UI thread and can read live game state
through a stable `hafen.*` facade.

## What it delivers
- A LuaJ runtime embedded in the client, fetched automatically by the build.
- An in-game **`:lua <expr>`** console command (a Lua REPL).
- One read: **`hafen.gob.pos(ref)`** → the world position of a gob.

## Build
The build downloads LuaJ from Maven Central automatically — no manual jar handling:
```
ant get-luaj      # fetches lib/brodgar/luaj-jse-3.0.1.jar (gitignored, not committed)
ant run           # build + launch the client
```
`get-luaj` also runs as a dependency of the normal build, so `ant`, `ant run`, `ant bin` all pull
LuaJ if it's missing.

## Using the `:lua` REPL
In-game, open the console with `:` and type `lua` followed by a Lua expression or statement.

```
:lua hafen.gob.pos()          -> lua= {"x":1234.5,"y":6789}   (compact JSON, copy-friendly)
:lua hafen.gob.pos().x        -> lua= 1234.5                   (your X)
:lua hafen.gob.pos().y        -> lua= 6789                     (your Y)
:lua 1 + 2                    -> lua= 3
```
- An **expression** shows its value in-game (via a system message); a **statement**
  (e.g. `print(...)`) runs but its output goes to the process stdout/terminal.
- The value is serialized as **compact single-line JSON** (recursive; tables become `{...}` objects
  or `[...]` arrays; integral numbers drop the trailing `.0`; reference cycles show as `"<cycle>"`),
  so you can copy it straight out of the console.
- Lua **errors** are shown in-game as an error notice.

### Strings in the `:lua` console
The in-game console word-splits its input and would strip `"quotes"`. So the engine evaluates the
**raw command line** (quotes intact) instead — normal Lua works, **including string literals**:
```
:lua hafen.log("hello world")     -> logs: hello world
:lua hafen.gob.pos("player").x    -> your X
:lua hafen.gob.pos().x            -> your X   (no argument = player, a convenience)
```
This needed a tiny `haven.Console` change: it now exposes the raw command line via
`Console.rawcmd()`, which the `:lua` command reads. Inside loaded addon files, quotes always worked.

## API: `hafen.gob.pos(ref)`
Returns `{ x = <number>, y = <number> }` in world units, or `nil` if the gob isn't present.

> **Note — this position is session-local, not global.** Haven & Hearth has no global coordinate:
> `rc` starts around (-10,-10) tiles at login and is relative to the session, so it is **not**
> comparable across sessions or between players. It's fine for local/relative use. Cross-session or
> cross-player positioning must be anchored on **grid IDs** (stable and shared) plus the within-grid
> offset — a later phase adds `hafen.map.grid` / `hafen.player.gridPos` for that.

`ref` (a GobRef):
| `ref` | Resolves to |
|---|---|
| `nil` (no arg) | the player |
| `"player"` / `"me"` | the player |
| a number | that gob id |

This is the reference-based, one-way accessor from the design ([D-012](../../specs/addons/decisions.md)):
per-gob reads are `hafen.gob.<attr>(ref)`.

## Files
- `src/io/brodgar/addon/AddonManager.java` — the static facade (mirrors `io.brodgar.voice.Voice`):
  captures the live `MapView` on attach, hosts the LuaJ env, registers `:lua`, resolves `hafen.gob.pos`.
- `src/haven/MapView.java` — two one-line hooks (`AddonManager.attach/detach`) next to the voice hooks.
- `build.xml` — `get-luaj` target, its dependency on `hafen-client`, and the manifest `Class-Path` token.

## Limitations (addressed in later phases)
- One shared Lua environment; **no sandbox yet** (Phase 1 adds per-addon envs + the strict sandbox).
- No addon loading from disk, no manifest, no tick pump, no events — Phase 1.
- Only `hafen.gob.pos` is exposed; the full read surface comes in Phase 1.
