# Interactive UI Controls

Input widgets for accepting player interaction: buttons, checkboxes, text entry fields, and sliders.

## Controls Reference

### Buttons (`hafen.ui():button()`)
Standard clickable button widget.

```lua
local action_button = hafen.ui():button()
  :text("Scan Surrounding Area")
  :parent(container_widget)

action_button:on("Click", function()
  hafen.log():write("Action button clicked!")
end)
```

---

### Checkboxes (`hafen.ui():check()`)
Toggleable boolean switch with text label.

```lua
local options_checkbox = hafen.ui():check()
  :text("Auto-harvest ripe crops")
  :value(true)
  :parent(container_widget)

options_checkbox:on("Changed", function(is_checked)
  hafen.log():write("Checkbox toggled: " .. tostring(is_checked))
end)
```

---

### Text Entries (`hafen.ui():entry()`)
Single-line editable text input field.

```lua
local name_entry = hafen.ui():entry()
  :text("DefaultName")
  :size(180)
  :parent(container_widget)

name_entry:on("Changed", function(new_text)
  hafen.log():write("Input changed to: " .. new_text)
end)
```

---

### Sliders (`hafen.ui():slider()`)
Draggable numerical range bar.

```lua
local range_slider = hafen.ui():slider()
  :range(10, 100)
  :value(35)
  :size(150, 20)
  :parent(container_widget)

range_slider:on("Changed", function(current_value)
  hafen.log():write("Slider value: " .. current_value)
end)
```
