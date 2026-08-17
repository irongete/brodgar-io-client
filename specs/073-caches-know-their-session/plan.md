# 073 — plan

## Approach

One new object, one census, and four conversions that work from it. Every task is **inert by
construction** — with one session live a per-session index holds one entry — so every suite here
proves *sameness*, exactly as `072`'s did, and the cross-session proof lands in the feature that
turns the engine's survival on.

### `SessionState`, keyed by the `UI`

A single class holding what today is scattered across eleven files as statics, reached through
`AddonManager.state(UI)` and never through a field anything writes by hand.

**The key is the `UI`, for three reasons that agree.** It is what the engine already holds at almost
every call site after `072` — `w.ui` at a monitor, `host()` at a tree read, `screen()` at the
pointer. It is what a relogin replaces: `Sessions.Member.run` builds a fresh `UI` through
`UILoop.bgui` for each runner in the chain, so a new `UI` is precisely the moment every cached widget
id, gob id and slot index stops meaning anything. And it is not `Member`, which **survives** a relogin
by swapping its `sess` and `ui` — keying on that would mean "the same slot in the switcher", which is
not what a widget cache is about.

`AddonManager.state(UI)` creates on first ask and is the only door. There is no `state()` with no
argument: an accessor that guesses is the ambient field this sequence spent `072` deleting.

### The census, and why it is a file

Criterion 3 is that the split between per-session and process-wide is **decided once and written
down**. `073.1` builds `specs/073-caches-know-their-session/census.md`: every static collection in
`src/io/brodgar/addon/`, with its verdict and the one-line reason. The later tasks work from it
rather than re-deriving it, and it freezes with the folder.

The test each entry is judged by, and it goes at the head of the census: **does this name something
that belongs to one login?** A widget id, a gob id, a marker ref, a bar slot, a `GameUI` adapter — per
session. A user preference, a keybind registration, a panel's state, the `:lua` REPL's owner, `Prof`'s
switch — process-wide, because they describe the *client*, not a character.

Two that will look ambiguous and are not: `HttpApi`'s in-flight requests are per session (they were
started by an addon running for one, and `HttpApi.reset()` already drops them on a switch for exactly
that reason), while its host allowlist is per **addon** and belongs to the manifest, not to either.

### Release, and the counter that proves it

State dies with its `UI`. The hook is where a `UI` already dies: `Sessions.Member.run`'s `finally`
(and its per-runner `u.destroy()` inside the loop, which is the relogin case), plus
`UILoop.newui`'s destroy for the login slot. A sweep keyed on `UI.destroyed` is the backstop, because
a hook missed is a leak that grows one entry per relogin and shows up as nothing at all.

`AddonManager.state(UI)` on a destroyed `UI` must answer without resurrecting an entry, or the
backstop and the accessor race each other forever.

The observable claim is one number: **`states` in the `hafen.client():profiling():session()` group,
which must equal `live`.** A leak reads as `states > live`; a premature release as `states < live`.
That is criterion 2, and it is the one thing in this feature a program can check.

### `Voice`

`io.brodgar.voice.Voice` holds `private static volatile MapView view`, written by hand from the same
`MapView` seam `072` removed from the addon layer, and able to disagree with the session on screen in
the same way. It reads through the drawn view instead. This is not a cache and joins no census — it
is the last copy of "the view" in the tree, and leaving it would mean the sequence deleted the
pattern everywhere except the one package nobody looked at.

## Files to create and modify

| File | Task | What |
|---|---|---|
| `specs/073-caches-know-their-session/census.md` | 1 (writes), 2–5 (read) | every static collection, its verdict, its reason |
| `src/io/brodgar/addon/AddonManager.java` | 1–5 | `SessionState`, `state(UI)`, the release hook, the `states` counter; its own 23 collections |
| `src/io/brodgar/addon/{UiApi,LuaWidget,Layout,Gesture}.java` | 2 | the widget cluster |
| `src/io/brodgar/addon/{CharApi,BeltHold}.java` | 3 | the HUD adapters and the bar holds |
| `src/io/brodgar/addon/{VrApi,MapApi}.java` | 4 | the world indexes |
| `src/io/brodgar/addon/{HttpApi,StoreApi}.java` | 5 | in-flight requests, the store's session scope |
| `src/io/brodgar/voice/Voice.java` | 5 | the last ambient view |
| `src/io/brodgar/session/Sessions.java` | 1 | the release hook's call site |
| `docs/addons/api/client/profiling/counters.md` | 1 | `states`, in the `session()` group |

## Risks and gotchas

- **The compiler does not help once**, and that is this feature's whole shape. A cache reached
  through `state(host())` where it should be `state(w.ui)` compiles, draws, and is wrong only when
  two sessions exist. Every conversion states which `UI` it keyed on and why, in the code.
- **`resetSession()` must not simply become `state(u).clear()`.** Today it is called from
  `AddonManager.init` and means *the session ended*. After this feature the same call on a switch
  would wipe a session that is merely no longer drawn. Each `resetSession` either becomes a release
  hung on the `UI`'s death, or stays where it is because `init` still runs — decide per subsystem and
  write which in the census.
- **`CharApi.treeAdapters` is rebuilt, not cleared**: `resetSession` re-adds nine adapters. Per
  session, that is nine per session, constructed when the state is, not when a switch happens.
- **`UiApi.resetSession` reaches into `consoleOwner`** — the `:lua` REPL's own addon record — and
  clears six of its collections. `consoleOwner` is process-wide (it persists across sessions by
  design) while those six collections name one session's widgets, so this is the one place where a
  process-wide object holds per-session state, and it needs the same treatment as the rest.
- **`AddonManager`'s nine queues** (`gobEvents`, `overlayEvents`, `removedWidgets`, `resolveQueue`,
  `beltSetQueue`, `resizedWidgets`, `textRewrites`, `markerChangeQueue`) are filled from Loader and
  network threads and drained on the tick. Per session they stay concurrent collections; what changes
  is which one an enqueue picks, and the enqueuer must resolve its `UI` off the object it was handed,
  never off `host()`, because those threads are not the drawn session's.
- **`ant hafen-client` is incremental**; `rm -rf build/classes` before believing any compile.

## Discarded alternatives

- **A map per collection inside each subsystem, rather than one `SessionState`** — eleven independent
  indexes means eleven release paths, and the leak that outlives the feature is the one in the file
  nobody remembered had a map.
- **Keying on `Sessions.Member`** — a `Member` survives a relogin by swapping its `sess` and `ui`, so
  its identity is "this slot in the switcher", not "this login". Every cached widget id and gob id
  dies at a relogin, which is exactly the boundary a new `UI` draws and `Member` does not.
- **Keying on `Session`** — it changes with the `UI` and would work, but the engine holds a `UI` at
  almost every call site after `072` and a `Session` at almost none, so it would add a lookup at each
  site to answer the same question.
- **An argumentless `AddonManager.state()` defaulting to the drawn session** — the ambient field with
  a longer name. Every site that could call it is a site that has stopped saying which session it
  meant, which is the whole thing `072` bought.
- **Doing this together with the engine's survival, so the cross-session assertion is provable** —
  eleven subsystems converted and a lifecycle switched in one feature, with no green tree between
  them. Inert first and observable second is slower to feel like progress and is the only order where
  a failure names its cause.
- **Leaving `Voice.view`** — it is the same hand-written copy of the drawn view, one package over,
  and a sequence that deletes a pattern everywhere except where it did not look has not deleted it.
