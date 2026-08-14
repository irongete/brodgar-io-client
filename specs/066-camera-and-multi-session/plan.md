# 066 — plan

## Approach

### The camera stops belonging to the mode

`MapView.FleetCam` becomes `MapView.RTSCam`, `camtypes` key `fleet` → `rts`. Two ties to
`io.brodgar.rts` come out of the class:

- **`kb_rtsfocus`** moves from `RTSCam.keydown` to `Control.keydown`, which `MapView.keydown` already
  calls **before** `camera.keydown` — so the mode's key is the mode's, and the camera answers only
  the client's own `cam-*` keys. `Control.focus` keeps its `instanceof` guard and its refusal.
- **`Fleet.groundz`** stays inside `camcc()`'s `Loading` fallback. It walks `Fleet.members`, empty
  with no fleet, and returns the height it was handed — the camera is already correct standalone and
  buys nothing by having a seam cut into it.

`Control.recam`, `Control.focus` and `prevcam` name `RTSCam`. The mode still installs it on the
session holding the screen, still restores the previous camera on the way out, and still writes
neither `defcam` nor `camargs` — a mode is not a preference.

`:cam fleet` must refuse and name `rts`: `camtypes.get` returning null already throws *"no such
camera: fleet"*, which does not, so the `cam` command gets a retired-spelling check.

### One registry, two ways in

`camtypes` becomes a `LinkedHashMap`, so the dropdown's order is the registration order, and **no
label field is added**: it shows the registry key itself. One name per camera everywhere beats a
prettier word for `bad` and `worse`, which are what `:cam` has always taken.

The `cam` command's body becomes `public void setcam(String name, String... args)`, which it then
calls, and `camname()` reverse-looks-up the live `camera`'s class — so the dropdown shows what is in
force rather than what the pref says, which matters precisely while RTS mode has swapped one in
without writing the pref.

`OptWnd.CameraPanel` gains a `Camera` label and an `SDropBox<String, Widget>` above the two inversion
checkboxes. The panel exists at login too, where `ui.gui` is null: with no `MapView` to install onto,
the box writes `defcam` alone and the next session comes up on it.

### And the same knob from Lua

`CameraOptions` gains a third `OptionsMethod`, `mode`. Read: `camname()` off `AddonManager.view`
(package-private, and `CameraOptions` is in that package), falling back to the `defcam` pref when
there is no view, so `camera()` keeps answering before the UI exists as its page promises. Write: the
`setcam` the console and dropdown call, plus the pref. An unknown name throws a `LuaError` naming what
it got **and the names that exist** — which is why no list verb is needed.

### The bindings, all unbound

`rts-next-anchor` and `rts-focus` are both `KeyBinding.get(id, KeyMatch.nil)` — the client's idiom for
a remappable id with no default. `BindingPanel` gains a **Multi session** section after
`Camera control`, listing both.

### The pivot is a modifier, and cannot be a binding

`RTSCam.click` reads `ui.modflags() & UI.MOD_CTRL` where it read `MOD_SHIFT`. One line.

It stays hard-wired because the keybind panel cannot express a modifier and cannot be made to without
breaking everything else it does. `KeyMatch.Capture` opens a key grab and closes it the moment
`handle` returns true; `handle` refuses `VK_SHIFT`/`VK_CONTROL`/`VK_ALT`/`VK_META`/`VK_WINDOWS`, and
that refusal is exactly what holds the grab open across the `VK_CONTROL` event of a `Ctrl+M` so the
`VK_M` event after it can be captured. Lift the refusal and every chord in the client becomes
unassignable — you could bind bare keys and bare modifiers and nothing else. (`KeyMatch.ModCapture`
solves the same problem the other way, committing on key**up**, and nothing in the tree uses it.) So a
rebindable pivot could only ever hold a non-modifier key, which is the wrong shape for a gesture you
hold while dragging.

Nothing collides. `MapView.mousedown` sends `ev.b == 2` straight to `camera.click` with no modifier
branch and no fallthrough, so the middle button on the map view is the camera and nothing else. The
other Ctrl gestures within reach are on different events: `StdPlace.rotate` is Ctrl and the wheel
**turning** while placing a building, and the RTS marquee's additive select takes Shift or Ctrl on
buttons 1 and 3.

