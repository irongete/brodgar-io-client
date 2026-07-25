# Phase 3c — `hafen.ui.replace` + the `bags` example (Widget replacement, the Phase-3 DoD)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), 36/36 headless logic checks
> (`descTable` nil-omit; `matchOnCreate` type + `context="main"` gating [main inv = `place "inv"` under `GameUI`,
> a container inventory rejected] + exact caption + escape-hatch `match(desc)` predicate; the scan-path
> `matchCriteria`; `nativeWindowOf` self-when-no-Window; `LuaReplacer.handled`; `LuaModel` hide-target routing
> [`hide`/`show`/`visible` via `hideTarget`, `origVisible` capture for a visible grid AND a hidden-by-default
> wrapper]) + LuaJ parse of `bags/main.lua` under the sandbox. **In-game verified ✅** (Ctrl+Shift+I replaced the
> open inventory with the custom Bags view drawing the real items — exercising the scan path; toggling off / closing
> the view / disabling + `:reload` all restored the stock inventory; `hello` regression intact).
> **Design:** [specs/addons/08-widget-replacement.md](../../specs/addons/08-widget-replacement.md) (the "wrap,
> don't reimplement" golden rule, the `replace` sketch, teardown un-hide), decisions **D-009** (wrap, don't
> reimplement), **D-024** (targeting descriptor), **D-013** (one canonical way), **D-010/D-025** (item verbs are
> the gated Phase-4 tier).

The **third and final slice of Phase 3 (Widget replacement)** — the **DoD**: *replace the native inventory with a
custom view; disabling restores the stock window.* It is the high-level sugar over 3a + 3b:

- **3a** — *observe.* `hafen.ui.onWidgetCreate(fn)` sees every server widget as the client builds it (a
  `{id,type,place,caption,parentType}` descriptor).
- **3b** — *adopt.* `hafen.ui.adopt(id)` holds a widget as a hidden **model** (kept server-bound; `:items()` +
  lifecycle events).
- **3c (this)** — *replace.* `hafen.ui.replace(type, opts, fn)` **finds** the target by descriptor (both when it
  is created *and* when it is **already open**), **adopts + hides** it, and hands the addon a **view** to draw —
  then **un-hides** on teardown, restoring the stock UI.

## The API — `hafen.ui.replace`

```lua
-- Replace a native/server window with your own view. type = the server type string ("inv" = inventory).
-- opts (all optional): context="main" (the main inventory, GameUI.maininv) · caption="…" (an exact window
--   title, for titled containers) · match=function(desc) … end (an escape-hatch predicate; desc is the same
--   {id,type,place,caption,parentType} shape as onWidgetCreate).
-- fn(model) receives the adopted MODEL (the real, now-hidden widget — a 3b model handle) and RETURNS a view
--   (e.g. a hafen.ui.window). The bridge owns that view.
local handle = hafen.ui.replace("inv", { context = "main" }, function(model)
  local win = hafen.ui.window{ title = "Bags", size = {260, 320},
    onDraw = function(g, w, h)
      for _, item in ipairs(model:items()) do   -- reads the REAL (hidden) inventory's live items
        -- draw a cell for each item at item.pos {x,y}; a click can log/inspect it
      end
    end }
  return win                                    -- the addon's view; auto-destroyed with the replacement
end)

handle:remove()   -- stop replacing AND restore the native window (destroying your view). A live toggle-off.
```

- **Returns** a handle `{ :remove() }`. `:remove()` **fully undoes** the replacement: it restores the native
  window's original visibility and destroys the view. Bridge-owned — `:reload`/disable does the same
  automatically (the DoD: *disable restores it*).
- **The `model`** handed to `fn` is a 3b model handle (`:hide`/`:show`/`:visible`/`:raw`/`:items`/
  `:onItemAdded`/`:onItemRemoved`/`:onDestroy`). `replace` has already hidden the native window; you mostly just
  read `model:items()`.
- **Item moving stays gated.** Reading items + drawing a view is ungated; `take`/`transfer`/`drop` emit a player
  `wdgmsg`, so they belong to the **Phase-4 `hafen.act` tier** (D-010/D-025). The `bags` view is **read-only**
  (a click logs the item).

