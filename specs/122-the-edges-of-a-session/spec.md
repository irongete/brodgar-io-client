# 122 — The edges of a session

## What & why

A session has four moments at which its state changes: it **joins**, **takes the screen**, **leaves
the screen**, and **goes quiet** behind another. The layer gets all four wrong — invisibly while
nothing goes wrong, permanently once it does.

**The screen is the serious one.** `Sessions.anchor(Member)` is `static synchronized` and takes the
**outgoing** UI's monitor inside that lock. Every way of asking for the screen from the frame — a
keybinding handler, a click on a session's widget, a console line, an action hook, a draw handler —
arrives already holding that same monitor and then waits for `Sessions.class`. A session that is
ending takes the two in the other order, from its own thread, through `relinquish`. It is an
inversion, and it is exercised daily: `session-manager`'s cycle hotkey calls
`hafen.session():current(s)` under the drawn tree's monitor, and a session ending under the player's
hand is the other half. A local client starts no watchdog, so the outcome is a permanent freeze with
no frame, no tick and no diagnosis.

The other three lose something quietly. An account can be logged in twice, because the check that
forbids it is a read separated from its write by two blocking network round-trips. A background
session that never played a sound can stay audible for life, because the mute is written with no
barrier against the channel that reads it. And window geometry is no longer saved when a character
leaves the screen: the write that did it sits behind a guard false by construction on that path.

None of this was reported by a user. All four were found by reading, and all four are cheap to close.

## Acceptance criteria

Each is checked by the named task's own suite.

1. **`hafen.session():current(s)` is legal from every handler**, one holding a widget tree included,
   and moves the screen without waiting on anything: `:current()` reads the new session on the line
   after the write. *(122.1)*
2. **A switch away and straight back, inside one step, leaves the screen where it started** — drawn,
   with its click-map in the scene, so `s:world():screenToWorld(x, y, fn)` still answers. Naming the
   session already on screen still changes nothing and still fires no `SessionSelected`. *(122.1)*
3. **The camera survives the switch aimed where it was.** With the `rts` camera focused on a place, a
   round trip through another character returns the view to that place, not to an offset copy. *(122.1)*
4. **Closing the session that holds the screen hands the screen on** — to another live session, or to
   the login screen — and never takes it from a session the player has just switched to. *(122.1)*
5. **A login that fails leaves its account name free**, and an account already live is refused
   whichever of the two doors it comes through. *(122.3)*
6. **A background session is silent from the first sound it plays**, not from the second. *(122.2)*
7. **A character's window layout is written when the screen leaves it**, not up to sixty seconds later
   or never. *(122.4)*

## Out of scope

The boundary is **correctness at those four edges**. Each task ships a whole edge.

- **The dead code and stale prose the same review found** — seven unreferenced symbols in
  `Sessions.java`, and eleven comments and doc rows naming symbols that no longer exist
  (`Sessions.tickrebind`, `AddonManager.init`, a `SessionDestroyed` key spelled `SessionRemoved`).
  None of it is a defect at an edge; it is its own feature, and the natural next one.
- **What the layer costs per frame** — the per-session `OCache` tick, the frustum test per merged gob,
  the recalled-ground raster, the retained `Recall` cache. Not one number in that review is measured;
  `:stats on` prints the gauges, and measuring comes first.
- **Splitting `docs/client/multi-session.md`.** It is 182 lines against a 150-line ceiling and
  `specs/ROADMAP.md` already carries the split (*"`docs/client/multi-session.md` maps `io.brodgar`"*,
  filed 066). This feature corrects the rows it falsifies, in place and without growing the page.

## Docs impact

The derived set — the commands and what they returned:

```text
grep -rln "one lock direction\|two trees\|second tree" docs/
  -> docs/addons/api/threading.md · docs/client/boot-and-loop.md · docs/client/console.md
grep -rln "adoptcam\|outgoing camera\|hands the screen\|takes the screen" docs/
  -> docs/addons/api/session.md · docs/addons/examples.md · docs/addons/guides/events-and-timers.md
     docs/client/boot-and-loop.md · docs/client/camera.md · docs/client/multi-session.md
grep -rln "window geometry\|onscreen\|savewndpos\|positions it loaded" docs/
  -> docs/client/gameui-windows.md · docs/client/multi-session.md
grep -rln "RootChannel\|applymute\|It is silent\|going quiet" docs/
  -> docs/client/multi-session.md · docs/client/services.md
```

Written: `docs/client/multi-session.md` (camera-adoption order, *Handing the screen over*, *One lock
direction*, *It is silent*, *It does not fight over window geometry*, *Where a session comes from*) ·
`docs/client/gameui-windows.md` (its *at logout AND every 60 s* row is **false today**, and this
feature makes it true again from a different site) · `docs/client/services.md` (the audio channel
row) · `docs/addons/api/threading.md` (its table has **no row for a keybinding handler**, the one
holding the drawn session's tree and the one this defect fires from).

Checked, expected to stand: `camera.md` (only *when* the offset is read moves), `boot-and-loop.md`,
`session.md`.

## Context files

- `src/io/brodgar/session/Sessions.java` — 1, 3
- `src/io/brodgar/session/Control.java` — 1
- `src/haven/UILoop.java` — 1
- `src/haven/Client.java` — 1
- `src/haven/MapView.java` — 1, 4
- `src/haven/ActAudio.java` — 2
- `src/haven/GameUI.java` — 4
- `docs/client/multi-session.md` — 1, 2, 3, 4
- `docs/addons/api/threading.md` — 1
- `docs/addons/api/session.md` — 1
- `docs/addons/api/world.md` — 1
- `docs/addons/api/client/README.md` — 1
- `docs/client/camera.md` — 1
- `docs/client/boot-and-loop.md` — 1
- `docs/client/services.md` — 2
- `docs/client/gameui-windows.md` — 4
