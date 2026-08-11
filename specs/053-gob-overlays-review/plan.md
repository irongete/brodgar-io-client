# 053-gob-overlays-review — Plan

## Approach

**Two tasks, accuracy first.** `053.1` corrects what the pages say about the code, reading `src/`; `053.2`
applies the standard and closes with the sweep. That order is 001.4's rule — *run the sweep after the last
cosmetic pass, never after the last structural one* — and it also puts the wording that is **decided by the
code** ahead of the wording that is decided by the style guide, so nothing is re-worded twice.

**The oracle is the registration site, never a grep for the name** (001.2). The both-directions sweep needs
two oracles, because this surface has two kinds of name:

- **Verbs** are registrations: `grep -rn 'set("' src/io/brodgar/addon/LuaOverlay.java` is the whole `ov:`
  surface, `LuaGob.java`'s `m.set("overlay"` is the verb, and the same query over the facade is how a page
  name is derived. Every `ov:`/`gob:` name on a page must appear there, and every name there on a page.
- **Spec fields and error cases are not registrations** — they are `spec.get("…")` reads and `LuaError`
  strings. So the second query is `grep -n 'spec.get("\|LuaError' ` over `LuaGobOverlay.java` and
  `RenderApi.java`'s `overlayEntity`/`makeSprite`/`makeObject`/`makeGhost`, which is what lists `draw`,
  `text`, `color`, `offset`, `image`, `model`, `ghost`, `scale`, `alpha`, `tint`, `a`, `billboard`, the
  `clickable`/`onClick` refusal, and the raises. **This is the half a symbol sweep normally misses**, and it
  is where all four known defects live.

**The four corrections, each from its site.** `ghost.md`'s `list` row and the paragraph under it gain the
exclusion (`LuaWorldEntity#asOverlay`, [RenderApi.java:928](src/io/brodgar/addon/RenderApi.java:928)) stated
as what `list` *is* — this addon's ghosts that you placed — with `gob:overlay` as where the other kind is
reached. `gob.md`'s call table gains the absence case of all four arities on a gone gob (`nil`, including
the attach and the remove: [LuaGob.java:300](src/io/brodgar/addon/LuaGob.java:300)) and the two raises
([LuaGobOverlay.java:226](src/io/brodgar/addon/LuaGobOverlay.java:226),
[RenderApi.java:933](src/io/brodgar/addon/RenderApi.java:933)) in the same place the native-key raise is
already stated. `ov:info()`'s shape splits into the two the code writes:
[LuaOverlay.java:288](src/io/brodgar/addon/LuaOverlay.java:288) sets `world` and `kind` for yours only.

**`## Overlays` is not retitled, and its gating does not go in the heading.** Ten links point at
`gob.md#overlays`, and 001.4's 18 broken links came from exactly this edit — appending an annotation to a
heading *is* a rename. D-236 scopes the annotation to write groups anyway, and `gob:overlay` is your own
drawing, like `hafen.ui.overlay`: it changes nothing the client, the server or the user owns, so it is
**not** a write group. The sentence goes in prose, where §6 already puts the surprising cases. What moves
is the row: `gob:overlay(…)` leaves the `## Read` table (it attaches, replaces and removes) and the section
below keeps the four-arity table it already has. Two headings *are* retitled — `ghost.md`'s "A ghost ON a
gob is an overlay" and `sprites.md`'s "An image ON a gob is an overlay" — and nothing links into either
today, which 053.2 re-verifies before the edit rather than after.

**The §7 list is extended by spellings that are proven, not by names.** `follow` is an ordinary English
word with 30+ legitimate hits, `grid:overlays()` is live on `api/map/overlays.md`, and `hafen.ui.overlay`
is live everywhere. So each candidate entry — `hafen.ui.gobOverlay`, `gobOverlay`, `gob:overlays(`,
`follow =`, `:follow(`, `:offset(` — is admitted only after it returns **zero** on the healthy tree *and*
catches a planted reintroduction. A spelling that cannot do both is not on the list; the list's value is
that it is run blind by a later task (001.2: a rule that operates on names is checked at name level).

## Files to create / modify

- `docs/addons/api/gob.md` — the two raises, the four arities' absence case, `ov:info()`'s two shapes, the
  `13 of 33` figure out, the two prose counts out, the `## Read` row moved, the gating sentence in prose
- `docs/addons/api/ghost.md` — the `list` exclusion; the obituary callout and the "used to be" clause
  restated as boundaries; the retitled heading
- `docs/addons/api/render/sprites.md` — the same two restatements, the retitled heading, the 113-column line
- `docs/addons/api/render/models.md` — checked against `makeObject`'s options; no known defect
- `docs/addons/api/ui/custom.md` — "`hafen.ui.gobOverlay` is gone" becomes what `hafen.ui.overlay` is
- `docs/addons/api/events.md` — "Four rules" loses its count
- `docs/addons/api/ui/drawing.md`, `api/conventions.md`, `guides/custom-ui.md` — checked, re-pointed if a
  heading they link into moves
- `docs/addons/README.md`, `examples.md`, `getting-started.md`, `guides/README.md` — the addon count in
  prose becomes a reference to the list below it
- `specs/standards/docs.md` — §7's grep list gains the proven spellings
- `specs/STATE.md` `FEATURES.md` `ROADMAP.md` `LEARNINGS.md` (+ `learnings/docs-maintenance.md`) — `/end`

## Risks & gotchas

- **A code block is a claim, and it is the one claim nobody re-reads** (001.5). Every `hafen.*`, `gob:` and
  `ov:` name **inside a fence** goes through the same oracle as the tables — `events.md`'s subscription
  example was wrong for eleven features because only the table was checked.
- **Correcting an absence case lengthens a table row**, and a re-point lengthens a line (002.1): the wrap
  check is `perl -CSD` and never `awk`, which counts bytes and over-reports by three per em dash (002.2).
  Diff the run against `HEAD` so inherited drift is not claimed as this feature's.
- **The heading check is a character class, not the em dash** (001.5): `^#.*( [—/&] |\[, )` plus a slug
  pass looking for `--`. The retitled headings are the ones to re-slug.
- **A "0 broken" from an unfalsified checker is not evidence** (001.1) — plant a bad path and a bad anchor,
  confirm both are caught, remove them, confirm zero.
- **`sed -i` on this CRLF tree rewrites every line ending** (001.4); `git diff --numstat` separates a real
  edit from a line-ending-only one before the report claims a file changed.
- **The four corrections are the docs being wrong about working code.** If reading `src/` turns up a real
  engine defect instead, it is filed to area `addons` and named in the report — never fixed here.

## Discarded alternatives

- One task — the accuracy edits are structural and the standard edits are cosmetic; merging them puts the
  sweep before the last cosmetic pass, which is the failure 001.4 recorded.
- `## Overlays (ungated)` — an annotation in a heading is a rename of a ten-link anchor, and D-236 does not
  ask for one on a group that is not a write group.
- Splitting `api/gob.md` into `api/gob/` — spec, Out of scope: one namespace, inside the ceiling.
- Bare `follow` on the §7 grep list — 30+ legitimate hits; a list that cries wolf stops being run.
- Filing `hafen.ghost.list()`'s exclusion to area `addons` as a bug — it is deliberate (D-103) and the code
  is right; the page is what is behind.
- Documenting the two raises as a callout — §10 allows one blockquote for what must not be missed, and the
  page already spends two; an error case belongs beside the verb that throws it.
