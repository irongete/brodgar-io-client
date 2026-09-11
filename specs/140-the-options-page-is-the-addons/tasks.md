# 140 — tasks

- [x] **140.1 — The page is a column the addon fills.** `opts:panel(fn)` / `:panel()` / `:panel(nil)` on
      `hafen.client():options():addon()`, bumping `optionsGen`; `AddonManager.describePages()` (addons
      holding a panel, load order) in place of `describeOptions`, `OptionGroup.fn`, and `OptWnd.addonpanels`
      reading it; `AddonOptionsPanel` as the host — heading, `Scrollport` at `OptWnd.PAGE` less the
      heading, and `root`, an owned `AddonWidget` column pinned to the port's width, armed at once — with
      its `Rows`/`Row`/`layout` half deleted; `pendingPages` drained in `layerStep` through `callLua`, a dead
      root skipped, an error logged and the heading left. The six row builders keep declaring for one task.
      Docs: `addon.md` gains *The page* (when `fn` runs, what it may reach, its lifetime), `threading.md` a
      first-group row, `docs/client/ui-panels.md` the host and why the fill is deferred.
      *Its suite* registers a panel whose `fn` builds a label and a button into `root` and records what it
      was handed; `:t140` arms a thirty-second watch and asks the maintainer to open Options ▸ AddOns ▸ its
      row. Scored as the fill lands: `root:role()` is `"column"`, `:info().owned` is `true`, `:size().w` is
      the port's width, its `:parent()` chain reaches a window titled `Options`, the button `:exists()` —
      built from `fn`, which proves it ran holding no tree — and `opts:panel()` reads the function;
      `:panel(nil)` reads `nil`, a second `:panel(g)` reads `g`. A fill that never comes fails naming the
      window that was not opened.

- [x] **140.2 — An option is the model.** `LuaOption.Kind` down to the four; `label`, `tooltip`, `text`,
      `press` and their reads gone; `info()` is `{name, type, value, default}` plus `min`/`max` or
      `choices`; `AddonOptions.Builder` keeps `default`/`range`/`choices`/`add`, and `:label`/`:tooltip`,
      `opts:button()`, `opts:label()` are `Refusal` rows naming `hafen.ui():check():text(s)` / `:tooltip(s)`,
      `hafen.ui():button()`, `hafen.ui():label()` inside `opts:panel(fn)`. `value(v)`, the store, `Changed`
      and `opts:option()` untouched. Docs: `addon.md` rewritten around the four and the Option object,
      `api/README.md:158`, `guides/hotkeys-and-commands.md:85-93`.
      *Its suite* declares one of each kind and asserts: `opt:type()` answers the four; a stored write
      reads back after `:value(v)`; `Changed` fires once for a change and not for a rewrite of the same
      value; `info()` has exactly the stated keys, `min`/`max` on the number and `choices` on the choice;
      `opts:option():count()` is four; and the four refusals — `:label("x")` on a builder, `:tooltip("x")`,
      `opts:button("b")`, `opts:label("l")` — each fail naming the control to build in the panel.

- [ ] **140.3 — `bind` is the one link.** `Binding.java`; `w:bind(opt)` / `:bind()` / `:bind(nil)` in
      `LuaWidget` on owned controls, kinds checked against the adapter, the control configured from the
      option (`range` from `lo/hi`, `rows` from `choices`) and set through the silent `value(LuaValue)`;
      the push in `Controls.fire` reading the adapter's `value()`; the pull in `LuaOption.value(v)` after
      `store()`, over live bound controls; the drop in `Owned.State.kill`. Docs: `addon.md` *Binding*,
      `writes.md` and `controls/README.md` rows.
      *Its suite* binds a check, a slider, a dropdown, a radio and an entry to matching options, then
      writes each option and reads the control's `:value()` moved; a `Changed` handler on the check counts
      `0` across those writes; the slider's `:range()` is the option's and the dropdown's `:rows()` are the
      choices; `:bind()` reads the option, `:bind(nil)` unbinds (a further write moves nothing), a second
      `bind` replaces; `slider:bind(boolopt)` fails naming the check a boolean takes; `bind` on a borrowed
      window fails naming the client. The suite's own page holds the bound check.
      `[manual]`: tick the box on the suite's page — expect: a `Changed <value>` line from the option.

- [ ] **140.4 — The six addons draw their pages.** `actionbars`, `builder-helper`, `essentials`, `hitboxes`,
      `simple-animal-radius`, `themes`: each an `opts:panel(fn)` of a column with its controls bound, `:label`
      and `:tooltip` gone, `button`/`label` rows as `hafen.ui():button()`/`:label()` in the panel, names
      unchanged so every stored value survives. `column.md`'s composed panel points at where it is
      mounted; the row in `runtime.md`.
      *Its suite* arms the same watch as 140.1, and once Options ▸ AddOns is open reads the tab's list
      through the Options window's own labels: the six names are present, in load order, and so is the
      suite's; each addon's page, opened by the maintainer within the window, fills with at least one
      control (`window[title=Options]` holds a `column` with a child). Twelve lines at most.
      `[manual]`: on *Essentials*, flip *Swimming* and press *Reload UI* — expect: the page comes back
      with it flipped.
      <!-- extra context: addons/*/main.lua for the six, and addons/essentials/README.md, which
      describes rows the panel now draws -->
