# Weather, tree sway, overlay plumes and plant sprites

> Four seams a client-side setting (fork) draws less at: the weather the server composes into the
> scene, the sway shader every tree and bush carries, the particle plumes standing at a gob's
> overlays, and the sprouts a crop or a forageable clump scatters over its tile.

## Weather: where it lives

| What | Where |
|---|---|
| The live set | `Glob.wmap`, `Map<Indir<Resource>, Object>` keyed by the weather's own resource. A value is either the raw `Object[]` args the `"wth"` branch of `Glob.blob` last put there, or — once read — the instantiated `Glob.Weather` |
| The type | `Glob.Weather`, an interface: `state()` (a `Pipe.Op`, `null` by default), `update(Object...)`, `remove()` (`true` when it is done), `tick(double)` (`true` when it ends itself) |
| Where one is made | `Glob.Weather.Factory`, published code (`@Resource.PublishedCode(name = "wtr", …)`) on the resource's `Resource.CodeEntry` layer. `Glob.weather()` instantiates a `wmap` entry the first time it is read, under `synchronized(this)`, and leaves the `Weather` in place of the raw args from then on |
| Composing the scene | `MapView.updweather()`, called every `MapView.tick`: reads `glob.weather()`, composes every `state()` into `basic(Glob.Weather.class, …)`, and adds every `Weather` that is a `RenderTree.Node` into `MapView.rweather` (a `Map<RenderTree.Node, RenderTree.Slot>`). A node no longer in the list is pulled out of `rweather` and the tree |
| Simulating it | `Glob.ctick()`, under `synchronized(this)`: ticks every live `Weather` in `wmap`, removing (and, if `Disposable`, disposing) one whose `tick(dt)` answers `true` |

**Fork.** Both readers ask the same question by the resource's own name before touching an entry:
`Glob.weather()` skips a withheld one so it never reaches `updweather` (and so `rweather` drops its
node on the next composition), and `Glob.ctick()` skips ticking a withheld one **without removing it**
— a rained-off `rain` still exists in `wmap`, ready the moment it stops being withheld. Neither loop
needs a second pass: the same per-resource decision answers both "should this draw" and "should this
simulate".

## Tree sway: where it lives

| What | Where |
|---|---|
| The adopted copy | `haven.res.lib.svaj`, `@haven.FromResource(name = "lib/svaj", version = 25)`: `GobSvaj` (a `GAttrib` and a `Gob.SetupMod`) and `Svaj` (the `Pipe.Op`/`State` it hands out) |
| The one seam that moved for multi-session | `GobSvaj.st()` translates the shader's origin by `io.brodgar.session.Sessions.offsetfor(gob.glob.map)` before comparing it to the cached `Svaj` — [multi-session.md](multi-session.md) has the why |
| Where a `SetupMod` feeds the placement | `Gob.Placed.Placement`, rebuilt every tick from every `Gob.setupmods` entry's `placestate()`, composed into the `mods` field; `Placement.equals` compares `mods` along with the transform and tile state |
| Applying a changed placement | `Gob.Placed.autotick(dt)` builds a fresh `Placement` and calls `Gob.Placed.update(np)` when it differs from the current one |

**Fork.** `GobSvaj.placestate()` answers `null` while tree effects are off, changing nothing else in the
file — `st()`, carrying the multi-session fix, is untouched. `Placement` already treats a `null` `mods`
term as absent and recomposes every tick regardless of any setting, so a changed answer here is already
a changed `Placement` with no further wiring: the next `autotick` finds the two unequal and calls
`update`.

## Overlays and plumes: where it lives

| What | Where |
|---|---|
| A served overlay arriving | `OCache.$overlay` (a `Delta`): builds a `Gob.Overlay` around an `OCache.OlSprite` (a `Sprite.Mill` closing over the resource and state bytes) and calls `Gob.addol` |
| The overlay object | `Gob.Overlay`: `spr` (`null` until `init()` runs), `slots` (`null` until it stands in a render tree — **the "in a tree" test**), `init()` (lazily creates the sprite and adds it to `gob.slots`), `remove0()`/`add0()` |
| Adding one | `Gob.addol(Overlay, boolean)`: `ol.init()`, `ol.add0()`, `ols.add(ol)` |
| Per-tick upkeep | `Gob.ctick()`: for each `Overlay` in `ols` whose `slots` is still `null`, retries `init()`; if it is **still** `null` afterwards, the loop `continue`s past `ol.tick(dt)` unless this session is dormant (the `rts:` comment there states the contract: an overlay in no render tree is not ticked) |

**Fork.** `Gob.withheldplume(Sprite)` decides by resource name — the gob's own `Drawable.getres()` as the
owner, the overlay `Sprite`'s own `res` as the plume — and answers through the one holder the panel
keeps. `Overlay.init()`'s last line gates its `RUtils.multiadd` on it: a withheld plume's `slots` never
leaves `null`, so `Gob.ctick()`'s existing retry-and-skip above costs it nothing and it is *shown* the
moment `smoke(true)` lets a later `init()` through — no walk needed for that direction. The *withhold*
direction, for a plume already standing in a tree, is `Gob.plumes()` (`defer(this::syncplumes)`) and
`syncplumes()`: under the gob's own monitor, for every `Overlay` with `slots != null` and a withheld
name, `RUtils.multirem(new ArrayList<>(ol.slots))` then `ol.slots = null` — leaving the `Overlay` in
`ols`, so `gob:overlay()` keeps listing it, `GobOverlayAdded` already fired for it stands, and the
server's own removal of it still works.

