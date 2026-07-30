# Glossary

> **Status:** 🟢 Living · **Spec:** AddOns
> Hafen client + addon terminology, defined once and referenced everywhere.

## Haven & Hearth / Hafen client terms

- **Hafen** — the codename of the `dolda2000/hafen-client`, the open-source Haven & Hearth
  client this project forks.
- **gob** — a *game object*: any entity in the world (player, animal, tree, stone, dropped
  item, building, effect). Class [`Gob`](src/haven/Gob.java:33). Identified by a `long id` and
  positioned at a `Coord2d rc`.
- **OCache** — the *object cache*, [`OCache`](src/haven/OCache.java:35): the live set of gobs,
  iterable, keyed by id. The primary world read surface.
- **GAttrib** — a *gob attribute*, [`GAttrib`](src/haven/GAttrib.java:33): a typed component
  attached to a gob (e.g. `Drawable`, `Moving`, `GobHealth`). Read via `gob.getattr(Class)`.
- **Drawable** — the `GAttrib` that carries a gob's visual resource; `getres().name` is the
  gob's identity string (e.g. `gfx/kritter/rabbit/rabbit`; `gfx/borka/body` = a player).
- **MCache** — the *map cache*, [`MCache`](src/haven/MCache.java:36): terrain tiles, heights,
  overlays, organized into grids.
- **tile / grid / segment** — a tile is one `11×11` world-unit cell (`MCache.tilesz`); a grid is
  `100×100` tiles (`MCache.cmaps`) with a **global grid id** (`MCache.Grid.id`, identical for all
  players); grids group into **segments** (`MapFile.Segment`) whose ids are **local per player**.
- **position model (NO global coordinate)** — Hafen obfuscates position: `Gob.rc` is **login/
  session-relative** (starts ~(-10,-10) tiles), not global or cross-player. The only shareable
  anchor is **grid id + within-grid offset**; segment ids are local. See
  [coverage-gaps.md](ROADMAP.md) C4 and [[hafen-positioning]].
- **Coord vs Coord2d** — `Coord` is an integer coordinate (tiles, pixels); `Coord2d` is a
  double world coordinate. `Gob.rc` is a `Coord2d`. Server "click" coords are
  `Coord2d.floor(posres)` where `posres = 11/1024` per unit.
- **Glob** — the *global* game state root, [`Glob`](src/haven/Glob.java:34): holds `oc`
  (OCache), `map` (MCache), `party`, `ast` (astronomy/time), character attributes (`cattr`),
  weather/light. Reached via `ui.sess.glob`.
- **genus** — the *world / server identity* string passed to
  [`GameUI`](src/haven/GameUI.java:257) (`GameUI(String chrid, long plid, String genus)`). Used
  with the character name to scope saved data (`savedata/<genus>_<char>/`).
- **chrid** — the character identifier/name string on `GameUI`.
- **plgob / plid** — the player's own gob id. [`MapView.plgob`](src/haven/MapView.java) is the
  live gob id; `MapView.player()` returns the player's `Gob`. `GameUI.plid` mirrors it.

## UI / widget terms

- **Widget** — the base UI node, [`Widget`](src/haven/Widget.java). The whole UI is a tree of
  widgets rooted at [`RootWidget`](src/haven/RootWidget.java) (id 0).
- **UI** — the UI root/dispatcher, [`UI`](src/haven/UI.java): owns the widget-id map, dispatches
  input, and routes server↔client widget messages. Its monitor (`synchronized(ui)`) serializes
  tree mutation.
- **GameUI** — the in-game HUD, [`GameUI`](src/haven/GameUI.java): holds `map`, `menu`,
  `maininv`, `equwnd`, `chrwdg`, `chat`, `opts`, `belt` (the action bar), etc., and places server widgets via
  `addchild`.
- **wdgmsg** — a *widget message*, client→server:
  [`Widget.wdgmsg(String, Object...)`](src/haven/Widget.java:737). The universal action channel.
- **uimsg** — a *UI message*, server→client:
  [`Widget.uimsg(String, Object...)`](src/haven/Widget.java:677). How the server updates a
  widget (e.g. item count, tooltip).
