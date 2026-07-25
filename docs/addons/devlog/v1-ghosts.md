# V1 — Minimal client-only world ghosts (`hafen.ghost.new` / `list` / `:move` / `:pos` / `:destroy`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **35 headless checks**
> (the canonical filter `ghostMatches`, the handle round-trip `:move`/`:pos`/`:res`, destroy idempotency +
> owner-list drop, `teardownGhosts`, and `newGhost` validation/no-world guard — same-package + reflection for
> the private statics, the documented headless pattern) + LuaJ parse of `hello`/`walker`/`bags`/`hogtest`.
> **Java engine change ⇒ `ant` rebuild + a full client restart before the in-game test.** **In-game DoD pending.**
> **Design:** [specs/addons/16-virtual-entities.md](../../../specs/addons/16-virtual-entities.md) (§2 mechanism,
> §6 core edits, §7 threading), decisions **D-029** (client-only, safe-tier — not gated) / **D-030** (dedicated
> `hafen.ghost.*` namespace, handle-based) / **D-011** (centralized invasiveness where it enables a feature).

V1 is the first slice of the **V-series** (virtual entities): it lets an addon place **client-only virtual
objects in the 3D world** — "ghosts". A ghost is a translucent-*capable* (translucency is V3), non-interactive
prop rendered at arbitrary world coordinates, **never sent to the server**. The motivating use is city / base
planning: lay out ghost buildings over the real terrain, save the layout (V4), and iterate. This slice ships the
minimum — **create, move, destroy** — plus the one MapView seam the whole subsystem builds on.

## The API

```lua
local g = hafen.ghost.new{
  res = "gfx/terobjs/arch/logcabin",   -- a client resource name
  x = wx, y = wy,                       -- WORLD coords (login-relative, like hafen.gob.pos)
  a = 0,                                -- facing radians (optional, default 0)
}
g:move(wx2, wy2 [, a])   -- reposition (+ optional facing)
g:pos()                  -- {x, y, a}
g:res()                  -- the resource name
g:destroy()              -- remove now (also automatic on reload/disable)

hafen.ghost.list([filter])   -- this addon's live ghosts (canonical filter, adapted to handles)
```

A ghost is **interactive + bridge-owned**, so it is addressed by a **handle** (D-030), like a
`hafen.ui.window` — not a GobRef: a ghost has **no server id**, so re-resolution is meaningless. `list`'s
`filter` is the canonical `nil` / string / function trichotomy, adapted to a handle collection: `nil` = all; a
**string** matches the ghost's `res`; a **function** is called with the **handle** (so it can call `g:pos()`),
truthy keeps it.

## Mechanism — a client-only `Gob`, the `Plob` precedent

The engine already renders a client-only, cursor-driven virtual object: the **placement preview**,
[`MapView.Plob extends Gob`](../../../src/haven/MapView.java:1779). A ghost is built exactly the way `Plob`
builds itself:

1. `new Gob(glob, rc)` — [`Gob(Glob, Coord2d)`](../../../src/haven/Gob.java:441). It passes `id = -1`, so the
   gob is `virtual` ([Gob.java:437](../../../src/haven/Gob.java:437)): **not in `OCache`, invisible to every read
   API and to the server**.
2. `gob.setattr(new ResDrawable(gob, res))` — the visual ([`ResDrawable`](../../../src/haven/ResDrawable.java:79)).
   The resource is resolved via **`Resource.remote()`** — the game/server resource pool (terobjs, gobs, …), with
   `local()` as a fallback for client-bundled resources. This is the pool the engine itself uses for gob drawables
   (Session/Music/Widget); `Resource.local()` alone only sees the client jar, so a terobj like
   `gfx/terobjs/arch/logcabin` would never resolve (an early bug — the prop silently failed to load).
3. `mapView.addClientGob(gob)` → adds `gob.placed` to the MapView **`basic`** scene slot; keep the returned
   `RenderTree.Slot`.
4. reposition with [`Gob.move(Coord2d, double)`](../../../src/haven/Gob.java:566) (position + facing); despawn
   with `slot.remove()` + `gob.dispose()`.

