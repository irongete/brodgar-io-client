# 165 — gob-outline: a coloured edge round what the client draws of a gob

## What & why

An addon can colour a whole object (`gob:tint`) but cannot mark one and keep its look. The maintainer wants
what strategy games draw round the unit under the mouse: one colour round the object's contour, the edge
alone. `hafen.ui():mouse():on("PickChanged", fn)` already names the object under the pointer, so a hover
highlight becomes one handler.

One verb, `:outline(color [, width])`, on the two receivers standing in the 3D scene: `gob:outline` (on the
Look page, beside `gob:tint`) and `entity:outline` (a ghost, sprite, object or panel of `hafen.virtual()`).
It takes `patch:border`'s shape: colour and optional width in one verb, the bare read answering the colour
keyed and then the width, `nil` taking it off, a snapshot key `{color = …, width = …}`.

Rulings taken before planning:

| Question | Ruling |
|---|---|
| Occlusion | The ring follows the visible part: where something solid stands in front, it runs along that edge. Never through walls. |
| Receivers | Gobs, and the entities of `hafen.virtual()`. |
| Width | Configurable: a whole number of design pixels, `1..8`, default `2`. |

## Acceptance criteria

Each is checked by the named task's suite: `[pass]` automated, `[manual]` read back by the maintainer.

1. `gob:outline(color [, width])` hands the Gob back and draws a ring of that colour, `width` design pixels
   wide, outside what the client draws of the object (model, equipment, the game's overlay sprites at it),
   nothing painted inside. — 165.1 `[manual]`
2. `gob:outline()` answers the colour keyed (`a` defaulting to `255`), then the width (`2` when none was
   given); `nil` with none, and once the gob is gone. `gob:info().outline` is `{color = …, width = …}`,
   absent with none. — 165.1 `[pass]`
3. `gob:outline(nil)` takes it off: the read answers `nil`, the snapshot key goes. — 165.1 `[pass]`
4. Refused, each naming what to write: a value that is not a colour (the shared colour refusal); a width
   that is not a whole number from `1` to `8` (`0`, `9`, `1.5`, `"2"`); a surplus argument; `nil` with a
   width. — 165.1 `[pass]`
5. `gob:tint`'s rules: written on the object (every character that sees it draws it, one loading it later
   included); last write wins across addons; dropped when the object leaves its last character; a write on a
   gob that is gone does nothing. Setting or clearing it leaves `tint` alone. — 165.1 `[pass]` (gone gob,
   composition)
6. Visible part only: with something solid in front, the ring runs along that occluder's edge. — 165.1
   `[manual]`
7. A hover highlight works: a `PickChanged` handler rings the object under the pointer and clears the one it
   left. — 165.1 `[manual]`
8. `:reload` takes every ring the addon drew off every object. — 165.1 `[manual]`
9. `entity:outline` on a ghost, a sprite, an object and a panel answers the reads, writes, refusals and
   snapshot key of 2–4. — 165.2 `[pass]`
10. An entity's ring is round what is drawn (a sprite's picture, never its square) while it is solid world
    geometry: none at `:alpha` below `1` or facing `"screen"`, back when it is again. — 165.2 `[manual]`
11. `patch:outline(…)` raises naming `patch:border(color, width)`. — 165.2 `[pass]`

## Out of scope

- **Through walls.** Ruled out; it would be a verb of its own over a second render pass.
- **A patch's line.** It is `patch:border`, a band on a ground shape in world units, unchanged.
- **Pictures over the scene.** Widgets, labels, HUD painters, a `"screen"` sprite or panel.
- **What a character carries.** A carried object is a gob of its own.
- **See-through parts.** Smoke, glass and a translucent entity lay down no silhouette.

## Docs impact

Pages written:

- `docs/addons/api/look.md` — intro, three rows, "They compose", an `## Outline (unprotected)` section whose
  example is the hover highlight (165.1).
- `docs/addons/api/virtual/README.md` — a shared-vocabulary row, the snapshot key (165.2).
- `docs/client/render-targets.md` — new: the scene's targets, extra fragment outputs, screen passes;
  upstream only (165.1).

Derived impact set:

```text
$ grep -rn "gob:tint\|:tint(" docs --include=*.md
conventions.md:129 gob.md:35,177 look.md README.md:44 shapes.md:54,55 threading.md:108
types/world.md:46                                                        -> 165.1
virtual/ghosts.md:43 virtual/README.md:89 virtual/patches.md (unchanged) -> 165.2
$ grep -rn "shared vocabulary" docs/addons
virtual/ghosts.md:26 virtual/widgets.md:29 (lists gain :outline) virtual/sprites.md:24
virtual/models.md:51 virtual/patches.md:31 (gains the refusal row)       -> 165.2
$ grep -rni "outline" docs/addons
font.md (a glyph's edge) gob.md:164 overlay.md:82 ui/* examples.md:15
client/profiling/counters.md (terrain): unchanged
virtual/patches.md:18 virtual/pieces.md:14: a patch's "outline" -> "edge" -> 165.2
```

Also (165.1): `manifest.md` and every `"api_version"` example (API `1.2`); `gob.md:181` ("a resized, hidden, tinted or re-dressed object"); the `scene` row of
`client/profiling/attribution.md`; `docs/client/README.md` (index row), `render-gl.md` (pointer), `state.md`
(gotcha: `OCache.remove` never calls `Gob.dispose()`).

## Context files

- `docs/addons/api/look.md`, `gob.md`, `shapes.md`, `conventions.md`, `threading.md`, `types/world.md`,
  `README.md`, `client/profiling/attribution.md` — 1
- `docs/addons/api/virtual/README.md`, `ghosts.md`, `sprites.md`, `models.md`, `widgets.md`, `patches.md`,
  `pieces.md` — 2
- `DOCUMENTATION.md` — 1, 2; `docs/addons/manifest.md` — 1
- `docs/client/render-gl.md`, `world-3d.md`, `state.md`, `README.md` — 1
- `src/haven/RenderedNormals.java`, `Outlines.java`, `RUtils.java`, `GSettings.java`, `UI.java`,
  `render/Rendered.java`, `render/FrameConfig.java`, `render/TickList.java`, `render/Texture.java`,
  `render/sl/ValBlock.java`, `render/sl/FragData.java`, `MapView.java` (constructor) — 1
- `src/haven/Gob.java` (`SetupMod`, `GobState`, `updstate`, `removed`, `dispose`) — 1, 2
- `src/io/brodgar/addon/GobTint.java`, `LuaGob.java`, `GobIntent.java`, `UiApi.java`
  (`teardownGobScales`), `ApiVersion.java`, `OutlineMask.java`, `OutlineRing.java` — 1
- `src/io/brodgar/addon/GobOutline.java`, `AddonManager.java` (`colorArg`, `colorRefusal`,
  `color`, `gobCopies`, `gobSnapshot`), `Args.java`, `Refusal.java` — 1, 2
- `src/io/brodgar/addon/VirtualApi.java` (`entityHandle`, `setEntityTint`, the look copies, `rehome`),
  `LuaWorldEntity.java`, `GhostGob.java` — 2
- `tools/docverbs.py`, `tools/refusalverbs.py` — 1, 2
