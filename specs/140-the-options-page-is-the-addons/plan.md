# 140 — plan

## Approach

**The mount point already exists; only the supplier changes.** `OptWnd.SettingsPanel` keeps its AddOns
tab as a census re-read on `optionsGen`/`reloadGen`, one `PanelEntry(id, name, supplier, fresh=true)` per
addon, built by `Subject.show` and destroyed by `Subject.reset` and by `fresh` on the next visit.
`AddonOptionsPanel` stays the page host and loses its `Rows`/`Row`/`layout` half: it builds the heading
(`Label(group.addon, OptWnd.PAGE.x)`), the `Scrollport` at `OptWnd.PAGE` less the heading, and inside the
port's `cont` **`root`** — an `AddonWidget` with `axis = COLUMN` (139), owned by the addon, `:size(w)` pinned
to the port's content width, armed at once (`c.armed()`: it is the client's own build, complete when handed
over) and registered in `owner.widgets` like any owned widget. `AddonManager.describeOptions()` becomes
`describePages()` — the addons holding a panel function, in load order — and `OptionGroup` carries the
`LuaValue fn` beside `id` and `addon`; `optionsGen` is bumped by `opts:panel(fn)` and `:panel(nil)` as
it is by `:add()` today.

**`fn(root)` runs on the step, never in the supplier.** `Subject.show` runs inside the window's tree —
from `PanelList.change` in the input pass, or from `tick` through `reset` — and `LuaWidget.monitor`
refuses a second tree from there (112.2), while every builder `attach`es to the layer first: a page filled
synchronously could build no control at all. So the host only **queues** `(owner, fn, root)` on
`AddonManager.pendingPages`, and `layerStep` drains it beside `runTimers`, holding no monitor, through
`callLua(owner, Addon.C_WIDGET, fn, LuaWidget.of(owner, root))` — the watchdog, the error isolation and the
CPU account of every other handler. A root whose page died before the drain (`!root.exists`) is skipped.
The page therefore fills one frame after it opens, which is the latency an owned widget has anyway.

**The model is `LuaOption` with its view half cut.** `Kind` keeps `BOOLEAN`/`NUMBER`/`CHOICE`/`TEXT`; the
`label`, `tooltip`, `text` and `press` fields go, with `text()`, `press()`, `label()`, `tooltip()` and the
two kinds' branches of `info()`. `AddonOptions.Builder` keeps `default`/`range`/`choices`/`add`; `label` and
`tooltip` become refusals through `Refusal` naming `hafen.ui():check():text(s)` (and `:tooltip(s)`) inside
`opts:panel(fn)`; `opts:button()` / `opts:label()` are refusals naming `hafen.ui():button()` / `:label()`
there — `Refusal.closedIndex`'s hint names the four. `value(LuaValue)` — the one write path: check, store,
fire — is untouched.

**`bind` is a record on the option, hooked at the two points a value already passes.** `Binding.java`:
`LuaOption` gains `List<Owned> bound`; `w:bind(opt)` (in `LuaWidget`, owned controls only, kinds checked
against the adapter — `CCheck`↔`BOOLEAN`, `CSlider`↔`NUMBER`, `CDropdown`/`CRadio`↔`CHOICE`,
`CEntry`↔`TEXT`) configures the control from the option (`Controls.Range.range` from `lo/hi`,
`Controls.Rows.rows` from `choices`), writes the value through the adapter's silent `value(LuaValue)`, and
registers. The **user's** move already ends in `Controls.fire(c, "Changed", v)` — one line there pushes
`opt.value(v)` for a bound control. The **option's** write already ends in `LuaOption.value(v)` — after
`store()`, one loop pulls the new value into every bound control that is not `dead()`, through the silent
path, so no control's `Changed` fires. A binding is dropped when the control dies (`Owned.State.kill`,
where the rest of a control's records already go) and replaced by a second `bind`; `w:bind()` reads,
`w:bind(nil)` unbinds.

**The six addons** each gain an `opts:panel(fn)` of a column with bound controls, lose `:label`/`:tooltip`
and turn their `button`/`label` rows into `hafen.ui():button()`/`:label()`; the prefs key
(`addon/<id>/opt/<name>`) is the option's name, so every stored value survives.

## Files to create/modify

