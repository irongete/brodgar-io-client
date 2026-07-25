# A9-2 — Wounds read (`hafen.wounds`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **23/23 headless checks**
> (the pure `woundListEqual` change-detection: identical / same-instance / empty; a **severity** change, and
> name / res / parentid / level / id changes; add / remove / reorder; nil-field edges — severity/name still
> Loading; non-table & nil edges; plus the locator/list null-safety with no session) + LuaJ parse of the
> harness under `Sandbox.create()`. The snapshot shape reads live resources (`name`/`severity`) → verified
> **in-game**. **In-game verification pending.**
> **Design:** [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.quests` /
> `hafen.wounds` gap-subsystem **A9**). A9 bundled **quests + wounds**; per the queue's "split each when
> reached", this slice is **A9-2 (wounds)**, the second half after [A9-1 (quests)](a9-1-quests.md). **This
> completes A9.**

A wound tracker — "what wounds do I have, and how bad are they?" — needs to read the **Health & Wounds** panel.
The client holds it in the **`WoundWnd`** widget (`@RName("wounds")`), the character sheet's **"Health &
Wounds"** tab, reached through the public **`CharWnd.wound`** field. `hafen.wounds` exposes it as a **read**
surface. There is **no wound action tier** (wounds heal by playing / tending — poultices, rest, cures), so this
is read-only by nature — no gated Phase-4 counterpart.

## Zero core edits — every backing is public

No `haven` file changed. Like [A9-1](a9-1-quests.md)/[A8](a8-craft.md)/[A7](a7-speed.md)/[A6](a6-kin.md)/[A4](a4-skills-credos-lore.md)/[A2](a2-radar.md),
every field read is already public:

- `CharWnd.wound` (`public WoundWnd wound`) — the Health & Wounds window, held by the character sheet (created
  hidden at login but **live**, so wounds read without the window ever being opened). No tree-walk — a direct
  named field, exactly like [A9-1](a9-1-quests.md)'s `CharWnd.quest`.
- `WoundWnd.wounds` (`public final WoundList`) → `WoundList.wounds` (`public List<Wound>`) — the flat set of
  wounds. The client renders it as a **tree** (see below), but the list itself is a plain public field.
- `WoundWnd.Wound` — `public final int id`, `public final int parentid`, `public Indir<Resource> res`,
  `public int level`, `public List<ItemInfo> info()`. The wound's **display name** comes from its resource
  tooltip (or the server-pushed `ItemInfo.Name` over `info()`), and its **severity** from the wound's
  `ItemInfo` (see below).
- `WoundWnd.QuickInfo` (`public static interface` with `public String qstr()` / `public int qprio()`) — the
  content-side "quick info" glyph the client shows beside a wound. We pick the highest-`qprio` one from the
  wound's `info()`, mirroring the client's own `WoundWnd.WoundList.Item.getqdat`.

So the only file changed is `AddonManager.java` (the bridge) + the `hello` harness. No `AddonWidgets`
haven-package accessor is needed (nothing read is private/protected).

## Wounds are a tree

A wound can have **complications** nested under it (e.g. an *Infection* that develops on a *Swelling*). The
client models this with `Wound.parentid` (the id of the parent wound, or **`-1`** for a root wound) and
`Wound.level` (the depth the `WoundList`'s `treesort` computes, used to indent the row). Both are exposed on the
snapshot, so an addon can reconstruct the same tree the panel shows.

## `hafen.wounds.list([filter])` — every wound

```lua
for _, w in ipairs(hafen.wounds.list()) do
  -- w.id        -- number, the server wound id
  -- w.name      -- string, the wound's display name (resource tooltip, else the ItemInfo.Name); nil while Loading
  -- w.res       -- string, the stable resource id (omitted while Loading)
  -- w.severity  -- string, the magnitude the client shows beside the wound (usually a number; NOT seconds); nil if none
  -- w.parentid  -- number, the parent wound's id (-1 = a root wound)
  -- w.level     -- number, the tree depth (0 = root), the client's computed indent level
end

-- Only the root wounds — the canonical predicate filter (one canonical way):
local roots = hafen.wounds.list(function(w) return w.parentid == -1 end)
```

Returns a snapshot per wound. `filter` is the canonical **nil = all / name-substring / predicate** (shared with
`hafen.gob`/`world`/`kin`/`radar`/`quests`); the string form matches the wound `name` (the snapshot's display
field, like every other snapshot).

> **`severity` is a content-defined display string, not a number and never seconds.** It is the wound's
> highest-priority `QuickInfo.qstr()` — the exact text the panel draws beside the wound (for most wounds a
> magnitude number like `"45"`). Faithful to what the client shows, exactly like [1d-2](phase-1d2-buffs-food.md)
> buff `amount`/`number` and [A8](a8-craft.md) craft specs. Parse it with `tonumber(w.severity)` if you want a
> number. It streams in a beat after the wound appears (the wound's `ItemInfo` resolves after the wound row), so
> it is `nil` on the first read and fills in shortly after — read on a later tick / off `WoundChanged`.

## `hafen.wounds.has(needle)` — presence test

```lua
if hafen.wounds.has("Infection") then ... end   -- any wound whose name OR res contains "Infection"?
```

Returns `true` when any wound's `name` or `res` contains the substring `needle` — the ergonomic "do I have wound
X?" check, mirroring [1d-2](phase-1d2-buffs-food.md)'s `hafen.buffs.has` (wounds and buffs are both status items
on the character). Equivalent to `#hafen.wounds.list(needle) > 0` but short-circuits and builds no snapshots.

## Events — `WoundChanged`

```lua
hafen.events.on("WoundChanged", function(list) end)   -- the wound set or a severity changed (payload = the new list)
```

Fires when the wound set changes — a wound **added**, **healed/removed**, or its **severity advancing** (a wound
getting worse) — and as wounds and their severity **stream in** a beat after enter-world. The payload is the new
`list()` array (the same shape), mirroring [A6](a6-kin.md)'s `KinChanged`. This is the signal a wound-alert addon
lives on ("tell me the moment a bleed appears / worsens"). Read the initial state with `list()` in
`OnEnterWorld`; listen for deltas after.

### Mechanism — a poll-driven adapter + a snapshot diff

A wound being **added / healed / worsening** does arrive as a targeted **`"wounds"` `uimsg`** to the `WoundWnd`
(`decwound` adds / updates the `res`+`rawinfo` / removes) — so a uimsg-driven adapter (like [A6 kin](a6-kin.md) /
[A9-1 quests](a9-1-quests.md)) *could* catch the structural changes. But a wound's **`severity`** comes from
resource-published `ItemInfo` that **streams in a beat after** the wound row (its `res.get()` is still Loading on
the first refresh), so a uimsg-driven refresh would fire `WoundChanged` with `severity = nil` and **never re-fire**
when it resolves — exactly the [1d-3 `StudyAdapter`](phase-1d3-study-skills.md) situation ("its Curiosity info
streams in a beat later"). So the `WoundAdapter` is **poll-driven** (like study / buffs / actionbar / equip):
each tick — once the wound tab exists — it re-reads the full wound list and **change-detects** it against the
last snapshot via the pure `woundListEqual` (length + per-entry `id`/`parentid`/`level`/`name`/`res`/`severity`),
firing `WoundChanged` with the new list on any difference. This catches **everything**: an add/heal, a wound
**worsening** (`severity` is in the equality key), *and* a `severity` resolving `nil → value` a beat after the
wound appears. The diff is a plain snapshot compare (no per-id bookkeeping), so the whole change-detection is
**headless-testable** (the 23 checks). While the wound tab isn't up yet the poll is skipped (the cache is kept,
so nothing fires before there's anything to read). The cache is per-session (reset by re-instantiation in `init`)
and survives a `:reload` (session infra is untouched).

Unlike A9-1's quests there is **no `Added`/`Done` split** — the api-reference names a single **`WoundChanged`**
(the wound analog of `KinChanged`), so this is **one** event carrying the full list, the one canonical way.

## The `hello` example (`addons/hello/main.lua`) — read-only

- A `readWounds(tag)` helper logs `list()` (total + the first wound's name/severity) in the `[now]` and `[+3s]`
  login passes. Like the rest of the character sheet the wound list **streams in** a beat after enter-world, so
  `now` usually shows `wounds=0` and `+3s` the real count (for a wounded character).
- A `WoundChanged` subscription logs the first few changes (count + the first wound's name/severity) — a few
  fire at login as wounds and their severity resolve.
- A new **`:hello wound`** sub-command calls `dumpWounds()`, printing the full wound **tree** — one indented
  line per wound (`level`-indented, with name, `severity`, id and parentid). This is the on-demand read.

One login re-checks every prior slice **and** this one. Bumped to **v0.30.0**. `hello` stays **read-only** —
there is no wound action tier to gate. **Most characters have 0 wounds**, which is a valid empty read; take a
hit or run `hello` on a wounded character to see the surface populate.

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```bash
ant run
```

1. At login the harness logs `[hello] [now] wounds=…` and `[hello] [+3s] wounds=…`. On a **wounded** character
   the `+3s` pass shows your real wound count and the first wound (e.g. `wounds=2, first='Swelling' sev=45`);
   an unwounded character shows `wounds=0` — both correct. A few `WoundChanged: N wound(s) …` lines fire as the
   wounds/severity stream in.
2. Open the **character sheet → Health & Wounds** tab to see the same wounds the panel lists, then run in the
   console (chat):

   ```
   :hello wound
   ```

   Expect one indented line per wound: its name, `(sev …)` where the client shows a magnitude, and `[id=… parent=…]`
   (a complication is indented under its parent wound).
3. Cross-check directly from `:lua`:

   ```
   :lua #hafen.wounds.list()
   :lua hafen.wounds.list()[1]
   :lua hafen.wounds.has("Swelling")
   :lua hafen.wounds.list(function(w) return w.parentid == -1 end)   -- root wounds only
   ```

4. **Take a wound** (or watch one worsen / heal) — a `WoundChanged: N wound(s) …` line should appear, and a
   re-read of `list()` should reflect the new wound / severity.

## Files

- `src/io/brodgar/addon/AddonManager.java` — **the only engine file changed**: the `hafen.wounds.list` / `has`
  facade; the `WoundAdapter` (registered in `init`) + the pure `woundListEqual`; helpers `woundwnd()` (the
  `CharWnd.wound` locator), `copyWounds` (copy under the `ui` lock), `woundList`, `woundSnapshot`, `woundName`,
  `woundSeverity`; one new import (`haven.WoundWnd`). Reuses the shared `matches` filter, the `resTipName`/`resIdent`
  name helpers, and the `luaFieldEq` snapshot-field comparator.
- `addons/hello/` — `readWounds`/`dumpWounds` helpers, a `WoundChanged` subscription, a `wound` sub-command on
  `:hello`; manifest + load-line bumped to **v0.30.0**.

**No `haven` core edit.**

## Threading & safety

- All calls run on the **UI thread** (the addon tick / the `:lua` console). `woundwnd()` returns `CharWnd.wound`
  or `null` when the character sheet / wound tab isn't up yet — **null-safe, no NPE** (headless-verified).
- **List copy under the `ui` monitor.** The wound list is mutated on a **Loader thread** by
  `WoundWnd.uimsg("wounds")` (`decwound` add/update/remove) under `synchronized(ui)`, and reassigned by
  `WoundList.tick`'s `treesort` on the UI thread. So `copyWounds()` copies the list reference inside
  `synchronized(ui)`, then builds the Lua snapshots **outside** the lock — the marker "copy under the lock,
  snapshot outside it" discipline ([A1](a1-markers.md)/[A8](a8-craft.md)/[A9-1](a9-1-quests.md)).
- Resource / info reads are `Loading`-guarded (`resTipName`/`resIdent` and the `woundName`/`woundSeverity`
  try/catch swallow `Loading` → the field is omitted until it resolves). Building `Wound.info()` outside the
  lock is the same benign discipline as [1d-2](phase-1d2-buffs-food.md)'s `buffSnapshot`.
- The adapter cache is UI-thread-only, reset per session in `init`, and survives `:reload` — nothing to leak.

## Limitations / deferred

- **`severity` is a display string** (the content's `QuickInfo.qstr()`), not a guaranteed number and never
  seconds — see the note above. If a wound publishes no quick-info glyph, `severity` is absent (faithful — some
  wounds have no magnitude line, like [A9-1](a9-1-quests.md)'s bonds with no `Curiosity`).
- **No `selected()`** (unlike A9-1's quests). A quest's *conditions* load only for the selected quest, so
  `selected()` earned its place there; a wound carries its full `info()` for **every** wound in the list, so a
  selection accessor would add no data beyond `list()` — omitted by the "one canonical way / no bloat" rule.
- **No `WoundAdded`/`WoundHealed` split** — the api-reference names a single `WoundChanged` (the `KinChanged`
  analog); an addon derives add/heal transitions by diffing successive payloads against its own last set (the
  `hello` `KinChanged` handler shows the pattern). A granular split is a trivial future add if a use case appears.
- Wound **description / pagina text** (the long tooltip explaining the wound) and the wound **icon** are not
  exposed — the read surface is identity + severity + tree. Both are future adds (the pagina is a `RichText`
  document; the icon needs a `Tex`/resource path, deferred like `g:image`).
- Item/severity **quality** and any absolute healing timer are not exposed — the client has none (wounds have no
  countdown, mirroring [A8](a8-craft.md)/[A9-1](a9-1-quests.md)).

## Completes A9 → next: A10

A9 (quests + wounds) is done. Next in the gap-subsystem queue is **A10** — `hafen.fight` (the combat
deck/maneuvers over `FightWnd`, `@RName("fmg")`): read surfaces now, gated make/set in Phase 4.
