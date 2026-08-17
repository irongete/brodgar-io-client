# 070 — tasks

Two tasks. `070.1` corrects the ground query and ships the counter group both tasks read; `070.2`
closes the render-thread race and writes the map line that would have exposed it.

Read `spec.md` and `plan.md` first. Every file either task may open is listed in `spec.md` under
**Context files**, tagged with the task that needs it.

---

- [x] **070.1 — The ground query asks every session, in the frame each one is in.**
      `Sessions.groundz(Coord2d, double)` stops iterating `members` and iterates `Sessions.placed()`,
      reading `Placed.offset` and `Placed.glob.map` and skipping the entry whose `isanchor` is true —
      the caller reaches `groundz` only *because* the anchor's own map threw `Loading`. That single
      change fixes both halves: the main session enters the search (it is in `placed()` through
      `mainoff`, and was in `members` never), and the stale `Member.offset` of a member that became
      the anchor stops being read, because `buildplaced` already substitutes zero for it. Keep the
      `try`/`catch(Loading)` **inside** the loop: one session lacking that ground is ordinary and must
      not stop the next from answering. Adds the `p:session()` counter group to
      `io.brodgar.addon.ProfHandle`, beside `:net()` and `:render()`, with `groundAnswered` and
      `groundMissed` incremented at the two exits of `groundz`; documents it in
      `docs/addons/api/client/profiling/counters.md` in that page's existing table shape. Also
      corrects `haven.RemoteUI.init`'s comment, which says per-session addon state *"is F6's problem;
      until then the anchor owns the engine"* while `Sessions.tickrebind` is tagged `(F6)` and rebinds
      the engine on every anchor change — the guard `if(!Sessions.ismember(sess))` is right and is not
      touched, only the sentence about F6.
      *Its suite* reads `hafen.client():profiler():session()` and asserts both counters exist and are
      numbers with the profiler **off** — the group is pull-only, like `:net()`, and a counter that
      needs arming would be unreadable in the case it is for. It then drives the real path: with the
      camera reported as `rts` by `hafen.client():camera():mode()`, it samples `groundMissed`, waits
      a bounded window on a timer while the maintainer pans, and samples again. It asserts
      `groundAnswered` **rose** and, at a Position where `hafen.world():height(p)` reads `nil` — which
      is the precondition proving the anchor's map is the one throwing `Loading` there — that
      `groundMissed` did **not** rise. It `pcall`s `hafen.client():profiler():session(1)` and asserts
      the refusal names the group as read-only. Every check prints its numbers, so a failure says
      which counter moved.
      `[manual]`: before running, `:session add <a second account>` and let it reach the world, then
      `:cam rts`, then pan the camera well away from your character onto ground only the *other*
      session has loaded, and leave it there for the run. Expect: the ground stays solid under the
      view and the camera holds its height instead of flattening out.
      `[manual]`: with only one session up, the suite still runs and reports its shape checks —
      confirm it says so rather than passing silently.

- [x] **070.2 — The pick pass reads the session cache; it never builds it.**
      `Sessions.placedcache` becomes `volatile`, and `Sessions.placed()` gains two callers instead of
      one: the tick may build, anything else reads what is published. `buildplaced()` and
      `mainguiof()` — which runs `Widget.findchild`, a recursive walk of a widget tree another thread
      is mutating — stay UI-thread-only, so `mainguifor` and `mainguicache` are touched from one
      thread and need nothing. `Sessions.offsetfor(MCache)` answers `null` when there is no published
      cache, which is what it already answers for a map it does not recognise, so
      `MapView.checkmapclick`'s `if(off != null)` handles it unchanged. The route being closed is
      `checkmapclick` → `offsetfor` → `placed()`, which runs inside the callback of `out.pget(...)` /
      `clmaplist.get(...)` — an asynchronous GPU readback resolved on the render thread. Adds
      `placedRebuiltOffTick` to the `p:session()` group from `070.1`, incremented if `placed()` is
      ever asked to build off the tick. Writes the consequence into `docs/client/world-3d.md`, on the
      **Click dispatch** row that already says the pick is asynchronous: it names what the pick cannot
      *serve*, and not what the callback may *touch*, which is the omission that hid this. Upstream
      `haven` only — nothing about `Sessions`, `placed()` or `groundz` goes on that page.
      **That page is 151 lines against a 150 ceiling and `070.1` filed it on `ROADMAP.md`, so this
      must not grow it**: the Click dispatch row is a single unwrapped table line, so the consequence
      is written *into that sentence*, not as a new row, a new paragraph or a new section. Adding a
      line here worsens a violation this feature already declined to take on, and the ROADMAP line
      stays standing.
      *Its suite* re-asserts the `hafen.client():profiling():session()` group from scratch, assuming
      `070.1`'s suite is never run — `groundAnswered` and `groundMissed` read as numbers with the
      profiler off, and `placedRebuiltOffTick` beside them.
      It then hammers the closed route from Lua: `hafen.world():screenToWorld(sx, sy, fn)` goes
      through `MapView.Maptest` → `checkmapclick` → `offsetfor` → `placed()`, so a timer firing it at
      every tick across a spread of pixels over drawn ground drives the render thread onto that path
      hundreds of times while the tick mutates the cache underneath. It asserts every callback
      delivered a Position rather than `nil` for a pixel over drawn ground, that no two callbacks for
      one pixel disagreed by more than a tile, and — the criterion itself —
      that `placedRebuiltOffTick` is **still 0** at the end. It `pcall`s `screenToWorld` with a
      non-function third argument and asserts the refusal names that the answer comes back a frame
      later.
      `[manual]`: with a second session standing far enough away that its ground is merged into the
      scene rather than shared, left-click repeatedly on that merged ground. Expect: the character
      walks to the point clicked every time, with no click landing at an offset from the cursor.
