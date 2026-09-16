# hafen.locale: What the Client Says

One catalogue per addon says what this client displays: an entry is keyed on the text the client would have drawn and on the surface it is drawn at, so a translation is a file and a dozen lines of Lua. Unprotected: it writes client-local, and releasing it undoes it.

```lua
local document = hafen.json():parse(hafen.asset():get("es.json"):text())
hafen.locale():load(document):install()

for _, miss in ipairs(hafen.locale():miss():list()) do    -- ...and this is your next file
  hafen.log():write(miss:surface() .. "  " .. miss:text())
end
```

---

## The catalogue (unprotected)

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.locale()` | the catalogue | Unprotected | Your addon's one catalogue, the same object every time. |
| `locale:load(document)` | self | Unprotected | Replace what it says, whole ([the document](#the-document)). |
| `locale:install()` | self | Unprotected | Make it what the client displays, and start a fresh [miss](#what-missed) round. |
| `locale:release()` | self | Unprotected | Give the client its own words back. |
| `locale:info()` | `table` | Unprotected | `{installed = …, entries = …, patterns = …, surfaces = {…}, misses = …}`; `surfaces` is the surface keys the loaded document names, in its order. |
| `locale:miss()` | collection | Unprotected | The strings that reached a surface with no entry ([below](#what-missed)). |

| Rule | Detail |
|---|---|
| One catalogue per addon | The document is what you keep; `:install()` makes it what the client says. Installing again replaces it whole: a string no longer named is drawn in English again on the spot. |
| An edit applies at once | `:load(document)` on an installed catalogue is what the client says from that line on; there is no re-apply verb. |
| Live and owned | Text already on screen re-renders. Your addon's `:reload` or disable drops the catalogue. |
| Refusals | `:install()` before any `:load(document)` raises naming the door; `:release()` on one not installed does nothing. |
| Two addons stack | The last installed wins per entry: a string it does not name falls through to the catalogue beneath, not to English, and releasing hands those strings back the same way. |

## The document

`:load(document)` takes a whole catalogue as a table carrying `text` and `pattern` and nothing else; another key raises naming both. `text` is keyed first by surface, then by the string the client would have drawn. Nothing in it is a handle or Lua, so a translation is a JSON file parsed with `hafen.json():parse`.

```lua
hafen.locale():load{
  text = {
    button = { Cancel = "Cancelar", Buy = "Comprar" },
    ["window.title"] = { Inventory = "Inventario" },
    ["*"] = { Water = "Agua" },
  },
}:install()
```

## The surfaces a catalogue names

A key is one of the surfaces below or `"*"`; the surface's own key is looked up first, `"*"` after it has missed.

| Key | Names |
|---|---|
| `*` | Every surface below, consulted after the named key has missed. |
| `default` | The fallback every unnamed text surface draws under, a plain label among them. |
| `label` | A label built with a face of its own. |
| `button` | Button captions. |
| `window.title` | Window captions. |
| `heading` | The section headings inside a window. |
| `tooltip` | Every tooltip, and every row inside one ([below](#a-tooltip-is-many-rows)). |
| `menu` | The flower menu and the context menus. |
| `chat` | Chat text, with `chat.system`, `chat.mine`, `chat.private` and `chat.party` for its kinds. |
| `world.nick` | The floating names above characters. |
| `world.speech` | Speech bubbles. |

| Rule | Detail |
|---|---|
| A surface that draws no text | A window's frame, a panel, a checkbox, a rail, a HUD plate: raises, listing the ones that do. |
| `textentry` raises | What the user types is theirs: no catalogue matches an entry field, `"*"` included. |
| Free text has no exact key | A chat body, a kin name, an item's quality is a different string every time: a [pattern](#patterns-the-strings-the-client-composed) names the shape around the varying part. Key on `button` and `menu` rather than `"*"` first: a pattern wide enough to catch a composed line catches a typed one too. |
| A key is the string as the surface composed it | Asked once at the render with what the site had assembled: a System line is one string with the writing addon's name in front, a long description tip is title and body as one document, a window caption is the caption alone. Read the exact string off [`locale:miss()`](#what-missed). |
| A shortcut is composed around your word | The catalogue is asked about the caption alone (`Inventory`, `Craft`) and the shortcut painted after: a widget's tip writes `(key)`, a rich one `Keyboard shortcut: key` under it (a key of its own), the [action menu](menugrid.md) highlights the bound letter inside your word or writes `[key]` after one with no such letter. |
| A chat line is keyed at its kind | A System notice is `chat.system`, your own line `chat.mine`, `chat` the ones with no kind. `locale:miss()` hands back the key a line reached. |

### A tooltip is many rows

An item tooltip's name, quality, wear, gilding and each bonus are separate rows, each reaching `tooltip` as a key of its own: an entry names one row and leaves the rest alone. Most rows are drawn by code shipped inside the resource, keyed and answered like a button caption, with the resource author's wording: read the row off [`locale:miss()`](#what-missed).

## Patterns: the strings the client composed

A row carrying a number, a line carrying a name, a caption carrying a count is a different string every time; a pattern names its shape. `pattern` is an ordered list whose members carry three properties and no others.

| Property | Detail |
|---|---|
| `surface` | The key it is written under: one of [the surfaces above](#the-surfaces-a-catalogue-names), or `*`. |
| `match` | A regular expression that has to match the whole string the client would have drawn. |
| `text` | What to draw instead, with `%1$s` where the first capture group goes. |

```lua
hafen.locale():load{
  pattern = {
    { surface = "tooltip", match = "Quality: (\\d+)",  text = "Calidad: %1$s" },
    { surface = "heading", match = "(\\d+) of (\\d+)", text = "%1$s de %2$s" },
  },
}:install()
```

| Rule | Detail |
|---|---|
| Groups by number | `( )` is a capture group; `%2$s` is the second group wherever it stands, so a translation reorders parts. Every other per-cent sign is a literal: `"%1$s% de calidad"` ends in a per-cent sign. |
| Order resolves | The pattern written first answers when two describe one string. A `pattern` written as a JSON object raises, since an object has no order. |
| Exact keys first | The surface's own key, then `*`; a pattern is reached only by a string no key names. A string a pattern answered is not a [miss](#what-missed). |
| Checked whole at `:load` | A document carrying a bad pattern leaves the catalogue as it was. |

| Raises | The refusal names |
|---|---|
| A `match` that does not compile | Which construction failed and where, so an unclosed `(` names its index. |
| `pattern` written as an object | That the list's order is what resolves two patterns describing one string. |
| An argument past the last group | The number asked for, and how many groups the `match` has. |
| An unknown property in a member | That a pattern carries `surface`, `match` and `text`. |
| A missing `surface`, `match` or `text` | Which one is missing, and what it is for. |
| A `surface` that draws no text, or `textentry` | What [an exact key is told](#the-surfaces-a-catalogue-names). |

## What missed

`locale:miss()` is the [collection](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of the strings that reached a routed surface while your catalogue was installed and that your catalogue named nothing for.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `miss:surface()` | `string` | Unprotected | The surface key it reached, the key an entry goes under. |
| `miss:text()` | `string` | Unprotected | The string the client asked about, in the client's English; the marked-up source for a surface that draws marked-up text. |
| `miss:info()` | `table` | Unprotected | `{surface = …, text = …}`. |

| Rule | Detail |
|---|---|
| Records from `:install()` | Entries or none: installing an empty catalogue and reading this back is how the first file gets written. Each `:install()` starts a fresh round; the set holds `512` pairs, then stops growing. |
| Paste `miss:text()` as it came | For the chat and both tooltip flavours it is the marked-up source, not the drawn words. |
| Your catalogue's gaps | Another addon's gaps are never yours; a string yours matched is not in the set. With two installed the [stack](#the-catalogue-unprotected) is walked from the top and stops at the first catalogue that answers, so a string one above yours named reaches you neither as a translation nor as a miss. Read misses with yours alone installed for the whole of a surface. |
| The client's strings only | What an addon draws (its own window caption, a `graphics:text`, a listbox row, its button's tip, a label from Lua) is translated but never recorded. What the client composes for an addon is the client's: a `hafen.log():write` line in System is keyed at `chat.system`; an [action](menugrid.md) of yours on the menu, your addon's name on the AddOns page and a hotkey's label on the keybindings page miss like any caption there. |

```lua
hafen.locale():load({}):install()             -- names nothing, records everything

hafen.console():on("dump", function()         -- open the windows you want, then type :dump
  local missed = {}
  for _, miss in ipairs(hafen.locale():miss():list()) do
    missed[miss:surface()] = missed[miss:surface()] or {}
    missed[miss:surface()][miss:text()] = miss:text()     -- English to English, ready to edit
  end
  hafen.log():write(hafen.json():encode({ text = missed }))
end)
```

## The model is not translated

A catalogue lands at the render, the last thing that happens to a string before pixels, and nowhere above it.

| Read | While your catalogue is installed |
|---|---|
| `widget:text()`, `widget:title()`, a `[title=]` or `[text=]` [selector](ui/selectors.md) | The client's own English; `widget:text(text)` round-trips. |
| An [action's](menugrid.md) name, a [petal's](flowermenu.md) | The client's words: `select(label)` and the [event](event/bus/README.md) naming a petal go on matching what you wrote before a catalogue. |
| A tooltip row | The same, though an entry under `tooltip` changed it on screen: [`item:name()`](ui/items.md#the-item-object), `item:info().name`, the rows [`contents:text()`](ui/contents.md) prints, [`buff:name()`](buff.md#read), [`wound:name()`](wound.md#a-wound), the slot names [`item:slots()`](ui/items.md#the-item-object) answers. |
| [`gob:speech()`](gob.md#read) | English, while the bubble reads your words. |

| Rule | Detail |
|---|---|
| Only what you see changes | An addon that reads a caption and one that translates it never disagree; no addon observes your translation from Lua. `locale:miss()` is the one read-back. |
| The one exception | A name the item's own resource code handed the client already rendered, as a picture: no English underneath to answer with. The limit [`widget:text()`](ui/widget.md) has on a button built from a picture. |
| Nothing travels to the server | The model is English by construction; there is no inverse lookup. |

---

## See Also

- [`hafen.ui`: the stylesheet](ui/style/README.md) — what a surface is drawn with, keyed the same way.
- [`hafen.json`](json.md) — parsing the file a catalogue is shipped as.
- [`hafen.asset`](asset/README.md) — reading a file your addon ships.
- [Conventions](conventions.md) — collections, snapshots and the grammar every verb here follows.
