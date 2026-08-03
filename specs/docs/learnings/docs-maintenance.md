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
