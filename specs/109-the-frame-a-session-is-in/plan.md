# 109 — Plan

## Approach

**One `Base` per member, immutable and replaced whole** — `Recall`'s own shape:

```java
static final class Base {
    final MapFile file;        // GameUI.mmap.file -- which database this session names
    final long seg;            // sessloc.seg.id
    final Coord tc;            // sessloc.tc -- segment tile coord of this session's tile (0, 0)
    final boolean proven;
}
```

Derived every tick from that session's **corner** minimap (`Member.gameui().mmap`, whose `file` and
`sessloc` are the two halves and both `public`), for **every** member, the anchor included.

**Proved, not merely derived.** A live grid out of `AddonWidgets.loadedGrids(mc)` is translated by the
base — `sc = g.gc.add(tc.div(MCache.cmaps))` — and `Segment.gridid(sc)` must equal `g.id`. That is the
check `Recall.sweep` already runs, and `mapfile.md` already states as the rule. Taken under
`file.lock.readLock().tryLock()` and **never waited on**: a lock miss keeps the previous verdict rather
than dropping it, because the processor thread holds that lock for as long as a segment save takes.

**The offset becomes a subtraction**: `off = anchor.tc.sub(mine.tc)` scaled by `MCache.tilesz`. It is
the number `findoffgc` gives wherever `findoffgc` gives one — `gcMine - gcAnchor` is
`(tcAnchor - tcMine) / cmaps` — so nothing that works today changes value, only when there is a value.

**Three refusals, in this order and each with a name**: neither base proved · `mine.seg != anchor.seg` ·
`mine.file != anchor.file`. The reason is a field, and `say()` fires only when it **changes** — a pair
that anchors and unanchors with every cave transit is otherwise a line per transit.

**The addon layer reads that same base.** `MapApi.sessloc()` / `sessloc(user)` stop answering
`mm.sessloc` and answer the proved base's `Location`, or `null`. `gridUL`, `segGridUL`,
`recordedGridId` and `LuaPosition`'s `anchorAt` / `ulOf` fallbacks are untouched: they take a
`Location`, and now only ever get a proved one — so `components()` answering `nil` in the unproved
window falls out of the change instead of being written into every reader.

**`:session where`** is a `Client.cmdmap` subcommand over the `Member.where()` that exists today with no
caller, rewritten to state the base, the proof, the offset in grids, and the refusal.

## Files to create/modify

- `src/io/brodgar/session/Sessions.java` — `Member.Base`, `tickbase`, `tickoffset` rewritten,
  `findoffgc` and the `offgc`/`offshared`/`offconflict`/`offmine`/`offanchor`/`offtry` fields retired,
  `where()`/`check()` rewritten, and `tick()`'s member loop split so the base derives for the anchor too
- `src/haven/Client.java` — the `where` subcommand, and the `usage:` line that lists the subcommands
- `src/io/brodgar/addon/MapApi.java` — `sessloc()` and `sessloc(user)` onto the proved base
- `docs/client/multi-session.md` — *Aligning two coordinate frames* replaced; one `:session` table row
- `docs/client/minimap.md` — the proof, beside the staleness gotcha it exists for
- `docs/addons/api/position.md`, `docs/addons/api/world.md` — a place resolves through a **proved** base
- `addons/109-the-frame-a-session-is-in.1/`, `.2/`, `.3/`

## Risks & gotchas

1. **`Sessions.tick`'s member loop skips the anchor** (`u == an` → `continue`; the frame ticks it in
   full). The anchor's base is one half of every difference, so the base pass runs **ahead of** that
   skip. Get this wrong and every offset is null with nothing said.
2. **`GameUI.mmap` is the corner minimap and `MapWnd` carries a second `MiniMap`** over the same file
   (`MapApi.minimap`'s own comment says why it names one). The base comes from `mmap` and no other, or
   it follows a window the user panned.
3. **`sessloc` goes stale rather than null** (`minimap.md`) — that is the whole reason for the proof. A
   base that merely exists is not a base that is right.
4. **`MapFile.lock` is written by the processor thread.** `tryLock` only; and `Segment.gridid(sc)`
   answers from memory where `Segment.grid(sc)` waits on `Defer` — take the first and never the second.
5. **`buildplaced` already drops a member whose offset is null**, so "leaves the merged scene" needs no
   new code. It also means an unproved base at enter-world takes the merged view away for that window,
   which is what the spec's criterion 3 rules correct.
6. **`Member.offset()` keeps its last value once a member takes the screen** (`tickoffset` returns early
   for the anchor); `placed()`'s `isanchor ? zero : offset` is what corrects it. Preserve that shape —
   `groundz` and `offsetfor` both rest on it.
7. **`sessiontick2` rebuilds a `SessionView` whole when `off` changes** (`!fv.off.equals(v.offset)`), so
   a base that flaps rebuilds the merged scene each time. Second reason a lock miss keeps its verdict.
8. **`Sessions.say` reaches the anchor's `UI.msg`**, which the client also logs to the System channel —
   which is how a suite reads `:session where` back. One notice leaves **two** identical System lines
   (`console.md`); read the newest and do not count.
9. **`chrmap` writes a pref `GameUI.addchild` reads once**, at enter-world, so a `MapFile` split needs
   that session dropped and added again — it cannot be staged inside a run.

## Discarded alternatives

- **Keep the intersection and add a staleness check to it** — the check would have to be the base
  anyway, and two derivations that must agree are one more thing to keep agreeing.
- **Reset the offset when the map is dropped (`MCache.trimall`)** — it answers the re-base and not the
  distance, and half a re-base arrives as a partial `trim`; a base re-derived every tick needs no event.
- **Own the base in `MapApi` and have `Sessions` read it** — the addon layer would hold the number the
  client's own merged view rests on, and `MapApi` answers for the *drawn* session by default. The base
  is a property of a session, so the session holds it.
- **A bare `MiniMap.Location` per member instead of a `Base`** — a `Location` carries the segment and
  the tile coord and not the `MapFile`, so the `chrmap` split would have nothing to compare.
- **Say the refusal every tick, as `check()` reports today** — one line per frame. The reason changing
  is the edge; the reason holding is not news.
- **Fold `Recall` onto the shared base in this feature** — it needs `mustrelease` ordering no other
  caller has, and that refactor would ride on this one rather than be reviewed as itself.
- **Prove against every loaded grid rather than one** — the frames are rigid, so a second grid can only
  repeat the first's verdict; the disagreement case that `findoffgc` reported is a property of
  intersecting two live tables and does not survive the base.
