# 006-custom-ui — Spec

## What & why
The addon **drawing surface**: addons create their own client-side UI — draggable titled
windows and bare widgets with per-frame draw/tick/mouse callbacks — and paint **outside**
widgets too: HUD overlays on top of the whole interface, and world-space overlays pinned over
game objects (the SpeakerIcon pattern). One canonical draw wrapper (`g`, over `GOut`) serves
all three (D-013). Design: [design/07-ui-and-drawing.md](../design/07-ui-and-drawing.md).

## Acceptance criteria (verified in-game)
- [x] `hafen.ui.window{opts}` → a draggable, titled `haven.Window` (the Phase-2 "draggable
      custom window" DoD): drag by title bar, body clicks counted + consumed (truthy return),
      X fires `onClose` then destroys, handle ops (`:move/:show/:hide/:size/:destroy`) work
      from the REPL; `hafen.ui.widget{opts}` = the bare variant.
- [x] `g` wrapper in `onDraw(g,w,h)`: text/atext/rect/frect/line/prect/color; per-primitive
      color state; **inert outside the callback** (a stashed `g` no-ops — can't corrupt the
      pipeline); live vitals bars redraw every frame.
- [x] `hafen.ui.overlay(fn)` paints over the entire HUD (top-centre readout + exact-centre
      crosshair in `hello`); `hafen.ui.gobOverlay(filter, fn)` draws a marker + label over
      every matching gob's head, tracking through camera moves; filter = name-substring string
      or function over the `gob.info` snapshot.
- [x] All three are bridge-owned: `:reload` / disable destroys windows and removes both
      overlay kinds with no leftovers; `hello` v0.13.0→v0.14.0 exercises everything.
- [x] `OnEnterWorld` timing fix (found in this feature's first in-game run): the event now
      waits for `GameUI` to be **attached to `ui.root`** — a window created in the handler is
      visible on a fresh login, not only after `:reload`.

## Out of scope
- `g:image`/`g:resource` (→ [012](../012-custom-rendering/spec.md) / [014](../014-ui-extensions/spec.md));
  keyboard/focus/grab, `UI.scale` HiDPI (→ later); hooks (→ 007); draw-time CPU budget and
  per-overlay anchor offset (→ ROADMAP).

## Context files
- `design/07-ui-and-drawing.md` — the UI/drawing design
- `src/io/brodgar/addon/LuaWidget.java`, `LuaGOut.java`, `LuaGobOverlay.java` — the three new classes
- `src/io/brodgar/addon/UiApi.java` (post-split home of the `hafen.ui` facade), `Addon.java`
  (owned lists `widgets`/`hudOverlays`/`gobOverlays`)
- `src/haven/GOut.java`, `Window.java`, `UI.java` (`drawafter`), `SpeakerIcon.java`,
  `PView.java` (`Render2D`) — the public backings
- `docs/addons/api/ui.md` — shipped surface
- `../005-sandbox-reload-panel/` — the teardown/watchdog this plugs into
