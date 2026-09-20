# The on-disk store: `ResCache`

> Everything the client keeps on disk outside `savedata/` goes through one two-method interface and
> one of two implementations: a hash table of files under `%APPDATA%`, or — with `-Dhaven.store=sqlite`
> — two SQLite files beside the jar. What the store holds for the map is [mapfile.md](mapfile.md); the
> pipeline that reads and fills the resource half is [resource-loading.md](resource-loading.md).

## The interface and its implementations

| Class | What |
|---|---|
| `ResCache` | `store(name)` → `OutputStream`, `fetch(name)` → `InputStream`. A miss is `FileNotFoundException`, and every caller keys on exactly that (`Pool.handle`, `GobIcon.Settings.load`, `MapFile`'s `sfetch` sites); any other `IOException` is an error, not an absence. `ResCache.global` is a static of the interface, built at its initialisation by `ResCache.StupidJavaCodeContainer.makeglobal` — the store is chosen the first time any class touches `ResCache.global`, and a `Warning` issued then reaches stderr only |
| `HashDirCache` | Upstream's one live implementation: one file per entry under `Config.localdir()/data`, mechanics below. `HashDirCache.create()` picks the identity — `haven.cachebase`; else `-U`, with the brodgar.io cache host mapped to the proxy's URI (`Resource.BRODGAR_CACHE_FALLBACK`); else `urn:haven-cache:default` — and answers `null` on any failure, which every caller tolerates |
| The SQLite store | The fork's second implementation, `src/io/brodgar/addon/SqliteCache.java`: one blob per entry in one SQLite file, two files when selected. Its mechanics are that class's; how it is wired into `haven` is [the seam](#the-havenstore-seam) below |
| `ResCache.Fallback`, `ResCache.TestCache` | A primary with read-only secondaries (`store` goes to the primary); a store that prints what it would have written and misses every fetch — a sink, not the in-memory store a headless `MapFile` wants |
| `BaseFileCache`, `FileCache`, `SteamCache` | Dormant: nothing in the client constructs them. A per-identity folder tree under `Config.localdir()/cache` with an index file; a tree under `~/.haven/hafen/cache`; the Steam Cloud files ([steam.md](steam.md)) |

## Who writes which key

| Key family | Writer | Reader |
|---|---|---|
| `res/<name>` | `Resource.addsrc`'s `Caching`, a `TeeSource` every network source is wrapped in: its fork is `cache.store("res/" + name)` over `Resource.prscache`, the store `Client.setupres` handed to `Resource.setcache`. The tee's `setncwe` — no close without EOF — closes the fork only when the download was read to its end | `Resource.CacheSource.get`, `cache.fetch("res/" + name)`, walked after the jars and before the network |
| `map/[<file>/]index`, `…gi-<id>`, `…grid-<id>`, `…seg-<id>`, `…zgrid-<seg>-<lvl>-<x>-<y>` | `MapFile.sstore` through `mangle`: `map/`, then `<filename>/` when `GameUI.mapfilename()` is not `""` — by default it is, so the keys are `map/index`, `map/gi-…`. Ids print as `%x`. The index is written whole on every `save`; a grid on `Grid.save`; a `gi-` and a `seg-` on `BackCache.put` (`gridinfo`, `segments` — the processor thread's saves, never an eviction); a zoom grid on `ZoomGrid.save` | `MapFile.sfetch` from `read` (the index, once), `gridinfo`, `segments`, `Grid.load`, `ZoomGrid.load` |
| `data/mm-icons-2[/<genus>][/<account>]` | `GobIcon.Settings.save` over `ResCache.global`, under the name `GameUI.iconconfname` builds | `GobIcon.Settings.load`; a miss is a fresh `Settings` |
| `tmp/allused` | Nobody: the `Resource.loadlist` of it in `Client.setupres` is commented out | — |

The map's store is `GameUI.addchild`'s `mapstore` (the `"mapview"` placement): `ResCache.global`, or
`HashDirCache.get(MapFile.mapbase)` when `haven.mapbase` is set and the store is the files one — and
every shipped `haven-config.properties` sets it, to `http://game.havenandhearth.com/java/`. So under
`%APPDATA%` the map and the resource cache are two identities in one folder, and `MapFile.load` keys its
one instance per database on the `(store, filename)` pair ([mapfile.md](mapfile.md)).

## `HashDirCache`: the mechanics

| Piece | How |
|---|---|
| The folder | `Config.localdir()/data`, created by the constructor. `Config.localdir` is `%APPDATA%\Haven and Hearth` on Windows and `~/.haven` elsewhere, and `null` when neither exists writable ([services.md](services.md)) |
| The identity | `HashDirCache.id`, a URI: one instance per URI (`get`, memoised in `current`), and `idhash = namehash(0, id.toString())` — `h = h * 31 + char` over the string, in a `long` |
| A slot | `%016x.%d`: `namehash(idhash, name)` in hex, then an index. `.0` is where a name lives unless another `(id, name)` pair hashes to the same 64 bits, in which case `lookup` walks `.1`, `.2`, … reading headers until it finds the name or an empty slot. The chain has never been exercised: `CacheFile` takes the `.0` channel whatever slot matched, so a chained entry would read back as the first one's bytes |
| The header | `readhead`/`writehead`: a version byte (`1`), then `writeUTF(id)` and `writeUTF(name)`. A slot whose header does not parse counts as empty and is reused; the entry's bytes follow the header |
| `lookup(name, creat)` | Takes an exclusive `LockedFile.lock` on the `.0` slot — which opens it `READ, WRITE, CREATE` and locks its first byte — and walks the chain under it. Two threads of one JVM cannot lock one file twice (`OverlappingFileLockException`), so a per-path monitor in `monitors` serialises them first; more than 100 live monitors is a `Warning`. The `CacheFile` it returns has released the lock and keeps only the channel, positioned after the header |
| `fetch` | `lookup(name, false)`: a missing `.0` is a miss before any lock; otherwise the channel after the header is the entry's stream |
| `store` | `lookup(name, true)` first, so a new name gets its slot **now**, header only. Then a temp file (`createTempFile(dir, "cache", ".new")`) takes the header and the bytes; `close()` moves it over the slot with `ATOMIC_MOVE`, on Windows deleting the slot first (an open file cannot be replaced there, so a reader between the two sees a miss). A stream never closed is cleaned by a `Finalizer.Cleaner` that closes the channel and deletes the temp |
| `remove(name)` | Public; deletes the slot. Only the CLI calls it |
| `list` | Private: opens every `%016x.%d` in the folder and reads its header. Only `main` reaches it — `java -cp bin/hafen.jar haven.HashDirCache <id or URI> ls`, `cat <name>`, `purge`, `rm <name>…` |
| Eviction | None. Nothing counts, ages or caps the folder; `purge` is the one way to shrink it, and it is per identity |

## Gotchas

- **A read opens for write and takes an exclusive lock.** A `fetch` whose `.0` slot exists goes through
  `LockedFile.lock`: the slot is opened `READ, WRITE` and locked, so a read-only `data/` folder fails a
  read with an `IOException` that is not a miss: `Pool.handle` still asks the next source, but marks the
  name `found`, so one every source then fails ends as `LoadFailedException`, not `NoSuchResourceException`.
  Every read is a file open, a lock and a header parse before the first byte: hundreds of microseconds.
- **An unclosed store stream leaves a header-only slot.** `store` creates the slot before the bytes
  exist; a download the tee never finished (`setncwe` skips the close), or a crash, leaves the slot with a
  header and nothing after it. `fetch` then answers an empty stream: `Pool.handle` turns it into
  `FileNotFoundException("empty file")` and asks the next source
  ([resource-loading.md](resource-loading.md)); a `MapFile` reader sees `data.eom()` and answers `null`. A
  re-store of an existing name that aborts leaves the old bytes: only the move replaces a slot.
- **A `-U` change orphans a whole identity.** The identity is in every slot's hash and every header, so a
  client started with another `-U` (or `haven.cachebase`) sees an empty cache and downloads everything
  again, while the old identity's files stay forever: nothing enumerates the folder but `purge`, and that
  needs the old URI. Three dead identities have been measured holding half of a 1.8 GB folder.
- **The segment tombstone is a zero-length entry, and so is a zoom-grid invalidation.** `MapFile.segments`'
  store with `seg == null` and `ZoomGrid.inval` both do `sstore(…).close()` with no bytes written, and the
  readers test `data.eom()`. An implementation must store and return zero bytes — never a miss — and must
  not judge emptiness itself: `Pool.handle` does that for a resource, and a map reader wants the empty
  stream.
- **Two processes on one folder are safe at the entry and not above it.** The `FileLock` is cross-process,
  so two clients never interleave one slot; `MapFile` reads its index once and writes it whole
  (`knownsegs`, every marker), so of two clients on one map the last index save wins.
- **`Config.localdir()` can be `null`.** `HashDirCache`'s constructor throws `UnsupportedOperationException`
  and `create()` swallows it to `null` — but `GameUI`'s `HashDirCache.get(mapbase)` does not, and throws
  into `addchild`.

## The `haven.store` seam

| | |
|---|---|
| The switch | `-Dhaven.store`: `files` — the default, and the value when the property is absent — or `sqlite`. Any other value warns, naming it and the two accepted, and means `files`. `ant -Dstore=sqlite run` passes it (`build.xml`'s `run` target). Resolved once, in the store class |
| The three `// addon:` lines | `ResCache.StupidJavaCodeContainer.makeglobal` — `ResCache.global` is `savedata/map.sqlite`, else `HashDirCache.create()`. `Client.setupres` — `Resource.setcache` gets `savedata/rescache.sqlite`, else `ResCache.global`. `GameUI.addchild`'s `mapstore` — `haven.mapbase` is consulted in files mode only, so in sqlite mode the map is `ResCache.global`'s file whatever the properties file says |
| The two files | Under `savedata/` beside the jar (`haven.savedatadir` moves it): `map.sqlite` for what `ResCache.global` holds — the `map/` keys and `data/mm-icons-2/…` — and `rescache.sqlite` for `res/`. Two lifecycles, two files: a cache wipe never runs inside the player's map. Each store keeps its own data — nothing is imported either way, and switching back shows the files store as it was left |
| What the store promises `haven` | The same contract as the files: a miss is `FileNotFoundException` and only a miss; a `store` stream writes on `close()` and nothing otherwise, so an aborted download leaves no row where `HashDirCache` leaves a header-only slot; a zero-length blob round-trips as zero bytes. A file that cannot be opened — no driver or `java.sql`, an unwritable folder, a file a newer client wrote — is one `Warning` naming it and the reason, and `null`: the degradation a `null` from `create()` already has. Two clients on one folder share a file through the write-ahead log and a busy timeout |
| `:store` | A static console command, registered by the store class's initialiser in both modes and printing through `cons.out` — the System channel in-world ([console.md](console.md)). `store: files` with the `data/` folder and the `HashDirCache` identities in use, or `store: sqlite` with a `map:` and a `res:` line — path, entry count and size, or `not open: <why>` — and a `sweep:` line. A client command, so `hafen.console():on("store")` is refused |
| The sweep | The pack (`brodgar-res.jar`) answers before the cache, so a `res/` row it holds at the same or a newer version is dead weight; the version is the `uint16` after the 16-byte `"Haven Resource 1"` signature, `substr(data, 17, 2)` of the blob. The sweep belongs to the store class and to sqlite mode — enumerating the files folder is minutes — and `:store`'s `sweep:` line is where its counts print; it reads `sweep: none` while none has run |

## See also

- [the map database](mapfile.md) — what the `map/` keys hold, the one lock over them, and the index that is written whole
- [resource loading](resource-loading.md) — the source order the cache sits in, the tee that fills it, and the `"empty file"` rule
- [services](services.md) — `Config.localdir` and the folder beside the jar
- [the console](console.md) — where a static command's `cons.out` lands
