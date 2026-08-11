# 032-replace-verb — Plan

## Approach

**Delete a subsystem, add a verb.** `LuaReplacer` and the whole descriptor-matching path go; what is left is
one method on the entity, because 030 and 031 already built everything it needs.

1. **032.1 is a measurement, not code.** Hover the inventory grid with `widgetstack`'s inspector and read the
   selectors it offers — the list is self-validating (030.3: a candidate survives only after `hafen.ui.all()`
   resolved it and found the widget inside). Record the string that resolves to `GameUI.maininv` **and to
   nothing else** in the feature folder. If none does, the grammar is extended by the minimum that names it,
   in this same task, before the verb is written.
2. **`w:replace(view)`** on `LuaWidget`, arity-as-verb like `:pos`/`:size` (029) — `w:replace()` reads,
   `w:replace(view)` installs, `w:replace(nil)` undoes. Installing is exactly what 031 already does internally:
   hide `nativeWindowOf(w)` — the **enclosing** window — join the single `Addon.hiddenNative` record and put the
   view in its field. Nothing new is stored; the verb exposes a field that exists.
3. **The view's fate follows the substitution.** When it ends — `w:replace(nil)`, teardown, or the server
   destroying the window — the engine **destroys the view**. This is not a new rule: `LuaReplacer` already
   documents `:remove()` as *"restore the native window + destroy the view"*, and 029 fixed a leak where a bad
   builder return silently skipped it. Leaving the view alive would strand a custom window over a container
   that no longer exists.
4. **`ui.on` absorbs the two match paths.** The old `replace` hand-rolled a creation path (the placement seam,
   with `place`/`parentType` from the **server** parent) and a scan path (targeting `GameUI.maininv` directly,
   because a live widget has no recoverable `place`). D-068 already unified exactly this: registration scans the
   live tree and fires `appear` for what is open. So `LuaReplacer`, `widgetReplacers`, the replacer branch in
   `onWidgetPlaced` and the registration scan are **deleted**, not ported.
5. **Destroy-detection stays where 031 left it.** `pollModels` is already destroy-detection only; it keeps
   clearing the record (and now destroying the view) when `getwidget(id) != wdg`. The addon's own
   `ui.on(sel, "disappear")` is a separate, addon-facing thing and is untouched.

## Files to create / modify

- `src/io/brodgar/addon/LuaWidget.java` — the `:replace` verb over the existing `Hidden` view field; the
  enclosing-window hop moves here from `LuaReplacer`.
- `src/io/brodgar/addon/UiApi.java` — `hafen.ui.replace` **deleted**; the replacer registry, its branch in
  `onWidgetPlaced` and the registration scan deleted with it.
- **delete** `src/io/brodgar/addon/LuaReplacer.java`.
- `src/io/brodgar/addon/AddonManager.java`, `Addon.java` — drop the replacer list and its teardown leg.
- `docs/addons/api/ui.md` — §"Replacing a native window" rewritten around the verb (the three arities, the
  enclosing-window rule stated as **the** reason the verb exists, the view's fate, and the `ui.on` pairing shown
  once as the whole pattern); `conventions.md` if the descriptor is named there; `getting-started.md`.
- `addons/bags/main.lua`, `addons/hello/main.lua` — ported to `ui.on` + `w:replace`.
- **`specs/codebase/`**: nothing new expected — no `haven` file is read that 029–031 did not already pay for.

## Risks & gotchas

*(prior art: `learnings/widget-replacement.md` — grepped, not read whole)*

- **The enclosing-window hop is the whole point.** `replace` hid `nativeWindowOf(wdg)` — the `Hidewnd
  "Inventory"` around `maininv` — so that the *whole stock window* disappears, not just the grid inside its
  frame. Moving it into the verb is the feature's one real behavioural transplant; get it wrong and every
  replacement leaves an empty frame on screen.
- **`w:hide()` must keep hiding what you point at.** 031 settled that; `:replace` hopping to the parent and
  `:hide` not hopping is intentional, and the docs must say so in one line or it reads as a bug.
- **The old `context="main"` had two different matchers** for the same intent (creation path gated on
  `place=="inv" && parentType=="GameUI"`, scan path targeting `GameUI.maininv` directly). The new form has one.
  032.1 must confirm the single selector covers **both** situations: registering while the inventory is open,
  and registering before it exists.
- **Destroy order.** The view is an addon-owned widget; destroying it from the record must not double-destroy
  when the addon's own teardown runs (029's owned-widget registry). Destroy through the same path, once.
- **`hello` uses `replace` in its login contract check** ([main.lua:991](addons/hello/main.lua:991)) and `bags`
  is the real consumer ([main.lua:127](addons/bags/main.lua:127)); both must move in the same task that deletes
  the old surface, or the client does not run.

## Discarded alternatives

- **Keep `hafen.ui.replace` as a convenience over the verb** — rejected by the maintainer: two ways to do one
  thing, which is exactly what D-012 forbids. The subscription (`ui.on`) is not a second way; it is how you
  wait for anything in this API.
- **Name it `standIn`** — rejected: `replace` is the word the project already uses, and keeping it means the
  concept does not change name in the docs, in `bags`, or in anyone's head.
- **Let `:replace` hide the widget you point at** — rejected: it would leave the stock window's frame around a
  hole, which is the mistake the old function existed to prevent.
- **Leave the view alive when the substitution ends** — rejected: it strands a custom window over a container
  that is gone, and it contradicts what `:remove()` already promised.
- **Keep `match = fn`** — rejected: with `ui.on` + `:replace` public there is nothing it can express that the
  pair cannot.
