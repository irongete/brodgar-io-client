# 069 — Tasks

- [x] **069.1 — The sessions layer, and the `:session` door.** Moves the five files of
      `src/io/brodgar/rts/` to `src/io/brodgar/session/`; `Fleet` → `Sessions`, `Fleet.Member` →
      `Sessions.Member`, `FleetWnd` → `SessionWnd` captioned `Sessions`, `Fleet.Sess` →
      `Sessions.Placed`, `Fleet.sessions()` → `Sessions.placed()`, `isfleet` → `ismember`.
      `Control`, `Recall` and `Greyscale` change their `package` line and nothing else. Retires the
      `:fleet` console command for `:session`, keeping every subcommand it had — `rts` included, which
      069.3 takes — and with it the usage strings, the `no such session: %s` refusal, the connect
      thread's name and the `session:` notice prefix. Every call site in `haven` follows.
      *Verified at the console, not by an addon* — sessions do not take the addon engine, so there is
      nothing for a suite to assert through: `:fleet` must answer `fleet: no such command`; the usage
      errors must name `session`; `:session users`, `add`, `list`, `drop`, `anchor` and `wnd` must each
      do what their `:fleet` twin did, with every notice beginning `session:` and the switcher window
      captioned `Sessions`. The task hands over the exact console script in its report.
      <!-- extra context: the whole `:fleet` block in `src/haven/Client.java` is the door being moved -->

- [x] **069.2 — `MapView`'s merged scene, said in sessions.** Renames the inner machinery:
      `FleetView`, `FleetTerrain`, `FleetGobs` and `FleetClickMap` → `SessionView`, `SessionTerrain`,
      `SessionGobs`, `SessionClickMap`; `fleettick`/`fleettick2` → `sessiontick`/`sessiontick2`,
      `fleetarea` → `sessionarea`, `fleetviews` → `sessionviews`, `lastfleet` → `lastsession`.
      `FleetClick` becomes **`ClickOrder`**, so that an order taking a target rather than a click can
      stand beside it. `UILoop`'s CPU phase `"fleet"` becomes `"sessions"`, and `MapView.setcam`'s
      `name.equals("fleet")` branch is deleted whole. `docs/client/world-3d.md` and the profiling
      page's phase list — which has never named this phase at all — follow.
      *Verified at the console and in the client's own profile window*: `:cam fleet` must answer only
      `no such camera: fleet -- the client has …`, with no sentence naming a replacement, and `:cam rts`
      must still install the camera; the frame's phase breakdown must read `sessions` where it read
      `fleet`. The merged scene itself is checked by eye — two sessions apart, the far one's ground and
      objects still drawn in the anchor's view. The task hands over the exact console script.

- [x] **069.3 — The mode follows the sessions.** `Sessions.tickmode()`, called from `Sessions.tick()`
      **above** its `members.isEmpty()` early return, compares that emptiness against a static
      `modeon` and calls `Control.mode(v)` on the edge alone — so the mode comes on when the first
      extra session enters and goes off when the last is dropped, camera swap and selection clear
      included. Deletes the `rts` subcommand and its word from `:session`'s usage line; `Control.mode`
      drops to package visibility. Rewrites the mode section of `docs/client/multi-session.md`,
      renames `Fleet` to `Sessions` in `specs/ROADMAP.md` line 62, and files the order family there as
      a candidate: *a family of orders — an attack, a follow — beside `ClickOrder`, which today is the
      only one and is shaped by the click that starts it (filed: 069)*. Being the last task, it runs
      `grep -rin fleet src/ docs/` and does not hand over until it prints nothing: the three tasks
      each retire their own share, and only this one can see whether anything was left.
      *Verified at the console and in Options ▸ keybindings*: `:session rts on` must fail with a usage
      line reading `add|drop|list|anchor|wnd|users`, with no `rts` in it; `:session add USER` must bring
      the mode on unasked, swap the camera and print `session: rts mode on`; `:session drop all` must
      put the mode off, the previous camera back and the selection empty. The binding panel must still
      list **Multi session** with its two keys, `rts-focus` and `rts-next-anchor`, unbound and
      assignable — the mode's keys survive a rename that was never theirs. The task hands over the
      exact console script.
      <!-- extra context: `Control.mode`, `Control.recam` and `prevcam` in `src/io/brodgar/session/Control.java` -->
