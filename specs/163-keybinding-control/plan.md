# 163 — plan

## Approach

### One class, on the panel and on the page

The key button is **`OptWnd.SetButton`**, the class every row of Options ▸ Game ▸ Keybindings ends in, and the
feature makes that one class buildable anywhere rather than writing a second capture beside it. Upstream nests it in
`OptWnd.BindingPanel`, an inner class of an inner class, with a `final` `cmd`: it can only be built inside an Options
window, joined for life to one binding. **163.1** moves it one level out, a `public static` member of `OptWnd` beside
`PointBind`; `cmd` loses `final` and may be `null`, and `follow()` is the display half of its `draw`, callable at
once. `BindingPanel.addbtn` builds it exactly as before, so the panel's rows do not change.

The bridge's control is **`CKeybinding extends OptWnd.SetButton implements Owned.Control, Controls.Value`** (163.1),
`Controls.Change` added in 163.2. It follows `CtlButton`'s shape — one `Owned.State`, the pending draw, the disabled
face, the `resize` redraw, the art's `minsz` — and adds one thing: `cmd` is the `KeyBinding` of one of its addon's
hotkeys, or `null`. `LuaWidget.typeName` climbs past an `Owned.Control`, so `:type()` reads `SetButton`; `role` reads
`button` for any `haven.Button`; the `button` stylesheet key dresses it.

### Binding: 163.1

`widget:bind(binding)` stays the one verb that joins a control to what it shows. The `LuaWidget` `bind` verb
dispatches on the adapter: a `CKeybinding` takes a Binding, every other owned control goes on to `Binding.bind` and
an Option as before. The key button's path is two steps, in this order, so a refused bind changes nothing:

