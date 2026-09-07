# hafen.ui: naming a widget

A **selector is a string**, and `s:ui():match` / `:matchAll` are the lookups. Reach for one whenever you
need a piece of the client's UI — to read it, to move it, to replace it, or as the key of a
[stylesheet](style/keys.md) rule.

A lookup searches **one character's tree**, so it is addressed at that character's
[session](../session.md): `s` below is `hafen.session():current()` for the one on screen, and
`hafen.session():get(user)` for any other. A session nobody is looking at keeps its whole tree, so its
windows answer the same searches.

```lua
local s = hafen.session():current()
s:ui():match("window[title=Cupboard]")             -- the one match, or nil
s:ui():matchAll("inventory")                           -- every open container, in tree order
s:ui():match("window[title=Cupboard] inventory")   -- the grid inside that window
s:ui():match("window[title=Foo] button[text=Close]")  -- one exact widget, named in one string
s:ui():match("@Equipory")                          -- by widget class
s:ui():matchAll("*[res*=gfx/hud/meter]")               -- by resource name, matched as a substring
s:ui():root()                                     -- no selector at all: that character's whole tree
```

The windows **your addon** builds are not in any of those trees — they stand in the addon layer, above
every session — so no selector reaches one. Hold the handle
[the builder](custom.md) gave you.

## One, or all of them

`:match` answers only where there **is** one answer. No match is `nil`; exactly one match is that widget;
**two or more raises an error** saying how many matched and handing you the two spellings that do have an
answer. `:matchAll` always answers — an array in tree order, empty rather than `nil`.

> A selector that names several widgets today may name one on your screen. "The Cupboard window" is one
> widget right up to the moment a second cupboard opens, and a lookup that quietly picked whichever came
> first would then act on the wrong one. That is the error you are being handed instead.

So `:match` is for a selector that identifies **one** widget — a chain down to an exact button, a unique
caption, a class only one widget has — and `:matchAll` for a question with several answers.

### Inside one widget

`w:match(sel)` and `w:matchAll(sel)` run the same search over `w`'s **own subtree**, `w` included. Same
grammar, same errors, same strictness. The scope decides which widgets are *candidates*; an ancestor step
may still name a widget above it, exactly as in CSS.

```lua
s:ui():on("window[title=Cupboard]", "Added", function(w)
  local grid = w:match("inventory")       -- THIS cupboard's grid, whatever else is open
  ...
end)
```

