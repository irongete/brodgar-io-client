# Resources: what a `.res` carries

A `Resource` is the versioned, named asset both sides load by the same id — a tree, a gate, a crop —
built out of typed `Layer`s (`Resource.Layer`). What a gob's state bytes are and how a change to them
reaches the client are both questions about this shape, not about `Gob` itself.

## Reading a layer

| Call | Answers | Absent |
|---|---|---|
| `Resource.layer(Class<L>)` | the first layer assignable to `L` | `null` |
| `Resource.flayer(Class<L>)` | same | throws `Resource.NoSuchLayerException` |
| `Resource.layer(Class<L>, Predicate<? super L>)` | the first matching layer that also passes the predicate | `null` |
| `Resource.layer(Class<L extends IDLayer<I>>, I id)` | the layer of that class whose `layerid()` equals `id` | `null` |

Each has an `f`-prefixed twin that throws `Resource.NoSuchLayerException` instead of answering `null`.
A resource may carry several layers of one class, so the id form is how one of several is named —
`Resource.IDLayer<T>` is the one-method interface (`layerid()`) a layer implements to be found this way.

**`id == null` matches the FIRST layer of that class, whatever its id — it does not mean "the layer
with no id".** Passing `null` where a specific id was meant answers whichever layer happened to load
first, silently. A caller wanting a particular id always passes the id, including a resource's own
default `""`.

`Resource.used` is set the moment any `layer`/`flayer` call runs, for the asset pool's own accounting;
`Layer.init()` runs once every layer of the resource has loaded, for a layer that needs to see the set.

## A gob's state bytes: `OD_RES`

The server tells a gob which resource draws it, and optionally what state that resource is in, in one
delta: `OCache.OD_RES` (`2`), handled by `ResDrawable.$cres` (`@OCache.DeltaType(OCache.OD_RES)`). The
wire carries a resource id, and — only when that id's top bit is set — a length-prefixed byte string
straight after it, the state:

| Resource id on the wire | State bytes follow? |
|---|---|
| bit `0x8000` clear | no — just the resource |
| bit `0x8000` set (cleared before use) | yes — a `uint8` length, then that many bytes |

`$cres.apply` compares the new bytes against `ResDrawable.sdt` (a `MessageBuf`, package-private;
`MessageBuf.equals` is a byte comparison and `MessageBuf.nil` is what a first arrival differs from).
Same resource, changed bytes, and the drawn `Sprite` implements `Sprite.CUpd`: it calls
`((Sprite.CUpd)d.spr).update(sdt)` and reassigns `d.sdt` in place. Anything else — a different
resource, a sprite that cannot take a live update, no `ResDrawable` yet at all — replaces the whole
attribute with `Gob.setattr(new ResDrawable(g, res, sdt, msg.old))`, which is a fresh object with a
fresh `sdt`.

**The state lives on the `ResDrawable`, not on the `Gob`.** A body that is a `Composite` (a player) has
no `ResDrawable` and so no state bytes at all — an absent fact, not an empty one. `ResDrawable.sdt` is
package-private, so `AddonWidgets.gobSdt(Gob)` is the one non-zero-edit read surface for it (decision
D-017); that method clones the field before reading it, since the field is shared with the `Sprite`
built from it and reading it directly would advance that sprite's own cursor.

## `obst`: the collision footprint

`Resource.Obstacle` (`@LayerName("obst")`) is an `IDLayer<String>`: `Obstacle.id` and `Obstacle.p`, a
`Coord2d[][]` — one ring of points per shape the resource collides as, **already in world units** —
`Obstacle`'s constructor ends each point with `.mul(MCache.tilesz)`, so scaling a read of `p` by the
tile size again inflates every shape elevenfold.

A resource may carry more than one `obst` layer under ids of its own, so `res.layer(Resource.obst, id)`
is how one is named. **Pass `""`, never `null`, for the collision layer**: `layer(Class, null)` answers
the *first* layer of that class whatever its id — not "the layer with no id" — so a resource carrying a
build box and a collision box under two ids would answer whichever loaded first. An unsupported `obst`
wire version parses to `id = "#"` and an empty `p`, which is a layer that exists but is not found at
`""` and carries no points either way — never assume a found layer has any.

`Gob.BasePlace`, the placer that sits the object on the terrain, reads the same layer
(`new BasePlace(map, surf, res, "")`) and rotates each point by the object's own facing before using it
to probe the ground height: `getz` turns `(x, y)` by `Gob.a` about the origin, adds `Gob.rc`, and walks
the tile grid under the rotated ring to find the lowest point the object's feet touch. That is the same
arithmetic a reader placing the footprint in the world repeats — the object's facing turns the shape in
place, then its position slides the turned shape to where it stands.

## `neg`: a click-box the wire carries and the stock reader throws away

