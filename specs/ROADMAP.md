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
- a `haven.Progress` is only ever server-placed, so `widget:value(v)`'s refusal on one has no target a suite can reach (filed: 061)
- `widget:position(x, y)` writes through `Widget.move` and reads back `c`, so on a widget that overrides `move` — the chat, whose argument is its BASE — the pair does not round-trip and a drag lands its own height off (filed: 062)
- the stock half of a layout record is an absolute value taken at the first touch, so `widget:position(nil)`/`:size(nil)` after the client has re-laid the screen out restores a place or a box fitted to the OLD screen, and it stands until the client's next re-layout (filed: 062)

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
- **Minor client subsystems**: screenshots, custom cursors, graphics settings, polity, news, calendar, server-published windows, the party HUD (filed: 009)
