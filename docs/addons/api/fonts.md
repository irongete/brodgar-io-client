# `hafen.font` — per-addon typography

Load a font into a **private handle** the addon holds, then either draw with it (F2) or install it as an
**owned override** on a named client surface. There is **no shared cross-addon registry** — a handle is a value
your addon keeps; another addon cannot look it up (no name collisions, no coupling).

> **Slice status.** **F1 (shipped):** `load` / `setFont` / `reset` / `scopes`, and the **`"default"`** scope
> (the global fallback — most UI text). Applying a font to your **own** widgets/draw (`font=` on
> `hafen.ui.window`/`widget`, `g:text{font=…}`) and the per-scope surfaces (`window.title`, `button`, `chat`, …)
> arrive in later slices — the scope names are already listed by `scopes()`, they simply have no effect until
> routed. See [`21-fonts.md`](../../../specs/addons/21-fonts.md) for the roadmap.

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
| `"window.title"` | window captions | F3 |
| `"button"` | button captions | F3 |
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
