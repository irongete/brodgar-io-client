# R2b — 2D image in the world, camera-facing billboard (`hafen.render.sprite{billboard=true}`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL) + `ant bin` packages, **LuaJ parse**
> of `hello`/`planner`/`gizmo.lua` (all compile clean) + **pure billboard colour-math checks** (`LuaSpriteBillboard.
> clampByte`/`lerpByte` via reflection — `LuaSpriteBillboard` loads headlessly). The billboard **render**
> (camera-facing screen blit) and the **sprite click dispatch** are GL/session-dependent, so they are verified
> **in-game** (like R2a's fixed quad). **Java engine change ⇒ `ant` rebuild + a full client restart before the
> in-game test.** **In-game DoD pending.**
>
> **Design:** [specs/addons/17-custom-rendering.md](../../../specs/addons/17-custom-rendering.md) (§2 the shared
> world-entity core, §5 caps 2 & 3 — the billboard is the `SpeakerIcon`-style `PView.Render2D`),
> [16-virtual-entities.md](../../../specs/addons/16-virtual-entities.md) (the V-series core), decisions **D-034**
> (custom non-`.res` rendering is `hafen.render.*`, safe-tier — not gated) / **D-013** (extract shared) / **D-032**
> (client-only click detection is safe-tier). Builds directly on [r2a-sprites-world](r2a-sprites-world.md).

R2b is the third slice of the **R-series** and completes `hafen.render.sprite`: it adds the **second world visual**
— a **camera-facing billboard** — on the same client-only world-entity core the fixed quad (R2a) stands on, and it
**wires sprites into the V2 click dispatch** so a fixed sprite is selectable + gizmo-transformable. The `planner`
example is generalized to place **sprites** (ghost *or* sprite, one code path), giving the "gizmo-move" DoD.

## The API

```lua
local icon = hafen.render.image("icon.png")
local p    = hafen.gob.pos("player")

-- fixed (R2a) — a world quad, world-rotate/scale apply:
local q = hafen.render.sprite{ image = icon, x = p.x, y = p.y, scale = 3 }

-- billboard (R2b) — a screen blit that ALWAYS faces the camera, constant screen size:
local b = hafen.render.sprite{ image = icon, x = p.x, y = p.y, billboard = true, scale = 2 }
-- b:move(x,y) moves it (and the gizmo moves it); b:scale(k) resizes it on screen; :rotate is a no-op (2D).

-- a FIXED sprite can now be clickable (V2 dispatch), like a ghost:
local s = hafen.render.sprite{ image = icon, x = p.x, y = p.y, clickable = true,
  onClick = function(s, button, x, y) hafen.log("clicked!") end }   -- + owner-scoped SpriteClicked event
```

## The billboard visual — `LuaSpriteBillboard` (a `Drawable` that is a `PView.Render2D`)

[`LuaSpriteBillboard`](../../../src/io/brodgar/addon/LuaSpriteBillboard.java) is the camera-facing sibling of R2a's
[`SpriteQuad`](../../../src/io/brodgar/addon/SpriteQuad.java). It is the **`haven.SpeakerIcon` / `LuaGobOverlay`
pattern**: a node that draws a screen-space blit anchored at a projected world point.

- **Mechanism.** It `extends haven.Drawable implements haven.PView.Render2D`. Its `draw(GOut, Pipe state)` projects
  the gob's origin `(0,0,0)` to the screen with [`Homo3D.obj2view`](../../../src/haven/render/Homo3D.java:201)
  (the `state` carries the gob's `Placed` world transform), then blits the `TexI` **bottom-centred** on that screen
  point (so it "stands" there). Because the gob's `Placed` slot supplies the anchor, **moving the entity (or a
  followed gob) moves the billboard** — position + gizmo-move apply; **world-rotate/world-scale do not** (it is a
  flat 2D image, always squarely facing the viewer). It draws in the 2D overlay pass (`PView.ScreenList`, after the
  3D scene), so it is **on top** (no depth test) and **screen-sized** (constant px at any zoom).
- **Why a `Drawable`, not a bare `GAttrib`.** Unlike `SpeakerIcon`/`LuaGobOverlay` (which sit *beside* a gob's real
  visual), a billboard **is** the entity's only visual, so it is the gob's **`Drawable`** — a resource-free one
  (`getres()==null`, like `SprDrawable`). Two payoffs: (1) `getattr(Drawable.class) != null`, so the gob is **not**
  swept up by [`Gob.ctick`](../../../src/haven/Gob.java:463)'s `virtual && no-overlays && no-Drawable ⇒
  oc.remove(this)` cleanup (which would otherwise run every tick, locking the shared `OCache` and copying its
  callback list — a real waste, even though `oc.remove` of a never-cached gob is a no-op); (2) a `Drawable` **is** a
  `RenderTree.Node`, so [`Gob.added`](../../../src/haven/Gob.java:761) adds it under the `Placed` transform and the
  tree adapter registers its `PView.Render2D` in the `ScreenList` automatically (membership is by the slot object's
  type, independent of the node's no-op `added` — exactly why `SpeakerIcon` renders). `GAttrib.added` still tracks
  the slot, so a swap/`gob.dispose()` cleans it up.
- **Look.** The billboard reads the [`GhostGob`](../../../src/io/brodgar/addon/GhostGob.java)'s live look fields
  itself (the `obstate` state recipe applies to a 3D mesh, which a billboard has none): `alpha` → the blit's opacity
  (`chcolor` alpha), `tint` → a colour multiply lerped toward the tint by its alpha (blend strength) — the closest
  `chcolor` analogue of a ghost's `MixColor` — and `scale` → a **screen-size** multiplier (so `:scale`/the gizmo
  scale-box resize it). Size = `UI.scale(image.sz) × scale` (DPI-scaled like the HUD). Never throws into the render
  pass (the projection is guarded, mirroring `LuaGobOverlay`).
- **Ownership.** The `TexI` belongs to the `LuaImage` handle (`Addon.images`); this visual only references it, so
  `dispose()` is a no-op and `teardownImages` frees the texture (teardown order: sprites before images). A disposed
  image (`LuaImage.dead`) blits nothing.

`newSprite` now branches on `billboard`: `true` → `gob.setattr(new LuaSpriteBillboard(gob, img))`; `false` → the
R2a `SprDrawable`/`SpriteQuad` quad. Everything else (the `GhostGob`, the synchronous create, the `follow` anchor,
teardown, the gizmo) is unchanged and shared — **only the visual differs**, exactly the spec-17-§2 architecture.

## The V2 click dispatch, generalized to sprites

R2a shipped sprites **click-through** (`onGhostClick` searched only `Addon.ghosts`). R2b makes a **fixed** sprite
opt-in **clickable**, reusing the V2 machinery whole:

- `newSprite` accepts `clickable` + `onClick` (mirroring `hafen.ghost.new`) and the sprite handle gains
  `:clickable(bool)` (over the shared `setEntityClickable`). Setting `clickable` preps the `GobClick` in
  `GhostGob.obstate` — and because a **fixed** sprite has a real quad mesh, that mesh renders into the clickmap, so
  the pick pass returns it. (A **billboard** has no world mesh, so the flag is a harmless no-op — it is never picked.)
- The dispatch is widened from ghosts to any world entity: **`findGhostByGob`→`findEntityByGob`** now scans
  `a.ghosts` **and** `a.sprites` and returns a `LuaWorldEntity`; **`onGhostClick`** (still the `MapView.Click.hit`
  entry point, so **no new core edit**) works on that base, fires the per-entity `onClick`, and fires the
  owner-scoped **click event named by the subclass** — new abstract `clickEvent()`/`clickKey()` on
  `LuaWorldEntity` return `"GhostClicked"`/`"ghost"` (a ghost) or **`"SpriteClicked"`/`"sprite"`** (a sprite). The
  click is still **consumed** before `wdgmsg` (client-only ⇒ safe-tier, D-032).

So a fixed sprite is now selectable by clicking (its `onClick`) and fires `SpriteClicked{sprite, button, x, y}` to
its owning addon — the sprite analogue of `GhostClicked`.

## The `planner` example — records generalized to ghost **or** sprite

The `planner` (the V-series layout/persistence/gizmo example) is generalized so the **same code path** places,
selects, gizmos, and grid-anchor-persists **both** a `.res` ghost and a custom-PNG sprite — the point of the D-013
shared core. The change is mostly a mechanical rename (`it.ghost` → `it.entity`, the live handle) plus a `kind`
discriminator on each record:

- **`spawn(it, wx, wy)`** branches on `it.kind`: `"sprite"` → `hafen.render.sprite{image=it.img, …, billboard=
  it.billboard, clickable=not it.billboard, onClick=…}`; else the ghost path (unchanged). Because a ghost handle
  and a sprite handle are identical, `selectItem`, the gizmo, `grab`, `list`, `rotate`/`scale`, `remove`/`clear`,
  and `resolvePending` all operate on `it.entity` **kind-agnostically**.
- **`applyLook`** branches: a ghost gets the bluish/warm **tint** highlight; a sprite highlights by **alpha** only
  (a colour tint would recolour the PNG). Both read clearly as selected/idle.
- **Persistence** stores `kind`/`img`/`billboard` alongside `res`/`a`/`scale`/`anchor`; the loader defaults old
  (pre-sprite) layouts to `kind="ghost"`. Sprites ride the **same grid-anchored** persistence as ghosts
  (`hafen.map.gridPos`/`fromGridPos`), so a relog restores them at the same spot too.
- **`:planner sprite [billboard]`** places a sprite at the player: bare = a **fixed** upright quad (clickable →
  click to select); `billboard` = a **camera-facing** billboard (no mesh → auto-selected, since a click can't pick
  it — use `:planner select` in general). `:planner gizmo` then moves either. `planner/icon.png` ships the asset
  (a copy of `hello`'s 32×32 test PNG).

`hello` (the read-only regression harness) gains **`:hello billboard`** (a camera-facing icon at your feet,
mirroring `:hello sprite`); its existing `:hello sprite`/`:hello follow` + the ghost auto-demo re-exercise the
shared core at every login (regression).

## Zero `haven` core edit

R2b reuses the V1 [`MapView.addClientGob`](../../../src/haven/MapView.java:1868)/`removeClientGob` seam and the V2
[`MapView.Click.hit`](../../../src/haven/MapView.java:2082) intercept (`onGhostClick`, unchanged signature). Every
billboard primitive is public (`Homo3D.obj2view`, `GOut.image`/`chcolor`, `UI.scale`, `Drawable`,
`PView.Render2D`, `TexI`). So all new code is **`io.brodgar.addon`-only** — no new `// addon:` line.

## Files changed

- **`src/io/brodgar/addon/LuaSpriteBillboard.java`** (new) — the camera-facing visual: a resource-free `Drawable`
  that is a `PView.Render2D`, projecting the gob origin and blitting the `TexI` bottom-centred with the entity's
  live opacity/tint/screen-scale. Pure colour helpers `lerpByte`/`clampByte`.
- **`src/io/brodgar/addon/LuaWorldEntity.java`** — abstract `clickEvent()` / `clickKey()` (the owner-scoped click
  event name + payload key per subclass).
- **`src/io/brodgar/addon/LuaGhost.java`** — `clickEvent()="GhostClicked"` / `clickKey()="ghost"` (unchanged behaviour).
- **`src/io/brodgar/addon/LuaSprite.java`** — a `billboard` field; `clickEvent()="SpriteClicked"` / `clickKey()="sprite"`.
- **`src/io/brodgar/addon/AddonManager.java`** — `newSprite` billboard branch (`LuaSpriteBillboard` vs `SpriteQuad`)
  + `clickable`/`onClick` options + `gob.clickable`; `spriteHandle` gains `:clickable`; the V2 dispatch generalized
  (`findGhostByGob`→`findEntityByGob` over ghosts + sprites; `onGhostClick` over `LuaWorldEntity`, firing
  `e.clickEvent()`/`e.clickKey()`); the `hafen.render.sprite` facade doc comment updated.
- **`addons/hello/`** — v0.41.0: `:hello billboard` (a camera-facing icon); manifest updated.
- **`addons/planner/`** — v0.5.0: records generalized to ghost/sprite (`kind`/`img`/`billboard`, `it.ghost`→
  `it.entity`), `:planner sprite [billboard]`, `SpriteClicked` listener, kind-aware `applyLook`/`persist`/load;
  ships `planner/icon.png`; manifest updated.

## Try it in-game (R2b DoD)

Rebuild (`ant hafen-client`) and **fully restart** the client (a Java engine change).

1. **Billboard renders + faces camera.** Log in, `:hello billboard` — the green "H" icon **stands facing you** at
   your feet. **Rotate the camera / zoom**: it stays square-on and the same pixel size (that is the billboard
   behavior; the R2a fixed `:hello sprite` quad, by contrast, foreshortens + occludes). `:hello billboard` again removes it.
2. **Both gizmo-move (the planner DoD).** `:planner sprite` stands a FIXED icon (click it to select) and
   `:planner sprite billboard` a CAMERA-FACING one (`:planner select <n>` to select it). For each: `:planner gizmo`,
   then **drag the arrows / centre** — the sprite moves, the **camera stays put**. `:planner sprite` (fixed) also
   demonstrates the **`SpriteClicked`** event (click it → the log line).
3. **Persistence.** With a few placed, relog — the sprites reload at the **same grid position** alongside the ghosts.
4. **`:reload` leaks nothing.** With sprites (fixed + billboard) up, `:reload` (or disable the addon): the scene
   slots are removed and the shared `icon.png` `TexI` is freed by the image teardown — no orphaned gob/texture.
5. **Regression.** `:hello sprite`/`:hello follow`/`:hello ghost` and the ghost auto-demo still work (the shared
   `*Entity` helpers are exercised by both ghosts and sprites).

DoD: a PNG **faces the camera** AND a fixed one **stands**; **both gizmo-move**; `:reload` leaks nothing.

## Deferred (R3 / later)

- **R3 — glTF model** (`hafen.render.model`/`object`) on the same core — see
  [18-custom-models-gltf.md](../../../specs/addons/18-custom-models-gltf.md).
- A billboard **pick** (screen-space hit-test in the click dispatch) so billboards can be click-selected too
  (today they select via `:planner select` / an addon's own list); explicit pixel-size (vs native × scale);
  a flat-on-ground **decal** variant; async decode for large textures.
