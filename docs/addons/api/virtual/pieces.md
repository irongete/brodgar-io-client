# Multi-Piece Ground Patches

Combine multiple polygon rings or complex geometric footprints into a single virtual ground patch.

## Quick Example

```lua
local session = hafen.session():current()
local player_position = session and session:player():gob():position()

if player_position then
  local main_patch = hafen.virtual():patch():add(primary_ring, player_position)

  -- Add secondary disjoint polygon rings to the same patch
  main_patch:piece():add(secondary_ring)
  main_patch:tint({ 0, 200, 100, 80 })
end
```

---

## Methods on `patch:piece()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:add(ring_points)` | `Position[]` | `self` | Adds an additional polygon ring to this ground patch. |
| `:list()` | None | `Position[][]` | Returns all polygon rings comprising this patch. |
| `:clear()` | None | `self` | Clears additional pieces from the patch. |
