# 040-ui-controls — Plan

<!-- LINE LIMIT LIFTED for this feature by the maintainer's explicit instruction at /plan time. The
     template's 100-line ceiling does NOT apply to this file. One-off for 040, granted because the
     feature ships 18 surfaces and each carries its own engine contract; not a precedent. -->

## Approach

Four mechanisms carry all 17 controls. Everything else is one adapter per control, which is why the task
list is long and each task is small.

### 1. Provenance becomes a contract, not a class

`LuaWidget.ownedContent` decides *owned vs borrowed* by asking `w instanceof AddonWidget` and matching
`profOwner()` / `rootw()` / `!dead()`. It is **derived from the tree and never stored** (029.2) precisely
because the entity intern cache is weak on both axes and a flag on a handle would silently be lost. That
reasoning survives untouched; only the type widens.

A new interface — three methods, no state — is extracted from what `isOwn` already asks:

```java
interface Owned { Addon profOwner(); Widget rootw(); boolean dead(); }
```

`AddonWidget` implements it with the methods it already has. Every control adapter implements it too.
`ownedContent` tests `instanceof Owned` instead of `instanceof AddonWidget`, and the chrome-case child walk
is unchanged. **One file, one type change**; the mechanism keeps its shape and its per-addon correctness.

The registry follows: `Addon.widgets` is `List<AddonWidget>` and becomes `List<Owned>` (or a second list
beside it, decided in 040.1 by whichever keeps `AddonRegistry.destroyWidgets` simplest), so teardown reaches
a control exactly as it reaches a window today — under `synchronized(ui)`, `kill()` then destroy.

### 2. One adapter per control, and it does double duty

Every control ships a thin subclass in a new `src/io/brodgar/addon/control/` package. It carries two things
and nothing else: the `Owned` contract, and the Lua callback where the engine wants an override.

The engine splits into two halves here, and the split decides how much each adapter does:

- **Settable** — `Button.action(Runnable)`, `ACheckBox.changed(Consumer<Boolean>)` / `.state(Supplier)`,
  `Progress.val(Supplier<Float>)`, `SDropBox.of(...)`, `SListMenu.of(...)`. The adapter only carries `Owned`
  and hands a lambda to the engine.
- **Overridable** — `HSlider.changed()` / `fchanged()`, `TextEntry.changed()` / `activate(String)`,
  `GridList.drawitem(g, item)`, `SListWidget.items()` / `makeitem(...)`, `TableBox.items()` / `spec()` /
  `itemh()`. The adapter overrides and forwards.

Lua callbacks go through the existing bridge — error-isolated, watchdog-counted, UI-thread — exactly as
`AddonWidget`'s slots do. Nothing new is invented for dispatch.

### 3. The face setter completes the class, while the surface is pending

`:button():text(s)` must produce a `Button` and `:button():image(up, down, hover)` an `IButton` — two engine
classes, one Lua builder (spec, decision E/F). The builder cannot know which at construction.

The existing arming machinery is what makes this cheap. `UiApi` builds a surface, adds it to `u.root`
**immediately**, and marks it `pending` so it skips its own draw until `armPending()` runs on the next tick
(039.6, D-119). So there is a whole statement's worth of time in which the widget is in the tree, findable,
and **has never painted**.

The rule: **while pending, a face setter may rebuild the underlying widget**; once armed it is refused,
naming that a face is chosen while the control is built. That is D-113 (a setter that changes how the visual
is BUILT rebuilds it) and it is the same shape `:parent(w)` already has (D-121, building-only). The Lua
handle is a `LuaWidget` whose `wdg` field is re-pointed, and the intern entry moves with it — both already
mutable, because staleness nulls the same field.

`:text(s)` after arming is **not** a rebuild and keeps working: `Button.change(String)` is a public
post-construction setter. Only *switching class* is building-only.

### 4. One bridge for the model-backed five

`SListWidget` demands exactly `items()` and `makeitem(item, idx, sz)`. One holder class implements both over
a Lua array, and `SListBox` and `SDropBox` fall out of it; `SListMenu` is that pair plus `choice(item)`;
`TableBox` is that pair with a factory per column (`ColSpec.of`). `GridList` does not use it at all — it has
`drawitem(GOut, T)` and gets the `LuaGOut` wrapper the API already ships.

