# 079 — plan

## Approach

Four tasks. The first two close one defect each; the last two are the bus, split so that the risky
half is done **alone**.

The order is forced twice. **A before B**, because the quit flush must write every session's saved
variables and there is no point writing it against a storage shape that is about to change. **`079.3`
before `079.4`**, because re-keying `LuaGob` is a change the compiler cannot check, and doing it in
the same task as the event rework would mix a silent risk with a loud one.

### A — the storage follows the address

`078` moved `store` onto the session and left the per-character tables as one set held for the drawn
character. `073` already put per-session engine state behind `AddonManager.state(UI)` and `074.4`
already hung the flush on that session's `UI` death, so the shape exists: what moves is where the
tables live. `Manifest.SavedVar.account` is the declaration that says which half a variable name is
in, and it is unchanged — the split is by name, decided by the addon, and this task only makes the
character half honour the session it was reached through.

### B — flush always, and `Disable` on a leash

`Client.main`'s `finally` runs `savewndstate()`, `loop.dispose()`, `System.exit(0)`, and touches
neither the addons nor the store.

**The obvious fix is worse than the defect.** Firing `Disable` on the way out runs arbitrary addon Lua
during shutdown: a handler that loops hangs the client on exit, and a client that will not close is a
worse bug than a client that forgets a table. So the two are separated:

- **The flush is engine code and always runs.** It walks every live session, writes what `StoreApi`
  holds, and runs nothing an addon wrote. It goes **before** `loop.dispose()`, because a destroyed
  `UI` is a session whose scope can no longer be named.
- **`Disable` fires too, and cannot delay the exit.** Bounded — a wall-clock budget for the whole
  set, and a handler that overruns is abandoned rather than waited on. What it is *for* is the addon
  that computes its state at `Disable` rather than keeping it in the store; what it is not is a
  prerequisite for the flush.

The 30-second auto-save stays. Neither of these helps a crash or a kill, and the auto-save is the
only thing that ever did.

### C — two kinds of event, and a Gob that is one object

**Four world events fire once.** `GobAdded` when a gob enters its first session, `GobRemoved` when it
leaves its last, nothing in between. The two overlay events follow the gob they hang on.

`gob:sessions()` needs no bookkeeping: it asks which live sessions hold that id in their `OCache`,
now, and a session that dies drops out of the answer without anything being notified. **The edge does
need tracking** — one client-wide map of the ids currently held by at least one session — and that map
is where the trap is (below).

**Sixteen character events fire per session**, carrying it as the handler's **last** argument. Last
and not first because a handler that ignores the session is not wrong, and Lua drops a trailing
argument it did not declare: an addon that does not care is not asked to change.

### The Gob re-key, and the one verb it breaks

`LuaGob` wraps *"an id and the session that reads it"*. Keyed on the id alone, every **read** still
answers the same — `name`, `health`, `overlay` come from the server's object, and `gob:position()`
already hands back a `LuaPosition` anchored on a **server** grid id, so whichever session computes it
the answer matches.

`gob:click()` does not survive. It is protected by `Permission.GOB_CLICK` and sends the `MapView`
click **a character** makes; a gob keyed on the id alone has nobody to send it as. So it is retired
into **`s:world():click(gob, button, mods)`** — the session acts, the world is the target, which is
the shape `session:move(p)` already has and what `077` decided when it ruled that a permission key
names the action rather than the target.

## Files to create and modify

