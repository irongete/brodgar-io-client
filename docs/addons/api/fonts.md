# `hafen.font` — per-addon typography

Get a font into a **private handle** the addon holds — a [built-in](#the-built-ins--hafenfontname) by name or
your own `.ttf` as an [asset](asset.md) — then either draw with it (F2) or name it in a
[**stylesheet**](ui.md#the-stylesheet--restyling-the-client) to restyle the client. There is **no shared
cross-addon registry** — a handle is a value your addon keeps; another addon cannot look it up (no name
collisions, no coupling).

> **A font is a stylesheet property now.** `hafen.font.setFont` / `reset` / `scopes` are **gone** (they read as
> plain `nil`): the surfaces below are restyled with
> [`hafen.ui.skin{ ["window.title"] = { font = h } }`](ui.md#the-stylesheet--restyling-the-client), where the key
> is a [selector](ui.md#selectors--naming-a-widget) rather than a scope enum. `hafen.font` keeps its one job —
> naming an engine font. Everything this page says about *what each surface does under an override* is unchanged;
> only the call that installs one moved.

> **Slice status.** **F1 (shipped):** the handle and the **`"default"`** surface — now the sheet's `*` key
> (the global fallback — most UI text). **F2 (shipped):** applying a font to your **own** drawing — `font=` on
> `hafen.ui.window`/`widget`, `g:text`/`g:atext` with a `{font=…, color=…}` option, and a custom TTF in a
> `$font[…]{…}` rich-text tag. **F3a (shipped):** the **`"window.title"`** scope (window captions).
> **F3b (shipped):** the **`"button"`** scope (button captions). **F3c (shipped):** the **`"textentry"`** scope
> (every text-input field + the console command line) and the **`"label"`** surface (the client's **body text** —
> character-sheet attribute rows, skill/lore/quest/wound list items, and the explicit-foundry labels F1's
> default-`Label` routing left out) are now live. **F3e (shipped):** the **`"heading"`** scope — the embossed
> section headings *inside* a window ("Base Attributes", "Lore & Skills", "Kin", …); a **new scope**, added to the
> enum in this slice. **F3d (shipped):** the **`"menu"`**, **`"tooltip"`** and **`"chat"`** scopes — which
> **completes the UI chrome**. **F4 (shipped):** the two **world** scopes — **`"world.speech"`** (speech bubbles)
> and **`"world.nick"`** (floating kin names) — so **every scope in `scopes()` is now live**. **F5 (shipped):**
> the **per-instance** override — `widget:setFont(h)` on any [Widget](ui.md#the-widget-object) restyles **one** widget
> and its subtree while its siblings keep their font. **The font series is complete.**
> See [`21-fonts.md`](../../../specs/addons/design/21-fonts.md) for the roadmap.

Client-only and cosmetic (**safe-tier — not gated**), like a HUD overlay.

## Where a font comes from — two doors

A font handle comes from exactly one of two places, and which one you use is decided by **who owns the
file**:

```lua
local h = hafen.font("serif")                     -- a BUILT-IN: the client already owns it
local h = hafen.asset("fonts/Inter.ttf")          -- a FILE THIS ADDON SHIPS
```

Neither takes options. **Size and style are a `:derive{…}` away**, never part of the load — see
[the variant](#the-variant--hderiveopts).

### The built-ins — `hafen.font(name)`

`hafen.font` is **callable**: `hafen.font(name)` is the handle for one of the client's four built-in fonts —
`"sans"`, `"serif"`, `"mono"`, `"fraktur"`. They are **engine-owned**, so they are *addressed by name*, not
loaded: interned (`hafen.font("mono") == hafen.font("mono")`), no file, no path, and **no lifetime** — a
built-in carries none of the [asset verbs](asset.md#every-asset) (`:type`/`:path`/`:dispose`). A typo, a
number, or a path raises an error listing the four names and pointing paths at `hafen.asset`.

### Your own `.ttf`/`.otf` — `hafen.asset(path)`

A font file your addon ships is an [**asset**](asset.md), loaded through the same door as an image or a
model: `hafen.asset("fonts/Inter.ttf")`. It is sandboxed (absolute paths and `..` escapes are rejected),
**interned per path** (one parse per file, however many times you call it), and disposed automatically on
reload/disable. Loading also registers the family into the JVM, so `h:family()` resolves in a `$font[…]`
rich-text tag (F2). Being an asset, it *also* answers `:type()`/`:path()`/`:dispose()`.

> `hafen.font.load` is **gone**. A built-in is `hafen.font(name)`; a file is `hafen.asset(path)`; a sized or
> styled variant is `:derive{…}`.

### The variant — `h:derive(opts)`

`opts` (all optional):

| Key | Meaning |
|---|---|
| `size` | logical px (passed through `UI.scale`). Omit ⇒ use the stock size of whatever surface it is applied to. |
| `aa` | antialias on/off. Omit ⇒ inherit the surface's stock setting. |
| `bold` / `italic` | style (baked into the font). |
| `color` | text colour `{r, g, b [, a]}` (0–255) — **for your own drawing only**, see below. |

Either way you hold an opaque **`FontHandle`** (no AWT font object crosses into Lua):

| Method | Returns | Notes |
|---|---|---|
| `h:derive(opts)` | `FontHandle` | a cheap variant with a different `size`/`aa`/`bold`/`italic`/`color`; never mutates `h`. |
| `h:family()` | string | the AWT family name — feed it to a `$font[family, sz]{…}` tag for per-run mixing (F2). |
| `h:size()` | number \| nil | the handle's logical px size (nil if unset). |

A derived handle is a **variant of a font**, not a file: like a built-in, it carries no
`:type`/`:path`/`:dispose`, even when the handle it came from was an asset.

> **`color` is the one option that does not travel.** It applies wherever *you* draw with the handle — `g:text`,
> your own windows and widgets — and is **ignored** when the handle is installed on a client surface, through
> [`hafen.ui.skin`](ui.md#properties) or [`widget:setFont`](#restyle-one-widget--widgetsetfonth-f5). A surface's
> colour is a [sheet property](ui.md#color--a-surfaces-colour-is-the-sheets), stated where you can read it, not a
> value hidden inside a font handle. `size`, `aa`, `bold` and `italic` travel everywhere.

## Draw with it — your own widgets (F2)

Applying a font to your **own** drawing is **fully isolated**: it touches only your widgets' pixels, so there
is no conflict and nothing to revert — the stock UI and every other addon are untouched.

### `font =` on a window / widget — the default for its draws

```lua
local h = hafen.font("serif"):derive{ size = 12 }
hafen.ui.window{ title = "Mine", size = {200, 120}, font = h, onDraw = function(g, w, h)
  g:text("this text is in my font", 6, 6)   -- no per-call opts => uses the widget's font=
end }
hafen.ui.widget{ ..., font = h }             -- same, for a bare widget
```

`font =` sets the **default font** for every `g:text`/`g:atext` the widget draws that gives no per-call font.
(It does **not** restyle the window's *title bar* — that is the `"window.title"` scope, F3.)

### `g:text` / `g:atext` — a per-call `{font, color}` option

```lua
g:text(str, x, y [, { font = h, color = {r,g,b[,a]} }])
g:atext(str, x, y, ax, ay [, { font = h, color = {r,g,b[,a]} }])
```

- **`font`** — a `FontHandle`; render this one call in that font (overrides the widget `font=` default for the
  call). Omit ⇒ the widget default, else the client stock.
- **`color`** — `{r,g,b[,a]}` (0–255); tint the glyphs (composes with `g:color` exactly like a `g:color` call
  around it). Omit ⇒ white glyphs tinted by the current `g:color` (the stock behaviour — unchanged).

Coordinates stay **positional** (`x, y`) — the same as every other `g:` call.

> **The rendered text is cached** (per addon, keyed by the string *and* the handle), so redrawing the same
> string in the same font every frame rasterises it once. **`color` is not part of the key** — it is a tint over
> the same raster, so one string in two colours is one cache entry and animating a colour is free. Installing,
> moving or resetting an override (below, and F5) invalidates on the next frame. See
> [text is cached across frames](ui.md#text-is-cached-across-frames).

### Mix fonts on one line — the `$font` rich-text tag

`g:text`/`g:atext` interpret **rich-text markup**, so you can mix fonts (and styles/colours) inside a single
string. Feed a handle's `h:family()` to the engine's existing `$font[family, size]{…}` tag:

```lua
g:text(("$font[%s,16]{Fancy} normal"):format(h:family()), 6, 6)   -- two fonts, one line
g:text("$col[235,180,80]{$b{bold} orange} plain", 6, 26)          -- $col / $b / $i / $u / $size too
```

This works because loading a `.ttf` [asset](asset.md) registers its family into the JVM — **zero engine markup
change**. Plain text with no `$` and no `font=` takes the exact stock render path (no behaviour change for
existing addons); malformed markup falls back to drawing the literal string (it never throws).

## Restyle a global surface — the stylesheet

```lua
hafen.ui.skin{ ["window.title"] = { font = h } }   -- install THIS addon's sheet (replacing its previous one)
hafen.ui.skin(nil)                                 -- drop it (every surface it styled falls back)
```

A sheet is **owned**: it is dropped **automatically** on your addon's `:reload`/disable (the same owned-resource
model as [`widget:replace`](ui.md#replacing-a-native-window), hooks, and overlays), so the stock UI is always
restorable. The change is **live** — most existing text re-renders on the spot. The call, the one-sheet-per-addon
rule and the conflict model are documented in full under
[`hafen.ui.skin`](ui.md#the-stylesheet--restyling-the-client); what follows is **what each surface does** when a
font lands on it.

### Site keys

A **site key** is a bare selector naming a place the client draws. All of them are live:

| Site key | Client surface | Slice |
|---|---|---|
| `*` | global fallback — most UI text (`Text.std` / `Text.render` / `RichText.render` / default `Label`) | **F1 (live)** |
| `"window.title"` | window captions | **F3a (live)** |
| `"heading"` | in-window section headings (embossed fraktur) | **F3e (live)** |
| `"button"` | button captions | **F3b (live)** |
| `"label"` | body text — attribute rows, list items, explicit-foundry labels | **F3c (live)** |
| `"tooltip"` | every tooltip — items, buffs, meters, craft, minimap, action menu, `settip`, pagina, food/study | **F3d (live)** |
| `"menu"` | flower-menu petals + the action-menu keybind letters | **F3d (live)** |
| `"chat"` | the chat window — messages, channel tabs, the typed line | **F3d (live)** |
| `"textentry"` | text-entry fields (+ the console command line) | **F3c (live)** |
| `"world.nick"` | floating kin names over characters (kin-list members) | **F4 (live)** |
| `"world.speech"` | speech bubbles over talking characters | **F4 (live)** |

> **These are the same names as the selector roles**, because there is one vocabulary — `hafen.ui.all("button")`
> finds the widgets whose captions `["button"] = {font=h}` restyles ([roles](ui.md#roles)). The overlap is not
> 1:1, and the difference is the point: a site key names a **render site**, a role names a **widget**. The
> global fallback is `*` on both sides; `window` and `inventory` are roles with **no site**, so as sheet keys
> they are [tree keys](ui.md#tree-keys-are-accepted-and-do-nothing-yet) and do nothing yet; and five site keys —
> `window.title`, `heading`, `tooltip`, `world.nick`, `world.speech` — are valid selectors that **classify no
> widget**, because a caption is drawn by its window's decoration, a tooltip is painted rather than placed, and
> the world sites live over the 3D view. Restyling them works; selecting them finds nothing, which is the honest
> answer.

`*` is the broad hammer: it **cascades** to every routed surface with no more-specific rule — so `["*"]` alone
changes everything, while another key refines any one surface. The resolution order is **most-specific first**:
[per-instance](#restyle-one-widget--widgetsetfonth-f5) → the site rule → the `*` rule → stock.

```lua
hafen.ui.skin{ ["*"] = { font = h } }                            -- everything routed (incl. captions + buttons)
hafen.ui.skin{ ["*"] = { font = h }, ["button"] = { font = h2 } } -- ...but buttons use h2 (a refinement)
hafen.ui.skin{ ["*"] = { font = h } }                            -- buttons fall back to the `*` cascade again
```

**Notes on `"button"` (F3b).** It covers the captions of the client's standard buttons — the Options window, the
character-sheet / craft / build buttons, tab buttons, key-bind buttons, `wrapped` (multi-line) buttons, and any
caption a button changes at runtime (e.g. a key-bind button showing `Click element...`). Buttons rasterize their
caption into an image, so each **visible** button re-renders itself on the frame after the override moves —
open a window with buttons while toggling and you see it live. **Tip:** the stock button caption font is
**bold serif 12** (each scope has its own stock — `"window.title"` is fraktur), so overriding `"button"` with a
serif handle at size 12 is installed correctly yet looks like nothing happened; pick a contrasting family when
you want the change to be visible. Two surfaces are deliberately *not* in this
scope: a button whose face was supplied by the client as a ready-made image or pre-rendered text (icon buttons
like `IButton`, and the character-selection list entries), and button-shaped widgets that are not buttons at all
(checkboxes, radio labels) — those are not button captions and keep their own foundry.

**Notes on `"textentry"` (F3c).** It covers **both** of the client's text-input surfaces: every editable field
(the chat input, search boxes, the login name/password fields, name-a-save fields, …) **and** the console
command line — the `:` prompt, so `:lua` and your own [`hafen.slash`](console.md) commands are typed in your font
too. Each field drops its cached line when the override moves, so the change is live on the next frame, and
selection/caret positions follow the new glyph advances automatically. **Geometry caveat:** a field's *height*
comes from its background texture, not from the font — a much larger size is drawn but vertically clipped. Stay
near the stock **serif 12** (the command line's stock is **mono 12**, wheat-coloured, and an override with no
explicit colour inherits that per-site colour) unless you want the clipping.

**Notes on `"heading"` (F3e).** The big embossed fraktur captions **inside** a window — "Base Attributes",
"Food Satiations", "Abilities", "Study Report", "Lore & Skills", "Entries", "Quest Log", "Health & Wounds",
"Martial Arts & Combat Schools", "Kin", the credo group captions ("Pursuing" / "Credos Available" / "Credos
Acquired"), a village name, and the quest-completed banner. Deliberately **its own scope**: a heading is neither
the window's title bar (`"window.title"`) nor body text (`"label"`), so you can restyle one without the others.
Two stock sizes ride this scope — 25 px window headings and 18 px group captions — and an override with no `size=`
keeps each of them, so nothing around a heading moves. Headings are an embossed **furnace** baked into an image, so
the client rebuilds the furnace and re-renders each **visible** heading on the frame after the override moves: keep
a window open while toggling and you see it change.

**Notes on `"label"` (F3c).** This is the client's **body text**: everything it renders with its own hand-picked
foundry, which until F3c was frozen at whatever font it was constructed with. F1 had already routed the *default*
labels through `"default"`; `"label"` covers the rest:

| Surface | Where you see it |
|---|---|
| Attribute rows (name + value) | character sheet — **Base** and **Study** tabs |
| List items (text + icon rows) | **Skills & Lore**, **Quests**, **Wounds**, combat maneuvers, radar icon settings |
| Menu-search results | the search box results list |
| Explicit-foundry labels | credo `Level:`/`Quest:` lines, wound quality, the combat-schools counter, the login screen, village name |

Each site re-renders **lazily, on the next frame it draws** (so a mass restyle never stalls a frame) and keeps its
own colour; labels also keep their **wrap width**. Because an override **inherits each site's stock size** unless
you pass `size=`, `["label"] = { font = h }` swaps the *family* everywhere while an 18 px row stays 18 px — the safe way
to restyle body text without moving layouts. **Two geometry caveats if you do pass `size=`:** list/attribute **row
heights** were computed from the stock font at construction, so taller glyphs clip; and a `Label` resizes itself to
its text while its container does not re-lay-out around it. Deliberately *not* in this scope: a caller-supplied
pre-rendered `Text` (e.g. the italic "Unused save" placeholder) and text a widget rasterizes into its own face —
those are not body text.

**Notes on `"tooltip"` (F3d).** Every tooltip the client pops up. The bulk of it is the client's tooltip
**engine** (`ItemInfo`), which composes the tip of an **inventory item**, a **buff**, a **HUD meter**, a **craft**
recipe input/output, a **minimap** marker or object, a **character-sheet attribute row** and an **action-menu**
icon — hovering an inventory item is the quickest way to see the override. On top of that:

| Surface | Where you see it |
|---|---|
| Plain string tips | rendered at *display* time, so even the tip already under your cursor re-renders |
| `settip` tips | any widget's own tooltip, including its `Keyboard shortcut: …` tail |
| Resource pagina tips | the long action / item descriptions |
| Food & study tips | food event points / satiations, curiosity study times — the tooltips you read most |
| Terrain, minimap, keybind help, combat action tips | the remaining engine-side tips |
| Equipment empty-slot names, skill / credo list tips | pre-rendered at construction, re-rendered on the change |
| Quality / wear / armour / gilding / attribute rows | drawn by code that ships **inside the game resources** — reached anyway (see below) |
| Gilding chance / `Gildable (6/6)` | same, and with their own private font — also reached, keeping their own size |

Markup inside a tooltip keeps working over your override (`$b`, `$col`, `$img`, …): the override swaps the font
**family + size** rather than the whole font attribute, precisely so the client's own markup still applies. Each
cached tooltip image is re-composed lazily, the next time you hover it, so a mass restyle never stalls a frame. A
tooltip sizes its box around its text, so an explicit `size=` is **safe** here (unlike a text field or a list row).

Some tooltip rows — the `Quality:` line, `Wear`, `Armor class`, `Gilding`, `Gildable (6/6)`, the `+5` attribute
rows — are drawn by code the **server ships inside the game resources**, not by the client. `"tooltip"` reaches
them anyway: while the client composes a tooltip, that whole composition is *declared* to be tooltip text, so any
text rendered inside it follows the scope — even from code that knows nothing about the font system, and even when
that code picked its own font (those rows keep their own **size**, so a small italic line stays small and italic in
your family). Nothing outside a tooltip composition is affected. One nuance: with no `size=` the point size is
preserved exactly, but ascent/descent are per-family metrics, so a family swap can still move a row by a pixel.

**One row needed more than that.** A resource-shipped class that rasterises text into a `static` field **when the
class loads** cannot be restyled afterwards from any font system — the JVM never re-runs a static initialiser. That
was the **`Gilding:`** heading (`ui/tt/slots-alt`), so the client now carries a **local copy of that resource's code**
(the engine's own `doc/resource-code` mechanism: `get-code` + `@FromResource`, version-matched) which renders the
heading on demand instead. Nothing to do on the addon side — it simply follows like every other row. The one caveat
is upstream: if the game ships a new version of that resource, the local copy steps aside (a line in the client log)
and the heading returns to stock until the copy is refreshed.

**Notes on `"menu"` (F3d).** Two surfaces: the **petal captions** of a flower menu (the ring of options a
right-click opens) and the **keybind letters** the action-menu grid paints over its buttons. A petal re-renders
*and* re-sizes around its own centre when the override moves, so a menu that is already open restyles in place
without drifting off its ring — and, like a tooltip, a petal sizes itself around its caption, so a bigger `size=`
is safe.

**Notes on `"chat"` (F3d).** The whole chat window: every **message** line (area / party / private / system), the
**channel tabs** down its side, and the **quick line** you type over the map. Only the messages currently
**visible** re-render (the scrollback re-renders as you scroll it into view), and each message's height is
re-measured, so the log re-flows correctly under a bigger font. URLs stay clickable — the override keeps chat's own
link parser. The typed quick line belongs to this scope, not to `"textentry"`: it lives in the chat window and is
built from the chat's own recipe. Minor caveat: the channel-tab **truncation width** was measured from the stock
font once, so a much wider font can shorten a long channel name slightly early.

**Notes on the world scopes (F4).** `"world.speech"` is the speech bubble that pops up over a character's head
when they talk in area chat — your own included, so it is the easiest scope to check: say something and look. The
bubble measures its frame around the text on every frame, so a large size is completely safe here (unlike a text
field, whose height is fixed by its background texture); a bubble already on screen re-renders on its next frame.
`"world.nick"` is the floating name drawn over characters on your **kin (buddy) list**, in their kin-group
colour — the colour is per-render and stays untouched, only the font changes, and the label re-centres itself over
the character at the new size. You need a **kin visible on screen** to observe it: with nobody on your list
nearby there is no label to restyle. Any other world label composed by the same client mechanism (parts
contributed by game resources alongside the kin name) follows `"world.nick"` too. Neither world scope follows a
tooltip composition, and both fall back to the `"default"` cascade when unset, like every other scope.

## Restyle ONE widget — `widget:setFont(h)` (F5)

A scope restyles a *family* of surfaces across the whole client. To restyle **one** widget instead, call
`setFont` on its [Widget object](ui.md#the-widget-object) — from
[`hafen.ui(selector)`](ui.md#selectors--naming-a-widget) / `hafen.ui.node(id)` / `hafen.ui.at(x, y)`:

```lua
local n = hafen.ui.at(hafen.ui.mouse().x, hafen.ui.mouse().y)   -- the widget under the cursor
n:setFont(h)        -- this widget and everything inside it -> h; its SIBLINGS keep their font
n:resetFont()       -- drop it again
```

| Call | Returns | Description |
|---|---|---|
| `widget:setFont(h)` | (self) | install **your** per-instance override on that widget |
| `widget:resetFont()` | (self) | drop **your** per-instance override on that widget |

- **It covers the whole subtree.** The client draws parents before children, so an override on a window reaches
  its caption, its labels, its button captions, its list rows — and any widget created inside it *later*. A child
  with an override of its own wins inside itself (innermost first).
- **It is the top of the chain**: per-instance → scope → `"default"` → stock. Inside an overridden widget the
  handle wins whatever scope the text belongs to, so it also catches text drawn by the game's **own resource
  code** (the `.res` tooltip rows) — the one place a scope override could never reach.
- **The handle's `color` does not apply here.** A native widget is a client surface, and a surface's colour comes
  from a [sheet rule](ui.md#color--a-surfaces-colour-is-the-sheets); `setFont` takes the family, size and
  antialiasing only. Colouring *one* widget is what C1b's `widget:skin{…}` will be for.
- **Sizes are inherited unless your handle carries one**, exactly as with a scope: each site keeps its own stock
  size, so the layout does not move. If you *do* pass `size=`, remember the geometry caveats of the scopes it
  overlaps (a text field's height is fixed by its background texture, list-row heights were measured at
  construction) — a per-instance override is not a layout engine.
- **Owned and short-lived.** The override is tagged with your addon and reverted on `:reload`/disable like every
  other one, and it is held **weakly against the widget**: when that window closes, the override goes with it (a
  stashed node reports `nil` from every accessor and `:resetFont()` becomes a no-op — never an error).
- **Some windows have no text to restyle.** An Inventory or Equipment window contains item *icons* (`WItem`s) —
  its only text is the caption, so an override there shows up on the title bar alone and looks like it did nothing.
  Pick a text-rich window (the Character Sheet, Options) when you want to see the effect.
- `hafen.ui():setFont(h)` works and covers the entire client, but that is what a sheet's `["*"]` rule is for —
  prefer the sheet when you mean "everything".
- A node is not owned (it is a lazy handle), so **keep the node** if you intend to reset the override later —
  or just re-find the widget when you need it.

### Conflict model (one intrinsic limit)

Over your **own** drawing (F2): isolated, unlimited freedom. Over a **global** surface: it is shared client
state, so each scope holds a **stack of overrides, each tagged with its owning addon — the last applied wins**.
On teardown an addon's entries are pulled from every scope and the surface falls back to the next owner beneath
(or stock). Two addons cannot own the same surface at once; the outcome is deterministic and per-owner
reversible (this mirrors [`widget:replace`](ui.md#replacing-a-native-window)). Per-instance overrides (F5) follow the same rules, per
widget.

## Example

```lua
local h
hafen.events.on("OnLoad", function()
  h = hafen.asset("fonts/Inter.ttf"):derive{ size = 11 }   -- or hafen.font("serif"):derive{ size = 11 }
end)

hafen.slash.register("bigserif", function()
  hafen.ui.skin{ ["*"] = { font = h } }   -- most UI text becomes serif, live
end)
-- reverted automatically when the addon is reloaded or disabled;
-- or explicitly: hafen.ui.skin(nil)
```

## See also

- [`hafen.asset`](asset.md) — the one door for a `.ttf`/`.otf` your addon ships (and every other local file).
- [`hafen.ui`](ui.md) — the `font=` widget option + the `g:text` draw wrapper take a handle (F2), and a
  [Widget](ui.md#the-widget-object) carries `:setFont`/`:resetFont` (F5). The
  [selector roles](ui.md#roles) are these same scope names, from the other side (widget, not render site). Drawn text is
  [cached across frames](ui.md#text-is-cached-across-frames) per `(string, handle)`; an override change
  invalidates it on the next frame.
- [`hafen.client`](client.md#textcache) — `profiling():textcache()` reports that cache: entries, texture bytes,
  hit rate.
- [conventions](conventions.md) — owned resources & teardown, the safe-tier vs gated split.
