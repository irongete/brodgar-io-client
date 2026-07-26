# F1 — Registry + provider + the `"default"` scope (`hafen.font.*`)

> **Status:** ✅ Implemented; clean compile (`rm -rf build/classes && ant hafen-client` → BUILD SUCCESSFUL),
> **24 headless checks** on the `haven.Fonts` provider (baseline no-override → stock identity; the scope enum
> `isScope`/`scopes`; push bumps `gen`; override resolves to the right family; the override foundry is cached;
> `"default"` **cascades** to an unset scope; **last-wins** across two owners; a per-scope override refines one
> surface while `"default"` still cascades elsewhere; `reset` resurfaces the owner beneath + returns false for an
> unset owner; `removeOwner` drops it from **every** scope; empty registry → the fast-path stock) + LuaJ parse of
> `hello`. **Java engine change ⇒ `ant` rebuild + full client restart before the in-game test. In-game DoD pending.**
> **Design:** [specs/addons/21-fonts.md](../../../specs/addons/21-fonts.md), decision
> **[D-043](../../../specs/addons/decisions.md)** (per-addon `hafen.font.*` — private handles + owned overrides,
> **no** shared registry).

F1 is the foundation of the **F-series** (fonts): an addon can **load a private font handle** and install an
**owned override** on the **`"default"`** client surface — which restyles most UI text **live** and reverts
automatically on `:reload`/disable. Later slices reuse the exact provider/gen pattern to route more surfaces
(F3 chrome, F4 world) and to apply a font to the addon's own drawing (F2).

## The API (shipped this slice)

```lua
local h = hafen.font.load("serif", { size = 11 })   -- built-in; or a .ttf/.otf under the addon folder
h:family(); h:size(); h:derive{ size = 14, bold = true }

hafen.font.setFont("default", h)   -- owned override on the global default surface → most UI text, live
hafen.font.reset("default")        -- drop it (also automatic on :reload/disable)
hafen.font.scopes()                -- the full enumerated scope list (F1 routes only "default")
```

## How fonts work today (the design pivots on this)

