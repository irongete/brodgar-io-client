# 021-actionbar-oop — Plan

## Approach
**Mirror 017/020 exactly** — the Gob and Kin migrations already solved every mechanism this needs; 
the delta is that actionbar is a **fixed-size collection** (always 144 slots, never changes size) 
rather than dynamic.

- **`LuaSlot`**, a new class beside `LuaGob` and `LuaKin`: `LuaValue.userdataOf(Integer index, mt)` 
  with a **per-addon** metatable whose `__index` is the method table. Userdata ⇒ immutable from Lua, 
  `==` and table-key identity come from `raweq`/`hashCode` on the interned instance.
- **Interning per addon** (D-045): `LuaSlot.of(owner, index)` over a `Map<Integer, WeakReference<LuaValue>>` 
  + `ReferenceQueue` held **on the `Addon`** (never static — it must die with the env on `:reload`).
- **Wraps the index only.** Every method re-reads through one funnel, `gui().belt[index]`, so a 
  stashed Slot always tracks current state and goes `:empty() == true` when cleared. `actionbarSnapshot` 
  survives only as `:info()` and as the event-diff input.
- **`hafen.actionbar` = an empty `LuaTable` with `mt.__call`** (017/020): `hafen.actionbar(...)` works 
  while `hafen.actionbar.slot` reads as plain `nil`. Arity dispatch inside `__call`: **no arg → slots table**, 
  `isnumber()` → Slot by index (0..143), throw if out of range.
- **The slots table** is a fresh `LuaTable` per call: array part = Slot objects (indices 0..143), 
  always 144 elements. No methods on the array part so `#`/`ipairs` are exact. No `:find()` or 
  `:list()` — the array is always fully populated as objects.
- **Writes** keep today's `requireActions` gating and wdgmsg wrapping (D-009/D-027), but hang off 
  the object and `return self` (the userdata) so they chain. `:use(mods)` validates the slot 
  is not empty before activating.
- **`ActionbarChanged`** keeps its per-slot event but fires with a single `Slot` object (not an 
  array), `hasSub`-gated.

## Files to create / modify
- `src/io/brodgar/addon/LuaSlot.java` — **new**: the userdata handle, `of(owner, index)`, method 
  table, the `gui().belt[index]` resolve funnel
- `src/io/brodgar/addon/Addon.java` — the per-addon Slot intern cache + metatable (beside Gob/Kin ones)
- `src/io/brodgar/addon/CharApi.java` — `installActionbar` rewritten (callable table, slots array, 
  methods); `actionbarSlot/Snapshot/ResObj/Name/Cooldown` fold into the object's methods; 
  `actActionbarUse` becomes `:use()` on the Slot
- `src/io/brodgar/addon/AddonManager.java` — fire `ActionbarChanged` with a single `Slot` object 
  (per-addon, `hasSub`-gated) at the event site
- `docs/addons/api/actionbar.md` — rewritten to the object surface; `types.md` (ActionbarSlot → 
  `slot:info()`), `conventions.md`, `actions.md`, `events.md`, `README.md` — cross-refs
- `addons/hello/main.lua` — add slot reads + maybe a demo use (gated)
- **No `haven` file is touched** — the belt widget and its draw loop stay in `haven`.

## Risks & gotchas
- **`WeakHashMap` is the wrong tool** (weak *keys*): use `Map<Integer, WeakReference<LuaValue>>` + 
  a `ReferenceQueue` drained on **every** access, the `WeakReference` subclass carrying its own key, 
  and check `live.get(key) == thatRef` before unmapping. Without the drain a per-tick sweep leaks 
  tens of thousands of entries (017/020, `learnings/luaj-bridge.md`).
- **`isstring()` is true for NUMBERS in LuaJ** — the arity dispatch must test `isnumber()` **first**, 
  or `hafen.actionbar("42")` fails silently.
- **`__call` receives the table itself as arg1** — real params start at `a.arg(2)`.
- **`gui().belt` is null before the HUD exists** and during `:reload`; every read must tolerate it 
  (return a Slot whose `:empty()` is true and `:res()` is nil).
- **Out-of-range index (< 0 or ≥ 144) should throw** — the spec bounds-checks, unlike kin which 
  tolerates any id.
- Slot reads run on the UI thread only (REPL/tick/draw callbacks); `belt[n]` cannot throw `Loading`, 
  but always returning a Slot object (even for empty/OOB) means the roster array is never sparse.
- Mid-feature breakage is expected and fine: 021.1 deletes the flat API, so `hello` is updated in 
  the same task rather than left compiling against a dead surface.

## Discarded alternatives
- **Keep `hafen.actionbar.slot` beside the objects** — the dual style D-013 forbids; 017/020 set 
  the precedent.
- **Sparse array** (skip empty slots) — loses the stable 0..143 indexing the game uses everywhere; 
  the fixed dense array matches the game's mental model.
- **Return nil for OOB** — would make `hafen.actionbar(150)` return nil, breaking the 
  `hafen.actionbar(n)` → Slot contract; throwing is clearer.
- **`:has()` instead of `:empty()`** — rejected at spec review: `:empty()` is more intuitive 
  (`if not slot:empty()` to verify content).
