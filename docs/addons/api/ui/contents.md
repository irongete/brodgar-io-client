# hafen.ui: what an item holds

A stack, a creel, a bucket carries something, and `item:contents()` is how you read it. Reach for it
whenever the thing on the icon is not the thing you want — the water in the jug, the dandelions in the
pile, the fish in the creel. Reading is unprotected.

```lua
local s = hafen.session():current()
for _, it in ipairs(s:ui():inventory():items():list()) do
  local held = it:contents()
  if held then
    hafen.log():write((it:name() or "?") .. " holds "
                      .. (held:text() or (held:items():count() .. " things")))
  end
end
```

[`item:contents()`](items.md#the-item-object) answers a **`Contents`** object for an item that holds
something, and `nil` for one that holds nothing — which is most of them, and also one whose
[tooltip has not landed yet](items.md#an-item-arrives-before-it-can-be-described). A stack of dandelions and
a creel carry real items, each with its own quality and its own server address; a bucket carries what its
tooltip states and no items at all. One object answers for both, because the client is never told which kind
it has — the difference is the server's, and a read that guessed would answer confidently and wrongly.

## Read

| Read | Returns | Description |
|---|---|---|
| `contents:items()` | collection | what is inside, as live objects; **empty**, never `nil`, for a container that states what it holds rather than carrying it |
| `contents:name()` | string \| nil | what the server calls this inside — the caption its own window carries; `nil` when it gave none |
| `contents:text()` | string \| nil | the line the tooltip states about what is inside; `nil` for a container carrying items |
| `contents:quality()` | number \| nil | the **content's** own quality, which is not `item:quality()`; `nil` when none is stated |
| `contents:fill()` | table \| nil | the fill meter's `{cur, max}`; `nil` for a container that draws none |
| `contents:info()` | table | the [snapshot](../types/items.md#contents), which carries no `items` |

Reading is unprotected, and a `Contents` is **interned** like every other object here, so two reads of one
item's contents are `==`. It answers `nil` while the item's info is still resolving, never a half-built
object — so `nil` means "holds nothing" and an empty `:items()` means "an empty container".

```lua
for _, it in ipairs(hafen.session():current():ui():inventory():items():list()) do
  local held = it:contents()
  for _, one in ipairs(held and held:items():list() or {}) do
    hafen.log():write((one:name() or "?") .. " q" .. (one:quality() or 0)   -- its own quality...
                      .. " in " .. (it:name() or "?"))                      -- ...not the stack's
  end
end
```

**`item:container()` is the exact inverse.** `a:contents():items()` holds `b` if and only if `b:container()`
is `a`, and it chains: a dandelion in a stack in a creel answers the stack, and the stack answers the creel.
It is a **where** read, so like `:cell()`, `:slots()` and `:handle()` it answers `nil` on a stale item —
where it is is exactly what a departed item no longer has.

**A contained item is not in `widget:items()`.** A stack is one item there, as it is one cell on screen:
flattening it would break `:cell()` and `#items` as the count of slots used, and delete the difference between
one stack of eight and eight loose things. So a thing inside answers `:cell()` as `nil` too — it is drawn no
cell of its own — and you reach it by recursing through `:contents()`, picking your own depth. The protected
verbs reach it like any other item: `:take()` on one dandelion lifts that one, and `:take()` on the stack
lifts the whole pile in one message.

## A liquid container: a stated line, a fill, and no items

A bucket, a jug, a barrel holds something and carries no items, so the other three reads answer instead:

```lua
local b = hafen.session():current():ui():inventory():items():list()[1]   -- a jug holding water
local c = b:contents()

c:text()                   --> "4.55 l of Water"   the line its tooltip states
c:quality()                --> the water's quality, and b:quality() is still the jug's
c:fill()                   --> { cur = 455, max = 500 }   the fill meter, in its own scale
c:items()                  --> { }                 it states what it holds; it does not carry it
```

`c:fill()` is how you ask how full something is, and the two counts are the ones behind the bar drawn on
the item's icon — the client itself paints only the fraction of them, so this is the one place they read as
numbers. **They are the meter's own scale and not the units the line states**: divide one by the other and
compare fractions, rather than reading `cur` as the number in front of the `l`. A stack answers `nil` to
`:text()` and `:fill()`, and a container that states what it holds answers an empty `:items()`, so the two
insides are told apart by asking rather than by knowing which you hold.

**The substance is never named to the client.** What arrives is that rendered line, a quality and a fill:
there is no water type behind them to ask for instead, so `c:text()` is the whole of what can be said about
what is in there. Match on the line if you must, knowing it is a display string carrying the amount as well
as the name.

**Nothing has to be open.** The window a container pops up under the pointer is hidden rather than destroyed
when you move away, so every read here answers the same with it down. Opening it is not something an addon can
do either: the message that pins it open is the server's answer to a right-click, not anything the client
sends.

## Where these reads end

What an item holds is what the **server** pushed with it, and it pushes it for the containers you carry: a
stack, a creel, a bucket. An item it sent nothing for reads `nil`, and no message the client can send asks for
one — a chest standing in the world is opened, and read as the container widget it becomes.

**An item on the cursor is one of those.** The one you are carrying arrives as a widget of its own under the
HUD rather than as the icon you lifted, and nothing is attached to it: a stack in your hand answers `nil` to
`:contents()`, and — having no quality of its own, since a stack's quality is its parts' — `nil` to
`:quality()` as well. Both read again the moment it lands somewhere. So a decoration that reads what an item
holds shows nothing while it is being carried, and there is nothing to wait for: it is not late, it was never
sent.

## See also

- [items](items.md) — the `Item` object this reads off, and the verbs on one
- [`Contents`](../types/items.md#contents) — the snapshot `:info()` hands back
- [`session:player`](../player.md#the-hand) — the cursor, which carries an item and nothing under it
- [widget](widget.md) — the container the items themselves are read from
