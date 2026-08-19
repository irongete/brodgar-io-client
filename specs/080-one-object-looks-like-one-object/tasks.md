# 080 — tasks

One task. The write and the undo are two halves of one claim — *a visual write on a gob applies to the
object* — and either half alone is the same asymmetry pointing the other way, which is a worse defect
than the one being closed.

Read `spec.md` and `plan.md` first, and `docs/client/multi-session.md` for the monitor rule. Every
file this task may open is in `spec.md` under **Context files**.

**Guardrail**: `grep -rn "hafen\.\(timer\|event\|slash\|asset\|json\|http\|font\|client\)()" docs/`
is **212** before and after. No namespace moves here; what changes is what two verbs reach.

---

- [x] **080.1 — A gob is one object, and it looks like one.**
      `079.3` settled that a gob is one object — `LuaGob` keyed on the id, every read answering the
      same whichever session computes it — and left its two **visual writes** landing on one copy.
      A `Gob` is per-`OCache`, so *one object* and *one `Gob`* are different things: **the object is
      what an addon addresses, the copies are what the engine paints.**
      **The write**: `LuaGob`'s `scale` resolves one `Gob` through `gob(self, "scale")` and calls
      `GobScale.apply(g, owner, k)`; `gob:overlay()` reaches `LuaGobOverlay.on(g)` the same way. Both
      grow a walk over **every live session that holds that id** — `gob:sessions()`, which `079.3`
      added, is a live read of the `OCache`s and the natural source. A session that does not hold it
      is **skipped, not an error**, the same *state rather than fault* the offset machinery already
      reports.
      **The undo**: `UiApi.teardownGobOverlays` and `teardownGobScales` stop reading `screen()` and
      `AddonManager.allGobs()`. Each walks every live session through
      `AddonManager.allGobs(String user)`, **which already exists**. Then the argumentless
      `allGobs()` has no callers and **is deleted** — the discipline `072.3` and `078.4` used, because
      an ambient accessor left standing is an invitation to write the next caller, and deleting it
      hands the watch to the compiler.
      **The monitors, one at a time.** Each method takes `synchronized(u)` today because teardown
      *"may run off the UI thread (session bind) while `ctick` rebuilds the state"* — true per
      session. But `docs/client/multi-session.md` records that **the tick never holds two UI monitors
      at once**: take one session's monitor, do that session's work, release, move on. **Never
      nested.** A session whose `UI` has gone is skipped, not a reason to run unguarded. **Two
      monitors at once is the one way this becomes worse than the defect it closes.**
      `GobScale` records who wrote a size and reverts only what this addon last set — widening the
      walk must not widen that. Revises `api/gob.md` and `api/overlay.md`, whose promises —
      *"puts back **everything** you resized, so **nothing** is left distorted behind you"* and
      *"a `:reload` or a disable likewise removes every overlay you…"* — are true after this task and
      are not true today. Removes the one `(filed: 079)` line about the teardown walks from
      `ROADMAP.md`, and no other.
      *Its suite* scales a gob and attaches an overlay to it, then asserts `gob:scale()` reads back
      the value it wrote and that the overlay is found by its key — the write half, before anything is
      torn down. It asserts `gob:sessions()` lists the sessions it walked. It `pcall`s `gob:scale()`
      with a value outside the accepted range and asserts the refusal names the range.
      **The undo cannot be asserted by the addon that performed it**, because that addon is gone by
      the time there is anything to check — so it is two-phase, and the suite prints what to look for.
      `[manual]`, **phase one**: with two characters **standing together** so both hold the same
      objects, run the suite. Expect: it reports the gob it scaled, its id, and that `:sessions()`
      named both accounts.
      `[manual]`, **phase two**: `:reload`, then **tab to the other character** and look at that same
      object. Expect: it is back to its normal size with no overlay on it. Before this task it is
      still scaled there, because the sweep only ever reached the character that was on screen.
      `[manual]`: paste the guardrail line. Expect: **212**.
