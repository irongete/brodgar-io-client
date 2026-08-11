# 032-replace-verb — Tasks

<!-- MAX 60 lines. One task = one session: self-contained, compiles, in-game verifiable on its own. -->

- [x] **032.1 — name the main inventory, then add the verb.**
      **First, no code:** hover the inventory grid with `widgetstack`'s inspector and read the selectors it
      offers (self-validating since 030.3 — a candidate survives only after `hafen.ui.all()` resolved it and
      found the widget inside). Record in this folder the string that resolves to `GameUI.maininv` **and to
      nothing else**, checked **both** ways round: with the inventory already open, and before it exists. If no
      selector names it, extend the grammar by the minimum that does — **in this task**, never keep the old
      descriptor as a fallback.
      Then **`w:replace(view)`** on `LuaWidget`, arity-as-verb (029): `w:replace()` reads the installed view or
      nil, `w:replace(view)` installs, `w:replace(nil)` undoes. Installing = hide **`nativeWindowOf(w)`**, the
      enclosing window — the transplant that matters — and put the view in the `Hidden` record 031 already
      keeps. The view's fate follows the substitution: undo, teardown or a server destroy **destroys it**,
      through the owned-widget path exactly once. `hafen.ui.replace` still exists in this task.
      **Verify:** in-game from `:lua` — `w:replace(myWindow)` on the inventory grid hides the **whole** stock
      window (no empty frame), Tab and the menu button drive the custom one, `w:replace()` reads it back,
      `w:replace(nil)` restores; a chest replaced then closed by the server takes its view with it.

- [x] **032.2 — delete the old surface, port the addons.**
      `hafen.ui.replace` **deleted**, and with it `LuaReplacer`, the replacer registry, its branch in
      `onWidgetPlaced` and the registration scan — D-068's `ui.on` already does both match paths, so this is a
      deletion, not a port. Drop the replacer list and its teardown leg from `Addon`/`AddonManager`. Port
      `bags` ([main.lua:127](addons/bags/main.lua:127)) and `hello` ([main.lua:991](addons/hello/main.lua:991))
      to `ui.on(sel, "appear", …)` + `w:replace(view)` in this same task or the client will not run.
      **Verify:** in-game — `bags` behaves exactly as before (hotkey arms it, Tab and the menu button drive the
      custom window, disarm/disable/`:reload`/relog all restore under D-070), **including the already-open
      case**: arm it with the stock inventory open. `hafen.ui.replace` reads `nil`. A second addon replacing an
      owned window still gets the D-069 error; replacing a non-window fails clearly.

- [x] **032.3 — docs, harness, close.**
      `docs/addons/api/ui.md` §"Replacing a native window" rewritten around the verb: the three arities; **the
      enclosing-window rule stated as the reason the verb exists**, next to the one line saying `w:hide()` still
      hides exactly what you point at (or the pair reads as a bug); the view's fate when the substitution ends;
      and the `ui.on` + `w:replace` pattern shown **once**, as the whole story. Sweep `conventions.md`,
      `events.md` and `getting-started.md` for the retired descriptor — `type`/`context`/`caption`/`match`/`desc`
      must appear nowhere under `docs/addons/`. `hello` version bump + the once-per-login contract check (the
      three arities, the enclosing-window hop, `replaceGone` for the old namespace function).
      **Verify:** one login re-checks 032 and every prior feature; `bags` and `widgetstack` clean; the
      link/anchor checker over `docs/addons/` reports 0 broken.
