# Learnings — auditing, sweeping and maintaining the docs tier

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- **(001.1) A coverage matrix needs `CUT` and `N/A` as first-class verdicts, or its own close is
  unachievable.** The natural vocabulary is two values — documented / GAP — and it is wrong twice
  over on a project that hard-cuts. Of 140 `addons` tasks, **11 shipped an API a later task
  deleted** (`hafen.key`, `ui.adopt`, `onWidgetCreate`, `node:setFont`, the flat gob table, the
  master switch, …) and **8 shipped no user-facing surface at all** (an internal refactor, a GL bug
  fix, a spec-only closure). Calling those 19 rows GAPs would have manufactured 19 pieces of work
  that must not be done, and would have made the feature's own acceptance criterion — *at the close
  every row is covered* — permanently unreachable. The rule generalises: **before counting
  coverage, name the states in which "not documented" is the correct answer.** A `CUT` row also
  earns its keep, because it is exactly where an obituary is likely to be sitting in the prose.

- **(001.1) Rebuild the task list from each feature's own `tasks.md`, never from the features
  index.** `FEATURES.md` is one line per feature summarising what the feature *achieved*, so a task
  that shipped nothing user-facing (001.4's `AddonManager` split, 005.4's tooltip fix) has no trace
  in it, and the matrix silently loses the rows most likely to be miscounted. Expect the formats to
  differ: across 36 folders this project uses **three** (`- [x] NNN.X — …`, `- [x] **NNN.X — …**`,
  `## NNN.X — … ✅ DONE`), so one grep finds two thirds of the tasks and looks complete. Grep for
  each shape and **reconcile the count against the ranges `FEATURES.md` declares** before believing
  the list.

- **(001.1) A link checker's slugger is where it lies to you, and a "0 broken" is only trustworthy
  when the checker has been falsified in BOTH directions.** The first run over `docs/` reported
  **208 broken anchors** — every single one false. The bug: an em dash surrounded by spaces
  (`## Selectors — naming a widget`) slugs to a **double** hyphen (`selectors--naming-a-widget`),
  because GitHub strips the dash and then maps each remaining space to a hyphen; the checker
  stripped the dash first and then collapsed the resulting double space with `\s+ → -`. 035.4's
  discipline (verify against a *planted break*) would not have caught this: the checker was
  over-reporting, not under-reporting. The complete rule is **plant a break and confirm it is
  found, then confirm a known-good corpus reports zero** — a checker that flags a third of a healthy
  tree is as useless as one that flags nothing, and far more expensive, because it reads as a
  finding.

- **(001.1) Zero broken links is compatible with the docs being structurally wrong — the metric that
  finds duplication is OUTBOUND LINKS TO ONE ANCHOR.** `ui.md` owns selectors, the cascade, the
  property tables and the layout rules, and links out to `fonts.md#restyle-one-widget--widgetskin`
  **eight times** rather than documenting the top of its own cascade. Every one of those links
  resolves, so the sweep is green while two pages document one subject (~250 duplicated lines). When
  a page repeatedly links *out* for something it is the natural home of, the subject is in the wrong
  place — count the repeats per target anchor, not just whether each one resolves.

- **(001.1) A checker scoped to the docs tree cannot see the links that LEAVE it.** 16 links under
  `docs/` point at `specs/` and `addons/`. All resolve on disk, so a tree-local checker reports them
  as fine — but seven target `specs/addons/design/*`, which 030.4 recorded as
  *historical-at-write-time* and therefore the one class of document the user-facing tier must never
  cite as truth. The check is not "does the target exist" but **"does this link leave the tier?"**,
  and it has to be written deliberately, because the healthy case (an internal link) and the broken
  case (a link into `specs/`) both resolve.

- **(001.1) The docs can be right where the area's own STATE is wrong.** `specs/addons/STATE.md`
  lists a `ChatMessage` event on the addon bus; that string exists nowhere in `src/`, and
  `api/events.md` correctly omits it. Auditing the docs against `src/` incidentally audits every
  other document that claims the same facts — so when a sweep finds the docs *more* accurate than
  the specs, file the finding rather than discarding it. Ground truth being `src/` is what makes the
  audit able to say which of two disagreeing documents is wrong.

