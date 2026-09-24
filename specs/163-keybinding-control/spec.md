# 163 — keybinding control: the client's key button on your own page

## What & why

An addon's hotkeys get a section in Options ▸ Game ▸ Keybindings, and its page in Options ▸ AddOns holds checks,
sliders and entries, but not the button a Keybindings row ends in. `hafen.ui():keybinding()` makes that button a
control: the client's own key button (`OptWnd.SetButton`, the very class the panel builds), joined to one of the
addon's own hotkeys with `widget:bind(binding)`, so the user assigns the addon's keys on its page or in any window
of it. The Keybindings rows stay: the page's button is a second place to assign the same key, never a replacement,
and a key assigned in either place shows in the other.

The feature closes whole in three tasks: **163.1** builds, binds, reads and refuses; **163.2** is the press — the
capture, `Changed`, and what ends a capture; **163.3** is the client's own key button read from Lua, and the pages
that send a key to the Keybindings panel naming the page's button too.

## Acceptance criteria

1. `hafen.ui():keybinding()` builds the client's key button: `:type()` `"SetButton"`, `:role()` `"button"`, the
   Keybindings panel's width (175 design px) until `:size(w)`, the height of the client's button art. Built bare it
   reads `None` (`:text()`), `:value()` and `:bind()` read `nil`, and a press does nothing. An argument is refused.
2. `:bind(binding)` joins it to a hotkey the addon declared and has not ended: it shows that hotkey's key at once,
   `:bind()` reads the same Binding (`==`), `:bind(nil)` unbinds (the key stays shown, a press does nothing), a second
   bind replaces the first. A hotkey that ends while bound keeps its Binding on the button: a press does nothing until
   the name is declared again.
3. Refused, each naming what to do instead: a client binding (`inv`) or another addon's (Options ▸ Game ▸
   Keybindings); a handle taken before `keybindings:on` declared it (take it after); a hotkey ended with `sub:off()`
   (declare it again); an Option on the key button (a Binding) and a Binding on any other control
   (`hafen.ui():keybinding()`); `:value(v)` (`binding:key(key)`, `client.settings`); `:text(s)` (a label). A refused
   bind changes nothing.
4. A press is a Keybindings row's press: the button reads `...`, then the next key with its modifiers is assigned,
   the same persisted, exclusive edit. Escape cancels, Backspace reverts to the default, Delete unbinds, a bare
   modifier waits for its key, and the native tooltip names the three. Building, binding and pressing it are
   unprotected.
5. `:value()` reads the key shown, spelled as `binding:key()` spells it, `nil` unbound. `:on("Changed", fn)` runs
   `fn(key)` once per press that moves it, after the capture has closed. A press that leaves it fires nothing. A change
   made elsewhere fires nothing and shows on the button's next frame.
6. A disabled button (`:enabled(false)`, a disabled column) takes no key. A capture in progress ends — nothing
   assigned, nothing fired — when the button is disabled, unbound, hidden, or its hotkey ends.
7. `:tooltip(s)` replaces the native tooltip; `:tooltip("")` brings the native one back.
8. A key assigned on a key button of the addon's (its page or a window of it) shows in Options ▸ Game ▸
   Keybindings, and the reverse.
9. The key button the client builds in Options ▸ Game ▸ Keybindings reads its key with `:value()`. `:value(v)` on it
   is refused naming `binding:key(key)` before any permission is asked: it is not driven under `widget.value`.
   `:bind(binding)` on it is refused naming `hafen.ui():keybinding()`.
10. The client implements API `1.1`. An addon using the button declares `"1.1"`, and every manifest example in the
    docs declares `"1.1"`.
11. The Keybindings panel's buttons assign and follow the key as before.

## Out of scope

- **Another addon's binding or the client's on the page.** The boundary is the addon's own hotkeys. The rest stay
  the Keybindings panel's, and widening this lifts a refusal.
- **A whole Keybindings row as one control.** A line is the author's `row`. Aligning a child to the row's far edge
  is a layout feature of `hafen.ui():row()`.
