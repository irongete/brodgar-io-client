# V3 — Look & orientation (`:rotate` / `:setRes` / `:alpha` / `:tint` / `:show` / `:hide`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **33 headless checks** (the
> `GhostGob.obstate` render-state gating via a `BufPipe` — clickable → a `GobClick`, `alpha < 1` → `BaseColor`
> + alpha-blend, `tint` → a `MixColor`, and the `alpha == 1` boundary → no blend, plus all-three composed; and
> the pure option/arg parsers `clampAlpha`/`luaAlpha`/`luaTint`/`luaSdt` — same-package for the protected
> `obstate`, reflection for the private statics, the documented headless pattern) + LuaJ parse of `hello`.
> **Zero new core edit** (only `io.brodgar.addon`, reusing the V1 `addClientGob`/`removeClientGob` seam).
> **Java engine change ⇒ `ant` rebuild + a full client restart before the in-game test.** **In-game DoD pending.**
> **Design:** [specs/addons/16-virtual-entities.md](../../../specs/addons/16-virtual-entities.md) (§3 the handle
> table; §5 "Tint / alpha" / "Scale" seams), decisions **D-030** (handle, flat verbs) / **D-029** (client-only,
> safe-tier) / **D-013** (one canonical flat verb per operation).

V3 is the third slice of the **V-series**: it gives a [V1](v1-ghosts.md)/[V2](v2-clickable-ghosts.md) ghost a
**look and an orientation** — a live facing (`:rotate`), a swappable visual (`:setRes`), the translucent "ghost"
appearance (`:alpha` / `:tint`), and scene visibility toggling (`:show` / `:hide`) — plus the matching
`new{...}` options (`a`, `sdt`, `alpha`, `tint`). Everything stays **client-only / SAFE-tier**: these are render
states on a virtual gob, nothing reaches the server. Scale + the gizmo are still later (V5/V6).

## The API

```lua
local g = hafen.ghost.new{
  res   = "gfx/terobjs/arch/logcabin", x = wx, y = wy,
  a     = math.pi/4,                    -- facing radians (V1 already; default 0)
  sdt   = { 0x01, 0x00 },               -- V3: optional spawn-data bytes (resource variant/state); rarely needed
  alpha = 0.5,                          -- V3: opacity 0..1 (1 = opaque); < 1 = the translucent look
  tint  = { r = 120, g = 180, b = 255, a = 110 },  -- V3: colour overlay 0..255 (a = blend strength, default 255)
}
g:rotate(math.pi)                       -- V3: set facing, keeping position
g:setRes("gfx/terobjs/arch/timberhouse")-- V3: swap the visual (streams in like new; optional 2nd arg = sdt)
g:alpha(0.3)                            -- V3: opacity 0..1
g:tint{ r = 255, g = 80, b = 80 }       -- V3: colour overlay ({...} or nil to clear)
g:hide(); g:show()                      -- V3: remove / (re)add the scene slot (keeps the ghost)
```

Every verb returns the handle, so calls chain: `g:setRes(res):rotate(a):alpha(0.4)`.

### Colour shape — named keys, reusing `luaColor` (not the spec's positional example)

The design sketch in the spec wrote `tint = {80,160,255,180}` (positional), but the **canonical colour form
already shipped** in this client is **named keys** `{r=, g=, b=[, a=]}` (0..255) — [`hafen.markers`](../api/markers.md)
add-opts (`luaColor`), and the `{r,g,b,a}` returned by `hafen.party`/`hafen.kin`. Per the "one canonical way"
rule, `tint` uses the **same named-key shape and the same `luaColor` parser** (with `a` defaulting to 255), so
there is exactly one colour convention across the whole API. The spec example was illustrative; the shipped
surface follows the established form.

## Mechanism 1 — the look states (`GhostGob.obstate`)

V2 already established that a ghost's render state is set in the `protected obstate(Pipe buf)` hook that
`Gob.GobState.apply` calls for **every** gob (the one path `virtual` does not gate). V3 extends that same hook to
prep the look states, using the engine's own primitives:

