# 060 — plan

## Approach

**`LuaSpeed`, on the idiom `LuaSlot` already established.** A new
`src/io/brodgar/addon/LuaSpeed.java`: userdata over a small int key `0..3`, interned per addon in a
`LuaSpeed.Cache` field on `Addon` (`Addon.speeds`, beside `slots`/`kins`/`buffs`), so
`hafen.speed():get(2) == hafen.speed():get(2)` and `==` is the identity test. `LuaSlot` is the exact
model — it is the other cache keyed by an index rather than by an object — and the same weak-value +
`ReferenceQueue` drain applies unchanged even though this key space is four wide.

The member's verbs: `:index()` (the wire number), `:name()` (`Speedget.tips[n]`), `:available()`
(`n <= Speedget.max`), `:exists()` (the selector is still up), `:info()` (`{index, name, available,
current}`). Every one answers `nil`/`false` rather than throwing while the selector is absent.

**The section object IS the collection** (§2.1), mounted exactly as `hafen.buff()` is:
`Section.mount(hafen, "speed", LuaSpeed.collection(owner), hint)` at the site in `AddonManager` that
calls `ActApi.installSpeed` today. `LuaSpeed.collection(owner)` builds a `LuaCollection.Source` whose
`members()` is `of(owner, i)` for `i` in `0..max` — empty when there is no selector and empty when
`max < 0` — with `named()` true, `needle()` the display name, and `addressable()` true so `:get(key)`
mounts, taking an index or a whole case-insensitive name and reaching **all four**. `:current()` and
`:set(x)` go in the `extra` table `LuaCollection.create` already takes for a section's own verbs
(`hafen.timer():every` is the precedent).

**`:set(x)`** is `AddonManager.requirePermission(owner, Permission.SPEED_SET)` as its first statement
(D-213), then resolves `x` — a `LuaSpeed`, a number or a string, through the same resolver `:get` uses
— then refuses one that is not `:available()` naming the ones that are, and finally calls
`Speedget.set(n)`, which is `wdgmsg("set", n)`: wrap, never reimplement, and the server stays
authoritative on whether a speed is allowed.

**`ActApi` loses its speed half whole** — `installSpeed`, `speedget()`, `speedName()`, `actSpeedSet()`
and the `A7` comment block move into `LuaSpeed`, and the class javadoc becomes what the file then is:
crafting alone.

## Files to create/modify

| File | |
|---|---|
| `src/io/brodgar/addon/LuaSpeed.java` | **new**: the member, its `Cache`, `collection(owner)`, the locator, `:set` |
| `src/io/brodgar/addon/Addon.java` | the `speeds` cache field |
| `src/io/brodgar/addon/AddonManager.java` | `Section.mount` where `ActApi.installSpeed` was called |
| `src/io/brodgar/addon/ActApi.java` | delete the speed half; rewrite the class javadoc to craft alone |
| `src/io/brodgar/addon/LuaCollection.java` | `meta`'s `__index` consults `Retired` before its generic refusal |
| `src/io/brodgar/addon/Permission.java` | `SPEED_CURRENT` → `SPEED_SET ("speed.set", "hafen.speed():set", …)` |
| `src/io/brodgar/addon/Retired.java` | rows for `hafen.speed():max` / `():name`; the `hafen.speed.*` dot rows re-pointed; `section("speed", …)` dropped |
| `docs/addons/api/speed.md` | rewritten whole |
| `docs/addons/api/types.md` · `docs/addons/guides/permissions.md` | the `Speed` snapshot row; the key row |
| `docs/client/services.md` | the HUD speed selector (`Speedget`) — read for this feature, mapped nowhere |
| `addons/hello/main.lua` · `addons/walker/main.lua` · `addons/walker/manifest.json` | the new spellings; `speed.set` |
| `addons/060-speed-collection.1/` · `addons/060-speed-collection.2/` | the two suites |

## Risks & gotchas

- **`Speedget` has no `GameUI` field.** It is found with `g.children(Speedget.class)`, first match — the
  locator `ActApi.speedget()` already is, moving as it stands.
- **`Speedget.max` can be negative.** Both `Speedget.mousewheel` and `Speedget.globtype` guard on
  `if(max >= 0)`. That is a live selector with an empty `:list()` and a `:set` that refuses everything.
  Do not clamp it to `0` — it is the honest answer `max()` never gave.
- **`Speedget.tips` is static**, built in a class-init block from
  `Resource.local().loadwait("gfx/hud/meter/rmeter/<name>-on").flayer(Resource.tooltip)`. The names
  therefore exist before any selector does; `:get("Run")` is still `nil` then, because it is the
  *collection* that is empty and not the names.
- **`Speedget.cur` only moves when the server sends `uimsg("cur")`.** A read-back after `:set` is a
  round trip, so a suite polls for it on a timer rather than asserting on the next line.
- **`LuaCollection.meta`'s `__index` never consults `Retired`** — it throws its own *has no verb*,
  where `Section.meta` looks the row up first. Mounting a collection as a section object would
  therefore swallow `:max()`'s replacement message. The fix belongs in `LuaCollection`, keyed
  `coll.name + ":" + verb` — the same key `Section.meta` builds — and is additive: no collection has
  such a row today, so nothing else changes behaviour.
- **`PermissionSet` fails a manifest carrying an unknown key.** Renaming the enum constant is what
  makes `speed.current` fail to load, so `walker`'s manifest changes in the *same* task, or `walker`
  stops loading.

## Discarded alternatives

- **`:list()` = all four, with the selectable ones as a `:list(function(s) return s:available() end)`
  filter** — rejected: it hands the caller back the loop `max()` already made them write, and gives up
  the invariant worth having, that everything `:list()` gives you is something `:set` accepts.
- **Keeping `:current(n)` as the write (arity is the verb)** — rejected: `:current()` addresses a
  member, and the grammar's own rule is that a member address is not a property (`coll:get(key)` reads
  nothing and writes nothing). *Which one am I on* and *make it this one* are two acts, not two arities.
- **`sp:set()` on the member, the `pag:use()` shape** — rejected: what changes is the character's
  speed, not the Speed. The four speeds are static client facts; the thing that has a *current* is the
  collection, and `hafen.flowermenu():select(label|n)` is the same shape one page away.
- **`sp:selected()`** — rejected: objects are interned, so `hafen.speed():current() == sp` already
  answers it, and a second spelling of one fact is the dual style this grammar exists to refuse.
- **Keeping the key `speed.current` for the renamed verb** — rejected: a key is `<section>.<verb>` and
  the verb is `set`; an alias key is the deprecation nothing here gets.
- **A `SpeedChanged` event** — rejected as scope: the page already says *read on demand*, and the
  `uimsg("cur")` seam is there for a feature that wants one.
- **A `docs/client/` page of its own for the speed selector** — rejected: `services.md` already
  collects the HUD's small server-placed widgets, and a page per widget is the tree its README forbids.
