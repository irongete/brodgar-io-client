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
| Combat schools — the deck BUILDER | `FightWnd` (`@RName("fmg")`), reached via the public `CharWnd.fight` field — created hidden at login but live, so it reads without opening the tab. `acts`  (every maneuver you know) is **replaced wholesale** by the `avail` uimsg, which nevertheless **carries an existing `Action` over** via `findact(resid)`  — so the Action object is stable and the LIST is not. `Action{res, private id, a (slottable), u (slotted)}`; `order[]`  is the fixed-length hotkey LAYOUT (`null` = empty slot), its entries reassigned by the `used` uimsg; labels `FightWnd.keys` (`"1".."5"`, `"⇧1".."⇧5"` — **non-ASCII**); scalars `maxact`/`nsave`/`usesave`. Every field read is public → zero `haven` edit; the handlers run on a loader thread under `synchronized(ui)`, so copy the list/array under it and resolve resource names outside |
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
- **A read of a public field here is not a core edit.** Every field named above is `public` and read
  straight, which is why this whole surface carries no `addon:` seam: the tags in `CharWnd` and
  `BAttrWnd` are font work and nothing else.

## What is not mapped

The layout of each tab, the `Tabs` machinery behind them (that is [ui-panels.md](ui-panels.md)), the
wire format of the uimsgs beyond the fields named above, and the saved-school **names**, which live in
`FightWnd`'s private `saves[]`.

## See also

- [GameUI's own windows](gameui-windows.md) — how `CharWnd` is placed, hidden and destroyed
- [state roots](state.md) — `Glob.getcattr`, the base attributes this window draws
- [services](services.md) — the HUD surfaces beside the sheet: the vitals bars, the speed selector, the belt
- [panels and tabs](ui-panels.md) — the `Tabs` model the six tabs hang in
