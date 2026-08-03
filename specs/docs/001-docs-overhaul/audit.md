# 001.1 — The audit

> Evidence for the whole feature. **Edits no docs page.** Three parts: (a) the coverage matrix —
> every `addons` feature 001–036 and every task inside it, against where it is documented today;
> (b) the 39-page inventory — size, topics, verdict; (c) the drift list.
> Re-run at the close (001.7): every matrix row must read `OK` or be listed as a reasoned omission.

## Method & ground truth

- **Ground truth is `src/` first**, the feature's `NNN-` folder second (030.4). `specs/addons/design/*`
  is historical-at-write-time and was **not** used as truth.
- Task list built from each `specs/addons/NNN-*/tasks.md` (three different formats; all 140 tasks
  recovered). Shipped surface cross-checked against the `hafen` facade assembly
  (`AddonManager.installHafen`, `src/io/brodgar/addon/AddonManager.java:901`) and the 30 `install*`
  entry points beside it.
- Page topics read from every heading (`##`/`###`/`####`) plus each page's opening paragraph.
- Drift found by grepping the docs for **promise shapes** (035.4's rule: rot lives in prose, not in
  tables) and for retired API names, then checking each hit against `src/`.

**Baseline numbers taken this session** (re-run at the close):

| Measure | Today |
|---|---|
| Pages under `docs/` | 39 (1 landing, 1 tutorial, 1 api index, 36 api pages) |
| Lines under `docs/` | 5,727 |
| Internal links checked | 695 |
| Broken links / anchors **inside** `docs/` | **0** |
| Links pointing **outside** `docs/` | 16 (all resolve on disk — see D-4) |
| `hafen.*` namespaces installed by `src/` | 37 |
| Events fired by `src/` | 26 — all 26 in `api/events.md` |
| Gated verbs in `src/` (`requireActions`) | 18 — all 18 documented |

---

## (a) Coverage matrix — feature × task × where documented

Verdicts: **OK** = an owning section describes it · **THIN** = mentioned, but not described where a
reader would look · **GAP** = shipped and not described anywhere · **N/A** = no user-facing surface
(internal refactor, bug fix, spec-only) · **CUT** = what it shipped no longer exists; nothing to
document, and any surviving mention is drift (§c).

