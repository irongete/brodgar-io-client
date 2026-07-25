# V2 — Clickable ghosts + `GhostClicked` (`clickable` / `g:clickable(bool)` / per-ghost `onClick`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **N headless checks** (the
> `GhostGob.obstate` clickable-gates-a-`GobClick` mechanism via a `BufPipe`; `onGhostClick` dispatch —
> null/unregistered/non-clickable/dead → false, a registered clickable ghost → fires `onClick` + the
> owner-scoped `GhostClicked` event + returns true (consume), and consumes even with no `onClick`;
> `setGhostClickable` flag mirroring on a not-yet-live ghost + no-op-when-unchanged/dead — same-package
> reflection for the private statics, a real `Sandbox.create()` env) + LuaJ parse of `hello`.
> **Java engine change ⇒ `ant` rebuild + a full client restart before the in-game test.** **In-game DoD pending.**
> **Design:** [specs/addons/16-virtual-entities.md](../../../specs/addons/16-virtual-entities.md) (§3 clickability,
> §6 core edit 2), decisions **D-032** (opt-in clickable via client-only pick detection — still safe-tier; the
> click is consumed at `Click.hit`) / **D-029** (client-only, not gated) / **D-011** (centralized invasiveness).

V2 is the second slice of the **V-series**: it makes a [V1](v1-ghosts.md) ghost **opt-in clickable**, so an addon
can select / context / drag it — the core need for a planner. Clicking a clickable ghost fires the addon's
handlers and is **consumed** (the character does not walk or interact), and **nothing is sent to the server** —
so the whole thing stays **SAFE-tier** (D-032). A non-clickable ghost carries no pick surface, so it never wins a
pick and ordinary game clicks fall straight through it.

## The API

```lua
local g = hafen.ghost.new{
  res = "gfx/terobjs/arch/logcabin", x = wx, y = wy,
  clickable = true,                            -- opt-in pick surface (default false)
  onClick   = function(g, button, x, y) end,   -- fires on click (also via the GhostClicked event)
}
g:clickable(false)   -- toggle the pick surface at runtime (e.g. an "edit mode")

hafen.events.on("GhostClicked", function(ev)   -- ev = { ghost, button, x, y }; owner-scoped
  ev.ghost:destroy()
end)
```

`button` = 1 (left) / 3 (right); `x, y` = the world point the click resolved to. Both `onClick` and
`GhostClicked` fire on every click of a clickable ghost; `GhostClicked` reaches **only the owning addon** (a
ghost is private to its addon, so its handle must not leak cross-addon — unlike the broadcast world events).

## Mechanism 1 — giving a *virtual* gob a pick surface (`GhostGob.obstate`)

The MapView pick pass ([`Clicklist`](../../../src/haven/MapView.java:1155)) returns a gob only if the gob's mesh
carries a [`Clickable`](../../../src/haven/Clickable.java:32) render state. The engine adds one — a
[`Gob.GobClick`](../../../src/haven/Gob.java:670) — in every gob's render state
([`Gob.GobState.apply`](../../../src/haven/Gob.java:715)), **but only for non-virtual gobs** (`if(!virtual)`). A
ghost is virtual (id `-1`), so a plain V1 ghost has **no** `GobClick` and is unpickable — which is exactly why V1
ghosts were purely decorative.

`GobState.apply` does, however, call the `protected` extension hook `obstate(Pipe)` for **every** gob, virtual or
not. So V2 introduces [`GhostGob extends Gob`](../../../src/io/brodgar/addon/GhostGob.java) — the gob a ghost now
uses — which overrides `obstate` to prep a `GobClick` **when `clickable`**:

```java
public final class GhostGob extends Gob {
    public volatile boolean clickable;
    public GhostGob(Glob glob, Coord2d c) { super(glob, c); }   // id -1 ⇒ still virtual
    protected void obstate(Pipe buf) {
        if(clickable)
            buf.prep(new Gob.GobClick(this));
    }
}
```

