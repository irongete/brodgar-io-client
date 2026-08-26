# 113 — plan

## Approach

Two reads on `LuaGob`, in the shape every reader there already has: resolve the handle, ask
`AddonManager.gobUser(id)` which character computes, read under the gob's monitor, answer `nil` for
anything unavailable, throw for an argument.

**`gob:sdt()`.** `ResDrawable.sdt` is **package-private**, so the read goes through
`haven.AddonWidgets`, the one file in package `haven` carrying the non-zero-edit read surface. It
hands back a `byte[]`: `sdt.clone()` (a fresh reader from `oh`; reading the field itself would advance
the sprite's own `rh`) then `Message.bytes()`. `AddonManager` converts to a 1-based array of
`b & 0xff` and files the same array in `gobSnapshot` under `sdt`.

**`gob:hitbox()`.** No bridge: `Resource.Obstacle` is public. `Drawable.getres()` gives the resource
and `res.layer(Resource.obst, "")` the collision layer — `layer`, not `flayer`, because a resource
without one is ordinary and `NoSuchLayerException` is not. Each `Obstacle.p` point is rotated by
`Gob.a` and offset by `Gob.rc` with `Gob.BasePlace.getz`'s arithmetic, then handed to
`LuaPosition.of(owner, user, c)`. An array of arrays of Positions crosses.

**`GobSdtChanged`.** The seam is `ResDrawable.$cres.apply`, the only place the bytes are replaced: its
`Sprite.CUpd` branch assigns `d.sdt` in place, its other branch `setattr`s a fresh `ResDrawable`, and
neither is visible from an existing hook. A local holds `d.sdt` before the chain; one tagged line
after it calls `AddonManager.gobSdtChanged(g, sdt)` when `!old.equals(sdt)` (`MessageBuf.equals` is a
byte comparison; `MessageBuf.nil` is the "none" a first arrival differs from). That is the whole
upstream edit, copying `Gob`'s three `// addon:` overlay seams: **queue only, never Lua, never
throw.**

The addon half mirrors the overlay family: a `sdtEvents` queue on `SessionState`, a `drainSdtEvents`
bounded by the queue's size at entry, a fire-once gate on `(gobId, bytes)` shaped like `nativeEdge`,
`fireGobSdt` broadcasting, the key in `BUS_KEYS` with its near-miss refusal, and a `LuaEvent` payload.

## Files to create and modify

| File | Change |
|---|---|
| `src/haven/AddonWidgets.java` | `gobSdt(Gob)` → `byte[]`, and a class javadoc that no longer says widgets only |
| `src/haven/ResDrawable.java` | the one `// addon:` seam in `$cres.apply`, and the local it compares against |
| `src/io/brodgar/addon/AddonManager.java` | `gobSdt`, `gobHitbox`, `sdt` in `gobSnapshot`; the queue, drain, gate, fire and key |
| `src/io/brodgar/addon/LuaGob.java`, `LuaEvent.java` | the two verbs with their arity refusals; the `:gob()` `:sdt()` payload |
| `docs/addons/api/gob.md` | two `Read` rows, a section each, and the `:info()` row's "everything above" |
| `docs/addons/api/types/world.md` | the `sdt` field; what a snapshot does not carry |
| `docs/addons/api/event/bus/world.md`, `.../bus/README.md` | the key, its payload table and its once-per-object rule |
| `docs/addons/api/overlay.md` | the impact-set line calling a crop's stage an overlay |
| `docs/client/resources.md` + `README.md`'s row | **new**: the layer catalogue, the `IDLayer` lookup, `obst`, the `sdt` path |
| `addons/113-the-shape-and-the-state.1/`, `.2/`, `.3/` | the three suites |

## Risks and gotchas

- **`Obstacle` points are already world units** — the constructor ends `.mul(MCache.tilesz)`. Scaling
  by the tile size again makes a tree's footprint eleven tiles wide.
- **Pass `""`, never `null`, to `Resource.layer(Class, I)`.** With `null` it returns the *first* layer
  whatever its id, so a resource carrying a build box and a collision box answers whichever is first.
- **An unknown `obst` version parses to `id = "#"` and an empty `p`** — not found at `""`, which is
  right, but never assume a found layer has points.
- **`Drawable.getres()` throws `Loading`.** `AddonManager.gobName` is the shape both reads need:
  catch `RuntimeException`, answer `null`.
- **The sdt write already runs under the gob monitor** — `OCache.ObjDelta.apply` calls
  `deltas.get(d.type).apply(gob, …)` inside `synchronized(gob)`, and `$cres.apply` is what reassigns
  the field. `synchronized(g)` on the read side is the whole of the correctness; no `volatile`.
- **A composed body has no sdt.** A player's `Drawable` is a `Composite`; `nil` there is a documented
  answer rather than a hole.
- **`MessageBuf.nil` is the empty payload**, so a resource-drawn gob the server sent no state for is
  the empty array. Empty and `nil` differ, and the page says which.
- **`gob.md` is at 226 lines of 300.** A task whose writing pushes it over splits it there and
  re-points its inbound anchors (`#read`, `#size-unprotected`, `#gobsessions`).
- **How widely resources carry an `obst` layer is not knowable from `src/`.** The suite scores over
  the gobs the run reached rather than assuming one object has it.
- **`$cres.apply` runs per session copy.** Two characters watching one field would give two events for
  one growth; the `(gobId, bytes)` gate is what makes it one.
- **The bytes belong on the event.** The seam queues and the handler runs a tick later, so two deltas
  in one tick would both read the *final* `gob:sdt()` and a stage would vanish. `ev:sdt()` is that
  firing's bytes.
- **A res swap can carry identical bytes** — `$cres.apply`'s second branch runs when only the resource
  changed, so the seam compares bytes rather than trusting it ran.

## Discarded alternatives

- **Hand back the resource-space polygon and let the caller place it** — every consumer redoes the
  rotation `Gob.BasePlace.getz` does, and a sign error is invisible on a symmetric tree and wrong on
  every wall, gate and cupboard.
- **One flat array of points** — a resource carries several rings, and flattening draws an edge between
  shapes that do not touch.
- **An axis-aligned box, the `mdl:bounds()` shape** — not what the server collides against: the box
  around a fence turned 45° covers ground the fence does not.
- **Put the hitbox in `gob:info()`** — a snapshot carries no objects, and a polygon rebuilt on every
  `:info()` is paid by every sweep that only wanted the name.
- **Decode the state into meanings (`gob:cropStage()`)** — the decoder is each resource's published
  code, which the client does not have, so the layer would be guessing.
- **Hand the state back as a string** — a `0..255` array indexes without `string.byte`, and it is the
  form `hafen.json` encodes straight out of `gob:info()`.
- **Make `ResDrawable.sdt` public** — `AddonWidgets` exists so that surface is one file; a second door
  is a second thing an upstream pull silently breaks.
- **`gob:hitbox(id)` for the other `obst` layers** — a resource-authoring vocabulary with no meaning a
  reader could act on.
- **A row on `docs/client/services.md`** — it is at 146 of its 150-line ceiling, and the layer
  catalogue is a subject of its own.
- **Diff each gob's bytes on the tick** — the sweep the event exists to remove, costing every gob in
  view every frame to report the few that ever change.
- **Hang the edge on `Gob.setattr`** — it sees only the branch minting a fresh `ResDrawable`, while the
  `Sprite.CUpd` branch, the one a growing crop takes, mutates `d.sdt` beneath it.
- **Name the key `SdtChanged`** — every world-bus key names the gob it is about (`GobAdded`,
  `GobOverlayAdded`); a bare subject reads as a fact about the client.
- **`gob:on("SdtChanged", fn)` instead of the bus** — a Gob carries no `:on`, so one event would have
  to invent a per-object subscription registry, its lifetime and its teardown; the address rule sends
  it to the bus, where every other fact about a gob arrives.
- **Payload a bare Gob, like `GobAdded`** — the handler would read the current bytes, not the ones that
  fired it, which is the one thing this event exists to make reliable.
