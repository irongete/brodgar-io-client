# Gob sprites: `ModSprite`, its mods, and variable materials

> How a resource-drawn object becomes render-tree nodes: the `ModSprite` a `ResDrawable` builds, the `Mod`
> pipeline that assembles its parts, which `GAttrib`s take part in it and when it rebuilds, and `lib/vmat`,
> the served attribute that dresses a model's tagged parts in materials the server chose.

## The sprite

| What | Where |
|---|---|
| Who builds it | `ResDrawable`'s constructor: `Sprite.create(owner, res, sdt)` — the resource's own `Sprite.Factory` (its `code` layer, `Resource.getcode`) first, then `Sprite.factories` in order, else a bare `Sprite`. `ModSprite.fact` is the `@Resource.PublishedCode.Builtin(name = "mod")` factory and answers for any resource with a `FastMesh.MeshRes` or a `RenderLink.Res` layer |
| The gob it belongs to | `ModSprite.gob` = `owner.fcontext(Gob.class, false)`, resolved through the owner's context chain: a render-linked sub-sprite's owner is `ModSprite.RenderLinks`, whose `context` falls through to the main sprite's owner, so it finds the **same gob**; `null` for an owner with no gob in its chain (an item icon), and then the attribute half below is off |
| What it draws | `ModSprite.parts`, a `RenderTree.Node[]` rebuilt by `update()` and re-added to every slot in `ModSprite.slots` through `RUtils.readd` (the previous parts restored on a `Loading`) |
| The per-part record | `ModSprite.Part`: `obj` (the node — a `FastMesh.ResourceMesh` for a mesh layer, whose `info.rdat` is the layer's key/value tags), `wraps` (a `LinkedList<NodeWrap>`, the first applied **innermost**), `state` (`Pipe.Op`s composed once), `dynstate` (per-frame suppliers, a `RUtils.StateTickNode`), `info` (metadata). `Part.make()` composes them in that order |
| A material on a part | `Material implements Pipe.Op` (so a `NodeWrap`): `apply(Pipe)` puts its `states` and `dynstates`; `apply(RenderTree.Node)` wraps the node in the dynamic states unlocked and then the static ones **locked** — each a `Pipe.Op.Wrapping`, whose `added(slot)` does `slot.ostate(op)` and, when `locked`, `slot.lockstate()`. A `MeshRes` carries its own `Material.Res` as `mat`, and `ModSprite.Meshes.operate` adds each mesh as `new Part(mr.m, mr.mat.get())` — the material is the part's first wrap |

## The `Mod` pipeline

| What | Where |
|---|---|
| The contract | `ModSprite.Mod`: `operate(Cons)`, `order()` (default `0`), `age()`, `decdata(Message)` |
| Three sources of mods | `ModSprite.modifiers(Cons)`: the resource's (`ResData.mods`, built once per `Resource` by every `RMod` in `ModSprite.rmods` — the `@RMod.Global` classes, `Meshes.$res`, `RenderLinks.$res`, `Animation.$res`, `Poser.$res` — plus the resource's own `RMod` code), then the instance's (`imods`, what `SMod`s installed at construction), then the **gob's** (`omods`) |
| The gob's mods | `ModSprite.omods(buf, gob)`: every value of `Gob.attr` that `instanceof Mod`. So a `GAttrib` that implements `Mod` takes part in the sprite of the gob it sits on, with no registration beyond `Gob.setattr` |
| The order | `Cons.process()` pops the **lowest `order()` first**, one at a time, and each `operate` sees the `Cons.parts` the earlier ones built. `Poser` is `-1000`, the mesh and link mods `0`, `VarMats` `100`, `Animation` `1000`, `Poser.Applier` `1010` — so a wrap that must go under the animation and pose morphs goes in between |
| Adding to a part in flight | `Cons.parts` is the live collection: a later mod mutates `part.wraps`/`part.state`/`part.dynstate` in place, `addFirst` on `wraps` making it the innermost |
| The current build | `ModSprite.curcons()` — a `ThreadLocal` set for the duration of `Cons.process()`, so a mod can find the `Cons` it is being run in from anywhere below it (`eqpoint` during construction uses it) |