| File | Task | What |
|---|---|---|
| `src/io/brodgar/addon/StoreApi.java` | 1, 2 | per-session character tables; the flush walk |
| `src/io/brodgar/addon/LuaSession.java` | 1 | `s:store()` answers for its own session |
| `src/haven/Client.java` | 2 | the flush and the bounded `Disable`, before `loop.dispose()` |
| `src/io/brodgar/addon/AddonRegistry.java` | 2 | `Disable` on a budget |
| `src/io/brodgar/addon/LuaGob.java` | 3 | keyed on the id; `:sessions()`; `click` retired |
| `src/io/brodgar/addon/WorldApi.java` | 3 | `s:world():click(gob, button, mods)` |
| `src/io/brodgar/addon/Permission.java` | 3 | `gob.click`'s Lua spelling in the catalogue |
| `src/io/brodgar/addon/Retired.java` | 3 | `gob:click` names its replacement |
| `src/io/brodgar/addon/AddonManager.java` | 3, 4 | the gob queue's drain, the edge map, `fire` |
| `src/io/brodgar/addon/CharApi.java`, `FlowerMenuApi.java` | 4 | the sixteen firings carry their session |
| `specs/ROADMAP.md` | 1, 2, 4 | each task removes its own line, and no other |
| `docs/addons/api/gob.md` | 3 | one object, `:sessions()`, where the click went |
| `docs/addons/api/world.md` | 3 | `s:world():click` |
| `docs/addons/api/event/bus.md` | 4 | the two kinds, and the payload column |
| `docs/addons/api/store.md`, `guides/saved-data.md` | 1, 2 | |
| `docs/addons/runtime.md` | 2 | what an ordinary quit persists |

## Risks and gotchas

- **The drain must settle the whole tick before emitting an edge.** `073` queues gob deltas per
  session and `AddonManager.tick` drains them. A gob that leaves session A and enters session B in one
  tick never really leaves the set — but a drain that processes the removal first sees the count touch
  zero and emits a spurious `GobRemoved` + `GobAdded` pair. Apply every delta, then compare the set
  against what it was at the start of the drain, then emit. **This is what works with one session and
  fails with five moving.**
- **Re-keying `LuaGob` is the change the compiler cannot check.** A handle that meant *this gob in
  this session* comes to mean *this gob*, and a site that relied on the first still compiles. `079.3`
  does it alone and its suite proves **sameness** — every read answers what it answered — rather than
  proving the new verb.
- **The flush must run before `loop.dispose()`.** A destroyed `UI` is a session whose per-character
  scope can no longer be named, so a flush after it writes nothing and reports success.
- **`Disable` on a budget must not become a silent skip.** An addon abandoned mid-handler is worth a
  line on the console; a quit that silently dropped somebody's `Disable` is the same defect this task
  is closing, one layer up.
- **`gob:sessions()` on a gob nobody holds is an empty list, not nil** — it is a collection read, and
  `GobRemoved` is exactly when it is empty.
- **`ant hafen-client` is incremental**; `rm -rf build/classes` first.

## Discarded alternatives

- **Labelling all twenty events with a session** — that labels the noise instead of removing it. Five
  characters standing together see one tree, and five `GobAdded` for one object is the defect, not the
  reporting of it.
- **Deduplicating the character events too** — five characters' meters are five facts. Collapsing them
  would lose exactly the information the label exists to carry.
- **The session as the handler's first argument** — every existing handler would have to be rewritten
  to ignore it, for an addon that does not care which character an event came from and is not wrong
  to.
- **Keeping `gob:click()` by giving it a session argument** — one verb on an object carrying an
  address none of its siblings need, to say something the session already says better: a click is
  something a character does.
- **Two kinds of Gob handle, one bound and one free** — the event would hand out a different object
  from `s:world():gob():get(id)` for the same gob, and interning would stop meaning identity.
- **Firing `Disable` on quit and waiting for it** — the fix that trades lost data for a client that
  will not close. The flush needs no addon code; only `Disable` does, so only `Disable` is bounded.
- **Dropping `Disable` on quit entirely and flushing alone** — cheaper and it abandons the addon that
  computes its state at teardown rather than keeping it in the store. Bounded is the middle that costs
  nothing.
- **Maintaining a reference count for `gob:sessions()`** — a count is a second copy of what the
  `OCache`s already know, and the one that disagrees is the one nothing reads. The set is asked, not
  kept; only the *edge* is tracked, and only for one tick.
