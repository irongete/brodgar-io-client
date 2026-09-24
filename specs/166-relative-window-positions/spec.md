# 166 — Relative window positions

## What & why

A window's place is saved as pixels from the top-left (`760x780`), so a window left bottom-centred is
elsewhere, or off screen, after a resize or a start at another resolution or interface scale.

This feature makes a window's place **a fraction of its free space per axis**: `f = c / (parent size −
window size)` — 0 the left or top edge, 1 the right or bottom, 0.5 centred; the server's own rule for a
`Coord2d` placement (`GameUI.addchild`, `misc`). Held per window, re-applied when the screen changes size,
saved in the client's own keys. A fraction has no unit, so the interface scale cancels out.

### Rulings — taken with the maintainer, not reopened

1. The rule above, per axis. No anchors, no new Lua verb.
2. A fraction is clamped to `0..1`: a window left half off screen comes back whole.
3. The magnet: a place within 10 design px of an edge is taken as on it.
4. A window is re-placed when the screen changes size, and only then: its own resize moves no origin. A
   move is found by comparing `c` with where the rule put it. Free space `<= 0` keeps that axis's
   fraction. A window an addon's layout holds is left to the layout.
5. **No new keys.** `wndc-inv`, `wndc-equ`, `wndc-chr`, `wndc-zerg`, `wndc-map`, `wndc-misc/<id>`,
   `makewndc` and `cont-wndc/<id>` carry `fx/fy`: a slash, each number written by `Double.toString`. An old
   `NxM` value loads as today and the next save rewrites it. Sizes stay pixels (`wndsz-map`).
6. `wndc-srch` and `wndc-icon`, read today and never written, are written too.
7. Addons: a place the user's hand gives — a `:draggable` drag, the title-bar drag of a window the addon
   built, a place `widget:remember` puts back — to a widget directly on the HUD or the layer's root
   follows the same rule. A place the addon writes (`widget:position(x, y)`, a sheet
   `position`) stays the pixels written, and a window an addon built follows only once the user has moved
   it. `remember` rows keep their schema: `x` holds the TEXT `fx/fy`, `y` is `NULL`.
8. **Transparent to published addons.** The client is released and third-party addons exist: no verb
   changes shape or gains a refusal, a place an addon wrote keeps its meaning, and what hangs off a window
   the rule moves (`anchor{ to = window }`) follows it. A pre-feature build reading a new value falls back
   to its default place, never an error.

## Acceptance criteria

1. When the HUD changes size, every client window directly on it that no addon's layout holds stands at
   the same fraction of its free space as before, per axis, under rulings 2 and 3.
2. Back at the original size, each of those windows stands where the rule puts it there: the pixel it
   left, unless the clamp or the magnet moved it.
3. A client window an addon placed with `widget:position(x, y)` keeps that pixel place through a HUD
   resize, and `widget:position(nil)` then lands it at the user's relative place for the current size.
4. A place survives a restart at another window size or interface scale, relatively.
5. The first start after the update opens every window where the previous build left it.
6. A place the user's hand gave an addon's widget on the HUD follows a HUD resize. A place the addon wrote
   with `widget:position(x, y)`, and an addon's window the user never moved, do not.
7. A name `widget:remember` saved at one HUD size puts the widget back at the same relative place at
   another. A widget remembered inside another window comes back at the same pixels in it.
   `widget:remember(nil)` still deletes the row.
8. A window an addon built in its layer, moved by the user, follows a real resize of the game window.
9. An addon's widget anchored to a client window stays on it when the rule moves that window.

## Out of scope

- **Sizes.** Every saved box (`wndsz-map`, a remembered box) stays pixels.
- **The HUD's own pieces** — chat, map view, belt, progress bar, corner panels — keep `GameUI.resize`'s
  re-placement.
- **The stock box of a layout record** (the size half of the 062 `ROADMAP.md` line): a HUD piece's width.
  Its position half is criterion 3.
- **Any new Lua verb or property.**

## Docs impact

- `docs/addons/api/ui/native.md` — the `widget:position(nil)` row, *The disk is the user's*, *Re-layout*
  (166.1); *A drag writes your `:position` level* (166.2); `remember`'s *What is saved* (166.3).
- `docs/addons/api/ui/style/geometry.md` — *Dropping the rule* "restores the exact numbers it found",
  false once the stock is relative (166.1).
- `docs/client/gameui-windows.md` is at its 150-line ceiling: its position-store and `GameUI.resize`
  sections move, rewritten, to a new `docs/client/window-positions.md`, indexed in the README (166.1).

Derived impact set:

```bash
grep -rn -i -E "window positions|saved position|position store|wndc|resizing the game window|resized game window|game window resiz|screen resiz|remembers? (where|its place)|where the user (put|dragged|placed)|stock place|:remember|widget:remember" docs/ --include=*.md
```

34 hits in 11 files. Beyond the pages above (`custom.md`, `vars.md`, `writes.md`, `saved-data.md`,
`store/README.md`, `widget.md`, `permissions.md`) each stays true.

## Context files

- `src/io/brodgar/ui/WndPos.java` — 1, 2, 3
- `src/haven/GameUI.java`, `src/haven/GItem.java`, `src/haven/Utils.java`, `src/haven/AddonWidgets.java` — 1
- `src/io/brodgar/addon/AddonManager.java`, `src/io/brodgar/addon/UiApi.java` — 1, 2
- `src/io/brodgar/addon/Layout.java` — 1, 2
- `src/io/brodgar/addon/Gesture.java`, `src/haven/UILoop.java` — 2
- `src/io/brodgar/addon/LuaWidget.java` — 1, 2, 3
- `src/io/brodgar/addon/Px.java` — 2, 3
- `src/io/brodgar/addon/Owned.java` — 1
- `src/io/brodgar/addon/StoreApi.java`, `src/io/brodgar/addon/ClientDb.java` — 3
- `docs/addons/api/ui/native.md` — 1, 2, 3
- `docs/addons/api/ui/style/geometry.md`, `docs/client/gameui-windows.md`, `docs/client/README.md` — 1
- `docs/client/window-positions.md` — 1, 2
- `DOCUMENTATION.md` — 1, 2, 3
