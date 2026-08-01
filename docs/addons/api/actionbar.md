# hafen.actionbar — action bar / hotbar

Read and activate the action bar (the F-key / number-key hotbar; the engine's internal name for it is
the "belt"). `hafen.actionbar` is a function, and the arity is the verb:

| Call | Returns |
|---|---|
| `hafen.actionbar()` | all **144** slots — a 1-based array of `Slot` objects, in game-index order |
| `hafen.actionbar(n)` | the `Slot` at the **raw 0-based game index** `n` (0..143) |

```lua
for _, slot in ipairs(hafen.actionbar()) do              -- 144 slots, occupied or not
  if not slot:empty() then
    hafen.log(slot:index() .. ": " .. (slot:name() or slot:res()))
  end
end
hafen.actionbar(0):use()                                 -- gated: activate the first slot
```

**The index is 0-based, the array position is not an index.** `hafen.actionbar(n)` takes the raw game
index — the same number `use` takes, the same one the server uses — and that is the one way to address a
slot. `hafen.actionbar()` is the *iteration view*, and a Lua array starts at 1, so
`hafen.actionbar()[1] == hafen.actionbar(0)`. Never do the arithmetic yourself: a Slot always knows its
own index, so `slot:index()` is the way back.

Slot objects are **interned per addon**, so `hafen.actionbar(0) == hafen.actionbar(0)` and `seen[slot] = true`
works as a table key. A `Slot` wraps **only the index** and re-reads the bar on every call, so a stashed one
tracks the slot being set, cleared or dragged, and goes `:empty()` the moment it is cleared — see
[snapshots vs handles](conventions.md#snapshots-vs-handles).

The array is always exactly 144 entries (`#hafen.actionbar()` is 144), never sparse: an empty slot is a
`Slot` object like any other, it just answers `:empty()`. There is no `find` or `list` — with a fixed dense
array there is nothing to look up that `hafen.actionbar(n)` does not already answer.

An index outside `0..143` **raises an error** — the bar is a fixed array, so an out-of-range index is a bug
in the addon, never a slot that merely does not exist yet.

Before you are in the world (and briefly after `:reload`) there is no hotbar yet: every slot reads as
empty. At login the occupied slots stream in a beat later, as a burst of `ActionbarChanged`.

## Read

| Method | Returns | Description |
|---|---|---|
| `slot:index()` | number | the raw 0-based game index this Slot addresses — always answers |
| `slot:empty()` | boolean | has the slot no content (also `true` before the hotbar exists) |
| `slot:res()` | string \| nil | the resource name of the slot's action or item |
| `slot:name()` | string \| nil | the display name, when the action's data has resolved |
| `slot:cooldown()` | number \| nil | the meter fraction, `0..1` |
| `slot:info()` | [`ActionbarSlot`](types.md#actionbarslot) \| nil | a plain-table **snapshot** — the escape hatch for logging/serialising |

Every reader except `:index()` and `:empty()` answers `nil` for an empty slot.

> A slot's `cooldown` is present only for an ability with a meter, and is a **0..1 fraction — not seconds**.

Subscribe to [`ActionbarChanged`](events.md#character--status-widget-tree-backed) — the payload is the
changed `Slot` itself — to react to a slot being set, cleared or changed. It does **not** fire on a
cooldown ticking (that would be every frame); read `:cooldown()` live off the object instead.

## Write *(gated — requires the `actions` permission)*

| Method | Description |
|---|---|
| `slot:use([mods])` | activate the slot — exactly a left-click on that button. `mods` is an optional modifier bitfield (Shift=1, Ctrl=2, Alt=4). Returns the `Slot`, so it chains |
| `slot:set(resourceName)` | assign an action to the slot **by resource name** — exactly what dragging it off the menu grid does. Returns the `Slot`, so it chains |

A ground-targeted ability enters targeting mode when used, just as clicking the button would; supply the
target with the [MapView action verbs](actions.md). `use` errors on an empty slot — check `:empty()` first.

```lua
hafen.actionbar(0):set("gfx/hud/act/mine")               -- gated: put "Mine" on the first slot
hafen.timer.after(0.5, function()                        -- the write lands a beat later (see below)
  hafen.actionbar(0):use()
end)
```

`set` takes the **resource name** of the action — the same string `slot:res()` reads back, so the way to
learn a name is to put the action on the bar by hand once and read it. It works on any slot, empty or
occupied (an occupied one is overwritten); a nil, non-string or empty name **raises an error**.

**The write is asynchronous.** `set` sends the assignment to the server, which echoes it back and *then* the
slot changes — so the very next line still reads the old content, and `:set(...):use()` in one chain would
activate whatever was there **before**. React to the [`ActionbarChanged`](events.md#character--status-widget-tree-backed)
event on that slot (or wait a beat, as above) when you need the new action.

An unknown resource name is **silently ignored** by the server, exactly as a drag of something that does not
exist would be: the slot simply does not change, and no error comes back. There is no way to assign by
pagina id — those are session-local and opaque to addons.
