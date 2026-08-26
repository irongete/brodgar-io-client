# 109 — Tasks

- [x] **109.1 — One proved base per session, and the verb that reads it.** Adds
      `Sessions.Member.Base` (`file`, `seg`, `tc`, `proven`) and `tickbase()`, derived every tick from
      that session's corner minimap (`gameui().mmap`) for **every** member, the anchor included —
      `tick()`'s member loop splits so the base runs ahead of the `u == an` skip. The proof is
      `Recall`'s: a live grid out of `AddonWidgets.loadedGrids` translated by `tc` must equal
      `Segment.gridid` at that coord, under `file.lock.readLock().tryLock()`, a lock miss keeping the
      previous verdict. `:session where` becomes a `Client` subcommand over the `Member.where()` that
      has no caller today, and the `usage:` line lists it. `docs/client/minimap.md` gains the proof
      beside the staleness gotcha that is its reason.
      *Its suite* runs `s:console():run("session where")` on the anchor and reads the lines back out of
      `chat.system` (newest first — one notice leaves two identical lines): it asserts every live
      session reports a base carrying a segment id and a tile coord, and that each says it is proved.
      It asserts a session that cannot be proved is named as such and reports no coordinate at all.
      `[manual]`: with one character underground, its line reads as a segment refusal.

- [x] **109.2 — The offset is a difference of bases.** `tickoffset` is rewritten to
      `anchor.tc.sub(mine.tc)` scaled by `MCache.tilesz`, with three refusals in order and each named —
      neither base proved, `seg` differing, `file` differing — said once when the reason **changes**,
      never per tick. `findoffgc` retires with the `offgc`, `offshared`, `offconflict`, `offmine`,
      `offanchor` and `offtry` fields; `check()` and `where()` lose the shared-grid count and gain the
      offset in grids. `docs/client/multi-session.md`'s *Aligning two coordinate frames* is replaced,
      and its `:session` table gains the `where` row.
      *Its suite* asserts, over `session where` read back from `chat.system`, that two characters
      several grids apart both report a proved base **and** an offset between them — the pair that
      reports `unanchored` before this task. It asserts the offset is a whole number of grids, and that
      a session whose line names a refusal reports no offset rather than a stale one.
      `[manual]`: two characters standing together — the merged patch, an RTS order and the selection
      behave as they did; and after one of them walks into a house and out again, the pair is related
      again, at the same offset, with nothing drawn through the old base in between.

- [x] **109.3 — A place resolves through a proved base.** `MapApi.sessloc()` and `sessloc(user)` answer
      the proved base's `Location` or `null` instead of `mm.sessloc` raw, so `gridUL`, `segGridUL`,
      `recordedGridId` and `LuaPosition`'s `anchorAt` / `ulOf` fallbacks — untouched — only ever convert
      through a base that has been proved. `docs/addons/api/position.md` and `world.md` state that a
      place resolves through a **proved** base and what `nil` now means; the seven pages the spec's
      third grep names are each checked, and rewritten only where their sentence stops being true.
      *Its suite* takes one durable Position and asks it of two sessions through
      `s:world():components(p)`: it asserts both answer, and that the difference between the two
      answers is the offset `session where` reports for that pair — the two bridges agreeing is the
      claim, since the streamed half already answers at any distance. It asserts `components(p)` is
      `nil`, and not a number, for a session whose base that same line calls refused.
      `[manual]`: `chrmap` on one session, that session dropped and added again, and its line reads a
      `MapFile` refusal.

- [x] **109.4 — The base moving is the event.** The notice that a session's coordinate space moved is hung
      on the **base** rather than on `MiniMap.sessloc`: `Member.setbase` is the one place `base` is written
      and it calls `AddonManager.sessionRebased(ui)`, which `VrApi` takes for the drawn session alone and
      turns into `groundDirty`. The `// addon: 045.2` line in `MiniMap.tick` retires with the
      `vrSessSeg`/`vrSessTc`/`vrSessSeen` memo it fed — `tickbase` replaces the `Base` only when it differs,
      so the notice arrives as an edge and has nothing left to remember. `sessloc` moves once, when the
      server re-bases the session; the base moves then **and again** when a live grid's id first agrees with
      the record through it, and that second edge — the one 109.3 opened and the old tap could not see — is
      when a place off the streamed ground starts resolving at all. `docs/client/minimap.md` loses the tap
      from its anchors.
      *Its suite* stands one entity at a durable place its own character cannot see the ground of, reads
      `hafen.client():profiling():counters()` for `waiting` and `passes`, and asserts the pass runs on the
      edge and not on the frame: `passes` holds still across a second of standing, and the entity that is
      `waiting` reports `:drawn()` false while `:position():info()` answers unchanged.
      `[manual]`: with something standing on remembered ground, walk into a house and out again **without
      moving after arriving** — it is drawn again by itself, and `passes` climbed by a handful rather than
      by a hundred.
