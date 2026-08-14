# 065 — theming depth

## What and why

A theme reaches the client's **text** everywhere, and its **chrome** on exactly two surfaces: a window's
decoration and the window-less panels. Everything else a theme wants is a constant compiled into the widget
that draws it — where a window's caption sits, what its close button looks like, the face of every button,
field and checkbox, the tooltip's box, the inventory square, the belt across the bottom of the screen. This
feature settles the **whole vocabulary** a theme is written in, and spends it on those surfaces.

It also closes the data model. A rule is values, so a look can be a file — except that a `border` needs an
**image handle** and a `font` needs a **font handle**, the only two values JSON cannot carry, and they are
exactly what a theme is built out of. Naming art and faces by *name* rather than by handle is what makes a
whole client look a file with no Lua in it.

Two facts about the client shape every decision here. Widgets compute their layout **in their constructors**,
so nothing here re-flows a window's insides. And a rule is **parsed once into plain data** the engine paints
from, so nothing here runs Lua per frame.

## The vocabulary

The whole of it, settled here and spent by every task. Four kinds of value, and the properties that take
them.

| Value | Written | Taken by |
|---|---|---|
| **art, 9-slice** | `{image = <handle>}` · `{asset = "art/f.png"}` · `{res = "gfx/…"}`, each with `slice = {l,t,r,b}`; or `{box = "gfx/hud/wnd"}`, the engine's own eight-part box. `mode = "stretch"` (the default) or `"tile"` says what the four edges do between the corners | `border` |
| **art, line** | `{color = {r,g,b,a}, width = n}` | `border` |
| **art, surface** | `{color = {…}}` · `{image = <handle>}` · `{asset = "…"}` · `{res = "gfx/…"}`, each with an optional `at`, `offset` and `mode` | `bg` and its layers, `picture`, the art inside `close`/`sizer`, each entry of `border`'s `parts`, and `emboss`'s `texture` |
| **a list of either** | an **array** of surfaces is a stack of layers, painted in order; an array of `{art, at, offset}` is a set of pieces pinned to a frame | `bg`, and `border`'s `parts` |
| **face** | `<font handle>` · `{asset = "fonts/x.ttf", size =, bold =, aa =}` · `{builtin = "mono", size = …}` | `font` |
| **generated colour** | `{palette = {{r,g,b}, …}}` cycles the colours given; `{generate = {step =, saturation =, brightness =}}` walks a hue, which is what the client does per speaker | `color`, on the keys whose colour is a sequence |

| Property | Value | Says |
|---|---|---|
| `font` | face | what the text is set in |
| `color` | `r,g,b[,a]` | what colour it is drawn in |
| `emboss` | `false` · `{texture = <art>}` | whether the client's relief is used, and with what. `false` is what makes `color` reach a caption, a heading or a button label at all |
| `glow` | `{color = {…}, radius = n}` | the halo behind the glyphs |
| `bg` | art, surface, or a list of them | the surface something is painted on, one layer or several |
| `border` | art, 9-slice **or** line, plus `parts` | the frame around it, and any piece pinned inside that frame |
| `padding` | `n` or `{l,t,r,b}` | the room between a frame and its content |
| `picture` | art, surface | the whole picture a surface *is*, where the client draws a plate rather than a frame |
| `caption`, `close`, `sizer` | `{at =, offset =}`, plus art for the last two | where a window's decoration puts its three ornaments |
| `position`, `anchor`, `size` | as they are today | where a widget is, and how big |

**Every image slot in the vocabulary is the same value, with the same four spellings.** Wherever a picture
may go, `{res = "gfx/hud/…"}` names the game's own art and `{asset = "art/f.png"}` names a file the addon
ships, so nothing has to be extracted from the client's resources to be themed with, and nothing may be
named one way in one property and another way in the next. One parser answers for all of them, and one
error message. The two mix freely inside a rule, which is what a theme of its own art built on the client's
frames needs.

One difference between them is the author's to know, and the page says it: **a shipped file's pixels are
design pixels** — authored at the weight you want to see, and scaled up with the interface — while a
`.res` carries its own scale and the client resamples it per interface scale from art authored several
times larger. So game art stays crisper on a scaled-up client, and a theme that wants the same wants to
ship its art at that weight.

**State rides inside the value, never in the selector.** `bg` and `picture` take named variants —
`hover`, `pressed`, `disabled`, `checked` — of the same shape as the value they sit in. The rasteriser
already knows its own state; nothing publishes state to the cascade.

