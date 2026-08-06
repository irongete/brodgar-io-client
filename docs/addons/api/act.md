# hafen.act: acting on the world

`hafen.act()` drives the character: it sends player actions to the server. Reach for it when your addon
has to *do* something rather than watch — walk somewhere, click an object, use an item, pick a menu
entry. Every verb on this page is gated by the `actions` permission.

```lua
if hafen.act():enabled() then
  local tree = hafen.world():gob():nearest("terobjs/tree")
  if tree then hafen.act():clickGob(tree, 3) end     -- right-click it: opens the flower menu
end
```

## The `actions` permission

The gate is **per addon**, in two steps:

1. Your addon **declares** it in its manifest: `"permissions": ["actions"]`.
2. The user **enables** the addon. An addon that declares `actions` is disabled by default, and
   enabling it raises a consent dialog under Options ▸ AddOns.

So a running write addon is one the user opted into. There is no global on/off switch: the tier is
always available at the system level and control is entirely per addon. A verb called by an addon that
did not declare `actions` raises an error naming the verb. Everything stays server-authoritative — an
addon can only send what a player click could send.

The same permission gates the write verbs that live in their own namespaces:
[`hafen.speed():current(n)`](speed.md), [`craft:make`](craft.md), [`slot:use` and
`slot:res(name)`](actionbar.md), and the roster verbs on [`hafen.kin`](kin.md).

**Places.** The four verbs that act on the ground take a
[Position](world.md#the-position-type) — `gob:position()`, `hafen.world():position(x, y)`, or one you
saved. A plain `{x, y}` table is refused, and so is a widget's pixel position: a place in the world and a
point on the screen are different kinds of thing, and this is where confusing them used to walk you
somewhere wrong.

> Every verb below except `enabled` needs a live map view or game UI, and **throws** before you are in
> the world. Guard the first action of a session on `EnterWorld`, not on addon load.

**Modifiers.** Where a verb takes `mods` it is a bitfield — Shift = 1, Ctrl = 2, Alt = 4, combined by
adding. It defaults to `0`.

## Feature detection (ungated)

### `hafen.act():enabled()`

Whether **this** addon may act — that is, whether it declared the permission. Returns a boolean, never
throws, and answers before you are in the world, which is what makes it safe to branch on.

## Movement and the world (gated: `actions`)

### `hafen.act():moveTo(p)`

Walk to a [Position](world.md#the-position-type). Off-screen destinations are fine. Anything that is not
a Position raises an error, as does a Position this session cannot locate.

### `hafen.act():clickGob(gob, button, mods)`

Click a game object — exactly the click a left- or right-click on it sends. `gob` is a
[Gob object](gob.md); a raw id is not accepted. `button` is optional, 1 = left (default), 3 = right
(the context/flower click). It throws when the Gob is not in view (check `gob:exists()`) or has no
position yet.

### `hafen.act():useItemOn(p, mods)`

Use the item on your cursor on the **ground** at a Position. With nothing on the cursor the server
ignores it, and nothing comes back to say so.

### `hafen.act():place(p, angle, button, mods)`

Place the object on your cursor at a Position, rotated by `angle` **radians**. `button` is optional,
1 = confirm (default). To land where a real building would, snap the place first with
[`hafen.world():snapPlace`](world.md#screen-to-world-and-placement-snapping).

### `hafen.act():select(p1, p2, mods)`

Area-select the tile rectangle spanned by two Positions. This is what drives the tile-area tools.

## Menus (gated: `actions`)

### `hafen.act():menu(path)`

Invoke a menu action by its path tokens — `hafen.act():menu("lo", "cs")` logs out to character select.
Tokens are strings and at least one is required; anything else raises an error.

> Pagina paths are server-fetched, content-defined, localized and versioned, and a path resolves only
> while that page is loaded. Some paths commit real actions.

[`hafen.menugrid`](menugrid.md) is the better door: it hands you the action menu as objects you can
enumerate first, and it reaches the entries that have no path at all.

### `hafen.act():flower(label)`

Select a petal of the open radial (flower) menu by its `label`, matched case-insensitively. Returns
`true` when a petal matched and `false` when no menu is open or nothing matched — it does not throw for
either. Typically used after `clickGob(gob, 3)`. A non-string `label` raises an error.

## Items (gated: `actions`)

### `hafen.act():item(item, verb, n)`

Act on an item. `item` is an [`Item` object](ui/items.md#the-item-object) from a container's
[`:items()`](ui/items.md) or from [`hafen.ui():hand()`](ui/widget.md) — the object itself, never its
`:handle()` number, which the server re-uses and which would therefore aim the verb at whatever holds
that number now. An item that has moved, been used or vanished raises an error and sends nothing.

| `verb` | Effect |
|---|---|
| `"take"` | pick it up onto the cursor, or unequip a worn item |
| `"drop"` | drop it on the ground |
| `"transfer"` | move it to the linked container, or to your inventory |
| `"iact"` | activate it: its default right-click action — eat, open, light, … |
| `"itemact"` | apply the item on your cursor **onto** this item |

`n` is how many of a stack to move, for `"drop"` and `"transfer"` only; it defaults to `-1`, meaning
all, and is ignored by the other three verbs. An unknown `verb` raises an error.

```lua
local first = hafen.ui():inventory():items()[1]
if first then hafen.act():item(first, "take") end
```

## Escape hatch (gated: `actions`)

### `hafen.act():raw(target, msg, ...)`

Send an arbitrary widget message from a bound widget, for what the typed verbs do not cover. `target`
is a widget id — a number, such as a [widget's `:id()`](ui/widget.md) — or the token
`"mapview"` or `"gameui"`. Trailing arguments are marshalled the way an
[`action`](event.md#intercepting-an-outbound-action) `ev:args()` is: a `{x=, y=}` table becomes a
coordinate, and numbers, strings and booleans pass through. A target that resolves to no live widget
raises an error, as does a non-string `msg`.

## See also

- [`hafen.menugrid`](menugrid.md) — the action catalogue, enumerable and addressable by name
- [Gob](gob.md) — the objects `clickGob` takes
- [`hafen.world`](world.md#screen-to-world-and-placement-snapping) — building a place, and snapping one
  before you place on it
- [gating](conventions.md#gating-the-actions-permission) — how the permission reads across the API
