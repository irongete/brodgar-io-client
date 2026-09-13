# 145 — addon-registry: tasks

The suites run against the local hub: the client is started with
`ant -Dregistry=http://localhost:3730/addons/api run`, and each suite's fixtures are published first
**the way a developer publishes** — each is a ready zip under `bin/addons/<suite>/fixtures/`, uploaded
at `http://localhost:3730/addons/me` (sign in with Haven & Hearth, pick the zip, **Publish**); the
suite's first output line names them. A suite waits up to thirty seconds for `window[title=AddOns]`
(the maintainer opens the manager) and scores what that run's state allows; a press is the
maintainer's and is watched for sixty seconds.

- [x] **145.1 — Browse: the hub client and the tab that searches it.** `io.brodgar.addon.registry`:
      `Registry` (the base URL rule, the worker, `search`/`lookup` as polled `Request`s), `Entry`,
      `Semver`. `AddonPanel` takes a `Tabs`: **Installed** is today's widgets inside a tab, **Browse**
      a `TextEntry` polled from `tick` (a third of a second still, or Enter) over a `Scrollport` of
      `BrowseRow`s and a status line; the list widens to 560 with the status at 290 and a button
      column at 480, empty for now. A row: name, version, owner, the markers and tooltip `Row`
      composes, and a status — `in addons/ by hand` where the folder exists, `ApiVersion.why`'s
      sentence where the `api_version` is not this client's, else empty. `build.xml`'s `run` passes
      `-Dregistry`. Docs: `docs/addons/panel.md` born from `runtime.md`'s section (the tabs, Browse,
      the hub named, `-Dhaven.addon.registry`), the three anchors in `manifest.md` and
      `guides/debugging.md` re-pointed, the index rows in `docs/addons/README.md`, `docs/README.md`
      and `getting-started.md`, and the `/buildinfo` row in `docs/client/boot-and-loop.md`.
      *Its suite* declares `widget.value`; fixtures `145-plain` (1.0.0, no permissions) and
      `145-old` (`api_version "9.0"`). It asserts `@Button[text=Installed]`, `@Button[text=Browse]`
      and a `@TextEntry` in the window; `w:value("simple")` and, within fifteen seconds, a row label
      starting `Simple Chat` with `1.0.0` whose status reads `in addons/ by hand` (the dev client's
      copies); `w:value("145-old")` → a status `outdated (API 9.0, client 1.0)`; `w:value("145-plain")`
      → an empty status; `w:value("zzzz-nothing")` → the tab's line `no addon matches`;
      `w:value("")` → no rows. `[manual]`: press **Browse** — expect: two tabs, the field, the rows
      the log names.

- [ ] **145.2 — Install: the package, the staging and the reload that applies it.**
      `Registry.download` (streamed sha256, progress against `Entry.size`, 16 MB cap);
      `InstallRecord` (`.registry.json`) and `Staging` (`stage`: sha256, paths, count and size,
      `Manifest.load`, the record last; `apply` in `AddonRegistry.reload()` after `StoreApi.detach()`
      and in `AddonManager.boot()` before `loadAll()`; `.trash/`; a refused move waits for the
      next apply); `AddonRegistry.stage`/`pending` raising `reloadNeeded`; `AddonInfo.hub` and
      `staged`. `BrowseRow` gains **Install** where the folder is absent, and the statuses
      `downloading <n>%`, `staged <v> — Reload UI to apply`, `restart to apply`, `failed: <why>`,
      `installed v<v>` (a record present); the Installed row names a staged state. Every step is a
      `log` line. Docs: `panel.md`'s *how an install lands* and *what it keeps*; `runtime.md`'s
      *what a reload keeps* gains the apply. Headless first, in `jshell` against `Staging.stage`:
      a zip whose sha256 is not the entry's is refused naming both digests, an entry `../x` naming
      the entry, a manifest `{` naming the parse.
      *Its suite* declares `widget.value`; fixture `145-plain` 1.0.0. With the folder absent it
      asserts `@Button[text=Install]` in the `145-plain` Browse row and none in `essentials`'s;
      `[manual]`: press **Install** on `145-plain` — the suite watches the row's status for
      `staged 1.0.0 — Reload UI to apply` and the System channel for the log line, and the hint for
      `Changes pending`. `[manual]`: **Reload UI**, `:t145` — expect: the Browse status
      `installed v1.0.0` without a button, and the Installed row `loaded v1.0.0`; the second run
      asserts both.

- [ ] **145.3 — Installed: Update, Remove and Check for updates.** `Registry.lookup` of the
      hub-installed ids when the Installed tab shows and on a **Check for updates** button;
      `Semver.compare` against the record; **Update** (the install path, status `update <v>` until
      pressed) and **Remove** (`AddonRegistry.markRemove` → `.staging/<id>.remove`, status
      `removed on Reload UI`, `apply` deletes; `savedata/`, the enabled set and the consent record
      untouched) on a row with a record, neither on a by-hand row; **Enable all** unchanged. Docs:
      `panel.md`'s Installed section.
      *Its suite* declares `widget.value`; fixtures `1.0.0/145-plain` and `1.1.0/145-plain`, the
      second published only when the suite says. Run 1 (folder absent): as 145.2, install 1.0.0
      and reload. Run 2: `[manual]`: publish `1.1.0`, close and reopen the manager — the suite
      watches the `145-plain` Installed row for `@Button[text=Update]`, status `update 1.1.0`, and
      `@Button[text=Remove]`; the `essentials` row carries neither; `[manual]`: press **Update** —
      watched for `staged 1.1.0`. Run 3, after **Reload UI**: the row reads `loaded v1.1.0`;
      `[manual]`: press **Remove** — watched for `removed on Reload UI`. Run 4, after **Reload
      UI**: no `145-plain` row on either tab, and `w:value("145-plain")` shows an empty status with
      **Install** back.
