# 065 — theming depth: tasks

Three stages. Each is shippable on its own, each task inside one ships alone, and the vocabulary is settled
in stage A before any surface is routed against it.

## Stage A — the vocabulary, spent on the window

- [x] **065.1 — a rule's chrome is a property bag, and `pad` becomes `padding`.** `Fonts.Spec`'s three
      opaque fields collapse into one immutable `Map<String, Object>`, folded key by key by `Fonts.combine`;
      `Fonts.Style` drops `bg()`/`border()`/`pad()` for `Object prop(String)`; `Fonts.push` and
      `treeSpec` take the map. Spent at once: `pad` is retired and `padding` takes its place — one number
      for four sides or `{l, t, r, b}` — through `Chrome.Pad` (`tlIn()`/`brIn()`, the pair
      `Chrome.Border` already answers), consumed by `SkinDeco.iresize`. `Sheet.PROPS`, the load dispatch,
      `Props.toLua` and `LuaRule` follow; `Retired` gains the refusal.
      *Its suite* installs `padding(8, 4, 8, 8)` on `window.frame`, reads it back as four numbers, and drives
      a window of its own whose content box it measures against the four distances; then installs
      `padding(6)` and asserts all four moved. It asserts the fold survives the bag by putting a `font` on
      `["*"]` and a `border` on `["window.frame"]` and reading both back off one window's resolved style.
      `pcall`s `rule:pad(4)` and `sheet:load{["window.frame"] = {pad = 4}}` and asserts each failed naming
      `padding`; `pcall`s `padding(1, 2)` and `padding(-1)` and asserts each was refused.
      `[manual]`: open any client window — expect its contents to sit further from the frame at the top than
      at the bottom, and the frame itself unchanged.

- [ ] **065.2 — art is named, not only handed over.** One parser, `Chrome.parseArt`, behind `bg` and **every
      image slot every later task adds** — `picture`, the art in `close`/`sizer`, each `parts` entry, an
      `emboss` texture: `{color=}`, `{image=<handle>}`, `{asset="path"}`, `{res="gfx/…"}`. Naming the game's
      own art is what this buys: nothing has to be extracted from the client's resources to theme with.
      `border`
      gains `{res=}`/`{asset=}` beside `{image=}`, and `{box="gfx/hud/wnd"}` — the engine's own eight-part
      `IBox.Scaled`, whose insets are `ctloff()`/`cbroff()` rather than a `slice`. A `.res` art takes the
      resource's **scaled** view, since the HUD art is authored at 4×. It also gains
      `mode = "stretch" | "tile"`: the tiling box blits its edge a tile at a time, clipped to the run, which
      is what the client's own decoration does and what a `stretch` edge cannot say. And a `bg` may be an
      **array of layers** painted in order, each an ordinary surface value with its own `at` and `mode` —
      the stock window's background being three of them, a tiled field and a shade down each side.
      *Its suite* dresses a window and a panel from `{box = "gfx/hud/wnd"}` with no file of its own and
      asserts both resolved a border; loads the same PNG twice, once by handle and once by `{asset=}`, and
      asserts the two rules resolve to the same art; installs the same art twice, `stretch` and `tile`, and
      asserts the two resolve to different borders — one art, two modes, so the mode is what it proves;
      installs a three-layer `bg` and asserts all three came back in the order written.
      `pcall`s `{box = "gfx/hud/nosuchbox"}`, `{res = "gfx/nosuchimage"}`, `{image = h, box = "gfx/hud/wnd"}`,
      `{color = {…}, res = "gfx/…"}`, `mode = "repeat-x"` and an empty layer array, and asserts each failed
      naming what was wrong.
      `[manual]`: expect the window's frame to be the client's own plain golden frame, at the same weight the
      panels beside it wear, and sharp — not blurred or four times too large. Under `tile`, expect the edge
      art repeated along a wide window with no stretching and no seam at the corners; under the three-layer
      `bg`, a shade down each side over the tiled field.
      <!-- extra context: src/haven/Resource.java — Image.scaled()/tex() and NoSuchResourceException -->

