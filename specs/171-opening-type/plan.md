# 171 — Plan

## Approach

**One widget class, two Lua types.** Upstream draws the bar and a fight's openings with `haven.Buff` in a `Bufflist`, and a widget never moves between lists (`GameUI.buffs`; `Fightview.buffs`, each `Relation.buffs`), so its list decides its type.

- `LuaBuff` keeps the bar.
- A new `LuaOpening` takes the fight half that 170 put on `LuaBuff`.
- Both wrap the same widget class and share the readers (`res`, `name`, `amount`, `duration`, `number`), which stay package-private statics on `LuaBuff`. The sharing is code, never API.

**`LuaOpening`**, on the `LuaBuff` model:
- **Cache.** A per-addon intern cache, `Addon.openings`, keyed strongly on the `Buff` widget, with weak values, a `ReferenceQueue` and `retire(Buff)`.
- **Type.** A metatable through `Refusal.closedIndex("opening", …, "an opening is one icon the fight draws beside you or an opponent")`, with `__name` `Opening` and `tostring` `Opening(<res>)`.
- **Verbs**, each through `Args.only(a, 0, "opening:<verb>")`:
  - `res`, `name`, `amount`, `remaining`, `number`: the `LuaBuff` readers;
  - `widget`: `LuaWidget.of`;
  - `exists`: `LuaOpening.active`;
  - `opponent`: `opponentOf`;
  - `info`: `LuaOpening.snapshot`, which is `LuaBuff.snapshot`'s fields with `remaining` in place of `duration`.

  Inside an anonymous `VarArgFunction`, qualify `LuaBuff.name(b)`: a bare `name` there is LuaJ's `LibFunction.name` field.
- **What moves in from `LuaBuff`**, with its 170 comments: `rowUp`, `drawn`, `drawnLive`, `fought`, `opponentOf`, `Place`, `placeOf`, the `Door` source, `noKey`, `fightCollection` and `opponentCollection`.
  - `Place` loses its `fight` flag. A `Place` is now always the fight's, and its `opponent` is a gob id or `Place.YOURS` (`-1`).
  - `noKey` reads "an opening has no key, since two can share a resource and the server can replace one under a live opening: <door>:find(needle) is the search and <door>:list()[n] takes a position".
  - The doors mint with `LuaOpening.of`.
  - Each door still passes its own constant spelling to `LuaCollection.create`: `CharApi.FT + ":opening()"` and `"opponent:opening()"`.
- **`LuaOpening.active(Buff)`**, the fight predicate, under the view's monitor:
  - the buff is not `dest`;
  - its list's parent is a `Fightview`;
  - the list stands in the tree;
  - `rowUp`;
  - `drawn`;
  - the list holds the buff.

**`LuaBuff`**, the bar alone:
- **`active(Buff)`**: not `dest`, the list stands in the tree, and the list is its `GameUI`'s `buffs`. That is `bl.getparent(GameUI.class).buffs == bl`, which names no fight, plus `holds`.
- **Verbs**: `opponent` is cut. The `exists` comment says "on the bar".
- **Class comment**: back to the bar.
- **`collection`**: the one bar door. The `Door` class leaves with the fight doors, so the bar's source is one anonymous `LuaCollection.Source` again.
- **Package-private now**: `barOf`, `holds` and `live`, which `LuaOpening` calls.

**The adapter** (`CharApi.BuffsAdapter`) keeps one cache over both lists, since a widget is the same diff key whichever type it is handed as.
- `Seen.place` is `LuaOpening.Place`, and `null` for the bar.
- **`announce`**: `LuaBuff.active(b)` → place `null`; otherwise `LuaOpening.active(b)` → `LuaOpening.placeOf(b)`, skipping a `null` as 170 did; otherwise nothing.
- **`edge`**: a `null` place fires `fireBuff`, any other `fireOpening(…, place.opponent)`.
- **`removed(Fightsess)`** retires the entries whose place is non-null and which `LuaOpening.active` no longer counts.
- **`placed(Fightsess)`** iterates `LuaOpening.fought(fv)`.
- The diff key stays `LuaBuff.snapshot`, which reads every field either type exposes.

**Elsewhere:**
- **`AddonManager.fireOpening`** mints `LuaOpening.of(a, b)`. The `Buff` paragraph of `onWidgetDisposed` names `OpeningRemoved`: a relation's `del` is the path that needs it.
- **`Addon`**: a `LuaOpening.Cache openings` beside `buffs`. `dropInternedHandles`' `Buff` branch retires the widget from both caches.
- **The doors**: `CharApi.fight`'s `opening` verb returns `LuaOpening.fightCollection(owner, user)` (rename the local `fightBuffs` to `openings`). `LuaOpponent`'s `opening` verb returns `LuaOpening.opponentCollection`.

