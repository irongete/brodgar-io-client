# 170 — The live fight

## What & why

`session:fight()` reads the deck builder and one fact about the fight in progress: who the target is. Everything else the client holds about a fight is unreadable and unannounced: every opponent, both sides' initiative and give state, the openings drawn beside each side, the combat-action row and its cooldowns, the last manoeuvre each side used. No addon can use a combat action. Meanwhile the buff events already fire for every combat buff, with no door that lists them, no way to say whose they are, and a `BuffRemoved` that is missing or carries another object when a relation ends.

This feature publishes the fight in progress in the grammar the API already uses. The opponents are a collection whose `:current()` is the target and whose `:set()` switches it, which is the speed selector's shape. The combat-action row is shaped like the action bar. The buff events are made whole: a fight's buffs get three keys of their own, which say whose they are.

**Maintainer's ruling (2026-09-30).** Nobody has written an addon yet, so `session:fight():target()` is cut outright, with no alias and no announcement. Its place is `session:fight():opponent():current()`. The API edition moves to `1.3`.

**Maintainer's ruling (2026-09-30, at 170.1).** A fight's buffs fire `OpeningAdded`, `OpeningChanged` and `OpeningRemoved`, and `BuffAdded`, `BuffChanged` and `BuffRemoved` stay the bar's. The event says whose: the opponent as its second argument, `nil` for yours, as `ManeuverUsed` does.

## Acceptance criteria

