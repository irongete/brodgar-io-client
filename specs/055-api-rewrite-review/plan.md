# 055-api-rewrite-review — Plan

## Approach

Four tasks: a **census** that produces the worklist, two **accuracy** passes split by feature
family, and the **standard + sweep + close**. The split is by *what the reading is*, not by page
count — the census reads registrations, the accuracy passes read refusals, the close reads the
tree.

**The census comes first because nothing else can be scoped without it.** Seven features is too
much surface to hold, and `003.1` proved the two directions are different jobs: a name that
exists and a page that has an owner are the cheap half; the expensive half is a page nobody's
file list ever contained (`player.md`, on nobody's, held a live mis-point after `038` moved a
surface).

**The two oracles rule the accuracy passes.** `grep 'set("<name>"' src/io/brodgar/addon/` proves
a name *exists* and would have closed `003.1` green; the corrections all came from the second
query — `LuaError` strings and `spec.get("…")` reads over the owning files, which is where a
refusal, an absence case and an undocumented field live. `039` added a third: `Args.java` is
where arity-as-verb is enforced, so *what `f()` answers versus `f(x)`* is readable rather than
inferred.

**The headline finding, and the spine of 055.4.** `src/io/brodgar/addon/Retired.java` is a
generated table of every spelling the rewrite replaced — **69 `hafen.*` section and verb keys, 54
entity-verb keys** (`entry:text`, `gob:overlays`, `marker:anchor`, 17 `widget:on*` callbacks) and
a separate table of retired *event keys*, which are string arguments and so carry no field read.
The style guide's §7 list is **28 hand-maintained names with no relationship to it**: it omits
`hafen.gob`, `hafen.hook`, `hafen.ghost`, `hafen.render`, `hafen.ui.all`, and it explicitly
annotates as **live** six names the engine now refuses (`hafen.world.gridPos`,
`fromGridPos`, `screenToWorld`, `snapPlace`, `snapAngle`, `hafen.map.markers`). So §7 stops being
remembered and becomes **derived from the engine's own refusal table**, with a hand-written entry
only where a retirement has no row there (a name cut without a refusal — `WidgetNode`, `setFont`,
`gobOverlay`) or where the spelling is inadmissible (`:offset(`, bare `follow`). That is a
decision, D-245, and it retires the maintenance problem rather than the list.

`042` gets its own line in the accuracy passes despite shipping no `hafen.*` change: it changed
**when** an event fires and deleted the poll stage, so any page still telling the reader a value
is up to a frame late, or to poll for a change, is wrong prose behind a right table — `006`'s
"rot lives in prose, not in tables" is the exact shape.

**Scope control**: `055.1` produces a worklist and the two accuracy tasks work only from it. A
page not on it is touched by `055.4`'s tree-wide sweep alone.

## Files to create / modify

- `specs/standards/docs.md` — §7 rewritten as derived from `Retired.java`; §2's "no
  `now`/`already`/`still`" rule is *scoped* (152 `still` and 67 `already` hits, nearly all
  legitimate present tense — "is it still there?"); §12 gains the derived-list re-falsification
- `specs/decisions/docs-standard.md` — **D-245** (§7 is derived from the engine's refusal
  table), and D-246 if §2's scoping is judged a change of rule rather than a clarification
- `specs/standards/docs-ia.md` — `api/vr/**`, `api/event.md`,
  `api/ui/controls/**` in the tree and the reading orders; §5.3's stale `api/markers.md` /
  `api/radar.md` rows (ROADMAP)
- `docs/README.md` — "It covers one thing" and "the ten addons" both out
- `docs/addons/README.md`, `docs/addons/api/README.md` — the two indexes, against the census
- `docs/**` — the pages `055.1`'s worklist names; plus the six over-110 lines the ROADMAP names
- `specs/_template/spec.md:20` — the `docs/addons/api/*.md` tier spelling (ROADMAP)
- `specs/learnings/docs-maintenance.md`, `STATE.md`, `FEATURES.md`, `ROADMAP.md` — the close
- **Flag, not a change:** `CLAUDE.md:16` carries the same stale tier spelling but sits outside
  this area's commit paths (`docs specs`). `055.4` reports it; the maintainer says whether it
  rides the commit.
- No new `specs/codebase/` file: `codebase/addon-engine.md` already covers
  `src/io/brodgar/addon/`, and no `haven` source is read.

## Risks & gotchas

- **A §7 entry's proof has a shelf life** (`004.2`): `:offset(` was admitted against a zero tree
  and read 17 by the time anything re-ran it. Deriving from `Retired.java` does not fix this —
  a *derived* name can also collide with a live spelling. Every entry is falsified in both
  directions at `055.4`, and the derivation is re-run, not trusted.
- **The stale link is the one that still resolves** (`003.1`): `043` moved a whole tier
  (`api/ghost.md`, `api/render/**` → `api/vr/**`). Grep the **prose name** — "ghost", "sprite",
  "the render namespace" — across the whole tier, not the identifiers, and on pages outside the
  feature's file list.
- **Converting an obituary is two edits and the second is invisible** (`003.2`): once a
  blockquote's content stops being a warning it stops being a callout. Ask what the paragraph
  *is*, not only what it says.
- **Deleting text is a re-wrap job** (`003.2`): a deletion joins lines and produced a 153-column
  line in a paragraph the task never touched. The wrap check runs **after the last edit**, with
  `perl -CSD` and `s/\r?\n?$//` — never `chomp`, because the tree is mixed CRLF/LF per file.
- **The em-dash trap is any slugger-deleted character between two spaces**, and the em-dash grep
  alone reads clean while ` / ` and ` & ` anchors survive (`001.5`).
- **Zero broken links is compatible with the tree being structurally wrong** (`001.1`): the
  two-click property is a graph traversal, not a hand-checked index.
- **§2's word rule is the one place this review could manufacture work.** 152 `still` hits are
  mostly `:exists()` rows. The risk is a mechanical sweep that damages correct prose; the answer
  is to scope the rule in `055.4` and fix only what is genuinely a change-note.

## Discarded alternatives

- **One task per feature (seven tasks)** — the pages do not divide that way: `041` alone touched
  44 of them, and `039`, `043` and `045` overlap on `conventions.md`, `world.md` and `gob.md`.
- **Fold the census into the first accuracy pass** — then the second pass inherits an unaudited
  worklist, which is how `003` and `004` each found a page nobody had listed.
- **Regenerate §7 mechanically at every task** — no tooling ships (AREA.md); the derivation is a
  documented `grep` over `Retired.java`, re-run and reported, not a script in the tree.
- **Sweep `specs/` for retired spellings too** — out of scope in `spec.md`; `specs/` is history
  by design and the maintainer's `hafen.gob(` hits live there legitimately.
