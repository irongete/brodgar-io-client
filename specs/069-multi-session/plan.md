# 069 — Plan

## Approach

Three tasks, each of which compiles on its own and each of which carries the slice of
`docs/client/multi-session.md` that sits over the code it moved.

**1 — the layer and its door.** The five files under `src/io/brodgar/rts/` move to
`src/io/brodgar/session/`; `Fleet` becomes `Sessions`, `Fleet.Member` becomes `Sessions.Member`, and
`FleetWnd` becomes `SessionWnd` captioned `Sessions`. `Control`, `Recall` and `Greyscale` keep their
names and change only their `package` line — `rts` names the mode, and the mode stays.

Two nested names follow from the collection's: `Fleet.Sess` becomes `Sessions.Placed` — what it adds
over a bare session is the offset into the anchor's frame and `charpos()` in it — and `Fleet.sessions()`
becomes `Sessions.placed()`. `Fleet.isfleet(Session)` becomes `Sessions.ismember(Session)`.

The console door is `cmdmap.put("session", …)` in `Client`, with every usage string, the
`not in the fleet: %s` refusal (now `no such session: %s`) and the `rts-fleet-connect` thread name
following. Every `io.brodgar.rts.Fleet.` in `haven` becomes `io.brodgar.session.Sessions.`.

**One rule for the runtime strings**, because two words survive in one system: a message about the
sessions is prefixed `session:` (`Sessions.say`, the tick's `Warning`), and `rts` is kept only where
it means the mode — the mode's own notice, the camera name, the two keybinding ids.

**2 — `MapView`'s own names.** `FleetView`, `FleetTerrain`, `FleetGobs` and `FleetClickMap` become
`SessionView`, `SessionTerrain`, `SessionGobs` and `SessionClickMap`; `fleettick`/`fleettick2`,
`fleetarea`, `fleetviews` and `lastfleet` take the same word. `FleetClick` becomes **`ClickOrder`**,
which is the head of a family and not a description of a pick pass. `UILoop`'s CPU phase
`"fleet"` becomes `"sessions"`. The `name.equals("fleet")` branch in `MapView.setcam` is deleted
whole, so a retired camera name gets the same generic answer as any other unknown one.

**3 — the mode follows the sessions.** `Sessions.tickmode()`, called from `Sessions.tick()`, compares
`members.isEmpty()` against a static `boolean modeon` and calls `Control.mode(v)` on the edge alone.
The `rts` subcommand and its usage word leave the console; `Control.mode` loses its `public`, having
one caller left, in its own package. The same task updates `specs/ROADMAP.md` line 62 to name
`Sessions`, and files the order family as a candidate.

## Files to create/modify

| File | What |
|---|---|
| `src/io/brodgar/rts/*.java` → `src/io/brodgar/session/` | five files move; `Fleet.java` → `Sessions.java`, `FleetWnd.java` → `SessionWnd.java` (1) |
| `src/haven/Client.java` | the `:session` door, its usage strings and its connect thread (1); the `rts` subcommand deleted (3) |
| `src/haven/UILoop.java` | call sites (1); the `"sessions"` CPU phase (2) |
| `src/haven/GameUI.java`, `MCache.java`, `RemoteUI.java`, `res/lib/svaj/GobSvaj.java` | call sites and one comment each (1) |
| `src/haven/MapView.java` | call sites (1); the nine inner names, `ClickOrder`, and `setcam`'s deleted branch (2) |
| `docs/client/multi-session.md` | the `:session` table and the class names (1); the merged-scene names (2); the mode section (3) |
| `docs/client/world-3d.md` | `MapView.FleetTerrain` → `SessionTerrain`, twice on line 22 (2) |
| `docs/addons/api/client/profiling/README.md` | its phase list gains `sessions`, which it has always omitted (2) |
| `specs/ROADMAP.md` | line 62 renamed; the order family filed (3) |
| `docs/client/services.md` | the console’s command lookup, which no page covered (1) |

## Risks & gotchas

- **`Sessions.tick()` returns early on `members.isEmpty()`**, after `SessionWnd.tick()`. `tickmode()`
  must sit **above** that return: dropping the last member is exactly the frame on which `members` is
  empty and the mode still has to be turned off.
- **`Control.mode(false)` is UI work** — it restores `prevcam` for every `MapView` it recorded, clears
  `sel`, releases the `UI.Grab` and resets `dragfrom`/`dragto`. That is why the flip is derived on the
  tick and never in `add()`, which runs on the console's `HackThread`.
- **`Control.recam()` needs the anchor's `MapView`.** On a frame where `Sessions.mapview()` answers
  null the flip must be left pending, not swallowed, or the mode is on under the wrong camera.
- **`Console.findcmds` merges three tiers, last wins**: static (`setscmd`), per-instance (`setcmd`),
  then every registered `Directory`. `Client` is a `Directory` and its `cmdmap` is the door being
  renamed, so `:session` sits in the tier that shadows the other two — a stale `:fleet` left anywhere
  below would never have shown itself. `Console.run` is what answers `"<name>: no such command"`.
- **`haven/res/lib/svaj/GobSvaj.java` is a version-pinned `@FromResource` copy.** Change the word in
  its comment and nothing else; a stray edit there restores a foliage bug on the next server bump.
- **`rm -rf build/classes` before the compile check.** A package that moved is precisely what an
  incremental `ant` build reports green on.
- **`docs/client/multi-session.md` is 135 lines against a 150 ceiling.** The rewrite corrects in
  place and does not grow it.

## Discarded alternatives

- **`io.brodgar.multisession.MultiSession`** — the grammar says a set is a collection, and
  `MultiSession.add` names the system where `Sessions.add` names the thing being added.
- **Keeping the package `io.brodgar.rts`** — the multi-session machinery is the bulk of it and the
  mode is the tenant, so the package would go on naming the smaller half.
- **`MapView.OrderClick`** — an order with no ground to resolve, given a target directly, could never
  join a family whose head noun is the gesture that happened to start the first one.
- **Keeping `Sessions.Sess` and `Sessions.sessions()`** — a collection that stutters at every call
  site reads as two different things, and the reader has to check which.
- **A `:session rts [on|off]` kept beside the derived flip** — two authorities over one boolean, and
  the one the user typed is the one that goes stale the moment a session joins.
- **A standalone `:rts [on|off]`** — the mode is only ever wanted when there is something to command,
  so a switch for it is a question with one answer.
- **Leaving the camera manual while the mode is derived** — playing a character and commanding a group
  want different cameras, and separating them makes the user perform half of one decision by hand.
- **Keeping the `:cam fleet` refusal that names `rts`** — it is the last sentence in the client that
  would still teach the retired word to somebody who never knew it.
- **A fourth, docs-only task** — a page section belongs to the task that changed the code underneath it.
- **A per-task addon suite** — the standing rule everywhere else, and wrong here: a session that is not
  the anchor never takes the addon engine, so an addon cannot observe one at all. Every claim this
  feature makes is a console answer, a window caption or a profile row, and the verification is the
  console script each task hands over. An addon here would assert around the subject, not on it.
