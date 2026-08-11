# 055.1 — The census

> The surface → page map for the seven features, **both directions**, plus the prose-name greps no symbol
> sweep can do. **Edits no docs page.** Its output is §5, the worklist `055.2` and `055.3` work from; a
> page not on it is touched by `055.4`'s tree-wide sweep alone.

## Method & ground truth

- **Ground truth is `src/io/brodgar/addon/`**, nothing else. The registration oracle is
  `grep 'set("<name>"' src/io/brodgar/addon/` (001.2); the section oracle is
  `Section.install|mount|object(hafen, "<name>"`; a plain section's verb roster is its own receiver check,
  `Section.self(self, "<section>", "<verb>")`, which every verb of every plain section calls.
- A collection's verbs are structural, not written per site: `list`/`count`/`find` always, plus `get` /
  `add` / `remove` where its `Source` answers `addressable()` / `creatable()` / `destroyable()`
  (`LuaCollection.methods`). So a collection page is checked against the flags, not against a grep.
- Reachability is a **traversal**, never read off an index (001.7). The link graph, the anchor slugger and
  the BFS are one throwaway script, run from the tree and thrown away — no tooling ships (`AREA.md`).
- Both directions were **falsified**. A checker that reports zero is worth nothing until a planted break is
  caught and the restored corpus reads zero again (001.1).

## 1. Forward — every registered surface has an owning page

**31 sections** are mounted on `hafen`. **31 have an owning `api/` page**; **0 offenders**.

| Section | Owner | Section | Owner | Section | Owner |
|---|---|---|---|---|---|
| `act` | `api/act.md` | `fight` | `api/fight.md` | `slash` | `api/slash.md` |
| `actionbar` | `api/actionbar.md` | `font` | `api/font.md` | `sound` | `api/sound.md` |
| `asset` | `api/asset.md` | `http` | `api/http.md` | `speed` | `api/speed.md` |
| `buff` | `api/buff.md` | `json` | `api/json.md` | `store` | `api/store.md` |
| `char` | `api/char.md` | `kin` | `api/kin.md` | `study` | `api/study.md` |
| `client` | `api/client/**` (5) | `log` | `api/log.md` | `time` | `api/time.md` |
| `craft` | `api/craft.md` | `map` | `api/map/**` (6) | `timer` | `api/timer.md` |
| `event` | `api/event.md` | `menugrid` | `api/menugrid.md` | `ui` | `api/ui/**` (18) |
| `meter` | `api/meter.md` | `party` | `api/party.md` | `vr` | `api/vr/**` (6) |
| `player` | `api/player.md` | `quest` | `api/quest.md` | `world` | `api/world.md` |
| `wound` | `api/wound.md` | | | | |

**Verb level.** Every verb of every plain section is named inside its owner's subtree — **0 missing**, over
the rosters `Section.self` yields (`ui` 31, `act` 10, `world` 10, `time` 6, `fight` 4, `event` 3, `vr` 3,
`speed` 3, `char` 3, `client` 2, `http` 2, `json` 2, `store` 2, `craft` 1, `log` 1, `slash` 1, `study` 1).
A section's **sub-collections** carry no receiver check of their own, so they were taken from their
registration sites and checked separately — `char`'s `attr` `skill` `credo` `experience`, `study`'s `slot`,
`map`'s `segment` `grid` `marker` `icon` `overlay`, `vr`'s `ghost` `sprite` `object` `widget`. **0 missing.**

**Name level, tree-wide.** **486** distinct registered Lua names; **485** are named somewhere under
`docs/`. The one that is not is `module` (`Sandbox.java`) — a Lua stdlib name on the sandbox allowlist,
not a `hafen.*` surface. **N/A**, not a GAP.

**The seven features' own surfaces.** `hafen.vr()`'s four collections each own a leaf
(`ghost`→`vr/ghosts.md`, `sprite`→`vr/sprites.md`, `object`→`vr/models.md`, `widget`→`vr/widgets.md`); the
hub's collection table claims `:add` `:list` `:count` `:find` `:remove` and **no `:get`**, matching
`VrApi`'s collections, which are `creatable`+`destroyable` and not `addressable`. All **26** `BUS_KEYS` are
on `api/event.md`; `045.2`'s seventh pull-only counter, `hafen.client():profiling():entities()`, is on
`client/profiling/counters.md` and in the hub table; `046`'s `gob:scale` is on `api/gob.md`.

