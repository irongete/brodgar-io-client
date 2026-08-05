# 040-ui-controls — Spec

<!-- LINE LIMIT LIFTED for this feature by the maintainer's explicit instruction at /plan time
     ("para este plan se levanta el límite de líneas en los archivos, documéntalo bien"). The
     template's 80-line ceiling does NOT apply to this file. It is a one-off for 040, granted
     because the feature ships 18 surfaces at once and each needs its contract written down;
     it is NOT a precedent for the next feature. Everything else in the template still holds. -->

## What & why

An addon can build **three** surfaces today — `hafen.ui():window()`, `:widget()` and `:overlay()` — and
everything inside them is painted by hand through the `g` wrapper. There is no way to put a **control** on
screen: a button, a text field, a checkbox, a scrollable list. An addon that wants one draws a rectangle,
draws a caption in it, reads `:onClick`, and reimplements hover, press, focus and the stylesheet's own look.

Meanwhile the client already **has** all of them — `Button`, `TextEntry`, `SListBox` and fifteen more, the
same classes the client's own windows are built from. They are simply not reachable from Lua: nothing in
`src/io/brodgar/addon/` so much as names one of them.

This feature exposes them. **18 controls in two groups**, chosen by what they cost, not by what they look
like:

| Group | Controls | Why they are one group |
|---|---|---|
| **Direct** | `Button` `IButton` `TextEntry` `Label` `ILabel` `Img` `Progress` `HRuler` `CheckBox` `ICheckBox` `RadioButton`+`RadioGroup` `HSlider` `Scrollport`+`Scrollbar` | concrete classes with plain constructors — construct, configure, place |
| **Model-backed** | `SListBox` `SDropBox` `SListMenu` `GridList` `TableBox` | `abstract`, generic over the item type: they cannot be instantiated at all without a **row source** |

The payoff is not the code saved. It is that a real client control is **dressed by the stylesheet for free** —
fonts, colours, chrome, every property features 033–036 already resolve — while a hand-painted rectangle is
outside the theme permanently and stays outside it as the theme grows.

### The design spine

Three rules generate the whole surface, and all three are the area's existing grammar
(`design/25-uniform-api.md`) applied to a new kind of thing rather than anything new:

1. **A control is a Widget.** `docs/addons/api/ui/README.md` opens with *"There is one type"* — a window you
   built, a native window you found and the widget under the cursor are all the same
   [Widget object](../../../docs/addons/api/ui/widget.md). A control joins that type; it does not start a
   nineteenth entity. So `:position`, `:size`, `:visible`, `:parent`, `:destroy`, `:style` and every selector
   keep working on a control on day one, with nothing written for them.
2. **A verb answers where it applies.** The Widget type already does this — `:title()` reads `nil` on a bare
   widget, `:items()` answers on a container. The controls extend the same pattern instead of inventing a
   per-control vocabulary: **`:value()` is the one verb for a control's value** (R2: the bare noun reads, the
   same name with a value writes and chains) and **`:onChange(fn)` is the one notification**. A checkbox's
   value is a boolean, a slider's a number, a text field's a string, a list's the selected item. One name,
   read at any control, `nil` where a control has no value.
3. **A builder is constructed bare and configured by chained setters** (R4). `hafen.ui():button()` takes no
   argument; `:text`, `:size`, `:onClick` follow. It is the same shape `:window()` already has, including
   D-112/D-119's rule that a builder is **attached inert** and completes on the tick after the statement that
   built it — so a control configured across five lines is never drawn half-built.

**One builder per role, not per class.** `Button` and `IButton` are one control to a Lua author and two
classes to the client; the difference is whether the face is text or images. So the *builder* is
`hafen.ui():button()` and **the setter chooses the class**: `:text("Go")` completes it as a `Button`,
`:image(up, down, hover)` as an `IButton`. Same for `CheckBox`/`ICheckBox` and `Label`/`ILabel`. This is
D-113 already ruling ("a setter that changes how the visual is BUILT rebuilds it"), and it keeps the `I`
prefix — an implementation detail of the client — out of an API whose vocabulary is supposed to come from
the engine's *meaning*, not its class list (D-061).

**The model-backed five are one contract.** `SListBox` and `SDropBox` both extend `SListWidget`, whose whole
demand is two methods: `items()` and `makeitem(item, idx, sz)`. `SListMenu` is that pair plus `choice(item)`.
`TableBox` is that pair with a factory per column. Build the bridge from a Lua table to those two methods
once and four of the five fall out of it. `GridList` is the odd one — it does not build row widgets, it
**draws** cells (`drawitem(g, item)`), which is the `g` wrapper the API already ships.

