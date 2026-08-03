# 035-ui-chrome — Tasks

<!-- MAX 60 lines. One task = one session: compiles, verifiable through TESTING.md. -->

- [x] **035.1 — `window.frame`, and a `Deco` fed by the sheet.**
      `window.frame` joins the site keys; `bg` and `border` become sheet properties (plain data:
      `bg = {color=…}|{image=…}`, `border = {image=<asset>, slice={l,t,r,b}}`), folded per property by
      `Fonts.combine` like `font`/`color` (D-076). New `SkinDeco extends Window.DragDeco` paints `drawbg`/
      `drawframe` from the resolved rule while keeping `DefaultDeco`'s structure and its `iresize`/`contarea`
      contract — and **keeping F3a's caption routing**, or `window.title` stops working the moment a theme is
      installed. Swap with the public `chdeco` when a rule names a window, swap **the stock object** back when
      none does; drive it from `Window.tick` (never inside a draw, never mid-`animst`), one `// addon:` line.
      **Geometry stays stock in this task** — `pad` is 035.2.
      **Suite `035-ui-chrome.1/`**: assert `widget:style()` reports `bg`/`border`; that a window's
      `size()`/`pos()` are **unchanged** by a frame rule (geometry is not yet in play); that an unknown property
      still errors (D-072); and that dropping the sheet restores stock. `[manual]`: the frame looks restyled,
      and drag / resize / close / focus still behave.

- [ ] **035.2 — geometry: `pad`, and the insets that move content.**
      `pad` and a sliced border's own insets feed `iresize`/`contarea` — the one place that decides where
      content starts. Remember the direction: **the ctor's `sz` is the CONTENT size and the deco sizes the
      frame around it**, so `pad` grows the window's outer size for fixed content, not the reverse. A window
      re-packs when the rule changes; a surface that cannot re-lay-out **ignores** the property (inert, never an
      error). Survey which surfaces can, and write the answer into the table — measured, not assumed.
      **Suite `035-ui-chrome.2/`** — this is the task where the automation pays: assert the **numbers**. A `pad`
      rule changes a window's content area read through `widget:size()`/`:pos()`; removing it restores the
      exact previous numbers; a `pad` on a surface that cannot re-lay-out leaves its numbers untouched and
      raises nothing. `[manual]`: content sits where the padding says, and nothing overlaps the frame.

- [ ] **035.3 — the window-less panels (`IBox`).**
      `IBox` is an interface with `draw(g, tl, sz)`, so a sheet-fed implementation drops in where the panels
      build theirs. **Survey first** which panels are actually reachable (`Frame`, `FlowerMenu`, `GItem`,
      `BuddyWnd`, `FightWnd`, `Partyview`, `SListMenu`, `Speaking`) and cover the ones that are, then record
      the rest as honest gaps rather than implying coverage.
      **Suite `035-ui-chrome.3/`**: assert through `widget:style()` that a rule reaches a panel widget and not
      its neighbours; assert the un-named case is untouched; assert teardown restores. `[manual]`: one panel
      (a flower menu is the easiest to summon) looks restyled and still clicks through correctly.

- [ ] **035.4 — cost, docs, the theme, close.**
      **Measure** (`hafen.client:profiling()`, the 029/030 method): the per-frame draw cost of a fully restyled
      client against stock, reported in the handoff, and confirm **no Lua runs per frame** to paint chrome.
      Docs: `ui.md`'s **property × key table** gains `bg`/`border`/`pad` columns and the `window.frame` row,
      each cell measured — including which keys ignore geometry and why — beside the existing emboss caveats;
      sweep `fonts.md`, `getting-started.md`, both index tables. Extend `specs/codebase/gameui-windows.md` with
      the `Deco` contract (`iresize`/`contarea`/`chdeco`). `addons/theme/` gains a frame in its `theme.json`.
      **Suite `035-ui-chrome.4/`**: the whole sheet from JSON applies and reverts; `[manual]`: the themed
      client looks coherent and the stock client is unchanged with the theme off.
      **Verify:** every prior suite (and the frozen `hello`) still passes on the same login; the link/anchor
      checker over `docs/addons/` reports 0 broken.
