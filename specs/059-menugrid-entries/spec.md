# 059 — Entries of your own, in the action menu and on the bar

## What and why

An addon can read the action menu and fire its entries, and it cannot **put anything there**. Every addon
that wants a button of its own paints a window, so the client's one catalogue of *everything you can do*
holds only what the server granted.

This feature opens it: `hafen.menugrid():add(id)` mints an entry the addon owns, with a display name, a
`.png` icon from its own folder and a parent — and a left-click on it runs Lua. The entry is a `Pagina`
like any other, so every documented reader answers for it and an addon's actions sit in the same grid,
under the same categories, as the game's own. **And it drags onto the action bar**, where it draws and
fires exactly as a game action does.

Nothing reaches the server: a custom entry is drawn by this client and clicking it calls a function, so
**adding needs no permission**, like a HUD overlay. `pag:use()` keeps its `menugrid.use` key, for both
kinds of entry.

The bar is the one place the server owns — it stores the hotbar and echoes every assignment back — so a
custom entry does not go *into* it: the client **holds** that slot, drawing over what the server has
there and handing it back untouched when the hold ends. A hold is per character and survives a relog.

The id is addon-relative, as an asset path is: `:add("tools/dig")` on `myaddon` has the identity
`addon/myaddon/tools/dig`. Two addons cannot collide, and it carries a `/`, so `:get()` resolves it by
shape exactly as it resolves `paginae/act/dig`.

## Acceptance criteria

Each is verifiable in-game through the task's own suite.

1. `hafen.menugrid():add("dig")` returns a `Pagina` whose `:res()` is `addon/<addon id>/dig`; `:get` of
   that string is `==` it, and `:list()` and `:roots()` carry it in the grid's own sort order.
2. `:name(text)` and `:icon(image)` set what the grid draws: the entry stands on the root screen with the
   addon's PNG fitted to the cell.
3. `:parent(pag)` hangs one entry under another — a custom category, or one of the game's own;
   `:parent(nil)` is the root screen. An entry with children **is** a category: clicking it opens them.
4. A left-click fires every handler registered with `pag:on("use", fn)`, in registration order; `sub:off()`
   ends one; `pag:use()` does the same programmatically.
5. `pag:addon()` names the addon that added an entry, `nil` for the game's own; every write refuses on an
   entry this addon did not add — a server entry, or another addon's — naming which.
6. `slot:pagina(pag)` holds a bar slot for a custom entry: `slot:res()` reads its id, `slot:pagina()` is
   that same object, the slot draws the icon, `slot:use()` and the slot's key fire the handlers, and
   `ActionbarChanged` fires. Dragging one from the grid onto the bar does the same, sending nothing.
7. A hold ends on `slot:pagina(nil)`, a right-click, the entry being removed, the addon being disabled,
   or a server write to that slot — and the slot goes back to the server's own content, unchanged,
   every time.
8. A hold is remembered per character: after a relog or a `:reload`, the entry is back in its slot as soon
   as the addon adds it again.
9. `:remove(idOrPagina)`, `:reload`, disable and relogin leave the grid holding exactly the game's own
   catalogue.
10. `:add` refuses and says why: a duplicate id, an empty one, one absolute or climbing out with `..`, a
    non-string, `nil`. `:icon` refuses a path string, a font, a mesh and a data asset.
    `slot:res(customId)` raises pointing at `slot:pagina`.
11. Everything but `pag:use()` and `slot:use()` runs from an addon declaring **no permission key at all**;
    those two keep `menugrid.use` and `actionbar.use`.

## Out of scope

A hotkey bound to a custom entry (each gets an unbound `KeyBinding` id; binding one is `hafen.hotkey`) ·
reordering or hiding the game's own entries · an icon from a `.res` name · the new-discovery flash, a
cooldown meter or an overlay on a custom button · dragging a held slot to another slot.

## Docs impact

**Written:** the write half of `docs/addons/api/menugrid.md` and the bar half of `actionbar.md`.

**Derived impact set.** `grep -rln "menugrid\|action menu\|pagina\|Pagina\|actionbar\|action bar" docs/` →
23 files:

| Page | Why it is in the set |
|---|---|
| `api/types.md` | both snapshots gain the owning addon |
| `api/references.md` | "the strings are **server-published**"; Slot's "`:empty()` when cleared" |
| `api/asset.md` | the `.png` row's "Use it with" |
| `api/event.md` | what makes `ActionbarChanged` fire |
| `api/ui/custom.md` | the `{kind = "pagina", …}` drop payload |
| `guides/permissions.md` | the near side of the gate |
| `docs/client/services.md` | its two rows map reading, not extending |
| `api/README.md`, `README.md` | the namespace one-liners |

Read and **discharged with the reason**: `examples.md`, `getting-started.md`,
`api/{craft,party,flowermenu,buff,map/icons}.md`, `api/ui/style/{keys,surfaces}.md`,
`docs/client/{README,glossary,widget-input,gameui-windows}.md`.

## Context files

`/implement` loads these and nothing else.

- `src/haven/MenuGrid.java`, `LuaPagina.java` — 1, 2, 3, 4 · `LuaCollection.java` — 1, 2
- `AddonPagina.java` — 2..5 (the `Pagina`/`PagButton` pair, the icon sprite, `:add`/`:remove`)
- `AddonRegistry.java` — 4, 5 (its `teardown` is the owned-resource sweep, not `AddonManager`'s)
- `{Addon,AddonManager,Args}.java` — 1..5 · `{Subs,LuaSub,WidgetSubs}.java` — 3
- `{LuaImage,AssetApi,Controls}.java`, `src/haven/{GSprite,Inventory}.java` — 1
- `src/haven/GameUI.java` — 4, 5 (the belt half only: `BeltSlot`, `Belt`, the `setbelt` uimsgs)
- `LuaSlot.java` — 4, 5 · `StoreApi.java` — 5 (all of the above under `src/io/brodgar/addon/`)
- `docs/addons/api/{menugrid,conventions}.md` — 1, 2, 3, 4 · `asset.md` — 1
- `docs/addons/api/{types,references}.md`, `docs/addons/guides/permissions.md` — 3
- `docs/addons/api/{actionbar,event}.md` — 4, 5 · `docs/client/services.md` — 1, 4
