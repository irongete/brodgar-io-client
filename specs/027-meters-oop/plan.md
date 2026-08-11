# 027-meters-oop — Plan

## Approach

The seventh pass of the pattern 017/020/021/023/024/025 established, and the closest sibling is **025**:
like a buff, a HUD meter is a **widget with a lifetime**, so it is interned on **widget identity** and it
gets a real `:exists()`. `design/14`'s `VitalsAdapter` row is superseded here — the adapter survives as
pure change *detection*; the reads move onto the entity.

**`LuaMeter`** (userdata + per-`Addon` weak-valued `Cache`, `IdentityHashMap` + `ReferenceQueue`, the
`LuaBuff` file copied structurally) wraps a `haven.IMeter`:

- `:res()` — `IMeter.bg.get().name`, the identity (`Loading` ⇒ nil, normal for a beat).
- `:index()` — 1-based position in the HUD meter list (`nil` once destroyed).
- `:value()` — `AddonWidgets.meters(m).get(0).a`, the first segment's fraction 0..1.
- `:color()` — that segment's `Color` as `{r,g,b,a}` 0..255.
- `:segments()` — the whole `List<LayerMeter.Meter>` as `{{value=,color=},…}`. A vital bar is one
  segment; the engine's type is genuinely multi-segment and the old snapshot threw it away.
- `:exists()` — is it still in the HUD meter list.
- `:info()` — the snapshot escape hatch: `{res,index,value,color,segments}`.

**The namespace is callable-only** (D-056): `hafen.meter()` = every HUD meter, 1-based, in HUD order;
`hafen.meter(needle)` = the first whose res name **contains** `needle` — the 025 lookup verbatim (number
key → an error naming `hafen.meter()`, `""` → an error, a miss → `nil`, no trimming). There is no alias
map and no fixed vitals triple: the bg names are **server-published** (`learnings/widget-tree-reads.md`),
so 027.1 reads the real ones off a live client and 027.3 documents them as *observed*, with `:res()` named
as the way to re-derive them.

**Location**: one new `haven`-package accessor over the **private `GameUI.meters`** list (the engine's own
ordered "these are the HUD meters" truth, maintained at the `place == "meter"` seam and at `:1151` on
removal), filtered to `IMeter`. This replaces `gui().children(IMeter.class)` — a recursive DFS over the
whole HUD that would also collect any `IMeter` placed elsewhere and whose order is tree order, not the
layout order `:index()` promises.