**Ticking (the other early bug).** The render tree ticks `gob.placed` (a `TickList.Ticking`) itself, so a
`:move` (which sets `rc`/`a`) is picked up by `Placed.autotick` on the next frame — no addon poll for **position**.
But a client-only gob is **not in `OCache`**, so nothing calls its `Gob.ctick`/`gtick` — and without those the
**sprite never prepares/animates and nothing renders**. That is exactly why MapView explicitly `ctick`s the
placement `Plob` in `tick()` ([:1730](../../../src/haven/MapView.java:1730)) and `gtick`s it in `draw()`
([:1651](../../../src/haven/MapView.java:1651)). So the seam (below) makes MapView own the client-gob list and
`ctick`/`gtick` each ghost in the same two spots. Ghosts still need **no addon-side poll** — the work lives in
MapView, and they hold **no global dispatch list**, only the per-addon owned-resource registry.

## Core edit — the one MapView seam (spec 16 §6, option A)

`basic` (a `public final RenderTree.Slot` on [`PView`](../../../src/haven/PView.java:38)) and `Gob.placed`
(public) are both reachable, but they are `haven`-package internals; `io.brodgar.addon` is a different package.
Per the ratified design (option A — "smallest, clearest, mirrors `Plob.place`") the subsystem adds **one
centralized `// addon:` seam** to [`MapView`](../../../src/haven/MapView.java): a `clientGobs` list, the
add/remove pair, and — critically — the `ctick`/`gtick` of each client gob right where MapView ticks the `Plob`:

```java
private final Collection<Gob> clientGobs = new CopyOnWriteArrayList<Gob>();

public RenderTree.Slot addClientGob(Gob gob) {
    RenderTree.Slot slot = basic.add(gob.placed);
    clientGobs.add(gob);
    return(slot);
}
public void removeClientGob(Gob gob, RenderTree.Slot slot) {
    if(gob != null) clientGobs.remove(gob);
    if(slot != null) { try { slot.remove(); } catch(RenderTree.SlotRemoved e) { /* already gone */ } }
}
// in tick(dt), beside the Plob's ctick:   for(Gob g : clientGobs) synchronized(g) { try { g.ctick(dt); } catch(RuntimeException e) {} }
// in draw(GOut g), beside the Plob's gtick: for(Gob gob : clientGobs)             { try { gob.gtick(g.out); } catch(RuntimeException e) {} }
```

Each per-gob tick/gtick is error-isolated so one bad ghost never breaks the frame; the list is copy-on-write so
the bridge may add from a loader thread and remove from the UI thread while draw/tick iterate. This centralizes
the scene mutation **and** the render upkeep behind one seam so the addon layer never touches `basic`/`placed` or
the tick loop directly — the same D-011 "invasiveness where it clearly enables a feature"
call the maintainer made in the spec. `removeClientGob` swallows `SlotRemoved` so teardown is idempotent even
after a relog has already torn down the whole scene. **This is the only `haven` edit in V1** (the V2 `Click.hit`
intercept and the V5 snap refactor come later).

## Deferred create (dodging `Loading`)

The `ResDrawable` constructor calls `res.get()`, which throws `Loading` until the resource is cached — so
building it on the UI thread would blow up on a not-yet-loaded prop. Exactly like `Plob` (which builds inside
`glob.loader.defer`) and `hafen.sound.play`, `newGhost` **defers** the build to a loader thread: `res.get()`
throws `Loading` → the loader re-runs the task when the resource lands → then it constructs the gob + drawable
and `addClientGob`s it. The handle is returned **immediately** and works while the prop streams in.

The publish (`gh.slot`/`gh.gob` assignment) and the destroy handoff are guarded by the ghost's own monitor, so
the loader-thread create never races a concurrent `:move`/`:destroy`:

- **`:destroy` before the create publishes** → it flips `dead`; the create checks `dead` under the lock and
  **discards** its un-added gob (disposes it), so nothing leaks.
- **`:move` before the create publishes** → it updates the target `rc`/`a`; the create applies the latest right
  before adding.
- **`:move`/`:destroy` after publish** → operate on the live gob directly.

A bad resource name resolves to a non-`Loading` exception → the create reports `addon: ghost resource '…' could
not be loaded` and abandons (marks the ghost `failed`), rather than re-running forever.

## Threading & lifecycle (P2/P5)