And the first cut needs **no row factory at all**: `SListWidget.TextItem.of(sz, supplier)` and
`IconText.of(sz, res)` are ready-made rows, so a list of strings and a list of `{icon, text}` work before any
custom-row surface exists.

### The one mechanism that must change

Provenance — *is this widget mine?* — is **derived from the tree, never stored** (029.2,
`LuaWidget.ownedContent`): a widget is owned exactly when it *is*, or directly contains, an
**`AddonWidget`** whose owner and root match. That test is written against a **class**. A `haven.Button` is
not an `AddonWidget`, so under today's rule every control this feature ships would read as **borrowed** —
native — and `:destroy()`, the builder setters and the whole owned half would refuse on the addon's own
button.

So provenance generalises from a class to a **contract**: an interface carrying the three things `isOwn`
already asks for (owner, root, dead), which `AddonWidget` implements unchanged and every control adapter
implements too. It is the same test against a wider type, in one file, and it stays derived.

This costs nothing extra, because most controls need a thin adapter subclass **anyway**: `HSlider.changed()`,
`TextEntry.changed()`/`activate()`, `GridList.drawitem()` and `SListWidget.items()`/`makeitem()` are
overridable methods, not settable fields. The adapter that carries the Lua callback is the same object that
carries the ownership contract. (The rest — `Button.action()`, `ACheckBox.changed()`, `Progress.val()`,
`SDropBox.of`, `SListMenu.of` — take lambdas and need no override, only the ownership contract.)

**Expected `haven` core edits: zero.** Every class involved is `public` with `public`/`protected` members
reachable by a subclass in another package. If that turns out to be wrong for one control, the plan records
the edit and tags it `// addon:`; it is not assumed.

### The agreed surface

Settled with the maintainer during review, in Lua, before any code — the full sketch with a worked panel is
[`api-sketch.md`](api-sketch.md) beside this file. **Six new names carry all 18 controls:**

| Name | Meaning | Answers on |
|---|---|---|
| `:text()` / `:text(s)` | what the control **displays** | button, label, check, radio, entry* |
| `:value()` / `:value(v)` | what the control **holds** | check, radio, slider, entry, progress, scrollbar, list, dropdown |
| `:rows(t)` | the **row source** | list, dropdown, menu, grid, table, radio |
| `:onChange(fn)` | the value changed | everything with a `:value()` |
| `:onPress(fn)` | it fired, and holds nothing | button |
| `:onSelect(fn)` | a row was chosen from a menu | menu |

plus `:onSubmit(fn)` (the entry's Enter), `:onCell(fn)` (the grid's cell painter), `:range(min, max)`,
`:rowHeight(n)`, `:columns{…}`, `:image(…)` and `:source(h)`, each belonging to one or two controls.

| Builder | Class(es) | Its own verbs |
|---|---|---|
| `hafen.ui():button()` | `Button` / `IButton` | `:text` \| `:image` · `:onPress` |
| `hafen.ui():entry()` | `TextEntry` | `:value` · `:onChange` · `:onSubmit` |
| `hafen.ui():label()` | `Label` / `ILabel` | `:text` · `:image` |
| `hafen.ui():image()` | `Img` | `:source` |
| `hafen.ui():progress()` | `Progress` | `:value` |
| `hafen.ui():separator()` | `HRuler` | — |
| `hafen.ui():check()` | `CheckBox` / `ICheckBox` | `:text` \| `:image` · `:value` · `:onChange` |
| `hafen.ui():radio()` | `RadioGroup` + `RadioButton` | `:rows` · `:value` · `:onChange` |
| `hafen.ui():slider()` | `HSlider` | `:range` · `:value` · `:onChange(v, final)` |
| `hafen.ui():scroll()` | `Scrollport` + `Scrollbar` | — (children via `:parent`) |
| `hafen.ui():scrollbar()` | `Scrollbar` | `:range` · `:value` · `:onChange` |
| `hafen.ui():list()` | `SListBox` | `:rows` · `:rowHeight` · `:value` · `:onChange` |
| `hafen.ui():dropdown()` | `SDropBox` | `:rows` · `:rowHeight` · `:value` · `:onChange` |
| `hafen.ui():menu()` | `SListMenu` | `:rows` · `:rowHeight` · `:onSelect` |
| `hafen.ui():grid()` | `GridList` | `:rows` · `:cell` · `:onCell` |
| `hafen.ui():table()` | `TableBox` | `:rows` · `:rowHeight` · `:columns` |

Seven questions were put and answered; each becomes a decision in [`plan.md`](plan.md):

