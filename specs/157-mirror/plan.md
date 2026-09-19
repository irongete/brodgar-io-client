# 157 — mirror: plan

## Approach

**One class, one pass, one blit.** `MirrorWidget extends Widget implements Owned, Controls.Source`
is an owned surface of its own kind (not an `AddonWidget`, which is `final` and carries a painted
surface's semantics — `Draw`, `Update`, packing, the column axis — none of which a picture has;
not an `Owned.Control`, whose adapters `LuaWidget.typeName` climbs past to the client class, and
there is no client class here: `:type()` reads `MirrorWidget`, as a bare surface reads
`AddonWidget`). It holds the `LuaWidget` handle the addon passed to `:source(w)`, a `Texture2D`
sized to the source's device box and a `TexRender` over it with the v-flipped 2D blit
`WidgetSurface` already carries, and it draws that texture into its own box, the client's
stock/rule chrome around it as `AddonWidget.draw` paints (`Sheet.chromeOf`: `bg` under, `border`
over).

**The pass** is `WidgetSurface.render`'s recipe, per mirror: `MirrorWidget.renderAll(UI drawn, UI
layer, Render out)` walks a copy-on-write list of live mirrors from `UILoop.display`, right after
`AddonManager.drawSurfaces(ui, buf)` and before the `ui2d` pass — so the texture is written ahead
of the commands that sample it in the same `Render`, and with **no tree monitor held**. For each
mirror that is shown (its own `visible` chain up to its root) and whose source is live
(`LuaWidget.live(handle)`), it takes the **source tree's** monitor (`LuaWidget.monitorOf(src.ui)`),
re-checks the source is still in that tree, (re)creates the texture at `src.sz`, clears it to
transparent, and draws the source as its parent would: `Fonts.frame(src)` around `src.draw(new
GOut(out, base, src.sz))`, then `LuaWidgetOverlay.paint` for a painter an addon hung on it. A
source in a tree the frame did not `gtick` — neither the drawn session's nor the layer's — is
handed a `Widget.GTickEvent` first (`u.dispatch(src, …)`, what `UI.gtick` does for a whole tree):
`Sessions.gtick` feeds a background member's `Glob` alone, and a `PView`'s `TickList` (a
`Composited`'s equipment sprites) is reached only through the widget tree. `Loading` leaves the
last picture standing; any other throwable is logged once until the mirror draws again.

**Why a `PView` draws into it.** `PView.draw` renders its scene into its own colour target sized by
its `FrameConfig` and then `resolve`s that texture into whatever `GOut` it was handed — the
mechanism that lets the virtual-widget feature stand a portrait in the world. The `Avaview` reads
its gob through `ui.sess.glob.oc`, which a background session keeps current (`Composite.comp` is
built in the gob's constructor and posed in `ctick`, which `Sessions.tick` runs for every member).

**The verbs.** `hafen.ui():mirror()` is a `UiApi` builder beside `:image()` (`Controls.image`'s
shape: no arguments, `requireUi`, `UiApi.attach`). `:source(w)` / `:source()` reuse the picture
control's verb: `Controls.source(owner, w, c, v)` gains a `MirrorWidget` branch that resolves `v`
through `LuaWidget.resolve`/`live`, refuses a non-widget, the mirror itself or an ancestor of it
(`self == src || self.hasparent(src)`), and a source over `MirrorWidget.MAX_SIDE` device pixels;
the read answers through `Controls.Source.source()`, which hands back the userdata passed in while
`LuaWidget.live` still answers for it. The box copies `CImg`: `resize` outside the sourcing write
pins it, `:size(nil)` unpins (`LuaWidget`'s `size` verb gains the `MirrorWidget` branch beside the
`CImg` one), `minsz()` stays `null` so `:size(w)` meets the existing refusal.

**Teardown.** `Owned.State.kill` → `root.destroy()` → `dispose()` frees the texture and leaves the
list; the addon's owned registry (`owner.widgets`) already walks every mirror on `:reload` and
disable. The source is never touched, so there is nothing to restore.

## Files to create/modify

- `src/io/brodgar/addon/MirrorWidget.java` — new.
- `src/io/brodgar/addon/UiApi.java` — the `mirror` builder; `DEF_W`/`DEF_H` reachable by it.
- `src/io/brodgar/addon/Controls.java` — `source` write: the mirror branch and its refusals.
- `src/io/brodgar/addon/LuaWidget.java` — `size(nil)`: the mirror's unpin.
- `src/io/brodgar/addon/AddonManager.java` — `drawMirrors(UI, UI, Render)`.
- `src/haven/UILoop.java` — `display`: the call, tagged `// addon:`.
- `src/haven/PView.java` — `draw`: the once-per-frame seam (`lastout`), tagged `// addon:`.
- `docs/addons/api/ui/mirror.md` — new; rows in `ui/README.md`, `api/README.md`, `ui/custom.md`;
  the impact-set lines in `ui/native.md` and `ui/widget.md`.
