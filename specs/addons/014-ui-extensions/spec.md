# 014-ui-extensions — Spec

## What & why
Three small additive UI primitives (maintainer-requested, one build/one in-game check) that
unblock drag-drop custom UI (the future custom-action-bar use case): widgets as **drop
targets** (D-038), drawing the client's own `.res` art by name (D-039), and a `mods`
modifier table on every mouse callback (D-040). Zero core edit — the engine already
dispatches drops generically. Design: [design/07-ui-and-drawing.md](../design/07-ui-and-drawing.md)
+ decisions D-038/D-039/D-040.

## Acceptance criteria (verified in-game)
- [x] `onDrop = fn(x, y, drop)` on `hafen.ui.widget/window` (`LuaWidget implements
      DropTarget`): dragging a menu-grid action onto the `hello` drop box logs its `res`;
      truthy return consumes; the descriptor is neutral plain data
      (`{kind="pagina", res=…}` — res only for resource-based paginae, Loading-guarded),
      ungated, no live Java object.
- [x] `g:resource(name, x, y[, w, h])` on the shared draw wrapper: blits an engine `.res`
      image by name — async + cached (`Indir` per name, cleared on `:reload`),
      Loading-guarded (draws nothing until ready, never throws into the render thread);
      the dropped action's real icon renders in the box.
- [x] Mouse `mods`: every `LuaWidget` mouse callback gains a trailing `{shift,ctrl,alt}`
      table (same shape as `hook.grab` — one modifier convention, D-012); Shift+click on
      the box logs `shift=true`; additive/back-compatible.

## Out of scope
- Activating a dropped action (needs a resolvable ability handle + a gated `use` — the
  "menu-ability primitive", ROADMAP); item drops (the separate `DTarget.drop` path);
  `g:resource` live sprites / cooldown sweeps; the `Inventory.invsq` slot texture.

## Context files
- `design/07-ui-and-drawing.md`; decisions D-038/D-039/D-040 in `../decisions/widgets-ui.md`
- `src/io/brodgar/addon/LuaWidget.java` (DropTarget + mods), `LuaGOut.java` (g:resource)
- `src/haven/DropTarget.java`, `MenuGrid.java` (Pagina) — the backings
- `docs/addons/api/ui.md` — shipped surface
- `../006-custom-ui/` — the widget/draw surface this extends; `../012-custom-rendering/`
  (`g:image`, the sibling verb)