**A part the client draws separately is a key of its own**, the way `window.frame` and `window.title`
already are: `checkbox` and `checkbox.mark`, `scrollbar` and `scrollbar.knob`, `slider` and `slider.knob`.
It is also what makes a **caption plate** sayable — `window.title` starts carrying `bg` and `border`, the
plate being the caption's own surface — and what splits the chat's colours, which are one constant per kind
rather than one colour, into `chat.system`, `chat.private`, `chat.party` and `chat.urgent`.

The keys this feature adds to the thirteen that exist: `checkbox`, `checkbox.mark`, `scrollbar`,
`scrollbar.knob`, `slider`, `slider.knob`, `inventory.slot`, `hud.belt`, `hud.menu.left`,
`hud.menu.right`, `hud.search`, `minimap.frame`, `chat.system`, `chat.private`, `chat.party`,
`chat.urgent`, `chat.speaker`.

**Everything the client's own look is made of is sayable in this vocabulary**, which is the bar this
feature is held to: tiled runs and stretched ones, a plate that sizes to its caption, a background of
several layers, a piece pinned at the end of a run, and a colour the client walks rather than holds. The
proof is `sheet:stock()` — the client's whole look read back **as data**, written to a file, and installed
again with nothing left over.

## Scope, in three stages

Each stage is shippable on its own, and every task inside one ships alone.

**A — the vocabulary, spent on the window** (065.1–065.5). The property bag that makes a new property cost no
core edit; `padding`; art and faces by name, stretched or tiled; the caption and its plate, the close button
and the sizer. At the end of it a window theme is a JSON file.

**B — the surfaces that draw a box** (065.6–065.11). The line border, the tooltip's box, the inventory
square, the button face, text fields, checkboxes, scrollbars and sliders, the speech bubble.

**C — the picture, the letter, and the client said back** (065.12–065.18). `picture` over the client's
plates, the HUD's own art, `emboss` and `glow` — the two that turn "another frame" into another client —
the chat's colours, `sheet:stock()` and the catalogue it writes, and the sweep.

## Acceptance criteria

Each is verifiable in-game through the task's own suite; the task that owns it is named.

- **A1** (065.1) — `rule:padding(n)` and `rule:padding(l,t,r,b)` both apply and read back; a window's content
  sits at the four distances the rule says. `rule:pad(…)` and `"pad"` inside `sheet:load` each raise an error
  naming `padding`.
- **A2** (065.1) — a rule naming one property still composes with a broader rule naming another: the
  per-property fold survives the bag.
- **A3** (065.2) — `border{box = "gfx/hud/wnd"}` dresses a window and a panel with the client's own frame,
  shipping no art; `{res = …}` and `{asset = …}` reach the same pixels as a handle; `mode = "tile"` repeats
  an edge where the default stretches it; a name that resolves to no resource, and a `box` whose parts are
  missing, raise distinguishable errors.
- **A4** (065.3) — a **complete theme loads from a JSON file** through `sheet:load` and installs: every
  property in the vocabulary, no handle anywhere in it, including its fonts.
- **A5** (065.4) — with a `caption` spec the title is drawn at the corner and offset the rule says; with
  none, where the stock client draws it, to the pixel. Same for the sizer. A `bg` or a `border` on
  `window.title` draws a **plate** behind the caption, sized by the client's own rule, and a longer title
  widens it.
- **A6** (065.5) — a themed close button shows the theme's art at its corner, still closes its window, and
  holds its place **after the window is resized**.
- **A7** (065.6) — `bg`, `border{color,width}` and `padding` on `tooltip` restyle the box the client pops up.
- **A8** (065.7) — `inventory.slot` restyles every square of every inventory, the equipment window included.
- **A9** (065.8) — `bg` and `border` on `button` reach the client's own buttons and the `pressed` variant
  shows while one is held.
- **A10** (065.9, 065.10) — the same on a text field, a checkbox, a scrollbar and a slider, each through its
  own key, with the part keys reaching the mark and the knob.
- **A11** (065.11) — a `border` on `world.speech` restyles the bubble over a talking character.
- **A12** (065.12, 065.13) — `picture` replaces a client plate: an `Img` the client built, the belt, the two
  menu backgrounds and the minimap's frame.
- **A13** (065.14) — `emboss = false` plus a `color` paints a window caption, a heading and a button label in
  that colour; `emboss{texture = …}` tiles the theme's own texture through the glyphs.
