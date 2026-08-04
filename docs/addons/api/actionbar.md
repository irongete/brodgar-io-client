# hafen.actionbar: the action bar

Read and activate the action bar, the F-key and number-key hotbar. `hafen.actionbar` is a function, and
the arity is the verb.

```lua
for _, slot in ipairs(hafen.actionbar()) do              -- every slot, occupied or not
  if not slot:empty() then
    hafen.log():write(slot:index() .. ": " .. (slot:name() or slot:res()))
  end
end
hafen.actionbar(0):use()                                 -- gated: activate the first slot
```

| Call | Returns |
|---|---|
| `hafen.actionbar()` | every slot — a 1-based array of `Slot` objects, in game-index order |
| `hafen.actionbar(n)` | the `Slot` at the raw 0-based game index `n`, `0..143` |

**The index is 0-based, and an array position is not an index.** `hafen.actionbar(n)` takes the raw
game index — the same number `use` takes and the same one the server uses — and that is the one way to
address a slot. `hafen.actionbar()` is the iteration view, and a Lua array starts at 1, so
`hafen.actionbar()[1] == hafen.actionbar(0)`. Never do the arithmetic yourself: a Slot knows its own
index, and `slot:index()` is the way back.

The array is always 144 entries and never sparse. An empty slot is a `Slot` object like any other; it
just answers `:empty()`. There is no `find` and no `list`: with a fixed dense array there is nothing to
look up that `hafen.actionbar(n)` does not already answer. An index outside `0..143` **raises an
error** — the bar is a fixed array, so an out-of-range index is a bug rather than a slot that does not
exist yet.

Slot objects are **interned per addon**, so `hafen.actionbar(0) == hafen.actionbar(0)` and
`seen[slot] = true` work as a table key. A `Slot` wraps only the index and re-reads the bar on every
call, so a stashed one tracks the slot being set, cleared or dragged, and goes `:empty()` the moment it
is cleared — see [snapshots vs handles](conventions.md#snapshots-vs-handles).

Before you are in the world, and briefly after a reload, there is no hotbar: every slot reads as empty.
At login the occupied slots stream in a beat later, as a burst of `ActionbarChanged`.

## Read

| Method | Returns | Description |
|---|---|---|
| `slot:index()` | number | the raw 0-based game index this Slot addresses — always answers |
| `slot:empty()` | boolean | whether the slot has no content; also `true` before the hotbar exists |
| `slot:res()` | string \| nil | the resource name of the slot's action or item |
| `slot:name()` | string \| nil | the display name, once the action's data has resolved |
| `slot:cooldown()` | number \| nil | the meter fraction, `0..1` |
| `slot:info()` | [`ActionbarSlot`](types.md#actionbarslot) \| nil | a plain-table **snapshot**, the escape hatch for logging and serialising |

Every reader except `:index()` and `:empty()` answers `nil` for an empty slot. None of them throws, and
none is gated.

> A slot's `cooldown` is present only for an ability with a meter, and it is a `0..1` **fraction, not
> seconds**.

Subscribe to [`ActionbarChanged`](event.md#character-and-status), whose payload is the
changed `Slot` itself, to react to a slot being set, cleared or changed. It does **not** fire on a
cooldown ticking, which would be every frame; read `:cooldown()` live off the object instead.

## Write (gated: `actions`)

| Method | Description |
|---|---|
| `slot:use(mods)` | activate the slot, exactly as a left-click on that button does |
| `slot:set(resourceName)` | assign an action to the slot **by resource name**, exactly as dragging it off the menu grid does |

Both return the `Slot`, so they chain. Called from an addon that did not declare the permission, each
raises an error; see [`hafen.act`](act.md). `mods` is the optional modifier bitfield — Shift = 1,
Ctrl = 2, Alt = 4.

`use` raises an error on an empty slot, so check `:empty()` first. A ground-targeted ability enters
targeting mode when used, just as clicking the button would; supply the target with
[`hafen.act`](act.md)'s map verbs.

`set` takes the **resource name** of the action, the same string `slot:res()` reads back — so the way
to learn a name is to put the action on the bar by hand once and read it. It works on any slot, empty
or occupied, overwriting an occupied one, and a `nil`, non-string or empty name raises an error. An
unknown resource name is **silently ignored** by the server, exactly as dragging something that does
not exist would be: the slot does not change, and no error comes back. There is no way to assign by
pagina id, since those are session-local and opaque to addons.

> **The write is asynchronous.** `set` sends the assignment to the server, which echoes it back before
> the slot changes — so the very next line still reads the old content, and `:set(...):use()` in one
> chain would activate whatever was there before. React to `ActionbarChanged` on that slot, or wait a
> beat, when you need the new action.

```lua
hafen.actionbar(0):set("gfx/hud/act/mine")               -- gated: put "Mine" on the first slot
hafen.timer():after(0.5, function()
  hafen.actionbar(0):use()
end)
```

## See also

- [`hafen.menugrid`](menugrid.md) — where the resource names `set` takes come from
- [`hafen.act`](act.md) — the permission both writes share, and the verbs that supply a target
- [`ActionbarSlot`](types.md#actionbarslot) — the snapshot shape `:info()` returns
- [events](event.md#character-and-status) — `ActionbarChanged`
