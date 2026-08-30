# 121 — a shape and its edge: the plan

## Approach

**The border is carved out of the number the silhouette is already carved from.** `PatchCarve.carve` walks
the ring's inward half-planes, keeps the minimum signed distance `m` — positive inside — and returns
`smoothstep(-fw, fw, m)`, `fw` being `fwidth(m)`; its `ShaderMacro` multiplies that into the fragment's alpha
at order 500, over what `BaseColor` wrote at 0. The border is a second band off the same `m`, in the same
expression:

```
rimf = step(0, width) * (1 - smoothstep(w - fw, w + fw, m))     -- w = max(width, fw)
out  = mix(in, rimcol, rimf) * vec4(1, 1, 1, carve)
```

`in` is the fill at the fill's opacity, `rimcol` the border at its own, and `mix` carries the alphas across
with the colours — so the band comes out at the border's opacity and the interior at the fill's, which two
concentric patches cannot do. The silhouette then scales both, leaving the outer antialias unchanged.

**`w = max(width, fw)` is the one-pixel floor**, and it is why `width` can be world units without vanishing:
`fw` is how much world one pixel spans, so the floor is the silhouette's own pixel. `width < 0` is the "no
border" encoding — derived from `border == null`, never seen from Lua — and `step(0, width)` switches the
band off.

**The word is the stylesheet's; the argument shape is `g:line`'s.**
[A rule's `border`](../../docs/addons/api/ui/style/chrome.md#border) already names a line at one colour and
one thickness — but it says it as `{color =, width =}`, which `conventions.md` bars in a call and permits
there only because a stylesheet is a *document*. So `patch:border(colour, width)`, as
`g:line(x1, y1, x2, y2, width)` has them, and `patch:border()` hands **both** back, so
`two:border(one:border())` is one expression. `width` is world units rather than design px, and `0` is legal
and means the thinnest line the screen draws. `border{box = ...}` is refused naming that out here a border
is two arguments.

**The rest is the shape the four look verbs already have.** `LuaPatch` gains the border's colour and width
as fields. `LuaPatch.colour()` becomes two derivations — the fill is the tint's RGB at `tint.a/255 × alpha`,
the border's is its own at `border.a/255 × alpha` — so `:alpha(a)` multiplies both without the shader
knowing it exists, and the tint's `a`, dropped today, becomes the fill's own opacity.
`PatchOverlay.set(ring, fill, edge, width)` builds the same `Material` plus the fuller `PatchCarve`.
`VirtualApi.patchHandle` grows the verb through the `extra`/`extraVocab` pair `ghostHandle` uses for
`:res()`, through a setter shaped like `setEntityTint`: fields under the entity monitor, then
`refreshEntityScene`, which pushes a new material through the `MapView.Overlay` slot and rebuilds no mesh.
`LuaPatch.infoInto` gains `border`, absent while there is none.

## Files to create and modify

| File | What |
|---|---|
| `src/io/brodgar/addon/PatchCarve.java` | the two uniforms, the mix, the floor, the sentinel |
| `src/io/brodgar/addon/PatchOverlay.java` | `set` takes fill, edge and width |
| `src/io/brodgar/addon/LuaPatch.java` | the two fields, the two colour derivations, `infoInto` |
| `src/io/brodgar/addon/VirtualApi.java` | `patchHandle`'s `extra`/`extraVocab`, the setter, the refusals |
| `docs/addons/api/virtual/patches.md` | the border: the value, the floor, what the fill's own opacity is |
| `docs/addons/api/virtual/README.md` | the "a patch adds none" sentence, size-neutrally |
| `docs/addons/api/client/profiling/counters.md` | the reason the outline counter stands still, not the number |
| `addons/session-manager/main.lua`, `README.md` | the base wears its border again (121.2) |
| `addons/121-a-shape-and-its-edge.1/` `.2/` | one suite per task |

`chrome.md` and `drawing.md` are read, not modified. No `docs/client/` page is written either —
`world-3d.md` maps the ground overlays and the `rematerial` seam, `render-gl.md` the shader-state template,
the array uniform, the hand-declared builtin and the baked-uniform rule. Both held.

## Risks and gotchas

- **A uniform is baked, not re-read.** `GLDrawList.DrawSlot.getsettings` resolves every uniform at slot
  construction and caches it under a `SettingKey` compared by identity, so mutating a live `PatchCarve` field
  propagates nothing: the value arrives only as a **new state through the slot**, which is
  `PatchOverlay.set` → `MapView.rematerial`.
- **`fwidth` must be taken on the finished minimum.** `PatchCarve` declares it with `Function.Builtin`'s
  public constructor and takes it *after* the `For`, where control flow is uniform because the loop bound is.
  A derivative inside the loop is undefined, and the border wants the very `fw` the silhouette computes.
- **The mix stays in the one mod at 500.** `FragColor.fragcol(prog.fctx).mod(fn, order)` composes by order,
  so a second mod above it would be handed the mixed colour with no way left to tell fill from border.
- **Map space, not world space.** `Homo3D.fragmapv` is world with `y` negated (`Gob.Placed`) and
  `PatchCarve.planes` already builds there, so distance and width share units.
- **`MCache.Grid.getolcut` builds `MapMesh.makeolol` either way** — the ROADMAP defect filed at 068. This
  never reaches `MCache.OverlayInfo.omat()`, so do not cost the border as though that pass were its.
- **`docs/addons/api/virtual/README.md` is 326 lines**, so the edit there stays size-neutral; its own
  ceiling is the maintainer's to queue.
- Java is source/target **1.8** here; a change needs `ant hafen-client`, `ant bin` and a restart.

## Discarded alternatives

- **`patch:outline(c)` beside `patch:outlineWidth(w)`** — rejected: two verbs for one frame, neither a word
  this API uses, and a compound name for the half that is only a number. `border` was already the word for a
  line at one colour and one thickness.
- **`patch:border{color = c, width = w}`, the stylesheet's own value** — rejected: `conventions.md` bars a
  table of named arguments in a call, and says why a document may have one. The word crosses that boundary;
  the value shape does not.
- **`patch:fill(c)` in place of `:tint(c)`** — rejected: `:tint` is the one verb every entity of every kind
  answers, and the section's model is *shared verbs shared, per-kind additions on top*. A prettier pair on one
  kind costs the uniformity the other four are documented by, where a sentence on the patch's page — out
  here the tint **is** the fill — costs nothing.
- **Two concentric patches** — rejected: each is its own ground overlay and they blend one over the other, so
  the interior can never come out *less* opaque than the band around it — a rim inverted, and no choice of
  colours escapes it.
- **A ring of thin convex quads** — rejected: `PatchOverlay.coverage` masks each shape's bounding box plus a
  tile every way, so a twenty-segment ring masks twenty overlapping boxes and pays cuts for all of them.
- **The engine's own overlay outline** (`MCache.OverlayInfo.omat()`, `MapMesh.makeolol`) — rejected: it
  outlines the masked **tiles**, and a patch's mask is the ring's bounding box in whole tiles, so it draws a
  rectangle around the shape rather than the shape.
- **Width in screen pixels** — rejected: every other length on a patch is world units, and a shape whose ring
  is world and whose border is pixels cannot be taken out by `:scale(k)` without the two disagreeing. The
  floor buys the one thing a pixel width was wanted for.
- **A second `ShaderMacro` for the no-border case** — rejected: it doubles the terrain programs to save a
  multiply, where the sentinel costs a `step` on the fragment that computed `m`.
