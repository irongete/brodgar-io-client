# 016-fonts — Plan

> History: this work appears in git history and `learnings/` tagged **F1, F2, F3a, F3b,
> F3c, F3d, F3e, F4, F5** (the F-series). F3 split by RENDER SITE, not by scope count —
> each chrome scope is a different beast.

## Approach
- **Provider + gen, not a mutable global** (F1): `Text.std` is `static final` and ~81 sites
  hardcode foundries — so `haven.Fonts` holds owner-tagged per-scope stacks, routed sites
  call `Fonts.foundry(scope, stock)` (most-specific → `"default"` cascade → stock), and a
  volatile `gen` counter drives invalidation (every routed site: one int compare per frame).
  Owners are opaque Objects → no `haven`→`io.brodgar` edge. `active` fast path = one
  volatile read when no addon touches fonts.
- **Own drawing (F2) is the isolated half**: fast path byte-identical to stock for plain
  text; a RICH path (`RichText.Foundry`, cached per-px on the handle) only for `font=`/
  markup; colour is a blit tint (foundries stay cacheable); `$font` works because `load`
  AWT-registers the family — zero RichText edit.
- **Each chrome scope by its site's nature**: furnace rebuild + cap-cache gen (F3a);
  recorded caption recipe + `redraw()` for the twice-baked Button surface, stock `tf`
  untouched because `Charlist.df` derives from it at class-init (F3b); the cheap ~4-line
  pattern for sites that keep their text source (F3c: TextEntry/ConsoleHost/Label), plus
  the correction that the SURFACE was `CharWnd.attrf`/`SListWidget` rows, not the Label
  class; a new-scope amendment for headings (F3e — folding into label/window.title would
  chain unrelated surfaces); and for the RichText sites (F3d) a new `Fonts.style()` scalar
  primitive (derive drops Parser subclasses; FAMILY+SIZE never FONT or markup dies).
- **The tooltip odyssey (F3d, five in-game passes)**: the real surface is the `ItemInfo`
  composer (17 call sites) → widen to the engine; published-code rows (`ui/tt/q/qbuff`…)
  render via the generic statics → make the scope DYNAMIC (`Fonts.enter/exit/scope()`,
  composers declare context); private foundries in published tips → resolve AT the foundry
  (`Foundry.resolved()`, gated on the declared context); constructor-rendered tips → rebuild
  the info lists on gen moves (guarding the not-loaded-yet nil case); and the one
  class-load-rasterised `static final Text` → **adopt** `ui/tt/slots-alt` v4 via
  `get-code` + `@FromResource` (version-pinned, never `override=true`).
- **World scopes (F4)**: Speaking = an ordinary engine site (recorded string + gen check;
  asks for its NAMED scope — a bubble is not tooltip text); the kin nick = adopted
  `ui/obj/buddy` v4 (route the shared `InfoPart.fnd`, call the `dirty()` the author
  already wrote).
- **Per-instance (F5) = the dynamic scope keyed on the DRAW PASS**: `Fonts.frame(widget)`
  around child draw (two 3-line core edits, shared no-op singletons — zero allocation),
  `resolve()` consults the frame first (an instance override claims every scope at once);
  invalidation via **`gen ^ stamp`** (a global counter can't express a per-place override —
  the stamp makes unchanged call sites detect contextual change, incl. labels created
  after the override); `WeakHashMap` keyed on identity → a flag on `Addon`, not a list.

## Files created / modified
- `src/haven/Fonts.java` — new (the provider); `FontApi.java`, `FontHandle.java` — new
- Routed: `Text`, `Label`, `Window`, `Button`, `TextEntry`, `ConsoleHost`, `CharWnd`,
  `GridList`, `SListWidget`, `BAttrWnd`, `SAttrWnd`, `MenuSearch`, `SListMenu`,
  `FlowerMenu`, `MenuGrid`, `ChatUI`, `UILoop`, `Widget`, `ItemInfo` + the 14 tooltip
  files, `Speaking`, `Equipory`, `SkillWnd`, `Fightsess`, `GameUI`, `OptWnd`, `UI`
- Adopted: `src/haven/res/ui/tt/slots_alt/`, `src/haven/res/ui/obj/buddy/`
- `addons/hello/` — v0.47→v0.55.1 (eleven independent `:hello` font toggles + the
  candidate-scoring `:hello node` demo)

## Risks & gotchas hit (detail: learnings/fonts.md — the richest category, 35 entries)
- "Wiring right, screen unchanged" hit repeatedly: grep the SURFACE (composer call sites),
  not the class the spec names; count `longtip(` sites; score demo targets by what the
  feature can affect.
- `static final Text` rasterised at class-load is unreachable — the question is "when was
  it rasterised?", and the answer sometimes forces resource-code adoption.
- `Warning.warn` is invisible in-game once an ErrorHandler installs; one classloader branch
  swallows silently — instrument the DECISION POINT to stdout.
- Never assume a published resource's name (`slots` vs the live `slots-alt`).
- An invalidation hook must not turn "not loaded yet" into "load now" (the info-list NPE).
- Two cache layers = two invalidations (tooltip Tip text + composed image).

## Discarded alternatives
- A mutable `Text.std` — final, and most sites don't read it.
- `TextAttribute.FONT` overrides — kills `$b/$i/$size` markup.
- Folding headings into label/window.title — chains unrelated surfaces (the amendment).
- `override=true` resource adoption — stale local code beats losing one styled heading.
- A per-widget field + second resolution path in ~20 sites (F5) — the frame does it for free.
