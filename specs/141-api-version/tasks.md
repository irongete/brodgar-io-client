# 141 — tasks

- [x] **141.1 — An addon out of date is not loaded.** `ApiVersion` (`CURRENT`, `parse`, `why`, `label`,
      `toString`); `Manifest.apiVersion` an `ApiVersion`, `null` when absent, `parse` in `load`, `CURRENT`
      in `internal()`/`test()`, `intv` deleted; `AddonRegistry`: the `outdated` map cleared with
      `loadErrors`, the skip in `load` logging why, `liveStatus` between `error` and `disabled`,
      `listAddons` printing `[outdated]`, `AddonInfo.outdated` in place of the dead `apiVersion`,
      `describeAddons` filling it; `AddonPanel.Row`'s tooltip opening with `Out of date: …`;
      `docverbs.api_version()`; the 29 sibling manifests, then `ant bin`. Docs: `manifest.md` born from
      `runtime.md`'s first two sections plus *The API version*; the `outdated (…)` row in `runtime.md`'s
      panel table; the three manifests and the "two required fields" sentence in `getting-started.md` and
      `http.md`; `debugging.md`'s status table; `README.md`, `conventions.md` and `getting-started.md`
      re-pointed at `manifest.md`.
      *Its suite* declares `"api_version": "1.0"`, and running at all is its one automatic line — every
      other state here is one the program cannot cause, so the maintainer edits the suite's own manifest
      between lines and `:reload`s after each.
      `[manual]`: `"api_version": "9.0"` — expect: the row reads `outdated (API 9.0, client 1.0)`, its
      checkbox still ticked.
      `[manual]`: hover that row — expect: the tip opens `Out of date: written for API 9.0, this client
      implements 1.0`.
      `[manual]`: `:addons` — expect: `141-api-version.1 [outdated]`.
      `[manual]`: `"1.3"` — expect: `outdated (API 1.3, client 1.0)`, the tip `too new: needs API 1.3 or
      newer, this client implements 1.0`.
      `[manual]`: the line removed — expect: `outdated (no api_version, client 1.0)`.
      `[manual]`: `1`, a number — expect: `manifest error (hover)`, the tip naming the `"X.Y"` form and
      `"1.0"`.
      `[manual]`: `"1.0"` back — expect: `loaded v0.1`, and `:addons` lists no `[outdated]` among the
      maintainer's addons.

- [ ] **141.2 — Load out of date AddOns.** `PREF_LOAD_OUTDATED = "addons/loadoutdated"`,
      `AddonRegistry.loadOutdated()` / `setLoadOutdated(v)` raising `reloadNeeded` on a change; the bypass
      in `load` — with the box on an out-of-date addon runs, the log naming it and why; `AddonPanel`: a
      `CheckBox("Load out of date AddOns")` between the hint and the buttons, `a` from the pref, `set`
      writing it, the javadoc naming it. Docs: the checkbox paragraph in `runtime.md`'s panel section, the
      box's sentence in `manifest.md`'s rule, `debugging.md`'s `outdated` row pointing at it.
      *Its suite* declares `"1.0"`, arms a thirty-second watch from `:t141` and asks the maintainer to open
      the AddOns panel. Once `window[title=AddOns]` exists it asserts a `@CheckBox[text=Load out of date
      AddOns]` inside it, printing its `:value()`, and that the row whose label starts with the suite's id
      — its `:parent()`'s `:children()` — holds a label reading `loaded v0.1`; a panel never opened fails
      naming it. Then, editing the manifest as 141.1 did:
      `[manual]`: `"9.0"`, tick *Load out of date AddOns*, **Reload UI** — expect: the row reads
      `loaded v0.1`, `:t141` answers, and the log has `loading out of date addon '141-api-version.2'`.
      `[manual]`: tick it off, **Reload UI** — expect: `outdated (API 9.0, client 1.0)`.
      `[manual]`: `"1.0"` back, **Reload UI** — expect: `loaded v0.1`.
