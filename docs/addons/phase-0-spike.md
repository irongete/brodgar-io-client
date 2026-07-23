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
:lua hafen.gob.pos().x        -> lua= 1234.5     (your X, shown as a system message)
:lua hafen.gob.pos().y        -> lua= 6789.0     (your Y)
:lua 1 + 2                    -> lua= 3
```
- An **expression** shows its value in-game (via a system message); a **statement**
  (e.g. `print(...)`) runs but its output goes to the process stdout/terminal.
- Lua **errors** are shown in-game as an error notice.

### Console quoting caveat
The in-game console strips quotes (`Utils.splitwords`), so string literals can't survive a `:lua`
line. To keep the REPL usable, the spike:
- defaults `hafen.gob.pos()` (no argument) to the **player**, and
- predefines the globals `player`, `me`, `target` as their token strings.

So all of these work from the console without quotes:
```
:lua hafen.gob.pos().x            -- no arg = player
:lua hafen.gob.pos(player).x      -- 'player' global
:lua hafen.gob.pos(me).x
```
Inside a loaded addon file (Phase 1+), normal quoted strings work: `hafen.gob.pos("player")`.

## API: `hafen.gob.pos(ref)`
Returns `{ x = <number>, y = <number> }` in world units, or `nil` if the gob isn't present.

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