`Resource.Neg` (`@LayerName("neg")`) is read for `cc`, the hotspot a UI icon or a cursor sprite is
placed by, and the eight `ep` rings a click on a *2D* sprite is tested against — both used elsewhere in
`haven` (`MiniMap`, `SimpleSprite`, `UILoop`'s cursor hotspot). Between `cc` and `ep` the wire also
carries two more `Coord`s, opposite corners of a plain click-box — `ac` and `bc` below `cc` in the
stream — and the stock constructor never names them: `buf.skip(12)` walks past all 12 bytes (`ac`,
`bc`, and 4 more this client does not decode either) without keeping any of it.

**A mesh resource with no `obst` layer at all may still carry one of these.** `gfx/terobjs/log` — the
trunk a felled tree becomes — is exactly that: no collision shape, because nothing stops you walking
through one, and a `neg` layer whose `ac`/`bc` are `(-9, -2)` and `(9, 2)`. `addon: ac, bc` decodes
those two `Coord`s; `io.brodgar.addon`'s one caller is `AddonManager.hitboxRings`, behind
`gob:hitbox()`. Nothing upstream reads them, so nothing upstream is at risk: the wire was already being
parsed correctly, with two fields discarded further in.

**Its units are world units, the same `obst` ends in.** That is easy to doubt, because `cc` beside them
is a *pixel* hotspot everywhere else it is read (`MiniMap`, `UILoop`'s cursor, and `MiniMap` even wraps
it in `UI.scale`) — but a tile is 11 world units precisely *because* the old 2D client drew one as 11
pixels, so the grids coincide. The numbers settle it either way: `18×4` for a log, `6×22` for a drying
frame, `10×10` for the largest bumling — 1.6, 2 and 0.9 tiles, which is what those objects measure.
Multiplying by `MCache.tilesz`, as `obst` needs, would make a log 18 **tiles** long.

Reading the layer is not the whole job, and two of the three remaining steps are easy to get wrong:

- **All of them, not the first.** `res.layers(...)` — a resource may carry several `obst` layers under
  ids of its own and the shape is their union. `res.layer(Resource.obst, "")` finds only the unnamed
  one, which on such a resource silently answers a fragment of the object, or nothing.
- **Except `build`.** An `obst` layer under that id is the clearance a placement ghost tests before you
  may put one down. It is larger than the object, and it is not where the object is.
- **On the render-linked mesh.** A gob's own resource may be a thin wrapper whose geometry — and whose
  `obst`/`neg` — lives on a mesh resource it reaches through a `RenderLink.MeshMat` layer. A reader that
  stops at `Drawable.getres()` finds neither layer on exactly the resources built that way.

## Published code, and a shape the resource computes itself

A `.res` can carry **its own Java**: a `code` layer of classes plus a `codeentry` layer naming the entry
point and, under type `2` or a `use` datum, a **classpath** of other resources to load it against —
`$use: lib/obst` in the preprocessed source. `CodeEntry.loader()` chains a class loader per entry.

**A local copy under `src/haven/res/`** wins over the served class only when its
`@haven.FromResource(name, version)` matches the resource actually served; otherwise the loader warns and
uses the fetched code. So adopting a class is version-pinned by construction and a server-side bump
degrades to upstream behaviour rather than breaking. `Resource`'s own `main` has `get-code` (fetch the
source and write it annotated) and `find-updates` (report copies whose version has moved on).

**Some objects have no shape layer at all and are sent one per object.** `gfx/terobjs/consobj` — every
building site in the game — carries neither `obst` nor `neg`: what is going up there is not a property of
the site resource, so its `Consobj` sprite opens its **state bytes** with `Obstacle.parse(sdt)` from
`lib/obst` and stands a pole at each vertex. The rings that come back are model-local and unrotated,
exactly like an `obst` layer's, so the two feed one reader.

`lib/obst`'s wire format is a header byte — type in the high nibble, an extent exponent in the low —
then, by type: `0` several polygons, `2` one polygon, `3` a rectangle as four `snorm8` edges, and `1`
the empty obstacle. An unknown type throws `Message.FormatError`, which is the honest signal that the
bytes were never an obstacle.

## The gob monitor already guards it

`$cres.apply` runs from `OCache.GobInfo.apply`, which takes `synchronized(gob)` around every pending
delta of that tick, one at a time, before applying it. A reader that also takes `synchronized(gob)`
around `ResDrawable.sdt` therefore sees either the bytes from before the change or the bytes from
after it, never a torn read, with no `volatile` needed on either side of the seam.

The delta stream is per session: two sessions holding one gob each run this independently, off their
own `OCache`, so a change landing on one session's copy says nothing about when — or whether — the
other session's copy has seen its own.

## See also

- [state roots](state.md) — where the rest of a gob's live state lives
- [boot and the frame loop](boot-and-loop.md) — the `Loading` protocol a resource read can throw into
