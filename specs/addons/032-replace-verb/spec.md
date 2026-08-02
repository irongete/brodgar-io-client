# 032-replace-verb — Spec

## What & why

**One way to replace a window, and it is a verb on the widget.**

```lua
-- before: a namespace function carrying the server's descriptor language
hafen.ui.replace("inv", { context = "main" }, buildBagsView)

-- after: wait for it with the selector you already know, then replace it
hafen.ui.on("inventory[title=Inventory]", "appear", function(w)
  w:replace(buildBagsView(w))
end)
```

Two things are wrong today and one change fixes both. **`replace` is the last place that names a window a
different way**: 030.2 deleted `onWidgetCreate` and `LuaWidgetObserver`, so the server-side descriptor
`{id, type, place, caption, parentType}` survives **only** as `replace(type, opts{context,caption,match}, fn)`'s
argument — two vocabularies for "which window", a selector everywhere and a descriptor here (D-012). And
**`replace` was privileged**: 031 made hiding a native window take its toggle, but **only `replace` could bind
a view to it**, so an addon doing the same by hand got a swallowed toggle and nothing driving it. Nothing is
released, so that gap closes by making the binding *the* verb instead of a private step inside a function.

**`hafen.ui.replace` is deleted; `w:replace(view)` takes its place.** Arity is the verb: `w:replace()` reads
the view standing in for this window (or nil), `w:replace(view)` installs one, `w:replace(nil)` undoes it. It
owns the whole operation **including the trap** — it hides the **enclosing** window, not the widget you
matched, which is most of why the old namespace function existed; inside the verb nobody can get it wrong.
(`w:hide()` still hides exactly what you point at — a different operation, unchanged.) **The waiting is not
part of it**: `hafen.ui.on(sel, "appear", fn)` already waits for anything, already fires for what is **already
open** (D-068) and already hands back a `:remove()` handle — the old `replace`'s other half, now just the
general subscription doing its job.

**Why B2 deferred this.** The old `replace` matched at **placement** time, where `place`/`parentType` are the
**server** parent — `GameUI.addchild` re-parents the `Inventory` into a client-side `Hidewnd`, so a live
`maininv.parent` is that wrapper and no live-tree selector could express `context="main"`. 030's
enclosing-window rule (`[title=]` resolves against the nearest enclosing `Window`) is what changed.

> **A hypothesis, treated as one.** `context="main"` becomes `inventory[title=Inventory]`. 030 already refuted
> a promise of this exact shape in-game — its `[res=]` claim died on contact — so **032.1 verifies it with the
> inspector before any code is written**. If no selector names the main inventory the vocabulary is
> **incomplete and gets completed** (the minimal grammar addition), never a descriptor kept as a fallback.

Feature **B3b** — the end of the plumbing. Next is **C**, where the client finally looks different.

## Acceptance criteria

- [ ] **032.1 first**: the inspector confirms a selector resolving to `GameUI.maininv` and nothing else, and the
      string is recorded in the feature folder.
- [ ] `w:replace(view)` on a native window: the stock window disappears, the view stands in, **the client's own
      toggle drives the view** and the menu tick reads it (D-069), and teardown/`:reload`/relog restore under
      D-070 — *the window ends up as the user was seeing it*.
- [ ] It hides the **enclosing** window: replacing the inventory **grid** hides the whole stock window, not just
      the grid inside its frame.
- [ ] `w:replace()` reads back the installed view; `w:replace(nil)` undoes it there and then.
- [ ] `bags` works exactly as before (hotkey arms it, Tab and the menu button drive the custom window,
      disarm/disable/`:reload`/relog restore), **already-open** case included via `ui.on`'s registration scan.
- [ ] **The old surface is gone**: `hafen.ui.replace` reads `nil`, and `type`/`context`/`caption`/`match`/`desc`
      appear nowhere in `hafen.*` or in `docs/addons/`.
- [ ] Replacing a non-window fails clearly; a second addon replacing an owned window gets the D-069 error;
      `hello` and `bags` ported, `hello`'s login check covers the verb and the removed surface.

## Out of scope

- **`match = fn`**, the arbitrary descriptor predicate — cut, not re-homed: `ui.on` + `w:replace` is the
  full-strength path, so nothing is left that only the old function could do.
- Changes to `ui.on` or the classifier. The **grammar** is touched only if 032.1 proves it cannot name the
  main inventory, and then only by the minimum that does.
- The toggle/restore rules (D-069/D-070, shipped in 031). And `hafen.ui.skin` — that is C.

## Context files

- `design/08-widget-replacement.md` — what replacement is for (D-009) and the descriptor it loses;
  `design/22-ui-selectors.md` — the grammar and the enclosing-window rule
- `src/io/brodgar/addon/UiApi.java` — `replace`, `LuaReplacer`, the placement path and scan both dropped;
  `LuaWidget.java` — `Hidden`, the view field the verb exposes, `nativeWindowOf`, `hide`
- `docs/addons/api/ui.md` §"Replacing a native window" + §"Selectors" — rewritten; `031-window-lifecycle/`,
  `030-ui-selectors/` — prior art (D-068/069/070); `addons/bags/main.lua:127`, `hello/main.lua:991` — the ports
- `learnings/widget-replacement.md` — **grep, never read whole**: the two match paths, the wrapper rule;
  `decisions/architecture-api.md` (D-012, D-013), `decisions/widgets-ui.md` (D-009, D-024)
