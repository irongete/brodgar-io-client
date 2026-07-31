# 021-actionbar-oop — Spec

## What & why
Migrate the **action bar / hotbar** from the flat snapshot API (`hafen.actionbar.slot(n)` → 
`ActionbarSlot` table, `hafen.actionbar.use(n, mods)`) to **OOP**, following the kin migration 
pattern. The action bar is a fixed-size collection (0..143 slots), each optionally populated, with 
gated write verbs (`use`). **Arity is the verb**: `hafen.actionbar()` is the slots collection, `hafen.actionbar(n)` 
a Slot object — which wraps only the slot index and re-reads per call (D-012 freshness), 
**interned per addon** so `a == b` and `seen[slot]` work. **Hard cut** (D-013): the flat 
`hafen.actionbar.slot` and `hafen.actionbar.use` are deleted.

```lua
for i, slot in ipairs(hafen.actionbar()) do
  if not slot:empty() then
    hafen.log(i .. ": " .. (slot:name() or slot:res()) .. " [cd=" .. (slot:cooldown() or 0) .. "]")
  end
end
hafen.actionbar(0):use()                          -- invoke the first slot
```

## Decisions taken here (to confirm at review)
- **`hafen.actionbar()` → indexable slots collection (0..143)**: `#slots` is always 144. `hafen.actionbar()[n]` 
  returns the Slot at index `n` (0..143); `ipairs` iterates all. No `:find()` or `:list()` — the 
  slots are fixed-size and always fully populated as objects.
- **`hafen.actionbar(n)` → Slot object**: `:res() :name() :cooldown() :empty()` reads (live per call); 
  gated write `:use(mods)` returns **self** for chaining. Out-of-range `n` throws (bounds-checked).
- **`:empty()` returns true if slot has no content**: a populated slot has at least a `res`. 
  Empty slots return `true`.
- **`slot:info()` survives** as the one snapshot escape hatch (today's `ActionbarSlot` shape, 
  017 precedent). **`ActionbarChanged` payload is a single `Slot`** (the affected slot). 
  **`hafen.actionbar` is callable-only** — no fields, exactly one way in.

## Acceptance criteria (verified in-game, one login)
- [ ] `:lua`: `hafen.actionbar()[1]:name()` reads; `#hafen.actionbar()` = 144 (full slots); 
      `hafen.actionbar(0) == hafen.actionbar(0)` and is interned (same object per addon).
- [ ] Freshness: use a slot in the game UI → `:cooldown()` tracks it; `:info()` returns 
      the old flat shape. `:empty()` correctly identifies populated vs empty slots.
- [ ] Gated (actions permission): `hafen.actionbar(0):use()` activates a non-empty slot; 
      without permission it errors. Empty slot or out-of-range errors.
- [ ] Hard cut proven: `hafen.actionbar.slot` and `hafen.actionbar.use` are nil, grep 
      `hafen\.actionbar\.` zero in `src/io/brodgar/addon/`, `docs/addons/`, `addons/`. 
      `hello` exercises it; **full prior regression passes**.

## Out of scope
- Visual rendering / UI tweaks (the belt widget itself lives in `haven`).
- Party / Fight / other flat-to-OOP migrations — later (ROADMAP).
- Slot drag/drop reordering — a future write verb if needed.

## Context files
- `src/io/brodgar/addon/CharApi.java` — `installActionbar`, `actionbarSlot/Snapshot/ResObj/Name/Cooldown`, 
  `actActionbarUse` (the whole rewrite)
- `src/io/brodgar/addon/AddonManager.java` — the `installActionbar` call site + the 
  `ActionbarChanged` fire site
- `src/haven/GameUI.java` — the `Belt` array (`belt[n]`, `BeltSlot`), READ ONLY
- `docs/addons/api/actionbar.md` rewritten; `types.md`, `conventions.md`, `actions.md`, `events.md`, 
  `README.md` cross-refs; `addons/hello/main.lua` (reads + maybe uses for a demo)
- `017-gob-oop/`, `020-kin-oop/` — the two OOP precedents this follows
