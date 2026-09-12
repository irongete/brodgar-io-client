# 141 — API version

## What & why

`api_version` is parsed and forgotten: a number, `1` when absent, copied into a field nothing reads, and
the client has no number of its own to hold it against. The docs are about to be versioned — `1.0` first —
and an addon written against them says which, so the client can tell an addon **out of date** from a
current one the way WoW's `## Interface` does, and the player can load one anyway the way WoW's *Load out
of date AddOns* does.

- **A version is `"X.Y"`.** X is the generation and moves on a hard cut; Y is the edition and moves when a
  release adds a verb, a section or a key. There is no Z: a change that changes no documented contract
  changes no number. The client carries one, `1.0` today.
- **Same X, and Y at most the client's → current. Anything else → out of date.** An out-of-date addon is
  not loaded and is not an error: a state of its own on its row, in `:addons`, in the tooltip. Absent is
  out of date too, like a `.toc` without `## Interface`.
- **Load out of date AddOns**, a persistent checkbox on the AddOns panel, loads them regardless — applied
  on reload like everything on that panel.

The whole of it is the engine's: an addon declares what it wrote against and the loader decides. Nothing
crosses into Lua.

## Acceptance criteria

1. **The field is a string `"X.Y"`.** X a positive integer, Y a non-negative one, neither with a leading
   zero: `"1.0"`, `"1.5"`, `"12.3"`. Anything else — a number, `"1"`, `"0.1"`, `"1.0.0"`, `"v1.0"` — is a
   manifest error naming the form and the client's version. Absent is not an error. The client's version is
   one constant, `1.0`, and `tools/docverbs.py` fails when the version the docs state is not it.
2. **Out of date is a state.** At load, a manifest whose X differs, whose Y exceeds the client's, or which
   declares no `api_version` is not run: the row reads `outdated (API 9.0, client 1.0)` —
   `outdated (no api_version, client 1.0)` for the absent one — and `:addons` lists it `[outdated]`. Its
   tooltip carries the sentence, on every row it applies to, disabled ones included: `written for API 9.0,
   this client implements 1.0` · `too new: needs API 1.3 or newer, this client implements 1.0` · `declares no
   api_version, this client implements 1.0`. The enable checkbox keeps its persisted value, and a disabled
   addon is never read, so `disabled` still wins. A current declaration loads exactly as today.
3. **Load out of date AddOns.** A checkbox on the AddOns panel, persisted with the enabled set, off by
   default. Flipping it marks changes pending; on the next reload every out-of-date addon loads as a
   current one does — the log names it and why — and reads `loaded v…`; off again, the next reload leaves
   them out. It is not a permission: an out-of-date write addon still passes its consent dialog.
4. **The maintainer's addons declare `"1.0"`** — the 21 that say `1` and the 8 that say nothing, one line
   each in the sibling repository — so none of them reads `[outdated]` after `ant bin`.

## Out of scope

- **A version read from Lua.** An addon declares its minimum and the loader decides: a current addon has
  everything it declared against, and one loaded under the box cannot branch on a generation it never saw.
  An optional newer section is what the feature probe, `if hafen.x then`, is for.
- **Enforcing the bump.** Whether a release that lost a name raised X is the maintainer's editorial call;
  the checker that would diff a release's vocabulary against the last one's is the next word, reported at
  the close.
- **Dependencies between addons.** `dependencies` stays recorded and unread (the ROADMAP line 051 filed);
  a version range on another addon belongs to that feature.
- **The client's own release version** (`0.1.0-beta.1`) is not the API's and is not read here.
- **A console twin of the checkbox.** The panel is the one place the box is; `:addons` reports the state.
- **The archived suites** under `specs/*/addons/` keep their `1`: frozen, never run.

## Docs impact

Written: `docs/addons/manifest.md` — new, *Where an addon lives* and *The manifest* moved out of
`runtime.md`, which is over its ceiling at 322 lines, plus the version rule; `runtime.md` (the panel table
and its checkbox paragraph, the console table); `getting-started.md:20-29,32,170-179` (both manifests, and
the "two required fields" sentence); `api/http.md:26-33` (the manifest); `guides/debugging.md:130-136`
(the status table); `README.md:22,32` and `api/conventions.md:357` (`runtime.md#the-manifest` re-pointed).

Derived impact set — `grep -rniE 'api_version|out of date|outdated|required field|two required|Enable all|Reload UI' docs/addons/`:
`runtime.md:40` (the row), `:210-211` (the checkbox's paragraph), `getting-started.md:32`,
`guides/permissions.md:146` (*Enable all*, unchanged: the box beside it is not a bulk enable),
`api/virtual/widgets.md:242` (prose, unrelated). Manifest examples — `grep -rn '"files"' docs/addons/`:
`getting-started.md:28,177`, `api/http.md:28`.

## Context files

- `docs/addons/manifest.md` (*The API version*, the rule the box's sentence joins), `docs/addons/runtime.md`,
  `docs/addons/api/conventions.md`, `DOCUMENTATION.md` — every task
- `docs/addons/README.md`, `getting-started.md`, `api/http.md` — 1; `guides/debugging.md` (the status table) —
  every task
- `docs/addons/api/ui/selectors.md`, `api/ui/edit.md` (reading a borrowed checkbox), `api/ui/widget.md`
  (`:parent()`, `:children()`) — 2
- `docs/client/prefs-and-options.md` (`Utils.getprefb`/`setprefb`), `docs/client/ui-controls.md`
  (`ACheckBox.a`, `set`) — 2
- `src/io/brodgar/addon/Manifest.java`, `Json.java` — 1; `ApiVersion.java` (`why`, what the bypass logs) — 2
- `tools/docverbs.py` (`main`) — 1
- `../brodgar-io-client-addons/*/manifest.json` — 1
- `src/io/brodgar/addon/AddonRegistry.java` (`load`, `loadErrors`, `liveStatus`, `listAddons`, `AddonInfo`,
  `describeAddons`, `disabledSet`, `writeDisabled`, `setEnabled`, `reloadNeeded`) — every task
- `src/io/brodgar/addon/ui/AddonPanel.java` (the constructor, `Row`, `refresh`) — every task
- `src/io/brodgar/addon/AddonManager.java` (the `addons` console command) — 1
