# WASD Movement

**Four keys that walk the character the way the camera is looking**, the way an MMO does it. `W` goes up
the screen, `S` comes back down it, `A` and `D` go ninety degrees off to either side, and any two of them
together go diagonally between. Hold them and it keeps going; turn the camera while you hold them and the
walk comes round with the view; let go and it stops.

| You press | It does |
|---|---|
| **W** | walks away from the camera — up the screen |
| **A** | walks ninety degrees to the left of that |
| **D** | walks ninety degrees to the right of that |
| **S** | walks back towards the camera |
| **W + D**, and the other three pairs | walks forty-five degrees between the two |
| **W + S**, or **A + D** | nothing: they cancel, and the character stops |
| **drag the view**, with a key down | re-aims the walk to the way the camera now looks |
| let go | the character stops |
| **left-click**, as always | cancels the walk on the spot, the way it cancels any other |
| `:wasd` | turns the keys off, and on again |

## Bind the keys first

**Options ▸ Keybindings ▸ WASD Movement**, four rows, all of them unbound until you say otherwise:

```text
Forward (W)     Left (A)     Back (S)     Right (D)
```

They start unbound because the client gives one key to exactly one action, so a default this addon
claimed would lose every collision and leave you with a hotkey that silently never fired. Bare `W`, `A`,
`S` and `D` are free in this client — `Ctrl+A`, `Alt+A` and `Alt+S` are the only ones of the four spoken
for, and they are a different binding — so the four rows are yours to fill in. Type `:wasd` at any time
and it says how many are bound.

**Typing never walks you.** The addon wakes on a hotkey, and a hotkey is only offered a key that no text
field took first — so writing in chat, naming a marker or filling in a form starts nothing.

## A key is read against the camera

The direction is the **view's**, not the character's. `W` is away from the camera whatever the character
happens to be facing, so the keys mean the same thing on screen from one moment to the next, and turning
the character round with `S` does not turn the keys round with it.

`A` and `D` **turn and walk** rather than sidestep. The game has no sidestep: a character walks the way it
is going and the server turns the body to match. So the body swings round to face the way you sent it,
while the key that sent it goes on meaning left-of-the-screen.

Nothing here knows or cares which camera you are on. The bearing is read off the **projection**: the
addon projects the character, and a point one tile east of it, and a point one tile south, and those three
screen points give the little map from a step in the world to a step on the screen. Inverted, it answers
which step in the world draws straight up the screen — which is what `W` means. That works out the same for
every camera the client has, at any rotation, elevation and zoom, and on a hillside as well as on the flat.

## Two keys make a diagonal

Every tick, the keys that are **down** are added together as a direction on the screen: `W` is up, `D` is
right, so `W`+`D` is up-and-right — forty-five degrees between them. All four pairs work, and two opposite
keys cancel to nothing, which is standing still.

**Let go of half a diagonal and the other half carries on.** `W`+`D` released down to `W` alone straightens
out mid-stride, with nothing to re-press.

That last part is why this reads the keys rather than counting key presses. A hotkey fires when a key goes
**down** and has no counterpart for it coming up, and the desktop's key repeat cannot stand in for one:
every desktop repeats only the key you pressed **last**, so `W` held while `D` is tapped stops repeating and
never starts again. The client answers the question directly instead — `binding:down()`, which is *is that
key held right now* — and a diagonal is then just two of them being true at once.

## Turning the camera turns the walk

**Hold a key and drag the view, and the character comes round with it.** That is the whole difference
between this and clicking once where you happened to be looking: the keys and the camera are read twenty
times a second while anything is held, so the camera leads and the character follows.

An order to the server is not free, so one goes out when something has actually changed — a key up or down,
or the view come round by **four degrees**. A camera drifting a hair sends nothing, a key held dead still
sends nothing, and a camera you are dragging sends a few corrections a second, which is about what your own
hand on the mouse would send.

Between those corrections the character is walking the last bearing it was given, so a fast spin of the
view has it cutting a corner. Slow the drag and it tracks.

## What it sends

**One order per change, and nothing per frame.** The destination is a hundred tiles along the bearing —
far past anything on screen — and the server walks the character the whole way on its own, so a key held
for ten seconds costs exactly what tapping it costs.

A hundred tiles is longer than any hold, so in practice the character never arrives. Hold one key for the
better part of a minute and it will: the walk ends there, and the next change of key starts another.

## Turning it off

| Type | It does |
|---|---|
| `:wasd` | off if it was on, on if it was off |
| `:wasd on` / `:wasd off` | for when you would rather say which |

Turning it off stops whatever is walking and leaves the keys bound but inert — useful while you are
fighting, or driving something else with the same fingers. The choice is saved for the **account**, so it
holds across characters, reloads and restarts.

## What it asks for

One permission: `player.move` — *walk your character to a place*, which is the whole of what this addon
does. The client asks you to approve it the first time you enable the addon.

## What it drives

**The character on screen.** Tab to another and the keys drive that one instead; whatever the first was
walking is stopped as you go, so nothing is left wandering off behind you.

**Alt-tab lets go of everything.** A key held while the window loses focus has its release delivered to
whatever took the focus, so the client drops the whole set rather than leaving you walking off into the
distance. Come back with the key still physically held and it does nothing until you press it again.

Reloading or disabling the addon stops the character too. It has to: a walk sent to a point a hundred tiles
away outlives the code that sent it, and there would be nothing left running to call it back.
