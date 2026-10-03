# 173 — HUD regions: plan

## Approach

### Regions (173.1, 173.2)

**A region** is `io.brodgar.ui.Region`, a `Widget` that paints nothing, carrying `role`, `home` (the HUD it stands on) and `painter`. On the HUD, the existing rules apply: `LuaWidget.onScreen` follows the screen, `Layout.fit` clamps, `:remember` keeps a fraction.

- `Region.stand(home, painter, roles...)` adds one region per role, each at a fresh `Coord`, then hands each to `AddonManager.regionStood(r)`, which is `Layout.placed(r, -1)`.
  - A layout rule reaches a widget the moment it appears only through the server's placement seam (`UiApi.onWidgetPlaced` → `Layout.placed`). A widget the client mints is reached by a sheet sweep alone.
  - Without this call, a rule installed before a fight never places that fight's regions.
  - `Layout.placed` is built to run where `stand` runs: inside the tree's `synchronized(ui)`, on whatever thread placed the widget.
- `held()` is `AddonManager.posHeld(this)`, or `parent != home` after a `:parent(p)`.
- `checkhit(c)` answers `false`.
  - A region paints nothing and takes no click: the press passes it to the widget behind (`PointerEvent.propagation` tests the box, never `checkhit`). So `hafen.ui():hit()`, `mouse():over()` and `widget:at()` must pass it too. `LuaWidget.hitTest` honours `checkhit` at the leaf, so `false` is what keeps them answering what a click would hit.
  - The `hud.*` regions stand in front of the map, which `GameUI` lowers to the back (`map.lower()` in `addchild`). Without this, they would answer `hit()` over a strip of the world the HUD always shows.
  - `:draggable` still works on a region: `Gesture` arms a `MouseDownEvent` listener, reached by box. While a handle is armed it takes the press, as every handle does.
- `off(ul, sz)` is the painter's one call per element per frame. `ul` and `sz` are the element's own box in the painter's coordinates.
  - It is called **every frame, for every element, at the top of the painter's draw and outside the element's own conditions**. The box comes from constants, so an element with nothing to show still has a place: no target, no last manoeuvre. `fight.ip.*` without a target is size zero.
  - It resizes the region when `sz` differs.
  - Unheld, it writes the region's `c` to `ul`, converted through the painter's and the parent's `rootpos()`. It writes only when the value changed, so a still frame allocates nothing.
  - When it changed, it calls `AddonManager.regionMoved(r)`. Where `Layout.followed(r)` says an anchor names the region, that call queues it on the geometry seam (`AddonManager.onWidgetResized`). The anchor's drag listener re-derives only on pointer moves, so without this a widget anchored to a region would not follow the character while the mouse is still. The step re-derives the followers holding no monitor, the door `Layout.cascade` already uses across trees.
  - It answers the offset to paint by: `Coord.z` unheld, the region's place less `ul` held, and `null` while `!tvisible()`.

The painter adds the offset to the element's existing anchor, so the default is upstream's arithmetic, pixel for pixel.

**The boxes** come from the client's own constants, so a held region's top-left keeps its meaning:

| Role | Box |
|---|---|
| `fight.opening.mine` | `Bufflist.num * cframe.x + (Bufflist.num - 1) * Bufflist.margin` wide (`Buff.cframe`), as tall as the list, at least one frame. Right edge `UI.scale(20)` left of `pcc`, top at `pcc.y + pho - cframe.y` |
| `fight.opening.theirs` | The same box, left edge `UI.scale(20)` right of `pcc` |
| `fight.ip.mine`, `.theirs` | The text's box as `GOut.aimage` places it at `pcc ∓ UI.scale(75)`. Size zero without a target |
| `fight.cooldown` | `cdframe`'s box, centred on `pcc + cmc` |
| `fight.last.mine`, `.theirs` | `useframe`'s box, centred on `pcc + usec1`, `usec2`, with or without a manoeuvre to show |
| `fight.action` | From `actc(0)` less half `actpitch`: five pitches wide, `(actions.length + 4) / 5` tall |
| `hud.cmdline`, `hud.message` | The slot `(blpw + 10, by - 20)`, the chat's width less 10, `UI.scale(20)` tall |
| `hud.chat` | `UI.scale(100)` tall above `by`, the chat's width less 10 |

