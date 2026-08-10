# References: how you address things

A verb takes an explicit **reference** to the thing it acts on, and it re-resolves that reference on
every call. This page is the catalogue: one entry per kind of thing the API hands you, and what you
pass back when a verb asks for it. The grammar those verbs are spelled in is
[conventions](conventions.md).

```lua
local tree = hafen.world():gob():nearest("terobjs/tree")   -- a Gob, never an id
if tree then tree:overlay():add("mark"):text("here") end   -- and a Gob is what a verb takes
```

## Gob: a game object

`hafen.world():gob()` is the collection of loaded game objects and everything on it hands back a
[**Gob**](gob.md) whose methods read the live one; `hafen.player():gob()` is your own. Every method
re-resolves, so it answers `nil` once the gob is gone while `:id()` still answers. Anywhere a single gob
is addressed — [`gob:overlay()`](overlay.md), [`hafen.player():hand():use(gob)`](player.md#the-hand)
— you pass the Gob itself, never an id.

## Kin: a roster entry

A kin is an object too, and `hafen.kin()` **is** the roster collection: `:list(filter)` is the array of
`Kin`, `:get(idOrName)` one of them. A `Kin` re-reads the roster on every call, so a stashed one tracks
renames, regroups and online flips, and `hafen.kin():get(7) == hafen.kin():get(7)`. `gob:kin()` and
`kin:gob()` cross between the two. See [`hafen.kin`](kin.md).

## Slot: an action-bar slot

Same pattern: `hafen.actionbar():list()` is all 144 slots, a 1-based array of `Slot`, and `:get(n)` is
the one at the **raw 0-based game index**, with `slot:index()` giving that index back from an array
position. A stashed `Slot` goes `:empty()` the moment the slot is cleared. See
[`hafen.actionbar`](actionbar.md).

## Named, and nameless: Menugrid, Sound, Buff, Meter

[`hafen.menugrid():get(key)`](menugrid.md) names one action — a `/` makes the key a resource name,
anything else a display name — and [`hafen.sound():get(name)`](sound.md) one clip; the strings are
**server-published**, so read them off a live client with `:res()` rather than trusting a list.
[`hafen.buff()`](buff.md) and [`hafen.meter()`](meter.md) carry **no `:get`** at all, because their
members have no key: several bars can share one resource. There a name is a *search*, `:find(needle)`,
and `:get` raises an error naming it — a miss is `nil`, and a **position** is an error.

## Asset: a file your addon ships

[`hafen.asset()`](asset.md) is a collection keyed by an **addon-relative path**: `:get(path)` is one
asset, `:list(filter)` the ones this addon holds. It is the one collection that hands back an **owned
resource** rather than a view of client state — the type comes from the file's extension, the handle is
interned per path, and it is freed on reload or disable, or by `:dispose()`, after which the same path
loads as a *new* object. Wherever a local file is used — a sprite's `:add(image)`, an object's
`:add(model)`, a widget's `:font(h)` — you pass the **handle**, never a path.

## Item: a thing in a container

An item has no stable content id, so an [`Item`](ui/items.md#the-item-object) is interned on the item
itself and **not** on `:handle()`, the server widget id it is addressed by on the wire: that number is
re-used, so a reference built on it would quietly stop naming this item and start naming its
replacement. One you keep therefore answers *the same item* or *gone*, and the
[protected verbs](ui/items.md#write-protected-actions) are on the item itself rather than on a number.

## Widget: a piece of the UI

A widget is an object, and there is only one kind. A window you create with `hafen.ui():window()`, a
native one you name with `hafen.ui():find(selector)`, `node(id)`, `at(x, y)` or `inventory()`, and the one
[`hafen.ui():on`](ui/replace.md#watching-for-a-widget) hands your callback are all the same
[Widget](ui/widget.md). It is interned per addon, so `hafen.ui():at(x, y) == hafen.ui():at(x, y)` and `==`
is the identity test; it re-reads the tree on every call and answers `nil` or empty, with `:exists()`
false, once its widget is gone. What you may *write* depends on whether your addon created it — see
[owned vs borrowed](ui/widget.md#owned-vs-borrowed). A **server widget id**, `:id()`, is what makes one
*bound*, which is what the protected [`widget:send`](ui/widget.md#send-a-message-protected-actions) needs.

Its write verbs answer for **your** addon: what you wrote comes back unchanged, and what you drop
leaves another addon's alone. [`w:replace(view)`](ui/replace.md) installs a stand-in and
`w:replace(nil)` undoes it; [`w:rule()`](ui/style/README.md#restyle-one-widget) is your own level of the
style cascade, and `w:rule():remove()` drops it.

## Selector: naming a piece of the UI

Ids and handles address a thing you already have. A **selector** addresses one you can only
*describe*: a string that names a widget by what it **is**, resolved against the live tree.

```lua
hafen.ui():find("window[title=Cupboard]")     -- the first match, or nil
hafen.ui():all("inventory")              -- every match, in tree order (empty array, never nil)
```

Three properties make it a convention rather than a lookup helper:

- **One string, three uses.** The same selector names a widget for a lookup, `hafen.ui():find(sel)`, for a
  listing, `hafen.ui():all(sel)`, and for one that does not exist yet,
  [`hafen.ui():on(sel, "appear", fn)`](ui/replace.md#watching-for-a-widget) — so waiting for a window and
  then reading it are one vocabulary.
- **One string, two resolutions.** The same selector is also the key of a
  [stylesheet](ui/style/README.md): a **role** names a render *site* and restyles it
  ([the site keys](ui/style/surfaces.md)), while every other selector resolves against the live tree.
  `w:role()` reports a widget's role, or an honest `nil`.
- **The verb says how many**: `hafen.ui():find(sel)` is one widget, `hafen.ui():all(sel)` is all of them,
  and `hafen.ui():root()` is the root of the tree.

The grammar, the role table and the two rules worth knowing first — a space is the *descendant
combinator*, and you hold your result rather than re-selecting every frame — are in
[selectors](ui/selectors.md), where the bundled **`widgetstack`** addon also
[names one by hovering](ui/selectors.md#the-inspector).

## See also

- [conventions](conventions.md) — the grammar, the filter, coordinates, colours and the permission
- [data types](types.md) — the plain tables an `:info()` hands back
- [Gob](gob.md) — the object every world verb is aimed at
- [the Widget object](ui/widget.md) — what a widget answers, and what it lets you write
- [selectors](ui/selectors.md) — the grammar of the string that names a widget
