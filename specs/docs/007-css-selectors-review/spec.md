# 007-css-selectors-review — Spec

## What & why

Area `addons` closed `049-css-selectors`: the descendant combinator, the four operators, `[text=]`,
attributes that test the widget they are written on, an ambiguous `find` that **raises**, the scoped
`w:find`/`w:all`, and a caption seam that hands over the whole window. Its tasks wrote the docs as
they landed — **ten pages over four commits** — which is what this area asks of a shipping feature.
But a feature documents the pages it *opens*. `006` held this review out deliberately (ROADMAP), and
`049.5`'s own close is the argument for it: what was still wrong sat in pages the feature never
opened, written in the **negative**, invisible to every grep aimed at the new syntax. **Twenty-five
pages mention a selector; `049` opened ten.**

## Acceptance criteria

- [ ] **The census, both ways.** One table over the `049` surface — `Selector`, `UiApi`,
      `LuaWidget`, `LuaSelectorWatch`, `Sheet` — listing every grammar fact the engine holds against
      the page that states it, and every selector claim the tier makes against the file that proves
      it. No row is `GAP` and none is `INVENTED`; each correction cites the `src/` file in the task
      report, never on the page.
- [ ] **Every wrong claim on the ten pages `049` wrote is corrected**, and the report names each
      one with what the source actually does. A page that was already right is reported as checked,
      not rewritten.
- [ ] **The fifteen pages `049` never opened are read against the new grammar** — the ones that
      mention a selector, quote one, or state what a lookup answers. Every selector string in the
      tree parses, every `hafen.ui():find(…)` example is unique enough not to raise, and no page
      still teaches the attribute that walked up the tree.
- [ ] **The negatives sweep.** Every sentence in the tier that says what a selector *cannot* do is
      read and re-stated against what ships, in the present tense. `049.5` found three of these;
      this task's report says how many exist and that each is true.
- [ ] **`find`'s contract is stated once.** The raise-on-ambiguity rule, the absence case and the
      scoped pair belong to one page; every other page links it rather than restating it (§6, the
      claim made twice). Collection `:find(filter)` — a different verb on twenty-odd pages — is
      confirmed untouched and stays "the first that matches".
- [ ] **The refused spellings are settled against D-013.** `inventory[title=…]`, `*[title=…]`, bare
      `[title=…]` and `window[text=…]` are parse errors now; `selectors.md` quotes two of them as
      boundaries, so a bare grep reads non-zero by design. Either an admissible spelling is added to
      §7 with its falsification, or the decision records why none exists and what catches a
      reintroduction instead.
- [ ] **The tree's own figures are re-derived, not carried forward**: page count, total lines, the
      largest pages and the link total, in `STATE.md` and in the IA's §3. The IA's tree text matches
      what `find docs -name '*.md'` returns.
- [ ] **§12 runs whole-tree and is reported as counts with the offenders listed**: links and anchors
      falsified in all five directions, `wc -l` under 300 everywhere, headings, the retired-name list
      re-derived from `Retired.java` with its row count and one plant per entry, symbols both ways
      with every unregistered colon verb accounted for, and §2's change-note constructions read
      rather than counted.
- [ ] **Any engine or API defect found is filed to area `addons`**, named in the report and in
      `STATE.md`'s open list, and **never fixed here**.

## Out of scope

- **Any change to `src/`, to the `hafen.*` API or to a shipped addon.** This area ships no code
  (AREA.md); a finding is filed, not fixed.
- **Re-reviewing the surfaces `005` and `006` closed**, except where a `049` claim reaches into one.
- **Restructuring the `api/ui/` tree.** No page is near the ceiling — the largest is 286 and
  `selectors.md` is 205 — so no split is priced here. A split only happens if a correction pushes a
  page over 300.
- The four things `049` left out (`>`, pseudo-classes, sibling combinators, `,` lists): they are
  boundaries to state correctly, not gaps to document as coming.

## Context files

- `specs/docs/design/style-guide.md` — the standard; §6, §7, §9 and §12 are what this feature runs
- `specs/docs/design/information-architecture.md` — §3's tree and figures, which this feature
  re-derives
- `specs/docs/decisions/docs-standard.md` — D-006, D-008, D-013, D-015 (the one-liners via
  `grep "^### D-"`; open only the entry named)
- `specs/addons/049-css-selectors/` — spec, plan and tasks: what shipped, and each task's close
  note, which is where the deltas the pages must match are recorded
- `src/io/brodgar/addon/Selector.java` — the parser and matcher: the grammar of record
- `src/io/brodgar/addon/UiApi.java` · `LuaWidget.java` — `:find`/`:all`, the scoped pair, `text`/
  `role`, and the `:on()` placement seam
- `src/io/brodgar/addon/LuaSelectorWatch.java` · `Sheet.java` — the other two consumers of the parser
- `docs/addons/api/ui/selectors.md` + the nine other pages `049` wrote, and the fifteen that mention
  a selector and it never opened — the tier under review
- `specs/docs/006-act-dissolved-review/` — the previous review: its census shape and §12 report are
  the format this one follows
