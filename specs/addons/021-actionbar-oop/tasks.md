# 021-actionbar-oop — Tasks

## 021.1 — LuaSlot + CharApi rewrite (hard cut) — [x] DONE (2026-08-01)
> Shipped as specified, with one addition settled at implementation time (**D-057**): the collection
> `hafen.actionbar()` is a **1-based** 144-element array (LuaJ cannot give a 0-keyed table an honest `#`
> or `ipairs`), and the Slot gained **`:index()`** so the 0-based game index is always on the object.
> `hafen.actionbar(n)` remains the one way to address a slot. `hello`/`walker` were moved off the flat
> API here (the plan folds that into this task); the docs stay for 021.3.

**Goal:** Create the Slot OOP layer, rewire `hafen.actionbar` to the new callable dispatch, 
verify the hard cut (flat API is gone).

**Scope:**
- `LuaSlot.java` — new file, mirror LuaGob/LuaKin shape (`userdataOf`, `of(owner, index)`, 
  per-addon interning over `Map<Integer, WeakReference<LuaValue>>` + `ReferenceQueue`, method table 
  with `:res/:name/:cooldown/:empty/:info/:use`)
- `Addon.java` — add the Slot intern cache + shared metatable (beside gob/kin)
- `CharApi.installActionbar` — rewrite to callable table + no-arg → slots array, `isnumber()` → Slot; 
  fold `actionbarSlot/Snapshot/ResObj/Name/Cooldown` into LuaSlot methods; delete `actionbar.slot` 
  and `actionbar.use` flat entries
- Compile check: `ant hafen-client` → `BUILD SUCCESSFUL`, no errors; `ant run` launches
- Regression: `:lua` — `hafen.actionbar()[1]:empty()` works, `hafen.actionbar.slot` is nil

**In-game verification:** in-game `:lua` REPL — index access, interning, empty slots read as true

## 021.2 — AddonManager + ActionbarChanged event — [x] DONE (2026-08-01)
> Shipped as specified. `fireSlot(int)` mirrors `fireKin` exactly — `hasSub` gate first, then one
> interned `LuaSlot.of(owner, n)` per subscribing owner. The payload is the Slot **alone** (no index
> beside it): `slot:index()` already carries the raw game index (D-057). `addons/hello`'s handler was
> moved over in this task rather than 021.3, since the old one would break on the new payload; the
> `events.md` row + prose were corrected here too, the rest of the docs stay for 021.3.

**Goal:** Wire the event firing to pass Slot objects instead of raw indices, gated by `hasSub`.

**Scope:**
- `AddonManager.java` — at the `ActionbarChanged` fire site, mint a per-addon Slot object + 
  fire with `hasSub` gate (mirror kin's `fireKin`), no longer fire the raw index
- Verify: `:lua` + addon hook — `ActionbarChanged` fires with a Slot object, not a number; 
  `:res()` reads live
- Regression: all prior tests still pass

**In-game verification:** subscribe to `ActionbarChanged`, verify it receives Slot objects

## 021.3 — Documentation + hello addon + verification
**Goal:** Update the user-facing API surface, extend the regression harness, full end-to-end test.

**Scope:**
- `docs/addons/api/actionbar.md` — rewritten for the OOP surface (hafen.actionbar(), hafen.actionbar(n), 
  methods `:res/:name/:cooldown/:empty/:info/:use`, no more `slot(n)`/`use(n)`)
- `docs/addons/api/types.md` — ActionbarSlot shape now `:info()` escape hatch
- `docs/addons/api/conventions.md`, `events.md`, `actions.md`, `README.md` — cross-refs updated
- `addons/hello/main.lua` — add slot read demo (iterate + read a few) + maybe a gated `:use()` demo
- Grep check: `hafen\.actionbar\.` returns zero in `src/io/brodgar/addon/`, `docs/addons/`, `addons/`
- Full regression: one login in-game — prior features all work, hello loads + runs, new slot API 
  works (reads + gated writes)

**In-game verification:** full session — all prior addons functional, new slot API reads + uses work 
under gating
