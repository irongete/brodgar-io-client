# 006-custom-ui — Plan

> History: this work appears in git history and `learnings/` tagged **2a, 2b** (Phase 2).

## Approach
- **One forwarding class** (`LuaWidget extends haven.Widget`): tick/draw/mouse events forward
  to Lua callbacks through `callLua` (the single watchdog-armed, error-isolated, CPU-accounted
  choke point). **Windows are composition, not a `LuaWindow` subclass** — a plain `haven.Window`
  (title/drag/close for free) wrapping a `LuaWidget` content child; `reqclose` redirected to
  `onClose`+destroy (a client-side widget has no server for `wdgmsg("close")`).
- **The `g` wrapper is bound to the live `GOut` only during `onDraw`** and inert otherwise;
  extracted into shared `LuaGOut` when 2b needed it (widgets + HUD + gob overlays = one
  canonical draw surface, D-013).
- **HUD overlays**: `UI.drawafter` is one-shot (cleared each `UI.draw`), so the engine keeps a
  persistent per-addon list and re-queues ONE afterdraw each `tick`; the frame loop is
  `tick → draw → swap`, so it fires that same frame AFTER `root.draw` — above GameUI (which a
  `ui.root` sibling can never reliably be).
- **Gob overlays**: `LuaGobOverlay` = `GAttrib` + `RenderTree.Node` + `PView.Render2D`,
  modelled 1:1 on `SpeakerIcon` — the render tree pins it over the gob in every camera and
  disposes it with the gob; its 2D pass runs inside the same `UI.draw` traversal (never races
  the tick). **One shared attrib per gob serves all addons** (holds no addon state; re-checks
  every filter at draw). A **throttled sweep** (5 Hz, `-Dhaven.addon.gobsweepsec`) attaches
  attribs (attach-only, like `SpeakerIcon.sweep`, but Lua filters → rate-limited); idle
  attribs detach wholesale when no addon wants overlays.
- **Ownership (P2)**: `Addon.widgets`/`hudOverlays`/`gobOverlays`; teardown destroys under
  `synchronized(ui)`. `callLua` changed `void`→`Varargs` so input forwards read the consume
  return (existing callers unaffected).
- **Two arg conventions, deliberate**: addon callbacks receive params direct (no self);
  handle/wrapper methods are colon-called (self = arg1).

## Files created / modified
- `src/io/brodgar/addon/LuaWidget.java`, `LuaGOut.java`, `LuaGobOverlay.java` — new
- `src/io/brodgar/addon/AddonManager.java` (→ `UiApi.java` post-split) — `hafen.ui` facade,
  afterdraw queue, sweep/paint/detach, teardown
- `src/io/brodgar/addon/Addon.java` — three owned-resource lists
- `addons/hello/` — v0.13.0/v0.14.0 (draggable panel + HUD readout/crosshair + player head-tags)
- No `haven` edits (all backings public).

## Risks & gotchas hit (detail: learnings/ui-widgets.md, threading.md, engine-lifecycle.md)
- The 2a login bug: `OnEnterWorld` fired one tick before GameUI attached to `ui.root` → the
  window drew UNDER the HUD; proven by instrumenting the widget protocol; fixed by gating on
  `gui().parent != null` (benefits every addon).
- Draw callbacks run in `UI.draw` after the soft-budget window → draw time escapes the
  per-tick budget (instruction cap still applies) — known gap, in ROADMAP.
- LuaJ `isstring()` is true for numbers — filter validation must check `isfunction()` first.
- Keep draw callbacks cheap: `hello` caches its gob count on a 1 s timer, not per-frame.

## Discarded alternatives
- A `LuaWindow` subclass — duplicates the forwarding; composition keeps it in one class.
- One `drawafter` per overlay — one-shot semantics + ordering make a single re-queued
  afterdraw painting the list strictly better.
- Per-addon gob attribs — `getattr` keys by exact class; one shared attrib per gob.