- `src/io/brodgar/addon/AddonOptions.java` — `panel` verb, the two refusals, the four builders;
  `LuaOption.java` — the cut, `bound`, the pull in `value(v)`; `Binding.java` (new) — bind/unbind, the
  kind table, the refusal texts; `Controls.java` — the push in `fire`; `LuaWidget.java` — `bind` verb;
  `Owned.java` — drop the binding on `kill`.
- `src/io/brodgar/addon/AddonManager.java` — `describePages`, `OptionGroup.fn`, `pendingPages`, the
  drain in `layerStep`; `src/io/brodgar/addon/ui/AddonOptionsPanel.java` — the host;
  `src/haven/OptWnd.java` — `addonpanels()` reads `describePages()` (one line, `// addon:`).
- Docs: `docs/addons/api/client/addon.md` (rewritten), `api/README.md`, `guides/hotkeys-and-commands.md`,
  `api/ui/column.md` (where a panel is mounted), `api/ui/writes.md` and `api/ui/controls/README.md`
  (`:bind`), `api/threading.md` (a first-group row), `docs/client/ui-panels.md` (the host, and why the
  fill is deferred).
- `addons/{actionbars,builder-helper,essentials,hitboxes,simple-animal-radius,themes}/main.lua`.

## Risks & gotchas

- **`Subject.show` runs under the tree's monitor**, and `UiApi.attach` takes the layer's — the nesting
  `LuaWidget.monitor` refuses. The fill is queued, never called from the supplier.
- **`fresh` destroys the previous page before building the next**, and `Subject.reset` destroys every
  page on a census change: `root` and the controls inside it leave through the disposal seam
  (`onWidgetDisposed` → `drainDisposedWidgets`), which is the path a control built into a client window
  already takes — `:exists()` reads `false` after, and the binding goes with the control.
- **A page may open twice per frame** (a reset picking the same row): the queue holds one entry per
  root, and a root already dead at the drain is skipped rather than filled into a destroyed tree.
- **The login screen has its own `OptWnd`** and no `SessionState`: `queueArming` would drop `root` there,
  so the host arms it directly; the addon's controls arm by the layer's tick as they always have.
- **`Controls.fire` runs inside the tree that dispatched the press**; `opt.value(v)` from there touches
  no tree (prefs + a pull into controls that may stand in another tree — a plain field write on each,
  the silent path, no monitor taken). Keep the pull to field writes; never `resize` from it.
- **A dropdown's silent write is `super.change(I)` with its notify skipped**; `CDropdown.value(LuaValue)`
  already does that — bind through it, never through `change`.
- **What `fire` carries differs per control** — a bare value on a check, an `ev` (value, `final`) on a
  slider — so the push reads the adapter's own `Controls.Value.value()` after the fire, never the argument.
- **`Refusal` rows are keyed by name**: the four retired spellings are rows; `info()`'s new shape is a
  line on the page, not a row.
- **The suite cannot open the page.** `:t140` arms a bounded watch; the maintainer opens Options ▸ AddOns
  ▸ the suite's row; `fn` records `root`, and the suite scores what the fill reached within thirty
  seconds — the recall-gauge pattern, not a `[manual]`.

## Discarded alternatives

- **Filling the page inside the supplier** — the supplier runs under the tree's monitor, and a builder
  attaches to the layer: the second monitor is refused, so no control could be built there.
- **A borrowed `root` minted by the client** — `:gap`, `:stock` and `:enabled` refuse on a borrowed
  widget, and a panel needs all three; an owned column inside a client tree is what `:parent(win)` already
  makes.
- **Keeping the drawn rows as a default page** — the maintainer's decision: an addon that wants a page
  builds it, and two ways to draw an option is the dual style the grammar forbids.
- **`bind` on the option (`opt:bind(w)`)** — the verb belongs to the thing that changes look, and a
  control is bound to one option while an option may have many controls.
- **A `Changed` on the control when the option writes it** — the row page never fired one, and a
  handler on the control is for the user's hand; the option's own `Changed` is the write's event.
- **A readout `label:bind(num)`** — two lines on `Changed`; a format would be a second language.
- **A page kept between visits** — `fresh` is what the tab does for every addon page today, and a kept
  page would need a re-fill on `:reload` that the destroy already gives.
