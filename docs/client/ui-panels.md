# Panels and tabs

> Where `OptWnd` keeps its pages and how one is swapped in, and `Tabs` — the client's one tab
> coordinator, which is not a widget at all. The frame around a window is [chrome](ui-chrome.md); the
> controls inside a panel are [controls](ui-controls.md) and [lists](ui-lists.md); the tree itself is
> [the widget system](widgets.md).

## `Tabs` — a coordinator that never joins the tree

`Tabs` is a **plain class**, not a `Widget`. It holds the tab list and the current tab; the `Tab`s are
widgets, and they are children of the `parent` the constructor was handed rather than of `Tabs`. So
nothing addresses a `Tabs`: it has no place in the tree, no id and no role, and its `c`/`sz` are
bookkeeping for the tabs it places.

| What | Where |
|---|---|
| Construction | `Tabs(Coord c, Coord sz, Widget parent)` — `c` is where **every** tab body is placed in `parent`, `sz` the box each is born with |
| Minting a tab | `Tabs.add()` → `parent.add(new Tab(), c)`, reading `c` **at that moment**. A caller that wants the bodies under their buttons therefore sets `Tabs.c` and moves the tabs afterwards, because a `TabButton` needs its `Tab` and a `Tab` is placed where `Tabs` was already told |
| The first one wins | `Tab()` makes itself `curtab` when there is none and **hides itself** otherwise, so whichever tab was minted first is the one showing |
| The button | `Tabs.TabButton(int w, String text, Tab tab)` — an ordinary `Button` whose `click()` is `showtab(tab)`, added by the caller wherever it wants it |
| The swap | `showtab(Tab)` hides the old, shows the new, then calls `changed(Tab from, Tab to)` — the one overridable hook, empty by default |
| Sizing | `resize(Coord)` folds over every tab · `contentsz()` is the **union** of them · `pack()` = `resize(contentsz())`, so every tab ends up the same box · `indpack()` packs each on its own instead |
| Teardown | `Tab.destroy()` takes itself out of `Tabs.tabs`; `curtab` is **not** re-pointed, so a `Tabs` whose current tab was destroyed is showing nothing |

> **`Tabs.resize(Coord)` is not a `Widget.resize` override**, because there is no `Widget` here to
> override. Each `Tab` it resizes *is* one and reaches the base method as usual — what carries no seam is
> the coordinator, and nothing can address that anyway.

## `OptWnd` — the panels one window swaps between

One window, and `GameUI.opts`, `GameUI.kb_opt`, `GameUI`'s private `togglewnd` and `LoginScreen`'s own
instance all point at the same one. It is opened on `OptWnd.main` every time: `show()` is
`chpanel(main)` before `super.show()`, so the panel the user left it on is never what it comes back up on.

| What | Where |
|---|---|
| The panels | `OptWnd.main` — the game menu — and `OptWnd.settings`, the tabbed settings view. A third is built by a `PButton` on first press |
| The swap | `chpanel(Panel)` hides `current`, shows the new one, writes the caption, and calls `cresize` |
| The caption | `Panel.cap` is what the window is called **while that panel shows**, `null` for a panel that carries none. `chpanel` writes it through `Window.chcap` behind an equality test, `chcap` being the one caption seam |
| A panel is born hidden | `Panel()` sets `visible = false` at `Coord.z`; whatever puts one on screen calls `show()` itself |
| Where a resize stops | `OptWnd.cresize(Widget)` repacks the window and re-centres it on its old middle — **only when the child is `current`**, so a panel that is not showing may resize freely |
| The menu button | `OptWnd.PButton` builds its panel on first press, `chpanel`s it and keeps it. Its `fresh` flag destroys the previous build first, which is what a panel whose contents are read at build time needs |
| Escape | nothing under this window binds it, so it reaches `Window.keydown` and closes the window like any other |

## The settings view — a list on the left, a panel on the right

`OptWnd.SettingsPanel` is a `Tabs` strip over one list and one panel holder per tab. The list is the
navigation: picking a row puts that row's panel in the holder beside it, and no panel carries a button
back to anywhere.

