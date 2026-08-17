# 072 — plan

## Approach

Three tasks, one per question, and the third proves the other two by deleting the fields: after it,
nothing compiles unless every one of the 94 sites has been answered.

### The field is a duplicate, which is why deleting it is the right move

`AddonManager.ui` is not an independent fact. `Sessions.tickrebind` calls `AddonManager.init(u)` with
`Sessions.anchor()` on every anchor change, so the field is a **second copy of `Sessions.anchor()`**
kept in sync by hand — the same shape `071` deleted from the session layer, one layer up.

`AddonManager.view` is the same against `MapView`: written by `AddonManager.attach(mv)` from the
`MapView` constructor and cleared by `detach`, which is a copy of "the drawn view".

Nothing is invented to replace them. Three names are introduced, each saying what its callers
actually mean, and each derived rather than stored:

| Name | Answers | Takes a session later? |
|---|---|---|
| `AddonManager.screen()` | the drawn `UI` — the pointer, the modifier keys, and any tree walk **driven by the pointer**, because the pointer is over the screen | **Never.** There is one pointer however many sessions are live |
| `AddonManager.screenView()` | the drawn `MapView` | Never |
| `AddonManager.host()` | the `UI` whose widget tree this addon layer works in: where its own windows live, and where a selector searches | **Yes.** This is the one genuinely session-shaped question, and its callers are exactly the sites that will grow an argument |
| *(no name)* | which monitor guards a widget mutation | Never — it is `w.ui`, and the widget has always carried it |

`screen()` and `host()` both answer `Sessions.anchor()` today, and that is not a redundancy to
collapse: the distinction is the deliverable. When sessions become addressable, `host()` grows a
parameter and `screen()` does not, and the sites needing one are already sorted.

### 072.1 — the monitor is the widget's own

~22 sites read the field only to lock it:

```java
UI u = AddonManager.ui;
synchronized(u) { …mutate w… }
```

These become `synchronized(w.ui)`. **This is a correctness fix, not a rename**: locking the drawn
session's monitor while mutating a widget belonging to another session is wrong the moment two
sessions exist, and wrong without a symptom. `Widget.ui` is a public non-final field every widget
carries, assigned by `Widget.attach(UI)` which recurses the subtree.

The null guard those sites already have moves with them: an unattached widget has a null `ui`, which
is the same case the old `if(u == null)` covered, reached from the widget rather than from the field.
Where a site holds a Lua handle rather than a `Widget`, the handle resolves to one first — the
resolution already happens on the line above in every case.

### 072.2 — the tree names whose it is

The `u.root` readers become `host()`. Mechanical, but each is read rather than swept: a walk driven
by the **pointer** is a `screen()` site, not a `host()` one, because what is under the cursor is a
question about the screen. `LuaMouse`'s hit test reads `u.root` and `u.mc` in the same expression and
belongs to `072.3` entirely.

### 072.3 — the screen, and the proof

`u.mc` and `u.modflags()` become `screen()`; the seven `AddonManager.view` reads
(`CameraFacing`, `CameraOptions` ×2, `LuaGob`, `LuaHand`, `SurfaceInput` ×2) become `screenView()`.

Then **both fields are deleted**, along with `AddonManager.attach`/`detach` if nothing else writes
them, and the `MapView` constructor and `dispose` seams that fed them. A clean build is the whole
proof of criteria 1–4: with no field to read, a site that was not converted does not compile.

This task also removes what the deletion exposes in `haven`: `RemoteUI.init`'s guarded
`AddonManager.init(ui)` call. Since `071` made every game session a `Member` — registered before its
`UI` is built, in both `Sessions.add` and the bootstrap handoff — `Sessions.ismember(sess)` is true by
the time `init` runs, so the branch appears never to fire and `Sessions.tickrebind` is the only binder
left. **Confirm before deleting**: log or breakpoint the branch through one login and one
`:session add`, and if it does fire, the comment is what changes instead.

