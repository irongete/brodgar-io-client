# 023-menugrid-oop — Tasks

<!-- One task = one session. Each is self-contained and verifiable on its own. -->

- [x] **023.1 — `LuaPagina`: the entity, the catalogue, the lookup**
      `src/io/brodgar/addon/LuaPagina.java` (new) — intern `Cache` keyed by resource name, metatable,
      the reads `:res :name :path :parent :children :tooltip :hotkey :isnew :exists :info`, the
      collection (`:find` / `:roots` / `:list`) and the callable factory with the shape-based key
      dispatch (`/` ⇒ res, else display name; a number errors; a miss ⇒ `nil`). `+1` cache field on
      `Addon`, `+1` install line in `AddonManager.installHafen`. No `haven` edit.
      **Verify** (`:lua`): `#hafen.menugrid()` is in the hundreds; `local a = hafen.menugrid()[1]`
      then `a == hafen.menugrid(a:res()) and a == hafen.menugrid(a:name())`; `"nope/nope"` and
      `"Nope"` → `nil`; `hafen.menugrid(1)` errors; `a:parent():name()` names the category the button
      really sits under; `cat:children()` matches what that category shows in the grid; `:hotkey()`
      matches the letter painted under Alt; `:tooltip()` the pagina description. Re-run right at
      `OnEnterWorld` to see the catalogue fill in rather than error.

- [x] **023.2 — `:use()`**
      The verb on the Pagina object, driving `PagButton.use(new MenuGrid.Interaction(1, 0))` — **no
      arguments**: that method ignores `Interaction.modflags` and builds the message from
      `ui.modflags()`, so a `mods` parameter could not mean anything. A category errors and points at
      `:children()`. Demo in `addons/walker/main.lua`: `:walker menugrid <name>`.
      **Verify**: `hafen.menugrid("Dig"):use()` enters dig targeting exactly as clicking the button
      does; an ability with no path (id-only pagina) fires too; `:use()` on a category errors.
      Ungated on purpose — permissions are a later plan's business.
      *extra context*: [`learnings/actions-gated.md:149`](../learnings/actions-gated.md) — the
      wrap-not-reimplement precedent for pagina messages; [`Makewindow.java:375`](src/haven/Makewindow.java:375)
      — the client calling `PagButton.use` from a non-grid widget.

- [ ] **023.3 — Docs, harness, coverage toll**
      `docs/addons/api/menugrid.md` (new page: the two key forms and why they are not equals, the
      `Loading`/fill-in behaviour, the tree, `:use`), its `api/README.md` index line and the
      `docs/addons/README.md` "API at a glance" row, the `Pagina` type in `types.md`, a cross-ref
      from `actions.md`. `addons/hello/main.lua` prints a menu summary and re-checks the read
      contract each login. *(Coverage toll already paid at 023.1's `/end`: the MenuGrid/paginae row +
      gotchas are in `specs/codebase/services.md`, the A12 entries in `learnings/gap-subsystems.md`;
      extend only with what `:use()` teaches.)*
      **Verify**: one login — `hello` prints the summary and its contract check passes, and the
      **full prior regression still passes**; `rm -rf build/classes && ant hafen-client` →
      `BUILD SUCCESSFUL` (no incremental false-green).
