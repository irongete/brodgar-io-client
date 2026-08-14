# 066 — The RTS camera, and the Multi session bindings

## What & why

Three things are welded to `:fleet rts on` that should not be. The camera it installs is a general one
— free pan, proportional zoom, a frustum that survives being pulled back — reachable only by turning
a whole mode on or knowing `:cam fleet`. The anchor switch defaults to **Tab**, which is also
`Inventory`, and appears in no panel, so it cannot be moved. And no camera at all can be chosen
without the console.

This feature separates them: the camera becomes `:cam rts`, anyone's to use — from the console, from a
new dropdown in Options ▸ Camera, and from Lua — and the mode goes on reusing it; and the keys the mode
owns join a new **Multi session** section in Options ▸ Keybindings.

**One camera, one name.** `RTSCam` is `rts` at the console, in the dropdown and from Lua; the dropdown
shows the registry's own keys rather than labels of its own, so no camera has a second spelling.

**No binding here ships with a default key.** Every id this feature touches starts unbound, and the
user assigns it. A default cannot be conflict-free: the whole reason the anchor switch has to move is
that its default was Tab and `Inventory`'s is too.

**The pivot is a modifier, and therefore not a binding.** `KeyMatch.Capture.handle` refuses a bare
`VK_SHIFT`/`VK_CONTROL`/`VK_ALT`/`VK_META`/`VK_WINDOWS`, and that refusal is load-bearing: it is what
keeps the key grab open across a modifier press so the chord following it can be captured at all. A
rebindable pivot could therefore only ever hold a non-modifier key, which is the wrong shape for a
hold-while-dragging gesture. So the gesture stays hard-wired, and moves to Ctrl.

## Acceptance criteria

1. `:cam rts` installs `MapView.RTSCam` on any character, with no fleet, no mode and no members —
   the pan, the wheel zoom, `cam-left`/`cam-right`/`cam-in`/`cam-out` and `cam-reset` all work.
   `:cam fleet` is gone and refuses, naming `rts`.
2. `:fleet rts on` still installs that same camera and `off` still puts the previous one back, and
   neither writes the `defcam` preference.
3. Options ▸ Camera has a **Camera** section whose dropdown lists every camera the client has **under
   the name `:cam` takes**, shows the one in force, and installs and persists the one picked.
4. Options ▸ Keybindings has a **Multi session** section listing the anchor switch and the focus key.
   Both are **unbound** until assigned, so Tab keeps `Inventory` and nothing collides; each is
   rebindable and persisted.
5. **Ctrl and a middle-button drag** rotates and elevates `RTSCam` instead of panning it; the same drag
   without Ctrl pans. Shift + middle drag stops rotating — that gesture is retired, not kept alongside.
   No modifier collides: nothing but the camera claims the middle button on the map view.
6. Both ids are visible to `hafen.client():options():keybindings():list()` reading `"None"`, and a
   `key(id, k)` write round-trips through `key(id)`.
7. `hafen.client():options():camera():mode()` reads the camera in force by its console name, and
   `:mode(name)` installs and persists it — the same result as the dropdown and as `:cam <name>`. An
   unknown name raises, naming what it got and the names that exist. It answers before the UI is up,
   as the rest of `camera()` does.

## Out of scope

- **A camera *collection*.** `mode()` is an option, not a set you address into: the names are a fixed
  enumeration the page documents, as `keybindings.md` documents its key strings rather than exposing
  a list verb. The refusal names them too.
- Per-camera arguments — `:cam <name> <args>` keeps those to the console; `mode(name)` takes a name.
- The rest of the RTS mode — marquee, orders, `:fleet` — and the `cam-*` bindings' own defaults.

## Docs impact

Pages written: **`docs/client/world-3d.md`** (the camera registry — `camtypes`,
`makecam`/`restorecam`, `defcam`/`camargs`, `:cam` and the selector — none of it documented anywhere,
and all of it read out of `src/` to write this plan), **`docs/client/multi-session.md`** (the RTS
table), **`docs/client/services.md`** (the Options row for `defcam`, and the binding sections),
**`docs/addons/api/client/README.md`** (`mode()` in the `camera()` table).

The derived impact set, grepped across the whole of `docs/`:

```
grep -rn "cam fleet\|FleetCam\|fleet camera\|FreeCam\|camtypes\|defcam" docs/
grep -rn "rts-next-anchor\|rts-focus\|cam-reset\|cam-left\|Switch character" docs/
grep -rn "middle drag\|Middle drag\|Shift and middle\|Options ▸ Camera" docs/
grep -rn "invertHorizontal\|invertVertical\|options():camera\|opts:camera" docs/
```

The first three hit `docs/client/multi-session.md` and nothing else — lines 25 (`MapView.FleetCam`,
`:cam fleet`, "a `FreeCam` with a centre of its own"), 32 (Middle drag), 33 (Shift and middle drag),
34 (`rts-next-anchor` (Tab), `rts-focus` (space)) and 35 (`FreeCam` has no keyboard of its own). All
five are falsified by this feature.

The fourth hits `docs/addons/api/client/README.md` alone, where two of three lines go stale: **line
13**, the namespace map, calls `camera()` "camera drag inversion", and its table at 105–111 lists only
the two inversions. Line 144's "`interface()`, `camera()` and `client()` always answer" stays true,
and criterion 7 keeps it true.

## Context files

- `src/haven/MapView.java` — 1, 2, 3, 4, 5 *(`setcam`/`camname`/`camnames` are the public members task 2 added and task 5 reads and writes through; `kb_rtsfocus`/`kb_rtsnext` are declared here, which is what task 3 re-defaults; `mousedown` sends `ev.b == 2` straight to `camera.click` with no modifier branch, which is why task 4's modifier collides with nothing)*
- `src/io/brodgar/rts/Control.java` — 1, 4 *(the marquee's additive select reads Shift **or** Ctrl, on buttons 1 and 3)*
- `src/haven/OptWnd.java` — 2, 3
- `src/haven/KeyBinding.java` — 1, 3 *(`set` steals a key from any other binding matching it, which is what a suite saving and restoring one has to work around)*
- `src/haven/KeyMatch.java` — 4 *(`Capture.handle`'s bare-modifier refusal, and why it cannot simply be lifted)*
- `src/haven/SDropBox.java` — 2
- `src/haven/GameUI.java` — 2 *(`Polity.Selector`, a concrete `SDropBox` to copy; `GameUI.map` reached from a panel by `getparent(GameUI.class)` — `UI` has no `gui` field)*
- `src/io/brodgar/addon/CameraOptions.java` — 5
- `src/io/brodgar/addon/OptionsMethod.java` — 5
- `docs/addons/api/client/README.md` — 5
- `docs/addons/api/conventions.md` — 5
- `docs/client/world-3d.md` — 1, 2, 4 *(the camera registry, and the row saying the middle button reaches `camera.click` alone)*
- `docs/client/multi-session.md` — 1, 3, 4
- `docs/client/services.md` — 1, 2, 3, 4 *(the keybinding registry's row, where `set`'s exclusivity lives, and the gotcha on why no modifier can be bound)*
- `docs/client/ui-lists.md` — 2
- `docs/addons/api/client/keybindings.md` — 1, 2, 3, 4, 5 *(the suites' oracle for a key)*
- `DOCUMENTATION.md` — 1, 2, 3, 4, 5

## A note on the suites

Tasks 1–4 have no Lua surface but the keybinding registry, so their suites automate what
`keybindings()` reads back and carry `[manual]` lines for the gestures and the panels — the "a program
cannot observe it" case. **Task 5 closes that gap**: its suite asserts a camera directly.
