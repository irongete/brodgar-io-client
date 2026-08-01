# hafen.act — actions (gated)

`hafen.act` is the one part of the API that **drives the character** — it sends player actions to the
server (move, click, use items, menus, …). Everything else observes; this acts.

## The `actions` permission

Action verbs are gated by a **per-addon permission**:

1. The addon **declares** it in its manifest: `"permissions": ["actions"]`.
2. The user **enables** the addon. Write-declaring addons are **disabled by default**, and enabling one
   raises a consent dialog (Options → AddOns) — so a running write addon is one the user opted into.

There is no global on/off switch — the tier is always available at the system level; control is
entirely per addon. A verb called by an addon that didn't declare `actions` raises a clear error.
Everything stays server-authoritative: an addon can only send what a player click could send.

| Function | Returns | Description |
|---|---|---|
| `hafen.act.enabled()` | bool | whether **this** addon may act (declared the permission). Never throws — use it to feature-detect |

```lua
if hafen.act.enabled() then hafen.act.moveTo(x, y) end
```

The same `actions` permission also gates the per-subsystem write verbs in their own sections:
[`hafen.speed.set`](speed.md), [`hafen.craft.make`](craft.md),
[`slot:use` / `slot:set`](actionbar.md#write-gated--requires-the-actions-permission), and the kin verbs
[`hafen.kin():add` / `kin:rename`/`setGroup`/`endkin`/`forget`](kin.md#write-gated--requires-the-actions-permission).

> **Modifiers.** Where a verb takes `mods`, it is a bitfield: Shift = 1, Ctrl = 2, Alt = 4 (combine by
> adding). Default `0`.

## Movement & world

### `hafen.act.moveTo`
`moveTo(x, y)` — walk to a **world** position (the same space [`gob:pos()`](gob.md) returns).
Off-screen destinations are fine.

### `hafen.act.clickGob`
`clickGob(gob [, button [, mods]])` — click a game object, exactly the click a left/right-click on it
sends. `gob` is a [Gob object](gob.md) (an id is not accepted). `button`: 1 = left (default), 3 =
right (the context/flower-menu click).

### `hafen.act.useItemOn`
`useItemOn(x, y [, mods])` — use the item on your cursor on the **ground** at world `x, y`. With
nothing on the cursor the server ignores it.

### `hafen.act.place`
`place(x, y, angle [, button [, mods]])` — place the object on your cursor at world `x, y`, rotated by
`angle` **radians**. `button` 1 = confirm (default).

### `hafen.act.select`
`select(x1, y1, x2, y2 [, mods])` — area-select the tile rectangle spanned by the two world corners
(drives tile-area tools).

## Menus

### `hafen.act.menu`
`menu(path...)` — invoke a menu/pagina action by its path tokens (e.g. `hafen.act.menu("lo", "cs")`
logs out to character select). Tokens are strings; at least one is required.

> **Caveat:** pagina names are server-fetched, content-defined, localized, and versioned — not a stable
> address space — and a path resolves only if that page is currently loaded. Some paths commit real
> actions. Supply tokens deliberately.

### `hafen.act.flower`
`flower(label) -> bool` — select a petal of the open radial (flower) context menu by its `label`,
matched case-insensitively. Returns `true` if a petal matched, `false` if no menu is open or nothing
matched (it doesn't throw for those). Typically used after `clickGob(gob, 3)` to auto-pick from the menu.

## Items

### `hafen.act.item`
`item(item, verb [, n])` — act on an item. `item` is an [`Item`](types.md#item) snapshot (from
[`hafen.items.*`](items.md) or a [model's `:items()`](ui.md#model-handle)) or its raw `handle` number;
it re-resolves the live item each call (a stale/moved/used item raises a clear error). `verb`:

| `verb` | Effect |
|---|---|
| `"take"` | pick it up onto the cursor (from a container, or unequip a worn item) |
| `"drop"` | drop it on the ground; `n` = how many of a stack (default `-1` = all) |
| `"transfer"` | move it to the linked container / your inventory; `n` as for drop |
| `"iact"` | activate it — its default right-click action (eat, open, light, …) |
| `"itemact"` | apply the item on your cursor **onto** this item |

`n` is ignored for `take`/`iact`/`itemact`. For a modified interaction, use `raw` (below).

```lua
local first = hafen.items.inventory()[1]
if first then hafen.act.item(first, "take") end
```

## Escape hatch

### `hafen.act.raw`
`raw(target, msg, ...)` — send an arbitrary widget message from a bound widget. `target` is a widget id
(a number, e.g. from a [model's `:raw()`](ui.md#model-handle) or an `onWidgetCreate` descriptor) or the
token `"mapview"` / `"gameui"`. Trailing args are marshalled like the [hooks](hooks.md) (a `{x=, y=}`
table ↔ a coord; numbers/strings/booleans pass through). For power users — the typed verbs cover the
common cases.
