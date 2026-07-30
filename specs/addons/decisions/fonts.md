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