1. `CKeybinding.hotkey(owner, v)` — static, touches nothing: the `KeyBinding` of a live hotkey of `owner`'s, or a
   refusal (the table below). The classification is by the handle's registry id (`LuaBinding.id`) against
   `HookApi.keyBindIdPrefix(owner.manifest.id)`: not a Binding (an Option, or anything else) → own prefix, live or
   ended → another `addon/` prefix → an id the registry holds (the client's) → anything else, which is a handle taken
   before `keybindings:on` declared the name.
2. Under the widget's tree monitor, `bind(kb)` sets `cmd` and `follow()`s, or `unbind()` sets `cmd = null` and leaves
   the caption alone (the key stays shown).

A hotkey is **live** while `Addon.keybinds` holds a `LuaKeyBind` whose `alive` is set and whose `binding` is `cmd`.
`KeyBinding.get` hands back the same object for an id across re-declarations and reloads, so a hotkey ended while
bound leaves the button joined and inert (a press does nothing), and declaring the name again makes it answer.

The reads: `:bind()` is `LuaBinding.of(owner, cmd.id)`, the interned handle the addon already holds, so `==` holds;
`:value()` is `LuaBinding.keyName(key)`, the caption's key as `binding:key()` spells it; `:text()` is the caption
(`Button.rtext`, an existing read); `:tooltip()` the field. `tick` runs `follow()` every frame, drawn or hidden, so
`:value()` is never a frame behind the binding. A press starts a capture only while the hotkey is live
(`click()`), so a key button built bare, unbound or joined to an ended hotkey does nothing when pressed.

`:tooltip(s)` writes the widget's `tooltip` field; `CKeybinding.tooltip(Coord, Widget)` answers the field when it
is set and `SetButton`'s own tip (`kbtt`, which names Escape, Backspace and Delete) when it is not, so `""` brings
the client's tip back.

### The press: 163.2

The capture is `KeyMatch.Capture`'s: `click` opens a key grab and the caption reads `...`; `keydown` hands every key
to `handle` and closes the grab when `handle` answers `true`. 163.2 wraps it without changing it:

- `handle` first asks `answers()` — live, enabled through every `Owned` ancestor (`Owned.effective`), and shown
  (`tvisible()`). Where it does not, the capture is cancelled and `handle` answers `false`, so nothing is assigned.
  Otherwise it reads `LuaBinding.keyName(cmd.key())` before and after `super.handle(ev)`, and records the new key in
  `moved` when the two differ.
- `keydown` calls `super.keydown(ev)` — the grab is closed when it returns — and then fires `Changed` with `moved`
  through `Controls.fire`, once. The fire waits for the close because a handler may unbind or destroy the button.
- `tick` cancels an open capture that no longer `answers()`: disabled (itself or a column), unbound, hidden, or its
  hotkey ended. `bind` and `unbind` cancel first, being the button's own verbs. `click` lets a second press through
  while a capture is open, which is what closes it.
- Core: `KeyMatch.Capture` gains `capturing()` and `cancel()`, the two things the adapter cannot reach (`grab` is
  private).

### The client's own key button: 163.3

A `SetButton` the addon did not build is borrowed. `LuaWidget.value(Widget)` gains its arm (`keyName` of `key`, the
same read), the `value` write refuses it between provenance and the `widget.value` gate — the refusal is a fact of
the widget's class, read off the handle — and the borrowed `bind` refusal names `hafen.ui():keybinding()` for one.

### API 1.1: 163.1

A new builder is a new verb, so the edition moves: `ApiVersion.CURRENT` is `1.1`, the manifest page states it, and
every manifest example in the docs declares `"1.1"`. `tools/docverbs.py` holds the sentence and the out-of-date
examples to the literal.

### The docs

A key button is a section of `interactive.md`, as every control is a section of `display.md` or `interactive.md`,
with the roster rows in `controls/README.md`, `writes.md` and the index. The prose sweep (163.3) adds the page's
button wherever a page sends the user to Options ▸ Game ▸ Keybindings to assign a key, and separates the user's
remap (a press, unprotected) from the remap the addon's code makes (`binding:key(key)`, `client.settings`).

### The suites

Nothing in Lua can deliver a key press or a click (ROADMAP: *Driving an input event*), so every press is a `[manual]`
line whose effect the suite observes on a 0.05 s timer and scores itself. A capture is observed through `:text()`
reading `...`; a click on a button that must do nothing through its `MouseUp` subscription. Each suite prints its
`[manual]` lines at once, in the order they are to be done, then the verdicts as they are reached, and closes on a
deadline with every unreached verdict a `[fail]` saying so. 163.2 keeps its buttons in a window of its own, never on
the page: the Options window destroys the page's controls when it switches to the Keybindings panel, and the round
trip needs both on screen. 163.1 proves the page holds the same control.

## Files to create/modify

**163.1**
- `src/haven/OptWnd.java` — `SetButton` hoisted to a `public static` member of `OptWnd`, `cmd` mutable and nullable,
  `follow()`.
- `src/haven/KeyBinding.java` — the `aware` comment names `OptWnd.SetButton.follow()`.
- `src/io/brodgar/addon/CKeybinding.java` — new: the adapter.
- `src/io/brodgar/addon/Controls.java` — the `keybinding` builder; `text` refuses a key button.
- `src/io/brodgar/addon/UiApi.java` — `hafen.ui():keybinding()` registered.
- `src/io/brodgar/addon/LuaWidget.java` — the `bind` verb's read and write dispatch to a key button.
- `src/io/brodgar/addon/Binding.java` — a Binding given to any other control refused.
- `src/io/brodgar/addon/LuaBinding.java` — `keyName` package-private.
- `src/io/brodgar/addon/Refusal.java` — `keybinding` among the controls `uiKept` names.
- `src/io/brodgar/addon/ApiVersion.java` — `CURRENT` is `1.1`.
- `tools/refusalverbs.py` — `keybinding` in the control tuple of `HOPS`, `bind` in `WIDGET_SETTERS`.
- `docs/addons/api/ui/controls/interactive.md`, `controls/README.md`, `api/ui/writes.md`, `api/README.md`,
  `manifest.md`, `getting-started.md`, `api/http.md`, `api/websocket.md`, `api/voice/README.md`,
  `guides/libraries.md`, `docs/client/services.md` (the map toll for `SetButton`, `follow()` and the capture's
  toggle: no page covered them).
- `addons/163-keybinding-control.1/` — the suite.

**163.2**
- `src/haven/KeyMatch.java` — `Capture.capturing()`, `Capture.cancel()`.
- `src/io/brodgar/addon/CKeybinding.java` — `Controls.Change`, `answers()`, the `handle`/`keydown` pair and `moved`,
  the `tick` cancel, `click` letting a second press close, `bind`/`unbind` cancelling.
- `docs/addons/api/ui/controls/interactive.md`, `controls/README.md`, `docs/client/services.md` (the two accessors,
  and the disabled cut inside a key grab's dispatch: no page covered a grab meeting the cut).
- `addons/163-keybinding-control.2/` — the suite.

**163.3**
- `src/io/brodgar/addon/LuaWidget.java` — `value(Widget)` reads a `SetButton`; the `value` write refuses one before
  the gate; the borrowed `bind` refusal for one.
- `docs/addons/api/ui/edit.md`, `api/client/addon.md`, `api/client/keybindings.md`, `guides/hotkeys-and-commands.md`,
  `guides/debugging.md`, `getting-started.md`, `api/ui/controls/interactive.md`, `controls/README.md`,
  `api/ui/writes.md`.
- `addons/163-keybinding-control.3/` — the suite.

## The refusals — exact text

Every task writes these exactly. `NAME`, `ID` and `TYPE` are the concatenated values named in the Site column; `—`
is the em dash and `▸` the triangle, both already in bridge strings (`AddonOptions`, `LuaKeybindSection`). The last
column is the substring the suite asserts.

| Id | Site | Message | Suite asserts |
|---|---|---|---|
| R1 | `Controls.keybinding`, an argument passed | `hafen.ui():keybinding() takes no arguments — it is built bare and configured by chained setters: hafen.ui():keybinding():bind(binding):size(w):parent(w)` | `takes no arguments` |
| R2 | `CKeybinding.hotkey`, `v` neither a Binding nor an Option | `widget:bind(binding): a key button joins one of your hotkeys, the Binding keybindings:binding():get(name) hands you once keybindings:on(name, fn) has declared it — got ` + `v.typename()` | — |
| R3 | `CKeybinding.hotkey`, `v` an Option (`o.name` is NAME) | `widget:bind(binding): a key button joins one of your hotkeys, and 'NAME' is an option — hafen.ui():check(), :slider(), :dropdown(), :radio() or :entry() shows an option, and a key button takes the Binding keybindings:binding():get(name) hands you once keybindings:on(name, fn) has declared it` | `keybindings:binding():get(name)` |
| R4 | `CKeybinding.hotkey`, own prefix, no live hotkey (NAME is the id past the prefix) | `widget:bind(binding): your hotkey 'NAME' has ended — sub:off() ended it, or your addon has not declared it since it reloaded. keybindings:on("NAME", fn) declares it again, and this Binding answers it at once.` | `has ended` |
| R5 | `CKeybinding.hotkey`, another `addon/` prefix (ID is the id) | `widget:bind(binding): 'ID' is another addon's hotkey — the user assigns its key in Options ▸ Game ▸ Keybindings, or on that addon's own page. A key button joins a hotkey your own addon declared with keybindings:on(name, fn).` | `another addon's` |
| R6 | `CKeybinding.hotkey`, `KeyBinding.get(ID) != null` | `widget:bind(binding): 'ID' is one of the client's own bindings — the user assigns its key in Options ▸ Game ▸ Keybindings, and binding:key(key) under client.settings is the write your code makes. A key button joins a hotkey your own addon declared with keybindings:on(name, fn).` | `client's own` |
| R7 | `CKeybinding.hotkey`, anything else | `widget:bind(binding): the Binding 'ID' names no hotkey of yours — it was taken before keybindings:on declared it, so it is the registry id as written. Take it after keybindings:on("ID", fn): keybindings:binding():get("ID") then answers your own hotkey.` | `taken before keybindings:on` |
| R8 | `Binding.bind`, `v` a Binding (TYPE is `LuaWidget.typeName(w)`) | `widget:bind(opt): a Binding joins a key button to one of your hotkeys — hafen.ui():keybinding() — and a TYPE binds to an option your addon declared, what ` + `AddonOptions.HANDLE` + `:boolean(name):default(v):add() and the three builders beside it hand back.` | `hafen.ui():keybinding()` |
| R9 | `CKeybinding.VALUE_REFUSAL`: `CKeybinding.value(LuaValue)` (163.1) and `LuaWidget`'s `value` write on a borrowed `SetButton` (163.3) | `widget:value(v) on a key button is refused: the key it shows is its binding's — binding:key(key) under client.settings writes it, and a press on the button is the user's own edit. widget:value() reads the key it shows.` | `binding:key(key)` and `client.settings` |
| R10 | `CKeybinding.TEXT_REFUSAL`: `Controls.text` | `widget:text(s) on a key button is refused: its caption is the key it shows, and it follows the binding — a line beside it is a label: hafen.ui():label():text(s)` | `hafen.ui():label()` |
| R11 | `LuaWidget` `bind` write on a borrowed `SetButton` (163.3; WHOSE is `another addon's` when `Owned.of(w) != null`, else `one of the client's own`) | `widget:bind(binding) joins a key button YOUR addon built, and this one is WHOSE — its key is its binding's: the user assigns it by hand, and binding:key(key) under client.settings is the write your code makes. To assign one of your hotkeys, build your own: hafen.ui():keybinding():bind(binding).` | `hafen.ui():keybinding()` |

Every other borrowed control keeps `LuaWidget`'s existing `bind` refusal word for word.

## Risks & gotchas

- **`BindingPanel` is an inner class, and Java 8 forbids a static member inside one.** `SetButton` cannot become
  static where it stands; it moves one level out, into `OptWnd` beside `PointBind` (already `public static`).
  `addbtn`'s `new SetButton(UI.scale(175), cmd)` then resolves to the moved class with no edit.
- **`SetButton.set(KeyMatch)` is the write**, `Capture.set` then `cmd.set`. Displaying a key goes through `follow()`
  (`Capture.set` alone). Calling `set(cmd.key())` to show a key stores the `Yielding` wrapper as an assignment: a
  binding on its default becomes the user's, and only Backspace undoes it.
- **`follow()` compares by identity** (`cmd.key() != key`), as the panel's `draw` did: `KeyBinding.key()` hands out
  one cached `Yielding` per key (`KeyBinding.aware`). `equals` would treat the wrapper and its raw match as one and
  skip the re-label a claim needs.
- **`Capture.keydown` calls `handle` for every key while grabbed**, and closes the grab only when `handle` answers
  `true`; a bare modifier answers `false`. The moved-key test is therefore before/after around `super.handle`,
  never "`handle` ran".
- **`Changed` fires after `super.keydown`, never inside `handle`.** Inside `handle` the grab is still held; a
  handler that unbinds (`cancel()` nulls `grab`) or destroys the button would make `Capture.keydown`'s
  `grab.remove()` dereference `null`.
- **The cancelling guard inside `handle` answers `false`**: `cancel()` has already closed and nulled the grab, and a
  `true` sends `Capture.keydown` to close it again.
- **The disabled cut runs inside a key grab's dispatch.** `UI.dispatch` walks `grabs` first; `UI.WidgetGrab.handle`
  is `Event.dispatch`, which is `Widget.handle`, whose first line is the fork's cut (`AddonWidgets.disabled`): a
  `KbdEvent` passes a disabled widget by. A disabled key button with an open grab never sees the key — it goes on to
  the tree, the caption still `...` — so the tick is what ends that capture, not `handle`.
- **The tick reaches hidden widgets.** `Widget.TickEvent.propagation` has no visibility gate
  (`docs/client/widget-draw.md`), so the adapter's `tick(double)` runs for a hidden button; `tvisible()` walks the
  parents.
- **The addon layer is offered every key first** (`Client`'s event loop: `layer.keydown(awt, false)`, then the
  session only when the layer did not take it), so a key button in an addon window captures exactly as the panel's
  does. `Widget.remove` → `UI.removed` drops the grabs of a removed subtree, so a page closed mid-capture leaves none.
- **No `wdgmsg` from a key button.** `Button(int, String, boolean)` wires `action = () -> wdgmsg("activate")`, and
  `Capture` overrides `click()` without running `action`; `Button.gkeytype` only runs for a matching `gkey`, which a
  key button has not got.
- **`typeName` needs a named class.** It climbs past an `Owned.Control`, and past an anonymous class only; `SetButton`
  is named, so the adapter reads `SetButton`.
- **An addon hotkey's default is unbound** (`KeyBinding.get(id, KeyMatch.nil)`): Backspace on one reads `None` with
  `binding:assigned()` `false`; Delete reads `None` with `assigned()` `true`.
- **`KeyMatch.name()` spells modifiers Shift, Ctrl, Alt**, in that order: Ctrl+Shift+F12 pressed reads
  `Shift+Ctrl+F12`.
- **`keybindings:binding():get(name)` resolves the addon's own scope only while `KeyBinding.get("addon/<id>/<name>")`
  exists, and `KeyBinding.bindings` never drops an id** for the life of the process, across `:reload`. A suite
  proving R7 needs a name no earlier run declared: one built from `os.time()`.
- **`LuaBinding` handles are interned per addon by id** (`Addon.bindings`, weakly): `LuaBinding.of(owner, id)` is
  the object the addon holds while it holds one.
- **`Binding.bind` resolves an Option first**: R8 has to come before `LuaOption.resolve`, or a Binding falls into
  the generic "opt is an Option" refusal.
- **R9 on a borrowed `SetButton` sits between provenance and the gate** and reads only the handle's own widget
  (`h.wdg`), which survives the widget leaving the tree: the answer depends on the handle, never on state (D-213).
- **Suites.** A `:t163-X` handler holds the typed tree's monitor: the body goes through `hafen.timer():after(0, run)`.
  A `Changed` or `MouseUp` handler runs inside the layer's tree: it records, the timer prints. `Changed` lands a few
  microseconds after the caption changes, on the event thread: a step is scored two polls (0.1 s) after its capture
  is seen to end. A width is written in design px and reads back exactly; a height is compared with a plain
  `hafen.ui():button()`'s, never a literal (at scale 1.5 the art's device height is not a whole design pixel). A
  Java-raised `LuaError` reads `@main.lua:NN msg` through `pcall`: strip `^@?.-%.lua:%d+:?%s*`.
  `string.format("%d", os.time())` is the one spelling that prints the number.
- **Build.** `rm -rf build/classes` before the compile check (a class moves between files), and `rm -f
  build/hafen.jar` before `ant bin` (the jar task keeps `OptWnd$BindingPanel$SetButton.class`). The client runs
  `bin/hafen.jar`; a Java change needs `ant bin` and a full client restart.

## Discarded alternatives

- **A key button of the bridge's own, a `KeyMatch.Capture` subclass copying `SetButton`** — its `:type()` would not
  be the panel's class, `@SetButton` could not name both, and a fix to the panel's button (Backspace, exclusivity,
  the identity follow) would reach one of the two.
- **Building the panel's `SetButton` where it stands, through an enclosing instance** (`panel.new SetButton(w,
  binding)`) — every button would need an `OptWnd` and a `BindingPanel`, a whole Options window built to mint one
  control, and `cmd` final forbids re-binding.
- **`:bind(name)` with the hotkey's name as a string** — a string is the registry id or the addon's name, two
  spellings of one thing; the Binding is what `keybindings:binding():get(name)` already hands out, and `:bind()`
  reads back `==` it as it reads back an Option.
- **Binding through the `Sub` that `keybindings:on` returns** — the Sub is the subscription, ended with `:off()`; the
  key is the Binding's, which outlives the Sub and is where `binding:key()` reads and writes it.
- **A second verb beside `:bind`** (`:hotkey(b)`, `:binding(b)`) — one verb joins a control to what it shows, told
  apart by the value; two would be the dual style the grammar forbids.
- **Gating the builder, the bind or the press under `client.settings`** — the press is the user's own edit with
  their own hand, as on the panel's row, and Lua cannot cause it; `binding:key(key)`, the write the addon's code
  makes, stays protected. A gate here would protect the user from themselves.
- **`:value(v)` writing the binding** — one canonical write, `binding:key(key)`; an owned control's value write is
  unprotected, so it would be a second, unprotected door to a persisted remap.
- **Driving the client's key button with `widget:value(v)` under `widget.value`** — that drive would be
  `binding:key(key)`'s write under a second key; `widget.value` is the act on controls the server sees.
- **Refusing the client's key button after the `widget.value` gate** — the refusal is a fact of the widget's class:
  an addon without the key would be sent to declare one that changes nothing.
- **`Changed` for a change made elsewhere** — a control's `Changed` is the user's hand on that control, the rule every
  control keeps; a change from anywhere is the binding's own event, out of scope.
- **`Changed` fired inside `handle`, or on every key `handle` sees** — the first runs while the grab is still held
  (a handler unbinding or destroying the button breaks `Capture.keydown`); the second fires on a bare modifier and
  on Escape. The test is the key moving, fired after the grab closes.
- **Unbinding the button when its hotkey ends** — re-declaring the name (a `:reload`, a second `on`) hands back the
  same `KeyBinding`, and a button that forgot it would need re-binding by hand; the button keeps its Binding and
  takes no press while the hotkey is not live.
- **Accepting a Binding whose hotkey has ended, to answer when it comes back** — at bind time the refusal names the
  order at the line that wrote it; an end after the bind is tolerated as above.
- **Cancelling a capture at each write site** (`:enabled`, `:visible`, `sub:off()`) — a disabled column, a hidden
  window and a teardown reach the button through no verb of its own. One check in `tick` covers every cause and the
  guard in `handle` covers the key that comes before that tick; `:bind` and `:bind(nil)` cancel at once, being the
  button's own verbs.
- **`:value()` calling `follow()` itself** — a read would re-label a widget from Lua, off its tree's monitor; the
  tick keeps the key current every frame, drawn or hidden.
- **Keeping the `LuaBinding` handle on the adapter** — `LuaBinding.of(owner, id)` re-reads the interned handle, so
  nothing is kept alive for the read.
- **The capture accessors on `SetButton`** — `grab` is `Capture`'s private field, and `Capture` owns the gesture.
- **A verb or an event for a suite to see a capture** — `:text()` already reads `...`; an API added for a suite is a
  namespace for the sake of a suite.
- **A page of its own for the key button** — every control is a section of `display.md` or `interactive.md`, and a
  page would split the roster.
- **163.2's suite on the addon's page** — the page's controls die when the Options window switches to the
  Keybindings panel, which the round trip needs open; 163.1 proves the page holds the same control.
- **Two tasks, as first drawn** (the control whole, then the client's button and the sweep) — the control's suite
  would run past fifteen lines with the press, the refusals and the capture's ends together; the press is its own
  verification session.
