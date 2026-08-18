# 073 — the census

Every static collection in `src/io/brodgar/addon/`, with its verdict and the one line that decides it.
Criterion 3 is that this split is **decided once and written down**; `073.2`–`073.5` work from this table
rather than re-deriving it.

**The test, and it is the only one:** *does this name something that belongs to one login?* A widget id, a
gob id, a marker ref, a bar slot, a `GameUI` adapter — **per session**. A user preference, a keybind
registration, a panel's state, the `:lua` REPL's owner, a catalogue the client compiles in —
**process-wide**, because it describes the *client*, not a character.

Three verdicts, and the third earns its place rather than dodging the second:

| Verdict | Means |
|---|---|
| **per session** | it names one login's things, and it lives in `AddonManager.SessionState`, reached through `state(UI)` |
| **process-wide** | it does not name one login's things, and it stays exactly where it is |
| **deferred** | it *does* name one login's things, and converting it alone would leave a half-conversion worse than either end — the task that takes it is named, and so is what has to move with it |

**Where the clearing lives.** Route (a) — cleared by `AddonManager.init`, which meant *the session ended* and
ran on every anchor change — and route (b), dropped whole when the session's `UI` is destroyed
(`AddonManager.uiDestroyed`). **074.2 collapsed (a) into (b)**: a switch ends nothing, `init` is gone, and
every per-session collection now lives exactly as long as the `UI` it is keyed on. The one clearing that had
to find a new home is the prune of records naming widgets of a tree that died, which hangs on `uiDestroyed`
and walks every addon rather than only the `:lua` REPL — because every addon outlives a session now.

**One shape recurs and is worth naming up front: a `WeakHashMap` keyed on a `Widget` needs no session
index.** The key *is* the session-shaped thing, it is held weakly, and it dies with the tree it was in. Such
a map is process-wide and correct — indexing it by session would add a second answer to a question the key
already answers. Every entry below that reads *self-releasing* is one of these.

---

## `AddonManager` — converted here (073.1)

| Field | Verdict | Why |
|---|---|---|
| `SessionState.addonRoot` | per session | the tick widget stands on *that* session's root |
| `SessionState.ocCb` | per session | the strong ref to that session's own `OCache` callback |
| `SessionState.enterWorldPending` | per session | "the world came up" is one session's world |
| `SessionState.hudUpSince` | per session | when *that* session's `GameUI` entered the tree |
| `SessionState.reloadPending` | per tree | a flag on the tree whose pump drains it. Since 074.2 the tree is the **addon layer**, because that is what a `:reload` rebuilds |
| `SessionState.dispatchingAction` | per session | it guards one dispatch, under one `UI`'s monitor |
| `SessionState.gobEvents` | per session | gob ids, and the callback is that session's own network thread |
| `SessionState.overlayEvents` | per session | gob ids again; the `Gob`'s `Glob` is what names the session |
| `SessionState.removedWidgets` | per session | widgets of one tree (`w.ui` at the seam) |
| `SessionState.resizedWidgets` | per session | the same, at the geometry seam |
| `SessionState.textRewrites` | per session | the same, at the caption seam |
| `SessionState.beltSetQueue` | per session | a slot index names one character's bar (`GameUI.ui` at the seam) |
| `states` | process-wide | it *is* the index: one entry per session, and no session owns the map |

| Field | Verdict | Why |
|---|---|---|
| `addons` | **process-wide (074.2)** | an `Addon` stopped belonging to a login. The set looked per-session only because `init` replaced it on every switch; deleting that made the deferral answer itself, and the answer is the opposite of what deferring it assumed — one instance per addon, loaded for the client, reaching sessions rather than living in one |
| `autoDisabledWarn` | **process-wide — with `addons` (074.2)** | one addon over budget is one warning. It lasts until a reload rather than until a switch, because a switch ends nothing |
| `clock` | **process-wide — with `addons` (074.2)** | the engine clock a *timer* is due on, and timers hang on `Addon`. It accrues on the layer's own tick, so it counts one second per second however many sessions are up, and it is never reset: a clock that restarted would make a timer set before a switch due at an instant already past |
| `resolveQueue` | **process-wide — with `addons` (074.2)** | a `Resolve` retry is owned by an `Addon` and cancelled by that addon's teardown; the seam is handed a bare `Runnable` and knows no session |
| `markerChangeQueue` | **deferred — 073.4** | the seam is handed a `MapFile` and nothing else: the on-disk map database, reached from disk, under its own lock. `MapApi`'s per-session marker maps are the only thing that can name a session for it |
| `consoleOwner` | process-wide | criterion 3 names it: the `:lua` REPL persists across sessions by design |
| `overlaySubs` | process-wide | it **arms a seam in `Gob`**, which belongs to no session. It gates cost, never content: a stale `true` costs one drain that finds no subscriber |
| `viewcache` | process-wide | the *screen's* memo, and there is one screen. It re-checks its own answer (`mv.ui == screen()`), so it cannot go stale |
| `prevProbed` | process-wide | which frame the profiler's probes were armed for — the client's frame, not a login's |
| `pictures` | process-wide | picture object → the resource it was decoded from. A resource is the game's, and the map is weak on the picture |

## `UiApi`, `LuaWidget`, `Layout`, `Gesture` — the widget layer (073.2)

