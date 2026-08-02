# 033-ui-stylesheet — Tasks

<!-- MAX 60 lines. One task = one session: self-contained, compiles, in-game verifiable on its own. -->

- [x] **033.1 — the sheet, and the end of the font-scope API.**
      New `Sheet`: parse a Lua sheet once into (key → property table), keys through the existing `Selector`
      parser so the grammar and its errors are shared. Classify each key — a **site key** (a `Fonts.SCOPES`
      name, or `*` for `"default"`) pushes an owner-tagged entry on the provider stack; a **tree key** parses
      fine and is **inert**, never an error, so a C1b-ready sheet loads today. `hafen.ui.skin{…}` installs and
      **replaces** this addon's previous sheet; `hafen.ui.skin(nil)` drops it; teardown pulls it through the
      existing `Fonts.removeOwner`, which already bumps `gen`. `font` is the only property in this task.
      **Hard cut**: `hafen.font.setFont`/`reset`/`scopes` deleted (`hafen.font(name)` untouched).
      **No render site is re-routed** — every site keeps calling `Fonts.foundry(scope, stock)`; what changes is
      who fills the stack. Keep the `null`-means-stock identity fast path, or an addon-less client stops being
      byte-for-byte stock and teardown stops being exactly reversible.
      **Verify:** in-game — a sheet with `["*"]` restyles the client's text live exactly as `setFont("default")`
      did; each of the eleven site keys restyles its surface; `textentry`'s two sites (`TextEntry` and the
      console command line) both stay native in size/colour with one rule; a tree key in the sheet is silently
      inert; `hafen.font.setFont` reads `nil`; `:reload` and disable restore stock.

- [x] **033.2 — `color`, and the two-addon stack.**
      `color` as a sheet property, `{r,g,b[,a]}` 0..255 (the `kin:color()` shape), reaching the **blit tint**
      path (F2: glyphs render white, the colour is applied around the blit) and the `Fonts.style` scalars the
      non-`Foundry` sites read — never baked into a foundry, or one cached foundry per colour reappears and
      026's text cache thrashes. **Settle the duplication**: a surface's colour comes from the **sheet**; a
      handle's `color` applies only to your own drawing (`g:text`, your own widgets) and is ignored on a
      surface. That is a deliberate change from F1–F5 — verify it, do not assume it.
      Then the conflict model, which is D-043 reused and needs no new code: two addons styling one surface.
      **Verify:** in-game — `["chat"] = {color=…}` recolours chat and `["*"] = {color=…}` cascades to unset
      sites; a handle carrying a colour installed on a surface does **not** tint it, while the same handle
      still colours the addon's own `g:text`; with two addons, the last applied wins, disabling it falls back
      to the other rather than to stock, disabling both restores stock, and `:reload`/relog behave.

- [ ] **033.3 — docs, the `theme` example, close.**
      `docs/addons/api/fonts.md` rewritten around the sheet — the F-slice history collapses into *a font is a
      sheet property* — and `ui.md` gains `hafen.ui.skin` plus the **property × key table**: what each of the
      eleven sites accepts, what it ignores, and the honest limits (a `static final Tex` baked at class-init
      can never follow an override). Sweep `conventions.md` and `getting-started.md`; both index tables.
      A small **`theme` example addon** carrying a `theme.json`, its font strings mapped through `hafen.asset`
      in Lua — the proof that a theme needs no code of its own. Plus the task's own suite,
      `addons/033-ui-stylesheet.3/` per `specs/addons/TESTING.md`: a site key, `*`, an inert tree key, the
      two-addon fallback and `setFontGone` asserted, with the look of each restyled surface as `[manual]`.
      **Verify:** the suite's run is all `[pass]` and the `[manual]` lines match; every prior suite (and the
      frozen `hello`) still passes on the same login; the `theme` addon restyles the client from its JSON and
      un-restyles on disable; the link/anchor checker over `docs/addons/` reports 0 broken.
