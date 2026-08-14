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
under a rule, with [the chat](../api/ui/style/chat.md) and [the HUD's plates](../api/ui/style/hud.md) on
pages of their own, and [what each key accepts](../api/ui/style/keys.md#what-each-key-accepts) says which
properties a given key honours — a site that draws text has no background of its own to paint.

## What a rule can say

| Setter | Says |
|---|---|
| `:font(h)`, `:color(r, g, b)` | [the text](../api/ui/style/text.md) |
| `:emboss(v)`, `:glow(t)` | [what a carved caption is filled with, and the halo behind it](../api/ui/style/text.md#emboss) |
| `:bg(t)`, `:border(t)`, `:padding(n)` | [the surface it is painted on](../api/ui/style/chrome.md) |
| `:picture(t)` | [the whole plate a surface **is**](../api/ui/style/chrome.md#picture) |
| `:caption(t)`, `:close(t)`, `:sizer(t)` | [a window's ornaments](../api/ui/style/chrome.md#ornaments) |
| `:position(x, y)`, `:size(w, h)`, `:anchor(t)` | [where the widget is, and how big](../api/ui/style/geometry.md) |

Each setter returns the rule, so a level is one expression, and each reads back with no argument.
Properties are independent: a colour-only rule leaves the font alone, a border-only rule leaves the
background. A misspelt property is an error naming the ones that exist, which is the failure you want.

```lua
s:rule("window.frame")
  :bg{ color = {26, 26, 28, 240} }
  :border{ box = "gfx/hud/wnd", mode = "tile" }   -- ...or your own art, cut into a 9-slice
  :padding(4, 12, 4, 4)
s:rule("window.title"):emboss(false):color(230, 220, 190)
s:rule("window[title=Inventory]")
  :anchor{ to = "screen", at = "bottomright", offset = {-8, -8} }
```

## A theme is a file

Nothing in a rule is code the client calls, and **four kinds of value cover the whole vocabulary**: a
**picture** is [named](../api/ui/style/chrome.md#naming-a-picture) — the path of a file you ship, or the
resource name of the client's own art — a [**face**](../api/ui/style/text.md#font) is named the same way, a
**list** of pictures is an array, and a colour the client hands out one at a time is the
[**sequence**](../api/ui/style/chat.md#the-two-colours-the-client-walks) it walks. Every one of them has a
spelling JSON carries, so a whole look lives in a file your addon ships and the Lua does nothing but read
it:

```lua
local doc = hafen.json():parse(hafen.asset():get("theme.json"):text())
hafen.ui():sheet():load(doc.rules):install()
```

```text
{ "rules": {
    "*":            { "font": { "builtin": "serif", "size": 11 } },
    "window.frame": { "border": { "box": "gfx/hud/wnd", "mode": "tile" },
                      "bg": [ { "res": "gfx/hud/wnd/lg/bg",  "mode": "tile" },
                              { "res": "gfx/hud/wnd/lg/bgl", "at": "left", "mode": "tile" } ],
                      "padding": [8, 24, 8, 8] },
    "chat":         { "color": [190, 210, 190] },
    "chat.speaker": { "color": { "palette": [[220, 190, 140], [150, 200, 220]] } }
} }
```

Four rules, and one property of each kind. A **face** named by its built-in, at 11
[design px](../api/ui/pixels.md), under everything. The client's **own** window frame, named by the folder
its eight pieces sit in, its edges repeated rather than stretched, over a background of two **layers** — a
tiled field with a shade down the left of it. Eight pixels of room between that frame and the window's
contents on three sides, and twenty-four at the top, because a caption is painted rather than laid out and
the room for a title bar is the padding's to make. The chat in one colour. And its speakers cycling two
colours of yours instead of the hue the client walks, since one flat colour would stop telling them apart.

`sheet:load(rules)` takes the whole sheet at once and replaces whatever it said, so the file **is** the
document. Inside it the properties are the setter names, an unknown one is an error naming the ones that
exist, and nothing is a handle — which is what makes editing the file the way you make a different theme.

**A `size` on `"*"` reaches every surface**, window captions and section headings included, so this file
sets the whole client at 11 and the big carved fraktur goes with it. That is the fallback working, not a
surprise — but [omitting the size](../api/ui/style/text.md#font) is what swaps the family alone and leaves
each surface the height it was built at. Name the size on the keys you mean when you want both.

## Start from the client's own look

You do not have to write the first one. `sheet:stock()` hands back what this client draws with when no rule
says anything, keyed by site, in the very shape `sheet:load()` takes:

```lua
local look = hafen.ui():sheet():stock()
hafen.log():write(hafen.json():encode(look))   -- the whole catalogue, ready to paste into theme.json
hafen.ui():sheet():load(look):install()        -- ...and the client looks exactly as it did
```

Open the windows you mean to read first: a site that has not drawn declares nothing, so what comes back is
what the client has *offered*. [The client's own look](../api/ui/style/README.md#the-clients-own-look) says
which properties a key leaves out, and why what is missing is worth reading as an edge of the grammar
rather than a gap in the answer.

## One widget, and the cascade

To restyle a single widget you already hold, ask it for
[its own rule](../api/ui/style/README.md#restyle-one-widget) rather than inventing a selector that matches
only it. That is the top of a cascade which resolves most-specific-first — the widget's own level, then the
matching tree rule, then the site rule, then `*`,
then the client's stock — and **every level composes per property**, so a narrow rule never silently drops
a broad one. [`widget:style()`](../api/ui/widget.md) reads back what a widget actually resolves to, which
is the answer to "why is that still the wrong colour".

Layout resolves through the same cascade with a different top: the hand-named level for `position` and
`size` is the [verb](../api/ui/native.md), `w:position(x, y)`, not a rule of your own. Saving a *layout* is
the same trick a theme file is: window positions read back with `widget:position()` are a table of numbers,
and [`hafen.store`](../api/store.md) persists tables.

## Where it stops

The sheet restyles; it does not rebuild. A state is a face **inside** a value rather than a key of its own —
a [`bg`](../api/ui/style/chrome.md#a-face-per-state) names one for `hover`, `pressed` or `disabled` — and
there is no animation and no re-flowing of what a client window puts inside itself, that last one being
[replacing](../api/ui/replace.md) the window rather than styling it.
[Where the skinning system ends](../api/ui/style/README.md#where-the-skinning-system-ends) states the
whole boundary and why each part of it is a decision rather than a gap.

Two addons may style the same surface: the entries are tagged by owner, the last applied wins, and
disabling one falls back to the next owner beneath it.

**Next:** [debugging](debugging.md) — when the rule, the selector or the addon does not do what you meant.
