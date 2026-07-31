# 020-kin-oop — Plan

## Approach
**Mirror 017 exactly** — the Gob migration already solved every mechanism this needs; the delta is
that Kin also has a *collection* and *gated writes*.

- **`LuaKin`**, a new class beside `LuaGob`: `LuaValue.userdataOf(Integer id, mt)` with a
  **per-addon** metatable whose `__index` is the method table. Userdata ⇒ immutable from Lua for
  free, `==` and table-key identity come from `raweq`/`hashCode` on the interned instance.
- **Interning per addon** (D-045): `LuaKin.of(owner, id)` over a `Map<Integer, WeakReference<LuaValue>>`
  + `ReferenceQueue` held **on the `Addon`** (never static — it must die with the env on `:reload`).
- **Wraps the id only.** Every method re-resolves through one funnel, `buddywnd().find(id)`, so a
  stashed Kin tracks renames/regroups and goes `:exists() == false` when forgotten. `kinSnapshot`
  survives only as `:info()` and as the event-diff input.
- **`hafen.kin` = an empty `LuaTable` with `mt.__call`** (017): `hafen.kin(...)` works while
  `hafen.kin.list` reads as plain `nil`, which is what makes the hard cut visible from Lua. Arity
  dispatch inside `__call` (params start at `a.arg(2)`): **no arg → roster**, `isnumber()` → Kin by
  id, else `isstring()` → Kin by exact case-insensitive name (or nil).
- **The roster** is a fresh `LuaTable` per call: array part = Kin objects in `BuddyWnd` iteration
  order (its `iterator()` copies under the window's own lock), plus a shared per-addon metatable
  whose `__index` carries `find`/`list`/`add` — methods stay off the array part so `#`/`ipairs` are
  exact. `BuddyWnd` absent (pre-HUD) ⇒ empty roster, and `hafen.kin(id)` still returns an object.
- **Writes** keep today's `requireActions` gating and wdgmsg wrapping (D-009/D-027), but hang off
  the object and `return self` (the userdata) so they chain. `setGroup` validates **0..254**.
- **Kin ↔ Gob**: `gob:kin()` reads the `ui/obj/buddy` `GAttrib` off the gob (one `getattr`) and
  mints `LuaKin.of(owner, b.id)`; `kin:gob()` sweeps `OCache` for the gob whose attrib carries this
  id — the `world.nearest` shape, no cache, no index (spec's Out of scope).
- **`KinChanged`** gains a `fireKin` beside `fireGob`: per-addon payload gated by `hasSub`, so
  addons that don't subscribe never mint objects. Change *detection* is unchanged — it keeps
  diffing the internal snapshots (`kinListEqual`), never `BuddyWnd.serial`.

## Files to create / modify
- `src/io/brodgar/addon/LuaKin.java` — **new**: the userdata handle, `of(owner,id)`, method table,
  the `BuddyWnd.Buddy` resolve funnel, `:gob()`
- `src/io/brodgar/addon/Addon.java` — the per-addon Kin intern cache + metatable (beside the Gob one)
- `src/io/brodgar/addon/CharApi.java` — `installKin` rewritten (callable table, roster, methods);
  `kinList`/`kinFind` become internal helpers; `kinSnapshot` feeds `:info()` + the diff;
  `resolveKin`/`requireKin`/`actKinAdd`/`actKinSetGroup` fold into the object's verbs
- `src/io/brodgar/addon/AddonManager.java` — `fireKin` (per-addon `Kin[]`, `hasSub`-gated) at the
  `KinChanged` site
- `src/io/brodgar/addon/WorldApi.java` — `gob:kin()` in the Gob method table
- `docs/addons/api/kin.md` — rewritten to the object surface; `types.md` (KinEntry → `kin:info()`),
  `gob.md` (`:kin()`), `conventions.md`, `actions.md`, `events.md`, `README.md` — cross-refs
- `addons/hello/main.lua` (A6 + the KinChanged block), `addons/walker/main.lua` (`:walker kin`)
- `specs/codebase/services.md` — kin section extended for the gob-side buddy attrib (`/end` writes it)
- **No `haven` file is touched** — the palette guard and the group picker are the maintainer's
  separate `client`-area feature.

## Risks & gotchas
- **`WeakHashMap` is the wrong tool** (weak *keys*): use `Map<Integer, WeakReference<LuaValue>>` +
  a `ReferenceQueue` drained on **every** access, the `WeakReference` subclass carrying its own key,
  and check `live.get(key) == thatRef` before unmapping. Without the drain a per-tick sweep leaks
  tens of thousands of entries (017, `learnings/luaj-bridge.md`).
- **`isstring()` is true for NUMBERS in LuaJ** — the arity dispatch must test `isnumber()` **first**,
  or `hafen.kin(42)` resolves as the name `"42"`.
- **`__call` receives the table itself as arg1** — real params start at `a.arg(2)`.
- **`BuddyWnd.serial` under-reports**: it does NOT bump on `chst` (the online/offline flip), the
  single event a kin addon most wants. Keep the snapshot diff; never "optimise" it to the counter
  (`learnings/gap-subsystems.md`).
- **The `@FromResource(name="ui/obj/buddy", version=4)` pin**: if the server ships v5 the local class
  stops overriding ([Resource.java:1556](src/haven/Resource.java:1556)), the resource's own class is
  loaded, and `getattr(Buddy.class)` returns `null` for every gob — `gob:kin()` goes silently blind.
  Resolve the attrib with a **name-based fallback** (scan `gob.attr` for a value whose class name is
  `haven.res.ui.obj.buddy.Buddy`, read `id` reflectively) so a bump degrades to slow, not to wrong.
- **`BuddyWnd` is null before the HUD exists** and during `:reload`; every read must tolerate it.
- Kin reads run on the UI thread only (REPL/tick/draw callbacks); `getattr` cannot throw `Loading`,
  but the `OCache` sweep must copy under the same discipline `world.gobs` uses.
- Mid-feature breakage is expected and fine: 020.1 deletes the flat table, so `hello`/`walker` are
  updated in the same task rather than left compiling against a dead surface.

## Discarded alternatives
- **Keep `hafen.kin.list` beside the objects** — the dual style D-013 forbids; 017 set the precedent.
- **Rename the namespace (`hafen.roster()`)** — loses discoverability for zero gain.
- **Roster as a handle you must `:list()`** — rejected at review: `hafen.kin()[1]` is the idiom the
  ROADMAP already commits to for `hafen.party()[1]:gob()`.
- **A maintained buddy-id → gob index (O(1) `kin:gob()`)** — out of scope: the attrib is set/cleared
  by the server outside `GobAdded`/`GobRemoved`, so a lifecycle-driven index would go silently wrong.
- **Expose `online` as the raw tri-state** — the boolean is the deliberate A6 call already shipped.
