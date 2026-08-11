# 054-ui-controls-review — Plan

## Approach

Two tasks, in the order `003` used, and for the same reason: **truth first, shape second**.

**054.1 — accuracy and the roster.** Read the two new pages and the four edited ones against `src/`,
using 003.1's oracle: the registration string for existence, the `LuaError` text for what a call
*refuses*. This surface is unusually refusal-heavy — the pending-rebuild rule on `:image()`, bare
builders refusing an argument, `:value(nil)`, a progress bar refusing `0..1` while a slider **clamps**
— so a name that exists proves almost nothing about the sentence on the page. The same pass deletes
what §6 forbids: the prose counts, and any claim a page makes twice. It ends by **re-measuring
`wc -l`**, which is the input the next task needs.

**054.2 — shape, the standard's own files, and the close.** Settle the gating question as a decision
entry; decide the `controls.md` size question with 054.1's number in hand; teach the IA the pages
exist; extend §7 with what `040` retired; then the full §12 sweep, the re-derived `STATE.md` figures
and the close. A split, if it happens, happens here — after the deletions, never before them.

**The size question is genuinely open, and the plan does not pre-answer it.** `controls.md` is at 300,
not over it, and §9 says a page splits by subject and never by length. Three outcomes are legitimate:
054.1's deletions buy enough headroom and the page stands; the page is two subjects (the spine plus
the display controls against the interactive ones) and becomes `api/ui/controls/` under IA rule 3; or
it stands and the IA records why, the way §9 provides for. What is *not* legitimate is leaving a page
at the ceiling with no statement about it, because the next control `addons` ships goes over.

**The gating question has a likely answer and the task must still earn it.** §3 keeps `hafen.ui.overlay`
and the `g:` verbs plain because your own drawing changes no client state, and D-240 makes a
client-local write group say `(ungated)`. A control you built is between the two — and 001.3's learning
is decisive on the risk: annotating every setter group `(ungated)` is exactly how `## Write (ungated)`
stopped being a signal on the twenty group-A pages. `widget.md` already states it in prose ("None of
these writes is gated"), which is the shape to copy. The entry goes in `decisions/docs-standard.md` as
**D-244** whichever way it lands.

## Files to create / modify

- `docs/addons/api/ui/controls.md` — corrections; the prose counts (`Two faces or three`, `Four faces
  here, not two or three`); the 115-column line 69; the group headings; possibly a split
- `docs/addons/api/ui/lists.md` — corrections; the shared row-shape claim and `the first three`
- `docs/addons/api/ui/widget.md` — the fourteen control-verb rows appear in **both** its tables (reads
  at 59-72, owned/borrowed at 113-126); judge the duplication, do not reflexively cut it
- `docs/addons/api/ui/custom.md`, `docs/addons/api/asset.md` — two page links and one anchored link in
- `docs/addons/api/ui/README.md`, `docs/addons/api/README.md`, `docs/addons/README.md` — index and
  at-a-glance rows; new rows per leaf if `controls.md` becomes a directory (two-click rule)
- `docs/addons/examples.md` — `040`'s example row, and the inherited `:offset(` §7 hit at line 106
- `specs/standards/docs-ia.md` — §3's tree, §4's `api/ui` reading order
- `specs/standards/docs.md` — §7's grep list, extended with what `040` retired
- `specs/decisions/docs-standard.md` — **D-244**, the gating of a control you own
- `specs/STATE.md`, `FEATURES.md`, `LEARNINGS.md` (+ `learnings/docs-maintenance.md`) — the close

## Risks & gotchas

- **The split is priced by anchors, not lines** (001.5). One grep, run before anything moves: ~30
  anchored links point into these two pages, **20 of them from `widget.md` alone** (`#setters` six
  times, `#a-caption-or-a-picture` three), plus one from `asset.md` and four from `lists.md`.
- **Plan any split so every heading owning an anchor changes level, never wording** (002.1) — then the
  re-point is a pure path substitution and costs nothing beyond it. Re-titling while moving turns one
  `sed` pass into a per-link audit, and it is optional work.
- **Longest pattern first, the bare page link last** (001.4), or `controls.md)` eats `controls.md#slider)`.
- **`sed -i` on this tree rewrites every line ending** (001.4): it is mixed CRLF/LF per file, and
  `git status` will show files whose content did not change.
- **A re-point is a re-wrap job** (002.1): a longer target pushes a line past 110. Diff the over-110 run
  against the same run on `HEAD` — only the drift the task introduced is the task's to fix.
- **Measure columns in characters, with the terminator fix** (003.1): `perl -CSD` with `s/\r?\n?$//`,
  table rows excluded. `awk 'length>110'` measures bytes and this tree's punctuation is multi-byte.
- **A registration grep proves existence, not refusal** (003.1). Every "is refused", "clamps",
  "reads `nil`" sentence on these pages is checked against the `LuaError` string or the code path.
- **A same-page duplicate is invisible to the cross-page greps** (§6) — `widget.md` is where to look.
- **Two-click reachability is a graph property** (001.7): if `controls.md` becomes a directory, its
  leaves need their own rows in `api/README.md` or they land three clicks from the landing page.
- **The §12 link check covers every page linking *into* the touched set**, not just the touched set,
  and is falsified in both directions — plant a break, confirm it is caught, remove it.

## Discarded alternatives

- **One task.** The size decision needs the line count *after* the deletions; merging the two makes the
  most consequential call in the feature the one taken with the stalest number.
- **Split `controls.md` up front.** The split is an outcome of evidence, not a premise; §9 splits by
  subject, and the page may well be one.
- **Move the fourteen control-verb rows off `widget.md`.** It would strip the one page that answers
  "what does this verb do on *any* widget" — the duplication gets judged, not removed by construction.
- **File the `examples.md` `:offset(` hit as its own feature.** One line, and a guard that does not read
  zero is not a guard.
- **Re-audit the pre-040 tree.** `001`..`003` closed it; this reads the 040 surface and its inbound pages.
