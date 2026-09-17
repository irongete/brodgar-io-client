# Translating the Client

One catalogue says what this client displays. An entry names the string the client would have drawn and the surface it is drawn at. A translation is a JSON file your addon ships, loaded from Lua. No permission. Client-local. Releasing it or disabling your addon puts the client's English back.

```lua
hafen.locale():load{
  text = {
    button = { Cancel = "Cancelar" },
    ["window.title"] = { Inventory = "Inventario" },
  },
}:install()
```

---

## One catalogue, one command

Reload, open a window, and the caption is in your words. `hafen.locale():release()` restores the client's own.

| Rule | Detail |
|---|---|
| One catalogue per addon | Handed back by identity from [`hafen.locale()`](../api/locale.md). `:install()` replaces the displayed one whole. `:load(document)` on an installed catalogue is what the client says from that line on: no re-apply verb, nothing to restart. |

## Write the first file by playing

A key is the string as the surface composed it. A tooltip row carries the number the player's item put in it. A System line carries the name of the addon that wrote it. So write the file from what the client offered. Install a catalogue that names nothing, play, and read back what missed.

```lua
hafen.locale():load({}):install()          -- names nothing, and records everything that missed

hafen.console():on("dump", function()
  local catalogue = hafen.store():var("catalogue")
  for _, miss in ipairs(hafen.locale():miss():list()) do
    catalogue[miss:surface()] = catalogue[miss:surface()] or {}
    catalogue[miss:surface()][miss:text()] = miss:text()  -- English to English, ready to edit
  end
  hafen.log():write(hafen.json():encode({ text = catalogue }))   -- the file, on the terminal
end)
```

| Rule | Detail |
|---|---|
| The sweep | Open the windows you mean to translate, hover the items, right-click for a menu, type `:dump`. The terminal shows a JSON object keyed as a catalogue's `text` is, every string doubled. Translate the right-hand side. |
| Kept in a var | Reached through `hafen.store()`, since the client's strings are not one character's. The live table grows across sweeps. |
| Rounds | `:install()` starts a fresh round, so re-installing between sweeps tells you what one sweep reached. [`locale:miss()`](../api/locale.md#what-missed) is per addon, bounded, and holds neither a string yours matched nor anything an addon drew, so the same command is your progress report. |

## The file

Nothing in a catalogue is a handle or code, so the document is JSON your addon ships.

```json
{ "text": {
    "button":       { "Cancel": "Cancelar", "Buy": "Comprar" },
    "window.title": { "Inventory": "Inventario", "Character Sheet": "Hoja de personaje" },
    "menu":         { "Pick": "Recoger", "Destroy": "Destruir" },
    "*":            { "Water": "Agua" }
  },
  "pattern": [
    { "surface": "tooltip", "match": "Quality: (\\d+)", "text": "Calidad: %1$s" }
  ]
}
```

| Rule | Detail |
|---|---|
| The key is the surface | The surface's own key is looked up first, `"*"` after it has missed. [The surfaces a catalogue names](../api/locale.md) draw text. A key naming a window's frame or a checkbox raises listing them. |
| A tooltip is many rows | An item's name, quality, wear and each bonus reach `tooltip` as separate strings. An entry names one row. Most are drawn by code inside the resource, keyed and answered like a button caption. |

## The strings the client composed

A row carrying a number is a different string every time, so a [pattern](../api/locale.md#patterns-the-strings-the-client-composed) names its shape. A pattern is the surface, a `match` for the whole string, and the `text` to draw with `%1$s` where the first capture group goes.

```lua
hafen.locale():load{
  pattern = {
    { surface = "tooltip", match = "(\\d+) uses left", text = "quedan %1$s usos" },
    { surface = "heading", match = "(\\w+) of (\\w+)",  text = "%2$s: %1$s" },
  },
}:install()
```

| Rule | Detail |
|---|---|
| Groups by number | `%2$s` is the second group wherever it stands, so your language orders the parts. |
| An ordered list | Two patterns can describe one string and position says which wins. Every exact key is consulted first. |
| Write `match` tightly | A pattern reaches a whole string, not a word. One wide enough to catch a composed line catches a typed one too. That is why a catalogue keys on `button` and `menu` rather than `"*"`. |

## Ship it

```lua
local document = hafen.json():parse(hafen.asset():get("es.json"):text())
hafen.locale():load(document):install()
```

[`hafen.asset`](../api/asset/README.md) reads the file out of your folder as text, [`hafen.json`](../api/json.md) parses it. A language picker is one addon choosing which file to read. `locale:info()` says whether yours is in force and how much it holds. Two addons' catalogues stack, the last installed winning per entry. A string yours does not name falls through to the one beneath rather than to English.

## What a catalogue does not reach

| Not reached | Detail |
|---|---|
| Free text | A chat body, a kin name, a player's words: a different string every time, so only a pattern around the varying part comes close. |
| What the user types | No catalogue matches an entry field, `"*"` included. `textentry` is refused as a key. |
| The model | A catalogue lands at the render. [`widget:text()`](../api/ui/widget.md), a [`[title=]` selector](../api/ui/selectors.md), an [action's](../api/menugrid.md) name and a [petal's](../api/flowermenu.md) answer the client's English while yours is installed. That lets an addon that reads a caption and one that translates it run side by side, and it is why `locale:miss()` exists. |

**Next:** [debugging](debugging.md) — the reload loop, the inspector, and finding out why a string did not change.
