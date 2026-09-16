# session:menugrid: The Action Menu

One character's action menu (the client's catalogue of every unlocked action, or pagina, in a category tree), read, invoked and extended through its [session](session.md); `session:menugrid()` is that character's catalogue.

```lua
local session = hafen.session():current()         -- the character on screen
local dig = session:menugrid():get("Dig")         -- or "paginae/act/dig" (a "/" means a resource name)
hafen.log():write(dig:parent():name() .. " > " .. dig:name())    -- "Dig" lives under a category
dig:use()                                                       -- fire it: exactly a click on that button
```

---

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:menugrid():get(key)` | `Pagina \| nil` | Unprotected | The one entry `key` names: a resource name, a display name, or the short id you gave `:add(id)`. |
| `session:menugrid():list(filter)` | `Pagina[]` | Unprotected | The whole catalogue, in the grid's own sort order. |
| `session:menugrid():count(filter)` | `number` | Unprotected | How many match. |
| `session:menugrid():find(filter)` | `Pagina \| nil` | Unprotected | The first that matches. |
| `session:menugrid():roots()` | collection | Unprotected | The entries with no parent: the grid's root screen. |

| Rule | Detail |
|---|---|
| Whose catalogue | Two characters know different things: `hafen.session():get("alt"):menugrid():get("Dig")` asks whether that character can dig. `:add` puts an entry in the menu you addressed, and every write reaches the entry in the session it was read through. |
| One object | `session:menugrid()` is the same object every call, minted once per session. A session the client no longer holds has an empty catalogue. |
| Interned per addon | On character and resource name: `session:menugrid():get("Dig") == session:menugrid():get("paginae/act/dig")` and `seen[pagina] = true` work; the same name through two sessions is two objects. A `Pagina` re-resolves the live menu on every call, so a stashed one goes `:exists() == false` when the action is revoked ([snapshots vs handles](conventions.md#snapshots-vs-handles)). |
| No positions | The catalogue grows as you discover things, so `:get(1)` raises pointing at the two name forms; `:list()` is 1-based so `ipairs` works. |
| `filter` | A string [filter](conventions.md#the-filter-argument) matches the display name as a substring: `:list("Dig")` is every entry whose name contains it. An entry whose resource has not resolved has no display name and matches nothing. |
| The catalogue fills in | Resources resolve asynchronously, so a scan at `SessionEnteredWorld` is often short; unresolved entries are absent and appear a fraction of a second later. Scan on a timer for the complete set, and expect it to grow as the character plays. |
| No `MenuChanged` | Poll `session:menugrid():list()` on `Update` or a [timer](timer.md). |

## The key splits by shape

| Key | Meaning |
|---|---|
| Contains a `/` (`"paginae/act/dig"`) | A resource name: the identity. The intern key, known as soon as the resource is named, the string [`slot:res(name)`](actionbar.md#write-protected) takes, compared exactly. |
| Anything else (`"Dig"`) | A display name: a search convenience. Needs the resource fully loaded, matched case-insensitively, not unique (the first match in catalogue order wins). |

A resource name never scans display names and a display name never hijacks a resource lookup. A miss is `nil` for both: that character does not have the action. A stashed handle still answers `:exists()` after the entry goes.

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `pagina:res()` | `string` | Unprotected | The resource name, the identity; always answers, even for a revoked entry. |
| `pagina:addon()` | `string \| nil` | Unprotected | The id of the addon that added this entry; `nil` for the game's own. |
| `pagina:name()` | `string \| nil` | Unprotected | The display name the grid shows. |
| `pagina:icon()` | image `\| nil` | Unprotected | The [asset](asset/README.md) your addon gave this entry; `nil` on the game's own. |
| `pagina:tooltip()` | `string \| nil` | Unprotected | The description under the name, when the resource carries one. |
| `pagina:hotkey()` | `string \| nil` | Unprotected | The letter the grid paints over the button while Alt is held. |
| `pagina:categories()` | `string[] \| nil` | Unprotected | The categories above this entry, as the action tokens the message carries; empty for a category and for an id-only entry. |
| `pagina:parent()` | `Pagina \| nil` | Unprotected | The category this entry sits under; `nil` for a root entry. |
| `pagina:children()` | collection `\| nil` | Unprotected | The entries under this one, what the grid shows after clicking it; empty for a leaf. |
| `pagina:unseen()` | `boolean` | Unprotected | Whether the entry is still flagged as a new discovery, the grid's highlight. |
| `pagina:exists()` | `boolean` | Unprotected | Whether the entry is still in the menu. |
| `pagina:info()` | [`Pagina`](types/ui.md#pagina) `\| nil` | Unprotected | A plain-table snapshot of the same fields. |

Every reader except `:res()` and `:exists()` answers `nil` once the entry is gone, and while its resource is loading. No reader throws; none is protected.

### The tree

The catalogue is flat and complete: every action plus the categories they hang under, so `:parent()` always lands on a readable entry, `:roots()` is never empty once the menu is up, and "is this a category" is `pagina:children():count() > 0`.

```lua
for _, category in ipairs(hafen.session():current():menugrid():roots():list()) do
  hafen.log():write(category:name() or category:res())
  for _, child in ipairs(category:children():list()) do
    hafen.log():write("   " .. (child:name() or child:res()))
  end
