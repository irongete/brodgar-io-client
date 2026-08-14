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

`rts-next-anchor`, `rts-focus` and the new `cam-pivot` are all `KeyBinding.get(id, KeyMatch.nil)` —
the client's idiom for a remappable id with no default. `BindingPanel` gains a **Multi session**
section after `Camera control`, listing all three.

### Hold-to-pivot

The client tracks **no** held-key state — `UI.modflags` covers Shift/Ctrl/Alt/Meta and nothing else —
so `UI` gains a `Set<Integer>` of held keycodes, fed from `UI.keydown`/`UI.keyup`: the OS doors,
called before `dispatch`, so the set is right no matter who has focus. `UI.keyheld(KeyMatch)` answers
it; normalising a `KeyMatch` to one keycode is what `KeyBinding.keycode` already does privately,
lifted to a public static on `KeyMatch`.

`RTSCam.click` then reads `ui.keyheld(kb_campivot.key())` where it read `ui.modflags() & MOD_SHIFT`.
`KeyMatch.nil` is `VK_UNDEFINED` and never in the set, so unbound means "middle drag always pans".

## Files to create/modify

| File | What |
|---|---|
| `src/haven/MapView.java` | `RTSCam`; `camtypes` → `LinkedHashMap`; `setcam`/`camname`; the `cam` command; `kb_campivot`; `kb_rtsnext`/`kb_rtsfocus` re-defaulted to `KeyMatch.nil` |
| `src/io/brodgar/rts/Control.java` | `RTSCam` everywhere; `kb_rtsfocus` dispatch moves in |
| `src/haven/OptWnd.java` | `CameraPanel`'s selector; `BindingPanel`'s Multi session section |
| `src/haven/UI.java` | the held-keycode set, `keyheld` |
| `src/haven/KeyMatch.java` | `keycode(KeyMatch)` public static |
| `src/haven/KeyBinding.java` | its private `keycode` calls that one |
| `src/io/brodgar/addon/CameraOptions.java` | the `mode` option |
| `docs/client/world-3d.md` | **new** — the camera registry, the prefs, `:cam`, the selector |
| `docs/client/multi-session.md` | lines 25 and 32–35 |
| `docs/client/services.md` | the Options row for `defcam`, the binding sections |
| `docs/addons/api/client/README.md` | the `camera()` table, and line 13's one-line description of it |
| `addons/066-camera-and-multi-session.{1,2,3,4,5}/` | one suite per task |

## Risks & gotchas

- **`KeyMatch.Capture.handle` refuses bare modifiers** — `VK_SHIFT`, `VK_CONTROL`, `VK_ALT`,
  `VK_META`, `VK_WINDOWS` all return false. So `cam-pivot` can never be bound to Shift through the
  panel, and Shift + middle drag is gone for good. Backspace in a `SetButton` reverts to the default,
  which here is "None"; Delete unbinds.
- **A lost window focus delivers no `keyup`**, so a key can read as held after alt-tab. It is read
  only at the middle-button press, and one press-and-release clears it; `held` also clears in
  `UI.destroy`. **`UI.keydown` sees every key, including one typed into a `TextEntry`** — the pivot
  key reads as held while you type it into the chat, and the worst case is one drag that rotates.
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
- **Defaulting `cam-pivot` to Shift so today's gesture survives** — no default can be conflict-free,
  and a gesture the panel could never restore afterwards is worse than a clean absence.
- **A "pivot mode" toggle instead of a held key** — a mode you can leave switched on is a camera that
  has silently stopped panning; a held key cannot be forgotten.
- **Tracking held keys in `MapView.keydown`/`keyup` rather than in `UI`** — a focused key never
  reaches the map view while a text entry has focus, so the *release* would be missed exactly when
  the user tabs away mid-hold, which is the one case the tracking exists to survive.
- **A camera *collection*, or a `modes()` verb beside `mode()`** — the members would be five strings
  with no identity, no lifetime and nothing to address into, which is not what a collection is for,
  and the plural belongs to a collection's verb. Either would be a second way to learn the same five
  names the refusal already names.
