# hafen.event: The Fight

What one character's fight in progress reports: an opponent joining it and leaving it, the numbers between you moving, and the target changing. Every key hands the thing it is about and that character's [`Session`](../../session.md) last. Part of [the catalogue](README.md).

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

| Rule | Detail |
|---|---|
| Diff, one frame | The client re-reads the fight once per frame after the server's message, as on [the character page](character.md), so a value that changes and comes back inside one frame fires nothing. |
| The order in one frame | Added, then Removed, then Changed, then Selected. A lost target is announced gone before the next one is announced picked. |
| Losing the target | Fires nothing. `OpponentSelected` fires when another opponent becomes the target, and `OpponentRemoved` says the fight with the last one ended. |
| Not an event | The list's order: the client's "Switch targets" key reorders it without a message. |
| The fight's buffs | Fire the [opening keys](character.md#character-and-status), whose second argument names whose. |
| A session ending | Fires none of these: the fight goes with the session. |

---

## See Also

- [`session:fight`](../../fight.md) — the opponents, their numbers, and the buffs of a fight.
- [The character and the rosters](character.md) — the opening events a fight's buffs fire.
- [`hafen.event()`](../README.md) — subscribing, and why the key set is closed.
