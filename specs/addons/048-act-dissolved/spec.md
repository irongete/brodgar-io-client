# 048-act-dissolved — Spec

<!-- Line ceilings are lifted for spec.md, plan.md and tasks.md of this feature by maintainer
     directive (2026-08-09): it is a whole-surface migration, the map IS the contract, and each task
     runs under `/implement` in a CLEAN CONTEXT — so detail that is not written here has to be
     re-derived from source every session. Do not "fix" the length. -->

## What & why

`hafen.act()` is the one section grouped by **permission** rather than by what it acts on. Every
other section is named for a thing — a player, a gob, the world, a widget, an item — and carries
whatever verbs that thing answers, reads and writes side by side. `act` collects nine unrelated
verbs because they share a gate. So the door to moving your character is not on the character, the
door to placing a building is not beside the snapping that prepares its argument, and the door to an
item verb is not on the item — it is a fifth argument (`"take"`, `"drop"`, `"transfer"`, `"iact"`,
`"itemact"`) to one overloaded call.

That shape also costs capability. Because the verbs are grouped by gate rather than by subject,
three of them send a message the client itself could not produce: `useItemOn` and
`item(x, "itemact")` fire the held-item gesture with **nothing on the cursor**, and `useItemOn` can
only aim at bare ground — the client's own `MapView.iteminteract` appends `clickargs()` when the hit
resolves to an object, so *apply the held item to that plant* is a message no addon can send today.

The rule that ends it is already written. **D-187** — *a verb that existed only for a KIND leaves
with that kind, to the thing's new home rather than to nowhere.* **047-flowermenu** already applied
it once, moving the radial menu's reads and writes onto their own section, and left the old door
standing: `act():flower` still exists, delegating to `FlowerMenuApi`, which is an open **D-103**
obligation (*an absorbed mechanism keeps one door*). This feature finishes the move for the
remaining nine verbs, deletes the two that no longer say anything, and renames the tier's adjective
from **gated** to **protected** across `src/` and the docs tier.

Nothing about the permission *model* changes: D-028 holds, the declaration is still
`"permissions": ["actions"]`, the consent dialog is untouched. Only where a verb lives, and what the
API calls the state of being behind that permission.

## The map

| Was | Becomes | What it sends |
|---|---|---|
| `act():moveTo(p)` | `hafen.player():move(p)` | `mapview "click"` — `{pc, mc, 1, 0}` |
| `act():clickGob(g, b, mods)` | `gob:click(b, mods)` | `mapview "click"` — `{pc, rc, b, mods, 0, id, rc, 0, -1}` |
| `act():useItemOn(p, mods)` | `hafen.player():hand():use(p, mods)` | `mapview "itemact"` — `{pc, mc, mods}` |
| — *(not expressible today)* | `hafen.player():hand():use(gob, mods)` | `mapview "itemact"` — `{pc, mc, mods}` + `clickargs()` |
| `act():item(x, "itemact")` | `hafen.player():hand():use(item, mods)` | `item "itemact"` — `{mods}` |
| `act():item(x, "iact")` | `item:use(mods)` | `item "iact"` — `{Coord.z, mods}` |
| `act():item(x, "take")` | `item:take()` | `item "take"` — `{Coord.z}` |
| `act():item(x, "drop", n)` | `item:drop(n)` | `item "drop"` — `{Coord.z, n}` |
| `act():item(x, "transfer", n)` | `item:transfer(n)` | `item "transfer"` — `{Coord.z, n}` |
| `act():place(p, a, b, mods)` | `hafen.world():place(p, a, b, mods)` | `mapview "place"` — `{rc, round(a*32768/PI), b, mods}` |
| `act():select(p1, p2, mods)` | `hafen.world():select(p1, p2, mods)` | `mapview "sel"` — `{tc1, tc2, mods}` |
| `act():menu(path...)` | **deleted** — `hafen.menugrid():get(name):use()` | — |
| `act():raw(target, msg, …)` | `widget:send(msg, …)` | `Widget.wdgmsg(msg, args)` |
| `act():flower(label)` | **deleted** — `hafen.flowermenu():select(label\|n)` | — |
| `act():enabled()` | **deleted** | — |
| `hafen.ui():hand()` | `hafen.player():hand():item()` | *(read)* |

Every encoding above is what ships today and must keep shipping: this feature moves doors, it does
not renegotiate the wire. The one addition is the `clickargs()` row.

## The contracts

### `hafen.player()` gains its first two writes

`hafen.player():move(p)` walks the character to a Position. `hafen.player():hand()` is the cursor.

