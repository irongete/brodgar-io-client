# `hafen.font` — per-addon typography

Load a font into a **private handle** the addon holds, then either draw with it (F2) or install it as an
**owned override** on a named client surface. There is **no shared cross-addon registry** — a handle is a value
your addon keeps; another addon cannot look it up (no name collisions, no coupling).

> **Slice status.** **F1 (shipped):** `load` / `setFont` / `reset` / `scopes`, and the **`"default"`** scope
> (the global fallback — most UI text). **F2 (shipped):** applying a font to your **own** drawing — `font=` on
> `hafen.ui.window`/`widget`, `g:text`/`g:atext` with a `{font=…, color=…}` option, and a custom TTF in a
> `$font[…]{…}` rich-text tag. **F3a (shipped):** the **`"window.title"`** scope (window captions).
> **F3b (shipped):** the **`"button"`** scope (button captions). **F3c (shipped):** the **`"textentry"`** scope
> (every text-input field + the console command line) and the **`"label"`** scope (the client's **body text** —
> character-sheet attribute rows, skill/lore/quest/wound list items, and the explicit-foundry labels F1's
> default-`Label` routing left out) are now live. **F3e (shipped):** the **`"heading"`** scope — the embossed
> section headings *inside* a window ("Base Attributes", "Lore & Skills", "Kin", …); a **new scope**, added to the
> enum in this slice. **F3d (shipped):** the **`"menu"`**, **`"tooltip"`** and **`"chat"`** scopes — which
> **completes the UI chrome**: every scope except F4's two world ones (`world.nick`, `world.speech`) is now live.
> See [`21-fonts.md`](../../../specs/addons/21-fonts.md) for the roadmap.

Client-only and cosmetic (**safe-tier — not gated**), like a HUD overlay.

## Load a font — `hafen.font.load(source [, opts]) → FontHandle`

```lua
local h = hafen.font.load("serif")                        -- a built-in
local h = hafen.font.load("fonts/Inter.ttf", {size = 12}) -- a .ttf from THIS addon's folder
```

- **`source`** — a **built-in name** (`"sans"`, `"serif"`, `"mono"`, `"fraktur"`), or a **path to a
  `.ttf`/`.otf` under the addon's own folder** (`"fonts/Inter.ttf"`). File paths are sandboxed exactly like
  [`hafen.render.image`](render.md): absolute paths and `..` escapes are rejected. Loading a file also registers
  its family into the JVM so `h:family()` resolves in a `$font[…]` rich-text tag (F2).
- **`opts`** (all optional):

  | Key | Meaning |
  |---|---|
  | `size` | logical px (passed through `UI.scale`). Omit ⇒ use the stock size of whatever surface it is applied to. |
  | `aa` | antialias on/off. Omit ⇒ inherit the surface's stock setting. |
  | `bold` / `italic` | style (baked into the font). |
  | `color` | default text colour `{r, g, b [, a]}` (0–255). Omit ⇒ inherit the surface's stock colour. |

Returns an opaque **`FontHandle`** (no AWT font object crosses into Lua):

| Method | Returns | Notes |
|---|---|---|
| `h:derive(opts)` | `FontHandle` | a cheap variant with a different `size`/`aa`/`bold`/`italic`/`color`; never mutates `h`. |
| `h:family()` | string | the AWT family name — feed it to a `$font[family, sz]{…}` tag for per-run mixing (F2). |
| `h:size()` | number \| nil | the handle's logical px size (nil if unset). |

## Draw with it — your own widgets (F2)

Applying a font to your **own** drawing is **fully isolated**: it touches only your widgets' pixels, so there
is no conflict and nothing to revert — the stock UI and every other addon are untouched.

### `font =` on a window / widget — the default for its draws