That is the right lookup inside an [`Added` callback](replace.md#watching-for-a-widget), and the reason the
pair exists: `s:ui():match("window[title=Cupboard] inventory")` asks a whole tree a question that has no
single answer while two cupboards are open, while the widget your callback was handed does.

Both **refuse on a widget that has left the tree**, rather than reporting an empty result — "nothing matched"
and "the thing you were searching is gone" are different answers, and a handle you kept across a window's
lifetime is exactly where they get confused. Ask [`:exists()`](widget.md#read) if you are unsure.

## The grammar

It is CSS. A selector is one or more **steps** separated by spaces, and a step is a role (or `*`), any of the
refiners, or both — in any order, each refiner at most once. Refiners alone are a step (`@Equipory`,
`[res*=gfx/hud]`); the one that needs a role is `[title=]`, and the role it needs is `window`.

| Part | Meaning |
|---|---|
| `*` | any widget, including one no role classifies |
| a **role** | what the widget *is* — see [the table below](#roles) |
| `@Class` | its class name, the same string `:type()` reports |
| `[title=…]` | a **window's own caption**; only valid in a step whose role is `window` |
| `[text=…]` | the words a widget **displays** — a button's caption, a label, a checkbox's label, an entry's contents |
| `[res=…]` | its resource name (`:res()`) |
| `[name=…]` | what the addon that **built** it calls it, as `<addon>/<name>` — [see below](#the-one-refiner-an-addon-owns) |

### The one refiner an addon owns

Every other part of a step describes what a widget *happens to be*, and somebody else decided it: a role and
a class come off its Java type, a caption and a text off what it displays, a resource off what the server
named. **`[name=…]` is what an addon calls a widget it built**, and it is the only part an author *chose*.

It exists because without it there is nothing finer to say. Every bare surface every addon builds reports the
same class, so `@AddonWidget` reaches one addon's action bar, another's meter and a third's overlay all at
once, and no refiner tells them apart.

```lua
-- in the addon, once, where the widget is built
local bar = hafen.ui():widget():parent(hud):name("bar")

-- in a theme, or in any other addon
["[name=actionbars/bar]"] = { bg = {color = {9, 13, 22, 214}} }
```

- **The engine writes the addon's id in front.** You say `:name("bar")`, the selector says
  `[name=actionbars/bar]`. Two addons naming a bar cannot collide, and a rule reads as the thing it points
  at without anyone having to look up whose bar it is.
- **It outranks everything: 16**, above `[res=]`'s 8. Not because it identifies one widget — it does not —
  but because it is the only part of a step an author *chose*. Everything else describes what a widget
  happens to be; a name is what its builder decided to call it, so it wins.
- **Give each surface its own name, and reach a group with an operator.** `[name=…]` takes the same four
  the other refiners do — `=`, `*=`, `^=`, `$=` — so an action bar that names its squares `slot1` …
  `slot12` can be dressed both ways, and a theme chooses which it means:

  ```json
  "[name^=actionbars/slot]":  { … },   // all twelve, in one rule
  "[name=actionbars/slot7]":  { … }    // ...and that one
  ```

  Naming all twelve the *same* is legal — nothing here requires a name to be unique, and what is written
  once is the name *of one widget*, never the name across widgets — but it buys nothing the prefix does
  not, and it costs the ability to name one of them.
- **Two rules that weigh the same are not ordered.** `[name^=…]` and `[name=…]` are both 16, and a theme
  loaded from JSON has no key order to break the tie with. So an exception is written as a **chain**, which
  sums: `["[name=actionbars/bar] [name=actionbars/slot7]"]` is 32 and beats the group's 16, deterministically.
  It is the same thing a CSS author writes for the same reason.
- **It is written once.** A name is identity, not state — the states a surface enters ride
  [inside the value](style/chrome.md#a-face-per-state), never in a selector, and renaming to express one
  would leave every rule naming the old one silently pointing at nothing.
- **Only on a widget your addon built.** Naming one of the client's own is refused, and the refusal names
  [`widget:rule()`](style/README.md#restyle-one-widget) — your own level on somebody else's widget, reverted
  with your addon.

A name a theme writes and nobody answers to is not an error: like every selector here, it stays valid grammar
and matches nothing.

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
as the stylesheet's [site keys](style/keys.md), deliberately, so there is one set of names rather than two —
**every site key is a role**, and one role (`item`) classifies a widget that names no render site.

**The vocabulary describes itself.** `hafen.ui():role()` is the collection of every role the client
publishes, each answering `:name()` and `:selector()` — the same string when it can match a widget, and
`nil` for a **site** role, which is valid in a stylesheet rule and matches no widget by construction. So
`for _, r in ipairs(hafen.ui():role():list()) do` enumerates the table below rather than reading it.

| Role | Matches |
|---|---|
| `window` | `Window` and every subclass, including the `Hidewnd` the client wraps the inventory in |
| `inventory` | `Inventory` and `Equipory` — *every* open container, not just yours |
| `button` | `Button`, `IButton` |
| `label` | `Label` |
| `textentry` | `TextEntry` |
| `chat` | `ChatUI` and its channels |
| `menu` | `MenuGrid`, `FlowerMenu` |
| `item` | the icon **one item** is drawn as, wherever it is drawn — a container's slot, an equipment slot, and the cursor while the item is carried |

**Some site-key names classify no widget** — `window.title`, `window.frame`, `panel`, `heading`, `tooltip`,
`inventory.slot`, `world.nick`, `world.speech`. They name a *render site*, not a widget: a window's caption
and frame are drawn by the window's decoration, an inventory's empty square is paved by the grid rather than
placed in it, a tooltip is painted rather than placed, and the world sites live over the 3D view. They stay
valid selectors, because the vocabulary is shared with the sheet, but they match nothing.

Most widgets have **no** role — layout containers, scroll ports, images. That is the rule working, not a
gap: an unrecognised widget answers `nil` rather than being guessed into the nearest role. Reach those with
`*`, `@Class`, `[res=]`, or by anchoring a chain on the window they sit in.

**`item` is why a role is not `@Class`.** A role is what a widget *is*, so it covers the subclasses `@Class`
deliberately does not: the icon under the cursor is its own class, and an addon decorating item icons wants
it without having to learn that name. `item` is also the one role with no site key behind it — an item icon
draws a picture and whatever overlays its own resource publishes, and has no text of its own to give a font
to, so calling it a site would promise a style nothing reads.

## Two rules that are easy to get wrong

- **`[title=]` is the window's *own* caption, and the space is what reaches inside it.** The client wraps
  bare widgets in titled windows — the inventory grid itself has no caption — so the grid inside the
  Cupboard window is `window[title=Cupboard] inventory`, and the window itself is `window[title=Cupboard]`.
  Writing the refiner on the inner step instead (`inventory[title=Cupboard]`) is **refused**, and the error
  hands you the spelling above: under CSS rules it would parse and then silently never match, which is a
  worse answer than an error. For the same reason `[text=]` is refused *on* a `window` — a window's words
  are its caption, and that is `[title=]` — and no `[text=]` matches a window even when the step says `*`,
  so the two keys never name the same thing.
- **`@Class` is the class name, not a base class.** `@Window` matches a plain `Window`, not a `CharWnd`.
  Use the `window` role for "any window". The client builds most widgets as anonymous subclasses, and both
  `@Class` and `:type()` report the nearest **named** class, so this is the name you actually see.

## What carries a res

`[res=]` is the *stable* key: a resource name never changes with the client's language, where a caption
can. But only some widgets have one — **items** (`gfx/invobjs/…`), **meters** (`gfx/hud/meter/hp`), a
building site's **material boxes** (`@ISBox`, the material each counts), and
widgets whose code ships inside a resource (`ui/rchan`, `ui/vlg`). **Most windows carry none**: the client's
own windows are plain Java classes with nothing behind them. So in practice, `[res=]` for items and meters,
`[title=]` for windows. [`w:res()`](widget.md#read) tells you what a widget actually carries.

A caption selector matches the client's **own English**, whatever the client is displaying: a
[catalogue](../locale.md) lands at the render and nowhere above it, so `[title=Inventory]` goes on matching
a window whose caption is drawn in another language.

A resource name is a path, so `*=` is usually the operator you want: `[res*=gfx/hud/meter]` catches every
meter, where `[res=gfx/hud/meter]` matches nothing, because no widget's resource name is *exactly* that.

### The picture is a different read

`:res()` names the resource a widget's own **code** came from. Most of the client's chrome is an ordinary
Java class showing an ordinary piece of the game's art, and that art has a name of its own:
[`w:picture()`](widget.md#read) answers it, where `w:res()` answers `nil`.

```lua
local w = hafen.ui():window():title("Sample")
local close = w:match("@IButton")  -- the close box: the client put it in the chrome, not you
close:picture()                    -- "gfx/hud/wnd/lg/cbtnu"
close:res()                        -- nil
```

The two never merge, and `:picture()` has **no selector key**: `[res=]` matches what `:res()` reads and
nothing else, so every selector already written keeps meaning what it meant. `:picture()` answers on a
widget that *holds* a picture — a [picture control](controls/display.md#picture), a picture button, a
picture checkbox — and `nil` on one that composes or paints its art instead of holding one, an inventory
square or a meter's bar among them. It also answers `nil` for a picture that came out of **your own**
[asset](../asset.md) file rather than the client's art: the name it hands back is a client resource name or
nothing. One picture shared by two widgets names one resource on both, which is the true answer — they are
showing the same art. Any argument raises.

## The inspector

Nobody guesses a widget's role. The bundled **`widgetstack`** addon answers it by hovering: its bottom
panel reports the hovered widget's **role** (or an honest `nil`), its **class**, its own `[title=]` or
`[text=]` — whichever key its role takes — its `[res=]`, and its **anchor**, the nearest enclosing
**captioned** window, written as the first step of a chain. Under those it lists **every selector it can
build from those parts and then resolve back to this widget**, most specific first, with how many widgets
each one matches and where this one falls among them.

Chains come first, because they are what name **one** widget. Hovering the grid in your Inventory offers
`window[title=Inventory] inventory@Inventory`, matching exactly that grid, where the flat `inventory` matches
every container on screen. The panel offers [the operators](#the-grammar) too, wherever they say something
`=` cannot: `[res*=…]` on the last segment of a resource path, and the `^=` form of the widget's own key on
the part of its value before the first digit — `[title^=…]` on a window, `[text^=…]` on anything else, the
form that keeps matching when a counter ticks over.

The bottom line is ready to paste into `:lua` — it is
`hafen.session():current():ui():match("…")` when the selector matches this widget and **nothing else**, and
`…:matchAll("…")[i]` when it matches more. Every offered selector is
resolved before it is offered, so it always hands back the widget you were pointing at.

Some widgets cannot be named alone, and the panel says so rather than inventing a key: nine identical
attribute rows in one window differ in nothing the grammar can see, so an index is the best line there is.
The header counts the tree walks the last hover cost and how many of them were chains, since a chain
candidate costs one walk more than the flat step it extends.

Click a row, or run `:selector`, to log the line: chat-log text is selectable, which is how it reaches your
editor. Freeze the stack first with `widgetstack`'s `freeze` hotkey, or moving the mouse to the window
re-hovers.

> **Hovering a window's frame does not give you the window.** The chrome — border, title bar, close button
> — is the window's *decoration*, a child widget of its own, so the hover resolves to that (`@DefaultDeco`,
> role `nil`) and not to the `Window`. Address the deco with a chain, `window[title=…] @DefaultDeco`; for
> the window itself, click one level up in the stack, or write `window[title=…]`.

## What the inspector says a widget answers

Under the selector list sits the other half: every [read](widget.md#read) that has something to say about
the widget you are pointing at, in one fixed order, in the hover panel and in an inspector window alike.
A read answering `nil`, an empty collection or a plain `false` prints **no line**, so what you are looking
at is what the widget *has* — a window's close button is one `picture:` line, a plain label is none at all.

Some of those reads — `:range()`, `:rows()`, `:rowHeight()`, `:cellSize()`, `:columns()`, `:source()` and
`:image()` — answer a [control](controls/README.md)'s own configuration, which belongs to the addon that
**built** it. Over one of the client's own controls they say nothing, which is the honest answer rather
than a guess at one. `:picture()`, `:tooltip()`, `:value()`, `:items()`, `:focused()` and `:style()` answer
on any widget in the tree, whoever put it there, so those are the lines you read off the client's own UI.

## Hold the result

Every lookup walks its whole scope — one character's tree for `s:ui():match` and `:matchAll`, one widget's
subtree for [the pair on a widget](#inside-one-widget) — and `:match` walks all of it too, since it cannot
know a match is the only one until it has looked everywhere. Once per event, or once when the hover changes,
that is nothing; sixty times a second it is a real slice of your frame budget. Because widgets are
[interned](widget.md), holding the result costs nothing and the objects stay `==`-comparable — so select
once, keep it, and use `:exists()` when you need to know it is still there.

## Hit-testing

[`hafen.ui():mouse():over()`](mouse.md) and `hafen.ui():hit(x, y)` answer *what widget is under
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
[`widget:send`](widget.md#send-a-message-protected).

## See also

- [widget](widget.md) — what you can read off the widget a selector found
- [style](style/keys.md) — the same selector, used as a rule's key
- [replace](replace.md#watching-for-a-widget) — waiting for a widget instead of polling for it
- [native](native.md) — moving, hiding and handing over what you named
- [references](../references.md#selector-naming-a-piece-of-the-ui) — where selectors sit among the
  other reference kinds
