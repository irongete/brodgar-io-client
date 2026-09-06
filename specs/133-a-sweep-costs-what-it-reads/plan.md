# 133 — plan

## Approach

### The offset is a whole-map property, so a sample decides it

The prove loop exists to catch a **re-base**: `sessloc` going stale while the session coordinate space
underneath has moved. That is a property of the map as a whole — under a stale offset *every* live grid
disagrees with the record, not one of them. So a bounded sample decides it exactly as the full walk does,
and the walk was never a per-grid integrity check.

The verdict keeps both halves it has: `checked == 0` still fails, because a record that knows nothing near
where the session stands cannot vouch for the offset, and `wrong > 0` still condemns. What a sample gives
up is noticing a *single* disagreeing grid among agreeing ones — a corrupt record rather than a re-base,
and nothing downstream distinguishes the two today.

### Witnesses the sweep already names

`Base` carries `tc` and `off`; `readset` is what the raster asked for, centred on the camera; and the live
cache certainly holds the ground around the character. The witnesses come from there — a handful of coords
the sweep already has, asked one at a time through `AddonWidgets.loadedGrid(map, gc)`, which takes the live
monitor once per ask. `RecallTerrain.tick` already asks that way, per grid, every ctick, which is what makes
it the shape rather than an invention. How many is a named constant beside `maxread` and `keepsquares`, so
"a handful" is a number a reader can find and change.

### Nothing is built until there is something to build

`sweep` opens with a `HashMap` for `ready`, one for `ids` and one for `got`, ahead of a `tryLock` that may
fail and a proof that may condemn. `got` and `ids` are filled inside the read loop and `ready` only after
it, so each moves to its first use. A sweep that cannot take the lock, that fails the proof, or that finds
`readset` empty — the ordinary case while the camera stands still — then allocates nothing at all.

### The LRU learns at the doors, not from the cache

`trim` reconciles its LRU against the recall's own cache because a sweep installs grids the LRU never heard
of. But there are only three doors, and all three are in this class: `install` at `Recall.java:576` is the
one way in, and `trim`'s own `map.drop(drop)` and the `map.trimall()` on a re-base are the two ways out.
The LRU records at each, and `trim` stops asking the cache what it holds: no copy, no `HashSet`, and the
reconciliation becomes bookkeeping the class already owns. Note that `install` **calls `trim(1)` first**, so
the record goes in after the room is made, not before.

### The page gains the distinction that makes one copy expensive

`terrain-raster.md` maps the recall raster in detail and never says that the recall holds its **own**
`MCache` — `new MCache(sess)`, a second source of ground beside the session's. That is exactly what decides
which of these two copies is expensive: `loadedGrids` on the session's cache takes the monitor the map and
render threads want, while the same call on the recall's own takes nobody's. One gotcha, on the page that
already owns the subject.

## Files to create or modify

| File | What |
|---|---|
| `src/io/brodgar/session/Recall.java` | the witnesses and the bounded proof; the three maps at first use; the LRU at the doors |
| `docs/client/terrain-raster.md` | the gotcha: the recall's own `MCache`, and which monitor each accessor takes |
| `addons/133-a-sweep-costs-what-it-reads.1`, `.2` | one suite per task |

No `docs/addons` page: nothing an addon observes changes, and the three `c:recall*` verbs and the four
`p:render()` counters are what criteria 5 and 6 hold to.

## Risks and gotchas

- **`install` calls `trim(1)` before it installs.** An LRU write placed after that call is a record made
  after the room was made for it, which is the order the cap depends on; placed before, the entry counts
  against its own room.
- **`map.trimall()` empties the cache wholesale on a re-base.** The LRU must be cleared in the same breath,
  or it holds coords for grids that no longer exist and `trim` drops nothing while believing it is full.
- **`readset` is read exactly once per sweep** — the raster may hand over a new set under a sweep already
  running, and the comment at that read says so. Witnesses drawn from it inherit that snapshot.
- **`loadedGrid` takes the live monitor per ask.** A witness set that grew toward the size of the cache
  would be worse than the copy it replaces; the constant is the whole point, not an incidental.
- **The record's `Segment.gridid` can throw.** The bounded proof must tolerate exactly what the loop
  tolerates today, and `sweep` must stay inside the `tryLock`/`finally` it already has.
- **`pinned` is written by `want` and read inside `trim`.** It is untouched here, and the LRU change must
  not move where it is read.
- **`nstale`, `proven` and `mustrelease` are the proof's outputs**, read by the status line the maintainer
  sees. A bounded proof writes them on the same conditions.

## Discarded alternatives

- **Hoisting the copy out of the sweep and keeping the full walk** — the copy is not the cost. The `Coord`
  per grid and the per-grid record lookup are, and the walk is what creates both.
- **Caching the loaded-grid snapshot across sweeps** — it is the one thing that goes stale on a re-base,
  which is the event the sweep exists to detect; a cache of it would answer with the world the proof is
  trying to disprove.
- **Proving the base from `readset` alone** — those are grids the record may hold and the live cache may
  not, and a witness with no live grid has no id to compare against.
- **Making `trim` ask `loadedGrid` per LRU key** — it trades one copy for up to `gridcap()` monitor takes,
  and leaves the LRU still learning its membership from somewhere other than its own doors.
- **Giving the sweep a clock instead of running it per ctick** — `sweeping` already serialises, and a clock
  would make the first proof after a re-base late by exactly its interval, which is the window in which
  remembered ground is drawn where it never was.
- **Counting the proof's witnesses in the `recall` counters** — they are the ground's gauges, and a number
  about how the offset was checked is a different subject on a page that says those four are what the reach
  costs.