Neither is a **forwarded Gob method**, so **D-046** (*Player is a composition anchor — `:gob()`
only*) is not bent: `player.md` already states the standing rule as *what lives on Player is only
what has no per-gob equivalent*, and both qualify. There is no `gob:move()`, because the server
accepts a walk command only for your own character; there is no other gob with a cursor. `move` also
does not collide with the existing `gob:moving()` read — one is an imperative on the player, the
other a property of a gob, and they are on different objects.

### The Hand is an object, and it is `nil` when empty

| Call | |
|---|---|
| `hafen.player():hand()` | the Hand, or **nil** when nothing is on the cursor (`GameUI.vhand == null`) |
| `hand:item()` | the Item on the cursor — every ordinary Item read answers on it |
| `hand:use(target, mods)` | apply what you are holding to `target` |

The hand earns objecthood rather than collapsing to the Item (the grammar's *a thing that holds
exactly one thing IS that thing*) because it carries a verb of its own — and that verb cannot live
on Item. `itemact` is dispatched through `DTarget.Interact`, an `ItemEvent` whose `src` **is** the
`ItemDrag`: in the client the gesture originates from the held item and carries no reference to it,
so the same message on the wire means *whatever is on the cursor*. Put `use` on an arbitrary Item
and `someInventoryItem:use(p)` would send an action about something else entirely.

`hand()` answering `nil` on an empty cursor is what makes the whole gesture guardable —
`if hand then hand:use(x) end` — which neither `act():useItemOn` nor `act():item(x, "itemact")` can
do today; both send blind into a state the client could never have produced.

**`target` is an Item, a Position or a Gob**, and dispatch is by type. **`hand:use()` with no
argument raises, naming the three**, and must never fall back to *activate the held item*: that
reading is a plausible author guess with no message behind it — every held-item action in the
protocol targets something — and the raise corrects it at the exact line. `hand:item():use()` is how
you activate what you are holding, and there is exactly one way to say it.

### Modifiers: the wire decides, per message

Not every verb takes `mods`, and the pattern is not stylistic — it is what each message carries.

- **`iact` and `itemact` carry a modifier field**, so `item:use(mods)` and `hand:use(target, mods)`
  take one, defaulting to `0`. Today's `act():item` hardcodes `0` for both, which is a fidelity gap
  this closes.
- **`take`, `drop` and `transfer` carry no modifier field at all.** In the client the modifier keys
  select the *count* — shift = transfer 1, shift+ctrl = transfer all, ctrl = drop 1, ctrl+meta =
  drop all (`WItem.mousedown`). Our `n` states that directly, so `item:drop(n)` **is** the modifier,
  and adding a `mods` parameter beside it would be a field with nowhere to go.
- **`pag:use()` keeps taking no arguments**, for the opposite reason: it goes through the client's
  own `MenuGrid.PagButton.use`, which reads `ui.modflags()` itself at the instant of the call, so a
  parameter there could only lie. Do not "align" it with the verbs above.

### `hafen.world()` gains its first protected verbs

`place` and `select` land beside `snapPlace` / `snapAngle`, which exist to prepare `place`'s
arguments and today live a page away from it.

`place` stays off the Hand for a reason that is structural, not aesthetic: placement is
**server-initiated** (`mapview.uimsg("place", …)` builds a `Plob`) and is sent from `mousedown` with
**no held item involved**, while `itemact` goes through `iteminteract`, which requires one. Two
different gestures. That also means `place` cannot be made guardable the way `hand:use` is —
`MapView.placing` is private, and reading it would cost a `haven` edit this feature does not need.

### `act():menu` is deleted, and `pag:use()` becomes protected

**No path door is built.** `hafen.menugrid()` addresses the entries it holds — by resource name
(`"paginae/act/dig"`) or by display name (`"Dig"`) — and `hafen.menugrid():get(name):use()` is
already the way to invoke one. A second, path-shaped address space beside it would be exactly the
dual style D-013 refuses, and **023-menugrid-oop** already absorbed this mechanism: `act():menu` is
the old door left open, the same **D-103** obligation as `act():flower`. So it goes the same way.

`pag:use()` is **ungated today** and performs a real server action — the docs say so in bold. It
becomes protected with this feature. That is a live behaviour change to a shipped verb and a stated
delta on D-028's surface (not on its model), and it needs its own decision recorded at `/end`.

### `widget:send(msg, ...)` deletes an address space

`raw`'s `target` parameter carried a private vocabulary: a numeric widget id, or the tokens
`"mapview"` / `"gameui"` / `"root"` resolved through `HookApi.hookTarget`. On a widget handle the
receiver *is* the target, and the tokens are already reachable as ordinary selectors —
`hafen.ui():find("@MapView")`, `find("@GameUI")` — so `rawTarget` and the token branch are deleted
outright rather than relocated.