- [ ] **065.3 — a face is named too, so a whole theme is a file.** `font` accepts
      `{asset = "fonts/x.ttf", size =, bold =, aa =}` and `{builtin = "mono", size = …}` beside the handle,
      resolved through `FontApi` to the `FontHandle` the property already takes. With this, every value in
      the vocabulary has a spelling JSON can carry.
      *Its suite* ships a `theme.json` naming a font by `builtin`, a font by `asset`, a `bg` by `color`, a
      `border` by `box` and a `padding`, parses it with `hafen.json()` and installs it through
      `sheet:load`, then asserts `sheet:info().installed` and that each rule reads back the property it
      declared — the round trip is the claim. `pcall`s `{builtin = "nosuchface"}` and asserts it failed
      naming the built-ins, and `{}` and asserts it failed for saying nothing.
      `[manual]`: expect the client's text in the file's face and its windows in the file's frame, with no
      Lua in the theme but the one line that loads it.

- [ ] **065.4 — a theme says where the caption goes, what it sits on, and where the sizer is.**
      `window.frame` gains `caption{at, offset}` and `sizer{<art>, at, offset}`, parsed into `Chrome.Spot` —
      one of the nine corners plus an offset in design px — through the corner vocabulary extracted from
      `Layout.parseAnchor`. `SkinDeco.drawframe` places both; with no spec each falls back to `Window.cpo`
      and `Window.sizer` at their stock places. `window.title` starts carrying `bg` and `border` — the
      **plate** behind the caption — drawn at the box `checkcap` computes (`cptl`/`cpsz`, from
      `cmw = max(caption width, sz.x / 4)`), so the client keeps deciding how wide a plate is and the theme
      says what it looks like. `border` also gains **`parts`**: an array of `{art, at, offset}` pinned with
      the same `Chrome.Spot`, for the pieces a frame carries that are not corners or runs — the stock's own
      foot piece at the bottom of its left edge being one.
      *Its suite* installs `caption{at = "topleft", offset = {12, 4}}`, drives a window of its own with a
      known title, and asserts the caption's drawn origin is the corner plus the offset; then removes the
      property and asserts it returned to the stock origin, the number the client itself uses. It installs a
      plate `border` on `window.title`, asserts the key resolved it, then **renames the window's caption to
      a much longer string** and asserts the plate's box grew with it — the client's rule still owning the
      geometry is the claim. It installs two `parts` at two corners and asserts both resolved, distinctly.
      `pcall`s `caption{at = "middle"}` and asserts the refusal named all nine corners, `caption{corner = …}`
      and asserts it named the fields a spot carries, and a `parts` entry with no art.
      `[manual]`: open the Inventory — expect its title tight under the top-left corner rather than floating,
      sitting on the theme's plate, and the window's contents where they were before the rule.

- [ ] **065.5 — the close button is the theme's.** `close{<art with hover/pressed variants>, at, offset}` on
      `window.frame`. `SkinDeco.check` rebuilds `cbtn` when the art moves — `IButton`'s faces are `final` —
      destroying the button it displaces, and `iresize` places it from the spot instead of pinning it to the
      top right. Art alone, or a spot alone, each work: the properties are independent.
      *Its suite* installs a close art of its own with a distinct size, asserts the button's box took the new
      art's dimensions, asserts its position is the spot's, then **resizes the window** and asserts the
      position held. It clicks nothing: instead it asserts the rebuilt button still carries the client's own
      close action by reading it back through the widget API. Dropping the sheet restores the stock size and
      place, asserted against the numbers read before installing.
      `[manual]`: click the X of a themed window — expect it to close, and the button to look like the
      theme's art and sit in the theme's corner both before and after dragging the window's edge.

## Stage B — the surfaces that draw a box

