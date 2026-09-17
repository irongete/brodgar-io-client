# References: How You Address Things

A verb takes an explicit reference to the thing it acts on and re-resolves it on every call. One entry per kind of thing the API hands you, and what you pass back when a verb asks for it. The grammar those verbs are spelled in is [conventions](conventions.md).

```lua
local session = hafen.session():current()                        -- the character on screen
local tree = session:world():gob():nearest("terobjs/tree")       -- a Gob, never an id
if tree then tree:overlay():add("mark"):text("here") end         -- and a Gob is what a verb takes
```

---

| Rule | Detail |
|---|---|
| Every reference is an object | A game object, a roster entry, a widget, a loaded file. Verbs are called with a colon. Nothing is written to it. `tostring` names it. An unknown name raises where you wrote it. A look-alike table is refused by the verb you pass it to. |
| A refusal names only verbs that exist | Every verb a message offers to write instead is one its receiver answers. |

## Gob: a game object

[`session:world():gob()`](world.md#objects) is the collection of the game objects one character has loaded, every verb handing back a [Gob](gob.md) whose methods read the live one. `session:player():gob()` is that character. The Session says which character's world is searched. A Gob is the object rather than that character's view, so two characters find the same value for one tree ([identity](gob.md#identity)). It answers `nil` once the gob is gone while `:id()` still answers. Wherever a gob is addressed ([`gob:overlay()`](overlay.md), [`session:player():hand():use(gob)`](player.md#the-hand)) you pass the Gob, never an id.

## Kin: a roster entry

`session:kin()` is that character's roster: `:list(filter)` the array of `Kin`, `:get(id_or_name)` one. A `Kin` re-reads the roster on every call, so a stashed one tracks renames, regroups and online flips, and `session:kin():get(7) == session:kin():get(7)`. `gob:kin()` and `kin:gob()` cross between the two ([`session:kin`](kin.md)).

## Slot: an action-bar slot

`session:actionbar():list()` is all 144 slots as a 1-based array of `Slot`, `:get(n)` the one at that position (`:list()[n] == :get(n)`, what `slot:index()` answers). `slot:wire()` is the server's 0-based number. A stashed `Slot` goes `:empty()` when cleared. A slot never names a place your addon owns: an entry of yours goes on the bar as a [hold](actionbar.md#hold-a-slot-unprotected) over the server's content ([`session:actionbar`](actionbar.md)).

## Named, and nameless: Menugrid, Sound, Buff, Meter

| Kind | Addressed by |
|---|---|
| [`session:menugrid():get(key)`](menugrid.md) | One action: a `/` makes the key a resource name, anything else a display name. Server-published strings: read them off a live client with `:res()`. An entry your addon [adds](menugrid.md#write-unprotected) carries `addon/<your addon's id>/<the id>`, the same in every session. |
| [`hafen.sound():get(name)`](sound.md) | One clip, by server-published name. |
| [`session:buff()`](buff.md), [`session:meter()`](meter.md) | No `:get`: members have no key, since several bars can share one resource. A name is a search, `:find(needle)`. `:get` [raises naming that search](conventions.md#get-what-a-key-that-names-nothing-answers). A miss is `nil`. A position is an error. |

## Resource and Layer: what the client draws, by name

[`hafen.resource():get(name)`](resource/README.md) is a live handle for a well-formed resource name, minted for any name and fetching nothing until a content read. `:list(filter)` walks the resources the client holds. A [`Layer`](resource/layers.md), `resource:layers():get(key)`, is live while it is one of its resource's layers. It answers `false` on `:exists()` once a load or a [write](resource/writes.md) replaced it.

## Asset: a file your addon ships

[`hafen.asset()`](asset/README.md) is a collection keyed by addon-relative path: `:get(path)` one asset, `:list(filter)` the ones this addon holds. The one collection handing back an owned resource rather than a view of client state. An asset is typed by extension and interned per path. It is freed on reload or disable or by `hafen.asset():remove(asset)`, after which the same path loads as a new object. The ending is the collection's, since the collection owns the file. Wherever a local file is used (a sprite's `:add(image)`, an object's `:add(model)`, a widget's `:font(handle)`) you pass the handle, never a path.

## Item: a thing the client draws

An item has no stable content id, so an [`Item`](ui/items.md#the-item-object) is interned on the thing drawn and not on `:handle()`. That is the server widget id it is addressed by on the wire, re-used, so a reference built on it would start naming a replacement. One you keep answers the same item or gone, and the [protected verbs](ui/items.md#write-protected) are on the item. Every item is found through the icon drawing it, which also ends it. A container's cell, the cursor, a recipe slot and a listing a resource paints are one type and one interning. So `icon:item()` is `==` the `:items()` entry of the widget around it. Each goes stale when that widget leaves the tree. What the client only draws answers [absence and a refusal](ui/items.md#a-depiction-that-is-not-an-item).

## Widget: a piece of the UI

One kind of object. A window from `hafen.ui():window()`. A native one from `session:ui():match(selector)`, `:node(id)` or `:inventory()`. The deepest under a screen point. The one [`session:ui():on`](ui/replace.md#watching-for-a-widget) hands your callback. All are the same [Widget](ui/widget.md).

| Rule | Detail |
|---|---|
| Interned per addon | `hafen.ui():hit(x, y) == hafen.ui():hit(x, y)`. It re-reads the tree on every call and answers `nil` or empty, `:exists()` false, once its widget is gone. |
| What you may write | Depends on whether your addon created it ([owned vs borrowed](ui/writes.md#owned-vs-borrowed)). A server widget id, `:id()`, makes one bound, what the protected [`widget:send`](ui/widget.md#send-a-message-protected) needs. |
| The domain objects cross back | A [Buff](buff.md), a [Meter](meter.md), a [StudySlot](study.md) and a [Kin](kin.md) answer `:widget()`, the widget that draws them. A badge over the buff about to expire is one hop. A selector reaches the window by role. `:widget()` is where the two address spaces meet. `widget:session()` crosses the other way: the character whose tree a widget stands in, `nil` for one in your own layer. |
| Writes answer for your addon | What you wrote comes back unchanged. What you drop leaves another addon's alone. [`widget:replace(view)`](ui/replace.md) installs a stand-in and `widget:replace(nil)` undoes it. [`widget:rule()`](ui/style/README.md#restyle-one-widget) is your level of the style cascade and `widget:rule():release()` gives it back. |
| The whole edit back | [`widget:revert()`](ui/edit.md#taking-the-whole-edit-back) gives back every [edit](ui/edit.md) on a widget and what is inside it, a standing replacement excepted. |

## Selector: naming a piece of the UI

A selector addresses a widget you can only describe: a string naming a widget by what it is, resolved against the live tree.

```lua
local session = hafen.session():current()
local cupboard = session:ui():match("window[title=Cupboard]")    -- the one match, or nil (two or more raises)
local inventories = session:ui():matchAll("inventory")           -- every match, in tree order (empty array, never nil)
```

| Rule | Detail |
|---|---|
| One string, three uses | A lookup (`session:ui():match(selector)`), a listing (`session:ui():matchAll(selector)`), and one that does not exist yet ([`session:ui():on(selector, "Added", fn)`](ui/replace.md#watching-for-a-widget)). |
| One string, two resolutions | The same selector is a [stylesheet](ui/style/README.md) key: a bare role the client draws at names a [render site](ui/style/surfaces.md). Every other selector resolves against the live tree. `widget:role()` reports a widget's role, or `nil`. |
| The verb says how many | `match` is one widget and [refuses several](ui/selectors.md#one-or-all-of-them). `matchAll` is all. `session:ui():root()` is the tree's root. Both are also methods on a widget ([`widget:match`](ui/widget.md#searching-inside-one-widget)). |
| One character's tree | The lookups hang off a [Session](session.md). The windows your addon built stand in none of them. |

The grammar, the role table, the descendant combinator and holding a result rather than re-selecting every frame are in [selectors](ui/selectors.md). There the `widgetstack` addon [names one by hovering](ui/selectors.md#the-inspector).

---

## See Also

- [Conventions](conventions.md) — the grammar, the filter, `nil` and the permission model.
- [Data types](types/README.md) — the plain tables an `:info()` hands back.
- [Gob](gob.md) — the object every world verb is aimed at.
- [The Widget object](ui/widget.md) — what a widget answers, and what it lets you write.
- [Selectors](ui/selectors.md) — the grammar of the string that names a widget.