## Plant sprites: where it lives

| What | Where |
|---|---|
| The factories | Served code on the crop and forageable resources, reached as the resource's `Sprite.Factory` through `Resource.getcode(Sprite.Factory.class, false)` (`Sprite.create` asks it first). The factory instance is made once per resource by `Resource.CodeEntry.get` from the `codeentry` layer's arguments and cached there; `create(owner, res, sdt)` runs once per gob |
| Field crops | `lib/plants`, adopted at version 11 under `src/haven/res/lib/plants/`: `GrowingPlant` holds `num` (the count, an argument of the entry) and `var`, the mesh variants grouped by growth stage (`MeshRes.id / 10`); `create` reads the stage from the first byte of the state bytes and scatters `num` parts over the tile at random offsets from `owner.mkrandoom()` |
| Trellis crops | `TrellisPlant`, beside it: the same `num` and stages, its parts lined along the trellis at `11f / num` apart, rotated by the gob's `a` |
| Forageable clumps | `lib/gplant`, adopted at version 1 under `src/haven/res/lib/gplant/`: `GaussianPlant` holds `numl`..`numh` and a radius `r`; `create` draws a count in that range and places each part at a gaussian offset, a variant being a mesh layer or a `RenderLink.Res` made per owner. A forageable drawn as one mesh does not use it |
| The part | `CSprite.addpart(xo, yo, a, mat, node)`: one render node per sprout at an offset and an explicit angle, all under the one `CSprite` the factory returns |
| The drawable | `ResDrawable`: `res` (the `Indir<Resource>`), `rres` (resolved in the constructor), `sdt` (a package-private `MessageBuf` of the state bytes) and `spr`, created by `Sprite.create` in the constructor and aged. `OCache.$cres` replaces it through `Gob.setattr` when the resource or the state bytes change, and updates the sprite in place when only the bytes did and the sprite is a `Sprite.CUpd` |

**Fork.** The three copies scale the count they draw by the panel's plant amount — a prefix of the full
set, since the loop runs to the scaled count and the random sequence is untouched, so a lower
setting keeps every drawn sprout where the full field had it; `TrellisPlant` re-spaces its prefix along the
whole trellis. At the default the loop bound is the count itself: upstream's own code path. A write of
`crops` or `forage` is followed by `Gob.replant()` (`defer(this::syncplant)`) over every gob of every
session: a `ResDrawable` whose `rres.getcode(Sprite.Factory.class, false)` is an instance of one of the
three is replaced by `setattr(new ResDrawable(this, rd.res, rd.sdt.clone()))` and `updated()` — the same
path a re-sent `OD_RES` takes, so the old drawable is disposed and the slots swapped by `setattr`, and the
gob's id, position, name, state bytes and overlays are untouched. A served factory of another version is
not an instance of the local class and is left alone, which is right: the setting never reached it.

## Gotchas

- **A weather not yet loaded throws `Loading`.** `Indir<Resource>.get()` on a `wmap` key can throw before
  the resource resolves; the withheld check swallows it and treats the entry as not-withheld for that
  frame — the very same `Loading` the factory branch of `Glob.weather()` would throw a line later, so
  nothing is skipped that would not already have been deferred.
- **`GobSvaj`'s fix is entirely in `st()`.** The tree-effects fork touches only `placestate()`'s return;
  changing anything inside `st()` risks the multi-session origin fix documented there (069).
- **A plant's sprout count is fixed when its sprite is created.** `create` reads the count and places
  every part in one pass, and `CSprite` has no way to add or drop a part afterwards; a changed amount is a
  new sprite, which is a new `ResDrawable`. Withholding the drawable would hide the whole plant.
- **`Sprite.Factory` and the adopted class are the same object only for a `Direct` factory.**
  `Sprite.FactMaker` chains three ways of making a factory out of a resource's entry class: an instance of
  the class when it implements `Sprite.Factory` (the three plant classes), else a lambda over a static
  `mksprite` or over a constructor. An `instanceof` test against an adopted class holds for the first kind
  alone; for the other two the instance is a lambda and the test is always false.
- **`Overlay.slots == null` already meant two things before this feature** — "never added yet" and "this
  session is dormant and the overlay is deliberately held out of the tree" (`Gob.ctick`'s own comment).
  Withholding a plume is a third reason with the same observable shape, which is exactly why the existing
  retry-and-skip in `Gob.ctick` needed no change to also cover it.

## What is not mapped

The particle simulation inside a plume's own sprite, and the render backend's instancing of weather
geometry.

## See also

- [the 3D world](world-3d.md) — the `MapView` scene `updweather` composes into
- [state roots](state.md) — `Glob`, `OCache` and the `Gob`/`GAttrib` lifetime
- [several sessions at once](multi-session.md) — the sway origin fix `GobSvaj.st()` carries
- [gob sprites](gob-sprites.md) — how a resource-drawn object becomes render nodes, and the `lib/vmat` adoption
- [published code](published-code.md) — `get-code`, `@FromResource` and the version rule an adopted copy lives by
