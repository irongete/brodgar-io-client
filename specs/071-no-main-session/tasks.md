# 071 — tasks

Three tasks, and the order is load-bearing: ownership moves first, the dead duplicate is deleted
second, the vocabulary is re-said third. Read `spec.md` and `plan.md` first. Every file a task may
open is listed in `spec.md` under **Context files**, tagged with the task that needs it.

`070.2` left a rule this feature works inside and must not break: only the frame thread (`UILoop.th`)
may build the `placed()` cache, `Sessions.republish()` runs at the end of `Sessions.tick()`, and any
other thread asking is answered `Sessions.unpublished` and counted by `placedRebuiltOffTick`. **Every
suite here re-asserts that counter is still 0**, because a feature rewriting `buildplaced`'s callers
is exactly what would break it.

---

- [x] **071.1 — The first session stops being the client's own, and becomes one of the sessions.**
      `Client.Main.run` cycles `Bootstrap` → `RemoteUI` → `Bootstrap` through `UILoop.newui`, which
      replaces `UILoop.ui` and destroys what was there. When that chain reaches a `RemoteUI`, hand it
      to `Sessions` instead of keeping it in the slot: built through `UILoop.bgui` (which replaces and
      destroys nothing, and takes no `uilock`) and run on its own `HackThread`, exactly as
      `Sessions.Member.start` already does — and **registered before its `UI` is constructed**,
      because `UI`'s constructor runs `RemoteUI.init`, which asks `Sessions.ismember(sess)`. `Main`
      then returns to `Bootstrap` and waits, as it does after a logout today; it must go on never
      returning, because `Client.run`'s outer `while(task != null)` ends the client when it does.
      `UILoop.ui` now holds the **login** UI and never a game session, so `UILoop.drawn()` stops
      falling back to a game session: it answers whichever session holds the screen, or the login
      screen when none does. `UILoop.run`'s second tick call, `Sessions.tickbg(main)`, is deleted with
      the concept it served. `Sessions.reclaim()` loses its `an == mu` early return and hands a dead
      anchor's screen to another live session, or to the login slot when it was the last. Adds `live`
      — how many sessions the client holds — to the `hafen.client():profiling():session()` group, and
      corrects `counters.md`'s sentence that this group's counters are "cumulative since the client
      started", which a gauge is not. Writes the runner state machine's new shape into
      `docs/client/boot-and-loop.md` (86 lines, room under the 150 ceiling).
      *Its suite* asserts `live` reads `1` with one character in the world — which is the whole claim
      of this task, because before it that session was in no list at all and `live` could only have
      answered `0`. It asserts `placedRebuiltOffTick` is `0`, then drives the render thread onto the
      published cache with a burst of `hafen.world():screenToWorld(sx, sy, fn)` across pixels over
      drawn ground and asserts it is **still** `0` and that every callback delivered a Position — the
      `070.2` guarantee, re-proved here because this task rewrote who builds that cache. It `pcall`s
      `hafen.client():profiling():session(1)` and asserts the refusal names the group as read-only.
      `[manual]`: `:session add <a second account>`, wait for it to reach the world, then re-run.
      Expect: the summary line reports `live` as 2, and both characters appear in `:session list`.
      `[manual]`: log out from the game menu with no other session up. Expect: the login screen comes
      back and the client stays running, exactly as before this change.

- [x] **071.2 — One list, and the second copy of everything is deleted.**
      With every session a `Member`, the parallel machinery is unreferenced. Delete `Sessions.mainoff`,
      `mainoffanchor`, `mainoffglob`, `mainofftry`, `tickmainoffset()`, `mainguiof()`, `mainguifor`
      and `mainguicache`. `Sessions.dormant(Glob)` loses its explicit `mainui()` branch and keeps its
      loop. `buildplaced()` loses its main-session branch and becomes one loop over `members`, so
      **`Placed.member` is never null and `Placed.user` is always `Member.user`** — the literal
      `"main"` leaves the class, which is what stops `hafen.session()` from publishing it later.
      `Sessions.mainui()` survives only if something still needs the login UI by name; if nothing
      does, it goes too. This task deletes and must add nothing: a compile that still passes with
      those eight members gone is most of the claim, and a clean build (`rm -rf build/classes` first)
      is what makes that claim mean anything, because an incremental one hides a symbol that moved.
      *Its suite* re-asserts the `session()` group from scratch, assuming no other suite is ever run:
      `live`, `groundAnswered`, `groundMissed` and `placedRebuiltOffTick` all read as numbers with the
      profiler off. It asserts `live` matches the number of characters the maintainer has in the world
      — declared by the `[manual]` below — and that `placedRebuiltOffTick` is `0` after a
      `screenToWorld` burst, which is the regression this deletion could plausibly cause by changing
      what `buildplaced` walks.
      `[manual]`: with **two** sessions up, run `:session list` and paste the whole block. Expect:
      both rows name an **account**, and neither reads `main`. This is the proof of the criterion no
      program can read, because nothing reaches `Placed` from Lua until `hafen.session()` exists.
      `[manual]`: pan the `rts` camera over ground only the *other* session has loaded. Expect: the
      ground stays solid and the camera holds its height — `groundz` reads `placed()`, whose body this
      task rewrote.

- [x] **071.3 — The words drop the main, and the last one out reaches the login screen.**
      `:session anchor` takes an account name and the `main` keyword is retired — a spelling that
      throws naming what to write instead, not one that reads as an unknown account.
      `:session drop <account>` reaches any session, and dropping the **last** live one leaves the
      client on the login screen rather than drawing nothing. `Sessions.add` refuses an account that
      is already live, checking the one list rather than a list plus a slot. `Sessions.next()` cycles
      a flat list with no distinguished first stop, so `rts-next-anchor` visits each session once.
      `Control.take(null)`, which meant "the main one", is re-spelled or retired with it, and
      `SessionWnd`'s rows and its main button follow. Revises `docs/client/multi-session.md` **in
      place**: it is 174 lines against a 150 ceiling, and the rows this feature deletes (`tickbg`, the
      `USER|main` row, the dormancy branch, "spelled four ways") should bring it under. Its
      `io.brodgar` shape problem is filed on `ROADMAP.md` and stays filed — do not split the page
      here, because the destination for its merged-scene rows is `world-3d.md`, itself over its
      ceiling.
      *Its suite* re-asserts the `session()` group from scratch, then reads `live` before and after
      each gesture the maintainer performs, scoring on a bounded timer so a slow login does not fail
      the run: after a duplicate `:session add`, `live` is **unchanged**; after `:session drop` of a
      session that is not the anchor, `live` falls by one. It prints the numbers on every line so a
      failure names which gesture did not move what it should have.
      `[manual]`: with two sessions up, `:session add <the account already logged in>`. Expect: it is
      refused, naming that account as already live, and `live` stays at 2.
      `[manual]`: `:session anchor main`. Expect: refused, naming an account name as what to write
      instead — not "no such session".
      `[manual]`: `:session drop` each session in turn, the one on screen last. Expect: the screen
      moves to a remaining session each time, and after the last the login screen returns with the
      client still running.
