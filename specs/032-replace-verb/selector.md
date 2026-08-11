# 032.1 — naming the main inventory

The measurement the feature is built on: **which selector resolves to `GameUI.maininv`, and to nothing else?**
The hypothesis was `inventory[title=Inventory]`; 030 already refuted a promise of this exact shape in-game
(`[res=]`), so it is recorded here as evidence, not as a claim.

## The selector

```lua
"inventory[title=Inventory]"
```

`GameUI` builds the main inventory as `invwnd = new Hidewnd(Coord.z, "Inventory")` with `maininv` added inside it
([GameUI.java:957](src/haven/GameUI.java:957)) — so the grid itself is roleless-captionless, and it is the 030
**enclosing-window rule** (`[title=]` resolves against the nearest enclosing `Window`) that makes this selector
possible at all. That rule is exactly what B2 deferred this feature for.

## Evidence 1 — headless, on a real `haven.UI` (029.1 recipe)

The tree `GameUI.addchild` builds was reproduced off-screen (`Hidewnd "Inventory"` ▸ `Inventory`, plus a container's
`Hidewnd "Chest"` ▸ `Inventory` as the near-miss) and every selector resolved with the real `Selector`:

| selector | matches |
|---|---|
| `inventory[title=Inventory]` | **1** — `maininv` |
| `inventory` | 2 — `maininv` **and the chest's grid** (why the refiner is needed) |
| `window[title=Inventory]` | 1 — the wrapper, not the grid |
| `inventory@Inventory[title=Inventory]` | 1 — `maininv` (the `@Class` refiner adds nothing here) |

It also matches **while the wrapper is hidden**, which is the normal state of the inventory: the selector is about
the tree, not about visibility (D-068).

Placement timing checks out too: `onWidgetPlaced` fires *after* `pwdg.addchild(wdg, pargs)`
([UI.java:494](src/haven/UI.java:494)), and `GameUI.addchild` has already put `maininv` inside the captioned
wrapper by then — so the `[title=]` refiner resolves on the **creation** path as well as on the registration scan.

## Evidence 2 — in-game, with `widgetstack`'s self-validating inspector

**Confirmed.** Hovering the grid on a live HUD gives the stack
`RootWidget#0 ▸ GameUI#7 ▸ Hidewnd 'Inventory' 380x269 ▸ Inventory #9 331x199` — `class: Inventory`, `role:
inventory`, `[title=] 'Inventory' (the ENCLOSING window's caption)`, `[res=] –`. The inspector's list is
self-validating (a candidate survives only after `hafen.ui.all()` resolved it and found this widget inside):

| selector | matches | index |
|---|---|---|
| `inventory@Inventory[title=Inventory]` | **1** | #1 |
| `@Inventory[title=Inventory]` | **1** | #1 |
| **`inventory[title=Inventory]`** | **1** | **#1** |
| `[title=Inventory]` | 32 | #4 |
| `inventory@Inventory` | 3 | #3 |
| `@Inventory` | 3 | #3 |
| `inventory` | 4 | #4 |

So the selector is confirmed at **1 match, first in tree order** — `hafen.ui("inventory[title=Inventory]")` is the
honest form, not just `all()`'s first of several. Three readings worth keeping:

- **`@Inventory` adds nothing** — the role already implies the class here, exactly as the headless run predicted.
  The inspector *offers* the fully-refined `inventory@Inventory[title=Inventory]` because it sorts most-specific
  first; the shortest 1-match form is the one to write.
- **The role is wider than the class**: `inventory` = 4 but `@Inventory` = 3, i.e. the `Equipory` classifies as
  `inventory` too (D-067 doing its job) — so the role alone can never name the backpack.
- **`[title=Inventory]` alone = 32** — every widget *inside* the wrapper answers the refiner, which is the
  enclosing-window rule working as designed and the reason the role part is load-bearing.

The `[res=]` column is empty again, as 030 found: no window on this server carries a resource.

## If it had failed

The grammar would have been extended by the minimum that names the main inventory — never the retired
`{id,type,place,caption,parentType}` descriptor kept as a fallback (D-012).
