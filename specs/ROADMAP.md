# ROADMAP — future work and open findings

> **One line each**, and every line ends with `(filed: NNN)` — the feature that raised it. `/plan`
> removes the line it picks up. This is the ONLY place open work lives: a finding never sits in
> another tree, and never in a feature folder, which is frozen.

## Defects

- `ev:resend()` / `ev:send(t)` reach the server through `UI.rawWdgmsg` with no permission check while their siblings have one (filed: 055)
- a numeric **string** passes `v.isnumber()` in LuaJ, so `place`, `item:drop` and `widget:send` coerce one through (filed: 055)
- the other side of that coercion: the `!isstring() || isnumber()` idiom refuses `"42"` and `"061.8"`, so `entry:value(s)` and `radio:value(s)` reject a row or a line that merely scans as a number (filed: 061)
- `LuaWorldEntity`'s javadoc calls five retired verbs "the common handle verbs" (filed: 055)
- `widget:on(key, fn)` on a STALE widget refuses naming the key as unknown, before the check that says the widget left the tree (filed: 061)
- the layout half of the caption seam takes no widget, so a late-captioned window never lays its descendants out (filed: 049)
- `world():screenToWorld(sx, sy)` and `player():worldToScreen(p)` speak device pixels while every other screen coordinate is a design pixel (filed: 058)
- `hafen.ui():on(sel, event, fn)` is the one `:on` whose handle ends with `:remove()` instead of `sub:off()` (filed: 061)
- `widget:on("Destroy", fn)` stays silent for one of the CLIENT's widgets inside a window that is destroyed: only an addon's own are reported from the disposal recursion (filed: 061)
- the post-apply `uimsg` tap carries no args, so re-reading the widget is the only way to learn what the server wrote — a rewrite to the string a level already holds is indistinguishable from no rewrite (filed: 061)
- `widget:range()` reads `nil` on a BORROWED slider or scrollbar, so `widget:value(v)` can drive one but nothing can read the bounds it clamps into (filed: 061)
- the rest of that family — `:rows()`, `:rowHeight()`, `:cell()`, `:columns()`, `:source()`, `:image()` — reads through the owned adapter alone too, so the inspector reports no configuration at all for one of the client's own lists, pictures or icon buttons (filed: 063)
- a `haven.Progress` is only ever server-placed, so `widget:value(v)`'s refusal on one has no target a suite can reach (filed: 061)
- `widget:position(x, y)` writes through `Widget.move` and reads back `c`, so on a widget that overrides `move` — the chat, whose argument is its BASE — the pair does not round-trip and a drag lands its own height off (filed: 062)
- the fill meter is reachable through `item:contents()` alone, and holding nothing is what makes that nil, so an EMPTY container that still states a capacity has no read for it (filed: 064)
- `GItem.NumberInfo` is the one door for every number drawn on an icon, so nothing distinguishes an amount from a gilding count and `item:quantity()` answers both without being able to say which (filed: 064)
- `widget:size(w, h)` on a window writes its CONTENT size and `widget:size()` reads back `Widget.sz`, which on a window is the OUTER box the chrome draws — so the pair does not round-trip, `Window.csz()` is reachable from nothing, and the docs say content for both (filed: 065)
- a font asset is loaded from the `File` itself, so the JVM holds that file open for the client's whole life: an addon's own `.ttf` cannot be replaced or deleted while it runs, and `:dispose()` does not release it (filed: 065)
- `Window.DefaultDeco.drawframe` blits `cap.tex()` unguarded, so a window built with a null caption throws on its first frame — the sheet-fed deco guards the same blit and the stock one does not (filed: 065)
- `ICheckBox`'s server-side factory loads BOTH hover faces from `args[1]`, so a resource-placed picture checkbox wears its `down` art for `hoverup` and never reads `args[2]`/`args[3]` at all (filed: 065)
- the stock half of a layout record is an absolute value taken at the first touch, so `widget:position(nil)`/`:size(nil)` after the client has re-laid the screen out restores a place or a box fitted to the OLD screen, and it stands until the client's next re-layout (filed: 062)
- `Speaking.draw` blits its finished text under a flat `chcolor(Color.BLACK)`, so the `color` a `world.speech` rule bakes into the raster through `fixcol` is multiplied away and the bubble's text is black whatever the rule says (filed: 065)

## Candidates

- **Sandbox hardening**: per-env string metatable, an addon-folder-only `require`, and the draw-time CPU budget gap (filed: 005)
- **Hook priority and L4**: integer priority with first-cancel-wins, and method replacement through a factory seam (filed: 007)
- **Lifecycle conveniences**: single-addon `:reload <id>`, and enable/disable without a full layer reload (filed: 005)
- **Finer events**: per-slot equipment changes and a skills-changed event (filed: 003)
- **World-space text**: a label standing in the world with perspective and occlusion, instead of a whole widget (filed: 043)
- **World-space shapes**: lines, polylines and filled areas in the world, which nothing can draw but a fan of sprites (filed: 043)
- **`dependencies` are parsed and never read**: either drop the manifest fields or order the load and refuse a missing one (filed: 051)
- **Package layout**: finish the tier-3 split — the serializer, the glTF parser and its mesh primitives (filed: 019)
- **Allocation profiling**: who costs GARBAGE, bracketing the seams 019 already brackets (filed: 019)
- **Driving an inbound message**: nothing can make the client apply a `uimsg` from Lua, so a feature riding the inbound tap has no in-game oracle at all on a server that never sends one (filed: 061)
- **Driving an input event**: nothing can make the client deliver a click or a keypress from Lua, so anything that only happens on a real gesture — a capability key firing, and therefore a subscription's removal — can be checked by hand alone (filed: 061)
- **Nothing reads what a SITE key resolved**: `widget:style()` answers tree levels alone, so a suite can read back only what a rule wrote and never which state face a surface actually wore — the `checked`, `hover` and `pressed` halves of every chrome rule are checkable by eye alone (filed: 065)
- **A control's disabled state has no verb**: nothing greys a button from Lua, so the `disabled` face of a state-varying rule has no target an addon can reach at all, by hand or otherwise (filed: 065)
- **`docs/addons/api/types.md` is at its 300-line ceiling**: the snapshot catalogue needs a split by subject, so a feature adding one shape stops pushing it over (filed: 064)
- **`docs/addons/api/ui/style/chrome.md` is at its 300-line ceiling**: its per-surface prose (the ornaments) is what `surfaces.md` is for, so the property pages stop growing with every surface routed (filed: 065)
- **`docs/client/ui-chrome.md` is at its 150-line ceiling**: the boxes drawn in code and the addon seam are two maps in one page, so the next surface routed pushes it over (filed: 065)
- **`docs/client/ui-lists.md` is at its 150-line ceiling**: the sliders, the text field and the model-backed lists are three maps in one page, so the next control read pushes it over (filed: 065)
- **`docs/addons/api/ui/style/surfaces.md` is at its 300-line ceiling**: one section per site key, so every key this grammar adds pushes it over — the split is by surface, the text sites apart from the ones that draw a box (filed: 065)
- **Minor client subsystems**: screenshots, custom cursors, graphics settings, polity, news, calendar, server-published windows, the party HUD (filed: 009)
