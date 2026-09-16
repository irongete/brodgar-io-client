# 153 — A window's box: the content read, the client's grip, the cancelable close

## What & why

An addon's window has one box everything writes — `widget:size(w, h)`, a sheet's `size` rule, a
remembered place, a `widget:resizable(h)` gesture — and that box is the **content area**. The one
read of it, `widget:size()`, answers the **frame** instead, so `w:size(w:size().w, w:size().h)`
grows a window by its chrome and nothing reads back what was written; `Resized` reports the same
frame, as `ev:x()/ev:y()`. Beneath that, a resize the addon did not write by hand never reaches the
**canvas** the window paints on: `haven.Window.resize` sizes the frame and tells its children
nothing, so after a gesture or a rule `Draw` paints the old box, clipped. The client's own corner grip
cannot be switched on for an addon window at all, though the decoration draws it and a theme's
`sizer` rule dresses it. And the frame's close button destroys the window with no say for the addon,
so a toggle by `:visible()` dies at the first X.

This feature makes the box one thing: read what you write, the canvas follows every resize, the
client's grip is a switch, `Close` cancels, and `remember` keeps an addon window's box.

## Acceptance criteria

1. **The read is the write's box.** `widget:size()` answers `LuaWidget.sizeArg`: a window's content
   area, any other widget's box. `widget:info().size` is the same. `w:size(w:size().w, w:size().h)`
   changes nothing on a window.
2. **The frame is still readable.** `widget:chrome()` on a window wearing the client's decoration
   carries `frame` (`{w, h}`, the outer box) and `content` (`{x, y, w, h}`, the content area from the
   frame's top-left), always, beside the ornaments it already reports.
3. **The canvas follows every resize.** After a size rule, a remembered box, a `widget:resizable(h)`
   gesture or the client's grip, the window's canvas is its content area: `Draw`'s `ev:w()/ev:h()`
   read the new box on the next frame.
4. **`widget:resizable(true|false)`** switches the client's own corner grip on a window this addon
   built; the content box follows the drag live, a standing size level follows it, the landing is
   remembered under `widget:remember` and `Resized` fires on release. `widget:resizable()` reads `true`
   while the grip is on, else the armed handle, else `nil`. Refused on a bare surface and on a window
   the client built, naming `widget:resizable(h)`; a non-boolean, non-widget argument is refused
   naming both forms.
5. **`Resized` is its own shape**: `ev:w()`/`ev:h()`, the content box as `widget:size()` reads it, from
   a gesture and from the grip alike. `Dragged` keeps `ev:x()`/`ev:y()`.
6. **`Close` cancels.** The handler receives an `ev` with `preventDefault()`; cancelled, the window
   stands and the handlers own what the X means; left alone, it is destroyed when they return.
7. **A remembered owned window keeps its box.** `widget:remember(name)` records an owned window's
   content box whether or not a level stands on it, unless the surface is packed; a borrowed widget's
   box is recorded only where this addon named a size. `widget:position(x, y)` and `widget:size(w, h)`
   on an owned widget re-point a standing level rather than leaving it to snap the widget back.

## Out of scope

- **The grip on a window the client built**: its decoration is the client's, its box is saved by the
  client's own store, and `widget:resizable(h)` already reaches it.
- **A minimum box for the grip beyond one design pixel**: an addon that wants one writes it from
  `Resized`.
- **The layer's own search verbs** (filed: 074) — unchanged.

## Docs impact

- **Written**: `docs/addons/api/ui/custom.md` (rewritten: the two builders, the setters with
  `:resizable(true)`, the events on a surface with `Close`'s cancel and `Resized`'s `w`/`h`);
  `docs/addons/api/ui/widget.md` (`:size()`, `:size(w, h)`, `:children()`, `:chrome()`, `:rootPos()`
  rows); `docs/addons/api/ui/native.md` (`:remember`, `:resizable`, `:draggable` rows).
- **Derived impact set**: `grep -rn "size()" docs/addons/api/ui/` → `widget.md`, `native.md`,
  `custom.md` (the rows above); `grep -rn "Resized\|Close" docs/addons/api/ui/` → `custom.md`,
  `container.md` (`Removed` only, unchanged).

## Context files

- `docs/addons/api/ui/custom.md`, `docs/addons/api/ui/widget.md`, `docs/addons/api/ui/native.md` — 1
- `DOCUMENTATION.md` — 1
- `src/haven/Window.java` (`DefaultDeco.mousemove/mouseup`, `resize`, `csz`, `ca`) — 1
- `src/io/brodgar/addon/UiApi.java` (`newUi`'s anonymous `Window`, `reqclose`) — 1
- `src/io/brodgar/addon/LuaWidget.java` (`size`, `position`, `resizable`, `chromeTable`, `snapshot`,
  `rememberCapture`, `chromeDragged`, `rememberLanded`, `sizeArg`) — 1
- `src/io/brodgar/addon/AddonWidget.java` (`closed`, `draw`) — 1
- `src/io/brodgar/addon/LuaEvent.java` (`Shape`, `gesture`, `meta`) — 1
- `src/io/brodgar/addon/Gesture.java` (`Move`, `write`, `fire`) — 1
- `src/io/brodgar/addon/Layout.java` (`half`, `resize`) — 1
- `src/io/brodgar/addon/StoreApi.java` (`land`, `placement`) — 1