- [ ] **065.6 — a border may be a line, and the tooltip has a box.** `border` gains `{color = {r,g,b,a},
      width = n}`. `tooltip` starts carrying `bg`, `border` and `padding`: `UILoop.drawtooltip`'s two
      hardcoded `chcolor`/`rect2` calls ask the sheet and paint what they paint today when nothing answers.
      *Its suite* installs a `bg`, a line `border` and a `padding` on `tooltip`, then asserts the resolved
      style for that key carries all three — the paint is manual, the resolution is not. `pcall`s
      `border{color = …, slice = {1,1,1,1}}` and asserts the refusal said a line has no slice; `pcall`s
      `border{width = 2}` with no colour and asserts it was refused for saying nothing; `pcall`s
      `border{color = …, width = -1}`.
      `[manual]`: hover an inventory item — expect the tip's box in the rule's fill and its outline in the
      rule's colour, at the rule's thickness, with the text further from the edge than stock.

- [ ] **065.7 — the inventory square.** New site key `inventory.slot`, carrying `bg` and `border`.
      `Inventory`'s static 33×33 raster stays exactly as the stock value, and `Inventory.draw` asks the sheet
      per square, falling back to it. `Equipory` draws the same square and follows for free.
      *Its suite* installs a `bg` colour and a line `border` on `inventory.slot`, asserts the key resolved
      both, and asserts a rule on it is refused a `position` — a site is not a widget — with the message
      naming the fix. It duplicates the line-border assertions it depends on rather than assuming 065.6 ran.
      `[manual]`: open the inventory and the equipment window — expect every empty square in the rule's fill
      and outline, the item icons unmoved, and no square drawn over an icon.

- [ ] **065.8 — the button's face.** `button` starts carrying `bg` and `border`, with the state variants
      inside the value: `bg{color = …, hover = …, pressed = …, disabled = …}`. `Button.draw(BufferedImage)`
      composes from the resolved style when one exists, from its seven statics otherwise; its stock `IBox`
      is built once from `bl`/`br`/`bt`/`bb`. `Button.draw(GOut)`'s re-render check widens from "the caption
      moved" to "the style generation moved", and calls `redraw()`.
      *Its suite* drives buttons of its own, installs a `bg` with a `pressed` variant and a `border`, and
      asserts the resolved style carries both the base value and the variant; asserts the stock face returns
      on `drop()` by reading the button's size back before and after. `pcall`s a variant naming a state that
      does not exist and asserts the refusal listed the ones that do.
      `[manual]`: open Options — expect every button in the theme's frame and fill, and the fill to change
      while a button is held down.

- [ ] **065.9 — text fields.** `textentry` starts carrying `bg`, `border` and `padding`: `TextEntry`'s three
      caps (`gfx/hud/text/l`, `m`, `r`) and its caret are the stock value, and the field asks the sheet
      first. The height caveat is unchanged and documented — a field measures from its background, so a
      taller art makes a taller field while a larger font still clips.
      *Its suite* drives a field of its own, installs a `border` and a `bg`, asserts both resolved, and
      asserts the field's own height followed the art it was given. It re-asserts that a `font` rule on the
      same key still reaches the typed text, so the two halves of the key are shown not to displace each
      other. `pcall`s `padding` with a non-number and asserts the refusal.
      `[manual]`: open the chat's input and the `:` command line — expect both in the theme's field art, the
      caret still where you type, and selection still highlighting the right glyphs.

- [ ] **065.10 — checkboxes, scrollbars and sliders, and the parts they draw.** Six new site keys:
      `checkbox` and `checkbox.mark`, `scrollbar` and `scrollbar.knob`, `slider` and `slider.knob`, each
      taking `picture`'s art shape with `checked` where a state exists. `CheckBox`/`ICheckBox`,
      `Scrollbar` and `HSlider` blit their `Tex`es in `draw(GOut)` and simply ask first; none is an
      `SIWidget`, so none needs invalidating.
      *Its suite* drives one of each, installs a distinct art on the whole and on the part, and asserts each
      key resolved its own — that the part key does not inherit the whole's art is the assertion, since one
      art on both would look identical on screen. Toggles its checkbox through the API and asserts the
      `checked` variant is what the mark resolves to while set.
      `[manual]`: open Options — expect the checkboxes in the theme's box with the theme's tick, and the
      scrollbar of a long list with the theme's rail and knob.

