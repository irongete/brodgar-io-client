# Data Types: The Item and What Holds It

The two snapshot shapes an item hands back: the item, and what a container states about its inside. Each is what `:info()` copies out of a live object. What *optional* means is on [the catalogue](README.md).

```lua
local backpack = hafen.session():current():ui():inventory()
for _, item in ipairs(backpack:items():list()) do
  local snapshot = item:info()
  hafen.log():write((snapshot.name or snapshot.res or "?") .. " x" .. tostring(snapshot.quantity or 1))
end
```

---

## Item

From [`item:info()`](../ui/items.md#the-item-object). The live reads hand an [`Item` object](../ui/items.md#the-item-object), not this table. They are any widget's [`:items()`](../ui/items.md): the backpack `session:ui():inventory()`, worn gear `session:ui():equipment()`, a chest, a cupboard. Also [`session:player():hand()`](../player.md#the-hand) for the cursor item, and [`widget:item()`](../ui/widget.md#read-methods) for what one icon draws. Every field is optional. The last four are absent together on [something the client only draws](../ui/items.md#a-depiction-that-is-not-an-item) (a recipe slot, a listing).

| Field | Type | Notes |
|---|---|---|
| `res` | `string` | Resource name, the stable identity. |
| `name` | `string` | Display name. |
| `quantity` | `number` | How many this one item is, the [number on its icon](../ui/items.md#the-numbers-on-an-icon). |
| `progress` | `number` | `0..1`, the [arc](../ui/items.md#the-numbers-on-an-icon) painted over the icon. |
| `durability` | `{cur, max}` | The [two counts](../ui/items.md#durability-the-counts-a-wear-row-prints) its wear row prints. |
| `quality` | `number` | The quality the tooltip shows. |
| `contents` | `table` | What it holds, as the [Contents](#contents) snapshot. |
| `handle` | `number` | Its server widget id, the number it is addressed by on the wire. Absent once gone and on one the client only draws. |
| `cell` | `{x, y}` | The 1-based grid cell it sits in (`item:cell()`). Absent for a worn or cursor item and one inside another item. |
| `slots` | `string[]` | The equipment slots it fills, by name. Absent when not worn. |

A snapshot holds no live objects. There is no `container` field, though [`item:container()`](../ui/contents.md) reads one. The `contents` snapshot carries no `items`, since a snapshot of a bag would otherwise nest without end.

## Contents

From [`contents:info()`](../ui/contents.md). [`item:contents()`](../ui/contents.md) hands the live object, and its [Item objects](../ui/items.md#the-item-object) are read with `contents:items()`. `{ name = string?, text = string?, quality = number?, level = table? }`. `name` is the caption the server gave this inside. `text` is the line its tooltip states about what is in there. `quality` is the content's own. `level` is the fill meter's `{cur, max}`, the live `contents:fill()`. A container carrying items states the first. One that states what it holds states the rest.

---

## See Also

- [The catalogue](README.md) — every snapshot shape, and what a snapshot is.
- [Items](../ui/items.md) — the live `Item` object this copies.
- [What an item holds](../ui/contents.md) — the live `Contents` object the other copies.
- [Shapes](../shapes.md) — the anonymous tables these fields carry.
