# 001.7 — The close: the matrix re-run and the final sweep

> The counterpart of [`audit.md`](audit.md), taken at the end of the feature. Same method, same rows:
> every `addons` feature 001–036 and every task inside it, against the tree as it now stands. A row
> reads **OK** (an owning section describes it), **N/A** (no user-facing surface), **CUT** (what it
> shipped no longer exists) or **OMITTED** (shipped, present, and deliberately not documented — each
> one reasoned in §4). There are no THIN and no GAP rows left.
>
> Paths are relative to `docs/addons/`. Every path and every `#anchor` in this file was resolved
> mechanically against the tree.

## 1. The numbers, then and now

| Measure | 001.1 (open) | 001.7 (close) |
|---|---|---|
| Pages under `docs/` | 39 | **72** |
| Lines under `docs/` | 5,727 | **7,460** |
| Largest page | 1,171 (`api/ui.md`) | **250** (`api/client/profiling/attribution.md`) |
| Pages over the 300-line ceiling | 4 | **0** |
| Internal links checked | 695 | **1,112** |
| Broken links / anchors | 0 | **0** |
| Links leaving `docs/` | 16 (into `specs/` and `addons/`) | **10**, all `examples.md` → `addons/<id>/main.lua` (D-009) |
| Headings with a slugger-deleted character between spaces | 208 false + 6 real | **0** |
| Retired names present | 7 | **0** |
| `hafen.*` namespaces installed by `src/` | 37 (miscount) | **36**, all documented |
| Events fired by `src/` | 26, all documented | 26, all documented |
| Gated verbs (`requireActions`) | 18, all documented | 18, all documented |

Two of 001.1's own figures were recounted at the close and are corrected here rather than in place:
the namespace count is **36**, not 37 (`grep -rnE '\bhafen\.set\("' src/io/brodgar/addon/*.java`,
one line per namespace, deduplicated); and the matrix's verdict totals were summarised as
*107 OK · 11 THIN · 3 GAP · 8 N/A · 11 CUT* while the table itself holds **117 OK · 8 THIN · 2 GAP ·
4 N/A · 9 CUT** (140 rows either way). The prose totals counted the three `G-` gaps and the grouped
THIN paragraphs as rows; the table is the record.

## 2. Coverage matrix, re-run

