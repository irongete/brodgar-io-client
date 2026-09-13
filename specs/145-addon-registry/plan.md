# 145 — addon-registry: plan

## Approach

**The hub protocol** lives in a new package `io.brodgar.addon.registry`: `Registry` (the base URL —
`haven.addon.registry`, default `https://brodgar.io/addons/api`, `http://` for a loopback host only,
else logged and the default kept; one daemon worker thread; `search(q)`, `lookup(ids)` and
`download(entry, file)` each hand back a `Request` the caller polls — `done()`, `result()`,
`error()`, `progress()` — so no widget is ever touched off the UI thread), `Entry` (one item of
the hub's list: id, name, owner, official, summary, version, `api_version`, permissions, hosts,
size, sha256, `package_url`) and `Semver` (parse, compare — the hub's rule, `MAJOR.MINOR.PATCH`
with an optional pre-release). HTTP is `HttpURLConnection` as `LuaHttp` does it: connect and read
timeouts, `Accept: application/json`, `User-Agent: brodgar-client/<jar.version>` from
`Utils.useragent`, an 8 MB cap on a JSON body and 16 MB on a package, the JDK's own trust store.
`Json.parse` reads the answers. A download streams into `addons/.staging/<id>.zip` through a
`MessageDigest`, reporting bytes against `Entry.size`.

