# hafen.map: Claim Masks & Map Overlays

Inspect recorded personal claims, village boundaries, realm borders, and display switches on the world map.

## Quick Example

```lua
local session = hafen.session():current()
local player_position = session and session:player():gob():position()

if player_position then
  local current_grid = hafen.map():grid():at(player_position)
  if current_grid then
    -- Inspect claim masks on this grid
    for _, claim_mask in ipairs(current_grid:mask():list()) do
      local tag_name = claim_mask:tag()
      local covered_tile_count = claim_mask:count() or 0
      hafen.log():write(string.format("Claim mask '%s' covers %d tiles.", tag_name, covered_tile_count))
    end
  end
end
```

---

## Methods on `Grid:mask()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list()` | None | `Mask[]` | All claim/overlay masks recorded on this grid. |
| `:get(tag_name)` | `string` | `Mask \| nil` | Retrieves a specific overlay mask by tag (e.g. `"cplot"` for personal claim, `"vlg"` for village). |
| `:count()` | None | `number` | Number of distinct masks on this grid. |

---

## Methods on `Mask`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:tag()` | None | `string` | Identifier tag of the overlay mask. |
| `:grid()` | None | `MapGrid` | The parent grid this mask belongs to. |
| `:covers(coords)` | `{x, y}` | `boolean \| nil` | Returns `true` if local tile coordinate `(0..99, 0..99)` falls inside the mask. |
| `:count()` | None | `number \| nil` | Total number of tiles covered by this mask on this grid (out of 10,000). |
| `:area()` | None | `{x, y, w, h} \| nil` | Tight bounding box around covered tiles in local grid coordinates. |
| `:exists()` | None | `boolean` | Returns `true` if this mask is still valid on the grid. |
