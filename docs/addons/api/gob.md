# Gob: Game Objects

A `Gob` represents an entity in the game world—such as a player, tree, boulder, building, or animal.

## Quick Example

```lua
local session = hafen.session():current()
local nearest_tree = session and session:world():gob():nearest("terobjs/tree")

if nearest_tree and nearest_tree:exists() then
  local position = nearest_tree:position()
  local distance = nearest_tree:distance()
  local health_ratio = nearest_tree:health() or 1.0

  hafen.log():write(string.format(
    "Tree ID %d at (%.1f, %.1f), distance: %.1f, health: %d%%",
    nearest_tree:id(), position:x(), position:y(), distance, math.floor(health_ratio * 100)
  ))
end
```

---

## Read Methods

| Method | Returns | Description |
|---|---|---|
| `:id()` | `number` | Server entity ID. Never returns `nil`. |
| `:exists()` | `boolean` | `true` if this object is currently loaded and visible to this character. |
| `:position()` | `Position \| nil` | Current 3D world position. |
| `:distance()` | `number \| nil` | Distance in tiles to the active player character. |
| `:facing()` | `number \| nil` | Heading angle in radians. |
| `:name()` | `string \| nil` | Engine resource path (e.g. `"terobjs/tree"`, `"gfx/borka/body"`). |
| `:health()` | `number \| nil` | Remaining integrity as a float `0.0..1.0` (or `nil` if not applicable). |
| `:moving()` | `boolean \| nil` | `true` if the object is currently moving. |
| `:speed()` | `number \| nil` | Movement speed, or `nil` if stationary. |
| `:speech()` | `string \| nil` | Active overhead speech bubble text. |
| `:player()` | `boolean` | `true` if this object represents a player character. |
| `:sessions()` | `Session[]` | All active local sessions that can currently see this object. |

---

## Visual Modification Methods (Client-Side)

These methods modify the local visual rendering of the object without altering server state (unprotected):

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:scale(factor)` | `number` | `self` | Scales the 3D model visual size. |
| `:visible(is_visible)` | `boolean` | `self` | Toggles local visibility of the 3D model. |
| `:tint(color_array)` | `{r, g, b, [a]}` | `self` | Applies a color tint overlay to the model. |
| `:overlay()` | None | `OverlayCollection` | Subsystem for adding markers or rings around this object. |
