# UI Type Snapshots

Table schemas for widget, channel, pagina, and crafting recipe snapshots.

---

## `WidgetInfo` (Widget Snapshot)

Returned by `widget:info()`:

```lua
{
  type = "Window",        -- string: widget class name
  id = 12,               -- number | nil: server widget ID
  name = "my_window",    -- string | nil: addon assigned name
  position = { x = 60, y = 80 }, -- table: position in parent
  size = { w = 200, h = 100 },   -- table: outer dimensions
  visible = true,        -- boolean
  enabled = true         -- boolean
}
```

---

## `ChannelInfo` (Chat Channel Snapshot)

Returned by `channel:info()`:

```lua
{
  name = "Area",         -- string: tab caption
  kind = "area",         -- string: "area" | "party" | "village" | "pm"
  urgency = 0            -- number: unread activity score
}
```

---

## `PaginaInfo` (Action Menu Entry Snapshot)

Returned by `pagina:info()`:

```lua
{
  name = "Dig",          -- string: display name
  res = "paginae/act/dig",-- string: resource path
  unseen = false         -- boolean: discovery highlight
}
```
