# 075 — tasks

Three tasks. `075.1` writes the classification every later feature reads and repairs three namespaces
that only needed to ask a different question; `075.2` and `075.3` each end one defect that a second
session already exposes.

Read `spec.md`, `plan.md` and `namespaces.md` first. Every file a task may open is in `spec.md` under
**Context files**, tagged with the task that needs it.

**No `hafen.*` spelling changes anywhere in this feature**, and that is its proof. Every task runs
`grep -rn "hafen\.[a-z]*()" docs/ | wc -l` before and after: **1070**, unchanged. A different number
means a namespace was touched that this feature does not move.

---

- [x] **075.1 — Thirteen namespaces were never a character's, and three stop pretending.**
      Writes `namespaces.md` in this folder: all thirty-one `hafen.*` namespaces — checked both ways
      against the `Section.install`/`mount` calls and the pages under `docs/addons/api/`, neither side
      missing a name — each with the verdict **global**, **per session** or **split**, the evidence
      that decided it, and which later feature moves it. The question it applies is *what does the
      thing this namespace names belong to* — the addon, the client, the world, or one character —
      **not** what it reads today, which is the drawn session for all thirty-one.
      Then three repairs, each a different right answer from the four accessors `072` left.
      **`sound`**: `LuaSound` reads `AddonManager.host()` → `u.audio` at three sites, and
      `Sessions.applymute` mutes every `UI` that is not the anchor — so **an addon's sound played
      while its session is in the background is silent today**. It plays through `layer()`, which
      `applymute` walks past because the layer is neither `mainui()` nor a member, and which
      `UILoop.mkui` already handed the same shared `Audio.Root`. **`log`**: posts through `screen()`
      and **not** `layer()` — `UI.msg` dispatches a `NoticeEvent` and what renders it is the
      `GameUI`'s notice area, which `LayerRoot` has not got, so a line sent to the layer would go
      where nothing draws it; the `System.out` half still carries it with no session up. **`time`**:
      `WorldApi`'s `glob()` resolves through `host()`, and every session answers the same clock, so it
      takes any live session and `nil` only when there is none. And **verify `slash`**: every `UI`
      carries its own `Console`, so confirm a command an addon registers is reachable from every
      session's console and the layer's — if it is not, that is this task's bug to fix. Revises
      `api/sound.md`, `api/log.md`, `api/time.md`, `api/slash.md`: what each answers, not how it is
      spelled.
      *Its suite* plays a sound and asserts `hafen.sound()` reports it playing; reads
      `hafen.time():clock()` and asserts a number; writes through `hafen.log()` and asserts the call
      returns rather than throwing with no world up. It `pcall`s `hafen.time():clock(1)` and asserts
      the refusal names the verb as a read.
      `[manual]`: with two sessions up, **tab to the other one** and re-run. Expect: the sound is
      **heard**. Before this task it is silent, which is the defect.
      `[manual]`: type the suite's own `:` command in the second session's chat. Expect: it runs.

- [x] **075.2 — One map database, one lock.**
      `MapFile.load` has exactly one caller in the tree — `GameUI.java:956` — and does
      `new MapFile(store, filename)` every time, with no cache. `GameUI.mapfilename()` is the `genus`
      plus `/` and the pref `mapfile/<chrid>` **only when that pref exists**, which by default it does
      not — so **two characters on one server name the same directory**, which is what two alts are,
      and the write lock `MapApi` documents is per instance: two instances, two locks, concurrent disk
      I/O on one database. Memoize on the pair **`(mapstore, mapfilename())`** — the pair, not the
      name alone, because `mapstore` comes from `MapFile.mapbase` and a key correct only while a
      config variable holds its default is wrong the day someone sets it. Sessions naming the same
      pair share one instance; a different `genus`, or a per-character pref deliberately set, gets its
      own. **Nothing disposes a `MapFile` today** — `GameUI.mapfile` is the `MapWnd`, and the database
      behind it is abandoned when the session ends — so this breaks no lifecycle, and must not
      introduce one: a session ending destroys its windows and must leave the database standing for
      the others. Revises `api/map/**`: one recorded map for the client, not one per character.
      *Its suite* reads `hafen.map():marker():list()` and asserts a list; adds a marker of its own,
      asserts `MarkersChanged` fires and that `:get` finds it, then removes it and asserts it is gone.
      It `pcall`s `hafen.map():marker():add()` with a malformed place and asserts the refusal names
      the Position type.
      `[manual]`: with two sessions up, add a marker from the suite, then **tab to the other session
      and open its map**. Expect: the marker is there. Before this task each session had its own
      database object over the same files.
      `[manual]`: `:session drop` one of them and re-run. Expect: markers still read — the database
      outlived the window that showed it.

- [x] **075.3 — A thing you stand in the world belongs to the world.**
      `073` put `VrApi.anchored` and `VrApi.free` under `SessionState`, reasoning that they are keyed
      on gob ids that "mean a different object in the next session" and hold entities "in one
      session's coordinate frame". **Both halves are wrong**: `docs/client/multi-session.md` records
      gob ids as global — *"one object observed by two sessions"* — and a free entity holds a
      `LuaPosition.Anchor`, which is a **grid id and an offset within it**, the server's own naming.
      The registries come back out of `SessionState` into one set, and an entity is drawn when the
      drawn session can resolve its place — the `Anchor` already resolves against whichever session's
      map is asked and answers nothing when that session has not loaded that grid, which is the same
      state a Position documents. The ground pass goes on reading `screenView()`: there is one scene,
      and what changes is which entities are considered, not where they are drawn. Corrects the two
      `VrApi` rows in `specs/073-caches-know-their-session/census.md` in place — a census that argues
      a verdict this task reverses is worse than none. Revises `api/vr/**`: an entity stands in the
      world, and the boundary in one line — **read and order across N sessions, draw the one on
      screen**.
      *Its suite* stands a free `hafen.vr()` entity at a Position, asserts `:exists()` and that its
      place reads back what it was given, then ends it and asserts it does not exist. It stands one
      anchored to a gob and asserts it reports that gob. It `pcall`s `hafen.vr():ghost()` with a
      malformed place and asserts the refusal names the Position type.
      `[manual]`: stand an entity where you are, then `:session add` a second account **standing
      beside you** and tab to it. Expect: the entity is **still drawn** — it is in the world, and the
      second character can see the same patch of world. Before this task it belonged to whoever stood
      it and vanished on the tab.
      `[manual]`: tab to a session standing far away. Expect: nothing is drawn, and nothing errors —
      that session cannot see that place, which is the boundary and not a fault.
