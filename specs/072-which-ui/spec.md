# 072 — which UI

## What and why

`AddonManager.ui` and `AddonManager.view` are two package-private statics holding the live `UI` and
the live `MapView`. **94 sites in 36 files read them**, almost all with the same line:

```java
UI u = AddonManager.ui;
```

Every one of those sites means *"the session"* without saying which, and today that is correct
because there is only one bound at a time. The whole remaining sequence — per-session caches, an
engine that stops reloading on a switch, `hafen.session()` — is impossible while the answer is
invisible: `session:world()` cannot be written when "the session" is an ambient field 94 places read
without naming.

**But reading them showed something better than a parameterisation problem.** Those 94 sites are not
94 places needing a session handed to them. They are **three different questions asked of one field**,
and only one of them was ever about *which session*:

| The question | How many | What the site actually wants | Today |
|---|---|---|---|
| *Which monitor guards this widget while I mutate it?* | ~22 `synchronized(u) { …w… }` | the monitor of **`w`'s own `UI`** — `Widget.ui`, which every widget carries | locks the bound session's, whichever that is |
| *Whose widget tree do I search?* | the `u.root` readers | a **named** session — this is the only genuinely session-shaped question | the bound one |
| *Where is the pointer, and what is drawn?* | `u.mc`, the modifier flags, and the 7 `view` reads | **the screen**, of which there is one however many sessions are live | the bound one |

So most of this feature is not threading a context through. It is **stopping 94 sites from asking an
ambient global for something the object in hand already knows** — and the monitor group is a latent
correctness bug, not a style point: locking the anchor's monitor while mutating a widget that belongs
to another session is wrong the moment two sessions exist, and it is wrong quietly.

**Nothing an addon can observe changes.** `conventions.md` promises that every `hafen.*` call, event
handler, timer and draw callback runs on the client's UI thread and that an author never needs a
lock. That promise is untouched — this feature changes which monitor the *engine* takes on the
author's behalf, and the author never sees a monitor.

## Acceptance criteria

1. `AddonManager.ui` and `AddonManager.view` **do not exist**. No ambient field answers "the
   session", so the compiler enumerates every reader rather than leaving one correct by accident.
2. A widget mutation is guarded by the monitor of **that widget's own `UI`**, taken from the widget,
   never from a field describing some other session.
3. Every site that searches a widget tree names **which** session's tree it is searching, in its own
   signature or from the object it was handed.
4. The pointer, the modifier keys and the drawn `MapView` are read through a name that says **the
   screen** — one, however many sessions are live — rather than through the same field that used to
   mean three things.
5. **No `hafen.*` verb changes behaviour**, and no page under `docs/addons/**` needs revising. The
   threading contract an author reads is the same sentence before and after.
6. `docs/client/boot-and-loop.md`'s locking row says **which** `UI`'s monitor serialises a widget
   tree, rather than "the `UI` monitor" as though there were one.

## Out of scope

- **Per-session caches** — the nine subsystems with a `resetSession` (`AddonManager`, `BeltHold`,
  `CharApi`, `Gesture`, `HttpApi`, `Layout`, `LuaWidget`, `StoreApi`, `UiApi`) still hold one
  session's worth of state and still get reset on a switch. This feature makes the question sayable;
  the next one changes the answer.
- **The engine ceasing to reload on a switch**, and `Sessions.tickrebind` with it.
- **`hafen.session()`** and the API cut.
- **Behaviour of any kind.** A change here that alters what the client does is a bug in this feature,
  not a feature of it — the whole value is that it is provably inert.
- The `Fonts` subsystem.

## Docs impact

**Written**: `docs/client/boot-and-loop.md` — its **Threading and locks** table says *"Widget tree +
event dispatch | serialized by the **`UI` monitor** (`synchronized(ui)`)"*, which reads as though the
client had one. It has one per session, and which one is exactly what this feature is about. 109
lines, room under the 150 ceiling.

**Not written**: nothing under `docs/addons/**`. Criterion 5 is that no page there needs revising,
and the impact set below is how that is checked rather than assumed.

**Derived set:**

```
grep -rniE "UI thread|session on screen|drawn session|which session" docs/
```

**31 hits**, each read. What they say and why it survives:

| Page | What it says | Verdict |
|---|---|---|
| `client/boot-and-loop.md` | "serialized by the **`UI` monitor**" | **Revised** — the one claim this feature makes false |
| `client/README.md` | "**One UI thread.** The frame loop is `tick → draw → swap`…" | unchanged — one *thread* is still true; it is the *monitor* that is per-session |
| `addons/api/conventions.md` | every call runs on the UI thread, "You never need locks" | unchanged, and criterion 5 is that it stays exactly this |
| `addons/runtime.md` | "everything your addon does runs on the client's one UI thread" | unchanged |
| `addons/api/{event,http,slash,sound}.md`, `guides/events-and-timers.md` | "on the UI thread" about their own subjects | unchanged, no overlap |
| `client/multi-session.md` | the two UIs and why they are two; the lock direction | unchanged — it already states the rule this feature makes the engine obey |
| `client/{minimap,network,services,state,ui-controls,world-3d}.md` | threads other than the UI one | unchanged, no overlap |
| `addons/api/client/profiling/counters.md` | the `session()` group's prose | unchanged |

## Context files

- `src/io/brodgar/addon/AddonManager.java` — 1, 2, 3
- `src/io/brodgar/addon/LuaWidget.java` — 1
- `src/io/brodgar/addon/UiApi.java` — 1, 2
- `src/io/brodgar/addon/Layout.java` — 1, 2
- `src/io/brodgar/addon/Controls.java` — 1, 2
- `src/io/brodgar/addon/LuaMouse.java` — 3
- `src/io/brodgar/addon/SurfaceInput.java` — 3
- `src/io/brodgar/addon/CameraOptions.java` — 3
- `src/io/brodgar/addon/CameraFacing.java` — 3
- `src/io/brodgar/addon/VrApi.java` — 3
- `src/io/brodgar/session/Sessions.java` — 2, 3
- `src/haven/Widget.java` — 1
- `src/haven/UI.java` — 1, 2, 3
- `src/haven/UILoop.java` — 3
- `docs/client/boot-and-loop.md` — 3
- `docs/addons/api/conventions.md` — 1, 2, 3
- `DOCUMENTATION.md` — 3
