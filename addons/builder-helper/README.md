# Builder helper

Remembers what every building site you have opened still needs, and one key floats each material's
`have/total` over the site itself, so a walk past your works tells you what to fetch.

## How it works

1. **Right-click a building site** (the stakes and string, `gfx/terobjs/consobj`), or **place a new
   one**. The window the server opens for it — *Stonestead*, *Palisade*, *Barter Stand*, any building —
   lists one material box per material. The addon claims that window for the site and then **reads it
   whole**: its boxes, in the order the window lists them, are the materials, and they are filed under the
   **place** the site stands on, as a durable Position (grid id plus offset), in an account-scope saved
   variable. It is read again whenever what it says can have changed, so the record is always the window's
   own answer and a reading that went wrong is rewritten rather than patched.

   Which window is the site's: a build window that opens while your right-click stands is that click's —
   including one that opens when you arrive after walking over, and clicks on the ground on the way are
   just you steering the walk. A window **already open** — for a site you just placed, or one you had open
   before the addon loaded — is taken once your character has stood still for three seconds with no window
   come for the click, which is the case of right-clicking a site whose window already stands. A click on
   any other object, or placing something, ends the gesture: a window opened after it is nobody's, until
   the next right-click on a site takes it.
2. **Press the key** to show or hide the labels; the client says which it is now, and remembers it, so a
   reload or a restart leaves them exactly as you left them. Every remembered site in view wears a column of
   rows rising from the ground it stands on, one per material: the material's icon and its figure, white
   while short and green once complete. A site that walks into view later, or is seen by another of your
   characters, wears its column too — from its first frame, or from the first frame after its ground and its
   name have resolved, when they arrive ahead of the map. Right-clicking a remembered site hangs its label
   too, whether or not a window comes.
3. While the site's window is open, every change the server reports is shown on the next frame.
4. **A site whose every material is complete is forgotten**, painter and record alike: there is nothing
   left to bring. Take a material back out while its window is still open and the record comes back.
5. **Right-click the building that took a site's place** — finished by you or by someone else, or
   whatever now stands on that exact spot — and the site's record is dropped.

`:builds` lists every remembered site, each material named as the window drew it, marking `[window]` the
ones a build window is standing for right now — those are the ones whose figures move as you build — and
naming any build window that is open and claimed by nothing. `:builds forget` drops every record.

## Options

Options ▸ AddOns ▸ Builder helper:

- **Hide completed materials** — off by default. On, a material the site already holds all of is left
  out of the column instead of drawn green, so only what is still to bring is shown.

Suggested key: **Ctrl+B** — assign it in Options ▸ Game ▸ Keybindings ▸ Builder helper.

## Notes

- The figures are what the window drew when you last had it open; a site somebody else fills in your
  absence reads as it was until you open it again.
- A site's figures move as you build only while a window is **claimed** for it. A window you had open
  before the addon loaded is claimed by right-clicking its site and standing still: three seconds without a
  window of its own, and the click takes the open one. `:builds` says which are claimed.
- A site torn down keeps its record until you right-click whatever stands on its place, or `:builds forget`.