- `docs/client/widget-draw.md` — the map toll: a row on `PView.draw`'s own target and `resolve`,
  and on `GTickEvent` reaching a `PView`'s `TickList` (upstream facts this plan leaned on that no
  page states).
- `tools/docverbs.py` — `RECEIVERS["mirror"] = "widget"`.
- `addons/157-mirror.1/` — the suite.

## Risks & gotchas

- **Monitors.** `LuaWidget.monitorOf(u)` throws when this thread holds another tree's monitor; the
  pass is issued from `UILoop.display` before `synchronized(ui)`, holding none, and takes one
  source tree at a time. `LuaWidget.live` takes the same monitor briefly; re-entering it inside the
  pass is legal (same tree).
- **Drawn twice is a freeze, not a cost.** The drawn session's own portrait is drawn by the pass
  and by the HUD in one frame, and `GLDrawList.draw` is once per frame by construction: its
  `settingbuf` double buffer (`GLDoubleBuffer.get(0)`) waits for the previous submission to run on
  the GL thread, so the second draw waits for ever holding `synchronized(ui)` — the whole client
  stops, with no error (found by the maintainer's first run; the thread dump shows the UI thread in
  `GLDoubleBuffer.get` under `PView.maindraw` from `GameUI.draw`). `PView.draw` therefore keeps the
  frame's `Render` it last rendered into (`lastout`; `UILoop` makes one per frame) and a second
  draw into the same `Render` only `resolve`s. The pass also `gtick`s each source once per frame.
- **Texture size is the source's.** The mirror scales at the blit, so a 48 px mirror of a 74 px
  portrait samples a 74 px texture. A source that changes box mid-life gets a fresh texture on the
  next pass; the old one is disposed (`TexRender.dispose` takes the sampler and the texture).
- **The blit is flipped**: a render target's first row is its bottom; copy `WidgetSurface`'s
  `render(GOut, float[], float[])` override, not `TexI`'s orientation.
- **A hidden source still draws**: `Widget.draw` does not test its own `visible` (the parent's loop
  does), so a mirror shows a widget the client keeps but hides. Documented, not a defect.
- **`LuaWidgetOverlay.paint` and `Fonts.frame` are the parent loop's two brackets** around a child
  draw; the profiling bracket (`Prof.on`) is left out, since the cost is the mirror's own frame and
  is attributed to it by the layer's draw.
- **`Sessions.gtick` skips the drawn member and `glob` only**: `UI.gtick` is what reaches a
  `PView.gtick`; the drawn UI and the layer get it in `UILoop.Frame.tick`, a member does not.

## Discarded alternatives

- **Re-homing across trees** (`widget:parent(p)` into the layer): the widget reads its own
  session — the `Avaview` its gob through `ui.sess`, a meter its bindings — so it goes dark there;
  the refusal in `LuaWidget.rehomeNative` is the truth, not a limit to lift.
- **A second `Avaview` built by the addon** (a `:clone()` verb): the copy would need the session
  behind it too, and a widget's class is not a picture — each kind would need its own copy.
- **Sampling `PView`'s own colour target** instead of drawing again: private, sized to the
  widget's own frame, and fresh only in a frame that drew the widget — a background portrait's
  would never move.
- **A `mirror` flag on `AddonWidget`**: the class is `final` and a surface's `Draw`, `Update`,
  `pack` and axis are about what the addon paints, none of which a picture has; a kind of its own
  reads back as one (`:type()`).
- **A dirty signature over the source subtree** (`WidgetSurface.needsDraw`'s): a `PView` changes
  every frame and so does anything a mirror is built for; at a portrait's size the pass is a small
  scene render, and the signature walk would cost about as much as it saves.
- **Forwarding presses into the source**: two widgets answering one click is what the
  virtual-widget feature avoids by moving the widget into its surface; a mirror is a picture, and a
  press on it is the mirror's — which is what a dock switching sessions needs.
