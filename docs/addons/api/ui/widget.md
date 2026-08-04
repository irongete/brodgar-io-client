# hafen.ui: the Widget object

A **Widget** is one piece of the UI — a window, a button, a container, a label, or something you built
yourself. It is the one type `hafen.ui` hands back, and every door below returns it.

```lua
local inv = hafen.ui.inventory()
if inv then hafen.log():write(inv:type() .. " holds " .. #inv:items() .. " items") end
```

## Getting a Widget

| Expression | Returns |
|---|---|
| `hafen.ui.window(opts)` / `hafen.ui.widget(opts)` | one you [created](custom.md) — owned |
| `hafen.ui(selector)` | the **first** widget matching a [selector](selectors.md), in tree order, or `nil` |
| `hafen.ui.all(selector)` | **every** match, in tree order — an empty array, never `nil` |
| `hafen.ui()` | the top of the whole client tree; walk down to any open window |
| `hafen.ui.node(id)` | the widget for a **server widget id**, or `nil` if it does not resolve |
| `hafen.ui.at(x, y)` | the **deepest** widget under a root-coord point — see [hit-testing](selectors.md#hit-testing) |
| `hafen.ui.mouse()` | `{x=, y=}` \| nil — the cursor in root coords, not a widget |
| `hafen.ui.inventory()` | your main backpack grid, a container like any other |
| `hafen.ui.equipment()` | your worn-equipment grid |
| `hafen.ui.hand()` | the [`Item`](../types.md#item) on the cursor, or `nil` — a **snapshot**, since there is no widget to walk |

A Widget is opaque, facade-safe userdata: no raw widget crosses into Lua and one cannot be forged. It is
**interned per addon**, so two lookups of the same live widget are the *same* Lua value:

```lua
hafen.ui.at(m.x, m.y) == hafen.ui.at(m.x, m.y)     -- true
hafen.ui.inventory() == hafen.ui.node(invId)       -- true: one widget, one object
```

`==` **is** the identity test, so there is no `:same()`. You can key a table by a Widget, stash one across
frames and compare it next frame. Holding one does not keep the widget or its dead subtree alive: it is a
view of engine state, not an owned resource, and there is nothing to tear down.

**Staleness.** A widget that leaves the tree — window closed, server destroy, relog — is *stale*: every
read answers `nil` or empty, every write is a silent no-op that still chains, and `:exists()`, the one read
that always answers, is `false`. A stashed object is therefore always safe to call; guard on `:exists()`
only when "is it still there?" is the question you are actually asking.

## Read

Every method below answers on every widget, owned or not, and none of them throws.

| Method | Returns | Description |
|---|---|---|
| `:type()` | string | class name, e.g. `"Inventory"`, `"Label"`; for an anonymous subclass, the nearest named superclass |
| `:role()` | string \| nil | what it **is** in the [selector vocabulary](selectors.md#roles), or `nil` when nothing classifies it |
| `:res()` | string \| nil | its [resource name](selectors.md#what-carries-a-res), e.g. `"gfx/invobjs/torch"`; `nil` for most widgets |
| `:id()` | int \| nil | server widget id, or `nil` when the widget is not server-bound |
| `:children()` | array | child Widgets in tree order; empty for a leaf |
| `:parent()` | Widget \| nil | the enclosing widget, or `nil` at the root |
| `:pos()` | `{x=, y=}` | position within the parent, in widget-local px — [`:pos(x, y)` moves it](native.md) |
| `:size()` | `{x=, y=}` | size; for a window its **outer** box |
| `:visible()` | boolean | whether it is visible |
| `:text()` | string \| nil | best-effort text for text-bearing widgets (Label, Button, Window, TextEntry), else `nil` |
| `:items()` | [`Item`](../types.md#item)`[]` | the items inside it — see [items](items.md) |
| `:exists()` | boolean | whether it is still in the tree |
| `:info()` | table \| nil | the snapshot escape hatch `{type, role, res, id, pos, size, visible, text, owned}`; absent values are unset, and the whole thing is `nil` once stale |
| `:walk(fn)` | self | depth-first visit — `fn(widget, depth)`; **return `false` to prune** that subtree |
| `:at(coord)` | Widget \| nil | the deepest widget under a `{x=, y=}` **root-coord** point within this subtree |
| `:rootpos()` | `{x=, y=}` \| nil | its top-left in **root coords**; with `:size()` that is the rectangle to outline it |
| `:style()` | table \| nil | the style this widget [resolves to](style/README.md#the-cascade), or `nil` when nothing names it |
| `:skin()` | table \| nil | read **your own** [skin entry](style/README.md#restyle-one-widget) back, not the resolved style |

Reading the tree is ungated client-side data.

```lua
-- dump the client's full nested tree from the :lua console
hafen.ui():walk(function(n, d)
  hafen.log():write(string.rep("  ", d) .. n:type()
    .. (n:role() and (" [" .. n:role() .. "]") or "")
    .. (n:id()   and (" #" .. n:id())          or "")
    .. (n:text() and (" '" .. n:text() .. "'") or ""))
end)
```

**`:id()` is the pivot for acting.** To *act*, read a **server-bound** widget's `:id()` and pass it to the
gated [`hafen.act.raw(id, msg, …)`](../act.md) with the message a client-side button would have sent. A
message from an unbound widget is dropped, so you never target the button itself but its nearest
server-bound ancestor.

## Owned vs borrowed

A widget is **owned** if *your* addon created it with [`hafen.ui.window{}`/`hafen.ui.widget{}`](custom.md),
and **borrowed** otherwise — a native client widget, or another addon's. Reads answer on both;
`:info().owned` tells you which you are holding, so you can ask rather than provoke the error.

| Method | Owned | Borrowed |
|---|---|---|
| `:pos(x, y)` | move, and chain | **works** — [it is a layer, and it restores](native.md) |
| `:size(w, h)` | resize the content, chrome repacks around it, and chain | **works**, same |
| `:pack()` | shrink the chrome to fit its content (a no-op on a bare widget), and chain | **error** — that is not yours to do |
| `:destroy()` | remove it and everything in it | **error**, same reason |
| `:hide()` / `:show()` | toggle visibility, and chain | **works** — [see hiding](native.md#hiding-a-native-widget-carries-a-restore) |
| `:replace(view)` | **error** — a window you created is not one to stand in for | **works** — [put your own window in its place](replace.md) |
| `:skin{…}` / `:skin(nil)` | restyle it and its subtree, and chain | **works**, same |

None of these writes is gated: they are client-side state, and every one of them restores.

**Arity is the verb** on geometry — `w:pos()` reads, `w:pos(x, y)` writes, `w:pos(nil)` drops your write;
`w:size()` the same — and on replacement and skinning: `w:replace()` reads, `w:replace(view)` installs,
`w:replace(nil)` undoes. That is the same shape [client options](../client/README.md) use, and it is why
there is no `:move()`.

Provenance comes from the tree, not from how you obtained the object: find your own window with
`hafen.ui.at(x, y)` and you get the very same value `hafen.ui.window{}` returned, writes and all. Addon B
looking at addon A's window holds a *borrowed* widget, which is the correct answer.

## See also

- [selectors](selectors.md) — how to name the widget you want in the first place
- [native](native.md) — what moving and hiding a borrowed widget actually does
- [replace](replace.md) — standing your own window in place of a native one
- [items](items.md) — `:items()` and the three container subscriptions
- [style](style/README.md) — `:skin{…}`, `:style()` and the cascade they sit in
