# 079 — what the address left behind

## What and why

`070`–`078` moved the addon layer above the sessions and gave it an address. Three things did not
follow, and each was **filed on `ROADMAP.md` by the feature that caused it** instead of being fixed
there. This feature takes those three lines off that list and closes them.

They are not future work. One is a criterion a closed feature claimed and did not deliver; one is
data loss a closed feature created; one is the largest remaining hole in the model.

### A — `s:store()` answers only the session on screen

`078`'s criterion 4 said the per-character scope is reached through its session. The address moved and
the storage did not: the per-character tables are **one set held for the drawn character**, so
`s:store()` answers for that one and raises for every other, while every other section on a Session
answers about the character it names. A `LuaSession` that is right about thirteen namespaces and wrong
about the fourteenth is worse than one that never had the verb.

### B — quitting the client persists nothing

`Client.main`'s `finally` runs `savewndstate()`, `loop.dispose()` and `System.exit(0)`. No path there
fires `Disable` or flushes, and `AddonRegistry`'s `fireTo(a, "Disable")` is reached only by a
teardown. So **the 30-second auto-save is the whole of what an ordinary quit persists**, and an addon
that writes its state from a `Disable` handler alone writes it on `:reload` and never on exit.

**`074` created this.** Before it, `Disable` fired on every character switch and state was flushed
constantly by accident; making it fire once removed the accidental flush without adding a real one.

**And the obvious fix would be worse than the defect.** Firing `Disable` on the way out runs arbitrary
addon Lua during shutdown, so a handler that hangs hangs the client on exit — trading lost data for a
client that will not close. They are two separable things and this feature separates them: **the flush
always happens**, engine-side and bounded, running no addon code; **`Disable` fires too, bounded, and
can never delay or prevent the exit**. The 30-second auto-save stays, because neither helps a crash or
a kill.

### C — the bus broadcasts, and two kinds of event were being treated as one

With several characters in the world an addon receives everything from every session
indistinguishably, because each session runs its own engine step. **The sequence addressed the reads
and left the notifications broadcasting.**

But labelling all of them would be labelling noise rather than removing it. The twenty events that
need work are **two kinds**, and the split is the one `075` drew for namespaces:

**Four are about the world, and must fire once.** `GobAdded`, `GobRemoved`, `GobOverlayAdded`,
`GobOverlayRemoved`. A tree is one tree — gob ids are the server's, and
`docs/client/multi-session.md` records one as *"one object observed by two sessions"*. Five characters
standing together must not produce five `GobAdded` for one object.

```
enters any session   → GobAdded
leaves one, still in another → nothing
leaves the last      → GobRemoved
```

**Sixteen are about a character, and must fire per session, saying which.** The meters, buffs, fep,
study, equipment, action bar, wounds, kin, quests and radial menu of five characters are five
different facts, and five firings are correct — the label is what makes them usable. The session
arrives as the handler's **last** argument, `fn(payload, session)`, because an addon that does not
care which character an event came from is not wrong: it keeps its handler unchanged, and one that
cares adds a parameter.

### What C implies for a Gob

For one event to carry one object, **`LuaGob` stops wrapping a session and starts wrapping the set.**
It wraps *"an id and the session that reads it"* today, and `076` bound it that way because `Gob.rc`
is session-relative — the right fix for a real problem, with the tool that did not exist yet. `075`
brought grid-anchored Positions, and `gob:position()` already returns one: it reads `g.rc` through a
session and hands back a `LuaPosition`, whose anchor is a **server** grid id. So the answer is the
same whichever session computes it.

- keyed on **the id alone**
- `gob:sessions()` — which live sessions hold it, a **live read** and not a snapshot
- `gob:position()` — computed through any session that has it, and the same Position either way

The live read is what makes the middle case need no event at all: an addon that cares who can see a
gob re-reads the set when it cares.

**Every read on a gob survives this, and one write does not.** `name`, `health`, `position`,
`overlay` and the rest answer about the **server's** object, so whichever session computes them the
answer is the same. But `gob:click()` is protected (`Permission.GOB_CLICK`) and sends the `MapView`
click a **character** makes — and a gob keyed on the id alone has no session to send it through. So
the click leaves the gob and joins the session: **`s:world():click(gob, button, mods)`**, which reads
as what it is and is the shape `session:move(p)` already has, where the session acts and the world is
the target.

## Acceptance criteria

1. `s:store()` answers **for the session it was reached through**, including one not drawn: two
   characters read and write their own per-character tables, and neither sees the other's keys.
