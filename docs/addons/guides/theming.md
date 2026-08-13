# Theming

One **sheet** says what the client looks like. A [selector](../api/ui/selectors.md) names a **rule**, the
rule's properties are setters, and installing the sheet restyles the running client on the spot — no
restart, no patched files, and nothing the user has to undo. It needs no permission, and it is dropped the
moment your addon is.

## Your first rule

```lua
local s = hafen.ui():sheet()
s:rule("*"):font(hafen.font():get("serif"):derive():size(11))
s:rule("chat"):color(190, 210, 190)
s:install()
```

`"*"` is the fallback under everything; `"chat"` refines one surface out of it. Reload, and the chat
log is green in a serif face. `s:drop()` puts the stock client back, as does disabling your addon.

**An addon owns exactly one sheet**, handed back by identity from `hafen.ui():sheet()`, and `:install()`
replaces the applied one *whole* rather than merging into it. The document is yours to keep and edit: a
rule is the same object every time you name its selector, and an edit to an **installed** sheet lands on
the spot.

```lua
local s = hafen.ui():sheet()
s:rule("chat"):color(190, 210, 190)     -- ...or, once the sheet is installed, changes it live
s:rule("chat"):remove()                 -- and this drops that one rule
```

## What a key can name

Two kinds, both written in the same grammar:

- A **[site key](../api/ui/style/keys.md#site-keys)** names a place the client draws — `chat`,
  `window.title`, `window.frame`, `panel`, `button`, `tooltip`, `label` — wherever in the client that is.
  It is how you restyle a *family* of surfaces at once.
- A **[tree key](../api/ui/style/keys.md#tree-keys)** is an ordinary selector, and it styles the widgets it
  matches, plus everything inside them: `"window[title=Inventory]"` dresses that window and its contents.
  A key can be a **chain**, so one rule reaches one part of one window: `"window[title=Inventory] label"`
  is that window's rows and nobody else's.

[surfaces](../api/ui/style/surfaces.md) says what each of the client's own surfaces is and how it behaves
under a rule, and [what each key accepts](../api/ui/style/keys.md#what-each-key-accepts) says which
properties a given key honours — a site that draws text has no background of its own to paint.

## What a rule can say

| Setter | Says |
|---|---|
| `:font(h)`, `:color(r, g, b)` | [the text](../api/ui/style/text.md) |
| `:bg(t)`, `:border(t)`, `:padding(n)` | [the surface it is painted on](../api/ui/style/chrome.md) |
| `:position(x, y)`, `:size(w, h)`, `:anchor(t)` | [where the widget is, and how big](../api/ui/style/geometry.md) |

Each setter returns the rule, so a level is one expression, and each reads back with no argument.
Properties are independent: a colour-only rule leaves the font alone, a border-only rule leaves the
background. A misspelt property is an error naming the ones that exist, which is the failure you want.

```lua
s:rule("window.frame")
  :bg{ color = {26, 26, 28, 240} }
  :border{ box = "gfx/hud/wnd", mode = "tile" }   -- ...or your own art, cut into a 9-slice
  :padding(4, 12, 4, 4)
s:rule("window[title=Inventory]")
  :anchor{ to = "screen", at = "bottomright", offset = {-8, -8} }
```

## One widget, and the cascade

To restyle a single widget you already hold, ask it for
[its own rule](../api/ui/style/README.md#restyle-one-widget) rather than inventing a selector that matches
only it. That is the top of a cascade which resolves most-specific-first — the widget's own level, then the
matching tree rule, then the site rule, then `*`,
then the client's stock — and **every level composes per property**, so a narrow rule never silently drops
a broad one. [`widget:style()`](../api/ui/widget.md) reads back what a widget actually resolves to, which
is the answer to "why is that still the wrong colour".

Layout resolves through the same cascade with a different top: the hand-named level for `position` and
`size` is the [verb](../api/ui/native.md), `w:position(x, y)`, not a rule of your own.

## A theme is a file

Nothing in a rule is code the client calls: a colour is three numbers, a `padding` is four, an anchor is a
corner and an offset, a picture is [named](../api/ui/style/chrome.md#naming-a-picture) — the path of a file
you ship, or the resource name of the client's own art — and a
[face](../api/ui/style/text.md#font) is named the same way. So a whole look lives in a JSON file your addon
ships, and the Lua does nothing but read it:

```lua
local doc = hafen.json():parse(hafen.asset():get("theme.json"):text())
hafen.ui():sheet():load(doc.rules):install()
```

```text
{ "rules": { "*":            { "font": { "builtin": "mono", "size": 11 } },
             "window.frame": { "border": { "box": "gfx/hud/wnd", "mode": "tile" },
                               "padding": [8, 4, 8, 8] } } }
```

Once the look is a file, making a different theme is editing that file rather than writing an addon.
Saving a *layout* is the same trick from the other side: window
positions read back with `widget:position()` are a table of numbers, and [`hafen.store`](../api/store.md)
persists tables.

## Where it stops

The sheet restyles; it does not rebuild. There is no hover or pressed state, no animation, and no
re-flowing of what a client window puts inside itself — that last one is
[replacing](../api/ui/replace.md) the window, not styling it.
[Where the skinning system ends](../api/ui/style/README.md#where-the-skinning-system-ends) states the
whole boundary and why each part of it is a decision rather than a gap.

Two addons may style the same surface: the entries are tagged by owner, the last applied wins, and
disabling one falls back to the next owner beneath it.

**Next:** [debugging](debugging.md) — when the rule, the selector or the addon does not do what you meant.
