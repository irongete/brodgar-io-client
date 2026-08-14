# UI scaling

One number decides how big the whole 2D interface is drawn. Everything the client lays out is written at a
**design** size and multiplied by it once, so the source reads in the art's own pixels and the screen gets
device pixels.

The number is `UI.scalef` — `private static final double`, assigned in `UI`'s static initialiser from
`UI.loadscale()`. **It is read once, at class load, and never changes for the life of the process**, which
is why the Options panel's own slider is labelled as requiring a restart.

## Converting

| Where | What |
|---|---|
| Design → device | `UI.scale(double)` · `scale(float)` · `scale(int)` · `scale(Coord)` · `scale(int, int)` · `scale(Coord2d)` |
| Device → design | `UI.unscale(double)` · `unscale(float)` · `unscale(int)` · `unscale(Coord)` |
| Round a fraction | `UI.rscale(double)` · `rscale(double, double)` — `Math.round(v * scalef)`, for a factor that is not a whole design pixel |
| A font | `UI.scale(Font, float)` → `Font.deriveFont(scale(size))`; see [text and fonts](text-and-fonts.md) |
| A texture | `UI.scale(Tex)` → a `ScaledTex` wrapping it at `UI.scale(tex.sz())` — a **view**, disposing the impl; `scale(ScaledTex)` answers the same object, so the call is idempotent and cannot double-scale. See [render-gl.md](render-gl.md)'s blit path |
| An image raster | `PUtils.uiscale(BufferedImage, Coord)` — the resampler everything above is built on |

`scale(int)`/`unscale(int)` round through `float`; `Coord.mul(double)`/`Coord.div(double)` round each axis,
which is what `scale(Coord)`/`unscale(Coord)` are.

## Where the number comes from

| Step | Member |
|---|---|
| The factor in force | `UI.scalef`, via `UI.loadscale()` |
| An override, before any preference | `UI.uiscale`, a `Config.Variable` over the `haven.uiscale` property |
| The stored preference | `Utils.getprefd("uiscale", defscale)`, clamped to `[1.0, UI.maxscale()]` |
| Where the user sets it | `OptWnd`'s Interface panel — an `HSlider` whose `changed()` calls `Utils.setprefd("uiscale", …)` and nothing else |
| The display-derived bounds | `UI.initscale()` fills `maxscale` (from the largest display's resolution against 800×600, floored at 1.25) and `defscale` (from `dev.density()`, capped by `maxscale`); `UI.maxscale()` is the public reader |

**The preference and the factor are two different numbers.** `Utils.getprefd("uiscale", 1.0)` — what the
Options panel reads back — is `1.0` on a fresh install, while `loadscale` starts from the density-derived
`defscale` and clamps; they agree only once the user has moved the slider, and only until the next launch.

## Art is scaled when it is loaded, not when it is drawn

`Resource.Image` does the conversion once, at load:

| Member | What |
|---|---|
| `sz` | the raw image's own pixel size |
| `scale` | the factor **the resource declares** (a `scale` key in the image layer, default `1`) — the client's button art declares `4` |
| `ssz` | `round(UI.scale(sz / scale))` — the device size, and the size everything measures |
| `scaled()` | the resampled `BufferedImage` at `ssz`; `Resource.loadsimg(name)` is `loadrimg(name).scaled()` |
| `tex()` | a `TexI` over `scaled()` — **already device-sized**. `rawtex()` is the unscaled one |
| `o` / `so` | the declared offset, and the same offset scaled |

**`tex()` and `rawtex()` are memoised on the layer; a `TexI` a SITE builds is not.** A handful of statics wrap
`loadsimg` in a `TexI` of their own rather than calling `loadtex` — `Window.cm`, so `DefaultDeco.checkhit` can
sample `TexI.back`'s alpha raster, and `HSlider.schain`, which transposes the pixels first. Those are second
objects over (or beside) the layer's own, so anything keyed on the texture identity misses them and has to ask
about `TexI.back` instead.

So a widget that measures its own art is already in device pixels, and scaling it again doubles it. The
design size of a piece of art is `sz / scale` — a whole number the artist chose, and the same integer at
every UI scale.

## The scaled constants a layout meets

| Where | Member |
|---|---|
| A plain button's height | `Button.hs` (`bl.getHeight()`) and `Button.hl` (`bm.getHeight()`) — device, from the art above; `Button.largep(int)` picks between them by width, `Button.margin` is `UI.scale(10)` |
| A window's chrome | `Window.dlmrgn` / `Window.dsmrgn`, both `UI.scale`d; `Window.DefaultDeco.iresize` adds `mrgn` twice plus the deco's own `tlm`/`brm` to the content size |
| A text field's height | `TextEntry`'s ctor — `TextEntry.bgheight()`, whose stock answer is `mext.sz().y`; `toffx`/`wmarg` (`lcap` + `rcap` + `UI.scale(1)`) are what its end caps take horizontally |
| A checkbox's box | `CheckBox.sbox`/`lbox` (with `smark`/`lmark`); the ctor's height is `max(box.sz().y, lbl.sz().y)` once it has a caption, and `settext` recomputes it |
| A slider's height | `HSlider`'s ctor — `sflarp.sz().y` (the thumb); `HSlider.resize(int)` takes a width and keeps that height |
| A horizontal rule's height | `HRuler`'s ctor — `(marg.y * 2) + 1`, `marg` defaulting to `(w / 10, UI.scale(2))`. **The one constant here that is not the same design number at every scale**: it is built from a scaled term plus `1`, not from art |
| A dropdown's closed box | `SDropBox`'s ctor — `Coord.of(w, itemh)`, with the drop arrow (`dropimg`, via `makedrop`) added at `1.0, 0.5` on the right edge, so a taller arrow overhangs the row rather than growing it |
| Text | `Text.Foundry` sizes pass through `UI.scale(float)` — see [text and fonts](text-and-fonts.md) |

## Gotchas

- **`scalef` is never below `1.0`** (`loadscale` clamps), so `unscale(scale(n)) == n` exactly, for every
  `n`: the rounding error of `scale` is at most `0.5` while a design pixel is at least `1.0` device wide.
  **The inverse does not hold** — `scale(unscale(d))` can land a device pixel away, because an arbitrary
  device coordinate is not a whole number of design pixels.
- **`Coord.div(int)` and `Coord.div(Coord)` FLOOR** (`Utils.floordiv`) while `Coord.div(double)` rounds.
  `UI.unscale(Coord)` is the rounding one; anything hand-rolled with an integer divisor drifts downward.
- **Touching `UI` needs a display.** Any `UI.scale` call runs the static initialiser, and `initscale()` asks
  `iosys` for the display list — which throws `iosys.Unavailable` with no toolkit. Set the `haven.uiscale`
  property (`-Dhaven.uiscale=1.5`) and `loadscale` returns before `initscale` is ever reached, which is what
  makes `UI`, `Button` and the rest loadable from `jshell` for a headless check.
- **A resource's declared `scale` is not the UI scale**, and the two multiply. Art declaring `scale = 4`
  drawn on a client at `1.5` is resampled to `sz * 1.5 / 4`.

## See also

- [text and fonts](text-and-fonts.md) — the font half, and the foundries that bake a size
- [chrome](ui-chrome.md) — `Window.deco` and the `Deco` contract the margins above belong to
- [the widget system](widgets.md) — `Widget.c` / `sz` / `resize`, the fields these numbers land in