| Task | What it shipped | Documented today | V |
|---|---|---|---|
| **001-bootstrap-engine** | LuaJ engine, disk loading, tick pump, events, timers | — | |
| 001.1 | LuaJ embed + `:lua` REPL | `getting-started.md#developing` (one table row) | THIN |
| 001.2 | `manifest.json` discovery, per-addon Lua envs | `getting-started.md#the-manifest` | OK |
| 001.3 | Tick pump, event bus, timers | `api/events.md`, `api/timer.md`, `getting-started.md#the-lifecycle` | OK |
| 001.4 | `AddonManager` split (behaviour-preserving) | — | N/A |
| **002-read-api** | Glob-backed read API | — | |
| 002.1 | `hafen.gob.*` flat + `hafen.world.*` | `api/world.md`; the flat gob table is CUT (017) | CUT |
| 002.2 | `map` / `player` / `time` / `sound` | `api/map.md`, `api/player.md`, `api/time.md`, `api/audio.md` | OK |
| 002.3 | `hafen.items.*` + `char` + `party` | `api/char.md`, `api/party.md`; `items` CUT (029.3) → `api/ui.md#items-inside-a-container` | OK |
| **003-widget-tree-reads** | adapter mechanism + 7 subsystem reads | — | |
| 003.1 | `TreeAdapter` + uimsg tap; vitals | mechanism internal; vitals CUT (027) → `api/meters.md` | CUT |
| 003.2 | buffs + FEP/food | `api/buffs.md` (rewritten 025), `api/char.md` (food), `api/events.md` (`FepChanged`) | OK |
| 003.3 | study + skills | `api/char.md#hafenstudy`, `api/types.md#studyslot` | OK |
| 003.4 | action bar read + `EquipChanged` | `api/actionbar.md` (rewritten 021), `api/events.md` | OK |
| **004-saved-variables** | — | — | |
| 004.1 | `hafen.store`, per-char + account scopes | `api/store.md`, `getting-started.md#saved-variables` | OK |
| **005-sandbox-reload-panel** | sandbox, `:reload`, panel, watchdog | — | |
| 005.1 | Sandbox whitelist + instruction watchdog | `getting-started.md#the-sandbox` (7 lines, no list of what IS available beyond three libs) | THIN |
| 005.2 | `:reload` (addon layer only), enabled set | `getting-started.md#developing`; behaviour described 20× across api pages, **no owning section** | THIN |
| 005.3 | AddOns panel + soft per-tick CPU budget | one clause in `getting-started.md#the-sandbox`; the panel itself (rows, badges, status) is undescribed | THIN |
| 005.4 | Panel tooltip GL fix | — | N/A |
| **006-custom-ui** | — | — | |
| 006.1 | `ui.window`/`widget` + the `g` draw wrapper | `api/ui.md#custom-windows--widgets`, `#the-g-draw-wrapper` | OK |
| 006.2 | HUD overlays + gob overlays | `api/ui.md#overlays` | OK |
| **007-hooks-hotkeys** | L1/L2/L3 hooks + hotkeys | — | |
| 007.1 | `hook.input` (L1) | `api/hooks.md#hafenhookinputtarget-event-fn` | OK |
| 007.2 | `hook.action` (L2) | `api/hooks.md#hafenhookactionmsg-fn` | OK |
| 007.3 | `hook.message` (L3) | `api/hooks.md#hafenhookmessagemsg-fn` | OK |
| 007.4 | `hafen.key.bind` | CUT (018.2) → `api/client.md#keybindings` | CUT |
| 007.5 | Per-addon keybind panel sections | `api/client.md#addon-hotkeys-start-unbound` | OK |
| **008-widget-replacement** | interception, model handle, replace | — | |
| 008.1 | `ui.onWidgetCreate` | CUT (030.2) → `api/ui.md#watching-for-a-widget` | CUT |
| 008.2 | `ui.adopt(id)` | CUT (029.2) → `api/ui.md#hiding-a-native-widget-carries-a-restore` | CUT |
| 008.3 | `ui.replace(...)` + the `bags` example | CUT (032) → `api/ui.md#replacing-a-native-window`; **`bags` itself is named nowhere in `docs/`** | GAP |
| **009-gap-subsystems** | 10 read subsystems | — | |
| 009.1 | `hafen.markers` + `MarkersChanged` | `api/markers.md` | OK |
| 009.2 | `char.skillsAvailable`/`credos`/`experiences` | `api/char.md`, `api/types.md#skill--credo--experience` | OK |
| 009.3 | `hafen.radar` categories + setters | `api/radar.md` | OK |
| 009.4 | `hafen.slash` console commands | `api/console.md#hafenslash--console-commands` | OK |
| 009.5 | `hafen.kin` roster + `KinChanged` | `api/kin.md` (rewritten 020) | OK |
| 009.6 | `hafen.speed` read | `api/speed.md#read` | OK |
| 009.7 | `hafen.craft` read | `api/craft.md#read` | OK |
| 009.8 | `hafen.quests` + `QuestAdded`/`QuestDone` | `api/quests.md` | OK |
| 009.9 | `hafen.wounds` + `WoundChanged` | `api/wounds.md` | OK |
| 009.10 | `hafen.fight` deck builder read | `api/fight.md` | OK |
| **010-write-actions** | the gated write tier | — | |
| 010.1 | Permission mechanism + `act.moveTo` | `api/actions.md#the-actions-permission`, `#hafenactmoveto` | OK |
| 010.2 | Global master switch | REMOVED by 010.3 (D-028); `actions.md` states the absence in the present tense | CUT |
| 010.3 | Enable-time consent dialog (D-028) | `api/actions.md#the-actions-permission` | OK |
| 010.4 | `clickGob`/`useItemOn`/`place`/`select`/`raw` | `api/actions.md#movement--world`, `#escape-hatch` | OK |
| 010.5 | `act.menu` / `act.flower` | `api/actions.md#menus` | OK |
| 010.6 | `act.item(item, verb[, n])` | `api/actions.md#items`, `api/conventions.md#itemref--an-inventoryequipment-item` | OK |
| 010.7 | Per-subsystem gated verbs | `api/speed.md`, `api/craft.md`, `api/kin.md`, `api/actionbar.md` | OK |
| **011-virtual-entities** | client-only ghosts | — | |
| 011.1 | `ghost.new/list` + `:move/:pos/:res/:destroy` | `api/ghost.md#functions`, `#ghost-handle` | OK |
| 011.2 | `clickable`/`onClick` + `GhostClicked` | `api/ghost.md#clickability--the-ghostclicked-event-v2` | OK |
| 011.3 | `:rotate/:setRes/:alpha/:tint/:show/:hide` | `api/ghost.md#look--orientation-v3` | OK |
| 011.4 | `map.fromGridPos` (grid-anchored layouts) | `api/map.md`, `api/ghost.md#layouts--persistence-v4` | OK |
| 011.5 | `map.screenToWorld` / `snapPlace` / grab | `api/map.md#screen--world--placement-snapping-v5`, `api/hooks.md#hafenhookgrab` | OK |
| 011.6 | The transform gizmo (bundled Lua) | `api/ghost.md#transform-gizmo-move--rotate--scale` (links **out** of `docs/` to `addons/planner/gizmo.lua`) | OK |
| 011.7 | `snapAngle`/`placeAngle`, rotate + scale | `api/map.md`, `api/ghost.md#scale-v6` | OK |
| **012-custom-rendering** | sprites + glTF models | — | |
| 012.1 | `render.image` + screen drawing | image loader CUT (028) → `api/asset.md`; drawing → `api/render.md#drawing-on-screen` | OK |
| 012.2 | Fixed world sprite | `api/render.md#standing-an-image-in-the-world`, `#sprite-handle` | OK |
| 012.3 | Billboard + sprite click | `api/render.md#billboard-camera-facing`, `#clickability--the-spriteclicked-event` | OK |
| 012.4 | glTF parser (.glb/.gltf) | `api/render.md#the-model-itself`, `#the-gltf-subset` | OK |
| 012.5 | Textures & materials | `api/render.md#the-gltf-subset` | OK |
| 012.6 | `planner` object integration | `planner` named in `api/ghost.md`/`map.md`/`render.md`, always as an out-of-docs link | THIN |
| 012.7 | Lighting (NORMAL baking + fallback) | `api/render.md#the-gltf-subset` (one clause) | THIN |
| **013-data-network** | — | — | |
| 013.1 | `hafen.json` parse/encode | `api/json.md` | OK |
| 013.2 | `http.get` + async/security substrate | `api/http.md#geturl--opts-cb`, `#security--limits` | OK |
| 013.3 | `http.post` + redirect follow | `api/http.md#posturl-body--opts-cb` | OK |
| **014-ui-extensions** | — | — | |
| 014.1 | widget `onDrop` + `g:resource` + mouse mods | `api/ui.md` (`onDrop`, `g:resource`); mouse modifiers → `api/actions.md` note | OK |
| **015-widget-introspection** | — | — | |
| 015.1 | `ui.root()`/`node(id)` + `WidgetNode` | CUT (029/030) → `api/ui.md#the-widget-object` | CUT |
| 015.2 | `ui.at`/`mouse` + `:at`/`:rootpos` | `api/ui.md#hit-testing--what-is-under-the-cursor-the-wow-framestack-enabler` | OK |
| **016-fonts** | typography + 11 render sites | — | |
| 016.1 | `haven.Fonts` provider (stacks, gen, cascade) | — (engine internal; its *effect* is the cascade in `api/ui.md#cascade--conflict`) | N/A |
| 016.2 | `font=` opts, `g:text` opts, `$font` tag | `api/fonts.md#draw-with-it--your-own-drawing` and its three subsections | OK |
| 016.3 | `"window.title"` site | `api/fonts.md#site-keys` | OK |
| 016.4 | `"button"` site | `api/fonts.md#site-keys` | OK |
| 016.5 | `"textentry"` + `"label"` sites | `api/fonts.md#site-keys` | OK |
| 016.6 | `"heading"` site (D-043 amendment) | `api/fonts.md#site-keys` | OK |
| 016.7 | `"menu"` + `"tooltip"` + `"chat"` sites | `api/fonts.md#site-keys` | OK |
| 016.8 | `"world.speech"` + `"world.nick"` sites | `api/fonts.md#site-keys` | OK |
| 016.9 | `node:setFont`/`:resetFont` | CUT (034.3) → `api/fonts.md#restyle-one-widget--widgetskin` | CUT |
| **017-gob-oop** | — | — | |
| 017.1 | `hafen.gob(id)` → interned Gob; hard cut | `api/gob.md` (whole page) | OK |
| 017.2 | Spec-side closure | **still unchecked in `specs/addons/017-gob-oop/tasks.md`** — no docs impact | N/A |
| **018-client-options** | — | — | |
| 018.1 | `client:options()` — 5 subsystem handles | `api/client.md#interface`…`#camera`, `#client` | OK |
| 018.2 | `hafen.key` retired → `keybindings` | `api/client.md#keybindings`, `#addon-hotkeys-start-unbound`, `#names`, `#key-strings` | OK |
| 018.3 | Documentation + coverage | `api/client.md` | OK |
| 018.4 | `optionstest` harness addon | **named nowhere in `docs/`** | GAP |
| **019-profiling** | — | — | |
| 019.1 | Options ▸ Client panel + master switch | `api/client.md#client`, `#profiling` | OK |
| 019.2 | Prof core + frame sampling | `api/client.md#frame`, `#historyn` | OK |
| 019.3 | Pull-only counters | `api/client.md#the-counters` (`memory`/`net`/`loader`/`render`/`textcache`) | OK |
| 019.4 | Per-addon accounting + custom scopes | `api/client.md#addons`, `#custom-scopes` | OK |
| 019.5 | Per-widget cost | `api/client.md#widgets` | OK |
| 019.6 | Named render passes + GL counters | `api/client.md#passes`, `#gl` | OK |
| 019.7 | Overhead accounting (0.54%) | `api/client.md#overhead` | OK |
| 019.8 | `profiler` addon + docs | named in `api/client.md` and `api/README.md`; the addon's six tabs are undescribed | THIN |
| **020-kin-oop** | — | — | |
| 020.1 | `LuaKin` + hard cut of the flat table | `api/kin.md#read` | OK |
| 020.2 | `kin:gob()` / `gob:kin()` | `api/kin.md#kin--gob`, `api/gob.md#kin` | OK |
| 020.3 | `KinChanged` payload = `Kin[]` | `api/events.md#roster-quests-markers` | OK |
| **021-actionbar-oop** | — | — | |
| 021.1 | `LuaSlot` + hard cut | `api/actionbar.md#read` | OK |
| 021.2 | `ActionbarChanged` = Slot | `api/events.md` | OK |
| 021.3 | Docs + harness | `api/actionbar.md` | OK |
| **022-actionbar-set** | — | — | |
| 022.1 | `slot:set(res)` (gated, async) | `api/actionbar.md#write-gated--requires-the-actions-permission` | OK |
| **023-menugrid-oop** | — | — | |
| 023.1 | `hafen.menugrid()` catalogue + lookup | `api/menugrid.md#the-key-is-a-string-and-it-splits-by-shape`, `#read`, `#the-tree` | OK |
| 023.2 | `pag:use()` (ungated) | `api/menugrid.md#use` — but see drift D-2 | OK |
| 023.3 | Docs, harness, coverage | `api/menugrid.md` | OK |
| **024-audio-oop** | — | — | |
| 024.1 | `hafen.sound(name)` interned Sound + `:play` | `api/audio.md#hafensoundname--a-sound-object` | OK |
| 024.2 | `:stop`/`:playing` + the live set | `api/audio.md#hafensound--what-your-addon-is-playing` | OK |
| 024.3 | The music section CUT (D-058) | `api/audio.md#there-is-no-hafenmusic` — a kept boundary, but carries history (D-6) | OK |
| 024.4 | Docs, harness, decisions | `api/audio.md` | OK |
| **025-buffs-oop** | — | — | |
| 025.1 | `hafen.buff` + hard cut of `hafen.buffs` | `api/buffs.md` | OK |
| 025.2 | `BuffAdded`/`Removed`/`Changed` = Buff | `api/events.md#character--status-widget-tree-backed` | OK |
| 025.3 | Docs, harness, coverage | `api/buffs.md`, `api/types.md#buff` | OK |
| **026-text-cache** | — | — | |
| 026.1 | Content-keyed text cache in `LuaGOut` | `api/ui.md#text-is-cached-across-frames` | OK |
| 026.2 | `profiling():textcache()` | `api/client.md#textcache` | OK |
| 026.3 | Docs | `api/ui.md` | OK |
| **027-meters-oop** | — | — | |
| 027.1 | `hafen.meter` + the `vitals()` cut | `api/meters.md#read`, `#there-is-no-fixed-hpstaminaenergy-triple` | OK |
| 027.2 | `MeterAdded`/`Removed`/`Changed` | `api/meters.md#events`, `api/events.md` | OK |
| 027.3 | Docs, harness, coverage | `api/meters.md`, `api/types.md#meter` | OK |
| **028-asset-loader** | — | — | |
| 028.1 | `hafen.asset(path)`, one intern cache | `api/asset.md` (whole page) | OK |
| 028.2 | Handle-only consumers + teardown audit | `api/render.md#handle-only`, `api/asset.md#interning` | OK |
| 028.3 | Docs, harness | `api/asset.md` | OK |
| **029-widget-oop** | — | — | |
| 029.1 | One interned Widget entity + reads | `api/ui.md#the-widget-object`, `#reads--they-answer-on-every-widget` | OK |
| 029.2 | Owned vs borrowed; end of `adopt` | `api/ui.md#owned-vs-borrowed--which-writes-answer` | OK |
| 029.3 | `widget:items()`; `hafen.items` cut | `api/ui.md#items-inside-a-container`, `#the-container-lifecycle` | OK |
| 029.4 | Docs, harness | `api/ui.md`, `api/conventions.md#widget--a-piece-of-the-ui` | OK |
| **030-ui-selectors** | — | — | |
| 030.1 | Grammar + the role classifier | `api/ui.md#selectors--naming-a-widget`, `#roles` | OK |
| 030.2 | `ui.on(sel, "appear"/"disappear")` | `api/ui.md#watching-for-a-widget`, `api/events.md#parts-of-the-ui-appearing--disappearing-not-on-this-bus` | OK |
| 030.3 | The `widgetstack` inspector | `api/ui.md#dont-guess--the-inspector-tells-you`, `getting-started.md#reading-the-clients-own-ui` | OK |
| 030.4 | Docs, harness | `api/ui.md`, `api/conventions.md#selector--naming-a-piece-of-the-ui` | OK |
| **031-window-lifecycle** | — | — | |
| 031.1 | A hidden native window swallows its toggle | `api/ui.md#hiding-a-native-window-takes-its-toggle` | OK |
| 031.2 | `replace` drives the view; one teardown rule | `api/ui.md#replacing-a-native-window` | OK |
| 031.3 | Docs, harness | `api/ui.md`, `getting-started.md` | OK |
| **032-replace-verb** | — | — | |
| 032.1 | Naming the main inventory + `w:replace()` | `api/ui.md#replacing-a-native-window` | OK |
| 032.2 | Deletion of `ui.replace`; addons ported | CUT → the same section; one deliberate obituary survives (D-5) | CUT |
| 032.3 | Docs, harness | `api/ui.md`, `api/events.md` | OK |
| **033-ui-stylesheet** | — | — | |
| 033.1 | `hafen.ui.skin{}` + end of the font-scope API | `api/ui.md#the-stylesheet--restyling-the-client`, `#site-keys--the-surfaces-this-ships` | OK |
| 033.2 | `color` (`fixcol`) + the two-addon stack | `api/ui.md#color--a-surfaces-colour-is-the-sheets` | OK |
| 033.3 | `.json`/`.txt` → `"data"` assets; `theme` | `api/asset.md#data--text`, `api/ui.md`; `theme` named in 5 pages | OK |
| **034-ui-stylesheet-tree** | — | — | |
| 034.1 | Tree-key resolution + `w:style()` | `api/ui.md#tree-keys--which-widgets-not-what-kind-of-surface` | OK |
| 034.2 | Drawing it (the widened frame) | `api/ui.md#cascade--conflict` | OK |
| 034.3 | `widget:skin{…}`, the `setFont` cut | **only** `api/fonts.md#restyle-one-widget--widgetskin`; `ui.md` links out to it **8 times** and owns no section for it | THIN |
| **035-ui-chrome** | — | — | |
| 035.1 | `window.frame` + a sheet-fed `Deco` | `api/ui.md#bg-and-border--the-surfaces-that-paint` | OK |
| 035.2 | `pad` + the insets that move content | `api/ui.md#pad--the-one-property-that-moves-things` | OK |
| 035.3 | The window-less `panel` key (`IBox`) | `api/ui.md#what-each-key-accepts`, `api/fonts.md#site-keys` | OK |
| 035.4 | Cost, docs, the theme, close | `api/ui.md`; the cost result itself is nowhere in `docs/` (by design — it is a spec fact) | OK |
| **036-ui-layout** | — | — | |
| 036.1 | `:pos(x,y)`/`:size(w,h)` on a native widget | `api/ui.md#laying-out-a-native-widget` | OK |
| 036.2 | `pos`/`size` as sheet properties | `api/ui.md#pos-and-size--laying-widgets-out-from-the-sheet` | OK |
| 036.3 | `anchor` (nine corners, offsets) | `api/ui.md#anchor--a-position-that-is-derived` | OK |
| 036.4 | The skinning boundary (D-092) + `:theme save` | `api/ui.md#where-the-skinning-system-ends` | OK |

