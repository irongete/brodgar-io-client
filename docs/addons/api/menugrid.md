# session:menugrid: the action menu

Read one character's **action menu**, invoke its entries, and put entries of your own in it. It is the
client's catalogue of everything that character can *do*: every unlocked action, or "pagina", in a category
tree. You reach it through the [session](session.md) whose character you mean, and `s:menugrid()` **is**
that character's catalogue.

```lua
local s   = hafen.session():current()         -- the character on screen
local dig = s:menugrid():get("Dig")           -- or "paginae/act/dig" (a "/" means a resource name)
hafen.log():write(dig:parent():name() .. " > " .. dig:name())    -- "Dig" lives under a category
dig:use()                                                -- fire it: exactly a click on that button
```

## Whose catalogue it is

Two characters know different things, and each carries its own menu, so the read says which character it is
about: `hafen.session():get("alt"):menugrid():get("Dig")` asks whether *that* character has learnt to dig,
while you watch someone else. Your own entries follow the same rule — `:add` puts one in the menu you
addressed, and every write verb reaches the entry in the session it was read through.

`s:menugrid()` is the same object every call, minted once for that session. A session the client no longer
holds has an empty catalogue rather than raising.

| Call | Returns |
|---|---|
| `s:menugrid():get(key)` | the one `Pagina` that `key` names, else `nil` |
| `s:menugrid():list(filter)` | the whole **catalogue** — an array of `Pagina` objects in the grid's own sort order |
| `s:menugrid():count(filter)` | how many match |
| `s:menugrid():find(filter)` | the first that matches, or `nil` |
| `s:menugrid():roots()` | the entries with no parent: what the grid shows on its root screen |