```lua
local h = hafen.font.load("serif", { size = 12 })
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

### Mix fonts on one line — the `$font` rich-text tag

`g:text`/`g:atext` interpret **rich-text markup**, so you can mix fonts (and styles/colours) inside a single
string. Feed a handle's `h:family()` to the engine's existing `$font[family, size]{…}` tag:

```lua
g:text(("$font[%s,16]{Fancy} normal"):format(h:family()), 6, 6)   -- two fonts, one line
g:text("$col[235,180,80]{$b{bold} orange} plain", 6, 26)          -- $col / $b / $i / $u / $size too
```

This works because `hafen.font.load` registers a loaded TTF's family into the JVM — **zero engine markup
change**. Plain text with no `$` and no `font=` takes the exact stock render path (no behaviour change for
existing addons); malformed markup falls back to drawing the literal string (it never throws).

## Restyle a global surface — owned overrides

```lua
hafen.font.setFont(scope, h)   -- install THIS addon's override on a named surface
hafen.font.reset(scope)        -- drop THIS addon's override on that scope (restores what's beneath)
hafen.font.scopes()            -- array of valid scope names (discovery)
```

`setFont` installs an **owned** override: it is reverted **automatically** on your addon's `:reload`/disable
(the same owned-resource model as [`hafen.ui.adopt`](ui.md), hooks, and overlays), so the stock UI is always
restorable. The change is **live** — most existing text re-renders on the spot.

### Scopes

`scope` is one of an enumerated set (`hafen.font.scopes()`). The full set is declared now; each becomes
**effective** only once its render site is routed (its slice):

| Scope | Client surface | Slice |
|---|---|---|
| `"default"` | global fallback — most UI text (`Text.std` / `Text.render` / `RichText.render` / default `Label`) | **F1 (live)** |
| `"window.title"` | window captions | **F3a (live)** |
| `"heading"` | in-window section headings (embossed fraktur) | **F3e (live)** |
| `"button"` | button captions | **F3b (live)** |
| `"label"` | body text — attribute rows, list items, explicit-foundry labels | **F3c (live)** |
| `"tooltip"` | every tooltip — items, buffs, meters, craft, minimap, action menu, `settip`, pagina, food/study | **F3d (live)** |
| `"menu"` | flower-menu petals + the action-menu keybind letters | **F3d (live)** |
| `"chat"` | the chat window — messages, channel tabs, the typed line | **F3d (live)** |
| `"textentry"` | text-entry fields (+ the console command line) | **F3c (live)** |
| `"world.nick"` | floating player / kin names | F4 |
| `"world.speech"` | speech bubbles | F4 |

`"default"` is the broad hammer: it **cascades** to every routed surface that has no more-specific override — so
`setFont("default", h)` changes everything in one call, while a per-scope override refines any one surface. The
resolution order is **most-specific first**: per-instance (F5) → scope override → `"default"` override → stock.

```lua
hafen.font.setFont("default", h)             -- everything routed (incl. captions + button captions)
hafen.font.setFont("button",  h2)            -- ...but buttons now use h2 (a refinement over the cascade)
hafen.font.reset("button")                   -- buttons fall back to the "default" cascade again
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
command line — the `:` prompt, so `:lua` and your own [`hafen.slash`](slash.md) commands are typed in your font
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
you pass `size=`, `setFont("label", h)` swaps the *family* everywhere while an 18 px row stays 18 px — the safe way
to restyle body text without moving layouts. **Two geometry caveats if you do pass `size=`:** list/attribute **row
heights** were computed from the stock font at construction, so taller glyphs clip; and a `Label` resizes itself to
its text while its container does not re-lay-out around it. Deliberately *not* in this scope: a caller-supplied
pre-rendered `Text` (e.g. the italic "Unused save" placeholder) and text a widget rasterizes into its own face —
those are not body text.

**Notes on `"tooltip"` (F3d).** Every tooltip the client pops up. The bulk of it is the client's tooltip
**engine** (`ItemInfo`), which composes the tip of an **inventory item**, a **buff**, a **vitals bar**, a **craft**
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

### Conflict model (one intrinsic limit)

Over your **own** drawing (F2): isolated, unlimited freedom. Over a **global** surface: it is shared client
state, so each scope holds a **stack of overrides, each tagged with its owning addon — the last applied wins**.
On teardown an addon's entries are pulled from every scope and the surface falls back to the next owner beneath
(or stock). Two addons cannot own the same surface at once; the outcome is deterministic and per-owner
reversible (this mirrors `hafen.ui.adopt`/`replace`).

## Example

```lua
local h
hafen.events.on("OnLoad", function()
  h = hafen.font.load("serif", { size = 11 })
end)

hafen.slash.register("bigserif", function()
  hafen.font.setFont("default", h)   -- most UI text becomes serif, live
end)
-- reverted automatically when the addon is reloaded or disabled;
-- or explicitly: hafen.font.reset("default")
```

## See also

- [`hafen.ui`](ui.md) — the `font=` widget option + the `g:text` draw wrapper take a handle (F2).
- [conventions](conventions.md) — owned resources & teardown, the safe-tier vs gated split.
