# 113 — tasks

- [x] **113.1 — the state the server sent, as the bytes it sent.** Adds `gob:sdt()`: the state bytes
      of a gob's resource drawable, as a 1-based array of `0..255` numbers. `haven.AddonWidgets` gains
      `gobSdt(Gob)`, which clones `ResDrawable.sdt` under the gob monitor and hands back its
      `byte[]`; `AddonManager.gobSdt` converts it and `AddonManager.gobSnapshot` files the same array
      under `sdt`, so `gob:info()` carries it and `hafen.json` can encode it. `LuaGob` gains the verb
      and its arity refusal. `docs/addons/api/gob.md` gains the `Read` row and a section saying that
      the meaning of the bytes belongs to the resource, not to the client; `types/world.md` gains the
      field. `docs/client/resources.md` is born here with the half this task read: what a `.res`
      carries, `layer`/`flayer` and the `IDLayer` lookup, and the `OD_RES` → `ResDrawable.$cres` →
      `sdt` path with the gob monitor that already guards it.
      *Its suite* asserts the array is dense, 1-based and inside `0..255` for every gob that answers;
      that at least one visible gob answers at all, retried over a bounded window; that
      `s:world():gob():get(1)` — an id nothing has loaded — answers `nil` while `:exists()` is
      `false`, which proves the gone case without waiting for a despawn; that the player's own body
      answers `nil`, its drawable being composed rather than resource-drawn; that `gob:info().sdt`
      matches `gob:sdt()` element for element and survives a `hafen.json` round trip; and that
      `gob:sdt(1)` raises, the message naming arity.

- [x] **113.2 — the ground an object stands on.** Adds `gob:hitbox()`: the collision footprint as an
      array of polygons, each an array of Positions, rotated by the object's facing and anchored at
      its place. `AddonManager.gobHitbox` reads `Drawable.getres()`, takes `res.layer(Resource.obst,
      "")` — never `null` for the id — and rotates each `Obstacle.p` point by `Gob.a` about `Gob.rc`
      with `Gob.BasePlace.getz`'s own arithmetic, minting a `LuaPosition` per point in the session
      `gobUser` named. `LuaGob` gains the verb and its arity refusal. `gob.md` gains the `Read` row
      and a section; its `:info()` row stops saying "everything above", since a snapshot carries no
      objects, and its scale section links the footprint it already claims is untouched.
      `docs/client/resources.md` gains the `obst` half. This task discharges the feature's whole
      impact set, `api/overlay.md` included.
      *Its suite* asserts every non-`nil` answer is at least one polygon of at least three Positions,
      each answering `p:x()`; that every point sits on its own object rather than at the frame's
      origin; that `s:world():worldToScreen(pt)` answers a screen point for one of them; that
      `gob:scale(3)` leaves every number unchanged and `gob:scale(1)` puts the object back, which is
      the page's claim that the drawn size is not the footprint; that `gob:info().hitbox` is `nil`;
      that a never-loaded id answers `nil`; and that `gob:hitbox(1)` raises naming arity.
      `[manual]`: the suite paints every visible hitbox on the HUD for twenty seconds — report
      whether the outlines hug the objects' bases, and whether the one on an asymmetric object (a
      gate, a wall segment, a cupboard) turns the way that object faces.
      <!-- extra context: docs/addons/api/ui/overlay.md, docs/addons/api/ui/drawing.md -->

- [ ] **113.3 — the state has a moment, so it has an edge.** Adds the bus key `GobSdtChanged`,
      payload `:gob()` and `:sdt()`. `ResDrawable.$cres.apply` gains the feature's one upstream edit:
      a local holding `d.sdt` before the branch chain, and a `// addon:` line after it calling
      `AddonManager.gobSdtChanged(g, sdt)` when the bytes differ — queue only, never Lua, never
      throw, since `apply` runs off the UI thread under the gob monitor. `AddonManager` gains the
      `sdtEvents` queue on `SessionState`, a `drainSdtEvents` bounded by the queue's size at entry,
      a fire-once gate on `(gobId, bytes)` in the shape of `nativeEdge`, `fireGobSdt` broadcasting to
      every subscriber, and the key in `BUS_KEYS` with its near-miss refusal. `event/bus/world.md`
      gains the row, the payload table and the line saying the first state a gob is given fires it
      too; `bus/README.md` gains the key beside the other world facts; `gob.md`'s sdt section points
      at the edge instead of leaving the reader to sweep.
      *Its suite* subscribes, runs a bounded window while objects stream in, and scores over what
      arrived: that at least one event reached it; that every `ev:sdt()` is the same array shape
      `gob:sdt()` answers and every `ev:gob()` is a Gob; that no `(id, bytes)` pair arrives twice in
      one frame, stamped by an `Update` counter, which is the proof two characters seeing one object
      give one event; that `sub:off()` is followed by silence for the rest of the run; and that
      `hafen.event():on("SdtChanged", fn)` raises, the refusal naming `GobSdtChanged`.
      `[manual]`: stand where a gate or a door is visible and change it — report whether the suite
      printed a **second** line for that same object carrying different bytes.
