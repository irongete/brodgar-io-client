# Text rendering and fonts

> Foundries, the named surfaces that bake them, and the custom-font paths.

| What | Where |
|---|---|
| Foundry (font+size+colour+aa) | `Text.Foundry`; `renderwrap` builds a `RichText.Foundry` |
| **Global default** | `Text.std` = `new Foundry(sans,10)` (`public static final`); `Text.render(…)` statics; `Label` default |
| Built-in fonts | `Text.sans/serif/mono/fraktur`. **Only two of the four name an AWT logical family.** `serif` and `mono` ask for `"Serif"`/`"Monospaced"`, which exist; `sans` asks for `"Sans"`, which does not, so it resolves to `Dialog` and `getFamily()` on it is indistinguishable from a `Dialog` font's. `getName()` keeps the string the `Font` was constructed with and **survives every `deriveFont`**, so it, not the family, is what tells the four apart in a font some site has already derived. `fraktur` is a resource font and answers its own family |
| Window titles | `Window.DefaultDeco.cf/ncf` = `new Text.Foundry(Text.fraktur,15).aa(true)` |
| **Speech bubbles** | `Speaking` — cached `Text` (ctor + `update`), frame measured from `text.sz()` in `draw`; stock = `Text.std`. **`draw` blits the finished raster under `g.chcolor(Color.BLACK)`**, so any colour baked into it — the `Foundry.fixcol` a rule sets, or the `Color` passed to `render` — is multiplied to black on the way to the screen. A colour cannot reach this surface without moving that `chcolor` |
| **Floating kin names** | **NOT in the fork** — published code in the `ui/obj/buddy` resource (`haven.KinInfo` is gone; `OCache.OD_BUDDY` commented `-- Removed`). Adopted with `get-code` → `src/haven/res/ui/obj/buddy/{Buddy,Info,InfoPart}.java`: shared foundry `InfoPart.fnd` + `rendertext`, composed `Tex` invalidated by `Info.dirty()` |
| **Per-run markup** (`$font`) | `RichText` `$font` tag — resolves by **AWT family name** (`TextAttribute.FAMILY`); a custom TTF needs `GraphicsEnvironment.registerFont` at load |
| DPI sizing | `UI.scale(float)` — every produced size passes through it |
| ~81 baked `Foundry` sites | across 36 files (`Label`, `ChatUI`, `SListMenu`, `CharWnd`, `Button`, `FlowerMenu`, …) — route **per slice**, never all at once |
| Custom-TTF load | `Font.createFont(TRUETYPE_FONT, file)` (built-ins from `Text.*`); `Resource.Font` is the resource-backed path |
| **`Text` → GPU** | `Text.tex()` lazily wraps the `BufferedImage` in a `TexI` and **memoises it**; `Text.dispose()` forwards to it. A `Text` is therefore two allocations: the AWT raster (kept) and the texture |
| **The immediate-mode blit** | `GOut.atext` = `Text.render` → `tex()` → `aimage` → `dispose()`, **all four per call, every frame**; `GOut.text` is `atext(…,0,0)`. Drop the `dispose()` and you have the `Label` pattern |
| **`TexI` GL side** | `st()` uploads **lazily on first render** (so building a `TexI` is free of GL); `dispose()` drops the `ColorTex` and nulls it — but `st()` would silently **re-upload**, so a double-free shows up as a slow leak, never a crash. Size is `Tex.nextp2`-rounded (`tdim`): bytes = `4·nextp2(w)·nextp2(h)`, not `4·w·h` |
| **A surface that draws what the USER TYPED** | `Fonts.enterTyped(scope)` — `enter` plus a mark on that frame, and `display` refuses a marked frame outright, an entry under `Fonts.EVERY` included. It exists for the one site where the two halves want different answers: the chat's quick line (`ChatUI.drawsmall`) is *styled* as `chat`, so it cannot declare `textentry` the way `TextEntry.draw` and `ConsoleHost.drawcmd` do. The mark is per **frame** — `exit()` takes it down with the scope it belongs to, and a scope entered inside a marked one is not itself marked |
| **The composition scope** | `Fonts.enter(scope)`/`Fonts.exit()` push a per-thread stack a routed site declares itself with; `Fonts.scope()` is the innermost one else `"default"` (what the generic `Text.render`/`RichText.render` statics resolve), and `Fonts.dynamic()` is the innermost one else **null**, which is the form `Text.Foundry.resolved()` and its `RichText` twin ask — that is how a foundry the fork cannot route, a `.res`'s own private one, still resolves. `enter` early-outs while `Fonts` is inactive AND the stack is empty, so the skip can only be taken outermost and an unmatched `exit()` finds an empty stack |
| **What a string is DISPLAYED as** | `Fonts.display(scope, text)`, called from `Text.Foundry.render(String, Color)` after `resolved()` has delegated — the last thing that happens to a string before `g.drawString`. It walks a volatile `Fonts.Catalogue[]` (top first), so `Text.text` is the string that was **drawn**, not the one the site was handed. `Fonts.isDisplayScope`/`displayScopes` are which scopes may be keyed; `Fonts.EVERY` is `"*"` |
| **...and its rich twin** | `RichText.Foundry.render(Document, int)`, the same way and in the same place — what a `RichText.Foundry` site (chat, both tooltip flavours) goes through. What it asks about is `Document.text`, i.e. **the marked-up source**, because a run is a fragment nothing could be keyed on; so `RichText.text` is the document that was drawn. `RichText.Foundry.nodisplay` turns it off for one foundry and rides along to the twin `resolved()` derives |
| **The wrapped path is displayed one level up** | `Text.Foundry.renderwrap` calls `Fonts.display` **itself**, before `RichText.Parser.quote` and before it wraps the string in `$col[…]{…}` — and marks its private `wfnd` `nodisplay`, since what that foundry would be asked about is the caption already quoted and wrapped. So a wrapped caption is keyed on the caption |
| **Which sites declare a scope** | The `Fonts.enter`/`exit` pair, one per routed text surface: `Window.DefaultDeco.checkcap` (`window.title`), `CharWnd.Heading` + `GridList.Group.rname` + `QuestWnd` (`heading`, through `Fonts.render(scope, furnace, text)`), `Widget.PaginaTip.get` + `Widget.KeyboundTip.get` + `UILoop`'s plain-string tip + `ItemInfo.longtip`/`shorttip`/`buildinfo`/`MenuGrid.PagButton.rendertt` (`tooltip`), `FlowerMenu.Petal.render` (`menu`), `ChatUI.Channel.RenderedMessage.text` (the line's own `Message.scope()`), `ChatUI.Selector.DarkChannel.rname` and `ChatUI.drawsmall`'s quick line (`chat`, the last through `enterTyped`), `Speaking.render` (`world.speech`), `res/ui/obj/buddy/InfoPart.rendertext` (`world.nick`), `TextEntry.draw` and `ConsoleHost.drawcmd` (`textentry`, which `display` refuses), `Button.render` (`button`), `Label.mktext` (its own) |
| **The scope is the SITE's, not the foundry's** | `display` reads `Fonts.scope()`, so a site that hands its foundry to someone else — a heading's is a `Supplier<Text.Furnace>` four classes ask for — owes the pair even though its foundry already knows its scope. `Fonts.render(scope, furnace, text)` is that pair written once, for the one-call case |
| **The scope vocabulary** | `Fonts.SCOPES` — the names a stylesheet key may be a **site** key for, `default` being the selector `*`'s twin; `Fonts.isScope` is the test. Some (`button`, `label`, `textentry`, `chat`, `menu`) are also `Selector.WIDGET_ROLES`; the rest name a render site nothing is ever classified as (`Selector.SITE_ROLES`); and the widget roles `window`/`inventory` have **no** scope, so a bare `window` key is a *tree* key |
| **What a site declares its own look to be** | `Fonts.stock(scope, prop, value…)` writes into the private `Fonts.stocks` map; `Fonts.stockOf(scope)` reads one site and `Fonts.stocked()` lists every scope that has declared anything, in `SCOPES` order. The property is spelled as a rule spells it (`"font"`, `"bg"`, `"border"`, `"padding"`, …) and the value is what the site holds — a `Text.Foundry`, a `Color`, a pair of `Coord`s, one or more `Fonts.Piece`s. Idempotent: an unchanged declaration compares equal and writes nothing, so a site may declare from inside its own draw |
| **Where COLOUR enters a render** | Three different layers — see the gotcha below. `Text.Foundry.defcol` (the default), the `Color` argument of `render(text, c)`/`renderwrap` (**baked into the raster** by `g.setColor(c)`), and a per-render `TextAttribute.FOREGROUND` extra on a `RichText.Foundry.render(…)` call |

