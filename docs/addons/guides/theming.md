# Theming

One sheet per addon says what the client looks like. A [selector](../api/ui/selectors.md) names a rule, the rule's properties are setters, and installing the sheet restyles the running client at once. No restart, no patched files, no permission. Dropped when your addon is.

---

## Your first rule

```lua
local sheet = hafen.ui():sheet()
sheet:rule("*"):font(hafen.font():get("serif"):derive():size(11))
sheet:rule("chat"):color{190, 210, 190}
sheet:install()
```

`"*"` is the fallback under everything. `"chat"` refines one surface. `sheet:release()` puts the stock client back, as does disabling your addon.

| Rule | Detail |
|---|---|
| One sheet per addon | `hafen.ui():sheet()` hands it back by identity. `:install()` replaces the applied sheet whole. |
| A rule is the same object each time its selector is named | An edit to an installed sheet lands on the spot. `sheet:rule("chat"):release()` gives one rule back. |

## What a key can name

| Kind | Names | Example |
|---|---|---|
| [Site key](../api/ui/style/keys.md#site-keys) | A place the client draws, wherever it is: a family of surfaces at once. | `chat`, `window.title`, `window.frame`, `panel`, `button`, `tooltip`, `label` |
| [Tree key](../api/ui/style/keys.md#tree-keys) | The widgets a selector matches, and everything inside them. A chain reaches one part of one window. | `window[title=Inventory]`, `window[title=Inventory] label` |

[Surfaces](../api/ui/style/surfaces.md), [chat](../api/ui/style/chat.md) and [hud](../api/ui/style/hud.md) say what each surface is under a rule. [what each key accepts](../api/ui/style/keys.md#what-each-key-accepts) says which properties a key honours.

## What a rule can say

| Setter | Says |
|---|---|
| `:font(h)`, `:color(c)` | [The text](../api/ui/style/text.md). |
| `:emboss(v)`, `:glow(t)` | [What a carved caption is filled with, and the halo behind it](../api/ui/style/text.md#emboss). |
| `:bg(t)`, `:border(t)`, `:padding(n)` | [The surface it is painted on](../api/ui/style/chrome.md). |
| `:picture(t)` | [The whole plate a surface is](../api/ui/style/chrome.md#picture). |
| `:caption(t)`, `:closeButton(t)`, `:sizer(t)` | [A window's ornaments](../api/ui/style/chrome.md#ornaments). |
| `:position(x, y)`, `:size(w, h)`, `:anchor(t)`, `:margin(n)` | [Where the widget is, how big, and the room around it](../api/ui/style/geometry.md). |

Each setter returns the rule and reads back with no argument. Properties are independent. A misspelt property raises naming the ones that exist.

```lua
sheet:rule("window.frame")
  :bg{ color = {26, 26, 28, 240} }
  :border{ box = "gfx/hud/wnd", mode = "tile" }    -- or your own art, cut into a 9-slice
  :padding(4, 12, 4, 4)
sheet:rule("window.title"):emboss(false):color{230, 220, 190}
sheet:rule("window[title=Inventory]"):anchor{ to = "screen", at = "bottomright", offset = {-8, -8} }
```

## A theme is a file

Every value has a JSON spelling, so a whole look lives in a file and the Lua only reads it. A [picture](../api/ui/style/chrome.md#naming-a-picture) is a path or a resource name. A [face](../api/ui/style/text.md#font) is named the same way. A list of pictures is an array. A walked colour is a [sequence](../api/ui/style/chat.md#the-colours-the-client-walks).

```lua
local theme = hafen.json():parse(hafen.asset():get("theme.json"):text())
hafen.ui():sheet():load(theme.rules):install()
```

```json
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

| Rule | Detail |
|---|---|
| What the file says | A serif face at 11 design pixels under everything. The client's own frame, named by its folder, edges tiled, over two background layers. Room of 8 px on three sides and 24 at the top. A caption is painted, and the room for a title bar is the padding's. The chat in one colour. Speakers cycling two colours of yours. |
| `sheet:load(rules)` | Takes the whole sheet and replaces what it said: the file is the document. Properties are the setter names. An unknown one raises. |
| A `size` on `"*"` reaches every surface | Captions and headings included. [Omitting the size](../api/ui/style/text.md#font) swaps the family alone and leaves each surface its height. |

## Start from the client's own look

`sheet:stock()` is what the client draws with when no rule says anything, keyed by site, in the shape `sheet:load()` takes. Open the windows you mean to read first: a site that has not drawn declares nothing — [the client's own look](../api/ui/style/README.md#the-clients-own-look).

```lua
local look = hafen.ui():sheet():stock()
hafen.log():write(hafen.json():encode(look))   -- the whole catalogue, ready for theme.json
hafen.ui():sheet():load(look):install()        -- and the client looks exactly as it did
```

## One widget, and the cascade

To restyle a single widget you hold, ask it for [its own rule](../api/ui/style/README.md#restyle-one-widget). That is the top of a cascade resolved most-specific first, composing per property. The levels: the widget's own, the matching tree rule, the site rule, `*`, [the widget's own stock](../api/ui/custom.md#naming-and-dressing-your-own-surfaces), the client's stock. [`widget:style()`](../api/ui/widget.md) reads what a widget resolves to. Layout resolves through the same cascade with the verb [`widget:position(x, y)`](../api/ui/native.md) as its top. A saved layout is a table of `widget:position()` reads kept in [`hafen.store`](../api/store/README.md).

## Reaching another addon's surfaces

An addon that [names](../api/ui/custom.md#naming-and-dressing-your-own-surfaces) the surfaces it builds is themed like anything else, knowing nothing about themes.

```json
{ "rules": {
    "[name=actionbars/bar]":   { "border": { "box": "gfx/hud/wnd", "mode": "tile" } },
    "[name^=actionbars/slot]": { "bg": { "asset": "img/slot.png", "mode": "stretch" } }
} }
```

| Rule | Detail |
|---|---|
| `[name=…]` | [The one refiner an addon owns](../api/ui/selectors.md#the-one-refiner-an-addon-owns), written `<addon>/<name>`. It outranks every other part of a selector. |
| One step is enough | What an addon declares for itself is a stock beneath every rule, so your rule wins without chaining. |
| No site key falls into it | `["*"]` reaches the places the client draws. An addon's surface is reached only by a rule naming it. |
| `^=` reaches a group | `[name^=actionbars/slot]` dresses `slot1`…`slot12`. `[name=actionbars/slot7]` dresses one of them. The two weigh the same, so an exception is a chain, `[name=actionbars/bar] [name=actionbars/slot7]`. |
| Finding names | Ask the addon, or point the `widgetstack` addon at it ([the maintainer's addons](../examples.md)). A name nobody answers to matches nothing. |

## Where it stops

The sheet restyles and does not rebuild: a state is a face [inside a value](../api/ui/style/chrome.md#a-face-per-state), not a key. There is no animation. Re-flowing what a client window puts inside itself is [replacing](../api/ui/replace.md) it. [Where the skinning system ends](../api/ui/style/README.md#where-the-skinning-system-ends) states the boundary. Two addons styling one surface: entries tagged by owner, the last applied wins, disabling one falls back to the next.

**Next:** [translating](translating.md) — what a surface draws, rather than what it is drawn with.