| Task | Where it is documented now | V |
|---|---|---|
| **001-bootstrap-engine** | | |
| 001.1 | `runtime.md#the-console-commands` — the command table, and what `:lua` is not sandboxed against | OK |
| 001.2 | `runtime.md#where-an-addon-lives`, `#the-manifest`, `#the-sandbox` | OK |
| 001.3 | `api/events.md`, `api/timer.md`, `runtime.md#when-your-code-runs`, `guides/events-and-timers.md` | OK |
| 001.4 | internal refactor, no surface | N/A |
| **002-read-api** | | |
| 002.1 | `api/world.md`; the flat gob table is gone (017) | CUT |
| 002.2 | `api/map.md`, `api/player.md`, `api/time.md`, `api/sound.md` | OK |
| 002.3 | `api/char.md`, `api/party.md`; `hafen.items` is gone (029.3) → `api/ui/items.md` | OK |
| **003-widget-tree-reads** | | |
| 003.1 | adapter internal; `vitals()` is gone (027) → `api/meter.md` | CUT |
| 003.2 | `api/buff.md`, `api/char.md#read` (food), `api/events.md#character-and-status` | OK |
| 003.3 | `api/study.md`, `api/types.md#studyslot` | OK |
| 003.4 | `api/actionbar.md#read`, `api/events.md#character-and-status` | OK |
| **004-saved-variables** | | |
| 004.1 | `api/store.md`, `guides/saved-data.md`, `runtime.md#the-manifest` | OK |
| **005-sandbox-reload-panel** | | |
| 005.1 | `runtime.md#the-sandbox` (available and absent, name by name), `#budgets-and-the-watchdog` | OK |
| 005.2 | `runtime.md#what-a-reload-keeps-and-what-it-drops`, `guides/debugging.md` | OK |
| 005.3 | `runtime.md#the-addons-panel` (rows, badges, status), `#budgets-and-the-watchdog` | OK |
| 005.4 | GL bug fix, no surface | N/A |
| **006-custom-ui** | | |
| 006.1 | `api/ui/custom.md#windows-and-widgets`, `api/ui/drawing.md` | OK |
| 006.2 | `api/ui/custom.md#overlays`, `guides/custom-ui.md` | OK |
| **007-hooks-hotkeys** | | |
| 007.1 | `api/hook.md#hafenhookinputtarget-event-fn` | OK |
| 007.2 | `api/hook.md#hafenhookactionmsg-fn` | OK |
| 007.3 | `api/hook.md#hafenhookmessagemsg-fn` | OK |
| 007.4 | `hafen.key` is gone (018.2) → `api/client/keybindings.md` | CUT |
| 007.5 | `api/client/keybindings.md#addon-hotkeys-start-unbound` | OK |
| **008-widget-replacement** | | |
| 008.1 | `ui.onWidgetCreate` is gone (030.2) → `api/ui/replace.md#watching-for-a-widget` | CUT |
| 008.2 | `ui.adopt` is gone (029.2) → `api/ui/native.md#hiding-a-native-widget-carries-a-restore` | CUT |
| 008.3 | `api/ui/replace.md#replacing-a-native-window-ungated`; **`bags` described** in `examples.md#bags` | OK |
| **009-gap-subsystems** | | |
| 009.1 | `api/markers.md`, `api/events.md#roster-quests-markers` | OK |
| 009.2 | `api/char.md#read`, `api/types.md#skill-credo-experience` | OK |
| 009.3 | `api/radar.md#read`, `#write-ungated` | OK |
| 009.4 | `api/slash.md#register`, `#the-command-handle` | OK |
| 009.5 | `api/kin.md#read`, `api/events.md#roster-quests-markers` | OK |
| 009.6 | `api/speed.md#read` | OK |
| 009.7 | `api/craft.md#read` | OK |
| 009.8 | `api/quests.md`, `api/events.md#roster-quests-markers` | OK |
| 009.9 | `api/wounds.md`, `api/events.md#character-and-status` | OK |
| 009.10 | `api/fight.md`, `api/types.md#maneuver-deckcard-fightsummary` | OK |
| **010-write-actions** | | |
| 010.1 | `api/act.md#the-actions-permission`, `#hafenactmovetox-y`, `guides/actions-and-permissions.md` | OK |
| 010.2 | the master switch was removed by 010.3; `api/act.md#the-actions-permission` states the model that exists | CUT |
| 010.3 | `api/act.md#the-actions-permission`, `runtime.md#the-addons-panel` (the consent dialog) | OK |
| 010.4 | `api/act.md#movement-and-the-world-gated-actions`, `#escape-hatch-gated-actions` | OK |
| 010.5 | `api/act.md#menus-gated-actions` | OK |
| 010.6 | `api/act.md#hafenactitemitem-verb-n`, `api/conventions.md#itemref-an-inventory-or-equipment-item` | OK |
| 010.7 | `api/speed.md#write-gated-actions`, `api/craft.md#write-gated-actions`, `api/kin.md#write-gated-actions`, `api/actionbar.md#write-gated-actions` | OK |
| **011-virtual-entities** | | |
| 011.1 | `api/ghost.md#create-ungated`, `#the-ghost-handle` | OK |
| 011.2 | `api/ghost.md#clickability`, `api/events.md#world-ghosts-and-sprites` | OK |
| 011.3 | `api/ghost.md#look-and-orientation` | OK |
| 011.4 | `api/map.md#saving-a-world-position-across-sessions`, `api/ghost.md#layouts-and-persistence` | OK |
| 011.5 | `api/map.md#screen-to-world-and-placement-snapping`, `api/hook.md#hafenhookgrabmove-up` | OK |
| 011.6 | `api/ghost.md#the-transform-gizmo`, `examples.md#planner` | OK |
| 011.7 | `api/map.md#screen-to-world-and-placement-snapping`, `api/ghost.md#scale` | OK |
| **012-custom-rendering** | | |
| 012.1 | the image loader is `api/asset.md#image`; screen drawing is `api/ui/drawing.md#images` | OK |
| 012.2 | `api/render/sprites.md#create-ungated`, `#sprite-handle` | OK |
| 012.3 | `api/render/sprites.md#billboard`, `#clickability` | OK |
| 012.4 | `api/render/models.md#the-model`, `#the-gltf-subset` | OK |
| 012.5 | `api/render/models.md#the-gltf-subset` | OK |
| 012.6 | `examples.md#planner` — described, not only named | OK |
| 012.7 | `api/render/models.md#the-gltf-subset` — lighting written up: baked normals, the smooth fallback, what the lights modulate | OK |
| **013-data-network** | | |
| 013.1 | `api/json.md` | OK |
| 013.2 | `api/http.md#hafenhttpgeturl-opts-cb`, `#security-and-limits` | OK |
| 013.3 | `api/http.md#hafenhttpposturl-body-opts-cb`, `#cancellation-and-lifecycle` | OK |
| **014-ui-extensions** | | |
| 014.1 | `api/ui/custom.md#ondrop-makes-a-widget-a-drop-target`, `api/ui/drawing.md#images`, `api/act.md#hafenactclickgobgob-button-mods` (mouse modifiers) | OK |
| **015-widget-introspection** | | |
| 015.1 | `ui.root()` and `WidgetNode` are gone (029/030); `hafen.ui.node(id)` lives, in `api/ui/widget.md#getting-a-widget` | CUT |
| 015.2 | `api/ui/selectors.md#hit-testing` | OK |
| **016-fonts** | | |
| 016.1 | provider internal; its effect is `api/ui/style/README.md#the-cascade` | N/A |
| 016.2 | `api/font.md#draw-with-it`, `#the-widget-default`, `#the-per-call-option`, `#mix-fonts-on-one-line` | OK |
| 016.3 | `api/ui/style/surfaces.md#windowtitle` | OK |
| 016.4 | `api/ui/style/surfaces.md#button` | OK |
| 016.5 | `api/ui/style/surfaces.md#textentry`, `#label` | OK |
| 016.6 | `api/ui/style/surfaces.md#heading` | OK |
| 016.7 | `api/ui/style/surfaces.md#menu`, `#tooltip`, `#chat` | OK |
| 016.8 | `api/ui/style/surfaces.md#worldspeech-and-worldnick` | OK |
| 016.9 | `node:setFont`/`:resetFont` are gone (034.3) → `api/ui/style/README.md#restyle-one-widget` | CUT |
| **017-gob-oop** | | |
| 017.1 | `api/gob.md` (whole page), `api/conventions.md#gob-a-game-object` | OK |
| 017.2 | spec-side closure, no surface | N/A |
| **018-client-options** | | |
| 018.1 | `api/client/README.md#interface` … `#camera`, `#client` | OK |
| 018.2 | `api/client/keybindings.md#names`, `#key-strings` | OK |
| 018.3 | `api/client/README.md` | OK |
| 018.4 | `examples.md#optionstest` — described, including the login-screen `nil` case it exists to show | OK |
| **019-profiling** | | |
| 019.1 | `api/client/README.md#client`, `api/client/profiling/README.md` | OK |
| 019.2 | `api/client/profiling/README.md#frame`, `#historyn` | OK |
| 019.3 | `api/client/profiling/counters.md` (all five) | OK |
| 019.4 | `api/client/profiling/attribution.md#addons`, `#custom-scopes` | OK |
| 019.5 | `api/client/profiling/attribution.md#widgets` | OK |
| 019.6 | `api/client/profiling/attribution.md#passes`, `#gl` | OK |
| 019.7 | `api/client/profiling/attribution.md#overhead` — the verb and the tiers; the measured 0.54% stays out (D-008) | OK |
| 019.8 | `examples.md#profiler` — the six tabs described | OK |
| **020-kin-oop** | | |
| 020.1 | `api/kin.md#read`, `api/conventions.md#kin-a-roster-entry` | OK |
| 020.2 | `api/kin.md#kin-and-gob`, `api/gob.md#kin` | OK |
| 020.3 | `api/events.md#roster-quests-markers`, `api/types.md#kinentry` | OK |
| **021-actionbar-oop** | | |
| 021.1 | `api/actionbar.md#read`, `api/conventions.md#slot-an-action-bar-slot` | OK |
| 021.2 | `api/events.md#character-and-status`, `api/types.md#actionbarslot` | OK |
| 021.3 | `api/actionbar.md` | OK |
| **022-actionbar-set** | | |
| 022.1 | `api/actionbar.md#write-gated-actions` | OK |
| **023-menugrid-oop** | | |
| 023.1 | `api/menugrid.md#the-key-is-a-string-and-it-splits-by-shape`, `#read`, `#the-tree` | OK |
| 023.2 | `api/menugrid.md#use-ungated` — the ungated write stated as a present-tense boundary (D-2 closed) | OK |
| 023.3 | `api/menugrid.md`, `api/types.md#pagina` | OK |
| **024-audio-oop** | | |
| 024.1 | `api/sound.md#read`, `#play-and-stop-ungated` | OK |
| 024.2 | `api/sound.md#play-and-stop-ungated` | OK |
| 024.3 | `api/sound.md#there-is-no-hafenmusic` — the boundary in the present tense, the history gone (D-6) | OK |
| 024.4 | `api/sound.md` | OK |
| **025-buffs-oop** | | |
| 025.1 | `api/buff.md#read` | OK |
| 025.2 | `api/events.md#character-and-status` | OK |
| 025.3 | `api/buff.md`, `api/types.md#buff` | OK |
| **026-text-cache** | | |
| 026.1 | `api/ui/drawing.md#text-is-cached-across-frames` | OK |
| 026.2 | `api/client/profiling/counters.md#textcache` | OK |
| 026.3 | `api/ui/drawing.md` | OK |
| **027-meters-oop** | | |
| 027.1 | `api/meter.md#read`, `#there-is-no-fixed-hp-stamina-and-energy-triple` | OK |
| 027.2 | `api/meter.md#events`, `api/events.md#character-and-status` | OK |
| 027.3 | `api/meter.md`, `api/types.md#meter` | OK |
| **028-asset-loader** | | |
| 028.1 | `api/asset.md#the-loader-takes-a-path-and-nothing-else`, `#the-types` | OK |
| 028.2 | `api/render/README.md#handle-only`, `api/asset.md#interning` | OK |
| 028.3 | `api/asset.md`, `api/conventions.md#asset-a-file-your-addon-ships` | OK |
| **029-widget-oop** | | |
| 029.1 | `api/ui/widget.md#getting-a-widget`, `#read` | OK |
| 029.2 | `api/ui/widget.md#owned-vs-borrowed` | OK |
| 029.3 | `api/ui/items.md#read`, `#the-container-lifecycle` | OK |
| 029.4 | `api/ui/widget.md`, `api/conventions.md#widget-a-piece-of-the-ui` | OK |
| **030-ui-selectors** | | |
| 030.1 | `api/ui/selectors.md#the-grammar`, `#roles` | OK |
| 030.2 | `api/ui/replace.md#watching-for-a-widget`, `api/events.md#widgets-appearing-and-disappearing` | OK |
| 030.3 | `api/ui/selectors.md#the-inspector`, `examples.md#widgetstack`, `guides/debugging.md` | OK |
| 030.4 | `api/ui/selectors.md`, `api/conventions.md#selector-naming-a-piece-of-the-ui` | OK |
| **031-window-lifecycle** | | |
| 031.1 | `api/ui/native.md#hiding-a-native-window-takes-its-toggle` | OK |
| 031.2 | `api/ui/replace.md#replacing-a-native-window-ungated`, `#where-replacing-ends` | OK |
| 031.3 | `api/ui/replace.md`, `examples.md#bags` | OK |
| **032-replace-verb** | | |
| 032.1 | `api/ui/replace.md#replacing-a-native-window-ungated` | OK |
| 032.2 | `hafen.ui.replace` is gone; the obituary is deleted (D-5), the verb documented as `widget:replace` | CUT |
| 032.3 | `api/ui/replace.md`, `api/events.md#widgets-appearing-and-disappearing` | OK |
| **033-ui-stylesheet** | | |
| 033.1 | `api/ui/style/README.md#install-a-sheet-ungated`, `api/ui/style/keys.md#site-keys` | OK |
| 033.2 | `api/ui/style/text.md#color` | OK |
| 033.3 | `api/asset.md#data`, `examples.md#theme`, `guides/theming.md` | OK |
| **034-ui-stylesheet-tree** | | |
| 034.1 | `api/ui/style/keys.md#tree-keys` | OK |
| 034.2 | `api/ui/style/README.md#the-cascade` | OK |
| 034.3 | `api/ui/style/README.md#restyle-one-widget` — its own section, on the page that owns the cascade (D-003) | OK |
| **035-ui-chrome** | | |
| 035.1 | `api/ui/style/chrome.md#bg-and-border` | OK |
| 035.2 | `api/ui/style/chrome.md#pad` | OK |
| 035.3 | `api/ui/style/chrome.md#panels`, `api/ui/style/keys.md#what-each-key-accepts` | OK |
| 035.4 | `api/ui/style/README.md`, `examples.md#theme`; the measured cost stays out (D-008) | OK |
| **036-ui-layout** | | |
| 036.1 | `api/ui/native.md#moving-and-resizing-ungated` | OK |
| 036.2 | `api/ui/style/geometry.md#pos-and-size` | OK |
| 036.3 | `api/ui/style/geometry.md#anchor` | OK |
| 036.4 | `api/ui/style/README.md#where-the-skinning-system-ends`, `guides/theming.md` | OK |

