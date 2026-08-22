# 098 — the vr snapshot: plan

## Approach

One verb, four kinds, one suite. It rides with [097](../097-three-edges-three-words/plan.md) in the same
session because they are both leftovers of the same re-audit, but they share no code and no page.

The shared ten live in `VrApi.entityHandle`, where the verbs they mirror already are. The per-kind one or
two go behind an abstract hook on `LuaWorldEntity`, beside the three per-kind hooks that already exist.

Every field is read from the same expression its verb reads, inside the same `synchronized(e)` block, so
the two cannot answer differently. `position` goes further and calls `p:info()` itself rather than
rebuilding `{gridId, x, y}` — the one field with a shape of its own is the one worth not copying.

## Gotchas found while doing it

**`panel:screen(x, y)` looked like a per-kind reader and is not.** It takes the point to project, so it has
no value to snapshot. Reading the verb rather than the vocabulary string is what caught it — the refusal
hint lists `:facing()` and `:screen()` side by side as though they were the same kind of thing.

**There is no `e:destroy()`.** An entity ends through the collection of its kind,
`hafen.vr():ghost():remove(e)`, which `audit/ns-vr.md` finding 5 explicitly calls the right call. The
suite's first draft assumed the verb existed; the docs said otherwise.

**A colour is a table, and loose components raise.** `e:tint(255, 0, 0, 128)` is refused on purpose
(`shapes.md`: *"Loose components are not a colour"*), so the suite writes `e:tint({255, 0, 0, 128})`.

## Discarded alternatives

- **A `switch` on `kind` inside `VrApi`** instead of an abstract hook. It puts the per-kind knowledge in a
  fifth place, and it lets a new kind be added with no compiler complaint about the snapshot it forgot.
- **Embedding the Position object** under `position`. It reads well and it is wrong: `:info()` is *the*
  point-in-time copy, and a live object inside one goes on changing after the photograph.
- **Flattening the place to `x` / `y`**, the way `gobSnapshot` does. Rejected because a vr entity's place is
  durable — the whole point of `:position()` there is a grid anchor that survives a relogin — and `x`/`y`
  alone are session-local and [never storable](../../docs/addons/api/map/grids.md).
- **Always emitting `tint`, `anchor` and `offset` as `nil`.** A key present with no value is not the shape
  any other `:info()` in the API has, and in Lua a `nil` field and an absent one are the same read anyway,
  so the only thing it would add is a longer table in a log.
- **Including `onClick`.** It is a callback slot; a function in a snapshot is not a fact about the entity.
- **Adding `:info()` to the `hafen.vr()` section itself**, summarising what is standing. Not the finding,
  and the collections already answer `:count()` and `:list()`.