This gives a virtual ghost a click surface **without flipping `virtual`** — which matters twice: (1) the ghost
stays invisible to OCache / the server / reads (its whole reason to exist), and (2) the `Click.hit` intercept
uses `cg.virtual` as its fast-path filter (below), so a "clickable ghost" must remain virtual. Because the
`GobClick` is a real `Gob.GobClick`, the engine's own `clickedgob(inf)` unwraps it to this gob with **zero**
extra plumbing. `obstate` is evaluated at render-**apply** time, so the flag is read live.

## Mechanism 2 — toggling (`setGhostClickable`): the click-list decides membership at add time

`Clicklist.add(slot)` runs its `Clickable` filter **once, when the mesh slot is added to the render tree** — a
later ancestor-state change routes to `Clicklist.update`, which only touches *already-tracked* slots and so
**cannot promote** a slot that gained a `Clickable` after the fact. (The engine never needs this: a real gob is
clickable for its whole life; a virtual one never is.) So a runtime `g:clickable(true)` cannot simply flip a
state — the mesh would keep whatever pick-membership it had at add time.

`AddonManager.setGhostClickable` therefore toggles a **live** ghost by **removing and re-adding** it to the scene
(`removeClientGob` + `addClientGob`, the V1 seam): on the re-add, `GhostGob.obstate` is applied fresh and reads
the new flag, so the click-list re-runs its filter and tracks (or drops) the mesh correctly. The gob is **not**
disposed across the re-add, and it happens under the ghost monitor in the same **ghost → tree** lock order the V1
deferred create already uses (no new hazard). A ghost created `clickable = true` needs **no** re-add — the
deferred create sets `GhostGob.clickable` **before** `addClientGob`, so it is tracked from the first frame.

## Mechanism 3 — the intercept (`MapView.Click.hit`, the one core edit)

A click resolves in [`MapView.Click.hit`](../../../src/haven/MapView.java:2065), which ends in
`wdgmsg("click", …)`. A ghost has no server id, so that send would be bogus. V2 adds a `// addon:` intercept at
the **top** of `hit` — **beside the voice hooks in the same method** ([:2066](../../../src/haven/MapView.java:2066)):

```java
// addon: V2 virtual entities (hafen.ghost.*) — consume a click on a CLICKABLE client ghost before wdgmsg.
Gob cg = clickedgob(inf);
if((cg != null) && cg.virtual && io.brodgar.addon.AddonManager.onGhostClick(cg, clickb, mc))
    return;
```

`cg.virtual` is the near-zero fast path: only a virtual gob can be a client ghost (real gobs are non-virtual), so
ordinary clicks never reach the addon layer. [`AddonManager.onGhostClick`](../../../src/io/brodgar/addon/AddonManager.java)
finds the live ghost whose gob is `cg` (across all addons + the REPL owner); if it is **clickable** it fires the
owner-scoped `GhostClicked{ghost, button, x, y}` event and the per-ghost `onClick(g, button, x, y)`, then returns
`true` so `hit` **returns without `wdgmsg`** — the click is consumed, no server contact, still safe-tier. A
clickable ghost consumes **unconditionally** (the DoD: "no server click, no walk/interact"); the event/`onClick`
are only how the addon reacts. Anything else (no ghost / not clickable / dead) returns `false` and the normal
click proceeds — so a non-clickable ghost is genuinely click-through.

**Threading.** `onGhostClick` is reached under `synchronized(ui)` (the click read-back's `hit` runs inside it,
like the L3 message hook), so `callLua` is safe with no extra thread guard. The ghost lock is released **before**
the Lua dispatch, so a handler may re-entrantly `g:destroy()` / `:move()` / `:clickable()` the ghost.

## Why `GhostClicked` is owner-scoped (not a global `fire`)

Every other bus event (`GobAdded`, `KinChanged`, …) is about **shared server state** and broadcasts to all
addons. `GhostClicked` carries the **ghost handle**, which is a private, owner-only capability (calling it can
move/destroy the ghost). Broadcasting it would hand addon B a live handle to addon A's ghost — a cross-addon
capability leak. So it is delivered with `fireTo(owner, …)` only, matching the per-ghost `onClick` (also
owner-only). The design decision and its rationale are noted in the spec (§3) and PLAN §8.

## Files changed

