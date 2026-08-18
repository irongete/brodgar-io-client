# session:craft: crafting

Read the recipe one character has open, and press its Craft button. You reach it through the
[session](session.md) whose character you mean. This namespace is a view of one window, not of everything a
character can make: there is nothing to read while that character has no recipe open.

```lua
local s = hafen.session():current()                      -- the character on screen
local c = s:craft():current()
if c then
  hafen.log():write("recipe: " .. c:name())
  for _, i in ipairs(c:inputs()) do
    hafen.log():write("  needs " .. (i.name or i.res) .. " x" .. i.num)
  end
end
```

## A window is open on the character that opened it

A recipe window belongs to the character it was opened on, and it stays open while you look at someone else.
So this reads and crafts on a character you are not watching, which is what a crafting addon across your
logins is built out of:

```lua
local c = hafen.session():get("alt"):craft():current()   -- the recipe that character has open
if c then
  hafen.log():write("the alt is making " .. c:name())
end
```

`s:craft()` is the same object every call, minted once for that session. Where a character has no recipe
open, `:current()` is `nil` — the same `nil` the drawn character gives you with nothing open, and a session
the client no longer holds gives you too.

## Read

| Call | Returns | Description |
|---|---|---|
| `s:craft():current()` | `Craft` \| nil | the recipe that character has open; `nil` when no recipe window is up |

> `:current()` is **`nil` while no recipe is open**, which is why the `if c then` above is the whole
> guard you need. Read it again rather than holding one across recipes: opening a different recipe is a
> different window, so the Craft you were holding reports `:exists() == false` from that moment.

## A recipe

| Method | Returns | Description |
|---|---|---|
| `c:name()` | string \| nil | the recipe's name |
| `c:inputs()` | [`CraftSpec`](types.md#craft-and-craftspec)`[]` | the ingredient slots, in window order |
| `c:outputs()` | [`CraftSpec`](types.md#craft-and-craftspec)`[]` | the product slots |
| `c:qualityInputs()` | [`ResRef`](types.md#craft-and-craftspec)`[]` | the ingredients whose quality carries into the product |
| `c:tools()` | [`ResRef`](types.md#craft-and-craftspec)`[]` | the tools you must have with you |
| `c:exists()` | boolean | whether that window is still open — always answers, drawn or not |
| `c:info()` | [`Craft`](types.md#craft-and-craftspec) \| nil | a plain-table **snapshot** |

The four list reads are plain arrays of plain tables, and they are empty rather than `nil` once the
recipe is gone. A slot is `{res, name, num, opt}`: `res` is the **displayed** resource — the constraint
category when the recipe accepts one, such as any board, else the concrete item — `num` is the required
or produced count, with `-1` meaning unspecified, and `opt` marks an optional ingredient or a chance
byproduct.

There is no `CraftChanged` event, because a recipe changes only when the player opens one. To notice
that, watch for the window with [`hafen.ui():on`](ui/replace.md): `hafen.ui():on("window", "appear", fn)`.

## Write (protected)

| Method | Key | Description |
|---|---|---|
| `c:make(all)` | `craft.make` | craft the open recipe once; with `all = true`, press Craft All |

It presses the recipe's own button, so it **consumes the ingredients** exactly as a click would — on the
character whose window it is, watched or not. Called from an addon that did not declare the `craft.make` key
it raises an error naming that key, and it also refuses on a window that is no longer open. It returns the
Craft, so writes chain. See [the permission model](conventions.md#the-permission-model).

One key covers every character: `craft.make` lets you press the Craft button on any of your logins — see
[a key names the action, not the target](../guides/permissions.md#a-key-names-the-action-not-the-target).

## See also

- [session](session.md) — the address every read here goes through
- [`Craft` and `CraftSpec`](types.md#craft-and-craftspec) — the snapshot shapes
- [items](ui/items.md#write-protected) — moving the ingredients into the window
- [`session:menugrid`](menugrid.md) — how a recipe window gets opened in the first place
