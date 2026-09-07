# Builder helper

Remembers what every building site you have opened still needs, and one key floats each material's
`have/total` over the site itself, so a walk past your works tells you what to fetch.

## How it works

1. **Right-click a building site** (the stakes and string, `gfx/terobjs/consobj`), or **place a new
   one**. The window the server opens for it — *Stonestead*, *Palisade*, *Barter Stand*, any building —
   lists one material box per material. The addon reads each box's figure and material and files them
   under the **place** the site stands on, as a durable Position (grid id plus offset), in an account-scope
   saved variable. A window already open when the site is named is taken as its, so a freshly placed
   site's own window counts, and so does one you had open before right-clicking. Any other click on the
   map, left or right, on the ground or on another object, ends the gesture: a window opened after it is
   nobody's.
2. **Press the key** to show or hide the labels. Every remembered site in view wears a column of rows
   rising from the ground it stands on, one per material: the material's icon and its figure, white
   while short and green once complete. A site that walks into view later, or is seen by another of your
   characters, wears its column too.
3. While the site's window is open, every change the server reports is shown on the next frame.
4. **A site whose every material is complete is forgotten**, painter and record alike: there is nothing
   left to bring.

`:builds` lists every remembered site with its figures.

## Options

Options ▸ AddOns ▸ Builder helper:

- **Hide completed materials** — off by default. On, a material the site already holds all of is left
  out of the column instead of drawn green, so only what is still to bring is shown.

Suggested key: **Ctrl+B** — assign it in Options ▸ Game ▸ Keybindings ▸ Builder helper.

## Notes

- The figures are what the window drew when you last had it open; a site somebody else fills in your
  absence reads as it was until you open it again.
- A site removed by other means — torn down, or finished by someone else — keeps its record until you
  right-click the building that took its place. `:builds` shows what is remembered.