**A region's records die with it.**
- The combat regions are new widgets every fight. An addon that hides one (`:visible(false)`, `recordHidden`) or takes one (`:parent(p)`, `UiApi.rehomedAdd`) on each fight's `Added` files a record holding that widget.
- Today those records are pruned only when the whole tree dies (`UiApi.prune`) or at teardown. The disposal drain (`AddonManager.drainDisposedWidgets`) retires subscriptions, layout records, gestures and handles, but not these. Without a fix, records and dead regions pile up per fight until a relog, and `anyHidden` keeps the window-toggle seam walking the list every frame.
- So the disposal drain hands a `Region` to `UiApi.pruneRegion(w)`, which drops every owner's hidden and re-home record for it and recounts `anyHidden`.
- It is limited to regions. A client window that dies hidden keeps today's rule, and its stand-in view stays as it is.
- `:remember` needs nothing: `rememberAs` frees a name whose widget has left the tree, so remembering each fight's region under one name holds one binding.

**`Fightsess`** stands eight regions in `added()`, once, and drops them in `destroy()`.
- `draw()` calls `off` for all eight right after `updatepos()` and stores each offset in `rgoff`, a `Coord[]`. It then adds the offset to every anchor that element uses: each buff, `ip`/`oip`, `cdc`, `useul`, `ca`.
- A `null` offset skips the element's own painting and nothing else: `fxon`'s target arrow and the `lastact1`/`lastact2` tracking run as before.
- `tooltip()` reads `rgoff` and skips a `null` one.
- **A moved or re-homed `Fightsess` carries its regions.**
  - An addon may move the whole display with `@Fightsess:position(x, y)`. It may also take it into a surface of its own with `:parent(p)`, which runs `Fightsess.added()` again; the `rgns == null` guard keeps one region per role.
  - `off` converts `ul` through the painter's `rootpos()`, so an unheld region stands on the element wherever `Fightsess` stands. A held one keeps its own place.
  - `regions.md` states both ways of moving the display, and that a region is found by its role, never by its index among `@GameUI:children()`, which changes as the combat regions come and go.
  - 173.1's suite proves both paths.

**`GameUI`** stands three regions in `attached()`, after `super.attached()`, while `rgns == null`.
- Not in the constructor: there the HUD is in no tree, so `Layout.placed` has no `UI` to resolve a rule's anchor or the clamp in. `attached()` runs as the HUD enters the root, under the tree's monitor.
- `draw()` calls `off` for all three every frame and paints each element at its region.
- It writes `beltwdg.c`, and lowers `by` for the belt, only while `mine(beltwdg) && !AddonWidgets.held(beltwdg)`.
- The chat's place counts in that arithmetic only while `mine(chat)`: `by = min(by, chat.c.y)` and the belt's default read `chat.c`.
  - A chat an addon took into a surface, or stood in the world, has a `c` in another space. Today that pushes the line, and so the `hud.*` regions' defaults, off the screen.
  - With the chat elsewhere, `by` ignores it and the belt takes the place `GameUI.resize` gives it, `(blpw + UI.scale(10), sz.y - beltwdg.sz.y - UI.scale(5))`.
  - This extends the `mine()` rule `GameUI.resize` already keeps for the same widgets.
- The line's `UI.scale(20)` comes off `by` only while the line (the command line, or else the notice) is painted at its default, its region unheld. A line painted elsewhere leaves no gap above the hidden chat's lines.

**Selectors.** `Selector.WIDGET_ROLES` gains the eleven roles, and `LuaWidget.role` answers a region's first, so a role is a tree key. `Layout.ownBox` takes a region, making a rule's `size` inert. `:size(w, h)`, `:size(w)` and `:resizable(h)` refuse on a region, naming "paints it at its own size". The order writes refuse on it too (173.3).

### Order (173.3)

`widget:raise()` and `widget:lower()` call `Widget.raise`/`lower` under `LuaWidget.monitor(w)`. They are acts, never given back.

**`widget:z(n)` is a level**, folded like the text level and restored like a re-home.
- `LuaWidget.Moved` gains:
  - `zs`, the stock band;
  - `zAfter`, the sibling it followed (`w.prev`, `null` for the first child);
  - `wantZ` and `zSeq`, this addon's level and when it was named;
  - `zWrote`, the band the fold last wrote.
