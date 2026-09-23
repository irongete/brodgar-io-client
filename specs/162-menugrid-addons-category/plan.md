# 162 — Plan

## Approach

A **second `MenuGrid.Pagina` subclass**, `AddonsCategory`, stands for AddOns. `MenuGrid` needs no edit:
`cons` reaches a category through `parent()` alone (`docs/client/services.md`, *Action menu*), so a pagina
that is **never put in `paginae`** and is the parent every addon entry answers is drawn on the root screen
exactly while something hangs under it, and `MenuGrid.use` opens it because `cons(pag, sub)` finds children.

The whole redirection is one line in the read: `AddonPagina.parent()` answers `parent != null ? parent :
AddonsCategory.of(scm)`. The field keeps `null` meaning "the top of AddOns", so `detach` (which re-roots a
removed category's children by nulling their field) and `teardownEntries` are unchanged, and the cycle walk
in `AddonPagina.parent(par)` still ends — at AddOns, whose `parent()` is `null`.

## Files to create/modify

- **Create `src/io/brodgar/addon/AddonsCategory.java`** — `final class AddonsCategory extends MenuGrid.Pagina`.
  - `static final String ID = "addon/"`, `static final String NAME = "AddOns"`.
  - `static AddonsCategory of(MenuGrid scm)` over a `WeakHashMap<MenuGrid, AddonsCategory>`, synchronized:
    one per grid, so `cons`'s identity comparison `parent == p` holds. A relog's new grid mints its own.
  - Constructed `super(scm, ID, AddonPagina.standin())` (make `standin()` package-visible).
  - `button()` caches its own `PagButton` subclass (`Pagina.button` is private — the `AddonPagina` trick):
    `name()` → `NAME`, `sortkey()` → `NAME`, `parent()` → `null`, `binding()` →
    `KeyBinding.get(AddonPagina.bindId(ID), KeyMatch.nil)` (read `pag` only: it runs inside the
    constructor), `hotkey()` → `KeyMatch.nil`, `spr()` → the dolmen `Icon`, `info()` → a fresh list with one
    `ItemInfo.Pagina` line ("What your addons add to the menu"), `use(iact)` → nothing.
  - The dolmen: a static lazily loaded `TexI` from `haven.Client.class.getResourceAsStream("icon.png")`
    through `ImageIO` — the file `build.xml` copies beside the classes. 64×64, so `Icon.fit` scales it to
    the cell.
- **`AddonPagina.java`**
  - `Icon` takes a `Tex` plus an optional `LuaImage` for the `dead` guard (`Icon(owner, LuaImage)` keeps
    working; add `Icon(owner, Tex)`).
  - `parent()` → the one-line redirection above; `AddonPagButton.parent()` already reads it.
  - `parent(MenuGrid.Pagina par)`: `par instanceof AddonsCategory` → treat as `null`; `par != null &&
    !(par instanceof AddonPagina)` → `LuaError("pagina:parent(pagOrNil): " + LuaPagina.label(par) + " is the
    client's own entry — an addon's entries all hang inside the AddOns category: nil for its top, or one of
    the entries under it")`; the rest is the existing cycle walk.
  - `owned(...)` and `inMenu(...)`: `res.equals(AddonsCategory.ID)` is caught **before** the
    `startsWith(PREFIX)` branch (which would call it "another addon") — "\"addon/\" is the AddOns category,
    the client's own entry: …". `inMenu`'s message says a slot holds an entry an addon added.
  - `relayout()`: after `scm.change(scm.cur)` it is enough to ask, when `scm.cur instanceof AddonsCategory`,
    whether any `AddonPagina` is left in `scm.paginae`; if none, `scm.change(null)`. `teardownEntries` does
    the same per grid. (A private helper `relayout(MenuGrid)` serves both.)
- **`LuaPagina.java`** — every branch that already reads `instanceof AddonPagina` for a custom entry gets
  its `AddonsCategory` twin: `resname` → `ID`, `dispname` → `NAME`, `tooltip` → the tip line,
  `categories` → an empty table, `snapshot` → `path = {}` and no `addon`. `category()`'s two refusals say
  "or nil for the top of AddOns" instead of "the root screen". `:addon()`, `:icon()`, `:unseen()`,
  `:children()`, `:exists()` need nothing (`live` resolves it through the closure).
- **`BeltHold.java`** — `dropped`: `pag instanceof AddonsCategory` → `return true` (swallowed, nothing
  sent; the stock body would `wdgmsg("setbelt", slot, "pag", "addon/")`).
- **`docs/addons/api/menugrid.md`** — as `spec.md`'s *Docs impact*. The category section's example drops
  the `paginae/act/craft` line; a rule *Everything inside AddOns* (AddOns: identity `addon/`, name `AddOns`,
  client-owned, drawn while an addon entry stands in the menu); the refusal table gains "a game entry as
  parent — it is the client's own; your entries hang inside AddOns"; `nil` means the top of AddOns. Keep it
  under 300 lines.
- **`docs/addons/api/conventions.md`** — the `nil` table row: "The top of the AddOns category |
  `pagina:parent(nil)`".
- **Create `addons/162-menugrid-addons-category.1/`** — the suite (`tasks.md`).

## Risks & gotchas

- **`PagButton(Pagina)` calls `binding()` from its constructor** before any subclass field exists, and the
  stock one throws on the stand-in (`gfx/hud/sc-next` has no `action` layer). Override it reading `pag` only.
- **`PagButton.parent()` memoises**; the override must answer live (`null`, constant, is live enough).
- **`act()` on the stand-in throws**: any reader that reaches `b.act()` (`categories`, `snapshot`'s path)
  must branch before it, as it does for `AddonPagina`.
- **`KeyBinding` id `scm/addon/`** never collides with `ClientDb.forget`'s sweep of
  `scm/addon/<id>/…`: that prefix always carries an addon id and a slash after it. Never `unregister` it.
- **`cons`'s `tnew` walk** reaches AddOns (it is not in `pmap`, so its `tnew` is never reset); harmless,
  because an addon entry's `anew` is always `0` and nothing else can sit under it.
- **One grid, one AddOns**: minting per call would break `parent == p` in `cons` and in
  `LuaPagina.childrenOf`, and draw AddOns once per entry.
- `LuaPagina.catalogue` dedupes by resname; AddOns enters it once through the closure.
- The maintainer's addons in the sibling repository that `:add` (paint, inspector-gadget,
  materials-preview) never pass a game category to `:parent`; they move under AddOns with no edit.
- `menugrid.md` says `:remove` on another's entry raises; the code is inert. Not this feature's surface —
  reported at the close, not fixed here.

## Discarded alternatives

- **Putting AddOns in `paginae`** — it would be drawn on a menu with no addon entry in it, and would need
  its own lifecycle across relogs; as a parent only, it appears and disappears with its children for free.
- **Allowing a game category as an explicit parent** — the point of the feature is that the game's menu and
  the addons' never mix; an exception would put addon buttons back among the game's.
- **Refusing AddOns itself as a parent (only `nil`)** — `a:parent(b:parent())` would fail for a top-level
  `b`; a read that cannot be written back is half a pair.
- **An identity like `brodgar/addons` or `paginae/…`** — `addon/` is the namespace every addon identity
  already sits under, and nothing may pretend to be a server resource name.
- **A per-addon sub-category minted by the client** — addons already build their own categories; a forced
  extra level costs every single-button addon a click.
- **Following `haven.icon` (dolmen or the original icon)** — the menu's AddOns is the fork's own mark, not
  the window icon's setting; the maintainer chose the dolmen.
- **Editing `MenuGrid`** — the subclass seam already carries everything, as it did for `AddonPagina`.
