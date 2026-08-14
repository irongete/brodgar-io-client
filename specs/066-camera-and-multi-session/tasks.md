# 066 — tasks

- [x] **066.1 — The camera is `RTSCam`, and it answers to `:cam rts`.** `MapView.FleetCam` becomes
      `MapView.RTSCam` and its `camtypes` key `fleet` becomes `rts`; upstream's `FreeCam` (the `bad`
      camera) is untouched. The `kb_rtsfocus` dispatch leaves `RTSCam.keydown` for `Control.keydown`,
      which `MapView.keydown` already runs first, so the camera answers only the client's own
      `cam-*` keys. `Control.recam`, `Control.focus` and `prevcam` name `RTSCam`; the mode still
      installs and restores it and still writes no pref. The `cam` command refuses `fleet` by name.
      *Its suite* drives `hafen.client():options():keybindings()`: `rts-focus` and `rts-next-anchor`
      still exist as ids after the move (`list()` carries both), and a `key(id, "F9")` write reads
      back through `key(id)` — the binding survived being re-homed. It must also `pcall` `key(id)` on
      a made-up id and assert plain `nil`, and `key(id, "F9")` on one and assert it *raised*.
      `[manual]`: on a lone character with no fleet, `:cam rts` — expect the panning camera, middle
      drag pans, the wheel zooms, Home follows again. Then `:cam fleet` — expect a refusal naming
      `rts`. Then `:fleet rts on`/`off` — expect the camera swapped in and put back.

- [x] **066.2 — Options ▸ Camera picks the camera.** `camtypes` becomes a `LinkedHashMap`, so the
      dropdown's order is the registration order and **no label field is added** — a camera is shown
      under the name `:cam` takes and no other. The `cam` command's body becomes
      `MapView.setcam(String, String...)`, which the console then calls; `camname()` reverse-looks-up
      the live camera's class. `OptWnd.CameraPanel` gains a `Camera` label and an `SDropBox` above
      the inversion checkboxes, showing `camname()` and calling `setcam` on a pick; with `ui.gui` null
      it writes `defcam` alone. Copy `GameUI.Polity.Selector`; `makeitem(null, …)` is a real call.
      *Its suite* has no Lua door to the camera at all, so it asserts only that it is running against
      a client that still answers `keybindings():list()` for `cam-left` and `cam-reset` — the ids the
      panel sits beside — and puts the rest on the maintainer.
      `[manual]`: open Options ▸ Camera — expect a Camera dropdown listing `follow`, `bad`, `worse`,
      `ortho` and `rts`, showing the one in force. Pick another — expect the view to change at once.
      Reopen the window — expect the new one still shown. Restart — expect it still in force.

- [x] **066.3 — A Multi session section, and no default keys.** `kb_rtsnext` and `kb_rtsfocus` are
      re-declared `KeyBinding.get(id, KeyMatch.nil)`, so Tab goes back to `Inventory` alone and space
      to whatever had it. `OptWnd.BindingPanel` gains a **Multi session** section after
      `Camera control`, listing "Next character" (`rts-next-anchor`) and "Focus selection"
      (`rts-focus`).
      *Its suite* asserts through `keybindings()` that both ids are present in `list()` and read
      `"None"` on a profile that has never assigned them — the whole point of the task, and the one
      thing a program can see. It then writes `key("rts-next-anchor", "F9")`, reads it back, writes
      `"None"`, reads back `"None"`, and asserts a bad key string (`key(id, "Ctrl+")`) *raised*. It
      restores whatever it found first.
      `[manual]`: Options ▸ Keybindings — expect a Multi session section with the two rows, both
      reading None. Bind one, press it in game — expect the anchor to switch. Press Tab — expect the
      inventory, and no anchor switch.

- [ ] **066.4 — Hold a key, pivot the camera.** `UI` gains a held-keycode set fed from `UI.keydown`
      and `UI.keyup` (the OS doors, so focus is irrelevant), cleared in `destroy`, and
      `UI.keyheld(KeyMatch)`. `KeyBinding`'s private `keycode` lifts to a public static on
      `KeyMatch`, so both callers normalise a char-match and a code-match the same way. New
      `MapView.kb_campivot` = `KeyBinding.get("cam-pivot", KeyMatch.nil)`, listed as "Pivot camera" in
      066.3's Multi session section. `RTSCam.click` reads `ui.keyheld(kb_campivot.key())` where it
      read `ui.modflags() & MOD_SHIFT`. `KeyMatch.nil` is `VK_UNDEFINED` and is never held, so
      unbound means always pan.
      *Its suite* asserts `cam-pivot` is in `keybindings():list()` reading `"None"`, that a write and
      read-back round-trips, and that `"None"` unbinds it again — a key that is not in the registry
      cannot be held, so its presence there is the precondition the gesture rests on. It restores what
      it found.
      `[manual]`: on `:cam rts`, middle-drag with nothing bound — expect a pan, and Shift + middle
      drag also a pan, not a rotate. Bind Pivot camera to a key, hold it and middle-drag — expect the
      rotate and elevate. Release, drag again — expect the pan back.

- [ ] **066.5 — `opts:camera():mode()`.** `CameraOptions` gains a third `OptionsMethod` beside the two
      inversions. The read is `camname()` off `AddonManager.view`, falling back to the `defcam` pref
      when there is no view, so `camera()` keeps answering before the UI exists — the promise
      `docs/addons/api/client/README.md` line 144 makes. The write is 066.2's `setcam` plus the pref,
      so Lua and the dropdown are one act. An unknown name throws naming what it got and the names
      that exist; that page's `camera()` table and its line-13 description gain the option.
      *Its suite* is the only one that can see a camera, so it carries the whole surface: `mode()`
      answers a name the client actually has; `mode("rts")` then `mode()` reads back `"rts"`; writing
      the name it found first restores it and chains. Two refusals, each asserted to have raised
      **and** to have named the offender — `mode("nosuchcam")`, whose message must contain `rts`, and
      `mode(nil)`, which the arity rule refuses as a write nobody made.
      `[manual]`: with `:fleet rts on` — expect `mode()` to read `rts` while the mode holds the
      camera, and the `defcam` pref to be untouched when it goes off.
