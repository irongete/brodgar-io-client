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

- [x] **115.4 — An addon's options page scrolls.** `AddonOptionsPanel` builds its rows into a
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

- [x] **115.5 — The settings window is one box.** Picking a subject changes what is drawn and never how
      big the window is: today every holder packs to the page in it, so the window is a different width and
      a different height on each of the nine subjects, and `OptWnd.cresize` re-centres it on every swap.
      `OptWnd.PAGE` is the box every page is drawn inside — `UI.scale(new Coord(410, 410))`, declared on
      `OptWnd` itself because a non-static inner class may hold no `Coord` constant of its own at source
      1.8. `Subject.holder` is built at `PAGE` and never packed, its `cresize` override goes with the
      packing it existed for, the subject list beside it takes the page's own height, and
      `SettingsPanel.relayout()` goes entirely — one `tabs.pack()` and one `pack()` at the end of the
      constructor is the whole layout, because after this nothing in the view has a size that moves. The
      number is the measured ceiling of what the view can show, in design pixels: `VideoPanel` is the
      tallest page at 395 and an addon's page the widest at 384 — its two columns plus `Scrollbar.width` —
      and both hold at 1.0, 1.5 and 2.0 interface scale. The two pages that carry a port of their
      own then **fill** the box instead of standing in a corner of it. `AddonOptionsPanel`: `PAGEW`/`PAGEH`
      become `OptWnd.PAGE`, the port is the box less the heading above it, the heading wraps at the box's
      width so a long addon name cannot push past it, and `refit()` keeps the port where it is and only
      re-runs `Scrollcont.update()` and `bar.ch(0)`. `BindingPanel`: its port is the box less the
      `PointBind` under it, which is built before the port so it can be measured, and the rows widen with
      the port — `addhl` lays each caption and key button across `cont.sz.x`, so the key sits at the right
      edge of the page rather than 110 pixels short of it.
      `docs/client/ui-panels.md` says the holder is a declared box, drops the re-fitting row for it and
      names the shape a page with a port of its own now wears;
      `docs/addons/api/client/addon.md` re-anchors "past the height of the list beside it" to the page box
      it now is.
      *Its suite* declares three rows — a page far shorter than the box, which is what makes the fill
      visible — and reads the view out of `hafen.ui()`: both tabs' holders are one box and it is 410×410
      design pixels; both subject lists are one box and it is the page's own height; every panel built
      under either holder fits inside the box, over however many of the nine the window has been walked
      through; every page that fills the box is exactly it — its port the page's own width, its content
      reaching the bottom edge — over the fillers the walk has built, an addon's page being the one that
      stands with no walking at all; and the view's own box is the tab strip plus the holder, so nothing
      drawn inside one can push the window. Selecting a row is not automatable — a native list hands
      out no row for a script to hand back — so the walk is the manual line and the checks score what the
      walk has built.
      `[manual]`: open Options, then walk the Game list from `Interface settings` down to `Client` —
      expect the window's frame never to move.
      <!-- extra context: src/haven/Tabs.java, src/haven/Scrollport.java, src/haven/Widget.java (resize/pack),
           src/io/brodgar/addon/ui/AddonOptionsPanel.java -->
