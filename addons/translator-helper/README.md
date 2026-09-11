# Translator helper

Displays the client in **Spanish, Russian or Chinese** and collects every string that language does not
name yet, so you translate them one at a time in a window and see each one on screen the moment it is
saved. What it writes is one catalogue document per language — the very thing
[`hafen.locale():load(doc)`](../../docs/addons/api/locale.md) takes — ready to ship as `<language>.json`.

## How it works

1. **Pick the language**, in the window or in Options ▸ AddOns ▸ Translator helper. `Spanish`, `Russian`
   or `Chinese` installs that language's catalogue: the client displays what it names, and everything it
   does not name is drawn in English and **recorded**. `English` releases it — the client's own words, and
   nothing is collected.
2. **Play.** Every string a routed surface draws while the catalogue is up — a button, a window title, a
   menu petal, a tooltip row, a chat line — is checked against the catalogue, and a string it does not
   name lands in the list once, keyed by the surface it reached and spelt exactly as the client offered it.
   Open the windows you mean to translate, hover the items, right-click for the menus.
3. **Translate.** `:translator` opens the window. Pick a row: the surface and the English are shown, and
   the entry holds the English to edit. Type the translation and press **Enter** (or **Save**): the
   catalogue is reloaded on the spot, the string re-renders in your words wherever it is on screen, the
   row leaves *Pending*, and the next one is picked so you can go on typing.
4. **Check it live.** Switch the language in the dropdown and the whole client follows — every saved
   entry of that language in force, everything else in English — so a translation is read in place, in
   the button or the tooltip it belongs to.

The window's rows are `[surface] text`. **Show** picks the view — *Pending* (seen, and nothing answers
it), *Translated* (everything the language's document answers: its entries and its patterns, seen this
session or not), *Ignored*, *All* — and **Filter** narrows any view on a fragment of the row. The list
holds the first 500 matches; the status line says when to narrow.

**The edit line shows what the catalogue says for the picked row**: an exact entry (Match empty, the entry
holding the translation), a pattern (Match holding its match, the entry its text), or nothing yet (Match
empty, the entry holding the English). **Save** writes what the line shows — Enter in either field —
and **Delete** removes it.

- **Delete** removes what answers the picked row — the exact entry, or the pattern — and a string still
  being drawn goes back to *Pending*. An empty **Save** changes nothing.
- **Ignore** puts a string aside — a chat line carrying a player's name, a row carrying a number, anything
  not worth an entry — and **Restore** (the same button, on an ignored row) brings it back. Ignoring
  hides; it never deletes: a string that is still drawn would only be collected again.

## Patterns

A line with a name or a number in it is a different string every time — `Alistar is now online.`,
`Quality: 23` — so no exact entry can name it, and a **pattern** names its shape instead:

1. Pick one of the rows and press **Pattern**: Match holds the English with every special character
   escaped — `Alistar is now online\.`.
2. Replace the part that varies with a **group**: `(.*)` anything, `(\d+)` a number, `(\w+)` one word,
   `(a|b)` one of two — `(.*) is now online\.`. The pattern has to match the **whole** string.
3. Write the translation in the entry with `%1$s` where the first group goes (`%2$s` the second, in any
   order your language wants): `%1$s se ha conectado.` Enter.

Every pending row the pattern answers leaves *Pending* on the spot, and a line with another name in it
never enters the list. The pattern has a row of its own in *Translated* and *All* — `[chat.system] /(.*) is
now online\./` — and picking a string it answers shows it too: Save there edits the pattern in place,
Delete removes it. A match the client cannot read (an unclosed `(`, a `%2$s` with one group) is refused on
the spot: the document stays as it was and the status line says why.

Patterns are Java regular expressions, resolved after every exact entry has missed and **in the order they
were written** — the first that matches answers. The list reads the usual regex on its own to decide what
leaves it (escapes, `.`, classes, groups, alternation, the quantifiers and their lazy forms, `(?s)` and
`(?i)`); a construction it does not read — a lookaround, a backreference — still works in the client, but
the rows it answers stay in the list this session. Reordering is done by hand, in the file.

