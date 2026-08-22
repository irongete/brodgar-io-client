# 098 — the vr snapshot

Discharges: `audit/ns-vr.md` finding 3.

**Not a row in `audit/INVENTORY.md`**, like [097](../097-three-edges-three-words/spec.md): reported by the
audit, never made a row, still standing when the independent re-audit looked.

## What and why

`docs/addons/api/conventions.md` states the rule without qualification: a read hands back a **live object**,
and *"a point-in-time copy is what `:info()` gives you, and nothing else does"*. Every live object in the
API answers it — a Gob, a Marker, a Wound, a Kin, a Widget, a Position.

The **vr entities** did not. Nothing in `VrApi.java` or `LuaWorldEntity.java` set an `info` verb, and
`types.md` carried no entity shape. So logging what an addon has standing in the world cost one call per
reader, and there are ten shared ones plus the per-kind:

```lua
for _, e in ipairs(hafen.vr():sprite():list()) do
  hafen.log():write(("%s vis=%s alpha=%.2f scale=%.2f image=%s"):format(
    tostring(e:position()), tostring(e:visible()), e:alpha(), e:scale(), tostring(e:image())))
end
```

Five calls, and five readers still unread. `addons/profiler` and any debug dump paid it.

## What it is

`e:info()` on all four kinds, carrying the ten shared readers plus the one or two only that kind answers,
**each key spelled the way its verb is**, so the snapshot and the vocabulary cannot drift into naming one
thing twice.

| Always | `kind` `position` `rotate` `scale` `alpha` `visible` `clickable` `exists` `drawn` |
|---|---|
| When set | `tint` — absent until one is laid over it |
| When anchored | `anchor` (the gob id) and `offset` — absent for one that stands still, exactly as `:offset()` itself raises there |
| Per kind | `res` (ghost) · `mesh` (object) · `image` + `facing` (sprite) · `facing` (panel) |

**The place comes out flat**: `position` is the `{gridId, x, y}` table `p:info()` answers, read *through*
that very verb rather than rebuilt beside it — a live object inside a snapshot is not a snapshot.

**`panel:screen(x, y)` has no field.** It is the other verb only that kind answers, and it projects a point
you pass in, so there is no value of it to photograph. `:onClick(fn)` is a callback slot rather than a fact,
and is likewise absent.

## Shape

Additive and nothing else: no verb retires, no payload changes, no addon already written notices.

The per-kind half is an **abstract** `LuaWorldEntity.infoInto(LuaTable)`, implemented by all four
subclasses, rather than a switch in `VrApi`. Abstract so that a fifth kind cannot be added without the
question being answered; and beside `kind()`, `visualName()` and `clickEvent()`, which is where the
per-kind hooks already live.

`:info()` is registered **before** the `extra` merge, which is what would let a kind override it — none do,
and the ordering is the collection's own rule rather than a special case.