- **`src/haven/MapView.java`** — the one V2 `// addon:` core edit: the 3-line `Click.hit` intercept (before
  `wdgmsg`, beside the voice hooks). The V1 `addClientGob`/`removeClientGob` seam is reused unchanged.
- **`src/io/brodgar/addon/GhostGob.java`** (new) — `Gob` subclass whose `obstate` adds a `GobClick` when
  `clickable`, giving a virtual ghost a pick surface without flipping `virtual`.
- **`src/io/brodgar/addon/LuaGhost.java`** — new `clickable` (target flag, mirrored onto the `GhostGob`) +
  `onClick` (per-ghost callback) fields.
- **`src/io/brodgar/addon/AddonManager.java`** — `newGhost` reads `clickable`/`onClick` and builds a `GhostGob`
  (setting `clickable` before `addClientGob`); `ghostHandle` gains `:clickable(bool)`; new `setGhostClickable`
  (toggle via remove + re-add), `onGhostClick` (the intercept target — event + onClick + consume), and
  `findGhostByGob`/`findGhostIn` (gob → owning ghost).
- **`addons/hello/`** — v0.36.0: the V1 auto-demo cabin stays NON-clickable (click-through), and `:hello ghost`
  now toggles a **clickable** cabin with an `onClick`; a `GhostClicked` event handler logs every ghost click.

## Try it in-game (V2 DoD)

Rebuild (`ant hafen-client`) and **fully restart** the client (a Java engine change — the JVM does not hot-reload
classes).

1. In the world, run `:hello ghost` — a **clickable** log cabin appears at you.
2. **Click the cabin.** The console logs `:hello ghost onClick …` **and** `:GhostClicked …`, and your character
   does **not** walk to it or interact — the click was consumed (no server click sent).
3. **Click the ground** next to it — you walk there normally (the intercept only fires on the ghost).
4. The V1 auto-demo cabin (spawned ~3 tiles east at login) is **non-clickable** — clicking it walks you there
   (click-through), confirming a decorative ghost never hijacks a click.
5. `:reload` (or disable `hello`) while a clickable ghost is up → it is removed cleanly, leaking nothing.

DoD: click a clickable ghost → `GhostClicked` fires, no server click / no walk-interact; a non-clickable ghost is
click-through.

## Deferred (later V-slices)

- **Consume-vs-pass-through routing** — V2 consumes a clickable-ghost click unconditionally (the DoD, and D-032
  says "the click is consumed at `Click.hit`"). Letting a handler forward the click to the real gob *behind* the
  ghost needs a re-pick that excludes the ghost; for now an addon that wants a normal click keeps the ghost
  non-clickable (the planner "edit mode" pattern).
- **`iteminteract` (held-item use) is NOT intercepted — flagged for the maintainer.** The spec closed-scopes V2 to
  the `Click.hit` intercept (§3, §6 edit 2), so that is all this slice touches. But `MapView.iteminteract`
  ([:2162](../../../src/haven/MapView.java:2162)) — item-on-cursor + click — runs the *same* pick and also extends
  `inf.clickargs()`, so using a held item **on** a clickable ghost would send `wdgmsg("itemact", …, gobid=-1, …)`
  (the ghost's id). The server ignores gob `-1` (functionally harmless), but it is a server send caused by a ghost,
  so it nicks the safe-tier invariant's letter. A 3-line guard mirroring the `Click.hit` one (a new
  `AddonManager.isClickableGhost(cg)` → consume the item-interact without firing `GhostClicked`, since an
  item-interact is not a click) would close it. **Left out deliberately** — it is a second core-edit region beyond
  the ratified scope and a rare combination (a planner's user isn't holding items); surfaced here for the
  maintainer to ratify as a quick follow-up if wanted.
- **V3** — `:rotate`, `:setRes(res[,sdt])`, `:show()/:hide()`, and `alpha`/`tint` (the translucent look).
- **V4** — grid-anchored layouts via `hafen.store` + the dedicated `planner` example addon (click-to-select via V2).
- **V5/V6** — `hafen.map.screenToWorld` + the transform gizmo, reusing V2's ghost picking for 3D-native handles.