end
```

## Write (unprotected)

An entry of your own stands in the same grid, in the same order: `session:menugrid():add(id)` mints a `Pagina` your addon owns, and every reader answers for it, `:get` addresses it, `:list()` carries it, `:roots()` shows it.

```lua
local session = hafen.session():current()
local dig = session:menugrid():add("dig")                      -- its identity is addon/myaddon/dig
dig:name("Auto-dig"):icon(hafen.asset():get("dig.png"))        -- writes chain
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:menugrid():add(id)` | `Pagina` | Unprotected | Mint an entry your addon owns. |
| `session:menugrid():remove(id_or_pagina)` | the collection | Unprotected | Take one of your own back out. |
| `pagina:name(text)` | the `Pagina` | Unprotected | The display name the grid paints and its tooltip shows. |
| `pagina:tooltip(text)` | the `Pagina` | Unprotected | The description the tooltip paints under that name. |
| `pagina:icon(image)` | the `Pagina` | Unprotected | The picture the button draws: an image [asset](asset/README.md) handle. |
| `pagina:parent(pagina_or_nil)` | the `Pagina` | Unprotected | The category it hangs under; `nil` is the root screen. |

| Rule | Detail |
|---|---|
| No permission | Nothing reaches the server: a custom entry is drawn by this client and pressing it runs [your own Lua](#a-click-runs-your-lua), like a [HUD overlay](ui/overlay.md). |
| Bridge-owned | Reloading or disabling your addon takes every entry back out of every character's menu. |
| A menu goes with the world it was in | A relog or a reconnect hands the character a new menu; entries in the old one are drawn nowhere, `:exists() == false`, their ids free. The same [`SessionEnteredWorld`](event/bus/lifecycle.md#sessions) handler that added them adds them again. The [action-bar slot](actionbar.md#a-hold-is-remembered) each was placed in is remembered separately, and the `:add` takes it back. |
| Only your own | Every write here refuses on an entry your addon did not add (the client's own, another addon's), naming which it is. |

### `session:menugrid():add(id)`

`id` is addon-relative, spelled like an asset path (`"dig"`, `"tools/dig"`); the identity is `addon/<your addon's id>/<id>`, what `:res()` answers and `:get()` resolves by the shape rule, so two addons cannot collide and neither with the game's own. Unique within a character: the same button on two logins is ordinary; twice in one menu is refused. Add from [`SessionEnteredWorld`](event/bus/lifecycle.md#sessions) or later, not from `Load`; that event hands you the session to add to, once per character.

| Call | Refusal names |
|---|---|
| `:add("dig")` twice on one character | Your addon already has an entry with that id in that menu. |
| `:add("")` | The id must be a non-empty string. |
| `:add("/dig")` | The id is absolute; an id is relative to your own addon. |
| `:add("../dig")` | The id climbs out of your addon with `..`. |
| `:add(7)`, `:add({})` | Expected a string id. |
| `:add(nil)` | The id must not be `nil` ([nil](conventions.md#nil-is-an-error-unless-it-means-something)). |
| `:add("dig")` before that character's HUD is up | The action menu is not up yet. |

### `session:menugrid():remove(id_or_pagina)`

Takes the `Pagina`, or its id as a string: `"dig"` as spelled to `:add`, or the whole `addon/myaddon/dig` identity. The entry leaves the grid at once and every handle goes `:exists() == false`. Removing one already gone is inert, so is one standing only in another character's menu. Removing one of the client's own or another addon's raises naming whose it is.

### What an entry draws

| Rule | Detail |
|---|---|
| `pagina:name(text)` | The label the grid paints and sorts by; an unnamed entry shows the id you gave it. |
| `pagina:tooltip(text)` | The description under that label, painted once the pointer has rested on the button; an entry with none has its name alone. |
| `pagina:icon(image)` | Takes the handle [`hafen.asset():get("dig.png")`](asset/README.md) hands you, never a path. No icon draws an empty cell. An image bigger than a cell is scaled down keeping its aspect ratio; a smaller one draws at its own size. Centred, in [design pixels](ui/pixels.md): a 32×32 PNG fills a cell like the game's own art. |

### A category is an entry that has children

`pagina:parent(category)` hangs one of your entries under another entry; `pagina:parent(nil)` puts it back on the root screen. A category is an entry with something under it: clicking it opens its children, and Back returns.

```lua
local menugrid = hafen.session():current():menugrid()
local tools = menugrid:add("tools"):name("Tools"):icon(hafen.asset():get("tools.png"))
menugrid:add("tools/dig"):name("Auto-dig"):parent(tools)             -- under your own category
menugrid:add("harvest"):parent(menugrid:get("paginae/act/craft"))    -- under one of the client's own
```

| Rule | Detail |
|---|---|
| One tree | The parent is any `Pagina` in the menu: yours under a game category, beside the server's actions; a category of yours holding your entries. `category:children()` lists what hangs under it. |
| Removing a category | Puts its children back on the root screen; it takes nothing with it. |
| `nil` means the root screen | One of the few places it [carries a meaning](conventions.md#nil-is-an-error-unless-it-means-something); elsewhere on this page an explicit `nil` raises. |
| A cycle is refused, not written | The tree after the error is the tree before it. |

| Call | Refusal names |
|---|---|
| `pagina:parent(pagina)` | That entry cannot hang under itself. |
| A cycle in two steps or more | The entry it would hang under already hangs under this one. |
| `pagina:parent(7)` | The parent is a `Pagina` object, or `nil`. |
| `pagina:parent("Tools")` | That is a key: pass the object `:get(key)` hands you. |
| A parent no longer in the menu | That entry is not in the menu; check `:exists()`. |
| A parent read through another session | That entry is in that character's menu; one tree per character. |

## A click runs your Lua

`pagina:on("use", fn)` runs `fn(pagina)` every time one of your entries is pressed: a left-click on its button in the grid, its key on a held [action-bar slot](actionbar.md#hold-a-slot-unprotected), and [`pagina:use()`](#use-protected) from your own code.

```lua
local dig = hafen.session():current():menugrid():add("dig"):name("Auto-dig")
local subscription = dig:on("use", function(pagina)
  hafen.log():write("pressed " .. pagina:name())
end)
subscription:off()
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `pagina:on("use", fn)` | subscription | Unprotected | Run `fn(pagina)` when this entry is pressed; the same subscription [`hafen.event()`](event/README.md) hands you. |
| `subscription:off()` | the subscription | Unprotected | Unsubscribe; idempotent, and done for you on reload or disable. |

| Rule | Detail |
|---|---|
| `use` is the only key | Any other name throws at the line that wrote it. |
| Several handlers | Both fire in registration order; `off()` on one leaves the other. A handler that errors is logged and breaks neither the others nor the client; one that fails the client itself (stack, memory) stops [your addon](../runtime.md#when-a-failure-is-fatal). |
| Nothing sent | The client runs your function and nothing about the grid changes. An entry nobody subscribed to does nothing when pressed; that is legal. |
| Your own entries only | `pagina:on` refuses on an entry your addon did not add. Subscriptions end on reload, disable and `:remove`; an entry added again starts with none. |

## Use (protected)

The client sends only shapes a player could compose.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `pagina:use()` | the `Pagina` | `menugrid.use` | Perform the action, as a left-click on that menu button does. |

| Rule | Detail |
|---|---|
| No arguments | The client builds the message from the modifier keys held at that instant; a `mods` parameter could only lie about them. |
| Permission | An addon that did not declare `menugrid.use` gets an error naming the key ([the permission model](conventions.md#the-permission-model)). Named after this section, since the entries are yours and the door is one; covers every character ([a key names the action, not the target](../guides/permissions.md#a-key-names-the-action-not-the-target)). The reads are unprotected: enumerating the catalogue tells the server nothing. |
| On an entry of your own | Runs your [handlers](#a-click-runs-your-lua) and sends nothing, through the button code a left-click uses. It still needs the key: any addon can address any entry by name. |
| Raises | On a category, on an entry no longer in the menu, on one whose resource has not finished loading; check `:exists()` on a stashed handle. |
| Targeting | A ground-targeted action enters targeting mode; supply the target with [`session:world():click`](world.md#write-protected) or [`place`](world.md#write-protected). |
| By path or by id | Sends the action by path when it has one and by id when it does not, so it reaches id-only entries such as server-pushed abilities. The one door onto a menu action; there is no path-shaped way in beside it. |

---

## See Also

- [Session](session.md) — the address every read here goes through.
- [`session:actionbar`](actionbar.md) — putting a name on the hotbar, and holding a slot for an entry.
- [`hafen.asset`](asset/README.md) — loading the PNG a custom entry draws.
- [`Pagina`](types/ui.md#pagina) — the snapshot shape `:info()` returns.
- [`session:craft`](craft.md) — the window a recipe action opens.
