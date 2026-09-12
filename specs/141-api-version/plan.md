# 141 — plan

## Approach

One value class carries the rule; every site that needs a word about a version asks it.

**`ApiVersion`** (new, `io.brodgar.addon`): `CURRENT = new ApiVersion(1, 0)` — the one number the client
implements, and the literal `tools/docverbs.py` reads; `major`/`minor`; `parse(Object)` — `null` stays
`null` (absent), a `String` matching `^[1-9][0-9]*\.(0|[1-9][0-9]*)$` parses, anything else (a `Double`,
`"1"`, `"0.1"`, `"01.0"`, a run `Integer.parseInt` refuses) throws `IllegalArgumentException` naming the
form and `CURRENT`; `why(ApiVersion)` — the sentence or `null`: absent → *declares no api_version, this
client implements 1.0*; another X → *written for API 9.0, this client implements 1.0*; a higher Y → *too
new: needs API 1.3 or newer, this client implements 1.0*; `label(ApiVersion)` — the row's `outdated (API
9.0, client 1.0)` / `outdated (no api_version, client 1.0)`; `toString()` → `1.0`.

**`Manifest`**: `apiVersion` becomes an `ApiVersion`, `null` when absent; `load` calls
`ApiVersion.parse(m.get("api_version"))`, so a bad shape is a manifest error like every other malformed
field, from all three callers of `load`; `internal()` and `test()` pass `CURRENT`; `intv` goes, nothing
else calls it.

**`AddonRegistry`**: an `outdated` map beside `loadErrors`, cleared where it is cleared, holding the
label. In `load`, after `Manifest.load` and before the sandbox is built: `why != null` and the box off
→ record, log *skipping out of date addon 'x': why*, `continue`; the box on (141.2) → log *loading out
of date addon 'x': why* and fall through. `liveStatus` answers the label between `error` and
`disabled`; `listAddons` prints `[outdated]` in the same slot; `AddonInfo` drops the dead `int
apiVersion` for `String outdated` — `why` recomputed in `describeAddons` from the manifest it already
parses, so the tooltip speaks for a disabled row too. 141.2: `PREF_LOAD_OUTDATED = "addons/loadoutdated"`,
`loadOutdated()` over `Utils.getprefb`, `setLoadOutdated(v)` writing `Utils.setprefb` and raising
`reloadNeeded` on a change, as `setEnabled` does.

**`AddonPanel`**: `Row`'s tooltip opens with `Out of date: …` when `ai.outdated` is set, before the
description, as the manifest error does. 141.2: a `CheckBox("Load out of date AddOns")` between `hint`
and the button row, `a` seeded from `loadOutdated()`, `set(v)` calling `setLoadOutdated(v)` and keeping
`a = v` — the `Row` checkbox's pattern; the javadoc names it.

**`docverbs.api_version()`**: reads `CURRENT = new ApiVersion\((\d+),\s*(\d+)\)` from `ApiVersion.java`
and greps `docs/addons/**` for `` implements API `(\d+\.\d+)` `` — exactly one hit, equal to the literal;
either miss is a red line in `main`'s exit. Its docstring states the blind spot: one sentence held to one
literal, nothing of the rule.

**Docs**: `runtime.md` is at 322 lines, over the ceiling, so its first two sections — *Where an addon
lives* and *The manifest* — move to a new `docs/addons/manifest.md`, which gains *The API version*: what
X and Y are, the declared-against-client table, absence, the shape error, and (141.2) the box. The
`api_version` row reads *string — the API you wrote against, `"X.Y"`; this client implements API `1.0`*,
the one sentence docverbs holds. `runtime.md`'s panel table gains the `outdated (…)` row and (141.2) the
checkbox paragraph its sentence; `README.md`'s *Where to go* gets a manifest row; its line 22,
`api/conventions.md:357` and `getting-started.md:33` re-point; the three manifest examples gain
`"api_version": "1.0"`, the tutorial's "two required fields" sentence says what the third is for, and
`debugging.md`'s status table gains `outdated (…)`.

**The sibling repository**: `sed` the 21 manifests' `"api_version": 1` to `"1.0"`, add the line to the
8 without, `ant bin`. `/end` commits nothing there — the maintainer does.

## Files to create/modify

- `src/io/brodgar/addon/ApiVersion.java` — new
- `src/io/brodgar/addon/Manifest.java`, `AddonRegistry.java`, `ui/AddonPanel.java`, `AddonManager.java`
  (the `:addons` comment) — 1; `AddonRegistry.java`, `ui/AddonPanel.java` again — 2
- `tools/docverbs.py` — 1
- `docs/addons/manifest.md` — new, 1; `runtime.md`, `README.md`, `getting-started.md`, `api/http.md`,
  `api/conventions.md`, `guides/debugging.md` — 1; `manifest.md`, `runtime.md`, `guides/debugging.md` — 2
- `../brodgar-io-client-addons/*/manifest.json` — 1
- `docs/client/` — nothing: `prefs-and-options.md`, `ui-controls.md` and `ui-panels.md` already map
  every upstream member read

## Risks & gotchas

- **`Json` numbers are `Double`**: a manifest's `1` arrives as `Double 1.0`; the refusal branch is
  `!(v instanceof String)`, and the message says a number is not the form.
- **`Manifest.load` has three callers**; only `AddonRegistry.load` decides running, `describeAddons`
  recomputes `why` for the tooltip, and `scanAddonDefaults` is untouched: an out-of-date write addon
  still gets its default-disable and its consent.
- **The row's status label** sits at `UI.scale(200)` in a 360-wide `Row`: `outdated (API 9.0, client
  1.0)` is the longest status a row has carried; check it at scale 1.5, and if it clips, `Row`'s geometry
  is the place, not the wording.
- **`Refusal.reason(e)`** is what the panel shows for a manifest error: the message stands alone.
- **Pre-check `ApiVersion.parse` and `why` in `jshell`** on `build/classes` (`-R-Djava.awt.headless=true`)
  before the maintainer restarts; a Java change needs `ant bin` and a full restart, after `rm -rf
  build/classes` — `intv` leaving `Manifest` is a moved symbol an incremental build hides.
- **A suite cannot cause the states it checks**: no verb reads another addon's state, and a suite cannot
  put a folder beside itself — so the maintainer edits the suite's own `api_version` between `[manual]`
  lines; `:reload` re-reads manifests from disk.
- **141.2's watch reads the Options window's own widgets**: `window[title=AddOns]` — `OptWnd.chpanel`
  writes the panel's `cap` on the window while it shows — and `@CheckBox[text=…]`; `w:value()` reads a
  borrowed checkbox, unprotected. The watch is a timer, never the `:t141` handler, which holds the typed
  tree's monitor.

## Discarded alternatives

- **An integer that bumps only on a hard cut** — an addon cannot then say "needs at least this
  edition", so a Release-channel player behind a Beta-written addon meets the gap mid-play.
- **The client's release version as the API version** (WoW's literal `## Interface`) — every release
  would outdate every addon, and the box would end up permanently ticked.
