# 170 — The live fight: plan

## Approach

The fight in progress lives in two upstream widgets, and every read here copies what it needs out of one of them under that tree's monitor.

- **The combat view**, `GameUI.fv`, a `Fightview`. It holds:
  - the relations `lsrel` (one `Fightview.Relation` per opponent) and the target `current`;
  - per relation: `gst`, `ip`, `oip`, `lastact`/`lastuse`, and the `buffs`/`relbuffs` lists;
  - your side: `buffs`, `lastact`/`lastuse`, and the global cooldown `atkcs`/`atkct`.
- **The combat row**, a `Fightsess`. The server hangs it as a direct child of the `GameUI`, with no field, for the length of a fight. It holds `actions[]` (`Action{res, cs, ct}`).

`docs/client/combat.md` (written in 170.1) is the map of both, of `GiveButton`, and of every message.

The surface follows the addon API's grammar, and the precedents it copies are named where they matter:

- **Opponents.** `session:fight():opponent()` is a collection addressed by gob id (the party's shape). `:current()` is the target, read-only; `:set(opponent)` switches it under `fight.set`. Both are `session:speed()`'s shape for a selection the server confirms.
- **The combat row.** `session:fight():action()` is ten `CombatAction`s, one per combat key: the action bar's shape, a fixed array with `:empty()`. `action:use(mods, position)` is `slot:use(mods)`'s shape, plus an optional place.
- **Pursue and give.** `session:fight():pursue(opponent)` and `session:fight():give(opponent, button)` are actions of the character on the object it acts on: `session:world():click(gob, button, mods)`'s shape.
- **Pairs.** `opponent:ip()` and `opponent:give()` answer `{mine, theirs}`, because a verb on the opponent must not mean "yours".
- **Last manoeuvre.** `opponent:last()` and `session:fight():last()` are resource names, like every `:res()`.
- **Buffs.** A fight's buffs are the existing `Buff` object, behind two new doors (`session:fight():opening()`, `opponent:opening()`) and one inverse read (`buff:opponent()`). The bar keeps `BuffAdded`/`BuffChanged`/`BuffRemoved`; a fight's buffs fire `OpeningAdded`/`OpeningChanged`/`OpeningRemoved`, handing the `Buff`, then the opponent it is drawn beside (`nil` for yours), then the session. One object type, two event families, one per collection of doors.

**Events.** Two new `TreeAdapter`s in `CharApi`, both `SessionAdapter`s that read that login's HUD by account, and five new bus keys fired through `AddonManager`.

- **`FightAdapter`** is uimsg-driven on `Fightview`'s `new`/`del`/`upd`/`cur`/`used`/`ruse`.
  - It diffs the relations once per frame: `OpponentAdded`/`Removed`/`Changed`/`Selected`.
  - It diffs each side's `lastuse` stamp for `ManeuverUsed`. Every use writes a fresh `Utils.rtime()` stamp, and a side cannot use twice inside one frame (its cooldown), so the diff sees every use without touching haven.
- **`CombatActionAdapter`** is uimsg-driven on `Fightsess`'s `act`. The row's arrival is the entry seam and its death the removal seam. It fires `CombatActionChanged`.

**Writes.** Every write goes through `Wire.send`, gated first by its own key: `fight.use`, `fight.set`, `fight.pursue`, `fight.give`, and the group `fight.*`. The "use" and "give" messages each get a `Wire.SHAPES` row. `action:use` sends `use` and `rel` inside one dispatch, under the tree's monitor. The connection numbers every message in order, so they arrive in that order with nothing between them.

**The buff fixes.**
- `AddonManager.onWidgetDisposed` also queues a dying `Buff` descendant for removal. A relation's `del` destroys its buff lists, and no buff under them runs `remove()`. The removal drain runs before the disposal drain retires the handle, so `BuffRemoved` fires once, with the `BuffAdded` object.
- `LuaBuff.active` also requires its list to stand in the tree, and to be one the client draws: the bar, `Fightview.buffs`, or a relation's `buffs`, never its `relbuffs`.

**Rulings, as fact.**
- The maintainer (2026-09-30): nobody has written an addon, so the cuts ship without alias or announcement:
  - `session:fight():target()` is gone, and a call raises the section's own "has no verb" listing `opponent`;
  - `relbuffs` fire nothing;
  - every `Buff` and `Opponent` verb now refuses a surplus argument;
  - a fight's buffs leave the `Buff` keys for `OpeningAdded`/`OpeningChanged`/`OpeningRemoved` (170.1), which name the opponent as their second argument.
- The API edition becomes 1.3, because a release adding verbs and keys moves it. The generation stays 1, since moving it would only turn every addon "outdated".
- Nothing in `src/haven/` changes.

## Files to create/modify

| Task | Files |
|---|---|
| 170.1 | `src/io/brodgar/addon/LuaBuff.java`, `LuaOpponent.java`, `CharApi.java`, `AddonManager.java`, `ApiVersion.java`; `tools/docverbs.py`, `tools/refusalverbs.py`; `docs/addons/api/buff.md`, `event/bus/character.md`, `fight.md`, `references.md`; `docs/addons/manifest.md`; **new** `docs/client/combat.md`; `docs/client/services.md`, `docs/client/README.md`; suite `addons/170-live-fight.1/` |
| 170.2 | `LuaOpponent.java` (whole), `CharApi.java` (the `FightAdapter`, `opponent()`, `target()` gone), `AddonManager.java`; `tools/docverbs.py`, `tools/refusalverbs.py`; `docs/addons/api/fight.md` (whole), **new** `event/bus/fight.md`, `event/bus/README.md`, `types/fight.md`, `types/README.md`, `README.md`, `session.md`, `gob.md`, `party.md`, `shapes.md`, `conventions.md`, `buff.md`; `docs/addons/manifest.md`; suite `addons/170-live-fight.2/` |
| 170.3 | **new** `src/io/brodgar/addon/LuaCombatAction.java`; `Addon.java`, `LuaOpponent.java`, `CharApi.java` (`FightAdapter` gains `ManeuverUsed`, new `CombatActionAdapter`, `action()`/`cooldown()`/`last()`), `AddonManager.java`; `tools/docverbs.py`, `tools/refusalverbs.py`; `docs/addons/api/fight.md`, `event/bus/fight.md`, `event/bus/README.md`, `README.md`, `types/fight.md`, `types/README.md`, `conventions.md`, `shapes.md`, `references.md`; `docs/addons/manifest.md`; suite `addons/170-live-fight.3/` |
| 170.4 | `LuaCombatAction.java` (`use`), `Permission.java` (`FIGHT_USE`), `Wire.java` (the "use" row); `docs/addons/api/fight.md` (Write), `guides/permissions.md`, `README.md`; `docs/addons/manifest.md`; suite `addons/170-live-fight.4/` |
| 170.5 | `Permission.java` (three keys), `Wire.java` (the "give" row), `LuaOpponent.java` (`set`, `target`, `send`), `CharApi.java` (`pursue`, `give`); `docs/addons/api/fight.md`, `guides/permissions.md`, `references.md`, `README.md`; `docs/addons/manifest.md`; suite `addons/170-live-fight.5/` |

## Risks & gotchas (the classes and members read)

- **`GameUI.fv` is never cleared.** `GameUI.cdestroy` does not know the field. `LuaOpponent.view(user)` answers the view only while `fv.hasparent(fv.ui.root)`.
- **`Fightsess` has no field on `GameUI`.** `GameUI.addchild` `place == "fsess"` is `add(child, Coord.z)`, so `LuaCombatAction.row(user)` is `gameui.getchild(Fightsess.class)` under the HUD's monitor. `Widget.getchild` walks direct children only.
- **Never touch `Fightsess.kb_acts` from the bridge.** Reading a static field runs `Fightsess`'s static initializer: `Resource.loadtex`, and a `Resource.local().loadwait` of `gfx/hud/combat/trgtarw`, on whatever thread asked first. `LuaCombatAction.SLOTS` is the literal 10 for that reason. `instanceof Fightsess` and instance-field reads initialize nothing.
- **Whose number is whose.** `Relation.ip` is YOURS and `Relation.oip` theirs (`Fightsess.draw` paints `ip` left and `oip` right), while `Relation.lastact` is theirs and `Fightview.lastact` yours.
- **`GiveButton.draw` bits.** Bit 1 is the left half (`ol` open, `sl` shut) and bit 2 the right half (`or`, `sr`). `LuaOpponent.MINE = 1` and `THEIRS = 2` follow the fight view's you-left/them-right layout.
  - This is confirmed in game by 170.5's give check and by reading the halves. If the halves turn out the other way round, swap the two constants; nothing else changes.
- **`used` reaches both widgets.** The server sends it to `Fightview` and to `Fightsess` (where `Fightsess.uimsg` ignores it), so every `interested` tests the widget class.
- **`getrel` throws.** `Fightview.getrel` throws `Notfound` for an unknown gob id, so a `del`, `upd` or `ruse` for an id not in `lsrel` aborts before the tap sees it.
- **The combat view outlives the fight.** The server destroys the `Fightsess` with the fight (the only painter of the buff lists), but the `Fightview` stays in the tree with the buffs of yours not yet expired, unpainted. A list under the view counts only while a `Fightsess` stands under the `GameUI`, and the row's own entry and removal announce and retire what that moves (170.1).
- **A relation's buffs are never removed.** `Relation.remove()` calls `buffs.destroy()` and `relbuffs.destroy()`. `Widget.destroy` is `remove()` on the list and `rdispose()` on the children, so every buff stays linked under a detached list and never runs `remove()`.
  - Only `AddonManager.onWidgetDisposed` sees it, which is why the fix lives there.
  - `hasparent(ui.root)` is the one test that goes false.
- **`Widget.children(Class)` recurses.** Never call it on the `Fightview`, where it would reach every relation's lists. `LuaBuff.live(bl)` calls it on one `Bufflist`, whose buffs are direct children.
- **The removal drain runs first.** `AddonManager.drainWidgetDeaths` runs `drainRemovedWidgets` before `drainDisposedWidgets`, which calls `Addon.dropInternedHandles` → `LuaBuff.Cache.retire`. That order is what keeps the `BuffRemoved` payload the `BuffAdded` object.
  - `Buff.reqdestroy`'s own tap announces a server-removed buff at its `dest`. `CharApi.BuffsAdapter.removed` fires only for a cached buff, so the later unlink fires nothing.
- **Monitors and threads.**
  - `lsrel`, `current`, a relation's ints, `lastact`/`lastuse`, `atkcs`/`atkct` and `Fightsess.actions` are written by `uimsg` on a loader thread under `synchronized(ui)`, and "Switch targets" reorders `lsrel` on the UI thread.
  - Every read copies inside `LuaWidget.monitor(fv)` (or `(fs)`, `(g)`) and mints handles outside it.
  - `LuaWidget.monitor` refuses a second tree, so reading another character's fight from inside one tree's callback is refused, as every cross-tree read already is.
- **`Wire.SHAPES` is keyed by message name alone.**
  - The "use" row (`mods` at index 2) is also the action menu's message name, but `pagina:use` sends null args and never reaches a row.
  - Feature 171's school writes send through the names "load"/"save".
  - `widget:send` (`Wire.ESCAPE`) skips every row.
- **`LuaCollection.create`'s spelling must be a constant**, or `tools/refusalverbs.py` loses the door's vocabulary. `LuaBuff`'s three doors each pass their own constant, and share one `Door` source and one `noKey(door)` message helper.
- **`Section.object`'s unknown verb raises** `session:fight() has no verb 'target' — it answers …` with the section's verb list. That is the whole of `target()`'s retirement.
- **The build.**
  - An incremental build hides `LuaOpponent.target` leaving (170.2): run `rm -rf build/classes` before the compile check.
  - The client runs `bin/hafen.jar`: the maintainer restarts with `ant run`, which packages.
  - `luac -p` writes `luac.out` into the working directory: run it from a scratch directory, never the repo root.
- **In-game facts the suites settle:**
  - which give half is yours (170.5);
  - that a tap (`use` then `rel` in one dispatch) fires the move (170.4);
  - that `nact` is 10 and the row exists only during a fight (170.3);
  - that a relation ends by `del` (170.1, 170.2).

  If the tap does not fire, the fallback is decided: `rel` moves to the next tick through a queue on the session's `SessionState`, drained in `AddonManager.tick(UI)`.

## Discarded alternatives

- **Keep `session:fight():target()`** beside `opponent():current()`: two doors to one member. Nobody has written an addon, and the maintainer ruled the cut with no alias.
- **A `Refusal.MOVED` row or a changelog line for the cut**: nothing names the old spelling any more, the section's own refusal lists `opponent`, and the maintainer ruled out any announcement.
- **Move the API generation to 2.0** for the cut: it would turn every existing addon "outdated", the maintainer's own included, which is the largest break there is.
- **`opponent():current(o)` as the write**: a selection the server has to confirm is an action, not a property write. `session:speed():set()` is the shape.
- **The key `fight.target`**: keys are `<section>.<verb>`, and the verb is `set`.
- **`card:use()` on the deck's `DeckCard`**: the deck is the schools tab's layout, local until saved, while the wire's `use n` addresses the fight's row.
- **A row of any length, with `:exists()`**: a slot no combat key reaches is one no player can use. The places are the ten keys, and `:empty()` says what each holds, as on the action bar.
- **`Fightsess.kb_acts.length` for the row's size**: reading it runs `Fightsess`'s static initializer, with textures and a `loadwait`, off the thread that builds the widget.
- **`opponent:ip()` as yours plus `opponent:oip()` as theirs**: a verb on the opponent reads as the opponent's own. The pair names both sides.
- **`opponent:give(boolean)` as the write, or `opponent:giveState()` as a number**: the first collides with the read, the second loses the meaning of the bits.
- **`opponent:pursue()` and `opponent:give()` on the opponent**: the character acts and the opponent is acted on, the `session:world():click(gob)` shape. It keeps the Opponent a read handle, and the give read keeps its name.
- **A per-message uimsg tap for `ManeuverUsed`**, passing the applied arguments through a core edit to `UI.java`: the `lastuse` stamp diff sees every use without touching haven.
- **`CombatActionChanged` on a cooldown starting (`acool`)**: the action bar fires nothing for its cooldown meter, and the moment of a use is already `ManeuverUsed`.
- **One `Buff*` family for the bar and the fight**: the events would be the union of three doors, and the discriminator falls short: `buff:opponent()` is `nil` for yours and for the bar alike, and it is `nil` at the removal, when the opponent's list has died with its relation. The maintainer ruled the split on 2026-09-30 (nobody having written an addon); the event carries the opponent, recorded when the buff was announced.
- **Keep announcing `relbuffs`**: an event hands only what some door lists, and nothing paints those.
- **Keep the published `Buff` and `Opponent` verbs silently dropping a surplus argument**: `conventions.md` promises a refusal, and the maintainer ruled breaks are fine.
- **Build each buff door's collection over a spelling passed in**: `tools/refusalverbs.py` can no longer see which collection it is. Each door passes its own constant to `LuaCollection.create`.
- **Send `rel` on the next tick through a queue**: the connection numbers every message, and one `Wire.send` keeps the pair adjacent. The queue is only the fallback if the tap does not fire in game.
- **A verb for holding a combat key**: nothing in the client makes a hold's length matter.
- **A list of `relbuffs`; `Fightview.blk`/`batk`/`iatk`; `Fightsess.use`/`useb` markers; the portrait's `click`**: the client draws or reads none of them, so no page could say what they mean.
- **A verb to start a fight**: `pagina:use()` on the attack action, then `session:world():click(gob, 1)`, already compose it.
- **Split `fight.md` into a folder now**: it ends at 184 lines, under the 300 ceiling.

## Refusals: the exact messages and what each suite asserts

| Call | Message (exact) | Suite asserts |
|---|---|---|
| `session:fight():opening(1)` | `session:fight():opening() takes no arguments — it IS the collection of your openings in the fight, and :find(needle) searches it` | `takes no arguments` (170.1) |
| `session:fight():opening():get(1)` | `session:fight():opening() has no verb 'get' — a buff has no key, since two can share a resource and the server can replace one under a live buff: session:fight():opening():find(needle) is the search and session:fight():opening():list()[n] takes a position` | `has no key` (170.1) |
| `buff:res(1)` (every `Buff` verb) | `buff:res: takes no arguments, got 1 — an argument a verb does not take is refused rather than dropped` | `takes no arguments` (170.1) |
| `opponent:ip(1)` (every `Opponent` verb) | `opponent:ip: takes no arguments, got 1 — an argument a verb does not take is refused rather than dropped` | `takes no arguments` (170.2) |
| `session:fight():target()` | `session:fight() has no verb 'target' — it answers :action() :cooldown() :deck() :give() :last() :maneuver() :opening() :opponent() :pursue() and :summary()` (the list as of 170.5; 170.2 has fewer) | `:opponent()` (170.2) |
| `session:fight():opponent():current(1)` | `session:fight():opponent():current() takes no argument — it reads the opponent that character's fight has picked` + (170.5) `; switching the target is session:fight():opponent():set(opponent), under the "fight.set" permission` | refused (170.2) |
| `session:fight():opponent():get(1.5)` | `Args.integer`: `session:fight():opponent():get: gobId must be a whole number (a GOB ID — an opponent has no name of its own (opponent:gob():name() is the creature's)), got 1.5` | refused (170.2) |
| `session:fight():action():get(0)` | `session:fight():action():get(n): the key is the 1-based position action:index() answers, so :get(1) is Combat action 1; the server's 0-based slot number is action:wire()` | `wire()` (170.3) |
| `session:fight():action():get(11)` | `session:fight():action():get(n): position out of range (1..10), got 11 — the row is the client's 10 combat keys` | `1..10` (170.3) |
| `action:use(0, pos, 1)` | `action:use: takes at most 2 arguments, got 3 — an argument a verb does not take is refused rather than dropped` | `at most 2` (170.4) |
| `action:use()` out of a fight | `action:use(): that character is not in a fight — the combat row is there only while it fights (session:fight():opponent():current() is nil). Nothing was sent.` | `not in a fight` (170.4) |
| `action:use()` on an empty place | `action:use(): action N is empty (check action:empty() first). Nothing was sent.` | `empty` (170.4) |
| `action:use()` past the row | `action:use(): this fight's row has K actions and this is action N — the client's own key for it sends nothing. Nothing was sent.` | `row has` (170.4) |
| `action:use(8)` | `Wire` row: `action:use: mods is a bitfield of Shift=1, Ctrl=2 and Alt=4, so 0..7 — those are the three keys the client sends. Got 8. Nothing was sent.` | `0..7` (170.4) |
| a write handed a Gob | `<verb>(opponent): expected an Opponent, one session:fight():opponent() handed you — got a Gob: the fight names its relation to that creature as an Opponent, session:fight():opponent():get(gob:id()). Nothing was sent.` | `Opponent` (170.5) |
| a write handed another character's Opponent | `<verb>(opponent): that Opponent is in another character's fight — take it from this session's session:fight():opponent(). Nothing was sent.` | — |
| a write handed an ended Opponent | `<verb>(opponent): that character is no longer fighting them (opponent:exists() is false). Nothing was sent.` | `:exists()` (170.5) |
| `session:fight():give(o, 4)` | `Wire` row: `session:fight():give: button must be 1, 2 or 3 — the buttons a mouse has, 1 being left and 3 right. Got 4. Nothing was sent.` | `1, 2 or 3` (170.5) |
| any write without its key | `AddonManager.requirePermission`: `<verb>: this addon did not declare the "<key>" permission — add "permissions": ["<key>"] to its manifest.json (or the group "fight.*"). …` | read, not run: the gate is each write's first statement |
