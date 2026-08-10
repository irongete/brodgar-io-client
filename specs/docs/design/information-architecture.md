# Docs information architecture

> Where every page lives. Written by 001.2 from 001.1's evidence
> (`specs/docs/001-docs-overhaul/audit.md`). The companion is
> [`style-guide.md`](style-guide.md), which says what a page looks like. **§3 is the tree as it
> stands and is the authority**; §1, §5, §6 and §8 record the migration `001` executed and are not
> rewritten as the tree moves on.

## 1. What the tree had to fix

As `001` found it: 39 pages, 5,727 lines, and four of them hold 45% of it (`ui.md` 1171, `client.md` 688,
`fonts.md` 436, `render.md` 336) while 14 sit under 40 lines and nothing sits between 260 and
330. There is one tutorial and then a wall of reference: no task-first tier at all, so
`getting-started.md` is a tutorial *and* six guides *and* the only description of the runtime.
Three things are shipped and undocumented — five example addons named nowhere — and the
stylesheet is documented twice, in `fonts.md` and in `ui.md`, which is why `widget:skin{…}` has
no section on the page that owns everything around it and `ui.md` links out to `fonts.md` for it
eight times.

So the tree needs a **task tier**, a **runtime page**, an **examples page**, one owner per
subject, and pages small enough to be re-read.

## 2. Rules

1. **Three tiers, and a page belongs to exactly one**: the tutorial (one path), `guides/` (one
   task per page), `api/` (one namespace per page). A guide never restates a signature; a
   reference page never teaches a workflow.
2. **One namespace, one path.** A namespace under the ceiling is `api/<namespace>.md`, named
   exactly as it is spelled in Lua: `hafen.buff` → `api/buff.md`, `hafen.flowermenu` →
   `api/flowermenu.md`. A reader who knows the name can type the path.
3. **Over the ceiling, a namespace becomes a directory** `api/<namespace>/` whose `README.md` is
   its hub. The same recursion applies inside: a sub-subject needing more than one page becomes
   its own directory with a hub (`api/ui/style/`, `api/client/profiling/`).
   **At the top level of `api/`, a directory means a namespace** (D-018). A page named for a
   *type* rather than a namespace — `gob.md`, `overlay.md`, which the index writes `[Gob]` and
   `[Overlay]` — splits into a **sibling type page**, never into a directory: `hafen.gob` is
   retired, so `api/gob/` would name a namespace that does not exist.
4. **A hub gives the reading order**; the index gives the flat map. `api/README.md` lists **every
   leaf page**, nested ones included — that is what keeps every page two clicks from
   `docs/addons/README.md`.
5. **The ceiling is 300 lines and the split is by subject** (style guide §9). No page in the
   target tree is planned over 270.
6. Non-namespace reader-facing pages live at the top of `docs/addons/`, not under `api/`:
   `runtime.md`, `examples.md`. `api/` is the `hafen.*` contract and nothing else.

## 3. The tree

83 pages, 10,344 lines, average ~125. Nothing is over the 300-line ceiling and nothing is within 10
lines of it: the largest are `api/types.md` and `api/event.md` at 286, then `api/world.md` at 271.
`conventions`, `gob` and `ui/widget` each shed a subject to a page of its own — `references`,
`overlay` and `ui/mouse`. Both totals and every size are one command away
(`find docs -name '*.md' | xargs wc -l`) and are re-derived at each close rather than carried
forward — a size written down here is a measurement, and it rots.

