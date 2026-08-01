# 025-buffs-oop — Tasks

- [x] 025.1 — **`LuaBuff` + the `hafen.buff` namespace + the hard cut.** New `LuaBuff.java`: userdata
      handle over the `haven.Buff` widget, per-`Addon` weak-valued `Cache` keyed by **widget
      identity** (`IdentityHashMap` + `ReferenceQueue`, `Ref` carries its key, `drain()` on every
      access), metatable (`__index`/`__name`/`__tostring`), methods `:res/:name/:amount/:cooldown/
      :number/:exists/:info`, and the `factory(owner)` callable table: `hafen.buff()` = the active
      buffs 1-based in `Bufflist` order (`buffDest` excluded), `hafen.buff(needle)` = the first whose
      res or name contains it (`nil` on a miss), number-before-string error, empty-string error.
      `Addon.buffs` field; `CharApi.installBuffs` → the one-line install, flat `list`/`has` deleted,
      `buffRes`/`buffName`/`buffSnapshot` moved onto `LuaBuff` (`:info()` keeps the documented table
      shape byte-for-byte; the adapter calls into it). Events still fire snapshots at this point.
      Verify in `:lua`: the array matches the HUD, the lookup returns the *same object*, `hafen.buffs`
      is nil, a `Loading` buff answers `:res()` and nil elsewhere without erroring.

- [x] 025.2 — **The event payloads become the object.** `AddonManager.fireBuff(Buff, event)` beside
      `fireKin`/`fireSlot`: `hasSub`-gated, per-addon interning, UI-thread. `BuffsAdapter` keeps its
      snapshot cache as the change-detection key (`buffEqual`) but fires objects for `BuffAdded`/
      `BuffChanged`/`BuffRemoved`; `refresh`-before-`poll` unchanged. Verify: at login the existing
      buffs arrive as `BuffAdded` objects; a `BuffRemoved` handler that stashes its payload can still
      read `:res()/:name()` a tick later and gets `:exists()` → false; no `BuffChanged` spam at idle.

- [x] 025.3 — **Docs, harness, coverage toll.** `docs/addons/api/buffs.md` rewritten for `hafen.buff`
      (collection + entity tables, `:info()` as the escape hatch, the kept "not seconds" note, and the
      `buff:click()` footnote saying why there is no verb); `api/README.md`, `docs/addons/README.md`,
      `types.md`, `events.md`, `conventions.md` updated. `hello`: `readBuffs` becomes the once-per-
      login contract check (array + lookup hit/miss + identity + `buffsGone` + the number-key error)
      and the three event handlers print from the object; `manifest.json` version bump.
      `specs/codebase/services.md` — extend the Buffs row (the coverage toll for `Buff`/`Bufflist`).
      Verify: one login, `hello`'s buff lines all pass, full regression clean.
