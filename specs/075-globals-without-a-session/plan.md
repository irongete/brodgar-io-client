# 075 — plan

## Approach

Three tasks. The first writes the classification and repairs the three namespaces that only ever
needed to ask a different question; the second and third each fix one defect that a second session
already exposes.

**Nothing here changes a `hafen.*` spelling.** The whole feature is provable by a count that must not
move: `grep -rn "hafen\.[a-z]*()" docs/ | wc -l` is 1070 before and 1070 after.

### The classification is the durable half

`namespaces.md` is what `076`, `077` and `078` read instead of deciding again. It is complete and
checked both ways — thirty-one mount calls, thirty-one pages, nothing on one side missing from the
other — and it applies one question: **what does the thing this namespace names belong to?** The
addon, the client, the world, or one character. Not *what does it read today*, which is "the drawn
session" for all thirty-one and tells you nothing.

### `sound`, `log`, `time` — three reads, three different right answers

`072` left four accessors and they are not interchangeable, which is the point of having four:

- **`sound` → `layer()`.** `LuaSound` reads `host()` → `u.audio` at three sites.
  `Sessions.applymute` mutes every `UI` that is not the anchor, walking `mainui()` and `members`; the
  layer is neither, so it is never muted. `UILoop.mkui` gives it the same shared `Audio.Root` every
  session gets, so nothing is constructed — only the `UI` the clip is handed to changes.
- **`log` → `screen()`, not `layer()`.** `UI.msg` builds a `NoticeEvent` and dispatches it down the
  tree; what *renders* it is the `GameUI`'s notice area, which `LayerRoot` does not have. Posting to
  the layer would put the line where nothing draws it. The drawn session is where the player is
  looking, and `log` already writes `System.out` besides, which is what carries the line on the login
  screen.
- **`time` → any live session.** `WorldApi`'s `glob()` resolves through `host()`; the clock it reads
  is the same number in every session, so the drawn one is an arbitrary choice that answers `nil`
  during a switch for no reason. Take the first live session, and `nil` only when there is none.