Every widget builds its own [`Text.Foundry`](../../../src/haven/Text.java:127) (an AWT `Font` + size + colour +
`aa`) with a **hardcoded** font; there are ~81 such sites across 36 files. The global default is
[`Text.std`](../../../src/haven/Text.java:50) = `new Foundry(sans, 10)` — a `public static final`, used directly
by the [`Text.render(…)`](../../../src/haven/Text.java:359) statics and by [`Label`](../../../src/haven/Label.java:62)'s
default constructors. Those two are the **highest-traffic default consumers**, so routing them alone restyles
most of the UI. `Text.std` is `final` (can't be reassigned) and many sites don't read it anyway — hence a
**provider + scope** indirection rather than a mutable global.

## What was built

### The provider — `haven.Fonts` (new)

A `haven`-reachable facade (the "voice template" — `haven` may call into the addon layer, but here we keep the
provider **in `haven`** so core files depend only on their own package). It holds:

- the **owner-tagged override registry** (`scope → stack of Spec{owner, font, size, aa, color}`) and a **`gen`**
  counter;
- the primitive routed sites call — **`Fonts.foundry(scope, stock)`** — resolving **most-specific first**: the
  scope's own override → the `"default"` override (cascade) → the site's `stock` foundry. A `Spec` builds its
  actual `Text.Foundry` lazily and **caches** it (keyed by the stock foundry's identity), applying `UI.scale` to
  a logical `size` and falling back to the stock's size/aa/colour for any unset field;
- `push`/`reset`/`removeOwner` (all bump `gen`), `scopes()`/`isScope()`, and the full **scope enum** (declared in
  full from F1; only `"default"` is routed this slice — the rest are inert until their slice).

**Owner tokens are opaque.** An override is tagged with the owning `Addon` as a bare `Object`, compared by
identity — so this core class carries **no** dependency on `io.brodgar.addon`. (This is a small, deliberate
deviation from the spec sketch, which put the registry in `FontApi`: keeping it in the provider avoids a
`haven → io.brodgar` edge in `Text`/`Label`'s dependency graph, and the behaviour is identical.)

**Cost.** A `volatile boolean active` short-circuits `foundry()` to return `stock` after a single read whenever
no addon has installed **any** override (the overwhelmingly common case) — no lock, no allocation on the hot
`Text.render` path. Only once an override exists does it take the registry lock to resolve.

### The Lua surface — `io.brodgar.addon.FontApi` + `FontHandle` (new)

- **`FontHandle`** — the Java half of a loaded font: an AWT `Font` (bold/italic baked in) + optional
  `size`/`aa`/`color`. Opaque + facade-safe exactly like [`LuaImage`](../../../src/io/brodgar/addon/LuaImage.java)
  (an unforgeable `userdata` under a `KEY` field, `resolve`d back by the bridge — **no AWT font crosses into
  Lua**, D-017).
- **`FontApi`** — builds `hafen.font.*` per owner (mirrors the other `*Api` files): `load` resolves a built-in
  (`Text.sans/serif/mono/fraktur`) or a sandboxed addon-folder `.ttf`/`.otf` (`Font.createFont` +
  `GraphicsEnvironment.registerFont` so the family works in a `$font` tag, F2); the handle's `:derive`/`:family`/
  `:size`; `setFont`/`reset` drive the provider and record the scope in the addon's owned list; `scopes()`.
  Reuses `RenderApi.resolveAddonAsset` (made package-private) for the D-017 containment check and
  `AddonManager.luaColor` for colour parsing.

### Routed render sites (`// addon:`, F1 = the `"default"` scope)

- **[`Text.render(String,Color)`](../../../src/haven/Text.java:359)** + **`Text.renderf`** — now render through
  `Fonts.foundry("default", std)` (they rebuild a `Line` every call, so they pick up an override with no caching
  problem).
- **[`Label`](../../../src/haven/Label.java)** — the two default constructors resolve through
  `Fonts.foundry("default", Text.std)` and **follow the `"default"` scope**: `f` became non-final, and a small
  `restyle()` in `draw()` re-renders the label (preserving wrap width + colour) whenever `Fonts.gen()` has moved.
  It is a single int-compare when nothing changed, and only visible labels re-render (and only on a `gen` bump).
  A label built with an **explicit** foundry stays fixed (that is the future `"label"` scope, F3).

### Owned teardown

`Addon` gains a `fontOverrides` list; `FontApi.teardownFonts(a)` (wired into `AddonRegistry.teardown`, the same
chain as hooks/overlays/adopt) calls `Fonts.removeOwner(a)` — dropping the addon's entries from every scope and
bumping `gen` so routed sites revert to stock. Skips the `gen` bump entirely for an addon that never touched
fonts.

## Invalidation — the real cost

A `Text`/`Tex` is cached at its render site, so an override only shows when those caches rebuild. The single
**generation counter** is the mechanism: `setFont`/`reset`/teardown bump `Fonts.gen`; `Text.render` rebuilds
every call anyway, and `Label` caches `(gen, foundry)` and re-renders when `gen` moved. F1 proves this loop
end-to-end on `"default"`; later slices reuse the identical pattern.

## Threading

All font ops (`load`/`setFont`/`reset`/teardown) run on the UI thread (P5). `foundry()` is also read from the
render thread (`Text.render`, `Label.draw`); the provider's registry mutators are `synchronized(Fonts.class)`,
`gen`/`active` are `volatile`, and a `Spec`'s lazy foundry cache is guarded by the `Spec`. The fast path (no
override) is a lock-free `volatile` read.

## `hello` harness (v0.47.0)

At `OnLoad`, `hello` loads a font — a bundled `fonts/demo.ttf` if present (the "loads a TTF" path — drop any
`.ttf` there to exercise `Font.createFont` + AWT registration with **no code change**), otherwise the built-in
`"serif"` (still proves the whole loop) — and logs `scopes()`. **`:hello font`** toggles a `setFont("default", …)`
override (most UI text changes live) and, while turning it on, **stacks a second override (`mono`) on top for 3 s
then drops back to the first** — proving **last-wins** on the owner-tagged stack. `hafen.font.reset("default")`
(or a `:reload`/disable) restores the stock font.

## Deferred (later slices)

- **F2** — `font=` on `hafen.ui.window`/`widget`, `g:text{font=…}`, and a custom TTF in the existing `$font` tag
  (family registered at `load` — zero `RichText` edit).
- **F3** — the UI-chrome scopes (`window.title`, `button`, `label`, `tooltip`, `menu`, `chat`, `textentry`).
- **F4** — the world scopes (`world.nick`, `world.speech`), whose render sites still need locating.
- **F5** — per-instance override on a `WidgetNode` (top of the resolution chain; the priciest slice).
