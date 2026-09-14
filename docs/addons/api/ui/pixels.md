# Design Pixels & UI Scaling

How pixel dimensions, coordinate spaces, and UI scaling operate across the `hafen.ui` subsystem.

---

## 1. Design Pixels vs. Screen Pixels

All coordinates and dimensions in the `hafen.ui` API (`:position()`, `:size()`, layout gaps, canvas drawing coordinates) are expressed in **Design Pixels**:
* Design pixels are independent of the physical display resolution.
* The client automatically scales design pixels to physical device pixels according to the player's **UI Scale** setting (in Options ▸ Video).

---

## 2. Reading UI Scale

Read the active scaling factor through [`hafen.client():scale()`](../client/README.md):

```lua
local active_scale = hafen.client():scale()
hafen.log():write("Current UI scale factor: " .. tostring(active_scale))
```

---

## 3. Pixel Invariants

* Windows sized to `(200, 100)` in code will appear at an identical relative visual proportion on high-DPI (4K) displays and standard 1080p monitors.
* Mouse pointer coordinates (`hafen.ui():mouse():position()`) are automatically converted to design pixels, matching widget bounds directly.