And **`slash` is a check, not a change**: `HookApi.slashHandlers` is process-wide (`073`'s census) and
its `host()` read is a best-effort collision test at registration. But every `UI` carries its own
`Console` — `UI.cons = new WidgetConsole()`, and `UILoop.mkui` adds the loop's directory to each — so
confirm a registered command is reachable from every session's console and the layer's. If it is not,
fix it here.

### One map database, one lock

Every step verified:

- `MapFile.load` has **one caller**, `GameUI.java:956`, and does `new MapFile(store, filename)` every
  time. No cache.
- `GameUI.mapfilename()` is `genus`, plus `/` and the pref `mapfile/<chrid>` **only when that pref
  exists**, which by default it does not.
- So two characters on one server name the same directory, which is what two alts are.
- The write lock `MapApi`'s header documents is **per instance**: two instances, two locks, no
  exclusion, concurrent disk I/O on one database.
- Nothing disposes a `MapFile`. `GameUI.mapfile` is the `MapWnd`; the database is `mapfile.file` and
  is abandoned when the session ends.

The fix is to memoize that one caller on the pair `(mapstore, mapfilename())` — the pair and not the
name alone, because `mapstore` comes from `MapFile.mapbase` and including it makes the key correct by
construction rather than by the current configuration. Sessions naming the same pair share; a
different `genus` or a set per-character pref gets its own, which is exactly what that pref asks for.

### An entity stands in the world, not in a session

`073` put `VrApi.anchored` and `VrApi.free` under `SessionState`, reasoning that they are keyed on gob
ids that "mean a different object in the next session" and hold entities "in one session's coordinate
frame". Both halves are wrong: `docs/client/multi-session.md` records gob ids as global — *"one object
observed by two sessions"* — and a free entity holds a `LuaPosition.Anchor`, which is a **grid id and
an offset within it**, the server's own naming.

So the registries come back out of `SessionState` into one set, and an entity is drawn when the drawn
session can resolve its place. The resolution machinery already exists: an `Anchor` resolves against
whichever session's map is asked, answering nothing when that session has not loaded that grid — which
is the same "no shared ground" state `Sessions.findoffgc` reports and the same one a Position already
documents.

`073`'s `census.md` is corrected in place, because a census that argues a verdict this feature reverses
is worse than none.

## Files to create and modify

| File | Task | What |
|---|---|---|
| `specs/075-…/namespaces.md` | 1 (writes), 2–3 (read) | all thirty-one, verdict and evidence |
| `src/io/brodgar/addon/LuaSound.java` | 1 | three `host()` reads become `layer()` |
| `src/io/brodgar/addon/AddonManager.java` | 1, 3 | the `log` post; the vr registries |
| `src/io/brodgar/addon/WorldApi.java` | 1 | `time` off `host()` |
| `src/io/brodgar/addon/HookApi.java` | 1 | the console reachability check |
| `src/haven/MapFile.java` | 2 | the `(store, filename)` memo |
| `src/haven/GameUI.java` | 2 | the one call site, if the memo lands there instead |
| `src/io/brodgar/addon/MapApi.java` | 2 | it reaches the file through `gui()`; one instance now |
| `src/io/brodgar/addon/VrApi.java` | 3 | the registries out of `SessionState` |
| `specs/073-…/census.md` | 3 | the two `VrApi` rows corrected |
| `docs/addons/api/{sound,log,time,slash}.md` | 1 | what they answer |
| `docs/addons/api/map/**` | 2 | one database for the client |
| `docs/addons/api/vr/**` | 3 | an entity stands in the world |

## Risks and gotchas

- **The 1070 count is the feature's proof.** Run it first, run it last. This feature moves no
  namespace, so any change at all is a spelling that should not have been touched.
- **`log` to the layer would be silent.** `LayerRoot` renders no `NoticeEvent`. The temptation is
  symmetry with `sound`; the answer is different because what draws the two is different.
- **A shared `MapFile` is shared by widgets that outlive each other.** `MapWnd` and `MiniMap` both
  hold it, one per session, and a session ending destroys its windows. Nothing may take the database
  down with them — which is already true, and the memo must not make it false.
- **The vr ground pass reads `screenView()`** and stays that way: there is one scene, and drawing is
  the drawn session's. What changes is which entities are considered, not where they are drawn.
- **An entity whose place the drawn session cannot resolve draws nothing and is not an error.** That
  is the boundary — read and order across N, draw the one on screen — and it is the same state a
  Position already documents.
- **`ant hafen-client` is incremental**; `rm -rf build/classes` first.

## Discarded alternatives

- **Leaving `sound` on `host()`** — that is the current bug written down as a design. An addon plays a
  sound because it wants the player to hear it, and `applymute` guarantees they do not whenever the
  addon's session is in the background.
- **Posting `log` to the layer, for symmetry with `sound`** — the layer has no notice area, so the
  line would go where nothing renders it. Symmetry between two namespaces that are drawn by different
  things is not a reason.
- **Giving the layer a notice area so `log` could post there** — a second place the player must learn
  to watch, to solve a problem the drawn session's notice area does not have.
- **Keying the map memo on the filename alone** — `mapstore` is the other half of the identity, and a
  key that is correct only while a configuration variable holds its default is a key that is wrong
  the day someone sets `MapFile.mapbase`.
- **Letting two `MapFile` instances stand and serialising them with a shared lock** — a lock over two
  in-memory views of one database still lets each hold stale segments the other has rewritten. One
  instance is one truth.
- **Leaving `073`'s `VrApi` verdict and treating `vr` as per session** — an entity holds a grid id,
  which is the server's, and gob ids are global. Per session means a thing you stood in the world
  vanishes when you look at it from another character, which is not what standing something in the
  world means.
- **Doing this after the addressing features** — it changes no spelling, so it collides with nothing,
  and it fixes two defects that a second session already exposes. Waiting buys nothing and costs the
  map database.