**Totals: 140 task rows + 36 feature rows.** 107 OK · 11 THIN · 3 GAP · 8 N/A · 11 CUT.

### The three GAPs

| # | What | Why it is a gap |
|---|---|---|
| G-1 | `bags` (008.3, 031, 032) | The replacement example the reference *describes the mechanism of* is never named, so a reader has no working code to open. |
| G-2 | `optionstest` (018.4) | A whole namespace's exploration harness, invisible from the docs. |
| G-3 | The shipped example addons as a set — `hogtest`, `bags`, `netdemo`, `walker`, `optionstest` | Five of the nine examples are named nowhere under `docs/`; `planner`, `widgetstack`, `profiler` and `theme` are named only in passing. There is no index of what ships and what each one demonstrates. |

### The ten THIN rows, grouped

- **The engine/dev tier has no owning page** (001.1, 005.1, 005.2, 005.3): the sandbox, `:reload`,
  `:addons`, the AddOns panel, the instruction watchdog and the CPU auto-disable live as a handful of
  lines in `getting-started.md` plus ~20 incidental mentions scattered across the api pages. A reader
  who wants to know what `:reload` keeps and what it drops has no page to open.
- **The examples are undescribed** (012.6 `planner`, 019.8 `profiler`): named, linked out of `docs/`,
  never explained.
