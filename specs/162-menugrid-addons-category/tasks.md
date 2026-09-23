# 162 — Tasks

- [x] **162.1 — The AddOns category: every addon entry hangs inside it.** New `AddonsCategory` (a
      `MenuGrid.Pagina` never put in `paginae`, one per grid, identity `addon/`, name `AddOns`, the jar's
      `icon.png` as its sprite); `AddonPagina.parent()` answers it for a `null` field; `pagina:parent(x)`
      takes `nil`, AddOns or an addon entry and refuses a game entry naming AddOns; `owned`/`inMenu` name
      AddOns as the client's own; `LuaPagina`'s custom-entry branches (`resname`, `dispname`, `tooltip`,
      `categories`, `snapshot`) gain their AddOns twin and `category()`'s refusals say "the top of AddOns";
      `BeltHold.dropped` swallows an AddOns drop; a grid left showing an empty AddOns goes back to the root
      screen. `menugrid.md` and `conventions.md` rewritten as `spec.md`'s *Docs impact* says; the
      `paginae/act/craft` example goes. Criteria 1–9.
      *Its suite* `addons/162-menugrid-addons-category.1/`, `:t162`, deferred with
      `hafen.timer():after(0, fn)`, on `hafen.session():current()`; it adds `t162/leaf`, `t162/cat` and
      `t162/child`, removes `cat` and `child` before its summary whatever failed, and leaves `leaf` (named
      `t162 leaf`) standing for the manual line — an entry is client-local and goes with `:reload`/disable,
      and a second `:t162` removes a leftover `leaf` before adding it again. Checks (one line each):
      (1) `leaf:parent():res() == "addon/"` and `:name() == "AddOns"`; (2) `menugrid:roots()` holds
      `menugrid:get("addon/")` and not `leaf`, and `get("addon/") == get("AddOns")`; (3) AddOns
      `:addon() == nil` and its `:children()` holds `leaf`; (4) `child:parent(cat)` → `child:parent() ==
      cat` and `cat:parent() == addons`; (5) `leaf:parent(nil)` and `leaf:parent(addons)` each leave
      `leaf:parent() == addons`; (6) `pcall(leaf.parent, leaf, game)` with `game` the first root that is
      not AddOns fails, and its message contains `AddOns`; (7) `pcall(addons.name, addons, "x")` fails
      naming `client's own`; (8) `menugrid:remove(cat)` → `child:parent() == addons`. Then `[summary]`.
      `[manual]`: open the action menu on its root screen and click AddOns — expect: one AddOns button with
      the blue dolmen, opening onto the suite's `t162/leaf` entry.
      <!-- extra context: none beyond spec.md's list -->
