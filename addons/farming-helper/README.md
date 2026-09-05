# Farming Helper

Puts the growth stage over every planted crop in sight, at ground level.

The number is the stage the **server** sent with the object. A crop's resource is handed a few state
bytes with it and reads the first of them to pick which mesh to draw — a sprout, a half-grown plant, a
ripe one — so the number over a crop is the very byte the game is drawing that plant from. It is printed
1-based, so a freshly sown field reads `1`.

It is drawn **white and bold on a black outline**, so it reads over pale soil and dark leaves alike, centred
**on the ground the plant stands on** rather than floating over it, and inside the 3D view: every window
covers it.

## Using it

**Suggested key: `Ctrl+F`** — assign it in Options ▸ Game ▸ Keybindings ▸ Farming Helper. Addon hotkeys
start unbound, so nothing happens until you assign one.

The key toggles the numbers for every character you have logged in. While they are on, a crop that walks
into view is picked up as it arrives, one that advances a stage repaints itself, and one that is harvested
stops being drawn.

The trellis (`gfx/terobjs/plants/trellis`) is left out — it is the frame grapes and hops grow on, not a
crop, and carries no stage of its own.

## What it does not tell you

**How many stages that crop has.** The client publishes the state bytes but not the resource's mesh
layers, and the number of stages lives in those layers — so there is no honest way to say *ripe* here,
and this addon does not guess at one. Learn each crop's last stage once and the number is all you need.
