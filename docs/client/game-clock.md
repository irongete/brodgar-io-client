# The game clock and astronomy

> The one clock the whole world runs on: what the server sends, how the client converges its own copy on
> it, and the astronomy blob that carries the time of day, the moon and the season.

## Where it lives

| What | Where |
|---|---|
| The clock a caller reads | `Glob.globtime()` — game-world seconds, and the only public read. It answers the **client's** interpolated `gtime`, never the server figure directly |
| The three numbers behind it | `Glob.gtime` (the client's own clock), `sgtime` (the last figure the server sent) and `epoch` (the `Utils.rtime()` at which that figure arrived), plus the two rates `ctimefac` and `stimefac`, both starting at `itimefac` = `3.0` |
| Advancing it, every ctick | `Glob.tickgtime(now, dt)`, called from `Glob.ctick` before the object and map ticks |
| Taking a server figure | `Glob.updgtime(sgtime, inc)`, from the `"tm"` branch of `Glob.blob` |
| Reading the convergence live | `Glob.gtimestats()` — `gtime`, `sgtime`, `epoch`, the projected server time, the gap, and the two rates |
| Astronomy | `Glob.ast`, an `Astronomy` record built whole by the `"astro"` branch of `Glob.blob`: `dt` (the fraction through the day), `night`, `mp` (the moon phase), `yt` (the fraction through the year), `is`, `sp`, `sd`, `years`, `ym`, `md`, and the moon colour |
| The world's light | the `"light"` branch of `Glob.blob` writes `tlightamb`/`tlightdif`/`tlightspc` under the `Glob` monitor |

## How the clock converges

The server does not tick the client's clock; it publishes its own figure now and then and the client
chases it. `tickgtime` runs every ctick and does three things: it projects where the server's clock has
got to (`sgtime + (now - epoch) * stimefac`), advances its own by `dt * ctimefac`, and then nudges
`ctimefac` **towards** whichever side is behind — by at most `0.02 * dt`, and only while the two rates are
within 10% of each other, so the clock never jumps and never runs more than a tenth off real speed. A
final term pulls `ctimefac` back towards `stimefac` at `0.002 * dt`, which is what stops it from settling
at a permanent offset once the gap has closed.

`updgtime` is the other half. It records how long it has been since the last figure, and then either
**re-bases** or **learns**:

- **Re-base** — `gtime` and `sgtime` are both set to the server's figure outright, with no convergence at
  all, when this is the first figure (`sgtime == 0`), when the blob is not incremental (`inc` false), or
  when the figure is more than **500 seconds** from the last one. That last case is the one to know: a
  jump of any size lands instantly rather than being converged towards, so a reader that was
  interpolating sees the clock move by minutes in one tick.
- **Learn** — otherwise, and only when the figure advanced by more than a second, it folds the observed
  rate (`(sgtime - this.sgtime) / delta`) into `stimefac` with a weight of `min(delta * 0.01, 0.5)`. So
  the client's idea of how fast the server's clock runs is an exponential average over the updates, and
  it takes several of them to move.

`inc` is the first byte of the blob, so both branches of that decision come off the wire.

## Gotchas

- **`Astronomy.is` is a season index the engine never names.** `Cal` is its only reader, and it uses the
  index to pick one of four calendar textures (`gfx/hud/calendar/dayscape-<i>` and `nightscape-<i>`), so
  the domain is `0..3`. The `"astro"` branch defaults the field to `1` when the server omits it, which is
  a hint that 1 is the neutral season and not proof. Which index is which season appears nowhere in
  `src/haven`: read it off the calendar the client draws.
- **`Glob.ast` is null until the first `"astro"` blob lands**, which is a beat after login, while
  `globtime()` answers from the first tick. Any reader of the astronomy half guards for it.
- **The clock is one world's, not one session's.** Every session interpolates the same server clock, so
  asking a session that happens to hold the screen only adds a way to get nothing — ask any live `Glob`.
- **`ast` is replaced wholesale**, a new `Astronomy` per blob, so nothing may hold a field of it across a
  tick and expect it to move; hold the `Glob` and re-read.

## What is not mapped

The calendar window that draws all this (`Cal`), the seasonal art itself, the server's own clock, and the
rest of `Glob.blob`'s branches beyond `"tm"`, `"astro"` and `"light"`.

## See also

- [state roots](state.md) — `Glob` itself, and the rest of what hangs off it
- [networking](network.md) — where a blob arrives and on which thread
- [the 3D world](world-3d.md) — what the light fields feed
