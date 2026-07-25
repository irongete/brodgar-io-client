# Phase 4f — Item action verbs (`hafen.act.item`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **19 headless checks**
> (9 on the pure builder `itemVerbArgs` — the take/drop/transfer/iact/itemact arg shapes, the `n` stack-count
> threading for drop/transfer, `n`-ignored for iact, and unknown/empty → null — plus 10 facade checks in a
> **real `Sandbox.create()` env via the actual `installHafen`**: a declaring owner passes the gate to verb-
> validation + item-resolution errors, a raw-number handle is accepted, the bad-item-type / table-without-
> `handle` rejections fire, and a **non-declaring owner is blocked by the gate before verb validation**) + LuaJ
> parse of the updated `walker` + `hello`. **In-game DoD pending.**
> **Design:** [specs/addons/09-events-catalog.md](../../specs/addons/09-events-catalog.md#appendix--action-message-reference-clientserver)
> (the `"take"/"drop"/"transfer"/"iact"/"itemact"` encodings — the authority for the arg shapes),
> [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.act`, the **ItemRef** model),
> [specs/addons/12-security-and-permissions.md](../../specs/addons/12-security-and-permissions.md),
> decisions **D-022** (items are handle-only) / **D-013** (one canonical way) / **D-010** / **D-025** /
> **D-027** / **D-028** (the per-addon permission, unchanged since 4a/4c).

Slice **4f** adds the **item** verbs of the gated write-actions tier: pick up / drop / transfer / activate an
inventory or equipment item, and apply the cursor item onto one. This is also where the **`LuaModel`
item-mutating verbs deferred from Phase 3** land — delivered through the same canonical flat verb (see
[The item reference](#the-item-reference-a-handle) below), so there is no second OO style (D-013).

## The verb

```lua
hafen.act.item(item, verb [, n])
```

- **`item`** — an **Item snapshot** you got from a read (`hafen.items.inventory()` / `equipment()` / `hand()`
  / `find()`, or `model:items()` from an adopted/replaced widget), **or** its `handle` field (a number)
  directly. See [The item reference](#the-item-reference-a-handle).
- **`verb`** — a string, one of:

  | verb | what it does | `GItem.wdgmsg` sent | client source |
  |---|---|---|---|
  | `"take"` | pick it up onto your cursor/hand (from a container, or **unequip** a worn item) | `"take", Coord.z` | [WItem:180](../../src/haven/WItem.java:180) |
  | `"drop"` | drop it on the ground; `n` = how many of a stack | `"drop", Coord.z, n` | [WItem:178](../../src/haven/WItem.java:178) |
  | `"transfer"` | move it to the linked container (an open container / your inventory); `n` = how many | `"transfer", Coord.z, n` | [WItem:175](../../src/haven/WItem.java:175) |
  | `"iact"` | right-click / activate (its default context action: eat, open, light, …) | `"iact", Coord.z, 0` | [WItem:184](../../src/haven/WItem.java:184) |
  | `"itemact"` | apply the item on your **cursor** onto this item (pour a waterskin onto a plant, …) | `"itemact", 0` | [WItem:195](../../src/haven/WItem.java:195) |

  An unknown verb throws a guiding error listing the five.
- **`n`** — the **stack count** for `drop`/`transfer`: a positive number moves that many; the default (omitted)
  or `-1` moves the **whole stack / the item** (the `-1 = all` server convention). Ignored for
  take/iact/itemact (they carry no count).

Each verb sends **literally what a click on the item sends** — the same `GItem.wdgmsg` as
[`WItem.mousedown`](../../src/haven/WItem.java:171) / [`iteminteract`](../../src/haven/WItem.java:194) — so the
client stays **server-authoritative** (an addon can only do what a player could). The intra-item grab
coordinate those messages carry is a fixed corner (`Coord.z`) — a deterministic, faithful substitute for a
programmatic action (it is only the hand-grab offset).

**Modifiers.** `iact`/`itemact` send no modifiers (plain activate / apply). For a **modified** item
interaction, use the escape hatch with the same handle: `hafen.act.raw(item.handle, "iact", {x=0, y=0}, mods)`.

## The item reference: a handle

Items have no *content* id, so — per **[D-022](../../specs/addons/decisions.md)** (handle-only, honoring
[D-013](../../specs/addons/decisions.md)) — they are addressed by a **handle = the item's server widget id**
([`GItem.wdgid()`](../../src/haven/Widget.java:560)). Every server item **is** a bound widget: the server
sends a `GItem` (`@RName("item")`) as a child of the `Inventory`/`Equipory`, which wraps it in a client-side
`WItem` for display ([Inventory.addchild](../../src/haven/Inventory.java:101),
[Equipory.addchild](../../src/haven/Equipory.java:127)); the **`GItem`** is what carries the id and what the
verbs `wdgmsg` from.

So `itemSnapshot` now sets a **`handle`** field (the `GItem` wdgid) on every item snapshot. Because
`hafen.items.*` **and** `model:items()` share `itemSnapshot`, both surfaces became actionable at once — this is
how "the Phase-3 `LuaModel` item verbs" are delivered, via the one canonical `hafen.act.item(...)` rather than
a separate `model:item:take()` OO path (D-013). `hafen.act.item` reads that `handle` (or takes a raw number)
and **re-resolves the live `GItem` each call** with `UI.getwidget(id)` — always fresh, exactly like a GobRef;
if the item was moved/used/consumed the id no longer maps to a `GItem`, and the verb throws a guiding
"handle is stale" error instead of acting on the wrong thing.

The `handle` is **facade-safe** (principle P1 — only an int crosses into Lua, never a Java object) and
**leak-free** (no bridge registry — the engine's own `UI.widgets` id↔widget map already tracks it).

## The permission gate (unchanged)

`hafen.act.item` is `requireActions`-gated exactly like every `hafen.act.*` verb: it runs only if the calling
addon **declared** `"permissions": ["actions"]`, else it throws the standard guiding error. Per **D-028** there
is no global switch — a running write addon is one the user opted into (write addons are disabled by default;
enabling raises the 4c consent dialog). The gate fires **before** verb/arg validation.

## Core edits

**Zero `haven` edit.** Every backing is public: `GItem.wdgid()` / `Widget.wdgmsg` / `UI.getwidget`, and the
`Inventory`/`Equipory` item structure. Only [`AddonManager.java`](../../src/io/brodgar/addon/AddonManager.java)
changed — the `hafen.act.item` facade entry + the backings `itemVerbArgs` (pure) / `resolveItemHandle` /
`actItem`, and one added `handle` field in `itemSnapshot`.

## Threading

`hafen.act.item` runs on the UI thread (addon callback / REPL / timer), like every act verb. `GItem.wdgmsg`
routes through the outbound `UI.wdgmsg` choke point, so a **2d action hook** can even observe a `"take"`/etc.
(it is a real action); the 2d re-entrancy guard stops a hook-issued verb from looping. Adding the `handle`
field does not perturb the change-detection diffs (`equipEqual`/`actionbarEqual` compare only `slot`/`res`/
`name`/`num`; the model item poll is `WItem`-identity-keyed), so **`EquipChanged`** and model item events are
unaffected.

## Try it in-game

`hello` is the always-on **read-only** harness and now (v0.34.0) logs the new `handle` on the first inventory
item every login — read-side proof of the 4f plumbing, no permission needed. The **write** demo is the opt-in
`walker` addon (v0.5.0), which declares `"actions"`:

1. Enable **Walker** in **Options → AddOns** (confirm the consent dialog) → **Reload UI**. At login it logs
   `write-actions GRANTED`.
2. Make sure you have at least one item in your main inventory, then run:
   ```
   :walker item
   ```
   → takes the **first** inventory item onto your cursor (default verb `take`). The item lifts to the cursor;
   **left-click an empty inventory slot to put it back** (fully reversible). The log shows the item name + its
   `handle`.
3. Try another verb (deliberately): `:walker item drop` (drops it — pick it back up), `:walker item iact`
   (right-click activates it — e.g. eat food; irreversible, so choose the item you point it at), etc.
4. `hello`'s login log line `[now/+3s] inventory=… first=… handle=<id>` confirms every read item carries a
   handle.

DoD: `hafen.act.item(item, verb)` drives the live item; a stale handle errors cleanly; the verb is refused for
an addon that didn't declare `"actions"`.

## Deferred

- **Item quality / contents / a stack-split take** (take N from a stack) — content-defined / extra protocol.
- **A dedicated hand-item / bulk `Inventory "invxf"` transfer verb** — `raw` covers `invxf` today.
- **`clickGob` sub-mesh + `useItemOn`-on-a-gob** (from 4d) and the remaining **per-subsystem gated verbs**
  (4g: `hafen.speed.set`, `craft.make`, `actionbar.use`, `kin.*`).
