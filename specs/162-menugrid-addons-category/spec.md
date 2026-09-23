# 162 — The AddOns category in the action menu

## What & why

`session:menugrid():add(id)` puts an addon's entry straight on the action menu's root screen, beside the
game's own categories, and `pagina:parent(x)` can hang it under any entry, a game category included. With
several addons installed the root screen fills with buttons that are not the game's, and no player can tell
which are which.

The client gets **one category of its own, AddOns**, on the root screen of every character's menu. **Everything
an addon adds — an entry, a category, a subcategory — hangs somewhere inside it**, and nowhere else:

- A fresh `:add(id)` lands at the **top of AddOns**. `pagina:parent(nil)` means the top of AddOns (it no
  longer means the root screen).
- `pagina:parent(x)` accepts `nil`, the AddOns category itself (so `a:parent(b:parent())` round-trips for a
  top-level `b`), or any addon entry (every one of them already stands inside AddOns). **A game entry as
  parent is refused**, naming AddOns.
- AddOns is **the client's own entry**: identity `addon/` (the namespace every addon identity
  `addon/<id>/<rel>` already lives under, so `:get("addon/")` resolves it by the `/` shape rule), display
  name `AddOns`, the blue dolmen (the jar's `icon.png`) as its icon, fitted to the cell. `:addon()` is `nil`,
  `:icon()` is `nil` (it is not an asset of yours), `:categories()` is empty, and every write verb on it
  (`:name`, `:tooltip`, `:icon`, `:parent`, `:on`) is refused as the client's own. `menugrid:remove(addons)`
  behaves as `:remove` does on any entry the addon did not add.
- It is drawn **only while some addon entry stands in that menu**: it is a parent the tree reaches, never a
  member of the granted set, exactly as a game category is. A player viewing AddOns when its last entry
  leaves is put back on the root screen.
- Dropping AddOns on the action bar does nothing and sends nothing: the server has never heard of it.
- Removing a category of yours puts its children back at the top of AddOns.

## Acceptance criteria

Each is checked by `:t162`, the suite of task 162.1, on the character on screen.

1. A fresh `:add` hangs under AddOns: `entry:parent():res() == "addon/"` and `entry:parent():name() == "AddOns"`.
2. `:roots()` holds AddOns and does not hold the entry; `:get("addon/") == :get("AddOns")`.
3. AddOns answers `:addon() == nil`, `:exists() == true`, and its `:children()` holds the entry.
4. A category of the addon's own nests: the child's `:parent()` is that category, the category's is AddOns.
5. `entry:parent(nil)` and `entry:parent(addons)` both put the entry back at the top of AddOns.
6. `entry:parent(<a game category>)` is refused, and the refusal names AddOns.
7. A write on AddOns (`addons:name("x")`) is refused naming it as the client's own.
8. Removing a category of yours puts its child back under AddOns.
9. In the drawn grid, one AddOns button with the dolmen stands on the root screen and opens onto the addon
   entries (`[manual]`).

## Out of scope

- **Where a game action sits.** The server's own tree is untouched; nothing of the game's moves into AddOns.
- **A per-addon grouping** (a sub-category per addon, made by the client). An addon groups its own entries
  with the categories it already builds; this feature draws the one boundary between the game's menu and
  everyone else's.
- **Localising the label.** `AddOns` is a literal, as the grid's own `Back` and `More...` are.

## Docs impact

Pages written: `docs/addons/api/menugrid.md`, `docs/addons/api/conventions.md`.

Derived impact set — `grep -rn -i "root screen\|menugrid():add\|:roots()\|parent(nil)" docs --include=*.md`:

- `docs/addons/api/menugrid.md` (lines 20, 62, 75, 90, 127, 133, 139, 140) — the tree, the write intro,
  the `:parent` row, the category section, its `paginae/act/craft` example, its removal and `nil` rules.
- `docs/addons/api/conventions.md:130` — "The root screen | `pagina:parent(nil)`": the top of AddOns.
- `docs/addons/api/actionbar.md:105,148,152` — `:add` calls whose claims (the slot a placed entry returns
  to) are unaffected; no edit.
- `docs/addons/api/event/bus/lifecycle.md:45`, `docs/addons/guides/permissions.md:113` — name `:add`
  without saying where the entry stands; no edit.
- `docs/addons/api/types/ui.md#pagina` — `parent` is the parent's resource name, which is `addon/` for a
  top-level entry; true as written, no edit.
- `docs/addons/api/wound.md`, `world.md`, `ui/native.md`, `ui/edit.md` — other receivers' "root"; no edit.

`docs/client/services.md` (*Action menu (paginae)* and its gotchas) already maps every `MenuGrid` seam this
feature uses: a second `Pagina` subclass, the `parent()` closure, `change(cur)` as the relayout door. No
`docs/client/` page is written.

## Context files

- `docs/addons/api/menugrid.md` — 1
- `docs/addons/api/conventions.md` (the `nil` table around line 130) — 1
- `docs/client/services.md` (the *Action menu (paginae)* row and its gotcha list) — 1
- `src/io/brodgar/addon/AddonPagina.java` — 1
- `src/io/brodgar/addon/AddonsCategory.java` (the AddOns category) — 1
- `src/io/brodgar/addon/AddonWidget.java` (`dropDescriptor`) and `src/io/brodgar/addon/LuaSlot.java`
  (`slot:res`'s refusal) — the other two readers that branch on a custom entry — 1
- `src/io/brodgar/addon/LuaPagina.java` — 1
- `src/io/brodgar/addon/BeltHold.java` (`dropped` only) — 1
- `src/haven/MenuGrid.java` (`Pagina`, `PagButton`, `cons`, `change`, `use`) — 1
- `src/haven/Client.java` (how `icon.png` is read out of the jar) — 1
- `build.xml` (the two `<copy>` lines that put `icon.png` beside the classes) — 1
