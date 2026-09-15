# hafen.locale: Localization & Translations

Load translation catalogs, translate client display text, and inspect untranslated strings.

## Quick Example

```lua
-- Load and activate a translation catalog
hafen.locale():load({
  text = {
    ["button"] = {
      ["Craft"] = "Fabricar",
      ["Close"] = "Cerrar"
    },
    ["tooltip"] = {
      ["Quality"] = "Calidad"
    }
  },
  pattern = {
    { surface = "tooltip", match = "Quality: (%d+)", text = "Calidad: %1$s" }
  }
}):install()

-- Dump strings that missed translation
for _, missing_entry in ipairs(hafen.locale():miss():list()) do
  hafen.log():write(missing_entry:surface() .. ": " .. missing_entry:text())
end
```

---

## Methods on `hafen.locale()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:load(document)` | `table` | `self` | Parses and stages a translation catalog document. |
| `:install()` | None | `self` | Activates the staged translation catalog live on the client display. |
| `:release()` | None | `self` | Deactivates the installed catalog and restores default client strings. |
| `:miss()` | None | `MissCollection` | Collection of strings that reached surfaces without matching translations. |
| `:info()` | None | `table` | Snapshot `{ installed, entries, patterns, misses, surfaces }`. |

---

## Methods on `Miss`

| Method | Returns | Description |
|---|---|---|
| `:surface()` | `string` | The UI surface identifier where the string was rendered (e.g. `"button"`, `"tooltip"`). |
| `:text()` | `string` | The raw source text in the client's English. |
| `:exists()` | `boolean` | `true` if the miss entry remains recorded. |
| `:info()` | `table` | Plain table snapshot `{ surface, text }`. |