## Files to create/modify

| File | What |
|---|---|
| `src/haven/MapView.java` | `RTSCam`; `camtypes` → `LinkedHashMap`; `setcam`/`camname`; the `cam` command; `RTSCam.click`'s modifier; `kb_rtsnext`/`kb_rtsfocus` re-defaulted to `KeyMatch.nil` |
| `src/io/brodgar/rts/Control.java` | `RTSCam` everywhere; `kb_rtsfocus` dispatch moves in |
| `src/haven/OptWnd.java` | `CameraPanel`'s selector; `BindingPanel`'s Multi session section |
| `src/io/brodgar/addon/CameraOptions.java` | the `mode` option |
| `docs/client/world-3d.md` | **new** — the camera registry, the prefs, `:cam`, the selector |
| `docs/client/multi-session.md` | lines 25 and 32–35 |
| `docs/client/services.md` | the Options row for `defcam`, the binding sections |
| `docs/addons/api/client/README.md` | the `camera()` table, and line 13's one-line description of it |
| `addons/066-camera-and-multi-session.{1,2,3,4,5}/` | one suite per task |

## Risks & gotchas

- **`KeyMatch.Capture.handle` refuses bare modifiers**, and the refusal is load-bearing rather than a
  restriction — see above. Backspace in a `SetButton` reverts to the default, which for the two ids
  here is "None"; Delete unbinds.
- **`restorecam()` runs in a field initialiser** and falls back to `SOrthoCam` on an unknown
  `defcam` — a pref still saying `fleet` comes back on ortho rather than erroring. Say so in the docs.
  **`makecam` reflects for `(MapView, String[])` then `(MapView)`**, which `RTSCam(String... args)`
  keeps; a `Camera` is a non-static inner class, hence the `MapView` argument.
- **`SDropBox`**: `makeitem(null, …)` is a real call; `change(I)` both sets `sel` and rebuilds the
  closed-box widget, so it is the only way to set the shown value; the drop arrow is placed once in
  the constructor and `resize` is not overridden. Copy `GameUI.Polity.Selector`.
- **`SetButton.draw` re-reads `cmd.key()` every frame**, so a binding changed from Lua refreshes
  itself. **`KeyBinding.set` steals a key from any other binding matching it** (the fork's
  exclusivity rule) — but only on a write, which is why two *defaults* could collide and none is set.

## Discarded alternatives

- **Console name `free`, on the grounds that the camera is no longer RTS-specific** — it is the RTS
  camera by construction and by name, one thing gets one spelling, and the word would have wanted
  upstream's `FreeCam` (the `bad` camera) renamed out of its way for nothing.
- **A display label per camera in the dropdown** — "Isometric" beside `ortho` reads better exactly
  once, and thereafter is a second name for one camera, unfindable from the console word the docs and
  the refusals use.
- **A rebindable `cam-pivot` id, held down and read through a new `UI.keyheld`** — built, and taken
  back out. It works, but the panel can only ever put a *non-modifier* key in it, so the gesture
  becomes "hold P and middle-drag" rather than the modifier a drag gesture wants. Paying a held-keycode
  set in `UI`, a public `KeyMatch.keycode`, a registry id and a panel row to end up with a worse
  gesture than the one line it replaced is the wrong trade.
- **Lifting `Capture`'s bare-modifier refusal so a modifier could be bound** — it is what keeps the key
  grab open across the modifier press of a chord, so lifting it makes every `Ctrl+X` in the client
  unassignable. Doing it properly means `ModCapture`'s commit-on-keyup rule inside `Capture`, plus a
  `name()` that does not render a bare Ctrl as "Ctrl+Ctrl", plus `PointBind`'s own copy of the refusal
  — a client-wide input capability, not a camera feature, and it belongs in its own spec.
- **A "pivot mode" toggle instead of a held modifier** — a mode you can leave switched on is a camera
  that has silently stopped panning; a modifier cannot be forgotten.
- **A camera *collection*, or a `modes()` verb beside `mode()`** — the members would be five strings
  with no identity, no lifetime and nothing to address into, which is not what a collection is for,
  and the plural belongs to a collection's verb. Either would be a second way to learn the same five
  names the refusal already names.
