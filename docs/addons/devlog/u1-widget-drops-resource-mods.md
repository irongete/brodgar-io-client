# U1 — Widget `onDrop` + `g:resource` + mouse `mods` (one build)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL) + LuaJ parse of the harness
> (`hello`). **In-game DoD pending.**
> **Design:** [specs/addons/07-ui-and-drawing.md](../../specs/addons/07-ui-and-drawing.md) (drops, the GOut
> wrapper, input modifiers), [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.ui`),
> [specs/addons/code-map.md](../../specs/addons/code-map.md), decisions **D-038** (`onDrop`), **D-039**
> (`g:resource`), **D-040** (mouse `mods`), plus **D-012** (one canonical way) / **D-017** (facade-safe).

The first slice of the **U-series** (maintainer-requested UI-API extensions): three small, **additive**
primitives shipped together — they are co-located edits with a single rebuild + one in-game check. They
unblock **drag-drop custom UI** (a future "ActionBars" addon: drag an action from the menu grid onto a
client-only bar, render its icon, move the bar with Shift, persist it). **Zero `haven` core edit** — the
engine already dispatches menu-grid drops generically.

## The three primitives

### (a) `onDrop` — a widget is a drop target (D-038)

`hafen.ui.widget` / `hafen.ui.window` gain an optional `onDrop = fn(x, y, drop)` callback;
[`LuaWidget`](../../src/io/brodgar/addon/LuaWidget.java) now **`implements haven.DropTarget`**. When the
client's own drag-drop machinery drops a "thing" over the widget, the engine walks the widget tree
([`DropTarget.Drop`](../../src/haven/DropTarget.java) via `PointerEvent.propagation`) and calls
`dropthing(cc, thing)` on the first `DropTarget` under the cursor — so a bridge widget receives drops with
**no core edit**. `cc` is already **widget-local** (the `Drop` event derives child-local coords as it
propagates), so the callback gets widget-local pixels. A **truthy** return consumes the drop.

v1 delivers the **`dropthing` path** only, whose "thing" is a
[`MenuGrid.Pagina`](../../src/haven/MenuGrid.java) (a menu-grid action), as a **neutral descriptor**:

```lua
onDrop = function(x, y, drop)   -- drop = { kind = "pagina", res = "<resource name>" }
  droppedRes = drop.res         -- a resource name is plain data → ungated
  return true                   -- consume
end
```

- **`res` only for resource-based paginae.** The descriptor carries `res` only when the pagina is
  resource-based (`pag.id instanceof Indir` — its id *is* the resource `Indir`) and only once resolved
  (`Loading` is swallowed → `res` absent until ready). An **id-only** pagina (the `fl&2` case in
  `MenuGrid.uimsg`) has no stable resource name, so the descriptor carries `kind` alone — usable in-session
  but **not reliably persistable** across relogs. Most real abilities are resource-based, so this is a corner
  case (D-038).
- **Neutral descriptor, not a live object** (D-017): a resource name is already exposed all over the read API
  (`hafen.actionbar.slot`, …), so it stays **ungated**, and no mutable Java handle leaks into the sandbox.
- **No activation.** `onDrop` tells you *what* was dropped, not the power to *fire* it — firing a dropped
  action needs the deferred **menu-ability primitive** (a resolvable ability handle + a gated `use`).
- Any other kind of thing (an inventory item — the separate `DTarget.drop`/`iteminteract` path, a different
  drag) returns `false`, so the engine keeps looking for a handler.

### (b) `g:resource(name, x, y [, w, h])` — draw an engine `.res` image by name (D-039)

Added to the shared [`LuaGOut`](../../src/io/brodgar/addon/LuaGOut.java) draw wrapper — the sibling of
`g:image`: `g:image` blits the addon's **own** PNGs (`hafen.render.image`, R1), `g:resource` blits the
**client's own `.res` art** (action icons, hud pieces) **by name**. Used to render the real game icon of the
`res` a widget receives from `onDrop`.

- Resolved **async + cached**: one `Indir<Resource>` per name (`Resource.remote().load(name)`), kept in a
  static `LuaGOut` name→`Indir` cache so a per-frame draw does not re-issue the lookup. The referenced
  resources are the client's own global engine resources (shared via `Resource.remote()`, which caches them
  anyway), so the map holds only lightweight refs and leaks nothing; it is cleared on `:reload` for
  faithfulness (`LuaGOut.clearResourceCache()`, called from `AddonManager.reload()`).
- **`Loading`-guarded**: draws nothing until the texture is ready, then blits the resource's default image
  layer (`Resource.imgc` → `Resource.Image.tex()`) — the client's own idiom (cf. `MenuGrid.draw` swallowing
  `Loading`). A bad name / load error simply draws nothing (never throws into the render thread).
- **Static icon only** — no live sprite / cooldown sweep (that is the deferred menu-ability primitive,
  `g:ability`). The native empty-slot square (`Inventory.invsq`) is a code-built `TexI`, not a plain `.res`,
  so an addon draws its own slot background (a `g:frect`/`g:rect`); `g:resource` covers any real `.res`.

### (c) mouse `mods` — a trailing `{shift, ctrl, alt}` table (D-040)

`LuaWidget` now passes a trailing `mods = {shift, ctrl, alt}` table (booleans, from `ui.modflags()` via the
shared `AddonManager.modsTable` helper) as the **last** argument of every mouse callback:
`onClick(x, y, button, mods)`, `onMouseUp(x, y, button, mods)`, `onMouseMove(x, y, mods)`,
`onWheel(x, y, amount, mods)`. **Additive and back-compatible** — a handler that ignores the extra argument
is unaffected. Mirrors the `mods` already delivered by `hafen.hook.grab`, so one modifier shape is used
everywhere (D-012). This lets an addon branch on the modifier state **at press time** (e.g. Shift+drag to
move a bar vs. a plain click to use a slot).

## Code changes

- **[`LuaWidget.java`](../../src/io/brodgar/addon/LuaWidget.java)** — `implements DropTarget`; new `onDrop`
  callback field; `dropthing(Coord, Object)` override + a private `dropDescriptor(Object)` (the
  `Pagina`→descriptor marshal, resource-based vs. id-only gate, `Loading`-guarded `resName`); a `mods()`
  helper; the four mouse forwards now append `mods()`.
- **[`LuaGOut.java`](../../src/io/brodgar/addon/LuaGOut.java)** — new `g:resource` verb + a static name→`Indir`
  cache + `resTex(name)` (async/cached/`Loading`-guarded resolve) + `clearResourceCache()`.
- **[`AddonManager.java`](../../src/io/brodgar/addon/AddonManager.java)** — one line in `reload()` calls
  `LuaGOut.clearResourceCache()`. (`newUi` passes `opts` through unchanged — `onDrop` is read in `LuaWidget`.)

**Zero `haven` core edit.** Ungated (all three are client-side / plain data).

## Threading

`dropthing` runs on the UI thread under the engine's own drop dispatch; `mods()` reads `ui.modflags()` on the
same input-dispatch thread. `g:resource` runs inside a draw callback (render thread) — `resTex` swallows
`Loading` and any load error; the `Indir`/`Tex` are the engine's own draw-thread-safe caches; the name cache
is a `ConcurrentHashMap`. Every Lua forward goes through `AddonManager.callLua` (watchdog-armed,
error-isolated, CPU-accounted), exactly like the existing widget callbacks.

## Harness — `hello` v0.46.0

A borderless `hafen.ui.widget` drop-target box below the 2a window (created in the panel's `OnEnterWorld`):
its `onDrop` logs the dropped `res` and remembers it; its `onDraw` draws that action's real icon via
`g:resource`; its `onClick` logs the `mods` table (Shift+click → `shift=true`). Bridge-owned → `:reload`
leaks nothing.

## In-game DoD (pending)

Open the menu grid (bottom-right), **drag an action** onto the `hello` drop box → its icon renders + the
`res` name logs; **Shift+click** the box → the log shows `shift=true`; `:reload` leaks nothing.

## Deferred

Activating a dropped action (the **menu-ability primitive** — a resolvable ability handle + gated `use`, the
"fire it" half of a custom action bar); item drops (the `DTarget.drop`/`iteminteract` path, not `dropthing`);
`g:resource` live sprites / cooldown sweeps (`g:ability`); the `Inventory.invsq` empty-slot `TexI`.