**The checkers:**
- **`tools/docverbs.py`**: `RECEIVERS` gains `"opening": "opening"`. `RETURNS` drops `("buff", "opponent")` and gains `("opening", "widget"): "widget"` and `("opening", "opponent"): "opponent"`.
- **`tools/refusalverbs.py`**: `RECEIVERS` gains `"opening": "opening"`, and both opening doors in `MEMBER` map to `"opening"`.

**The pages.** Write them against the separation rule in spec criterion 5:
- `fight.md` calls the fight's icons openings, and its example handler's parameter is `opening`.
- The `Opening*` rows and the rule that says whose move from `event/bus/character.md` to `event/bus/fight.md`, with `Opening` as the payload.
- `types/fight.md` gains an `Opening` section.
- `buff.md` loses every fight line.

## Files to create/modify

| Kind | Files |
|---|---|
| Java, new | `src/io/brodgar/addon/LuaOpening.java` |
| Java, modified | `LuaBuff.java`, `CharApi.java` (`BuffsAdapter`, `fight()`'s `opening`), `LuaOpponent.java` (`opening`), `AddonManager.java` (`fireOpening`, the `onWidgetDisposed` comment), `Addon.java` (`openings`, `dropInternedHandles`) |
| Tools | `tools/docverbs.py`, `tools/refusalverbs.py` |
| Pages under `docs/addons/api/` | `buff.md`, `fight.md`, `types/fight.md`, `types/README.md`, `event/bus/character.md`, `event/bus/fight.md`, `event/bus/README.md`, `README.md`, `references.md`, `shapes.md` |
| Other pages | `docs/addons/manifest.md` (the "What needs `1.3`" row) |
| Suite | `addons/171-opening-type.1/` |

No `docs/client/` page: everything this feature reads in upstream, `combat.md` already maps.

## Risks & gotchas

- **The removal drain runs before the disposal drain.** `AddonManager.drainWidgetDeaths` calls `drainRemovedWidgets` before `drainDisposedWidgets`. The second reaches `Addon.dropInternedHandles`, which now retires from `openings` too. That order is what keeps the `OpeningRemoved` payload `==` the `OpeningAdded` one when a relation's `del` destroys its list: `Relation.remove()` → `Bufflist.destroy()`, and the buffs under it only reach `onWidgetDisposed`.
- **No widget is in both caches** while every mint follows its list: the bar's door and `fireBuff` mint `LuaBuff`, the fight's doors and `fireOpening` mint `LuaOpening`, and nothing else mints either.
- **`Widget.children(Class)` recurses.** Never call it on the `Fightview`. `live(bl)` calls it on one `Bufflist`, whose buffs are its direct children.
- **The monitor.** Every read of `lsrel`, a relation's lists and the `GameUI`'s children for `rowUp` is under `LuaWidget.monitor(fv)`. The loader thread writes them under that same `UI` monitor.
- **`refusalverbs` needs a constant spelling** in each `LuaCollection.create`, or it loses the door's vocabulary.
- **A suite's verbs are checked by nothing.** `docverbs` never reads `addons/`, so grep `LuaOpening`'s `m.set` list before the suite asserts a verb.
- **The build.** Run a true compile check: `rm -rf build/classes`, then `ant hafen-client`. Then `ant bin`, because the client runs `bin/hafen.jar`. Then a full client restart.

## Discarded alternatives

- **Rewrite only the pages, keeping one `Buff` type.** While `opening()` hands a `Buff` that has `:opponent()`, a page silent about the other subject omits what the API is.
- **One `LuaBuff` class with a second metatable chosen by the list.** The class is the type: a separate class keeps each object's `toString`, refusal blurb and closed vocabulary its own, and it is what `refusalverbs` reads per entity.
- **A discriminator on `Buff` (`buff:kind()`, or `buff:opponent()` kept for the bar as `nil`).** One type would still carry a verb that means nothing on the bar, which is exactly what the maintainer ruled out.
- **Drop `:widget()` from `Opening`.** Every read the fight's buffs answered in `1.3` stays on the opening, so an addon written against `1.3` makes the same calls and gets the same answers.
- **`opening:info()` with `duration`, as `buff:info()` has.** That field name is a misnomer the `Buff` shape keeps only because it shipped. A new shape names each field after its read.
- **API edition `1.4`.** Nothing an addon calls is new to a `1.3` client; `1.4` would only refuse addons that run on v11.1-beta.
- **A `MOVED` row for `buff:opponent()`.** The maintainer ruled out any announcement.
- **Keep the opening events on `event/bus/character.md`, beside the `Buff` keys.** A subject's events live on its own page, and the fight's are on `event/bus/fight.md`.
- **Rename upstream's terms in `docs/client/combat.md`.** That page maps `haven`, where an opening's widget is a `Buff` in a `Bufflist`.
