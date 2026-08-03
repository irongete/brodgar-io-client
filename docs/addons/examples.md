# The example addons

Ten addons ship with the client, in the same `addons/` folder yours goes into. They are working code for
every part of the API, and most of them are also the harness that keeps that part honest: they re-run their
own checks on every login. Read one when a reference page tells you *what* a verb does and you want to see
*how* it is used.

Most are **dormant** — installed and enabled, but drawing nothing and doing nothing until you press their
hotkey or type their command — so having them all on costs you an untouched login.

| Addon | Shows |
|---|---|
| [`hello`](../../addons/hello/main.lua) | the read, event and UI tiers, all of them, on every login |
| [`bags`](../../addons/bags/main.lua) | waiting for the client's own window, and standing yours in its place |
| [`theme`](../../addons/theme/main.lua) | a whole client look as a data file |
| [`planner`](../../addons/planner/main.lua) | your own props, images and models in the 3D world |
| [`widgetstack`](../../addons/widgetstack/main.lua) | what a widget is, and how to name it |
| [`profiler`](../../addons/profiler/main.lua) | where the frame went |
| [`optionstest`](../../addons/optionstest/main.lua) | reading and writing the client's own settings |
| [`walker`](../../addons/walker/main.lua) | the gated write tier, one deliberate verb at a time |
| [`netdemo`](../../addons/netdemo/main.lua) | HTTP against a declared host allowlist |
| [`hogtest`](../../addons/hogtest/main.lua) | what happens to an addon that burns the frame |

## hello

The broad one: [events](api/events.md) and [timers](api/timer.md), [saved variables](api/store.md), a
window, a HUD overlay and per-gob overlays drawn with [the `g` wrapper](api/ui/drawing.md),
[world ghosts](api/ghost.md), [the kin roster](api/kin.md), [markers](api/markers.md),
[radar categories](api/radar.md), [container reads](api/ui/items.md) and the
[hide and replace](api/ui/replace.md) rules. It reads only — it declares no permissions — and it asserts
each surface at login rather than merely demonstrating it, so its console output is a pass list.

Hotkeys `toggle`, `ping`, `bags` and `marker`, plus `:hello <sub>` for the parts you ask for by hand.

## bags

The two verbs that replace a piece of the client's UI, end to end:
[`hafen.ui.on(selector, "appear", …)`](api/ui/replace.md#watching-for-a-widget) waits for the inventory —
including one that is already open — and [`widget:replace(view)`](api/ui/replace.md) hides the stock window
and puts a custom one in its place, drawing the **real** items at their real grid positions while the
client keeps doing the work. The client's own Tab and menu button then drive your window.

Dormant until its `toggle` hotkey arms the replacement; the view is read-only, since moving an item is the
[gated tier](guides/actions-and-permissions.md).

## theme

A client theme that is **data, not code**: everything it looks like lives in a `theme.json` beside its Lua,
which never names a surface, a font, a colour or a pixel. It reads the file with
[`hafen.asset`](api/asset.md), parses it with [`hafen.json`](api/json.md) and hands the table to
[`hafen.ui.skin{…}`](api/ui/style/README.md). Exactly two values in a rule are handles the file cannot
carry — a font face and an image — and it maps those two. It also keeps a window layout of its own in
[`hafen.store`](api/store.md), account-wide.

`:theme` applies, drops, saves and restores. To make a different theme, edit the JSON.

## planner

A base planner over the real terrain: translucent [ghosts](api/ghost.md) of the game's own props, your own
PNGs as [sprites](api/render/sprites.md) and a glTF [model](api/render/models.md), all placed, selected by
clicking, transformed and saved as one layout. The layout is anchored by
[grid position](api/map.md#saving-a-world-position-across-sessions), so it comes back at the same spot,
facing and scale after a relog.

`:planner gizmo` gives it a drag gizmo — move, rotate, scale — built in Lua over the drawing and snapping
primitives, in a second file the manifest loads beside the first. `:planner grab` moves a prop by its body.

## widgetstack

The inspector. It shows the live stack of widgets under the cursor, outlines the hovered one, and opens a
browsable window for any of them: type, id, position, size, text, [role](api/ui/selectors.md#roles),
resource, parent and children. Its selector panel answers the question the
[selector grammar](api/ui/selectors.md) is useless without — what is this widget, and how do I name
it? — by offering every selector that matches, each one resolved before it is shown and ready to paste
into `:lua`.

`:widgetstack` toggles it, `:selector` logs the current line, and the `freeze` hotkey holds the stack still
while you move the mouse to read it.

## profiler

A six-tab window over [`hafen.client:profiling()`](api/client/profiling/README.md): the frame graph and the
thread phases, the render passes with their GL counters, per-widget cost, per-addon cost with each addon's
own scopes, the [pull-only counters](api/client/profiling/counters.md), and what profiling itself costs.
Pausing freezes the ring and turns the graph into a timeline you scrub frame by frame.

Dormant until its `toggle` hotkey or `:profiler`, which is the shape any profiling addon should have.

## optionstest

Every [client setting](api/client/README.md) an addon can reach, read at load time on the login screen —
where video and audio legitimately answer `nil` — and again in the world, where they are all real. The two
dumps side by side are the "before the client is up" rule made visible. Its writes are non-destructive
round-trips: read, write something different, read back, put the original value back.

`:opttest`, plus a registered hotkey and a temporary one it registers and drops again.

## walker

The [gated write tier](guides/actions-and-permissions.md) in one small addon: it declares
`"permissions": ["actions"]`, so it is disabled until you enable it and confirm the consent dialog. Nothing
it does is automatic — every verb is a deliberate `:walker <sub>`: walking, clicking a gob, using an item
on the ground, area-select, placing, a menu path, a flower petal, an item verb, and the write verbs that
live in their own namespaces — [speed](api/speed.md), [crafting](api/craft.md) and the
[action bar](api/actionbar.md).

## netdemo

[`hafen.http`](api/http.md) against a `network` block: its manifest lists three hosts and that list **is**
the allowlist, so anything else is refused at the call. It fetches JSON with a GET, posts a table and reads
the echo back, and parses both with [`hafen.json`](api/json.md). Nothing touches the network at login —
every request is a `:netdemo <sub>` you type.

The AddOns panel shows its `[net]` badge and the exact hosts before you enable it.

## hogtest

The [soft CPU budget](runtime.md#budgets-and-the-watchdog) from the wrong side. Armed, it burns about 15 ms
of Lua on every frame — over the per-tick budget, under the per-call cap — until the engine auto-disables
it for the session and puts the reason on its row in the AddOns panel. Arming is deliberate and its own
file explains it; leave it disarmed while you play.

## See also

- [getting started](getting-started.md) — before you read someone else's addon, write a small one
- [the guides](guides/README.md) — the task each of these addons is an answer to
- [the runtime](runtime.md) — where these folders live, and how the client loads them
