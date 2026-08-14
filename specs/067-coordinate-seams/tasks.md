# 067 — Coordinate seams: tasks

- [x] **067.1 — The projection verbs answer and take root design pixels.** Adds `Px.out(double)`, the
      unrounded mirror of `in(double)`. `CharApi.worldToScreen` adds the map view's `Widget.rootpos()` to
      `MapView.screenxf`'s device `Coord3f` and puts both axes through `Px.out`;
      `WorldApi.screenToWorld` does the inverse before `MapView.Maptest` — `Px.in`, then subtract
      `rootpos()`. `AddonManager.xy` still converts nothing.
      *Its suite* asserts the round trip that needs no eye: for the player's own position, and for two
      points offset a few tiles away, `screenToWorld(worldToScreen(p))` comes back within one tile —
      `screenToWorld` is asynchronous, so it retries on a timer for a bounded window and scores what it
      reached. It asserts the projected player point lies inside `hafen.ui():root():size()`, and that
      `worldToScreen` refuses a `{x=, y=}` table naming Position. It duplicates nothing from another
      suite.
      `[manual]`: with Interface scale at 1.0 and again at 1.5 (a restart each), run it and read the line
      — expect the same verdicts, and the drawn dot sitting on your character's feet both times.

- [x] **067.2 — A raw argument can say which space it is in.** Adds `LuaEvent.coordArg` and the two
      readers in `common()`, which serves the ACTION and MESSAGE shapes alone: `ev:position(i)` undoes
      `OCache.posres` into a Position, `ev:pixel(i)` puts the pair through `Px.out` into the sender's own
      design pixels. Both join the two vocabulary strings `Retired.closedIndex` prints.
      *Its suite* subscribes `action` on `click`, and on the click the maintainer makes asserts
      `ev:position(2)` is durable and within a few tiles of the player, that `ev:pixel(1)` lies inside the
      root box, and that `ev:args()` is **unchanged** — `args[2].x` still reads the raw wire number, so
      nothing was converted underneath. It asserts `ev:position(3)` raises, the button being an integer,
      and that the message names `:pixel(i)` in that refusal. It re-clicks through `ev:resend()` and
      asserts the character still walks.
      `[manual]`: left-click the ground once when the suite says to — expect the character to walk there
      exactly as it does with no addon loaded.

- [ ] **067.3 — A Position can be written back to the server.** `LuaMarshal.toJava`'s `TUSERDATA` branch
      resolves a `LuaPosition` and encodes `rc.floor(OCache.posres)`, raising for one this session cannot
      locate; the `TTABLE` branch keeps taking `{x=, y=}` so hand-built argument tables still work.
      *Its suite* intercepts `click`, replaces argument 2 with
      `hafen.world():snapPlace(ev:position(2))` and re-sends through `ev:send(a)`, then asserts the
      character's resting position is the snapped tile centre rather than the raw click point — polling
      for arrival over a bounded window. It asserts that a Position rebuilt from a grid this session has
      not located raises on `ev:send`, and that the refusal says the place has no coordinate. It asserts
      a `{x=, y=}` table still round-trips unchanged through `ev:args()` → `ev:send`.
      `[manual]`: click a few tiles away — expect the character to stop at the centre of that tile, not
      at the pixel you clicked.

- [ ] **067.4 — The pages say which space every coordinate is in.** Rewrites the `worldToScreen` row and
      paragraph in `api/player.md`, the `screenToWorld` row and the "screen to world" section in
      `api/world.md`, and `api/ui/mouse.md`'s grab example, which is correct only after 067.1. In
      `api/event.md`, the `:args()` row states raw protocol in wire units, the two readers get their rows,
      and a note records that `click` is not the map's alone. `api/ui/pixels.md` adds the projection verbs
      to its "one space" list. `docs/client/world-3d.md` already carries the space `MapView.screenxf`
      and `Maptest` speak — the map toll 067.1 paid — so this task adds nothing there. Discharges the
      spec's impact set, `api/overlay.md` included, with its reason.
      *Its suite* runs `api/ui/mouse.md`'s grab example verbatim and asserts the Position it hands back is
      within a tile of the ground under the cursor — the page's own claim, executed. It asserts every
      `hafen.*` name the touched pages add resolves to a callable.
      `[manual]`: take the grab, drag across the ground and release — expect the read-out to track the
      cursor with no offset at either interface scale.
