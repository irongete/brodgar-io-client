# Gob Look & Visual Overrides

Modify how a game object is rendered locally on the client (visual scaling, tinting, and visibility toggling). These operations are purely visual, client-side, and **unprotected**.

## Quick Example

```lua
local session = hafen.session():current()
local boar_gob = session and session:world():gob():nearest("kritter/boar")

if boar_gob then
  -- Highlight target boar: tint red and scale to 1.5x size
  boar_gob:tint({ 255, 0, 0, 100 })
    :scale(1.5)
end
```

---

## Methods on `Gob`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:scale()` | None | `number \| nil` | Current visual scale factor (default `1.0`). |
| `:scale(factor)` | `number` | `self` | Scales visual model size (`factor > 0`). Chains. |
| `:visible()` | None | `boolean \| nil`| `true` if model is currently rendered. |
| `:visible(is_visible)`| `boolean` | `self` | Shows or hides the 3D model. Hidden models cannot be clicked. Chains. |
| `:tint()` | None | `{r, g, b, a} \| nil` | Current color tint applied to the model. |
| `:tint(color_table)` | `{r, g, b, [a]} \| nil` | `self` | Applies a translucent color wash over the model. Pass `nil` to clear. Chains. |

> All visual overrides revert automatically when your addon reloads or unloads.
