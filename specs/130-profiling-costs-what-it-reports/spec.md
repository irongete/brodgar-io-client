# 130 — profiling costs what it reports

## What and why

`haven.Profile` keeps `public final Part[] hist`, a ring of the last 300 frames, and every `Part` holds
`public final Object nm` strongly beside a child list. `Widget.dispatch` and `Widget.draw` bracket **every
widget** with `CPUProfile.begin(wdg)`, so `nm` **is the Widget**. The ring holds every widget the client
touched in the last three hundred frames — about five seconds — plus a `Part` and an `ArrayList` each.

That structure ages into the old generation, where every reference it holds into the young generation is a
root each young collection must scan and copy. Measured live, across the switch being turned off, in one
uninterrupted GC log:

| | armed | disarmed |
|---|---|---|
| young pause, mean | **68.0 ms** | **9.2 ms** |
| young pause, max | 93.5 ms | 15.1 ms |
| survivor regions | 40 (320 MB) | 6 (48 MB) |
| live after collection | 1717 MB | 1218 MB |
| interval between collections | 3.9 s | 3.8 s |
| wall time stopped | 1.74 % | 0.24 % |

The interval does not move, so this is not garbage — it is **retention**: 499 MB held, and a pause 7.4×
longer, landing as a hitch every few seconds in picture and sound together, because a safepoint stops
every thread.

**Nothing on the addon surface needs that ring.** `p:history()` is served from `io.brodgar.prof.Prof`,
whose history is flat primitive arrays — `long[] rfno`, `double[] rms` — six hundred frames of numbers
retaining nothing. `Prof.frame` reads `uprof.last()` and `rprof.last()`: one frame, not
three hundred. The only reader of the ring itself is `haven.Profdisp`, the bar graph backtick opens.

**And `overhead()` cannot see any of it.** Its measured method is the median work time of armed frames
minus a control frame's. A control frame disarms the probes and leaves the ring exactly as retained, so
the collection cost stands on both sides of that subtraction and cancels to zero. The surface reports
`withinBudget` against 5 % of frame time while excluding its dominant cost.

## Acceptance criteria

1. With profiling armed in a loaded world, the `gcMs` delta per armed frame that `memory()` reports is
   within a small multiple of the same delta measured with profiling off.
2. A widget destroyed while profiling is armed is held by the profile ring for a handful of frames rather
   than three hundred, so it is collectable within a frame or two of its own retirement.
3. The backtick windows still open, still draw and still dump the frame that is clicked, over the shorter
   ring: a narrower graph, never a broken one.
4. `p:frame()`, `p:history(n)`, `p:widgets()`, `p:passes()`, `p:addons()` and `p:gl()` answer as they do
   now, and `history()` still holds several hundred frames.
5. `overhead()` names how many frames the client's own trees retain, so a later change that grows them is
   visible on the surface that claims a budget.
6. The page states which costs the control-frame method can and cannot see, so no reader is told a
   subtraction covers a cost standing on both its sides.

## Out of scope

- **Allocation profiling** — `ROADMAP.md` line 67, *"who costs GARBAGE, bracketing the seams 019 already
  brackets"*. A capability this feature does not add: 130 makes the figures that already ship honest and
  cheap; a per-seam allocation tier is a surface of its own.
- **The per-frame cost of the drawing and projection verbs** — `g:line` minting a `Line2d` and a state
  change per call, `g:poly` a `VertexArray` per call, `world:worldToScreen` a Lua table per point. A
  different surface: those verbs stand whole on their own, where this feature is what the profiler holds.
- **Whether the persisted `profiling` preference can disagree with the live switch.** Seen once, not
  reproduced, about the switch rather than what the ring keeps.

## Docs impact

`docs/addons/api/client/profiling/attribution.md` — the `overhead()` table gains the retention figure, and
the *two numbers* passage gains what the control-frame method cannot see. No other page changes: the ring
this shrinks is not the ring `history()` names.

Derived impact set — every prose mention of the ring, the budget and the method:

```text
$ grep -rn "several hundred frames\|history ring\|5% of frame\|control frame\|the ring" docs/
attribution.md:211  the ceiling this surface holds itself to, 5% of frame time…
attribution.md:237  the median **work** time of its armed frames minus its control frame…
README.md:70        frame time over the whole history ring
README.md:98        The ring holds several hundred frames, a handful of seconds…
README.md:120       reset() empties the ring and every per-addon…
character.md:84, world.md:165, flowermenu.md (14)  — a petal ring or a laid shape; no impact
```

The three in `README.md` stay true, discharged with that reason: they name `Prof`'s six-hundred-frame
flat history, untouched here.

## Context files

- `src/haven/UILoop.java` — 1, 2 (`histlen` and the three rings built from it, `profwire`, and the one `last()` read in `framedone`; 2 reads `histlen` for `ringFrames`)
- `src/haven/Profile.java` — 1 (read only: `hist`, `add`, `last`, and `Part.nm`, which is the Widget)
- `src/haven/Profdisp.java` — 1 (read only: the only reader of `hist`, sized from `hist.length` throughout)
- `src/io/brodgar/prof/Prof.java` — 1, 2 (the flat arrays, `CAP`, `frame`, `pgf`/`PENDING`)
- `src/io/brodgar/prof/Overhead.java` — 2 (`PERIOD`, `calibrate`, the control-frame comparison)
- `src/io/brodgar/addon/ProfHandle.java` — 2 (`overhead()` at :1102, where the snapshot is built)
- `docs/client/boot-and-loop.md` — 1 (the profile subsystem table and its gotchas; 130.1 writes the ring length, the `Profdisp` floor and the backtick wiring into it)
- `docs/addons/api/client/profiling/attribution.md` — 2 (the page this feature writes)
- `docs/addons/api/client/profiling/README.md` — 1, 2 (the armed-only rule and the empty-table-when-off
  contract, which 2's suite asserts of `ringFrames` directly)
- `docs/addons/api/client/README.md` — 1, 2 (`options():client():profiling(b)` and the `client.settings`
  a suite must declare to arm profiling at all)
- `DOCUMENTATION.md` — 2
