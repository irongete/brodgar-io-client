# 152 — Plan

## Approach

**The engine seam.** A gob's model is a `haven.ModSprite`; on every `tick` it compares
`gob.updateseq` against its own `lastupd` and, when they differ, `attrupdate()` collects every
`GAttrib` on the gob that implements `ModSprite.Mod` (`omods`) and calls `update()` **only when that
array differs by identity** (`Arrays.equals` over instances). `update()` runs `Cons.process`, which
pops the `Mod`s **lowest `order()` first**, then `Part.make()` applies each part's `wraps` in list
order — the first wrap is the innermost node. `lib/vmat` (served code, version 39, fetched with
`get-code`) is four classes: `VarMats extends GAttrib implements Mod` (`order() 100`; `operate` wraps
every `FastMesh.ResourceMesh` whose `info.rdat.get("vm")` names a slot in `new VarWrap.Applier(mat,
mid)`, added **first**), `AttrMats extends VarMats` (`Map<Integer, Material> mats`, keyed `0..` in wire
order by `decode`; `parse` is the `OD_RESATTR` entry that `gob.setattr`s a fresh one), `VarWrap`
(`Pipe.Op.Wrapping`, locked) and `VarSprite`. `Gob.attrclass` keys the map on the class **directly
under `GAttrib`**, so the server's attribute sits under `VarMats.class`.

**Adopt, do not reflect.** The four classes are copied verbatim under `src/haven/res/lib/vmat/`
with `@haven.FromResource(name = "lib/vmat", version = 39)`, `lib/obst`'s idiom: the loader takes the
local copy while the served version matches and warns and uses the served one otherwise, so a
server-side bump degrades to "no object has slots" rather than breaking. The read is then
`gob.getattr(VarMats.class)` cast to `AttrMats`, and its `mats` values are `Material.ResMaterial`,
each carrying `res` (the `Resource`) and `id` (the `mat2` layer's).

**The override is an attribute of its own.** `io.brodgar.addon.GobMaterials extends GAttrib implements
ModSprite.Mod`, `order() 101`, keyed on its own class — never on `VarMats.class`, so the server's
re-sent `AttrMats` replaces the server's and touches nothing of ours, and the `gob.updated()` that
delta ends with re-runs both. Its state is an immutable map `wire → Entry(owner, name, id,
Indir<Resource>)`; **every write mints a new instance** (`setattr` replaces; a new identity is what
makes `attrupdate` re-render) and calls `gob.updated()` — package-private today, made `public` under
one `// addon:` tag. `operate(Cons)`: for each part whose mesh carries a `vm` we override, remove the
`VarWrap.Applier` wraps with that `mid` and `addFirst` ours. Resolving the material: `indir.get()`
throws `Loading` until the fetch lands — the engine's own retry, since `ModSprite.tick` catches it
and tries again next tick — and `Resource.BadResourceException` (a name the server has not got) must
be **caught in `operate`**, the slot left to the server's wrap and the entry marked failed: `tick`
catches only `Loading`, and anything else escapes into the UI thread. A loaded resource with no
`Material.Res` at `id` is the same outcome. Each `operate` records per wire what was drawn, which is
what `slot:drawn()` reads on the handle's copy. `GobTint`'s rules for the rest: `on(g)`, `apply`,
`revert(g, addon)` per slot by owner, last write wins per slot; applied to `AddonManager.gobCopies(id)`
on write; recorded in `GobIntent.Record` (`Map<Integer, MaterialSpec> materials`, part of `empty()`),
re-applied in `applyTo`, dropped in `forget`; reverted in `UiApi.teardownGobScales`'s per-gob loop.

**The Lua surface.** `LuaMaterials` (the collection, a view built per call like `LuaOverlay.collection`)
and `LuaMaterialSlot` (interned per addon on `(login, gob, wire)` through a `LuaOverlay.Cache`-shaped
cache on `Addon`). Reads hand back `LuaResource.of(owner, name)`. The write's name goes through
`ResourceApi.name` (the well-formed rule) and `Resource.remote().load(name)`; `id` through
`Args.integer`. `gob:info()`'s `materials` is added in `AddonManager.gobSnapshot`.

## Files to create/modify

- `src/haven/res/lib/vmat/{VarMats,AttrMats,VarWrap,VarSprite}.java` — new, adopted verbatim.
- `src/haven/Gob.java` — `updated()` public (`// addon:`).
- `src/io/brodgar/addon/GobMaterials.java` — new. `LuaMaterials.java`, `LuaMaterialSlot.java` — new.
- `src/io/brodgar/addon/LuaGob.java` (`materials` verb), `AddonManager.java` (`gobSnapshot`),
  `Addon.java` (the slot cache), `GobIntent.java`, `UiApi.java` (`teardownGobScales`).
- `docs/addons/api/materials.md` — new. `gob.md`, `look.md`, `types/world.md`, `api/README.md` — rows.
- `docs/client/gob-sprites.md` — new; `docs/client/README.md` row; `docs/client/state.md` attrib-map
  row's dead `learnings/engine-lifecycle.md` pointer re-aimed at it.
- `addons/152-gob-materials.{1,2,3}/` — the suites.

## Risks & gotchas

- **Identity, not equality, triggers the re-render** (`ModSprite.attrupdate`): mutating the attribute
  in place draws nothing new. Mint per write.
- **Only `Loading` is retried** (`ModSprite.tick`); `BadResourceException` out of `operate` kills the
  UI thread. Catch it in `operate`, never let it out.
- **Wrap order is state precedence**: a wrap added first is innermost and its `Material` states win
  per `State.Slot`, but states it does not set **leak through** from an outer material (a texture under
  a plain colour). Remove the server's `VarWrap.Applier` for the slot rather than wrapping inside it.
- **`instanceof` against the adopted class fails after a version bump** (`state.md`'s attrib-map row
  says it for `getattr`): reads answer no slots, and the write refuses a slot that does not exist, so
  nothing stacks. `haven.Resource find-updates src` reports the pin moving.
- **The write runs under the session's tree monitor** (a Lua verb); `Gob.setattr` and `updated()` take
  the gob's monitor inside it — the lock order every gob write here already keeps (gob after tree is
  never taken; `OCache.ctick` holds the gob alone).
