# 022 — Tasks

## 022.1 — Implement `slot:set(resourceName)` + docs + demo — **DONE**

**One session, one commit (at `/end`).**

Implement the full feature:
1. Add `set(resourceName)` method to `LuaSlot.buildMeta()` methods table
   - Validate non-empty string argument; error on nil/empty
   - Gate on `actions` permission via `AddonManager.requireActions(owner, "slot:set")`
   - Send `wdgmsg("setbelt", n, "res", resourceName)` where `n` is the slot index
   - Return self for chaining
2. Update `docs/addons/api/actionbar.md`:
   - Add `:set(resourceName)` row to the "Write" section
   - Document the gating, the chaining, the return value
   - Add a usage example showing assignment + activation in one chain
3. ~~Extend `addons/hello/hello.lua`~~ → **the working demo went to `addons/walker/main.lua`**
   (`:walker setbar <n> <res>`): `hello` declares no permissions, so a real `:set` there can only ever
   throw. `hello`'s per-login actionbar contract line instead asserts the gate fires (`setGated=true`),
   matching the split already used for `slot:use`/kin/speed/craft.
4. Build check: `ant hafen-client` → `BUILD SUCCESSFUL`
5. In-game: load hello addon, verify slot 5 now has the action and can be activated

**Extra context:** The server's `setbelt` handler is the same path drag-and-drop uses; it mutates
`GameUI.belt[]` asynchronously. Poll detection in CharApi fires `ActionbarChanged` on the slot
when the write lands (wired in 021.2).
