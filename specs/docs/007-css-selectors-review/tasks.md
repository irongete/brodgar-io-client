# 007-css-selectors-review — Tasks

- [x] **007.1 — the census both ways, and the ten pages `049` wrote.**
      Build `census.md`: every grammar fact `Selector` enforces and every door `UiApi`/`LuaWidget`
      opens on the left, the page that states it on the right, verdicts `OK`/`WRONG`/`THIN`/`GAP`/
      `INVENTED` with `CUT` and `N/A` first-class. Then the reverse direction — every selector claim
      those ten pages make, against the file that proves it. Correct what is wrong.
      **Report:** the table's row counts per verdict; each correction with the `src/` file and symbol
      behind it; each page checked-and-clean named as such. `049`'s spec and close notes say what to
      check, never whether it is true (005.2). Every selector string on the ten pages driven through
      the real `Selector.parse`, not read.
      <!-- extra context: `src/io/brodgar/addon/Selector.java`, `UiApi.java`, `LuaWidget.java`,
           `LuaSelectorWatch.java`, `Sheet.java`; `specs/addons/049-css-selectors/tasks.md` -->
      **Closed.** `census.md`, 62 rows: **44 OK · 7 WRONG · 2 THIN · 2 GAP · 0 INVENTED · 6 CUT · 1 N/A**.
      Eleven corrections on six pages; `replace.md`, `ui/README.md`, `style/README.md` and `theming.md`
      checked and clean. The one the standard could never have caught was `keys.md` listing the refused
      `[title=…]` as a valid tree key — found by driving the **concretised** notation through the real
      `Selector.parse` (49/49 strings, harness falsified 4 ways). Three more came from `049.4`'s close note
      copied as prose about the inspector; `widget.md` disagreed with itself on staleness. Also filed to
      area `addons`: `UiApi.java:257`'s `:text()` roster comment has been missing `CheckBox` since 040.4.

- [x] **007.2 — the fifteen pages `049` never opened, and the negatives.**
      Read them whole — not grepped for the new syntax, which is already green. What is being hunted
      is the sentence a grammar change leaves false: a page-level generalisation about what a lookup
      answers, a `hafen.ui():find(…)` example that is legal but **ambiguous** against a real HUD, link
      text that names the old rule at a live target (003.1, 006.3), and a boundary written in the
      **negative** that now promises what ships (`049.5` found three; this task says how many exist
      and that each is true). Keep collection `:find(filter)` — "the first that matches", twenty-odd
      pages, untouched by `049` — separate from `hafen.ui():find(sel)` throughout.
      **Report:** every page read, with clean stated as clean; the negatives found and their new
      wording; the prose-name and link-text greps with their hit counts read rather than counted;
      the `find` examples classified unique / ambiguous, with the ambiguous ones' fix.
      **Closed.** The fifteen are derived, not remembered — `grep -rlni selector docs/` reads **25**, minus
      `049`'s ten — and `items.md`/`runtime.md` were read too, carrying a lookup example without the word.
      **Nineteen negatives about this surface exist across the tier; seventeen are true.** The two that were
      not said the same thing on the two halves of one pair: `style/keys.md` and `style/surfaces.md` both
      claimed "no selector ever finds it" of a window's chrome, where the true fact is one word narrower —
      no *role* classifies it — while `Window.chdeco` adds the deco as an ordinary child (`Window.java:145`)
      and `selectors.md` teaches `window[title=…] @DefaultDeco`. The same bullet called the close button
      unclassified; `DefaultDeco.cbtn` is an `IButton` and `LuaWidget.role` answers `button`. Four more
      corrections: `items.md`'s `find("window[title=Chest]")` was legal but **ambiguous** (the tier's own
      `replace.md` says two containers can be open), now an `appear` subscription; `interactive.md`'s "one
      selector still **finds** every button" → "matches"; `debugging.md`'s "the enclosing window" →
      "captioned", agreeing with the line 007.1 had already fixed above it; `api/README.md`'s index row
      omitted the lookups. **Twelve of the fifteen were clean and are reported as clean.** All 14 "the first
      match"/"first that matches" hits are the collection verb, untouched and correct. 21/21 selector
      strings driven through the real `Selector.parse` (falsified 4 ways); `>`, `:hover`, `:first-child`
      and `,` all raise, so `style/README.md`'s four-way boundary holds. Links 1546/0, falsified five ways.

- [ ] **007.3 — the standard, the figures, and §12 whole-tree.**
      Settle the refused spellings against D-013 — `inventory[title=…]`, `*[title=…]`, bare
      `[title=…]`, `window[text=…]` — either admitting a spelling into §7 with its falsification in
      the same task (004.2), or recording as **D-019** why none exists and what catches a
      reintroduction instead. State `find`'s contract on one page and link it from the others (§6).
      Re-derive the tree's figures — pages, lines, the largest, the link total — into `STATE.md` and
      the IA's §3, whose text must match what `find docs -name '*.md'` returns.
      **Report:** §12's checks over the whole tree, as counts with the offenders listed — links
      falsified five ways including a broken wrapped link and a valid one; `wc -l` under 300
      everywhere; headings; the retired-name list re-derived from `Retired.java` with its row count
      and one plant per entry; symbols both ways with every unregistered colon verb accounted for
      (a computed registration reads unregistered and is live — 006.4); §2's change-note
      constructions read, not counted.

**Filed, not fixed** (all three tasks): an engine or API defect goes to area `addons` — named in the
report and appended to `STATE.md`'s open list. Nothing under `src/` or `addons/` is touched here.
