# 059 — plan

## Approach

**A custom entry is a `MenuGrid.Pagina` subclass, and the grid never learns it is different.** The engine
reaches everything about an entry through two virtual methods — `Pagina.button()` and the `PagButton` it
returns — so a subclass pair is the whole seam. `MenuGrid.cons` walks `paginae` plus the parent closure and
`MenuGrid.use` decides "category" by `cons(pag, sub).size() > 0`, both of which our overrides answer.
Nothing in `MenuGrid` is edited.

`AddonPagina extends MenuGrid.Pagina` **carries every piece of state**: owner addon, id, name, tooltip,
icon, parent, and the `Subs` its `on("use", fn)` fills. Its nested `AddonPagButton extends
MenuGrid.PagButton` overrides `name()`, `sortkey()`, `parent()`, `spr()`, `binding()`, `info()` and
`use(Interaction)` — each reading `pag`, never a field of its own (see the gotchas). `spr()` returns a
small `GSprite` drawing `LuaImage.stex` fitted to `Inventory.sqsz`.

`hafen.menugrid()` is already a `LuaCollection`, whose `Source` carries unused `addMember`/`removeMember`
seams; `:add(id)` mints an `AddonPagina`, registers it on the `Addon`, inserts it into `MenuGrid.paginae`
and relayouts. The setters are Lua-side verbs on the existing Pagina metatable, refusing on any entry whose
owner is not the calling addon.

**On the bar, the slot is held rather than assigned.** `GameUI.PagBeltSlot` already draws with
`pag.button().spr()` and fires with `pag.scm.use(pag.button(), …)`, so writing one into `GameUI.belt[n]`
gives icon and click for free. The layer remembers what it displaced and restores it when the hold ends.
Two `// addon:` hooks in `GameUI` are the whole core edit: `Belt.dropthing` routes an addon-owned Pagina to
the layer instead of `wdgmsg("setbelt", …)`, and the `setbelt`/`setbelt2` uimsg arms tell the layer the
server has written that slot, which ends any hold there. Placements persist per character in a
layer-owned file beside `savedata/<genus>_<char>/`, keyed by the entry's id, and are re-applied when an
entry with that id is added — which at login is after the server's belt burst, so the burst cannot race it.

## Files to create and modify

**Create** — `src/io/brodgar/addon/AddonPagina.java` (the `Pagina`/`PagButton` pair and the icon sprite),
`src/io/brodgar/addon/BeltHold.java` (the per-slot holds, the displaced originals, the persisted
placements), `addons/059-menugrid-entries.{1..5}/`.

