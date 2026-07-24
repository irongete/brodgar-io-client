# A8 — Crafting read (`hafen.craft`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **11/11 headless checks**
> (locator + `readCraft()` null-safety with no HUD; `craftRes`/`craftReses` resolved + Loading-omitted
> paths + 1-based array indexing + empty; the `craftSpec` shape test is a documented **headless resource
> skip** — building a live `Makewindow.Spec` triggers `Makewindow.<clinit>` → `Text.render` → the
> `ui/fraktur` font resource, absent headless, exactly like the 3a Window-caption skip) + LuaJ parse of the
> harness under `Sandbox.create()`. The `current()` data shape (recipe/inputs/outputs/qmod/tools) reads
> live resources → verified **in-game**. **In-game verification pending.**
> **Design:** [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.craft`
> gap-subsystem A8), [specs/addons/coverage-gaps.md](../../specs/addons/coverage-gaps.md)
> (A8 — "Crafting: structured read (`Makewindow`)").

A crafting helper — "do I have the inputs / the right tools for this recipe?", a quality planner, a
craft-queue readout — needs to read the **current recipe**: its inputs (with counts), its outputs, the
quality-affecting inputs, and the tools it requires. The client holds all of that in the **`Makewindow`**
widget (`@RName("make")`) the server places under the HUD when you open a recipe. `hafen.craft` exposes it
as a **read** surface. **Actually crafting** the item (the *Craft* / *Craft All* buttons) sends a
`wdgmsg("make", …)` to the server, so `hafen.craft.make` is the **gated Phase-4 action tier** — this slice
ships the **read** only.

This is independent of the widget-*replacement* path ([Phase 3](phase-3c-replace-bags.md)): spec 08 lists
crafting only as a *reskin* target, but a structured read of the recipe is useful on its own.

## Zero core edits — every backing is public

No `haven` file changed. Like [A7](a7-speed.md)/[A6](a6-kin.md)/[A4](a4-skills-credos-lore.md)/[A2](a2-radar.md),
every field we read is already public:

- `Makewindow` is a `public class` the server places under the HUD, **wrapped** in `GameUI.makewnd` (a
  `private Window`). So — like A7's `Speedget` — we don't reach a named `GameUI` field; we locate the
  content widget with the **1d-1 Locator**, a `children(Makewindow.class)` subtree walk from the HUD.
- `Makewindow.rcpnm` (`public String`) — the recipe name (set once in the constructor).
- `Makewindow.inputs` (`public List<Input>`) / `Makewindow.outputs` (`public List<SpecWidget>`) — the input
  and output slots. Both element types expose `public final Spec spec`.
- `Makewindow.Spec` (`public class`) — `public ResData item, constraint` (with `ResData.res`, a public
  `Indir<Resource>`), `public int num`, and `public boolean opt()`.
- `Makewindow.qmod` / `Makewindow.tools` (`public List<Indir<Resource>>`) — the quality-modifier and tool
  resources drawn along the top of the window.

So the only file changed is `AddonManager.java` (the bridge). No `AddonWidgets` haven-package accessor is
needed (nothing we read is private/protected — unlike vitals/buffs, audit B5).

## `hafen.craft.current()` — the open recipe, or `nil`

```lua
local c = hafen.craft.current()
if c then
  -- c.recipe          -- string, the recipe name (may be "")
  -- c.inputs[i]       -- {res, name, num, opt}  (ingredient slots)
  -- c.outputs[i]      -- {res, name, num, opt}  (product slots)
  -- c.qmod[i]         -- {res, name}            (quality-affecting inputs)
  -- c.tools[i]        -- {res, name}            (required tools)
end
```

Returns a snapshot of the **currently-open** crafting window, or `nil` when no craft/recipe window is up.

**Input/output specs** are `{res, name, num, opt}`:

- **`res`** — the **displayed** resource's stable identity (its resource name). A recipe slot shows the
  **constraint** resource when the recipe accepts a *category* (e.g. "any board"), otherwise the concrete
  item — we follow `Makewindow.Spec.display()` and expose the constraint when present, else the item. This
  is exactly what fills the slot on screen. Omitted (absent key) while the resource is still `Loading`.
- **`name`** — that resource's tooltip display name; falls back to `res` when there's no tooltip layer, and
  is omitted while `Loading`.
- **`num`** — the required (input) / produced (output) count. **`-1`** means *unspecified* (drawn with no
  number badge, ≈ 1) and is exposed **faithfully** (not normalised).
- **`opt`** — `true` for an **optional** ingredient / chance byproduct (the green-tinted slots — the
  `Makewindow.Optional` info tag). `Loading`-guarded (defaults `false` until the item info resolves).

**`qmod`** (quality-affecting inputs) and **`tools`** (required tools) are `{res, name}` arrays — bare
resource references, no count. Both `res` and `name` are omitted while `Loading`.

> **One canonical way:** a single `current()` returns the whole recipe; there's no `hafen.craft.recipe()` /
> `hafen.craft.inputs()` split — read `current()` once and index it. `current()` returns a **snapshot table**
> (or `nil`), consistent with the rest of the read API.

### Shape note — extends the one-line sketch, faithfully

The api-reference sketch was `{recipe, inputs, output}`. The engine keeps a **list** of outputs (a recipe
can have byproducts) and separate **qmod**/**tools** lists, so the shipped shape is the richer, faithful
`{recipe, inputs, outputs, qmod, tools}` (`outputs` **plural**, an array). The api-reference table has been
updated to match. This mirrors earlier in-phase shape refinements ([kin](a6-kin.md),
[credos](a4-skills-credos-lore.md)).

## No `CraftChanged` event

Crafting is exposed **read-on-demand**, with **no `CraftChanged` event** — matching the api-reference (which
lists only `current`/`make`) and the read-only gap surfaces [A7](a7-speed.md)/[A2](a2-radar.md). A recipe
only changes when the player **opens** one; to observe *that*, an addon can already use the
[3a `hafen.ui.onWidgetCreate`](phase-3a-widget-interception.md) observer (a `Makewindow` is placed at
`place = "craft"`) and then read `current()`. The recipe's own `"inpop"`/`"opop"`/`"use"` updates flow
through the 1d-1 inbound-`uimsg` tap, so a `CraftChanged` adapter is a trivial future add if a use case
appears.

## The `hello` example (`addons/hello/main.lua`) — read-only

- A `readCraft(tag)` helper logs `current()` in the `[now]` and `[+3s]` login passes. **No craft window is
  open at login**, so both normally show `craft: none open` — the correct `nil` path.
- A new **`:hello craft`** sub-command calls `dumpCraft()`, which prints the open recipe's name and every
  input / output / qmod / tool line. This is the on-demand read: open any recipe, run `:hello craft`.

One login re-checks every prior slice **and** this one. Bumped to **v0.28.0**. `hello` is **read-only** for
crafting — `hafen.craft.make` (actually craft) is the **gated Phase-4 action tier**, not shipped here.

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```bash
ant run
```

1. At login the harness logs `[hello] [now] craft: none open` and `[hello] [+3s] craft: none open` — the
   expected `nil` path (no recipe open yet).
2. **Open any recipe** — via the crafting menu (e.g. *Wooden* → a board/plank recipe, or any station
   recipe). The `Makewindow` appears with its inputs, output(s) and tools.
3. In the console (chat) run:

   ```
   :hello craft
   ```

   Expect the recipe name plus one line per `input[i]` / `output[i]` / `tool[i]` (and `qmod[i]` if the
   recipe has quality modifiers), e.g. `input[1]  Board x2` / `output[1] Wax x1` / `tool[1]  <tool name>`.
4. Cross-check directly from `:lua` (open a recipe first):

   ```
   :lua hafen.craft.current().recipe
   :lua hafen.craft.current().inputs[1]
   :lua #hafen.craft.current().tools
   :lua hafen.craft.current()            -- the whole snapshot as compact JSON
   ```

   `current()` should be `nil` with **no** window open, and the inputs/outputs/tools should match what the
   window shows. Open a **second, different** recipe and re-read — `current()` should reflect the new one.

## Files

- `src/io/brodgar/addon/AddonManager.java` — **the only file changed**: the `hafen.craft.current` facade;
  helpers `makewindow()` (the Locator), `readCraft()`, `craftSpecs`/`craftSpec` (spec → `{res,name,num,opt}`)
  and `craftReses`/`craftRes` (resource → `{res,name}`); one new import (`haven.Makewindow`). Reuses the
  shared `resIdent`/`resTipName` name helpers (from [A4](a4-skills-credos-lore.md)).
- `addons/hello/` — `readCraft`/`dumpCraft` helpers + a `craft` sub-command on `:hello`; manifest + load-line
  bumped to **v0.28.0**.

**No `haven` core edit.**

## Threading & safety

- All calls run on the **UI thread** (the addon tick / the `:lua` console). `makewindow()` walks the widget
  tree via the public `children(Class)` and returns the first `Makewindow`, or `null` when no recipe is open
  — **null-safe, no NPE** (headless-verified).
- **List copy under the `ui` monitor.** `inputs`/`outputs`/`qmod` are List references the `"inpop"`/`"opop"`/
  `"qmod"` uimsgs swap **wholesale** off the UI thread (a Loader thread, under `synchronized(ui)`); `tools`
  is mutated **in place** (`tools.add` on the `"tool"` uimsg). So `readCraft` copies all four lists inside
  `synchronized(ui)`, then builds the Lua snapshots **outside** the lock — the marker "copy under the lock,
  snapshot outside it" discipline. Resolving resource names (`res.get()`, which may `Loading`) happens
  outside the lock.
- Resource reads are `Loading`-guarded (`resIdent`/`resTipName` swallow `Loading` → the field is omitted /
  falls back); `Spec.opt()` (which builds item info) is wrapped in a `try/catch` → `false` until it resolves.
- Stateless — no cached snapshot, no listener, nothing to reset across `:reload`/relog (each call re-reads
  live), so there is nothing to leak.

## Limitations / deferred

- **Read only.** `hafen.craft.make([all])` (actually craft, the *Craft* / *Craft All* buttons →
  `wdgmsg("make", 0|1)`) is the **gated Phase-4 action tier**, not shipped here.
- **No `CraftChanged` event** — read on demand; observe a recipe opening via
  [`onWidgetCreate`](phase-3a-widget-interception.md) if needed.
- **`num = -1`** (unspecified) is exposed **as-is** (faithful) — an addon treats it as "≈ 1 / no explicit
  count".
- **Constraint vs. concrete item:** when a recipe accepts a category, `res`/`name` are the **constraint**
  (the displayed category), not the concrete example item — matching what the window shows. The concrete
  `item` behind a constraint is not separately exposed (deferred; add a `constraint`/`item` split later if a
  use case appears).
- Deferred: an input's **fill state** (`Input.using` vs `num` — the partial red bar for construction-style
  crafts), per-item **quality**, and the `qmod`/`tool` **counts** (there are none — they're bare resources).
