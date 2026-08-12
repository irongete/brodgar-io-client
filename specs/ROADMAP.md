# ROADMAP — future work and open findings

> **One line each**, and every line ends with `(filed: NNN)` — the feature that raised it. `/plan`
> removes the line it picks up. This is the ONLY place open work lives: a finding never sits in
> another tree, and never in a feature folder, which is frozen.

## Defects

- `ev:resend()` / `ev:send(t)` reach the server through `UI.rawWdgmsg` with no permission check while their siblings have one (filed: 055)
- a numeric **string** passes `v.isnumber()` in LuaJ, so `place`, `item:drop` and `widget:send` coerce one through (filed: 055)
- `LuaWorldEntity`'s javadoc calls five retired verbs "the common handle verbs" (filed: 055)
- `widget:on(key, fn)` on a STALE widget refuses naming the key as unknown, before the check that says the widget left the tree (filed: 061)
- the layout half of the caption seam takes no widget, so a late-captioned window never lays its descendants out (filed: 049)
- `world():screenToWorld(sx, sy)` and `player():worldToScreen(p)` speak device pixels while every other screen coordinate is a design pixel (filed: 058)
- `hafen.ui():on(sel, event, fn)` is the one `:on` whose handle ends with `:remove()` instead of `sub:off()` (filed: 061)
- the post-apply `uimsg` tap carries no args, so re-reading the widget is the only way to learn what the server wrote — a rewrite to the string a level already holds is indistinguishable from no rewrite (filed: 061)

## Candidates

- **Sandbox hardening**: per-env string metatable, an addon-folder-only `require`, and the draw-time CPU budget gap (filed: 005)
- **Hook priority and L4**: integer priority with first-cancel-wins, and method replacement through a factory seam (filed: 007)
- **Lifecycle conveniences**: single-addon `:reload <id>`, and enable/disable without a full layer reload (filed: 005)
- **Finer events**: per-slot equipment changes and a skills-changed event (filed: 003)
- **World-space text**: a label standing in the world with perspective and occlusion, instead of a whole widget (filed: 043)
- **World-space shapes**: lines, polylines and filled areas in the world, which `planner` fakes with sprites today (filed: 043)
- **`dependencies` are parsed and never read**: either drop the manifest fields or order the load and refuse a missing one (filed: 051)
- **Package layout**: finish the tier-3 split — the serializer, the glTF parser and its mesh primitives (filed: 019)
- **Allocation profiling**: who costs GARBAGE, bracketing the seams 019 already brackets (filed: 019)
- **Driving an inbound message**: nothing can make the client apply a `uimsg` from Lua, so a feature riding the inbound tap has no in-game oracle at all on a server that never sends one (filed: 061)
- **Minor client subsystems**: screenshots, custom cursors, graphics settings, polity, news, calendar, server-published windows, the party HUD (filed: 009)
