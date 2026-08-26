# Resources: what a `.res` carries

A `Resource` is the versioned, named asset both sides load by the same id — a tree, a gate, a crop —
built out of typed `Layer`s (`Resource.Layer`). What a gob's state bytes are and how a change to them
reaches the client are both questions about this shape, not about `Gob` itself.

## Reading a layer

| Call | Answers | Absent |
|---|---|---|
| `Resource.layer(Class<L>)` | the first layer assignable to `L` | `null` |
| `Resource.flayer(Class<L>)` | same | throws `Resource.NoSuchLayerException` |
| `Resource.layer(Class<L>, Predicate<? super L>)` | the first matching layer that also passes the predicate | `null` |
| `Resource.layer(Class<L extends IDLayer<I>>, I id)` | the layer of that class whose `layerid()` equals `id` | `null` |

Each has an `f`-prefixed twin that throws `Resource.NoSuchLayerException` instead of answering `null`.
A resource may carry several layers of one class, so the id form is how one of several is named —
`Resource.IDLayer<T>` is the one-method interface (`layerid()`) a layer implements to be found this way.

**`id == null` matches the FIRST layer of that class, whatever its id — it does not mean "the layer
with no id".** Passing `null` where a specific id was meant answers whichever layer happened to load
first, silently. A caller wanting a particular id always passes the id, including a resource's own
default `""`.

`Resource.used` is set the moment any `layer`/`flayer` call runs, for the asset pool's own accounting;
`Layer.init()` runs once every layer of the resource has loaded, for a layer that needs to see the set.

## A gob's state bytes: `OD_RES`

The server tells a gob which resource draws it, and optionally what state that resource is in, in one
delta: `OCache.OD_RES` (`2`), handled by `ResDrawable.$cres` (`@OCache.DeltaType(OCache.OD_RES)`). The
wire carries a resource id, and — only when that id's top bit is set — a length-prefixed byte string
straight after it, the state:

| Resource id on the wire | State bytes follow? |
|---|---|
| bit `0x8000` clear | no — just the resource |
| bit `0x8000` set (cleared before use) | yes — a `uint8` length, then that many bytes |

`$cres.apply` compares the new bytes against `ResDrawable.sdt` (a `MessageBuf`, package-private;
`MessageBuf.equals` is a byte comparison and `MessageBuf.nil` is what a first arrival differs from).
Same resource, changed bytes, and the drawn `Sprite` implements `Sprite.CUpd`: it calls
`((Sprite.CUpd)d.spr).update(sdt)` and reassigns `d.sdt` in place. Anything else — a different
resource, a sprite that cannot take a live update, no `ResDrawable` yet at all — replaces the whole
attribute with `Gob.setattr(new ResDrawable(g, res, sdt, msg.old))`, which is a fresh object with a
fresh `sdt`.

**The state lives on the `ResDrawable`, not on the `Gob`.** A body that is a `Composite` (a player) has
no `ResDrawable` and so no state bytes at all — an absent fact, not an empty one. `ResDrawable.sdt` is
package-private, so `AddonWidgets.gobSdt(Gob)` is the one non-zero-edit read surface for it (decision
D-017); that method clones the field before reading it, since the field is shared with the `Sprite`
built from it and reading it directly would advance that sprite's own cursor.

## The gob monitor already guards it

`$cres.apply` runs from `OCache.GobInfo.apply`, which takes `synchronized(gob)` around every pending
delta of that tick, one at a time, before applying it. A reader that also takes `synchronized(gob)`
around `ResDrawable.sdt` therefore sees either the bytes from before the change or the bytes from
after it, never a torn read, with no `volatile` needed on either side of the seam.

The delta stream is per session: two sessions holding one gob each run this independently, off their
own `OCache`, so a change landing on one session's copy says nothing about when — or whether — the
other session's copy has seen its own.

## See also

- [state roots](state.md) — where the rest of a gob's live state lives
- [boot and the frame loop](boot-and-loop.md) — the `Loading` protocol a resource read can throw into
