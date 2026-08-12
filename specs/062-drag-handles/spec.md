# 062 — drag and resize handles

## What & why

The client has no system for this, only three unrelated things soldered per widget by hand:

| | How the client gets it | How many widgets have it |
|---|---|---|
| Dragging | `DragDeco.mousedown` → `wnd.drag()`, and `DefaultDeco extends DragDeco` | every window, free |
| Resizing | `DefaultDeco(true).dragsize(true)`, by hand | **one** — `MapWnd`, the map |
| Remembering | a `Utils.setprefc` line per widget | **eight** slots (`wndc-inv`, `wndc-misc/<wndid>`, …) |

So almost nothing remembers where you put it, exactly one window resizes, and the chat, the belt and the
HUD's `Hidepanel`s drag nowhere at all. `w:position(x, y)` and `w:size(w, h)` already give an addon both
writes as a layer that restores; what is missing is handing them to the **user**, which means separating the
three:

```lua
local chat = hafen.ui():find("@ChatUI")
local grip = hafen.ui():image():source(hafen.asset():get("grip.png")):parent(chat)

chat:draggable(grip)      -- can it be dragged, and by what
chat:resizable(grip)      -- can it be resized, and by what
chat:remember("chat")     -- is it remembered, and under what name
```

**The handle is a widget**, which keeps each verb one verb instead of a vocabulary of edges and zones: the
target drags the whole thing, a grip adopted into it (`:parent(w)` takes any widget in the tree) drags only
from there, and so does a button of yours elsewhere.

**No section, no object.** Three verbs on `Widget`, in the arity every placement verb uses: the bare call
reads, one argument writes, `nil` drops. **Unprotected** — they arm writes already filed as client-side
placement, and what they remember lands in the addon's own save folder, never the client's:
`savewndpos` goes on writing `AddonWidgets.stockc`.

**A resize never moves the origin**, the client's own rule. **A gesture moves the widget within its parent**,
so nothing re-homes anything: this is not the item 061 deferred. **`:remember(name)` restores on the call** —
no other instant is correct, so there is nothing to schedule and no second verb to pair it with — and the
name is the addon's, the engine's only derivable key being type-and-path, whose failure is silent and
crossed.

**Why not left to Lua.** `m:grab()` ships (041), so a drag is writable today — badly. Only the engine reaches
the off-screen clamp, the root-to-parent conversion, the release the grab swallows, and the client's own
re-layout, which discards a dragged place on every screen resize (criterion 7).

## Acceptance criteria

1. `w:draggable(h)` arms a drag: pressing `h` and moving the pointer moves the **target** one-for-one, and it
   stays where dropped. `w:draggable()` reads the handle; `w:draggable(nil)` drops it. The handle may be the
   target, a grip adopted into it, or any other widget.
2. `w:resizable(h)` is the same three arities, sizing one-for-one while the **top-left stays put**, never
   below `(1, 1)`.
3. A gesture survives the pointer outrunning the handle — fast, and off the window — and the client's own
   click never fires underneath it.
4. A drag writes **your `:position` level**, a resize **your `:size` level**: the verbs read where it landed,
   and `nil`, `:reload` and disable give the stock value back.
5. A dragged widget is clamped by `GameUI.fitwdg`'s own rule and cannot be lost off screen.
6. A resize is **inert on a window that packs itself around its contents** — honoured then undone, as
   `w:size(w, h)` is, and never an error.
7. Both **survive the client re-laying the screen out**: resizing the game window leaves a dragged chat and
   belt where the user put them, and `nil` still yields the stock value afterwards.
8. `w:remember(name)` **applies what is saved under that name at the instant it is called** and writes both
   levels back after every gesture, with no further code: surviving a relog is the three lines above, with no
   manifest declaration and no handler. `w:remember()` reads the name.
9. **Forgetting and dropping differ.** `w:remember(nil)` drops the name *and* deletes the record; `:reload`,
   disabling and a relog do not. The slot is **per character**, so it has nothing to apply before
   `EnterWorld` and says so; a `w:position(x, y)` written *after* it wins, being the later write.
10. `w:on("Dragged", fn)` and `w:on("Resized", fn)` fire **once, on release**, answering `ev:x()`/`ev:y()`
    with what landed, in the frame their verbs read. Neither is cancelable, and neither fires for your own
    write or for `:remember`, so a handler cannot drive itself.
11. `w:revert()` drops all three bindings, and teardown does the same — without forgetting, per 9.
12. Two addons may arm one target: a gesture moves it **once**; both levels take the landed value, so a `nil`
    from either is invisible; each `nil` drops only its own; both handlers fire.
13. Refusals naming what to do instead: a stale handle; a handle or target that is not a Widget; a name that
    is not a string; **the same name on a second widget** of one addon, naming the widget holding it; and
    `win:draggable(win)`, which the caption already does — any other handle is accepted on a `Window`, and
    where `dragsize` is live the last gesture wins.

## Out of scope

- **A resize that moves the origin** — corners, a floor, and a per-corner anchor decision, for a gesture no
  window in the game offers.
