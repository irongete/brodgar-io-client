# 172 — Plan

## Approach

**What upstream holds.** `FightWnd` (`@RName("fmg")`, `CharWnd.fight`, reached by `CharApi.fightwnd(user)`) is built hidden at login and stays live.
- **Fields:** `nsave` (final), `order` (a final `Action[]`, `nact` long), and `usesave`, written by the `use` uimsg.
- **Names:** the private `Text[] saves`, written by `saved` (a bitmask plus names, default "Saved school n"). An unused slot holds the private `unused` text.
- **Buttons:** Load is `load(n)` then `use(n)`. Save is `save(n)` then `use(n)`, where `save` composes `n`, the name of a used slot, and the layout (`null` or `id, u` per place).

**The core edit** is one public reader in `FightWnd`, tagged `// addon:` with the reason: `public String savename(int n) {return((saves[n] == unused) ? null : saves[n].text);}`. `Text.text` is public.

**172.1, the schools (new `LuaSchool`, modelled on `LuaCombatAction`).**
- **Type and interning:** a per-addon intern `Cache` keyed by account and 0-based slot, held as `Addon.schools`, with a metatable through `Refusal.closedIndex("school", …, "a school is one saved-school slot of the combat-schools tab")` and `tostring` `School(<n>)`.
- **Verbs,** each through `Args.only(a, 0, "school:<verb>")`:
  - `index` and `wire`;
  - `empty`, which is `savename == null`;
  - `name`;
  - `info`, which is `{ name }`, or `nil` when empty.

  Read `nsave`, `usesave` and `savename` under `LuaWidget.monitor(fw)`: `saved` writes them off-thread, a rename on the UI thread.
- **The collection** goes through `LuaCollection.create(CharApi.FS, …)`, with the constant `FS = "session:fight():school()"`.
  - `members()`: one school per slot `0..nsave-1`, and none before the tab has built.
  - The source is `named()`, with the name as its needle, and `addressable()`.
  - `getMember`: `Args.integer(key, FS + ":get", "n", "the 1-based position school:index() answers; a name is a search, " + FS + ":find(filter)")`, then `nil` outside `1..nsave`, or with no tab.
- **`current`** is an extra verb on the speed shape. It raises on an argument, naming the tab's Load button until 172.3 mounts `school:load()` and names that: `refusalverbs` holds a refusal to the verbs its receiver answers today. It answers `of(usesave)` when `0 <= usesave < nsave`, and `nil` otherwise or with no tab.
- **`CharApi.fight()`** gains `school`, minted once like `opponent`.
- **`LuaFightSummary`** keeps `maxActions`, `used`, `exists`, `info`, and `deckSize` until 172.2. `number()` becomes a `VarArgFunction` with `Args.only`. `info` is `{ maxact, used, nact }`, then `{ maxact, used }` in 172.2. Fix the summary verb's refusal ("the five counts").
- **`ApiVersion.CURRENT`** becomes `(1, 4)`.

**172.2, the deck (`LuaDeckCard`).**
- **`deck()`:**
  - `members()` is every place `0..order.length-1`;
  - it is `addressable()`, and `getMember` follows the schools' rule (`1..order.length`, `nil` outside);
  - the `noGet` sentence is dropped;
  - the needle stays `LuaManeuver.needleOf`, with `null` for an empty place;
  - `CharApi.fight()` mints it once.
- **Card verbs:**
  - `index` is `slot + 1`, so `position()` goes;
  - `empty` replaces `exists`;
  - the snapshot is `nil` for an empty place and drops `slot`.

  The class comment states the new shape. Every verb counts its arguments.
- **`LuaManeuver`**: its six `OneArgFunction`s become `VarArgFunction`s with `Args.only`.
- **The summary** loses `deckSize`, and its `info` becomes `{ maxact, used }`.

**172.3, the writes.**
- **`school:load()`:**
  - gated first with `Permission.FIGHT_LOAD` (D-213), then `Args.only(a, 0)`;
  - `fw == null` refuses: the tab has not built;
  - `slot >= fw.nsave` refuses;
  - an empty school refuses, naming `school:empty()`;
  - then `Wire.send(user, "school:load", fw, "load", new Object[] {n}, () -> { fw.load(n); fw.use(n); })`.
- **`school:save()`** takes the same gate with `FIGHT_SAVE`, then `Wire.send(user, "school:save", fw, "save", null, () -> { fw.save(n); fw.use(n); })`. Its arguments are `null` because `FightWnd.save` composes the layout.
- Both return `self`.
- **`Permission`** gains `FIGHT_LOAD("fight.load", "school:load", "load a saved combat school, on any of your characters")` and `FIGHT_SAVE("fight.save", "school:save", "save the combat-school layout into a slot, on any of your characters")`.

