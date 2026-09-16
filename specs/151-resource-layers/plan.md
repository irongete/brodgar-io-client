# 151 — plan

## Approach

**One seam, one direction.** Every write is a record in a static registry keyed by resource name —
`(owner, sequence, kind ∈ {spec, remove, file}, address, payload bytes)` — and one function,
`ResourceWrites.apply(Resource, List<Layer>) → List<Layer>`, folds the records of a name over a freshly
parsed layer list in sequence order. It is called from **one `// addon:` line at the end of
`Resource.load(Message)`, between the parse loop and the `init()` loop** (the `onPicture` precedent), so
it covers both pools, every source and every future version, runs on the loader thread with no Lua, and
never throws (a failing record is skipped and `AddonManager.log`ged). A write on a resource already in
a pool's cache is applied by **re-parsing that same object from its own sources** — `// addon:`
`Pool.reload(Resource)`, the `handle` loop over `sources` again, ending in `res.load(msg, keep)` — so the
live path and the load path are one code path. `Pool.peek(name)` reads the cache without enqueuing.

**A written layer is a served layer.** A spec is encoded in the layer's own wire format (`image` v129,
`audio2` v3, `obst` v2, `neg`, `tooltip`/`pagina` UTF-8, `props` v1, `tex`) and constructed through the
`ltypes` factory — `// addon:` `Resource.newLayer(res, type, buf)` — so the object the client holds is
an upstream `Resource.Image`, bound to the right outer instance, with the served one's constructor side
effects (`UI.scale`, `onPicture`). A `.res` file is read by the same record grammar `load` uses
(`string type, int32 len, bytes`), version skipped, unknown types skipped, `code`/`codeentry` refused
before any constructor runs. **Validation happens at write time**, on a scratch `Resource.Virtual`
(public constructor, `add(Layer)`): construct, and for a file also `init()`, so a self-contained set
proves itself and a dependent one is refused naming the layer. What passed cannot fail at apply.

**Reads are the pool's cache.** `hafen.resource()` is a `LuaCollection` over `Resource.remote().cached()`
(which folds in `local()`), `named`, `addressable` with `Missing.MINT`: `get(name)` mints an interned
`LuaResource` holding `Resource.remote().load(name)` lazily — the `Indir` is taken on the first content
read, `Loading` caught, `LoadFailedException` kept as `:error()`. `LuaLayer` is bound to the layer
object (weak `Interned`); `:exists()` is identity-membership in the resource's current list.

**Release** removes the owner's records and re-parses every touched name still cached; teardown is one
`Step("resource layers", ResourceApi::teardown)` beside `locale`. Invalidations on every applied write:
`Addon.resTexCache.remove(name)` across `AddonManager.profOwners()`, and `// addon:` `Audio.forget(res)`
for `resclips`. Unprotected.

## Files

**Create** — `src/io/brodgar/addon/ResourceApi.java` (section, collection, teardown), `LuaResource.java`,
`LuaLayer.java`, `ResourceWrites.java` (registry, `apply`, release, invalidations), `LayerCodec.java`
(per-type snapshot ← layer, spec → wire, Lua ↔ tto values), `ResFile.java` (record reader, refusal);
`docs/addons/api/resource/README.md`, `layers.md`, `writes.md`; `docs/client/resource-loading.md`;
`addons/151-resource-layers.{1,2,3,4}/`.

**Modify** — `src/haven/Resource.java` (`load` hook line + `load(Message, Collection<Layer> keep)`,
`newLayer`, `Pool.peek`, `Pool.reload`), `src/haven/Audio.java` (`forget`), `AddonManager.java`
(`onResourceLayers` delegating to `ResourceWrites`), `AddonRegistry.java` (the step), `Addon.java`
(the interned handle maps), `tools/docverbs.py` (`RECEIVERS`: `resource`, `layer`);
`docs/addons/api/README.md`, `docs/addons/README.md`, `api/references.md`, `api/asset/handles.md`,
`api/sound.md`, `api/ui/controls/display.md`; `docs/client/README.md`, `resources.md` (see-also),
`published-code.md` (pool paragraph → pointer).

## Risks & gotchas

