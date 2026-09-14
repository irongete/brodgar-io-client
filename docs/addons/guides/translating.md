# Localization and Translation

The [`hafen.locale()`](../api/locale.md) API allows addons to support multiple languages and translate UI strings cleanly.

---

## 1. Registering Translation Catalogs

Addons register translation key-value mappings per language code (e.g. `"en"`, `"es"`, `"de"`):

```lua
local locale_manager = hafen.locale()

-- Register English catalog (default fallback)
locale_manager:register("en", {
  ["window.title"] = "Radar Scanner",
  ["button.start"] = "Start Scanning",
  ["status.scanning"] = "Scanning for nearby resources...",
  ["status.found"] = "Found %d items nearby."
})

-- Register Spanish catalog
locale_manager:register("es", {
  ["window.title"] = "Radar de Recursos",
  ["button.start"] = "Iniciar Escaneo",
  ["status.scanning"] = "Buscando recursos cercanos...",
  ["status.found"] = "Se encontraron %d objetos cerca."
})
```

---

## 2. Translating Strings in Code

Retrieve translated strings using `hafen.locale():translate(key, ...)` or `hafen.locale():t(key, ...)`:

```lua
local current_locale = hafen.locale()

-- Fetch a translated string
local window_title = current_locale:translate("window.title")

-- Fetch and format with variable arguments
local total_found = 4
local status_message = current_locale:translate("status.found", total_found)

hafen.log():write(status_message)
```

If a key is missing in the active language, the system automatically falls back to the `"en"` default translation or returns the raw key if no match exists.