## 2. The traversal

`docs/` is **80 pages / 10,037 lines**. From `docs/addons/README.md`, over the real link graph:
**1 at depth 0 · 40 at depth 1 · 38 at depth 2 · 0 deeper**. The only page at no depth is
`docs/README.md`, the site root above the landing page, which nothing links down to — correct (D-242).
From `docs/addons/api/README.md`, all **66** `api/` pages sit at depth ≤ 1: **0 unreachable**.

**Links: 1,469 internal, 0 broken.** **14 leave `docs/`**, all from `examples.md` to
`addons/<id>/main.lua` — the one exception (D-239), up from 13 as `cupboard` joined the list.

**Falsified both ways.** Planted, one at a time, on an in-memory copy: a bad path
(`world.md` → `wolrd.md`), a bad cross-page anchor (`world.md#no-such-heading`) and a bad same-page anchor
(`#no-such-local`). Each was caught, and **exactly one** finding each time — the over-reporting failure mode
(001.1) does not reproduce. The traversal was falsified too: cutting all **7** inbound edges to
`api/vr/gizmo.md` moved it to unreachable. Restored corpus: **1,469 links, 0 broken, 0 unreachable but the
root**.

## 3. Backward — no page names something absent from `src/`

| Query | Distinct | Offenders |
|---|---|---|
| `hafen.<name>` tokens | 32 | **1** — `hafen.music` (`api/sound.md`), the stated absence. **N/A** |
| colon verbs used in docs | 304 | **8** raw; **0** real (below) |
| event keys in `:on("…")` | 29 | **0** |
| quoted string literals in backticks | 75 | **10** raw; **0** real (game resource names, example paths, and `"CheckBox"`/`"ICheckBox"`, which are `haven` class names) |
| type names on `api/types.md` | 22 sections | **0** — see below |

The eight raw verb offenders resolve to nothing wrong **except two**, and those two are §5's W1/W2:

- `:setMode` `:mode` `:isDragging` `:detach` (`vr/gizmo.md`) — the page says outright that the gizmo is a
  bundled Lua library and not a `hafen.*` function; all four exist in `addons/planner/gizmo.lua`. **N/A**.
- `:format` `:rep` — Lua string methods on a string literal (`("…"):format(…)`). **N/A**.
- `:sender` (`api/event.md`) — registered, but under a computed key
  (`final String noun = (shape == ACTION) ? "sender" : "target"; m.set(noun, …)`), so the literal grep
  misses it. **N/A**, and a note for any later run of the same oracle.
- `:move` — **registered nowhere.** Two live uses. **WRONG**, §5.

**Type names.** `KinEntry`, `ActionbarSlot`, `IconCategory`, `GobInfo`, `CraftSpec`, `Tile` and `Color` have
no `__name` in the engine, and that is correct: each is the docs' name for a **plain table** an `:info()`
hands back, which `types.md` states in the same breath ("The roster and `KinChanged` hand you live `Kin`
objects, not this table"). Every name that *is* an engine type (`Gob`, `Position`, `Item`, `Buff`, `Meter`,
`Quest`, `Condition`, `Wound`, `Craft`, `Maneuver`, `DeckCard`, `FightSummary`, `Pagina`, `Marker`, `Attr`,
`Food`, `StudySlot`, `Skill`, `Credo`, `Experience`, `PartyMember`, `Slot`) matches its `__name`. **0
offenders.**

## 4. The greps no symbol sweep does

The stale link is the one that still resolves (003.1), so the **prose names** of what `043`/`044` moved were
grepped across the whole tier, pages on no feature's file list included.

