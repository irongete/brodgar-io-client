# 022 — Plan

## Approach

Extend the Slot metatable (built in `LuaSlot.buildMeta()`) with a new `set(resourceName)` method.
The method:
1. **Validates** the resource name is non-empty and a string
2. **Requires** the `actions` permission (call `AddonManager.requireActions(owner, "slot:set")`)
3. **Sends** `wdgmsg("setbelt", slotIndex, "res", resourceName)` — the same message the client sends
   when you drag an action from MenuGrid to the hotbar
4. **Returns self** for chaining (same as `:use()`)

The server processes `setbelt` exactly as it does for drag-and-drop, mutating `GameUI.belt[n]`
asynchronously. The addon layer's poll-driven change detection (CharApi.ActionbarAdapter) will
fire `ActionbarChanged` on the affected slot when the server's write lands (already wired in 021.2).

## Files to create / modify

- **`src/io/brodgar/addon/LuaSlot.java`** (the methods table in `buildMeta()`):
  - Add `set()` method alongside `:use()` in the `m` table
  - Same structure as `:use()`: `VarArgFunction` for arity, `handle()` for self-resolution,
    `AddonManager.gui()` to reach `GameUI`, then `wdgmsg()` dispatch
- **`docs/addons/api/actionbar.md`**:
  - Add `set(resourceName)` to the "Write" table; document it takes a string resource name,
    requires `actions` gating, returns self for chaining
  - Add an example: `hafen.actionbar(0):set("gfx/hud/act/mine"):use()` — assign then use
- **`addons/hello/hello.lua`**:
  - Add a demo that calls `:set()` on a slot (e.g., on OnLoad, set slot 10 to "mine" and log it)
  - Verify it chains: `:set().use()` as a single statement

## Risks & gotchas

- **Resource validation**: If the resource name is invalid (typo, doesn't exist), the server will
  silently ignore the `setbelt` message (it's how the drag-and-drop path works). The addon won't
  get an error back; the slot just won't change. Consider adding a `hafen.res.exists(name)` check
  for better UX, but that's a separate feature — for now, document that invalid names are silent.
- **UI thread only**: `slot:set()` is called from Lua (addon tick / REPL / timer / slash command),
  all UI-thread-marshalled, and `wdgmsg()` is UI-safe. No threading issue.
- **Consistency with `:use()`**: Both require `actions`, both return self, both error if the slot
  is out of bounds or the HUD doesn't exist yet (pre-world). `:use()` also errors on empty slots;
  `:set()` has no such restriction (you can assign to any slot, empty or occupied).

## Discarded alternatives

- **Setting by page ID** (`"pag"` variant): Page IDs are session-local and generated dynamically by
  the server. Addons have no way to enumerate them or know a page's ID. Exposing page IDs would
  require a second API to discover/list pages, which is out of scope. Stick to resource names.
- **Validation loop checking MenuGrid**: Could try to find the resource in MenuGrid, but that's
  invasive and MenuGrid is a widget (mutable state). Resource names are the stable handle; let
  the server validate.
- **Clearing slots**: Proposed as "set(nil)" or ":clear()", but right-clicking a slot in-game
  clears it, and user confusion about whether `:set(nil)` clears or errors is not worth it now.
  Can add `:clear()` as a separate feature if needed.
