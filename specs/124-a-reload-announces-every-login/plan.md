# 124 — Plan

## Approach

`AddonRegistry.reload()` ends by taking `AddonManager.state(screen())`, and where that session has a
`GameUI` it calls `StoreApi.enterWorld(st, g)` and then `fireSession("SessionEnteredWorld", who)` with
`Sessions.nameof(st.ui)`. That tail becomes a walk of `AddonManager.allStates()`, ordered so the
screen's state is taken first and the rest follow, applying the same three gates per state and the same
two calls behind them:

- `st.ui.destroyed` is false — `sweepStates()` runs on a tick, so a dead entry can still be in the map;
- `AddonManager.gui(st.ui)` is non-null — the session is in the world, and this is also what excludes
  the addon layer's own `SessionState`, which has no HUD;
- `Sessions.nameof(st.ui)` names an account — a member that has gone answers `null`.

Nothing else in the method moves. The order of `teardown` → `StoreApi.detach()` → `loadAll()` → this
walk stays exactly as it is: `detach` has to have cleared `charStores` before `enterWorld`'s own
`unloadChar` runs, or a character's tables would be written back by addons that no longer exist, and
`loadAll()` has to have registered the new addons' handlers before anything fires at them.

`reload()`'s javadoc states the old boundary in two paragraphs — the one-line summary and the
*"The screen's session and no other"* paragraph. Both are rewritten to the rule that now holds, present
tense, with no note of what the method used to do.

The engine change and the addon sweep are **one task**, so there is never an intermediate state in
which an addon carries its catch-up loop *and* receives the new announcement, which would build its
per-login state twice.

## Files to create / modify

**Modify — 124.1**

- `src/io/brodgar/addon/AddonRegistry.java` — `reload()`'s tail and its javadoc.
- `addons/actionbars/main.lua` — the bare `syncAll()` at file-body level. `syncAll` itself stays: four
  other callers, and `screenBox()`'s own walk of the logins is genuine.
- `addons/autodrop/main.lua`, `addons/clickpath/main.lua`, `addons/item-indicators/main.lua`,
  `addons/simple-chat/main.lua`, `addons/simple-minimap/main.lua`, `addons/water-meter/main.lua`,
  `addons/stockpile-controls/main.lua` — the catch-up call and the comment above it. In five of them it
  is the whole body of a `hafen.event():on("Load", …)`, which goes with it.

**Modify — 124.2**

- `docs/addons/api/event/bus/lifecycle.md` — *These report changes, not the state*.
- `docs/addons/runtime.md` — the moments table's **Once per session**, and *What a reload keeps, and
  what it drops*.
- `docs/addons/guides/events-and-timers.md`, `docs/addons/getting-started.md` — the one sentence each
  that names the character on screen.
- `docs/addons/api/session.md` — *Sessions that come and go*, and the example under it.

**Create** — `addons/124-a-reload-announces-every-login.1/` and `.2/`.

No `docs/client/` page is owed: everything read here is `src/io/brodgar/addon/**`, which that subtree
does not document.

## Risks and gotchas

- **`allStates()` is `states.values()` and holds more than the logins.** A `SessionState` is minted for
  the addon layer itself, and a destroyed `UI`'s entry survives until `sweepStates()` removes it. The
  three gates above are the ones the current code already leans on for the screen; none is new.
- **Read `st.ui`, do not call `AddonManager.state(u)` again.** That method refuses to mint for a
  destroyed `UI` and would answer `null` for exactly the entries the walk is iterating.
- **Do not touch `SessionState.enterWorldPending`.** The reload's announcement is not the tick's; arming
  the flag would make `AddonManager.tick` fire a second one for the same session a frame later.
- **`StoreApi.rescope()` reads `AddonManager.screen()` itself**, so it does not care which session
  `enterWorld` was called for. Called once per session it is N string compares and at most one write.
- **Do not add `BeltHold.restore(st)`.** `beltPlaced` lives on `SessionState`, survives the layer, and
  the bar is put back by `BeltHold.entryAdded` as each `AddonPagina` is re-added. Calling it would
  re-read a file to arrive at what is already in memory.
- **`fireSession` is guarded by `hasSub`**, so a session costs one map lookup per addon that never
  subscribed.
- **The suite's discriminator rests on `loadAll()` running before the announcement.** If that order ever
  moves, the suite reports its precondition as not reached rather than passing on a scenario that
  proves nothing.
- **`docs/client/multi-session.md` is over its ceiling and must not be touched here** — it maps upstream
  `haven`, and nothing this feature reads belongs to it.

## Discarded alternatives

- **Reload announces nothing, and the query becomes mandatory and uniform** — the other end of the same
  line, and coherent: events would report changes and nothing else. Rejected because it breaks every
  addon that starts its real work in `SessionEnteredWorld`, and keeps the boilerplate this feature
  exists to delete.
- **Replaying the session keys at subscribe time, the way a selector subscription replays a tree** — a
  selector subscription is a live query over a tree and can answer *what matches right now*; the bus is
  a stream of things that happened. Making one bus key answer with state opens the question for the
  whole catalogue, and an object-arrival key has no honest answer to it.
- **A convenience verb — `hafen.session():each(fn)`, or a replay flag on the subscription** — a second
  way to say what `hafen.session():list()` already says, and it leaves the silent failure standing for
  every author who does not know to reach for it.
- **Leaving the loop and documenting it harder** — the failure is invisible under one login, which is
  how an addon is written and tested, so a page is not where it can be caught.
- **Announcing the background sessions and leaving the screen's last** — the order would then be the
  map's, so a single login's behaviour would be observably different for no gain. Screen first makes
  the change purely additive.
- **Keeping the boundary and giving the background sessions a key of their own** — a second key meaning
  *this session is in the world and you were not told* is the catch-up loop with a subscription around
  it, and every addon would have to handle two keys to be correct.
