# hafen.ui: naming a widget

A **selector is a string**, and `hafen.ui():find` / `:all` are the lookups. Reach for one whenever you
need a piece of the client's UI — to read it, to move it, to replace it, or as the key of a
[stylesheet](style/keys.md) rule.

```lua
hafen.ui():find("window[title=Cupboard]")             -- the first match, or nil
hafen.ui():all("inventory")                           -- every open container, in tree order
hafen.ui():find("window[title=Cupboard] inventory")   -- the grid inside that window
hafen.ui():find("window[title=Foo] button[text=Close]")  -- one exact widget, named in one string
hafen.ui():find("@Equipory")                          -- by widget class
hafen.ui():all("*[res*=gfx/hud/meter]")               -- by resource name, matched as a substring
hafen.ui():root()                                     -- no selector at all: the root of the whole tree
```

## The grammar

It is CSS. A selector is one or more **steps** separated by spaces, and each step is a role (or `*`)
followed by any of the refiners, in any order and each at most once.

| Part | Meaning |
|---|---|
| `*` | any widget, including one no role classifies |
| a **role** | what the widget *is* — see [the table below](#roles) |
| `@Class` | its class name, the same string `:type()` reports |
| `[title=…]` | a **window's own caption**; only valid in a step whose role is `window` |
| `[text=…]` | the words a widget **displays** — a button's caption, a label, an entry's contents |
| `[res=…]` | its resource name (`:res()`) |

**A space is the descendant combinator**, exactly as in CSS: `window[title=Cupboard] inventory` is the
container grid *anywhere* below the Cupboard window, however many layout wrappers sit in between. That is
how you name one exact widget in one string, and it is the only thing that looks upward — every refiner
tests the widget its own step is written on. There is no `>` (direct child).

**Every refiner takes one of four operators**, so a selector says which test it is doing:

| Operator | Matches when the value |
|---|---|
| `=` | is exactly this |
| `*=` | contains this |
| `^=` | starts with this |
| `$=` | ends with this |

Anything else raises an error naming the offending part and, for a bad role, listing every valid one. The
same error comes back when an invalid selector is used as a [sheet key](style/keys.md).

## Roles

`:role()` answers what a widget is, or `nil` when nothing classifies it. The names are the same vocabulary
as the stylesheet's [site keys](style/keys.md), deliberately, so there is one set of names rather than two.

| Role | Matches |
|---|---|
| `window` | `Window` and every subclass, including the `Hidewnd` the client wraps the inventory in |
| `inventory` | `Inventory` and `Equipory` — *every* open container, not just yours |
| `button` | `Button`, `IButton` |
| `label` | `Label` |
| `textentry` | `TextEntry` |
| `chat` | `ChatUI` and its channels |
| `menu` | `MenuGrid`, `FlowerMenu` |

**Some site-key names classify no widget** — `window.title`, `window.frame`, `panel`, `heading`, `tooltip`,
`world.nick`, `world.speech`. They name a *render site*, not a widget: a window's caption and frame are
drawn by the window's decoration, a tooltip is painted rather than placed, and the world sites live over
the 3D view. They stay valid selectors, because the vocabulary is shared with the sheet, but they match
nothing.

Most widgets have **no** role — layout containers, scroll ports, images, item icons. That is the rule
working, not a gap: an unrecognised widget answers `nil` rather than being guessed into the nearest role.
Reach those with `*`, `@Class`, `[res=]`, or by anchoring a chain on the window they sit in.

## Two rules that are easy to get wrong

- **`[title=]` is the window's *own* caption, and the space is what reaches inside it.** The client wraps
  bare widgets in titled windows — the inventory grid itself has no caption — so the grid inside the
  Cupboard window is `window[title=Cupboard] inventory`, and the window itself is `window[title=Cupboard]`.
  Writing the refiner on the inner step instead (`inventory[title=Cupboard]`) is **refused**, and the error
  hands you the spelling above: under CSS rules it would parse and then silently never match, which is a
  worse answer than an error. For the same reason `[text=]` is refused *on* a `window` — a window's words
  are its caption, and that is `[title=]`.
- **`@Class` is the class name, not a base class.** `@Window` matches a plain `Window`, not a `CharWnd`.
  Use the `window` role for "any window". The client builds most widgets as anonymous subclasses, and both
  `@Class` and `:type()` report the nearest **named** class, so this is the name you actually see.

## What carries a res

`[res=]` is the *stable* key: a resource name never changes with the client's language, where a caption
can. But only some widgets have one — **items** (`gfx/invobjs/…`), **meters** (`gfx/hud/meter/hp`), and
widgets whose code ships inside a resource (`ui/rchan`, `ui/vlg`). **Windows do not**: the client's windows
are plain Java classes with no resource behind them. So in practice, `[res=]` for items and meters,
`[title=]` for windows. [`w:res()`](widget.md#read) tells you what a widget actually carries.

A resource name is a path, so `*=` is usually the operator you want: `[res*=gfx/hud/meter]` catches every
meter, where `[res=gfx/hud/meter]` matches nothing, because no widget's resource name is *exactly* that.

## The inspector

Nobody guesses a widget's role. The bundled **`widgetstack`** addon answers it by hovering: its bottom
panel reports the hovered widget's **role** (or an honest `nil`), its **class**, its `[title=]` and its
`[res=]`, and then **every selector that actually matches it**, most specific first, with how many widgets
each one matches and where this one falls among them. The bottom line is ready to paste into `:lua` — it is
`hafen.ui():find("…")` when this widget is the **first** match and `hafen.ui():all("…")[i]` when it is
not, because `hafen.ui():find(sel)` means *first match*. Every offered selector is resolved before it is
offered, so it always
hands back the widget you were pointing at.

Click a row, or run `:selector`, to log the line: chat-log text is selectable, which is how it reaches your
editor. Freeze the stack first with `widgetstack`'s `freeze` hotkey, or moving the mouse to the window
re-hovers.

> **Hovering a window's frame does not give you the window.** The chrome — border, title bar, close button
> — is the window's *decoration*, a child widget of its own, so the hover resolves to that (`@DefaultDeco`,
> role `nil`) and not to the `Window`. Address the deco with a chain, `window[title=…] @DefaultDeco`; for
> the window itself, click one level up in the stack, or write `window[title=…]`.

## Hold the result

`hafen.ui():all("*")` walks the whole tree. Once per event, or once when the hover changes, that is nothing;
sixty times a second it is a real slice of your frame budget. Because widgets are
[interned](widget.md), holding the result costs nothing and the objects stay `==`-comparable — so select
once, keep it, and use `:exists()` when you need to know it is still there.

## Hit-testing

[`hafen.ui():mouse():over()`](mouse.md) and `hafen.ui():at(x, y)` answer *what widget is under
a point*. Both **mirror the engine's own pointer dispatch**: they walk children topmost-first, skip
invisible widgets, follow scroll offsets and honour non-rectangular hit areas, so they return exactly the
widget a real click would hit. A naive position-plus-size rectangle test is *wrong* inside scrolled lists
and for custom hit shapes. Walk [`:parent()`](widget.md#read) up from the hit for the full stack;
`:rootPos()` and `:size()` give the rectangle to outline it.

```lua
local last                                             -- the leaf we last built the stack for
hafen.event():on("Update", function(dt)
  local leaf = hafen.ui():mouse():over()                -- deepest widget under the cursor, or nil
  if leaf == last then return end                      -- hover unchanged: no walk, no rebuild
  last = leaf
  local stack, n = {}, leaf
  while n do stack[#stack + 1] = n; n = n:parent() end  -- leaf to root
  -- ... render `stack`; outline the leaf with leaf:rootPos() + leaf:size()
end)
```

The guard is the point: `Update` fires every frame, but the walk and the relayout run **only when the
hovered widget changes** — and because widgets are interned, that guard is a plain `==`, which covers
"still hovering nothing" too, since `nil == nil`. Reading the cursor and the geometry is unprotected
client-side data; sending a message from the resolved widget still goes through the protected
[`widget:send`](widget.md#send-a-message-protected-actions).

## See also

- [widget](widget.md) — what you can read off the widget a selector found
- [style](style/keys.md) — the same selector, used as a rule's key
- [replace](replace.md#watching-for-a-widget) — waiting for a widget instead of polling for it
- [native](native.md) — moving and hiding what you named
- [references](../references.md#selector-naming-a-piece-of-the-ui) — where selectors sit among the
  other reference kinds
