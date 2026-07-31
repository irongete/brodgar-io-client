# 020-kin-oop — Tasks

- [x] 020.1 — **The namespace migration (hard cut).** New `LuaKin` (userdata + per-addon metatable
      + `WeakReference`/`ReferenceQueue` intern cache on `Addon`); `hafen.kin` becomes a callable
      table with arity dispatch (none → roster, number → id, string → exact name); the roster is a
      fresh array of Kin with `:find`/`:list`/`:add` on its metatable; reads `:id/:name/:group/
      :color/:online/:exists/:info`; gated writes `:rename/:setGroup(0..254)/:endkin/:forget` and
      roster `:add(secret)`, each returning self. Delete the flat `hafen.kin.*` table. Update
      `docs/addons/api/kin.md` + `addons/hello` (A6) + `addons/walker` (`:walker kin`) in the same
      task so nothing compiles against the dead surface.
      *Verify:* `hafen.kin()[1]:name()`, `#hafen.kin()`, interning (`==`), freshness after a
      regroup in the Kin window, `hafen.kin.list == nil`, walker's gated verbs, `setGroup(255)`
      and `(-1)` error. **Do not test 8..254 in-game** — the client's palette still crashes on it.

- [ ] 020.2 — **Kin ↔ Gob, both directions.** `gob:kin()` = read the `ui/obj/buddy` attrib off the
      gob (with the class-name fallback for a `@FromResource` version bump) → interned Kin;
      `kin:gob()` = `OCache` sweep for that buddy id → interned Gob. Both nil when unlinked. Docs:
      `gob.md` + the `kin.md` section; `hello` prints, for the nearest player gob, its kin or nil.
      *Verify:* with an online kin in view, `hafen.kin()[n]:gob():name()` answers and
      `g:kin() == hafen.kin()[n]`; offline/out of view → `:gob()` nil; a non-kin gob → `g:kin()` nil.
      <!-- extra context: `src/haven/Resource.java` (the `ResClassLoader` override check, ~1556) -->

- [ ] 020.3 — **`KinChanged` payload + close-out.** `fireKin` beside `fireGob`: per-addon `Kin[]`,
      minted only for owners with a live subscription (`hasSub`); change detection stays the
      `kinListEqual` snapshot diff. Finish the docs cross-refs (`types.md`, `conventions.md`,
      `actions.md`, `events.md`, `README.md`) and prove the hard cut by grep.
      **Two 017 statements become FALSE with this feature and must be rewritten, not just
      cross-linked**: [conventions.md:29](docs/addons/api/conventions.md:29) ("Gob is the only
      section that is object-oriented today") and [:67](docs/addons/api/conventions.md:67)
      (`hafen.world` as the only filter whose predicate takes an object — `kin:list` now does too).
      *Verify:* add/rename/regroup a kin and flip one online/offline → `KinChanged` fires with
      objects (`payload[1]:name()`); `grep -r 'hafen\.kin\.'` is zero in `src/io/brodgar/addon/`,
      `docs/addons/`, `addons/`; the **full `hello` regression passes in that one login**.
      **No dead Java left behind** (`javac` does not warn on unused privates): grep a call site for
      each old helper — `kinList`, `kinFind`, `kinListEqual`, `kinSnapshot`, `resolveKin`,
      `requireKin`, `actKinAdd`, `actKinSetGroup`, `buddywnd` — and delete every one with zero
      callers. Survivors by design: `buddywnd` (the resolve funnel), `kinSnapshot` (`:info()` + the
      diff), `kinListEqual` (change detection).
