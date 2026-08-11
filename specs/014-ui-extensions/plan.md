# 014-ui-extensions — Plan

> History: this work appears in git history and `learnings/` tagged **U1** (the U-series).

## Approach
- **Drops**: the engine walks the tree for the first `DropTarget` under the cursor
  (`DropTarget.Drop` via `PointerEvent.propagation`) — implementing the interface on
  `LuaWidget` gets drops with zero core edit; `cc` is already widget-local. v1 covers the
  `dropthing` path (menu-grid `Pagina`); the descriptor is neutral data — an id-only pagina
  (`fl&2`) has no stable resource name, so it carries `kind` alone (usable in-session, not
  persistable) — the persistability line is readable off `pag.id instanceof Indir`.
- **`g:resource`**: one `Indir<Resource>` per name in a static ConcurrentHashMap —
  engine resources are already globally cached, the map holds lightweight refs; cleared on
  `:reload` for faithfulness. Blits the default image layer, swallowing `Loading` (the
  MenuGrid idiom).
- **`mods`**: appended as the LAST arg of the four mouse forwards via the shared
  `modsTable` helper (`ui.modflags()`) — additive, and the same shape `hook.grab` delivers.

## Files created / modified
- `src/io/brodgar/addon/LuaWidget.java` — `implements DropTarget`, `onDrop` +
  `dropDescriptor`, mods on the four mouse forwards
- `src/io/brodgar/addon/LuaGOut.java` — `g:resource` + the name→Indir cache +
  `clearResourceCache()` (called from `reload()`)
- `AddonManager.java` — one line in `reload()`
- `addons/hello/` — v0.46.0 (the drop box: onDrop + g:resource icon + mods logging)
- No `haven` edits; all three ungated (client-side / plain data).

## Risks & gotchas hit (detail: learnings/ui-widgets.md)
- A UI slice like this is thin on headless coverage by nature — onDrop needs the engine's
  live drop dispatch; the compile + LuaJ parse + in-game DoD carry the verification.
- `g:resource` runs on the render thread — everything must swallow `Loading`/errors.

## Discarded alternatives
- Handing Lua the live `Pagina` — facade break (D-017); the neutral descriptor suffices.
- A per-addon resource cache — `Resource.remote()` already caches globally.
