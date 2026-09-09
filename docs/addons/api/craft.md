# session:craft: crafting

Read the recipe one character has open, and press its Craft button. You reach it through the
[session](session.md) whose character you mean. This namespace is a view of one window, not of everything a
character can make: there is nothing to read while that character has no recipe open.

```lua
local s = hafen.session():current()                      -- the character on screen
if s:craft():exists() then
  hafen.log():write("recipe: " .. s:craft():recipe())
  for _, spec in ipairs(s:craft():inputs():list()) do
    hafen.log():write("  needs " .. (spec:name() or spec:res()) .. " x" .. spec:count())
  end
end
```

## The section is the recipe

`s:craft()` **is** the open recipe, not a wrapper around it — the same shape
[`s:buff()`](buff.md) is the bar, [`s:kin()`](kin.md) is the roster and
[`s:flowermenu()`](flowermenu.md) is the open menu. Every read asks the window again rather than
holding it, which is what you want here: the server builds a fresh window for each recipe, so opening a
different one does not *change* this recipe, it ends it.

With nothing open, `:exists()` is `false`, `:recipe()` is `nil` and the four list reads are empty. That
is also what a session the client no longer holds answers, so one guard covers both.

## A window is open on the character that opened it

A recipe window belongs to the character it was opened on, and it stays open while you look at someone else.
So this reads and crafts on a character you are not watching, which is what a crafting addon across your
logins is built out of:

```lua
local alt = hafen.session():get("alt")                   -- the recipe that character has open
if alt:craft():exists() then
  hafen.log():write("the alt is making " .. alt:craft():recipe())
end
```

## Read

| Method | Returns | Description |
|---|---|---|
| `s:craft():recipe()` | string \| nil | the recipe's name, as the server titled the window |
| `s:craft():inputs()` | a collection of [specs](#a-spec) | the ingredient slots, in window order |
| `s:craft():outputs()` | a collection of [specs](#a-spec) | the product slots |
| `s:craft():qualityInputs()` | a collection of [specs](#a-spec) | the ingredients whose quality carries into the product |
| `s:craft():tools()` | a collection of [specs](#a-spec) | the tools you must have with you |
| `s:craft():exists()` | boolean | whether a recipe is open at all — always answers, drawn or not |
| `s:craft():info()` | [`Craft`](types/ui.md#craft-and-craftspec) \| nil | a plain-table **snapshot** |

The four slot reads are **collections** of [spec objects](#a-spec): `:list(filter)` is the array,
`:find(needle)` the first that matches and `:count(filter)` how many, and each is empty rather than `nil`
when nothing is open. Each is a
[view](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) minted per call, so
`:inputs() ~= :inputs()`; there is nothing to compare here anyway, since a spec holds what it was minted
from rather than re-resolving. A slot has no key, so there is no `:get` — the server rebuilds a recipe's
slots wholesale, so `:list()[n]` takes a position and `:find(needle)` searches the name and the resource.

`s:craft():info()` is the other shape: a plain-table [`Craft`](types/ui.md#craft-and-craftspec) with the
same four lists copied out as tables, for logging and serialising.

## A spec

One slot of the open recipe — an ingredient, a product, a quality input or a tool. One type covers the
four: an ingredient and a product carry a count and an optional flag, a quality input and a tool carry
neither and answer `nil` for both.

A spec **holds what it was minted from** rather than re-resolving, which is the one object here that
does. A recipe's slots are data the server rebuilds whole on every update, so there is no key to address
one by and nothing to track: read the recipe again rather than holding a slot across one.

| Method | Returns | Description |
|---|---|---|
| `spec:res()` | string \| nil | the **displayed** resource — the constraint category where the recipe accepts one, such as any board, else the concrete item; `nil` while it is loading |
| `spec:name()` | string \| nil | its display name, from the resource's own tooltip; `nil` while that is loading |
| `spec:count()` | number \| nil | how many are required or produced; `nil` on a quality input and a tool, which carry no count |
| `spec:optional()` | boolean \| nil | whether it is an optional ingredient or a chance byproduct; `nil` on a quality input and a tool |
| `spec:info()` | [`CraftSpec`](types/ui.md#craft-and-craftspec) | a plain-table **snapshot** |

Every read is unprotected, and none of them throws. The wire's "unspecified" count is `-1` and
`spec:count()` answers `1` for it, which is what it means; the snapshot keeps the server's own number.

There is no `CraftChanged` event, because a recipe changes only when the player opens one. To notice
that, watch for the window with [`s:ui():on`](ui/replace.md): `s:ui():on("window", "Added", fn)`.

## Write (protected)

A write goes out **once per frame at most**; a second in the same frame raises. The client sends only
shapes a player could compose, and what the server does with more than that is the server's.

| Method | Key | Description |
|---|---|---|
| `s:craft():make(all)` | `craft.make` | craft the open recipe once; with `all = true`, press Craft All |

It presses the recipe's own button, so it **consumes the ingredients** exactly as a click would — on the
character whose window it is, watched or not. Called from an addon that did not declare the `craft.make` key
it raises an error naming that key, and it also refuses when no recipe is open. It returns the section, so
writes chain. See [the permission model](conventions.md#the-permission-model).

One key covers every character: `craft.make` lets you press the Craft button on any of your logins — see
[a key names the action, not the target](../guides/permissions.md#a-key-names-the-action-not-the-target).

## See also

- [session](session.md) — the address every read here goes through
- [`Craft` and `CraftSpec`](types/ui.md#craft-and-craftspec) — the snapshot shapes
- [items](ui/items.md#write-protected) — moving the ingredients into the window
- [`session:menugrid`](menugrid.md) — how a recipe window gets opened in the first place
