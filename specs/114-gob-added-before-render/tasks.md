# 114 — tasks

- [x] **114.1 — Hold a gob out of the render tree until its GobAdded has fired.** Adds
      `Gob.addonpend` (`volatile boolean`, `// addon:`), set in `AddonManager`'s
      `OCache.ChangeCallback.added` beside the existing enqueue and only while some addon holds a
      `GobAdded` subscription, cleared per **copy** in `drainGobEvents` after the settle loop. Adds a
      `Loading` subclass over one `Waitable.Queue`, `wnotify`d at the end of the drain, and the gate
      at the head of `Gob.added(RenderTree.Slot)` that throws it. Adds the two releases that keep the
      world drawable: the `gobRescans` pass clears the flag on a dead session's copies, and a
      wall-clock deadline swept in `layerTick` releases anything held past it. Also adds
      `render():gobsHeld` (`AtomicLong`, through `ProfHandle`). Writes `docs/client/boot-and-loop.md`
      (`Loading` as a parking primitive, `Waitable.Queue` behind it, `Gob.updwait` as precedent) and
      the `counters.md` row. The disarmed path — nothing subscribed, nothing held — is verified by
      **reading the site**, because a suite subscribes and so cannot observe its own absence.
      *Its suite* subscribes to `GobAdded`, records every firing over a bounded window, and
      reconciles that set against `s:world():gob():list()` — every object drawn was announced, once.
      It asserts `gobsHeld` climbs across that window and that `gob:name()`, `gob:sdt()` and
      `gob:hitbox()` answer inside the handler for a resource-drawn object, which is what proves the
      hold cost no data. It `pcall`s `gob:overlay():add(key)` from a `GobAdded` handler and asserts it
      did **not** raise *not renderable yet*. It stands a ghost (`hafen.vr()`) —
      a client-only gob the layer never queues — and asserts `:info().drawn` becomes true within a
      bounded window, which is what proves the gate holds only what it was told about.
      `[manual]`: with the suite scaling every arriving object to nothing on `GobAdded`, walk into
      ground you have not seen this session — report whether anything appeared at full size, even for
      an instant, and whether the world drew any slower.

- [x] **114.2 — State the guarantee where an addon reads it.** Writes the promise onto
      `docs/addons/api/event/bus/world.md` (`GobAdded` runs before the object's first drawn frame,
      and what is and is not populated by then — a `Composite` still answers `nil`) and onto
      `docs/addons/api/threading.md`, whose step row now carries an ordering promise it did not have.
      Corrects the two sentences the guarantee makes false: `docs/addons/api/overlay.md:107`, where
      "attach from `GobAdded` or a timer instead" stops being a workaround and becomes the rule, and
      the matching refusal text inside `LuaGobOverlay.ensure`, which names `GobAdded` as an escape
      from a case that can no longer arise there.
      *Its suite* drives the documented promise rather than restating it: it attaches an overlay from
      a `GobAdded` handler and asserts the key reads back through `gob:overlay():get(key)` in that
      same handler, and it asserts a refusal it *can* still reach — `gob:overlay():add()` on a gob
      that has left — names the gob and not renderability. It duplicates 114.1's reads-at-handler-time
      assertions rather than assuming that suite was ever run.
      `[manual]`: read the three edited passages and confirm each says what the client now does.

- [x] **114.3 — `gob:visible(b)`: an object the client draws, or does not.** Adds
      `Gob.addoninvis` (`volatile boolean`, `// addon:`) read by 114.1's gate, which attaches every
      `RenderTree.Node` attrib **except** the `Drawable` while it is set. Adds the `visible` verb to
      `LuaGob` — `gob:visible()` reads, `gob:visible(b)` writes and hands the Gob back — with the
      revoke path for an object already in a tree (`RUtils.multirem(d.slots)` to hide,
      `RUtils.multiadd(gob.slots, d)` to show). Records the owner in `GobIntent` beside `scaleOwner`,
      so a session that loads the object afterwards draws it hidden too, and extends
      `UiApi.teardownGobScales` so a `:reload` or a disable puts back everything the addon hid.
      Writes `docs/addons/api/gob.md` and the `visible` field on the Gob snapshot in
      `docs/addons/api/types/world.md`, and fixes `gob.md:37` — there are three writes now, not two.
      *Its suite* round-trips the pair on a live object: `visible()` is true to begin with, `false`
      after a write, true again after the next, and the verb hands the Gob back so the call chains.
      It asserts composition — `scale(2)` then `visible(false)` then `visible(true)` leaves
      `scale()` at 2 — and `pcall`s `gob:visible(nil)` and `gob:visible(0)`, asserting each raised and
      that the message names the argument. It hides an object, reads `gob:exists()` still true, and
      shows it again, so the record's lifetime is proved not to be the object's.
      `[manual]`: with the suite holding one nearby object hidden, confirm it is gone from the scene
      and that clicking where it stands reaches the ground; then let the suite show it and confirm it
      is back at the size it had. Walk into unseen ground with the suite hiding trees on `GobAdded` —
      report whether any tree appeared, even for an instant.