## How the target is found — two paths (the 3c headline)

A server widget can be matched **as it is created** or **while it is already open**:

| | Creation path | Scan path |
|---|---|---|
| **When** | A server widget is placed (`UI.AddWidget.run` → `onWidgetPlaced`) | Once, at `replace()` registration |
| **Catches** | A target opened *after* you registered (normal login: the inventory streams in a beat after enter-world) | An *already-open* target — **the `:reload` case**, where the inventory was created before the rebuilt addon layer, so no creation event fires for it |
| **Descriptor** | Full `{id,type,place,caption,parentType}` (from the 3a seam) | Best-effort: `type` keyed by the widget's Java class, `caption` from a live `Window`, `parentType` from the live parent; `place` is not recoverable → `nil` |
| **Match** | `matchOnCreate` (type + `context` place/parent gate + `matchCriteria`) | `context="main"` → the public `GameUI.maininv`; else class-scan + `matchCriteria` (caption + `match` fn) |

This is exactly the gap 3b flagged: *"`:reload` does not recreate the existing inventory, so the freshly-registered
observer won't re-fire for it — re-adoption after `:reload` waits for `hafen.ui.replace`."* The **scan** closes it.
Because the server type string is **not** stored for an already-live widget, the scan keys on the Java class
(`typeClass`: `inv`→`Inventory`, `epry`→`Equipory`, `chr`→`CharWnd`, `wnd`→`Window`) — a bridge-internal detail;
the addon's `type` string remains the one canonical key (D-013).

## Hiding the **wrapper** window (the other 3b-deferred item)

3b's `model:hide()` hid the widget itself (the inventory *grid*). `replace` hides the **native window around it** —
`nativeWindowOf(wdg)` = the nearest enclosing `Window` (or the widget itself if none). For the main inventory the
server widget is a bare `Inventory` that `GameUI.addchild` wraps in a `Hidewnd` titled *"Inventory"* (`invwnd`), so
`replace` hides **that whole window**, not just the grid.