## When it rebuilds, and by what test

| What | Where |
|---|---|
| The trigger | `ModSprite.tick(dt)` compares `gob.updateseq` against its own `lastupd` and calls `attrupdate()` when they differ. `Gob.updated()` bumps `updateseq`; `OCache.GobInfo.apply` calls it once after every batch of deltas and once on removal, `MapView.Plob.move` when the placement ghost moves |
| The test | `attrupdate()` re-collects `getomods()` and calls `update()` **only when `Arrays.equals` over the `Mod[]` fails — an identity comparison per element**. A `GAttrib` mutated in place is the same instance and rebuilds nothing; a new instance under the same key is a different array and rebuilds everything. `Gob.setattr` replaces the instance and `dispose()`s the old one, which is what a re-sent server attribute does |
| What is retried | `tick` catches **`Loading` only**, leaving `lastupd` behind so the next tick tries again, and `attrupdate` restores the previous `omods` on it. Anything else `operate` throws leaves `tick` — into `Gob.ctick`'s caller on the UI thread |
| `update(Message)` | the `Sprite.CUpd` half: `decdata` (each `imods`/`ResData.mods` entry gets a chance to consume the state bytes, else `flags = decflags(sdt)`, the bit mask `Meshes`/`RenderLinks`/`Animation`/`Poser` gate their layers by `id`) then `update()` |

## `lib/vmat`: variable materials

Served code (`get-code lib/vmat`, version 39 at the time of the adoption under `src/haven/res/lib/vmat/`,
[published-code.md](published-code.md)). A model whose mesh layers carry a `vm=<n>` tag is drawn in
whatever material the server names for slot `n`, so one cupboard resource is every wood.

| What | Where |
|---|---|
| The tag | `FastMesh.MeshRes.rdat.get("vm")` on the `ResourceMesh`'s `info` — the slot number as a string; a mesh without one is not variable |
| The attribute | `VarMats extends GAttrib implements ModSprite.Mod`, abstract on `varmat(int id)`, `order()` **100**. `operate(Cons)` walks `cons.parts`, and for every `FastMesh.ResourceMesh` whose tag names a slot with a material does `part.wraps.addFirst(new VarWrap.Applier(mat, mid))` — innermost, under the mesh's own `mat` |
| The server's dressing | `AttrMats extends VarMats`: `mats`, a `Map<Integer, Material>` keyed `0..` in **wire order**. `AttrMats.decode(Resource.Resolver, Message)` reads pairs of (resource id, `int8` material id) until the message ends, resolving each to `Material.Res.get()` — a `Material.ResMaterial`, which carries `res` (the `Resource`) and `id` (the `mat2` layer's) — and numbers them by position, not by the mesh tag. `AttrMats.parse(Gob, Message)` is the resource's `GAttrib.Parser` (`>objdelta`), reached from `OCache.$resattr` on an `OD_RESATTR` delta: it `gob.setattr`s a **fresh** `AttrMats` every time the server sends the attribute |
| Where it sits in `Gob.attr` | under `VarMats.class`: `Gob.attrclass` keys the map on the class **directly under `GAttrib`**, so `gob.getattr(VarMats.class)` finds it and `getattr(AttrMats.class)` does too (`getattr` walks up to the same key and then tests `isInstance`) |
| The wrap | `VarWrap extends Pipe.Op.Wrapping`, **locked**, carrying `mid`; `VarWrap.Applier implements NodeWrap` mints one per `apply`. A locked slot refuses every later `ostate` on it (`RenderTree.TreeSlot.lockstate`), so nothing above the wrap can change the states the material set |
| `VarSprite` | `extends ModSprite` with nothing added — the resource's `>spr` entry, so a `lib/vmat` user is a `ModSprite` by declaration |

