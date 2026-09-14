# Item Contents

Inspect items held inside nested containers (such as stacks, bags, and creels) or fluid volumes inside vessels (buckets, barrels).

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

for _, item_entry in ipairs(session:ui():inventory():items():list()) do
  local contents = item_entry:contents()
  if contents then
    local fluid_info = contents:text() -- e.g. "Water 10.0L"
    if fluid_info then
      hafen.log():write(item_entry:name() .. " contains: " .. fluid_info)
    else
      local nested_count = contents:items():count()
      hafen.log():write(string.format("%s holds %d nested items.", item_entry:name(), nested_count))
    end
  end
end
```

---

## Methods on `Contents`

| Method | Returns | Description |
|---|---|---|
| `:items()` | `ItemCollection` | Array of concrete items held inside this item container. |
| `:name()` | `string \| nil` | Category or container caption name. |
| `:text()` | `string \| nil` | Text description of contained volume or fluid. |
| `:quality()` | `number \| nil` | Quality rating of the contained fluid or substance. |
| `:fill()` | `{cur, max} \| nil` | Current and maximum capacity fill levels. |
| `:info()` | `table` | Plain table snapshot. |