- [ ] **065.11 — the speech bubble.** `world.speech` starts carrying `border` and `bg`. `Speaking` is a
      `GAttrib` rather than a widget, so it resolves through the widget-less `Fonts.style(scope)` path that
      `RichText` and `ChatUI` already use, not through `Fonts.box(scope, wdg, stock)`.
      *Its suite* installs a `border` on `world.speech` and asserts the key resolved it through the
      widget-less path — asserted by resolving the scope with no widget in hand, which is the seam the task
      adds. It re-asserts that a `font` rule on the same key still reaches the bubble's text.
      `[manual]`: say something in area chat and look above your character — expect the bubble's frame in the
      theme's art, the tail below it unchanged, and the text still centred in the bubble.

## Stage C — the picture, the letter, and the client said back

- [ ] **065.12 — `picture`, the whole plate.** A new property taking the art value with its state variants,
      read at the **draw** rather than written into the widget: an `Img` is re-pointed by the server, so a
      `setimg` write would be clobbered and would fight the restore. It applies through a tree key, so
      `["@Img"]` and a chain reach any picture the client built.
      *Its suite* finds a client `Img`, installs a `picture` on a tree key that names it, and asserts the
      widget's resolved style carries the art while its own `picture()` read still answers the client's
      resource — the two are different questions and the task's claim is that the second is untouched.
      Drops the sheet and asserts the resolved style is empty again.
      `[manual]`: expect the panel behind the minimap to be the theme's plate, and the minimap itself drawn
      over it exactly where it was.

- [ ] **065.13 — the HUD's own art.** Five site keys for the plates the client blits rather than builds a
      widget for: `hud.belt` (`GameUI.nkeybg`), `hud.menu.left` and `hud.menu.right` (`mapmenubg`,
      `menubg`), `hud.search` (the `csearch-bg` group) and `minimap.frame`. Each is one lookup at its draw,
      falling back to the static it uses today.
      *Its suite* installs a distinct `picture` on each of the five and asserts each key resolved its own
      art, then drops and asserts all five fell back. It asserts a `padding` on one of them is accepted and
      inert rather than refused — the doctrine every unappliable property here follows.
      `[manual]`: expect the belt across the bottom, the two menu backgrounds at the corners and the
      minimap's frame all in the theme's art, with every button on them still clickable where it was.

- [ ] **065.14 — `emboss`, and the colour it gives back.** `emboss = false` drops the `PUtils.TexFurn` a
      surface tiles through its glyph mask; `emboss{texture = <art>}` tiles the theme's own. It reaches the
      five surfaces that are embossed today — `window.title`, `heading`, `button`, and the two furnaces
      `CharWnd`/`GridList` build — through the foundry each already resolves. With `emboss = false` a
      `color` rule **reaches those glyphs**, which is the point of the property.
      *Its suite* installs `emboss(false)` plus a `color` on `window.title` and asserts the resolved style
      carries both; asserts that with no `emboss` property the stock relief is what resolves, so a client
      with only a `color` rule is unchanged. `pcall`s `emboss(true)` and asserts the refusal named the two
      things an emboss may be, and `emboss{texture = "gfx/…"}` with a missing resource.
      `[manual]`: expect window titles, section headings and button captions in the rule's flat colour with
      no golden relief; then with `emboss{texture = …}`, in the theme's texture.

- [ ] **065.15 — `glow`.** `glow{color = {…}, radius = n}` supplies the `PUtils.BlurFurn` every embossed site
      already builds behind its text — two radii and a colour, the stock caption's being `UI.rscale(0.75)`
      and `UI.rscale(1.0)`. A rule's radius is design px and converts through `Px.in`. No `glow` property
      leaves the stock halo exactly as it is.
      *Its suite* installs a `glow` on `window.title` and asserts the resolved style carries the colour and
      the radius; asserts a key with no `glow` resolves none, so the stock halo is untouched. `pcall`s
      `glow{radius = -1}` and `glow{color = {…}}` with no radius, asserting each refusal names what a glow
      needs.
      `[manual]`: expect a coloured halo behind the window titles at the radius asked for, and the captions
      still legible over it; at radius 0, no halo at all.

