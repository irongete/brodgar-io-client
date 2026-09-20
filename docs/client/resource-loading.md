# Resource loading: pools, sources, versions and the soft cache

How a name becomes a `Resource` object: which pool is asked, which sources it walks in what order, the
two version rules that decide whether a copy is taken, what holds a loaded one and for how long, and the
`Loading` protocol a read goes through while none of that has happened yet. What a `.res` *carries* once
it is loaded is [resources](resources.md); the Java one can carry is [published code](published-code.md).

## The two pools

| Pool | Built by | Sources, in the order `Pool.handle` walks them |
|---|---|---|
| `Resource.local()` | `Resource.local` (lazily, once) | `JarSource("res")` — the `res/` tree of `builtin-res.jar`; then a `FileSource(resdir)` when `HAFEN_RESDIR` / `haven.resdir` names a directory (a development aid — an error opening it is swallowed) |
| `Resource.remote()` | `Resource.remote` (lazily, once), **with `local()` as its parent** | `JarSource("res-preload")` — the `res-preload/` tree of `hafen-res.jar`; then `JarSource("brodgar-res")` — the `brodgar-res/` tree of `brodgar-res.jar`, the brodgar.io resource pack (every resource at the version the cache held when it was packed; `hafen.jar`'s `Class-Path` names the jar, the launcher puts it beside `hafen.jar`, and without it the source answers nothing); then `CacheSource(prscache)` — the `HashDirCache` under `Config.localdir()/data`, when `Resource.setcache` has run; then whatever `Resource.addurl` appended, an `HttpSource` for the server's resource URL wrapped in a `TeeSource` that writes each fetched stream into that same cache as `res/<name>` |

`Pool.load(name, ver, prio)` asks **the parent first**: a `Queued` in the child waits on the parent's
(`Queued.awaiting`, `rdep`), and only when the parent has answered with an error does the child enqueue
itself and walk its own sources (`Queued.prior`). So the order a name is looked for is *builtin jar →
resdir → preload jar → brodgar.io pack → disk cache → server*, and the first source to produce a stream that parses is the
one whose bytes are kept. `Resource.source` names it; every failure before it is chained on the
`LoadException` (`error.prev`, `addSuppressed`).

**Everything the game names goes through `remote()`.** `Session.pool()` is `Resource.remote()`; the
`local()` pool is asked directly only by the client's own start-up art (`Resource.loadrimg`,
`loadtex`, …). A pool has its own `Loader` threads (`nloaders = 2`, daemon `HackThread`s in
`Resource.loadergroup`, each exiting after ten idle seconds and started again by `ckld`), and a layer's
constructor runs on one of them — `UI.scale` inside `Image`, `ImageIO.read`, `Font.createFont`.

## Where a wire id becomes a name

The server refers to resources by a per-session integer. `Session.rescache` maps it to a `CachedRes`;
the name and version arrive in their own message (`RMessage.RMSG_RESID` → `CachedRes.set`, which also
starts the fetch at a low priority), and `CachedRes.Ref.get()` — the `Indir<Resource>` every `OCache`
delta and `uimsg` carries — throws `Session.LoadingIndir` until the name is known and then
`Resource.remote().load(resnm, resver, prio).get()`. `Session` implements `Resource.Resolver`, the
interface (`getres(id)`) a widget or a message decoder asks; `Resolver.ResourceMap` re-bases one over
a message's own id table.

## The two version rules

**Asking (`Pool.load`).** With `ver == -1` any version satisfies: a cached object is answered at once
(`cur.indir()`), a queued fetch is joined. With a version:

| The cache holds | Result |
|---|---|
| that version | the cached object |
| a newer one | `BadVersionException` ("Obsolete version … requested"), thrown **synchronously** into the caller |
| an older one | a new `Queued` for the newer version; on success it **replaces** the cache entry under that name |

A `Queued` that has already **failed** is retried only by an ask for a *newer* version than it failed
under, or by an unversioned ask when it failed under a version; an unversioned ask that failed answers
the same failed `Queued` for the pool's lifetime (`Pool.load`'s `XXX` branch). A name the server has no
resource for therefore costs one round of every source, once.

