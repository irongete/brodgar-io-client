# The example addons

The addons below ship with the client, in the same `addons/` folder yours goes into. They are working code
for every part of the API, and most of them are also the harness that keeps that part honest: they re-run
their own checks on every login. Read one when a reference page tells you *what* a verb does and you want
to see *how* it is used.

Most are **dormant** — installed and enabled, but drawing nothing and doing nothing until you press their
hotkey or type their command — so having them all on costs you an untouched login.

| Addon | Shows |
|---|---|
| [`hello`](../../addons/hello/main.lua) | the read, event and UI tiers, all of them, on every login |
| [`bags`](../../addons/bags/main.lua) | waiting for the client's own window, and standing yours in its place |
| [`theme`](../../addons/theme/main.lua) | a whole client look as a data file |
| [`atlas`](../../addons/atlas/main.lua) | a live minimap panel out of the map database, painted by the engine |
| [`stockfilter`](../../addons/stockfilter/main.lua) | a whole panel of the client's own controls, filtering your real items |
| [`planner`](../../addons/planner/main.lua) | your own props, images and models in the 3D world |
| [`cupboard`](../../addons/cupboard/main.lua) | a container window standing in the world, on the thing it belongs to |
| [`tagger`](../../addons/tagger/main.lua) | attaching things to a game object, and reading what is already on it |
| [`widgetstack`](../../addons/widgetstack/main.lua) | what a widget is, and how to name it |
| [`profiler`](../../addons/profiler/main.lua) | where the frame went |
| [`optionstest`](../../addons/optionstest/main.lua) | reading and writing the client's own settings |
| [`walker`](../../addons/walker/main.lua) | the protected write tier, one deliberate verb at a time |
| [`netdemo`](../../addons/netdemo/main.lua) | HTTP against a declared host allowlist |
| [`hogtest`](../../addons/hogtest/main.lua) | what happens to an addon that burns the frame |

## hello

The broad one: [events](api/event.md) and [timers](api/timer.md), [saved variables](api/store.md), a
window, a HUD overlay and per-gob overlays drawn with [the `g` wrapper](api/ui/drawing.md),
[world ghosts](api/vr/ghosts.md), [the kin roster](api/kin.md), [markers](api/map/markers.md),
[icon categories](api/map/icons.md), [container reads](api/ui/items.md) and the
[hide and replace](api/ui/replace.md) rules. It reads only — it declares no permissions — and it asserts
each surface at login rather than merely demonstrating it, so its console output is a pass list.

Hotkeys `toggle`, `ping`, `bags` and `marker`, plus `:hello <sub>` for the parts you ask for by hand.

## bags

