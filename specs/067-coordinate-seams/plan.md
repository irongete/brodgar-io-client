# 067 — Coordinate seams: plan

## Approach

**One rule, applied at the three places that break it: a coordinate crossing into Lua is converted at
the crossing, and the class that converts is `Px`.** Its own javadoc already says a crossing that skips
it is the bug it exists to make visible, and `UiApi.paintGobOverlays` already obeys it for the gob
overlay's projected point (`Px.out(sc)`, tagged `058.2`). This feature finishes that sweep rather than
inventing a convention.

### 1. The projection seam

`Px` gains `out(double)` — device → design, unrounded, the mirror of the existing `in(double)`; the
class has `out(int)` and `out(Coord)` only, and a projected point has no pixel to round to.

`CharApi.worldToScreen` takes `MapView.screenxf`'s `Coord3f`, which is **MapView-local device pixels**,
and answers **root design pixels**: add the view's own `Widget.rootpos()`, then `Px.out` each axis.
`WorldApi.screenToWorld` is the inverse door and takes the same space: `Px.in` the pair and subtract the
view's `rootpos()` before handing it to `MapView.Maptest`. Root rather than view-local because that is
the space `hafen.ui():at()`, the mouse, `widget:rootPos()` and a HUD overlay's painter already share, and
because view-local forces every caller to re-add the offset — the workaround this deletes.

`AddonManager.xy(double, double)` keeps converting nothing. It is a table builder, not a seam; the
conversion belongs at the call site, which is what makes the seam greppable.

### 2. The typed readers

`LuaEvent` gains a private `coordArg(LuaValue i, String verb)` — the `Coord` at 1-based argument `i`, or
a `LuaError` naming what is actually there and naming the other reader. Two verbs go into `common()`,
which serves exactly the two shapes that carry raw arguments (ACTION and MESSAGE; every other shape has
its own builder and no `:args()`):

- `position(i)` → `LuaPosition.of(owner, Coord2d.of(c.x, c.y).mul(OCache.posres))`, the exact inverse of
  the `mc.floor(posres)` every map message is built with.
- `pixel(i)` → `Px.out` each axis, in the **sender's own** design pixels — the space `ev:x()`/`ev:y()`
  already speak on `MouseDown`, so a press and a message agree about the same widget.

`ev:args()` is untouched: it stays the faithful snapshot `ev:resend()`/`ev:send(t)` round-trip through.

### 3. The write half

`LuaMarshal.toJava`'s `TUSERDATA` branch resolves a `LuaPosition` and encodes it as
`rc.floor(OCache.posres)`, refusing one this session cannot locate. It is unambiguous because a Position
always means a place and never a screen pixel — which is exactly the property a `{x=,y=}` table lacks.
The `TTABLE` branch keeps accepting `{x=,y=}`, so hand-built argument tables still work.

## Files to create/modify

| File | What |
|---|---|
| `src/io/brodgar/addon/Px.java` | `out(double)` |
| `src/io/brodgar/addon/CharApi.java` | `worldToScreen` → root design pixels |
| `src/io/brodgar/addon/WorldApi.java` | `screenToWorld` → takes root design pixels |
| `src/io/brodgar/addon/LuaEvent.java` | `coordArg`, `position(i)`, `pixel(i)`; the ACTION/MESSAGE vocabulary strings |
| `src/io/brodgar/addon/LuaMarshal.java` | `toJava` accepts a `LuaPosition` |
| `docs/addons/api/player.md` | the `worldToScreen` row and its paragraph |
| `docs/addons/api/world.md` | the `screenToWorld` row and the "screen to world" section |
| `docs/addons/api/ui/mouse.md` | the grab example, correct only after this |
| `docs/addons/api/event.md` | `:args()` is raw protocol in wire units; the two readers; `click` is not the map's alone |
| `docs/addons/api/ui/pixels.md` | the projection verbs join the "one space" list |
| `docs/client/world-3d.md` | the space `screenxf` and `Maptest` speak |
| `addons/067-coordinate-seams.{1,2,3,4}/` | the four suites |

## Risks and gotchas

- **`Coord2d.floor(Coord2d f)` is `floor(x / f)`**, so the inverse is `mul(posres)` and the floor
  truncates below one wire unit — `11/1024` of a world unit. The round-trip criterion is "within a tile"
  for that reason, not exactness.
- **`UI.scalef` is clamped to `>= 1.0`**, so `Px.out(Px.in(n)) == n` exactly, but `in(out(d))` can land a
  device pixel away — `docs/client/ui-scaling.md` states this. A screen→world→screen trip is therefore
  not the identity, and the suite asserts the world→screen→world direction, which is.
- **`MapView.screenxf` can throw or answer null** for a point the view cannot project; the existing
  `try`/`catch(RuntimeException)` around it stays. `docs/client/world-3d.md` records that the underlying
  `HomoCoord4f.toview` divides unguarded and that a point behind the eye answers a plausible wrong number.
- **`MapView.Maptest.hit(Coord pc, Coord2d mc)` hands back world units already** — `posres` is not in that
  path. Do not convert it.
- **`AddonManager.dispatchAction` has a re-entrancy guard** (`dispatchingAction`), so a `wdgmsg` sent from
  inside a handler passes through rather than recursing.
- **`Retired`**: every replaced spelling must throw naming its replacement. Nothing is renamed here, but
  the two new verbs join the ACTION/MESSAGE vocabulary strings that `Retired.closedIndex` prints.

## Discarded alternatives

- **A `WireCoord` opaque type in `ev:args()`, answering `:position()`/`:pixel()`.** It makes the wrong
  read impossible rather than merely available, which is strictly safer — but it is a public type whose
  whole meaning is "the client does not know what this is", exported to every addon author to protect two
  doors of eleven event shapes. It also breaks `hafen.json():encode(ev:args())` and any existing read of
  `ev:args()[i].x`. Paid for with one documentation line instead.
- **A per-message argument schema**, so `ev:args()` hands back a Position at the right index by itself.
  The action/message key set is open *because* a message name is protocol the server owns; a table of
  argument meanings is that closed catalogue returning, keyed on (sender class, message) since `click`
  has four shapes across `MapView`, `Avaview`, `ISBox` and `Partyview` — and a drifted entry converts a
  number that then looks right, which is the silent-wrongness this feature exists to delete.
- **Leaving `worldToScreen` in device pixels and documenting it.** A sibling crossing already converts,
  so this keeps two conventions for one question and leaves `mouse.md`'s grab example wrong.
- **Answering MapView-local pixels rather than root.** Every consumer re-adds `rootPos()` — the
  workaround being deleted.
- **A `ScreenPoint` type.** A screen point has one form once the seam is right; the type would touch
  `widget:position`, `:size`, `:rootPos`, the mouse, every `g:` verb and every stylesheet number to
  protect against an ambiguity that no longer exists.
- **Converting inside `ev:args()`.** It cannot tell `pc` from `mc` — both are `Coord` — and it breaks the
  round-trip `ev:resend()` depends on.
- **A `hafen.utils()` namespace of conversions.** A section is named after the thing it changes; a
  grab-bag collects whatever arrives next, and a published converter makes the mismatch permanent API
  instead of removing it.
