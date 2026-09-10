# References: how you address things

A verb takes an explicit **reference** to the thing it acts on, and it re-resolves that reference on
every call. This page is the catalogue: one entry per kind of thing the API hands you, and what you
pass back when a verb asks for it. The grammar those verbs are spelled in is
[conventions](conventions.md).

**Every reference below is an object**, whatever it names — a game object, a roster entry, a widget, a file
you loaded. You call its verbs with a colon, you cannot write to it, `tostring` names it, and a name it does
not answer raises where you wrote it instead of reading `nil`. A table you build to look like one is not
one, and the verb you pass it to refuses it.

**A refusal names only verbs that exist.** Every verb a message offers as the one to write instead — a
replacement for a name that moved, the door a dot call should have used — is one its receiver answers: the
tree's own check, `tools/refusalverbs.py`, resolves each such promise against that receiver's vocabulary, so
following a message never lands you on a second one.

```lua
local s = hafen.session():current()                        -- the character on screen
local tree = s:world():gob():nearest("terobjs/tree")       -- a Gob, never an id
if tree then tree:overlay():add("mark"):text("here") end   -- and a Gob is what a verb takes
```

## Gob: a game object

`s:world():gob()` is the collection of the game objects one of your characters has loaded, and everything
on it hands back a [**Gob**](gob.md) whose methods read the live one; `s:player():gob()` is that character
itself. `s` is a [Session](session.md), which says **which** character's world is being searched — but a Gob
is the object rather than that character's view of it, so two of your characters find the same value for one
tree; see [identity](gob.md#identity). Every method re-resolves, so it answers `nil` once the gob is gone
while `:id()` still answers. Anywhere a single gob is addressed — [`gob:overlay()`](overlay.md),
[`s:player():hand():use(gob)`](player.md#the-hand) — you pass the Gob itself, never an id.

## Kin: a roster entry

A kin is an object too, and `s:kin()` **is** that character's roster collection: `:list(filter)` is the
array of `Kin`, `:get(idOrName)` one of them. A `Kin` re-reads the roster on every call, so a stashed one
tracks renames, regroups and online flips, and `s:kin():get(7) == s:kin():get(7)`. `gob:kin()` and
`kin:gob()` cross between the two. See [`session:kin`](kin.md).

## Slot: an action-bar slot

Same pattern: `s:actionbar():list()` is all 144 slots, a 1-based array of `Slot`, and `:get(n)` is
the one at **that same position** — `:list()[n] == :get(n)`, which is what `slot:index()` answers.
The raw 0-based number the server carries is `slot:wire()`. A stashed `Slot` goes `:empty()` the moment the slot is cleared. The one thing a slot does *not*
name is a place your addon owns: the server owns the bar, so an entry of your own goes on it as a
[hold](actionbar.md#hold-a-slot-unprotected) over the server's content, which comes back the moment the hold
ends. See [`session:actionbar`](actionbar.md).

## Named, and nameless: Menugrid, Sound, Buff, Meter

[`s:menugrid():get(key)`](menugrid.md) names one action — a `/` makes the key a resource name,
anything else a display name — and [`hafen.sound():get(name)`](sound.md) one clip; the strings are
**server-published**, so read them off a live client with `:res()` rather than trusting a list. The one
exception is the one you write yourself: an entry your addon
[adds to the menu](menugrid.md#write-unprotected) carries the identity you gave it,
`addon/<your addon's id>/<the id>`, and that string is the same in every session.
[`s:buff()`](buff.md) and [`s:meter()`](meter.md) carry **no `:get`** at all, because their
members have no key: several bars can share one resource. There a name is a *search*, `:find(needle)`,
and `:get` [raises naming that search](conventions.md#get-what-a-key-that-names-nothing-answers) — a
miss is `nil`, and a **position** is an error too.

## Asset: a file your addon ships

[`hafen.asset()`](asset.md) is a collection keyed by an **addon-relative path**: `:get(path)` is one
asset, `:list(filter)` the ones this addon holds. It is the one collection that hands back an **owned
resource** rather than a view of client state — the type comes from the file's extension, the handle is
interned per path, and it is freed on reload or disable, or by `hafen.asset():remove(a)`, after which the
same path loads as a *new* object. The ending is the **collection's**, because the collection is what owns
the file. Wherever a local file is used — a sprite's `:add(image)`, an object's `:add(model)`, a widget's
`:font(h)` — you pass the **handle**, never a path, and `:remove` is no exception.

## Item: a thing the client draws

An item has no stable content id, so an [`Item`](ui/items.md#the-item-object) is interned on the thing
drawn and **not** on `:handle()`, the server widget id it is addressed by on the wire: that number is
re-used, so a reference built on it would quietly stop naming this item and start naming its
replacement. One you keep therefore answers *the same item* or *gone*, and the
[protected verbs](ui/items.md#write-protected) are on the item itself rather than on a number.

**Every item is found through the icon drawing it**, which is also what ends it. A container's cell, the
cursor, a crafting recipe's slot and a listing a resource paints are one type and one interning, so
`icon:item()` is `==` the container's own `:items()` entry, and each of them goes stale when the widget
drawing it leaves the tree. What the server put in a container answers where it is; what the client only
draws answers [absence and a refusal](ui/items.md#a-depiction-that-is-not-an-item), because there is no
widget behind it to address.

## Widget: a piece of the UI

A widget is an object, and there is only one kind. A window you create with `hafen.ui():window()`, a
native one you name with `s:ui():match(selector)`, `:node(id)` or `:inventory()` on the
[session](session.md) whose tree it stands in, the deepest one under a screen point, and
the one [`s:ui():on`](ui/replace.md#watching-for-a-widget) hands your callback are all the same
[Widget](ui/widget.md). It is interned per addon, so `hafen.ui():hit(x, y) == hafen.ui():hit(x, y)` and `==`
is the identity test; it re-reads the tree on every call and answers `nil` or empty, with `:exists()`
false, once its widget is gone. What you may *write* depends on whether your addon created it — see
[owned vs borrowed](ui/widget.md#owned-vs-borrowed). A **server widget id**, `:id()`, is what makes one
*bound*, which is what the protected [`widget:send`](ui/widget.md#send-a-message-protected) needs.

**The domain objects cross back to it.** A [Buff](buff.md), a [Meter](meter.md), a
[StudySlot](study.md) and a [Kin](kin.md) each answer `:widget()` — the widget that draws them — so
"put a badge over the buff that is about to expire" is one hop rather than a search the selector language
cannot express. A selector reaches the *window* by role; the thing inside it is a domain object, and
`:widget()` is where the two address spaces meet. `w:session()` is the crossing in the other direction:
the character whose tree a widget stands in, `nil` for one in your own layer.

Its write verbs answer for **your** addon: what you wrote comes back unchanged, and what you drop
leaves another addon's alone. [`w:replace(view)`](ui/replace.md) installs a stand-in and
`w:replace(nil)` undoes it; [`w:rule()`](ui/style/README.md#restyle-one-widget) is your own level of the
style cascade, and `w:rule():release()` gives it back.
[`w:revert()`](ui/edit.md#taking-the-whole-edit-back) gives back every [edit](ui/edit.md) you hold on a
widget and on what is inside it at once, a standing replacement excepted.

## Selector: naming a piece of the UI

Ids and handles address a thing you already have. A **selector** addresses one you can only
*describe*: a string that names a widget by what it **is**, resolved against the live tree.

```lua
local s = hafen.session():current()
s:ui():match("window[title=Cupboard]")    -- the one match, or nil (two or more raises)
s:ui():matchAll("inventory")                  -- every match, in tree order (empty array, never nil)
```

Three properties make it a convention rather than a lookup helper:

- **One string, three uses.** The same selector names a widget for a lookup, `s:ui():match(sel)`, for a
  listing, `s:ui():matchAll(sel)`, and for one that does not exist yet,
  [`s:ui():on(sel, "Added", fn)`](ui/replace.md#watching-for-a-widget) — so waiting for a window and
  then reading it are one vocabulary.
- **One string, two resolutions.** The same selector is also the key of a
  [stylesheet](ui/style/README.md): a bare role the client *draws at* names a
  [render site](ui/style/surfaces.md) and restyles it, while every other selector — a role that names a
  widget rather than a site, and anything carrying a class, a refiner or a second step — resolves against
  the live tree. `w:role()` reports a widget's role, or an honest `nil`.
- **The verb says how many**: `s:ui():match(sel)` is one widget — and
  [refuses where the selector names several](ui/selectors.md#one-or-all-of-them), rather than picking one —
  `s:ui():matchAll(sel)` is all of them, and `s:ui():root()` is the root of that character's tree. Both
  verbs are also methods on a widget ([`w:match`](ui/widget.md#searching-inside-one-widget)).

- **A selector resolves in one character's tree.** The lookups hang off a [Session](session.md) because
  that is the tree they search, and the windows your addon built stand in none of them.

The grammar, the role table and the two rules worth knowing first — a space is the *descendant
combinator*, and you hold your result rather than re-selecting every frame — are in
[selectors](ui/selectors.md), where the bundled **`widgetstack`** addon also
[names one by hovering](ui/selectors.md#the-inspector).

## See also

- [conventions](conventions.md) — the grammar, the filter, `nil` and the permission model
- [data types](types/README.md) — the plain tables an `:info()` hands back
- [Gob](gob.md) — the object every world verb is aimed at
- [the Widget object](ui/widget.md) — what a widget answers, and what it lets you write
- [selectors](ui/selectors.md) — the grammar of the string that names a widget