- **A14** (065.15) — `glow{color, radius}` draws a halo behind a caption, and no glow property leaves the
  stock halo exactly as it was.
- **A15** (065.16) — `chat.system`, `chat.private`, `chat.party` and `chat.urgent` each colour their own
  lines, so a rule on one leaves the others reading as they did; `chat` still covers the whole window; and
  `chat.speaker` takes a palette or a generator, so the colour the client walks per speaker is a value a
  theme can both read and replace.
- **A16** (065.17) — `sheet:stock()` hands back **the client's whole look as plain data**: every key this
  client routes, with the art named by resource, the faces by built-in, and the colours as numbers. Written
  to a file with `hafen.json()` and loaded back through `sheet:load()`, it installs, and the client looks
  the way it looks with no sheet at all — the frame's tiled runs, its caption plate, its layered background
  and its foot piece included.
- **A17** (every task) — a stock client with no addon draws every surface above byte-for-byte as it does
  today, and `sheet:drop()` restores that state on all of them.

## Out of scope

- **An engine-wide layout pass**, and any re-flow of what a window puts inside itself. That is
  `widget:replace()` and it stays there.
- **State and pseudo-class selectors** — the state is in the value.
- **Motion**: no transition, no animation, no per-frame effect. `glow` is a halo resolved once, not a bloom.
- **The chat window's frame**, the combat HUD, buffs, meters, the calendar and the equipment plate: art bound
  to each widget's own geometry, none of it a frame, and each its own small feature.
- **The 3D world**, the cursor, and anything the server draws from a resource.
- **A theme changing what the client draws.** A theme dresses the three buttons a window has; it does not
  remove one.

## Docs impact

Pages this feature writes:

- `docs/addons/api/ui/style/chrome.md` — `padding`, the art values, the ornaments, the control faces
- `docs/addons/api/ui/style/keys.md` — the new keys, and every row that says chrome is inert on a text site
- `docs/addons/api/ui/style/text.md` — `emboss` and `glow`, and where `color` stops being inert
- `docs/addons/api/ui/style/README.md` — the property table, the data section, the boundary section
- `docs/addons/api/ui/style/surfaces.md` — what each new key is on screen
- `docs/addons/guides/theming.md` — the guide, and the complete theme file it ships
- `docs/addons/api/font.md`, `docs/addons/api/asset.md` — a face and an art named rather than loaded
- `docs/client/ui-chrome.md`, `ui-controls.md`, `ui-lists.md`, `text-and-fonts.md` — the seams, and the
  surfaces the client draws **in code** rather than from a resource

The derived impact set:

```text
$ grep -rn "pad" docs/ | cut -d: -f1 | sort | uniq -c | sort -rn
     17 docs/addons/api/ui/style/chrome.md      6 docs/addons/api/ui/style/README.md
      4 docs/addons/api/ui/style/keys.md        3 docs/addons/guides/theming.md
      3 docs/addons/api/ui/style/geometry.md    2 docs/addons/api/ui/native.md
      1 docs/addons/api/ui/style/surfaces.md    1 docs/addons/api/ui/pixels.md
      1 docs/addons/api/README.md

$ grep -rn "no hover\|hover, pressed\|State-dependent" docs/addons
docs/addons/api/ui/style/README.md:198  ·  docs/addons/guides/theming.md:100

$ grep -rn "embossed\|inert" docs/addons/api/ui/style/*.md | cut -d: -f1 | sort | uniq -c
     10 keys.md      4 text.md      2 surfaces.md      1 README.md
```

So `geometry.md`, `native.md`, `pixels.md` and `api/README.md` carry a `pad` the rename must reach; the two
boundary paragraphs state limits this feature moves; and every "embossed, so `color` is inert" sentence is
revised by 065.14 rather than deleted — the emboss is still the stock behaviour, it is now nameable.

## Context files

Tagged with the tasks that need them; an untagged line is read by every task.

- `docs/addons/api/ui/style/README.md`, `keys.md`, `chrome.md` — all
- `docs/addons/api/ui/style/surfaces.md` — 4, 6, 7, 8, 9, 10, 11, 12, 13, 16, 17, 18
- `docs/addons/api/ui/style/text.md` — 3, 14, 15, 16, 18
- `docs/addons/guides/theming.md` — 1, 2, 3, 17, 18
- `docs/addons/api/json.md` — 3, 17 (what a theme is parsed with, and a catalogue serialised with)
- `docs/addons/api/timer.md` — every task whose suite drives a window: the chrome swap lands in `Window.tick`,
  so a geometry read has to wait a beat for it
