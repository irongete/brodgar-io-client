# 122 — Plan

## Approach

**The screen is published on any thread; the view is moved only on the frame's.** `Sessions.anchor`
splits in two. The publishing half keeps `Sessions.class`, touches no widget tree, and does four
things: it reads the incoming session's offset out of `placed()`, writes `UILoop.drawn(target)`,
`invalidate()`s, and coalesces a request into an `AtomicReference`. `Sessions.class` becomes a **leaf
lock** and the cycle cannot form. The consuming half, `Sessions.tickview()`, runs at the top of
`UILoop.run`'s loop — after `env.render()`, before the `synchronized(uilock)` block that reads
`drawn()` — on the frame thread, holding nothing. It walks both trees (each under its own monitor)
and does `MapView.adoptcam` + `dormant(false)` on the incoming view, then `dormant(true)` on the
outgoing one.

The coalesce keeps the **first** `cur` and the **last** `target`, and drops the request outright when
they are equal: a switch away and back inside one step is not a screen change, and without that guard
the `dormant(false)`/`dormant(true)` pair lands on the same view and sleeps the one being drawn.

`Sessions.relinquish` re-reads `anchor() != u` **under** `Sessions.class` rather than outside it, and
picks its successor there too, so the screen cannot be taken from a session the player has just
switched to.

The other three edges are local. `ActAudio.RootChannel.mute` and `setvolume` become `synchronized` —
the missing edge against `mixer()`'s own block — and `ActAudio.Root.clear` gains the `aui.clear()` its
two siblings already get. `Sessions.add`/`adopt` **reserve the account name** before connecting, under
a private leaf monitor, released in a `finally`. `GameUI.savewndpos` splits into a guard and a body,
and the body is called from `MapView.dormant(true)` — the one place every exit from the screen passes
through.

## Files to create/modify

| File | What |
|---|---|
| `src/io/brodgar/session/Sessions.java` | `anchor(Member)` split; `tickview()`; a private locked walk; `relinquish` under the lock; `claiming`/`claim`/`enlist`/`unclaim`; `Member.start`'s failed-`Thread.start` unwind; the false comments on the lines `add`/`adopt` rewrite |
| `src/haven/UILoop.java` | one `// rts:` line in `run()`, between `env.render()` and the `uilock` block |
| `src/haven/ActAudio.java` | `RootChannel.mute`/`setvolume` `synchronized`; `Root.clear` gains `aui.clear()` |
| `src/haven/GameUI.java` | `savewndpos` → guard + `savewndpos0`; package-visible `leavingscreen()` |
| `src/haven/MapView.java` | `dormant(boolean)`'s `d == true` branch calls `getparent(GameUI.class).leavingscreen()` |
| `docs/client/multi-session.md` | the camera-adoption order, *Handing the screen over*, *One lock direction*, *It is silent*, *It does not fight over window geometry*, *Where a session comes from*. In place, no net growth |
| `docs/client/gameui-windows.md` | its *at logout AND every 60 s* row, which is false today |
| `docs/client/services.md` | the `ActAudio` channel row |
| `docs/addons/api/threading.md` | one table row: a keybinding handler, and the tree it holds |
| `addons/122-the-edges-of-a-session.{1..4}/` | the four suites, `:t122` |

## Risks & gotchas

- **`Sessions.mapview(UI)` must keep its contract.** Twenty addon sites reach it through
  `AddonManager.screenView()`, several — `SurfaceInput`, `CameraOptions`, `CameraFacing` — from
  handlers holding the **layer's** monitor. `synchronized(u)` inside it would nest layer→session,
  which `UILoop.Frame.tick`'s own comment declares forbidden. `tickview()` gets a private locked walk;
  the public verb is untouched.
- **Why the walk needs a monitor.** `Widget.child`/`next` are not volatile and `Widget.unlink` ends by
  setting `next = null`, so a concurrent `Widget.findchild` stops early and answers `null` in silence.
  The mutator is that session's **own Loader** — `UI.CommandQueue.execute` → `loader.defer`, with
  `NewWidget.run`, `AddWidget.run` and `DstWidget.run` each under `synchronized(UI.this)` — not the
  frame thread. A spurious `null` is sticky: miss `dormant(false)` and the session that just took the
  screen draws with no scene until the player switches away and back.