Rows are the client's own ready-made widgets, so **v1 writes no row widget**: `SListWidget.TextItem.of(sz,
supplier)` for a string row and `IconText.of(sz, res)` / `of(sz, imgSupplier, textSupplier)` for
`{icon =, text =}`. Row shape is chosen per element by what the table holds; a mixed table takes the icon row
where an entry has an `icon` and the text row elsewhere.

Selection is `SListWidget.sel` plus `change(I)`, which is what `:value()` reads and writes.

### 5. Property dispatch

`:text` / `:value` / `:rows` / `:range` / `:onChange` / `:onPress` / `:onSelect` / `:onSubmit` / `:onCell` /
`:columns` / `:rowHeight` / `:cell` / `:image` / `:source` are added to the **one Widget entity**, beside
`:title` and `:items`, dispatching on the live widget's type. A control that has no such property reads
`nil`; a *write* where it does not apply throws naming which controls take it. Unknown-verb behaviour on the
Widget entity is unchanged — this feature adds names, it retires none except `entry:text()`, which goes
through the existing `Retired` table so it throws naming `:value()`.

## Files to create / modify

**New — `src/io/brodgar/addon/control/`**
- `Owned.java` — the provenance contract (owner, root, dead)
- `Controls.java` — the builders, the face-setter completion, and the property dispatch table
- `LuaRows.java` — the Lua-array→`items()`/`makeitem()` bridge shared by list/dropdown/menu/table
- `CButton.java` `CIButton.java` `CEntry.java` `CLabel.java` `CILabel.java` `CImg.java` `CProgress.java`
  `CSeparator.java` `CCheck.java` `CICheck.java` `CRadio.java` `CSlider.java` `CScrollport.java`
  `CScrollbar.java` `CList.java` `CDropdown.java` `CMenu.java` `CGrid.java` `CTable.java` — one adapter each
  (names indicative; 040.1 fixes the convention and the rest follow it)

**Modified — `src/io/brodgar/addon/`**
- `LuaWidget.java` — `ownedContent`/`isOwn` test `Owned`; the new property verbs; `entry:text()` retirement
- `UiApi.java` — the 16 builders beside `:window()`/`:widget()`/`:overlay()`; teardown reaches controls
- `Addon.java` — the owned registry widens from `AddonWidget` to `Owned`
- `AddonRegistry.java` — `destroyWidgets` over the widened registry
- `AddonWidget.java` — `implements Owned` (its three methods already exist; no behaviour change)
- `Retired.java` — one row: `entry:text()` → `:value()`

**`haven` core edits — expected ZERO.** Every class is `public` with `public`/`protected` members a subclass
in another package can reach. If one control disproves it, the edit is tagged `// addon:` and named in that
task's `HANDOFF.md`.

**Docs (the one tier)**
- `docs/addons/api/ui/controls.md` — **new page**: the roster, the six names, and the worked panel
- `docs/addons/api/ui/README.md` — a `controls` row in the pages table and in the reading order
- `docs/addons/api/ui/widget.md` — the new properties in the read table and the owned-vs-borrowed table
- `docs/addons/api/ui/custom.md` — a pointer: three surfaces, and now the controls that go in them
- `docs/addons/api/ui/selectors.md` — any role row a shipped control adds
- `docs/addons/README.md` + `docs/addons/api/README.md` — the "API at a glance" row
- `docs/addons/examples.md` — the example addon this feature ships

**Specs**
- `specs/codebase/ui-controls.md` — **new, and this feature owes it**: no subsystem file covers the client's
  control catalogue, so the source reading done at plan time is paid for once (max 70 lines). Constructors,
  the settable-vs-overridable split, `SListWidget`'s two-method contract, the ready-made row factories,
  `Scrollport`'s `addchild` redirect, `ACheckBox`'s four function slots
- `specs/codebase-map.md` — its index line
- `specs/addons/decisions/widgets-ui.md` — the decisions below
- `specs/addons/learnings/ui-widgets.md` — whatever the build teaches

**Addons**
- `addons/040-ui-controls.1/` … `.13/` — one self-checking suite per task
- one example addon (name settled in 040.13) demonstrating a real panel, referenced from `examples.md`

## Decisions this feature records

- **A control is a Widget, not a nineteenth entity.** The type stays one; a verb answers where it applies.
- **Provenance is a CONTRACT, not a class** — the generalisation of 029.2 when a second kind of owned widget
  appears. (The 029.2 reasoning — derived, never stored — is what is being preserved, not replaced.)
- **A builder that has two engine classes behind it picks one with a setter, and may rebuild while pending.**
  D-113 and D-121 meeting: what is chosen at build time is refused after arming.