| Grep | Hits | Verdict |
|---|---|---|
| `hafen.render` · `hafen.ghost` · "render namespace" | **0** | clean |
| link text naming ghost / sprite / model / glTF / gizmo | **49** | every one points into `api/vr/**`. **0 mis-points** |
| "gob overlay" in prose | 2 | `player.md:29` → `gob.md#overlays` (003.1's fix, still right); `examples.md:31`, no link |
| §7's 28-entry retired list, `grep -rnF` over `docs/` | **0** | every entry at zero today |

The 042 grep (`poll` · `every/per frame` · `a frame late` · `next frame`) returns **40** hits. Nearly all are
correct present tense — `Update` genuinely fires every frame, `time.md` genuinely has no change event. One
reads as prose the mechanics changed under, and it is §5's T2.

## 5. The worklist

| # | Finding | Verdict | Task |
|---|---|---|---|
| W1 | `api/ui/widget.md:261` — `ghost:move(hafen.world():snapPlace(w.x, w.y, ev:shift()).x, w.y)`. Three defects: `:move` is registered nowhere (the entity verb is `e:position(p [, a])`); `screenToWorld`'s `fn(p)` is handed a **Position**, so `w.x`/`w.y` are not fields; and `snapPlace(p, fine)` takes a Position, not `(x, y, fine)`. **This is the fourth page holding the pre-045 model**, and it was on no feature's file list | WRONG | 055.3 |
| W2 | `api/world.md:217` — `ghost:move(s:x(), s:y())` in the `snapPlace` example. `s` is correctly a Position; `:move` still does not exist, and the Position belongs in whole | WRONG | 055.3 |
| T1 | `hafen.vr():pointer(key, x, y [, a])` is a **section** verb (044.4) and is documented only on the leaf `vr/widgets.md:110`. `vr/README.md`'s "The whole section at once" table carries `:list` and `:visible` and stops there | THIN | 055.3 |
| T2 | `api/ui/items.md:77-79` states the container subscription in poll vocabulary — "A container nobody subscribed to is never polled", "widget out of the poll entirely". `UiApi` still owns a container-subscription poll, so the sentence may be exactly right; it is also the shape 042 changed the mechanics under, and it is the one 042-risk hit the grep found | THIN | 055.2 |
| T3 | `vr/widgets.md` names `:alpha` once, inside the shared-vocabulary line, and states no transparency rule of its own for a standing widget. 044's rule wants stating as what it **is** | THIN | 055.3 |
| C1 | `api/conventions.md`'s arity-as-verb rows — every "`f()` answers X, `f(x)` answers Y" claim. Readable off `Args.java` rather than inferred; the census did not open it | — | 055.2 |
| C2 | `hafen.event()`'s three verbs, `sub:off()`, the closed key sets (`BUS_KEYS` 26, `INPUT_KEYS` 4, `TREE_KEYS` 3), the open `action`/`message` names, `ev:preventDefault()`, and the handler return value (`ui/widget.md:122` states it is never read) | — | 055.2 |
| C3 | `043`'s `:facing(mode)` (`"fixed"`/`"camera"`/`"screen"`, `VrApi.FIXED/CAMERA/SCREEN`) and whether `"camera"` still raises anywhere a page says it does | — | 055.3 |
| C4 | `046`'s validation, which **differs** from its `vr` siblings on purpose: `gob.md:91` says `0` and a negative "both raise naming the rule", where the siblings clamp. A page that harmonises them is wrong | — | 055.3 |
| N1 | `hafen.music` · `vr/gizmo.md`'s library verbs · `:format`/`:rep` · `:sender` · `module` · the `:info()` table-shape names | N/A | — |
| OK | §1's two zero counts · §2's traversal and 0 broken · §3's four zero rows · §4's four grep rows | OK | — |

**Filed to `055.4`, not accuracy work.** Three no-history candidates the prose greps surfaced:
`api/act.md:34` "this is where confusing them used to walk you somewhere wrong" (the spec already names it);
`api/ui/widget.md:268` "to drag something along the ground, **exactly as before**"; and `api/gob.md:222`
"where the target used to stand", which reads as ordinary English about a gob and is probably not one.

**And one for the §7 derivation, so it is not learnt the hard way.** `hafen.ui():root()` and
`hafen.ui():all(sel)` are both **live** — `UiApi` registers them and six pages use `:root()` — while the
retired names are the *dotted* `hafen.ui.root` and `hafen.ui.all`. Both grep zero over `docs/` today at the
dotted spelling, so the guards hold; a derivation that admits the bare verb instead would read non-zero on a
healthy tree, which is `:offset(`'s failure exactly (004.2).

## 6. What the census deliberately did not decide

Every `C` row above. The census's oracle is the **registration** — it proves a name exists and has an owner,
and it says nothing about what a call **refuses** (003.1: all nine corrections came from the second oracle).
`LuaError` strings and `spec.get("…")` reads over the owning files are `055.2`'s and `055.3`'s reading, and
no verdict here should be read as clearing a contract.

Nothing found is an engine or API defect, so **nothing is filed to area `addons`** by this task.
