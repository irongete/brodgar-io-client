# The client's internals

A map of the upstream `haven` engine: where each subsystem lives, what owns what, and the gotchas
that cost time. It is for someone changing the client's own source. **Writing an addon needs none
of it** — that is [the AddOn documentation](../addons/README.md).

The engine is around 100k lines of unannotated Java. This subtree exists so that finding a seam is a
lookup instead of a search.

> **A map, not an authority.** When a page here disagrees with `src/`, `src/` wins — and the task
> that noticed fixes the page. Class and member names are the anchors; `grep -n` gives you the line.

## The pages

**Never read the tree.** Open the one or two subsystems the work touches.

| Page | Covers |
|---|---|
| [terminology](glossary.md) | the words the engine's own code uses — gob, grid, pagina, wdgmsg, `Loading` — each anchored to the class that defines it |
| [boot and the frame loop](boot-and-loop.md) | `main` → login → `RemoteUI.init` → `GameUI`; the per-frame tick/draw loop; the `UI` monitor, Loader/Connection threads and the `Loading` protocol; the profiling seams; building a `UI` headlessly |
| [the widget system](widgets.md) | the `Widget`/`UI` tree, the `@RName` registry, the create/place/**destroy** seams (unbind ≠ unlink: a `Window` fades, and its death notice arrives before its death; entry runs under the tree's monitor), `GOut` |
| [reading a widget](widget-introspection.md) | the read-only walk over a live tree: `children()` vs the recursive `children(Class)`, the server id, text sources, the hit-test walk and `xlate`/`parentpos`, and who resizes the root |
| [the tick and draw traversals](widget-draw.md) | how a frame reaches a widget: the two recursion seams, why an override of `draw` may paint no child at all, where the screen `GOut` is built, and what a frame allocates |
| [widget input](widget-input.md) | how an event reaches a widget: the grab checked *before* the tree, the three propagation walks, focus bookkeeping vs delivery (`hasfocus` is the wrong read), the per-frame point queries, popup rooting, and the two drop families |
| [GameUI's own windows](gameui-windows.md) | the `Hidewnd` wrappers, the menu bars, the one private `togglewnd`/`wndstate` path, and the client's own window-position store |
| [chrome](ui-chrome.md) | what draws a **frame**: `Window.deco` and the `Deco` contract (the ctor's `sz` is the CONTENT size), `IBox`, and the window-less panels |
| [controls](ui-controls.md) | `SIWidget`'s rasterise-once cache, `Button`, `IButton`, `ACheckBox`'s value spine, the four plain display controls, `RadioGroup` |
| [panels and tabs](ui-panels.md) | `Tabs`, which is not a widget, and `OptWnd`'s panel model: the caption that follows the panel, the list that is the navigation, and where a re-fit stops |
| [lists, text and scrolling](ui-lists.md) | `HSlider`/`Scrollbar`, `TextEntry` and its `ReadLine`, `Scrollport`, and the model-backed family — `SListWidget`/`SListBox`, `SDropBox`, `GridList`, `TableBox` |
| [state roots](state.md) | where game state lives: `Glob`, `OCache`/`Gob` and the `GAttrib` lifetime, `MCache`, player, inventory and `ItemInfo`, party, time |
| [networking](network.md) | `Session`/`Connection`, `uimsg` in and `wdgmsg` out, and the full action channel |
| [the map database](mapfile.md) | the **recorded** map: the one RW lock and its processor thread, `gridinfo`/`segments`, `ZoomGrid`, markers and the `merge` that re-bases them |
| [the minimap](minimap.md) | the live ⇄ recorded coordinate bridge (`sessloc`), `resolve`'s `tryLock` rule, and how a grid becomes a picture |
| [the 3D world](world-3d.md) | the `MapView` scene, a client-only gob in it, ground overlays over the terrain, materials, billboards, glTF and render-to-texture |
| [the pointer and the ground](map-click.md) | screen ↔ world and the space both speak, placement snapping, the pick pass, and the click dispatch a synchronous hook gets in front of |
| [which ground is drawn](terrain-raster.md) | the terrain display lists: the one bolted to the player, one over a second cache, and what bounds each |
| [the camera](camera.md) | reading the installed camera outside a render pass, the name→class registry and the two prefs, and the frustum's two traps |
| [the render backend](render-gl.md) | the scene counters `:stats on` reads, where a frame's draw calls are submitted, and the 2D blit path |
| [several sessions at once](multi-session.md) | one process holding several logged-in sessions with one on screen: the drawn-UI split, what a session that is not drawn stops doing, aligning two sessions' coordinate frames, ordering one that is not drawn, and the merged scene |
| [text and fonts](text-and-fonts.md) | `Text.Foundry` and every named surface that bakes one, `RichText`, DPI scaling, custom TTF loading |
| [UI scaling](ui-scaling.md) | the one factor the whole 2D interface is drawn at: `UI.scalef` and its converters, where the number comes from, art that is scaled at load, and the round-trip that is exact in only one direction |
| [the chat](chat.md) | `ChatUI` and its channels: where a line arrives, the three argument shapes one message name wears, what selects a tab, and what is never trimmed |
| [the console](console.md) | the `:` command line: what registers a command, the three tiers a name resolves in, what a command that throws does, and which thread its body runs on |
| [resources](resources.md) | what a `.res` carries: reading a layer by class, predicate or id, the `id == null` gotcha, and the `OD_RES` delta that carries a gob's resource and state bytes under the gob monitor |
| [services](services.md) | keybindings, `Resource` and code adoption, prefs and Options, audio, combat, buffs, kin, vitals, study, quests, crafting, the action menu, minimap icons |

## Client-wide gotchas

- **One UI thread, one monitor per session.** The frame loop is `tick → draw → swap` under
  `synchronized(ui)`, and Loader threads apply that session's server messages under that same
  monitor — but there is a `UI` per session and so a monitor per session, and the one guarding a
  widget is `w.ui`'s ([multi-session.md](multi-session.md)). Never call into a scripting layer from
  a Connection worker — queue and drain on the tick.
- **`Loading` is control flow, not an error.** Any resource, gob or grid read can throw it. Swallow
  it to nil or partial, or defer to a loader task; never let it escape into user code.
- **Widget creation runs off the UI lock** (on a Loader) before attach and bind, so tree work belongs
  in `added()`/`attached()`. `Widget.add` links directly and does **not** route through `addchild`.
- **`.res` resolve asynchronously.** `Indir.get()` throws `Loading` until cached, and some `.res`
  files carry published Java code this fork does not ship. Never assume a resource's name — servers
  ship `-alt` variants; read it off the running client.
- **There is no global world position.** `Gob.rc` is login-relative. The shareable anchor is a grid
  id plus a within-grid offset, and grid and segment ids are 64-bit — expose them as decimal strings.

## Writing a page

The standard is [`DOCUMENTATION.md`](../../DOCUMENTATION.md) §12, and it is six rules:

| Rule | |
|---|---|
| Never a line number | cite the class and the member — `MapView.click`, `Session.sendmsg` |
| Map, not narrative | tables of where a thing lives; prose only for lifecycle, threading, ownership |
| Upstream `haven` only | never `io.brodgar` — that is already `src/` and the AddOn docs |
| Gotchas live on their subsystem's page | not in a tier of their own, and only when they cost time |
| A page is born from need | no completeness goal; a missing subsystem is one nobody has needed yet |
| Ceiling 150 lines | corrected in place, never appended to |
