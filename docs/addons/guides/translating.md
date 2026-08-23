# Translating the client

One **catalogue** says what this client displays. An entry names the string the client would otherwise have
drawn and the **surface** it is drawn at, so a translation is a JSON file your addon ships and a dozen lines
of Lua. It needs no permission, it writes client-local, and releasing it — or disabling your addon — puts
the client's own English back.

## One catalogue, one command

```lua
hafen.locale():load{
  text = {
    button = { Cancel = "Cancelar" },
    ["window.title"] = { Inventory = "Inventario" },
  },
}:install()
```

Reload, open a window, and the caption is in your words. `hafen.locale():release()` hands them back.

**Your addon owns exactly one catalogue**, handed back by identity from
[`hafen.locale()`](../api/locale.md), and `:install()` replaces the displayed one **whole** rather than
merging into it. The document is yours to keep and edit: `:load(doc)` on an installed catalogue is what the
client says from that line on, so there is no re-apply verb and nothing to restart.

## Write the first file by playing

Do not guess at the strings. A key is the string **as the surface composed it** — a tooltip row carries the
number the player's own item put in it, a System line carries the name of the addon that wrote it — so the
file is written from what the client actually offered. Install a catalogue that names nothing, play, and
read back what missed:

```lua
-- addons/myaddon/main.lua, beside "saved_variables": ["catalogue"] in the manifest
hafen.locale():load({}):install()          -- names nothing, and records everything that missed

hafen.console():on("dump", function()
  local out = hafen.store():get("catalogue")
  for _, m in ipairs(hafen.locale():miss():list()) do
    out[m:surface()] = out[m:surface()] or {}
    out[m:surface()][m:text()] = m:text()  -- English to English, ready to edit
  end
  hafen.store():flush()                    -- savedata/account/myaddon.json, on disk
end)
```

Open the windows you mean to translate, hover the items, right-click something for its menu, then type
`:dump`. [`hafen.store`](../api/store.md) has written a JSON object keyed exactly as a catalogue's `text`
is, with every string doubled: translate the right-hand side of each pair and you have the file.

`:install()` starts a fresh round, so re-installing between two sweeps tells you what that one sweep
reached. [`locale:miss()`](../api/locale.md#what-missed) is per addon and holds a bounded set, and a string
yours matched is not in it at all — which is what makes the same command your progress report as the file
fills up.

## The file

Nothing in a catalogue is a handle and nothing in it is code, so the whole document is JSON your addon
ships:

```text
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

**The key is the surface**, and naming one exactly is always the stronger statement: the surface's own key
is looked up first and `"*"` only after it has missed. [The surfaces a catalogue names](../api/locale.md)
is the list of the ones that draw text — a key naming a window's frame or a checkbox raises, naming the
ones that do.

**A tooltip is many rows.** An item's name, its quality, its wear and each of its bonuses reach `tooltip` as
separate strings, so an entry names one row and leaves the rest of the tip alone. Most of those rows are
drawn by code that ships inside the resource rather than by the client, which changes nothing you write —
they are keyed and answered exactly like a button caption.

## The strings the client composed

A row carrying a number is a different string every time, so no exact key can name it and a
[pattern](../api/locale.md#patterns-the-strings-the-client-composed) names its shape instead. Each one
carries the surface it is written under, a `match` that has to match the **whole** string, and the `text` to
draw, with `%1$s` where the first capture group goes:

```lua
hafen.locale():load{
  pattern = {
    { surface = "tooltip", match = "(\\d+) uses left", text = "quedan %1$s usos" },
    { surface = "heading", match = "(\\w+) of (\\w+)",  text = "%2$s: %1$s" },
  },
}:install()
```

An argument names its group **by number**, so `%2$s` is the second group wherever it stands — which is what
lets your language put the parts in its own order. Patterns are an **ordered list** because two of them can
describe one string and the position says which wins; every exact key is consulted first, so a pattern never
changes a line the file had already named.

Write the `match` as tightly as the string allows. A pattern reaches a whole **string**, not a word: one
written wide enough to catch a line the client composed will catch a line the player typed as well, which
is why a catalogue keys on `button` and `menu` rather than reaching for `"*"` first.

## Ship it

The Lua that ships a finished translation is the whole of it:

```lua
local doc = hafen.json():parse(hafen.asset():get("es.json"):text())
hafen.locale():load(doc):install()
```

[`hafen.asset`](../api/asset.md) reads the file out of your own folder — `.json` comes back as text, so
[`hafen.json`](../api/json.md) parses it. A language picker is two addons, or one addon choosing which file
to read: choosing a language is installing a catalogue.

`locale:info()` says whether yours is in force and how much it holds, and two addons' catalogues stack — the
last installed wins **per entry**, so a string yours does not name falls through to the one beneath it
rather than to English.

## What a catalogue does not reach

**Free text has no key.** A chat body, a kin name, a player's own words — anything the server or the player
generated — is a different string every time, so nothing under `text` can name it and only a pattern written
around the part that varies comes close.

**What the user types is theirs.** No catalogue matches an entry field, an entry under `"*"` included, so a
word being typed into a search box is never rewritten as it is typed. `textentry` is refused as a key for
that reason and says so.

**The model is not translated.** A catalogue lands at the render and nowhere above it, so
[`w:text()`](../api/ui/widget.md), a [`[title=]` selector](../api/ui/selectors.md), an
[action's](../api/menugrid.md) name and a [petal's](../api/flowermenu.md) all still answer the client's own
English while yours is installed. That is what lets an addon that reads a caption and one that translates it
run side by side — and it is why `locale:miss()` exists, since nothing else above the render can see a
translation at all.

**Next:** [debugging](debugging.md) — the reload loop, the inspector, and finding out why a string did not
change.
