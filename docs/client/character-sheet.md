# The character sheet: CharWnd and its tabs

> One window and six tabs: base attributes and FEP, study, skills and credos, the combat-school deck
> builder, the quest log and the wound list. Every one of them is **created hidden at login and left
> live**, so all of it reads without the player opening anything — the lifecycle and the tab parentage
> are on [gameui-windows.md](gameui-windows.md).

| Tab | Where |
|---|---|
| The window itself | `GameUI.chrwdg` (`CharWnd`, `place == "chr"`), public `exp` and `enc` — the character's experience points and its encumbrance figure, written by `CharWnd.uimsg`. The base attributes are not here: they are `Glob.getcattr` and `CAttr{base,comp}` ([state.md](state.md)) |
| FEP / food / hunger | `CharWnd.battr` (`BAttrWnd`) `feps` (`FoodMeter.cap`/`els`, `El.res`/`a`/`ev()`) + `glut` (`GlutMeter.glut`/`lbl`/`gmod`) — all public; **the one place absolute FEP/hunger numbers exist** |
| Study / curiosity | `CharWnd.sattr` (`SAttrWnd`) → `children(StudyInfo.class)` → the `StudyInfo.study` it holds a REFERENCE to (`children(GItem.class)`) + totals `texp`/`tw`/`tenc`, all three recomputed together by `StudyInfo.upd` out of `StudyInfo.tick`, so they are one UI-thread read and never disagree; per item `resutil.Curiosity` `exp`/`mw`/`enc`/`time` (public). `time` = **total** (no countdown). **The inventory is a sibling of the `StudyInfo`, not its child** — both hang off `SAttrWnd` ([gameui-windows.md](gameui-windows.md)) |
| Skills / credos / lore | `CharWnd.skill` (`SkillWnd`). Skills: `skg.csk`/`nsk` (`GridList.Group.items`) → `Skill.nm`/`res`/`cost`/`has` — `has` is the known/buyable flag, `nm` the server's token. Credos: `CredoGrid` `ccr`/`ncr` (`List<Credo>`), `Credo.nm`/`res`/`has`, plus the pursued one in `pcr` with `pcl`/`pclt` (level), `pcql`/`pcqlt` (quest), `pqid` (quest id) and `cost` (LP to begin one) — all public. Lore: `ExpGrid.seen.items` → `Experience.res`/`mtime`/`score`, which carries **no token** — the resource is its only identity. **Every one of these lists is replaced WHOLESALE** by its `csk`/`nsk`/`ccr`/`ncr`/`exps` uimsg, off-thread ⇒ copy before iterating, and never key anything on a record's Java identity; `pcr` is built as a **separate** `Credo` instance, so the pursued credo is not `==` its twin in `ccr`/`ncr` either |
| Combat schools — the deck BUILDER | `FightWnd` (`@RName("fmg")`), reached via the public `CharWnd.fight` field — created hidden at login but live, so it reads without opening the tab. `acts`  (every maneuver you know) is **replaced wholesale** by the `avail` uimsg, which nevertheless **carries an existing `Action` over** via `findact(resid)`  — so the Action object is stable and the LIST is not. `Action{res, private id, a (slottable), u (slotted)}`; `order[]`  is the fixed-length hotkey LAYOUT (`null` = empty slot), its entries reassigned by the `used` uimsg; labels `FightWnd.keys` (`"1".."5"`, `"⇧1".."⇧5"` — **non-ASCII**); scalars `maxact`/`nsave`/`usesave`. Every field read here is public; the handlers run on a loader thread under `synchronized(ui)`, so copy the list/array under it and resolve resource names outside |
| Combat schools — the saved schools | `FightWnd.nsave` (final) slots, named in the **private** `Text[] saves`. Each starts as the private `unused` text ("Unused save", italic), and the `saved` uimsg rewrites them all: a bitmask, then one argument per slot — a set bit takes that argument when it is a `String` and `"Saved school n"` (1-based) when it is not, a clear bit puts `unused` back. `usesave` (public) is the loaded slot, written by the `use` uimsg, which also selects it in `savelist` (`Savelist`, `sel` starting at `0`): **it is `0` until the first `use`**, so the check mark the list paints on `usesave` can sit on an unused slot. The names are read through `FightWnd.savename(n)`, the surface's one `addon:` reader: `null` for an unused slot (`saves[n] == unused`, the tab's own test), else `saves[n].text`. A rename is local: a double-click on the loaded, used slot opens a `ReadLine` whose `done` writes `saves[n]` on the UI thread, and only a later save carries it |
| Combat schools — what the tab sends | Three public methods, each one `wdgmsg` from the `FightWnd` itself: `load(n)` sends `load {n}`, `use(n)` sends `use {n}`, and `save(n)` composes `save {n, name?, layout…}` — the name only when `saves[n] != unused`, then one entry per `order[]` place, `null` for an empty one and `Action.id`, `Action.u` for a filled one, so its length varies with what is dealt. Every button pairs them: **Load** is `load(sel)` then `use(sel)` on `savelist.sel`; **Save** is `save(sel)` then `use(sel)`, refusing `sel < 0` through `GameUI.error`; a double-click on a used slot that is not `usesave` is `load` then `use` (on the loaded one it opens the rename). None of them writes `usesave`, `saves[]` or `order[]`: the tab only changes when the server's `use`, `saved` and `used` uimsgs answer |
| Quest log | `CharWnd.quest` → `QuestWnd` (`@RName("quests")`), created hidden at login but LIVE, so quests read without opening it. Two `QuestList`s: `cqst` (Current: pending/disabled) and `dqst` (Completed: done/failed), each with a public **final** `quests`/`get(id)` — `QuestList(sz, showcond)`, and only `cqst` is built with the `ndcond`/`ncond` objective counter shown. `Quest` = `{final id, res, title, done, mtime, ncond, ndcond}` and the `"quests"` uimsg — **one nested `OBJS` per quest** — **mutates it in place then MOVES it between the two lists** — a completion is the same object — while a record carrying **the id ALONE** removes it from both (the only way a quest leaves). The completion popup (`Quest.done`) fires on that MOVE, `cqst` → `dqst`, so it never fires for a quest that arrives already finished. Status ints `QST_PEND/DONE/FAIL/DISABLED`  are compile-time constants → inlined, so a status helper does **not** load `Quest` (whose `<clinit>` renders text and dies headless). Objectives exist for the SELECTED quest only: `Quest.Box` (`QuestWnd.quest`, swapped by `addchild`/`cdestroy`) holds `Condition[] cond`, and its `"conds"` uimsg calls `findcond(desc)` to **carry an existing `Condition` over**, rewriting only `done`/`status` — `Condition.desc` is `final`, so the server's key for an objective is its TEXT |
| Wounds | `CharWnd.wound` → `WoundWnd` (`@RName("wounds")`), also hidden-but-live. `wounds` is a `WoundList` whose public `List<Wound>` is FLAT but held in TREE order: `treesort` recurses from `parentid == -1` writing `Wound.level` (the indent depth), and it runs on the **UI thread** in `tick` while `decwound` adds/updates/removes off-thread → copy under `synchronized(ui)`. `Wound` = `{final id, final parentid, res, level}`; `decwound` looks the record up by id and mutates it, so a wound worsening is the same object. Name and **severity** come from resource-published `ItemInfo` that streams in a beat LATER — severity is the highest-`qprio` `QuickInfo.qstr()`, a content-defined string (usually a magnitude number, **not** seconds) — which is why the event is poll-driven, not uimsg-driven |

