# 081 — Session Manager: tasks

- [x] **081.1 — The screen is a value an addon can write.** `hafen.session():current(s)` gains its
      one-argument half in `SessionApi.installSession`: resolve with `LuaSession.resolve`, find the
      member with `Sessions.byuser`, hand it to `Control.take` so the selection and camera follow the
      screen. The bare `:current()` read is untouched; today's *"takes no arguments"* refusal is
      **retired** and its text goes with it. `docs/addons/api/session.md` gets a
      `## Write (unprotected)` group and loses the "reads and never writes" paragraph, saying instead
      why the screen needs no permission; `docs/addons/api/conventions.md` gains the addon as a second
      writer of the screen.
      *Its suite* asserts with two accounts up: `:current(other)` then `:current():user() == other`,
      and a `SessionSelected` subscription that recorded that same `Session`; writing the session
      already on screen leaves `:current()` where it was and fires nothing. Three refusals, each
      `pcall`ed and asserted to name its fault — a string argument, an explicit `nil`, and
      `hafen.session():get("nobody-is-here")`, whose message must say the client holds no such session.
      `[manual]`: log in a second account with `:session add` before running; report whether the screen
      visibly moved to the other character and back.

- [ ] **081.2 — A session can be closed from an addon.** `s:close()` on `LuaSession`: the
      `session.close` gate first, then `Sessions.byuser(user)` and `Member.drop()`. A session that no
      longer exists is refused by name. `Permission.SESSION_CLOSE` carries the consent line *"log out
      any of your characters"*. `docs/addons/api/session.md` gets the verb under a
      `## Write (protected)` group, saying it is asynchronous and that `s:exists()` and
      `SessionDestroyed` are what answer; `docs/addons/guides/permissions.md` gains the row and the
      `session.*` group.
      *Its suite* declares `session.close`, closes a session that is **not** on screen, and polls
      `s:exists()` on a timer for a bounded window, scoring pass when it turns false and reporting the
      window it reached otherwise; a `SessionDestroyed` subscription must have been handed that same
      `Session`, and `s:user()` must still answer afterwards. Then `pcall(s.close, s)` on that dead
      session and assert the refusal names it.
      `[manual]`: two accounts up first; report that the alt actually left `:session list`. Then
      delete `"session.close"` from this suite's own `manifest.json`, `:reload`, re-run, and report
      the first refusal — an addon that declared the key cannot reach the ungranted branch from
      inside itself, and the family has no second key to leave undeclared.

- [ ] **081.3 — The client's own Sessions window is retired.** Delete
      `src/io/brodgar/session/SessionWnd.java` whole, the `SessionWnd.tick()` call in
      `Sessions.tick()`, and the `wnd` branch of the `:session` console command in `Client.java`.
      `docs/client/multi-session.md` loses the `:session wnd` row, and the switcher stops being one of
      the spellings named in the `:session anchor` row and in the RTS Alt-click row — three spellings
      now, all of them inputs. Build clean (`rm -rf build/classes`) so no stale class hides the
      deletion.
      *Its suite* asserts, for **every** session in `hafen.session():list()`, that
      `s:ui():find("window[title=Sessions]")` is `nil` — the window lived on the drawn session's HUD
      and was rebuilt on each switch, so checking one session is not checking the client. It then
      writes the screen with `hafen.session():current(s)` across every session and re-asserts, which
      is the rebuild path the class used.
      `[manual]`: run `:session wnd` and report what the console says — it must be the unknown-
      subcommand line, not a window.

- [ ] **081.4 — Session Manager.** The addon at `addons/session-manager/`, copied to
      `bin/addons/session-manager/`: a layer window with one row per live session — the character's
      name where there is one, the account before that, the row for the session on screen marked — a
      button per row that writes `hafen.session():current(s)`, an `X` per row calling `s:close()`
      behind a declared `session.close`, and a `next` hotkey registered unbound that cycles forward
      through `hafen.session():list()` and round. The four session events rebuild the rows; the
      window's place lives in `hafen.store()`. Its `README.md` names the suggested key rather than
      claiming one.
      *Its suite* drives the same API the addon does, since no search verb reaches the layer: the
      cycle visits every session exactly once a lap and returns to where it started, and a row label
      falls back to `s:user()` when `s:character()` is `nil`.
      `[manual]`: assign the hotkey in Options ▸ Keybindings ▸ session-manager; report that the window
      shows every character, that a button and the key both move the screen, that the window does
      **not** flicker on a switch, that `X` logs that character out, and that its position survives a
      client restart.
