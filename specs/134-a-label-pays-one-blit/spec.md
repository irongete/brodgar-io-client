# 134 — a label pays one blit

## What & why

A number painted over a crop costs five blits and five trips into Lua a frame today: four black copies
of the string, one step out in each direction, and the white one on top. That is the only outline the API
can draw, while the client bakes its own into the raster once (`Utils.outline2`) and blits once. The
farming helper drops the frame rate on a full field for that reason, and every addon drawing over the
world pays the same.

The addon is a painter at all because `gob:overlay():text(s)` hangs its label **15 world units above
the gob** and nothing says "on the ground". A `text` overlay never enters Lua per frame and dies with
its object: the cheap path a crop number wants, if it could stand at the plant's foot.

Two verbs close both gaps:

1. **`h:outline(c)`** on a [font variant](../../docs/addons/api/font.md): the outline is a property of
   the face, baked into the cached raster by the same `Utils.outline2` the client's own stack counters
   and progress bars use. One blit per label, one raster for its lifetime, and `hafen.ui():measure`
   answers the grown box.
2. **`ov:height(z)`** on a [gob overlay](../../docs/addons/api/overlay.md): where along the gob's own
   up-axis the projected point is taken, in world units, `15` by default and `0` the ground under the
   object. The label and the `draw` callback's `sx, sy` alike are taken there.

## Acceptance criteria

- **A1.** `h:derive():outline(c)` writes a colour on a draft variant and hands the handle back;
  `h:outline()` reads it back, `nil` on a face carrying none; `h:outline(nil)` undoes it.
- **A2.** A label drawn with an outlined handle is **one** blit: `hafen.ui():measure(s, {font = h})` is
  the plain measurement plus two pixels in each dimension, and the same string in the same handle is one
  cache entry across frames (`hafen.client():profiling():textcache()`).
- **A3.** The outline colour is baked and the text colour tints the whole raster, so a black outline
  stays black under any tint. Stated on the page.
- **A4.** A built-in and a used handle refuse `:outline(c)` naming `:derive()`, as every other setter
  does; a handle carrying an outline is refused where it would style a client surface (a sheet rule,
  `widget:rule()`), and the refusal says an outline is the addon's own drawing.
- **A5.** `ov:height(z)` on an overlay of yours writes a number in world units and hands the overlay
  back; `ov:height()` reads it, `15` on a fresh one; `:info()` carries `height`. A non-number raises;
  on a native overlay the write raises naming the key.
- **A6.** A `draw` record at `height 0` is handed the `sx, sy` that `s:world():worldToScreen(gob:position())`
  answers for the same object in the same frame, within one design pixel; a `text` record at `0` is
  blitted bottom-centred at that point. Two records of one gob at two heights are each drawn at their
  own.
- **A7.** The refusal `ov:offset(x, y, z)` raises names `ov:height(z)` as the vertical world form.

## Out of scope

- **An outline on a client surface** through a stylesheet. A sheet styles the client's own foundries and
  none of them is decorated; the boundary is A4's refusal. What that would be: a furnace decorator in
  the `TexFurn`/`BlurFurn` stack, routed per site.
- **A horizontal world offset** (`x`, `y` in world units). An overlay's offset is pixels; the ground
  under the object is one point, which is all a label at the foot needs. Beside a gob is `hafen.virtual`.
- **A text anchor verb** (`ax, ay`) on a gob label. A label stands on its point; an addon that wants it
  centred moves it by `ov:offset`.
- **The farming helper.** The maintainer's own; adopting the two verbs is their change.
- **Sub-pixel placement of a blitted label.** The jitter a rotating camera shows is integer blitting,
  the client's own labels included: a feature of its own.

## Docs impact

Pages written: `docs/addons/api/font.md` (the variant table, the "does not travel" paragraph),
`docs/addons/api/overlay.md` (the anchor paragraph, the setter table, the `:info()` shape),
`docs/addons/api/ui/drawing.md` (the cache section: the outline rides in the font key),
`docs/addons/guides/custom-ui.md` (the "just above the head" sentence), `docs/client/text-and-fonts.md`
(the `Utils.outline`/`outline2` decorator beside `TexFurn`/`BlurFurn`; the page is at its 150-line
ceiling, so its chat-colours section splits out to its own page in the task that writes the row).

Derived impact set — `grep -rn "does not travel\|just above the head\|15 world units" docs/addons`:

```text
docs/addons/api/font.md:87        "color is the one option that does not travel"   -> two options now
docs/addons/api/overlay.md:52     "15 world units above the gob"                    -> the default, not the rule
docs/addons/guides/custom-ui.md:115 "just above the head"                            -> by default
docs/addons/api/virtual/README.md:264 "does not travel" (a standing widget)          -> unrelated, discharged
```

`grep -rn "the string, the font and the" docs/addons/api/ui/drawing.md` — still true, the outline is
in the font; discharged with a sentence saying so.

## Context files

- `docs/addons/api/font.md` — 1
- `docs/addons/api/ui/drawing.md` — 1
- `docs/addons/api/ui/style/text.md` — 1 (where a rule takes a face: the refusal is stated there too)
- `docs/addons/api/overlay.md` — 2
- `docs/addons/guides/custom-ui.md` — 2
- `docs/client/text-and-fonts.md` — 1
- `DOCUMENTATION.md`
- `src/io/brodgar/addon/FontHandle.java` — 1
- `src/io/brodgar/addon/FontApi.java` — 1
- `src/io/brodgar/addon/LuaGOut.java` — 1
- `src/io/brodgar/addon/LuaRule.java` — 1 (where a rule takes a font: the refusal site)
- `src/io/brodgar/addon/Sheet.java` — 1 (the hop from the rule to `FontApi.face`)
- `src/haven/Utils.java` — 1 (`outline`, `outline2` only)
- `src/haven/Text.java` — 1 (the protected constructor)
- `src/io/brodgar/addon/LuaGobOverlay.java` — 2
- `src/io/brodgar/addon/LuaOverlay.java` — 2
- `src/io/brodgar/addon/UiApi.java` — 2 (`paintGobOverlays` only)
- `src/io/brodgar/addon/Eye.java` — 2
- `src/io/brodgar/addon/WorldApi.java` — 2 (`worldToScreen` only)
