# 022 — Actionbar write: `slot:set(resourceName)`

## What & why

Add a write method `slot:set(resourceName)` to the Slot OOP API (021-actionbar-oop) to assign actions
to the action bar (hotbar) by resource name — mirroring the client's drag-and-drop from MenuGrid.
Addons today can **read** slots and **activate** them, but cannot **assign** which action/item goes
into a slot. This closes the write API surface.

The client sends `wdgmsg("setbelt", slot, "res", resourceName)` when you drag an action from the
menu grid to the hotbar. We reuse that same path, gated on the `actions` permission like `:use()`.

## Acceptance criteria (in-game verification by maintainer)

- [x] `slot:set(resourceName)` assigns a resource-backed action to a slot
  - Example: `hafen.actionbar(0):set("gfx/hud/act/mine")` → slot 0 now shows the "Mine" action
- [x] Method rejects a nil / non-string / empty resource name with a clear error message (an unknown but
  well-formed name is silently ignored by the server — the drag path behaves the same)
- [x] Gated on `actions` permission (same as `slot:use()`)
- [x] Returns self for chaining — but the write is **asynchronous**, so `:set(res):use()` in one chain
  activates the PREVIOUS content; react to `ActionbarChanged` instead (documented)
- [x] Demo: `:walker setbar <n> <res>` in the opt-in `walker` addon (a real `:set` cannot live in the
  read-only `hello`, which instead checks the gate refuses — `setGated=true`)
- [x] Compile clean: `ant hafen-client` → `BUILD SUCCESSFUL`; no incremental-build false-greens

## Out of scope

- Setting by page ID (`"pag"` variant) — page IDs are session-local and opaque to addons
- Clearing a slot (can right-click it in-game; addon-side clear might come later)
- Visual feedback or slot-assignment history

## Context files

- **Prior feature**: `021-actionbar-oop/` (the Slot class)
- **Client side**: `src/haven/GameUI.java` Belt.dropthing() — the `wdgmsg("setbelt", ...)` pattern
- **Decision**: [permissions.md](../decisions/permissions.md) — D-027/D-028
- **Subsystem**: [addon-engine.md](../codebase/addon-engine.md)
