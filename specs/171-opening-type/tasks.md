# 171 — Tasks

- [x] **171.1 — A fight's openings are Opening objects, and a Buff is the bar's alone.**
  - **Java.** New `LuaOpening`: the type, its cache (`Addon.openings`), its verbs and the two fight doors, with `LuaBuff`'s fight half moved in as `plan.md` lists.
    - `LuaBuff` loses `:opponent()`. `LuaBuff.active` answers for the bar alone (`GameUI.buffs`).
    - `CharApi.BuffsAdapter` announces a buff of the bar as a `Buff` and an opening as an `Opening`, keeping 170's once-per-edge guarantees.
    - `AddonManager.fireOpening` mints `LuaOpening`. `Addon.dropInternedHandles` retires the widget from both caches.
    - `CharApi.fight()`'s `opening` and `LuaOpponent`'s `opening` return the `LuaOpening` doors.
  - **Checkers.** Remap `tools/docverbs.py` and `tools/refusalverbs.py` as `plan.md` says.
  - **Pages.** Rewrite every page of the spec's *Docs impact* to the separation rule:
    - the `Opening*` rows and their rule move to `event/bus/fight.md`;
    - the `Opening` snapshot goes on `types/fight.md`;
    - `buff.md` names no fight, and `fight.md` names no buff.

    Then run both commands below and get no output:

    ```bash
    grep -n -i -E "fight|opponent|opening" docs/addons/api/buff.md docs/addons/api/event/bus/character.md
    grep -n -i "buff" docs/addons/api/fight.md docs/addons/api/types/fight.md docs/addons/api/event/bus/fight.md
    ```

    Also read the `Buff` section of `types/character.md`: it names no fight.
  - **Build.** `rm -rf build/classes`, then `ant hafen-client` → `BUILD SUCCESSFUL`; `ant bin`. Then run `docverbs`, `refusalverbs` and `widgetstate`, each on its own, and each exits `0`.

  *Its suite* `addons/171-opening-type.1/` declares `api_version` `"1.3"`. It runs on `:t171` and watches one fight on a timer, from the combat row coming up to it going down. It is modelled on 170.1's suite and judges what arrived, whatever the animal. Its window says: "Have a buff on your bar. Attack an animal and kill it." A Stop button aborts. Its checks:
  1. `session:fight():opening()` is one object across calls. `:get(1)` raises `an opening has no key`, naming `:find(needle)`. `opening(1)` raises `takes no arguments`.
  2. At every look:
     - each member of both fight doors has a `tostring` starting `Opening(`, answers `:exists()` `true`, and is not in `session:buff():list()`;
     - each `session:buff()` member has a `tostring` starting `Buff(` and is in neither fight door.
  3. Each `OpeningAdded`, `OpeningChanged` and `OpeningRemoved` hands an `Opening`, then `nil` for one of yours or the target for one of theirs, then the session. The `Added` payload `==` the door's member.
  4. Each opening fires `OpeningRemoved` once per `OpeningAdded`, with the same object. Afterwards `:exists()` is `false` and `:opponent()` is `nil`.
  5. No `Buff*` key ever hands an `Opening`, and every `Buff*` payload's `tostring` starts `Buff(`.
  6. `opening:opponent()` is `nil` for yours and the target for theirs, at every look.
  7. `opening:info()`'s keys are within `res`, `name`, `amount`, `remaining` and `number`, never `duration`. `info().remaining == opening:remaining()` whenever both are present.
  8. On one opening: `:res(1)` raises `takes no arguments`, and `:frob()` raises `has no verb 'frob'` and its message lists `:opponent()`.
  9. On the first bar buff the run saw, `buff:opponent()` raises `has no verb 'opponent'`. This is scored over the run. If the bar never held a buff, the check fails, saying "no buff on the bar during the run".
  10. After the fight the row is gone and both doors are empty.

  Then `[summary]`, about 12 lines in all. Before asserting a verb, grep `LuaOpening`'s `m.set` list: nothing checks a suite's verbs.

  `[manual]`: none. Everything here reads back through the API.
  <!-- extra context: specs/170-live-fight/addons/170-live-fight.1/main.lua (the fight-watching suite to model) -->
