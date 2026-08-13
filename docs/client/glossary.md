# Upstream terminology

> The vocabulary of the **upstream `haven` client**: the terms its own code uses, each anchored to the
> class that defines it. The addon layer's own terms are deliberately absent — they are defined by
> `src/io/brodgar/` and by [the AddOn documentation](../addons/README.md), and a third copy is the one
> that goes stale.

## World and game state

- **Hafen** — the codename of `dolda2000/hafen-client`, the open-source Haven & Hearth client
  this project forks.
- **gob** — a *game object*: any entity in the world (player, animal, tree, stone, dropped item,
  building, effect). Class `Gob`. Identified by a `long id` and
  positioned at a `Coord2d rc`.
- **OCache** — the *object cache*, `OCache`: the live set of gobs,
  iterable, keyed by id. The primary world read surface.
- **GAttrib** — a *gob attribute*, `GAttrib`: a typed component
  attached to a gob (e.g. `Drawable`, `Moving`, `GobHealth`). Read via `gob.getattr(Class)`.
- **Drawable** — the `GAttrib` that carries a gob's visual resource; `getres().name` is the gob's
  identity string (e.g. `gfx/kritter/rabbit/rabbit`; `gfx/borka/body` = a player).
- **MCache** — the *map cache*, `MCache`: terrain tiles, heights,
  overlays, organized into grids.
- **tile / grid / segment** — a tile is one `11×11` world-unit cell (`MCache.tilesz`); a grid is
  `100×100` tiles (`MCache.cmaps`) with a **global grid id** (`MCache.Grid.id`, identical for all
  players); grids group into **segments** (`MapFile.Segment`) whose ids are **local per player**.
- **position model (NO global coordinate)** — Hafen obfuscates position: `Gob.rc` is
  **login/session-relative** (starts ~(-10,-10) tiles), not global and not comparable between
  players. The only shareable anchor is **grid id + within-grid offset**; segment ids are local.
- **Coord vs Coord2d** — `Coord` is an integer coordinate (tiles, pixels); `Coord2d` is a double
  world coordinate. `Gob.rc` is a `Coord2d`. Server "click" coords are `Coord2d.floor(posres)`
  where `posres = 11/1024` per unit.
- **Glob** — the *global* game state root, `Glob`: holds `oc` (OCache),
  `map` (MCache), `party`, `ast` (astronomy/time), character attributes (`cattr`), weather/light.
  Reached via `ui.sess.glob`.
- **genus** — the *world / server identity* string passed to
  `GameUI` (`GameUI(String chrid, long plid, String genus)`). Used
  with the character name to scope saved data (`savedata/<genus>_<char>/`).
- **chrid** — the character identifier/name string on `GameUI`.
- **plgob / plid** — the player's own gob id. `MapView.plgob` is the live
  gob id; `MapView.player()` returns the player's `Gob`. `GameUI.plid` mirrors it.

## UI and widgets

- **Widget** — the base UI node, `Widget`. The whole UI is a tree of
  widgets rooted at `RootWidget` (id 0).
- **UI** — the UI root/dispatcher, `UI`: owns the widget-id map, dispatches
  input, and routes server↔client widget messages. Its monitor (`synchronized(ui)`) serializes
  tree mutation.
- **GameUI** — the in-game HUD, `GameUI`: holds `map`, `menu`, `maininv`,
  `equwnd`, `chrwdg`, `chat`, `opts`, `belt` (the action bar), etc., and places server widgets via
  `addchild`.
- **wdgmsg** — a *widget message*, client→server:
  `Widget.wdgmsg(String, Object...)`. The universal action channel.
- **uimsg** — a *UI message*, server→client:
  `Widget.uimsg(String, Object...)`. How the server updates a widget
  (e.g. item count, tooltip).
- **@RName / Factory** — a widget *type registration*, `Widget.RName` +
  `Widget.Factory`. Server widget type strings (`"inv"`, `"wnd"`,
  `"scm"`, …) resolve to factories via `Widget.gettype3` using the
  package-private `Widget.types` map — the widget-replacement seam.
- **bind** — the id↔widget association, `UI.bind` / `getwidget` / `widgetid`.
  A widget must be bound to a server id to send/receive server messages.
- **MenuGrid** — the action/craft menu grid, `MenuGrid` (`@RName("scm")`).
  Actions are **paginae**; invoked via `wdgmsg("act", path...)` or `GameUI.act(...)`.
- **pagina** — one action entry in the MenuGrid (an icon in the action tree), identified by a
  resource or a server id.
- **FlowerMenu** — the radial right-click context menu, `FlowerMenu`
  (`@RName("sm")`). A choice is a **petal**; selected via `wdgmsg("cl", num)`.
- **GItem / WItem** — `GItem` is the server-side item widget (fields `num` and
  `meter`, plus tooltip info); `WItem` is its visible cell inside an
  `Inventory`, and the one that draws both icon numbers — from `info()` as much as
  from those two fields (see `state.md`).
- **ItemInfo** — the item metadata system, `ItemInfo`; `ItemInfo.Name`
  is the display name. Some info (quality) is server-shipped resource code, not a plain field.
- **GOut** — the 2D drawing context, `GOut`, passed to `Widget.draw`:
  `image/text/rect/line/prect/…`.
- **KeyBinding** — the remappable hotkey registry, `KeyBinding`;
  `KeyBinding.get(id, default)` interns a binding persisted under `keybind/<id>`.
- **model / view** — the vocabulary of widget replacement: the *model* is the real server-bound
  widget, kept hidden; the *view* is the custom presentation standing in its place.

## Networking and lifecycle

- **Session / RemoteUI** — `Session` is the connected game session;
  `RemoteUI` is the in-game runner that pumps server messages into the
  UI. `RemoteUI.init(ui)` is where `ui.sess` is bound.
- **rel / RMSG_\*** — reliable ordered server messages (`RMessage`):
  `RMSG_NEWWDG`, `RMSG_WDGMSG`, `RMSG_ADDWDG`, `RMSG_DSTWDG`, `RMSG_GLOBLOB`, …
- **CommandQueue** — `UI.CommandQueue`: server widget operations are deferred
  and dependency-ordered, executed on Loader threads under `synchronized(ui)`.
- **Loading** — an exception (`haven.Loading`) thrown when a resource/gob/grid isn't ready yet; the
  caller retries when it resolves. Any code touching resources or gobs must tolerate it.
- **Loader / Defer** — worker thread pools for deferred work (widget commands, resource loads,
  sprite and mesh building).