- **`binding:on("Changed", fn)`.** That is the binding's own event for a change from anywhere. The button's
  `Changed` is the user's hand on that button, as with every control.
- **A bare modifier as a key.** The button inherits the panel's refusal (ROADMAP: *No modifier can be bound to
  anything*).

## Docs impact

**Written** (every path under `docs/addons/` unless marked `docs/client/`; the task that writes each in brackets):

- `api/ui/controls/interactive.md` — the *Key button* section [163.1], its *A press* subsection [163.2], the
  client's-own row [163.3]
- `api/ui/controls/README.md` — builders, setters, the `:bind`/`:value` rules, sizing, see-also [163.1];
  subscribing [163.2]; the `:value()` rule's borrowed half [163.3]
- `api/ui/writes.md` — `:text(s)`, `:value(v)` and `:bind` rows, owned half [163.1]; `:value(v)` borrowed half [163.3]
- `api/README.md` — the interactive-controls index row [163.1]
- `manifest.md`, and the `"api_version"` of every manifest example: `getting-started.md`, `api/http.md`,
  `api/websocket.md`, `api/voice/README.md`, `guides/libraries.md` [163.1]
- `api/ui/edit.md`, `api/client/addon.md`, `api/client/keybindings.md`, `guides/hotkeys-and-commands.md`,
  `guides/debugging.md`, `getting-started.md` (the hotkey step) [163.3]
- `docs/client/services.md` — the key button hoisted out of the panel, `follow()`, the capture's toggle [163.1];
  `capturing()`/`cancel()` and the disabled cut inside a grab [163.2]

**Derived impact set.** Every path is under `docs/addons/` unless it is marked `client/`. A location marked *stays*
is still true and is not edited; the task in brackets discharges the rest.

| Command | Hits |
|---|---|
| `grep -rnE 'Game ▸ Keybindings\|Game > Keybindings\|assigns the key\|assign a key\|Assign a key' docs/` | `keybindings.md`:31, 74; `getting-started.md`:112; `debugging.md`:93; `hotkeys-and-commands.md`:22 [163.3] · stays: `keybindings.md`:64, 107; `edit.md`:212; `getting-started.md`:101; `client/widget-input.md`:76 |
| `grep -rnE 'binds to\|holds nothing an option\|what holds one\|:bind\(opt\)' docs/` | `controls/README.md`:48, 54; `writes.md`:37 [163.1] · stays: `addon.md`:105 |
| `grep -rnE 'remap needs\|A remap\|remapping\|Remapping any' docs/addons` | `keybindings.md`:3; `hotkeys-and-commands.md`:3 [163.3] · stays: `client/README.md`:205 |
| `grep -rnE 'Answered by the button\|one-number arity' docs/addons` | `controls/README.md`:62 [163.1] · stays: `column.md`:64 |
| `grep -rnE ':check\(\)`, `:radio\(\)' docs/` | `controls/README.md`:84 [163.2] |
| `grep -rnE "A checkbox's boolean\|holds nothing \(naming" docs/addons` | `edit.md`:119, 147 [163.3] |
| `grep -rnE 'SetButton\|BindingPanel\|KeyMatch.Capture' docs/client` | `services.md`:10, 102 [163.1] · stays: `multi-session.md`:82, 86; `ui-panels.md`:60; `prefs-and-options.md`:22 |
| `grep -rnE 'implements API\|"1\.0"' docs/` | `manifest.md`:8, 51, 80–87; `getting-started.md`:25, 143; `http.md`:25; `websocket.md`:23; `voice/README.md`:30; `libraries.md`:10, 44 [163.1] |
| `grep -rnE 'a scroll,? (and )?a scrollbar\|scroll, scrollbar' docs/addons` | `api/README.md`:90; `controls/README.md`:110; `interactive.md`:3 [163.1] |

## Context files