- **`res:` is the HTTP result in `tools/docverbs.py`** (`LuaHttpResult`'s `closedIndex("res")`). The
  pages spell `resource:`/`layer:`/`layers:`, the handles declare `closedIndex("resource")` and
  `("layer")`, and the map gains both rows — or a green run resolves nothing.
- **`Layer` is a non-static inner class** (`Layer.getres()` is `Resource.this`): only
  `LayerConstructor.cons(res, buf)` builds one for a given resource. Never `new Resource.Image` outside.
- **`Resource.load` keeps the object's version**: `this.ver` is the server's and the file's `uint16` is
  the one thing a file write ignores. The hook runs BEFORE `init()`; `Anim.init` binds frames by image id
  after it, which is why images are replaced before, not after.
- **Never `init()` a live list twice**: `FastMesh.MeshRes.init` nulls `tmp` and builds from it —
  re-parse instead. `load(msg, keep)` carries the existing `Code`/`CodeEntry` instances over the parsed
  ones, or `ResClassLoader.defineClass` defines every served class a second time under a new loader.
- **`layers` is swapped by reference** (`this.layers = layers`, upstream's own pattern) and read
  unsynchronised on the render thread: build the new list, assign once, never mutate in place.
- **`Pool.sources` and `load` are private**: `peek`/`reload` live inside `Pool`; `res.pool` is the pool
  that found it, `res.source` the source. A cache file bumped to a newer version since the object loaded
  makes `reload` throw "Wrong res version": the write raises to Lua and stays registered.
- **The hook runs on loader threads**: `ConcurrentHashMap` + copy-on-write record lists; a record's
  payload is bytes, never a `LuaValue` or an asset handle.
- **Several layers share one address**: clip variants (`audio2` at `""`), layered anim frames. A write or
  `remove` at an address touches all of them; `get(key)` is the first. `id` defaults to the first
  original's; with none, the spec must name it.
- **Merged fields need the original's bytes**: `Image.img` re-encodes to PNG (`ImageIO.write`),
  `Audio.coded` is kept as is; `TexR.Encoded.img` is private, so a `tex` spec requires `image`.
- **`obst` points are `float16` in tile units** (constructor multiplies by `MCache.tilesz`); the snapshot
  is world units, the encoder divides. `neg` has 4 unread bytes after `bc`: write zeros, keep `ep`.
- **`Audio.resclips` caches the combined clip per resource** for >1 clip; `Controls.sourceTex` and
  `StaticGSprite` read at build; `Button.clbtdown` and `UI.java`'s statics never re-read — the page's
  staleness rule, stated as fact.
- **The console suite handler holds the tree monitor** (151.3's window): `hafen.timer():after(0, …)`.
- **Multi-addon order** cannot be driven by one suite: jshell on `build/classes` with two owners checks
  it; the suite proves order within one owner.

## Discarded alternatives

- **A `ResSource` in front of the pool** (upstream's `HAFEN_RESDIR` shape): the file must carry the exact
  version the server names — lower is skipped silently, higher makes the server's own request throw
  `BadVersionException` — whole files only, no live path, no owner, and a source has already been seen
  to write empty cache files.
- **Mutating `layers` in place and re-running `init()`**: `MeshRes.init` is not idempotent, and the
  list is read without a lock on the render thread.
- **Synthetic `Layer` subclasses** (an `Image` over a `TexI`): a second constructor per type; the wire
  constructor is the one served layers take, so a written layer is indistinguishable from a served one.
- **A builder dispatched with `:apply()`**: a write is a declaration at an address, not a thing with a
  lifetime; the collection verbs are already the write vocabulary, and "patch" is a ground patch.
- **Layer handles interned by address**: several layers share an address, so a handle is bound to the
  object and `get` is the first.
- **Writes requiring a loaded resource**: an addon reskinning two hundred icons at `Load` would fetch
  two hundred resources it never draws; a declaration by name fetches nothing.
- **Allowing `code` from a file**: `defineClass` would run addon Java with the client's privileges,
  outside the LuaJ sandbox and every permission key.
- **A whole-file write as a by-address merge**: a model with three meshes and a file with one draws all
  four; a file replaces, a spec merges.
- **Silencing by removing `audio2`**: served sprites `flayer` the clip and die; `volume = 0` keeps it.
- **`resource:layer(type)` beside `layers():get(key)`**: two spellings of one read.
- **A `Changed` edge here**: its own `Subs` plumbing and suite; reads poll `:loaded()`.
- **A protected tier**: client-local and released with the addon, like `hafen.locale`.
- **Extending `docs/client/resources.md`**: 140 lines against a 150 ceiling.
