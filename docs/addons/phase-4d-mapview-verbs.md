# Phase 4d — MapView action verbs (`clickGob` / `useItemOn` / `place` / `select` + `raw`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **33 headless checks**
> (the pure arg-array builders `moveClickCoord` / `clickGobArgs` / `itemactArgs` / `placeAngle` /
> `placeArgs` / `selArgs` — coord flooring incl. negatives, the 9-element gob-click order, the
> `round(angle*32768/PI)` place-angle, and the world→tile `sel` conversion — **plus** the whole
> `hafen.act` namespace end-to-end in the **real facade**: a declaring owner passes each verb's gate to
> `"no map view"`, a non-declaring owner is blocked with `"did not declare"`, `raw` rejects a missing
> `msg`, and bad numeric args are refused after the gate) + LuaJ parse of the updated `walker`.
> **In-game DoD pending.**
> **Design:** [specs/addons/09-events-catalog.md](../../specs/addons/09-events-catalog.md#appendix--action-message-reference-clientserver)
> (the action-message encodings — the authority for the arg shapes),
> [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.act`),
> [specs/addons/12-security-and-permissions.md](../../specs/addons/12-security-and-permissions.md),
> decisions **D-010** / **D-025** / **D-027** / **D-028** (the permission model, unchanged since 4a/4c).

Slice **4d** completes the **MapView** half of the gated write-actions tier. Slice **4a** shipped the
permission model and the flagship verb `moveTo`; 4d adds the remaining four `MapView` gestures a player
performs with the mouse — **clicking an object**, **using a held item on the ground**, **placing** a
building, **area-selecting** tiles — plus **`raw`**, the escape hatch for messages the typed verbs don't
cover. They all reuse 4a's encoding: a `Widget.wdgmsg` from the `MapView`, world coordinates floored the
one canonical way, and the same per-addon permission gate.

## The verbs

```lua
hafen.act.clickGob(ref [, button [, mods]])   -- click a game object (left/right)
hafen.act.useItemOn(x, y [, mods])            -- use the item on your cursor on the GROUND at world (x,y)
hafen.act.place(x, y, angle [, button [, mods]])  -- place the object on your cursor (angle in RADIANS)
hafen.act.select(x1, y1, x2, y2 [, mods])     -- area-select the tile rectangle between two world corners
hafen.act.raw(target, msg, ...)               -- escape hatch: any wdgmsg from a bound widget
```

- **Gated, exactly like `moveTo`.** Every verb calls `requireActions(owner, …)` first: it runs only if
  the calling addon **declared** `"permissions": ["actions"]` (D-027/D-028 — a per-addon permission, no
  global switch). `hafen.act.enabled()` still never throws, so an addon feature-detects without a `pcall`.
- **World coordinates throughout** (`hafen.gob.pos`'s coordinate space) — one space across the whole API.
  Conversions are the ones the read API already exposes: world→server via the `moveTo` flooring
  (`Coord2d.floor(OCache.posres)`), world→tile via `hafen.map.worldToTile` (`Coord2d.floor(MCache.tilesz)`).
- **Optional trailing modifiers.** `button` (1 = left, the interact/select click; 3 = right, the
  context/flower-menu click) and `mods` (a bitfield: **Shift=1, Ctrl=2, Alt=4**, the same values
  `hafen.key` uses) default to a plain left-click with no modifier. They are the *same* function, not a
  second calling style (one canonical way) — omit them for the common case.

### `clickGob(ref [, button [, mods]])`

Sends the `MapView` `"click"` that a mouse-click on that object produces
([MapView.Click.hit](../../src/haven/MapView.java:2009)):

```
"click", pc, mc, button, mods, 0, gobid, gobrc, 0, -1
```

- **`ref`** is the **same GobRef the read API takes** — a gob id, `"player"`/`"me"`, `"partyN"`, or `nil`
  (= you). It is resolved through the central `resolve()`, so `clickGob` and `hafen.gob.*` name objects
  identically.
- The trailing `{0, gobid, gobrc, 0, -1}` is [`Gob.GobClick.clickargs`](../../src/haven/Gob.java:677) for a
  **bare** click — no overlay, no specific sub-mesh (`-1`). This is exactly what clicking a plain world
  object (a tree, a container, a plant) sends, so `clickGob` is faithful for the common "interact with
  this object" case. A **composite** gob (player/animal) would, in a real click, carry a computed
  sub-mesh id identifying the exact body part; targeting a specific part is **deferred** (see below).
- `mc` (the click's world point) is the gob's own floored position — where a click landing on its base
  would report.

### `useItemOn(x, y [, mods])`

The `MapView` `"itemact"` ([:2096](../../src/haven/MapView.java:2096)) — apply the item **on your cursor**
to the **ground** at world `(x, y)`: `"itemact", pc, mc, mods`. With nothing on the cursor the server
ignores it. (This is the ground-target form; using a held item **on a specific gob** is a later addition.)

### `place(x, y, angle [, button [, mods]])`

The `MapView` `"place"` ([:2040](../../src/haven/MapView.java:2040)) — drop the object **currently being
placed** (on your cursor) at world `(x, y)` rotated by `angle`: `"place", rc, round(angle*32768/PI),
button, mods`. **`angle` is in RADIANS** (0 = north); the engine's fixed-point encoding
(`round(angle*32768/PI)`) is applied for you. With nothing being placed the server ignores it.

### `select(x1, y1, x2, y2 [, mods])`

The `MapView` `"sel"` ([:2281](../../src/haven/MapView.java:2281)) — area-select the **tile rectangle**
spanned by the two **world** corners: `"sel", tc1, tc2, mods`. The corners are converted world→tile with
`Coord2d.floor(MCache.tilesz)` — the **same** conversion `hafen.map.worldToTile` exposes — so an addon can
compute the exact tiles it will hit (`hafen.map.tileToWorld` at a tile corner round-trips cleanly). Drives
tile-area tools (survey, mining designation, …).

### `raw(target, msg, ...)`

The **escape hatch** for power users: send an arbitrary `wdgmsg` from a **bound** widget.

- **`target`** is either a **server widget id** (a number — e.g. `model:raw()` from `hafen.ui.adopt`, or a
  `desc.id` from a `hafen.ui.onWidgetCreate` observer), resolved via `UI.getwidget`, **or** a token
  **`"mapview"`** / **`"gameui"`** (reusing the 2c input-hook target resolver — `"root"` is accepted too).
- **`msg`** is the message name (a string); the remaining args are marshalled with the **shared
  `LuaMarshal`** the action/message hooks use — a `{x=, y=}` table ↔ `Coord`, numbers/strings/booleans
  map directly. So you can hand-build any client→server message the typed verbs don't cover.

> `raw` on the `MapView` reproduces any typed verb — e.g. `raw("mapview", "click", {x=0,y=0}, mc, 1, 0)`
> is `moveTo` — which is exactly how the `walker` example demonstrates it.

## Faithful, and still server-authoritative

Each verb sends **literally what the matching mouse gesture sends** — same widget, same message, same arg
order (verified against `MapView.Click.hit` / `iteminteract` / the place branch of `mousedown` /
`Selector.mmouseup`, and `Gob.GobClick.clickargs`). The client stays **server-authoritative**: an addon
can do only what a player click could do. The permission gates *automation/convenience*, not capability —
it exists so **you** decide which addons drive your character (see
[phase-4a](phase-4a-actions-gate-moveto.md) and [phase-4c](phase-4c-enable-consent-dialog.md)).

## Zero `haven` core edit

Pure engine — **only `AddonManager.java`** (the five facade entries + their backing senders and the pure
arg builders). Every backing is public and already used by 4a: `Widget.wdgmsg`, `UI.mc`/`UI.getwidget`,
`Coord2d`/`Coord`, `OCache.posres`, `MCache.tilesz`, and the existing `resolve()` / `hookTarget()` /
`LuaMarshal` helpers. **No new imports.**

## The `walker` example (`addons/walker/main.lua`) — one deliberate trigger per verb

`hello` is the read-only regression harness (declares no permissions), so the write demo lives in the
dormant, opt-in **`walker`** addon (it declares `"actions"` → disabled by default; enable + confirm the
consent dialog + Reload UI). `:walker` is now a **sub-command dispatcher**, one gated verb each — nothing
acts unless you ask:

| Command | Verb | What it does |
|---|---|---|
| `:walker walk` | `moveTo` | walk ~2 tiles south (the original 4a demo) |
| `:walker click` | `clickGob(id, 3)` | **right-click the nearest non-player object** → its context menu opens (safe, cancelable) |
| `:walker use` | `useItemOn` | use the cursor item on the ground under you (no-op empty-handed) |
| `:walker sel` | `select` | area-select the ~3×3 tiles around you |
| `:walker place` | `place(x, y, 0)` | place at your feet facing north (no-op unless placing) |
| `:walker raw` | `raw("mapview", "click", …)` | the escape hatch — walks ~2 tiles south (== `moveTo`) |

Version **v0.3.0**. `walker` stays separate from `hello`, so one login still re-checks every read slice.

## How to test in-game

**Java changed → full rebuild + restart** (the JVM does not hot-reload classes):

```bash
ant run
```

1. **Enable `walker`** (it is disabled by default): **Options → AddOns**, tick **Walker**, **confirm the
   consent dialog** (it can act on your behalf), then **Reload UI**. At login it logs
   `walker: write-actions GRANTED`.
2. **Each verb (deliberate):**
   - `:walker walk` — you walk ~2 tiles south.
   - `:walker click` — the nearest object's **context menu opens** (right-click). Cancel it.
   - `:walker sel` — with a tile-area tool active you'll see the ~3×3 selection; otherwise it's a no-op
     the server accepts.
   - `:walker use` — hold something on your cursor first (e.g. a bucket), then run it: it applies at your
     feet.
   - `:walker place` — start building something (so an object is on your cursor), then run it: it places
     at your feet facing north.
   - `:walker raw` — you walk ~2 tiles south again, proving the escape hatch reaches the `MapView`.
3. **The gate.** From the trusted console (which declares every permission), `:lua hafen.act.clickGob(…)`
   works; from an addon that did **not** declare `"actions"` the same call errors with *"this addon did
   not declare the actions permission …"*.

## Files

- `src/io/brodgar/addon/AddonManager.java` — the `hafen.act` facade gains `clickGob` / `useItemOn` /
  `place` / `select` / `raw` (each `requireActions`-gated); backing senders `actClickGob` / `actUseItemOn`
  / `actPlace` / `actSelect` / `actRaw` + the `rawTarget` resolver; pure, headless-testable arg builders
  `clickGobArgs` / `itemactArgs` / `placeAngle` / `placeArgs` / `selArgs` (reusing `moveClickCoord`).
- `addons/walker/` — `main.lua` refactored into the `:walker <sub>` dispatcher exercising all five verbs;
  `manifest.json` **v0.3.0** + description.

**No `haven` core edit.**

## Threading & safety

- All verbs run on the **UI thread** (addon callback / REPL). `clickGob` copies `gob.rc` under
  `synchronized(g)` (the OCache discipline) before flooring. `wdgmsg` queues to the session (thread-safe).
- **Null-guarded:** each sender throws a clear `"no map view (not in the world yet)"` when off-world;
  `clickGob` additionally guards an unresolved ref (`"no such gob"`) and a positionless gob; `raw` guards
  an unresolved target and a non-string `msg`.
- **Stateless** — no cached snapshot, no listener, nothing to reset across `:reload`/relog. Nothing to leak.
- A 2d `"click"` action-hook **does** see a `clickGob` (it is a real `"click"`); the 2d re-entrancy guard
  stops a hook-issued verb from looping.

## Limitations / deferred

- **Composite sub-mesh targeting.** `clickGob` sends the bare `Gob.GobClick` encoding (mesh id `-1`); a
  specific body part / equipped item of a player-or-animal composite (via `Composited.CompositeClick`) is
  not targeted. Faithful for world objects, which is the common case.
- **`useItemOn` on a gob.** Only the ground-target form ships; applying the held item **to a specific
  object** (the gob-clickargs form of `"itemact"`) is a later addition — `raw` can express it meanwhile.
- **Later Phase-4 slices:** `hafen.act.menu` + `flower` (4e); item verbs `hafen.act.item` + the `LuaModel`
  item-mutating verbs (4f); the per-subsystem gated verbs `speed.set` / `craft.make` / `actionbar.use` /
  `kin.*` (4g).
