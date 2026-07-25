# A9-1 — Quest log read (`hafen.quests`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **26/26 headless checks**
> (`questStatus`/`questCondStatus`/`questActive` map every status code incl. the unknown fallback; the pure
> `questDiff` covers new-active→`QuestAdded`, new-completed→silent, active→finished→`QuestDone`,
> pending→disabled→none, removed→pruned, insertion-order, disabled/failed transitions, reopen→none, and the
> streaming add-then-done / completed-stay-silent sequences) + LuaJ parse of the harness under
> `Sandbox.create()`. The snapshot shape reads live resources (`title`/`res`) → verified **in-game**.
> **In-game verification pending.**
> **Design:** [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.quests` /
> `hafen.wounds` gap-subsystem **A9**). A9 bundled **quests + wounds**; per the queue's "split each when
> reached", this slice is **A9-1 (quests)** — [A9-2 (wounds)](#deferred--a9-2-wounds) is next.

A quest tracker — "what quests do I have, which are done, and what are the objectives of the one I'm looking
at?" — needs to read the **quest log**. The client holds it in the **`QuestWnd`** widget (`@RName("quests")`),
the character sheet's **"Quest Log"** tab, reached through the public **`CharWnd.quest`** field. `hafen.quests`
exposes it as a **read** surface. There is **no quest action tier** (the player advances quests by playing), so
this is read-only by nature — no gated Phase-4 counterpart.

## Zero core edits — every backing is public

No `haven` file changed. Like [A8](a8-craft.md)/[A7](a7-speed.md)/[A6](a6-kin.md)/[A4](a4-skills-credos-lore.md)/[A2](a2-radar.md),
every field read is already public:

- `CharWnd.quest` (`public QuestWnd`) — the quest-log window, held by the character sheet (created hidden at
  login but **live**, so quests read without the window ever being opened). No tree-walk — a direct named
  field, like FEP's `CharWnd.battr`.
- `QuestWnd.cqst` / `QuestWnd.dqst` (`public final QuestList`) — the **Current** (active) and **Completed**
  tabs; `QuestList.quests` (`public List<Quest>`) and `QuestList.get(int)` are public.
- `QuestWnd.Quest` — `public final int id`, `public Indir<Resource> res`, `public String title` (may be
  `null`), `public int done` (a status code), `public int mtime`. The status codes `QST_PEND`/`QST_DONE`/
  `QST_FAIL`/`QST_DISABLED` are `public static final int` (compile-time **constants** → inlined at use sites,
  so the status helpers never load `Quest`, whose `<clinit>` renders text and would fail headless — the same
  reason A8's `craftSpec` shape test was a headless skip).
- `QuestWnd.quest` (`public Quest.Info`) — the **selected** quest's `Quest.Box` (or `null`); `Box.id`
  (`public final int`) and `Box.cond` (`public Condition[]`) are public, as are `Condition.desc`
  (`public final String`), `Condition.done` (`public int`) and `Condition.status` (`public String`).

So the only file changed is `AddonManager.java` (the bridge) + the `hello` harness. No `AddonWidgets`
haven-package accessor is needed (nothing read is private/protected).

## `hafen.quests.list([filter])` — every quest

```lua
for _, q in ipairs(hafen.quests.list()) do
  -- q.id       -- number, the server quest id
  -- q.name     -- string, the quest title (Quest.title(): explicit title, else the resource tooltip)
  -- q.res      -- string, the stable resource id (omitted while Loading)
  -- q.status   -- "pending" | "done" | "failed" | "disabled"
  -- q.mtime    -- number, the server modification stamp (higher = more recently changed)
end

-- Only the active quests (Current tab) — the canonical predicate filter (one canonical way, no .active()):
local active = hafen.quests.list(function(q) return q.status == "pending" or q.status == "disabled" end)
```

Returns snapshots for **both** the Current (active: `pending`/`disabled`) and Completed (`done`/`failed`) tabs.
`filter` is the canonical **nil = all / name-substring / predicate** (shared with `hafen.gob`/`world`/`kin`/
`radar`); the string form matches the quest `name` (the snapshot's display field, like every other snapshot).

## `hafen.quests.selected()` — the open quest, with objectives

```lua
local q = hafen.quests.selected()
if q then
  -- same fields as a list() entry, plus:
  -- q.conds[i] = { desc = <string>, status = "pending"|"done"|"failed", text = <string?> }
end
```

Returns the quest **currently open** in the Quest Log — the **only** one whose conditions/objectives the
client loads — as a `list()` snapshot **plus** a `conds` array, or **`nil`** when nothing is selected (e.g. at
login, before the player clicks a quest). Each condition is `{desc, status, text?}`: `desc` is the objective
text, `status` maps the condition's 0/1/2 code to `pending`/`done`/`failed` (conditions have **no** "disabled"
state — only three status glyphs exist), and `text` is the objective's extra status string when present
(e.g. a `3/10` progress note), absent otherwise.

> **Faithful limitation — conditions are per-*selected*-quest.** The client only loads a quest's conditions
> when the player **selects** it (a `qsel` → the server sends the quest's `Box` + a `conds` update). So
> `selected()` exposes the objectives of the **one open quest**, not all quests at once — mirroring what the
> window shows, exactly like [A8](a8-craft.md)'s "the open recipe" and [A1](a1-markers.md)'s "x,y only in the
> player's segment". `list()` gives every quest's header (title/status); `selected()` gives the open one's
> objectives.

## Events — `QuestAdded` / `QuestDone`

```lua
hafen.events.on("QuestAdded", function(q) end)  -- a new ACTIVE quest appeared (payload = the snapshot)
hafen.events.on("QuestDone",  function(q) end)  -- an active quest was completed/failed (q.status says which)
```

- **`QuestAdded`** — fires when a quest first appears in the **active** set (`pending`/`disabled`). A quest that
  is **already finished** when first seen (the completed **history** that streams in at login) is recorded
  **silently** — no event — so the log's history never spams a fresh subscriber. Read the initial state with
  `list()` in `OnEnterWorld`; listen for deltas after.
- **`QuestDone`** — fires when a previously-**active** quest transitions to a **finished** state (`done` or
  `failed`), mirroring `QuestWnd`'s own completion trigger (`(was pending/disabled) && (now done/failed)`).
  The payload's `status` distinguishes completed from failed, so there is **one** event, not two.

### Mechanism — a uimsg-driven adapter + a pure diff

Every quest change the client learns of arrives as a targeted **`"quests"` `uimsg`** to the `QuestWnd` (a quest
added, its status advanced, or removed). So — like the [A6 `KinAdapter`](a6-kin.md) — the new `QuestAdapter` is
**uimsg-driven** (the [1d-1](phase-1d1-vitals-widget-tree.md) inbound-`uimsg` tap): `interested()` flags the
`"quests"` message off-thread → the tick re-reads the full quest set on the UI thread and diffs it against a
per-id status cache. The diff itself is a **pure** static helper, `questDiff(cache, fresh)` (both
`Map<Integer,Integer>` of id → status), returning the events to fire as `{event, id}` pairs and updating the
cache — no widget or Lua access, so the add/complete **semantics** are fully headless-testable (13 of the 26
checks). The adapter then maps each event's id back to the live `Quest` for the payload snapshot and fires
through `callLua`. The cache is per-session (reset by re-instantiation in `init`) and survives a `:reload`
(session infra is untouched), so a completion after a reload still fires correctly.

## The `hello` example (`addons/hello/main.lua`) — read-only

- A `readQuests(tag)` helper logs `list()` (total + active count + the first quest's name/status) and
  `selected()` in the `[now]` and `[+3s]` login passes. Like the rest of the character sheet the log **streams
  in** a beat after enter-world, so `now` often shows `quests=0` and `+3s` the real count.
- `QuestAdded` / `QuestDone` subscriptions: the first few `QuestAdded` at login are logged (as active quests
  stream in), then every completion is narrated in full.
- A new **`:hello quest`** sub-command calls `dumpQuest()`, printing the selected quest's title/status and one
  line per objective (`desc` + `status` + `text`). This is the on-demand read: open the Quest Log, click a
  quest, run `:hello quest`.

One login re-checks every prior slice **and** this one. Bumped to **v0.29.0**. `hello` stays **read-only** —
there is no quest action tier to gate.

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```bash
ant run
```

1. At login the harness logs `[hello] [now] quests=…` and `[hello] [+3s] quests=…`. The `+3s` pass should show
   your real quest counts (e.g. `quests=37 (4 active), first='<title>' [done], selected=none`) — `selected` is
   `none` until you open a quest. A few `QuestAdded: '<title>' [pending]` lines fire as active quests stream in.
2. Open the **character sheet → Quest Log** tab (or click a quest's tracker line in the HUD). Click a quest in
   the **Current** list, then run in the console (chat):

   ```
   :hello quest
   ```

   Expect the quest title + status and one `cond[i]` line per objective (its `status` and, where the server
   sends one, a `-- <text>` progress note).
3. Cross-check directly from `:lua`:

   ```
   :lua #hafen.quests.list()
   :lua hafen.quests.list(function(q) return q.status == "pending" end)
   :lua hafen.quests.selected()          -- nil until you select a quest; then the open quest + conds
   :lua hafen.quests.selected().conds
   ```

4. **Complete or advance** a quest (or watch one complete naturally) — a `QuestDone: '<title>' -> done` (or
   `-> failed`) line should appear. Selecting a different quest and re-reading `selected()` should reflect the
   new one.

## Files

- `src/io/brodgar/addon/AddonManager.java` — **the only engine file changed**: the `hafen.quests.list` /
  `selected` facade; the `QuestAdapter` (registered in `init`) + the pure `questDiff`; helpers `questwnd()`
  (the `CharWnd.quest` locator), `questList`, `questSelected`, `questSnapshot`, `questConds`/`questCond`,
  `questActive`, `questStatus`/`questCondStatus`; one new import (`haven.QuestWnd`). Reuses the shared
  `matches` filter and `resIdent`/`resTipName` name helpers.
- `addons/hello/` — `readQuests`/`dumpQuest` helpers, `QuestAdded`/`QuestDone` subscriptions, a `quest`
  sub-command on `:hello`; manifest + load-line bumped to **v0.29.0**.

**No `haven` core edit.**

## Threading & safety

- All calls run on the **UI thread** (the addon tick / the `:lua` console). `questwnd()` returns `CharWnd.quest`
  or `null` when the character sheet / quest tab isn't up yet — **null-safe, no NPE** (headless-verified).
- **List/array copy under the `ui` monitor.** The quest lists (`cqst.quests`/`dqst.quests`) and the selected
  box's `cond[]` are mutated on a **Loader thread** by `QuestWnd.uimsg("quests")` / `Box.uimsg("conds")` under
  `synchronized(ui)`. So `questList`/`questSelected`/`QuestAdapter.refresh` copy the list/array references
  inside `synchronized(ui)`, then build the Lua snapshots **outside** the lock — the marker "copy under the
  lock, snapshot outside it" discipline ([A1](a1-markers.md)/[A8](a8-craft.md)). Resolving `title`/`res`
  (`res.get()`, which may `Loading`) happens outside the lock.
- Resource reads are `Loading`-guarded (`resIdent`/`resTipName` swallow `Loading` → the field is omitted /
  falls back to the explicit title).
- The adapter cache is UI-thread-only, reset per session in `init`, and survives `:reload` (session infra is
  untouched) — nothing to leak.

## Limitations / deferred

- **Conditions only for the selected quest** (a client limitation — see above). Reading all quests' objectives
  at once is not possible client-side.
- **No `QuestRemoved` event** and **no per-condition-progress event** — the api-reference names only
  `QuestAdded`/`QuestDone`; a removed quest (`res == null`) is pruned from the cache silently, and objective
  progress is read on demand via `selected()`. Both are trivial future adds (a condition change rides the
  `Box` `"conds"` uimsg through the same tap) if a use case appears.
- **Instant-complete / reopen edges:** a quest that arrives **already finished** on first sight fires neither
  event (it's treated as history); a finished quest **re-opened** to active (`done → pending`) fires no event.
  Both are rare and intentional (mirroring `QuestWnd`'s own completion trigger, which only fires
  active→finished).
- `mtime` is exposed **as-is** (the server's opaque stamp; the Quest lists sort by it, most-recent first).

## Deferred — A9-2 (wounds)

A9's second half — **`hafen.wounds`** (`WoundWnd`, reached via the public `CharWnd.wound` field; a
`WoundChanged` event) — is the next task. Then **A10** (`hafen.fight`, the combat deck/maneuvers over
`FightWnd`, `@RName("fmg")`). Read surfaces now; gated actions in Phase 4.