- `idle()` knows all of them.
- `Layout.applyZ(w)` runs inside `Layout.apply`'s block, beside `textHalf`, gated on `anyMoved`.
  - The latest level of any live owner wins (`LuaWidget.topWantZ`). The first touch records `zs` and `zAfter` through `UiApi.stockZ(w)`, the first live owner's, as `stockText` does, so a second addon records what the client had.
  - The write is `Widget.z(int)`, inside the monitor, since `Widget.z` takes no lock. It is skipped when `w.z` already stands there.
  - `zWrote` is set to the winning band whenever the fold stands the widget at it, written or already there. Otherwise a second addon naming the band already in force would hold no `zWrote`, and its `:z(nil)` would leave the band standing.
- **The restore puts the band back and then the place**: `Widget.z(zs)`, then one of three:
  - `zAfter` still stands in band `zs` under the same parent: `LuaWidget.relink(w, zAfter)`, right behind it;
  - `zAfter` is `null` or of a lower band, so the widget was the first of its band: `w.lower()`, which is `linkfirst` and lands it first within its own band. A window's first content child, following the deco at `-100`, goes back right behind the deco, and the lowered map goes back to the back;
  - `zAfter` has left that parent, or now stands in a higher band: it stays where `link()` put it, the front of its band.
  - Why: `Widget.z` re-links through `link()`, which puts the widget in front of every sibling of its band. On the map, which `GameUI` lowers, that would paint the whole map over the HUD. `Rehomed.after` and `relink` already solve this for `:parent(nil)`, but `relink` alone leaves a widget that followed another band at the front of its own.
- A band the server (`Widget.uimsg "z"`) or the client (`GItem.ContentsWindow.chstate`) wrote over the level stands: the restore runs only while `w.z == zWrote`.
- Every place a level is dropped knows z: `:z(nil)`, `UiApi.restoreMoved` (teardown), `UiApi.revertLevels` (`widget:revert()`).

