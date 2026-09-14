# hafen.locale: Localization & Translations

Manage multi-language translation catalogs and render localized text.

## Quick Example

```lua
local locale_manager = hafen.locale()

-- Register English catalog (default fallback)
locale_manager:register("en", {
  ["window.title"] = "Status Radar",
  ["radar.detected"] = "Detected %d players nearby."
})

-- Register Spanish catalog
locale_manager:register("es", {
  ["window.title"] = "Radar de Estado",
  ["radar.detected"] = "Detectados %d jugadores cerca."
})

-- Retrieve localized string
local active_title = locale_manager:translate("window.title")
local player_count = 3
local status_message = locale_manager:translate("radar.detected", player_count)

hafen.log():write(status_message)
```

## Methods on `hafen.locale()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:current()` | None | `string` | The active language code of the game client (e.g. `"en"`, `"es"`). |
| `:register(lang, catalog)` | `string, table` | `self` | Registers a dictionary of key-value translation strings for language code `lang`. |
| `:translate(key, ...)` | `string, ...` | `string` | Looks up `key` in the current language catalog (or falls back to `"en"`). Supports `string.format` placeholder arguments. |
| `:t(key, ...)` | `string, ...` | `string` | Shorthand alias for `:translate(key, ...)`. |
