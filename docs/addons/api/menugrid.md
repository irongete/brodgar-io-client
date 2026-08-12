# hafen.menugrid: the action menu

Read the **action menu**, invoke its entries, and put entries of your own in it. It is the client's
catalogue of everything your character can *do*: every unlocked action, or "pagina", arranged in a category
tree. `hafen.menugrid()` **is** the catalogue.

```lua
local dig = hafen.menugrid():get("Dig")       -- or "paginae/act/dig" (a "/" means a resource name)
hafen.log():write(dig:parent():name() .. " > " .. dig:name())    -- "Dig" lives under a category
dig:use()                                                -- fire it: exactly a click on that button
```

| Call | Returns |
|---|---|
| `hafen.menugrid():get(key)` | the one `Pagina` that `key` names, else `nil` |
| `hafen.menugrid():list(filter)` | the whole **catalogue** — an array of `Pagina` objects in the grid's own sort order |
| `hafen.menugrid():count(filter)` | how many match |
| `hafen.menugrid():find(filter)` | the first that matches, or `nil` |
| `hafen.menugrid():roots()` | the entries with no parent: what the grid shows on its root screen |

Pagina objects are **interned per addon**, so
`hafen.menugrid():get("Dig") == hafen.menugrid():get("paginae/act/dig")` and `seen[pag] = true` works as
a table key. A `Pagina` wraps only the resource name and re-resolves the
live menu on every call, so a stashed one goes `:exists() == false` the moment the action is revoked —
see [snapshots vs handles](conventions.md#snapshots-vs-handles).

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

A miss is plain `nil` for both forms: "not in the menu" means "you do not have that action". A stashed
handle still answers `:exists()` after the entry goes away.

**There are no positions to address.** The catalogue grows every time you discover something, so an
array position is an artefact of one call's ordering rather than an index — `:get(1)` raises an error
pointing at the two name forms. The array `:list()` hands back is 1-based only so that `ipairs` works.

A string [filter](conventions.md#the-filter-argument) matches an entry's **display name** as a
substring, so `:list("Dig")` is every entry whose name contains it and `:find("Dig")` the first.
An entry whose resource has not resolved yet has no display name and matches nothing.

> **The catalogue fills in.** Names come from the pagina's resource and resources resolve
> asynchronously, so a scan run right at `EnterWorld` is often short: entries that have not resolved
> are simply absent, and they appear a fraction of a second later. Nothing ever comes back half-read —
> every resource-backed reader answers `nil` rather than a partial value. Scan on a timer if you need
> the complete set, and expect it to keep growing as you play.

There is no `MenuChanged` event — poll `hafen.menugrid():list()` on `Update` or a [timer](timer.md)
if you must track it.

## Read

The first four are called on the collection, the rest on a `Pagina`.

| Method | Returns | Description |
|---|---|---|
| `hafen.menugrid():list(filter)` | `Pagina[]` | the whole catalogue, in the grid's own sort order |
| `hafen.menugrid():count(filter)` | number | how many match |
| `hafen.menugrid():find(filter)` | `Pagina` \| nil | the first entry that matches |
| `hafen.menugrid():roots()` | `Pagina[]` | the entries with no parent: what the grid shows on its root screen |
| `pag:res()` | string | the resource name, the identity — always answers, even for a revoked entry |
| `pag:name()` | string \| nil | the display name the grid shows |
| `pag:icon()` | image \| nil | the [asset](asset.md) your addon gave this entry; `nil` on the game's own, whose art is not a file of yours |
| `pag:tooltip()` | string \| nil | the description under the name, when the resource carries one |
| `pag:hotkey()` | string \| nil | the letter the grid paints over the button while Alt is held |
| `pag:path()` | string[] \| nil | the action tokens the message carries; **empty** for a category and for an id-only entry |
| `pag:parent()` | `Pagina` \| nil | the category this entry sits under; `nil` for a root entry |
| `pag:children()` | `Pagina[]` \| nil | the entries under this one, exactly what the grid shows after clicking it; empty for a leaf |
| `pag:isNew()` | boolean | whether the entry is still flagged as a new discovery, the grid's highlight |
| `pag:exists()` | boolean | whether the entry is still in the menu |
| `pag:info()` | [`Pagina`](types.md#pagina) \| nil | a plain-table **snapshot** of the same fields |

Every reader except `:res()` and `:exists()` answers `nil` once the entry is gone, and also while its
resource is still loading. No reader throws, and none is protected.

### The tree

The catalogue is **flat and complete**: it holds every action *plus* the categories they hang under, so
`:parent()` always lands on something you can read, and `:roots()` is never empty once the menu is up.
"Is this a category" is `#pag:children() > 0`.

```lua
for _, cat in ipairs(hafen.menugrid():roots()) do
  hafen.log():write(cat:name() or cat:res())
  for _, child in ipairs(cat:children()) do
    hafen.log():write("   " .. (child:name() or child:res()))
  end
end
```

## Write (unprotected)

**An entry of your own stands in the same grid, in the same order.** `hafen.menugrid():add(id)` mints one
your addon owns, and it is a `Pagina` like any other: every reader above answers for it, `:get` addresses
it, `:list()` carries it and `:roots()` shows it on the root screen.

```lua
local dig = hafen.menugrid():add("dig")                  -- its identity is addon/myaddon/dig
dig:name("Auto-dig"):icon(hafen.asset():get("dig.png"))  -- writes chain, like everywhere else
```

| Method | Description |
|---|---|
| `hafen.menugrid():add(id)` | mint an entry your addon owns and hand it back |
| `hafen.menugrid():remove(idOrPagina)` | take one of your own back out; returns the collection, so removals chain |
| `pag:name(text)` | the display name the grid paints and its tooltip shows |
| `pag:icon(image)` | the picture the button draws: an image [asset](asset.md) handle |

Nothing here reaches the server — a custom entry is drawn by this client — so it needs **no permission at
all**, like a [HUD overlay](ui/custom.md#overlays). A custom entry also runs no action of its own: clicking
it, and `pag:use()` on it, send nothing.

Your entries are **bridge-owned**. Reloading or disabling your addon, and logging out, take every one of
them back out, so the menu is the game's own catalogue again with nothing of yours left in it.

### `hafen.menugrid():add(id)`

The new `Pagina`, ready for its setters. `id` is **addon-relative**, spelled like an asset path — `"dig"`,
`"tools/dig"` — and the identity it gets is `addon/<your addon's id>/<id>`. That string is what `:res()`
answers and what `:get()` resolves, by the same shape rule as any other resource name, so two addons cannot
collide and neither can collide with the game's own.

The menu has to exist: add your entries from [`EnterWorld`](event.md) or later, not from `Load`.

| What you did | What you get |
|---|---|
| `:add("dig")` twice | your addon *already has an entry with that id* — an id is unique within an addon |
| `:add("")` | the id must be a **non-empty** string |
| `:add("/dig")` | the id *is absolute* — an id is relative to your own addon |
| `:add("../dig")` | the id *climbs out of your addon* with `..` |
| `:add(7)`, `:add({})` | expected a **string** id |
| `:add(nil)` | the id must not be `nil` — see [nil](conventions.md#nil-is-an-error-unless-it-means-something) |
| `:add("dig")` before the HUD is up | the action menu *is not up yet* |

### `hafen.menugrid():remove(idOrPagina)`

Takes the `Pagina` object, or its id as a string: `"dig"` as you spelled it to `:add`, or the whole
`addon/myaddon/dig` identity you read back from `:res()`. The entry leaves the grid at once and every handle
to it goes `:exists() == false`.

Removing one that is already gone is **inert** — a removal is a moment, not a mistake. Removing one of the
client's own entries, or another addon's, raises naming whose it is: what the server granted is the
server's to revoke.

### What an entry draws

`pag:name(text)` sets the label the grid paints under the pointer and sorts by; an entry you never name
shows the id you gave it. `pag:icon(image)` takes the handle [`hafen.asset():get("dig.png")`](asset.md)
hands you and **never a path** — the loader is one door, and a string here says so.

An entry with no icon draws an empty cell. An image bigger than a cell is scaled down to fit, keeping its
aspect ratio; a smaller one is drawn at its own size. Either way it is centred, and its pixels are
[design pixels](ui/pixels.md), so a 32×32 PNG fills a cell exactly like the game's own art.

Every write here refuses on an entry your addon did not add — one of the client's own, or another
addon's — naming which it is, so a stashed handle can never write over someone else's button.

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
is one.

`use` raises an error on a category, on an entry that is no longer in the menu, and on one whose
resource has not finished loading; check `:exists()` first if you are holding a stashed handle. A
ground-targeted action enters targeting mode, just as the click would, and you supply the target with
[`gob:click`](gob.md#write-protected) or
[`hafen.world():place`](world.md#write-protected).

Because it goes through the client's own button code, `use` sends the action **by path when it has one
and by id when it does not** — so it reaches the id-only entries, such as server-pushed abilities, that
no path can express. This is the one door onto a menu action: the entry addresses itself, by resource
name or display name, and there is no path-shaped way in beside it.

## See also

- [`hafen.actionbar`](actionbar.md) — putting one of these resource names on the hotbar
- [`hafen.asset`](asset.md) — loading the PNG a custom entry draws
- [`Pagina`](types.md#pagina) — the snapshot shape `:info()` returns
- [`hafen.craft`](craft.md) — the window a recipe action opens
