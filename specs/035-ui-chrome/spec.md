# 035-ui-chrome — Spec

## What & why

**The sheet learns to draw.** Everything C1 shipped is text: `font` and `color`. C2 adds the properties that
paint and, for the first time, can **move things** — `bg`, `border`, `pad` — and points them at the client's
window chrome.

```lua
hafen.ui.skin{
  ["window.frame"] = { bg = {color={26,26,28,240}},
                       border = {image = hafen.asset("img/panel.png"), slice = {8,8,8,8}}, pad = 6 },
}
```

**Both seams already exist in the engine; neither has to be invented.**

- **`Window.Deco`** — `public Deco deco` + **`chdeco(Deco)` swaps it live**
  ([Window.java:131](src/haven/Window.java:131)); `Deco extends Widget` is abstract and `DefaultDeco` is merely
  *one* implementation ([:177](src/haven/Window.java:177)). It owns the whole chrome: tiled background
  ([`drawbg`:233](src/haven/Window.java:233)), frame + corners + caption plate ([`drawframe`:249](src/haven/Window.java:249)),
  the close button, the sizer, **the geometry** (`iresize`/`contarea`, [:214](src/haven/Window.java:214)) and the
  drag + frame hit-test.
- **`IBox`** — already an *interface* with `draw(g, tl, sz)` ([IBox.java:29](src/haven/IBox.java:29)), so
  **9-slice is a first-class engine concept**, used by the window-less panels (`Frame`, `FlowerMenu`, `GItem`, …).

### The naming question, decided rather than discovered

030's inspector found that **a window's chrome is a child** (`@DefaultDeco`, role `nil`), so a `window` rule
does **not** reach the frame — the first thing anyone writing a theme will trip over. The answer here:
**`window.frame` is a site key, the sibling of `window.title`** — not a new idea but the existing one, since the
caption is *also* drawn by the deco and is *already* a site key. No new role, no classifier change, and it
answers honestly: the frame is not the window, it is a site, like the title.

### Geometry is a new risk class

`pad`, and a sliced border's own insets, change **where the content starts** (`iresize`/`contarea`) — for the
first time a sheet rule can move the client's own layout. So the existing doctrine (*nothing is refused, some
things are inert*) gets its geometry row: a size-changing property applies only where the surface **owns its
geometry and re-lays-out**, inert elsewhere — and which is which is **measured, not assumed**, since 033.3
already proved that guessing produces a table that lies. **Cost differs too**: chrome draws every frame with no
raster cache to amortise it, so the engine paints from the resolved style **declaratively** — a per-frame Lua
callback was rejected on cost at design time and stays rejected.

Feature **C2**, the last of the skinning run; after it only **E** (layout) is left.

## Acceptance criteria — per `TESTING.md`; `[manual]` only for looks, everything readable is asserted

- [ ] `["window.frame"]` with `bg` and `border` restyles **every** window live; `skin(nil)` / disable /
      `:reload` / relog put the stock chrome back **byte-for-byte** (the identity fast path).
- [ ] `widget:style()` reports `bg`/`border`/`pad` beside `font`/`color`, keeping D-075 and its one `nil`.
- [ ] **Geometry is asserted numerically, not eyeballed**: a `pad` rule changes a window's content area read
      back through `widget:size()`/`:pos()`; removing it restores the old numbers.
- [ ] A size-changing property on a surface that cannot re-lay-out is **inert, never an error**, and the
      property × key table says so per key, measured.
- [ ] A restyled window still **drags, resizes, closes and focuses** as stock (the deco is replaced, not
      bypassed), and `IBox` panels follow `bg`/`border` through the same keys, untouched when unnamed.
- [ ] **Cost measured** (`hafen.client:profiling()`): the per-frame draw cost of a restyled client against
      stock, and **no Lua runs per frame to paint chrome**; the `theme` example gains a frame, so a JSON theme
      restyles chrome with no code of its own.

## Out of scope

- Layout — moving, anchoring or resizing widgets by rule, and profiles: **E**.
- `hover`/`down`/state-dependent skins, descendant selectors, new roles beyond `window.frame`.
- The *behaviour* of the close button or the sizer (only their drawing is in scope); and the 3D world, the map
  and item icons — anything that is not client UI chrome.

## Context files

- `design/21-fonts.md` §F3a — how the caption already routes through the deco; `design/22-ui-selectors.md` —
  key kinds, and why the chrome is a child with role `nil`; `specs/testing/addon-suite.md` — the suite format
- `src/haven/Window.java` — `deco`/`chdeco`/`Deco`/`DragDeco`/`DefaultDeco`, `drawbg`, `drawframe`,
  `iresize`/`contarea`; `src/haven/IBox.java` — the 9-slice interface and `Images`/`Scaled`
- `src/haven/Fonts.java` — the provider, the frame, `combine`, `treeStyles` (034's seam) the style flows
  through; `src/io/brodgar/addon/Sheet.java` — key classification + `specOf`; `LuaWidget.java` — `skin`/`style`
- `docs/addons/api/ui.md` §"What each key accepts" — the table this feature must extend and correct
- `034-ui-stylesheet-tree/` (D-075/076/077), `033-ui-stylesheet/` (D-072/073/074 and the emboss findings)
- `learnings/ui-widgets.md`, `learnings/fonts.md` — **grep, never read whole**;
  `decisions/widgets-ui.md` (D-009), `architecture-api.md` (D-011 invasiveness, D-012)
