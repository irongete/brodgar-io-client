# 025-buffs-oop — Spec

## What & why

`hafen.buffs` is still a flat snapshot table — `list()` hands out `{res,name,amount,cooldown,number}`
tables and `has(needle)` answers a bool. It is one of the namespaces the ROADMAP's "finish the OOP
migration" block names, and the last **widget-tree-backed** read surface that is still flat now that
vitals/actionbar have been dealt with. This feature migrates it the way 017/020/021/023/024 did:
**arity is the verb** (D-056), the entity owns its reads, the flat table is hard-cut (D-013), and the
`Buff*` events stop shipping snapshots and start shipping the object.

Delta over [design/14-widget-tree-reads.md](../design/14-widget-tree-reads.md): the `BuffsAdapter`
mechanism (poll for add/remove, `"ch"`/`"tt"` uimsg for content) is unchanged — only what it hands to
Lua changes, from a `LuaTable` snapshot to an interned `LuaBuff`.

The shape:

- `hafen.buff()` — the **active** buffs as a 1-based array of Buff objects (bar order = the
  `Bufflist` child order; a buff fading out after removal, `Buff.dest`, is excluded as today).
- `hafen.buff(needle)` — the **first active buff whose res or name contains `needle`** (the old
  `has()` as a lookup, so `if hafen.buff("poison") then` reads as before), `nil` when none.
  A number is an error naming the string key; the empty string errors.
- The Buff object: `:res() :name() :amount() :cooldown() :number() :exists() :info()`.
  Per-addon interned on the `Buff` **widget identity** (weak values), so `hafen.buff("x") ==
  hafen.buff("x")` and `seen[buff] = true` work while the buff is up.
- `BuffAdded`/`BuffRemoved`/`BuffChanged` payload = the Buff object (`fireBuff`, `hasSub`-gated,
  like `fireKin`/`fireSlot`). A **removed** Buff keeps answering `:res()/:name()/...` — the widget
  object is still referenced even after `destroy()` unlinks it — with `:exists()` false, so
  `BuffRemoved` stays as useful as the snapshot payload it replaces.
- `:exists()` is right here (unlike `hafen.sound`, D-060): a buff has a lifetime and goes away.
- `:info()` is the one snapshot escape hatch and keeps the `Buff` table shape `types.md` documents.
- No `hafen.buffs` any more — the flat namespace is gone, not aliased.

## Acceptance criteria

- [ ] In `:lua`: `hafen.buff()` returns the buffs on the HUD (count matches the bar); each entry
      answers `:res()`, `:name()`, `:exists()` → true, and `:info()` gives the old snapshot table.
- [ ] `hafen.buff("<substring of a live buff's name>")` returns that same object
      (`hafen.buff(n) == hafen.buff()[k]`); a miss returns `nil`; `hafen.buff(1)` errors saying the
      key is a name/res string.
- [ ] `hafen.buffs` is `nil` in Lua (hard cut) — `hafen.buffs.list()` raises.
- [ ] With a buff that changes (e.g. a cooldown/meter buff), `BuffChanged` fires with an object whose
      `:cooldown()`/`:amount()` reflect the new value; `BuffAdded`/`BuffRemoved` fire with objects,
      and the removed one still prints its name and answers `:exists()` → false.
- [ ] `ant hafen-client` → `BUILD SUCCESSFUL`; a full restart, one login, no red lines.
- [ ] `hello` exercises the whole contract once per login (list + lookup + identity + the flat cut)
      and its `BuffAdded/Removed/Changed` handlers use the object; full regression still passes.

## Out of scope

- `buff:click()` — `Buff.mousedown` sends `wdgmsg("cl", …)`, but no buff is known to do anything with
  it, so there is no verifiable behaviour to ship a gated verb for. Note it in the docs page instead.
- Seconds-based buff timers: the client has none (`amount`/`cooldown` are content-defined 0..1
  fractions). Unchanged, and the docs keep saying so.
- The other still-flat namespaces (party, fight, char, study, items…) — each is its own feature.
- The `BuffsAdapter` detection mechanism itself (poll + uimsg) — untouched.

## Context files

- `specs/design/14-widget-tree-reads.md` — the adapter mechanism this sits on
- `specs/design/06-lua-api.md` — the read API surface + its OOP banner
- `src/io/brodgar/addon/CharApi.java` — `BuffsAdapter`, `buffSnapshot/buffRes/buffName/buffEqual`,
  `installBuffs` — the code being migrated
- `src/io/brodgar/addon/LuaSound.java` — the freshest entity template (userdata, per-addon weak
  intern Cache, callable-table factory, error wording)
- `src/io/brodgar/addon/LuaSlot.java` — the closest analogue: an entity whose event payload is the
  object (`fireSlot`, `hasSub` gate)
- `src/io/brodgar/addon/AddonManager.java` — `fireKin`/`fireSlot`/`hasSub`, `installBuffs` call site
- `src/io/brodgar/addon/Addon.java` — where the per-addon caches hang
- `src/haven/Buff.java`, `src/haven/Bufflist.java` — the widgets read
- `src/haven/AddonWidgets.java` — `buffDest`, the one non-public bit
- `docs/addons/api/buffs.md`, `types.md` (`Buff`), `events.md`, `README.md` — the shipped surface
- `specs/024-audio-oop/`, `021-actionbar-oop/` — prior art for the migration + event payload