```text
docs/README.md                     the site root, one screen
docs/addons/README.md              landing: what an addon is, the three tiers, the nav
docs/addons/getting-started.md     the tutorial: zero to a running addon, one path
docs/addons/runtime.md             the runtime: folder layout, manifest fields, the
                                   sandbox, budgets and the watchdog, :reload, the
                                   AddOns panel, the console commands
docs/addons/examples.md            every addon that ships, what it demonstrates,
                                   which reference pages it exercises

docs/addons/guides/README.md       the task index
                  /reading-the-world.md        gobs, scans, terrain, the player
                  /events-and-timers.md        the bus, the lifecycle, scheduling
                  /custom-ui.md                windows, widgets, overlays, drawing
                  /saved-data.md               store scopes, data files
                  /hotkeys-and-commands.md     keybindings and console commands
                  /actions-and-permissions.md  the gate, and what it does not gate
                  /theming.md                  a sheet, then a theme as a data file
                  /debugging.md                the reload loop, the inspector, the
                                               profiler, reading the log

docs/addons/api/README.md          the reference index: every leaf page
                   /conventions.md  the grammar: sections, verbs, collections, nil,
                                    snapshots, filters, coordinates, colours,
                                    threading, the permission
                   /references.md   every kind of thing a verb takes: gob, kin, slot,
                                    menugrid, sound, asset, item, widget, selector
                   /types.md        every snapshot shape
                   /event.md        the one bus, the door onto it, and the catalogue

                   /gob.md /overlay.md /world.md
                   /player.md /time.md /char.md /study.md /party.md /buff.md /meter.md
                   /kin.md /speed.md /craft.md /quest.md /wound.md /fight.md
                   /actionbar.md /flowermenu.md /menugrid.md
                   /asset.md /font.md
                   /http.md /json.md /timer.md /store.md /log.md /slash.md /sound.md

                   /map/README.md       hub: what the recorded database is, the
                                        nil-until-loaded rule, interning, the order
                       /grids.md        segments, grids, and saving an anchor
                       /overlays.md     the recorded masks, and the display toggles
                       /drawings.md     grid images: levels, ownership, the cache
                       /markers.md      the Marker object, its Position, the unprotected writes
                       /icons.md        the icon registry and the IconCat object

                   /ui/README.md        hub and reading order
                      /custom.md        your own windows, widgets and overlays
                      /controls/README.md  hub: what a control is, the roster,
                                        the shared setters, the permission in prose (D-014)
                               /display.md     the passive controls: label, picture,
                                                separator, progress bar
                               /interactive.md the controls the user drives: button,
                                                entry, checkbox, radio, slider, scroll,
                                                scrollbar
                      /lists.md         the row-source controls: list, dropdown,
                                        menu, grid, table
                      /widget.md        the Widget object: reads, owned vs borrowed,
                                        subscribing, send, tooltips and focus
                      /mouse.md         the pointer: where it is, what is under it, the
                                        modifiers, and the grab
                      /selectors.md     naming a widget, roles, hit-testing,
                                        the inspector
                      /items.md         the items inside a container
                      /native.md        placing and hiding the client's own widgets
                      /replace.md       watching for a widget, and replacing it
                      /drawing.md       the g wrapper, images, the text cache
                      /style/README.md  the sheet, one widget's own rule,
                                        the cascade, where skinning ends
                            /keys.md    site keys vs tree keys, resolution, and
                                        which properties each key honours
                            /surfaces.md every surface the client ships, and what
                                        it does with a rule
                            /text.md    font and color
                            /chrome.md  bg, border, pad
                            /geometry.md pos, size, anchor

                   /client/README.md    settings: the five option handles
                          /keybindings.md
                          /profiling/README.md      turning it on, frame, history,
                                                    overhead, when it is off
                                    /counters.md    memory, net, loader, render,
                                                    standing surfaces, textcache
                                    /attribution.md addons, custom scopes, widgets,
                                                    passes, gl

                   /vr/README.md        hub: the anchor, the place a thing keeps, the
                                        shared verbs, and the switch for the section
                      /ghosts.md        the game's own props, stood where you put them
                      /sprites.md       an image in the world: its facing modes,
                                        clicks, following a gob
                      /models.md        glTF: the subset, the object handle, clicks
                      /widgets.md       a window standing in the world
                      /gizmo.md         the drag handles that move, rotate and scale one
```

## 4. Reading orders (what each hub says)