**Totals: 140 task rows — 127 OK · 4 N/A · 9 CUT.** At the open the same table read 117 OK · 8 THIN ·
2 GAP · 4 N/A · 9 CUT, so the arithmetic closes exactly: 117 + 8 + 2 = 127. Every THIN and every GAP
row is now OK, no row moved the other way, and **no row reads OMITTED** — everything shipped and still
present is described somewhere a reader would look. The four N/A and the nine CUT rows are the states
in which "not documented" is the correct answer (§4.3, §4.4).

## 3. What the open matrix flagged, and where it landed

| Open | What it was | Closed by |
|---|---|---|
| 001.1 THIN | `:lua` was one table row | `runtime.md#the-console-commands` |
| 005.1 THIN | the sandbox was seven lines | `runtime.md#the-sandbox` — every library and base function, present and absent |
| 005.2 THIN | `:reload` had no owning section | `runtime.md#what-a-reload-keeps-and-what-it-drops` |
| 005.3 THIN | the AddOns panel was one clause | `runtime.md#the-addons-panel` + `#budgets-and-the-watchdog` |
| 012.6 THIN | `planner` linked, never explained | `examples.md#planner` |
| 012.7 THIN | lighting was one clause | `api/render/models.md#the-gltf-subset` |
| 019.8 THIN | `profiler`'s tabs undescribed | `examples.md#profiler` |
| 034.3 THIN | `widget:skin{…}` lived on the wrong page | `api/ui/style/README.md#restyle-one-widget` (D-003) |
| 008.3 / G-1 | `bags` named nowhere | `examples.md#bags`, and named in `api/ui/replace.md` (D-009) |
| 018.4 / G-2 | `optionstest` named nowhere | `examples.md#optionstest` |
| G-3 | no index of what ships | `examples.md` — all ten addons, each with what it demonstrates |