**Modify** — `src/haven/GameUI.java` (the two hooks); `LuaPagina.java` (the write verbs, `:addon()`,
ownership refusals, and the `resname`/`tooltip`/`path` branches for a custom entry); `LuaSlot.java`
(`:pagina`, and `:res(name)` refusing a custom id); `Addon.java` + `AddonManager.java` (the registry and
its teardown, the re-apply at `EnterWorld`); `StoreApi.java` (the layer-owned file's read/write).

**Docs** — `docs/addons/api/menugrid.md` and `actionbar.md` (the write halves); `types.md`,
`references.md`, `asset.md`, `event.md`, `ui/custom.md`, `guides/permissions.md`, `api/README.md`,
`docs/addons/README.md`; `docs/client/services.md` (the extension seam and its ctor trap, per §12 rule 5 —
the action-menu row maps reading the grid, and this feature had to read how one is *made*).

## Risks and gotchas

- **`PagButton`'s constructor calls the virtual `binding()`.** `PagButton(Pagina)` assigns `this.res =
  pag.res()` and then `this.bind = binding()`, so an override runs before the subclass's own fields exist.
  Put all state on `AddonPagina`; the button reads `pag` only. Java will not warn you.
- **Stock `binding()` reaches `hotkey()` → `act()` → `res.flayer(Resource.action)`.** With no action layer
  that is an NPE inside the constructor, so `binding()` must be overridden — to `KeyBinding.get("scm/" +
  id, KeyMatch.nil)`, which also gives each entry an unbound, remappable id.
- **`Pagina.button` is private**, so `MenuGrid`'s own `next`/`bk` trick of pre-assigning it is unavailable:
  override `button()` and cache in the subclass.
- **The backing `Resource` is a stand-in.** `Resource`'s constructor is private and pool-managed, so
  `AddonPagina` passes `Resource.local().loadwait("gfx/hud/sc-next")` and `PagButton.res` names *that*.
  Nothing may key on it: `LuaPagina.resname()` and `tooltip()` currently read `p.res()`/`b.res.layer(…)`
  and must branch on `AddonPagina` first, and `path()` must answer an empty array rather than fall through
  its `catch(RuntimeException)`.
- **Relayout has one public door.** `updlayout()` and `recons` are private; `MenuGrid.change(scm.cur)` is
  the public call that rebuilds `curbtns` and `layout`. It resets `curoff`, so an add while the player is
  on page 2 of a category jumps them to page 1.
- **A category need not be in `paginae`** — `cons` reaches parents through `parent()` only — but a **cycle
  in `:parent()` makes `cons` loop forever**. `:parent(p)` must walk up and refuse one.
- **`Loading` never escapes.** Every existing reader in `LuaPagina` is guarded to `nil`; a custom entry
  never throws `Loading`, so its readers must answer immediately rather than inherit a guard that hides a
  real error.
- **The drop payload.** `MenuGrid.mouseup` hands `dragging` to `DropTarget.dropthing`, and the addon-side
  payload builder reads `Pagina.res().name` — the stand-in again. It must report the custom id.
- **Sizes.** `MenuGrid.draw` reclips to `spr.sz()` in device pixels, `Inventory.sqsz` is
  `UI.scale(32,32).add(1,1)`, and `LuaImage.stex` is already `UI.scale(sz)`: fit into the cell, do not
  scale twice.
- **The server still owns `belt[n]`.** Writing a `PagBeltSlot` there overwrites what the server put in the
  array, so the displaced `BeltSlot` must be kept or right-click leaves an empty slot instead of the old
  action.

## Discarded alternatives

- **A synthetic `haven.Resource` per entry** — its constructor is private and every instance is
  pool-managed, so an orphan would answer `res.name` to caches it was never registered in (keybindings,
  the belt, tooltips) and no pool could ever load it back.
- **A second constructor, `:addCategory(id)`** — a category is already *an entry that has children*, both
  in the docs and in `MenuGrid.use`'s own test, and a declared-but-empty category could then exist.
- **`pag:action(fn)` as a setter** — one handler, no way to unsubscribe, and a second spelling for the
  notification verb the rest of the API already speaks.
- **Overloading `slot:res(name)` with a custom id** — the two writes differ in permission, in timing and
  in who owns the result; one verb answering two ways by the shape of a string is the silent split this
  grammar exists to delete.
- **Sending `setbelt` with the custom id and letting the server drop it** — the drag would do nothing at
  all, and the failure would read as an addon bug rather than as a rule.
- **Suppressing the drag of a custom entry** (a `draggable()` flag on `Pagina`) — dropping one on an
  addon's own widget is a real gesture, and once the bar accepts it there is nothing left to suppress.
- **Storing the placement in the addon's saved variables** — the drag is the player's gesture and happens
  outside the addon's code, so every addon would have to implement persistence or the button would vanish
  on relog.
- **A bare id with no `addon/<addon id>/` prefix** — two addons would collide in one namespace, and
  `:get()` tells a resource name from a display name by the presence of a `/`.
- **An options table on `:add`** — a thing you build is constructed bare and configured by chained
  setters, so a setter can refuse what it cannot do at the line that wrote it.
- **`pag:use()` unprotected for a custom entry** — any addon can reach any entry by id, so the permission
  answer must not depend on who owns the entry. One door, one key.