- **`docs/addons/README.md`**: new here → the tutorial; want to do a thing → guides; want a name
  → the reference. Then the at-a-glance table, regenerated from this tree.
- **`api/map/README.md`**: the ground itself (`grids`) → what covered it (`overlays`) → what it looks
  like (`drawings`) → what you and the server put on it (`markers`, `icons`).
- **`api/ui/README.md`**: draw your own UI (`custom` → `drawing`) · put the client's own widgets in it
  instead (`controls/` → `lists`) · point at the client's UI (`selectors` → `widget` → `items` →
  `mouse`) · change it (`native` → `replace`) · restyle it (`style/`).
- **`api/ui/controls/README.md`**: what a control is and the roster → the ones that only show
  (`display`) → the ones the user drives (`interactive`) → the ones with rows (`lists`).
- **`api/ui/style/README.md`**: what a sheet is → `keys` (which widgets) → `surfaces` (what they
  do with a rule) → the property pages → where it ends.
- **`api/client/profiling/README.md`**: switch it on and read a frame → the counters → who spent
  it.
- **`api/vr/README.md`**: the anchor and the place a thing keeps, then the kind you are standing
  (`ghosts`, `sprites`, `models`, `widgets`), then `gizmo` to drag one about.

## 5. The migration map

Every page and section as `001` found it, and where that task put it. Source line ranges are 001.1's.
**This is the record of one migration, not the current map** — where a later feature moved a target
again, §3 is the authority and this table is not rewritten to match.

### 5.1 The four oversized pages

| Source | Lines | Target |
|---|---|---|
| `api/ui.md` intro | 1–17 | `api/ui/README.md` |
| ` ` Custom windows & widgets | 18–64 | `api/ui/custom.md` |
| ` ` Overlays | 420–438 | `api/ui/custom.md` |
| ` ` Overlay / observer handles | 573–582 | `api/ui/custom.md` |
| ` ` The Widget object | 65–98 | `api/ui/widget.md` |
| ` ` Reads | 195–235 | `api/ui/widget.md` |
| ` ` Owned vs borrowed | 236–260 | `api/ui/widget.md` |
| ` ` Selectors (+ roles, the two rules, `res`, the inspector, holding it) | 99–194 | `api/ui/selectors.md` |
| ` ` Hit-testing | 543–572 | `api/ui/selectors.md` |
| ` ` Items inside a container + the lifecycle | 365–419 | `api/ui/items.md` |
| ` ` Laying out a native widget | 261–307 | `api/ui/native.md` |
| ` ` Hiding a native widget / a native window's toggle | 308–364 | `api/ui/native.md` |
| ` ` Observing & replacing + watching + replacing | 439–542 | `api/ui/replace.md` (D-5 obituary deleted) |
| ` ` The `g` draw wrapper + text cached across frames | 1112–1170 | `api/ui/drawing.md` |
| ` ` The stylesheet | 583–639 | `api/ui/style/README.md` |
| ` ` Cascade & conflict | 1045–1069 | `api/ui/style/README.md` |
| ` ` Where the skinning system ends | 1070–1111 | `api/ui/style/README.md` |
| ` ` Site keys | 640–666 | `api/ui/style/keys.md` |
| ` ` Tree keys | 667–728 | `api/ui/style/keys.md` |
| ` ` What each key accepts | 959–1044 | `api/ui/style/keys.md` |
| ` ` Properties (intro) | 729–748 | `api/ui/style/README.md` |
| ` ` `color` | 749–773 | `api/ui/style/text.md` |
| ` ` `bg`/`border` (+ the `panel` h5) · `pad` | 774–866 | `api/ui/style/chrome.md` |
| ` ` `pos`/`size` · `anchor` | 867–958 | `api/ui/style/geometry.md` (D-1 stale promise deleted) |
| `api/client.md` intro + reading and writing | 1–39 | `api/client/README.md` |
| ` ` `interface` `video` `audio` `camera` `client` | 40–138 | `api/client/README.md` |
| ` ` Before the client is up | 139–150 | `api/client/README.md` |
| ` ` `keybindings` + addon hotkeys + names + key strings + example | 151–229 | `api/client/keybindings.md` |
| ` ` `profiling` + `frame` + `history` + `overhead` + when it is off | 230–327, 612–688 | `api/client/profiling/README.md` |
| ` ` The counters (memory, net, loader, render, textcache) | 328–432 | `api/client/profiling/counters.md` |
| ` ` `addons` + custom scopes + `widgets` + `passes` + `gl` | 433–611 | `api/client/profiling/attribution.md` |
| `api/fonts.md` two doors + built-ins + your own ttf + `derive` | 1–81 | `api/font.md` (D-7 deleted) |
| ` ` Draw with it: `font=`, `g:text`/`g:atext`, the `$font` tag, example | 82–133, 405–424 | `api/font.md` |
| ` ` Restyle a global surface (intro) | 134–148 | `api/ui/style/README.md` |
| ` ` Site keys (the surface catalogue) | 149–325 | `api/ui/style/surfaces.md` |
| ` ` Restyle ONE widget: `widget:skin{…}` + the conflict model | 326–404 | `api/ui/style/README.md` (D-8 deleted; closes the THIN row) |
| `api/render.md` intro + handle-only | 1–36 | `api/render/README.md` (D-9 deleted) |
| ` ` Drawing on screen (`g:image`/`g:aimage`) | 37–76 | `api/ui/drawing.md` — it is the `g` wrapper, not the world |
| ` ` Standing an image + sprite handle + billboard + clicks + anchoring | 77–219 | `api/render/sprites.md` |
| ` ` The model + the glTF subset + standing one + object handle + clicks | 220–336 | `api/render/models.md` (lighting written up, not one clause) |

