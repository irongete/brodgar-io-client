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

- **(001.3) Re-derive an "it works like the client does" sentence from the source, because the
  plausible version is usually the wrong one.** `speed.md` said the write drives the client's own
  control, which is true, and a reader would reasonably conclude the server refuses a bad value.
  `ActApi.actSpeedSet` validates `0..3` **client-side** and throws, and throws again when the selector
  does not exist. The old page never said it was the server, but its shape implied it, and the docs
  had been read past for eleven features. **A wrapper's error behaviour is not inherited from the
  thing it wraps** — read the call site, not the design intent, for every "throws / returns nil /
  is ignored" clause.
