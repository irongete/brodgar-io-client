# hafen.ui: Regions

A region is an empty widget on the HUD standing for one element the client paints by hand, so you move, hide and anchor to that element with a widget's own verbs.

```lua
local session = hafen.session():current()
session:ui():on("fight.cooldown", "Added", function(cooldown)
  cooldown:position(40, 40)                                  -- the cooldown circle, top left
  session:ui():match("fight.opening.mine"):visible(false)    -- your openings, not painted
end)
local sheet = hafen.ui():sheet()
sheet:rule("fight.action"):position(400, 500)                -- every fight's combat row, there
sheet:install()
```

---

## The regions

Each region is named by its role, a [selector](selectors.md#roles) and a sheet [tree key](style/keys.md#tree-keys). `:role()` answers it, `:type()` answers `Region`.

| Role | What the client paints there | The box |
|---|---|---|
| `fight.opening.mine` | Your [openings](../fight.md#an-opening), left of your character. | Five icons and their gaps wide. As tall as the rows the openings fill, at least one icon. The row fills from its right edge. |
| `fight.opening.theirs` | The target's openings, right of your character. | The same box. The row fills from its left edge. |
| `fight.ip.mine` | Your IP, `IP: n`, left of your character. | The text. Size zero without a target. |
| `fight.ip.theirs` | The target's IP, right of your character. | The text. Size zero without a target. |
| `fight.cooldown` | The cooldown circle under your character. | The circle's frame. |
| `fight.last.mine` | The manoeuvre you used last, left of the cooldown. | Its frame, with or without a manoeuvre to show. |
| `fight.last.theirs` | The manoeuvre the target used last, right of the cooldown. | Its frame, with or without a manoeuvre to show. |
| `fight.action` | The [combat row](../fight.md#the-fight-in-progress), under the cooldown. | Five actions wide, one row of five per five actions. |
| `hud.cmdline` | The [command line](../console.md) while you type at `:`, bottom left. | The line's slot: as wide as the chat less 10, 20 tall, its bottom on the top of the belt, of the chat while it is shown, or of the screen, whichever is highest. |
| `hud.message` | The last notice, for three seconds, while no command line is open. | The same slot. |
| `hud.chat` | The newest chat lines while the chat is hidden. | As wide as the slot, 100 tall, its bottom on the line's top while the line is painted at its default, else on the slot's bottom. |

The `fight.*` regions stand on the HUD while a fight is drawn. They appear with the combat display and fire `Removed` when it goes, new widgets every fight. The `hud.*` regions stand from the HUD's arrival until it goes, one set per login.

---

## What a region answers

| Rule | Detail |
|---|---|
| Unheld, it stands on the element | It is placed on the element's box every frame, following the character. `:position()` and `:rootPos()` read where the element is painted. A surface [anchored](style/geometry.md#anchor) to it follows on the next step. |
| Placed by the frame that paints it | A region takes its place and its box from the frame that draws its element. In a character's tree nobody is drawing, it keeps the place it had when last drawn, and one that appeared there stands at `0, 0` with no size until the tree is drawn. |
| Held, the element stands on it | `:position(x, y)`, a sheet rule's `position` or `anchor`, `:remember(name)`, a [`:draggable`](native.md#letting-the-user-drag-it-unprotected) drag, or [`:parent(p)`](native.md#taking-one-into-a-surface-of-your-own-unprotected) holds it, and the client paints the element with its top-left on the region's. `:position(nil)` and `:parent(nil)` give it back. On the HUD the place [follows the screen](native.md#moving-and-resizing-unprotected) and is clamped as a window's is. |
| `:visible(false)` | The element is not painted. The region stays where the element would be, and `:visible()` reads `false`. |
| Rules reach it as it appears | A sheet rule naming a role places each new region the moment it stands, so a rule installed before a fight places that fight's. An anchor needs a live target: anchor to a combat region from its `Added`. |
| The client's size | The element is painted at its own size. `:size(w, h)`, `:size(w)` and `:resizable(h)` raise, naming that the client paints it at its own size. A rule's `size` is inert. `:size()` reads the element's box. |
| The painter's order | The element is painted when its painter is, so what covers a held element is decided by the painter, not the region. `:raise()`, `:lower()` and `:z(n)` raise on a region, naming that. A combat element is painted at the combat display's place among the HUD's children. A `hud.*` element is painted by the HUD after all of its children, over every one of them. |
| The line and the notice share a slot | `hud.cmdline` and `hud.message` stand on one place while unheld: the client paints the command line or the notice there, never both. Hold each apart to paint them apart. |
| The line leaves its gap only at home | While the command line or the notice is painted at its default, the hidden chat's lines stand above it. Held elsewhere or hidden, it leaves no gap and `hud.chat` drops onto the slot. |
| Hit and click pass through | A point over a region is over whatever is behind it. [`hafen.ui():hit(x, y)`](selectors.md#hit-testing) and [`mouse:over()`](mouse.md) never answer a region, and a click there reaches the map or the widget behind, as it does on a stock client. |
| An armed handle takes the press | A region armed as a [`:draggable`](native.md#letting-the-user-drag-it-unprotected) handle takes every press over its box while the binding stands: a click on the world under `fight.action` starts the drag. Disarm it with `:draggable(nil)` when editing ends. |
| Hidden and left | A hidden region is a widget with [no toggle](native.md#hiding-a-native-widget-carries-a-restore): teardown leaves it hidden. A combat region is new every fight, but `hud.cmdline` hidden and left keeps the `:` line typing unseen until a relog. Put it back from [`Disable`](../event/bus/lifecycle.md#lifecycle). |
| A fight's region takes its records with it | Your `:visible(false)` and `:parent(p)` on a combat region end when it goes. Hide or take each fight's region on its `Added`. |
| Moving the whole combat display | `@Fightsess:position(x, y)` moves every element at once and keeps them round the character, offset by (x, y). Holding the eight regions fixes each element on the screen instead. An unheld region follows `@Fightsess` wherever it stands, taken into a surface of yours included. |
| Finding a region | By its role, through a role selector or `:role()`, never by its index among `@GameUI:children()`. The combat regions are new widgets every fight, so that index changes as they come and go, and a place saved under it lands on another region. |

---

## See Also

- [Native widgets](native.md) — `:position`, `:visible`, `:draggable`, `:remember` and `:parent` on a client widget, and the restore.
- [Selectors](selectors.md#roles) — every role, and hit-testing.
- [Geometry](style/geometry.md) — a rule's `position` and `anchor`.
- [The fight](../fight.md) — what the combat display paints, read as data.