## The furnace stack

A `Text.Furnace` is "render this string"; `Text.Forge` narrows it to one that also answers `height()`,
`strsize()` and a `Slug`. `Text.Foundry` is the only leaf that owns a `Font`; everything else is a
**decorator** — `Text.OffsetForge` wraps a backing `Forge` and post-processes the raster, reporting the
room it took as `tloff()`/`broff()`.

| Decorator | Does | Costs |
|---|---|---|
| `PUtils.TexFurn(bk, BufferedImage)` | `PUtils.tilemod` — tiles the image through the glyph raster **in place**, so the mask is what you see and the foundry's colour is gone | no growth (`tloff`/`broff` are `Coord.z`) |
| `PUtils.BlurFurn(bk, grad, brad, Color)` | `PUtils.blurmask2` — a coloured halo behind the glyphs | grows the raster by `grad + brad` on every side, which is `tloff`/`broff` and moves what the site lays out around it |

The two are always stacked the same way — `BlurFurn(TexFurn(foundry, tex), …)` — at four places, and each
rebuilds its statics on a `Fonts.gen()` compare. The blur's `grad`/`brad`/`col` are per site and none of them
is shared:

| Site | Stock foundry | Texture | Blur |
|---|---|---|---|
| `Window.DefaultDeco.checktitlefont` → `cf`, `ncf` | `DefaultDeco.titlefnd` (fraktur 15) | `Window.ctex` = `Resource.loadsimg("gfx/hud/fonttex")` | `UI.rscale(0.75)`, `UI.rscale(1.0)`; `Color(96,96,0)` focused, `Color.BLACK` not |
| `CharWnd.checkcapfont` → `bcatf`, `bfailf` | `CharWnd.capfnd` (fraktur 25) | `Window.ctex`, and `gfx/hud/fontred` for the failed twin | `UI.scale(3)`, `UI.scale(2)`, `Color(96,48,0)` |
| `GridList.dcatfont` → `bdcatf` | `GridList.dcatfnd` (fraktur 18) | `Window.ctex` | `2`, `1`, `Color(96,48,0)` — **unscaled literals**, unlike the three above |
| `Button.checkfont` → `bnf` | `Button.tf` (bold serif 12) | `Window.ctex` | `UI.rscale(0.75)` twice, `Color(80,40,0)` |

