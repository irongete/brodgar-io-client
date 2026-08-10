# 006-act-dissolved-review — Plan

## Approach

`005`'s shape, applied to two features instead of seven: **census first, corrections second, the
standard third, the tree-wide sweep last.** The difference is that `048` moved a surface rather than
adding one, and a move is the case this area has the most prior art on — the stale link is the *prose
name*, not the path (003.1), and the guard for a grammar change is a grep for the old **grammar**
(005.3/005.4). `hafen.act` and `gated`/`ungated` already read 0 under `docs/`, so the census is aimed at
the half a substitution cannot reach: whether the sentence around the new spelling is **true**.

**006.1 — the census.** One row per rehoused verb (`048/spec.md` is the map) plus `047`'s three reads,
two writes and two events: where it is documented, what the page claims, what `src/` does, verdict. The
oracle is the registration string and the method behind it, never the feature spec — a page's wrong
sentence is usually that spec's own rationale copied verbatim (005.2). Four claims get read with
particular care because `048` states them as rules the rest of the API inherits: the gate runs **before**
the argument check (so an argument refusal on a protected verb is not observable), a client-local write
on a departed thing is inert while a server write **raises**, `hand()` is `nil` while the cursor is
empty, and an argument that leaves the client is never coerced.

**006.2 — the standard.** The tier shipped the new adjective and `specs/docs/` did not. `style-guide.md`
§3's skeleton, §6's per-verb bullet and the IA's two `ungated` lines move to `protected`/`unprotected`;
§7's whole-section list gains `hafen.act` as the regex `hafen\.act\b` — the first entry needing one,
since `grep -F hafen.act` hits `hafen.actionbar`; §4/§7's own worked examples stop being written in a
retired spelling; the IA's §3 tree loses `act.md` and gains `flowermenu.md`. **D-010 and D-014 are not
rewritten** — a new decision records the adjective and that both read in it, on `005`'s D-015/D-016
precedent.

**006.3 — the ceiling.** Three pages at 300/300/299 and `048` wrote into all three. Split **by subject**
(D-001), the candidate seams being `conventions.md`'s `## References: how you address things` (~96 lines,
a catalogue of reference types), `gob.md`'s `## Overlays` (~136) and `ui/widget.md`'s `## The mouse` +
`### The grab` (~60). A split is priced by its **inbound anchors**, not its size (001.5), so the count is
made before the cut is chosen, and promoting a `###` to `##` keeps the slug (002.1).

**006.4 — the sweep and the close.** §12's seven checks over the whole tree, each re-derived: the retired
list out of `Retired.java` with its generators expanded, the backward colon-verb sweep, the two-threshold
wrap check, links falsified both ways. Then the STATE filed-items re-check and the close's figures.

**`049` is a hard boundary in every task.** The selector pages describe what ships today and are read as
such; a finding that `049` will overwrite is written into the task report and left in the tree.

## Files to create / modify

- `docs/addons/api/flowermenu.md`, `player.md`, `gob.md`, `menugrid.md`, `world.md`, `conventions.md`,
  `ui/items.md`, `ui/widget.md`, `event.md` — the `047`/`048` surfaces, corrected where 006.1 says so
- `docs/addons/guides/actions-and-permissions.md` — the permission stated in full lives here
- `docs/addons/api/README.md`, `docs/addons/README.md` — the two indexes, after any split
- new pages from 006.3's splits + every page linking into a moved heading
- `specs/docs/design/style-guide.md` — §3, §4, §6, §7 (and §12 if the checklist wording follows)
- `specs/docs/design/information-architecture.md` — §3's tree, the `api/` reading order, the two
  `ungated` lines
- `specs/docs/decisions/docs-standard.md` — one new decision (the adjective)
- `specs/docs/006-act-dissolved-review/census.md` — 006.1's table, the feature's evidence
- `specs/docs/STATE.md`, `FEATURES.md`, `learnings/docs-maintenance.md` — the close

## Risks & gotchas

- **`grep -rniF` core-dumps on this shell and a dead tool still prints `0`** (005.4). Reproduce a
  known-good positive with the exact command line before trusting any zero; prefer `-E`.
- **A row count of `Retired.java` by grep counts source lines** — `section(…)` and the loops generate
  rows no literal grep sees (005.4). The table grew from 93 keys to something larger; expand, don't count.
- **A plant must be written the way a page would write it** (005.4). `hafen.act` is safe to plant as
  itself; a receiver-qualified key never is.
- **`048` retires a verb under BOTH its field reads (D-216)** and the dotted regex is what carries them —
  but only for `hafen.*`. The rehoused verbs are *entity* verbs, so their guard is the backward sweep,
  which is also the only thing that can catch `gob:click` documented on a page as `gob.click`.
- **A move's stale link is the prose name** (003.1): grep "the actions section", "`act`", "the act
  namespace" as prose, not only as a path.
- **Splitting rewrites anchors**; every inbound link is re-pointed in the same task (§8) and the
  two-click rule is re-measured as a graph, not by hand (001.7).
- **`sed -i` on this CRLF tree rewrites every line ending** (001.4) — edit in place, review the diff.
- **Zero headroom is itself evidence** (004.2), so a page that comes out at 295 has not been split.

## Discarded alternatives

- Waiting for `049` and reviewing both at once — `049` will write selector pages against a standard that
  still says `gated`, so the drift compounds into the very feature that has to obey it.
- Rewriting D-010/D-014 in the new adjective — a decision records what was decided; a later one
  supersedes it (D-015/D-016 precedent).
- Splitting the ceiling pages by line count, or moving overflow into an appendix — D-001 is subject.
- One task for census + corrections — `005` proved the census is its own artefact and its own session.
- Fixing `pag:use()`'s gate claim from `048`'s spec — the gate is code; the oracle is `src/`.
