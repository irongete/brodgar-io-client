# 020-kin-oop — Spec

## What & why
Migrate the **kin / buddy roster** from the flat snapshot API (`hafen.kin.list()` → `KinEntry`
tables) to **OOP**, the next step of the ROADMAP's "Finish the OOP migration". Kin is at once a
**collection** and a set of **addressable entities with write verbs**, so it exercises both shapes
at once. **Arity is the verb on the namespace itself**: `hafen.kin()` is the roster,
`hafen.kin(idOrName)` a Kin — which wraps **only the buddy id** and re-resolves per call (D-012
freshness verbatim), **interned per addon** so `a == b` and `seen[kin]` work. **Hard cut** (D-013):
the flat `hafen.kin.*` table is deleted.

```lua
for _, k in ipairs(hafen.kin()) do                  -- the roster IS the array
  hafen.log(k:name() .. " [" .. k:group() .. "] " .. tostring(k:gob() ~= nil))
end
hafen.kin("Bob"):setGroup(3):rename("Bobby")        -- gated, chainable; g:kin() is :gob()'s inverse
```

## Decisions taken here (to confirm at review)
- **`hafen.kin()` → the roster, indexable**: array part = the roster in window sort order
  (`#roster`, `roster[1]`, `ipairs`), plus `:find(nameOrId)` → `Kin` | nil, `:list([filter])` →
  filtered `Kin[]` (a **function filter receives a Kin**), `:add(secret)` (gated). Wart to document:
  the **array is the roster at call time**, the Kin objects and methods are live.
- **`hafen.kin(idOrName)` → Kin**: `:id() :name() :group() :color() :online() :exists() :gob()`;
  gated writes `:rename(name) :setGroup(g) :endkin() :forget()` return **self** so they chain.
  `remove` becomes **`endkin`** (it maps to `Buddy.endkin`); the two-step end-then-forget dance is
  unchanged, only better named.
- **`setGroup` takes 0..254** — the range the server accepts (confirmed by the H&H developers), not
  the 0..7 of the client's 8-colour palette; outside it, a guiding error. `kin:color()` returns the
  palette colour for 0..7 and **nil** above it (there is no colour to report), so `:group()` is the
  identity and `:color()` is presentation.
- **Kin ↔ Gob, both directions.** Server-authoritative: the `ui/obj/buddy` `GAttrib`
  ([Buddy:31](src/haven/res/ui/obj/buddy/Buddy.java:31)) carries the buddy id on the gob. `gob:kin()`
  is the O(1) primitive (attrib read → interned Kin); `kin:gob()` is an `OCache` sweep for that id —
  the shape `hafen.world.nearest(fn)` already ships. Both give **nil** when unlinked, and nil is
  **ambiguous** (not kinned / not loaded / out of view): documented, not resolved. Inverse
  relations, not two styles of one operation — D-013 holds.
- **Number = id, string = exact (case-insensitive) name** (what `resolveKin` does). A **name
  matching nobody returns nil**; an unknown **id** still yields a Kin whose `:exists()` is `false`.
- **`kin:info()` survives** as the one snapshot escape hatch, today's `KinEntry` shape (017
  precedent). **`KinChanged` payload becomes a `Kin[]`** (mirrors `GobAdded`), same change
  detection. **`hafen.kin` is callable-only** — no fields, exactly one way in.

## Acceptance criteria (verified in-game, one login)
- [ ] `:lua`: `hafen.kin()[1]:name()` and `#hafen.kin()` read; `hafen.kin(<id>) == hafen.kin(<id>)`
      and is the same object as `hafen.kin()[n]`; `hafen.kin("NoSuchName")` is nil.
- [ ] Freshness: hold a Kin, change its group in the Kin window → `:group()`/`:color()` track it;
      `:info()` returns the old flat shape. `:list(function(k) return k:online() end)` filters;
      `:find` round-trips by name and by id to the **same interned object** as `hafen.kin(id)`.
- [ ] Gated (`walker`, actions permission): `hafen.kin("X"):setGroup(3):rename("Y")` applies in the
      Kin window; `:endkin()` leaves the kin memorized, `:forget()` drops it; `:add(secret)` sends
      `bypwd`. Without the permission each errors. `KinChanged` fires with Kin objects.
- [ ] Range: `setGroup(255)` and `setGroup(-1)` error with a guiding message; `setGroup(7)` applies
      and recolours the kin. (Values 8..254 are only exercised once the `client` area ships its
      palette fix — until then they would break the client's own draw loop, see Out of scope.)
- [ ] Kin ↔ Gob with an online kin in view: `hafen.kin()[n]:gob():name()` answers and
      `g:kin() == hafen.kin()[n]`; offline/out of view → `:gob()` nil; a non-kin gob → `g:kin()` nil.
- [ ] Hard cut proven: `hafen.kin.list` is nil, grep `hafen\.kin\.` zero in `src/io/brodgar/addon/`,
      `docs/addons/`, `addons/`. `hello` (A6) + `walker` exercise it; **full prior regression passes**.

## Out of scope
- **Every client-side change.** `BuddyWnd.gc` is 8 colours indexed unguarded in three draw paths, so
  a group ≥ 8 crashes the client's own draw loop, and the Kin window's `GroupSelector` cannot even
  select one. Both the guard and the numeric group picker are a **`client`-area feature** the
  maintainer plans separately; this feature touches no `haven` file.
- Other namespaces (party, fight, markers…) and the "final demolition" of 017's markers — later.
- New kin data (hearth-secret state, the `online == -1` tri-state); `:online()` stays boolean.
- Caching the Kin↔Gob link (a buddy-id → gob index): `kin:gob()` sweeps; 019 can measure it.

## Context files
- `src/io/brodgar/addon/CharApi.java` — `installKin`, `kinList/kinFind/kinSnapshot/kinListEqual`,
  `resolveKin/requireKin/actKinAdd/actKinSetGroup` (the whole rewrite);
  `AddonManager.java` — the `installKin` call site + the `KinChanged` fire site
- `src/io/brodgar/addon/WorldApi.java` — the Gob class, interning pattern, where `gob:kin()` lands
- `src/haven/res/ui/obj/buddy/Buddy.java` — the gob-side buddy id, READ ONLY (`@FromResource` v4 —
  a server bump silently un-adopts it; fallback goes in plan.md)
- `specs/codebase/services.md` — kin section; **extended by `/end`** for the gob-side attrib
- `docs/addons/api/kin.md` rewritten; `types.md`, `conventions.md`, `actions.md`, `events.md`,
  `README.md` cross-refs; `addons/hello/main.lua` (A6) + `addons/walker/main.lua` (`kin`)
- `017-gob-oop/`, `018-client-options/` — the two OOP precedents this follows