**The disk half** sits beside `AddonRegistry` in `io.brodgar.addon`, where `addonDir()` is:
`InstallRecord` (`.registry.json` — `version`, `sha256`, `installed_at` — written with
`Json.write` on a `LuaTable`, read with `Json.parse`) and `Staging`. `Staging.stage(dir, entry,
zip)` refuses a sha256 that is not `entry.sha256`, unpacks with `ZipInputStream` into
`.staging/<id>/` refusing an entry that is not under `<id>/`, climbs (`..`), is absolute, or takes
the count past 2000 or the total past 64 MB, runs `Manifest.load` on the folder, and writes the
record last — a folder without one is incomplete and is swept. `markRemove(id)` writes
`.staging/<id>.remove`. `apply(dir)` moves the old folder to `.trash/<id>-<millis>` and the staged
one into place with `Files.move(ATOMIC_MOVE)`, deletes marked folders the same way, then empties
`.trash` best-effort; a move a held file refuses stays for the next apply. It runs in
`AddonRegistry.reload()` between `StoreApi.detach()` and `loadAll()`, and in `AddonManager.boot()`
before `loadAll()`, so a restart finishes what a reload could not. `AddonRegistry` grows the public
statics the panel calls — `stage`, `markRemove`, `pending(id)` — each raising `reloadNeeded`, and
`AddonInfo` gains `hub` (the record's version, or null for a by-hand folder), `staged` and
`removing`.

**The panel.** `AddonPanel` takes a `Tabs` exactly as `OptWnd.SettingsPanel` does: two
`TabButton`s under the heading, `tabs.c` set before the bodies are moved, `tabs.pack()` then
`pack()`, Back below both. **Installed** is today's widgets inside a tab, its rows gaining Remove
and — after a `lookup` of the hub-installed ids, run when the tab shows and by **Check for
updates** — Update, and a status that names a staged or marked state. **Browse** is a `TextEntry`
whose `activate` searches at once and whose `text()` `tick` polls, searching once it has been still
a third of a second (so a `w:value(s)` write from Lua, which is `rsettext`, searches too); a
`Scrollport` of `BrowseRow`s; a status line. A row: name, version, owner, the markers and tooltip
`AddonPanel.Row` already composes, a status label, and Install where the folder is absent. The list
widens from 480 to 560 so a button column stands at 480 beside the status at 290. Every hub answer
is read from `tick` and stamped with a sequence, so a late answer to an earlier search is dropped.

**Docs.** `runtime.md`'s panel section becomes `docs/addons/panel.md` with the tabs, the statuses,
how an install lands (`.staging/`, `.trash/`, the record, the reload), what a removal keeps, and the
hub it reads from — `brodgar.io/addons`, named and not linked, with `-Dhaven.addon.registry`
beside it. Inbound anchors move with it; the index rows follow.

**Verification shape.** A suite declares `widget.value`, waits for `window[title=AddOns]` and drives
the search field; it reads rows through `w:text()` on their labels and the registry's log lines in
the System channel. A press is the maintainer's, scored over a bounded window; the refusals no
suite can cause — a tampered sha256, an entry that climbs out, a manifest that does not parse —
are proven in `jshell` on `build/classes` against `Staging.stage`.

## Files to create or modify

- new: `src/io/brodgar/addon/registry/{Registry,Entry,Semver}.java`,
  `src/io/brodgar/addon/{Staging,InstallRecord}.java`, `docs/addons/panel.md`
- `src/io/brodgar/addon/AddonRegistry.java` — `reload`, `describeAddons`/`AddonInfo`, the statics
- `src/io/brodgar/addon/AddonManager.java` — `boot`
- `src/io/brodgar/addon/ui/AddonPanel.java` — the tabs, `Row`, `BrowseRow`
- `build.xml` — `run`: `<sysproperty if:set="registry" key="haven.addon.registry" …>`
- `docs/addons/runtime.md`, `manifest.md`, `guides/debugging.md`, `README.md`,
  `getting-started.md`, `docs/README.md` — the move and its links
- `docs/client/boot-and-loop.md` — `/buildinfo` → `Utils.useragent`

## Risks and gotchas

- `Tabs.add()` places a body at `Tabs.c` **as read then**; `SettingsPanel` sets `tabs.c` and moves the
  tabs afterwards. `Tabs.pack()` gives both tabs the union box, so Browse is laid out to Installed's
  height or the shorter tab shows blank space.
- Rows go through `Scrollport.cont.add`, never `Scrollport.add` (`Widget.add` skips `addchild`).
- `TextEntry`'s height is `bgheight()`, Enter reaches `activate(String)` through `done`/`gkeytype`,
  and `w:value(s)` from Lua is `rsettext` — silent, so `changed` is not the hook; `tick` polling is.
- The AddOns `PButton` is built once (`fresh` false): the panel lives as long as the window, so a
  `Request` in flight outlives a hide and its answer must be dropped when stale.
- `describeAddons` lists folders with a `manifest.json` directly inside, so `.staging/` and `.trash/`
  are invisible to it and to `loadAll`; `addonDir()` is package-private, which is why `Staging` is.
- The ROADMAP's font row: a `.ttf` an addon loaded is held for the client's life, so an update of that
  addon cannot move its folder until a restart — `apply` at `boot` is the retry, and the status says
  `restart to apply`.
- `Json.parse` numbers are `Double`; `ApiVersion.parse(Object)` throws on a malformed string — a
  row shows the message.
- `AddonManager.log` reaches the System channel only in the world; the suites run there.
- The list width is a scale-1.5 geometry (the suites' client): assert on labels, never on pixel sums.
- `jar.version` is `dev` off `ant`, so the `User-Agent` reads `brodgar-client/dev`.

## Discarded alternatives

- `:addons search|install|update|remove` beside the panel — the maintainer's call: the manager is
  the one door; a second vocabulary repeats every refusal, and a command line is not where a player
  installs from.
- Install over a by-hand folder behind a confirmation — a folder without a record is the player's
  working copy; a dialog invites overwriting it where a refusal names it.
- A Lua verb on the registry — an addon that installs addons is the supply-chain door the sandbox
  exists to keep shut.
- Swapping folders the moment the download lands — Windows holds a loaded addon's files, and the
  panel's rule is already "applied on reload": one rule, one moment.
- Staging under the system temp folder — a move across volumes is a copy; a sibling of `addons/<id>`
  renames in place.
- The install record in `java.util.prefs` — the record belongs to the folder: it travels with it under
  a launcher update and dies with it when the player deletes the folder.
- `java.net.http.HttpClient` — `LuaHttp` set the layer's idiom on `HttpURLConnection`.
- A check on launch — network before the player asked; a setting for a later feature.
- Semver in `Manifest` for every addon — a by-hand folder may carry any string; only the hub's order
  needs the rule, so the compare lives with the hub client.
- `docs/addons/publishing.md` — publishing is the hub's surface, and `docs/` links nowhere outside
  itself; the panel page names the hub.
- Searching on the entry's `changed` — a driven write fires none; polling serves typing, paste and a
  suite alike.
