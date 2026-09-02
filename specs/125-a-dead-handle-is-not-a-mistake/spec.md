# 125 — a dead handle is not a mistake

## What and why

`hafen.ui():widget():parent(icon)` raises when `icon` has left the tree. The handler holding `icon`
runs on the step **after** the widget arrived, and the step holds no tree monitor — so between the
event and the call the client may have destroyed it, and does, in batches: a stockpile that swallows
a full inventory destroys every item icon at once, and every queued `item` `Added` handler then holds
a dead widget.

The refusal cannot be guarded. `if icon:exists() then … :parent(icon) end` is check-then-use with no
lock across it, so the guard the page names narrows the window and never closes it. The raise also
fires *inside* the build chain, after `hafen.ui():widget()` has already put a surface in the layer —
so every failure leaves an orphan there, holding no handle anything can reach, until `:reload`.

The rule this feature installs: **a Widget argument that has left the tree stops the write, quietly**
— what staleness already means everywhere else on this surface, where reads answer `nil` and writes
are chaining no-ops. An argument that is **not a Widget** goes on raising: that is a spelling mistake,
and it is the only thing the refusal was ever able to tell anyone.

**Six refusals, in five verbs**, take a Widget, chain, and refuse a dead one: `widget:parent(w)`
twice — the surface direction and the native one, which are the two ends of one verb and today
answer the same fact two different ways — `widget:draggable(h)`, `widget:resizable(h)`,
`widget:replace(view)` and `rule:anchor{ to = w }`. On the surface direction of `:parent(w)` the
receiver is a surface still being built, so stopping the write means **abandoning** it — it has not
painted, and `:destroy()` already has the path for one built and ended in the same statement.
`:exists()` then answers `false` on it, which is the question its caller was told to ask.

**A seventh site takes a Widget and is not one of them.** `hafen.virtual():widget():add(w, anchor)`
**mints** the entity it hands back rather than handing a receiver back, and it has never had a `nil`
to hand — even "there is no map view yet" raises there. A quiet stop would have to answer one, and
the caller's next line, written on the panel it just asked for, would fail on it: a line further from
the fault and inside their own code. So it goes on raising, and what it owes this feature is the
**reason** — today it reports the argument's type name, so a dead widget reads as `"userdata"`, a
spelling mistake that is not one.

## Acceptance criteria

1. `hafen.ui():widget():parent(w)` with a `w` that has left the tree raises nothing, and the chain
   after it — `:name(s)`, `:stock(t)`, `:position(x, y)`, `:size(w, h)` — runs inert and hands the
   surface back.
2. That surface's `:exists()` is `false`, and nothing of it is left in the layer to find or release.
3. `:parent(v)` with a value that is not a Widget still raises, naming what a Widget is.
4. `:parent(w)` with a live `w`, on a surface already on screen, still raises the build-time refusal
   naming `:position(x, y)`.
5. `w:parent(p)` on one of the client's **own** widgets, with a `p` that has left the tree, moves
   nothing and chains; a `p` that is not a Widget still raises, naming a surface of your own.
6. `w:draggable(h)` and `w:resizable(h)` with a handle that has left the tree arm nothing and chain;
   `w:draggable()` and `w:resizable()` read back `nil`.
7. `rule:anchor{ to = w }` with a `w` that has left the tree installs a rule inert for that widget,
   exactly as one whose target closes later; a `to` that is neither `"screen"` nor a Widget raises.
8. `w:replace(view)` with a `view` that has left the tree installs nothing and chains, and
   `w:replacement()` reads back `nil`; a `view` that is not a Widget, or one this addon did not
   build, still raises.
9. `hafen.virtual():widget():add(w, anchor)` with a `w` that has left the tree still raises, and its
   message names the tree instead of reporting `"userdata"`; a value that is not a Widget raises as
   it did.
10. A stale *receiver* keeps every rule it has: `:send`, `:on(key, fn)`, `:match`/`:matchAll` and
    `widget:overlay():add(key)` each still raise, naming the missing tree.

## Out of scope

**The receiver.** The verbs in criterion 10 answer a different question — there is no tree to send
into, no vocabulary to check a key against, no subtree to search, nothing to draw over — and each
states its own reason on its own page. This feature says what an *argument* means. That boundary is
where the surface is whole: the argument rule holds at every site that takes one.

**Enumerating the layer.** An addon holds the handles it built and has no search verbs over its own
layer, so a suite proves abandonment through the handle's `:exists()`. Unchanged here.

## Docs impact

Pages revised: `widget.md` (the model), `custom.md` (`:parent(w)`), `native.md` (the two gestures and
the native direction of `:parent(p)`), `style/geometry.md` (`anchor.to`), `replace.md` (the view) and
`virtual/widgets.md` (the one that goes on raising).

```text
$ grep -rln "left the tree" docs/addons/
docs/addons/api/ui/edit.md
docs/addons/api/ui/items.md
docs/addons/api/ui/native.md
docs/addons/api/ui/selectors.md
docs/addons/api/ui/widget.md
```

- `native.md` — the two gesture statements that a stale handle raises, and the section that takes one
  of the client's widgets into a surface of yours. **Revised**, in both tasks, by section.
- `widget.md` — the staleness paragraph. It counts the receiver raises as three where there are four
  (`widget:overlay():add(key)`), which is also a prose count duplicating a list; and its
  "Guard on `:exists()`" is advice that cannot hold for an argument. **Revised.**
- `edit.md` (`:send`), `selectors.md` (the searches), `items.md` (subscribing) — receiver rules this
  feature does not touch. **Discharged.**
- `overlay.md` — the fourth receiver raise, already stated where it lives. **Discharged**, and it is
  what `widget.md` is made to agree with.
- `style/geometry.md` — `to` is held weakly and goes inert when the target closes; the build-time
  half is missing, and it is the half that raises. **Revised.**
- `replace.md` — its "four things are refused outright" list: three of them still are, and the
  view that has left the tree is stopped instead. **Revised.**
- `virtual/widgets.md` — `:add(w, anchor)`'s refusals, and the one sentence that says why this site
  raises where the rest stop. **Revised.**

No `docs/client/` page is owed: the destroy seam, `Widget.destroy` → `remove` → `parent = null`, and
the `hasparent(ui.root)` liveness test are already mapped in `docs/client/widgets.md`, which is where
this was read.

## Context files

- `src/io/brodgar/addon/LuaWidget.java` — 1, 2
- `src/io/brodgar/addon/UiApi.java` — 1, 3
- `src/io/brodgar/addon/Owned.java` — 1
- `src/io/brodgar/addon/Layout.java` — 2
- `src/io/brodgar/addon/VirtualApi.java` — 3
- `docs/addons/api/ui/custom.md` — 1
- `docs/addons/api/ui/native.md` — 1, 2
- `docs/addons/api/ui/widget.md` — 2
- `docs/addons/api/ui/style/geometry.md` — 2
- `docs/addons/api/ui/overlay.md` — 2
- `docs/addons/api/ui/replace.md` — 3
- `docs/addons/api/virtual/widgets.md` — 3
- `docs/client/widgets.md` — 1
- `docs/addons/api/console.md` — 1, 2, 3 (a suite's command holds the on-screen tree's monitor)
- `DOCUMENTATION.md` — 1, 2, 3