### 5.2 `getting-started.md` (255 lines, tutorial + six guides + the runtime)

| Source section | Lines | Target |
|---|---|---|
| Your first addon | 6–42 | `getting-started.md` (steps 1–3) |
| The manifest | 43–60 | `getting-started.md` (the minimum) + `runtime.md` (every field) |
| The lifecycle | 61–82 | `getting-started.md` (the two events it uses) + `guides/events-and-timers.md` |
| The sandbox | 83–90 | `runtime.md` (what is available, the watchdog, the budget) |
| Reading & reacting | 91–108 | `guides/reading-the-world.md` + `guides/events-and-timers.md` |
| Saved variables | 109–123 | `guides/saved-data.md` |
| Custom UI & hotkeys | 124–137 | `guides/custom-ui.md` + `guides/hotkeys-and-commands.md` |
| Reading the client's own UI | 138–179 | `guides/debugging.md` (the inspector) + `api/ui/selectors.md` |
| Restyling the client | 180–209 | `guides/theming.md` |
| Files your addon ships | 210–227 | `runtime.md` (the layout) + `api/asset.md` (loading them) |
| Actions & permissions | 228–242 | `guides/actions-and-permissions.md` |
| Developing | 243–255 | `runtime.md` (the command table) + `guides/debugging.md` |

### 5.3 Everything else, page for page

