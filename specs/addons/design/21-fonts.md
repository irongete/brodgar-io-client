# Fonts (register a font, change it anywhere)

> **Status:** 🟡 Draft · **Spec:** AddOns · **Series:** F (Fonts)
> **Decision:** [D-043](../decisions/fonts.md) · **Surface:** `hafen.font.*`
> **Slices:** **F1** — registry + provider + the `"default"` scope (foundation); **F2** — own-widget fonts +
> draw wrapper + `$font` custom families; **F3** — UI-chrome scopes; **F4** — world scopes; **F5** —
> per-instance override on a `WidgetNode` (stretch).
> **Related:** [07-ui-and-drawing.md](07-ui-and-drawing.md) (the draw wrapper + `LuaWidget`),
> [20-widget-introspection.md](20-widget-introspection.md) (the `WidgetNode` F5 reuses),
> [05-lifecycle-and-reload.md](05-lifecycle-and-reload.md) (owned-resource teardown), [../../codebase-map.md](../../codebase-map.md),
> [the API reference](../../../docs/addons/api/README.md)

## The problem this solves

An addon author wants **complete control over the client's typography**: change the global default font, put
the window titles in one font and the chat in another, render their own widgets in a bundled TTF, and even mix
fonts inside a single line of text. Today that is impossible from Lua and painful in Java — the font is
**baked at the point each `Text.Foundry` is constructed**, and there are **~81 such call sites across 36
files** (`Label`, `Window`, `ChatUI`, `SListMenu`, `CharWnd`, …). There is **no central font resolution
layer**; `Text.std` (`new Foundry(sans, 10)`) is a `public static final` used directly by `Label` and the
`Text.render(…)` statics.

This series adds a **per-addon font system**: an addon **loads a private font handle**, applies it **freely to
its own drawing** (fully isolated), and — where it wants to restyle the *stock* client — installs an **owned
override** on a named surface that is **reverted automatically on `:reload`/disable** (the same
owned-resource model as hooks/overlays/adopt, [05](05-lifecycle-and-reload.md)).

## What we explicitly are NOT building (rejected in [D-043](../decisions/fonts.md))

- **No shared cross-addon font registry (no LibSharedMedia).** There is no cross-addon code sharing in this
  system and the maintainer wants each addon **independent**. A font handle is a **private Lua value the addon
  holds**, not a global name another addon looks up. No shared namespace ⇒ no name collisions, no coupling.
- **No "own the same pixel twice."** Two addons cannot both *be* "the window-title font" simultaneously — a
  global surface is shared client state. That frontier is intrinsic, not a design flaw (see *Conflict model*).

## How fonts actually work today (verified — the design pivots on this)

- **`Text.Foundry`** ([Text.java:127](src/haven/Text.java:127)) wraps an AWT `Font` + size + colour + `aa`;
  every widget builds its own with a hardcoded font. The built-ins are `Text.sans/serif/mono/fraktur`
  ([Text.java:37](src/haven/Text.java:37)); the global default is
  **`Text.std = new Foundry(sans, 10)`** ([Text.java:50](src/haven/Text.java:50)) — a `public static final`.
- **`Label`** ([Label.java:62](src/haven/Label.java:62)) defaults its foundry to `Text.std`; the `Text.render(…)`
  statics ([Text.java:359](src/haven/Text.java:359)) render through `std`. These two are the **highest-traffic
  default consumers** — routing them alone restyles most of the UI.
- **Window titles** use `DefaultDeco.cf/ncf` ([Window.java:178](src/haven/Window.java:178)) —
  `new Text.Foundry(Text.fraktur, 15).aa(true)` inside a blur/tex furnace.
- **Sizing is DPI-scaled** via `UI.scale(float)` ([UI.java:982](src/haven/UI.java:982)); every font size the
  provider produces must pass through it.
- **Per-run font markup already exists.** `RichText` supports a **`$font[family,size]{…}` tag**
  ([RichText.java:566](src/haven/RichText.java:566)) — but it resolves by **AWT family name**
  (`TextAttribute.FAMILY = args[0]`), not by a `Font` object. So a **custom TTF becomes usable in `$font` only
  if its family is registered into AWT** (`GraphicsEnvironment.registerFont`); then the existing tag "just
  works" with **zero RichText edit**. `$size/$b/$i/$col` are likewise already there.