- `docs/addons/api/asset.md` — 2, 3, 12 (the door an art and a face are both NAMED through)
- `docs/addons/api/font.md` — 3, 14, 15
- `docs/addons/api/ui/pixels.md` — 1, 2
- `docs/addons/api/ui/style/geometry.md` — 1, 4, 5
- `docs/addons/api/ui/controls/interactive.md` — 8, 9, 10
- `docs/addons/api/ui/controls/display.md` — 10, 12
- `docs/addons/api/ui/widget.md` — 5, 17 (`widget:chrome()`, the read a window's ornaments are MEASURED
  with: the caption's drawn origin, the plate's box, the sizer's origin and the close button's box)
- `docs/client/ui-chrome.md` — 2, 4, 5, 6, 7, 11, 17, 18
- `docs/client/ui-controls.md` — 8, 9, 10, 12, 18
- `docs/client/ui-lists.md` — 9, 10 (and it now carries `TextEntry`'s whole draw: its four statics, its
  raster cache, and the chrome seam a routed control wears)
- `docs/client/ui-scaling.md` — 9, 10 (where each control's CONSTRUCTED height comes from: the one page that
  says which art decides a button's, a field's, a checkbox's and a slider's box)
- `docs/client/text-and-fonts.md` — 1, 14, 15, 16, 17, 18
- `src/haven/Fonts.java` — 1, 3, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17 (and `Fonts.drawchrome`, the seam
  for a box the SITE sizes and a rule dresses — the tooltip's and the inventory square's shape too)
- `src/haven/ChatUI.java` — 16 (the per-kind colour constants, and the generated speaker hue)
- `src/io/brodgar/addon/AddonManager.java` — 17 (`onPicture`, the texture-to-resource-name registry a
  catalogue reads art names back through)
- `src/io/brodgar/addon/LuaSheet.java` — 17
- `src/io/brodgar/addon/Sheet.java`, `Chrome.java`, `LuaRule.java` — 1–17
- `src/io/brodgar/addon/SkinDeco.java` — 1, 4, 5, and **every task that changes `Chrome.Border`**: it is the
  second consumer of that type's `tlIn()`/`brIn()`, so a new border shape has to answer the window's layout too
- `src/io/brodgar/addon/Px.java` — every task adding a property whose value is a DISTANCE: the design/device
  seam a width, an inset or a padding converts through on its way into a layout sum
- `src/io/brodgar/addon/Retired.java` — 1
- `src/io/brodgar/addon/Selector.java` — 4, 7, 10, 11, 13, 16, 17, 18 (`WIDGET_ROLES`/`SITE_ROLES`, where a
  new key is declared a site rather than silently becoming a tree key)
- `src/io/brodgar/addon/UiApi.java`, `LuaWidget.java` — every task whose suite drives a widget of its own:
  what an addon window is built as, and which reads a suite can measure it with
- `src/io/brodgar/addon/LuaImage.java`, `AssetApi.java` — 2, 12
- `src/io/brodgar/addon/FontApi.java`, `FontHandle.java` — 3
- `src/haven/Window.java` — 4, 5
- `src/haven/GOut.java` — 6, 8, 9, 10, 13 (`image`/`rimage` and the clipped blit a run is written with)
- `src/haven/IBox.java` — 2, 11
- `src/haven/UILoop.java` — 6
- `src/haven/Inventory.java` — 7
- `src/haven/Button.java`, `src/haven/TextEntry.java` — 8, 9, 10: the two routed controls, and the pattern
  every later one copies — `Fonts.chrome` for the paint, and `Fonts.chromesz` where a control MEASURES itself
  from its own background (a field does; ask it before `super(…)`, since there is no widget yet)
- `src/haven/CheckBox.java`, `IButton.java`, `Scrollbar.java`, `HSlider.java` — 10
- `src/haven/Speaking.java` — 11
- `src/haven/Img.java` — 12
- `src/haven/GameUI.java`, `MiniMap.java` — 13
- `docs/client/minimap.md` — 13 (where the corner map is added, and the one that decides what a plate over it
  looks like: `GameUI` calls `mmap.lower()`, so `blframe` is drawn **over** the map it frames)
- `src/haven/PUtils.java`, `Text.java` — 14, 15
- `addons/065-theming-depth.<N>/` — each task its own