Teardown **restores the wrapper to its *original* visibility**, not blindly `show()`: `invwnd` is
**hidden-by-default** (the client only shows it on `Tab`), so we record `hideTargetOrigVisible` when we hide it and
replay it — a hidden-by-default window goes back to *hidden*, a visible grid goes back to *visible*. (`LuaModel`
gained a `hideTarget` + `hideTargetOrigVisible`; a plain `adopt` model still targets the widget itself, so 3b
behaviour — `hello`'s Ctrl+B grid toggle — is byte-for-byte unchanged.)

> **Honest limit.** The native window is *hidden*, but the client's own `Tab`/menu toggle (`togglewnd`) can still
> re-show it — a full replace would also intercept that binding (a later refinement / Phase-4). Pressing `Tab`
> after replacing shows the real inventory *alongside* your view, which actually demonstrates the model is still
> live. It does not break restore.

## Lifecycle & ownership (P2)

- **View** — what `fn(model)` returns is a normal `hafen.ui.window`, already in the addon's owned `widgets` list.
  `replace` also remembers it (`LuaModel.replaceView`) to auto-destroy it when the model dies.
- **Server destroys the widget** (a cupboard the server closes) → `pollModels` fires `onDestroy`, drops the model,
  and **destroys the view** (spec 08: "the view must die with the model").
- **`:reload` / disable / auto-disable** → `teardownReplacers` stops the matcher, `teardownModels` un-hides the
  native window (to its original visibility), `destroyWidgets` destroys the view — the stock UI is restored.
- **`:remove()`** (live toggle-off) → `undoReplace` per active model: restore the native window + destroy the view.
- All tree ops run under `synchronized(ui)`; everything is on the UI thread (the creation path is inside
  `AddWidget.run`'s `ui` lock, the scan/toggle runs from the tick/hotkey which hold it too).

## Core edits

**Zero new `haven` edits.** 3c reuses 3a's two `UI.java` one-liners (`onWidgetCreated`/`onWidgetPlaced`) — the
replacer dispatch is folded into `onWidgetPlaced` alongside the observers, and the fast path now checks *both*
lists. Everything else is new code in `io.brodgar.addon`:

- **`LuaReplacer.java`** (new) — the matcher (type/context/caption/match + builder; `handled` set; `active` models).
- **`LuaModel.java`** — `hideTarget` + `hideTargetOrigVisible` (restore basis) + `replaceView`/`fromReplace`.
- **`AddonManager.java`** — `hafen.ui.replace`, `newReplacer`/`scanForReplace`/`matchOnCreate`/`matchCriteria`/
  `fireReplace`/`nativeWindowOf`/`typeClass`/`widgetsOfClass`/`removeReplacer`/`undoReplace`/`destroyReplaceView`/
  `teardownReplacers`; `onWidgetPlaced`/`onWidgetCreated` feed replacers; `modelHandle`/`teardownModels`/`pollModels`
  use `hideTarget`; the shared `descTable` (also used by the 3a observer — DRY); session reset + teardown wiring.
- **`LuaWidgetObserver.java`** — uses the shared `descTable`.

## The `bags` example addon (`addons/bags/`)

A **dedicated** example (spec 08's "first target"), separate from `hello` so the regression harness stays clean.
It is **dormant until you press a hotkey**, so a normal login is undisturbed:

- **Ctrl+Shift+I** (remappable under **Options > Keybindings > Bags**) toggles the replacement:
  - **on** → `hafen.ui.replace("inv", {context="main"}, …)` — the **scan** finds your open inventory immediately,
    hides the native window, and shows a custom **"Bags"** window drawing your **real** items at their real grid
    cells (no icons yet — `g:image` is deferred — so a short name + stack count per cell), with a hover highlight.
  - **off** → `handle:remove()` restores the native inventory and destroys the view.
- **Disable/`:reload` while replaced** also restores it (teardown) — the DoD, the non-toggle way.
- A **click logs** the item under it (moving items is the gated Phase-4 tier).

Because `bags` is dormant by default it coexists with `hello` (which still adopts the inventory for its 3b Ctrl+B
demo) without disturbing routine logins; for the cleanest *visual* 3c test you may `:addons disable hello` +
`:reload` so only `bags` touches the inventory, but it is not required (both restore correctly).

## How to test in-game (DoD)

1. `ant hafen-client` (done) → `ant run`. `bags` loads (enabled by default, dormant) and logs the hotkey hint; a
   **"Bags"** section appears under Options > Keybindings.
2. In-world, open your inventory (**Tab**) so you can see it, then press **Ctrl+Shift+I**:
   - the native inventory window disappears and a **"Bags (custom)"** window appears drawing your real items →
     **replace ✓** (and it exercised the **scan** path — the inventory was already open).
   - drag the Bags window by its title bar; click an item → a `bags: clicked …` log line.
3. Press **Ctrl+Shift+I** again → the native inventory is back, the Bags window is gone → **restore ✓**.
4. Replace again, then `:addons disable bags` + `:reload` → the native inventory is restored on teardown → the DoD
   *"disable restores it"* ✓. (`:addons enable bags` + `:reload` to bring it back.)
5. Regression: `hello`'s everything (window, overlays, hooks, Ctrl+B/Ctrl+H, all the read logs) still works.

## Deferred

- **Item verbs** (`take`/`transfer`/`drop`/`use`) — the gated **Phase-4** `hafen.act` tier (D-010/D-025).
- **Item icons** in the view — needs `g:image` (Tex/resource resolution, deferred since 2a).
- **Intercepting the client's `Tab`/menu toggle** so the native window can't be re-shown while replaced.
- **Sizing the view to the live grid dimensions** (`Inventory.isz`) as items stream in; the current view sizes to
  the items present at replace time.
- Widget-*type* **factory override** (seam A, `Widget.types`) — 3c uses adopt-after-create (seam B) only, which
  covers HUD-placed windows; a pre-emptive factory swap (and resource-`/`-named widgets) remains future work.
