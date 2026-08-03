# Decisions — Fonts

> Part of the decision log (ADR-lite). Index: [../DECISIONS.md](../DECISIONS.md). Entries are
> verbatim; each `### D-xxx` header line doubles as the decision's one-liner (grep them).
> Append new decisions of this kind here.
> Legend: ✅ Accepted - 🔄 Revisit later - ❌ Rejected - 💤 Superseded.

### D-043 — Fonts are a per-addon `hafen.font.*` system: private handles + owned overrides (NO shared registry) ✅ (maintainer, 2026-07-27)
**Decision.** Add a **`hafen.font.*`** namespace (the **F-series**, spec [21](../design/21-fonts.md)) giving an addon
**complete control over client typography**, built on the client's existing **owned-resource** model — not on a
shared media registry. Three moving parts:
- **`hafen.font.load(source[, opts])` → a private `FontHandle`.** `source` = a `.ttf`/`.otf` under the addon
  folder, or a built-in (`"sans"/"serif"/"mono"/"fraktur"`); `opts = {size,aa,bold,italic,color}` (`size` in
  logical px, `UI.scale`d). The handle is an **opaque, per-addon Lua value the addon holds** ([D-017](security-sandbox.md)
  facade-safe) — **no cross-addon name, no shared table.** Methods `:derive(opts)`, `:family()` (AWT family, for
  `$font`), `:size()`.
- **Apply to the addon's OWN drawing** (isolated, conflict-free): `font =` on `hafen.ui.window`/`widget`,
  `g:text(str,pos,{font=…})`, and per-run `$font[h:family(),sz]{…}` in rich text.
- **Apply to a GLOBAL client surface** via an **owned override**: `hafen.font.setFont(scope, h)` /
  `hafen.font.reset(scope)` / `hafen.font.scopes()`, over an **enumerated** scope set (`"default"`,
  `"window.title"`, **`"heading"`** *(amendment, see below)*, `"button"`, `"label"`, `"tooltip"`, `"menu"`,
  `"chat"`, `"textentry"`, `"world.nick"`, `"world.speech"`). Resolution is **most-specific first**: *per-instance (F5) → scope override → `"default"`
  override → stock foundry*, so `setFont("default", h)` **cascades to everything** unless a scope refines it.
**Rationale.** The maintainer wants total freedom to restyle *anything* AND each addon **independent**. A shared
LibSharedMedia-style registry is rejected: there is no cross-addon code sharing here and it would add a global
name namespace + coupling. A private handle + owned override delivers the same power while fitting the client's
existing teardown model (hooks/overlays/adopt are all owner-tagged and reverted on `:reload`, [05](../design/05-lifecycle-and-reload.md)).
**Scope / limits.**
- **Naming:** `setFont(scope, h)` (maintainer renamed `setScope`→`setFont`). The global default is
  `setFont("default", h)` — **no separate `setDefault` sugar** (one canonical way, [D-012](architecture-api.md)); revisit
  if wanted.
- **Conflict on a global surface is intrinsic:** a scope is shared client state → an **owner-tagged stack,
  last-wins, reverted per owner on teardown** (mirrors [08](../design/08-widget-replacement.md) adopt). Two addons cannot
  own the same surface at once; that is correct, not a gap.
- **Invalidation cost is real:** `Text`/`Tex` are cached at each render site, so the provider carries a
  **generation counter**; `setFont`/`reset` bump it and routed sites/widgets rebuild when it moves. **F1 proves
  this end-to-end** on `"default"` (`Text.std`+`Label`); later slices reuse the pattern.
- **Coverage grows per slice, API does not:** the scope enum is declared in full at F1; each scope becomes
  effective when its render site is routed (`// addon:` one-liners), avoiding a one-shot rewrite of all ~81
  `Foundry` sites.
