# Gob Materials

Read which materials a game object is drawn in. Many objects — cupboards, chests, carts, boats, walls — are one model drawn in **variable materials**: the server sends one material resource per *slot*, and the client wraps each part of the model tagged with that slot in it. `gob:materials()` is the collection of those slots. It is client-local and **unprotected**.

```lua
local session = hafen.session():current()
local cupboard = session and session:world():gob():nearest("terobjs/cupboard")

if cupboard then
  for _, slot in ipairs(cupboard:materials():list()) do
    hafen.log():write(("slot %d: %s"):format(slot:index(), slot:native():name()))
  end
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

`gob:materials():list()[n] == gob:materials():get(n)`: a slot is interned per object and position, so `==` compares handles and a slot works as a table key.

---

## Methods on `MaterialSlot`

A `MaterialSlot` is a live handle. It reads through the object every call: the resource reads answer `nil` once the object is gone or the server has re-sent it with fewer slots; `:index()` and `:wire()` always answer.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `slot:index()` | `number` | Unprotected | The 1-based position, the number `:get(n)` takes. |
| `slot:wire()` | `number` | Unprotected | The server's own number for the slot, `index - 1` — the `vm` tag on the model's part. |
| `slot:native()` | `Resource \| nil` | Unprotected | The material the server dressed the slot in, as a [`Resource`](resource/README.md) handle. |
| `slot:material()` | `Resource \| nil` | Unprotected | The material in force on the slot. |
| `slot:drawn()` | `Resource \| nil` | Unprotected | The material the model is drawn with right now, on the copy this handle reads through. |
| `slot:info()` | `table \| nil` | Unprotected | `{index, wire, native, material, drawn, id}` — the three resources as names, `id` the material layer's number within its resource. |

The three resource reads hand back the interned `Resource` handle for the name, so `slot:material() == slot:native()` compares handles. The server dressed the slot, so `slot:native():loaded()` is `true` on every slot the object has. Until an addon writes a slot, the three read the server's material.

---

## See Also

- [Gob](gob.md) — the object the slots belong to.
- [Gob Look & Visual Overrides](look.md) — the other client-local overrides on an object.
- [`hafen.resource`](resource/README.md) — the `Resource` handle and its [layers](resource/layers.md), where a material is a `mat2` layer.
- [Conventions](conventions.md) — collection verbs and filters.
