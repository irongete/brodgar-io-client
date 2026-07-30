# F5 — Per-instance font override (`node:setFont(h)`): one widget, not a scope

> **Status:** ✅ Implemented; clean compile (`rm -rf build/classes && ant hafen-client` → BUILD SUCCESSFUL) +
> `ant bin`, **66 headless checks** in nine groups (baseline fast path, frame scoping, subtree inheritance +
> nesting + sibling isolation, instance-beats-scope-beats-default, the `gen()` frame stamp, `dynamic()` claiming
> unroutable foundries, ownership/last-wins/teardown, size-aa-colour, **and a real `Label` restyling end-to-end
> inside the frame**) + LuaJ parse of all 8 addon files. `hello` **v0.55.1** (`:hello node` — v0.55.0 picked a
> text-less window on the first in-game run; see *Gotchas*). **In-game verified ✅.** *(Java engine change ⇒ `ant` rebuild + full
> client restart.)*
> **Design:** [specs/addons/21-fonts.md](../../../specs/addons/21-fonts.md) §F5, decision
> **[D-043](../../../specs/addons/decisions.md)**; the node itself is
> [20-widget-introspection.md](../../../specs/addons/20-widget-introspection.md) (W1). Builds on
> [f1-fonts](f1-fonts.md) (the provider, the owner-tagged stacks, `gen`) and
> [f3d](f3d-menu-tooltip-chat-fonts.md) (the *dynamic* — non-lexical — scope idea).

F5 is the **last slice of the F-series** and the only piece of the font system that is not a named scope. A scope
restyles a *family* of surfaces client-wide; this restyles **one arbitrary native widget** (and everything drawn
inside it) while its siblings keep the scope/`"default"` font. It sits at the **top of the resolution chain** the
spec drew in F1:

```
per-instance (F5)  →  scope override  →  "default" override  →  stock foundry
```

## The API

```lua
local h = hafen.font.load("mono", { size = 13 })
local n = hafen.ui.at(hafen.ui.mouse().x, hafen.ui.mouse().y)   -- any WidgetNode (W1/W2)
n:setFont(h)      -- this widget + its whole subtree; its siblings are untouched
n:resetFont()     -- drop it (automatic on :reload/disable, and when the widget dies)
```

Only these two methods are new — added to the **`WidgetNode`** handle rather than as a
`hafen.font.setFont(widgetRef, h)` twin, to honour "one canonical way per operation"
([D-012](../../../specs/addons/decisions.md)). They are the first *write* a node carries, which is fine: a font is
pure client-side pixels, owner-tagged and reverted like every other owned resource — the node stays read-only
where it matters (server state).

## Why this was the "priciest slice", and why it turned out cheap

The spec's worry was invalidation: an override on one widget means finding *that widget's* cached `Text`/`Tex`
among the many already-routed sites, none of which knows anything about widget identity. The naïve shape is a
per-widget field plus a second resolution path in all ~20 routed sites.

The cheap shape came from noticing that **F3d had already built the mechanism**, in a different guise. F3d's
tooltip scope is *dynamic*, not lexical: a composer declares "everything rendered from here down is tooltip text"
and the generic statics ask the provider which scope is in force. A per-instance override is the same idea keyed
on the **draw pass** instead of on a composer: the UI already descends the widget tree parent-first, so the one
place that knows *"we are now inside widget W"* is the child-draw loop.

So the whole slice is: open a **frame** around a child that carries an override, and have `resolve()` consult the
frame before any scope. Every site F1–F4 routed becomes per-instance-capable **for free** — no second edit
anywhere, and even text drawn by published `.res` code follows (see *`dynamic()`* below).

## The mechanism (all in `haven.Fonts`)

**The registry.** `Map<Widget, List<Spec>> instances` — the same owner-tagged stack per widget that a scope has
per name (one override per owner, last applied wins, `removeOwner` sweeps them on teardown). It is a
**`WeakHashMap`**, and `Widget` overrides neither `equals` nor `hashCode`, so the keys are identity keys *and*
weak: a closed window's override evaporates with it. That is why `Addon` gained only a **boolean** `fontNodes`
flag, not a list — a list of styled widgets would pin dead widget trees in memory, defeating the point.

