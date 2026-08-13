# hafen.vr: a widget standing in the world

`hafen.vr():widget():add(w, anchor)` takes a [Widget](../ui/widget.md) off the flat UI and stands it **in the
3D world** — a window of your own, a control you built, or one of the client's own windows — on the same
client-only world-entity core a [sprite](sprites.md) uses. It is the fourth collection of the section, and it
is the one that is not a picture.

```lua
local cupboard = hafen.world():gob():nearest("cupboard")

local win = hafen.ui():window():title("Cupboard"):size(128, 96)
win:on("Draw", function(ev) ev:g():text("4 / 16 slots", 8, 8) end)

hafen.vr():widget():add(win, cupboard):facing("camera"):offset(0, 0, 12)
```

The shape that pays for itself is entirely event-driven:
[`hafen.ui():on(sel, "appear", …)`](../ui/replace.md#watching-for-a-widget) is the window opening,
`w:on("Destroy", …)` is the server closing it, and a panel of your own stands in the same place between the
two. No timer, and no distance check.

## The rule: if it works on screen, it works in the world

A standing widget is **the same widget**, drawn somewhere else. There is nothing to port and nothing to
re-register: the same `:on("Draw", …)`, the same `:on("MouseDown", …)`, the same
[controls](../ui/controls/README.md), the same [stylesheet](../ui/style/README.md) rules and the same button
callbacks. Clicking a button on a panel in the world runs the handler it ran on screen, dropdowns open,
tooltips appear, hover states light up, text entries take the keyboard, and items go in and out of a
container.

That holds because the surface is a real **root**, not a texture with clicks forwarded into it: standing a
widget moves it into that root, so the client's own hit-testing, focus, hover, popup placement and drag
gesture resolve there by themselves.

The widget stays live in every other sense. It is still in the tree, `:exists()` is still true, a
server-bound one is still bound to its id and still filling with [items](../ui/items.md) — what changes is
that it is no longer under the flat UI's hit-testing, so [`hafen.ui():at(x, y)`](../ui/widget.md) never
answers with it while it stands.

> **The title bar does not move it.** Dragging a window's caption is a flat-UI gesture and its place is now
> out in the world. One standing at a point is moved with `:position(p, a)`; one standing on a game object
> has no place of its own at all, because its place is that object's.

## The standing widget

The [shared vocabulary](README.md#one-vocabulary-four-kinds) — `:position`, `:offset`, `:rotate`, `:scale`,
`:alpha`, `:tint`, `:visible`, `:clickable`, `:exists` — plus the three verbs only a standing widget has.

| Method | Description |
|---|---|
| `x:widget()` | the [Widget](../ui/widget.md) that is standing — the same object you passed in |
| `x:facing()` / `x:facing(mode)` | how it meets the viewer: `"fixed"`, `"camera"` or `"screen"` — see [facing](#facing) |
| `x:screen(wx, wy)` | where a pixel of the panel is drawn, as two screen [design pixels](../ui/pixels.md), or `nil` — see [clicks](#clicks-are-the-widgets-own) |

`x:widget()` is read-only, like a ghost's resource and an object's mesh: standing another widget is another
`hafen.vr():widget():add(w, anchor)`, and taking this one back is `hafen.vr():widget():remove(x)`.

A panel's world size comes from the widget's own [design pixels](../ui/pixels.md), at **a hundred pixels to
the tile**, so a default `hafen.ui():window()` stands about two tiles across on every client, whatever
interface scale the user runs — the world is not the HUD. `:scale` adjusts it from
there, and a string [filter](README.md#the-collections-unprotected) over the collection matches the widget's
caption — the "Cupboard" window is found by its title, which is what anybody looking for it knows.

**A panel is composited, not cut out.** A window frame that is translucent on screen is translucent in the
world, and antialiased text keeps its soft edges: what the widget paints is blended against the scene behind
it. Only what is fully transparent — the margin a widget never paints — is dropped outright, and that is
what keeps a world quad from standing a rectangle of depth in front of the scene: a `"fixed"` or `"camera"`
panel occludes, and is occluded, over exactly the pixels it painted. `:alpha(a)` fades the whole panel on
top of that, and a faded one no longer writes depth at all, so the world comes through it.

## Facing

A panel is flat, so how it meets the viewer is a property of its own, and it is the same three modes a
[sprite](sprites.md#facing) has:

| Mode | What it draws |
|---|---|
| `"fixed"` | a **world quad** standing upright at the entity's own `:rotate` angle, drawn double-sided |
| `"camera"` | a **world quad that turns to the viewer**, in yaw and pitch, keeping its world size |
| `"screen"` | a **constant-size blit** at the anchor's projected point, drawn over the scene |

`"camera"` is the mode the spatial framing asks for: still world geometry, so it has real world size,
perspective and occlusion and shrinks with distance, and it is always square-on and readable. This client
looks down at the world from an angle, so an upright `"fixed"` panel is foreshortened where a camera-facing
one is not. `:rotate` sets the angle in `"fixed"` and is stored but unused in the other two.

> **A camera-facing quad rises along the camera's own *up* axis.** Tilt all the way to a top-down view and
> that axis is horizontal, so the panel lies in the horizontal plane through its anchor — and at ground level
> that is the terrain's own plane, which swallows it. Stand it on the object it belongs to and lift it with
> `:offset(0, 0, z)`; one standing at a **point** has no lift of its own, so give it a gob anchor or keep the
> camera tilted.

Writing `:facing(mode)` rebuilds the visual in place — the mode decides *which* thing is drawn — but the
**surface is untouched**: the offscreen pass, the texture and the widget inside it are the same objects
before and after, so a swap costs a quad, not a re-stand. Any other mode raises, naming the three.

## Clicks are the widget's own

There is no `:onClick` here, and that is the point: the other three kinds are pictures, so "it was clicked"
is the whole of what they have to say, while a widget answers a click the way it does anywhere else — its
own `MouseDown` at the pixel the pointer landed on, in the handler you already wrote for the flat UI.

```lua
local ok = hafen.ui():button():text("Sort")
ok:on("Pressed", function() hafen.log():write("pressed, in the world") end)
```

`x:onClick(fn)` raises, naming `x:widget():on("MouseDown", fn)` and the three keys beside it. What the entity
does carry is `x:clickable(b)`: **does this panel take the pointer at all**, default `true`. Set it `false`
and the panel is click-through — the click reaches the world beneath it, exactly as a click that misses the
panel always does.

Two verbs turn a screen point into a panel pixel and back, and they are exact inverses off one map, so *where
is my button on screen* and *what did the player click* can never disagree:

| Call | Returns | Description |
|---|---|---|
| `x:screen(wx, wy)` | `x, y` \| `nil` | where widget-local pixel `wx, wy` is drawn, in screen coordinates |
| `hafen.vr():pointer(key, x, y [, a])` | boolean | put the pointer on whatever is standing at screen point `x, y` |

Both pairs are [design pixels](../ui/pixels.md): the widget-local one is what `:size()` and `ev:x()` speak,
the screen one what [`hafen.ui():mouse()`](../ui/mouse.md) reports. So the two calls compose — feed
`x:screen(wx, wy)` to `hafen.vr():pointer` and the panel's own `MouseDown` lands back on `wx, wy`.

`key` is one of `"MouseDown"`, `"MouseUp"`, `"MouseMove"` or `"Wheel"` — the same four keys
[`widget:on`](../ui/widget.md#subscribing) answers to, so there is one input vocabulary and not two. `a` is
the button on a press or release (`1` left, `3` right, default `1`) and the amount on a wheel. It hands back
whether a panel took it; `false` means the point was on none, which is the moment the client's own world
click goes through untouched. `x:screen` answers `nil` when the panel is not being drawn or is
behind the camera.

Both are unprotected: this is the client's path from the map view inward, it cannot move the character, and
nothing reaches the server.

## Focus, popups and tooltips

These need nothing of you, because the surface is a root. A standing text entry takes the keyboard when it is
clicked and typed text arrives in it; [`w:focused()`](../ui/widget.md#read) answers whether a keystroke would
reach a widget, on a panel in the world exactly as on the flat UI.

> **A popup opens *inside* the panel, and is therefore clipped by it.** A dropdown's list, a right-click menu
> and a tooltip all resolve against the nearest root, which is the surface — so a list longer than the panel
> is cut off at its edge rather than spilling onto the screen. Size the panel for what it opens.

[`hafen.ui():tipAt(x, y)`](../ui/widget.md#getting-a-widget) answers which widget's tooltip the client would
show at a screen point, panels in the world included, so a tooltip over a standing widget resolves to that
widget and not to the map behind it.

## Standing the client's own windows (unprotected)

Point `:add` at one of the client's windows and it stands, unchanged and still server-bound: it goes on
filling with items, its buttons still reach the server, and items drag into and out of it while it hangs in
the world. Its own shortcuts come with it — the shift-wheel bulk transfer on a container moves items exactly
as it does on screen, because a widget that asks the tree which HUD it belongs to is answered with the one it
was standing out of. This is the same family of write as [`w:position(x, y)`](../ui/native.md) and
[`w:replace(view)`](../ui/replace.md) — a layer over the client's state, never a write into it — and it is
unprotected for the same reason: the clicks that reach the server are the ones the user makes with their own
hand, and only where the button is drawn has changed.

**One rule covers both provenances: standing a widget records where it was, and removing it puts it back
there.** `hafen.vr():widget():remove(x)` does it, and so do `:reload` and disabling your addon. A window of
the client's returns to the flat UI; a window of yours goes back to its default parent, visible, which is
where an unstood window of yours belongs — hide or destroy it yourself if that is not what you want.

What the record does **not** carry is visibility, and that is what makes it one rule rather than two:
standing hides nothing, so the widget's own `visible` is what the user was seeing the whole time it stood,
and it is carried through untouched. A standing window the user toggled off comes back off; one they were
looking at in the world comes back on screen.

**The toggle stays the client's.** Because standing hides nothing, Tab and the menu button go on opening and
closing a window that is standing in the world, and the menu tick goes on telling the truth about it. That is
the difference from [replace](../ui/replace.md), which *does* take the toggle: replacing decides what stands
in for the native window, standing decides where that thing is drawn. Different questions, so they compose —
stand the view you replaced with, and the client's own key drives a panel in the world.

**A standing entity ends with its content.** Destroy the widget, or let the server destroy the window, and
the entity goes with it: a panel whose content is gone shows nothing and can show nothing again, so the
surface is freed and the handle reports `:exists()` false, exactly as `:remove` would have left it. This is
the one ending that puts **nothing** back on the flat UI — there is nothing left to put — so a container the
server closes leaves the screen exactly as it would have if the window had never stood.

## What is refused

- **A widget that is already standing**, naming the addon that holds it. One widget stands in one place.
- **A widget inside one that is already standing.** A surface does not stand on another surface; two panels
  in the world are two `:add` calls, each on its own anchor.
- **The 3D view itself**, or anything containing it. A panel is drawn from the same frame that then draws the
  scene it stands in, so the world cannot stand inside itself. Point at one window, not at the whole
  interface.
- **Anything that is not a Widget**, naming the builders and the lookups that hand one back.
- **`:add` before you are in the world**, like every other kind: a thing in the 3D scene needs that scene.

## What it costs

A standing panel is a widget subtree and, where you painted one, a Lua `Draw` handler — so the cost is made
proportional to what is actually being looked at.

- **It redraws when it changes, not every frame.** A panel nothing changes costs one offscreen pass; changing
  a label costs exactly one more.
- **A panel outside the view is not drawn at all** — no `Draw` handler, no offscreen pass. `Tick` keeps
  firing, because ticking is logic and it happens in the widget tree a culled panel never leaves, so nothing
  inside it drifts out of date while you are facing the other way and the picture is correct the instant it
  comes back into view.

Being skipped for the view is not the same as being out of the scene: a panel standing at a point whose
ground is not drawn is not there at all, and [`x:drawn()`](README.md#the-ground-under-one-that-stands-still)
is what tells the two apart.

[`hafen.client():profiling():surfaces()`](../client/profiling/counters.md#surfaces) is where that is a
number rather than a claim: how many panels are standing, how many are being skipped right now, and the
uploads-against-frames pair that says whether one is repainting.

## See also

- [`hafen.vr`](README.md) — the section: the anchor, the shared verbs, and the whole-section switch
- [the Widget object](../ui/widget.md) — everything a standing widget still answers, unchanged
- [custom](../ui/custom.md) — building the window you stand, and its `Draw` and `Tick` callbacks
- [controls](../ui/controls/README.md) — the client's own controls, which work on a panel in the world
- [replace](../ui/replace.md) — the verb this one composes with, and the toggle it takes
- [native widgets](../ui/native.md) — the other layer-that-restores writes on a borrowed widget
- [the counters](../client/profiling/counters.md#surfaces) — what standing panels cost, measured