Argument marshalling is unchanged (`LuaMarshal.toJava`: a `{x=, y=}` table becomes a Coord; numbers,
strings and booleans pass through). The one rule that must survive is **bound widgets only**: a
widget with no server id has nothing to send from, and `send` must refuse it naming why rather than
throwing from inside the engine.

### The three deletions

Three of the ten verbs are **not moved anywhere**, and each for the same shape of reason: the
section that owns the concept already has the door.

`act():flower(label)` — `hafen.flowermenu():select(label|n)` already supersedes it and is strictly
better: it raises where `flower` returns `false`, it takes a ring position as well as a caption, and
it has a `:cancel()` counterpart. `actFlower` already delegates to `FlowerMenuApi.open()` /
`.names()`, so what is left is the old door D-103 requires closing.

`act():menu(path...)` — `hafen.menugrid():get(name):use()` is the door, and 023-menugrid-oop
absorbed the mechanism. Same obligation, same disposal.

`act():enabled()` is **not moved**. `actionsGranted(owner)` is literally
`owner.manifest.usesActions()` — a static fact about the calling addon's own manifest file — and
D-028 already removed the global switch it was built to report. The only caller that can be told
`false` is one that did not declare the permission, which can read that in its own `manifest.json`.
With it gone, `hafen.act()` has no ungated member left, which is what makes the section dissolvable
rather than merely smaller.

### Retirement

Every one of the ten verbs gets a `Retired` row naming its replacement, **and `hafen.act` itself
gets a section-level row**, so `hafen.act()` throws instead of failing later as *"attempt to call a
nil value"*. `hafen.ui():hand()` gets a verb-level row naming `hafen.player():hand():item()`.

## `gated` → `protected`

The adjective changes everywhere it appears in `src/io/brodgar/` (~95 occurrences) and under `docs/`
(117 occurrences across 58 files), including group headings, Javadoc and the `Section.install`
retirement hints. The manifest permission keeps the name `"actions"` (see *Out of scope*), so
`requireActions` / `actionsGranted` / `declaresActions` / `ActionsConsentWnd` keep their identifiers
and only their prose changes. `specs/` is the historical record and is **not** rewritten; the rename
is recorded as a decision instead.

## The docs tier

`docs/addons/api/act.md` **dies**, and it is the page 19 others link to for one sentence — *see
`hafen.act` for the permission*. So the permission needs its new home built **before** the page is
deleted, in two places that already exist:

- `docs/addons/api/conventions.md` §*Gating: the actions permission* → §*The actions permission*,
  renamed and made the canonical short statement every page points at.
- `docs/addons/guides/actions-and-permissions.md` — the long form, already structured around exactly
  this, updated for the new homes.

Every moved verb's documentation moves to its new page: `player.md` grows a write section and the
Hand, `gob.md` gains `:click`, `world.md` gains a protected group, `ui/items.md` gains the item
verbs, `menugrid.md` loses its *ungated* claim on `pag:use()`, `ui/widget.md` gains
`:send`. `api/README.md`'s index row for `act` goes, and the top `docs/addons/README.md` "API at a
glance" table is updated.

## Acceptance criteria

- [ ] Reading any of the ten `hafen.act` verbs **throws naming its replacement**, and `hafen.act`
      itself throws as a section, so no port fails as *"attempt to call a nil value"*.
- [ ] `hafen.player():move(p)` walks the character to a Position; `gob:click(3)` opens that gob's
      radial menu; each refuses an addon without the permission, naming the verb.
- [ ] `hafen.player():hand()` is **nil** with an empty cursor and a Hand while carrying;
      `hand:item()` is the Item and `hafen.ui():hand()` throws naming it.
- [ ] `hand:use(target)` sends for an **Item**, a **Position** and a **Gob** — the Gob form carrying
      `clickargs()`, which no call could send before — and `hand:use()` with no argument **raises
      naming the three target types** without activating anything.
- [ ] `item:use()`, `item:take()`, `item:drop(n)`, `item:transfer(n)` each act, `n` defaults to the
      whole stack, and the five verb strings of `act():item` are gone.
- [ ] `hafen.world():place(p, angle)` and `hafen.world():select(p1, p2)` act, and `place` is
      documented beside the `snapPlace` that prepares it.
- [ ] `pag:use()` — **ungated today** — now refuses an addon without the permission, while the
      menugrid **reads** still answer for it; no path-shaped door is added anywhere.
- [ ] `widget:send(msg, ...)` sends from a bound widget and **refuses an unbound one** naming why;
      the `"mapview"`/`"gameui"`/`"root"` tokens are gone and both are reachable as selectors.
