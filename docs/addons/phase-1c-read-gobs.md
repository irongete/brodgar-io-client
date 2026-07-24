# Phase 1c (part 1) — Read API: gobs & world

> **Status:** ✅ Implemented; compile + headless logic verified. In-game check pending.
> **Design:** [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.gob`,
> `hafen.world`), [specs/addons/code-map.md](../../specs/addons/code-map.md) (Java backings),
> [specs/addons/01-architecture.md](../../specs/addons/01-architecture.md) (threading, P5).

This is the **first slice of Phase 1c** (the Glob-backed read API). Phase 1c as designed is large —
`gob`, `world`, `map`, `items`, `char`, `party`, `player`, `time`, `sound` — so it is split. This
slice lands the two most fundamental, most-used surfaces, both backed by the object cache
(`OCache`) with **zero core edits**: reading a gob by reference, and enumerating gobs in the world.
It also upgrades the `GobAdded`/`GobRemoved` payloads from `{id,x,y}` to full snapshots.

## References — how you address a gob

A **GobRef** is either a **gob id** (number) or a **token** (string):

| Ref | Resolves to |
|---|---|
| `"player"` / `"me"` / *(nil)* | your own gob |
| *(a number, e.g. `12345`)* | that gob id |
| *(a numeric string, e.g. `"12345"`)* | that gob id |
| any other token (`"target"`, `"party1"`, …) | `nil` **for now** — wired up when combat/party land |

Accessors **re-resolve every call**, so they are always fresh and return `nil` if the gob is gone.
(Contrast a *snapshot*, below, which is a point-in-time copy.)

## `hafen.gob.*(ref)` — read one gob

The canonical per-gob accessor. Each reads one attribute, fresh:

```lua
hafen.gob.exists(ref)        -- bool: is the gob currently loaded?
hafen.gob.info(ref)          -- a full snapshot in one call (the Gob shape below), or nil
hafen.gob.pos(ref)           -- {x, y} world position, or nil
hafen.gob.facing(ref)        -- number: facing angle in radians, or nil
hafen.gob.name(ref)          -- string: the TYPE/what-it-is resource name, or nil
hafen.gob.health(ref)        -- 0..1 remaining integrity (1=undamaged), or nil
hafen.gob.moving(ref)        -- bool: is it moving?
hafen.gob.speed(ref)         -- number: movement speed, or nil if not moving
hafen.gob.speech(ref)        -- string: current floating speech text, or nil
hafen.gob.icon(ref)          -- string: minimap icon/category name, or nil
hafen.gob.distance(ref [, ref2])  -- number: distance between two gobs (ref2 defaults to "player")
```

> **`name` is a type, not a display name.** It is the drawable resource name, e.g.
> `"gfx/kritter/rabbit/rabbit"` or `"gfx/borka/body"` (a player) — **not** a character's nick.
> Other players' display names are not reliably available in this client (see api-reference.md
> "Not reliably available"). Use `info().isplayer` to tell a player-body gob apart.

### The `Gob` snapshot shape (from `info`, `world.*`, and gob events)

```lua
{
  id,          -- number (stable id)
  name,        -- string type/resource name (nil until it resolves)
  x, y,        -- world position (absent if unknown)
  angle,       -- facing radians
  moving,      -- bool
  speed,       -- number (present only while moving)
  hp,          -- 0..1 remaining integrity (present only if the gob has health)
  speech,      -- string (present only while speaking)
  icon,        -- string minimap icon name (present only if it has one)
  overlays,    -- array of active overlay resource names (crop stage, fire, drying…), best-effort
  isplayer,    -- bool (name == "gfx/borka/body")
}
```

Every field is **optional** — a snapshot taken while the world is still streaming may only have
`id`/position. Don't cache a snapshot across ticks; re-read with `hafen.gob.*(ref)` for fresh data.

## `hafen.world.*` — enumerate gobs

```lua
hafen.world.gobs([filter])          -- array of snapshots matching filter
hafen.world.count([filter])         -- how many match
hafen.world.nearest([filter])       -- the closest matching gob to the player, or nil
hafen.world.within(radius, [filter])-- snapshots within `radius` world units of the player
```

**`filter`** is optional and is either:
- a **string** — kept if the gob's `name` *contains* it (substring), e.g. `"rabbit"`, `"borka"`;
- a **function** `fn(snapshot) -> truthy` — full control (a handler error just drops that gob).

`nearest`/`within` measure distance **from the player** and **skip the player's own gob** (they are
"relative to me" queries); `gobs`/`count` are raw enumerations that include the player.

```lua
-- Everything nearby that looks like a rabbit:
local bunnies = hafen.world.within(50, "rabbit")
-- Other players in view:
local players = hafen.world.gobs(function(g) return g.isplayer end)
-- Nearest anything:
local n = hafen.world.nearest()
```

> **Prefer events over scanning.** For "react when X appears", use `GobAdded`/`GobRemoved`
> ([Phase 1b](phase-1b-events-timers.md)) — their payload is now a full snapshot too. A per-frame
> `world.gobs()` scan builds a snapshot per gob and is comparatively heavy.

## Try it from the `:lua` console

```
:lua hafen.gob.info("player")
:lua hafen.gob.pos("player")
:lua hafen.world.count()
:lua hafen.world.count("rabbit")
:lua hafen.world.nearest()
:lua hafen.gob.distance(hafen.world.nearest().id)
```

Results print as compact JSON in-game (`lua= …`). The `:lua` REPL also **echoes to the terminal**,
tagged `[console]` — both the input line and its result/error — so a headless/logged run keeps a
trace of what you ran:

```
[console] :lua hafen.world.count("tree")
[console] lua= 12
```

Before you are in the world every read is `nil`/empty (never an error).

## The `hello` example (`addons/hello/main.lua`)

On `OnEnterWorld` it now logs the player via `hafen.gob.info("player")` (name/hp/facing/pos), the
total `hafen.world.count()`, and the `hafen.world.nearest()` gob with its distance. `GobAdded` now
logs the gob's `name` from the fuller snapshot. This is our standing regression harness — one login
re-checks Phase 0/1a/1b **and** this read API.

## How to test in-game

```
ant run
```
On login (terminal), after `[hello] entered the world` you should see, once the world resolves:
- `[hello] player: name=gfx/borka/body hp=… facing=… at X,Y` — **per-gob read** (`hafen.gob.info`),
- `[hello] world has N gob(s)` — **enumeration** (`hafen.world.count`),
- `[hello] nearest gob: id=… name=… dist=…` — **nearest + distance** (`hafen.world.nearest`),
- `[hello] GobAdded id=… name=… (N so far)` as objects stream in — **richer gob-event payload**.

Then poke it live with the `:lua` snippets above (e.g. `:lua hafen.world.count("tree")`).

## Files

- `src/io/brodgar/addon/AddonManager.java` — `hafen.gob.*` (11 accessors) and `hafen.world.*`
  (gobs/count/nearest/within); centralized GobRef `resolve`, per-attribute Loading-guarded readers,
  the full `gobSnapshot`, and `matches`/`distTo` filter helpers. `GobAdded`/`GobRemoved` now carry
  the full snapshot.
- `addons/hello/` — example + manifest bumped to v0.3.0 to exercise the read API.
- **No `haven` core edits** — all reads go through `OCache`/`Gob` via the existing session hooks.

## Threading & safety

- All reads run on the **UI thread**. `OCache` iteration copies the gob list under
  `synchronized(oc)`; per-gob reads hold `synchronized(gob)`.
- Resource-backed fields (`name`, `icon`, `overlays`) can throw `Loading` before they resolve; every
  reader **swallows it and returns `nil`/skips**, so a half-loaded world never errors into Lua.

## Limitations (rest of Phase 1c and later)

- **Tokens `"target"`/`"partyN"`/`"mouseover"` are not resolved yet** — they need combat/party/hover
  tracking and return `nil` for now (`resolve()` is the single place to extend).
- Still to come in Phase 1c: `hafen.map`, `hafen.player` (name/worldToScreen), `hafen.time`,
  `hafen.sound` (next slice); then `hafen.items`, `hafen.char`, `hafen.party`.
- No sandbox/watchdog yet — a runaway Lua scan can still stall the UI thread (Phase 1f).
