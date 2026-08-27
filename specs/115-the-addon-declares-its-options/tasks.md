# 115 — Tasks

- [x] **115.1 — The Options window is a menu and two tabs.** `OptWnd.main` becomes the six-entry game
      menu — `Options`, `AddOns`, a gap, `Switch character`, `Logout`, `Close` — carrying no caption; the
      `gopts` block keeps the two session entries and `Visit store`. A new `SettingsPanel` holds a `Tabs`
      strip over an `SListBox` and a panel holder: the Game tab's rows are the seven client panels, and
      selecting one swaps its panel into the holder. Every `PButton(…, "Back", 27, …)` goes, in all seven.
      `chpanel` writes the caption through `chcap` behind an equality test, and
      `Window.DefaultDeco.drawframe` gets the `cap != null` guard without which a captionless window throws
      on its first frame. The AddOns tab is built empty here and filled by 115.3, and `AddOns` on the menu
      opens today's `AddonPanel` unchanged — only its entry point moves. `docs/client/ui-panels.md` is
      new: `Tabs` and `OptWnd`'s panel model, moved off `ui-controls.md`. It sweeps the impact set:
      `Options ▸ <panel>` → `Options ▸ Game ▸ <panel>`, and the three `Options ▸ AddOns` sites, which now
      name the manager reached from the menu.
      *Its suite* declares nothing and drives `hafen.ui()` over the open window, reading captions with
      `w:text()` and selecting rows with `list:value(row)` — **not** `:rows()`, which reads `nil` on one of
      the client's own lists. It asserts the menu holds those six labels in that order; the window's title
      is empty on the menu and `Options` on the tabbed view; no widget under the window is a button
      captioned `Back`; and selecting each of the seven Game rows puts a different, non-empty panel in the
      holder. `pcall` a selection past the last row and assert it failed.
      `[manual]`: press `Ctrl+O` — expect a menu of six entries with no title bar text.
      `[manual]`: click `Options`, then `Video settings` — expect the list on the left and that panel on
      the right, with no Back button anywhere.
      `[manual]`: click `AddOns` on the menu — expect the addon manager with its checkboxes, unchanged.
      `[manual]`: open Options from the login screen — expect the same menu without `Switch character`
      and `Logout`.
      <!-- extra context: src/haven/Window.java (the drawframe guard), src/haven/Scrollport.java -->

- [x] **115.2 — An addon declares an option.** `hafen.client():options():addon()` — a per-addon singleton
      wired into `OptionsHandle` and held on `Addon`, closed like its five siblings. Six typed builders, all
      taking `:label` and `:tooltip` and all dispatched by `:add()`. Four carry a value and a
      `:default` — `boolean`, `number` (`:range(lo, hi)`), `choice` (`:choices(t)`) and `text` — and hand
      back a live interned Option with `value()`/`value(v)`, `on("Changed", fn)`, `name()`, `default()` and
      `info()`. Two carry none: `button` takes `:press(fn)`, `label` takes `:text(s)`, neither persists.
      `opts:option()` is the collection of this addon's own. Values persist
      through `Utils.setpref*` under `addon/<addonid>/opt/<name>`. Unprotected: an addon's own option
      writes nothing outside itself. `docs/addons/api/client/addon.md` is the reference, listed in
      `api/README.md` and named in `api/client/README.md`'s handle table.
      *Its suite* declares one row of each of the six types and asserts `opts:option():count()` is six. For
      the four carrying a value it asserts each reads its declared default, then writes each and asserts the
      read that follows answers what was written; `Changed` fires on a Lua write carrying the new value and
      does not fire on a write of the value already held. On the button and the label it asserts `value()`
      is refused, naming what each does carry. Five refusals, each asserted to have failed **and** to name
      its fix: an `:add()` with no `:default`, a second under a name already declared, a `:range` default
      outside its own bounds, a setter called after `:add()`, and `:press` on a row that is not a button.
      `[manual]`: reload and run `:t115` again — expect every value line to read what the previous run wrote,
      not the declared defaults.

- [x] **115.3 — The AddOns tab draws what was declared.** `AddonManager.describeOptions()` returns
      `OptionGroup`/`OptionEntry` beside `describeKeyBinds`, one group per addon with at least one live
      declared option, in registration order. The AddOns tab's `SListBox` is that list, re-read on `show()`;
      selecting a row builds `io.brodgar.addon.ui.AddonOptionsPanel`, which draws one row per option in
      declaration order — `CheckBox`, `HSlider`, `SDropBox`, `TextEntry`, `Button`, `Label` — reading each value
      every frame and
      writing through the same setter Lua writes through. Each control's programmatic write takes its own
      silent path: `ACheckBox.a`, `HSlider.val`, `TextEntry.rsettext`, `SDropBox.change` with the notify
      wrapper skipped. `docs/addons/guides/hotkeys-and-commands.md` gains the section for the third way a
      user drives an addon by hand.
      *Its suite* declares a row of all six types under its own id, then asserts through the API that a
      write from Lua is what an open panel would read: it reads each control's value back after writing it
      and asserts `Changed` fired once, not twice, proving the panel's write path and Lua's are one and did
      not re-enter. It asserts an addon that declared nothing is absent by declaring none under a second
      name and asserting no group carries it.
      `[manual]`: open Options ▸ AddOns — expect this suite's addon listed and no addon that declared no
      option.
      `[manual]`: with the panel open, move its slider — expect the number the next `:t115` run prints to be
      the one you left it on.

- [ ] **115.4 — An addon's options page scrolls.** `AddonOptionsPanel` builds its rows into a
      `Scrollport` rather than into itself: `OptWnd.BindingPanel` one panel up is the shape, and it is
      there for the same reason — a panel whose row count an addon chooses is the one panel in this
      window nothing bounds. The port is the Game list's own height and wide enough for the two columns
      plus `Scrollbar.width`; the rows go into a container inside `cont` whose `cresize` repacks it and
      re-runs `Scrollcont.update()`, because `bar.max` is computed only on `add` and a label row's line
      is the addon's to rewrite. The page's own box is then the port's, so the window stops growing with
      the rows and the `cresize` override that followed them goes. `docs/addons/api/client/addon.md`
      says the page scrolls, which is what makes "declare as many rows as you like" true.
      *Its suite* declares forty rows of mixed kinds under its own id and asserts through `hafen.ui()`
      that the page is shorter than its own row column — the one claim that needs no measurement of its
      own: the page holds one `Scrollport`, the column inside it is taller than the port, the page is
      shorter than the column, and the port's scrollbar reads a `:range()` with room in it. It then
      writes the fortieth row from Lua and asserts its control reads the write back, a row nothing is
      drawing answering exactly as the first one does.
      `[manual]`: open Options ▸ AddOns and pick this suite — expect a scrollbar down the right of the rows.
      `[manual]`: drag that scrollbar to the bottom — expect row 40 whole, and the window no taller than
      it is on the Game tab.
      <!-- extra context: src/haven/Scrollport.java, src/haven/Scrollbar.java, src/haven/OptWnd.java (BindingPanel) -->
