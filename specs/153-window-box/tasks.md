# 153 — Tasks

- [x] **153.1 — The content read, the client's grip and the cancelable close.** `haven.Window`: the
      `resizedByHand(done)` hook, called from `DefaultDeco.mousemove` (after the grip's `resize`) and
      `mouseup`. `UiApi.newUi`'s window: `resize(Coord)` sizes the canvas to `csz()`; `resizedByHand`
      floors the box at one design pixel, `LuaWidget.chromeResized`, and on release `rememberLanded`
      and `AddonWidget.resized()`; `reqclose` destroys only when `closed()` answers `false`.
      `AddonWidget.closed()` fires `Close` with a cancel and answers it; `resized()` fires `Resized`
      with `sizeArg`. `LuaEvent`: `Shape.RESIZED` (`w`, `h`) and `Shape.CLOSE` (`preventDefault`),
      `resized(owner, w, h)`, `close(owner, c)`; `Gesture.fire` mints `RESIZED` for `Mode.SIZE` with
      `sizeArg`. `LuaWidget`: `size()` and `snapshot` read `sizeArg`; `resizable(true|false)` on an
      owned window's `DefaultDeco`, the read answering `true`/handle/`nil`, refusals naming
      `widget:resizable(h)`; `chromeTable` gains `frame` and `content`; `levelFollows` behind
      `chromeDragged`, `chromeResized` and the owned branches of `position(x, y)`/`size(w, h)`;
      `rememberCapture` records an owned, unpacked window's box. Comments naming `Tick`, `find`/`all`
      and the retired `:onX` setters re-pointed at `Update`, `match`/`matchAll` and `widget:on`.
      `custom.md` rewritten; the rows in `widget.md` and `native.md`. Criteria 1–7.
      *Its suite* builds a `300x200` window and asserts: `size()` reads `300`/`200`, `size(size())`
      leaves it, `info().size` agrees, `chrome().frame` is wider and taller than the content and
      `chrome().content.w == 300` with `x > 0`; a bare canvas reads its own box; `resizable()` is
      `nil`, `resizable(true)` chains and reads `true`, `chrome().sizer` is present, `resizable(false)`
      reads `nil`; a bare canvas refuses `resizable(true)` naming `widget:resizable(h)`, a string is
      refused naming `true/false`; `events()` lists `Resized` and `Close`. Under a throwaway
      `remember` name (deleted at the end): a window sized `360x240` is destroyed and a fresh `300x200`
      one remembered under the name reads `360`, and its `Draw` paints `360x240` within half a second.
      `[manual]`: drag the corner — the panel follows live and one `Resized` line agrees with `size()`;
      click the X — the window hides and `exists()` stays true; `:t153-1` again rebuilds it.
