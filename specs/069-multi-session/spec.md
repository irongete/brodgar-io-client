# 069 — Multi session: the vocabulary, and a mode that follows the sessions

## What & why

The client holds several accounts logged in at once and draws one of them. That system is called
**the fleet** everywhere it is spelled — a package, a class, a window caption, a console verb, a
profiler phase, nine names inside `MapView` — and the word says nothing true about it. There is no
fleet: there are sessions, and one of them is on screen.

The system becomes **multi session**. Its collection is `Sessions`, its package `io.brodgar.session`,
its console door `:session`. Nothing keeps the old spelling: no alias, no fallback, no refusal that
names it.

One behaviour changes with the name. `:fleet rts [on|off]` was a switch the user had to remember
beside the thing it depended on, and the mode is only ever wanted when there is more than one
session to command. It becomes **derived**: the mode is on exactly while a second session is up, and
goes off when the last one is dropped. The camera keeps following the mode, as it does today.

## Acceptance criteria

1. `:session add USER [CHAR]`, `:session drop USER|all`, `:session list`, `:session anchor USER|main`,
   `:session wnd` and `:session users` each do what the `:fleet` subcommand of that name did.
2. `:fleet` is not a client command: typing it answers `fleet: no such command`.
3. `grep -rin fleet src/ docs/` prints nothing — no identifier, comment, string, caption or doc line.
4. The package is `io.brodgar.session`; the collection is `Sessions` with `Sessions.Member`; the
   switcher is `SessionWnd` and its caption reads `Sessions`; notices are prefixed `session:`.
5. The UI-thread profiler phase is `sessions`: the client's own frame profile names it where it
   named `fleet`.
6. `:cam fleet` answers only the generic `no such camera: fleet -- the client has …`, with no
   sentence naming a replacement. `:cam rts` still installs the camera, and the binding panel still lists
   **Multi session** with `rts-focus` and `rts-next-anchor` under it.
7. The RTS mode has **no console verb**. It turns on when the first extra session enters and off when
   the last one leaves, and the camera swap and the selection clear happen on those two edges.

## Out of scope

- The RTS mode's design — selection, marquee, order routing, the `MOD_SEL` binding. Only its switch moves.
- **The order family.** `MapView.FleetClick` becomes `ClickOrder` so that an `AttackOrder` beside it
  reads as its sibling, but no second order is built and no shape is factored out of the one. That is
  filed as a ROADMAP candidate by task 3, which is where open work lives.
- The name `rts`: the camera, the two keybinding ids, `Control`, `RTSCam` and the `// rts:` comment
  tags all stand. Only `fleet` falls.
- Splitting `io.brodgar` policy out of `docs/client/multi-session.md` — a ROADMAP candidate (filed: 066).
  The page is corrected in place and its line stays on the ROADMAP, with the class renamed in it.
- Per-session addon state, and any change to what a dormant session does.

## Docs impact

Pages written: `docs/client/multi-session.md` (the console table, every class name, the mode section),
`docs/client/world-3d.md` (`MapView.FleetTerrain` → `SessionTerrain`, twice),
`docs/addons/api/client/profiling/README.md` (its phase list names six and omits this one; it gains
`sessions`).

Derived impact set — the prose names of this surface, across the whole of `docs/` and the ROADMAP:

```
grep -rin "fleet" docs/ specs/ROADMAP.md addons/
```

→ `docs/client/multi-session.md` lines 13–19, 28, 36, 54, 55, 59, 110, 121; `docs/client/world-3d.md`
line 22; `specs/ROADMAP.md` line 62. Nothing under `docs/addons/` and nothing under `addons/`. The
profiling page is reached by the second grep, not this one — its list simply never held the name:

```
grep -n "dwait" docs/addons/api/client/profiling/README.md
```

→ line 74, `dwait`, `stick`, `utick`, `draw`, `aux`, `wait`.

## Context files

- `src/io/brodgar/session/Sessions.java` — 1, 3
- `src/io/brodgar/session/SessionWnd.java` — 1
- `src/io/brodgar/session/Control.java` — 1, 3
- `src/io/brodgar/session/Recall.java` — 1
- `src/io/brodgar/session/Greyscale.java` — 1
- `src/haven/Client.java` — 1, 3
- `src/haven/Console.java` — 1
- `src/haven/UILoop.java` — 1, 2
- `src/haven/GameUI.java` — 1
- `src/haven/MCache.java` — 1
- `src/haven/RemoteUI.java` — 1
- `src/haven/res/lib/svaj/GobSvaj.java` — 1
- `src/haven/MapView.java` — 1, 2, 3
- `docs/client/multi-session.md` — 1, 2, 3
- `docs/client/world-3d.md` — 2
- `docs/client/boot-and-loop.md` — 2 (the UI-thread phase list, which the list above did not name)
- `docs/client/services.md` — 1
- `docs/addons/api/client/profiling/README.md` — 2
- `specs/ROADMAP.md` — 3
- `DOCUMENTATION.md` — 1, 2, 3