- **UI thread for the API** (`new`/`move`/`pos`/`destroy`/`list`, teardown); **one loader thread** for the
  one-shot resource resolve + scene add. `RenderTree.Slot.add`/`remove` take the render tree's own lock
  ([RenderTree.java:472](../../../src/haven/render/RenderTree.java:472)), so `addClientGob` from the loader thread
  and `removeClientGob` from the UI thread are both safe (the engine's own `Gobs`/`Plob` add slots off-thread
  too). The heavy build (gob + `ResDrawable`) happens **outside** the ghost lock; only the atomic
  add-and-publish is inside it, so lock hold time is minimal and there is no lock-ordering hazard with the tree
  lock.
- **Teardown (P2).** Each ghost is a bridge-owned handle in the per-addon list
  [`Addon.ghosts`](../../../src/io/brodgar/addon/Addon.java); `teardownGhosts` (wired into `AddonManager.teardown`)
  destroys each on `OnDisable` / `:reload` / relogin — removes the scene slot + disposes the sprite — leaking
  nothing, the same guarantee as windows and overlays.
- **Reload restores nothing automatically** (N6): an addon recreates its ghosts in `OnEnterWorld` from its saved
  layout (grid-anchored — V4). On a relog the old MapView's scene is gone; `removeClientGob` swallows
  `SlotRemoved`, so teardown stays clean.

## Files changed

- **`src/haven/MapView.java`** — the one `// addon:` seam: the `clientGobs` list, `addClientGob`/
  `removeClientGob(Gob, Slot)`, and the per-frame `ctick`/`gtick` of each client gob beside the `Plob`'s.
- **`src/io/brodgar/addon/LuaGhost.java`** (new) — the bridge-owned handle (data holder, like `LuaModel`):
  `owner`, `res`/`resName`, target `rc`/`a`, the live `gob`/`slot`/`mv`, `dead`/`failed`, and the stable Lua `handle`.
- **`src/io/brodgar/addon/AddonManager.java`** — imports (`ResDrawable`, `render.RenderTree`); the `hafen.ghost`
  namespace (`new`/`list`) in `installHafen`; `newGhost` (validation + `Resource.remote()` + defer + seam),
  `ghostHandle` (`:move`/`:pos`/`:res`/`:destroy`), `ghostList`/`ghostMatches` (canonical filter), `destroyGhost`,
  `teardownGhosts` (wired into `teardown`).
- **`src/io/brodgar/addon/Addon.java`** — the `ghosts` owned-resource list.
- **`addons/hello/`** — v0.35.0: an OnEnterWorld regression (spawn a cabin 3 tiles E, read `list()`/`pos()`,
  `:move` it 2 tiles N, auto-destroy after 8 s) + a `:hello ghost` toggle at the player.

## Try it in-game (V1 DoD)

Rebuild (`ant hafen-client`) and **fully restart** the client (a Java engine change — the JVM does not
hot-reload classes).

1. **From `:lua`** (the trusted operator console), in the world:
   ```
   :lua local p = hafen.gob.pos("player"); return hafen.ghost.new{res="gfx/terobjs/arch/logcabin", x=p.x, y=p.y}
   ```
   A log-cabin prop appears at the player. `:lua g:move(...)` repositions it; `:lua g:destroy()` removes it.
2. **The `hello` harness** does this automatically at login: a cabin appears ~3 tiles east, jumps 2 tiles north
   (the `:move`), then vanishes after 8 s (the `:destroy`). `:hello ghost` toggles a cabin at your position.
3. **`:reload`** (or disable `hello`) while a ghost is up → it is removed cleanly, leaking nothing.

DoD: a visible prop at a world coord; `:move` repositions it; `:destroy` and `:reload` remove it cleanly.

## Deferred (later V-slices)

- **V2** — opt-in clickable ghosts + the `GhostClicked` event (the `MapView.Click.hit` intercept, still
  client-only ⇒ safe-tier).
- **V3** — `:rotate`, `:setRes(res[,sdt])`, `:show()/:hide()`, and `alpha`/`tint` (the translucent look).
  `sdt` (resource variant bytes) folds in here; V1 always uses `Message.nil`.
- **V4** — grid-anchored layouts via `hafen.store` + a dedicated `planner` example addon.
- **V5/V6** — `hafen.map.screenToWorld` + the transform gizmo (`hafen.ghost.gizmo`), reusing the client's own
  `placegrid`/`placeangle` snapping.
