# 059 — tasks

- [x] **059.1 — an entry of your own in the action menu, drawn with your own PNG.** Adds
      `AddonPagina` (a `MenuGrid.Pagina` subclass carrying owner, id, name and icon, with its
      `AddonPagButton` and the `GSprite` that fits a `LuaImage` into `Inventory.sqsz`), mounts
      `hafen.menugrid():add(id)` and `:remove(idOrPagina)` on the collection's unused `addMember`/
      `removeMember` seams, and the setters `pag:name(text)` and `pag:icon(image)`. Registers entries on
      the `Addon` and tears them down with it. Branches `LuaPagina`'s `resname`, `tooltip` and `path` onto
      the subclass so a custom entry names itself rather than its stand-in resource. Documents the write
      half of `menugrid.md` and the `.png` row of `asset.md`; adds the extension seam and the
      constructor trap to `docs/client/services.md`.
      *Its suite* adds one entry and asserts `:res()` is `addon/059-menugrid-entries.1/dig`, that
      `:get(that)` is `==` the object it got back, that `:list()` and `:roots()` both carry it, and that
      `:name()` reads back what it set — then `:remove`s it and asserts `:exists()` false and `:get()`
      nil. It `pcall`s and asserts the message of: a duplicate id, an empty id, `"/abs"`, `"../out"`, a
      number, `nil`, `:icon("dig.png")` (a path, not a handle) and `:icon` of a font asset. A write on a
      server entry — `hafen.menugrid():roots()[1]:name("x")` — must raise naming that the entry is the
      client's.
      `[manual]`: open the action menu — expect your PNG on the root screen, tooltip "Auto-dig".

- [x] **059.2 — the tree: a category is an entry that has children.** Adds `pag:parent(pagOrNil)`, the
      cycle refusal, and the relayout through `MenuGrid.change`. A custom entry may hang under a custom
      entry or under one of the game's own categories; `:parent(nil)` is the root screen. Documents the
      tree half of `menugrid.md`.
      *Its suite* builds `tools` and `tools/dig`, sets the second's parent to the first, and asserts
      `child:parent() == cat`, that `cat:children()` holds exactly the child, that `:roots()` carries the
      category and **not** the child, and that `:parent(nil)` puts the child back in `:roots()`. It then
      parents the child under `hafen.menugrid():roots()[1]` — a category of the game's own — and asserts
      `:children()` there carries it, which is the claim that the two kinds share one tree. It `pcall`s:
      `pag:parent(pag)` (a self-cycle) and a two-step cycle, each of which must raise rather than hang;
      `:parent` on a server entry; `:parent(7)`.
      `[manual]`: click your category in the menu — expect its children, and Back returning to the root.

- [ ] **059.3 — the click runs your Lua.** Adds `pag:on("use", fn)` over a `Subs` on the `AddonPagina`,
      returning a `Sub` with `:off()`, fired from `AddonPagButton.use(Interaction)`; adds
      `pag:tooltip(text)` and the reader `pag:addon()`. Writes the rest of `menugrid.md` and **discharges
      the menu half of the impact set**: `types.md`, `references.md`, `guides/permissions.md`,
      `ui/custom.md`, both `README.md`s, and every page the spec listed for discharge.
      *Its suite* registers two handlers on one entry, calls `pag:use()` and asserts both ran **in
      registration order** — the ordering is the claim, since one handler proves nothing about a list —
      then `sub:off()`s the first and asserts only the second runs on a second `:use()`. It asserts
      `pag:addon()` is its own id and that `hafen.menugrid():roots()[1]:addon()` is nil, and that
      `:tooltip()` reads back. It `pcall`s `pag:on("clicked", fn)`, whose error must name `use`. Its
      manifest declares `menugrid.use`, and a second entry left without a handler must not raise on
      `:use()`.
      `[manual]`: left-click the button in the menu — expect one line in the log, from a real click.

- [ ] **059.4 — drag it onto the action bar.** Adds `BeltHold` (the held slots, the displaced
      `BeltSlot`s, `ActionbarChanged` on both edges) and `slot:pagina(pagOrNil)`; makes `slot:res(customId)`
      raise pointing at it. Adds the two `// addon:` hooks in `GameUI`: `Belt.dropthing` routing an
      addon-owned Pagina to the layer, and the `setbelt`/`setbelt2` arms ending a hold on the slot the
      server just wrote. Documents the bar half of `actionbar.md` and `event.md`.
      *Its suite* takes slot `143`, records `slot:res()`, holds it, and asserts `slot:res()` is the entry's
      id, `slot:pagina()` is `==` the entry, `:empty()` false and that `ActionbarChanged` fired for it —
      then `slot:pagina(nil)` and asserts the recorded content is back, the claim that a hold restores
      rather than clears. It `pcall`s `slot:pagina` of a server entry, of a number, and
      `slot:res("addon/059-menugrid-entries.4/dig")`, whose error must name `:pagina`. It releases every
      hold it took before printing the summary.
      `[manual]`: drag the button from the menu onto the bar and press its key — expect the log line; then
      right-click the slot to bring back what was there.

- [ ] **059.5 — the slot stays yours across a relog.** Persists placements per character in a
      layer-owned file beside `savedata/<genus>_<char>/`, keyed by the entry's id, written on change and
      re-applied whenever an entry with that id is added — so at login the addon's own `EnterWorld` add
      restores it, after the server's belt burst. A hold released by hand is **forgotten**; one whose
      entry merely went away is **remembered**. Finishes `actionbar.md`.
      *Its suite* holds slot `143`, removes the entry and re-adds it, and asserts it is back in that slot
      with no second `:pagina` call — the re-apply path, the one a login runs. It then `slot:pagina(nil)`,
      removes and re-adds, and asserts the slot is **not** taken again: that the two ways a hold ends are
      remembered differently is the whole claim. It leaves one entry held in `143` for the manual line,
      and says so.
      `[manual]`: relog and run `:t059-5` again — expect the button already in slot 143, and the [pass]
      saying so.
