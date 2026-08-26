# 109 — The frame a session is in

## What & why

Every session logs in somewhere else, so one patch of ground has a different number in each of them.
`Sessions.Member.tickoffset` relates two frames by intersecting the **live** grid tables, and it is wrong
twice over.

- **It asks "are they near each other" where the question is "are they in one segment".** Each session
  holds a 3×3 block of grids, so apart they share nothing and the pair is `unanchored` — the same silence
  a character in a cave gets, the one pair that genuinely has no translation. `buildplaced` then drops
  the member whole: not merged, not orderable, not selectable, no click.
- **Once it succeeds it is never derived again** — `if(offset != null) return;`, reset by a new `Glob` on
  either side, nothing else. The coordinate space is re-based mid-play (a cave, a house:
  `MCache.trimall`) and a `Glob` survives that, so the kept number places that session's ground and
  objects where they never were, silently, for the rest of the login.

The client already knows where each session stands. `MiniMap.sessloc` is the segment tile coord of that
session's tile `(0, 0)`, every session records its own ground into the one database they all name, and
`Recall` (068) already proves such a base against a live grid's id. **This feature makes it the one
derivation**: one base per session, re-derived every tick, proved, and refused by name rather than
guessed. Two frames are related by differencing two bases, which needs no shared ground.

`MapApi.sessloc(user)` reads that base **raw**, so the *recorded* half of `LuaPosition`'s durable⇄session
resolution answers through a stale base in the same window — the streamed half anchors on a live grid's
id and is exact. Both move onto the proved base.

## Acceptance criteria

1. Two characters **several grids apart, never having met this login**, are related: `:session where` —
   added here; `Member.where()` has no caller today — reports a proved base for each and an offset.
2. The two bridges **agree**: that offset is the difference between what `s:world():components(p)` answers
   for one durable Position asked of each session, and each character's own position translated into the
   other's frame lands on it.
3. A session whose base is **not proved** answers nothing rather than a stale number: `offset()` is
   `null`, its merged view leaves the scene, `s:world():components(p)` on it is `nil`. **This wins over 8**:
   nothing is drawn through an unproved base.
4. A **re-base is followed**: a cave or a house drops the offset to `null`, and what returns is derived
   from the new base and proved, never the kept one.
5. Two sessions in **different segments** are refused by name. `[manual]`: one character underground.
6. Two sessions on **different `MapFile` instances** are refused by name. `[manual]`: `chrmap`'s pref is
   read once at enter-world, so that session is re-added first.
7. `:session where` states, per session: the base (segment, tile coord), whether it is proved, the offset
   in grids, and which of 3/5/6 it refuses for.
8. Nothing regresses for two standing together, both proved: merged view, RTS order and selection as today.

## Out of scope

**The merged view's own edges** — a boundary, not a remainder. This feature makes the translation right;
what is drawn under it is a second, coherent feature: merged gobs sit in `MapView.clobjlist`, so a click
on another session's object sends its id through the anchor's `wdgmsg`; `Gobs.added` bypasses the frustum
cull, so a `SessionView` submits the member's whole `OCache`; and `boxvisible` takes its height
band from the anchor's own character, so a patch on a slope pops.

Also out: `Recall` keeps its own base and proof — it needs `mustrelease` ordering no other caller has,
and folding it in is a second feature's refactor riding on this one.

## Docs impact

Pages written: `docs/client/multi-session.md` (*Aligning two coordinate frames*, one `:session` row) ·
`minimap.md` (the proof beside the staleness gotcha) · `docs/addons/api/position.md` and `world.md` (a
place resolves through a **proved** base, and what `nil` now means). The first (178 lines) and the last
(303) are over their ceilings already; this feature replaces and qualifies rather than
grows, and splits neither (filed: 066).

Derived impact set — this surface's prose, grepped across `docs/`:

```bash
grep -rln "sessloc\|session location" docs/          # mapfile.md, minimap.md, network.md, README.md, state.md
grep -rln "shared grid\|unanchored\|no offset" docs/ # multi-session.md
grep -rn  "cannot locate\|out of reach\|locatable" docs/addons/
# position, world, player, vr/README, event/streams, map/grids, client/profiling/counters
```

Seven pages in the negative, each saying when a place answers `nil`: each checked against the new
refusal, only those whose sentence stops being true rewritten.

## Context files

- `src/io/brodgar/session/Sessions.java` — 1, 2 *(`tickoffset`, `findoffgc`, `buildplaced`, `where()`)*
- `src/io/brodgar/session/Recall.java` — 1 *(the base/prove idiom: `Base`, `sweep`)*
- `src/haven/MiniMap.java` — 1, 3 *(`sessloc`, `Location`, `SessionLocator`, `resolve`)*
- `src/haven/MapFile.java` — 1 *(`lock`, `Segment.gridid`, `load`'s memoization)*
- `src/haven/MCache.java` — 1, 2 *(`gridids`, `cmaps`, `trimall`)*
- `src/haven/AddonWidgets.java` — 1 *(`loadedGrids`)*
- `src/haven/MapView.java` — 2 *(`sessiontick2`, `SessionView`, `tick`'s grid request)*
- `src/io/brodgar/addon/MapApi.java` — 3 *(`sessloc(user)`, `gridUL`, `segGridUL`, `recordedGridId`)*
- `src/io/brodgar/addon/LuaPosition.java` — 3 *(`anchorAt`, `worldOf`, `ulOf`)*
- `src/haven/Client.java` — 1, 2 *(the `:session` console verb and its usage line)*
- `src/haven/GameUI.java` — 1 *(`mapfiletick`, `mapfilename`, `chrmap`)*
- `docs/client/multi-session.md` — 1, 2 *(1 writes the `:session where` row; 2 revises it and replaces
  *Aligning two coordinate frames*)*
- `docs/client/minimap.md` — 1
- `docs/client/mapfile.md` — 1
- `docs/client/console.md` — 1, 2, 3 *(how a `say` and a console refusal each reach the System channel —
  what a suite reading `session where` back out of `chat.system` rests on)*
- `docs/addons/api/position.md` — 3
- `docs/addons/api/world.md` — 3
- `docs/addons/api/chat.md` · `console.md` — 1, 2, 3 *(reading it back)*
- `DOCUMENTATION.md` — 1, 2, 3
