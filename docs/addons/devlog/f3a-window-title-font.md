# F3a — The `"window.title"` font scope (window captions)

> **Status:** ✅ Implemented; clean compile (`rm -rf build/classes && ant hafen-client` → BUILD SUCCESSFUL),
> **8 headless provider-resolution checks** for the `"window.title"` path (no-override → stock fast path; the
> `"default"` cascade reaches the unset scope; a `"window.title"` override refines over the cascade; last-wins on
> its own stack; `gen` bumps on push; teardown falls back to the owner beneath then restores stock) + LuaJ parse
> of all addons. The caption render path itself is a **headless resource skip** — building the `DefaultDeco`
> blur/tex furnaces needs `ctex` (`gfx/hud/fonttex`) and a working `Text` toolkit (the same skip A8/A10/R2a/F2
> hit), so the on-screen wiring is verified **in-game**. **In-game verified ✅.** *(Java engine change ⇒ `ant`
> rebuild + full client restart.)*
> **Design:** [specs/addons/21-fonts.md](../../../specs/addons/21-fonts.md) §F3, decision
> **[D-043](../../../specs/addons/decisions.md)**. Builds on [f1-fonts](f1-fonts.md) (the provider + `gen` +
> `"default"` scope).

F3a routes the **first UI-chrome scope** through the F1 provider: **`"window.title"`** — the caption drawn on
every window's title bar. An addon can now `setFont("window.title", h)` to restyle **only** window captions
(leaving body text stock), while `setFont("default", h)` still restyles captions **via the cascade** (until a
`window.title` override refines them). Reverted automatically on `:reload`/disable (owned-resource model). This
is the first slice of F3; `button`/`label`/`tooltip`/`menu`/`chat`/`textentry` follow in later F3 slices, each a
distinct render site (the queue splits F3 into F3a…F3d — see PLAN §7).

## The API (already shipped in F1 — this slice makes one scope *effective*)

```lua
local h = hafen.font.load("serif", { size = 15 })
hafen.font.setFont("window.title", h)   -- window CAPTIONS restyle live (body text stays stock)
hafen.font.reset("window.title")        -- drop it (also automatic on :reload/disable)
-- cascade: with no window.title override, setFont("default", h) restyles captions too
```

The Lua surface (`hafen.font.*`, `FontApi`) is unchanged — the scope name has been in `scopes()` since F1. F3a
adds only the **render-site routing** for that scope.

## How window captions work today (the design pivots on this)

Window titles are **not** a plain `Text.Foundry` like the F1 `"default"` sites — they are a **blur/tex furnace**:

- [`Window.DefaultDeco`](../../../src/haven/Window.java:177) held two `public static final Text.Forge` furnaces,
  `cf` (focused) and `ncf` (unfocused), each = `new PUtils.BlurFurn(new PUtils.TexFurn(new Text.Foundry(Text.fraktur, 15).aa(true), ctex), …)`.
  The furnace wraps a `Text.Foundry` in a texture-fill + blur pipeline (the golden/black caption look).
- [`DefaultDeco.drawframe`](../../../src/haven/Window.java) renders the caption **once** into a cached `Text cap`
  and re-renders it only when `cap.text != wnd.cap` or focus flips — otherwise it blits the cached `cap.tex()`
  every frame.

So routing this scope is **not** a one-line foundry swap like F1: the whole furnace must be rebuilt from the
provider's chosen foundry, and each window's cached `cap` must be invalidated, whenever the override moves.

## What was built (2 tagged core edits, both in `Window.java`)

**No new class, no `io.brodgar` edit** — the F1 provider (`haven.Fonts`) and `gen` counter already exist; F3a
only wires one more render site into them.

### 1. The stock foundry + provider-routed furnaces (rebuilt on `gen`)

`DefaultDeco.cf`/`ncf` are no longer `static final` built at class-load. Instead:

- **`public static final Text.Foundry titlefnd`** = `new Text.Foundry(Text.fraktur, 15).aa(true)` — the **stock**
  title foundry, passed to the provider as the fallback.