**The frame.** `Fonts.frame(Widget)` returns a `Fonts.Frame` (a tiny `AutoCloseable` whose `close()` does not
throw, so a caller needs no `catch`) and pushes the widget's override onto a per-thread stack. Two **shared,
stateless singletons** back it — `NOFRAME` (a no-op `close`) and `POPFRAME` (pops the stack) — so the draw loop
allocates **nothing** per widget per frame. A widget with no override of its own returns `NOFRAME`, which is
exactly what gives **subtree inheritance**: the enclosing frame simply stays in force.

**Resolution.** `resolve()`/`resolveStyle()` consult `frameTop()` first and return it whatever scope was asked
for — an override on a window claims its caption (`"window.title"`), its rows (`"label"`), its buttons
(`"button"`) and its chat-like text in one go, which is what "restyle this widget" means. The `Spec`'s existing
per-stock foundry cache does the rest, so each site keeps its own stock size/colour unless the handle carries one.

**Invalidation — the one genuinely new idea.** Every routed site already does `if(Fonts.gen() != mygen) rebuild`.
A global `gen++` on `setFont` is not enough here: a `Label` **created after** the override (a row added to an
already-styled window) captures the current generation at construction and its check would never fire, leaving it
stock forever. Fix: each `Spec` carries a **`stamp`**, and `gen()` reports `gen ^ stamp` **while that override's
frame is active**. The generation a site sees therefore encodes *where it is being drawn*, so the very same check
every site already performs also detects "I was built outside this frame and am now drawing inside it". It is
**stable across frames** (the same stamp every time), so there is no per-frame rebuild, and it reverts by itself
when the frame is gone.

**Unroutable foundries.** `Fonts.dynamic()` — F3d's "which scope claims a foundry we cannot route" primitive,
consumed by `Text.Foundry.resolved()` and its `RichText` twin — now also answers for an active frame (reporting
`"default"`, which resolution turns into the instance override anyway). One `return` extended, and
`node:setFont` reaches text drawn by **published resource code** inside the subtree, with **zero** edits in
`Text.java`/`RichText.java`.

## The two core edits (both 3-line, both in the draw path)

| File | Edit |
|---|---|
| [`Widget.java`](../../../src/haven/Widget.java) | `draw(GOut, boolean)`: `try(Fonts.Frame ff = Fonts.frame(wdg)) { wdg.draw(g2); }` around the child draw (`// addon:`) |
| [`UI.java`](../../../src/haven/UI.java) | `draw(GOut)`: the same frame around `root.draw(g)` — the root has no parent loop, so this is what makes `hafen.ui.root():setFont(h)` work instead of silently doing nothing |

Cost with no per-instance override installed anywhere: one `volatile` read → a shared no-op frame. Once one
exists, one `WeakHashMap` lookup per drawn widget per frame; the flag clears again on teardown (`prune()` now
recomputes `instanced` as well as `active`, and drops emptied entries).

## Files touched

| File | What |
|---|---|
| [`src/haven/Fonts.java`](../../../src/haven/Fonts.java) | the F5 block: `instances` weak registry, `Frame`/`frame`/`frameTop`, `pushInstance`/`resetInstance`, `Spec.stamp`, `gen()` stamping, `dynamic()` extension, `removeOwner`/`prune` sweep |
| [`src/haven/Widget.java`](../../../src/haven/Widget.java) | the child-draw frame (`// addon:`) |
| [`src/haven/UI.java`](../../../src/haven/UI.java) | the root frame (`// addon:`) |
| [`src/io/brodgar/addon/FontApi.java`](../../../src/io/brodgar/addon/FontApi.java) | `setNodeFont`/`resetNodeFont` + teardown now also sweeps per-instance overrides |
| [`src/io/brodgar/addon/UiApi.java`](../../../src/io/brodgar/addon/UiApi.java) | `:setFont`/`:resetFont` on the node handle |
| [`src/io/brodgar/addon/Addon.java`](../../../src/io/brodgar/addon/Addon.java) | the `fontNodes` flag (why it is a flag, not a list) |
| [`addons/hello/main.lua`](../../../addons/hello/main.lua) | `:hello node` + two locals + the `OnLoad` reset + help text |
| `addons/hello/manifest.json` | **v0.55.0** |

No new class, no new scope, no change to `hafen.font.scopes()`.

## Verification