- **The offset is read in the publishing half, and that is not a detail.** After `drawn(target)` and
  `invalidate()`, `Sessions.buildplaced` rebuilds with `an == target` and answers `Coord2d.of(0, 0)`
  for the anchor, so a `tickview()` that read `placed()` itself would hand `adoptcam` a zero offset
  and land the pan a whole offset away between two distant characters.
  `MapView.Camera.restate` tolerates a null offset — it nulls place-typed fields, and `RTSCam.restate`
  re-centres only when it is non-null — so an unknown offset degrades to follow-the-character.
- **The honest price is one frame, on a session ending alone.** A request published by a session
  thread between `tickview()` and the `drawn()` read applies a frame late, so that frame draws the
  incoming session with its scene detached. Publishing `drawn()` **before** the request keeps that to
  the incoming session rather than blanking the outgoing one. A frame-thread switch never sees it: it
  publishes mid-frame and is consumed at the top of the next.
- **`ActAudio`: both writers or neither.** `mute` and `setvolume` both write `volc.vol` and read
  `muted`, and `mixer()`'s double-checked block is the only synchronized reader — so guarding `mute`
  alone leaves the same race reachable through the volume slider. The monitor is a leaf:
  `Sessions.applymute` runs from `Sessions.tick` holding nothing.
- **`finally`, never `catch`, around the reservation.** `connect(user)` is blocking network; a `catch`
  misses `InterruptedException` and `Error` and leaves the account unusable for the rest of the login
  — worse than the race it closes.
- **`GameUI.dispose()`'s `savewndpos()` is dead by two independent guards** —
  `Sessions.Member.discard` calls `relinquish` before `UILoop.bgdestroy`, and `bgdestroy` clears
  `drawui` — so `GameUI.onscreen()` is false by construction there. Do not revive it with a "this UI
  once had the screen" flag: that writes a dead character's geometry over the live one's.
- **122.4 cannot be automated.** `AddonWidgets.stockc` substitutes the coordinate the **user** last
  placed whenever an addon's layout stands on a widget, so a suite moving a client window with
  `widget:position()` makes `savewndpos` write the pre-suite value. Its check is a hand drag plus
  reading the site; a later task must not talk it into being automatic.

## Discarded alternatives

- **A dedicated lock in place of `synchronized` on `anchor()`** — it still nests under the outgoing
  tree's monitor; renaming a lock does not change its order.
- **Consuming the request in `Sessions.tick()`** — it runs at the *end* of `UILoop.Frame.tick`, so the
  frame in progress has already drawn the incoming session with its scene detached, and `Glob.ctick`
  has already called its glob non-dormant while its `MapView` still said otherwise.
- **Consuming it inside `synchronized(uilock)`, beside `lockedui = ui = drawn()`** — closes the
  one-frame window, and invents a `uilock`-plus-tree-monitor nesting on the one lock `UILoop.bgdestroy`
  already waits on. A blank frame on a logout is cheaper than a new lock order.
- **Deferring the publication too, not only the view** — `UILoop.bgdestroy` clears `drawui` the moment
  `relinquish` returns, so every logout would draw the login screen for a frame before the successor
  appeared.
- **`synchronized(u)` inside `Sessions.mapview`** — see the first gotcha.
- **Guarding the other three foreign-tree walks** (`Member.gameui`, `Member.autoplay`, `Member.status`)
  — `gameui()` caches on `guifor`/`guicache` and walks only while the member has no `GameUI`, and a
  member with no `GameUI` is already dropped by `buildplaced`, so a spurious `null` there changes
  nothing; the other two answer again on the next frame. Guarding them buys a nesting and no defect.
- **A queue of pending switches rather than one coalescing slot** — a switch superseded inside one
  frame is a switch that never happened, and replaying it would sleep a view twice.
- **`synchronized` on `Sessions.add` spanning `connect(user)`** — holds the class lock across two
  network round-trips and stalls every screen change for seconds.
- **Reviving the layout write in `GameUI.dispose()`** — see the seventh gotcha.
