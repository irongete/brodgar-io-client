# 140 — The options page is the addon's

## What & why

Today an addon *declares rows* — `opts:boolean("x"):label("…"):default(true):add()` — and the client draws
one fixed line per row on its page of Options ▸ AddOns. That page can only be a list: no heading, no
indent, no icon beside a checkbox, no group that greys out with its switch. 139 made each of those a column
of the client's own controls. This feature moves the page's *look* to the addon and keeps its *values*
with the client:

- **An option is the model.** `opts:boolean/number/choice/text(name)` declare a stored value — its
  default, its range or choices — and nothing else. The client stores it, checks a write, fires `Changed`.
- **The page is a column the addon fills.** `opts:panel(fn)` registers the one function the client calls
  with `root`, a column of the addon's own inside the page, when the user opens the addon's page.
- **`control:bind(opt)` is the one link between the two**, both ways and type-checked, so a control on the
  page and a write from Lua are one value.

An addon that wants a page builds it: the six drawn rows are gone. 115 rejected a free-form page because
it put the window's look in each addon's hands and left each inventing persistence; the look is now the
client's controls under the client's cascade, and the value never left the client.

## Acceptance criteria

1. **Four builders declare a value and draw nothing.** `opts:boolean(name)`, `:number(name)`, `:choice(name)`,
   `:text(name)` keep `:default`, `:range`, `:choices` and `:add()`; `:label(s)` and `:tooltip(s)` are refused
   naming the control that carries a caption inside `opts:panel(fn)`; `opts:button()` and `opts:label()` are
   refused naming `hafen.ui():button()` / `:label()` there. `opt:type()` answers one of the four,
   `opt:label()`, `:tooltip()` and `:text()` are gone, `opt:info()` is `{name=, type=, value=, default=}`
   plus `min`/`max` or `choices`. Value, store, `Changed` and `opts:option()` are unchanged.
2. **`opts:panel(fn)` is the page.** Registered once, from the file body; a second call replaces the first;
   `opts:panel()` reads the function or `nil`; `opts:panel(nil)` withdraws it. An addon appears in the
   AddOns list of the settings view exactly when it holds a panel, and its page is the addon's display
   name as heading and, under it, the box the client's own pages fill — a scrolling area holding `root`.
3. **`root` is a column of the addon's own**, `:role()` `"column"`, its width pinned to the box, owned — so
   `:gap`, `:stock`, `:enabled` answer on it — standing in the Options window of the character whose window
   it is. `fn(root)` runs on the step after the page is built, holding no tree — like an `Added` handler
   it may build anything and reach any tree — so the page fills a frame after it opens. It runs every time
   the page is opened, after `:reload` and once per Options window, and what it built dies with the page;
   an error in it is logged, and the page shows the heading alone.
4. **`bind` is one verb, both ways, typed.** `w:bind(opt)` on a control you built joins it to an option:
   a check to a `boolean`, a slider to a `number` (its `:range` is the option's), a dropdown or a radio
   to a `choice` (its rows are the choices), an entry to a `text`. The control takes the option's value at
   once; the user moving it writes `opt:value(v)` and so fires the option's `Changed`; a write to the
   option from anywhere moves every bound control without firing the control's own `Changed`. `w:bind()`
   reads the option or `nil`, `w:bind(nil)` unbinds, a second `bind` replaces. A mismatched pair is refused
   naming the control a kind takes; a borrowed control is refused naming the client. A binding ends with
   the control.
5. **The six addons that declared rows draw their pages** through `opts:panel(fn)`, keeping every value
   they stored — the prefs key is the option's name and does not change.

## Out of scope

- **A panel outside Options** — `root` is the client's page; a window of the addon's own is `hafen.ui()`.
- **A page for the client's own settings** — the Game tab stays the client's.
- **A readout label** — two lines of Lua on `Changed`; a format string would be a second language.
- **Keeping a page alive between visits** — rebuilt on every visit, as today; a page that remembers is
  the next word.

## Docs impact

Rewritten: `docs/addons/api/client/addon.md` (the four, the page, `bind`, the Option object). Modified:
`api/README.md:158` (the row), `guides/hotkeys-and-commands.md:85-93` (the example and its sentence),
`ui/column.md` (the composed panel names where it is mounted), `ui/writes.md` (`:bind` in the table),
`ui/controls/README.md` (the setter row), `threading.md` (a row for `opts:panel`, first group), `runtime.md`
(the AddOns panel section, one sentence), `docs/client/ui-panels.md` (what the page host is now).

Derived impact set — `grep -rnE 'client draws the control|build no widget|six rows|button row|label row|:label\(s\)|Options ▸ AddOns' docs/addons/`:
`addon.md:4,23,52,73,78-79,97` (every "drawn" sentence), `api/README.md:158` "the six rows your addon
declares", `hotkeys-and-commands.md:87,93` a row with `:label`; `runtime.md` names the panel but not the
rows.

## Context files

- `docs/addons/api/client/addon.md`, `api/ui/column.md`, `api/ui/writes.md`, `api/ui/controls/README.md`,
  `api/ui/controls/interactive.md`, `api/ui/lists.md`, `api/threading.md`, `api/conventions.md`,
  `DOCUMENTATION.md` — every task
- `docs/client/ui-panels.md`, `docs/client/prefs-and-options.md` — 1, 2
- `src/io/brodgar/addon/AddonOptions.java`, `LuaOption.java`, `OptionsHandle.java` — 1, 3
- `src/haven/OptWnd.java` (`SettingsPanel`, `Subject`, `PanelEntry`, `PAGE`),
  `src/io/brodgar/addon/ui/AddonOptionsPanel.java`, `src/io/brodgar/addon/AddonManager.java`
  (`OptionGroup`, `describePages`, `mountPage`, `optionsGen`, `callLua`) — 2
- `src/io/brodgar/addon/{AddonWidget,Column,UiApi,Owned}.java` (an owned column in a session tree) — 2
- `src/io/brodgar/addon/Controls.java`, `C{Check,Slider,Dropdown,Radio,Entry}.java` (`value`, `changed`,
  `onChange`), `LuaWidget.java` (the verb table) — 3
- `addons/{actionbars,builder-helper,essentials,hitboxes,simple-animal-radius,themes}/main.lua` — 4
