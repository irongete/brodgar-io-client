# 152 — Tasks

- [x] **152.1 — `gob:materials()`: the slots read, and the sprite map.** Adopt `lib/vmat` verbatim
      under `src/haven/res/lib/vmat/` (`@FromResource(name = "lib/vmat", version = 39)`, the four
      classes, `Obstacle.java`'s header note). Add `LuaMaterials` (the collection: `:list([filter])`,
      `:count([filter])`, `:find(filter)`, `:get(index)`, a view built per call over
      `gob.getattr(VarMats.class)` cast to `AttrMats`, filter on the name in force) and
      `LuaMaterialSlot` (interned per addon on login/gob/wire: `:index()`, `:wire()`, `:native()`,
      `:material()`, `:drawn()`, `:info()` — in this task the three resource reads all answer the
      server's `ResMaterial.res`, as `LuaResource.of`). `gob:materials()` on `LuaGob`; `materials` in
      `gobSnapshot`. Write `docs/addons/api/materials.md` (the reads; the write's rows come in 152.2),
      the rows in `gob.md`, `look.md`, `types/world.md`, `api/README.md`, and `docs/client/gob-sprites.md`
      with its `README.md` row and `state.md`'s re-pointed link. Criteria 1, 2, 6, 7.
      *Its suite* finds the nearest gob with `count() > 0` (retrying on a timer over a bounded window
      — the maintainer stands by a cupboard or chest) and asserts: `list()[n]:index() == n`,
      `wire() == index - 1`, `get(n) == list()[n]` (interned), `native():name()` is a string and
      `native():loaded()` is `true` (the server dressed it, so the client holds it), `material() ==
      native()` and `drawn() == native()`, `info().native == native():name()`, `count(native name)`
      ≥ 1, `find` by that substring answers slot 1, `get(count + 1)` is `nil`; the player's own gob
      counts `0`; refusals: `get(0)` names "1-based" and `wire()`, `get("a")` and `get(1.5)` name a
      whole number. `gob:info().materials[1] == native():name()`, and absent on the player.
      `[manual]`: none.

- [x] **152.2 — The write: `slot:material(name[, id])` and what is drawn.** Add `GobMaterials`
      (`GAttrib` + `ModSprite.Mod`, `order() 101`, immutable per-wire entries minted per write, `operate`
      removes the slot's `VarWrap.Applier` and adds its own; `Loading` left to the sprite's retry,
      `BadResourceException` and a missing `Material.Res` caught and recorded as "server's drawn"),
      `Gob.updated()` made public (`// addon:`), the verb on `LuaMaterialSlot` (name through
      `ResourceApi.name`, `id` through `Args.integer`, explicit `nil` refused naming `:release()`),
      applied to every `gobCopies(id)` and recorded in `GobIntent` (`Record.materials`, `applyTo`,
      `forget`). `:material()` answers the written resource, `:drawn()` what the last `operate`
      applied, `:info()` both. The write's rows and its loading/failure table on `materials.md`;
      `gob-sprites.md` gains the identity and `Loading` gotchas. Criteria 3, 4.
      *Its suite* takes the dressed gob of 152.1, reads `slot = get(1)`, picks a second material name
      — another slot's or another dressed gob's `native():name()` that differs from slot 1's, else the
      run reports `[manual]` for the look alone — and asserts: `slot:material(name)` chains and
      `material():name() == name` at once while `native()` is unchanged; on a timer, within a bounded
      window, `drawn():name() == name` (the swap landed); `info().material == name`;
      `gob:info().materials[1] == name`; a second write wins (`material()` follows it); a name the
      server has not got (`"gfx/terobjs/no-such-material"`) is accepted, `material():error()` is
      non-nil within the window and `drawn()` stays the server's; refusals: `material(nil)` names
      `release`, `material(42)` names a string, `material("gfx//x")` names the name rule,
      `material(name, 1.5)` names a whole number. `[manual]`: look at the object — expect: slot 1's
      part drawn in the second material, the rest unchanged.

- [x] **152.3 — The endings: `release`, teardown, and the object's own end.** Add `slot:release()`
      and `gob:materials():release()` (both drop entries by owner on every copy and in `GobIntent`,
      chain, no-op when nothing is yours), `GobMaterials.revert(g, addon)` in
      `UiApi.teardownGobScales`' per-gob loop, and the drop in `GobIntent.forget`. The `Disable`/reload
      rule and the object's end stated on `materials.md` in `look.md`'s words. Criterion 5.
      *Its suite* dresses slot 1 as 152.2 does, waits for `drawn()` to follow, then asserts:
      `slot:release()` chains and `material() == native()` at once; within the window `drawn() ==
      native()`; `release()` again is a no-op that chains; the collection's `release()` after two
      slots written brings every `material()` back to `native()`; `gob:info().materials` reads the
      natives. The teardown and the late-arriving copy are verified by reading the sites
      (`teardownGobScales`, `applyTo`): the suite runs inside the addon it would have to disable.
      `[manual]`: dress slot 1 (`:t152` leaves it dressed), then `:reload` — expect: the object wears
      the server's material again, no console error.
