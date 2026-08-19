# 079 — tasks

Four tasks. `079.1` and `079.2` each close one defect a closed feature left; `079.3` and `079.4` are
the bus, split so the risky half is done alone.

Read `spec.md`, `plan.md`, and `namespaces.md` in `specs/075-globals-without-a-session/` first. Every
file a task may open is in `spec.md` under **Context files**, tagged with the task that needs it.

**The order is forced twice.** `079.1` before `079.2`, because the quit flush must write every
session's tables and there is no sense writing it against a shape about to change. `079.3` before
`079.4`, because re-keying `LuaGob` is a change the compiler cannot check and must not be mixed with
one it can.

**Each task removes its own `ROADMAP.md` line and no other.** The sequence filed 24; this feature
closes 3.

**Guardrail, at every task boundary**:
`grep -rn "hafen\.\(timer\|event\|slash\|asset\|json\|http\|font\|client\)()" docs/` is **212** and
stays 212. `hafen.event()` is global and stays global — this feature changes what a handler
*receives*, never how the bus is reached.

---

- [x] **079.1 — The saved variables follow the address.**
      `078`'s criterion 4 claimed the per-character scope is reached through its session; the address
      moved and the storage did not, so `s:store()` answers only the drawn character and raises for
      every other. The per-character tables become **per session**, held where `073` already holds
      per-session engine state (`AddonManager.state(UI)`) and flushed where `074.4` already hangs the
      flush (that session's `UI` death). `Manifest.SavedVar.account` is untouched: the split is by
      **variable name**, declared by the addon, and this task only makes the character half honour the
      session it was reached through. Removes the `(filed: 078)` line about saved variables from
      `ROADMAP.md`. Revises `api/store.md` and `guides/saved-data.md`.
      *Its suite* writes a per-character table and an account-scope table through `:current()`, reads
      both back and asserts equality; asserts a key never written reads `nil`; asserts the two scopes
      do not see each other's keys. It `pcall`s a per-character write on a session it names by account
      and asserts it **succeeds** — which before this task raised, and is the criterion.
      `[manual]`: with two sessions in the world, write a per-character value on the one **not** on
      screen, then tab to it and re-run. Expect: that character reads its own value, and the first
      character's is untouched — two folders, not one.

- [x] **079.2 — An ordinary quit stops losing what an addon wrote.**
      `Client.main`'s `finally` runs `savewndstate()`, `loop.dispose()` and `System.exit(0)` and
      touches neither the addons nor the store, so the 30-second auto-save is all an ordinary quit
      persists. **The flush and `Disable` are separated, because the obvious fix is worse than the
      defect**: firing `Disable` on the way out runs arbitrary addon Lua during shutdown, and a
      handler that loops hangs the client on exit. So the **flush is engine code and always runs** —
      walking every live session, writing what `StoreApi` holds, running nothing an addon wrote — and
      it goes **before `loop.dispose()`**, because a destroyed `UI` is a session whose scope can no
      longer be named. **`Disable` fires too and cannot delay the exit**: a wall-clock budget for the
      whole set, and a handler that overruns is abandoned with a line on the console rather than
      waited on — a quit that silently dropped somebody's `Disable` would be this same defect one
      layer up. The auto-save stays: neither helps a crash or a kill. Removes the `(filed: 074)` line
      about the quit path. Revises `runtime.md`.
      *Its suite* writes a per-character value and **does not** wait for the auto-save, reporting the
      value and the key it used so the maintainer can read it back after a restart. On a later run it
      reads that key and asserts it survived.
      `[manual]`, **two phases**: run once and note the value the suite printed. Then **quit the
      client from the game menu** — not by killing it — and start it again, log the same character in,
      and re-run. Expect: the suite reports the same value, read from disk. Before this task it is
      gone unless thirty seconds happened to pass.

- [ ] **079.3 — A gob is one object, and the click belongs to a character.**
      `LuaGob` wraps *"an id and the session that reads it"*; it becomes keyed on **the id alone** and
      gains `gob:sessions()` — which live sessions hold it, asked of their `OCache`s at the moment of
      the call rather than kept, so a session that dies drops out of the answer with nothing notified.
      Every **read** survives unchanged: `name`, `health` and `overlay` are the server's object, and
      `gob:position()` already returns a `LuaPosition` anchored on a **server** grid id, so whichever
      session computes it the answer matches. **`gob:click()` does not survive** — it is protected by
      `Permission.GOB_CLICK` and sends the click *a character* makes, and a gob keyed on the id alone
      has nobody to send it as. It is retired into **`s:world():click(gob, button, mods)`**, naming
      its replacement: the session acts, the world is the target, which is `session:move(p)`'s shape
      and `077`'s ruling that a key names the action rather than the target. Revises `api/gob.md` and
      `api/world.md`. **This task does the re-key alone and changes no event**, because a handle that
      meant *this gob in this session* coming to mean *this gob* is a change that still compiles
      wherever it is wrong.
      *Its suite* proves **sameness**, not novelty: it reads `:id()`, `:name()`, `:health()`,
      `:position()` and `:overlay()` off a gob found through `:current():world():gob()` and asserts
      each answers what it answers today; asserts two lookups of one id are `==`, which is the
      interning claim under the new key. It asserts `gob:sessions()` lists at least the session it was
      found through. It `pcall`s `gob:click()` and asserts the refusal names
      `s:world():click(gob, …)`; without the key declared it `pcall`s `s:world():click(gob)` and
      asserts the refusal names the verb **and** `gob.click`.
      `[manual]`: with two characters standing together, run and read the `:sessions()` line. Expect:
      it names **both** accounts for a gob they can both see.

- [ ] **079.4 — The world fires once, and the character says which.**
      **The four world events fire once**: `GobAdded` when a gob enters its first session,
      `GobRemoved` when it leaves its last, and nothing when it leaves one of several — the middle case
      needs no event because `gob:sessions()` is a live read. The two overlay events follow the gob
      they hang on. The edge is tracked by one client-wide set of the ids currently held by at least
      one session, and **the drain must settle every delta of the tick before comparing and emitting**:
      `073` queues gob deltas per session, and a gob that leaves A and enters B in one tick never
      really leaves the set, but a drain that applies the removal first sees zero and emits a spurious
      pair. That is what works with one session and fails with five moving. **The sixteen character
      events carry their session as the handler's last argument** — meters, buffs, fep, study, equip,
      action bar, wounds, kin, quests and the radial menu — last so that a handler which ignores it
      needs no change, since an addon that does not care which character an event came from is not
      wrong. **The seven that need none do not grow one**: `Load`, `Update`, `Disable`,
      `MarkersChanged`, and the three `vr` click events. Removes the `(filed: 074)` line about the
      broadcasting bus. Revises `api/event/bus.md`, and the subscription examples the derived grep
      finds.
      *Its suite* subscribes to `MeterChanged` and asserts every firing carries a session whose
      `:user()` reads. It subscribes to `GobAdded`, counts firings per gob id over a bounded window,
      and asserts **no id fired twice** — the deduplication, stated as an assertion. It `pcall`s
      `hafen.event():on("GobAdded ", fn)` with the trailing space and asserts the refusal points at
      the catalogue.
      `[manual]`: with two characters **standing together**, walk one of them a few steps and re-run.
      Expect: the `GobAdded` count is the number of objects, not twice it. Before this task each
      object arrived once per session.
      `[manual]`: with the two apart, note a `MeterChanged` line for each. Expect: each names its own
      account.