- **A range language** (`>=1.3 <2`, `^1.3`, Maven `[a,b)`) — every form collapses to "same X, Y at
  least" against a host that keeps its editions additive.
- **A list of accepted versions** (WoW 11.0's comma list, RimWorld's `supportedVersions`) — an exact
  match with more syntax; one X per addon, and the box is the escape.
- **A JSON number for the field** — `1.10` is `1.1` as a number; the wire cannot carry an edition past 9.
- **Missing `api_version` as a load error** — the out-of-date state covers it and the box lets it load;
  a second error class would be a second door to one room.
- **`hafen.client():apiVersion()`** — a current addon has everything it declared against and cannot
  branch on a generation it never saw; WoW branches on `GetBuildInfo()` because its number moves every
  patch while its API mostly does not; an optional newer section is what `if hafen.x then` is for.
- **Loading an out-of-date addon and letting the per-line refusals speak** — the player meets the
  breakage mid-play with no row that said why; the box gives the same outcome as a choice.
- **A per-addon "load anyway"** — WoW's is global: the state is a player's stance, not a grant.
- **The release-time inventory checker in this feature** — enforcing the bump is a diff of two released
  vocabularies, a tool of its own; reported at the close.
- **A `hafen.addons()` read surface so a suite could observe another addon** — a namespace for the sake
  of a suite; the panel's labels are readable through the selectors, and what a suite cannot cause is
  `[manual]`.
