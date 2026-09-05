# 130 — plan

## Approach

### The ring is two lines in one file

`UILoop.java:43-44` builds all three:

```java
public final CPUProfile uprof = new CPUProfile(300), rprof = new CPUProfile(300);
public final GPUProfile gprof = new GPUProfile(300);
```

Nothing else in the tree constructs one. Those three constants **are** the retention: three hundred frames
of `Part` trees, and every `Part` holds `public final Object nm` strongly — which, for the tier
`Widget.dispatch` and `Widget.draw` bracket, is the Widget itself. They come down to a handful of frames,
tagged `// addon:` with the measurement as the reason, and that is the whole of the fix.

### What the ring actually has to hold

Two readers, and they are the entire set:

- **`UILoop.java:586`** — `Prof.frame(…, uprof.last(), rprof.last(), f.gprof, …)`. `last()` is **one
  frame**, folded immediately into `Prof`'s flat primitive arrays and never held as objects.
- **`haven.Profdisp`** — the bar graph `Profwnd` draws, which takes `prof.hist.length` at every use: the
  window size, the texture width, both draw loops, the click hit-test and the `dump` drill-down.

Nothing reads `hist` for correctness. The GPU side does not depend on it either: timestamps arrive several
frames late through `GPUProfile.waiting`, and `Prof` holds the frames it is still waiting on itself, in
`pgf` (`PENDING = 64`), never through the ring.

### The graph gets shorter, and that is the trade taken

`Profdisp` sizes everything from `hist.length`, so a short ring yields a narrow graph rather than a broken
one — the windows still open on backtick, still draw, still dump the frame that is clicked. The maintainer
does not use them, and `p:history()` — six hundred frames of numbers in `Prof`, retaining nothing — is what
replaced them. It is untouched, and `README.md`'s "several hundred frames" goes on describing it.

### Not one frame, because the render profile is written off-thread

`rprof` is added to from `UILoop.RenderProfile.run()`, which runs on the render callback thread, while
`UILoop.java:586` reads `rprof.last()` on the UI thread. That race is there today and this feature neither
introduces nor fixes it — but a ring of **one** turns a window that is merely wide into a certainty, because
the single slot being written is the same slot being read. A handful of slots keeps the margin the code has
always had. Sixteen: two orders of magnitude off the retention, and still more slack than the one frame the
reader needs.

### `overhead()` says what its method cannot see

The measured figure is the median work time of armed frames minus a control frame's. A control frame
disarms the probes and leaves the ring exactly as retained, so anything the profiler *holds* stands on both
sides of that subtraction and cancels to zero. That is a property of subtraction rather than a defect in
the arithmetic, and the page has to say it — otherwise the next cost that lives in retention hides exactly
the way this one did, behind a `withinBudget` that reads true.

`overhead()` gains one key, `ringFrames`: how many frames the client's own trees retain. One number on the
surface that claims a budget, so a later change that grows the ring is visible rather than inferred.

## Files to create or modify

| File | What |
|---|---|
| `src/haven/UILoop.java` | the three ring lengths at :43-44, `// addon:` with the measurement as the reason |
| `src/io/brodgar/addon/ProfHandle.java` | `ringFrames` into the `overhead()` snapshot at :1102 |
| `docs/addons/api/client/profiling/attribution.md` | the `overhead()` key, and what the control method cannot see |
| `addons/130-profiling-costs-what-it-reports.1`, `.2` | one suite per task |

`docs/client/boot-and-loop.md` needs no edit: its profile table already names the ring, `Profdisp`, and the
arming flag. Its "300-frame ring" wording is the one line the first task refreshes in passing, since the
number is the thing that changed.

## Risks and gotchas

- **`Profile.add` wraps with `if(++i >= hist.length) i = 0`, and `last()` reads `hist[i-1]` or
  `hist[hist.length-1]`.** Both are correct at any length ≥ 1; nothing assumes a minimum.
- **`Profdisp` builds a `Texture2D(prof.hist.length, h, …)`.** A very small width is a legal texture but an
  untested one — sixteen keeps it ordinary. The window's own size is `hist.length + UI.scale(50)`, so the
  chrome does not collapse either.
- **`Profdisp.click` hit-tests `c.x < prof.hist.length`** and indexes `hist[x]`; a short ring narrows the
  clickable strip and nothing else.
- **The GPU frame arrives late.** `p:frame()` documents `gpuMs` trailing `frameno` by several frames. That
  path runs through `Prof.pgf`, not `hist`, so shortening the ring must not change it — and the suite reads
  `gpuMs` back to prove it did not.
- **`Prof.frame` is called from `UILoop.framedone`, after `CPUProfile.end` closed the frame.**
  `boot-and-loop.md` records that work done there lands in no phase; that stays true and is not this
  feature's to change.
- **Arming is per frame, read once in the `UILoop.Frame` constructor.** Nothing here changes when the
  decision is made, only how much of the answer is kept.

## Discarded alternatives

- **Folding each frame to numbers and names inside `Profile`, keeping the 300-bar graph** — it preserves a
  window the maintainer does not open, at the cost of a structural change to three upstream classes; the
  ring length reaches the same measured outcome by moving two constants.
- **Storing `Part.nm` as a `String` instead of the object** — it stops the ring pinning widgets but leaves
  three hundred frames of `Part`s and their child lists as an old-generation root set, and adds a
  `toString()` per widget per frame on the armed frame path.
- **A ring of one** — the render profile is written on the render callback thread and read on the UI
  thread, so the only slot would be the slot under the writer.
- **Dropping `Profdisp` and the backtick windows** — they cost nothing while closed, and deleting a
  debugging surface is not what a measurement of its ring authorises.
- **Making `overhead()`'s measured method see retention** — it would have to release the ring for a control
  *period* rather than a control frame, which destroys the history the maintainer is looking at, to measure
  a cost this feature has just made small. Naming the blind spot is the honest half.
- **Leaving `overhead()` alone once the ring is short** — the budget number would still be silent about
  retention, and the next ring to grow would hide behind it exactly as this one did.