- **012.7 lighting** is one clause inside the glTF subset table.
- **034.3 `widget:skin{…}` has no owning section on the page that documents everything around it**:
  `ui.md` — which owns selectors, the cascade, the property tables and the layout rules — links out
  to `fonts.md#restyle-one-widget--widgetskin` **eight times** rather than describing the top of its
  own cascade. This is the sharpest single symptom of the `fonts.md`/`ui.md` duplication below.

---

## (b) Page inventory — all 39 pages

Verdicts: **keep** · **rewrite** (content stays, shape/voice/accuracy do not) · **split** · **merge**
· **move**. Sizes are today's line counts. Ceiling assumed ~300 lines (001.2 decides it).

| Page | Lines | Topics | Verdict |
|---|---|---|---|
| `README.md` | 47 | landing: doc links, quick-look example, "API at a glance" table | rewrite (regenerate the table from the final tree) |
| `getting-started.md` | 255 | 12 sections: first addon → manifest → lifecycle → sandbox → reads → store → UI/hotkeys → reading the client's UI → restyling → files → actions → dev commands | **split**: it is a tutorial *and* six guides *and* the dev-tier reference at once |
| `api/README.md` | 82 | api index, 6 thematic sections | rewrite (regenerate) |
| `api/actionbar.md` | 88 | Slot object, read verbs, gated `:use`/`:set` | keep |
| `api/actions.md` | 105 | the `actions` permission, movement, menus, items, `raw` | keep |
| `api/asset.md` | 239 | four types, paths, interning, per-type verbs, collection, errors, two "why" sections | rewrite (the two "why" sections are rationale, not reference) |
| `api/audio.md` | 77 | Sound object, the live set, the no-music boundary | keep (drop the history in D-6) |
| `api/buffs.md` | 65 | `hafen.buff` arity, Buff reads | keep |
| `api/char.md` | 52 | `hafen.char` **and** `hafen.study` on one page | **split** — two namespaces, one page |
| `api/client.md` | **688** | options (5 panels) + keybindings + **profiling** (13 subsections) | **split** — options and profiling are two subjects |
| `api/console.md` | 39 | `hafen.log` **and** `hafen.slash` | keep or merge into the dev-tier page |
| `api/conventions.md` | 200 | the namespace, 8 reference kinds, snapshots, filter, coords, colours, nil, threading, gating | keep (it is the vocabulary page) |
| `api/craft.md` | 32 | read + gated `make` | keep |
| `api/events.md` | 137 | subscription + the catalogue (6 groups, 26 events) | keep |
| `api/fight.md` | 22 | maneuver/deck reads | keep |
| `api/fonts.md` | **436** | two doors → derive → own drawing → **the stylesheet site-key table** → `widget:skin` → example | **split/merge** — ~250 lines of it is stylesheet material duplicated with `ui.md` |
| `api/ghost.md` | 237 | ghosts: functions, handle, anchoring, look, scale, clicks, layouts, ground moves, gizmo | rewrite (strip the `(V2)`…`(V6)` slice codes from headings) |
| `api/gob.md` | 118 | getting a Gob, methods, kin, identity, passing one around | keep |
| `api/hooks.md` | 118 | L1/L2/L3 + `hook.grab` | keep |
| `api/http.md` | 150 | the `network` declaration, get, post, `res`, lifecycle, security | keep |
| `api/json.md` | 79 | parse, encode, notes | keep |
| `api/kin.md` | 106 | roster reads, Kin↔Gob, gated writes | keep |
| `api/map.md` | 94 | terrain + coords + screen↔world + persisting a position | rewrite (strip the `(V5)` slice code) |
| `api/markers.md` | 32 | player/system markers, add/remove | keep (state the gating — see D-3) |
| `api/menugrid.md` | 122 | the string key, reads, the tree, `use` | keep (fix D-2) |
| `api/meters.md` | 108 | no fixed triple, reads, events | keep |
| `api/party.md` | 21 | party roster | keep |
| `api/player.md` | 31 | the Player object | keep |
| `api/quests.md` | 28 | quest log reads | keep |
| `api/radar.md` | 23 | icon categories + setters | keep (state the gating — see D-3) |
| `api/render.md` | **336** | handle-only, screen drawing, world sprites, billboards, clicks, anchoring, the glTF subset, objects | **split** — 2D screen drawing and the 3D scene are two subjects |
| `api/speed.md` | 26 | read + gated `set` | keep |
| `api/store.md` | 30 | saved variables, scopes | keep |
| `api/time.md` | 19 | clock + astronomy | keep |
| `api/timer.md` | 23 | `after`/`every` + handle | keep |
| `api/types.md` | 230 | 22 snapshot shapes | keep (it is a lookup table by design) |
| `api/ui.md` | **1171** | custom UI · the Widget object · selectors · layout · hiding · items · overlays · observing/replacing · hit-testing · **the whole stylesheet** · the `g` wrapper | **split** — at least five subjects; the split with the highest anchor-rot risk |
| `api/world.md` | 38 | gob enumeration | keep |
| `api/wounds.md` | 23 | wound tree reads | keep |

