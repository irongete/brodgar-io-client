# 030-ui-selectors — Spec

## What & why

**One way to point at a part of the UI.** `029` gave `hafen.ui` one entity; this gives it the vocabulary to
*name* one. A **selector** is a string, and **`hafen.ui` becomes callable** — the selector **is** the lookup:

```lua
hafen.ui("window[title=Cupboard]")   hafen.ui.all("button")   hafen.ui("inventory@Equipory")
```

Grammar, deliberately tiny, CSS-shaped because that is the mental model everyone already has: `*`, a **role**,
plus the refiners `@Class` (widget class), `[title=…]` and `[res=…]`. **No descendant selectors** in v1.

Most roles are **not invented here**: they are the names `016-fonts` established and routed one surface at a
time ([Fonts.SCOPES](src/haven/Fonts.java:75)). The promotion is **not 1:1** — of its 11 entries, `"default"`
is a font-cascade fallback and becomes **`*`**, the other 10 carry over verbatim (`window.title`, `heading`,
`button`, `label`, `textentry`, `tooltip`, `menu`, `chat`, `world.nick`, `world.speech`), and **two roles are
new**: **`window`** (the frame itself — fonts only ever needed its title) and **`inventory`**. Reusing those
names is what keeps this from becoming a second vocabulary.

> **Honest scoping, because it is easy to over-promise here.** Those scopes give the **vocabulary**, not the
> mechanism. Fonts resolve *scope → override* **at each render site**; a selector must answer the **inverse** —
> *what role is THIS widget?* — and nothing answers that today. **A widget→role classifier is new code and is
> the heart of this feature.**

Feature **B2** of the run (A ✅ → B1 ✅ → **B2** → B3 replacement owns the window lifecycle → C
`hafen.ui.skin` → D window chrome → E layout). Everything downstream consumes this vocabulary, which is why
it ships before any visual property exists.

**The inspector ships in this feature, not at the end.** A selector system without one is unusable — nobody
guesses a widget's role, and `hafen.ui.at()` + `:rootpos()` (015) already do 90% of the work. It is what turns
"powerful" into "easy".

## Acceptance criteria

- [ ] `hafen.ui("window")` answers the **first** match or `nil`; `hafen.ui.all("window")` answers every match
      (empty array, never nil). `hafen.ui()` with no argument is the **root** widget, and `hafen.ui.root()`
      is hard-cut (D-012 — the no-arg collection form *is* the tree).
- [ ] Every grammar element resolves in-game against real windows: `*`, each role, `@Class`, `[title=]`,
      `[res=]`, and a role+refiner combination.
- [ ] **Roles are classified, not guessed**: `w:role()` answers on any widget (`nil` when none), and the
      mapping is documented — `Window`→`window`, `Inventory`/`Equipory`→`inventory`, …
- [ ] **D-063 in practice**: `[res=]` matches a `.res`-published window whose **title is localized or absent**;
      the docs state `[title=]` is the convenient key and `[res=]` the correct one.
- [ ] **A late caption re-resolves itself**: a window whose title lands a tick after creation still matches
      `[title=…]`, with no polling or racing in the addon.
- [ ] `hafen.ui.on(selector, "appear"|"disappear", fn)` fires for widgets matching a selector; it **replaces
      `onWidgetCreate`'s descriptor** (`desc.type/place/caption/parentType`) as the discovery primitive.
- [ ] A malformed selector errors naming the offending part and listing the valid roles.
- [ ] **Cost is measured, not assumed** (`hafen.client:profiling()`, the 029 method): one `hafen.ui.all("*")`
      over a real UI, and the per-tick cost of an `on()` subscription with nothing matching.
- [ ] `widgetstack` gains the **inspector**: hovering shows role / class / title / res / matching selectors,
      and `hello` checks the grammar once per login; full regression passes.

## Out of scope

- **Descendant selectors** (`window[title=X] button`) — costly and rarely needed; addable later.
- **Any visual property** — `hafen.ui.skin` is C. B2 ships the vocabulary and the matcher, nothing that draws.
- **`replace(selector, fn)`** — deliberately deferred to **B3**, and not merely for size: `replace` matches at
  **widget-placement** time against a *descriptor* (`place`/`parentType` are the **server** parent — a live
  `maininv.parent` is already a client-side `Hidewnd`), so a live-tree selector cannot express what
  `context="main"` needs. Converting it is a semantic change, not a rename.
- `inventory()`/`equipment()` are **not** cut: they answer "the player's own", where the `inventory` role
  answers "every open container" — different questions (`hand()` stays a snapshot, 029). No selector cache
  either: on-demand only, and an addon holds its result for free since entities are interned.

## Context files

- `design/20-widget-introspection.md` — the tree walk + hit-testing the matcher and inspector ride; and
  `design/21-fonts.md` — the scope table: the role **names**, and the render-site model this feature inverts
- `design/08-widget-replacement.md` — the descriptor (`place`/`parentType`) and why `replace` stays out
- `src/io/brodgar/addon/LuaWidget.java` — the 029 entity: interning, liveness, `typeName`, where `:role()` lands;
  `UiApi.java` — the `hafen.ui` namespace and the `onWidgetCreate`/`onWidgetPlaced` seams
- `src/haven/Fonts.java:75` — `SCOPES`, the names promoted (read-only); `AddonManager.java` — namespace install
- `docs/addons/api/ui.md`, `fonts.md`, `conventions.md` — the surfaces extended
- `029-widget-oop/` — prior art: the entity, its interning and the measured costs (D-063 keys: `027-meters-oop/`)
- `addons/widgetstack/main.lua`, `addons/hello/main.lua` — the inspector's home; the harness (+ its `onWidgetCreate` use)
- `learnings/ui-widgets.md`, `learnings/widget-replacement.md` — **grep, never read whole**;
  `decisions/widgets-ui.md` (D-041/042), `decisions/architecture-api.md` (D-012, D-056, D-063, D-064/65/66)
