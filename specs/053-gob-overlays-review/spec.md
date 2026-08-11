# 053-gob-overlays-review — Spec

## What & why

Area `addons` shipped `038-gob-overlays` and wrote its own reference content across 14 pages: a new
"Overlays" section on `api/gob.md`, two event rows plus a section on `api/events.md`, and the removal of
`hafen.ui.gobOverlay`, `gob:overlays()` and `follow=` from `ui/custom.md`, `render/sprites.md`,
`render/models.md` and `ghost.md`. The structure is sound — every page is inside the ceiling, the new
anchors resolve, no link into a renamed heading was left behind. Two kinds of drift are not:

- **The pages describe a replacement where the standard allows only what exists.** Four sites read as
  change notes, and three retired names have no grep guard — plus a measured figure, three counts in
  prose, and an attach verb sitting in a `## Read` table.
- **Four claims do not match the code that shipped**, found by reading `src/` rather than the diff:
  `hafen.ghost.list()` silently stopped returning overlay-owned ghosts, two raises and the four arities'
  absence case are undocumented, and `ov:info()`'s published shape is wrong for a native overlay.

It brings the post-038 surface back to the standard, as `002` did for `037`, and every correction is
checked against `src/` rather than against memory.

## Acceptance criteria

- [ ] **Every claim on the 038 surface matches the code**, and the four known ones are corrected:
      `ghost.md`'s `hafen.ghost.list(filter)` says an overlay-owned ghost is not in it and is reached
      through `gob:overlay`; `gob.md` states the two raises (a gob not renderable yet — attach from
      `GobAdded` — and a world-space overlay with no map view yet) and the absence case of **all four**
      arities on a gone gob, which is `nil` including the attach and the remove; `ov:info()`'s shape
      carries `world` and `kind` as present for yours and absent for a native one.
- [ ] **Nothing shipped is undocumented and nothing documented is unshipped** across the 038 surface:
      every `hafen.*`, `gob:` and `ov:` name on these pages resolves to its registration in
      `src/io/brodgar/addon/`, and every verb, spec field and error the feature registers appears on a
      page — both directions listed in the task report, name by name.
- [ ] **No obituary anywhere under `docs/`.** The four sites are restated in the present tense as
      boundaries — what the API does today and why — each keeping the behaviour it carried:
      `ghost.md:158` and `render/sprites.md:116` (the ``> `follow =` … are **gone**`` callouts: passing
      `follow` raises naming `gob:overlay`, the methods read `nil`), `ui/custom.md:77`, and the two "used
      to float / used to be" clauses at `ghost.md:152` and `render/sprites.md:102`. §7's test applies
      literally: delete every clause that only makes sense to a reader of the previous version.
- [ ] **The names 038 retired are on the §7 grep list and return zero hits** — `hafen.ui.gobOverlay`
      (one live hit today), `gob:overlays()` and the `follow`/`:follow`/`:offset` surface — each in a
      spelling that cannot false-hit ordinary prose (`follows`, "redirects are followed" and 30 more
      legitimate uses stand).
- [ ] **No measured figure and no count in prose that duplicates a list** (§11 D-238, §6 D-242):
      `gob.md`'s `:count()` paragraph makes the union claim without "13 of 33", and `gob.md`'s "one of
      five things", `events.md`'s "Four rules" and the addon count spelled "twelve" in four indexes go.
- [ ] **`gob:overlay` is classified as what it is.** It is not a row in `gob.md`'s `## Read` table while
      it attaches, replaces and removes, and the page states in **prose** that it is your own drawing
      rather than a write (D-236 scopes the heading annotation to write groups; `## Overlays` keeps its
      title, since ten links point at that anchor).
- [ ] **Headings meet §4** — sentence case, no capitals used for emphasis (`## A ghost ON a gob is an
      overlay`, `## An image ON a gob is an overlay`), every link into a retitled one re-pointed in the
      same task.
- [ ] **The six checks of §12** run over every page touched and every page linking into them, reported as
      counts with the offenders listed, the link checker falsified in both directions. Wrap drift is zero
      on every touched page (`render/sprites.md:81`, 113 columns, is the one today).

## Out of scope

- Any change to `src/`, to the `hafen.*` API, or to the addons under `addons/` — this area ships no code.
  A gap or wrong behaviour found while reading is filed to area `addons` and named in the report; the
  four corrections above are the **docs** being wrong about working code, not the code being wrong.
- **Splitting `api/gob.md`.** It is 235 lines, inside the ceiling, and one namespace with one opening
  sentence: IA rule 2 keys the path on the namespace, and `gob:overlay` is a verb of `hafen.gob`, not a
  sub-namespace. What trips the "and" test is the *index row* in `api/README.md`, and that is wording.
- The tree-wide wrap sweep on the ROADMAP (`render/sprites.md` leaves that list, the other six lines
  stay), and re-auditing the tree beyond the 038 surface — `001.1`'s matrix stands.

## Context files

- `specs/standards/docs.md` — the standard enforced (§3 page kinds, §4 headings, §6 facts, §7 no history, §11, §12 checks)
- `specs/standards/docs-ia.md` (rules 2–4, namespace → path; §8 link discipline) ·
  `specs/decisions/docs-standard.md` (D-238, D-239, D-240, D-242, opened per entry, never whole)
- `specs/learnings/docs-maintenance.md` — the sweep traps: the double-hyphen slug, the over-reporting checker
- `specs/052-map-database-review/` — the same job for `037`: the obituary-to-boundary rewrite and the grep-list extension
- `src/io/brodgar/addon/LuaGob.java` (`overlay` at :277) · `LuaOverlay.java` (the `ov:` surface and
  `info()`'s two shapes, :165–:300) · `LuaGobOverlay.java` (the spec fields, `specKind`'s errors, the
  `Loading` raise, :143–:230) · `RenderApi.java` (`overlayEntity`, `asOverlay`, :920–:945) — the code
- `docs/addons/api/gob.md` (the new Overlays section, the largest surface this touches), `api/events.md`,
  `api/ghost.md`, `api/render/sprites.md`, `api/render/models.md`, `api/ui/custom.md`, `api/ui/drawing.md`,
  `api/conventions.md` — the 038 reference surface; plus `guides/custom-ui.md`, `examples.md`,
  `README.md`, `api/README.md`, `guides/README.md`, `getting-started.md` — the guide and the indexes
- `specs/038-gob-overlays/spec.md` + `tasks.md` — what shipped, and what each claim is checkable against