And it writes `docs/client/boot-and-loop.md`'s **Threading and locks** row, which says a widget tree
is *"serialized by the `UI` monitor (`synchronized(ui)`)"* as though the client had one. It has one
per session, and which one is the whole subject here.

## Files to create and modify

| File | Task | What |
|---|---|---|
| `src/io/brodgar/addon/AddonManager.java` | 1, 2, 3 | `screen()`, `screenView()`, `host()`; both fields deleted in 3 |
| `src/io/brodgar/addon/LuaWidget.java` | 1 | 23 sites, almost all the monitor pattern |
| `src/io/brodgar/addon/{UiApi,Layout,Controls}.java` | 1, 2 | monitor sites and tree sites, mixed |
| the remaining 30 files under `src/io/brodgar/addon/` | 1, 2, 3 | one to seven sites each |
| `src/io/brodgar/addon/{LuaMouse,SurfaceInput,CameraOptions,CameraFacing,LuaGob,LuaHand}.java` | 3 | the pointer and the view |
| `src/haven/RemoteUI.java` | 3 | the dead branch, or its comment |
| `src/haven/MapView.java` | 3 | the `attach`/`detach` seams, if they lose their reason |
| `docs/client/boot-and-loop.md` | 3 | which `UI`'s monitor — 109 lines, room under the 150 ceiling |

## Risks and gotchas

- **This feature must change no behaviour at all.** Every site answers the same object as before,
  because `screen()`, `host()` and `w.ui` all resolve to the one bound `UI` while one session is
  drawn. A suite that observes a difference has found a bug in the conversion, not a feature.
- **`Widget.ui` is null before attach.** `Widget.attach(UI)` sets it and recurses the subtree, and
  `add0` only calls it when the child's is null. A monitor site converted without carrying its null
  guard NPEs on a widget built and not yet placed — which `UiApi.dropPending` shows is a real state.
- **Do not convert a `synchronized` to a different object than the code below it touches.** Where one
  block mutates two widgets from different trees, it needs two monitors or one deliberate choice, and
  the lock direction rule (`Sessions.flushsay`'s comment: anchor then member, and never two at once
  on the tick) decides which. This is the one place a mechanical sweep can produce a deadlock.
- **`AddonManager.ui` is read under `synchronized` in some sites and bare in others.** The bare reads
  are the pointer group, where a stale answer is a frame old and harmless; the guarded ones are the
  monitor group. Converting a bare read to `host()` does not need a lock it did not have.
- **`ProfHandle` reads the field four times** and is on the counters path, which several suites now
  assert against. Its conversion must not change what `p:session()` reports.
- **`ant hafen-client` is incremental and hides a symbol that moved**; `rm -rf build/classes` before
  believing any compile here, and especially before believing `072.3`, whose entire claim is that the
  build fails without it.

## Discarded alternatives

- **Threading a session parameter through all 94 signatures** — most of the sites never wanted a
  session. Twenty-two wanted the monitor of the widget they were already holding, and handing them a
  session to look it up in would have kept the ambient question and merely moved where it is asked.
- **Renaming the field to `AddonManager.anchorUi` and stopping** — a clearer name for one ambient
  answer to three different questions still cannot be given an argument by the next feature, and
  still locks the wrong monitor.
- **Keeping the field as a cache and deriving only where it matters** — a field kept in sync by hand
  is what `071` spent three tasks deleting from the layer below. Two copies of "which session is
  drawn" disagree eventually, and the one that disagrees is the one nothing reads on the tick.
- **Collapsing `screen()` and `host()` into one name because they answer the same object today** —
  they answer the same object only while one session is drawn, and separating them is the entire
  deliverable: the next feature needs to know which sites take a session and which never can.
- **Converting the monitor sites to `host()` rather than `w.ui`** — cheaper, and it preserves the
  latent bug exactly: `host()` is the drawn session, and a widget being mutated is not always the
  drawn session's. The widget knows; ask it.
- **Deleting `RemoteUI.init`'s dead branch without confirming it is dead** — a branch that is
  unreachable by inspection and reachable in practice is how a login path loses its engine, and the
  cost of confirming is one log line through two logins.