## 4. Deliberate omissions

Everything below is shipped, real, and deliberately absent from `docs/`. Listed so a later audit does
not re-file it as a gap.

1. **Launcher flags and JVM system properties** (D-011). Three numbers `runtime.md` states — the addons
   directory, the per-call instruction cap, the per-tick budget — are overridable at launch. The enforced
   number stays, because an author branches on it; the switch belongs to an operator tier `docs/addons/`
   is not.
2. **Measured figures** (D-008): 019.7's 0.54% profiling overhead, 035.4's cost result, 001.1's own
   widget-classification counts. The verb that reports the number is documented; one machine's reading of
   it is not. Documented caps and budgets — the text cache's 512 entries / 8 MiB, `overhead().budget`,
   the `0..3` speed range — stay.
3. **Engine internals with no Lua surface**: the `TreeAdapter` mechanism (003.1), `haven.Fonts` as a
   provider (016.1), the `AddonManager` split (001.4). Their *effects* are documented where a reader
   meets them; the mechanisms are not part of the contract.
4. **Everything a hard cut removed** — the nine CUT rows. There is no migration tier and no obituary:
   the replacement is documented, the removal is not (style guide §7).
5. **The process tier**: task and feature numbers, decision ids, `specs/` and `src/` paths, class names.
   A reader of `docs/` cannot tell that any of it exists (style guide §11).
