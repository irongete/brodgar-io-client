# 145 — addon-registry: install addons from the hub

## What and why

Today a player who wants an addon finds a zip somewhere, unpacks it beside the client and restarts.
The hub at `brodgar.io/addons` (its own repository, `brodgar-io-addons`, whose `README.md` is the
API contract) holds every published addon with a version, a sha256 and a package URL. This feature
makes the client a hub client: the **AddOns** manager grows a **Browse** tab that searches the hub
and installs from it, and its **Installed** tab learns which rows came from the hub and offers
**Update** and **Remove** on those. The manager is the whole of the surface: no console command and
no Lua verb reaches the hub.

The client stays anonymous — it reads, downloads, never signs in. Every package is checked before it
lands — the advertised sha256, the zip's paths, the manifest through the client's own parser — and
**nothing changes under a running addon**: an install, an update or a removal is *staged* and applied
at the next reload, exactly as an enable checkbox is. Installing is not enabling: a
permission-declaring addon still arrives disabled and still asks through the consent dialog.

## Acceptance criteria

1. **Search.** The Browse tab searches the hub whenever its field's text has been still for a
   third of a second or Enter is pressed — so a write through `w:value(s)` drives it — and shows one
   row per match: name, version, owner, `[protected: N]` and `[net]`, the summary, permissions and
   hosts as the tooltip, and a status: empty, `installed v<version>`, `in addons/ by hand`, or the
   out-of-date sentence `ApiVersion.why` gives. The tab's own line says `searching`, `no addon
   matches`, or why the hub did not answer.
2. **Install.** The row's **Install** downloads the package, refuses a body whose sha256 is not the
   advertised one, unpacks it under `addons/.staging/<id>/` refusing any entry outside `<id>/`, runs
   `Manifest.load` on the result, writes the install record `.registry.json` last and raises "changes
   pending"; the next reload — Reload UI, `:reload`, or the next start — moves it into
   `addons/<id>/`. The row's status and the log name the step: `downloading <n>%`,
   `staged <version> — Reload UI to apply`, `failed: <why>`.
3. **Refusals, each naming why:** a folder in `addons/` not installed from the hub is *by hand* —
   the client never replaces a player's own folder, so its Browse row carries no button and its
   Installed row neither Update nor Remove; a package that fails the sha256, the path or the manifest
   check never lands and the row reads `failed: <why>`.
4. **Update and remove.** On Installed, **Update** stages the hub's newer version by the install
   path — offered only when the hub's latest is greater than the record's version, semver, the hub's
   rule — and **Remove** marks the folder, which the reload deletes. Neither touches `savedata/`, the
   enabled set or the consent record, so a reinstall comes back as the player had it.
5. **The panel.** Two tabs: **Installed** (today's rows, boxes and buttons) and **Browse**. A
   hub-installed row on Installed carries **Remove**, and **Update** once a check — run when the tab
   opens, and by **Check for updates** — finds a greater version. A staged or marked row says so in
   its status until the reload, on both tabs.
6. **Another hub for development:** `-Dhaven.addon.registry=<base>` overrides the base URL,
   `ant -Dregistry=<base> run` passes it; a plain `http://` base is taken for a loopback host only.
7. **Docs.** `docs/addons/panel.md` is the manager's page (born from `runtime.md`'s section, every
   inbound link re-pointed) and the impact set below is discharged.

## Out of scope — the boundary

- **The hub itself** — pages, publishing, accounts — is `brodgar-io-addons`; the client documents what
  the client does and names the hub as where it reads from.
- **No Lua verb and no console command reaches the hub.** Installing is the player's act, at the
  manager.
- **No check at start-up**, no network before the player opens the tab or types the command; a
  check-on-launch setting is a later feature.