- **Clean compile** (`rm -rf build/classes && ant hafen-client` → BUILD SUCCESSFUL) + `ant bin`.
- **53 headless checks** (`haven.Fonts` driven directly with real `Widget`s — no GL needed):
  1. **baseline** — stock identity for every scope, `style()`/`dynamic()` null, and a frame on an un-overridden
     widget changes nothing (not even `gen()`);
  2. **frame scoping** — the override applies inside the frame and *only* there: family taken, the site's stock
     size/colour kept when the handle carries none, every scope resolving to it, the per-stock cache returning the
     same instance, a different stock keeping its own size, `style()` reporting it, stock identity again after
     `close()`;
  3. **subtree inheritance / nesting / siblings** — a child with no override inherits the enclosing frame, a child
     with its own wins innermost, the ancestor's is back when the child's closes, and a sibling subtree is stock;
  4. **the chain** — with a `"default"` *and* a `"window.title"` override installed, the instance override beats
     both (including erasing the scope's 22 px size);
  5. **the `gen()` stamp** — differs inside vs outside, is **stable** across repeated frames, is untouched outside,
     and an un-overridden sibling reports the outside value;
  6. **`dynamic()`** — null outside, `"default"` inside a frame, a composition scope still wins over it, and the
     real consumer: an **unroutable private `Text.Foundry`** renders **taller** inside the frame and is
     byte-identical again outside;
  7. **ownership** — last-wins on a shared widget, `resetInstance` resurfacing the owner beneath, `false` for an
     owner with no override, re-applying not stacking up, and `removeOwner` (the `:reload` path) restoring **stock
     identity** on every widget + `dynamic()` null again;
  8. **size/aa/colour** — the handle's size is `UI.scale`d, its colour and `aa` are used, and a second site's
     stock does not move the handle's own size;
  9. **the subtree, end to end** (added after the first in-game run) — a real `Label` built with **no override
     installed** (i.e. exactly like every label already on screen when `setFont` is called), both a default-scope
     and an explicit-foundry one: untouched outside the frame, and inside it takes the override family **at the
     override's size and re-renders wider**, a second `restyle()` in the same frame is a no-op (generation stable
     ⇒ no per-frame work), and both are back to their **stock foundry object and stock width** outside. Plus the
     case a plain global `gen++` could never fix: a `Label` **created while the override was installed** still
     restyles on its first draw inside the frame (the `Spec.stamp`), and reverts on teardown.

  **66 ok, 0 failed.**
- **LuaJ parse** of all 8 addon files → ok.
- **In-game (the DoD) — verified ✅** (2nd pass, after the harness fix): open **two** windows (e.g. the Inventory and the Character Sheet), run
  `:hello node` → only the **first** one restyles (caption + labels + button captions), the other stays stock;
  `:hello node` again → back to stock; `:reload hello` with it applied → also back to stock. Then check it composes:
  `:hello font` (a `"default"` override) + `:hello node` → the styled window shows the **node** font, its siblings
  the `"default"` one.

## Gotchas worth keeping

- **A global generation counter cannot express a per-place override.** The `gen ^ stamp` trick is the whole
  invalidation story: it lets *unchanged* call sites detect a *contextual* change. Worth remembering before adding
  a second resolution path to N render sites.
- **The draw pass is a scope stack you already have.** Any "this widget and everything in it" feature (fonts,
  colours, scaling) can ride the parent-first descent instead of a per-widget field.
- **Weak keys, and therefore a flag instead of a list.** An owned-resource list is the project's default pattern,
  but here it would have re-introduced the leak the weak map exists to prevent. `removeOwner` sweeping the registry
  is enough; the flag only decides whether the sweep is worth doing.
- **Geometry still does not follow** (a per-instance override is not a layout engine): a widget's size was
  computed from the stock font, so prefer a handle with no `size=` unless you accept the same clipping caveats the
  scopes document.
- **Pick a demo target with text in it.** The first in-game run styled the **Inventory** window and "only the
  title changed" — correct behaviour, useless evidence: an inventory's children are `WItem` icons, so its only
  text *is* the caption (item names live in tooltips). The engine was fine; the harness was not. `:hello node` now
  **scores each open window by how many descendants report a `:text()`** and styles the richest, printing every
  candidate's score. A near-miss of the [[route-the-surface-not-the-class]] lesson, one level up: the wiring was
  right, the *observation point* was wrong.
- **A widget drawn in two different places in one frame** would thrash its cache (its reported generation would
  alternate). Nothing in the client does this today; it is a correctness-neutral perf note.
