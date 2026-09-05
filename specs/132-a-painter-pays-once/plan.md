# 132 — plan

## Approach

### One op, in the material that is already rebuilt

`PatchOverlay.set(ring, fill, edge, width)` builds the whole material in one expression:

```java
mat = new Material(new BaseColor(fill), States.maskdepth, new MapMesh.OLOrder(this),
                   new PatchCarve(PatchCarve.of(ring), …));
```

`Material` takes `Pipe.Op...`, and `States.Depthtest.none` **is** a `Pipe.Op` —
`p -> {p.put(depthtest, null);}` — which `GLPipeState` answers with `glDisable(GL_DEPTH_TEST)`. So the
whole of the render change is that op, present or absent, in that varargs list. `States.maskdepth` stays
either way: not writing depth is right for an overlay whether or not it is tested.

`set` already takes the four things that decide the material and returns whether the **tiles** moved. The
flag joins them as a fifth, and returns the same answer it does now — a flag change never moves the mask,
so it re-pushes the material down the path a colour change already takes and no cut is re-carved. That is
criterion 3, and it falls out of where the flag is put rather than needing a branch.

### `patch:occluded(b)`, because it names the property

A bare adjective, like `visible` and `drawn` beside it on the page, read and written by arity:
`p:occluded()` answers, `p:occluded(false)` says the world may not hide it, and the default is `true` —
what a patch does today. The verb names *whether the world hides it*, not the effect of turning it off, so
there is one true reading of `p:occluded(true)` and no double negative. `patches.md` already writes
"occludes" in its own prose, which is what makes this the page's word rather than an imported one.

### A filter that answers without building anything

`filter(Area b)` answers `b.overlap(tiles) == null`, and `Area.overlap` returns an `Area` built from two
`Coord` — the whole intersection, to be thrown away for its nullness. `Area.isects(o)` is the pure
comparison `overlap` itself calls first, so the answer is `!b.isects(tiles)`: same result, nothing built.
`tags()` returns `Arrays.asList("show")`, a fresh list per call; it becomes a `static final` field, since
every patch answers the same one tag and `oltick` asks per overlay per frame.

### The follow poll asks the cheap question first

`followPatch` calls `t.getc()` for every follower every frame — the interpolated point, which descends
through `MCache.getzp` → `getgridt`. `Gob.rc` is the server's own point, a field, and it changes only when
the server says the object moved. Reading that first and calling `getc()` only when it differs from what
the patch was laid at makes the standing-still case what the comment already claims: two field reads.
`getc()` stays the point that is *used*, so a moving object is laid exactly where it is laid today — the
cheap read gates the expensive one, it does not replace it.

### What the page must say it does not change

Three things stay true and a reader will assume otherwise: the HUD still covers a patch, because the
overlay is drawn inside the map view's own 3D pass and the windows are drawn after it; the shape still
follows the terrain's slope, so this is not a flat screen polygon; and the mask still exists only where a
cut is built, so ground that has not streamed in has nothing to draw. The page says all three beside the
verb, and says that ghosts, world-scaled sprites and world panels are unchanged.

## Files to create or modify

| File | What |
|---|---|
| `src/io/brodgar/addon/PatchOverlay.java` | the flag through `set` into the material; `filter` without `overlap`; `tags` a constant |
| `src/io/brodgar/addon/VirtualApi.java` | `patch:occluded(b)` on the patch handle; the cheap gate in `followPatch` |
| `docs/addons/api/virtual/patches.md` | the verb, and the three things it does not change |
| `addons/132-a-painter-pays-once.1` … `.3` | one suite per task |

`docs/client/world-3d.md` needs no edit: its overlay rows already carry `OverlayInfo.mat()`, what
`olreaches` may cost, and the ⚠️ that a generous filter costs cuts. This feature is our side of what that
page already states.

## Risks and gotchas

- **`maskdepth` is not the depth test.** `GLPipeState.maskdepth` answers `glDepthMask(false)` and
  `GLPipeState` for the `depthtest` slot answers `glDisable` only on a `null`. Reading the two as one state
  is the mistake this whole feature turns on.
- **With the test off, overlapping patches order by draw order**, not by depth — `MapMesh.OLOrder`'s
  `mainorder` 1002 orders overlays against the ground, not against each other. Two overlapping rings both
  shown through the world will stack in whichever order the draw list holds; that is acceptable and worth
  one line on the page, not a sort.
- **`set` returns whether the tiles moved**, and its caller uses that to decide whether to re-lay. A flag
  change must return the same answer it would have without it, or turning the flag re-carves the ring.
- **`PatchCarve` does the shape**, per fragment, and knows nothing about depth. Nothing in the carve
  changes; the ring is the same ring.
- **`Gob.rc` is read under the gob's own monitor** where `followPatch` already synchronises for its
  fallback; the cheap read takes the same monitor rather than inventing a second discipline.
- **`Area.isects` is upstream's own**, already called by `overlap`; there is no new comparison to get wrong.

## Discarded alternatives

- **Making `g` cheap enough to paint the world with** — a run-of-points verb, one draw call for a ring,
  fewer objects per call. It is a smaller per-frame cost where the right number is no per-frame cost at
  all: a shape that does not move does not need redrawing, and the overlay system already knows that.
- **Flipping `g:poly` to the outline and adding `g:fpoly`** — the pair `rect`/`frect` argues for it, but
  the same name with the same arity drawing a different picture is a change no refusal can key on, under
  four live callers. Whatever the `g` vocabulary gains later, it cannot arrive that way.
- **A patch drawn in screen space, matching the painter exactly** — it would keep the current `over`
  picture pixel for pixel, and give up the thing that makes a patch worth using: the shape lies on the
  ground, follows the slope, and moves with its anchor for free.
- **`patch:onTop(b)`** — it names the effect rather than the property, and reads as an absolute where the
  thing is relative to the scene; `occluded` is the adjective the page already uses.
- **A `Depthtest` state exposed on its own, for any material** — a render state is not an addon-facing
  vocabulary, and the patch surface is where the question is actually asked. Sprites and world panels can
  each take the same op behind their own verb when their turn comes.
- **Caching `Gob.getc()` per frame instead of gating on `rc`** — the placement pass already recomputes it
  for its own reasons, so a second cache would be a copy of a cache, stale in exactly the frames that
  matter.