- **Icons and screenshots** stay on the hub; a row is text.
- **A per-addon reload** (the roadmap's *lifecycle conveniences*) would spare the whole-layer
  reload; the rule stands on `:reload`.

## Docs impact

Written: `docs/addons/panel.md` (new); `docs/addons/runtime.md` (the panel section moves out; *what
a reload keeps* gains the apply); `docs/client/boot-and-loop.md` (the jar's `/buildinfo` read into
`Utils.useragent`).

Derived — `grep -rn "the-addons-panel\|AddOns panel\|AddOns manager\|Open addons folder" docs/`:
`manifest.md:38,82,87` and `guides/debugging.md:127,134` (a cell, three anchor links → re-point);
`docs/README.md:19`, `docs/addons/README.md:33`, `getting-started.md:34,227` (index rows naming the
panel under the runtime → point at the new page); `guides/permissions.md:122,142,293`,
`api/http.md:54` (row markers, true as they are — discharged); `api/client/addon.md:6,72-73`,
`guides/hotkeys-and-commands.md:93,98`, `examples.md:111`, `api/ui/column.md:214` (the Options ▸
AddOns *tab* of an addon's own page, another surface — discharged).

## Context files

- `../brodgar-io-addons/README.md` — 1, 2 (the API)
- `src/io/brodgar/addon/registry/Registry.java`, `Entry.java`, `Semver.java` — 2, 3 (the hub client 145.1
  shipped: `Request`, `search`/`lookup`, `Entry.size`/`sha256`/`packageUrl`, `Semver.compare`)
- `src/io/brodgar/addon/AddonRegistry.java` — 1, 2, 3 (145.2 shipped: `install`/`downloading`/`failed`/`pending`,
  `hubVersion`, `applyStaged`, the `Pending` class, `AddonInfo.hub`/`staged`; `pollInstalls` runs from
  `AddonManager.layerStep`)
- `src/io/brodgar/addon/Staging.java`, `InstallRecord.java` — 3 (145.2 shipped: `stage`, `apply`, `pending`,
  `zipFor`, `deleteTree`; the record's `version`/`sha256`/`installedAt` and `read`/`write` — `markRemove` and
  the `.remove` mark's apply are 145.3's)
- `src/io/brodgar/addon/AddonManager.java` — 2 (`boot`, `log`)
- `src/io/brodgar/addon/ui/AddonPanel.java` — 1, 2, 3 (`BrowseRow`, `LIST_W`/`STATUS_X`/`BUTTON_X`, the shared
  `meta`/`tip`)
- `src/io/brodgar/addon/Manifest.java` — 2
- `src/io/brodgar/addon/ApiVersion.java` — 1
- `src/io/brodgar/addon/Json.java` — 1, 2
- `src/io/brodgar/addon/LuaHttp.java` — 1 (`HttpURLConnection` idioms to mirror)
- `src/haven/Utils.java` — 1
- `src/haven/OptWnd.java`, `src/haven/Tabs.java`, `src/haven/TextEntry.java` — 1
- `build.xml` — 1 (the `run` target)
- `docs/addons/runtime.md` — 1, 2, 3
- `docs/addons/panel.md` — 2, 3 (the manager's page; *how an install lands* and *what an install keeps* are
  145.2's; the Installed section's Update/Remove rows are 145.3's)
- `specs/145-addon-registry/addons/145-addon-registry.1/fixtures/` — 2, 3 (the fixture zips as published:
  `145-plain.zip` 1.0.0 is on the local hub already, and the folder is the source of a `1.1.0`)
- `docs/addons/manifest.md`, `docs/addons/guides/debugging.md`, `docs/addons/README.md`,
  `docs/README.md`, `docs/addons/getting-started.md` — 1
- `docs/addons/api/chat.md` — 2, 3
- `docs/addons/api/ui/edit.md`, `docs/addons/api/ui/widget.md` — 1, 2, 3
- `docs/client/ui-panels.md`, `docs/client/ui-lists.md` — 1
- `docs/client/boot-and-loop.md` — 1
- `DOCUMENTATION.md`