| What | Where |
|---|---|
| A row | `OptWnd.PanelEntry` — the name the list draws, a `Supplier<Panel>`, and `PButton`'s own `fresh` flag, which is what the keybinding page needs |
| The list | `SettingsPanel.PanelList`, an `SListBox<PanelEntry, Widget>` whose rows are `SListWidget.ItemWidget`s holding a `Label` — a `Label` rather than an `SListWidget.TextItem` because a navigation row is read back by name, and a `TextItem`'s caption is a private raster |
| Both selections funnel through one method | `PanelList.change(I)` — a row click reaches it through `ItemWidget.mousedown`, and a selection written from anywhere else lands in the same place |
| No deselect | `PanelList.unselect(int)` returns without clearing, where every other `SListBox` in the client answers a click on empty space with `change(null)` |
| The holder, and the swap | one plain `Widget` per tab; `SettingsPanel.Subject.show(PanelEntry)` hides what was in it and adds or re-shows the one picked |
| The page box | `OptWnd.PAGE` — the box **every** holder is built at and the height every list is built at. It is a declared constant, not a page's own size, so a panel bigger or smaller than it changes what is drawn and never the window |
| Laying out | once, at the end of the `SettingsPanel` constructor: `Tabs.pack()` then `pack()`. Nothing in the view has a size that moves afterwards, so no swap re-packs anything |
| A page with a port of its own | `BindingPanel` builds its `Scrollport` at `OptWnd.PAGE` less the `PointBind` standing under it, so the page fills the box; the rows inside it spread to `Scrollcont.sz.x`, which is what `Widget.addhl` lays each caption and key button across |
| A tab whose rows are a census | one list is built once and one is re-read — in `SettingsPanel.show()`, and again from `tick` whenever a generation counter it watches moves, because what belongs in it changes while the window sits there |
| A row's identity across a re-read | `PanelEntry.key`, defaulting to the row's own name. The entries are minted fresh each time, so the selection cannot be remembered by identity |
| Swapping a whole list | `SettingsPanel.Subject.reset(List)` — destroys every panel the old entries built, swaps the contents of `entries`, then re-picks the row with the same `key`, else the first. The `SListBox` needs no telling: `update()` re-reads `items()` every tick and diffs it by identity |

## Gotchas

- **`SListBox` builds its row widgets in `tick`, not in `items()`.** A row is not a widget until the
  next frame, so nothing reads one back in the call that added it. It ticks whether or not it is
  visible — `Widget.TickEvent.propagation` has no visibility gate — so a list inside a hidden panel,
  inside a hidden window, still has its rows built.
- **`VideoPanel` throws its whole column away whenever a graphics preference moves.** `VideoPanel.draw`
  runs `resetcf(ui)` when `ui.gprefs` is not what `curcf` was built from, and `resetcf` destroys `curcf`
  and builds a fresh one — so the very checkbox a click just flipped is a **different widget** on the
  next frame. Hold the `VideoPanel`, never anything inside it.
- **`Widget.cresize(Widget)` is a no-op.** A panel that repacks itself long after it was built tells its
  parent, and the news stops there — so a container that has to follow a child's box overrides it. The
  settings view deliberately does not: its holder is `OptWnd.PAGE` whatever `VideoPanel.resetcf` does
  inside it. `OptWnd.cresize` is the override that matters, and it fires for the panel `chpanel` is
  showing.
- **A window may carry no caption.** `Window.cap` is nullable, `DefaultDeco.checkcap` nulls its own
  rendered `cap` to match, and `drawframe` guards the blit; the caption plate is still drawn, at the
  `sz.x / 4` minimum `checkcap` computes. Anything reading a window's caption gets `null` there rather
  than an empty string.
- **`AudioPanel`'s constructor assigns to a bare `prev`, and that is `Widget.prev` — the sibling link.**
  There is no local of that name anywhere in it. It is harmless only because a widget is linked into its
  parent *after* its constructor has run, which overwrites the field; the same idiom inside a method
  would corrupt the child list of whatever it ran on.

## See also

- [controls](ui-controls.md) — what a panel is built out of
- [lists, text and scrolling](ui-lists.md) — `SListBox`, and the `Scrollport` a long panel sits in
- [chrome](ui-chrome.md) — the caption plate a panel writes into, and who draws it
- [GameUI's own windows](gameui-windows.md) — where `opts` sits among the rest
