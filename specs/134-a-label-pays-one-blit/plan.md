# 134 — plan

## Approach

Two verbs, each on the handle that already owns the thing it configures, and neither adds a draw door.

**The outline is a property of the face.** `FontHandle` gains a fifth field, `Color outline`, written by
`h:outline(c)` through the same `FontApi.property` helper the other four use — the ownership guard
(`FontHandle.writable`: a built-in and a used draft refuse naming `:derive()`), the `nil`-undoes rule
`size` and `aa` document, `colorArg` for the value. It rides into the text cache for free: `LuaGOut.Key`
holds the `FontHandle` by identity, so a handle carrying an outline is a different key from one that does
not. The raster is grown at the one place a string becomes a `Text`, `LuaGOut.render`: when
`fh.outline != null`, wrap the result as `Utils.outline2(t.img, fh.outline)` and hand back a `Text` over
the grown image — `Text`'s `(String, BufferedImage)` constructor is `protected`, so the wrap is an
anonymous subclass built from the addon package, and `haven` is not edited. `measure`, `label` and both
`draw` sites all go through `render`, so the grown box is what every consumer sees. The fast path of
`render` (`Text.render(str)` with no handle) is never taken with a handle, so no second site exists.

The tint stays where it is: `blitText` multiplies the whole raster by the call's colour. A black outline
survives every tint, a coloured one is its colour times the text's — stated on the page, not worked
around, because the alternative is two blits per label.

`FontApi.face` — the door a rule or `widget:rule()` takes a face through — refuses a handle carrying an
outline beside the one it already refuses for a colour, with the same shape of message: the outline is the
addon's own drawing, hand the rule a face carrying none. The table form (`{builtin=, size=, …}`) refuses
an `outline` key the way it refuses `color`.

**The height is a property of the record.** `LuaGobOverlay.Attach` gains `volatile double height = 15`;
`LuaOverlay` gains `height` beside `offset`, through `writable` (the native refusal naming the key, the
virtual read-only refusal) and `numberArg`. `LuaGobOverlay.draw` projects **per record** rather than once:
`Eye.view(new Coord3f(0, 0, rec.height), state, area)` for each, memoising the last height's point since
the records of one gob usually share it; `UiApi.paintGobOverlays` takes the point per record (an array
beside the list, or the loop moves into `LuaGobOverlay.draw` and `paintGobOverlays` paints one). The
`:info()` snapshot of a record of yours gains `height`. The `offset` refusal on a third argument names
`ov:height(z)` as the vertical world form, and keeps pointing the horizontal one at `hafen.virtual`.

A label at the ground stays bottom-centred (`ax 0.5, ay 1.0`), standing on the point.

## Files to create/modify

| File | Change |
|---|---|
| `src/io/brodgar/addon/FontHandle.java` | `Color outline`; the copy in `derive` |
| `src/io/brodgar/addon/FontApi.java` | `outline` in `property`/`read`; the refusal in `face` and in the table form |
| `src/io/brodgar/addon/LuaGOut.java` | `render` grows the raster through `Utils.outline2` |
| `src/io/brodgar/addon/LuaGobOverlay.java` | `Attach.height`; per-record projection in `draw` |
| `src/io/brodgar/addon/LuaOverlay.java` | `height` setter/read; `info().height`; the `offset` refusal text |
| `src/io/brodgar/addon/UiApi.java` | `paintGobOverlays` takes the point per record |
| `docs/addons/api/font.md` | the row; "the two options that do not travel" |
| `docs/addons/api/ui/drawing.md` | the cache section: the outline is in the font, so in the key |
| `docs/addons/api/overlay.md` | the anchor paragraph, the `height` row, the `:info()` shape |
| `docs/addons/guides/custom-ui.md` | "just above the head" → by default, `:height(0)` for the ground |
| `docs/client/text-and-fonts.md` | `Utils.outline`/`outline2` row in the decorator table; **split**: the chat-colours section moves to `docs/client/chat-colours.md`, `README.md` and inbound links re-pointed |
| `addons/134-a-label-pays-one-blit.1/`, `.2/` | the suites |

## Risks and gotchas

- **`Utils.outline` tests alpha `< 250`**, so antialiased edge pixels count as outside and get outlined;
  the glyph's own soft edge is preserved by `outline2` drawing the original at `(1, 1)` over it. The raster
  grows by exactly two in each dimension, which is what A2 asserts.
- **`Text.tex()` memoises**: wrap the image before anything calls `tex()`. `render` is the right place; the
  `Line` subclass `Text.Foundry.render` answers is not needed downstream (`draw0` uses `tex()` and `sz()`).
- **`TexI` sizes are `nextp2`-rounded** (text-and-fonts.md): a raster grown from 15 to 17 wide costs a
  32-wide texture; the cache's byte cap counts it. Not a defect, but the reason a huge outlined font is dearer
  than it looks.
- **The record is read from the draw pass** (`Attach` fields are `volatile`); `height` is a `double`, one
  write. No lock.
- **A record is one instance in every session's copy of the gob** (`LuaGobOverlay.attach`), so the height
  is set once and every character draws it there.
- **`Eye.view` in object space**: `(0, 0, 0)` is the gob's placement, which is `Gob.getc()` — the
  interpolated point with the placer's `z` — so `height 0` is the ground the client itself stands the
  object on. A6 compares against `s:world():worldToScreen(gob:position())`, which projects `MCache.getzp`
  at `rc`; for a standing gob the two agree, which is why the suite uses the player gob **standing still**
  and allows one pixel.
- **`FontApi.property` refuses `nil`** except on `size` and `aa`: `outline` joins them, since `nil` there
  documents a meaning (no outline).
- **`docs/client/text-and-fonts.md` is at 150 lines**: the row pushes it over, so the split is in 134.1,
  by subject (the chat's colours are their own map), with `docs/client/README.md` re-pointed.

## Discarded alternatives

- **`opts.outline` on `g:text`/`g:atext` per call** — the cache key would need a fifth component and the
  same outline would be spelled in two places (the call and the font); a face already owns everything the
  raster is made of.
- **An outline as a stylesheet property on client surfaces** — every client foundry is undecorated and the
  routed sites stack `TexFurn`/`BlurFurn` per site; that is a per-site routing feature, not a font flag.
- **Two blits (glyph raster and outline raster) so the outline keeps its colour under a tint** — halves
  the gain for a case nobody has: an outline is dark, and dark survives a tint.
- **`ov:anchor("ground")` / `ov:anchor("head")`** — two words for a number; a height is a number, and a
  number reads back as what was written.
- **`ov:offset(x, y, z)` with the third number in world units** — the verb 043.3 removed for mixing two
  units in one pair; the vertical is its own property with its own unit.
- **A text kind for `hafen.virtual` (a label in the scene)** — perspective and occlusion are wrong for a
  readout, cost a scene node per label, and it is the ROADMAP's world-space text, not this.
- **`ax, ay` on a gob label** — `ov:offset` moves it; an anchor verb would be a second way to say the same
  pixels.
- **Editing the farming helper here** — the maintainer's own addon; the feature ships the verbs.