```java
protected void obstate(Pipe buf) {
    if(clickable)
        buf.prep(new Gob.GobClick(this));           // V2 — the pick surface
    Color tc = this.tint;                            // snapshot the volatile once
    if(tc != null)
        buf.prep(new MixColor(tc));                  // V3 tint — colour overlay (blend strength = tc alpha)
    float al = this.alpha;
    if(al < 1f) {                                    // V3 translucency
        buf.prep(new BaseColor(1f, 1f, 1f, al));     //   multiply the fragment alpha
        buf.prep(FragColor.blend(new BlendMode()));  //   standard SRC_ALPHA / INV_SRC_ALPHA blending
        buf.prep(States.maskdepth);                  //   don't write depth (the engine's translucent-overlay recipe)
    }
}
```

- **`tint` → `MixColor`.** This is the *exact* state `GobHealth` uses for the red damage tint — it blends the
  colour into the object's fragments, the colour's alpha being the blend strength. It is a colour overlay only; it
  does **not** make the object see-through. `tint = nil` clears it.
- **`alpha < 1` → `BaseColor` + blend + `maskdepth`.** `BaseColor(1,1,1,α)` multiplies the fragment alpha,
  `FragColor.blend(new BlendMode())` turns on standard alpha blending, and `States.maskdepth` stops the ghost
  writing depth — the same three-part recipe the client uses for its own translucent overlays (the tile-grid
  overlay `gridmat` and the drag-select rectangle in `MapView`). `alpha == 1` preps nothing → a normal opaque prop.

> **Translucency caveat.** With `maskdepth` the ghost does not write depth, so it never occludes things drawn
> after it (correct for a translucent overlay) but also does **not self-occlude** — you can see its far faces
> through its near ones, the usual "x-ray/hologram" look for a see-through 3D object. That is the intended ghost
> aesthetic; a fully solid-but-tinted prop is available via `tint` with `alpha = 1`.

## Mechanism 2 — applying a *live* look/orientation change

`GobState.equals` compares only the `SetupMod` mods, **not** `obstate`'s output (same reason V2's `clickable`
toggle can't ride the normal `updated()` → `updstate()` path). So a live `:alpha` / `:tint` / `:clickable` change
is applied the way V2 already toggles clickability — **remove + re-add the scene slot**, factored here into one
helper:

```java
private static void refreshGhostScene(LuaGhost gh) {           // caller holds the ghost monitor
    if((gh.gob != null) && (gh.mv != null) && (gh.slot != null)) {
        gh.mv.removeClientGob(gh.gob, gh.slot);
        gh.slot = gh.mv.addClientGob(gh.gob);                  // on the re-add, obstate runs fresh (reads the new fields)
        gh.gob.move(gh.rc, gh.a);                              // re-assert position/facing
    }
}
```

`setGhostClickable` (refactored onto it), `setGhostAlpha`, and `setGhostTint` all mirror the desired value onto the
`GhostGob`'s `volatile` field and call `refreshGhostScene`. **`:rotate`** is not a state change at all — it is just
`Gob.move(rc, a)` keeping position, picked up by the render tree's own `Placed.autotick` next frame (like `:move`).

## Mechanism 3 — `:setRes` (deferred res-swap) and `:show`/`:hide`

- **`:setRes(res[,sdt])`** records the new desired `res`/`sdt` on the `LuaGhost` (so a swap that lands *before* the
  prop has streamed in is honoured by the deferred create) and, if the gob is already live, **defers** building the
  new `ResDrawable` — `res.get()` throws `Loading` until cached, the very reason `new` defers — then `setattr`s it
  on the gob. The `setattr` runs under **`synchronized(gob)`**, the exact lock the engine's own live res-swap holds
  (`ResDrawable.$cres.apply` runs inside `OCache.GobInfo.apply`'s `synchronized(gob)`; the gob's `slots` is a plain
  `ArrayList` shared with the ghost `ctick` path). A newer `:setRes` that swapped `gh.res` meanwhile **wins** — the
  stale task sees `gh.res != rid` and drops its drawable.
