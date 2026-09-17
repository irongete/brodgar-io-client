# session:buff: Buffs

The buffs on one character's buff bar, read through its [session](session.md). `session:buff()` is that character's bar.

```lua
local session = hafen.session():current()                    -- the character on screen
if session and session:buff():find("poison") then hafen.log():write("poisoned!") end

for _, buff in ipairs(session and session:buff():list() or {}) do
  hafen.log():write(buff:name() or buff:res())
end
```

---

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:buff():list(filter)` | `Buff[]` | Unprotected | Every active buff on that bar, a 1-based array in bar order. |
| `session:buff():count(filter)` | `number` | Unprotected | How many match. |
| `session:buff():find(filter)` | `Buff \| nil` | Unprotected | The first active buff that matches. |

| Rule | Detail |
|---|---|
| `filter` | A string [filter](conventions.md#the-filter-argument) is a substring match against the resource name and the display name. A miss is `nil`. |
| No `:get` | A buff has no key: two can share a resource, and the server can replace a live buff's resource under it. `session:buff():get("poison")` raises: `session:buff() has no verb 'get' — a buff has no key, since two can share a resource and the server can replace one under a live buff: session:buff():find(needle) is the search and session:buff():list()[n] takes a position`. |
| Whose buffs | `hafen.session():get("alt"):buff():find("poison")` answers for that character while you watch another. |
| One object | `session:buff()` is the same object every call, minted once per session. A session the client no longer holds answers an empty array. |
| Interned per addon | `session:buff():find("poison") == session:buff():find("poison")` and `seen[buff] = true` work while the buff is up. A `Buff` wraps the buff widget and re-reads it on every call, so a stashed one tracks its meters ([snapshots vs handles](conventions.md#snapshots-vs-handles)). It carries its own character: `buff:exists()` is about the bar it stands on. |
| Order | The buffs on the bar in the order drawn, which is arrival order. A buff the server has removed is excluded even while it fades out on screen. |
| Res-only at first | The display name and the meters arrive in a second server message, so every reader may answer `nil` just after a buff appears. |
| No write side | The bar displays server state. Clicking a buff icon sends a message no buff is known to act on. |

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `buff:res()` | `string \| nil` | Unprotected | The resource name, such as `"paginae/buff/poison"`. |
| `buff:name()` | `string \| nil` | Unprotected | The display name, once the resource has resolved. |
| `buff:amount()` | `number \| nil` | Unprotected | The buff's own meter fraction, `0..1`. |
| `buff:remaining()` | `number \| nil` | Unprotected | The radial overlay fraction, `0..1`: how much of the buff is left. |
| `buff:number()` | `number \| nil` | Unprotected | The integer badge drawn on the icon. |
| `buff:widget()` | [Widget](ui/widget.md) `\| nil` | Unprotected | The widget that draws it: the crossing back into the tree. |
| `buff:exists()` | `boolean` | Unprotected | Whether this buff is still on its bar. Always answers. |
| `buff:info()` | [`Buff`](types/character.md#buff) `\| nil` | Unprotected | A plain-table snapshot, for logging and serialising. |

| Rule | Detail |
|---|---|
| Content-defined meters | `amount`, `remaining` and `number` are published by the buff's resource and often absent. `remaining` is a [`0..1` fraction](shapes.md#units) of the whole run (`0.25` is a quarter left). The client has no seconds-based buff timer. |
| A removed buff keeps answering | `:exists()` is `false` while `:res()`, `:name()` and the meters read the values it had, so a `BuffRemoved` payload or a stashed buff still reads. `:exists()` is the predicate `:list()` filters on. |
| Events | [`BuffAdded`, `BuffRemoved`, `BuffChanged`](event/bus/character.md#character-and-status). Each payload is the `Buff` object. The buffs a character already has arrive as a burst of `BuffAdded` shortly after it enters the world. |

```lua
hafen.event():on("BuffRemoved", function(buff)
  hafen.log():write((buff:name() or buff:res()) .. " wore off")   -- readable; :exists() is false
end)
```

---

## See Also

- [`Buff`](types/character.md#buff) — the snapshot shape `:info()` returns.
- [`session:meter`](meter.md) — the HUD bars, read the same way.
- [Snapshots vs handles](conventions.md#snapshots-vs-handles) — why a stashed `Buff` stays current.
- [Events](event/bus/character.md#character-and-status) — the buff events.
