# Simple Minimap

**This addon takes the client's minimap out of the corner and puts it in a panel of its own.** Not a
minimap of its own — *the* minimap: the same `CornerMap` widget the client built, moved into a surface this
addon owns. So a click still walks you there, the wheel still zooms, and the icons, the markers and the
tooltips are all still the client's. Nothing here draws a map.

| You do | It does |
|---|---|
| enable it | the map moves into its own panel, on every character you have logged in |
| **drag the strip above the map** | moves the panel where you want it |
| **drag the corner below-right of the map** | makes the map bigger or smaller |
| type `:simpleminimap` | puts it away, or brings it back — the client's own corner returns while it is off |
| disable it, or `:reload` | gives the corner back exactly as the client had it |

Where you put it and how big you made it are remembered **for the account**: it is a fact about how you want
the HUD to look, not about who you are playing.

## What it takes, and what it gives back

While it runs, **the bottom-left corner is empty**: the carved plate the client blits round the map, the
map, claim and icon buttons beside it, and the little arrows that fold them away all go, and all come back
when the addon does.

They go as **two panels** rather than as a list of widgets, and that is not a shortcut. The plate and one
arrow hang in the panel the map came out of; the buttons and two more arrows hang in the panel beside it.
Put the buttons away one by one and an arrow is left floating over an empty corner, because that arrow is
their *sibling* and not their child. The panel the map came out of can be put away precisely because the map
is not in it any more.

The map is **still one character's widget**, so there is one panel per character, each showing that
character's own map. They share the place and the size, because the panel is where *you* want the map, not
where a character does.

## The box is the action bars'

The panel wears `gfx/hud/wnd` — the eight-piece frame the client paves its inventory squares, its portrait
and its bars with — on the same dark translucent field, at the same alpha. So it sits among the game's own
panels rather than beside them.

The order things are painted in is what makes it a panel rather than a box next to one: a widget draws its
**background**, then its **children**, then its **border**. The field is therefore under the client's map
and the brass over it, exactly as an action bar's field is under its buttons.

**The strip above the map is the handle, and it is a strip for a reason.** Arming the whole panel would take
every press on it — and a press that starts a drag does nothing else — so clicking the map would move the
window instead of walking you there. The strip stands on the field and covers none of the map, and the
corner that sizes it sits where the right-hand margin meets the bottom one, for the same reason.

Both are built **after** the map is taken in, which is what makes them reachable at all: a parent offers a
press to its children last-added first, and the map answers anything that lands on it.

## Making it look like something else

Every surface it draws is named, so a [theme](../themes) can dress it without this addon knowing themes
exist:

| Selector | The surface |
|---|---|
| `[name=simple-minimap/panel]` | the panel — its field, and the box around it |
| `[name=simple-minimap/grip]` | the strip you drag it by; bare, so a theme can make it visible |
| `[name=simple-minimap/sizer]` | the corner you size it by; bare for the same reason |

Out of the box the panel is one rule, and it is the action bars' own, letter for letter:

```json
"[name=simple-minimap/panel]": {
  "bg": { "color": [43, 51, 44, 127] },
  "border": { "box": "gfx/hud/wnd", "mode": "tile" }
}
```

Everything is **declared** rather than painted, so a rule of yours beats it per property — replace the
border and the field stays, or the other way round.
