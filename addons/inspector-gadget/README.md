# Inspector Gadget

A magnifying glass in the action menu. Turn it on and a tooltip beside the pointer says everything the
client knows about whatever object you are hovering.

## Using it

1. Open the action menu and press **Inspector Gadget**. The pointer becomes the game's own magnifying
   glass (`gfx/hud/curs/study`).
2. **Hover anything.** The tooltip appears beside the pointer and follows it: an **object** when there is
   one under the pointer, and otherwise the **ground** itself.
3. **Left-click an object** to run the game's own Inspect on it. The answer arrives where the game always
   puts it.
4. **Right-click the ground**, or press the button again, to put the glass away.

The entry is a Pagina like any other, so it drags onto an action bar.

**The glass takes two gestures and leaves the rest alone.** A left-click on an *object* is swallowed and
becomes the Inspect; a right-click on the *ground* puts the glass away. Everything else goes out untouched —
a left-click on the ground is the ordinary map click that walks you there, and a right-click on an object
still opens its flower menu.

## What the tooltip says

One row per question the object has an answer to, so a boulder shows three rows and a player a dozen.
Everything is read **live**, on every frame it draws — a boar that starts running says so while it runs.

| Row | What it is |
|---|---|
| `resource` | the object's resource identity, e.g. `gfx/kritter/rabbit/rabbit` — not a display name |
| `id` | the server's gob id |
| `place` | where it stands, in this character's world components |
| `tile` | the tile it sits in, on this character's lattice |
| `grid` | the durable form: a server grid id, and the offset **within** that grid |
| `range` | how far it is from the character that read it |
| `facing` | its facing, in degrees |
| `health` | remaining object integrity |
| `motion` | `still`, or `moving` and its speed |
| `player` · `kin` | whether it is a player body, and the kin standing there if you have them |
| `icon` · `says` | its minimap icon category, and its floating speech |
| `sdt` | the raw state bytes the server sent with its resource — a crop's stage, a gate's leaf |
| `pose` | the animation poses a **composed** body is in — a swing or a bite while one plays, the standing set otherwise |
| `drawn` | what the **game** is drawing at it: a lit fire's flame, a fight's effects |

Each row is named after the verb that reads it, so `sdt` is `gob:sdt()` and nothing has to be translated
back. It is shown as the bytes it is: what a byte *means* belongs to that resource's own published code, and
turning one into a name would be guessing.

`sdt` and `pose` are the two halves of one fact and never appear together: a composed body — a player, an
animal — is in poses, and a resource-drawn one — a tree, a crop, a gate — has state bytes. `drawn` is
neither: it is what the game hangs *at* the object, not what the object is doing.

## What the tooltip says about the ground

With no object under the pointer, the same tooltip describes the ground itself:

| Row | What it is |
|---|---|
| `resource` | the tileset the ground is drawn from, e.g. `gfx/tiles/forest` |
| `tileset` | the tileset id **this session** made up when the server named the set — what the wire carries |
| `place` | the point itself, in this character's world components |
| `tile` | the tile it falls in, on this character's lattice |
| `height` | the terrain height there |
| `grid` | the durable form: a server grid id, and the offset **within** that grid |
| `segment` | that grid's coord inside its map segment |

## Which object counts as hovered

**The client's own answer.** `hafen.ui():mouse():pick()` is the very pick pass a right-click goes through —
`MapView.Hittest` → `checkgobclick` → `clickedgob` — so the object described is exactly the object a click
would have reached. There is no approximation and nothing to tune.

**And the ground comes out of the same pass.** `m:ground()` is `Hittest`'s other half — `checkmapclick`
beside `checkgobclick`, one submission — so the tile shown is never one frame's while the object over it is
another's, and it costs no second readback.

Holding the `PickChanged` subscription is what makes the client run that pass at all, so with the glass down
this addon costs a menu button and nothing else. With neither an object nor ground — the sky, an inventory
window — the tooltip puts itself away.

## What the click sends

The Inspect is exactly the pair of messages a player produces by pressing Inspect and then clicking: the
`paginae/act/inspect` action, and then the click that targets the object. They go a tick apart, because each
write door lets one send out per frame, and they reach the server in that order.

That makes this addon a **protected-tier** one: its manifest declares `menugrid.use` (to fire the action) and
`gob.click` (to target it), and the AddOns panel asks you to consent to both when you enable it. Nothing else
here reaches the server.

## `:inspector`

Says what the pointer is on, right now:

```text
inspector: glass on, pointer 1078, 531, over MapView
inspector: pick says gfx/terobjs/stockpile-metal, ground 1204, -338, tooltip is showing gfx/terobjs/stockpile-metal
```

`pick says nothing` with the glass on means the client resolved the pointer to no object — and `ground` then
says whether that is bare ground or nothing at all. `over` naming something other than `MapView` means the
pointer is on a window, where nothing is picked on purpose.

## Notes

- The tooltip is one painter over the HUD. Nothing is attached to any object, so nothing survives the glass
  being put away and nothing needs cleaning up.
- **The addon's own click comes back through its own handler.** `s:world():click` sends the ordinary way —
  `Widget.wdgmsg`, which *is* the outbound action stream — so the handler sees it exactly as it sees the
  player's. Without a guard it swallows the very click it just asked for and starts the gesture again, which
  leaves the character stuck in the game's Inspect targeting mode. Any addon that both intercepts an action
  and re-issues one needs the same flag.
- The pick is one frame behind and paced by the client: one readback in flight at a time, re-run at once
  when the pointer moves and a few times a second while it holds still, since the world moves under a
  pointer that does not.
