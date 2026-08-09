# Codebase map — index

> **Shared by every area.** This file is an INDEX, not a map: one line per subsystem file
> under `specs/codebase/`. **Never read the whole tree** — open only the 1–2 subsystems your
> feature touches. Each subsystem file holds `file:line` anchors (line numbers are indicative;
> the **class + method/field name is the stable anchor**). Max 40 lines.
>
> **Coverage is paid for, not assumed:** if a task has to read `haven` source that no subsystem
> file covers, `/end` writes or extends the matching file (max 70 lines). The reading is paid
> for once.

| Subsystem | Covers |
|---|---|
| [boot-and-loop.md](codebase/boot-and-loop.md) | `main` → login → `RemoteUI.init` → `GameUI`; the per-frame tick/draw loop; the `UI` monitor, Loader/Connection threads and the `Loading` protocol |
| [widgets.md](codebase/widgets.md) | `Widget`/`UI` tree, `@RName` registry, server→create/place/**destroy** seams (unbind ≠ unlink: a `Window` fades, and its death notice arrives before its death), the upward `getparent` walk, tick/draw traversal seams, `GameUI.addchild`, `GOut`, introspection + the read-only hit-test walk, per-frame allocation |
| [widget-input.md](codebase/widget-input.md) | How an event reaches a widget: the `UI` entry points and the **grab that is checked before the tree** (and reaches its owner by `rootpos`), the three different propagation walks (pointer rect-tests, MouseMove **broadcasts**, MouseHover carries a per-child flag), focus **bookkeeping vs delivery** (`FocusedKeyEvent` walks a path from the root — `hasfocus` is the wrong read), the three per-frame queries at a point (hover / tooltip / cursor, and how a derived query writes back on the original), **popup rooting** (three sites, all `ui.root` + `rootpos()`), and the **two drop families** (`DropTarget` things from the root, `DTarget` items from the dragged item's own parent) |
| [gameui-windows.md](codebase/gameui-windows.md) | The windows `GameUI` itself owns: the `Hidewnd` wrappers (created **hidden**; the inventory's packs around its grid and cannot be resized), the menu bars, the one private `togglewnd`/`wndstate` path all seven call sites funnel into, `setgkey` (key and button are ONE click), and **the client's own position store** — `savewndpos` (logout *and* every 60 s), `cdestroy`'s `wndc-misc`, `makewndc`, the `getprefc` reads and `fitwdg`. Also `Window`'s visibility state machine — its fade is a **private** field, not a `Widget.Anim` — and that `Window.draw` is itself a render-to-texture |
| [ui-controls.md](codebase/ui-controls.md) | The client's own **controls**: `SIWidget`'s rasterise-once cache (and that `Widget.resize` does **not** invalidate it), `Button`'s activation/caption/`largep` and its `wdgmsg("activate")` default, `IButton`'s final faces + alpha hit test + the `Resource.loadrimg`/`loadsimg` door a face comes through, the native close `IButton` every window's chrome carries, the resize/destroy/visibility calls an adapter makes, the four plain-`Widget` display controls (`Label`/`Img`/`Progress`/`HRuler` — `ILabel` is NOT an image variant despite the `I` prefix), and `ACheckBox`'s `a`/`click`/`changed` value spine (`CheckBox`/`ICheckBox`, neither `SIWidget`-based) including the mousedown-vs-mouseup window-destroy hazard |
| [ui-chrome.md](codebase/ui-chrome.md) | What draws a **frame**: `Window.deco`/`chdeco` and the `Deco` contract (`iresize`/`contarea` — the ctor's `sz` is the CONTENT size), and `IBox` + the window-less panels (`Frame` and its subclasses, petals, dropdowns) — including that `Frame.around` frames its **siblings** and that a panel's measurements are read at construction |
| [network.md](codebase/network.md) | `Session`/`Connection`, `uimsg` in / `wdgmsg` out, and the full action channel (map clicks, menu acts, item verbs, flower petals) |
| [mapfile.md](codebase/mapfile.md) | The **recorded** map (`MapFile`): the one RW lock and its processor thread, `gridinfo`/`segments` (`BackCache` — evicts and rebuilds), `Segment.grid(...)` handing back a `Defer`-backed `Indir`, `DataGrid`/`Grid`/`Overlay`, `ZoomGrid` and its alignment rule, markers and the `merge` that **re-bases** them |
| [minimap.md](codebase/minimap.md) | The **minimap** that reads it: `sessloc`, the one addition that is the live⇄recorded coordinate bridge, `resolve`'s `tryLock` rule, and how a grid becomes a picture — `DisplayGrid`/`CachedImage` on `Defer`, `MapSource.drawmap`'s 3×3 View at level 0 vs `DataGrid.render` above it, `olrender` for a mask |
| [state.md](codebase/state.md) | Where game state lives: `Glob`, `OCache`/`Gob` (+ `ols`/`addol` and the `GAttrib` lifetime), `MCache`, player, inventory/`GItem`/`ItemInfo`, `CharWnd` attrs, party, time/astronomy |
| [services.md](codebase/services.md) | Console, keybindings, `Resource` (+ code adoption), prefs + Options/`GSettings`, audio, chat, combat, buffs, kin, vitals, FEP/hunger, study, skills/credos/lore, quests, wounds, crafting, action menu (`MenuGrid` paginae), minimap icon registry (`GobIcon.Settings`) |
| [world-3d.md](codebase/world-3d.md) | `MapView` scene, client-only gobs, placement/snapping, pick pass + click intercept, the **ref-counted ground-overlay tags** (`oltags`/`enol`/`disol`, and `prov` vs the map's `realm`), `TexI`/`Material`/`TexRender`, billboards, world quads, Phong lighting, glTF geometry, and **render-to-texture** (the `Streamer`/`HeadlessClient` recipe, the offscreen `GOut`, and the v-flip a render target has against an uploaded image); the **per-drawable placement hook** (`Gob.Placer`/`Drawable.placer()`, re-read every frame by `Placed.autotick`), how to read the camera outside a render pass, and **which ground is actually drawn** (`MapRaster`/`Terrain`'s per-cut display list, ~50-75 tiles wide, and why that is smaller than `MCache.grids`) |
| [render-gl.md](codebase/render-gl.md) | The render backend's own numbers and seams: the scene counters `:stats on` reads (`InstanceList`/`DrawList`/`RenderTree` stats, VRAM pools, programs) and where a frame's draw calls are actually submitted (`GLDrawList.draw`, slot compile, `GLRender.draw`, immediate program binds), plus **the 2D blit path** (`Tex.crender` → `TexRender.render`, texel coords counted from the image top, and `TexRaw`'s invert flag for a render target) |
| [text-and-fonts.md](codebase/text-and-fonts.md) | `Text.Foundry` and every named surface that bakes one, `RichText` `$font`, DPI scaling, custom TTF loading |
| [addon-engine.md](codebase/addon-engine.md) | *(area `addons`)* `src/io/brodgar/addon/` file layout, the `haven` seams it owns, extension points, and the voice-feature integration template |

## Client-wide gotchas

- **One UI thread**: the frame loop is `tick → draw → swap` under `synchronized(ui)`; Loader
  threads apply server messages under the same monitor. Never call into a scripting layer from
  a Connection worker — queue and drain on the tick.
- **`Loading` is a control-flow exception**: any resource/gob/grid read can throw it — swallow
  to nil/partial, or defer to a loader task; never let it escape into user code.
- **Widget creation runs off the UI lock** (Loader) before attach/bind — do tree work in
  `added()`/`attached()`. `Widget.add` links directly (does NOT route through `addchild`).
- **`.res` lifecycle**: resources resolve async (`Resource.remote()`, `Indir.get()` throws
  `Loading` until cached); some `.res` files carry published Java code the fork does not ship —
  never assume a resource's name (servers ship `-alt` variants); read it off the running client.
- **Java changes need `ant` rebuild + full client restart** (no hot-reload). `ant hafen-client`
  is INCREMENTAL — a moved symbol can false-green; `rm -rf build/classes` for a true check.
- **No global world position**: `rc` is login-relative; the shareable anchor is grid id +
  within-grid offset. Grid/segment ids are 64-bit → expose as decimal strings.
- **Windows/Git-Bash**: `;`-separated `-cp` needs `MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`;
  write throwaway test files to the scratchpad, not `/tmp`.
