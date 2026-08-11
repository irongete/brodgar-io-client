# 034-ui-stylesheet-tree — Tasks

<!-- MAX 60 lines. One task = one session: self-contained, compiles, verifiable through TESTING.md. -->

- [x] **034.1 — resolve a tree key, and let Lua read it.**
      Tree keys stop being inert: fold the matching rules for one widget (owner order, then 030's specificity
      rank) into one resolved style, cached in a **`WeakHashMap<Widget, Resolved>`** (identity for free; weak
      keys are mandatory — a list of styled widgets pins closed windows) with the sheet generation it resolved
      at. **`w:style()`** returns it, `nil` when nothing matches — the "nil means stock" contract that keeps the
      identity fast path honest. **Nothing is drawn differently in this task**; resolution is observable and
      that is the point.
      **Suite `addons/034-ui-stylesheet-tree.1/`** — fully automated, **zero `[manual]`**: `w:style()` is nil on
      a stock client; a `[title=]` rule reaches a matched widget and not an unmatched one; the specificity fold
      picks the right rule with two competing keys; removing a sheet returns every widget to nil; an unknown
      property is still refused, saying which. Assert through `w:style()` only.

- [x] **034.2 — draw it: F5's frame, widened.**
      In the parent-first `Widget.draw` descent, a widget with a non-null resolved style opens the frame F5
      already opens, carrying a **style** instead of a `FontHandle` — so every routed site becomes tree-capable
      **with no second edit**, and the subtree is covered by being inside it. The `gen ^ stamp` contextual check
      is unchanged in shape, but the **stamp is derived from the rule set** (same rules ⇒ same stamp): a stamp
      that varies rebuilds every site every frame. Colour goes through 033.2's `Text.Foundry.fixcol` marker or
      it silently does nothing on the sites that pass colour per render. Confirm — do not assume — that a tree
      rule outranks a site rule where both reach the same widget, the frame being nearer the draw.
      **Gate on measurement** (`hafen.client:profiling()`): a cache hit is a lookup, not a match; report the
      descent's per-frame cost. **Resolving per frame is a failed task, not a slow one.**
      **Suite `addons/034-ui-stylesheet-tree.2/`**: assert the stamp is stable across frames (same rules, no
      rebuild) and that a widget created **after** a rule is installed still picks it up — the F5 case a
      captured-generation check silently misses. `[manual]` only for what a program cannot judge — and pick the
      surfaces with 033.3's table in hand: a `color` `[manual]` on an **embossed** surface (`heading`, a plain
      `button` caption) would be a false expectation, because the tile keeps only the alpha.

- [x] **034.3 — `widget:skin{}`, the cut, docs, close.**
      `widget:setFont(h)`/`:resetFont()` **deleted**, replaced by `widget:skin{…}` (set / read / `nil` clears
      that addon's override only) over the same frame — per-instance becomes the top of the cascade rather than
      a font-only special case. D-073 carries over unchanged: a handle's colour never styles a surface.
      Docs: `ui.md`'s **property × key table** grows its tree-key column — **carrying 033.3's emboss caveats
      verbatim**, since a tree rule reaches the same surfaces (`color` inert where `tilemod` keeps only the
      alpha; `button` partial) — plus the overlap rule stated once (bare role ⇒ site; role + refiner, or a role
      with no site ⇒ tree) and the honest note that a `window` rule does **not** reach the window's frame: the
      chrome is a `@DefaultDeco` child with role `nil` (030's inspector), which is C2's job. Sweep `fonts.md`, `conventions.md`, `getting-started.md`, both index tables.
      **Suite `addons/034-ui-stylesheet-tree.3/`**: `widget:setFont`/`:resetFont` read `nil`; `widget:skin{}`
      round-trips and its `nil` clears only this addon's entry; the per-instance level beats the most specific
      tree rule, asserted through `w:style()`; a handle's colour is refused on a surface and still colours the
      addon's own `g:text`. `[manual]`: one subtree restyled by `widget:skin` looks it, siblings untouched.
      **Verify:** every prior suite (and the frozen `hello`) still passes on the same login; the link/anchor
      checker over `docs/addons/` reports 0 broken.