- **(001.2) An audit verdict is evidence, not truth: re-check a name against `src/` before writing
  it into a rule.** 001.1's matrix marked 015.1 `CUT` — correctly, at task level — and that task had
  shipped `ui.root()`, `ui.node(id)` and the `WidgetNode` type together. Turning the row into a
  retired-name grep list retired all three, but **`hafen.ui.node(id)` is live** (`UiApi.java:240`)
  and correctly documented in `ui.md`; only `root` and the transient node type are gone. A
  task-level verdict says what the *feature* did, and a hard cut that replaces two names out of
  three leaves the third standing. Any rule that operates on names is checked at name level,
  against the registration site, before it is allowed to delete anything.

- **(001.2) The only reliable oracle for "does this Lua name exist" is the registration string, not
  a grep for the name.** Grepping `src/` for the retired list hit 4–7 files each for `adopt`,
  `setFont`, `items`, `vitals` and `root` — nearly all of them `haven`'s own Java methods and
  javadoc, with nothing to do with the addon API (`Widget.adopt`; `WidgetNode` survives only inside
  a comment). The addon surface is exactly what the installers register, so the query is
  `grep -rn 'set("<name>"' src/io/brodgar/addon/`; the same sweep over the `hafen` facade and
  `uiT.set(` lists the whole namespace set in one shot, which is what a docs tree's page names
  should be derived from.

- **(001.3) A page template is a skeleton, not a shape for every verb — decide the granularity from
  what the verb needs, or the template fights the rule that motivated it.** The style guide's reference
  template shows one `###` call heading per verb; §6, written from 001.1's finding that the tables were
  right and the prose wrong, says tables carry the facts. Both are the standard, and applying either
  alone is visibly wrong: `hafen.gob` would have become fifteen `###` sections for fifteen one-line
  readers, while `hafen.act`'s ten verbs each need arguments, an error case and a caveat that no row
  holds. The rule that resolved it (D-006) is granularity-by-need, and it is worth reaching for
  whenever two parts of a standard prescribe different shapes for the same thing: **the template says
  what a page contains, not how finely it is cut.**

- **(001.3) The em-dash trap is not about em dashes — it is about any deleted character between two
  spaces.** D-004 banned ` — ` in headings because the slugger drops the dash and maps both surviving
  spaces to hyphens. Writing the group-A call headings surfaced the same failure from a different
  character: `hafen.act.clickGob(gob [, button [, mods]])` slugs to `hafenactclickgobgob--button--mods`,
  because `[`, `,` and `]` are deleted and the spaces around them are not. Optional-argument brackets,
  parenthesised asides and ` / ` between words all do it. The general form (D-007): **a heading must
  contain no slugger-deleted character whose neighbours are spaces.** Checking for the em dash alone
  would have passed a page whose every verb anchor carried a double hyphen.

- **(001.3) A "state the gating on every verb" rule inverts into noise unless it is scoped to the
  verbs that can surprise.** Style guide §6 requires every verb to state its gating *including the
  ungated ones*, which came from drift D-3: `markers.add` and `radar.setVisible` write, are ungated,
  and no page said so. Applied literally, the twenty group-A pages would each have annotated a dozen
  readers as ungated — and the two pages where "ungated" is genuinely surprising would have been
  indistinguishable from the rest. Scoping the annotation to **write groups only** (D-006) made
  `## Write (ungated)` a signal again: it appears on exactly `markers`, `radar` and `menugrid`, which
  is precisely the audit's finding. A completeness rule aimed at an omission needs a scope, or it
  buries the thing it was written to expose.