`Charlist`, `Fightsess`, `MapView` and `QuestWnd` build furnaces of their own from the same two classes
and are **not** routed.

## The chat's colours, one per kind

There is no table of them anywhere: a chat line's colour is a literal at the place the line is **built**,
and they live in three classes. Nothing distinguishes the kinds at the render — every one of them is a
`SimpleMessage` or a `NamedMessage` carrying a `Color` — so the only way to tell a System line from a
private one is **which channel it was appended to**, or which `Message` subclass built it.

| Kind | Where the colour is | Value |
|---|---|---|
| System, informational | `UI.Notice.color()`'s default, via `GameUI.msg` → `syslog.append` | `Color.WHITE` |
| System, error | `UI.ErrorMessage.color` (`defcolor()` overrides `SimpleMessage`'s) | `(192, 0, 0)` |
| Your own line | `ChatUI.MultiChat.MyMessage` ctor | `(192, 192, 255)` |
| Private, received | `ChatUI.PrivChat.InMessage` ctor | `(255, 128, 128)` |
| Private, sent | `ChatUI.PrivChat.OutMessage` ctor | `(128, 128, 255)` |
| A speaker | `ChatUI.MultiChat.nextcol` — `Color.HSBtoRGB(colseq = (colseq + √2) % 1, 0.5f, 1.0f)`, memoised per sender id in `pc` | walked |
| A party speaker | `ChatUI.PartyChat.uimsg` — `Party.Member.col` blended with white | server-assigned |
| Unread urgency | `ChatUI.urgcols` (the toggle button's glow, `GameUI`) and `ChatUI.Selector.uc` (the tab, `namedeco`) | two arrays, both index-0-is-not-a-level |

⚠️ **The two urgency arrays are not the same array.** `urgcols[0]` is `null` — no glow — while `uc[0]` is
`(80, 40, 0)`, the resting tab colour. Levels 1–3 agree. Anything routing "the urgency colour" has to take
each site's own array as the fallback rather than assume one.

⚠️ **`MyMessage` is `MultiChat`'s, so the Party channel has it too** (`PartyChat extends MultiChat`): your
own party line is `(192, 192, 255)`, not a party colour. And `PrivChat`'s error path builds a bare
`SimpleMessage(err, Color.RED)` — a third red, unrelated to `ErrorMessage`'s.

## Gotchas

- A foundry is captured at construction: a site that caches its `Text` must be told to rebuild
  (generation counter) — changing the provider alone does nothing.
- **`Fonts.gen()` is not frame-global.** While a per-widget frame is open (`Widget.draw`'s child loop
  opens one around any widget a style resolves for) it XORs in that style's `Spec.stamp`, so it differs
  *between draw sites within one frame*. Fine for the `gen != mygen` compare each site does on its own
  `Text`; **wrong as a global "clear everything" trigger** for a cache shared by several sites, which
  would then clear on every alternation. Use it as a key component there. The frame opens for any widget
  a **stylesheet tree rule** matches, not only for one an addon named by hand — so assume any widget may
  be drawing under a stamped generation.
- Some text surfaces live in published `.res` code, not in the fork — check before assuming a
  class exists (see the kin-names row).
- **A foundry's `defcol` is almost never what you see**: nearly every site passes its colour *per
  render* — `Label` its `col`, tooltips `Text.white`, `ChatUI` the speaker's as a `FOREGROUND` extra
  (`SimpleMessage.render`, `MultiChat.Rendered.get`).
  And it is baked into the AWT raster: a rendered `Text` cannot be re-tinted, only a *white* glyph
  tex can (the `GOut` draw colour — the `g:text` path).
- **`$col[…]` markup vs a `FOREGROUND` extra**: both land as a foreground attribute on a run, but the
  first comes from the *string* and the second from the *call site* — opposite precedence against an
  override. Check which one you are looking at.
- **`TexFurn` mutates the slug it is given** (`tilemod(text.img.getRaster(), …)`) and hands the same
  `BufferedImage` back. It is the last word on colour for anything it wraps: `Foundry.defcol`, a `Color`
  passed to `render`, and `Foundry.fixcol` alike are all overwritten. Removing it from the stack is the
  only way a colour survives to the screen on one of the four sites above.
- **When a site declares its stock is the site's own business, and it is not one moment.** Three shapes
  are in use: a `static {}` block (`ChatUI`, so `chat.speaker` and `chat.urgent` are declared before the
  chat has rendered a line — deliberately, since neither is ever drawn *from*), a once-guarded call made
  from a draw (`Window.stockdecl`, behind its own `stockdone`), and a plain per-draw call
  (`Button.checkfont`, `CheckBox.draw`, `Inventory.draw` → `stocksq`). So what the registry holds is what
  the client has **drawn**, not what it can draw, and `Fonts.stocked()` grows as a session goes on. A
  surface built more than one way — a `CheckBox` is large or small and the two wear different art —
  leaves whichever was drawn last.
- **A guard that compares against `Text.text` compares against the DISPLAYED string.** `Label.settext`
  compares `Label.texts` instead — the caption the widget was written — because a catalogue makes the two
  different strings, and an unchanged write measured against `Text.text` stops short-circuiting. Any site
  that caches a `Text` and asks "is this still the same caption" has the same choice, and the site's own
  field is the answer, and every site that cached one keeps it: `Window.DefaultDeco.capsrc`, `CheckBox.lbls`,
  `BuddyWnd.Buddy.rnamesrc`, `Fightsess.acttipsrc`, `ILabel.texts`, `SListWidget.TextItem`/`IconText`'s
  `textsrc`. `ChatUI.Selector.DarkChannel.rname` needs none — `namedeco` rebuilds the `Text` around the
  channel's own name, so what it holds is the source already.
- ⚠️ **An ellipsis measured on the raster must be cut out of the raster's string.** `charat(x)` indexes the
  string that was **drawn**, so taking the substring out of the source indexes one string with an offset
  measured in another — and a display string longer than the English runs past the end. `Foundry.ellipsize`,
  `SListWidget`'s two row classes and `ChatUI.Selector.DarkChannel.rname` all cut `Text.text` for this.
- ⚠️ **`ChatUI.drawsmall`'s quick-line cache never matched at all.** Its guard compared the `ReadLine`
  buffer (`lneq`) against `rqline.text`, which carries the `"Area> "` channel prompt as well — so the
  typed line was re-rendered, and re-textured, on every frame the quick line was open. It keeps an `rqsrc`
  of the composed string now. `ConsoleHost.drawcmd` has the identical shape and compares `cmdtextf`, its own
  composed field, so that one is right.
- **`Foundry.ellipsize` cuts the raster it just made**, so the substring is taken out of `full.text` rather
  than out of the string it was handed: the two are different strings once a catalogue is installed, and a
  longer display string would index past the end of the shorter English.
- **`Text.Foundry` is both leaf and `Forge`**, so `new TexFurn(foundry, tex)` and
  `new TexFurn(otherFurn, tex)` compile identically — the `Text.Furnace` overloads of both decorators are
  `@Deprecated` and exist only for that ambiguity. Prefer the `Forge` one.