- **`:hide()` / `:show()`** drop / re-add the scene slot (via the V1 `removeClientGob`/`addClientGob` seam) but
  **keep** the gob, so the ghost is reusable. A `hidden` flag is honoured by the deferred create (a ghost hidden
  before it published stays out of the scene) and by `destroy` (a hidden ghost's gob is still disposed — no leak).

## Desired-state honoured at publish time

The deferred create now reads the ghost's **current** `res`/`sdt` (fresh, under the ghost lock) rather than the
resource captured at `new`, and applies `alpha`/`tint`/`clickable`/`hidden` before the first `addClientGob`. So any
verb called in the window between `new` and the prop streaming in — `:setRes`, `:alpha`, `:tint`, `:hide`,
`:rotate` — takes effect the moment the ghost appears, with no visible flip.

## Threading & lifecycle

- **UI-thread verbs, one loader task.** All handle verbs run on the UI thread; only the `:setRes` drawable build
  (like the create) runs on a loader thread. The ghost monitor guards the desired-state fields and the
  publish/swap so a loader task never races a concurrent `:move`/`:destroy`/`:setRes`. `:setRes`'s `setattr` nests
  `synchronized(gob)` inside `synchronized(gh)`; nothing takes `gob` → `gh`, so the order is safe.
- **Teardown (P2) unchanged.** Ghosts remain bridge-owned in `Addon.ghosts`; `destroyGhost` un-adds + disposes,
  and a hidden ghost is destroyed cleanly too. No new owned-resource list.

## Files changed

- **`src/io/brodgar/addon/GhostGob.java`** — `alpha`/`tint` `volatile` fields + `obstate` now preps the V3 look
  states (`MixColor` tint; `BaseColor` + alpha-blend + `maskdepth` for `alpha < 1`) alongside the V2 `GobClick`.
- **`src/io/brodgar/addon/LuaGhost.java`** — desired-state fields `alpha`/`tint`/`hidden`/`sdt`; `res`/`resName`
  made non-final (`:setRes` swaps them).
- **`src/io/brodgar/addon/AddonManager.java`** — `newGhost` parses `alpha`/`tint`/`sdt` and the deferred create
  reads them (and `hidden`) at publish time; `ghostHandle` gains `:rotate`/`:setRes`/`:alpha`/`:tint`/`:show`/
  `:hide`; new `setGhostAlpha`/`setGhostTint`/`hideGhost`/`showGhost`/`setGhostRes`/`refreshGhostScene` (the last
  shared with the refactored `setGhostClickable`) + parsers `luaAlpha`/`clampAlpha`/`luaTint`/`luaSdt`. Reuses the
  existing `luaColor` (named-key colour parser). **No `haven` edit.**
- **`addons/hello/`** — **v0.37.0**: the OnEnterWorld auto-demo cabin now spawns **rotated 45° + translucent +
  tinted** (the V3 look), then at +3s **live-swaps** its resource + `:rotate`s (the res-swap DoD), `:hide`s (+5s) /
  `:show`s (+6s), and auto-destroys (+8s); `:hello ghost` toggles a **clickable translucent** cabin whose `onClick`
  live-cycles `:rotate` + `:alpha` (V2 click driving V3 look).

## Try it in-game (V3 DoD)

Rebuild (`ant hafen-client`) and **fully restart** the client (a Java engine change — the JVM does not hot-reload
classes). Then, in the world:

1. On login the harness spawns a **rotated, translucent, bluish log cabin** ~3 tiles east — the V3 look (DoD part 1).
2. It jumps 2 tiles north (`:move`, the V1 regression), then at +3s **morphs into a different building** and spins
   180° (`:setRes` + `:rotate` — live res-swap, DoD part 2), blinks off/on at +5/6s (`:hide`/`:show`), and vanishes
   at +8s (`:destroy`).
3. `:hello ghost` drops a **clickable translucent** cabin at you; **click it** — it does not walk you (V2), and each
   click rotates it 45° and toggles its opacity (V3 `:rotate` + `:alpha`, live). `:hello ghost` again removes it.
4. `:reload` / disabling `hello` removes every ghost cleanly (no leak).

## Deferred

- **`:scale(s)`** — the least-native piece (a scaling `Location`/`Pipe.Op` on `placed`) → **V6**, with the gizmo.
- **Per-face / animated translucency, custom blend modes** — the single `alpha`/`tint` pair covers the ghost look;
  richer materials are out of scope.
- **Grid-anchored layouts** (`hafen.store`) and the **`planner`** example addon → **V4**; the **gizmo** → V5/V6.
