# 134 — tasks

- [x] **134.1 — the outline is in the raster.** `FontHandle` gains `outline` (a colour, or null);
      `h:outline()` reads it, `h:outline(c)` writes it on a draft, `h:outline(nil)` undoes it, through
      `FontApi.property` with the ownership guard every setter has. `LuaGOut.render` wraps the rendered
      `Text` through `Utils.outline2` when the handle carries one, as an anonymous `Text` subclass over the
      grown image, so `measure`, `label` and both draw sites see one raster. `FontApi.face` refuses a handle
      carrying an outline for a rule or `widget:rule()`, naming the addon's own drawing; the table form
      refuses an `outline` key. Pages: `font.md` (the row; two options do not travel), `drawing.md` (the
      cache section), `docs/client/text-and-fonts.md` (the `outline2` row, and the chat-colours split to
      `docs/client/chat-colours.md` with `README.md` re-pointed). Criteria A1–A4.
      *Its suite* derives `sans` at 11 bold twice, one with `:outline{0, 0, 0}`, and asserts
      `hafen.ui():measure("7", {font = o})` is the plain measurement plus 2 in `w` and in `h`; that
      `o:outline()` reads back `{0, 0, 0}` and the plain one's is `nil`; that a fresh draft's
      `:outline(nil)` reads `nil` after; that `hafen.font():get("sans"):outline{0, 0, 0}` fails naming
      `:derive()`, and that `o:outline{1, 1, 1}` after the measure fails naming `:derive()` too; that
      `widget:rule():font(o)` on a window of its own fails and the message names "own drawing"; that after
      a HUD overlay draws `"7"` with `o` on two frames, `hafen.client():profiling():textcache()` holds one
      entry more than before the overlay went up, not two — scored over a bounded timer, then the overlay
      removed.
      `[manual]`: press the suite's key once and read the digit at the top-left of the screen — expect:
      white digit with a one-pixel black edge all round, one clean edge (no doubled outline).

- [x] **134.2 — a label stands where it is told.** `LuaGobOverlay.Attach` gains `height` (world units,
      `15` by default); `LuaOverlay` gains `ov:height()` / `ov:height(z)` through `writable` and
      `numberArg`, `:info()` carries `height`, and the `offset` refusal on a third argument names
      `ov:height(z)`. `LuaGobOverlay.draw` projects per record through `Eye.view` at each record's own
      height, memoising the last one, and `UiApi.paintGobOverlays` paints each at its own point. Pages:
      `overlay.md` (the anchor paragraph, the row, the snapshot), `custom-ui.md`. Criteria A5–A7.
      *Its suite* attaches to the player's gob, standing still: `"a"` as `:text("a"):height(0)` and `"b"`
      as `:text("b")`; asserts `a:height()` is `0`, `b:height()` is `15`, `a:info().height` is `0`; that
      `a:height("x")` fails naming a number; that a native overlay (`gob:overlay():find("")` with
      `:native() == true`, over the gobs in sight, retried on a timer for a bounded window) refuses
      `:height(0)` and the message names its key, else the check reports it reached none; that
      `a:offset(1, 2, 3)` fails and the message contains `height`. Then a `:draw(fn)` record at
      `height(0)` stores the `sx, sy` it is handed on one frame, and the suite compares it with
      `s:world():worldToScreen(me:position())` read in the same tick, within one pixel each way; and a
      second `draw` record at `15` is handed a `sy` strictly smaller than the first's. Every record is
      removed at the end.
      `[manual]`: with the suite's overlays up, look at your character — expect: `a` standing at the feet,
      `b` above the head, both following as you walk.