**The band is an integer from `-9` to `9`**, `0` being every widget's own.
- A window's deco stands at `-100`, a hovered contents window at `90` and the item in hand at `100`. A band in that range moves a widget among its siblings without crossing any of them.
- The client's popups open at band `10`. One `// addon:` line at each place a popup is added to its root sets `z` before the add: `SListMenu.addat`, `SDropBox`'s list `add`, `BuddyWnd`'s menu. A dropdown, a list menu or a buddy menu opened over a surface at `:z(9)` paints over it and takes the click it shows. Those three lines are already this layer's (044.5's `popuproot()`).

**Refusals**, each naming why:
- a tree's root: it has no siblings;
- a widget the client stands directly on a session's root — the HUD, the login screen, a popup. That is the screen, and beside it stand only the client's popups and the drag in hand: a raised HUD would bury the server's flower menu while it holds the pointer;
- a column's or a row's child: `Column.placed(verb)`, its place is its order;
- a region: the client paints it in its painter's order;
- `:z` outside `-9`..`9`, with a surplus argument, or with a non-integer (`Args.integer`).

### Owner and layer (173.4)

- `widget:addon()` is `Owned.of(w).profOwner().manifest.id`, the string `pagina:addon()` already answers for a menu entry.
  - It is `"(console)"` for the `:lua` console's widgets.
  - It is the library's id for a widget built inside a library's call, since code there runs under the library.
  - It is `nil` for every widget no addon built, a piece the client builds inside an addon control included.
  - An argument refuses: nothing writes it.
- `:info().addon` is the same value, absent where it is `nil`.
- `hafen.ui():root()` is `UiApi.nodeRoot(owner, layer())`, with no arguments, and refuses one. The layer root is the client's widget and borrowed like any other, its `:parent()` is `nil`.
  - While a pointer grab or a drag runs, its children include the client's bridges (`LuaMouseGrab`, `Gesture`), which answer `:addon()` with `nil`.
  - The comment at `UiApi.java`'s `session:ui():root()` that says there is no layer twin is corrected.

### Fixes (173.5)

**The chat.** `ChatUI.move(base)` takes the chat's *bottom*: `c = base - sz.y` while visible, `c = base` while hidden. The `Spring` of a hide ends at `c = base`, and `:visible(false)` (`Widget.hide`) leaves `c = base - sz.y`. So `c` alone does not say where a chat stands.
- **`base` is the chat's one place.** `c` follows it whenever the chat is shown:
  - `ChatUI.show()` gains one `// addon:` line, `c = base.add(0, -sz.y)`.
  - Today nothing re-derives `c` on a show. `Widget.show` sets the flag, and `:visible(true)` is a plain `w.show()`. Only `ChatUI.resize`, `select` and `GameUI.resize` do it.
  - So a place written while the chat was hidden by `:visible(false)` would show the chat where the hidden `c` was, its own height low. Today it shows at the place but at the wrong base, and jumps at the next resize.
  - The `Spring` is unaffected: its `oy` is a field initializer that runs before the constructor's `show()`, and `ntick` rewrites `c` on every tick.
- `LuaWidget.place(w, c)` is the one write of a level's place. A `ChatUI` takes `move(c.add(0, sz.y))` whatever its visibility, and every other widget takes `move(c)`.
- `LuaWidget.at(w)` is the one read. For a `ChatUI` it is `base() - (0, sz.y)`, through a new `// addon:` getter `ChatUI.base()`; for every other widget it is `c`.
- Writes through `place`:
  - `Layout.applyHalf`'s two `w.move` calls;
  - `UiApi.restoreMoved`;
  - `WidgetSurface.reparent` for a `ChatUI` only, after its `np.add(w, at)`, since `ChatUI.added()` takes `c` as the base. Every other widget keeps the plain add: `place` is `move`, which a `Scrollbar` overrides to shift by `sflarp`;
  - `Column`'s child placement.
- Reads through `at`:
  - `:position()`;
  - `Layout.applyHalf`'s two comparisons;
  - `LuaWidget.handOf`, and so `landHand`, `retakeHand`, `placeNow` and the drag's hand;
  - **every record of where a widget stood, to be given back**, since every give-back of a chat now goes through `place`:
    - `Rehomed.at`, the place `:parent(p)` records (`new Coord(w.c)` today, in `LuaWidget`'s `:parent` verb);
    - the standing entity's `prevPos`, which `hafen.virtual():widget():add` records in `VirtualApi` (`new Coord(content.c)` today) and `LuaWidgetEntity.destroyed` gives back through `WidgetSurface.reparent`. `UiApi.stockPos` also reads it for a standing widget;
    - `UiApi.stockPos`'s last fallback, `w.c`.
  - Each record stays a fresh `Coord`. For a chat hidden by Ctrl+C, the raw `c` is its bottom. Given back through `place`, a raw record would land the chat its own height low: once shown, its top at the old bottom, or whole below the screen.
- A drag needs nothing: it starts on a shown chat, whose `c` is its place.
- **The chat's stock** is where the client puts it now, not a number from the first touch. For a chat directly on a `GameUI`, `UiApi.stockPos` answers `GameUI.chatbase()` (a new `// addon:` accessor, `Coord.of(blpw, sz.y)`, the base `GameUI.resize` gives it) less its height.
  - `UiApi.restoreMoved` writes that same live stock for such a chat instead of the recorded `m.pos`.
  - So `:position(nil)` and a teardown both land the chat whole on the screen at its current size, shown or hidden, after any screen resize.

**A widget covering its parent.** `LuaWidget.onScreen` answers `false` for a widget whose size is at least its parent's less one device pixel on both axes (`w.sz.x >= p.sz.x - 1 && w.sz.y >= p.sz.y - 1`), so `handOf`, `placeNow` and `rememberFrac` keep pixels.
- One pixel absorbs the design-to-device round trip: `Px.in(Px.out(n))` lands a pixel short at some scales, 1.75 among them.
- `WndPos` and the client's windows never ask `onScreen`. A remembered row carries its unit (`Placement.frac` or `pos`), so a fraction saved earlier is still put back as a fraction and saved again as pixels.

### The edition

`ApiVersion.CURRENT` becomes `1.5`. `manifest.md`'s edition tables, and every example manifest declaring `1.4`, follow.

## Files to create/modify

- Create `src/io/brodgar/ui/Region.java`, `docs/addons/api/ui/regions.md` and `addons/173-hud-regions.1` … `.5`.
- `src/haven/`, every line marked `// addon:`:
  - `Fightsess.java`, `GameUI.java` (`attached`, `draw`, `chatbase`);
  - `ChatUI.java` (`base()`, `show()`);
  - `AddonWidgets.java` (`held`, `POPUP_Z`);
  - `SListMenu.java`, `SDropBox.java`, `BuddyWnd.java` (the popup band).
- `src/io/brodgar/addon/`:
  - `AddonManager.java` (`regionStood`, `regionMoved`, the `Region` arm of `drainDisposedWidgets`);
  - `Selector.java`, `LuaWidget.java`, `Layout.java`, `UiApi.java`, `Column.java`, `WidgetSurface.java`, `VirtualApi.java` (`prevPos`), `ApiVersion.java`.
- `docs/addons/api/ui/`: `regions.md`, `selectors.md`, `native.md`, `writes.md`, `widget.md`, `custom.md`, `style/keys.md`, `style/geometry.md`, `README.md`.
- `docs/addons/api/`: `fight.md`, `console.md`, `types/ui.md`, `README.md`.
- `docs/addons/manifest.md` and the example manifests.
- `docs/client/`: `combat.md`, `console.md`, `gameui-windows.md`, `window-positions.md`, `widget-introspection.md`.

## Risks & gotchas

**Regions**
- `Fightsess.updatepos()` moves `pcc` at the top of every `draw`, and `tooltip` reads the last draw's `pcc`: hence the cached offsets.
- `GOut.aimage` truncates with `(int)` casts, so the IP box uses the same `(int)(sz.y * -0.5)`.
- `Bufflist.arrange` lays five to a row and `Buff.move(Coord, double)` animates `buff.c`, so the openings box comes from constants, never `buff.c`.
- `Widget.add0` calls `added()` again on a re-home, so `Fightsess` stands its regions only while `rgns == null`, and `GameUI` likewise in `attached()`. A server destroy reaches `Fightsess.destroy()` (`UI.destroy` → `reqdestroy`). Regions not dropped there outlive the fight.
- `Widget.add(T, Coord)` keeps the `Coord` by reference, so a region's `c` is always a fresh `Coord`.
- `AddonManager.posHeld` is one volatile read with nothing laid out, else a scan of each `movedNative`: eleven a frame.
- `GameUI.draw` assigns `beltwdg.c` before `super.draw`. That is why `beltwdg:position` never showed, and why a re-homed belt was written in HUD coordinates.
- `ConsoleHost.cmdtext` is private: the line's region is its slot.
- In world, the `:` line is `GameUI`'s own: `GameUI.globtype` → `entercmd`, painted by `GameUI.draw`. `docs/client/console.md` credits `RootWidget.draw`, the login screen's, and 173.2 corrects it.
- A region is painted in its painter's order, not its own: `Fightsess`'s place among `GameUI`'s children decides what covers a held combat element.
- A region hidden and left so follows `native.md`'s rule for a widget with no toggle: teardown leaves it hidden. `hud.cmdline` hidden that way leaves the `:` line typing unseen until a relog. `regions.md` says to put it back from `Disable`.
- An armed `:draggable` handle on a region takes the press over the element: a click on the world under `fight.action` starts the drag. An editor disarms it when editing ends.
- A rule's anchor needs a live target (`Layout.parseAnchor`), so a widget anchored to a combat region is anchored on that region's `Added`. A follower is re-derived on the next step, one frame behind a moving region.
- `Region.drop` runs inside `Fightsess.destroy()`, on the thread that applied the server's destroy. Its records are pruned later, on the step, by the disposal drain, which every widget `destroy()` reaches through `rdispose`.

**Order**
- `Widget.raise`/`lower` take `synchronized(ui)`, which is `LuaWidget.monitor(w)` itself, re-entrant. `unlink()` dereferences `parent`: refuse a root first.
- `Widget.z(int)` re-links through `link()`, to the front of the band. Popups (`SListMenu`, `SDropBox`, `BuddyWnd`, the server's `FlowerMenu`) are added to a root at band `0` and stand in front only by being added last.
- `ItemDrag` sets `z(100)` in its constructor only, and each drag builds a new one. `ContentsWindow.chstate` writes `90` or `0` and raises.
- `Column.relayout` places children in link order, which is why a column's child refuses the order writes as it refuses `:position`.

**Owner and layer**
- `Owned.of` answers a direct child only where `rootw() == w`, so `@GameUI` answers `nil`. A `CtlButton` built into it answers its builder.
- `tools/docverbs.py` skips the `ui` section, and `refusalverbs.py` already finds a `"root"` verb on `session:ui()`. Neither sees `hafen.ui():root(`, so 173.4 checks it by grep.

**The chat and the screen**
- `ChatUI.resize` re-derives `c` from `base` while visible, even for an unchanged size. `Layout.apply` lands size before place, so `place` reads the final height.
- `ChatUI.show()` is also called by the `Spring` constructor, for a hide as for a show. On a shown chat the new line writes the `c` it already has.
- The chat's own grip (`ChatUI.mousemove`) moves its top over a held place and keeps the bottom. The next fold puts the top back, as it puts back a client window the user dragged under a level.
- `WndPos.frac` keeps the old fraction (or `0`) on an axis with no free space. `WndPos.place` answers `0` for a fraction at or below `0`, `p - w` at or above `1`, and clamps into `[p - w, 0]` between. The client's windows store kept fractions in that shape, so only the addon level changes, through `onScreen`.

## Discarded alternatives

**Regions**
- **A mirror cropping a widget and forwarding clicks.** It redraws its source into a texture every frame, once per mirror, and the crop chases an element that follows the character.
- **`Fightsess` split into self-painting children.** It rewrites upstream's `draw` and `tooltip`, so every `/merge` conflicts. A region adds one offset.
- **Regions as children of their painter.** Under a full-screen parent a region is not on the screen, so it would lose the screen-following place, the clamp and fractional `:remember`.
- **"Piece".** `patch:piece()` already names ground.
- **One `region` role with a refiner.** A new refiner key for one family, where a role per element is what selectors and sheet keys already are.
- **A box measured from what is drawn now.** A held openings row would grow from its far edge.
- **A region the pointer hits.** An empty widget would answer `hit()` and `mouse():over()` over the world above the chat, where the click itself reaches the map.
- **The `hud.*` regions stood in the constructor.** The HUD is in no tree there, so a rule naming a region has no screen to resolve against.
- **Layout applied to every widget the client mints, at the entry drain.** It changes when a rule reaches every client window, a wider change than the regions need.
- **Followers moved by `Layout.moved` from `off()`.** It writes widgets from inside the draw; the geometry seam's queue is the door the step already drains.
- **An order of its own on a region.** The element is painted in its painter's order, so the verb would answer and change nothing.
- **A region placed outside the draw, in a tree nobody is drawing.** The element's place is the character's projection through the camera, which only a drawn tree has. Such a region keeps its last place, or `0, 0` with no size, and `regions.md` says so.
- **A region's hidden and re-home records left to the tree's death.** A region dies every fight, so they would pile up until a relog.
- **Every dead widget's hidden record pruned at disposal.** A client window that dies hidden may carry an addon's stand-in view, and its rule is not this feature's to change.

**The bottom-left line**
- **The `:` line as a replaceable widget.** It is `ConsoleHost` state with a key grab.
- **Message and command line both painted once apart.** A second rule for one slot. Upstream's exclusivity stays.

**Order**
- **`:raise()` without a band.** It lasts until the user presses another window; strata plus raise is the standard (WoW).
- **`:z` as an unrestored act.** A map left at `z 200` would outlive its addon.
- **`:z` restored through `Widget.z` alone.** It re-links to the front of the band, which puts the lowered map over the HUD.
- **One stock per addon and no fold.** Two addons' bands each restore what the other wrote, so a reload hands back a band whose addon is gone.
- **An unbounded band.** A band past `90` crosses a hovered contents window and the item in hand, and one past the popups buries a dropdown that holds the pointer.
- **Popups left at band `0` and positive bands refused on a root's children.** It takes the strata away from top-level surfaces, the case the band is for.

**Owner and layer**
- **`widget:addon()` as an Addon handle.** `pagina:addon()` already answers the id string, and one verb name answers one type on every receiver. The handle is `hafen.client():addons():get(id)` away.
- **The generic writes refused on a root.** It changes the refusals of verbs already published on `session:ui():root()`, and the layer root is already reachable through a surface's `:parent()`.
- **`surface:parent()` documented as the layer's door.** An accident of the tree. `hafen.ui():root()` is `session:ui():root()`'s twin.
- **`hafen.ui():match` on the layer.** A second spelling of `hafen.ui():root():match`.

**The chat and the screen**
- **A centre fraction for a full-screen widget.** `0` and `1` already mean glued, and an overlay's offset is pixels.
- **Writing the chat's `c` directly.** `ChatUI.resize` re-derives it from `base`, and the chat springs back.
- **The chat moved by `c + sz.y` only while visible.** A place written while hidden lands its own height high once shown, and a stock recorded while hidden restores the chat below the screen.
- **`place` through `move` for every re-homed widget.** `move` is overridden (`Scrollbar` shifts by `sflarp`), so a widget that today keeps the exact `c` it was added at would land elsewhere.
- **Leaving `c` alone on a show.** With `base` the one place, a chat shown after a hidden write would stand at the hidden `c`, its own height off.
- **The chat's stock recorded at first touch.** It is a place on the screen's old size, and while hidden it is the chat's bottom.
- **Re-applying held widgets every frame.** A cost on every held widget for one line.
- **A per-axis unit for a widget covering one axis.** The remembered row would need a shape an older build cannot read.
