# hafen.event: The Fight

What one character's fight in progress reports: an opponent joining it and leaving it, the numbers between you moving, the target changing, a manoeuvre being used, an opening drawn and taken away, and the combat row changing. Every key hands the thing it is about and that character's [`Session`](../../session.md) last. Part of [the catalogue](README.md).

```lua
hafen.event():on("OpponentSelected", function(opponent, fight_session)
  local ip = opponent:ip()
  hafen.log():write(fight_session:character() .. " targets " .. opponent:id()
    .. (ip and (" at IP " .. ip.mine) or ""))
end)
```

---

| Event | Payload | Fires |
|---|---|---|
| `OpponentAdded` | [`Opponent`](../../fight.md#an-opponent) | The character starts fighting a creature. |
| `OpponentRemoved` | [`Opponent`](../../fight.md#an-opponent) | The fight with it ends. The object still answers `:id()` and `:gob()`, and `:exists()` is false. |
| `OpponentChanged` | [`Opponent`](../../fight.md#an-opponent) | Its IP pair or its give state changes. |
| `OpponentSelected` | [`Opponent`](../../fight.md#an-opponent) | It becomes the target, `session:fight():opponent():current()`. |
| `ManeuverUsed` | `string`, then [`Opponent`](../../fight.md#an-opponent) `\| nil` | A manoeuvre is used: its resource name, and the opponent who used it, `nil` when that character did. |
| `CombatActionChanged` | [`CombatAction`](../../fight.md#a-combat-action) | A place of the combat row is set, cleared or its name resolves, and every filled place as the row comes and goes with the fight. |
| `OpeningAdded` | [`Opening`](../../fight.md#an-opening), then [`Opponent`](../../fight.md#an-opponent) `\| nil` | The fight draws an opening: beside you (`nil`), or beside that opponent. |
| `OpeningRemoved` | [`Opening`](../../fight.md#an-opening), then [`Opponent`](../../fight.md#an-opponent) `\| nil` | The fight stops drawing it. The object still reads, `:exists()` is false. |
| `OpeningChanged` | [`Opening`](../../fight.md#an-opening), then [`Opponent`](../../fight.md#an-opponent) `\| nil` | A drawn opening's content updates. |

| Rule | Detail |
|---|---|
| Diff, one frame | The client re-reads the fight once per frame after the server's message, as on [the character page](character.md), so a value that changes and comes back inside one frame fires nothing. |
| The order in one frame | `OpponentAdded`, then `ManeuverUsed`, then `OpponentRemoved`, `OpponentChanged` and `OpponentSelected`. A lost target is announced gone before the next one is announced picked. |
| `ManeuverUsed`, once per use | Every use is announced, the same manoeuvre used twice included. It fires once the name has loaded, so it can trail the use by a frame. `session:fight():last()` and `opponent:last()` read the same name afterwards. |
| `CombatActionChanged` payload | The interned `CombatAction`, so `payload == session:fight():action():get(payload:index())`. Not on a cooldown starting or running: read `action:cooldown()` live, as the action bar's. |
| Losing the target | Fires nothing. `OpponentSelected` fires when another opponent becomes the target, and `OpponentRemoved` says the fight with the last one ended. |
| Not an event | The list's order: the client's "Switch targets" key reorders it without a message. |
| Whose an opening is | The second argument is the opponent the opening is drawn beside, `nil` for yours. It is what the client recorded when it announced the opening, so `OpeningRemoved` still names the opponent after `opening:opponent()` can no longer. |
| An opening's life | `OpeningAdded`, `OpeningChanged` and `OpeningRemoved` each fire once per edge, with one object. A relation ending takes its openings with it: each fires `OpeningRemoved` once. So does the end of the fight, for your own the server has not yet expired: they are no longer drawn, and fire `OpeningAdded` again if the next fight draws them again. A content update fires `OpeningChanged`; a meter running down against a clock moves silently. |
| A session ending | Fires none of these: the fight goes with the session. |

---

## See Also

- [`session:fight`](../../fight.md) — the opponents, their numbers, the combat row, and the openings of a fight.
- [`hafen.event()`](../README.md) — subscribing, and why the key set is closed.