- `cf`/`ncf` became `private static` (nothing outside `Window.java` referenced them — verified by grep), rebuilt
  by a new **`checktitlefont()`**: when `Fonts.gen()` moved (or on first use), it asks
  `Fonts.foundry("window.title", titlefnd)` for the current foundry (an addon override → the `"default"` cascade
  → `titlefnd`) and rebuilds both furnaces from it (the exact same `BlurFurn(TexFurn(…), …)` recipe, only the
  inner foundry changes). Cheap: an int-compare per frame when `gen` is unchanged.

### 2. Caption cache invalidation

`DefaultDeco` gained a per-instance **`int capgen = -1`** (the `Fonts.gen()` at the last `cap` render).
`drawframe` now calls `checktitlefont()` and re-renders `cap` when `capgen != Fonts.gen()` (in addition to the
existing text/focus-change triggers), then stores the new `gen`. So a live `setFont`/`reset`/teardown re-renders
every visible window's caption on the next frame — the F1 pattern (`Label` uses the identical `gen`-compare
restyle), applied to the furnace-backed caption.

## Resolution & cascade (reusing F1)

`checktitlefont` calls `Fonts.foundry("window.title", titlefnd)`, whose provider resolves **most-specific first**:
a `"window.title"` override (top of its owner-tagged stack) → the `"default"` override (cascade) → `titlefnd`.
So: a `window.title` override changes **only** captions; a `default` override changes captions **too** (until a
`window.title` override refines them); both revert per-owner on teardown. This is exactly the D-043 chain — F3a
adds no new resolution logic, only a routed consumer.

## Threading

`checktitlefont`/`drawframe` run on the **render thread**; `setFont`/`reset`/teardown run on the **UI thread**.
`Fonts.gen()`/`active` are `volatile` and the provider's mutators are `synchronized(Fonts.class)`, so the worst
case is a caption that restyles one frame late — the same tolerance F1's `Label.draw` accepts. The static
`cf`/`ncf`/`fontgen` are mutated only from the (single) render thread during draw, so windows never race each
other.

## Verification

- **Clean compile** (`rm -rf build/classes && ant hafen-client` → BUILD SUCCESSFUL).
- **8 headless checks** on the `"window.title"` resolution path via `haven.Fonts` directly (no GL): no-override →
  stock identity; `"default"` cascades to the unset `window.title`; a `window.title` override refines over the
  cascade; last-wins on its stack; `gen` bumps on push; `removeOwner` falls back to the owner beneath, then a
  full teardown restores the stock fast path. (Constructing the `Text.Foundry` prints a headless "could not
  determine maximum scaling factor" warning and falls back to scale 1.0 — the resolution assertions still hold.)
- **LuaJ parse** of all addons (hello/planner/gizmo/bags/walker/hogtest/netdemo/widgetstack).
- The caption render itself is the documented **resource skip** → verified in-game.

## `hello` harness (v0.49.0)

Added **`:hello title`** — toggles `setFont("window.title", demoFont:derive{size=15})` / `reset("window.title")`.
Open or focus any window while it is on: the caption font changes, but body text stays stock (independence). The
existing **`:hello font`** (a `"default"` override) now **also** restyles captions via the cascade — the DoD's
cascade half. Both revert on `:reload`/disable (P2 — the state flags reset in `OnLoad`).

## Deferred (later F3 slices)

- **F3b** — `"button"` (`Button.tf`/`nf` furnace + per-button `BufferedImage cont` re-render; note `Charlist.df`
  also derives from `Button.tf`).
- **F3c** — `"textentry"` + `"label"` (plain `Text.Foundry` sites: `TextEntry.fnd`/`ReadLine`, explicit-foundry
  `Label`s).
- **F3d** — `"menu"` + `"tooltip"` + `"chat"` (`FlowerMenu.ptf`/`MenuGrid`; the tooltip foundry; `ChatUI`'s
  `RichText.Foundry` — a different foundry type that needs its own provider path).
- **F4** — the world scopes (`world.nick`, `world.speech`); **F5** — per-instance override on a `WidgetNode`.
