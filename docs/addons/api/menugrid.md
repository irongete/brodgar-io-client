# hafen.menugrid — the action menu (paginae)

Read and invoke the **action menu** — the 4×4 grid in the corner of the HUD, which is the client's
catalogue of everything your character can *do*: every unlocked action ("pagina"), arranged in a
category tree. `hafen.menugrid` is a function, and the arity is the verb:

| Call | Returns |
|---|---|
| `hafen.menugrid()` | the whole **catalogue** — an array of `Pagina` objects in the grid's own sort order |
| `hafen.menugrid(key)` | the one `Pagina` that `key` names, or `nil` |

```lua
for i, a in ipairs(hafen.menugrid()) do                  -- every action you know, flat
  hafen.log(("%2d %-28s %s"):format(i, a:name() or "?", a:res()))
end
local dig = hafen.menugrid("Dig")                        -- or "paginae/act/dig" (a "/" ⇒ resource name)
hafen.log(dig:parent():name() .. " > " .. dig:name())    -- "Dig" lives under a category
dig:use()                                                -- fire it — exactly a click on that button
```

Pagina objects are **interned per addon**, so `hafen.menugrid("Dig") == hafen.menugrid("paginae/act/dig")`
and `seen[pag] = true` works as a table key. A `Pagina` wraps **only the resource name** and re-resolves the
live menu on every call, so a stashed one goes `:exists() == false` the moment the action is revoked — see
[snapshots vs handles](conventions.md#snapshots-vs-handles).

## The key is a string, and it splits by shape

| Key | Meaning |
|---|---|
| contains a `/` — `"paginae/act/dig"` | a **resource name**: the identity |
| anything else — `"Dig"` | a **display name**: a search convenience |

The two forms are **not equals**, and the split is by shape, not by fallback — a resource name never scans
display names, and a display name never hijacks a resource lookup.

- `:res()` is the **identity**. It is the intern key, it is known as soon as the resource is named, and it
  is the same string [`slot:set(res)`](actionbar.md#write-gated--requires-the-actions-permission) takes.
- A **display name** needs the resource fully *loaded*, and it is **not unique** — several actions can
  share one. The first match in catalogue order wins. Use it to explore, use `:res()` to address.

A miss is plain **`nil`**, both forms: "not in the menu" means "you do not have that action". (Unlike
[`hafen.kin(id)`](kin.md), whose ids persist — there is nothing here to hand back a handle *for*.) A stashed
handle still answers `:exists()` after the entry goes away.

**There are no positions to address.** The catalogue grows every time you discover something, so an array
position is an artefact of one call's ordering, not an index — `hafen.menugrid(1)` **raises an error**
pointing at the two name forms. The array is 1-based only so `ipairs` works.

> **The catalogue fills in.** Names come from the pagina's resource, and resources resolve asynchronously,
> so a scan run right at `OnEnterWorld` is often **short** — entries that have not resolved yet are simply
> absent, and they appear a fraction of a second later. Nothing ever comes back half-read: every
> resource-backed reader answers `nil` rather than a partial value. Scan on a timer (or re-scan) if you need
> the complete set, and expect it to keep growing as you play.

There is no `MenuChanged` event — poll `hafen.menugrid()` on `OnUpdate` or a timer if you must track it.

## Read

`find`, `roots` and `list` are called on the catalogue (`hafen.menugrid():find(...)`, or keep it in a
variable); the rest are called on a `Pagina`.

| Method | Returns | Description |
|---|---|---|
| `hafen.menugrid():find(text)` | `Pagina[]` | every entry whose **display name** contains `text`, case-insensitively, in catalogue order |
| `hafen.menugrid():roots()` | `Pagina[]` | the entries with no parent — what the grid shows on its root screen |
| `hafen.menugrid():list()` | [`Pagina`](types.md#pagina)`[]` | the whole catalogue as plain-table **snapshots** — the escape hatch for logging/serialising |
| `pag:res()` | string | the resource name — the identity; always answers, even for a revoked entry |
| `pag:name()` | string \| nil | the display name the grid shows |
| `pag:tooltip()` | string \| nil | the description text under the name, when the resource carries one |
| `pag:hotkey()` | string \| nil | the single letter the grid paints over the button while **Alt** is held |
| `pag:path()` | string[] \| nil | the action tokens the `"act"` message carries — **empty** for a category and for an id-only entry (see `:use()`) |
| `pag:parent()` | `Pagina` \| nil | the category this entry sits under; `nil` for a root entry |
| `pag:children()` | `Pagina[]` \| nil | the entries under this one — exactly what the grid shows after clicking it; **empty for a leaf** |
| `pag:isnew()` | boolean | is the entry still flagged as a new discovery (the grid's highlight) |
| `pag:exists()` | boolean | is the entry still in the menu |
| `pag:info()` | [`Pagina`](types.md#pagina) \| nil | a plain-table **snapshot** of the same fields |

Every reader except `:res()` and `:exists()` answers `nil` once the entry is gone — and also while its
resource is still loading.

### The tree

The catalogue is **flat and complete**: it holds every action *plus* the categories they hang under, so
`:parent()` always lands on something you can read, and `:roots()` is never empty once the menu is up.
"Is this a category" is `#pag:children() > 0`:

```lua
for _, cat in ipairs(hafen.menugrid():roots()) do
  hafen.log(cat:name() or cat:res())
  for _, child in ipairs(cat:children()) do
    hafen.log("   " .. (child:name() or child:res()))
  end
end
```

> **A category is not an action.** It has no path and sends nothing; `:use()` on one **errors** and points
> at `:children()`.

## Use

| Method | Description |
|---|---|
| `pag:use()` | perform the action — exactly a left-click on that menu button. Returns the `Pagina`, so it chains |

`use` takes **no arguments**, deliberately. The client builds the message from the modifier keys that are
*physically held* at that instant, so a `mods` parameter could only lie about them.

```lua
hafen.menugrid("Dig"):use()                              -- enters dig targeting, as clicking would
```

A ground-targeted action enters targeting mode, just as the click would; supply the target with the
[MapView action verbs](actions.md). `use` errors on a category, on an entry that is no longer in the menu,
and on one whose resource has not finished loading — check `:exists()` first if you are holding a stashed
handle.

`use` goes through the client's own button code, which sends the action **by path when it has one and by
id when it does not** — so it reaches the id-only entries (server-pushed abilities and the like) that no
path can express, and which [`hafen.act.menu(path...)`](actions.md#hafenactmenu) therefore cannot invoke.

> **Not gated yet.** `pag:use()` currently needs no permission, unlike the other write verbs. That is
> temporary: the permission model is being restructured, and this verb will join it.
