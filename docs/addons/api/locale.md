# hafen.locale: what the client says

One **catalogue** per addon says what this client **displays**. An entry is keyed on the text the client
would otherwise have drawn and on the **surface** it is drawn at, so a button caption and a chat line
reading the same word are two different entries. Reach for it to ship a translation: a file and a dozen
lines of Lua. `hafen.locale()` is **unprotected** — it writes client-local, and releasing it undoes it.

```lua
local doc = hafen.json():parse(hafen.asset():get("es.json"):text())
hafen.locale():load(doc):install()

for _, m in ipairs(hafen.locale():miss():list()) do    -- ...and this is your next file
  hafen.log():write(m:surface() .. "  " .. m:text())
end
```

## The catalogue (unprotected)

| Call | Returns | Description |
|---|---|---|
| `hafen.locale()` | the catalogue | your addon's one catalogue, the same object every time |
| `locale:load(doc)` | self | replace what it says, **whole** — the [document](#the-document) is below |
| `locale:install()` | self | make it what the client displays, and start a fresh [miss](#what-missed) round |
| `locale:release()` | self | give the client its own words back |
| `locale:info()` | table | `{installed = …, entries = …, surfaces = {…}, misses = …}` |
| `locale:miss()` | collection | the strings that reached a surface with no entry — [below](#what-missed) |

**An addon owns exactly one catalogue**, so the document is the thing you keep and `:install()` is the
moment it becomes what the client says. Installing again replaces it whole: a string it no longer names is
drawn in English again on the spot.

**An edit to an installed catalogue applies at once.** `:load(doc)` on one that is in force is what the
client says from that line on. There is no re-apply verb, because there is nothing to re-apply: a catalogue
is either in force, in which case what it says is what you read on screen, or it is not.

The change is **live** — text already on screen re-renders — and the catalogue is **owned**: your addon's
`:reload` or disable drops it, so the client's own English is always one step away. `:install()` before any
`:load(doc)` raises, naming the door; `:release()` on one that was not installed does nothing, because a
removal that already happened is not an error.

**Two addons' catalogues stack.** The last one installed wins, **per entry**: a string it does not name
falls through to the catalogue beneath it rather than to English, and releasing it hands those strings back
the same way.

## The document

`:load(doc)` takes a whole catalogue at once, as a table. `text` is the strings it names exactly, keyed
first by the **surface** and then by the string the client would have drawn:

```lua
hafen.locale():load{
  text = {
    button = { Cancel = "Cancelar", Buy = "Comprar" },
    ["window.title"] = { Inventory = "Inventario" },
    ["*"] = { Water = "Agua" },
  },
}:install()
```

Nothing in it is a handle and nothing in it is Lua, so a translation is a JSON file your addon ships and one
command — which is what `hafen.json():parse` in the block at the top is doing.

A document carries `text` and `pattern`, and nothing else. Anything else in it is a typo, and a typo that
quietly did nothing is the worst answer available, so it raises naming both.

## The surfaces a catalogue names

A key is one of the surfaces below, or `"*"`. The surface's own key is looked up first and `"*"` only after
it has missed, so naming a surface exactly is always the stronger statement.

| Key | What it names |
|---|---|
| `*` | every surface below, consulted after the named key has missed |
| `default` | the fallback every unnamed text surface draws under, a plain label among them |
| `label` | a label built with a face of its own |
| `button` | button captions |
| `window.title` | window captions |
| `heading` | the section headings inside a window |
| `tooltip` | every tooltip, and **every row inside one** — see [below](#a-tooltip-is-many-rows) |
| `menu` | the flower menu and the context menus |
| `chat` | chat text, with `chat.system`, `chat.mine`, `chat.private` and `chat.party` for its kinds |
| `world.nick` | the floating names above characters |
| `world.speech` | speech bubbles |

A key naming a surface that draws no text — a window's frame, a panel, a checkbox, a rail, one of the HUD's
plates — raises, listing the ones that do. So does `textentry`, and for a reason of its own: **what the user
types is theirs.** No catalogue matches an entry field, an entry under `"*"` included, so a word being typed
into a search box is never rewritten as it is typed.

**Free text has no key at all.** A chat body, a kin name, an item's quality — anything the server or the
player generated — is not a string the client chose, so nothing names it and nothing matches it. That is
also why a catalogue keys on `button` and `menu` rather than reaching for `"*"` first.

**A key is the string as the surface composed it.** A catalogue is asked once, at the render, with whatever
the site had assembled by then — so a tooltip that carries a keyboard shortcut is one string with the
shortcut inside it, a line in the System log is one string with the name of the addon that wrote it in
front, and a window caption is the caption alone. Read what a surface actually offered off
[`locale:miss()`](#what-missed) rather than guessing at it: that is the string an entry goes under, spelt
exactly as an entry has to spell it.

**A chat line is keyed at the kind it is**, not at the channel it landed in: a System notice is
`chat.system`, your own line is `chat.mine`, and `chat` names the ones that have no kind of their own. The
key a line reached is what `locale:miss()` hands back, so the four kinds never have to be told apart by
hand.

### A tooltip is many rows

An item tooltip is not one string. Its name, its quality, its wear, its gilding and each of its bonuses
are **separate rows**, composed one under another, and each one reaches `tooltip` as a key of its own. So
an entry names one row and leaves the rest of the tip alone — which is what you want, since most of those
rows carry a number the player's own item put there.

Most of them are also drawn by **code that ships inside the resource**, not by the client. That changes
nothing you write: a catalogue lands beneath every foundry, so a row a resource composed with a font of
its own is keyed and answered exactly like a button caption. It does mean the wording is the
*resource author's*, not the client's, so read the row off [`locale:miss()`](#what-missed) rather than
typing what you think it says.

## What missed

`locale:miss()` is the
[collection](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of the strings that
reached a routed surface while your catalogue was installed and that **your** catalogue named nothing for.
Each member is a miss:

| Call | Returns | Description |
|---|---|---|
| `miss:surface()` | string | the surface key it reached, which is the key an entry for it goes under |
| `miss:text()` | string | the string the client drew, in the client's own English |
| `miss:info()` | table | `{surface = …, text = …}` |

It records from the moment you hold one, entries or none — installing an empty catalogue and reading this
back is how the first file gets written. `:install()` starts a fresh round, and the set holds `512` pairs,
after which it stops growing.

```lua
hafen.locale():load({}):install()             -- names nothing, records everything

hafen.slash():on("dump", function()           -- open the windows you want, then type :dump
  local out = {}
  for _, m in ipairs(hafen.locale():miss():list()) do
    out[m:surface()] = out[m:surface()] or {}
    out[m:surface()][m:text()] = m:text()     -- English to English, ready to edit
  end
  hafen.log():write(hafen.json():encode({ text = out }))
end)
```

A miss is what **your** catalogue did not answer, so another addon's gaps are never reported as yours, and a
string yours matched is not in the set at all.

## The model is not translated

A catalogue lands at the **render**, which is the last thing that happens to a string before it becomes
pixels — and nowhere above it. So while yours is installed:

- `w:text()`, `w:title()` and a `[title=]` or `[text=]` [selector](ui/selectors.md) all answer the client's
  own English, and `w:text(s)` round-trips;
- an [action's](menugrid.md) name and a [petal's](flowermenu.md) are the client's own words too, so
  `select(label)` and the [event](event/bus.md) that names a petal go on matching what you wrote before you
  had a catalogue;
- an addon that reads a caption and one that translates it never disagree.

That is the whole of what this section changes: what you **see**, and nothing else. It also means no addon
can observe your translation from Lua — `locale:miss()` is the one thing that reads back, which is why it
exists.

There is no inverse lookup and nothing here travels to the server: the model is English by construction, so
what the server hears is the English the client would have sent.

## See also

- [`hafen.ui`: the stylesheet](ui/style/README.md) — what a surface is drawn with, keyed the same way
- [`hafen.json`](json.md) — parsing the file a catalogue is shipped as
- [`hafen.asset`](asset.md) — reading a file your addon ships
- [conventions](conventions.md) — collections, snapshots and the grammar every verb here follows
