# 033-ui-stylesheet — Plan

## Approach

**The headline: C1a changes *who fills* the override stack, not *how sites read it*.** Every routed render site
keeps calling `Fonts.foundry(scope, stock)` / `Fonts.style(scope)` exactly as it does today. **No render site is
re-routed and no drawing code changes** — `hafen.ui.skin{}` simply becomes the thing that pushes onto the
owner-tagged stack that `setFont` used to push onto. That is why an eleven-surface feature is three tasks.

1. **A sheet is parsed once into (key → property table).** Keys go through the existing `Selector` parser, so
   the grammar is shared and a malformed key errors the same way it does in `hafen.ui(sel)`. A key is then
   classified: a **site key** (a `Fonts.SCOPES` name, or `*` for `default`) contributes to the provider stack; a
   **tree key** parses fine and is **inert** in C1a — it must never error, or a C1b-ready sheet cannot load.
2. **One sheet per addon = one owner-tagged entry per site key it names.** Re-applying replaces that addon's
   entries; `hafen.ui.skin(nil)` and teardown pull them (`Fonts.removeOwner` already exists and already bumps
   `gen`). D-043's model is reused **literally** — last applied wins, always restorable — so two addons, a
   `:reload` and a relog all behave without a line of new conflict logic.
3. **`*` fills the `"default"` scope.** The cascade in C1a is therefore the one that already exists: exact site
   rule → `default` → stock. Specificity ranking only starts to matter when tree keys arrive (C1b).
4. **`color` is a sheet property, and that settles a duplication.** Colour *already* reaches a routed site
   today, by riding the `FontHandle` (`Fonts.Style.color(stock)`, F3d). Keeping both would be two ways to colour
   one surface. **Rule: a surface's colour comes from the sheet; a handle's colour applies only to your own
   drawing** (`g:text`, your own widgets). A handle installed on a surface contributes font/size/aa and its
   colour is ignored there. This is a **deliberate behaviour change** from F1–F5 and is verified in-game, not
   assumed.
5. **Invalidation is untouched**: apply/drop bumps `Fonts.gen()`, each site re-resolves on the `(gen, foundry)`
   check it already carries, only *visible* text pays it, once per bump.

## Files to create / modify

- `src/haven/Fonts.java` — the stack's **input** widens from a handle to a resolved spec (`font`, `color`, `aa`);
  `foundry`/`style`/`gen`/`removeOwner` and the per-stock cache stay as they are.
- `src/io/brodgar/addon/FontApi.java` — `setFont`/`reset`/`scopes` **deleted**; the teardown sweep kept and
  re-pointed; `hafen.font(name)` untouched.
- **create** `src/io/brodgar/addon/Sheet.java` — parse a Lua sheet into keys + property tables, classify
  site/tree, hold the addon's entries.
- `src/io/brodgar/addon/UiApi.java` — `hafen.ui.skin{…}` / `(nil)` installs and drops.
- `src/io/brodgar/addon/Selector.java` — reused unchanged for key parsing (a site key is a bare name it already
  accepts as a role).
- `docs/addons/api/fonts.md` — rewritten around the sheet (the F-slice history collapses into "fonts are a sheet
  property"); `ui.md` gains `hafen.ui.skin` + the **property × key table**; `conventions.md`, `getting-started.md`.
- `addons/hello/main.lua` (contract check) and a small **theme** example addon carrying a `theme.json`.
- **No new `specs/codebase/`** expected: `Fonts.java` is the addon layer's own provider, already covered.

## Risks & gotchas

*(prior art: `learnings/fonts.md` — grepped, not read whole)*

- **The identity fast path is load-bearing.** `Fonts.Style` returns `null` when nothing overrides, and a site
  hands back the **same** stock object — that is what keeps an addon-less client byte-for-byte stock *and* makes
  teardown exactly reversible. A sheet that always produces a spec (even an empty one) would silently destroy it.
- **One scope fronts several sites with different stocks.** `"textentry"` covers `TextEntry.fnd` (serif 12, entry
  colours) *and* `ConsoleHost.cmdfoundry` (mono 12, wheat); `"heading"` likewise. Each site must keep passing
  **its own** stock, and a rule with no explicit size/colour must keep inheriting it — otherwise one `skin` call
  makes half the client look wrong.
- **Colour is a blit tint, not a foundry property** (F2): glyphs render white and the colour is applied around
  the blit. The sheet's `color` must reach *that* path, not be baked into a foundry, or one cached foundry per
  colour reappears and 026's text cache starts thrashing.
- **`RichText.Foundry.derive` silently drops a `Parser` subclass** — chat composes its foundry from
  `Fonts.style` scalars instead of deriving. The sheet must not "simplify" that back into a derive.
- **`Fonts.gen()` is not frame-global** (026: F5 mixes a per-instance stamp in). It is a cache key, never a
  clear-everything signal.
- **A `static final Tex` baked at class-init can never follow an override** — a known coverage limit, not a bug
  to chase; it belongs in the property × key table as "ignored here".

## Discarded alternatives

- **A new provider beside `Fonts`** — rejected: the stack, the per-stock cache, the `gen` check and eleven
  routed sites already exist and are proven; C1a changes the input, not the machinery.
- **Keeping `setFont` as a shortcut** — rejected (D-012): two ways to style one surface, which is the exact
  thing the sheet exists to end.
- **Letting a handle's colour keep colouring surfaces** — rejected: it would leave two answers for "what colour
  is this text", one of them invisible in the sheet.
- **Erroring on a tree key in C1a** — rejected: a sheet written for C1b must load today, unstyled, not blow up.
- **Applying the sheet per frame** — rejected before it was written: 030 measured `all("*")` at 0.08 ms/625
  widgets; the provider's `gen` check is one int compare and is what "live" already means here.
