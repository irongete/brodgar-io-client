# 136 — a shape of many pieces · tasks

- [x] **136.1 — a patch is many pieces, drawn as their union.** `PatchCarve`'s half-plane row carries the
      piece index in its free `.w`; the fragment loop folds each piece's minimum into a running maximum with
      an `If`, and everything below that value — `fwidth`, the border band, the silhouette — is unchanged.
      `of()` takes a list of pieces through `planes()` untouched. The one cap becomes two: `RING_EDGES = 32`
      per ring, word for word as today, and `EDGES = 128` as the array's length and the patch's total; the
      class javadoc's "its lights" clause is corrected: a ground overlay carries no `Light.PhongLight`.
      `PatchOverlay` keeps a box per piece beside the box that bounds them, and `filter`, `fill` and `set`
      read them. `LuaPatch.local` becomes a list of pieces and `worldRing()` becomes `worldPieces()`.
      `patch:piece()` is the collection view: `:add(ring)`, `:list`, `:count`, `:find`, `piece:info()`,
      `piece:exists()`. `patches.md` is split, `## The ring` and `## What a ring may be` moving into the new
      `pieces.md` with the union and the budget; no inbound anchor moves.
      *Its suite* lays one ring and asserts `:piece():count()` is 1, then `:add`s a second overlapping quad
      and asserts it is 2 and that `:list()` returns them in the order laid — the patch itself is one handle
      throughout, which is the claim. It asserts `:add(ring, anchor)` still lays a patch that draws, tints
      and borders. Four refusals, each read for its own words: a concave ring says **concave**; a 40-point
      ring says **at most 32**; pieces past the total say **128**; a string filter names the two forms that
      work.
      `[manual]`: two overlapping quads laid as one patch under a white border — expect one L outlined only
      round the outside, with no line across the join where the two meet.

- [x] **136.2 — a piece is taken up, and the snapshot says pieces.** `patch:piece():remove(p)` ends one
      piece and leaves the rest drawn; the removed piece reads `:exists()` false and every verb on it goes
      on answering. A patch whose last piece is taken up still exists, still holds its place and draws
      nothing, so `:drawn()` reads false — ending the patch is still
      `hafen.virtual():patch():remove(p)`. `LuaPatch`
      re-derives its carve and its mask on each removal through the path `lay()` already has, so a removal
      inside the tiles already covered pushes a material and re-cuts nothing. `patch:info()` carries
      `pieces`, an array of rings of `{gridId, x, y}`, and carries no `ring`. `types/world.md`'s
      `WorldEntity` row gains `pieces`, drops `ring`, and its `kind` row gains `"patch"`, which it has been
      missing beside the four kinds it lists.
      *Its suite* lays three pieces, removes the middle one, and asserts the count fell to 2, that the
      removed piece reads `:exists()` false and the two survivors read true — a removal that took the wrong
      member is what that pair catches. It asserts `info().pieces` holds two rings of four durable points
      each and that `info().ring` is `nil`. It then removes both survivors and asserts the patch reads
      `:exists()` true and `:drawn()` false. Two refusals: removing a piece of **another** patch says so, and
      `:remove()` with nothing raises naming the argument.
      `[manual]`: three quads in a row with the middle one removed — expect a gap in the shape exactly where
      it was, and the outer two still bordered.

- [ ] **136.3 — a click lands on whichever piece is under it.** `PatchClick.hit` projects and tests each
      piece rather than one ring; a patch answers when any of its pieces contains the point, and the depth
      that orders one patch in front of another is the hit piece's rather than an average over the whole
      shape. A piece with a corner behind the eye is dropped **alone** — today one such corner drops the
      patch entire, so half a shape at the edge of the view took no clicks at all. `ev:x()`/`ev:y()` carry
      the world point as they do now. `patches.md`'s clickability section says which piece answers.
      *Its suite* lays one patch of two pieces with a clear gap between them, `:clickable(true)`, and holds
      a `PatchClicked` subscription that records the piece the point fell in. It asserts `:clickable()`
      reads back true, that the subscription arms and that `sub:off()` ends it, and it scores the three
      presses below over a bounded window rather than asking for them twice.
      `[manual]`: press inside the near piece — expect one line naming it. `[manual]`: press inside the far
      piece — expect one line naming that one. `[manual]`: press in the gap between them — expect no line,
      and the character walks there.
      <!-- extra context: docs/addons/api/event/bus/world.md — the PatchClicked row and its anchor -->