1. Every combat buff the client draws (yours, and each opponent's) fires `OpeningAdded`, `OpeningChanged` and `OpeningRemoved` once per edge, and never a `Buff` key, which stays the bar's. Each hands the `Buff`, then the `Opponent` it is drawn beside (`nil` for yours), then the session. The `OpeningRemoved` payload `==` the `OpeningAdded` one and names the same opponent, also when a relation ends and the buff can no longer say. `buff:exists()` is `false` once its list has left the tree or the combat row is down. A relation's hidden buffs fire nothing. `session:buff()` still lists the bar alone. *(170.1)*
2. `session:fight():opening()` and `opponent:opening()` list those same interned `Buff`s. `buff:opponent()` answers the opponent whose list holds the buff, and `nil` otherwise. *(170.1)*
3. An addon declaring `api_version "1.3"` loads, and `manifest.md` names what needs `1.3`. *(170.1)*
4. `session:fight():opponent()`:
   - lists every opponent, in the client's order;
   - `:get(id)` answers one by gob id, and `nil` on a miss;
   - a string filter is refused, naming the function form;
   - `:current()` is the target, and `nil` out of a fight;
   - `:current(x)` raises, naming `:set()`.

   `session:fight():target()` raises as an unknown verb whose message lists `opponent`. *(170.2)*
5. `opponent:ip()` and `opponent:give()` answer `{mine, theirs}`: the IP painted beside you and beside them, and the two halves of the give button. `opponent:info()` is `{id, ip, give, last}`. *(170.2, 170.3)*
6. These events fire with the `Opponent` and the session last:
   - `OpponentAdded`;
   - `OpponentRemoved`;
   - `OpponentChanged`, when the IP or the give state changes;
   - `OpponentSelected`, when another opponent becomes the target. *(170.2)*
7. `session:fight():action()` holds ten `CombatAction`s:
   - `:get(n)` is "Combat action n", and `:get(0)` and `:get(11)` raise;
   - all ten are `:empty()` out of a fight;
   - `:res()`, `:name()`, `:maneuver()` and `:cooldown()` read the fight's row;
   - `session:fight():cooldown()` reads the global cooldown;
   - `CombatActionChanged` fires on a set, a clear or a name resolving, never on a cooldown. *(170.3)*
8. `session:fight():last()` answers the resource name of the last manoeuvre you used, and `opponent:last()` of theirs. `ManeuverUsed(res, opponent | nil)` fires once per use: the same manoeuvre used twice fires twice. *(170.3)*
9. `action:use(mods, position)`, key `fight.use`, sends `use` then `rel` from the fight's row, as a tapped key does. Before sending anything, it refuses out of a fight, on an empty action, and on an action past the fight's row. *(170.4)*
10. Three writes send what the client's own controls send:
    - `session:fight():opponent():set(opponent)`, key `fight.set`, sends what "Switch targets" sends;
    - `session:fight():pursue(opponent)`, key `fight.pursue`, sends what Pursue sends;
    - `session:fight():give(opponent, button)`, key `fight.give`, sends what the give button sends.

    Each refuses an opponent of another character's fight, or one no longer fought. *(170.5)*
11. A surplus argument raises, naming the verb. This holds for every new verb and for every verb of `Buff` and `Opponent`. *(170.1, 170.2)*

## Out of scope

- **The combat schools**: `session:fight():school()`, loading and saving, the `deck()` reshape, and the summary's school and deck counts. They are feature 171, on the builder window.
- **Starting a fight**: `pagina:use()` on the attack action followed by `session:world():click(gob, 1)` already does it.
- **Things the client neither draws nor reads**: a list of the relations' hidden buffs, `Fightview`'s `blk`/`batk`/`iatk`, the row's two frame markers, and the portrait click.
- **A held combat key**: nothing in the client makes the length of a hold matter.

## Docs impact

**Pages written:**
- `docs/addons/api/`: `fight.md`, `types/fight.md`, `buff.md`, `event/bus/fight.md` (new), `event/bus/character.md`, `event/bus/README.md`, `README.md`, `types/README.md`, `conventions.md`, `shapes.md`, `references.md`, `gob.md`, `party.md`, `session.md`.
- `docs/addons/`: `guides/permissions.md`, `manifest.md`.
- `docs/client/`: `combat.md` (new), `services.md`, `README.md`.

**Derived impact set.** The command:

`grep -rn -E "target\(\)|target:|Opponent|BuffAdded|BuffRemoved|BuffChanged|still on its bar|initiative|no event" docs/addons --include=*.md`

It finds:
- `fight.md:3,20–23,34,87–96,103`
- `gob.md:62`
- `types/fight.md:21`
- `buff.md:28,43,49,50`
- `event/bus/character.md:6,28–30`
- `conventions.md:101`

Found by reading, not by the grep:
- `party.md:63`
- `manifest.md:52,79–91`
- `references.md:58`: a combat buff's `:widget()` is not where it is drawn.
- `docs/client/services.md:19`: gst is written by `new`/`upd`; there is no `give` uimsg.

## Context files

- `DOCUMENTATION.md`, `docs/addons/api/fight.md`, `docs/addons/api/conventions.md`, `docs/addons/manifest.md`, `docs/client/combat.md` (170.1 creates it) — all
- `src/io/brodgar/addon/CharApi.java`, `LuaOpponent.java`, `AddonManager.java`, `Args.java`, `LuaCollection.java`; `tools/docverbs.py`, `tools/refusalverbs.py`, `tools/widgetstate.py` — all
- `src/haven/Fightview.java`; `src/io/brodgar/addon/Section.java` — 1, 2, 3, 5
- `src/io/brodgar/addon/LuaBuff.java`, `ApiVersion.java`; `src/haven/Buff.java`, `Bufflist.java`, `AddonWidgets.java`, `Widget.java`, `GameUI.java` (the `fight`/`fsess` placements only); `docs/addons/api/buff.md`, `event/bus/character.md`, `docs/client/services.md`, `docs/client/README.md` — 1
- `src/haven/GiveButton.java`; `src/io/brodgar/addon/LuaSpeed.java`; `docs/addons/api/speed.md` — 2, 5
- `src/io/brodgar/addon/LuaPartyMember.java`; `docs/addons/api/party.md`, `gob.md`, `session.md` — 2
- `docs/addons/api/types/fight.md`, `types/README.md`, `shapes.md`, `event/bus/fight.md`, `event/bus/README.md` — 2, 3
- `docs/addons/api/README.md`, `references.md` — 1, 2, 3, 4, 5
- `src/haven/Fightsess.java`; `src/io/brodgar/addon/LuaCombatAction.java` (170.3 creates it), `LuaSlot.java`; `docs/addons/api/actionbar.md` — 3, 4
- `src/io/brodgar/addon/Addon.java`, `LuaDeckCard.java`, `LuaManeuver.java` — 3
- `src/io/brodgar/addon/Wire.java`, `Permission.java`; `docs/addons/guides/permissions.md`, `docs/addons/api/world.md` — 4, 5
- `src/io/brodgar/addon/LuaPosition.java` — 4
- `src/io/brodgar/addon/LuaGob.java` — 5
