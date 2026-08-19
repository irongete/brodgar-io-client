# 081 — Session Manager

## What & why

The client's own **Sessions** window is a `Window` built in Java, on the drawn session's HUD, rebuilt
from `Sessions.tick()` on every anchor switch. It is one of the four spellings of "go to that
character", and the only one with a surface. It should not be Java at all: it is a list of buttons over
a collection an addon can already read, and the one reason it cannot be written today is that **the
screen is not writable from Lua and a session cannot be ended from Lua**.

So this feature makes those two writes exist, retires the Java window, and ships **Session Manager**,
an addon that does what it did and the two things it never did — a key that cycles, and a way to close
a session without typing `:session drop`.

Retiring it also settles the blink: an addon's window stands in the **layer**, which is above every
session and is never rebuilt when the anchor moves.

## Acceptance criteria

Each is asserted by the suite of the task that ships it, with **two accounts logged in**.

1. `hafen.session():current(s)` hands the screen to `s`; `:current()` reads back `s` afterwards, and a
   `SessionSelected` handler was called with `s`. Naming the session **already** on screen is a no-op
   that fires nothing.
2. `:current(s)` refuses, each naming what is wrong: a non-Session argument, an explicit `nil`, and a
   `Session` whose account the client does not hold.
3. `s:close()` ends that session — `s:exists()` reads `false` within a bounded window and a
   `SessionDestroyed` handler was called with `s`. It works on a session that is **not** on screen and
   on the one that is.
4. `s:close()` refuses without the `session.close` permission, naming the key, and refuses on a session
   that has already ended.
5. With two sessions up, `s:ui():find("window[title=Sessions]")` reads `nil` in every session: the Java
   window is gone, and so is `:session wnd`.
6. Session Manager shows one row per live session — the character's name, or the account before it has
   one — with a button that goes to it and an `X` that closes it, a registered hotkey that cycles to the
   next session, and it survives an anchor switch without being rebuilt. Its source is
   `addons/session-manager/`, copied to `bin/addons/session-manager/` to be run — the way `clickpath`
   already lives in both. It is a **tool**, not a suite: never archived, never deleted.

## Out of scope

- **Adding** a session. `:session add` stays the only door; this feature manages the sessions that
  exist. The other half would be a login surface in the layer, and it needs a token vocabulary this
  API has none of.
- `rts-next-anchor` and the Alt-click gesture stay in Java: they are the RTS mode's own inputs, live in
  `MapView`, and are not a window.
- No permission gate on taking the screen — see `plan.md`.

## Docs impact

Pages written: `docs/addons/api/session.md` (the write group and the account line),
`docs/addons/guides/permissions.md` (`session.close` and the `session.*` group),
`docs/client/multi-session.md` (the switcher rows go).

Derived impact set —
`grep -rn "reads and never writes\|gesture of the player\|switcher\|:session wnd\|tabs between" docs/`:

- `docs/addons/api/session.md:129-130` — "`:current()` reads and never writes … the switcher window, an
  Alt-click, `:session anchor`". Both halves are retired by 081.1.
- `docs/addons/api/session.md:22`, `docs/addons/api/conventions.md:117` — "changes whenever the player
  tabs between them": now also when an addon writes it.
- `docs/client/multi-session.md:16` — "four spellings", one of which is "the switcher window's buttons";
  `:17` — the whole `:session wnd` row; `:46` — "the switcher window's gesture said somewhere else".
- `docs/addons/api/event/bus.md:76` — "tabbing to the session already drawn fires nothing at all" stays
  true and now also covers the addon write.

## Context files

- `docs/addons/api/session.md` — 1, 2, 4
- `docs/addons/api/conventions.md` — 1, 2
- `docs/addons/api/event/bus.md` — 1, 2, 4
- `docs/addons/api/event/README.md` — 2, 4 (`:on`/`sub:off`: every suite here subscribes)
- `docs/addons/api/timer.md` — 2, 4 (a session event lands a tick later, so a suite polls for it)
- `docs/addons/api/log.md` — 2, 4 (the in-game half of a line goes to the character on screen, and
  these tasks move it: a suite that prints as it runs splits its own verdict block in two)
- `docs/addons/guides/permissions.md` — 2
- `docs/addons/runtime.md` — 2, 4
- `docs/client/multi-session.md` — 1, 2, 3
- `DOCUMENTATION.md` — 1, 2, 3
- `src/io/brodgar/addon/SessionApi.java` — 1
- `src/io/brodgar/addon/LuaSession.java` — 1, 2
- `src/io/brodgar/addon/Args.java` — 1, 2
- `src/io/brodgar/addon/Permission.java` — 2
- `src/io/brodgar/addon/LuaSpeed.java` — 2 (a protected write end to end: the gate as the verb's
  first statement, and the collection handed back so writes chain)
- `src/io/brodgar/addon/AddonManager.java` — 1, 2
- `src/io/brodgar/session/Sessions.java` — 1, 2, 3
- `src/io/brodgar/session/Control.java` — 1
- `src/io/brodgar/session/SessionWnd.java` — 3
- `src/haven/Client.java` — 3 (the `:session` console block alone)
- `docs/addons/api/ui/custom.md` — 4
- `docs/addons/api/ui/widget.md` — 4
- `docs/addons/api/ui/controls/interactive.md` — 4
- `docs/addons/api/client/keybindings.md` — 4
- `docs/addons/api/store.md` — 4
- `addons/widgetstack/` — 4 (the shape of an addon that builds and toggles a window)