- **@RName / Factory** — a widget *type registration*, [`Widget.RName`](src/haven/Widget.java:56)
  + [`Widget.Factory`](src/haven/Widget.java:142). Server widget type strings (`"inv"`, `"wnd"`,
  `"scm"`, …) resolve to factories via [`Widget.gettype3`](src/haven/Widget.java:169) using the
  package-private [`Widget.types`](src/haven/Widget.java:51) map — the widget-replacement seam.
- **bind** — the id↔widget association, [`UI.bind`](src/haven/UI.java) / `getwidget` /
  `widgetid`. A widget must be bound to a server id to send/receive server messages.
- **MenuGrid** — the action/craft menu grid, [`MenuGrid`](src/haven/MenuGrid.java) (`@RName("scm")`).
  Actions are **paginae**; invoked via `wdgmsg("act", path...)` or `GameUI.act(...)`.
- **pagina** — one action entry in the MenuGrid (an icon in the action tree), identified by a
  resource or a server id.
- **FlowerMenu** — the radial right-click context menu, [`FlowerMenu`](src/haven/FlowerMenu.java)
  (`@RName("sm")`). A choice is a **petal**; selected via `wdgmsg("cl", num)`.
- **GItem / WItem** — [`GItem`](src/haven/GItem.java) is the server-side item widget (has count
  `num`, wear `meter`, tooltip info); [`WItem`](src/haven/WItem.java) is its visible cell inside
  an [`Inventory`](src/haven/Inventory.java).
- **ItemInfo** — the item metadata system, [`ItemInfo`](src/haven/ItemInfo.java); `ItemInfo.Name`
  is the display name. Some info (quality) is server-shipped resource code, not a plain field.
- **GOut** — the 2D drawing context, [`GOut`](src/haven/GOut.java), passed to `Widget.draw`:
  `image/text/rect/line/prect/…`.
- **KeyBinding** — the remappable hotkey registry, [`KeyBinding`](src/haven/KeyBinding.java);
  `KeyBinding.get(id, default)` interns a binding persisted under `keybind/<id>`.

## Networking / lifecycle terms

- **Session / RemoteUI** — [`Session`](src/haven/Session.java) is the connected game session;
  [`RemoteUI`](src/haven/RemoteUI.java) is the in-game runner that pumps server messages into
  the UI. `RemoteUI.init(ui)` is where `ui.sess` is bound.
- **rel / RMSG_*** — reliable ordered server messages ([`RMessage`](src/haven/RMessage.java)):
  `RMSG_NEWWDG`, `RMSG_WDGMSG`, `RMSG_ADDWDG`, `RMSG_DSTWDG`, `RMSG_GLOBLOB`, …
- **CommandQueue** — [`UI.CommandQueue`](src/haven/UI.java): server widget operations are
  deferred and dependency-ordered, executed on Loader threads under `synchronized(ui)`.
- **Loading** — an exception (`haven.Loading`) thrown when a resource/gob/grid isn't ready yet;
  the caller retries when it resolves. Any code touching resources/gobs must tolerate it.
- **Loader / Defer** — worker thread pools for deferred work (widget commands, resource loads /
  sprite & mesh building).

## AddOn-system terms (this spec)

- **Engine** — `AddonManager` in `io.brodgar.addon`: discovery, load/enable, tick pump, event
  dispatch, reload, watchdog.
- **Bridge** — the thin Java layer (`io.brodgar.addon.api.*`) that wraps `haven.*` and exposes
  the `hafen.*` Lua facade; owns every resource an addon creates.
- **Facade / SDK** — the stable `hafen.*` Lua API addons target.
- **Addon-root widget** — an invisible widget attached to `ui.root` whose `tick` drives the
  engine on the UI thread, and under which addon overlays are drawn.
- **Model / View (widget replacement)** — the *model* is the real server-bound widget (kept
  hidden); the *view* is the addon's custom presentation. See
  [08-widget-replacement.md](design/08-widget-replacement.md).
- **Owned-resource registry** — the per-addon set of created widgets/overlays/subscriptions/
  timers/keybinds the bridge tracks so disable/reload can release them.