| Source | Target | Note |
|---|---|---|
| `README.md` | `docs/addons/README.md` | rewrite; the at-a-glance table regenerated; the trailing deep-link paragraph deleted (nav belongs in tables) |
| `api/README.md` | `api/README.md` | regenerated: every leaf page |
| `api/char.md` | `api/char.md` + `api/study.md` | two namespaces, two pages |
| `api/console.md` | `api/log.md` + `api/slash.md` | same rule; both are tiny and that is fine |
| `api/audio.md` | `api/sound.md` | named for `hafen.sound`; the no-music boundary stays, its history goes (D-6) |
| `api/buffs.md` → `api/buff.md` · `api/meters.md` → `api/meter.md` · `api/actions.md` → `api/act.md` · `api/hooks.md` → `api/hook.md` | | renamed to the namespace |
| `api/asset.md` | `api/asset.md` | D-12 count fixed; "Why there are no URLs" / "Why engine resources are addressed" cut to one sentence each inside the sections they explain |
| `api/ghost.md` | `api/ghost.md` | `(V2)`…`(V6)` stripped from six headings (D-13) — every inbound anchor re-pointed in the same task |
| `api/map.md` | `api/map.md` | same, one heading |
| `api/markers.md` · `api/radar.md` | `api/map/markers.md` · `api/map/icons.md` | folded into the map directory with the rest of the database; each states its gating, since these verbs write and are **not** gated (D-3) |
| `api/menugrid.md` | `api/menugrid.md` | D-2 restated as a present-tense boundary, not a promise |
| `api/events.md` | `api/events.md` | D-10's obituary deleted, the present-tense half kept |
| `api/gob.md` `api/world.md` `api/player.md` `api/time.md` `api/party.md` `api/kin.md` `api/speed.md` `api/craft.md` `api/quests.md` `api/wounds.md` `api/fight.md` `api/actionbar.md` `api/conventions.md` `api/types.md` `api/http.md` `api/json.md` `api/timer.md` `api/store.md` | unchanged paths | template, voice and accuracy pass only |
| — | `docs/README.md` | new: the site root |
| — | `docs/addons/runtime.md` | new: closes the four THIN rows of the engine/dev tier (001.1, 005.1, 005.2, 005.3) |
| — | `docs/addons/examples.md` | new: closes G-1, G-2, G-3 and the `planner`/`profiler` THIN rows. One row per addon that ships, added by the feature that ships it |
| — | `docs/addons/guides/*` | new: the task tier |

## 6. Which task lands what

| Task | Lands |
|---|---|
| 001.3 group A | `gob` `world` `map` `markers` `radar` `player` `time` `char`+`study` `party` `buff` `meter` `kin` `speed` `craft` `quests` `wounds` `fight` `actionbar` `act` `menugrid` |
| 001.4 group B | `api/ui/**`, `api/client/**`, `api/font.md`, `api/render/**`, `ghost`, `asset`, `hook` — the whole anchor-rot surface |
| 001.5 group C | `conventions` `types` `events` `timer` `store` `json` `http` `log` `slash` `sound` |
| 001.6 learning path | `getting-started.md`, `guides/**`, **and `runtime.md` + `examples.md`** |
| 001.7 the close | `docs/README.md`, `docs/addons/README.md`, `api/README.md`, the full sweep, the matrix re-run |

**Amendment to `tasks.md`, for `/end` to record**: 001.6 gains `runtime.md` and `examples.md`.
Neither existed as a page when the tasks were written; both are demanded by the audit (the four
THIN engine/dev rows and the three GAPs), and both are reader-facing prose, which makes them the
learning path's work rather than the reference's.

## 7. Ownership after this lands

- Area `docs` owns the shape: this file, the style guide, the indexes, the tutorial, the guides,
  `runtime.md` and `examples.md`.
- Area `addons` keeps writing the reference content for each surface it ships, to this standard,
  and adds a row to `examples.md` when it ships an example addon.
- **The addons docs tier is `docs/addons/**`**, and both `specs/docs/AREA.md` and
  `specs/addons/AREA.md` say so (widened by 002.2, the two wordings 001 filed and left open). The
  older wording, `docs/addons/api/*.md`, describes neither half of the tree: the reference is nested
  (`api/ui/style/keys.md`, `api/map/grids.md`) and two reference-grade pages sit outside `api/`
  (`runtime.md`, `examples.md`).

## 8. Link discipline during the migration

Nearly every page moves or is retitled, and 695 internal links point at the current names. The
rule that keeps the tree link-clean at every task boundary (not only at the close):

**The task that moves or retitles a page re-points every link into it, in the same task.** A page
links the path that exists at the end of the task touching it — never a path a later task will
create. 001.4 therefore carries the largest share of the re-pointing, because `ui.md` and
`fonts.md` are the most-linked pages in the tree, and `api/README.md` and the landing page link
six `ui.md` anchors between them.