**Parsing (`Resource.load(Message)`).** The stream opens with `"Haven Resource 1"` and a `uint16`
version. When the object was created with `ver == -1` that number becomes `Resource.ver`; otherwise any
mismatch — higher or lower — is `LoadException("Wrong res version")` and `Pool.handle` asks the next
source. That is how a served version wins over the jar's: the jar's copy parses and is refused, the cache
or the server produces the one the session named. It is also why a copy dropped into `HAFEN_RESDIR` is
taken only when its version equals what the server asks for.

**The wanted version travels with the ask.** `Pool.handle` calls `ResSource.get(name, ver)`, a default
that falls back to `get(name)` for every source but `HttpSource`, which sends it as the
`X-Brodgar-io-Res-Version` request header when `ver >= 0` (`TeeSource` passes it through to the source
it wraps). A Brodgar resource proxy holding exactly that version answers from its cache without asking
the official server; any other server ignores the header, and the parse rule above still judges whatever
comes back.

**The brodgar.io resource cache (`-U https://res.brodgar.io/`).** `Resource.addurl` recognises that
host (`Resource.isbrodgarcache`) and appends two network sources instead of one: a
`BrodgarCacheSource`, which asks the cache for `<name>.res.v<ver>` when the ask names a version and for
`<name>.res` (the latest the cache knows) when it does not, and behind it a plain `HttpSource` at
`Resource.BRODGAR_CACHE_FALLBACK` (`http://brodgar.io/res/`), the proxy that fetches from the official
server and fills the cache. The cache has no logic: a `404` is "not yet", `RetryingInputStream` does not
retry a `FileNotFoundException`, and `Pool.handle` moves on to the proxy. Both sources are wrapped in the
same caching tee. The local cache keeps the proxy's identity for a cache `-U` (`HashDirCache.create`,
`BaseFileCache.create`), so moving a client from the proxy to the cache re-downloads nothing.

An empty stream is `FileNotFoundException("empty file")` on purpose: custom clients have been seen to leave
zero-length files in the disk cache under a resource's name, and the tee is what wrote them.

## The soft cache

`Pool.cache` is a `CacheMap` at its default `RefType.SOFT`: a loaded `Resource` stays only while
something holds it or the heap is not under pressure, and a collected one is fetched again on the next
ask — from the disk cache, not the server, when it came from there. `Pool.cached()` copies the live
entries of this pool and its parent into a fresh set; `Pool.used()` is the subset something has read a
layer of (`Resource.used`, set by every `layer`/`layers` call and cleared at the end of `load`).

`Resource.indir()` is the object's own `Indir`, minted once; `Pool.load` hands it back for a cache hit, and a `Queued` otherwise. `Resource.equals` is name **and** version.

## `Loading` and failure

