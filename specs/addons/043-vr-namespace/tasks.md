# 043 — Tasks

> Caps lifted for this feature (maintainer directive). Each task is one session, self-contained,
> and verified by its own `:t043-<X>` suite per [`TESTING.md`](../TESTING.md) — which stands alone
> and re-asserts whatever its own proof rests on, however old.
>
> Java is touched throughout: `ant hafen-client` → **full client restart** → run the command. And
> because this feature is nothing but moved symbols, **`rm -rf build/classes` before believing a
> green build** — an incremental compile false-greens exactly this kind of change.
>
> **The property every suite checks:** nothing renders differently. A ghost placed after a task
> must look and behave identically to one placed before it.

- [x] **043.1 — The section exists, and the three collections move into it**
  `hafen.vr()` with `:ghost()`, `:sprite()` and `:object()`, each answering every verb its old
  section answered. `hafen.ghost` and `hafen.render` are hard cut through 039's `Retired` table
  (pure data, so the refusals are generated rather than hand-written). Anchor stays
  Position-only in this task — the Gob form is 043.2 — so the diff is a pure re-home. Ports the
  38 measured Lua sites across `hello`, `planner` and `planner/gizmo.lua`, **by hand where a
  suite asserts a refusal** (a scripted port rewrites the very spelling the test pins — 039's own
  scar).
  *Suite proves*: each collection builds and hands back a working entity; every verb of the old
  surface answers on the new one (`:position`, `:rotate`, `:scale`, `:alpha`, `:tint`, `:visible`,
  `:clickable`, `:onClick`, `:exists`) plus the collection verbs; `hafen.ghost` and `hafen.render`
  each raise **naming `hafen.vr`**; a `.res` ghost, a PNG sprite and a glTF object each still
  place and still read back what they were given. `[manual]`: confirm all three still look exactly
  as they did.

- [x] **043.2 — The anchor becomes an argument**
  `:add(what, p)` stands at a point, `:add(what, gob)` follows a game object. No new plumbing —
  the entity core already supports both, and `learnings/rendering.md` (038.2) records that an
  anchored entity is *already* registered in these very collections and merely hidden. This task
  removes the hiding and passes the anchor in. Carries D-102's rule intact: an anchored entity
  dies with its gob, a free one does not, and the end still rides the `GobRemoved` the client
  already raises — **no per-frame sweep**, which is what D-100 deleted.
  *Suite proves*: `:add(x, gob)` anchors and tracks the gob as it moves; `:add(x, p)` stands
  still; a wrong anchor type raises naming **both** accepted forms; an anchored entity reports
  `:exists()` false once its gob is gone while a free one is untouched; `:position()` on an
  anchored entity answers the gob's live point; the collection's `:list()` includes both kinds of
  anchor.

- [x] **043.3 — The world kinds leave `gob:overlay()`**
  `ov:image`/`ov:model`/`ov:ghost` are cut, raising and naming their `hafen.vr()` replacement. The
  Overlay object loses the world verb set (`:scale`, `:rotate`, `:tint`, `:alpha`, `:billboard`,
  and `:offset`'s 3-argument world form), which is what makes `ov:offset` mean **one** thing and
  deletes the whole "refused on a screen-space overlay, naming the kinds" machinery. A VR entity
  anchored to a gob is surfaced in `gob:overlay():list()` **read-only**, down the path natives
  already take, so "what is at this gob?" keeps one complete answer. Ports `tagger` (2 sites).
  *Suite proves*: all three retired kinds raise naming their replacement; `ov:offset(x, y)` is
  screen pixels and the 3-argument form now raises; the world verbs are gone from the Overlay
  object; `ov:draw` and `ov:text` are untouched and still paint at the gob's projected point; a
  `hafen.vr()` entity anchored to a gob appears in `gob:overlay():list()` with `:native()` false
  and every write through it refused, naming the collection that owns it; the **game's own**
  overlays read exactly as before — same keys, same `:count()` union, same `:info()` shape.

- [ ] **043.4 — Seeing it all, and hiding it all**
  `hafen.vr():list()` across all three kinds, and `hafen.vr():visible(b)` as the section-wide
  switch. The restore rule is the one that needs care: showing the section back must restore
  **what was visible**, not turn everything on — an entity the addon had hidden individually
  stays hidden. That is per-entity state, not a global flag read at the draw.
  *Suite proves*: `:list()` returns every entity across the three kinds and **only this addon's**;
  it agrees with the three per-kind `:list()`s summed; `:visible(false)` takes them all off screen
  without destroying any (`:exists()` still true, handles still answer); `:visible(true)` restores
  exactly the prior visibility, with an individually-hidden entity still hidden; per-handle
  `:visible` keeps working independently of the section switch.

- [ ] **043.5 — `:facing()`, the docs, and the close**
  `:billboard(b)` → `:facing(mode)` with the two modes that exist today, `"fixed"` and `"screen"`;
  `"camera"` raises naming the valid ones, because it arrives in [044](../044-spatial-ui/) as a
  world quad and must not mean the wrong thing meanwhile. `planner`'s **persisted** `billboard`
  boolean becomes a `facing` string in its store — rewritten, not shimmed, since nothing is
  released. Then the docs: `api/ghost.md` and `api/render/**` retire into `api/vr/**` (hub +
  ghosts + sprites + models + the gizmo, which moves with the handles it drives), `api/gob.md`'s
  Overlays section is rewritten around what stays, both "API at a glance" tables lose two rows and
  gain one, and the 12 remaining pages naming the old sections are swept. **`api/gob.md` also owes what 043.3
  did**: the three kinds and the world verb set are gone from the Overlay object, `ov:offset(x, y)` is screen
  pixels and a third argument raises, and an anchored `hafen.vr()` entity is listed there read-only (D-188) —
  while `api/vr/**` owes the verb that moved with the kinds, `<entity>:offset(x, y, z)` (D-187). Runs area
  `docs`'s §12 checklist and reports it.
  *Suite proves*: `:facing()` reads back `"fixed"` and `"screen"`; `"camera"` raises naming the
  valid modes; an unknown mode raises the same way; `:billboard` raises naming `:facing`; the
  stored-layout round-trip works through the new field; the docs sweep is reported as counts.

## Notes

- **043.1 is the wide one, 043.3 is the sharp one.** The first touches the most files and the
  least logic; the third touches few files and reverses a shipped design decision, so it is the
  one to review closely.
- **Keep `VrApi`'s dispatch open to a fourth collection.** 044 adds `:widget()` to exactly this
  section, so a three-way branch a widget cannot join is the shape to avoid.
- **`GhostGob`, `SpriteQuad` and `MeshSprite` do not move.** They are engine-side visuals; only
  the Lua-facing section changes. The ROADMAP's "package layout" item lists `GhostGob` as a
  candidate for a *different* move — the two must not be done together.
- **Nothing here renders differently**, so a `[manual]` line that says "it looks the same" is the
  real acceptance test for this feature, and it is the one thing the suites cannot assert.
