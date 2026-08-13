# 063 — plan

## Approach

**1. The input origin.** `WidgetSubs` installs its four `Widget.listen` hooks on the widget the addon
was handed, which for a window is the chrome. It must install them on the widget the addon *paints*.
The contract already has the accessor: `LuaWidget.ownedContent(owner, wdg)` → `Owned.widget()`, "the
content leaf, which for a control is the control". `null` — every borrowed widget, and a bare
`hafen.ui():widget()`, whose root **is** its content — resolves to the widget itself, so nothing but the
owned-window case moves. Resolve once into a final field at construction, so `deafen` reaches the widget
`listen` did even if the tree moved. `Draw`/`Tick`/`Close`/`Drop` already fire from the content
(`AddonWidget.draw`, keyed on `rootw()`), so after this the whole surface vocabulary speaks one
coordinate system.

The chrome consequence *is* the reported fix: the deco is a sibling of the content under the `Window`,
so a caption press now misses the content's rect in `PointerEvent.propagation` and lands on
`DragDeco.mousedown`, which is where the drag lives. Neither addon is touched; both come right.

**2. `preventDefault` on a decorated window.** `Window.handle` calls `super.handle(ev)` and **discards
the answer** before propagating — the one place where the four keys' documented "cancels: yes" is a lie.
One tagged line: return `true` when the listener consumed it.

**3. `widget:picture()`.** The identity is thrown away downstream (`Img` keeps a `Tex`, `IButton` a
`BufferedImage`), so it is recorded upstream at the single place every picture comes from:
`Resource.Image.tex()`, `rawtex()` and `scaled()` each hand their object and `Resource.this.name` to a
new `AddonManager.onPicture(...)`, which files it in a weak identity map. Three tagged lines, one class.
The read then asks the widget which object it shows — `Img` (a tagged getter; its `img` is private),
`IButton.up`, `ICheckBox.up` — in one `instanceof` chain, the discipline `LuaWidget.typeName`/`role`/
`resName` already use for fragile upstream knowledge. Resting face, `string | nil`, no argument.
**Three classes and no more**: `Avaview` and the meters compose their picture at runtime rather than
holding one that came out of a `.res`, so they would answer `nil` from inside the chain exactly as they
do from outside it, and a branch that can only answer `nil` is a branch that lies about being asked.

**4. The inspector.** Pure Lua in `addons/widgetstack/main.lua`: one `describe(w)` table-driver that
`pcall`s each read in a fixed order and emits a line only where the read answered, shared by the hover
panel and every Inspector window. The window grows; the `==` hover guard and the walk budget do not
change, because none of these reads walks the tree.

## Files to create / modify

| | |
|---|---|
| `src/io/brodgar/addon/WidgetSubs.java` | the input target (1) |
| `src/haven/Window.java` | `handle` honours the listener (1) |
| `src/haven/Resource.java` · `src/haven/Img.java` | the picture registry + the `Img` getter (3) |
| `src/io/brodgar/addon/AddonManager.java` | `onPicture` + the weak map (3) |
| `src/io/brodgar/addon/LuaWidget.java` | `:picture()`, beside `resName` (3) |
| `docs/addons/api/ui/custom.md` · `api/ui/widget.md` | the coordinate rule; the read table (1, 3) |
| `docs/addons/api/ui/selectors.md` | `res` vs `picture`; the inspector section (3, 4) |
| `docs/addons/examples.md` | down to two (2, 4) |
| `docs/client/widget-input.md` | **new rows**: `Widget.listen` runs before propagation and a listener that returns `true` ends the dispatch; `Window.handle`'s pointer override (1) |
| `docs/client/ui-chrome.md` | a row for the origin a child of a `Window` sees (1) |
| `docs/client/services.md` | what `Resource.Image` caches, and where the identity is lost (3) |
| `docs/addons/**`, `DOCUMENTATION.md`, `CLAUDE.md`, `specs/ROADMAP.md`, `LuaGOut.java`, `AssetApi.java` | the purge (2) |
| `addons/widgetstack/main.lua` | the new lines (4) |

## Risks & gotchas

- The number is `Window.contarea().ul` = `tlm (18,30)` + `dsmrgn (9,9)`, both `UI.scale`d — 39 device
  pixels of `y` at scale 1, more at any other. Nothing in the bridge ever names it; it simply stops
  mattering.
- **`MouseMoveEvent.propagation` broadcasts to every visible child with no rect test** (already on
  `docs/client/widget-input.md`), so `MouseMove` still fires for a pointer outside the surface — now
  with an out-of-box *content-local* coordinate. `custom.md` must say it.
- The deco is `z(-100)`, so in the `lchild→prev` (topmost-first) walk the content is visited first: a
  press in the content never reaches the caption drag, and a caption press never reaches the content.
- `Tex` and `BufferedImage` define no `equals`, so the weak map is identity by construction. One picture
  shared by two widgets names one resource for both — correct, and worth a sentence on the page.
- `Img.setimg` is public and live (`uimsg "ch"` re-points it), so `:picture()` reads the field on every
  call and caches nothing.
- `hafen.ui():at(x, y)` over an owned window hands back the **window** (`UiApi.attach`), so the content
  leaf is invisible to Lua and no suite can read the offset directly. That is why 063.1 is proved by a
  gesture the program *judges*, not by arithmetic.
- `rm -rf build/classes` before `ant hafen-client`: `WidgetSubs` and `Window` are both moving.

## Discarded alternatives

- **Translate the coordinate in `LuaEvent.input`, listener left on the chrome** — caption and frame
  presses would still arrive, as negative coordinates, so every surface would have to guess whether a
  press was its own. Moving the listener makes the chrome simply not the addon's to click.
- **Extend `widget:res()` to name the picture** — `res` is a selector key, so an icon button would
  silently start matching `[res*=gfx/hud]` and every selector already written would shift meaning.
- **Find a widget's pictures by sweeping its fields with reflection** — it names shared static frames
  and borrowed state icons, so "this widget shows that picture" ends up buried in noise nothing can rank.
- **`:picture()` as an array of every face** — the resting face is what identifies a button, and the
  others are the same name with a suffix; an array makes the common read cost an index.
- **Unlink the fourteen demos from the docs but keep the folders** — an addon nothing names is an addon
  nothing keeps working, so the tree would carry fourteen silent breakages instead of none.
- **Archive the fourteen under `specs/`** — a frozen folder is prior art with a reason attached, not a
  code depot; a backup nobody reads already exists.
- **A second command to print 063.1's verdict after the gestures** — the suite would stop standing
  alone. A bounded timer that scores whatever the run reached keeps one command.
