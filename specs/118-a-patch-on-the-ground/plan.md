# 118 — plan

## Approach

**The geometry is the engine's, not ours.** A patch is one object implementing both
`MCache.OverlayInfo` and `MCache.LocalOverlay`, registered with `MCache.add`. `MapView.oltick` finds it
through `MCache.getols`, tests its `tags()` against `MapView.oltags` — seeded `{"show": 1}`, which nothing
decrements — and adds a `MapView.Overlay` raster itself. `MapMesh.makeol` re-lays every masked tile
through that tile's own `Tiler.lay` over the ground's **own vertices**, and `MapMesh.OLOrder`'s
`mainorder` 1002 draws it after the ground in the same pass, where `States.Depthtest(LE)` lets the exact
tie through. No lift, no bias, nothing that comes apart at distance, and gobs occlude it because their
depth is genuinely nearer. It needs no `.res`: `OverlayInfo` is a plain interface, and `MapView.selol` is
the precedent for an anonymous one.

**The mask is generous; the silhouette is carved.** `LocalOverlay.fill(Area, boolean[])` marks every tile
the ring's bounding box touches, so the mask never decides the shape. The shape is a `haven.render.State`
carrying the ring's edge half-planes, whose `shader()` mods `FragColor.fragcol`: each edge contributes
`dot(p, n) - d` against `Homo3D.fragmapv`, the fragment's own place in map space, and the polygon's signed
distance is the **minimum** across edges — so one `smoothstep` over that minimum's own `fwidth`
antialiases every edge and corner at once. Hence convex: a convex polygon is exactly the intersection of
its edges' half-planes.

They are built on the CPU, each point into map space once, with every inward normal chosen by which side
the centroid falls on — so a ring may be wound either way.

**Moving one costs a state push, not a rebuild.** `MCache.add`/`remove` bump `MCache.olseq`, which makes
`MCache.Grid.getolcut` dispose and rebuild every overlay mesh in that grid — so the mask is over-covered
and re-cut **only** when the ring leaves the tiles it already covers. Everything else — following a gob,
turning, scaling, tinting — is a new carve state pushed through the slot.

**The edge array is sized by the fragment stage, and by nothing else.** The half-planes are one
`Array(VEC4)` uniform walked by a `For`: `haven.render.sl` carries `Array`, `Index` and `For`, and
`UniformApplier.TypeMapping.register` is **public static**, so mapping it from `float[][]` beside the
`Array(MAT4)` row already there is a static initialiser of our own and no core edit. GLSL declares that
array at a fixed length, so a limit exists — it comes from what the fragment stage holds, is stated on the
page as a number, and a longer ring is refused naming it.

**Clicks are the projected ring.** `MapView` already runs four `// addon:` hooks at the top of
`mousedown`/`mouseup`/`mousemove`/`mousewheel` synchronously before its own dispatch. A patch hit-tests by
projecting its ring and solving the point against the same half-planes the carve writes; the engine's pick
pass is asynchronous and cannot answer inside the event.

## Files to create and modify

| File | What |
|---|---|
| `src/io/brodgar/addon/PatchOverlay.java` | new — the `OverlayInfo` + `LocalOverlay` pair, the bounding-box mask, the `Material` |
| `src/io/brodgar/addon/PatchCarve.java` | new — the `State`, its edge uniforms, the `fwidth` builtin, the shader, the half-plane build |
| `src/io/brodgar/addon/LuaPatch.java` | new — the kind: its ring, and the shared vocabulary's per-kind half |
| `src/io/brodgar/addon/VrApi.java` | the fifth collection, and its share of `:entity()` |
| `src/io/brodgar/addon/LuaWorldEntity.java` | `:offset`'s refusal of a `z` on a patch |
| `src/io/brodgar/addon/AddonManager.java` | `PatchClicked` beside its three siblings |
| `src/haven/MapView.java` | `// addon:` — `Overlay` keeps the slot `added` adds the material into and re-pushes through it; the `:spike` command comes out |
| `src/io/brodgar/addon/SpikeWorldPaint.java` | **deleted** |
| `docs/addons/api/vr/patches.md` | new — the kind's page |
| `docs/addons/api/vr/README.md` | the fifth collection; the four-kinds heading loses its count, its two inbound anchor links re-pointed |
| `docs/addons/api/event/bus/world.md`, `event/bus/README.md` | `PatchClicked`, and the `ev` table that was closed at three |
| `docs/addons/api/gob.md`, `docs/addons/api/README.md` | the index, and what a hitbox is now for |
| `docs/client/world-3d.md` | the ground-overlay gotchas, and the split its ceiling forces |

## Risks and gotchas

- **A uniform is baked, not re-read.** `GLDrawList.DrawSlot.getsettings` resolves every uniform at slot
  construction and caches it under a `SettingKey`, so mutating a `State`'s array afterwards propagates
  nothing: the value arrives as a **new state pushed through the slot**. Hence the `MapView.Overlay`
  seam — `added` is the only place `id.mat()` is read today.
- **`getolcut` builds the outline mesh either way.** `MCache.Grid.getolcut` calls `MapMesh.makeolol`
  whether or not `omat()` is null, so a patch pays two tile-laying passes per cut. That is the ROADMAP
  defect filed at 068; a patch does not fix it and must not be costed as if absent.
- **The drawn terrain surface is not `getcz` or `getfz`.** `MapMesh.MapSurface` is per cut, implements
  `ConsHooks`, and `MapMesh` keeps a `ZSurface` per tiler. Sampling it and placing geometry on the result
  is what failed.
- **`haven.render.sl` has no `fwidth`.** `Function.Builtin`'s constructor is public, so declaring one is a
  line — and it is what makes the rim one pixel at every zoom rather than a slab of world.
- **Map space is world space with `y` negated**: `Gob.Placed` does `rc.y = -rc.y` before
  `Transform.makexlate`, and `Homo3D.fragmapv` reports that space.

## Discarded alternatives

- **A new section (`hafen.scene()`)** — rejected: a patch is a thing placed in the world, which is what
  `hafen.vr()` already is; a second door onto that would have to teach which one to use.
- **`hafen.world():patch()`** — rejected for the reason 043 gave: the live world is what the server sent,
  and a thing of yours is not a sibling of a real gob.
- **A `g`-style world painter with a depth mode** — rejected: "in front" is already
  `gob:overlay():draw`, and the ground is not a painter at all, so the mode would have made `g` mean two
  things and re-shipped a layer that exists.
- **`z` on `:offset` choosing ground or raised** — rejected: two mechanisms behind one number is a cliff.
- **Naming it `geometry`** — rejected: a mass noun where its four siblings are countable kinds, and it
  promises volume this does not ship. **`plane`** — the vr pages already use the word for the geometric
  plane a facing mode puts a quad in. **`footprint`** — `gob.md` uses it for the collision shape the
  *game* gives an object. **`decal`** — jargon from another domain, not the client's vocabulary.
  **`draw`** — a verb where the siblings are nouns, and the painter callback already owns it.
- **The tile mask as the silhouette** — rejected: an 11-unit staircase where the ring is the shape.
- **One uniform per edge, capped at a small number** — rejected: it was the spike's shortcut to avoid a
  type registration, and freezing an implementation convenience into the contract is exactly the kind of
  limit a line of our own code exists to remove — as is **packing four half-planes per `MAT4`**, which
  needs no registration but makes every shader read index a matrix row for an edge.
- **Sizing the edge limit by the `obst` rings resources carry** — rejected: `obst` is where *one* caller
  reads its points; a patch takes a ring from anywhere, and an API measured against one caller's data
  source is that caller's addon with a namespace on it.