- **`GobIntent.applyTo` runs before the copy's sprite exists**; `operate` reads the parts when the
  sprite builds, so the order does not matter — but the `AttrMats` may not be parsed yet either, so
  the re-apply must not consult it (it installs the override blind, as the write on a copy does).
- **A suite needs a dressed object in view** and a second material name: `slot:native():name()` of
  another slot or another object of the kind, or a name the server holds for the kind (the wood
  family the object's own material came from). Score `drawn()` on a bounded timer.
- **Composed bodies** (`Composite`, players/animals) are not `ModSprite`s and carry no `lib/vmat`:
  `count()` is `0`, by construction.

## Discarded alternatives

- **Reflection by class name instead of the adopted copy** — robust to a version bump, but outside
  the project's stated idiom for served code, and the bump degrades cleanly either way.
- **Subclassing `VarMats` and replacing the server's attribute under `VarMats.class`** — one `Mod`
  and no wrap surgery, but the server's next `lib/vmat` delta silently replaces it and the override
  is gone with nothing to say so; an attribute of our own outlives every re-send.
- **Wrapping inside the server's wrap rather than removing it** — the inner material wins only the
  `State.Slot`s it sets; a texture or a light state the override lacks leaks through from the outer.
- **Refusing a name the client does not hold yet** — a synchronous, fully checkable write, but every
  caller would poll `hafen.resource()` first; the resource writes already register on an unloaded
  name and apply when it lands, and `drawn()` makes the wait observable.
- **Accepting a `Resource` handle or a `Layer` as the argument** — a second spelling of one write;
  the read hands a handle back and its `:name()` is the string the write takes.
- **Slots read from the meshes' `vm` tags** — the model's set, not the server's; a slot the server never
  dressed has no `native()` to answer and the collection is the server's dressing.
- **`gob:materials()` answering `nil` on an object with none** — the collection is a view like
  `gob:overlay()`; `count() == 0` is the answer, and no caller needs a nil check before `:list()`.