- **Text is rasterised to a `Tex` and cached** (in `Text`, in `UText`, in a window's `cap`). Changing a font at
  runtime therefore requires **invalidating those caches** — the real cost of this feature (see *Invalidation*).

## The API: `hafen.font.*` ([D-043](../decisions/fonts.md))

### Load — a private handle (per-addon, no shared names)

```lua
local h = hafen.font.load(source [, opts])
```

- `source` — either a **path to a `.ttf`/`.otf` under the addon's folder** (`"fonts/Inter.ttf"`), or a
  **built-in name**: `"sans" | "serif" | "mono" | "fraktur"`.
- `opts` (all optional) — `{ size = <px>, aa = <bool>, bold = <bool>, italic = <bool>, color = {r,g,b[,a]} }`.
  `size` is in **logical px** (passed through `UI.scale`); omitted ⇒ the stock default size for the surface it
  is applied to.

The returned **`FontHandle`** is an opaque Lua handle over a Java font (facade-safe — no AWT `Font` crosses into
Lua, [D-017](../decisions/security-sandbox.md)). Methods:

| Method | Returns | Notes |
|---|---|---|
| `h:derive(opts)` | `FontHandle` | a cheap variant with a different `size`/`color`/`aa`/`bold`/`italic` |
| `h:family()` | string | the AWT family name — feed it to `$font[…]` for per-run mixing |
| `h:size()` | number | the handle's logical px size (nil if unset) |

Loading a `.ttf` **registers its family into AWT** so `h:family()` resolves in `$font` (F2).

### Apply to YOUR OWN drawing (isolated — do whatever you want)

No conflict is possible here; it only touches the addon's own widgets/pixels.

```lua
hafen.ui.window{ ..., font = h }              -- default foundry for the window + its Labels
hafen.ui.widget{ ..., font = h }
g:text("hello", {x, y}, { font = h, color = {1,1,1} })   -- the draw wrapper takes a font (07)
-- per-run inside rich text (custom family via h:family()):
g:text("$font[" .. h:family() .. ",12]{fancy} normal")
```

### Apply to a GLOBAL client surface (owned override — reverts on teardown)

```lua
hafen.font.setFont(scope, h)     -- install THIS addon's override on a named client surface
hafen.font.reset(scope)          -- drop THIS addon's override on that scope (restores what's beneath)
hafen.font.scopes()              -- array of valid scope names (discovery)
```

`scope` is one of an **enumerated set of client surfaces**. `"default"` is the broad hammer — it **cascades to
every routed surface that has no more-specific override**, so `setFont("default", h)` really does *change
everything* in one call, while a per-scope override refines any one surface.

| Scope | Client surface | Backing | Slice |
|---|---|---|---|
| `"default"` | global fallback — most UI text | `Text.std` / `Text.render` / `Label` default | **F1** |
| `"window.title"` | window captions | `DefaultDeco.cf/ncf` ([Window.java:178](src/haven/Window.java:178)) | F3a |
| `"heading"` | in-window section headings | `CharWnd.catf`/`failf` + `GridList.dcatf` (furnaces) | **F3e** (amendment) |
| `"button"` | button captions | `Button` foundry | F3 |
| `"label"` | explicit non-default labels | `Label` (non-`std`) | F3 |
| `"tooltip"` | tooltips | `UILoop` String tips, `Widget.PaginaTip`/`KeyboundTip`, `MenuGrid.ttfnd` | **F3d** |
| `"menu"` | flower/context menus | `FlowerMenu.ptf` + `MenuGrid.PagButton.keyfnd` | **F3d** |
| `"chat"` | chat text | `ChatUI.fnd`/`qfnd`/`Selector.tf` | **F3d** |
| `"textentry"` | text-entry fields | `TextEntry`/`ReadLine` | F3 |
| `"world.nick"` | floating player/kin names | (render site to be located + routed) | F4 |
| `"world.speech"` | speech bubbles | `Speaking`-driven text | F4 |

> The **enum is declared in full from F1**; each scope becomes *effective* only once its render site is routed
> through the provider (its slice). An unrouted scope simply has no effect yet — the API never changes, only
> coverage grows.

### Resolution chain (one primitive, every granularity)

Every routed render site asks the provider for its scope; the provider resolves **most-specific first**:

```
per-instance override (F5)  →  scope override (top of the owned stack)  →  "default" override  →  stock foundry
```

That single chain yields all four granularities the maintainer asked for: everything (`"default"`), a family of
surfaces (a scope), one widget (F5), one run (`$font`).

## Conflict model (the one intrinsic limit)

Over **your own** widgets/text: absolute, isolated freedom. Over a **global surface**: it is shared client
state, resolved exactly like every other owned-override system in this client
([08](08-widget-replacement.md) adopt/hide):

- Each scope holds a **stack of overrides, each tagged with its owning addon**. **The last one applied wins.**
- On an addon's `:reload`/disable, **its** entries are pulled from every scope stack and the surface falls back
  to the next owner beneath (or the stock foundry) — the stock UI is always restorable.

So two addons cannot simultaneously own the same surface; the outcome is deterministic (last-wins) and fully
reversible per owner. This mirrors `hafen.ui.adopt`/`replace` teardown ([05](05-lifecycle-and-reload.md)).

## Invalidation — the real cost

A `Text`/`Tex` is cached at its render site, so an override only becomes visible when those caches rebuild. The
mechanism is a single **generation counter** in the provider:

- `setFont`/`reset` **bump `Fonts.gen`**.
- Each routed site caches `(gen, foundry)` and **rebuilds its foundry when `gen` moved**; widgets that cache a
  rendered `Text` (`Label`, a window `cap`) re-render on the same check.
- **F1 proves the loop end-to-end** on the `"default"` scope (`Text.std` consumers + `Label`); later slices reuse
  the exact pattern as they route more sites. No per-frame cost when `gen` is unchanged.

## Java shape (design level — implementation detail in [016-fonts](../016-fonts/plan.md))

- A new **`io.brodgar.addon.FontApi`** builds `hafen.font.*` per owner (mirrors the other `*Api` files) and owns
  the **override registry** (`scope → owner-tagged stack`) + `gen`. A **`haven`-reachable provider facade** (the
  fork already lets `haven` call into `io.brodgar.addon`, per the voice template) exposes
  `Fonts.foundry(scope, stockFont, stockSize, aa)`; routed sites replace their inline `new Foundry(…)` with that
  call, tagged **`// addon:`** — a handful of one-liners per slice, not a rewrite of all 81 at once.
- **`FontHandle`** wraps an AWT `Font` (built-in or `Font.createFont(TRUETYPE_FONT, file)`), plus size/aa/colour;
  loading a file also `registerFont`s the family for `$font`.
- **Owned teardown:** each addon's `Addon` gains a font-override list (like `subs`/`timers`/`widgets`); teardown
  removes its entries from every scope stack and bumps `gen` — no orphaned overrides after `:reload`.
- **Engine change ⇒ `ant` rebuild + full client restart** (per project rules); only Lua addon *files* hot-reload.

## Slices (each = one task, small + verifiable)

- **F1 — Registry + provider + `"default"` scope (foundation).** `hafen.font.load` (built-ins + a bundled TTF,
  AWT-registered, `UI.scale`d) → `FontHandle` (+ `derive`/`family`/`size`); the `Fonts` provider + owned-override
  stack + `gen`; route **`Text.std` consumers + `Label`** through it; `setFont("default",h)` / `reset("default")`
  / `scopes()` + owned teardown. **DoD:** an addon loads a TTF, `setFont("default", h)` changes default text
  across the client live; `reset`/`:reload` restores stock; last-wins verified with two overrides.
- **F2 — Own-widget fonts + draw wrapper + `$font` custom families.** `font =` on `hafen.ui.window`/`widget`;
  `g:text(str, pos, {font=…})`; a custom TTF usable in `$font[h:family(),…]{…}`. **Isolated, no global state.**
  **DoD:** an addon window renders in its bundled font; a single `g:text` line mixes two fonts via `$font`.
- **F3 — UI-chrome scopes.** Route + enable `window.title`, `button`, `label`, `tooltip`, `menu`, `chat`,
  `textentry` — **split into F3a (window.title) / F3b (button) / F3c (textentry+label) / F3d (menu+tooltip+chat)**,
  plus **F3e (`heading`)**, a scope ADDED to the enum mid-series (D-043 amendment, 2026-07-30) for the embossed
  in-window section headings — `CharWnd.catf`/`failf` + `GridList.dcatf`, which are `Text.Furnace`s baked into an
  `Img`, so the slice combines F3a's furnace rebuild with F3b's recorded-recipe re-render (`CharWnd.Heading`).
  **DoD:** each flips independently; `"default"` cascades to the ones left unset.
- **F4 — World scopes.** Locate + route `world.nick` and `world.speech`. **DoD:** floating names / speech bubbles
  restyle live.
- **F5 — Per-instance override (shipped).** `node:setFont(h)` / `node:resetFont()` on a
  [`WidgetNode`](20-widget-introspection.md) — the node handle only, not a `hafen.font.setFont(widgetRef, h)` twin
  (D-012) — at the top of the resolution chain, covering the widget **and its whole subtree**. Budgeted as the
  priciest slice; in the event the **draw pass** turned out to be the scope stack it needed (F3d's dynamic-scope idea
  keyed on the tree descent), so a frame opened in the child-draw loop made every F1–F4-routed site per-instance
  capable with no second edit — plus a per-override **stamp mixed into `gen()`** so each site's existing generation
  check also detects *where* it is drawing. **DoD:** one specific window restyles while its siblings keep the
  scope/default font.

## Open points for the maintainer

- **`setFont("default", …)` vs a `setDefault(h)` sugar.** To honour "one canonical way" ([D-012](../decisions/architecture-api.md))
  the global default is set through `setFont("default", h)` — no separate `setDefault`. Say the word if you want
  the sugar back.
- ~~**F5 scope for v1.**~~ **Resolved (2026-07-30): F5 ships in the first pass** — confirmed by the maintainer, and
  implemented as `node:setFont(h)` / `node:resetFont()` on a `WidgetNode` (no `hafen.font.setFont(widgetRef, h)`
  twin, per D-012). See [016-fonts](../016-fonts/plan.md). **The F-series is complete.**

## The `hello` harness

F1 extends `addons/hello/` to bundle a small `.ttf`, `load` it, and flip `"default"` (reverting on
`:reload`) — so one login re-checks the whole font path alongside every prior feature. If a slice grows too
large to fold in cleanly (e.g. F4's world text), a dedicated example addon is proposed instead
(D-031 spirit).