## Gotchas

- **Identity, not equality, triggers the re-render.** `attrupdate` compares the `Mod[]` it collected against
  the last one with `Arrays.equals`, and `GAttrib` has no `equals`, so the test is `==` per element: a `Mod`
  attribute whose fields change in place is the same instance, the arrays match, and `update()` never runs —
  the parts keep the wraps the old state built. A change that must draw goes in as a **new instance** under
  the same key (`Gob.setattr` replaces; `AttrMats.parse` mints one per delta for this reason), followed by
  `Gob.updated()` so the sequence moves.
- **One gob, several `ModSprite`s, one set of mods.** A `RenderLink.Res` layer makes the main sprite build a
  sub-sprite per link (`RenderLinks.operate` → `Sprite.create` on the linked resource), and each sub-sprite is a
  `ModSprite` of its own whose `gob` resolves to the same `Gob` — so every `Mod` attribute on the gob is collected
  by every one of them and its `operate` runs once **per sprite** per rebuild, each call seeing only that
  sprite's `Cons.parts`. A barter stand is the stand, its sign and a few one-mesh links, all wearing the one
  `AttrMats`. A mod that records anything from `operate` merges per part, never replaces: the last sprite to
  run is the one with the fewest parts.
- **Only `Loading` is retried, and it retries the whole rebuild.** `tick` catches `Loading` alone: a `Mod`
  whose `operate` needs a resource still in flight throws it, `lastupd` stays behind, the previous parts stay
  on screen and every mod runs again next tick — so a slow fetch in one mod holds back every other mod's
  change until it lands. Anything else `operate` throws (a `Resource.BadResourceException` from a fetch that
  failed, a `NoSuchLayerException` from `flayer`) is not caught here or in `Gob.ctick`: it reaches the UI
  thread. A mod that resolves a resource it did not receive from the server catches those itself.
- **`instanceof` against an adopted served class fails after a version bump.** The local copy under
  `src/haven/res/` wins only while its `@FromResource` version matches the served resource
  ([published-code.md](published-code.md)); past that the served loader defines a second
  `haven.res.lib.vmat.AttrMats`, `Gob.attr` is keyed on *that* class, and `gob.getattr(VarMats.class)`
  answers `null` for every object. So a reader through the local copy degrades to "no object has variable
  materials" rather than throwing — and `haven.Resource find-updates src` is what reports the pin moving.
- **`AttrMats.mats.size()` is always `0`.** `decode` builds a `haven.IntMap`, and `IntMap.put` never bumps
  the `sz` field that its `entrySet().size()` — and so `AbstractMap.size()` and `isEmpty()` — answers. `get`,
  `containsKey` and the entry iterator read the backing array and are right; a count has to walk them. In
  the tree only `SpriteLink` builds one, and it never asks its size; served code does.
- **The slot number is the position in the message, not the mesh tag.** `AttrMats.decode` numbers
  materials `0..` in arrival order; the model's `vm` tags are what select them. A slot the model has and
  the server did not send has no material, and `VarMats.operate` leaves that mesh in its own `mat`.
- **A material's states do not cover what it does not set.** A `Material` is a set of `Pipe.Op`s, one per
  `State.Slot`; an inner wrap wins the slots it fills and every other slot leaks through from the wrap
  outside it (the mesh's own `mat`, a texture, a light). Replacing what a part is drawn in means replacing
  the wrap, not adding one inside it.

## See also

- [state roots](state.md) — `Gob.attr`, `attrclass`, who writes the attrib map and who walks it
- [published code](published-code.md) — `get-code`, `@FromResource` and the version rule an adopted copy lives by
- [resources](resources.md) — what a `.res` carries, and the `OD_RES` delta beside `OD_RESATTR`
- [the 3D world](world-3d.md) — the `MapView` scene the sprite's parts are added to, and materials in it
