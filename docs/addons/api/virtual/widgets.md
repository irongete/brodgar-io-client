# hafen.virtual: A Widget Standing in the World

`hafen.virtual():widget():add(widget, anchor)` takes a [Widget](../ui/widget.md) off the flat UI and stands it in the 3D world, on the client-only core a [sprite](sprites.md) uses. The widget is a window of your own, a control you built, or one of the client's own windows. It is the one kind in the section that is not a picture.

```lua
local cupboard = hafen.session():current():world():gob():nearest("cupboard")

local info_window = hafen.ui():window():title("Cupboard"):size(128, 96)
info_window:on("Draw", function(draw_event) draw_event:g():text("4 / 16 slots", 8, 8) end)

hafen.virtual():widget():add(info_window, cupboard):facing("camera"):offset(0, 0, 12)
```

The event-driven shape: [`session:ui():on(selector, "Added", …)`](../ui/replace.md#watching-for-a-widget) is the window opening, `widget:on("Removed", …)` the server closing it, and a panel of your own stands between the two. No timer, no distance check.

---

## If it works on screen, it works in the world

| Rule | Detail |
|---|---|
| The same widget, drawn elsewhere | The same `:on("Draw", …)`, `:on("MouseDown", …)`, [controls](../ui/controls/README.md), [stylesheet](../ui/style/README.md) rules and button callbacks. Buttons run their handlers, dropdowns open, tooltips appear, hover states light. Text entries take the keyboard. Items go in and out of a container. |
| A real root | The surface is a root, not a texture with clicks forwarded. Standing a widget moves it into that root, so the client's own hit-testing, focus, hover, popup placement and drag gesture resolve there. |
| Still live | In the tree, `:exists()` true, a server-bound one bound to its id and filling with [items](../ui/items.md). It leaves the flat UI's hit-testing, so [`hafen.ui():hit(x, y)`](../ui/widget.md) never answers with it while it stands. |
| The title bar does not move it | Dragging a caption is a flat-UI gesture. One standing at a point moves with `:position(position, angle)`. One on a game object has no place of its own. |

## The standing widget

The [shared vocabulary](README.md#one-vocabulary-every-kind) (`:position`, `:offset`, `:rotate`, `:scale`, `:alpha`, `:tint`, `:visible`, `:clickable`, `:exists`) plus its own.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `panel:widget()` | [Widget](../ui/widget.md) | Unprotected | The widget standing, the object you passed in. Read-only: another widget is another `:add`. |
| `panel:facing()` / `panel:facing(mode)` | the panel | Unprotected | `"fixed"`, `"camera"` or `"screen"` ([facing](#facing)). |
| `panel:screen(widget_x, widget_y)` | `x, y \| nil` | Unprotected | Where a widget-local pixel is drawn, as two screen [design pixels](../ui/pixels.md). `nil` when the panel is not drawn or is behind the camera ([clicks](#clicks-are-the-widgets-own)). |

| Rule | Detail |
|---|---|
| Two objects, and a refusal names which | `panel has no verb '…'` lists what a thing in the world answers. `widget has no verb '…'` lists what one on the screen does. `panel:position()` is a [Position](../position.md). [`widget:position()`](../ui/widget.md#read-methods) is pixels within its parent. |
| World size | From the widget's [design pixels](../ui/pixels.md) at a hundred pixels to the tile. A default `hafen.ui():window()` stands about two tiles across, whatever interface scale the user runs. |
| Size decided when stood, and held | A widget over 2048 device pixels on a side is refused by `:add` naming the ceiling. The offscreen picture, the world quad and the click box are cut from that size at once. The widget inside is clipped by the panel, as inside a window. To stand a bigger one, take it down and stand it again. `:scale` adjusts from there. A string [filter](README.md#the-collections-unprotected) matches the widget's caption. |
| Composited, not cut out | A translucent frame is translucent in the world. Antialiased text keeps its edges. Only fully transparent pixels are dropped, so a `"fixed"` or `"camera"` panel occludes and is occluded over exactly what it painted. `:alpha(value)` fades the whole panel, and a faded one no longer writes depth. |

## Facing

The same modes a [sprite](sprites.md#facing) has.

| Mode | Draws |
|---|---|
| `"fixed"` | A world quad upright at the entity's `:rotate` angle, double-sided. Foreshortened under this client's angled camera. |
| `"camera"` | A world quad turned to the viewer in yaw and pitch, keeping world size, perspective and occlusion: the mode spatial framing asks for. `:rotate` stored but unused. |
| `"screen"` | A constant-size blit at the anchor's projected point, over the scene. `:rotate` stored but unused. |

| Rule | Detail |
|---|---|
| A camera-facing quad rises along the camera's up axis | Tilted top-down that axis is horizontal, so the panel lies in the plane through its anchor, which at ground level the terrain swallows. Stand it on its object and lift with `:offset(0, 0, z)`. One at a point has no lift, so give it a gob anchor or keep the camera tilted. |
| `:facing(mode)` rebuilds the visual, not the surface | The offscreen pass, the texture and the widget are the same objects before and after: a swap costs a quad. Any other mode raises naming the three. |

## Clicks are the widget's own

There is no `:onClick`. A widget answers a click with its own `MouseDown` at the pixel the pointer landed on, in the handler written for the flat UI. `panel:onClick(fn)` raises naming `panel:widget():on("MouseDown", fn)` and the keys beside it.

```lua
local sort_button = hafen.ui():button():text("Sort")
sort_button:on("Pressed", function() hafen.log():write("pressed, in the world") end)
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `panel:clickable(flag)` | the panel | Unprotected | Whether this panel takes the pointer at all, default `true`. `false` is click-through, the click reaching the world beneath. |
| `panel:screen(widget_x, widget_y)` | `x, y \| nil` | Unprotected | Where widget-local pixel `widget_x, widget_y` is drawn, in screen coordinates. |
| `hafen.virtual():click(key, x, y [, argument])` | `boolean` | `virtual.click` | Put the pointer on whatever is standing at screen point `x, y`. `key` is `"MouseDown"`, `"MouseUp"`, `"MouseMove"` or `"Wheel"`, the keys [`widget:on`](../ui/widget.md#subscribing) answers. `argument` is the button on a press or release (`1` left, `3` right, default `1`) and the amount on a wheel. `false` means no panel took it, the moment the client's own world click goes through. |

Both pairs are [design pixels](../ui/pixels.md): the widget-local one is what `:size()` and `event:x()` speak, the screen one what [`hafen.ui():mouse()`](../ui/mouse.md) reports. The two verbs are exact inverses off one map, so feeding `panel:screen(widget_x, widget_y)` to `hafen.virtual():click` lands the panel's own `MouseDown` back on `widget_x, widget_y`. Why `click` is keyed is on [the section page](README.md#clicking-what-stands-in-the-world-protected).

## Focus, popups and tooltips

| Rule | Detail |
|---|---|
| Focus | A standing text entry takes the keyboard when clicked. [`widget:focused()`](../ui/widget.md#read-methods) answers as on the flat UI. |
| Popups open inside the panel | A dropdown's list, a right-click menu and a tooltip resolve against the nearest root, the surface. A list longer than the panel is clipped at its edge. Size the panel for what it opens. |
| Tooltips | [`hafen.ui():tipAt(x, y)`](../ui/widget.md#getting-a-widget) answers the standing widget's tooltip at a screen point, not the map behind it. |

## Standing the client's own windows (unprotected)

Point `:add` at one of the client's windows and it stands, unchanged and server-bound. It fills with items, its buttons reach the server, items drag in and out, and its shortcuts come with it. The shift-wheel bulk transfer on a container works: a widget asking which HUD it belongs to is answered with the one it stood out of. The same family of write as [`widget:position(x, y)`](../ui/native.md) and [`widget:replace(view)`](../ui/replace.md). It is a layer over the client's state, unprotected because the clicks that reach the server are the user's own.

| Rule | Detail |
|---|---|
| Standing records where it was. Removing puts it back | `hafen.virtual():widget():remove(panel)`, `:reload` and disabling your addon all do. A client window returns to the flat UI. A window of yours goes back to its default parent, visible (hide or destroy it yourself otherwise). |
| Visibility is carried through | Standing hides nothing, so the widget's own `visible` is what the user saw the whole time: a standing window toggled off comes back off. |
| The toggle stays the client's | Tab and the menu button open and close a standing window, and the menu tick stays correct. [Replace](../ui/replace.md) takes the toggle. Standing decides where the thing is drawn, so the two compose. Stand the view you replaced with, and the client's key drives a panel in the world. |
| A standing entity ends with its content | Destroy the widget, or let the server destroy the window, and the entity goes: the surface is freed and `:exists()` is false. The one ending that puts nothing back on the flat UI. |

## It stands with the character you stood it from

The picture kinds [stand in the world](README.md#several-characters-one-world) and are drawn for any character on that ground. A panel draws a widget in the tree of the character it was stood from, painted and clicked through that tree. It is on screen while that character is. It is invisible, taking no clicks, while another is. The widget stays in that tree, ticking and filling. `panel:exists()` is true from every character, `panel:drawn()` answers whether it is in the scene you look at.

## What is refused

| `:add` on | Refusal names |
|---|---|
| A widget already standing | The addon that holds it: one widget stands in one place. |
| A widget inside one already standing | A surface does not stand on another surface. Two panels are two `:add` calls. |
| The 3D view itself, or anything containing it | A panel is drawn from the frame that then draws the scene it stands in. Point at one window. |
| Anything that is not a Widget | The builders and the lookups that hand one back. |
| A widget that has left the tree | The tree, not the value's type, so a stale handle never reads as a typo. Elsewhere a dead [Widget argument](../ui/widget.md) stops the write quietly and chains. `:add` mints the panel it hands back and has no `nil` to hand, so it raises. [`session:ui():on(selector, "Added", fn)`](../ui/replace.md#watching-for-a-widget) is where a widget that is up comes from. |
| Before you are in the world | A thing in the 3D scene needs that scene. |

## What it costs

| Rule | Detail |
|---|---|
| Redraws when it changes | A panel nothing changes costs one offscreen pass. Changing a label costs one more. |
| Outside the view, not drawn | No `Draw` handler, no offscreen pass. `Update` keeps firing, since ticking is logic in the widget tree. Nothing falls out of date, and the picture is correct the instant it returns. |
| Skipped is not gone | A panel at a point whose ground is not drawn is not in the scene at all. [`panel:drawn()`](README.md#the-ground-under-one-that-stands-still) tells the two apart. |
| Measured | [`hafen.client():profiling():surfaces()`](../client/profiling/counters.md#surfaces): how many panels stand, how many are skipped, and the uploads-against-frames pair that says whether one is repainting. |

---

## See Also

- [`hafen.virtual`](README.md) — the section: the anchor, the shared verbs, and the whole-section switch.
- [The Widget object](../ui/widget.md) — everything a standing widget still answers, unchanged.
- [Custom](../ui/custom.md) — building the window you stand, and its `Draw` and `Update` callbacks.
- [Controls](../ui/controls/README.md) — the client's own controls, which work on a panel in the world.
- [Replace](../ui/replace.md) — the verb this one composes with, and the toggle it takes.
- [Native widgets](../ui/native.md) — the other layer-that-restores writes on a borrowed widget.
- [The counters](../client/profiling/counters.md#surfaces) — what standing panels cost, measured.
