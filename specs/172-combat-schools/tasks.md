# 172 — Tasks

- [x] **172.1 — A character's saved combat schools are read, with the loaded one as current.**
  - **Upstream:** `FightWnd.savename(n)` (`// addon:`).
  - **New `LuaSchool`:** the type, `Addon.schools`, and the collection `session:fight():school()` with `:get(n)` and `:current()`, as `plan.md` says.
  - **Retired:** `summary:activeSave()` and `summary:saveCount()`. `summary:info()` is `{ maxact, used, nact }`, and every summary verb counts its arguments.
  - **`ApiVersion.CURRENT`** is `1.4`. `manifest.md` gets the edition sentence, the "What needs `1.4`" row (the schools) and the version table, and every example manifest and `getting-started.md`'s sentence move to `1.4`.
  - **Pages:** `fight.md` gets a "Schools" section and the summary rows. `types/fight.md` gets `School` and `FightSummary`, and `types/README.md` gets `School`. `client/character-sheet.md` maps the saved schools.
  - **Checkers:** remap them, then run all three.

  *Its suite* `addons/172-combat-schools.1/` declares `api_version` `"1.4"`, which is the proof that `1.4` loads. It reads the tab (`:t172`). Its checks:
  1. `school()` is one object.
     - `:count() == #:list()`, and that count is at least 1.
     - Each school's `:index()` is its position and `:wire()` is that minus 1.
     - `:get(n) == :list()[n]`.
     - `:get(0)` and `:get(count + 1)` are `nil`.
     - `:get("x")` raises, naming `:find(filter)`.
  2. For every school, `:empty()` is `true` exactly when `:name()` is `nil`, and exactly when `:info()` is `nil`. A named school's `:info()` is exactly `{ name = school:name() }`, and `:find(name)` finds a school with that name.
  3. `:current()` is a member of `:list()`. `:current(1)` raises `takes no argument`. `school:name(1)` raises `takes no arguments`.
  4. On the summary:
     - `activeSave` and `saveCount` raise `has no verb`;
     - `:info()`'s keys are exactly `maxact`, `used` and `nact`;
     - `summary:used(1)` raises `takes no arguments`.

  The suite prints each school's position, name and current mark.

  `[manual]`: open Character sheet → Martial Arts & Combat Schools. The expected result is the printed names in the tab's save list, in that order, with the check mark on the printed current one.

- [ ] **172.2 — The deck is every hotkey place, read like the combat row.**
  - **`LuaDeckCard`:**
    - `deck()` lists every place, is addressable by `:get(n)`, and is minted once;
    - `card:index()` is the hotkey number;
    - `card:empty()` replaces `card:exists()`;
    - `card:info()` is `nil` when empty, with no `slot`;
    - every verb counts its arguments.
  - **`LuaManeuver`:** every verb counts its arguments.
  - **The summary:** `deckSize` is retired, and `info()` is `{ maxact, used }`.
  - **Pages:** `fight.md` (the deck section and its rules, the summary), `types/fight.md` (`DeckCard`, `FightSummary`), and `manifest.md`'s `1.4` row.

  *Its suite* `.2` (`:t172`). Its checks:
  1. `deck()` is one object, and `:count() == #:list()`.
     - Each card's `:index()` is its position and `:wire()` is that minus 1.
     - `:key()` is the tab's label for positions 1–10 (`"1"`..`"5"`, `"⇧1"`..`"⇧5"`).
     - `:get(n) == :list()[n]`; `:get(0)` and `:get(count + 1)` are `nil`; `:get("x")` raises.
  2. An empty card answers `nil` from `:res()`, `:name()`, `:maneuver()`, `:used()` and `:info()`. A filled card's `:info()` keys are within `key`, `res`, `name` and `used`, never `slot`. A filled card's `:maneuver():res() == card:res()`, and `deck():find(card:res())` finds a card holding it.
  3. `card:exists()` raises `has no verb 'exists'`, listing `:empty()`. `card:res(1)` and `maneuver:res(1)` raise `takes no arguments`.
  4. `summary:deckSize()` raises `has no verb`, and `summary:info()`'s keys are exactly `maxact` and `used`.

  The suite prints the deck as `n: name` or `n: (empty)`.

  `[manual]`: open the same tab. The expected result is the printed manoeuvre under each hotkey, and the empty ones empty.

- [ ] **172.3 — An addon loads and saves a combat school as the tab's buttons do, under fight.load and fight.save.**
  - **`LuaSchool`:** `school:load()` and `school:save()`, gated first, sent through `Wire.send` with `FightWnd`'s own `load`/`save` then `use`, and refused as `plan.md` lists. `:current(x)`'s refusal names `school:load()` from here on.
  - **`Permission`:** `FIGHT_LOAD` and `FIGHT_SAVE`.
  - **Pages:**
    - `fight.md`'s write table and its rules;
    - `guides/permissions.md`: both rows, and the `fight.*` group;
    - `manifest.md`'s `1.4` row;
    - `client/character-sheet.md`: the outbound `load`, `save` and `use`.

  *Its suite* `.3` declares `fight.load` and `fight.save` (`:t172`). It subscribes `hafen.event():action():on` to `load`, `save` and `use`. Each handler records the message and its arguments and calls `event:preventDefault()`, so nothing reaches the server. Its checks, all on `cur = school():current()`:
  1. If `cur` is not empty, `cur:load()` returns `cur`. It sends exactly `load {cur:wire()}` and then `use {cur:wire()}`, and `school():current() == cur` afterwards.
  2. `cur:save()` returns `cur`, and sends `save` and then `use {cur:wire()}`.
     - The first `save` argument is `cur:wire()`.
     - The second is `cur:name()` when `cur` is not empty.
     - The rest encode one deck place each: a `nil` for an empty one, otherwise an id and a count. The filled places encoded equal the count of filled cards. `event:args()` can hold `nil` gaps, so walk it up to the highest index `pairs()` finds.
  3. `cur:load(1)` and `cur:save(1)` raise `takes no arguments`.
  4. On the first empty school, if there is one, `:load()` raises naming `school:empty()` and sends nothing. This check is scored over the run, and if no school is empty the line says so.
  5. No message escaped: every intercepted send was cancelled.
  6. `school():current(1)` raises, naming `school:load()`.

  `[manual]`: none.
