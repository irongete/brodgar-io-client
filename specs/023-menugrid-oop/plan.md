# 023-menugrid-oop — Plan

## Approach

**One new class, `LuaPagina`, cloned from [`LuaSlot`](src/io/brodgar/addon/LuaSlot.java)** — the OOP
template is settled (017/020/021) and this feature adds no mechanism, only a new entity. What differs
from `LuaSlot` is the *key* (a `String` res name, not an `int`) and the *collection* (variable-size
and derived, not a fixed 144-array), which is where `LuaKin`'s roster is the closer model.

- **The handle wraps only the resource name.** Every method re-resolves through one funnel —
  `AddonManager.gui().menu` → `MenuGrid.paginae` → the `Pagina` whose `res().name` matches — so a
  stashed handle tracks the live catalogue and goes `:exists() == false` when the action is revoked
  (D-012 freshness, verbatim from `LuaSlot`). Intern cache on the `Addon` (`Map<String, Ref>`, weak
  values + `ReferenceQueue`, never `WeakHashMap`), so `==` and `seen[pag]` hold.
- **The catalogue is built per call, and the lock is released before anything resolves.**
  `synchronized(scm.paginae)` to copy the set into a local list, then *outside* the monitor resolve
  each `res().name` / `button().name()` under a `Loading` guard, drop what has not resolved yet, sort
  by the client's own `PagButton.sortkey()` (so the order matches the grid), and hand back a 1-based
  Lua array of interned handles. Copy-under-lock-resolve-outside is the rule
  [`learnings/gap-subsystems.md:125`](../learnings/gap-subsystems.md) already paid for on the kin list.
- **Key dispatch by shape** in the `__call`: a number is a `LuaError` naming the two string forms; a
  string containing `/` is a resource name (exact match on `res().name`); any other string is a
  display name (first match over the sorted catalogue). Miss ⇒ `nil`, both forms. Note `__call`
  hands the table itself as arg1 — real params start at `a.arg(2)`.
- **The tree comes from `Pagina.parent()`**, which is `button().parent()` = `scm.paginafor(act().parent)`.
  `:children()` scans the catalogue for entries whose `parent()` is this one; `:roots()` for those
  whose parent is `null`. Both skip entries that throw `Loading`. No core edit: `paginae`,
  `Pagina.{id,res,anew,parent(),button()}` and `PagButton.{name(),act(),bind,use()}` are all public.
- **`:use()` calls [`PagButton.use(Interaction)`](src/haven/MenuGrid.java:195) directly**, not
  `MenuGrid.use(btn, iact, reset)`. The latter is the *widget's* click handler: for an entry with
  children it changes the visible page rather than sending anything, and it resets the grid's state —
  side effects an addon call must not have. `PagButton.use` is the pure message half, and it already
  branches `"act"`-by-path vs `"use"`-by-id internally, which is what reaches id-only paginae.
  [`Makewindow.java:375`](src/haven/Makewindow.java:375) is the client doing exactly this from a
  non-grid widget. A category (`:children()` non-empty) errors instead of sending an empty `"act"`.
- **No `mods` parameter.** `PagButton.use` builds the message from `ui.modflags()` and never reads
  `Interaction.modflags` — that field is consumed in one place client-wide,
  [`GameUI.java:111`](src/haven/GameUI.java:111) (the belt path behind `slot:use([mods])`). Pass
  `new Interaction(1, 0)`; a `mods` argument would silently send the live keyboard state.
- **Install** is one line in `AddonManager.installHafen`, beside `hafen.set("gob", …)`. No new Api
  class: `hafen.set("menugrid", LuaPagina.factory(owner))`. (`installKin`/`installActionbar` live in
  `CharApi` because they are character surfaces; the action catalogue is not one.)

## Files to create / modify

| File | Change |
|---|---|
| `src/io/brodgar/addon/LuaPagina.java` | **new** — the class, intern `Cache`, metatable, reads, `:use`, collection, callable factory |
| `src/io/brodgar/addon/Addon.java` | **+1 field** — `final LuaPagina.Cache paginae = new LuaPagina.Cache(this)` |
| `src/io/brodgar/addon/AddonManager.java` | **+1 line** in `installHafen` — `hafen.set("menugrid", …)` |
| `docs/addons/api/menugrid.md` | **new** — the page (docs tier = the contract) |
| `docs/addons/api/README.md`, `docs/addons/README.md` | index line + "API at a glance" row (new section) |
| `docs/addons/api/types.md`, `actions.md` | the `Pagina` type; cross-ref from the act verbs |
| `addons/hello/main.lua` | read summary + per-login contract check (read-only) |
| `addons/walker/main.lua` | `:use()` demo — `:walker menugrid <name>` |
| `specs/codebase/services.md` | **coverage toll** — a MenuGrid/paginae block (56 → ~68 of the 70-line cap) |
| `specs/learnings/gap-subsystems.md` | append what the build teaches |

## Risks & gotchas

- **`Loading` is everywhere on this surface.** `pag.res()`, `button()`, `parent()`, `act()` can each
  throw it; the catalogue is therefore *short* right after login and self-heals. Every read guards to
  `nil`, no `Loading` escapes into Lua (`codebase-map.md` client-wide rule).
- **Never resolve while holding `paginae`.** It is a `HashSet` mutated on the UI thread under its own
  monitor; `res.get()` can block on the loader. Copy, release, then resolve.
- **A res-keyed handle can repoint.** `MenuGrid.uimsg("fill")` may reassign `pag.res` for an id-based
  pagina (`pag.res = res; invalidate()`). Rare, and the alternative (keying by `id`) is worse — the
  maintainer's own objection: session-local and opaque. Document it; do not defend against it.
- **Display names are not unique and are not addresses.** First match in sort order wins; the docs
  say plainly that `:res()` is the identity.
- **`next`/`bk` are synthetic** `PagButton`s built on paginae that are *not* in `paginae` — they never
  appear in the catalogue, so no filtering is needed. Confirm in-game rather than assume.
- **Incremental `ant` false-greens** when a symbol moves — `rm -rf build/classes` for a true check
  ([`learnings/testing-tooling.md`](../learnings/testing-tooling.md)).

## Discarded alternatives

- **Position as a key** (`menugrid(1)`) — the catalogue grows on discovery, so a position is not an
  index; maintainer's call. The array stays 1-based for `ipairs` only.
- **Try-name-then-res fallback** — always scans display names first (the `Loading`-fragile pass) and
  lets a display name silently hijack a res lookup. The `/` shape rule is deterministic.
- **Exposing `Pagina.id`** — session-local and opaque (022 already refused it for `setbelt "pag"`).
- **A `// addon:` edit to read `Session.CachedRes.resnm`** before the resource loads — would make the
  catalogue complete instantly, but buys a core edit for a sub-second window. Revisit if it annoys.
- **Routing `:use()` through `hafen.act.menu`** — dies with the act restructure and cannot express
  id-only paginae. **Through `MenuGrid.use()`** — mutates the visible grid page.
- **A working `mods` argument** — would need `PagButton.use` to honour `Interaction.modflags` *and*
  `MenuGrid.mouseup`/`globtype` to start passing `ui.modflags()` (they pass `new Interaction()` = 0
  today and rely on the live read). Four `// addon:` lines on the real grid's click and hotkey paths,
  for no requested behaviour.
- **A `MenuChanged` event / exposing `pagseq`** — deferred (spec, out of scope).