- [ ] `hafen.act():flower`, `hafen.act():menu` and `hafen.act():enabled` are gone;
      `hafen.flowermenu():select` is the only door onto a petal and `hafen.menugrid():get(name):use()`
      the only door onto a menu action, with no `hafen.menugrid():use` beside it.
- [ ] **No page under `docs/addons/` says "gated" or "gating"**, `api/act.md` is gone, no page links
      to it, and the permission is explained in one place every other page points at.
- [ ] `ant hafen-client` → `BUILD SUCCESSFUL` after a clean `build/classes` (an incremental build
      false-greens when a symbol moves between files, which this feature does nine times).
- [ ] The `walker` example addon exercises every moved verb under its new name and still runs.
- [ ] Each task ships its self-checking addon per `specs/addons/TESTING.md`; its run is all
      `[pass]` (plus any `[manual]` line the maintainer confirms) and every prior suite still is.

## Out of scope

- **Renaming the manifest permission string `"actions"`.** *Protected* is the adjective for a verb;
  `"actions"` names what the permission grants, and the two are orthogonal. Renaming it would touch
  the persisted `addons/actions.seen` pref and **re-prompt consent for every already-enabled write
  addon** unless migrated — real risk, no clarity bought.
- Any capability beyond `hand:use(gob)`, which exists only because the target dispatch would
  otherwise have a hole the client itself does not have.
- **The pagina-path address space, dropped by maintainer directive.** `act():menu` could reach paths
  that are in no menu-grid catalogue (the client's own logout buttons are one). Nothing replaces
  that: `hafen.menugrid()` addresses the entries it holds, and a second path-shaped door beside
  `pag:use()` is the dual style D-013 refuses. Recorded here as a deliberate choice, not an
  oversight, so it is not re-proposed later.
- Splitting `ActApi.java` further than this move requires. Its `hafen.craft` and `hafen.speed` halves
  stay where they are; only the `act` half is dissolved.
- The permission **model** — the consent dialog, the default-disabled policy, the AddOns panel.
  D-027/D-028 hold unchanged.
- Making `place` guardable (a `hand:placing()` read over the private `MapView.placing`). It would
  cost a `haven` edit and is a separate call.
- Rewriting `specs/` prose to say *protected*. The decision log is verbatim history.

## Decisions this feature must record

1. A verb lives with **what it changes**, not with what it costs — D-187 generalised from one kind to
   the whole surface, and the reason a permission is not a namespace.
2. `pag:use()` becomes protected — a surface delta on D-028, with the reasoning that a verb which
   commits a real server action is protected wherever it lives.
3. A gesture the **client itself cannot produce** is not a capability to preserve: the held-item
   verbs move onto the one receiver that is nil when the gesture is impossible.
4. A feature-detection verb whose answer is a fact about the caller's own manifest is deleted, not
   relocated.

## Context files

- `design/25-uniform-api.md` — the grammar every relocated verb obeys (rules 2, 3, 8, 11, and the
  retirement rule that makes the cut portable)
- `design/12-security-and-permissions.md` — the permission tier being renamed, not redesigned
- `specs/codebase/network.md` — the action channel; every verb here is one of its rows
- `src/io/brodgar/addon/ActApi.java` — the section being dissolved (`craft`/`speed` halves stay)
- `src/io/brodgar/addon/CharApi.java` — `hafen.player()`, gaining `move` and `hand`
- `src/io/brodgar/addon/LuaItem.java` · `LuaGob.java` · `LuaWidget.java` · `WorldApi.java` ·
  `UiApi.java` · `AddonManager.java` — the other files the verbs move into
- `src/io/brodgar/addon/Retired.java` — where the eleven retirement rows land
- `src/haven/WItem.java` — `mousedown` / `iteminteract`: which item message carries mods, and which
  encodes the count instead
- `src/haven/MapView.java` — `iteminteract` (and its `clickargs()` extension), the `place` branch of
  `mousedown`, `uimsg("place")`
- `src/haven/DTarget.java` — `Interact`, the gesture that proves the held item is the subject
- `docs/addons/api/act.md` · `conventions.md` · `guides/actions-and-permissions.md` — the page that
  dies, the section that renames, the guide the permission moves to
- `docs/addons/api/player.md` · `gob.md` · `world.md` · `menugrid.md` · `ui/items.md` ·
  `ui/widget.md` — the six pages that receive a verb
- `047-flowermenu/` — the same move done once already; its `:select` is what deletes `act():flower`
- `010-write-actions/` — where these verbs were built, and the encodings they must keep sending