- [ ] **065.16 — the chat's colours, the walked one included.** `chat` keeps covering the whole window, and
      four keys refine it:
      `chat.system`, `chat.private`, `chat.party` and `chat.urgent`, each cascading into `chat` and then into
      `*`. They are the constants `ChatUI` holds one per kind — `(192,192,255)`, `(255,128,128)`,
      `(128,128,255)` and the `urgcols` triple — so a theme paints them apart instead of flattening them,
      which is what a `color` on `chat` alone does. A fifth, `chat.speaker`, takes the colour the client
      **generates** — `HSBtoRGB(colseq + √2 mod 1, 0.5, 1.0)` — as the sequence it is:
      `{generate = {step, saturation, brightness}}`, or a `{palette = {…}}` the theme lists and the client
      cycles. Naming the sequence is what makes it a value; flattening it to one colour stays impossible,
      because telling speakers apart is what it is for.
      *Its suite* installs a different colour on each of the four kinds, asserts each key resolved its own,
      and asserts a fifth kind with no rule of its own still resolves `chat`'s — the cascade between the new
      keys and the old one is the claim. It installs a two-colour `palette` on `chat.speaker` and asserts
      two speakers resolved the two listed colours in order, then a `generate` with a different saturation
      and asserts the resolved sequence changed. It re-asserts that `$col[…]` inside a message still wins
      over the rule, duplicating that check rather than assuming an older suite ran.
      `[manual]`: say something in area chat, take a private message and trigger a system line — expect the
      three in three different colours, a message carrying its own `$col` markup unchanged, and two
      different speakers in the palette's two colours.

- [ ] **065.17 — the client, read back as data.** `sheet:stock()` hands back the whole look this client
      draws with, as a plain table keyed by site, and `sheet:stock(key)` hands back one — art named by
      resource, faces by built-in, colours as numbers, in the very shapes `sheet:load()` takes. Nothing new
      is published for it: every routed site already hands its stock in as the third argument
      (`Fonts.box(scope, this, stock)`, `Fonts.foundry(scope, stock)`), so the registry records what it is
      offered, and a texture is named through `AddonManager.onPicture`, the map `widget:picture()` already
      fills. A key whose stock has not been drawn yet answers nothing, and an art that resolves to no
      resource name says so rather than inventing one.
      *Its suite* opens the windows it means to read, calls `sheet:stock()`, and asserts the table carries
      the keys it opened with the art it knows the client uses. Then the round trip, which is the whole
      claim: serialise it with `hafen.json()`, `sheet:load()` it back, `install()`, and assert every key
      resolves to what `stock()` said — the client's own look, expressed in the vocabulary, with nothing
      left over. It asserts a key nobody has drawn is absent rather than guessed, and `pcall`s
      `sheet:stock("nosuchkey")`.
      `[manual]`: install the catalogue the suite wrote and look at the client — expect it to be
      indistinguishable from the same client with no sheet at all: the window frame's tiled runs, its
      caption plate, the shading down its sides and the foot piece on its left edge, all present.

- [ ] **065.18 — the sweep, and the theme the guide ships.** Discharges the impact set the spec derived —
      every page carrying `pad`, both boundary paragraphs about state and hover, and every "embossed, so
      `color` is inert" sentence, which is revised rather than deleted. Rewrites `guides/theming.md` around
      a **theme file** short enough to read that still names one property of each of the four kinds, plus
      the three lines that write the **whole** catalogue with `sheet:stock()` — the complete look belongs in
      a file the reader generates, not in a page that would blow its ceiling carrying it. Brings
      `docs/client/ui-chrome.md`, `ui-controls.md`, `ui-lists.md` and `text-and-fonts.md` up to what this
      feature read: the ornaments, each control's art, and the boxes the client draws in code rather than
      from a resource.
      *Its suite* loads **the guide's own theme file, verbatim**, and asserts it installed and that each of
      its rules reads back what the page says it does — the page is the subject, and the assertion is that
      the documented block is true. It asserts every key the feature added is a site key by resolving each
      one, so a key documented but never routed fails here rather than in a user's addon.
      `[manual]`: run the client with that theme alone and read the guide top to bottom — expect every block
      to describe what is on screen.
