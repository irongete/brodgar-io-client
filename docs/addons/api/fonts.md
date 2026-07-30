# `hafen.font` — per-addon typography

Load a font into a **private handle** the addon holds, then either draw with it (F2) or install it as an
**owned override** on a named client surface. There is **no shared cross-addon registry** — a handle is a value
your addon keeps; another addon cannot look it up (no name collisions, no coupling).

> **Slice status.** **F1 (shipped):** `load` / `setFont` / `reset` / `scopes`, and the **`"default"`** scope
> (the global fallback — most UI text). **F2 (shipped):** applying a font to your **own** drawing — `font=` on
> `hafen.ui.window`/`widget`, `g:text`/`g:atext` with a `{font=…, color=…}` option, and a custom TTF in a
> `$font[…]{…}` rich-text tag. **F3a (shipped):** the **`"window.title"`** scope (window captions).
> **F3b (shipped):** the **`"button"`** scope (button captions) is now live. The remaining per-scope surfaces
> (`label`, `tooltip`, `menu`, `chat`, `textentry`) arrive in later F3 slices — the scope names are already
> listed by `scopes()`, they simply have no effect until routed. See
> [`21-fonts.md`](../../../specs/addons/21-fonts.md) for the roadmap.

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
| `"default"` | global fallback — most UI text (`Text.std` / `Text.render` / default `Label`) | **F1 (live)** |
| `"window.title"` | window captions | **F3a (live)** |
| `"button"` | button captions | **F3b (live)** |
| `"label"` | explicit non-default labels | F3 |
| `"tooltip"` | tooltips | F3 |
| `"menu"` | flower / context menus | F3 |
| `"chat"` | chat text | F3 |
| `"textentry"` | text-entry fields | F3 |
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
