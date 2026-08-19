# 081 — Session Manager: plan

## Approach

Two writes, one deletion, one addon, in that order: the API lands first so the addon has something to
call, and the Java window goes between them so nothing draws two switchers at once.

### The screen becomes writable — `hafen.session():current(s)`

`SessionApi.installSession` mounts `current` as a `VarArgFunction` whose whole body is the refusal
*"takes no arguments: it READS which session is on screen"*. That refusal is **hard-cut**: the argument
branch becomes the write. Arity is the verb — `keybindings:key(name)` / `key(name, k)` said about the
screen — and it sits on the **collection**, because there is one screen however many logins there are.

The write resolves the argument with `LuaSession.resolve`, turns the account into a member with
`Sessions.byuser`, and calls **`Control.take(m)`** — never `Sessions.anchor` — so the screen, the RTS
selection and the camera move together, exactly as `:session anchor`, the Alt-click and
`rts-next-anchor` all do. `Sessions.anchor` already returns early when the target is the session
already drawn, so naming it is a no-op that fires no `SessionSelected`, which is what `bus.md` already
promises about tabbing.

Three refusals, each naming its fault: a value `LuaSession.resolve` does not recognise, an explicit
`nil` (`Args.passed` tells one from a missing argument), and a `Session` whose account
`Sessions.byuser` does not hold — reachable by construction, since `:get` mints one for an account
nobody is logged in as.

### A session can be ended — `s:close()`

A colon verb on `LuaSession`, beside `:exists()`. `Sessions.byuser(user)` then `Member.drop()`, which
closes the `Session` so `RemoteUI.run` unwinds through its own cleanup rather than being torn out. It
is **asynchronous**: `drop()` returns before the member leaves the list, so the verb promises nothing
about the same tick and the page says so — `s:exists()` is the read that answers, and
`SessionDestroyed` is the edge.

Gated on a new `Permission.SESSION_CLOSE` (`session.close`, *"log out any of your characters"*), the
gate the **first** statement of the verb. One catalogue entry plus one gate call is the whole of it.
Closing the session **on screen** is allowed: `Sessions.relinquish`/`reclaim` already hand the screen
to another live session, or to the login screen when there is none left.

`current(s)` stays **unprotected**. Protection is *an action the player could have performed* whose
effect leaves the client; taking the screen changes which widget tree is drawn and nothing else — the
server is never told. The page says that in a line rather than leaving it to be inferred.

### The Java window goes

`src/io/brodgar/session/SessionWnd.java` is deleted whole, with its three call sites: the
`SessionWnd.tick()` line in `Sessions.tick()`, the `wnd` branch of the `:session` console command in
`Client.java`, and `SessionWnd.reopen()` with it. Nothing else references the class.

### Session Manager

An ordinary addon: `hafen.ui():window()` for the frame, `hafen.ui():button()` per row, the four
session events to rebuild the rows, `keybindings:register("next", …)` for the cycle, `hafen.store()`
for the window's place.

Two things the Java window could not do. Its window is in the **layer**, so an anchor switch does not
touch it — the blink gone rather than throttled. And its place lives in the **account's** file rather
than through `w:remember`, which files under the character on screen and would put a switcher's
position in one character's folder.

## Files to create/modify

| File | What |
|---|---|
| `src/io/brodgar/addon/SessionApi.java` | `current(s)` — the write, and the three refusals |
| `src/io/brodgar/addon/LuaSession.java` | `:close()` |
| `src/io/brodgar/addon/Permission.java` | `SESSION_CLOSE` |
| `src/io/brodgar/session/SessionWnd.java` | **deleted** |
| `src/io/brodgar/session/Sessions.java` | the `SessionWnd.tick()` call goes |
| `src/haven/Client.java` | the `wnd` branch of `:session` goes |
| `docs/addons/api/session.md` | a `## Write` group; the "reads and never writes" paragraph is rewritten |
| `docs/addons/guides/permissions.md` | `session.close` row, `session.*` group |
| `docs/client/multi-session.md` | the `:session wnd` row and the three switcher mentions go |
| `docs/addons/api/conventions.md` | `:117` — the screen also changes when an addon writes it |
| `addons/session-manager/` | the addon: `manifest.json`, its Lua, a `README.md` naming the suggested key |
| `bin/addons/session-manager/` | the copy the client runs; gitignored |
| `addons/081-session-manager.1/` … `.4/` | one suite per task |

## Risks & gotchas

- **`Control.take` throws on `null`** — *"the login screen is not one"*. The verb's own refusal comes
  first, so an author never sees that message.
- **`Sessions.anchor` is `synchronized` and touches two UIs**, and the console already nests anchor
  then member. A Lua verb runs on the UI thread inside the drawn UI's monitor, which is the same
  direction the console takes; `multi-session.md` says that is the only one anything may take.
- **`Member.drop()` is not the end of the member.** The list, `s:exists()` and `SessionDestroyed` all
  move on the session's own thread a tick or more later. A suite that asserts immediately fails.
- **Ending the drawn session fires two events in order** — that session's `SessionDestroyed`, then a
  `SessionSelected` for whoever takes over, and nothing at all if the client is left on the login
  screen. Already stated in `bus.md`; the suite has to expect it.
- **`s:ui():find` never reaches the layer**, so no suite can see Session Manager's own window. 081.4's
  visible half is `[manual]` by construction, and its automated half drives the same API the addon does.
- **`session.close` is alone in its family**, so a suite cannot do what 077.2 did — declare some keys
  and prove the refusal with the others. An addon that declared the key cannot reach the ungranted
  branch from inside itself, so 081.2 automates the grant and asks for the ungranted message by hand.
- `docs/client/multi-session.md` sits **exactly at** its 150-line ceiling. 081.3 only removes from it.

## Discarded alternatives

- **`s:select()` as a colon verb on the Session** — splits "which session is on screen" into a read on
  the collection and a write on a member, which is the `get`/`set` pair the grammar refuses everywhere
  else. The screen is one thing, so its verb belongs to the one collection, not to each member.
- **A `session.select` permission** — a consent line for something the server is never told. The tier
  is for actions that leave the client; gating a redraw would make the word mean nothing.
- **Keeping `SessionWnd` and restyling or replacing it from the addon** — two switchers argue over one
  screen, and the Java one is still rebuilt on every anchor switch: the blink is the class existing,
  not how it is dressed.
- **Fixing the blink in `SessionWnd.tick` instead** (drop the anchor mark from the signature) — buys a
  smoother version of a window a Lua addon can now build, and leaves a UI in Java that the API is
  meant to be able to express.
- **`w:remember` for the window's place** — files under the character on screen, so a switcher's
  position would live in one character's folder and be read back out of another's.
- **`s:close()` unprotected, by analogy with `:session drop`** — a console command is the user's own
  hand; an addon logging a character out with no consent line is the thing the tier exists for.
- **A `:session add` counterpart here** — needs a saved-token vocabulary the API has none of, and would
  put the boundary at the login flow rather than at the switcher.
