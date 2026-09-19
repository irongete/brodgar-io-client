# 157 — mirror: a surface showing another widget's live picture

## What & why

`hafen.ui():mirror()` builds a surface of your addon's whose picture is another widget's, redrawn
every frame: `mirror:source(widget)` names any widget of any tree, the client's or an addon's,
drawn or not. The mirror stands wherever a surface of yours stands — the addon layer, or a tree of
a character's through `:parent(w)` — and scales the picture into its own box.

Today a picture the client composes at runtime — the HUD portrait's 3D avatar, the corner
minimap's rendered ground, a meter's server-coloured fill — can be shown in exactly one place: the
tree it stands in. `widget:parent(p)` moves it into a surface built in that same tree and refuses
every other, because the widget reads its session; the addon layer has none; and only the session
on screen is drawn. So a dock in the layer listing every logged-in character cannot show their
portraits, and the portrait of a character nobody is looking at is shown nowhere. The client
already draws a widget subtree into a texture for the panels standing in the 3D world; the mirror
is that pass with a 2D blit at the other end, and the source left exactly where it is.

## Acceptance criteria

1. `hafen.ui():mirror()` is a builder like `hafen.ui():widget()`: no arguments (an argument is
   refused naming the chained form), built bare in the addon layer, `:parent(w)`, `:position(x, y)`,
   `:visible(b)`, `:name(word)`, `:stock(t)`, `:tooltip(s)`, `:destroy()` and every universal
   subscription (`MouseDown` and the rest) answer on it. `:type()` reads `MirrorWidget`.
2. `mirror:source(w)` takes a Widget of any tree and chains; `mirror:source()` reads back the same
   Widget object (`==` holds), `nil` before the first write and `nil` once the source has left its
   tree. A source is never moved, hidden, resized or re-parented by the mirror: its `:parent()`,
   `:position()`, `:size()` and `:visible()` read the same before and after.
3. The picture is the source's own draw, subtree included, re-made every frame the mirror is shown
   and scaled into the mirror's box. A source in a session nobody is looking at is drawn with its own
   session behind it: a portrait shows that character, a minimap that character's ground.
4. The box: `:source(w)` sizes the mirror to the source's box unless `:size(w, h)` pinned one,
   which a later `:source(w)` keeps; `:size(nil)` gives the box back to the source's; `:size(w)` is
   refused naming `:size(w, h)`.
5. Refusals name their reason: a source that is not a Widget names what `:source` takes; a mirror
   named as its own source, or a source the mirror stands inside, is refused as a mirror inside its
   own picture; a source over 2048 device pixels on a side is refused naming the ceiling.
6. A press on the mirror is the mirror's own: `MouseDown` fires on it and nothing reaches the
   source. `hafen.ui():hit(x, y)` over the mirror answers the mirror.
7. `:destroy()` frees the picture and leaves the source standing; `:reload` and disable take every
   mirror down the same way. Two mirrors of one source both draw.

## Out of scope

- Input forwarding: a click on a mirror never reaches the source. A widget clicked from elsewhere
  is [`hafen.virtual():widget()`](../../docs/addons/api/virtual/widgets.md)'s ground, where the
  widget itself stands in the surface.
- Redraw on change: a mirror re-draws its source every frame it is shown. The pictures a mirror
  exists for move every frame, and a dirty signature over a client subtree is a cost of its own.
- A mirror of a widget standing in the 3D world (`hafen.virtual():widget():add`) is not promised.

## Docs impact

- New: `docs/addons/api/ui/mirror.md`.
- Rows: `docs/addons/api/ui/README.md` (pages table), `docs/addons/api/README.md` (The UI),
  `docs/addons/api/ui/custom.md` (builders table).
- Derived impact set — `grep -rn -i "go dark\|cannot redraw\|nobody is looking" docs/addons`:
  `docs/addons/api/ui/native.md:206` ("A move, not a copy … which `replace` cannot redraw") and
  `:208` ("a widget taken there would go dark") gain the mirror as the other reach;
  `docs/addons/api/ui/widget.md:32` ("A session nobody is looking at keeps its whole tree") gains
  one clause; `docs/addons/api/ui/items.md:15`, `replace.md:37`, `selectors.md:16` stay true and
  are discharged as read.

## Context files

- `src/io/brodgar/addon/WidgetSurface.java` — 1 (the offscreen pass and the flipped blit to copy)
- `src/io/brodgar/addon/CImg.java` — 1 (the picture control: the pin, `:source`, the draw to the box)
- `src/io/brodgar/addon/Controls.java` — 1 (`Controls.source` read and write, `Controls.Source`)
- `src/io/brodgar/addon/Owned.java` — 1 (`Owned`, `Owned.State`)
- `src/io/brodgar/addon/UiApi.java` — 1 (the builders, `attach`, `requireUi`, `DEF_W`/`DEF_H`)
- `src/io/brodgar/addon/LuaWidget.java` — 1 (`size(nil)`'s `CImg` branch, `resolve`, `live`, `of`, `typeName`, `monitorOf`)
- `src/io/brodgar/addon/AddonManager.java` — 1 (`drawSurfaces`, `log`)
- `src/haven/UILoop.java` — 1 (`display`: where the pass is issued)
- `src/haven/PView.java` — 1 (`draw` renders to its own target then `resolve`s into the given `GOut`; `lastout`, the once-per-frame seam)
- `docs/addons/api/ui/controls/display.md` — 1 (the picture section, the page's shape)
- `docs/addons/api/ui/custom.md`, `docs/addons/api/ui/native.md`, `docs/addons/api/ui/widget.md`, `docs/addons/api/ui/README.md`, `docs/addons/api/README.md` — 1
- `docs/client/world-3d.md` (Render to texture), `docs/client/multi-session.md` (Everything not drawn), `docs/client/widget-draw.md` — 1
- `tools/docverbs.py` — 1 (`RECEIVERS`: the `mirror:` spelling)