- **AMENDMENT (maintainer, 2026-07-30, during F3e): the enum grew by one — `"heading"`.** F3c's first in-game pass
  showed the character-sheet *body text* restyling while the embossed fraktur captions **inside** the windows
  ("Base Attributes", "Food Satiations", "Abilities", "Study Report", "Lore & Skills", "Entries", "Quest Log",
  "Health & Wounds", "Martial Arts & Combat Schools", "Kin", the credo group captions, the `GameUI` village cap,
  the quest-completed banner) stayed stock. Those are a **third** surface: not the window's title bar
  (`"window.title"`), not body text (`"label"`). Folding them into `"label"` would chain a 25 px embossed display
  font to 18 px body text (neither restylable alone); folding them into `"window.title"` would tie section headings
  to window chrome. The maintainer chose a **new enum member**, `"heading"` — so `scopes()` now returns **11**
  names. **General rule this sets:** *if two surfaces have different stock recipes/sizes and a user could
  plausibly want one changed and not the other, they are different scopes* — grow the enum (a documented amendment
  + one line in `Fonts.SCOPES`) rather than overload a neighbour. Backings: `CharWnd.catf`/`failf` +
  `GridList.dcatf` (two stocks, one scope — the provider's per-stock cache keeps each size).
- **`$font` needs the family registered:** the existing `RichText` `$font` tag resolves by **AWT family name**
  ([RichText.java:566](src/haven/RichText.java:566)), so `load` of a `.ttf` also `GraphicsEnvironment.registerFont`s
  it — then the tag works with **zero RichText edit**.
- **Engine change ⇒ rebuild + restart** (per project rules); only Lua files hot-reload.
**Alternatives.** Shared named registry / LibSharedMedia (rejected — cross-addon coupling, name collisions, no
sharing exists). Rewiring all ~81 `Foundry` sites up front (rejected — huge, unverifiable in one step; route
per-slice instead). A global mutable `Text.std` reassignment (rejected — `final`; and it misses the many sites
that don't read `std`; the provider + scope enum is the general answer). Exposing raw AWT `Font` to Lua
(rejected — [D-017](security-sandbox.md)).
**Consequences.** Additive: a new `io.brodgar.addon.FontApi` + a `haven`-reachable `Fonts` provider facade +
`FontHandle`; per-slice `// addon:` one-liners at routed render sites (F1 = `Text.std`/`Text.render`/`Label`);
each `Addon` gains an owned font-override list for teardown. **Ungated** (client-only cosmetic — no server
traffic). Rollout F1→F5 in [21](../design/21-fonts.md).
**See.** [21-fonts.md](../design/21-fonts.md), [07-ui-and-drawing.md](../design/07-ui-and-drawing.md) (draw wrapper + `font=` opt),
[20-widget-introspection.md](../design/20-widget-introspection.md) (the `WidgetNode` F5 reuses), [D-012](architecture-api.md)
(one canonical way), [D-017](security-sandbox.md) (facade safety), [05-lifecycle-and-reload.md](../design/05-lifecycle-and-reload.md)

### D-073 — a surface's look is the sheet's; a handle's colour is for your own pixels ✅ (2026-08-02)
**Decision.** (033.2.) `color` becomes a stylesheet property, and two things follow that the F-series had the
other way round. **(a) A rule's colour outranks the colour the SITE asks for.** A provider-built `Text.Foundry`
carries the rule's colour (`Text.Foundry.fixcol`) and renders in it whatever `Color` the caller passes to
`render(text, c)`; the `RichText`/`ChatUI` sites likewise resolve the sheet's colour over the per-render
`TextAttribute.FOREGROUND` they were passing (`ChatUI.fndcol(site)`). **(b) A `FontHandle`'s own `color` no
longer styles a surface** — not through `hafen.ui.skin{font=h}` and not through `widget:setFont(h)`. The handle
still contributes family/size/aa there, and its colour still applies to the addon's **own** drawing (`g:text`,
its own widgets). `size`/`aa`/`bold`/`italic` travel everywhere; `color` is the one option that does not.
**Rationale.** (a) is forced by the client: almost no routed site renders with its foundry's `defcol` — a
`Label` passes its `col`, every tooltip passes `Text.white`, every chat line passes its speaker's `FOREGROUND`.
A colour that only filled `defcol` would have been correct in the provider and invisible on screen, which is the
worst kind of shipped feature. (b) is the duplication (a) creates: with the sheet able to colour a surface, a
handle that *also* coloured it would leave two answers to "what colour is this text", and the losing one is
invisible — it is spelled nowhere in the sheet, only inside a handle built elsewhere. One surface, one place
that says how it looks.
**Scope / limits.** `$col[…]` markup inside the text still wins: it is part of the string, not the site's choice,
so a tooltip's green/red attribute deltas survive a `["tooltip"]` colour rule. `widget:setFont` still outranks
every site rule (chain unchanged). A window caption ignores `color` entirely — `Window.DefaultDeco` tiles a
**texture** over the glyph mask, so there is nothing there for a colour to reach; that is a property of the
surface, not a gap. And the honest cost: while a rule is on, colour that carried *meaning* on that surface is
flattened with the rest (a red warning under `["*"] = {color=…}`), which is why the docs say to key the site
rather than `*` when that matters.
**Alternatives.** Filling only `defcol` (rejected — invisible, see above). Keeping the handle's colour as a
second door (rejected, [D-012](architecture-api.md) — two ways to colour one surface, one of them unreadable).
Baking the colour into the foundry *identity*, i.e. one cached foundry per colour (rejected — 026's text cache
keys on the foundry and would thrash; the colour rides the existing per-stock cache instead).
**Consequences.** `Fonts.Spec` tolerates a `null` base font, so a **colour-only rule** is a first-class rule that
keeps the site's own font; `Fonts.Style.color(null)` is how a rich site asks *whether* the sheet set a colour at
all. Read forward: C1b's `widget:skin{…}` is where a per-widget `color` returns, and this decision is what says
it belongs there rather than back on the handle.
**See.** [D-043](#d-043) (the stack this reuses), [D-072](architecture-api.md) (the other half of the sheet's
contract), [D-012](architecture-api.md), [033-ui-stylesheet](../033-ui-stylesheet/spec.md),
[fonts.md](../learnings/fonts.md), [text-and-fonts.md](../../codebase/text-and-fonts.md).

### D-076 — a cascade level takes the properties it names, not the ones it does not ✅ (2026-08-02)
**Decision.** (034.2.) The font resolution chain — per-instance `widget:setFont` → the **tree rule** covering the
widget → the site rule → the `*` rule → stock — **composes property by property** instead of the innermost level
replacing the ones beneath it (`Fonts.combine`, applied both when a draw-pass frame is pushed and when a site
resolves). A level fills only what it actually names; everything else falls through to the next level down, and
only what nothing sets falls through to the site's stock.
**Rationale.** C1b's own headline example refuted the wholesale reading before it shipped:
`["*"] = {font=body}` beside `["window[title=Cupboard]"] = {color=…}` is the sheet the spec opens with, and
under "innermost wins whole" that window's text would have gone back to the **stock** font — the broad rule
silently cancelled by a rule that never mentioned fonts. 034.1 had already settled the same question one level
down (the tree fold is per property: *"a specific `color` does not take a broad rule's `font`"*), so wholesale
would have meant one cascade obeying two different rules depending on which pair of levels you looked at.
**Scope / limits.** This also changes **shipped F5 behaviour**: `widget:setFont(h)` inside a scope that a sheet
has coloured now keeps that colour instead of reverting the subtree to the site's stock colour. That is the same
rule, applied to the level that already existed, and it is strictly the more useful answer — a handle carries no
colour for a surface anyway ([D-073](#d-073)), so there was never a colour of its own to defend. Nested frames
compose the same way, at push time, so the composition cost is paid once per (inner, outer) pair rather than per
render site.
**Alternatives.** Innermost-wins-wholesale (rejected — above; it is also what makes a "cascade" not one).
Folding the site half into `widget:style()` so the read shows the composed answer (rejected —
[D-075](widgets-ui.md): a read answers for the thing you point at, and the site half is only decided at the draw,
where the site is known). Composing at each render site instead of once in the provider (rejected — that is a
second resolution path, the thing C1b exists to avoid).
**Consequences.** The composed `Spec` must be **interned by the identity of its two halves**, because
`Fonts.gen()` mixes the frame's stamp into the generation every routed site compares against: a freshly minted
composition per resolve would hand every site a new generation every frame and rebuild the whole client sixty
times a second. The interning table is dropped whenever `gen` moves, so it never outlives the chain it composed.
**See.** [D-043](#d-043) (the chain), [D-073](#d-073) (whose colour a surface takes),
[D-075](widgets-ui.md) (why the read stays tree-only), [034-ui-stylesheet-tree](../034-ui-stylesheet-tree/spec.md),
[fonts.md](../learnings/fonts.md).

### D-077 — per-instance is a LEVEL of the cascade, not a mechanism beside it ✅ (2026-08-03)
**Decision.** (034.3.) `widget:setFont(h)` / `widget:resetFont()` are a **hard cut**, replaced by
`widget:skin{font = h, color = …}` — read / set / `nil` clears this addon's entry, arity as the verb (D-056) —
carrying the very properties a sheet rule carries. And the store moved with the verb: the per-widget override
registry left `haven.Fonts` entirely (`instances`/`pushInstance`/`resetInstance`/`instanceTop`, ~100 lines) and
became the **top level of the one per-widget fold** in `io.brodgar.addon.Sheet`, above every tree rule. The
provider keeps the F5 frame and asks its one source what a widget resolves to.
**Rationale.** Two things forced the same answer from opposite ends. From the API side, a font was never a special
case — only the first property that existed — so a font-only verb beside a two-property sheet is a second
vocabulary for "restyle this"; C1a already made that argument for scopes (033.1) and this is the same argument one
level down. From the implementation side, `widget:style()` must report a **`FontHandle`**, which the provider does
not keep (it stores an AWT font, a size and a flag), so keeping the store in `Fonts` would have meant a second
addon-side record beside it — two stores for one cascade level, free to drift exactly where they overlap. Deleting
the store beats reading it back: what is left is one place that resolves a widget, and it cannot disagree with
itself.
**Scope / limits.** A handle's own `color` is still **ignored** on a surface ([D-073](#d-073)) — `skin{font=h}`
takes family/size/aa and `widget:style()` reports no colour — because refusing it here would contradict the
sheet, which ignores it. `w:skin()` reads back **your own entry**, not the resolved style: what you wrote comes
back unchanged, which is a different question from what the widget resolves to ([D-075](widgets-ui.md)), and it
is why both verbs exist. An empty table carries no property and is therefore the same as no entry, exactly as a
sheet rule with no property styles nothing. A skin change invalidates the whole per-widget generation rather than
one entry: it costs one re-fold per widget on its next draw, is what installing a sheet already does, and keeps
ONE rule for "the cascade changed".
**Alternatives.** Keeping `Fonts.pushInstance` for the draw and adding a parallel addon-side record for the read
(rejected — the drift above, and it is the "second resolution path" C1b exists to avoid). Erroring when a handle
carrying a colour is skinned onto a surface (rejected — the sheet ignores it, and one rule beats two). Folding an
ancestor's style into a child's `:style()` (rejected — inheritance happens at the draw; a read answers for the
thing you point at).
**Consequences.** `haven` shrinks: the provider now holds the frame, the scope stacks and the generation, and
nothing per-widget at all — `Fonts.treeStyles`/`treeActive` mean *any* per-widget style, skins included. Teardown
is one sweep in the addon layer (`Sheet.forget` drops an addon's tree rules **and** its skins), and
`Addon.fontNodes` became `Addon.skinNodes`.
**See.** [D-043](#d-043) (the chain), [D-073](#d-073) (whose colour a surface takes), [D-076](#d-076) (each level
takes what it names), [D-075](widgets-ui.md), [D-056](architecture-api.md) (arity is the verb),
[034-ui-stylesheet-tree](../034-ui-stylesheet-tree/spec.md), [fonts.md](../learnings/fonts.md).