**Events**: `VitalsChanged` is deleted; `MeterAdapter` (replacing `VitalsAdapter`) fires **three** —
`MeterAdded` / `MeterRemoved` (poll: diff the meter list per tick, exactly `BuffsAdapter`'s shape) and
`MeterChanged` (uimsg: `interested` on `IMeter` + `"set"`/`"col"`, then re-read and compare). Payload is
the **Meter object**, via a `hasSub`-gated `AddonManager.fireMeter` beside `fireBuff`. The per-meter
change key stays a value-comparable snapshot inside the adapter (an interned object compares by identity
and cannot detect content change — the 025.2 lesson), and is never handed to Lua. Adding the two
lifecycle events is what makes the "vitals is nil for a beat after enter-world" race an *event* instead of
a documented gotcha, and it is what makes an extra meter appearing mid-session reachable at all.

**The hard cut** (nothing is released): `player():vitals()`, `readVitals`, `vitalsEqual`, `VITAL_KEYS`,
the static `vitalsCache`, the `Vitals` type page section and every doc/harness mention go in the same
feature — 027.1 for the API, 027.2 for the event, 027.3 for the prose.

## Files to create / modify

- `src/io/brodgar/addon/LuaMeter.java` — **new**: the entity, its per-addon `Cache`, the callable factory.
- `src/io/brodgar/addon/Addon.java` — the `meters` cache field (beside `buffs`).
- `src/io/brodgar/addon/CharApi.java` — `VitalsAdapter` → `MeterAdapter` (uimsg + poll); delete
  `readVitals`/`vitalsEqual`/`VITAL_KEYS`/`vitalsCache` and the `player():vitals()` method; install
  `hafen.meter`.
- `src/io/brodgar/addon/AddonManager.java` — `fireMeter(String, IMeter)` beside `fireBuff`.
- `src/haven/AddonWidgets.java` — **core edit** (`// addon:`): `hudMeters(GameUI)` → the private
  `meters` list, filtered to `IMeter`, never null. (`meters(LayerMeter)` already exists and stays.)
- `docs/addons/api/meters.md` — **new** page (the section's contract); `api/README.md` +
  `docs/addons/README.md` "API at a glance" get its row.
- `docs/addons/api/player.md` (drop `vitals()` + its note), `types.md` (delete `Vitals`), `events.md`
  (three rows replace one), `conventions.md`, `hooks.md`, `getting-started.md` — the vitals mentions.
- `addons/hello/main.lua` + `manifest.json` — the login contract check, the three handlers, the HUD
  overlay read, version bump.
- `specs/codebase/services.md` — coverage toll: extend the vitals row (`GameUI.meters` is the ordered
  list, `place == "meter"` is the seam, `IMeter.bg` is the identity, `"set"`/`"col"` are the signals).
- `specs/decisions/architecture-api.md` — D-063 (see below); `learnings/widget-tree-reads.md`.

## Risks & gotchas

- **The bg names are server-published**, not in the client source (`learnings/widget-tree-reads.md`) —
  which is *why* the original code went positional. Never hard-code them in Java; read them in 027.1 and
  document them as observed-on-this-server.
- **Initial values stream in**: meters are created a beat after `OnEnterWorld` and the bars arrive as
  individual `"set"` uimsgs (same learnings file). `hafen.meter()` is legitimately empty for a beat —
  `MeterAdded` is the honest signal, and `hello` must still read on a timer, not in the handler.
- **`Loading`**: `bg.get()` throws until cached, so `:res()` is nil for a beat and the lookup cannot find
  a meter by name yet. Never let it escape into Lua; `hafen.meter()` still lists it (identity ≠ res).
- **`meters` is `List<Widget>`, not `List<IMeter>`** — filter, don't cast.
- **Removed but still readable** (the 025 property): `Widget.destroy()` unlinks; `bg` and the segment
  list stay, so a stashed `MeterRemoved` payload keeps answering and reports `:exists()` false.
- **`"col"` changes colour only** — with colour now in the read surface it is a real change, so the
  adapter must compare colour too (the old `vitalsEqual` deliberately ignored it).
- **Java change ⇒ `ant` rebuild + full client restart**; `ant hafen-client` is incremental, so a moved
  symbol can false-green (`rm -rf build/classes` before the last check of 027.1).

## Discarded alternatives

- **Keep `hp`/`stamina`/`energy` as fixed keys, with an alias→res map** — re-introduces exactly the
  "there are three meters and I know them" assumption this feature deletes; breaks on any new meter.
- **Position as the lookup key** (`hafen.meter(2)`) — the HUD order is a layout artefact, not identity;
  the collection form already covers iteration.
- **Keep `gui().children(IMeter.class)` to avoid a core edit** — cheaper, but `:index()` would then be a
  DFS artefact and the set would not be "the HUD meters". One `// addon:` accessor is the honest price.
- **Fold the meters into `hafen.player()`** (`player():meter(…)`) — Player deliberately forwards nothing
  (see `player.md`); meters are not per-player state, they are a HUD slot the server fills.
- **A `:tooltip()` read now** — `LayerMeter.rawinfo`/`info()` is an `ItemInfo` compose with its own
  `Loading`/font-generation lifecycle; out of scope, its own feature if ever wanted.

## Decision to record (027.3)

**D-063 — an entity's key is what the engine publishes about it, not what the genre calls it.** The
meters are identified by a server-published resource name; the API therefore searches that name and
documents how to read it off a live client, instead of shipping a client-side dictionary that is a
guess about content the client does not own. (The sibling of D-061, one level up: D-061 is about
vocabulary, this is about *identity*.)
