# A6 — Kin / buddy roster (`hafen.kin`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **14/14 headless logic
> checks** (`kinListEqual` change-detection: identical / online-flip / group / name / id / add / remove /
> reorder / count / empty / nil edge cases) + LuaJ parse of the harness under `Sandbox.create()`.
> **In-game verified ✅.**
> **Design:** [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.kin`
> gap-subsystem A6), [specs/addons/coverage-gaps.md](../../specs/addons/coverage-gaps.md)
> (A6 — "Buddy / kin management"), [specs/addons/code-map.md](../../specs/addons/code-map.md) (`BuddyWnd`).

Kin addons — **kin-online alerts**, group/colour coding, "notify me when a villager logs in" — are a
staple. The client keeps the roster in the **Kin window** (`BuddyWnd`, `GameUI.buddies`): a list of
`Buddy` entries, each with a name, a **group** (0..7, a colour band), and an **online** state. Until now
`GameUI.buddies` was used only to resolve a chat sender's name. `hafen.kin` exposes the roster as read
snapshots plus a `KinChanged` event — the addon-scriptable view of that window.

## Zero core edits — every backing is public

No `haven` file changed. `BuddyWnd` and everything we read on it is already public:

- `GameUI.buddies` (public `BuddyWnd`) — the roster widget.
- `BuddyWnd implements Iterable<Buddy>`; `iterator()` **copies the list under the widget's own lock**, so
  iterating it is snapshot-safe against the network thread that mutates it.
- `BuddyWnd.find(int id)` (public) — id lookup.
- `Buddy.id` / `name` / `online` / `group` (public fields).
- `BuddyWnd.gc` (public `static final Color[]`) — the group → colour palette.

So the only file changed is `AddonManager.java` (the bridge) — like [A4](a4-skills-credos-lore.md),
[A2](a2-radar.md) and [1d-3](phase-1d3-study-skills.md). Unlike vitals/buffs (audit B5), no
`AddonWidgets` haven-package accessor is needed.

## `hafen.kin.list([filter])` — read the roster

```lua
for _, k in ipairs(hafen.kin.list()) do
  -- k = { id, name, group, color = {r,g,b,a}, online }
end
local online = hafen.kin.list(function(k) return k.online end)   -- just the online kin
```

Each kin snapshot:

| field | meaning |
|---|---|
| `id` | the kin's numeric id (stable within the session; the handle for `find` and future gated verbs) |
| `name` | the kin's name |
| `group` | the group index **0..7** (the colour band the client sorts/tints by) |
| `color` | `{r,g,b,a}` (0..255) — the group's colour (from `BuddyWnd.gc[group]`), the same tint the Kin list draws |
| `online` | **boolean** — `true` iff the kin is currently online |

The optional `filter` is the **canonical filter** used across the API (`world.gobs`, `markers.list`,
`radar.categories`): `nil` = all, a **string** = case-sensitive substring of `name`, a **function**
`filter(snap)->truthy` = a predicate over the full snapshot (use a predicate to match on `online` or
`group`). Entries come back in the **window's current sort order** (Status / Group / Name, whichever the
player picked in the Kin window).

Like the rest of the HUD, the roster is **empty until the Kin window exists and the server streams the
kin in** — a beat after `OnEnterWorld`, same as inventory/char. Read it on demand / off a timer, not
synchronously in `OnEnterWorld` (the `now` pass usually shows `0`).

### On `online` being a boolean

Internally `Buddy.online` is a **tri-state** int: `1` online, `0` offline, `-1` a hearth-secret-only kin
not yet resolved to a character. We expose it as a **boolean** (`online == 1`) — the common "is this kin
online right now" question a kin-alert addon asks (**one canonical way**). The `-1` vs `0` distinction
(and `Buddy.seen`/`notes`) is deferred; nothing needs it yet.

## `hafen.kin.find(nameOrId)` — one entry

```lua
local k = hafen.kin.find("Alice")   -- exact (case-insensitive) name
local k = hafen.kin.find(12345)     -- by id
```

Returns a single kin snapshot or `nil`. A **number** matches by `id` (via `BuddyWnd.find`); a **string**
matches the **first** kin whose name equals it **case-insensitively** (exact, not substring — `find`
returns one entry, so an exact match is unambiguous; for fuzzy/multi matches use `list(filter)`).

## `KinChanged` — the roster changed

```lua
hafen.events.on("KinChanged", function(list)   -- list = the new hafen.kin.list() (unfiltered)
  for _, k in ipairs(list) do ... end
end)
```

Fires when the roster changes: a kin **added** or **removed**, **renamed/regrouped**, or flipping
**online/offline**. The payload is the **new full kin list** (the same shape `list()` returns), so a
handler has the data without a second call (matching `EquipChanged`/`VitalsChanged`, which pass their
snapshot).

**Mechanism — uimsg-driven (a `TreeAdapter`), change-detected by a snapshot diff:** every roster change
the client learns of arrives as a targeted `uimsg` to the `BuddyWnd` — `add`, `rm`, `upd` (nick/group
edit), `chst` (online-status flip). So — unlike buffs/study, whose add/remove is a widget
create/`cdestroy` invisible to the tap and therefore *polled* — this rides the **1d-1 inbound-`uimsg`
tap**: `KinAdapter.interested` flags those four messages (off-thread, post-apply), and the tick's
`refresh()` re-reads the snapshot list and fires `KinChanged` **only when it actually differs** from the
cached one. It does **zero work when the roster is idle** (the common case).

> **Why a snapshot diff, not `BuddyWnd.serial`?** `serial` bumps on `add`/`rm`/`upd` but **not** on
> `chst` — the very online/offline flip a kin-alert addon most wants. Diffing the snapshot (id / name /
> group / online per entry) catches all four. The diff is order-sensitive, which is correct here: the
> Kin window only re-sorts on `add`, so a reorder is always driven by a real change we should report.

A few `KinChanged` fire at login as the roster streams in (like `VitalsChanged`/`BuffAdded` do) — expect
that and gate your logging.

## The `hello` example (`addons/hello/main.lua`) — read-only

- A new `readKin(tag)` helper logs `list()` — the count, how many are online, the first kin's
  `name`/`group`/`online`, and a `find(first.name)` round-trip — in both the `[now]` pass (usually `0` —
  still streaming) and the `[+3s]` pass (populated). One login re-checks every prior slice **and** this
  one. Bumped to **v0.26.0**.
- A `KinChanged` handler logs the first few roster updates **and** keeps its own last-online set so it can
  name **who just came online / went offline** on every later change — the kin-alert pattern, built on
  the single `KinChanged` + `list()` payload.

`hello` is **read-only** for kin — kin **add/remove/rename** is the **gated Phase-4 action tier**
(`BuddyWnd` `wdgmsg`), not shipped here.

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```bash
ant run
```

Read side (the harness, automatic):

- After `[hello] entered the world`, at `[+3s]` expect
  `[hello] [+3s] kin=<n> (<o> online), first=<name> [group=<g> online=<bool>], find(name)-><name>`
  with `n` = your kin count (`kin=0` at `now` is normal — streaming). If you have no kin, `n=0` and
  `first=none` — add one to see it populate.

Event side:

- On a fresh login `KinChanged` fires a few times as the roster streams in.
- Have a kin **log in or out** (or add/remove one) → a `KinChanged: <name> came ONLINE` /
  `went offline` line appears live.

From `:lua`:

```
:lua hafen.kin.list()
:lua hafen.kin.find("SomeKinName")
:lua #hafen.kin.list(function(k) return k.online end)   -- how many kin are online
```

## Files

- `src/io/brodgar/addon/AddonManager.java` — **the only file changed**: the `hafen.kin`
  `list`/`find` facade; the `KinAdapter` (uimsg-driven, registered in `init()` alongside the other
  `TreeAdapter`s); helpers `buddywnd`, `kinSnapshot`, `kinList`, `kinFind`, `kinListEqual`
  (change-detection), reusing the shared `matches` filter and `color` helper; one new import
  (`haven.BuddyWnd`).
- `addons/hello/` — `readKin` helper + `KinChanged` handler + manifest bumped to **v0.26.0**.

**No `haven` core edit.**

## Threading & safety

- `KinAdapter.interested` runs on the Loader thread under `synchronized(ui)` (the inbound-`uimsg` tap,
  post-apply); it only flags the adapter dirty — no Lua, no read. `refresh()` (and both facade functions)
  run on the **UI thread** (the tick / the `:lua` console), so the read never races Lua.
- Iterating `BuddyWnd` uses its public `iterator()`, which **copies the list under the widget's own
  lock** — snapshot-safe against the network thread's `add`/`rm`/`upd` mutations.
- `kinSnapshot` reads only plain public fields (no resource resolve → no `Loading` to guard), and
  bounds-checks `group` against `BuddyWnd.gc.length` before indexing the palette.
- The adapter holds a single cached `LuaValue` (the last list), reset each session by its
  re-instantiation in `init()` (the `treeAdapters` list is rebuilt) — no listener, no leak across
  `:reload`/relog.

## Limitations / deferred

- **Read only.** Kin **add / remove / rename / regroup** are `BuddyWnd` `wdgmsg`s → the **gated Phase-4
  action tier** (`hafen.kin.add/remove/rename`), not shipped here.
- **`online` is a boolean** — the `-1` (hearth-secret-only) vs `0` (offline, known) tri-state, plus
  `Buddy.seen` / `notes`, are not exposed (deferred; add if a use case appears).
- **No granular `KinOnline`/`KinOffline` event** — a single `KinChanged` carries the new list; derive
  per-kin transitions by diffing against your own last copy (as `hello` demonstrates).
- **`find` name match is exact (case-insensitive)** — for substring/fuzzy or multi-match use
  `list(filter)`.
- The **presentation name / hearth-secret** fields and the `BuddyWnd` avatar/description sub-panel are
  out of scope (UI, not roster data).