`Queued.get()` throws `Resource.Loading` (a `haven.Loading`) until `done`; `Loading.waitfor` blocks on
the `Waitable.Queue` for code that may block (`Pool.loadwait`, the client's start-up), and everything
under the frame loop catches it and retries next tick ([boot and the frame loop](boot-and-loop.md)).
Once `done`, `get()` is the object or one of two exceptions, kept on the `Queued` for every later ask:

| Exception | When |
|---|---|
| `Resource.NoSuchResourceException` | every source threw `FileNotFoundException` (`Queued.found` false) |
| `Resource.LoadFailedException` | at least one source produced a stream and it failed to parse |

Both are `BadResourceException`s carrying the name and version; `getCause()` is the last source's
`LoadException`, whose `prev` chain walks back through the earlier ones.

## `load` and `init`: the order layers are built in

`Resource.load(Message)` reads records of `string type, int32 len, bytes`; a type with no
`LayerFactory` in `ltypes` is **skipped by length**, so an unknown layer never fails a resource. Each
known one is constructed through `LayerConstructor.cons(res, buf)` — `Layer` is a **non-static inner
class**, so a layer exists only bound to its resource (`Layer.getres()` is `Resource.this`) — and the
whole list is then assigned to `Resource.layers` **by reference**, replacing the old list rather than
mutating it. Only after every layer exists does `Layer.init()` run over the list, which is what lets one
layer find another: `Anim.init` binds each frame to the `Image`s sharing its id, `FastMesh.MeshRes.init`
resolves its vertex buffer and material, `CodeEntry.init` indexes the resource's `Code` layers.

`ltypes` is filled at class-init from every class annotated `@Resource.LayerName` (jglob's `Discoverable`)
across the whole tree — `TexR.Encoded`, `FastMesh.MeshRes`, `Tileset`, … — so the known types are the
loaded classes, not a list anywhere.

**Gotchas.** `Resource.layers` is read unsynchronised on the render thread, which is why it is swapped
and never mutated. `init()` is not idempotent on every type (`MeshRes.init` consumes its temporary
index buffer), so a second `init()` over a live list is a corrupted mesh, not a refresh. `Resource.used`
is set by any `layer()` read, including one made only to inspect.

## The wire layouts the constructors read

The record's bytes after `string type, int32 len`, for the layers carrying pictures, shapes and key/value blocks:

| Layer | Layout |
|---|---|
| `Resource.Image` | `uint8 ver`; `ver >= 128` is the keyed form: `int16 id`, then `string key, tto value` pairs to an empty key (`z`, `subz`, `nooff` as an int, `off` and `tsz` as `Coord`, `scale` as a float, anything else lands in `info`), then the PNG. `ver < 128` is the old fixed header. `tsz` left out becomes `sz.add(o)`, so a parsed layer cannot tell a served `tsz` from the default |
| `TexR.Encoded` | `int16 id`, `uint16 off.x, off.y`, `uint16 sz.x, sz.y`, then parts: `uint8 t` whose top two bits are the framing (`0` inline, `1` an `uint8`-length sub-message, `2` an `uint8` flags byte and an `int32` length) and whose low six are the part (`0` the PNG as `int32 len, bytes`, `1` mipmapper, `2`/`3` filters, `4` the alpha mask). `img` and `mask` are private |
| `Resource.Neg` | `cc`, `ac`, `bc` as three `int16` pairs, four unread bytes, `uint8 en`, then `en` rings of `uint8 epid, uint16 n, n × int16 pair` |
| `Resource.Obstacle` | `uint8 ver` (`1` or `2`), `string id` when `ver == 2`, `uint8 rings`, one `uint8` count per ring, then every point as `float16 x, float16 y` **in tile units** — the constructor multiplies by `MCache.tilesz` |
| `Resource.Props` | `uint8 1`, then a `Message.list`: `tto` values alternating key and value, to `T_END` or the end |

`Message.addtto` picks the narrowest integer tag for a whole number, `T_FLOAT64` for a `Double`, `T_COORD`
for a `Coord`, `T_TTOL` for an `Object[]`, and has no `Boolean` case — `nooff` travels as an int.

## Re-parsing a live object

`Pool.reload(res)` walks the pool's sources again for a resource the cache already holds and ends in
`load(msg, keep)`: the same parse on the same object, so `Resource.indir()` and every `Indir` the client
holds stay valid and only `layers` is swapped. Two traps it steps around:

- **`Code`/`CodeEntry` are carried over, never re-parsed.** `keep` is the object's current code layers;
  the stream's own `code`/`codeentry` records are skipped by length. A second parse would construct a
  second `CodeEntry`, whose lazily built `ResClassLoader` would `defineClass` every served class again
  under a new loader — two `Class` objects with one name, and `getcode` handing out the other one.
- **`Pool.handle` versus `reload` on the version rule.** `handle` creates the object with the version
  the ask named (or `-1`, which takes the stream's); `reload` keeps `Resource.ver`, so `load` refuses any
  source whose `uint16` differs. A disk-cache file replaced by a newer version since the object loaded
  fails every source `"Wrong res version"`: `reload` throws the last `LoadException`, old layers standing.

`Pool.peek(name)` reads this cache and its parents' without enqueuing; `Resource.newLayer(res, type,
buf)` is the `ltypes` factory as a static, so a caller outside `haven` builds a layer bound to `res`
(`Layer` is a non-static inner class) through the same constructor a served one takes. `Resource.Virtual`
(public constructor over a pool, name and version; `add(Layer)` appends) is a resource never loaded, the
one `DynresWindow` composes on — and a scratch on which foreign layer bytes can be constructed and
`init()`ed against each other while nothing else holds them.

## See also

- [resources](resources.md) — what a loaded `.res` carries: reading a layer by class, predicate or id
- [published code](published-code.md) — the `code` layer, `@FromResource` adoption, and the ABI served code links against
- [boot and the frame loop](boot-and-loop.md) — the `Loading` protocol under the frame loop