6. **`dependencies` / `optional_dependencies` are *not* an omission**: `runtime.md#the-manifest`
   documents both as recorded and not enforced, and says why nothing imports across addons. The engine
   half is filed to area `addons` (§6).

## 5. The final sweep

Method: an ad-hoc session script over every page under `docs/`, falsified in both directions before the
numbers below were believed — a healthy tree reports **0 broken**, and two planted breaks (a missing page
and a missing anchor) are both caught and reported at the right line. The slugger deletes rather than
replaces, which is the trap 001.1 fell into.

| Check | Result |
|---|---|
| Links and anchors | **1,112 internal links, 0 broken**, across all 72 pages |
| Links leaving `docs/` | 10, all `examples.md` → `addons/<id>/main.lua`, the one allowed exception (D-009) |
| Size | 0 pages over 300 lines; largest 250 |
| Headings | 0 with a slugger-deleted character between spaces, 0 at `#####`, 0 internal codes |
| Retired names | 0 hits for all 14 names on the style guide's list |
| Symbols, docs → `src/` | every `hafen.<namespace>` named in `docs/` is registered in `src/io/brodgar/addon/`; the only first-level name that is not is `hafen.music`, which the tree names exactly once, as a stated absence. Every `hafen.<ns>.<verb>` resolves to a registration string except four `hafen.store.<yourvar>` reads, which are user-declared saved variables by design |
| Symbols, `src/` → docs | all **36** registered namespaces have an owning page, and all 36 are listed in `api/README.md` |
| Reachability | every one of the 71 pages under `docs/addons/` is within **two clicks** of `docs/addons/README.md` (44 at one, 26 at two). `docs/README.md` sits above the landing page and is linked by nothing below it, which is what a root is |

## 6. Filed to area `addons` (not fixed here)

- **`specs/addons/STATE.md` lists a `ChatMessage` event** that exists nowhere in `src/`. The docs are
  right to omit it (001.1).
- **`specs/addons/017-gob-oop/tasks.md` has 017.2 unchecked** while `FEATURES.md` records the feature
  done (001.1).
- **`pag:use()` is an ungated write** while the other 18 server-reaching verbs require the `actions`
  permission (001.3). `api/menugrid.md#use-ungated` states the fact; the permission model is the engine's
  decision.
- **`dependencies` / `optional_dependencies` are parsed, validated and never used** (001.6). Either the
  loader honours them or the fields go.
