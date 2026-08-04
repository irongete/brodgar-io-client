# hafen.menugrid: the action menu

Read and invoke the **action menu**, the grid in the corner of the HUD. It is the client's catalogue of
everything your character can *do*: every unlocked action, or "pagina", arranged in a category tree.
`hafen.menugrid` is a function, and the arity is the verb.

```lua
local dig = hafen.menugrid("Dig")             -- or "paginae/act/dig" (a "/" means a resource name)
hafen.log():write(dig:parent():name() .. " > " .. dig:name())    -- "Dig" lives under a category
dig:use()                                                -- fire it: exactly a click on that button
```

| Call | Returns |
|---|---|
| `hafen.menugrid()` | the whole **catalogue** — an array of `Pagina` objects in the grid's own sort order |
| `hafen.menugrid(key)` | the one `Pagina` that `key` names, else `nil` |

Pagina objects are **interned per addon**, so `hafen.menugrid("Dig") == hafen.menugrid("paginae/act/dig")`
and `seen[pag] = true` works as a table key. A `Pagina` wraps only the resource name and re-resolves the
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
  it is the same string [`slot:set`](actionbar.md#write-gated-actions) takes.
- A **display name** needs the resource fully loaded, and it is **not unique** — several actions can
  share one, and the first match in catalogue order wins. Use it to explore, and `:res()` to address.

A miss is plain `nil` for both forms: "not in the menu" means "you do not have that action". A stashed
handle still answers `:exists()` after the entry goes away.

**There are no positions to address.** The catalogue grows every time you discover something, so an
array position is an artefact of one call's ordering rather than an index — `hafen.menugrid(1)` raises
an error pointing at the two name forms. The array is 1-based only so that `ipairs` works.

> **The catalogue fills in.** Names come from the pagina's resource and resources resolve
> asynchronously, so a scan run right at `OnEnterWorld` is often short: entries that have not resolved
> are simply absent, and they appear a fraction of a second later. Nothing ever comes back half-read —
> every resource-backed reader answers `nil` rather than a partial value. Scan on a timer if you need
> the complete set, and expect it to keep growing as you play.

There is no `MenuChanged` event — poll `hafen.menugrid()` on `OnUpdate` or a [timer](timer.md) if you
must track it.

## Read

`find`, `roots` and `list` are called on the catalogue — `hafen.menugrid():find(...)`, or keep it in a
variable. The rest are called on a `Pagina`.

| Method | Returns | Description |
|---|---|---|
| `hafen.menugrid():find(text)` | `Pagina[]` | every entry whose **display name** contains `text`, case-insensitively, in catalogue order |
| `hafen.menugrid():roots()` | `Pagina[]` | the entries with no parent: what the grid shows on its root screen |
| `hafen.menugrid():list()` | [`Pagina`](types.md#pagina)`[]` | the whole catalogue as plain-table **snapshots**, the escape hatch for logging and serialising |
| `pag:res()` | string | the resource name, the identity — always answers, even for a revoked entry |
| `pag:name()` | string \| nil | the display name the grid shows |
| `pag:tooltip()` | string \| nil | the description under the name, when the resource carries one |
| `pag:hotkey()` | string \| nil | the letter the grid paints over the button while Alt is held |
| `pag:path()` | string[] \| nil | the action tokens the message carries; **empty** for a category and for an id-only entry |
| `pag:parent()` | `Pagina` \| nil | the category this entry sits under; `nil` for a root entry |
| `pag:children()` | `Pagina[]` \| nil | the entries under this one, exactly what the grid shows after clicking it; empty for a leaf |
| `pag:isnew()` | boolean | whether the entry is still flagged as a new discovery, the grid's highlight |
| `pag:exists()` | boolean | whether the entry is still in the menu |
| `pag:info()` | [`Pagina`](types.md#pagina) \| nil | a plain-table **snapshot** of the same fields |

Every reader except `:res()` and `:exists()` answers `nil` once the entry is gone, and also while its
resource is still loading. No reader throws, and none is gated.

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

## Use (ungated)

| Method | Description |
|---|---|
| `pag:use()` | perform the action, exactly as a left-click on that menu button does |

It returns the `Pagina`, so it chains. `use` takes **no arguments**, deliberately: the client builds
the message from the modifier keys physically held at that instant, so a `mods` parameter could only
lie about them.

> **`pag:use()` performs a real action and needs no permission.** Unlike the [`hafen.act`](act.md)
> verbs, it is not gated — any enabled addon can call it.

`use` raises an error on a category, on an entry that is no longer in the menu, and on one whose
resource has not finished loading; check `:exists()` first if you are holding a stashed handle. A
ground-targeted action enters targeting mode, just as the click would, and the target is supplied with
[`hafen.act`](act.md)'s map verbs.

Because it goes through the client's own button code, `use` sends the action **by path when it has one
and by id when it does not** — so it reaches the id-only entries, such as server-pushed abilities, that
no path can express and that [`hafen.act():menu`](act.md) therefore cannot invoke.

## See also

- [`hafen.act`](act.md) — the path-based door to the same menu, and the verbs that target an action
- [`hafen.actionbar`](actionbar.md) — putting one of these resource names on the hotbar
- [`Pagina`](types.md#pagina) — the snapshot shape `:info()` and `:list()` return
- [`hafen.craft`](craft.md) — the window a recipe action opens
