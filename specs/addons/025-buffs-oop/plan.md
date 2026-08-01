# 025-buffs-oop — Plan

## Approach

A new `LuaBuff` on the template `LuaSound`/`LuaSlot` set: `LuaValue.userdataOf(luaBuff, mt)`,
`mt.__index` = a per-`Addon` methods table, `__name`/`__tostring` = `Buff(<res>)`. The handle's whole
state is the `haven.Buff` **widget reference** — every read goes through it live, nothing is cached on
the handle (learnings/luaj-bridge: *interned userdata is an identity, not a record*).

**Interning key = the `Buff` widget object itself** (identity), in a per-`Addon` `Cache` with **weak
values + a `ReferenceQueue`** — an `IdentityHashMap<Buff, Ref>`, never a `WeakHashMap` (wrong axis).
This is the first cache keyed by an object rather than a name/index/id; the `Ref` subclass carries its
`Buff` key so the drain can unmap it, same as `LuaSound.Ref`. Strong keys are deliberate and bounded:
an entry outlives its buff only until the next access drains it, and a `Buff` the addon still holds a
handle to is *meant* to stay readable (that is what makes the `BuffRemoved` payload work).

Reads are the existing statics moved onto the object, unchanged in behaviour and still
`Loading`-guarded: `:res()` = `buffRes`, `:name()` = `buffName`, `:amount()/:cooldown()/:number()` =
the three `ItemInfo.find`s currently inlined in `buffSnapshot` (split into one accessor each, with
`buffSnapshot` re-expressed over them so there is one source of truth), `:info()` = `buffSnapshot`
verbatim (the documented `Buff` table shape). `:exists()` = the buff is a current `Bufflist` child and
not `AddonWidgets.buffDest` — i.e. the same predicate `hafen.buff()` filters on, so a removed or
fading buff answers false while its reads keep working.

`hafen.buff` is a callable table (`__call`), no fields: no argument ⇒ the 1-based array of interned
active Buffs in `Bufflist` child order; a string ⇒ the first active buff whose res **or** name
contains it (the old `has()` predicate, now returning the object), `nil` on a miss; a number ⇒ a
`LuaError` naming the string key — checked **before** `isstring()`, since in LuaJ a number *is* a
string; empty string ⇒ error. `installBuffs` shrinks to `hafen.set("buff", LuaBuff.factory(owner))`.

Events: `AddonManager.fireBuff(Buff, String event)` beside `fireKin`/`fireSlot` — `hasSub`-gated
(buffs stream in a burst at login), interning per addon (D-045), so each subscriber gets *its own*
object for the same widget. `BuffsAdapter` keeps its `IdentityHashMap<Buff, LuaValue>` **snapshot**
cache exactly as is — the snapshot stays the change-detection key (`buffEqual`), only the payload
changes from that snapshot to `fireBuff(b, …)`. `BuffRemoved` fires the object for the widget it just
dropped, which still answers its reads and now reports `:exists()` false.

## Files to create / modify

- `src/io/brodgar/addon/LuaBuff.java` — **new**: the entity, its per-addon `Cache`, the metatable,
  the `factory(owner)` callable table, and the active-buff scan (`bufflist().children(Buff.class)`
  minus `buffDest`) both entry points share.
- `src/io/brodgar/addon/Addon.java` — one `final LuaBuff.Cache buffs` field, like `sounds`/`slots`.
- `src/io/brodgar/addon/CharApi.java` — `installBuffs` becomes the one-line factory install; the flat
  `list`/`has` closures deleted; `buffRes`/`buffName`/`buffSnapshot` move to `LuaBuff` (keep
  `buffEqual`/`luaFieldEq` where the adapter lives); `BuffsAdapter` fires objects.
- `src/io/brodgar/addon/AddonManager.java` — `fireBuff`; the `hafen.buffs.*` comment block at the
  `installBuffs` call site updated.
- `addons/hello/main.lua` (+ `manifest.json` version) — `readBuffs` becomes the contract check
  (array, lookup hit/miss, identity, `hafen.buffs == nil`, a number key erroring) and the three event
  handlers use the object.
- `docs/addons/api/buffs.md` — rewritten as the `hafen.buff` page (entity + collection tables, the
  `:click()` footnote, the "no seconds" note kept); `api/README.md` + `docs/addons/README.md` rows,
  `types.md` (`Buff` = what `buff:info()` returns), `events.md` (payload = `Buff` object),
  `conventions.md` (its `buffs.has` mention).
- `specs/codebase/services.md` — extend the Buffs row (`Bufflist` child order, `Buff.dest`,
  `Widget.destroy` does not clear `res`/`info`) — the coverage toll for reading `Buff`/`Bufflist`.
- No `haven` core edit: `AddonWidgets.buffDest` already exists and is all the non-public access needed.

## Risks & gotchas

- **`Buff.info()` throws `Loading` and is `emptyList()` until the first `"tt"`** (learnings/
  widget-tree-reads): every read stays in its own try/catch returning `nil` — a partial buff (res
  only) is normal for a beat after it appears, and must never throw into Lua.
- **`res` is mutable**: a `"ch"` uimsg replaces `Buff.res`, so `:res()` must read through the widget
  every call and the intern key must NOT be the res name (a second reason beyond duplicates).
- **Removed-but-readable is the whole point**: `Widget.destroy()` unlinks, it does not null `res` or
  `info`; the check is that a `BuffRemoved` handler stashing the object still reads it a tick later.
- **Strong keys in the cache**: forgetting to `drain()` on every access pins destroyed `Buff` widgets
  for the session — the exact leak the 017 learning describes, one axis over.
- **`refresh` before `poll`** must stay (a brand-new buff = one `BuffAdded`, not `BuffChanged`-then-
  `BuffAdded`); the payload change must not reorder that.
- **The array is not `#`-safe if built from a stale scan** — build it in one pass over the live
  children, like `hafen.actionbar()`.

## Discarded alternatives

- Key by resource name (the `LuaSound` shape) — two buffs can share a res, and `"ch"` mutates it.
- Key by `Widget.wdgid()` — a real server id, but it buys nothing over identity here (the addon
  never sees or types a buff id) and adds a lookup that fails for a destroyed widget.
- Keep `hafen.buffs` plural with `()` — every other OOP namespace is singular; the rename is what
  makes the hard cut legible from Lua.
- A collection object with `:find/:has/:list` (the `menugrid` catalogue shape) — the active set is
  ~5 items with no catalogue behind it; a plain array + the lookup arity covers it with one way each.
- Keep the snapshot as the `BuffRemoved` payload (object for add/change only) — two payload types for
  one event family is exactly the dual style D-012 forbids.