- **A — a text entry's content is `:value()`**, the one door. **`entry:text()` is retired**: it throws
  naming `:value()` rather than reading as a second name for one property. (*The `:text()` column above is
  starred for the entry for exactly this: it is the refusal, not the read.*)
- **C — the row source is `:rows(t)`.** `:items()` keeps meaning what it already means, the game Items
  inside a container.
- **F — the plain words win over the engine's class names**: `:separator()`, `:dropdown()`, `:menu()`,
  `:list()` rather than `:ruler()`, `:dropBox()`, `:listMenu()`, `:listBox()`. `:type()` still reports the
  client's class, which is where a reader who wants the engine's name finds it.
- **B — `:onPress(fn)` is the button's activation and `:onClick(fn)` stays the raw mouse event**, on a
  button as on every other widget. Two callbacks, two meanings, said on the page.
- **D — `:columns{…}` takes a table of column descriptors.** It is data, not a builder's config, so R4 is
  not in play; the alternative (a column collection) was heavier for no gain.
- **E — `:image(…)` sets a face** on a button or checkbox, and `hafen.ui():image()` builds a picture whose
  own content setter is `:source(h)`, so no builder is ever `image():image()`.
- **G — `:rowHeight(n)` has a default** (the client's own text height) rather than being required at
  construction, which is what lets the builder stay bare (R4).

And one shape the sketch settled that is not a naming question: **a radio is ONE control, not a group object
plus N buttons.** It takes one `:position(x, y)` — the top-left of the whole stack — and lays its buttons
downward from there, one row height apart. That maps exactly onto the engine, whose `RadioGroup` already
keys its buttons by label (`RadioGroup.check(String)`), so `:value("Amount")` writes straight through it and
neither `RadioGroup` nor `RadioButton` ever appears in Lua. A horizontal arrangement is deliberately not
shipped until an addon asks for one.

## Acceptance criteria

Verified in-game by the maintainer, one login per task, through `specs/addons/TESTING.md`: each task ships
`addons/040-ui-controls.<X>/`, run by hand as `:t040-<X>`, printing `[pass]`/`[fail]`/`[manual]` lines pasted
straight back.

- [ ] **Every control is buildable and lands on screen.** For each of the 18, a suite builds one into a
      window it created, reads back its `:size()`/`:position()` and asserts it is in the tree
      (`hafen.ui():find`/`:all` sees it, `:parent()` is the window). One `[manual]` line per group confirms
      what a program cannot: that it *looks* like the client's own control.
- [ ] **`:value()` is one verb across every control that has one.** Round-tripped by assertion: write a
      value, read it back, write the other, read again — checkbox (boolean), slider (number, clamped to its
      range), text field (string), list/dropdown/menu (the selected item), progress (0..1). A control with
      no value (`Label`, `HRuler`, `Img`) reads `nil` rather than throwing.
- [ ] **`:onChange(fn)` fires from a real interaction, and only then.** Programmatic `:value(v)` is asserted
      *not* to re-enter the handler (no feedback loop); the user-driven half is one `[manual]` line per
      interactive control naming the click and the expected printed line.
- [ ] **A control is a Widget, proven through the existing surface and not a new one.** On a control the
      suite built: `:type()` reports the client's class name, `:role()` answers `button`/`label`/`textentry`
      where the classifier already knows it, a selector finds it, `:position(x, y)` moves it, `:visible(false)`
      hides it, `:destroy()` removes it — every one an existing verb, asserted with no new spelling.
- [ ] **The stylesheet dresses a control the addon built.** A rule matching it changes its font or colour,
      asserted by `widget:style()` reading the resolved property back, plus one `[manual]` line for the pixels.
- [ ] **Ownership holds in both directions.** The addon's own control answers the owned verbs; a *native*
      control of the same class (a `Button` inside a client window) still refuses `:destroy()` naming what to
      do instead; and a second addon sees the first addon's control as borrowed.
- [ ] **A list is driven by a Lua table.** `:items(t)` populates rows, `:selection()` reads the chosen one,
      selecting fires `:onSelect`, replacing the table re-renders, and an empty table is an empty list, not
      an error.
- [ ] **Teardown gives everything back.** `:reload` and disable destroy every control the addon built, with
      no orphan left in the tree and no exception in the log — asserted by counting the tree before and after
      in the suite, plus one `[manual]` line for the `:reload`.
- [ ] **Every refusal names its replacement.** Constructing with an argument (R4), an explicit `nil` where
      the page documents none (R5), an unknown verb on a control, and a builder setter on a native control
      are each `pcall`ed and asserted to fail *saying why*.
- [ ] Each task ships its self-checking addon per `specs/addons/TESTING.md`; its run is all `[pass]` (plus
      any `[manual]` line the maintainer confirms) and every prior suite still is.
- [ ] `ant hafen-client` → `BUILD SUCCESSFUL` after a clean `rm -rf build/classes`, and the docs tier meets
      area `docs`'s §12 checklist at the end of every task.

## Out of scope

- **`RichTextBox`** — viable, but it drags `RichText.Foundry` and its markup vocabulary behind it. That is
  its own feature, next to the font work, not a row in this table.
- **`FlowerMenu`, `ISBox`** — server-bound by construction: the flower menu is the right-click petal menu
  tied to `MenuGrid` and its wire messages, `ISBox` is a stock box implementing `DTarget`. A client-side copy
  that petitions nothing would be a control that lies. They stay **found**, not built.
- **`Tabs`, and `RadioGroup` as a widget** — neither extends `Widget`; they are coordinators over a parent.
  `RadioButton` ships (it is a widget); the *group* ships as whatever the plan settles for binding several
  radio buttons together. `Tabs` is deferred entirely.
- **Custom row widgets for the model-backed five** — v1 ships the client's ready-made `TextItem`/`IconText`
  rows. An arbitrary Lua-built row is a later delta on the same bridge.
- **`edit()` / adopting a control into a native window** — a separate feature. This one puts controls in
  surfaces the addon owns.
- **Sending anything to the server.** A control is client-side; acting on the game stays `hafen.act` (gated).
- **Retiring or reshaping any existing verb.** `:onDraw`, `:onClick` and the `g` wrapper are untouched;
  hand-painting stays exactly as capable as it is today.

## Context files

- `design/25-uniform-api.md` — the grammar every new verb obeys (R2 arity, R4 bare builder, R5 explicit nil)
- `design/07-ui-and-drawing.md` — the standing UI/drawing design this extends (§LuaWidget, §GOut wrapper)
- `decisions/widgets-ui.md` — D-084 (a size-changing property applies where the surface can re-lay-out),
  D-081 (raw pixels, only a type size is scaled), D-121 (a widget is named by a Widget; a parent is chosen
  while the surface is being built)
- `decisions/architecture-api.md` — D-013 (one canonical way), D-056 (arity is the verb), D-061 (the
  vocabulary comes from the engine), D-108 (a mechanism ships with a consumer), D-112/D-119 (a builder is
  attached inert; its draw is the completion), D-113 (a setter that changes how a visual is BUILT rebuilds
  it), D-125 (a closed vocabulary throws for an unknown verb)
- `src/io/brodgar/addon/AddonWidget.java` — the addon's own Widget: callback slots, `rootw`, `profOwner`,
  `kill`; the class the ownership contract is generalised out of
- `src/io/brodgar/addon/LuaWidget.java` — the Widget entity: interning, `live()`, `ownedContent`/`isOwn`
  (the provenance seam this feature widens), the builder setters, `:position`/`:size`/`:visible`
- `src/io/brodgar/addon/UiApi.java` — where `:window()`/`:widget()`/`:overlay()` are built and registered,
  and the teardown paths every new control joins
- `src/io/brodgar/addon/Addon.java` — the owned-resource registry (`widgets`) teardown walks
- `src/io/brodgar/addon/AddonRegistry.java` — `destroyWidgets`, the teardown that must reach the controls
- `src/haven/Widget.java` — `add`/`resize`/`pack`/`contentsz`, the tree the controls are placed into
- `src/haven/Button.java`, `IButton.java`, `TextEntry.java`, `Label.java`, `ILabel.java`, `Img.java`,
  `Progress.java`, `HRuler.java`, `CheckBox.java`, `ICheckBox.java`, `ACheckBox.java`, `RadioGroup.java`,
  `HSlider.java`, `Scrollport.java`, `Scrollbar.java` — the direct group
- `src/haven/SListWidget.java`, `SListBox.java`, `SDropBox.java`, `SListMenu.java`, `GridList.java`,
  `TableBox.java` — the model-backed group and its `items()`/`makeitem()` contract
- `specs/codebase/widgets.md` — the tree/`UI` seams already documented (do not re-derive)
- `specs/codebase/ui-chrome.md` — what draws a frame; `Frame`/`IBox` and the window-less panels
- `docs/addons/api/ui/custom.md` — the builder page these join
- `docs/addons/api/ui/widget.md` — the Widget type and its owned-vs-borrowed table
- `docs/addons/api/ui/selectors.md` — the role table a new control may add a row to
- `docs/addons/api/conventions.md` — the published grammar the new verbs are read against
- `029-widget-oop/` — where the one Widget entity and derived provenance came from
- `039-uniform-api/` — the grammar's own feature; the builder/setter shape to copy verbatim