| Field | Verdict | Why |
|---|---|---|
| `UiApi.selectorWatches` | per session | each holds matched **widgets of a tree** and fires on that tree's placements |
| `UiApi.pending` | per session | widgets waiting for a late caption, with a tick countdown of that session's |
| `UiApi.widgetSubsWatching` | per session | subscriptions on widgets of one tree |
| `UiApi.capChanged` | per session | widgets recorded at the caption seam (`w.ui` is in hand there) |
| `UiApi.unarmed` | per session | surfaces waiting to be placed into one session's tree |
| `UiApi.hudGout` / `hudAfterDraw` | process-wide | one draw wrapper and one one-shot after-draw, both stateless between frames |
| `LuaWidget.anyHidden` / `anyMoved` | process-wide | counts that gate a fast path; the *records* they count live on each `Addon` |
| `Layout.pending` | per session | widgets whose late `[title=]`/`[res=]` refiner is still being re-checked |
| `Layout.derived` | process-wide, self-releasing | `WeakHashMap` on the widget the anchor is on |
| `Layout.dragListeners` | process-wide, self-releasing | `WeakHashMap` on the drag target |
| `Layout.seq` | process-wide | a monotonic tiebreaker for rule order; it names no login |
| `Layout.capDirty` | process-wide | a flag that says *some* caption changed; it gates the re-check, never its content |
| `Gesture.arms` | process-wide, self-releasing | `WeakHashMap` on the armed handle widget |
| `Gesture.running` | per session | the gesture in flight is one session's press, on one session's widget |
| `Sheet.installed` | process-wide | the sheets an **addon** installed; a rule is per addon, not per login |
| `Sheet.cache` / `Sheet.skins` | process-wide, self-releasing | `WeakHashMap` on the styled widget |
| `Sheet.specs` | process-wide | one `Fonts.Style` per distinct resolved style — a value cache, keyed on the style itself |
| `Sheet.capChanged` | per session | widgets recorded at the caption seam, like `UiApi`'s |
| `Chrome.boxes` / `Chrome.paints` | process-wide | drawn boxes and paints keyed on the style that produced them |
| `CDropdown.toRaise` | per session | popups to re-raise in one session's tree, drained on that session's tick |
| `WidgetSubs.TREE_KEYS` | process-wide | a compiled-in catalogue of key names |
| `WidgetSurface.live` | per session | every standing surface hangs in one session's tree |

## `CharApi`, `BeltHold` — the HUD readers (073.3)

| Field | Verdict | Why |
|---|---|---|
| `CharApi.treeAdapters` | per session | each of the nine reads **a** `GameUI`, and there is one per session. Constructed when the state is, not re-added on a switch |
| `CharApi.treeDirty` | per session | which of *those* adapters an inbound `uimsg` marked |
| `BeltHold.holds` | per session | a slot index names one character's action bar |
| `BeltHold.placed` | per session | where an addon's entry belongs on *that* character's bar |
| `BeltHold.dirty` / `lastJson` | per session | the flush state of that character's own file |
| `FlowerMenuApi.live` / `clicked` | process-wide, self-releasing | `WeakHashMap` on the open `FlowerMenu` widget |

## `VrApi`, `MapApi` — the world (073.4)

| Field | Verdict | Why |
|---|---|---|
| `VrApi.anchored` | per session | keyed on **gob id**, which means a different object in the next session |
| `VrApi.free` | per session | entities standing at a place in one session's coordinate frame |
| `VrApi.groundDirty` | process-wide | it flags the **drawn** scene's cut map, and there is one scene |
| `VrApi.passes` | process-wide | a cumulative counter of the ground pass, which the profiler reports for the client |
| `VrApi.sessSeg` / `sessTc` / `sessSeen` | per session | the session coordinate space's own origin — the name says it |
| `MapApi.markerIds` / `markerById` | per session | interned refs into one session's `MapFile` |
| `MapApi.markerIdSeq` | process-wide | a counter that mints ids; two sessions minting from one sequence collide with nobody |
| `MapApi.markersPrimed` / `lastMarkerSeq` | per session | whether *this* session has been told the marker count once |

## `HttpApi`, `StoreApi` — the rest (073.5)

| Field | Verdict | Why |
|---|---|---|
| `HttpApi.results` | per session | a completion belongs to a request an addon running for one session started, which is why `reset()` already drops them on a switch |
| `HttpApi.starts` | per session | the same, one step earlier: which addon's queued request to launch |
| `HttpApi.pool` | process-wide | one thread pool for the client; it holds no session's anything |
| the manifest host allowlist | **per addon**, neither | it belongs to the manifest, and an addon's manifest is the same file whichever session it runs in |
| `StoreApi.charScope` | per session | `<genus>_<char>` is one character's folder |
| `StoreApi.lastAutoSave` | per session | the throttle for that character's own flush |

## Catalogues and reflection caches — process-wide, all of them

`HookApi.slashHandlers`, `HookApi.slashDispatched`, `HookApi.keyBinds`, `HookApi.KEYCODES`,
`Permission.BYKEY`, `Retired.NAMES`, `Retired.KEYS`, `LuaHttp.FORBIDDEN_HEADERS`, `LuaItem.qfields`,
`LuaItem.wfields`, `LuaKin.idfs`, `LuaGOut.resCache`.

Each is one of three things and none of them names a login: a **catalogue the client compiles in** (the
permission keys, the retired spellings, the forbidden headers, the key codes), a **registration made by an
addon** (a slash command, a keybind — criterion 3 names the keybind registry explicitly), or a
**reflection/resource cache keyed on a `Class` or a resource name**, which are the JVM's and the game's.