- **Writing into the client's own `wndc-*` prefs** — a `setprefc` outlives the addon, so uninstalling would
  leave the HUD rearranged.
- **Re-homing** a nested widget above its parent — still deferred, still ~29 `getparent(GameUI.class)` sites
  and `Inventory.mousewheel`'s unguarded dereference.
- A `draggable`, `resizable` or `remember` **stylesheet property**: a gesture binds one named pair; a rule
  describes a kind.
- Handle art: the handle is a widget, so the picture is the addon's.

## Docs impact

**Written**: `ui/native.md` — its subject is where a client widget sits and how big it is, so handing that to
the user is a section of it; its h1 and opening *three writes* gain the verbs. · `ui/widget.md` — three
*owned vs borrowed* rows, the two keys, *arity is the verb*. · `ui/mouse.md` — the grab's second built-on. ·
`guides/permissions.md` · `guides/saved-data.md` — the no-boilerplate path beside the hand-written one. ·
`store.md` — where the slot lives, and that it needs no declaration.

**Derived impact set** — each grep, then its result:

- `grep -rn "native.md" docs/addons/ | grep -v "api/ui/native.md:"` → `api/README.md:79`, `ui/README.md:45`,
  `ui/README.md:63`, `ui/edit.md:349`, `ui/selectors.md:208`, `ui/widget.md:274`, `guides/custom-ui.md:116`
  — seven call the page *moving and hiding*, which stops being the whole of it.
- `grep -rn "no \`:move()\`" docs/addons/` → `ui/widget.md:200` — the paragraph that already answers why
  there is no `restore()` beside `:remember`.
- `grep -rn "w:position\`, \`w:size\`, \`w:visible" docs/addons/guides/permissions.md` → `permissions.md:113`.
- `grep -rn "gizmo" docs/addons/api/ui/mouse.md` → `mouse.md:33`, `mouse.md:71` — incomplete.
- `grep -rn "a position is not a toggle" docs/addons/` → `ui/native.md:55` — the layering criterion 12 obeys.
- `grep -rn "does not overrule a window that owns its own" docs/addons/` → `ui/native.md:47` — criterion 6
  from the gesture's side.
- `grep -rn "nothing else is persisted" docs/addons/api/store.md` → `store.md:16` — the remembered slot is
  its exception.
- `grep -rn "parent(win)\` | puts the control" docs/addons/api/ui/edit.md` → `edit.md:81` — reads *one of the
  client's windows* while `LuaWidget` takes any widget in the tree, which the grip example relies on.
- No ROADMAP line: 061 named this in its *out of scope* and never filed it.

## Context files

`/implement` may load these, and nothing else. Untagged lines are read by every task. The split is **1** the
drag and `Dragged`, **2** the resize and `Resized`, **3** `:remember`.

**Always** — `DOCUMENTATION.md`, `docs/addons/api/ui/native.md`, `docs/addons/api/ui/widget.md`,
`src/io/brodgar/addon/LuaWidget.java`, `src/io/brodgar/addon/Gesture.java` (the gesture widget, the
bindings and the arming listener — 062.1 wrote it, 2 switches its mode, 3 writes its landed values back)

- `Layout.java` — 1, 2, 3 (`apply`, `Anchor.at`, `nextSeq`, `reapply`) · `UiApi.java` — 1, 2 (`fitc`,
  `revert`) · `Args.java` — 1, 2, 3
- `LuaMouse.java`, `LuaGrab.java`, `LuaMouseGrab.java` — 1, 2
- `WidgetSubs.java`, `Subs.java` — 1, 2 · `Addon.java` — 1, 2, 3 (teardown, `revert()`, what a reload keeps)
- `LuaEvent.java` — 2 (`Shape.GESTURE`, `gesture(owner, x, y)`) · `AddonRegistry.java` — 2, 3 (teardown order)
- `StoreApi.java` — 3
- `src/haven/AddonWidgets.java` — 1, 2 (`stockc`, `stockcsz`, `relayout`) · `UI.java` — 1 (`grabmouse`,
  grabs-first, `Grab.remove`)
- `src/haven/Widget.java` — 1, 2 (`move`, `resize`, `listen`, `handle`, `link`, `PointerEvent.propagation`)
  · `GameUI.java` — 1, 2 (`resize`, `fitwdg`)
- `src/haven/Window.java` — 1 (`DragDeco`, `drag`) · 2 (`DefaultDeco.dragsize`, `szdrag`)
- `src/haven/ChatUI.java` — 1, 2 (`move(Coord)` is NOT the identity there — its argument is the chat's
  base; the reason the suites drive one of the client's windows for the level rather than the chat)
- `docs/client/widget-input.md` — 1 · `gameui-windows.md` — 1, 2 · `ui-chrome.md` — 2
- `docs/addons/api/ui/mouse.md`, `pixels.md` — 1 · `edit.md` — 1, 2 · `guides/permissions.md` — 1
- `docs/addons/api/store.md`, `guides/saved-data.md` — 3
- `docs/addons/api/README.md`, `api/ui/README.md`, `api/ui/selectors.md`, `guides/custom-ui.md` — 1