- **A property that reads on any widget writes only where it applies**, and the write names which controls
  take it rather than failing as a nil call.
- **Where the engine has two hooks for one gesture, the API has one name and a flag** — `:onChange(v, final)`
  over `changed()`/`fchanged()`.
- **A coordinator the engine keeps out of the widget tree stays out of the API too** — `RadioGroup` is not a
  widget, so a radio is one control with `:rows`/`:value`.

## Risks & gotchas

Prior art: `learnings/ui-widgets.md` (grepped for the tree/teardown/scale entries).

- **`Widget.add` does NOT route through `addchild`, and `Scrollport` only overrides `addchild`.** So
  `:parent(scroll)` implemented with plain `add()` would drop the child **beside the scrollbar instead of
  inside the scrolling area** — silently, and looking almost right. `:parent` must redirect to the port's
  `cont` for this one control. This is the single most likely way 040.8 ships something subtly broken.
- **`Window.reqdestroy()` is async** (the close animation), so teardown must call `destroy()`/`remove()`
  directly. Same rule the owned-window teardown already follows.
- **`UI.scalef` is `static final`, read once at class init**, and the Options slider says *requires restart* —
  so **nothing can observe a UI-scale change at runtime** (036.3). No acceptance criterion may ask a suite to
  assert behaviour across a rescale; the honest check is that a control's box is in raw pixels like every
  other geometry verb (D-081).
- **`Button.mouseup` runs `d.remove(); redraw();` and calls `click()` LAST**, so an `:onPress` that destroys
  its own window is safe. Worth an assertion rather than an assumption, because the reverse order would be a
  use-after-free the first addon to try it would find.
- **A `Label` resizes itself when its text is written** (`Label.settext` → `resize(text.sz())`). So a
  control's `:size()` can change without the addon writing it. The page must say so; a suite must not assert
  a label's size is what it was before `:text(s)`.
- **A child is clipped strictly to its parent's box** (`Widget.draw(g, true)`), so a control placed outside
  the surface's `:size()` is invisible rather than erroring. Every suite that asserts "it is on screen" must
  assert placement, not just existence — and one `[manual]` line has to carry the pixels.
- **A `TextEntry` takes keyboard focus** (`setcanfocus(true)`). Typing into an addon's entry must not also
  reach the game's own key handling; 040.7 has to prove that explicitly, and it is the one control whose
  `[manual]` line is not optional.
- **Teardown can run off the UI thread**, so every tree mutation stays under `synchronized(ui)` — the rule
  `destroyWidgets` already follows and the new registry must keep.
- **The face-setter rebuild re-points a live intern entry.** If the entity is re-minted between the two
  statements the handle must still resolve; 040.2 asserts identity across the rebuild (`==` on the Lua side)
  rather than trusting it.
- **An empty `:rows{}` must be an empty list, not a crash.** `SListWidget` is written for populated lists;
  the zero case is the one every list suite checks first.
- **`SDropBox.of` / `SListMenu.of` return anonymous subclasses.** They must still carry `Owned`, so either
  the adapters subclass rather than use the factory, or ownership is attached another way — decided in 040.10
  and stated there, not assumed here.

## Discarded alternatives

- **A new entity type per control** (`Button` object, `List` object, …) — breaks *"there is one type"*, the
  first line of the UI reference, and would need `:position`/`:size`/`:visible`/selectors re-implemented per
  type.
- **Wrapping each control in an `AddonWidget`** — the cheap way to keep provenance working, but it adds a
  tree level, makes `:type()` report the wrapper instead of `Button`, and breaks every selector and role that
  names the real class.
- **One builder per engine class** (`:button()` + `:ibutton()`, `:label()` + `:ilabel()`) — imports the
  client's `I` prefix, an implementation detail, into an API whose vocabulary is supposed to carry meaning.
- **`:items(t)` for the row source** — collides head-on with `widget:items()`, the game Items in a container.
- **Custom Lua row widgets in v1** — the ready-made `TextItem`/`IconText` cover the shipped shapes; an
  arbitrary row is a later delta on the same bridge and would have doubled 040.9.
- **Deferring widget creation until the first setter** (so the class is known before anything exists) —
  would have avoided the rebuild, but costs what D-119 already ruled out: a surface held out of the tree is
  not findable, and buys nothing.
- **A second registry list for controls** beside `Addon.widgets` — considered to avoid touching the field's
  type; rejected unless 040.1 finds `destroyWidgets` genuinely simpler that way, since two registries are two
  teardown paths to keep in step.