The two verbs that replace a piece of the client's UI, end to end:
[`hafen.ui():on(selector, "appear", …)`](api/ui/replace.md#watching-for-a-widget) waits for the
inventory —
including one that is already open — and [`widget:replace(view)`](api/ui/replace.md) hides the stock window
and puts a custom one in its place, drawing the **real** items at their real grid positions while the
client keeps doing the work. The client's own Tab and menu button then drive your window.

Dormant until its `toggle` hotkey arms the replacement; the view is read-only, since moving an item is the
[protected tier](guides/actions-and-permissions.md).

## theme

A client theme that is **data, not code**: everything it looks like lives in a `theme.json` beside its Lua,
which never names a surface, a font, a colour or a pixel. It reads the file with
[`hafen.asset`](api/asset.md), parses it with [`hafen.json`](api/json.md) and hands the table to
[`sheet:load`](api/ui/style/README.md#a-sheet-from-data). Exactly two values in a rule are handles the
file cannot carry — a font face and an image — and it maps those two. It also keeps a window layout of
its own in [`hafen.store`](api/store.md), account-wide.

`:theme` applies, drops, saves and restores. To make a different theme, edit the JSON.

## atlas

A live minimap panel built from the [map database](api/map/README.md) alone. The only thing it reads from
the live world is *where am I* —
[a Position's durable form](api/world.md#the-position-type), the anchor the two
halves share; everything it shows comes out of `hafen.map`, and the picture is
[`grid:image(level)`](api/map/drawings.md), which answers `nil` while it renders, so its four-per-second
timer is both the retry loop and the "did the picture change?" test.

**It is also the cost claim.** A grid drawing is an ordinary image handle, so it goes into the stylesheet as
`bg = { image = … }` and the *engine* paints it: with its pin layer off, `atlas` runs **0 draw and 0 widget
callbacks** while a live map is on the screen. `:atlas pins` draws your markers from Lua and the same row in
[`profiling():addons()`](api/client/profiling/attribution.md) starts reading one a frame — which is what makes
the zero a measurement rather than a blind spot.

`:atlas` opens and closes it, `zoom <0..8>` changes the scale (never the size — every drawing is 100×100),
`pins` toggles the marker layer, `where` prints your anchor beside the segment id and grid coord this client
invented, and `mark [name]` drops a pin.

## stockfilter

A whole window of [the client's own controls](api/ui/controls/README.md), wired together rather than shown
one at a time: a label, a text entry, a separator, a radio group, a checkbox, a slider driving a live label,
a dropdown and a [scrolling list](api/ui/lists.md), each dressed by the stylesheet exactly like a window the
client built itself. Every filter reads the real [items](api/ui/items.md) in your backpack or your
equipment — by name, by quality, by wear — so there is nothing invented to look at.

`:stockfilter` opens the panel; running it again closes it. Read-only: nothing here writes to the server.

## planner

A base planner over the real terrain: translucent [ghosts](api/vr/ghosts.md) of the game's own props, your
own PNGs as [sprites](api/vr/sprites.md) and a glTF [model](api/vr/models.md), all placed, selected by
clicking, transformed and saved as one layout. The layout is anchored by
[grid position](api/world.md#the-position-type), so it comes back at the same spot,
facing and scale after a relog.

`:planner gizmo` gives it a drag gizmo — move, rotate, scale — built in Lua over the drawing and snapping
primitives, in a second file the manifest loads beside the first. `:planner grab` moves a prop by its body.

## cupboard

A window drawn **in the 3D world** instead of on the screen, standing on the game object it belongs to:
[`hafen.vr():widget():add(w, gob)`](api/vr/widgets.md) with `:facing("camera")` and an `:offset(0, 0, z)`
lift. What stands is the **client's own** cupboard window, re-homed rather than copied — so it is still bound
to its server id, still filling with items, and what you drag goes into it where it hangs. There is no view
to write and nothing to keep in sync.

It is **entirely event-driven**, which is the shape worth copying: three subscriptions and not one timer or
distance check. [`hafen.ui():on(sel, "appear", …)`](api/ui/replace.md#watching-for-a-widget) is the window
opening, [`w:on("ItemAdded"/"ItemRemoved", …)`](api/ui/items.md) keeps a snapshot of what is inside it
current, and `w:on("Destroy", …)` is the server closing it — at which point the panel in the world has
already ended with its content, and the addon stands a small panel of its **own** in the same place, drawn
from that snapshot. Open the window again and they swap back.

`:cupboard` arms and disarms it; `:cupboard <window title>` watches a different window. Dormant until then,
and read-only: dragging an item is your own hand, not the addon's.

## tagger

[`gob:overlay()`](api/overlay.md) from both ends. `:tagger` puts a green name and a ring over every
player body — a `text` and a `draw` record at the gob's projected **screen** point — and `:tagger pin`
floats a PNG 18 world units over the nearest object, this time a [sprite](api/vr/sprites.md) standing in the
world with the gob as its anchor, its setters chaining
(`:offset(0, 0, 18):tint(255, 200, 90):alpha(0.85)`). Nothing is polled either way: each follows the gob and
dies with it.

`:tagger read` is the other half of the same verb — it prints yours, and what you stood at the gob, beside
**the game's own**, which come back `native = true`, keyed by resource name, read-only, and counted
(`ov:count()`), because a native overlay is a union over that name. `:tagger watch` turns on
[`GobOverlayAdded`/`GobOverlayRemoved`](api/event.md#overlays-coming-and-going).

It is also what the missing filter form looks like in practice: "label every player" is a
[`GobAdded`](api/event.md#world) handler plus one loop over the players already here.

## widgetstack

The inspector. It shows the live stack of widgets under the cursor, outlines the hovered one, and opens a
browsable window for any of them: type, id, position, size, text, [role](api/ui/selectors.md#roles),
resource, parent and children. Its selector panel answers the question the
[selector grammar](api/ui/selectors.md) is useless without — what is this widget, and how do I name
it? — by offering every selector it can build from what the widget is, each one resolved before it is shown
and ready to paste into `:lua`. It builds **chains**, anchoring on the captioned window a widget sits in,
because that is usually what names one widget rather than several; and it reports what the answer cost, in
tree walks.

`:widgetstack` toggles it, `:selector` logs the current line, and the `freeze` hotkey holds the stack still
while you move the mouse to read it.

## profiler

A six-tab window over [`hafen.client():profiling()`](api/client/profiling/README.md): the frame graph and the
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

The [protected write tier](guides/actions-and-permissions.md) in one small addon: it declares
`"permissions": ["actions"]`, so it is disabled until you enable it and confirm the consent dialog. Nothing
it does is automatic — every verb is a deliberate `:walker <sub>`, and each is met on the page of the thing
it changes: [walking](api/player.md#write-protected-actions) and applying what is on your cursor,
[clicking a gob](api/gob.md#write-protected-actions),
[placing and area-select](api/world.md#write-protected-actions),
[the item verbs](api/ui/items.md#write-protected-actions),
[a menu action](api/menugrid.md#use-protected-actions), a [radial menu](api/flowermenu.md) petal, the raw
[widget message](api/ui/widget.md#send-a-message-protected-actions), and the writes in their own
namespaces — [speed](api/speed.md), [crafting](api/craft.md), the [action bar](api/actionbar.md) and the
[roster](api/kin.md).

`:walker petal` arms the next [radial menu](api/flowermenu.md) you open and picks from it — by caption,
by position on the ring, or cancelling it — from inside the event that says the menu is up.

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
