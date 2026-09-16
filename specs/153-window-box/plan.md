# 153 — Plan

## Approach

**One box.** Everything that writes a window's size already speaks its content area:
`LuaWidget.sizeArg(w)` is `csz()` on a `Window`, and `Layout.half`, `Gesture.Move.doff`,
`rememberCapture`/`rememberLanded`, `rule:size` and `widget:size(w, h)` all go through it. The read
alone spoke `w.sz`. So the read moves to `sizeArg` — the verb, the snapshot, the `Resized` payload —
and the frame is published where the frame's ornaments already are: `widget:chrome()` gains
`frame` (`wnd.sz`) and `content` (`wnd.ca()`), so an inspector still boxes a whole window from
`rootPos()` and `chrome().frame`.

**The canvas follows the frame.** `haven.Window.resize` → `resize2` → `deco.iresize` and
`ch.presize()` on every child, and `Widget.presize` is empty. The addon's window is an anonymous
`Window` subclass in `UiApi.newUi`; it overrides `resize(Coord)` and, after `super.resize`, sizes
`content` to `csz()` when the two differ and the canvas is already its child (the superclass
constructor resizes before it is added). `packSurface` sizes the canvas to that very box a moment
later and finds nothing to do; `cresize(content)` is already skipped at the repack seams.

**The client's grip is a hook.** `Window.DefaultDeco` drives `((Window)parent).resize` from its own
grab; it now also calls `Window.resizedByHand(false)` after each move and `resizedByHand(true)` on
release — an empty method on the stock window, tagged `// addon:`, overridden by the addon's window:
floor the content at one design pixel each way (the Lua gesture's own rule), `LuaWidget.chromeResized`
(the size twin of `chromeDragged`: a standing level follows the hand), and on release
`rememberLanded(owner, w, false)` and `AddonWidget.resized()`, which fires `Resized` with `sizeArg`.
`widget:resizable(true|false)` sets `DefaultDeco.dragsize` on the deco of a window this addon built
(`SkinDeco` inherits the grip whole and carries the flag across its swaps); the read answers `true`,
else the armed handle, else `nil`. A borrowed window and a bare surface refuse naming
`widget:resizable(h)`.

**`Resized` is its own shape.** `LuaEvent.Shape.RESIZED` answers `w`/`h`; `Gesture.fire` mints it
for `Mode.SIZE` with `sizeArg(target)` and keeps `GESTURE` (`x`/`y`) for `Mode.DRAG`.

**`Close` cancels like `Drop`.** `AddonWidget.closed()` mints a `Subs.Cancel`, fires `Close` with a
`Shape.CLOSE` event whose one verb is `preventDefault`, and answers whether any handler cancelled;
`UiApi`'s `reqclose` destroys only on `false`.

**The level follows the hand, and the box is remembered.** `LuaWidget.levelFollows(owner, w, pos)`
re-points a standing `wantPos`/`wantSize` at where an owned widget now is; `chromeDragged`,
`chromeResized`, and the owned branches of `widget:position(x, y)` and `widget:size(w, h)` call it.
`rememberCapture` records an owned window's box unless its surface is `packed`, keeping the level
gate for borrowed widgets.

## Discarded alternatives

- **Keep `size()` on the frame and make the write take the frame**: the rule `size`, the gesture,
  `remember` and `pack` all speak content, and a frame's inset differs per theme; converting at every
  write would spread the chrome's arithmetic through the cascade.
- **A `widget:bounds()` verb for the outer box**: one more read for a fact the decoration already
  reports; `chrome().frame` beside `caption`/`plate`/`sizer`/`close` is where the frame's geometry lives.
- **A floating grip widget of the addon's own in the layer**: the window raises above it on the next
  click, and nothing in the API raises a widget.
- **`Close` as a hide by default**: the X on a window of yours has always destroyed it, and every
  addon rebuilds on toggle; a cancel keeps that contract and hands the choice to the handler.