- `src/haven/OptWnd.java` — `BindingPanel` (its `addbtn`), `SetButton`, `kbtt`, `PointBind`. Tasks 1, 2, 3.
- `src/haven/KeyMatch.java` — `Capture` (`click`, `keydown`, `handle`, `set`, `namefor`), `forevent`, `name`. Tasks 1, 2.
- `src/haven/KeyBinding.java` — `set`, `key()`, `get`, `id`, `modign`, the `aware` cache. Tasks 1, 2.
- `src/haven/Button.java` — `click`, `mousedown`, `mouseup`, `disable`, `bl`/`br`/`hs`/`hl`/`lg`. Tasks 1, 2.
- `src/haven/Widget.java` — `tooltip(Coord, Widget)`, `tick(double)`, `tvisible()`, `handle(Event)` (its first line,
  the disabled cut). Tasks 1, 2.
- `src/haven/UI.java` — `grabkeys`, `dispatch`, `removed`. Task 2.
- `src/io/brodgar/addon/CtlButton.java`, `CCheck.java` — the adapter models. Task 1.
- `src/io/brodgar/addon/CKeybinding.java` — created by 163.1. Tasks 2, 3.
- `src/io/brodgar/addon/Owned.java` — `Control`, `State`, `effective`, `dim`. Tasks 1, 2.
- `src/io/brodgar/addon/Controls.java` — the builders, `text`, `value`, `fire`, `noValue`. Tasks 1, 2.
- `src/io/brodgar/addon/Binding.java` — `bind`, `read`, `VERB`. Task 1.
- `src/io/brodgar/addon/LuaOption.java` — `resolve`, `name`, `CHANGED`. Tasks 1, 2.
- `src/io/brodgar/addon/LuaBinding.java` — `of`, `resolve`, `keyName`, `id`. Tasks 1, 3.
- `src/io/brodgar/addon/HookApi.java` — `keyBindIdPrefix`, `newKeyBind`, `removeKeyBind`. Task 1.
- `src/io/brodgar/addon/LuaKeyBind.java` — `binding`, `alive`. Task 1.
- `src/io/brodgar/addon/Addon.java` — `keybinds`, `bindings`. Task 1.
- `src/io/brodgar/addon/LuaWidget.java` — the `bind`, `value` and `text` verbs, `widgetKeys`, `typeName`,
  `value(Widget)`, `monitor`. Tasks 1, 2, 3.
- `src/io/brodgar/addon/UiApi.java` — the builder registrations, `attach`, `requireUi`. Task 1.
- `src/io/brodgar/addon/Refusal.java` — `uiKept`. Task 1.
- `src/io/brodgar/addon/ApiVersion.java`. Task 1.
- `tools/docverbs.py`, `tools/refusalverbs.py`. Tasks 1, 2, 3.
- `docs/addons/api/ui/controls/interactive.md`, `controls/README.md`. Tasks 1, 2, 3.
- `docs/addons/api/ui/writes.md`. Tasks 1, 3.
- `docs/addons/api/ui/widget.md`, `api/ui/column.md`, `api/timer.md`, `api/client/keybindings.md`. Tasks 1, 2, 3.
- `docs/addons/api/README.md`, `manifest.md`, `api/http.md`, `api/websocket.md`, `api/voice/README.md`,
  `guides/libraries.md`. Task 1.
- `docs/addons/getting-started.md`, `api/client/addon.md`. Tasks 1, 3.
- `docs/addons/api/threading.md`. Task 2.
- `docs/addons/api/ui/edit.md`, `api/ui/selectors.md`, `guides/hotkeys-and-commands.md`, `guides/debugging.md`. Task 3.
- `docs/client/services.md`. Tasks 1, 2.
- `docs/client/ui-controls.md` (`Button`). Task 1.
- `docs/client/widgets.md` (the disabled cut), `docs/client/widget-input.md` (grabs), `docs/client/widget-draw.md`
  (the tick reaches hidden widgets). Task 2.
- `DOCUMENTATION.md`. Tasks 1, 2, 3.
