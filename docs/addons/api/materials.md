# Gob Materials

Read which materials a game object is drawn in, dress a slot in another, and hand it back. Many objects — cupboards, chests, carts, boats, walls — are one model drawn in **variable materials**: the server sends one material resource per *slot*, and the client wraps each part of the model tagged with that slot in it. `gob:materials()` is the collection of those slots. A write is client-local, purely visual and **unprotected**: nothing goes on the wire or to disk.

```lua
local session = hafen.session():current()
local cupboard = session and session:world():gob():nearest("terobjs/cupboard")

if cupboard then
  local slots = cupboard:materials()
  for _, slot in ipairs(slots:list()) do
    hafen.log():write(("slot %d: %s"):format(slot:index(), slot:native():name()))
  end
  -- Preview the first slot in the last one's material: the swap lands once the client holds the resource.
  slots:get(1):material(slots:get(slots:count()):native():name())
end
```

---

## Methods on `gob:materials()`

The collection is a **view**: derived from the object on every call, holding nothing between them, so it cannot outlive the object and needs no clean-up. An object with no variable materials — a tree, a boulder, a player or animal — counts `0`; a gone object lists nothing. A string filter matches the **name of the resource in force** on a slot.

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:list([filter])` | `string \| function` | `MaterialSlot[]` | Unprotected | Every slot the server sent, in slot order. |
| `:count([filter])` | `string \| function` | `number` | Unprotected | How many slots there are. |
| `:find(filter)` | `string \| function` | `MaterialSlot \| nil` | Unprotected | The first slot that matches. |
| `:get(n)` | `number` | `MaterialSlot \| nil` | Unprotected | The slot at 1-based position `n`; `nil` past the count. `0` is refused naming the 1-based rule; a fractional or non-number `n` is refused. |
| `:release()` | None | `MaterialSlotCollection` | Unprotected | Hands every slot your addon dressed on the object back to the server's material. Chains. See [The endings](#the-endings). |

`gob:materials():list()[n] == gob:materials():get(n)`: a slot is interned per object and position, so `==` compares handles and a slot works as a table key.

---

## Methods on `MaterialSlot`

A `MaterialSlot` is a live handle. It reads through the object every call: the resource reads answer `nil` once the object is gone or the server has re-sent it with fewer slots; `:index()` and `:wire()` always answer.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `slot:index()` | `number` | Unprotected | The 1-based position, the number `:get(n)` takes. |
| `slot:wire()` | `number` | Unprotected | The server's own number for the slot, `index - 1` — the `vm` tag on the model's part. |
| `slot:native()` | `Resource \| nil` | Unprotected | The material the server dressed the slot in, as a [`Resource`](resource/README.md) handle. |
| `slot:material()` | `Resource \| nil` | Unprotected | The material in force on the slot: yours once written, else the server's. |
| `slot:material(name[, id])` | `MaterialSlot` | Unprotected | Dresses the slot in the `mat2` layer of the resource named `name` — `id` a whole number naming which layer, the resource's first by default. Chains. See [The write](#the-write). |
| `slot:release()` | `MaterialSlot` | Unprotected | Hands the slot back to the server's material. Chains. See [The endings](#the-endings). |
| `slot:drawn()` | `Resource \| nil` | Unprotected | The material the model is drawn with right now, on the copy this handle reads through. |
| `slot:info()` | `table \| nil` | Unprotected | `{index, wire, native, material, drawn, id}` — the three resources as names, `id` the layer number of the material in force within its resource (absent while a write that named none is still loading). |

The three resource reads hand back the interned `Resource` handle for the name, so `slot:material() == slot:native()` compares handles. The server dressed the slot, so `slot:native():loaded()` is `true` on every slot the object has. Until you write a slot, the three read the server's material.

---

## The write

`slot:material(name[, id])` takes a resource name — the string `resource:name()` answers, under [`hafen.resource`](resource/README.md)'s rule for a well-formed name — and dresses the slot in that resource's `mat2` layer. It is accepted at once and hands the slot back; `slot:material()` reads the written name from that moment. The swap lands on the next frame when the client holds the resource, and the client fetches one it does not hold: `slot:drawn()` says which material the model is drawn with meanwhile.

| The client's state | `material():loaded()` | `material():error()` | `drawn()` |
|---|---|---|---|
| Holds the resource | `true` | `nil` | the written material, from the next frame |
| Still fetching it | `false` | `nil` | the server's material, until the fetch lands |
| The fetch failed (a name the server has not got) | `false` | the client's message | the server's material |
| Loaded, but no `mat2` layer at `id` | `true` | `nil` | the server's material |

**Last write wins per slot**, across addons: a second `slot:material(name)` replaces the first, on every copy. **The write lands on the object, not on one copy**: every live session's copy of the object is dressed, a session that loads the object later dresses its copy on arrival, and the object's own re-sent dressing replaces nothing of yours. How it ends is [below](#the-endings).

Refused when made:

| Call | Refused because |
|---|---|
| `slot:material(nil)` | An explicit `nil` names no material; handing a slot back to the server's material is `slot:release()`. |
| `slot:material(42)` | The name is a string. |
| `slot:material("gfx//x")` | The name is malformed (an empty segment, a `..` segment, a leading `/`). |
| `slot:material(name, 1.5)` | `id` is a whole number. |

`gob:info().materials` reads the names in force per slot, so a written slot reads your name there.

---

## The endings

`slot:release()` hands the slot back to the server's material and chains; `gob:materials():release()` does it for every slot your addon dressed on the object. Both drop the write on every live copy and from what a later session's copy is dressed with on arrival, so `slot:material()` reads `slot:native()` again at once and `slot:drawn()` follows on the next frame. Both are no-ops that still chain on a slot that is not yours — never written, released already, or dressed by another addon, whose write is that addon's to release.

| Ending | What it releases |
|---|---|
| `slot:release()` | Your write on this one slot. |
| `gob:materials():release()` | Your every write on this object. |
| Your addon reloads or unloads | Your every write on every object, in every session — the rule every [visual override](look.md) keeps. |
| The object leaves its last session | Every addon's writes on it: an object that unloads and streams in again is a new object, dressed by the server. |

```lua
local session = hafen.session():current()
local chest = session and session:world():gob():nearest("terobjs/chest")

if chest and chest:materials():count() > 1 then
  local slots = chest:materials()
  -- Preview the first slot in the last one's material, then hand the whole object back.
  slots:get(1):material(slots:get(slots:count()):native():name())
  hafen.timer():after(5, function() slots:release() end)
end
```

---

## See Also

- [Gob](gob.md) — the object the slots belong to.
- [Gob Look & Visual Overrides](look.md) — the other client-local overrides on an object.
- [`hafen.resource`](resource/README.md) — the `Resource` handle and its [layers](resource/layers.md), where a material is a `mat2` layer.
- [Conventions](conventions.md) — collection verbs and filters.