## Gotchas

- **Every list on this window is replaced WHOLESALE by its own uimsg, off-thread.** The skills, the
  credos, the lore, the maneuvers and the FEP entries all arrive as a new list rather than a delta, so a
  reader copies under `synchronized(ui)` before it iterates and **never** keys anything on a record's Java
  identity. Two of them go the other way and are worth knowing apart: `FightWnd`'s `avail` carries an
  existing `Action` over by `findact(resid)`, and `QuestWnd`'s `"quests"` mutates a `Quest` in place and
  moves it between the two lists — so there the object is stable and the list is not.
- **The tree order of the wound list is written by the UI thread and the list is written off it.**
  `WoundList.treesort` recurses in `tick`, writing `Wound.level`, while `decwound` adds, updates and
  removes from a loader thread. Copy under `synchronized(ui)` or read a half-sorted tree.
- **The name and severity of a wound arrive a beat after the wound.** They come from resource-published
  `ItemInfo`, so a reader that wants them polls; there is no message marking the moment.
- **`use` is two messages.** `FightWnd.use(n)` sends `use {n}`, and `Fightsess` sends `use {n, 1, mods, [pc]}` for a
  combat key. A message name is not unique across widgets, so anything keyed on the name alone — an outbound
  filter, a shape check — tells them apart by the sending widget's class.
- **A read of a public field here is not a core edit.** Every field named above is `public` and read
  straight but one: the saved-school names, behind `FightWnd.savename`, the surface's only `addon:`
  seam. The tags in `CharWnd` and `BAttrWnd` are font work and nothing else.

## What is not mapped

The layout of each tab, the `Tabs` machinery behind them (that is [ui-panels.md](ui-panels.md)), and the
wire format of the uimsgs beyond the fields named above.

## See also

- [GameUI's own windows](gameui-windows.md) — how `CharWnd` is placed, hidden and destroyed
- [state roots](state.md) — `Glob.getcattr`, the base attributes this window draws
- [services](services.md) — the HUD surfaces beside the sheet: the vitals bars, the speed selector, the belt
- [panels and tabs](ui-panels.md) — the `Tabs` model the six tabs hang in
