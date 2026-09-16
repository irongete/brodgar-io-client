# hafen.ui: What an Item Holds

`item:contents()` reads what a stack, a creel or a bucket carries — the items inside, or the line, quality and fill a liquid container states — as one `Contents` object.

```lua
local session = hafen.session():current()
for _, item in ipairs(session:ui():inventory():items():list()) do
  local held = item:contents()
  if held then
    hafen.log():write((item:name() or "?") .. " holds "
                      .. (held:text() or (held:items():count() .. " things")))
  end
end
```

`item:contents()` answers a `Contents` for an item that holds something and `nil` for one that holds nothing — most items, and any whose [tooltip has not landed yet](items.md#an-item-arrives-before-it-can-be-described). A stack and a creel carry real items, each with its own quality and server address; a bucket carries what its tooltip states and no items. One object answers for both, because the client is never told which kind it has. Reading is unprotected.

---

## Read Methods

| Method | Returns | Permission | Description |
|---|---|---|---|
| `contents:items()` | collection of [`Item`](items.md#the-item-object) | Unprotected | What is inside, as live objects; empty, never `nil`, for a container that states what it holds. |
| `contents:name()` | `string \| nil` | Unprotected | What the server calls this inside, the caption its own window carries. |
| `contents:text()` | `string \| nil` | Unprotected | The line the tooltip states about what is inside; `nil` for a container carrying items. |
| `contents:quality()` | `number \| nil` | Unprotected | The content's own quality, distinct from `item:quality()`. |
| `contents:fill()` | `{cur, max} \| nil` | Unprotected | The fill meter's two counts, in the meter's own scale; `nil` for a container drawing none. |
| `contents:info()` | `table` | Unprotected | The [snapshot](../types/items.md#contents), which carries no `items`. |

| Rule | Detail |
|---|---|
| Interned | Two reads of one item's contents are `==`. It is `nil` while the item's info resolves, never a half-built object: `nil` means "holds nothing", an empty `:items()` means "an empty container". |
| `item:container()` is the inverse | `a:contents():items()` holds `b` if and only if `b:container()` is `a`, and it chains: a dandelion in a stack in a creel answers the stack, the stack the creel. A where-read, so `nil` on a stale item. |
| A contained item is not in `widget:items()` | A stack is one item there, as it is one cell; a thing inside answers `:cell()` as `nil`. Reach it by recursing through `:contents()`. The protected verbs reach it: `:take()` on one dandelion lifts that one, on the stack the whole pile. |

```lua
for _, item in ipairs(hafen.session():current():ui():inventory():items():list()) do
  local held = item:contents()
  for _, inner in ipairs(held and held:items():list() or {}) do
    hafen.log():write((inner:name() or "?") .. " q" .. (inner:quality() or 0)   -- its own quality...
                      .. " in " .. (item:name() or "?"))                         -- ...not the stack's
  end
end
```

---

## A liquid container: a stated line, a fill, and no items

```lua
local jug = hafen.session():current():ui():inventory():items():list()[1]   -- a jug holding water
local contents = jug:contents()
contents:text()        -- "4.55 l of Water": the line its tooltip states
contents:quality()     -- the water's quality; jug:quality() is still the jug's
contents:fill()        -- { cur = 455, max = 500 }: the fill meter, in its own scale
contents:items()       -- { }: it states what it holds and carries nothing
```

| Rule | Detail |
|---|---|
| `:fill()` | The two counts behind the bar on the icon, which the client paints only as a fraction. They are the meter's scale, not the units the line states: divide one by the other, and do not read `cur` as the number before the `l`. |
| Telling the two insides apart | A stack answers `nil` to `:text()` and `:fill()`; a stating container answers an empty `:items()`. Ask rather than assume. |
| The substance is never named | Only the rendered line, a quality and a fill arrive; `:text()` is the whole of what can be said. |
| Nothing has to be open | The window a container pops under the pointer is hidden, not destroyed, when you move away, so every read answers the same. Opening it is the server's answer to a right-click; no client message asks for it. |

---

## Where these reads end

What an item holds is what the server pushed with it, for the containers you carry. An item it sent nothing for reads `nil`, and no client message asks for one; a chest standing in the world is opened and read as the container widget it becomes. An item on the cursor arrives as a widget of its own with nothing attached: a stack in your hand answers `nil` to `:contents()` and to `:quality()` (a stack's quality is its parts'), and both read again the moment it lands.

---

## See Also

- [Items](items.md) — the `Item` object this reads off, and the verbs on one.
- [`Contents`](../types/items.md#contents) — the snapshot `:info()` hands back.
- [`session:player`](../player.md#the-hand) — the cursor, which carries an item and nothing under it.
- [Widget](widget.md) — the container the items are read from.
