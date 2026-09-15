# Localization and Translation

Translate client text, buttons, and tooltips using the [`hafen.locale()`](../api/locale.md) subsystem.

---

## 1. Creating and Loading a Translation Document

A translation document organizes translations by target surface or regular expression patterns. Catalogs are typically loaded from a `.json` file bundled with your addon:

```lua
-- Load and install translation document
hafen.locale():load({
  text = {
    ["button"] = {
      ["Craft"] = "Fabricar",
      ["Close"] = "Cerrar",
      ["Inventory"] = "Inventario"
    },
    ["tooltip"] = {
      ["Armor"] = "Armadura"
    }
  },
  pattern = {
    { surface = "tooltip", match = "Quality: (%d+)", text = "Calidad: %1$s" }
  }
}):install()
```

---

## 2. Discovering Untranslated Strings

To identify strings the client renders that your catalog does not yet handle, inspect `hafen.locale():miss()`:

```lua
-- Install empty or partial catalog to record misses
hafen.locale():load({}):install()

-- Dump recorded untranslated strings
for _, miss_entry in ipairs(hafen.locale():miss():list()) do
  hafen.log():write(string.format("[%s] %s", miss_entry:surface(), miss_entry:text()))
end
```

---

## 3. Releasing Translations

Translations apply purely on the visual rendering layer and revert when the addon unloads or calls `:release()`:

```lua
hafen.locale():release()
```