2. Quitting the client **flushes every session's saved variables before the process ends**, and a value
   written and not auto-saved survives an ordinary quit. `Disable` fires on the way out as well, but
   **bounded**: an addon whose handler hangs or throws delays nothing and prevents nothing, and the
   flush happens either way.
3. `gob:sessions()` reads which live sessions hold a gob, and `LuaGob` is interned on the id alone.
   Every read on a gob answers the same whichever session holds it; `gob:click()` is **retired** into
   `s:world():click(gob, button, mods)`, naming its replacement, because a click is a character's.
4. The four world events fire **once**: on entering the first session and on leaving the last, with
   nothing in between.
5. The sixteen character events carry their session as the handler's last argument, and a handler
   that ignores it behaves exactly as it does today.
6. The seven that need no session **do not grow one** — `Load`, `Update`, `Disable`, `MarkersChanged`
   (`map` is global since `075.2`) and the three `vr` click events (`vr` is global since `075.3`) —
   and the four that already carry one are unchanged.
7. `ROADMAP.md` loses exactly the three lines this feature closes, and **no others**.
8. The guardrail holds: `grep -rn "hafen\.\(timer\|event\|slash\|asset\|json\|http\|font\|client\)()" docs/`
   is **212 before and 212 after**.

## Out of scope

- The **`Fonts`** subsystem — unrelated to sessions, and expensive only if upstream is merged again.
- **The other 21 `ROADMAP.md` lines** the sequence filed. Legitimate future work; this feature takes
  three and leaves the rest untouched.

## Docs impact

**Written**: `api/event/bus.md` (the two kinds, the payload column, and the rule that decides which
events carry a session) · `api/gob.md` (`:sessions()`, and that a Gob is one object) ·
`api/store.md` and `guides/saved-data.md` · `runtime.md` (what an ordinary quit persists) ·
`guides/events-and-timers.md` and `api/session.md` where their examples subscribe.

**Derived set:**

```
grep -rn "hafen\.event():on(" docs/
```

Every subscription example in the tree — each is a handler signature this feature may change, so each
is read rather than sampled. Run and pasted by `079.3` before it revises anything.

**Guardrail:**

```
grep -rn "hafen\.\(timer\|event\|slash\|asset\|json\|http\|font\|client\)()" docs/
```

**212, and it must still be 212.** `hafen.event()` is global and stays global; this feature changes
what a handler *receives*, never how the bus is reached.

## Context files

- `specs/075-globals-without-a-session/namespaces.md` — 1, 2, 3 (read: the classification)
- `src/io/brodgar/addon/StoreApi.java` — 1, 2
- `src/io/brodgar/addon/LuaSession.java` — 1
- `src/io/brodgar/addon/Manifest.java` — 1 (`SavedVar.account` splits the halves)
- `src/io/brodgar/addon/Addon.java` — 2 (`store` is the ACCOUNT tables and
  `lastAccountJson` their write-skip cache; the per-character tables are
  `AddonManager.SessionState.charStores`)
- `src/io/brodgar/addon/AddonRegistry.java` — 2 (the only `fireTo(a, "Disable")`)
- `src/io/brodgar/addon/AddonManager.java` — 1, 2, 3 (`fire`, `fireSession`, `BUS_KEYS`, the gob queue)
- `src/haven/Client.java` — 2 (`main`'s `finally`, `dispose()`, `System.exit(0)`)
- `src/haven/UILoop.java` — 2
- `src/io/brodgar/addon/LuaGob.java` — 3 (it wraps a session today; it must wrap the set)
- `src/io/brodgar/addon/LuaPosition.java` — 3 (the grid anchor that makes one answer serve)
- `src/io/brodgar/addon/CharApi.java` — 3 (the nine adapters that fire the character events)
- `src/io/brodgar/addon/FlowerMenuApi.java` — 3
- `src/io/brodgar/addon/LuaEvent.java` — 3
- `src/io/brodgar/addon/WorldApi.java` — 3 (`s:world():gob()` returns the same interned handle)
- `src/io/brodgar/session/Sessions.java` — 2, 3
- `specs/ROADMAP.md` — 1, 2, 3 (each task removes its own line)
- `docs/addons/api/event/bus.md` — 4
- `docs/addons/api/gob.md`, `api/world.md`, `api/position.md` — 3 (`world.md` hit the 300-line
  ceiling, so the Position type split off into its own sibling type page)
- `docs/addons/api/store.md`, `guides/saved-data.md` — 1, 2
- `docs/addons/runtime.md` — 2
- `DOCUMENTATION.md` — 1, 2, 3
