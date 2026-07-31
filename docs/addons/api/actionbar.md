# hafen.actionbar — action bar / hotbar

Read and activate the action bar (the F-key / number-key hotbar; the engine's internal name for it is
the "belt"). Slots are addressed by the **raw 0-based game index** (0..143) — the same index whether
reading or using. `use` is **gated** — it requires the [`actions` permission](actions.md).

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.actionbar.slot(n)` | [`ActionbarSlot`](types.md#actionbarslot) \| nil | the content of slot `n`, or nil if empty |

Subscribe to [`ActionbarChanged`](events.md#character--status-widget-tree-backed) — payload is the
changed `Slot` object — to react to a slot being set, cleared, or changed.

```lua
local s = hafen.actionbar.slot(0)
if s then hafen.log("slot 0: " .. (s.name or s.res)) end
```

## Write *(gated — requires the `actions` permission)*

| Function | Description |
|---|---|
| `hafen.actionbar.use(n [, mods])` | activate slot `n` — exactly a left-click on that button. `mods` is an optional modifier bitfield (Shift=1, Ctrl=2, Alt=4) |

A ground-targeted ability enters targeting mode when used, just as clicking the button would; supply
the target with the [MapView action verbs](actions.md). `use` errors on an out-of-range or empty slot.

> A slot's `cooldown` (0..1) is present only for an ability with a meter, and is **not** seconds.