- **(001.4) Splitting a page is cheap; the expensive half is that every heading you touch is an
  anchor, including the ones you touch by accident.** Group B replaced 5 pages with 22 and the
  rewriting was the small part — 917 links had to resolve at the task boundary, up from 695 across
  the whole tree. The instructive failure was self-inflicted: adding `(ungated)` to four group
  headings (`## Overlays` → `## Overlays (ungated)`) re-slugged them and broke **18** inbound links
  I had written correctly minutes earlier. Nothing about the edit *looked* like a rename. Two rules
  fall out: run the sweep after the last cosmetic pass, not after the last structural one; and when
  a heading carries an annotation, ask whether the annotation is worth the anchor (it was not — the
  gating went into prose, which is now D-006's scope in the style guide).

- **(001.4) A mechanical link re-point needs the longest pattern first and the bare page link last,
  or it eats its own output.** 96 distinct old link targets became 22 pages. As an ordered `sed`
  script that is one pass, but only if `ui.md#tree-keys--which-widgets-not-what-kind-of-surface`
  is substituted before `ui.md#tree-keys…`'s prefix-mates and `ui.md)` comes last of all —
  otherwise the bare rule rewrites `ui.md` inside an anchored target and leaves a link that resolves
  to the wrong page. Anchoring the bare pattern on the closing `)` is what makes it safe. The same
  script then works unchanged on the pages that reach the tier through a prefix
  (`api/ui.md#…` → `api/ui/widget.md`), because the prefix is never part of the match.

- **(001.4) `sed -i` on a CRLF working tree rewrites every line ending, so `git status` shows files
  the task never changed.** Three group-C pages were flagged modified with a zero-line content diff.
  Harmless — git normalises on commit — but it destroys the one signal `/end` relies on, "report
  anything that is NOT part of this task". `git diff --numstat` distinguishes them in one command
  (no rows = line endings only); `git checkout --` puts them back.

- **(001.3) Re-derive an "it works like the client does" sentence from the source, because the
  plausible version is usually the wrong one.** `speed.md` said the write drives the client's own
  control, which is true, and a reader would reasonably conclude the server refuses a bad value.
  `ActApi.actSpeedSet` validates `0..3` **client-side** and throws, and throws again when the selector
  does not exist. The old page never said it was the server, but its shape implied it, and the docs
  had been read past for eleven features. **A wrapper's error behaviour is not inherited from the
  thing it wraps** — read the call site, not the design intent, for every "throws / returns nil /
  is ignored" clause.

- **(001.5) The style guide's own conformance grep was the wrong check: `— ` is one member of a
  character class, not the rule.** After group B, `grep -rn "^#.*—" docs/` returned clean on every
  page 001.3 and 001.4 had landed — while six double-hyphen anchors were still live in the tree,
  because ` / ` and ` & ` slug exactly like ` — ` (`## Skill / Credo / Experience` →
  `#skill--credo--experience`, three inbound links; `## Character & status *(widget-tree backed)*` →
  `#character--status-widget-tree-backed`, sixteen). D-007 had already stated the general rule — *no
  slugger-deleted character between two spaces* — and the checkable line beside it still tested only
  the em dash, so the tree read conformant. **When a rule is a character class, the check greps the
  class** (`^#.*( [—/&] |\[, )`), or better, slugs every heading and looks for `--`. The same run
  found six more in the two pages 001.6/001.7 still own, which is how a check earns its keep.

- **(001.5) Rot lives in examples as well as in prose — a code block is a claim, and it is the one
  claim nobody re-reads.** 035.4's rule (the tables were right, the notes were wrong) has a third
  category: `api/events.md`'s opening subscription example read `gob.name` on the `GobAdded` payload,
  eleven features after 020/017 made that payload a **Gob object** whose name is `gob:name()`. The
  table two lines below said *Payload: Gob* and was correct; the block under it had been wrong since
  017 and would have failed for the first reader who pasted it. Group C's pass found three of these
  (`gob.name`, `hafen.char.attrs().lp` for `hafen.char.lp()`, a "per-addon log" file that never
  existed). **Check every `hafen.*` name inside a fence against `src/`, not only the ones in tables**
  — the audit's symbol sweep does exactly this and is worth re-running per page, not per feature.

- **(001.5) Splitting a page is priced by its inbound *anchors*, not by its size.** `console.md` (39
  lines, two namespaces) became `log.md` + `slash.md` for the cost of six link edits, because only one
  inbound link was anchored; `events.md` (137 lines) stayed one page but its two heading renames cost
  twenty-six. The migration map should carry the inbound-anchor count per page next to the line count:
  it is the number that predicts the work, and it is one grep away before anything moves.

- **(001.6) A fenced block can pass the symbol sweep and still not compile.** The five checks catch wrong
  *names*; they say nothing about Lua *semantics*. The permissions guide's first draft opened a file-body
  example with `if not hafen.act.enabled() then … return end` and carried on below it — every symbol real,
  every link resolving, and a **syntax error**, because `return` must be the last statement of its block.
  A tutorial or guide block is code the reader pastes, so it needs one read for "does this run", separate
  from the read for "does this name exist". The cheap version of that read: any block with a top-level
  `return`, a redeclared upvalue, or a value used before the moment it is filled.

- **(001.6) Writing the task tier is what audits the reference tier from the outside.** Group A to C each
  swept its own pages and passed; writing the guides *over* those pages immediately found
  `log.md` pointing at `hafen.client:options():profiling()` for what is `hafen.client:profiling()`. No
  earlier check could have caught it — the link resolved, the target page was right, and the wrong string
  was link **text**, which no sweep reads. Link text is an unchecked claim: when a page names a call it
  does not own, that name wants the same `src/` check as one inside a fence.

- **(001.6) The missing page is itself an audit instrument.** `runtime.md` had to state the manifest field
  by field, which is how two shipped-but-inert fields surfaced (`dependencies` / `optional_dependencies`,
  parsed and validated, never used — the loader runs folders in listing order and each addon has its own
  Lua environment, so there is nothing to import) and how the undocumented global `ADDON` table surfaced.
  A grep-driven audit reads what `docs/` **claims**; it is structurally blind to a surface that exists only
  in `src/`. Only enumerating the real thing, field by field, finds those — which is an argument for
  writing the owning page early rather than last.

- **(001.7) A matrix's prose totals drift from its own table, and the summary is what everyone quotes.**
  001.1's table held 117 OK · 8 THIN · 2 GAP · 4 N/A · 9 CUT; the "Totals" line under it read
  *107 · 11 · 3 · 8 · 11*, and `STATE.md` carried those numbers forward through five tasks. Both sum to
  140, which is exactly why nobody caught it — the check that would have (does each verdict count match
  the rows?) is one `awk` away and was never run, while the check that was run (do the rows add up?)
  passes on any distribution. The same close recounted the namespace total and found **36**, not the
  audit's 37. **Re-derive every headline number at the close from the artefact itself**, never from the
  sentence that summarised it, and prefer a count that a one-line command can reproduce over one a human
  totalled once.

- **(001.7) Two-click reachability is a graph property, so measure it as one — a hand-checked index
  always reads complete.** The close had to prove every page sits within two clicks of the landing page,
  and reading the index proves only that the links resolve, which the sweep already knew. A ten-line BFS
  over the same link table answered it exactly (44 pages at depth 1, 26 at depth 2, 0 deeper) and, more
  usefully, named the one page at no depth at all: `docs/README.md`, which nothing below it links. That
  is correct for a root and a defect for anything else — and the distinction only exists once the metric
  is a traversal rather than an impression. The link checker already builds the graph; the reachability
  answer is three more lines on top of it.

- **(002.1) Splitting a page is a rename only if you retype a heading — promoting `###` to `##` keeps the
  slug, so the split costs path rewrites and nothing else.** 001.4's expensive lesson was that a heading
  touched for cosmetics is an anchor change; the useful inverse is that the *structural* half of a split is
  free. `map.md`'s five `##` subjects became five pages whose former `###` subsections became `##` — and
  `#the-marker-object`, `#the-segment-object`, `#saving-a-position`, `#write-ungated` and the rest slug
  identically at either level, because the slugger reads the heading text and not its depth. All 16 anchored
  inbound links therefore needed `map.md#x` → `map/leaf.md#x`, a pure path substitution, and the 22-link
  re-point landed with zero broken anchors on the first sweep. The rule to carry: **plan a split so that
  every heading that owns an inbound anchor changes level, never wording.** Re-titling a section while you
  move it turns a one-pass `sed` into a per-link audit, and it is entirely optional work.

- **(002.1) A link re-point is a prose edit: lengthening a target pushes the line past the wrap column.**
  `map.md)` → `map/README.md)` adds nine characters wherever it lands, and two of the eleven re-pointed
  pages went from 106 and 111 columns to 114 and 118 — new drift, in files whose *content* the task never
  touched, invisible to the link sweep and to `wc -l`. `awk 'length>110 && $0 !~ /^\|/'` over the touched
  set, diffed against the same run on `HEAD`, separates the drift you introduced from the drift you
  inherited; only the first is yours to fix inside the task. **Any mechanical substitution that grows a
  string is a re-wrap job as well as a rewrite job.**

- **(002.2) A "columns" check written as `awk 'length>110'` measures bytes, and this tree's own punctuation
  is what makes the two disagree.** 002.1 filed nine over-wide lines on the pages the feature touched;
  seven were real. The other two measured 107 and 110 columns and were flagged only because an em dash is
  three bytes in UTF-8 and Git-Bash's `awk` has no multibyte `length` — a prose line carrying two of them
  reads four columns wider than it is. ` — ` sits on nearly every `See also` bullet and in half the
  callouts, so the error is systematic rather than occasional. It never produced a visible defect because
  it is one-sided: an over-reporting wrap check keeps "zero drift" true while manufacturing work, exactly
  as 001.1's slugger manufactured 208 broken anchors. The check that measures what §10 says is
  `perl -CSD -ne 'chomp; print "$ARGV:$.\n" if length($_)>110 && !/^\|/'`. The general form: **a count
  over text is a count of some unit, and a tool with no multibyte mode has silently chosen bytes** — so
  reproduce a known-good number with the new tool before believing either tool's answer.

- **(003.1) The wrap check's unit problem has a second half: the line TERMINATOR, and this tree is mixed.**
  002.2 fixed `awk 'length>110'` counting bytes and moved to `perl -CSD`, which measures characters — and
  that check still over-reported by exactly one column on two of the five pages 003.1 touched.
  `render/sprites.md` and `ghost.md` are **CRLF**; `gob.md`, `events.md`, `player.md` and the rest of the
  tree are **LF**. `chomp` strips `$/`, which is `"\n"`, so on the CRLF half the surviving `\r` counts as a
  column and a 111-column line reads 112 — one-sided again, and again it manufactures work rather than
  hiding it. Worse, the check that should have settled it lied: `grep -c $'\r'` returned **0 on a file perl
  proved carries `\r`**, so the first two hypotheses (a real edit, a `cp` that rewrote endings) were both
  chased before `ord` on the last character ended it. Two rules: the wrap check is
  `s/\r?\n?$//`, never `chomp`; and **a working tree's line endings are per file, not per repo** — check
  with a byte count (`b.count(b'\r\n')` vs `b.count(b'\n')`), never with a `grep` whose pattern has to
  survive a shell.

- **(003.1) The registration grep proves a name EXISTS; it says nothing about what the call REFUSES — and
  that second half is where the defects are.** 001.2's oracle (`grep 'set("<name>"' src/io/brodgar/addon/`)
  answered every one of the 65 `hafen.*` names and all 13 `ov:` verbs on the 038 surface as correct, and
  the sweep would have closed green. The second query — `grep 'spec.get("\|LuaError'` over the four owning
  files — listed 14 spec fields and 16 error sites, and **five of those errors were on no page at all**
  (the `Loading` raise on a gob not renderable yet, the no-map-view raise, the native read-only refusal of
  the five composed verbs, and both halves of `refuseFollow`'s `follow`/`offset` test), while a sixth was
  documented wider than it is: `clickable`/`onClick` raise in `RenderApi.overlayEntity`, which a
  screen-space spec never reaches, so there they are silently ignored. **The oracle for a name is the
  registration; the oracle for a contract is the `LuaError` string** — and a `spec.get("…")` read is a
  field the docs owe a row. Every one of 003.1's nine corrections came from the second oracle.

- **(003.1) When a feature MOVES a surface between namespaces, grep its PROSE NAME — the stale link is the
  one that still resolves.** 038 moved gob overlays from `hafen.ui.gobOverlay` to `gob:overlay`, and
  `player.md` was left saying `worldToScreen` gives what a "[gob overlay](ui/custom.md#overlays)" wants:
  correct before 038, a live mis-point after it, and **invisible to every check the standard runs** — the
  page resolves, the anchor exists, the symbol sweep sees no symbol because the link text is English. It is
  001.6's finding one step further out: there the link text named a wrong *call*, here it names the right
  *concept* at the wrong *page*. A symbol grep cannot find either. So a review of a moved surface greps for
  what the surface is **called in prose** ("gob overlay", "the filter form") across the whole tier, not
  only for the identifiers, and it does so on pages outside the feature's own file list — `player.md` was
  on nobody's.

- **(003.2) A callout whose content stops being a warning has to stop being a callout — converting an
  obituary to a boundary is two edits, and the second one is invisible.** 038 wrote both `follow` cuts as
  blockquotes (``> `follow =` … are **gone**``), which was right for what they then said: a reader with the
  old API in their fingers needed stopping. Restating them in the present tense — *`hafen.render.sprite`
  takes no anchor of its own: a `follow` or an `offset` key in its table raises* — turns them into ordinary
  description, and ordinary description in a `>` block is exactly the "aside in a callout" §10 forbids. The
  minimal edit (rewrite the words, keep the `>`) therefore *passes* the no-history test and still leaves
  the page wrong, and on `ghost.md` it preserved a §10 violation the obituary had introduced: two
  blockquotes with only a heading between them. **Ask what the paragraph now IS, not only what it says** —
  a rewrite that changes a sentence's kind changes the block it belongs in.

- **(003.2) Deleting text is a re-wrap job, the same way 002.1's substitutions were — because a deletion
  JOINS lines.** 002.1 recorded that lengthening a link target pushes a line past 110 columns. The inverse
  is less obvious and bit harder: removing the measured figure from a mid-paragraph sentence let the two
  following lines close up, and the reflow produced **one 153-column line** in a paragraph whose remaining
  words the task never touched. It is invisible to `wc -l`, to the link sweep and to a diff read for
  meaning; only the `perl -CSD` run caught it, on the same pass that proved the *inherited* 111-column line
  was gone. So run the wrap check after a deletion pass, not only after an insertion pass — and run it
  **after the last edit**, since the run that cleared the inherited drift was two edits before the one that
  introduced new drift.

- **(004.2) An admitted §7 spelling is only proven at the moment it is admitted — a later, unrelated
  feature can invalidate it, silently.** `:offset(` was admitted (003.2/D-013) against a tree where it
  read zero. By 004.2 it read **17**, all legitimate: `ov:offset(x, y[, z])` (038.2's gob-overlay anchor)
  and `p:offset(dx, dy)` (a Position's own translate) share the retired handle method's exact call
  spelling, and both shipped after the entry was admitted. Nothing re-ran the falsification in between —
  the guard just quietly stopped being zero, on pages no §12 sweep had reason to revisit, until a task
  touching an unrelated page (`examples.md`) tripped it. **A §7 entry's proof has a shelf life**: re-run
  both falsification directions whenever a sweep touches it, not just trust the admitting task's report
  forever. There was no narrower spelling to rescue it — `:offset(` joined bare `offset` as inadmissible.

- **(004.2) Zero headroom at the ceiling is itself evidence, even when the opening-sentence "and" test
  says the page is one subject.** `controls.md` sat at exactly 300/300 after an accuracy pass that fixed
  prose but freed no lines, with a clean single-subject opening sentence — D-001's literal test said
  "fine." But a page with nowhere left for the next control `addons` ships is wrong regardless of what
  its first sentence says, and the eleven `##`-per-control sections carried an unforced natural seam
  (nothing to click vs. something to drive) that a size-only reading would have missed. Split by that
  seam into a hub + two leaves, none over 155 lines. **Treat "no room to grow" as a second, independent
  split signal alongside D-001's prose test**, not an inferior stand-in for it.

- **(005.1) The name a page invents has no guard at all, because every guard this standard runs is aimed at
  names that once existed.** Two pages call `ghost:move(...)` in worked examples. `move` is registered
  nowhere in `src/io/brodgar/addon/` and never was — it is not a retirement, so no §7 entry could ever carry
  it and no `Retired` row answers it; the reader gets the generic "has no verb". And §12's symbol check is
  scoped twice over — to `hafen.*` names, and to the ones *the task wrote* — so a handle verb on a page the
  task did not touch falls outside it in both directions. 001.5 found the same blind spot one step in (a
  fenced block is a claim nobody re-reads) and its rule still said *every `hafen.*` name inside a fence*. The
  complete check is **every colon verb the tier uses, tree-wide, against the registration set** — one sweep
  for `[\w)\]]:(\w+)\(` over `docs/` yielded 304 distinct verbs, of which exactly one was fiction. A
  retired-name list guards the past; only a backward sweep guards the invented.

- **(005.1) The registration oracle has a blind spot, and it is a verb whose key is computed.**
  `grep 'set("<name>"'` (001.2) reports `ev:sender()` absent from `src/`, and `api/event.md` documents it on
  two lines — a textbook WRONG that is not one. `LuaEvent.common` writes
  `final String noun = (shape == ACTION) ? "sender" : "target"; m.set(noun, …)`, so the literal never appears
  beside `set(`. The failure is one-sided and dangerous in the direction that *manufactures* work, exactly
  like 001.1's slugger and 002.2's byte `length`: it cannot hide a real defect, it can only invent one. **A
  backward sweep's "absent" is a lead, not a verdict — open the registration site before deleting or
  correcting anything**, and expect a computed key wherever one class serves several shapes of one object.

- **(005.1) Pairing quotes with one regex across a whole corpus desynchronises on the first unpaired quote,
  and Java is full of them.** Checking every backticked string literal in `docs/` against the engine's own
  literals reported `"MouseDown"` as absent from a corpus where `grep` finds it on three lines of
  `WidgetSubs.java`. The cause is not the pattern for a literal but the *scan*: a char literal (`'"'`) or an
  escaped quote inside a string shifts every subsequent open/close pairing by one, so from that byte onward
  the extracted set is offset garbage — and it still looks like a plausible set, which is why the first read
  of the output was believed. This is 002.2 and 003.1's family again (a tool silently choosing its own unit),
  with a new member: **a tool silently choosing its own token boundaries.** The fix is to stop parsing and
  start testing — build the candidate list from the *docs* side, then ask `"<s>"` ∈ `SRC` per candidate. The
  rule those three entries now share: **reproduce a known-good POSITIVE with the new tool before believing
  any negative it reports.**

- **(005.2) A page-level generalisation is a claim about every section beneath it, and the page's own body is
  where it gets falsified — no check the standard runs reads two paragraphs at once.** `api/event.md`'s
  opening callout said every event on the bus *"fires in the frame its change happens, never a frame later"*;
  eighty lines down, the overlay section said the pair *"arrive on the next frame"*, which is what
  `drainOverlayEvents` and `drainMarkerChanges` actually do. Both sentences were written by feature tasks that
  each had it right locally. §6 already warns that a claim made twice on one page will disagree with itself,
  but this pair does not *look* like a duplicate: one is a summary at the top and one is a detail in a
  section's prose, and the greps compare pages to `src/`, never a page to itself. So when a review meets a
  sweeping always/never sentence, **read the page's own exceptions before opening the engine** — the cheapest
  oracle for an overstatement is the paragraph that contradicts it, already in the file you are editing.

- **(005.2) A wrong sentence in `docs/` is often the feature spec's own rationale, copied verbatim — so
  `specs/` is not a second opinion, it is the same source.** `041/spec.md:214` justified the ungated event
  tier with *"subscribing observes, and cancelling cancels the client's own behaviour rather than sending
  anything"*, and `api/event.md`'s opening carried that clause word for word — while the same page's own table
  documents `ev:resend()` and `ev:send(t)`, which call `UI.rawWdgmsg`. The docs did not drift from the spec;
  they inherited its blind spot, and a reviewer reading the spec to "check" the page would have confirmed the
  error twice. This is why AREA.md makes `src/` the only admissible backing: **a claim repeated in the spec is
  not corroborated, it is duplicated.** A design rationale is also the sentence most likely to be wrong,
  because it is written before the surface is finished and nobody re-derives it afterwards.

- **(005.2) A line-based link checker cannot see a link whose TEXT wraps, and this tree has three.** The
  per-line regex `\[[^\]]*\]\([^)\s]+\)` validated 1,467 of the tree's 1,471 `](` occurrences; the four it
  never looked at are three links whose `[` sits on the previous line (`act.md:29`, `buff.md`,
  `ui/controls/README.md`) and one whose text contains brackets (a `$font[…]` tag). All four happen to
  resolve, so the miss cost nothing this time — but the failure is one-sided in the dangerous direction, the
  opposite of 001.1's slugger and 002.2's byte `length`: it under-reports, so a break planted inside a wrapped
  link is caught by no falsification either, and the "0 broken" reads exactly as clean. The fix is to scan the
  file whole rather than line by line (slurp, blank the fences, then match), which also lets the falsification
  plant a break *in one of the wrapped links* — the only way to prove the checker can see them. The rule this
  joins: **before believing a checker's total, reconcile it against a dumber count of the raw token** (`](`),
  and account for every unit of the difference.
