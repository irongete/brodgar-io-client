# Theming

One table says what the client looks like. A [selector](../api/ui/selectors.md) is the key, a table of
properties is the value, and installing it restyles the running client on the spot — no restart, no
patched files, and nothing the user has to undo. It needs no permission, and it is dropped the moment your
addon is.

## Your first rule

```lua
hafen.ui.skin{
  ["*"]    = { font = hafen.font("serif"):derive{ size = 11 } },
  ["chat"] = { color = {190, 210, 190} },
}
```

`["*"]` is the fallback under everything; `["chat"]` refines one surface out of it. Reload, and the chat
log is green in a serif face. `hafen.ui.skin(nil)` puts the stock client back, as does disabling your
addon.

**An addon owns exactly one sheet**, and a second `skin{…}` replaces the first *whole* rather than merging
into it. So keep your rules in a table you can edit and re-apply, instead of calling `skin` twice:

```lua
local rules = {}

local function rule(key, props)
  rules[key] = props                                        -- props = nil drops that rule
  if next(rules) then hafen.ui.skin(rules) else hafen.ui.skin(nil) end
end
```

## What a key can name

Two kinds, both written in the same grammar:

- A **[site key](../api/ui/style/keys.md#site-keys)** names a place the client draws — `chat`,
  `window.title`, `window.frame`, `panel`, `button`, `tooltip`, `label` — wherever in the client that is.
  It is how you restyle a *family* of surfaces at once.
- A **[tree key](../api/ui/style/keys.md#tree-keys)** is an ordinary selector, and it styles the widgets it
  matches, plus everything inside them: `["window[title=Inventory]"]` dresses that window and its contents.

[surfaces](../api/ui/style/surfaces.md) says what each of the client's own surfaces is and how it behaves
under a rule, and [what each key accepts](../api/ui/style/keys.md#what-each-key-accepts) says which
properties a given key honours — a site that draws text has no background of its own to paint.

## What a rule can say

| Property | Says |
|---|---|
| `font`, `color` | [the text](../api/ui/style/text.md) |
| `bg`, `border`, `pad` | [the surface it is painted on](../api/ui/style/chrome.md) |
| `pos`, `size`, `anchor` | [where the widget is, and how big](../api/ui/style/geometry.md) |

Properties are independent: a colour-only rule leaves the font alone, a border-only rule leaves the
background. A misspelt property is an error naming the ones that exist, which is the failure you want.

```lua
["window.frame"] = {
  bg     = { color = {26, 26, 28, 240} },
  border = { image = hafen.asset("frame.png"), slice = {12, 40, 12, 12} },
  pad    = 4,
},
["window[title=Inventory]"] = {
  anchor = { to = "screen", at = "bottomright", offset = {-8, -8} },
},
```

## One widget, and the cascade

To restyle a single widget you already hold, call [`skin` on it](../api/ui/style/README.md#restyle-one-widget)
rather than inventing a selector that matches only it. That is the top of a cascade which resolves
most-specific-first — the widget's own entry, then the matching tree rule, then the site rule, then `*`,
then the client's stock — and **every level composes per property**, so a narrow rule never silently drops
a broad one. [`widget:style()`](../api/ui/widget.md) reads back what a widget actually resolves to, which
is the answer to "why is that still the wrong colour".

Layout resolves through the same cascade with a different top: the hand-named level for `pos` and `size` is
the [verb](../api/ui/native.md), `w:position(x, y)`, not a `skin` call.

## A theme is a file

Nothing in a rule is code the client calls: a colour is three numbers, a `pad` is a number, an anchor is a
corner and an offset. So a whole look can live in a JSON file your addon ships, with the Lua doing nothing
but reading it:

```lua
local sheet = hafen.json():parse(hafen.asset("theme.json"):text())
-- map the two values JSON cannot carry -- a font face and an image -- to handles, then:
hafen.ui.skin(sheet)
```

That is exactly what the bundled **`theme`** addon does, and it is why making a different theme is editing
a file rather than writing an addon. Saving a *layout* is the same trick from the other side: window
positions read back with `widget:position()` are a table of numbers, and [`hafen.store`](../api/store.md)
persists tables.

## Where it stops

The sheet restyles; it does not rebuild. There is no hover or pressed state, no descendant selectors, no
animation, and no re-flowing of what a client window puts inside itself — that last one is
[replacing](../api/ui/replace.md) the window, not styling it.
[Where the skinning system ends](../api/ui/style/README.md#where-the-skinning-system-ends) states the
whole boundary and why each part of it is a decision rather than a gap.

Two addons may style the same surface: the entries are tagged by owner, the last applied wins, and
disabling one falls back to the next owner beneath it.

**Next:** [debugging](debugging.md) — when the rule, the selector or the addon does not do what you meant.