**Shape of the tree today**: 4 pages hold **45%** of all lines (`ui.md` 1171 + `client.md` 688 +
`fonts.md` 436 + `render.md` 336 = 2,631 of 5,727), while 14 pages are under 40 lines. Nothing is
between 260 and 330 lines — the distribution is bimodal, which is what a tree grown task-by-task
looks like: a page is either a stub nobody revisited or the dumping ground for a whole feature run.

**Duplication found**: the stylesheet is documented **twice** — `fonts.md#restyle-a-global-surface`
(lines 134–424, incl. the site-key table and a `widget:skin` section) and
`ui.md#the-stylesheet--restyling-the-client` (lines 583–1111). Both are current and neither is
wrong; they are two entry points into one subject. 001.2 must pick one owner.

---

## (c) Drift list

Each entry: what the docs say · what `src/` says · verdict.

| # | Where | Claim | Ground truth | Verdict |
|---|---|---|---|---|
| D-1 | `api/ui.md:541` | *"The sheet reaches geometry in two places today — `pad` … and placing or anchoring a widget **by rule** is a later task of the same feature."* | `pos`, `size` and `anchor` **are** sheet properties (036.2/036.3); the same page documents them 300 lines earlier at `#pos-and-size…` and `#anchor…` | **WRONG** — a stale promise, exactly 035.4's shape. Fix in 001.4. |
| D-2 | `api/menugrid.md:121-122` | *"**Not gated yet.** … That is temporary: the permission model is being restructured, and this verb will join it."* | `LuaPagina.java:302` carries the same comment; the verb is genuinely ungated | **ACCURATE, WRONG SHAPE** — a promise about an unowned future. Restate as a present-tense boundary (why this verb is client-driven), or the docs promise work nobody has scheduled. |
| D-3 | `api/markers.md`, `api/radar.md` | neither page says anything about permissions | `markers.add/remove` and `radar.setVisible/setNotify` **write** (the on-disk map DB; the user's icon settings) and call **no** `requireActions` | **OMISSION** — `actions.md` tells the reader "the same permission also gates the per-subsystem write verbs" and lists five; a reader lands on `markers.add` with no way to know it is ungated. State it. |
| D-4 | 16 links in `asset.md`, `ghost.md`(5), `map.md`, `render.md`(6), `ui.md`(2) | link out of `docs/` into `specs/addons/decisions/*`, `specs/addons/design/*` and `addons/*` | all resolve on disk, but `specs/addons/design/17-*`/`18-*` are **historical-at-write-time** (030.4) | **WRONG TARGET** — the user-facing tier points at internal, possibly-superseded documents. The `addons/*` links (examples) are legitimate content that should be *described*, not just linked (see G-3). |
| D-5 | `api/ui.md:528-531` | the `hafen.ui.replace` obituary | correct (the function is gone) | **HISTORY** — deliberate in 032.3, forbidden by this feature's no-history rule. Delete in 001.4. |
| D-6 | `api/audio.md:59-73` | *"It was built to spec, tested…"* | correct | **PART HISTORY** — the *boundary* ("there is no `hafen.music`, because this server sends no MIDI") stays; the sentence about it having been built and removed goes. |
| D-7 | `api/fonts.md:51` | *"`hafen.font.load` is **gone**."* | correct | **HISTORY** — delete; say where a font comes from, not where it used to. |
| D-8 | `api/fonts.md:392` | *"`widget:setFont(h)` and `widget:resetFont()` are **gone** — they read as plain `nil`."* | correct | **HISTORY** — delete. |
| D-9 | `api/render.md:9` | *"`hafen.render.image` and `hafen.render.model` are **gone**"* | correct | **HISTORY** — delete. |
| D-10 | `api/events.md:79-81` | *"`hafen.ui.onWidgetCreate` — which reported the server's own widget-creation vocabulary — is gone."* | correct | **HISTORY** — delete; keep the present-tense half ("there is no `WidgetCreated` event; you say *which* widget you care about"). |
| D-11 | `api/ui.md:255`, `:359`, `:90` | *"there is no `:move()`"* · *"There is no `hafen.ui.adopt`. It existed only to…"* · *"that is why there is no `:same()`"* | all correct | **BORDERLINE** — `:359` narrates a removal (history, delete); `:255` and `:90` read as present-tense design statements and may stay if 001.2's style guide allows "there is no X because Y". Decide once, apply to all three. |
| D-12 | `api/asset.md:51` | *"why the signature is identical for all **three** types"* | there are **four** (image/font/mesh/**data**, D-074, 033.3) — and the section 25 lines above is titled "The four types" | **WRONG** — a count that went stale inside one page. |
| D-13 | `api/ghost.md` (5 headings), `api/map.md` (1) | headings carry internal slice codes: `(V2)`, `(V3)`, `(V4)`, `(V5)`, `(V6)` | these are `specs/` task labels | **LEAKED INTERNALS** — meaningless to a reader, and each one is an anchor other pages link to, so removing them is anchor-rot work. |
| D-14 | `api/ui.md:1001-1004` | *"**Corrected.** Until this was measured the table claimed a tree key and `widget:skin` were inert for `bg` and `border`. They are not, and never were…"* | the corrected statement is right | **HISTORY** — 035.2's correction note, addressed to a reader of the *previous* version of the page. Keep the true claim, delete the correction. |

### Checked and NOT drift

- All 26 events fired by `src/` appear in `api/events.md`; the catalogue names no event `src/` does
  not fire.
- All 18 `requireActions` verbs are documented, and `actions.md`'s cross-links to them resolve.
- `fonts.md:174`'s *"seven site keys … classify no widget"* — the list beside it has exactly seven
  entries and matches `Fonts.SCOPES` (13 members, 6 of which classify). Correct.
- `client.md:682`'s *"the five counters"* — `memory`/`net`/`loader`/`render`/`textcache`. Correct.
- `conventions.md:39`'s *"all 144 slots"*, `types.md` shapes, and the property × key table in
  `ui.md#what-each-key-accepts` — all match `src/`.
- 0 broken links and 0 broken anchors inside `docs/` (695 links checked).

### Findings that belong to another area

- **`specs/addons/STATE.md:25` lists a `ChatMessage` event** on the addon event bus. No such string
  exists anywhere in `src/`. The docs are right to omit it; the area's own STATE is wrong. File to
  area `addons` (this area does not edit it).
- **`specs/addons/017-gob-oop/tasks.md` still has 017.2 unchecked** while `FEATURES.md` records the
  feature DONE. No docs impact; file to area `addons`.
