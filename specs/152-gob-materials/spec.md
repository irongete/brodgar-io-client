# 152 — Gob variable materials: `gob:materials()`

## What & why

Many objects — cupboards, chests, carts, boats, walls — are one model drawn in **variable
materials**: the server sends one material resource per *slot* (the `lib/vmat` attribute) and the
client wraps each tagged mesh in it. An addon can scale, tint and hide an object, and can read nothing
of, and change nothing in, what it is made of.

`gob:materials()` is the collection of an object's material slots. A slot reads which resource the
server dressed it in and takes another by name to be drawn in instead — client-local, purely visual,
unprotected, nothing on the wire or on disk: `gob:tint`'s footing and lifetime rules exactly. An
addon can preview a cupboard in oak before building it, or mark a chest by re-dressing it.

## Acceptance criteria

1. **The collection.** `gob:materials()` answers a collection on every gob (a view, derived each call): `:list([filter])`, `:count([filter])`, `:find(filter)`, `:get(index)`, in the
   conventions' shape, over the slots the server sent. An object with no variable materials counts
   `0`; a gone gob lists nothing. `:get(0)` is refused naming the 1-based rule; `:get(n)` past the
   count is `nil`; a non-integer index is refused.
2. **The slot.** A `MaterialSlot` is a live handle, interned per addon on (login, gob, slot):
   `:index()` (1-based), `:wire()` (the server's own number — the `vm` tag on the mesh, `index - 1`),
   `:native()` (the server's material, a `Resource` handle), `:material()` (the material in force:
   yours once written, else the server's), `:drawn()` (the resource the model is drawn with right
   now, on the copy this handle reads through), `:info()` (`{index, wire, native, material, drawn,
   id}`, names as strings). The reads answer `nil` once the gob is gone; `:index()`/`:wire()`
   always answer.
3. **The write.** `slot:material(name[, id])` dresses the slot in the named resource's `mat2` layer
   (`id` a whole number naming which, the resource's first by default), chains, and lands on the next
   frame when the client holds the resource. The client fetches a resource it does not hold and the
   swap lands when the fetch does; until then, and if the name fails to load or holds no material at
   `id`, the server's own stays drawn — `slot:material():loaded()`/`:error()` and `slot:drawn()` say
   which. Refused when made: a name that is not a string or is malformed (the `hafen.resource()` rule),
   an `id` that is not a whole number, an explicit `nil` (naming `:release()`). Last write wins per slot.
4. **The write lands on the object**, not on one copy: every live session's copy of the gob, and a
   session that loads it later — the rules `gob:tint` obeys. What the server re-sends for the object
   (its own `lib/vmat` attribute) replaces nothing of yours.
5. **The endings.** `slot:release()` hands the slot back to the server's material and chains;
   `gob:materials():release()` drops your addon's every write on that object; both are no-ops on an
   untouched slot. Disabling or reloading your addon releases every write it made; the object leaving
   its last session forgets it. `:material()` reads `native()` again after each.
6. **`gob:info()`** carries `materials`: an array of the resource names in force per slot, absent for
   an object with none.
7. **The engine map.** `docs/client/gob-sprites.md` maps `ModSprite`, its `Mod` order, the
   identity-compared attribute re-render and `lib/vmat` — the source this feature had to read.

## Out of scope

- **A material built from anything but a served resource** — a colour, a texture asset, a `.res` the
  addon ships: that is a `hafen.resource()` write (`layers(file)` carries a `mat2`), and this write
  takes a name. A material *layer* handle as the argument would be that feature's second door.
- **Slots the model has and the server did not send** (a `vm` tag with no material) — the collection
  is the server's set; the mesh's tags stay an engine fact on the client page.
- **Composed bodies** (players, animals): they carry no `lib/vmat`; `count()` is `0` there.
- **Persisting a dressing**: an addon holding a rule re-applies it on `GobAdded`; nothing here writes disk.

## Docs impact

- **Written**: `docs/addons/api/materials.md` (new, the collection and the slot); `docs/addons/api/gob.md`
  (a `:materials()` row in the visual-modification table, a *See also* line); `docs/addons/api/look.md`
  (one line naming the fourth override and linking it); `docs/addons/api/types/world.md` (`GobInfo.materials`);
  `docs/addons/api/README.md` (the `gob/materials` index row); `docs/client/gob-sprites.md` (new) and its
  row in `docs/client/README.md`; `docs/client/state.md`'s attrib-map row, whose `learnings/…` pointer
  resolves to nothing, re-pointed at the new page.
- **Derived impact set**: `grep -rn -i "material" docs/addons/ | grep -v "^docs/addons/api/resource/"` →
  no hits: nothing states a material cannot be changed. `grep -rn -i "tinting" docs/addons/api/` →
  `gob.md:59`, `look.md:3`, `README.md:23` (plus two unrelated `:tint` rows): where the override family is enumerated, each gaining
  the fourth member. `grep -rn "ModSprite\|vmat" docs/client/` → none: the page is new.

## Context files

- `docs/addons/api/gob.md`, `docs/addons/api/look.md`, `docs/addons/api/overlay.md` — 1, 2, 3
- `docs/addons/api/resource/README.md` — 1, 2
- `docs/addons/api/types/world.md` — 1
- `docs/addons/api/README.md` — 1
- `docs/client/published-code.md`, `docs/client/state.md` — 1
- `docs/client/README.md` — 1
- `DOCUMENTATION.md` — 1, 2, 3
- `src/haven/ModSprite.java` — 1, 2
- `src/haven/Gob.java` (`attrclass`, `setattr`, `updated`, `updateseq`) — 1, 2
- `src/haven/Material.java` (`Res`, `ResMaterial`) — 1, 2
- `src/haven/res/lib/obst/Obstacle.java` — 1
- `src/haven/res/lib/vmat/{VarMats,AttrMats,VarWrap,VarSprite}.java` — 2, 3 (adopted by 152.1)
- `src/io/brodgar/addon/LuaMaterials.java`, `src/io/brodgar/addon/LuaMaterialSlot.java` — 2, 3 (152.1's)
- `src/io/brodgar/addon/LuaCollection.java` (the `Source` contract) — 2, 3
- `docs/addons/api/materials.md`, `docs/client/gob-sprites.md` — 2, 3 (152.1's; the `IntMap.size()` gotcha)
- `tools/docverbs.py`, `tools/refusalverbs.py` (the `materialslot` receiver rows) — 2, 3
- `src/io/brodgar/addon/LuaGob.java` (`scale`/`visible`/`tint`, `info`) — 1, 2, 3
- `src/io/brodgar/addon/LuaOverlay.java` (`collection`, `Cache`) — 1
- `src/io/brodgar/addon/LuaResource.java` (`of`, `res`) — 1, 2
- `src/io/brodgar/addon/ResourceApi.java` (`name`) — 2
- `src/io/brodgar/addon/LuaSlot.java` (`:get(n)`'s refusal) — 1
- `src/io/brodgar/addon/GobTint.java` — 2, 3
- `src/io/brodgar/addon/GobIntent.java` — 2, 3
- `src/io/brodgar/addon/AddonManager.java` (`gobSnapshot`, `gobCopies`, `getgob`) — 1, 2
- `src/io/brodgar/addon/UiApi.java` (`teardownGobScales`) — 3
- `src/io/brodgar/addon/Addon.java` (the intern caches) — 1
- `src/io/brodgar/addon/Args.java` — 1, 2