**The checkers.**
- `docverbs.RECEIVERS` gains `"school": "school"`.
- `refusalverbs` gains:
  - `RECEIVERS`: `"school": "school"`;
  - `MEMBER`: `"session:fight():school()": "school"`;
  - `HOPS`: `("session:fight():school()", "current"): "school"`.
- The `deckcard`, `fightsummary` and `maneuver` rows exist.

## Files to create/modify

| Task | Files |
|---|---|
| 172.1 | `FightWnd.java`, **new** `LuaSchool.java`, `LuaFightSummary.java`, `CharApi.java`, `Addon.java`, `ApiVersion.java`, the tools; `fight.md`, `types/fight.md`, `types/README.md`, `manifest.md`, the example manifests (*Docs impact*), `client/character-sheet.md`; suite `.1` |
| 172.2 | `LuaDeckCard.java`, `LuaManeuver.java`, `LuaFightSummary.java`, `CharApi.java`; `fight.md`, `types/fight.md`, `manifest.md`; suite `.2` |
| 172.3 | `LuaSchool.java`, `Permission.java`; `fight.md`, `guides/permissions.md`, `manifest.md`, `client/character-sheet.md`; suite `.3` |

`docs/client/character-sheet.md` gets the saved schools: `saves`, `unused` and `savename`, the `saved` and `use` uimsgs with `usesave`'s default `0`, and the outbound `load`, `save` and `use` with `save`'s layout. Its "What is not mapped" line about the names goes.

`fight.md` (215 lines) gains about 40. If a task pushes it past 300, that task splits the builder onto its own page and re-points the anchors.

## Risks & gotchas

- **`Wire.SHAPES` is keyed by message name**, and its `use` row is `Fightsess`'s (`mods` at index 2). `FightWnd`'s `use` rides inside the `load`/`save` dispatch, so never name it as a `Wire.send` message.
- **`usesave` is `0` until the first `use` uimsg**, so `:current()` can be school 1 while that slot is unused. That is what the tab marks, and `:current()` answers it.
- **`FightWnd.keys` holds ten labels.** `card:key()` is `nil` past them (`LuaDeckCard.keyLabel`).
- **`docverbs` holds every example manifest and the edition sentence to `ApiVersion.CURRENT`**, so 172.1 moves them all.
- **Surplus arguments.** A `OneArgFunction` drops one unseen, so every converted verb takes `Varargs` and calls `Args.only` first.
- **LuaJ's `LibFunction.name`.** Inside an anonymous `VarArgFunction`, a bare `name` is that field, so qualify a static helper called `name`.
- **The writes' suite never reaches the server.** It cancels each `load`, `save` and `use` with `event:preventDefault()`, as 170.5's suite does, and touches only the current school, so a failed cancel changes nothing.
- **The build.** `rm -rf build/classes` and `ant hafen-client`, then `rm build/hafen.jar` and `ant bin`, because `<jar update>` keeps classes that were deleted. Then a full restart.

## Discarded alternatives

- **The school picked with `:set()`, the speed's shape.** Two `set` verbs in one section would collide on the key `fight.set`, and upstream's word is `load`, paired with `save`.
- **Keeping `activeSave()`, `saveCount()`, `deckSize()` or `card:exists()` beside their places.** That is two ways for one question.
- **`school:exists()`.** A slot is a fixed place that never goes, and `:empty()` says what it holds, as on `Slot`.
- **`:get(0)` raising on the deck and the schools, as on the combat row.** The row's size is the client's ten keys; a size the server sends answers `nil` outside it, as the speed selector's does.
- **A school addressed by its name.** Names are the player's, not unique, and change with a rename: a name is a search.
- **Keeping `slot` in `DeckCard`'s snapshot.** The place is read live by `:index()` and `:wire()`, and `Slot`'s snapshot carries content only.
- **`school:save(name)`.** Naming a school is a design of its own, and the tab's rename already rides the next save.
- **Reading `saves` by reflection.** Core edits stay minimal and tagged: a one-line public reader is the seam.
- **Composing `load`, `use` or `save` ourselves.** The buttons call `FightWnd`'s methods and `save`'s layout encoding is the tab's: wrapping keeps the two from drifting apart.
- **Keeping the edition at `1.3`.** An addon using `school()` must not load on a client without it.
- **Generation `2.0`.** It only churns the maintainer's manifests.
- **A school event.** The schools change only on the player's own act, and are read on demand.