**Only a translation is saved.** The strings the list collects are held for the session: a `:reload` or a
restart starts the list again from what the client draws next, and a string put aside with *Ignore* is put
aside until then. What is in the list is shared by the three languages while it lasts — a string collected
while Spanish was up is pending in Russian too, until Russian names it.

## The file

Everything lands in **`savedata/account/translator-helper.json`**, written by the client. Its `es`, `ru`
and `zh` keys are the three documents, and each one is **exactly the content of `<language>.json`** —
`es.json`, `ru.json`, `zh.json` — nothing to convert:

```json
{
  "text": {
    "button":       { "Cancel": "Cancelar", "Buy": "Comprar" },
    "window.title": { "Inventory": "Inventario" },
    "menu":         { "Pick": "Recoger" },
    "tooltip":      { "Water": "Agua" }
  },
  "pattern": [
    { "surface": "tooltip", "match": "Quality: (\\d+)", "text": "Calidad: %1$s" }
  ]
}
```

`text` is keyed by the **surface** first and by the string the client would have drawn second; the surfaces
are the ones [`hafen.locale`](../../docs/addons/api/locale.md#the-surfaces-a-catalogue-names) lists, and
the helper only ever writes the ones the client actually reached. `pattern` is the **ordered** list the
[Patterns](#patterns) section writes, absent while there is none. An edit by hand — reordering the
patterns, say — is made **with the client closed**: the client rewrites the file while it runs, and would
overwrite an edit made underneath it.

The addon that ships a translation is then the whole of this:

```lua
local doc = hafen.json():parse(hafen.asset():get("es.json"):text())
hafen.locale():load(doc):install()
```

Beside the three documents the file keeps one thing of the helper's own — `settings`, where the window
stands — which a translation addon has no use for and simply leaves behind. Nothing the list collected is
in it.

## Options and keys

- Options ▸ AddOns ▸ Translator helper — the same **Language** dropdown as the window's; both move together.
- `:translator` opens and closes the window; it comes back where you left it, and open if it was open.
- **toggle** — the same, on a key. Suggested key: **Ctrl+T** — assign it in Options ▸ Game ▸ Keybindings ▸
  Translator helper.

## Notes

- **The window's own text is never listed.** It is drawn by the same client it watches, so its captions
  reach the catalogue like any other label: the fixed ones — *Language*, *Show*, *Filter*, *Match*,
  *Pattern*, *Save*, *Delete*, *Ignore*, *Restore*, the view and language names, *Pick a row*,
  *Translator helper* — are skipped when collecting, and the rows, the source line and the status line,
  which change all the time, are named by
  their shape in the installed catalogue (`[surface] text` and `es -- N pending, …` at `default`) so they
  never miss at all. Neither reaches the file. The one cost: a game string that is exactly one of those
  captions at the same surface is skipped too — write it into the file by hand. A document that an earlier
  round wrote a row of the list into is cleaned at load.
- **Two catalogues stack, and the top one answers first.** While another addon's catalogue is installed
  above this one — the translation addon that ships the file — a string it names never reaches the helper,
  as a translation or as a miss. Collect with the helper alone.
- A **tooltip row and a chat line are recorded as their marked-up source** (`$col[...]{...}`, `$b{...}`), not as
  the words drawn, because that is the string an entry has to spell. Translate inside the markup and leave
  the markup as it is.
- **What you type is what the entry takes.** Cyrillic needs the layout switched; Chinese needs the system
  IME to reach the client's window. The text drawn on screen is only as good as the font the client has for
  it: a face without the glyphs draws boxes, and a [theme](../themes/README.md) is where a face with them is
  installed.
- The catalogue changes what you **see** and nothing else: `w:text()`, a petal's name and everything an
  addon reads still answer the client's English, so the other addons keep working while a language is up.