Pagina objects are **interned per addon** on the character *and* the resource name, so
`s:menugrid():get("Dig") == s:menugrid():get("paginae/act/dig")` and `seen[pag] = true` works as a table key
— while the same name reached through two sessions gives you two objects, because it names two entries in
two menus. A `Pagina` carries the character and the resource name and re-resolves the live menu on every
call, so a stashed one goes `:exists() == false` the moment the action is revoked — see
[snapshots vs handles](conventions.md#snapshots-vs-handles).

## The key is a string, and it splits by shape

| Key | Meaning |
|---|---|
| contains a `/`, as in `"paginae/act/dig"` | a **resource name**: the identity |
| anything else, as in `"Dig"` | a **display name**: a search convenience |

The two forms are not equals, and the split is by shape rather than by fallback: a resource name never
scans display names, and a display name never hijacks a resource lookup.

- `:res()` is the **identity**. It is the intern key, it is known as soon as the resource is named, and
  it is the same string [`slot:res(name)`](actionbar.md#write-protected) takes.
- A **display name** needs the resource fully loaded, and it is **not unique** — several actions can
  share one, and the first match in catalogue order wins. Use it to explore, and `:res()` to address.

A miss is plain `nil` for both forms: "not in the menu" means "that character does not have that action",
and a stashed handle still answers `:exists()` after the entry goes away.

**There are no positions to address.** The catalogue grows every time you discover something, so an array
position is an artefact of one call's ordering rather than an index — `:get(1)` raises an error pointing at
the two name forms. The array `:list()` hands back is 1-based only so that `ipairs` works.

A string [filter](conventions.md#the-filter-argument) matches an entry's **display name** as a substring,
so `:list("Dig")` is every entry whose name contains it and `:find("Dig")` the first; an entry whose
resource has not resolved yet has no display name and matches nothing.

> **The catalogue fills in.** Names come from the pagina's resource and resources resolve asynchronously, so
> a scan run right at `SessionEnteredWorld` is often short: entries that have not resolved are simply absent,
> and they appear a fraction of a second later. Scan on a timer if you need the complete set, and expect it
> to keep growing as that character plays.

There is no `MenuChanged` event — poll `s:menugrid():list()` on `Update` or a [timer](timer.md) to track it.

## Read

The first four are called on the collection, the rest on a `Pagina`.

| Method | Returns | Description |
|---|---|---|
| `s:menugrid():list(filter)` | `Pagina[]` | the whole catalogue, in the grid's own sort order |
| `s:menugrid():count(filter)` | number | how many match |
| `s:menugrid():find(filter)` | `Pagina` \| nil | the first entry that matches |
| `s:menugrid():roots()` | `Pagina[]` | the entries with no parent: what the grid shows on its root screen |
| `pag:res()` | string | the resource name, the identity — always answers, even for a revoked entry |
| `pag:addon()` | string \| nil | the id of the addon that added this entry; `nil` for the game's own |
| `pag:name()` | string \| nil | the display name the grid shows |
| `pag:icon()` | image \| nil | the [asset](asset.md) your addon gave this entry; `nil` on the game's own, whose art is not a file of yours |
| `pag:tooltip()` | string \| nil | the description under the name, when the resource carries one |
| `pag:hotkey()` | string \| nil | the letter the grid paints over the button while Alt is held |
| `pag:categories()` | string[] \| nil | the categories above this entry, as the action tokens the message carries; **empty** for a category and for an id-only entry |
| `pag:parent()` | `Pagina` \| nil | the category this entry sits under; `nil` for a root entry |
| `pag:children()` | `Pagina[]` \| nil | the entries under this one, exactly what the grid shows after clicking it; empty for a leaf |
| `pag:unseen()` | boolean | whether the entry is still flagged as a new discovery, the grid's highlight |
| `pag:exists()` | boolean | whether the entry is still in the menu |
| `pag:info()` | [`Pagina`](types.md#pagina) \| nil | a plain-table **snapshot** of the same fields |

Every reader except `:res()` and `:exists()` answers `nil` once the entry is gone, and also while its
resource is still loading. No reader throws, and none is protected.

### The tree

The catalogue is **flat and complete**: it holds every action *plus* the categories they hang under, so
`:parent()` always lands on something you can read, `:roots()` is never empty once the menu is up, and "is
this a category" is `#pag:children() > 0`.

```lua
for _, cat in ipairs(hafen.session():current():menugrid():roots()) do
  hafen.log():write(cat:name() or cat:res())
  for _, child in ipairs(cat:children()) do
    hafen.log():write("   " .. (child:name() or child:res()))
  end
end
```

## Write (unprotected)

**An entry of your own stands in the same grid, in the same order.** `s:menugrid():add(id)` mints one
your addon owns, and it is a `Pagina` like any other: every reader above answers for it, `:get` addresses
it, `:list()` carries it and `:roots()` shows it on the root screen.

```lua
local s   = hafen.session():current()
local dig = s:menugrid():add("dig")                      -- its identity is addon/myaddon/dig
dig:name("Auto-dig"):icon(hafen.asset():get("dig.png"))  -- writes chain, like everywhere else
```

| Method | Description |
|---|---|
| `s:menugrid():add(id)` | mint an entry your addon owns and hand it back |
| `s:menugrid():remove(idOrPagina)` | take one of your own back out; returns the collection, so removals chain |
| `pag:name(text)` | the display name the grid paints and its tooltip shows |
| `pag:tooltip(text)` | the description the tooltip paints under that name |
| `pag:icon(image)` | the picture the button draws: an image [asset](asset.md) handle |
| `pag:parent(pagOrNil)` | the category it hangs under; `nil` is the root screen |

Nothing reaches the server — a custom entry is drawn by this client, and pressing it runs
[your own Lua](#a-click-runs-your-lua) — so it needs **no permission**, like a
[HUD overlay](ui/custom.md#overlays).

Your entries are **bridge-owned**. Reloading or disabling your addon, and logging out, take every one of
them back out — from every character — so each menu is the game's own catalogue again, with nothing left.

### `s:menugrid():add(id)`

The new `Pagina`, ready for its setters, in the menu of the character you addressed. `id` is
**addon-relative**, spelled like an asset path — `"dig"`, `"tools/dig"` — and the identity it gets is
`addon/<your addon's id>/<id>`. That string is what `:res()` answers and what `:get()` resolves, by the same
shape rule as any other resource name, so two addons cannot collide and neither can collide with the game's
own. It is unique **within a character**: putting the same button on two of your logins is the ordinary
thing, and adding it twice to one menu is the clash refused below.

The menu has to exist: add your entries from [`SessionEnteredWorld`](event/bus.md#sessions) or later, not
from `Load` — and that event hands you the very session to add them to, once per character.

| What you did | What you get |
|---|---|
| `:add("dig")` twice on one character | your addon *already has an entry with that id* in that menu |
| `:add("")` | the id must be a **non-empty** string |
| `:add("/dig")` | the id *is absolute* — an id is relative to your own addon |
| `:add("../dig")` | the id *climbs out of your addon* with `..` |
| `:add(7)`, `:add({})` | expected a **string** id |
| `:add(nil)` | the id must not be `nil` — see [nil](conventions.md#nil-is-an-error-unless-it-means-something) |
| `:add("dig")` before that character's HUD is up | the action menu *is not up yet* |

### `s:menugrid():remove(idOrPagina)`

Takes the `Pagina` object, or its id as a string: `"dig"` as you spelled it to `:add`, or the whole
`addon/myaddon/dig` identity you read back from `:res()`. The entry leaves the grid at once and every handle
to it goes `:exists() == false`.

Removing one that is already gone is **inert** — a removal is a moment, not a mistake — and so is removing
one that stands only in another character's menu. Removing one of the client's own entries, or another
addon's, raises naming whose it is: what the server granted is the server's to revoke.

### What an entry draws

`pag:name(text)` sets the label the grid paints under the pointer and sorts by; an entry you never name
shows the id you gave it. `pag:tooltip(text)` is the description under that label, painted once the pointer
has rested on the button — an entry with none has a tooltip that is its name alone. `pag:icon(image)` takes
the handle [`hafen.asset():get("dig.png")`](asset.md) hands you and **never a path** — the loader is one
door, and a string here says so.

An entry with no icon draws an empty cell. An image bigger than a cell is scaled down to fit, keeping its
aspect ratio; a smaller one is drawn at its own size. Either way it is centred, and its pixels are
[design pixels](ui/pixels.md), so a 32×32 PNG fills a cell exactly like the game's own art.

Every write here refuses on an entry your addon did not add — one of the client's own, or another
addon's — naming which it is, so a stashed handle can never write over someone else's button.

### A category is an entry that has children

`pag:parent(cat)` hangs one of your entries under another entry; `pag:parent(nil)` puts it back on the root
screen. There is no separate call that declares a category, because a category **is** an entry with something
under it: clicking it opens its children, and Back returns to where you came from.

```lua
local mg    = hafen.session():current():menugrid()
local tools = mg:add("tools"):name("Tools"):icon(hafen.asset():get("tools.png"))
mg:add("tools/dig"):name("Auto-dig"):parent(tools)       -- under your own category
mg:add("harvest"):parent(mg:get("paginae/act/craft"))    -- under one of the client's own
```

**Your entries and the client's own are one tree.** The parent is any `Pagina` that is in the menu, so an
entry of yours can sit under one of the game's categories, beside the actions the server granted, and a
category of yours can hold entries of your own. Reading it back is the same verb with no argument, and
`cat:children()` lists what hangs under it.

Taking a category out with `:remove` puts its children back on the root screen: it takes nothing with it.

| What you did | What you get |
|---|---|
| `pag:parent(pag)` | that entry *cannot hang under itself* |
| a cycle in two steps or more | the entry it would hang under *already hangs under this one* |
| `pag:parent(7)` | the parent is a **`Pagina` object**, or `nil` |
| `pag:parent("Tools")` | that *is a key*: pass the object `:get(key)` hands you |
| a parent that is no longer in the menu | that entry *is not in the menu* — check `:exists()` |
| a parent read through another session | that entry *is in that character's menu* — one tree per character |

A cycle is refused rather than written, so the tree after the error is the tree before it. `nil` here
**means the root screen** — one of the few places it
[carries a meaning](conventions.md#nil-is-an-error-unless-it-means-something), and everywhere else on this
page an explicit `nil` raises.

## A click runs your Lua

`pag:on("use", fn)` runs `fn(pag)` every time one of your entries is pressed: a left-click on its button in
the grid, and [`pag:use()`](#use-protected) from your own code. You get a subscription back, the same one
[`hafen.event()`](event/README.md) hands you.

```lua
local dig = hafen.session():current():menugrid():add("dig"):name("Auto-dig")
local sub = dig:on("use", function(pag)
  hafen.log():write("pressed " .. pag:name())
end)
-- later:
sub:off()
```

| Method | Returns | Description |
|---|---|---|
| `pag:on("use", fn)` | a subscription | run `fn(pag)` when this entry is pressed |
| `sub:off()` | the subscription | unsubscribe; idempotent, and also done for you on reload or disable |

`use` is the **only** key an entry has, so any other name throws at the line that wrote it rather than
reading as a handler that never fires. **Two handlers on one entry both fire**, in the order they registered,
and `off()` on one leaves the other running. A handler that errors is isolated: it is logged, and it breaks
neither your other handlers nor the client.

The handler is handed the entry that fired, so one function can serve several buttons. Nothing is sent to
the server, whichever way the entry was pressed, and nothing about the grid changes: the client runs your
function, and that is all.

An entry nobody subscribed to does nothing when it is pressed. That is not an error: a button you have not
wired yet is a legal thing to leave in the menu.

The same handlers run when the entry is pressed **on the action bar**: drag it out of the grid onto a slot, or
put it there with [`slot:hold(pag)`](actionbar.md#hold-a-slot-unprotected), and its key fires them too.

`pag:on` refuses on an entry your addon did not add, like every write above: the handler is your code, and
it hangs on your own button. Your subscriptions end when the addon reloads or is disabled and when you
`:remove` the entry, so there is nothing to unsubscribe by hand and an entry you add again starts with none.

## Use (protected)

| Method | Key | Description |
|---|---|---|
| `pag:use()` | `menugrid.use` | perform the action, exactly as a left-click on that menu button does |

It returns the `Pagina`, so it chains. `use` takes **no arguments**, deliberately: the client builds
the message from the modifier keys physically held at that instant, so a `mods` parameter could only
lie about them.

The reads above are not protected — enumerating the catalogue tells the server nothing. `use` commits a
real action, so it is behind the [permission model](conventions.md#the-permission-model) like every
other verb that does: an addon that did not declare `menugrid.use` gets an error naming that key. The key is
named after this section rather than after the action it fires, because the entries are yours and the door
is one. That key covers every character, since the entry you fire is on whichever of your logins you read
it through — see
[a key names the action, not the target](../guides/permissions.md#a-key-names-the-action-not-the-target).

On an entry of your own it runs your [handlers](#a-click-runs-your-lua) and sends nothing, through the very
button code a left-click goes through — so the two can never answer differently. It still needs the key:
any addon can address any entry by name, so the answer must not depend on who owns the one you named.

`use` raises an error on a category, on an entry that is no longer in the menu, and on one whose
resource has not finished loading; check `:exists()` first if you are holding a stashed handle. A
ground-targeted action enters targeting mode, just as the click would, and you supply the target with
[`session:world():click`](world.md#write-protected) or [`place`](world.md#write-protected).

Because it goes through the client's own button code, `use` sends the action **by path when it has one and
by id when it does not** — so it reaches the id-only entries, such as server-pushed abilities, that no path
can express. This is the one door onto a menu action: the entry addresses itself, by resource name or
display name, and there is no path-shaped way in beside it.

## See also

- [session](session.md) — the address every read here goes through
- [`session:actionbar`](actionbar.md) — putting a name on the hotbar, and holding a slot for an entry
- [`hafen.asset`](asset.md) — loading the PNG a custom entry draws
- [`Pagina`](types.md#pagina) — the snapshot shape `:info()` returns
- [`session:craft`](craft.md) — the window a recipe action opens
