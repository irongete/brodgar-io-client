# Data Types: The Widget Layer

The snapshot shapes that come out of the client's own windows. A widget in the tree. A HUD meter and one band of its bar. The open recipe. A hotbar slot. An action-menu entry. A chat channel and its lines. Each is what `:info()` copies out of a live object. What *optional* means is on [the catalogue](README.md).

```lua
local window = hafen.session():current():ui():match("window[title=Inventory]")
local snapshot = window and window:info()
if snapshot then hafen.log():write(snapshot.type .. " " .. snapshot.size.w .. "x" .. snapshot.size.h) end
```

---

## Widget

From [`widget:info()`](../ui/widget.md#read-methods). `nil` once the widget is stale. [`session:ui()`](../ui/widget.md) and every selector search hand live `Widget` objects, not this table.

| Field | Type | Notes |
|---|---|---|
| `type` | `string` | The client's class name for it (`widget:type()`). Always present. |
| `owned` | `boolean` | Whether your addon made it, so the write verbs answer. Always present. |
| `role` | `string` | The [selector role](../ui/selectors.md) it classifies as. Optional. |
| `res` | `string` | The resource behind it. Optional. |
| `id` | `number` | The number the server knows it by. Optional (absent on a client-side widget). |
| `pos` | `{x, y}` | Its top-left in its parent, [design pixels](../ui/pixels.md) (`widget:position()`). Optional. |
| `size` | `{w, h}` | Its box, design pixels (`widget:size()`). Optional. |
| `visible` | `boolean` | Whether it and its parents are showing. Always present. |
| `enabled` | `boolean` | Whether it takes input, its own flag ([`widget:enabled()`](../ui/widget.md#read-methods)). `true` on the client's own. Always present. |
| `text` | `string` | The label or content it draws. Optional. |

## Meter

From [`meter:info()`](../meter.md#read). `session:meter():list()` and the meter events hand live [`Meter` objects](../meter.md), not this table.

| Field | Type | Notes |
|---|---|---|
| `res` | `string` | The background resource name, the meter's identity. Optional. |
| `index` | `number` | Its 1-based HUD position. Absent once the meter is gone. |
| `value` | `number` | The first segment's fill fraction, `0..1`. Optional. |
| `color` | [colour](../shapes.md#colours) | The first segment's colour. Optional. |
| `segments` | `{value, color?}[]` | The whole bar, 1-based and gap-free. Always present, may be empty. The place is the array position. No `index` per entry. |

## MeterSegment

From [`segment:info()`](../meter.md#a-segment). `nil` once the band has gone. `{ index = number, value = number, color = colour? }`: `index` the 1-based place in the bar, `value` the fill fraction `0..1`, both always present. `color` absent where the content defines none. The live reads are `segment:index()`, `:value()`, `:color()`.

## Craft and CraftSpec

From `session:craft():info()` on [`session:craft`](../craft.md). The slot reads on the section hand back [spec objects](../craft.md#a-spec), not these tables.

| Shape | Fields |
|---|---|
| `Craft` | `{ recipe = string?, inputs = CraftSpec[], outputs = CraftSpec[], qmod = ResRef[], tools = ResRef[] }`. `qmod` is `session:craft():qualityInputs()`. `recipe` absent while the window carries no name. |
| `ResRef` | `{ res = string?, name = string? }`. |
| `CraftSpec` | `{ res = string?, name = string?, num = number?, opt = bool? }`, what [`spec:info()`](../craft.md#a-spec) copies. `num` is the count in the server's spelling, `-1` unspecified (`spec:count()` answers `1`). `opt` marks an optional ingredient or a chance byproduct. Both absent on a quality input and a tool. |

## Petal

From [`petal:info()`](../flowermenu.md#a-petal). `{ index = number, wire = number, native = bool, label = string? }`. `index` is the 1-based place on the ring, `wire` the 0-based number the menu sends. `native` is whether the server sent it, `false` for one [you added](../flowermenu.md#write-unprotected). All three are always present. `label` absent once the ring has closed.

## ActionbarSlot

From [`slot:info()`](../actionbar.md#read). `nil` for an empty slot. `{ res = string?, name = string?, cooldown = number? }`: `cooldown`, `0..1` and not seconds, present only for an ability slot with a meter. On a slot [held](../actionbar.md#hold-a-slot-unprotected) for an addon's entry, `res` is that entry's `addon/<addon id>/<id>` identity and `name` the name the addon gave it.

## Pagina

From [`pagina:info()`](../menugrid.md#read). [`session:menugrid():list()`](../menugrid.md#read) hands live `Pagina` objects, not this table.

`{ res = string, exists = bool, addon = string?, name = string?, tooltip = string?, hotkey = string?, path = string[]?, parent = string?, isnew = bool? }`: `res` is the identity and always present. `parent` is the parent's resource name, not an object. `path` is what `pagina:categories()` reads. `isnew` is `pagina:unseen()`. `addon` is the id of the addon that added the entry, absent on the game's own. Every other field is absent when the menu cannot answer it (the entry gone, its resource unloaded).

## Channel

From [`channel:info()`](../chat.md#a-channel). `nil` once the tab has gone. `{ name = string?, kind = string, urgency = number }`: `kind` is one of the words [`channel:kind()`](../chat.md#the-kinds) answers, always present. `urgency` is `0` for nothing unread. `name` is absent while the client cannot state one (a private conversation whose other person the kin roster does not carry yet).

## Message

From [`message:info()`](../chat.md#a-line). `nil` once the line's channel has gone.

| Field | Type | Notes |
|---|---|---|
| `text` | `string` | The line as written, markup and all. Optional. |
| `kind` | `string` | The [site key](../chat.md#the-kind-of-a-line) it is drawn at. Always present. |
| `color` | [colour](../shapes.md#colours) | The colour the line carries of itself. Optional. |
| `time` | `number` | When the client took the line, epoch seconds with a fraction (`string.format("%d", …)` to write it). Always present. |
| `mine` | `boolean` | Whether this character said it. Always present. |
| `speaker` | `number` | The kin id of whoever said it, what [`session:kin():get(id)`](../kin.md) takes. Optional. The live `message:speaker()` hands the Kin. |

---

## See Also

- [The catalogue](README.md) — every snapshot shape, and what a snapshot is.
- [`session:chat`](../chat.md) — the live channels and lines these copy, and saying a line.
- [`session:meter`](../meter.md) — the live meter bars and bands these copy.
- [`session:craft`](../craft.md) — the open recipe window, and its Craft button.
- [`session:actionbar`](../actionbar.md) — the hotbar, and holding a slot for an entry of your own.
- [`session:menugrid`](../menugrid.md) — the action menu, and invoking an entry.
- [`session:ui()`](../ui/widget.md) — the live widget tree these copy, and the verbs that read one.
