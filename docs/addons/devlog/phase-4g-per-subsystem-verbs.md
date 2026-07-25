# Phase 4g — Per-subsystem gated write verbs (`speed.set` / `craft.make` / `actionbar.use` / `kin.*`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **25 headless checks**
> (in a **real `Sandbox.create()` env via the actual `installHafen`**: a **declaring** owner passes the gate
> to each verb's argument-validation / no-live-widget error, and a **non-declaring** owner is refused up front
> with the "did not declare" gate error — across all eight verbs) + LuaJ parse of the updated `walker` + `hello`.
> **In-game DoD pending. This completes Phase 4** (the gated actions tier) and, with it, the read+write pairs
> for A3/A6/A7/A8.
> **Design:** [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (the four subsystem
> contracts — `hafen.speed`/`hafen.craft`/`hafen.actionbar`/`hafen.kin`),
> [specs/addons/12-security-and-permissions.md](../../specs/addons/12-security-and-permissions.md),
> decisions **D-009** (wrap-not-reimplement) / **D-013** (one canonical way) / **D-027** / **D-028** (the
> per-addon permission, unchanged since 4a/4c).

Slice **4g** finishes the gated write tier by adding the **per-subsystem** verbs — the write counterparts of
read subsystems already shipped (A7 speed, A8 craft, A3/1d-4 action bar, A6 kin). They live in **their own
namespace** (`hafen.speed.set`, not `hafen.act.speed`), because each pairs naturally with its reads, but they
share the **exact same `"actions"` permission gate** as every `hafen.act.*` verb.

## The verbs

```lua
hafen.speed.set(n)                 -- select movement speed n = 0..3 (crawl/walk/run/sprint)
hafen.craft.make([all])            -- craft the OPEN recipe; all → Craft All
hafen.actionbar.use(n [, mods])    -- activate action-bar slot n (the raw 0-based index slot(n) reads)
hafen.kin.add(secret)              -- add a kin by the other player's hearth secret
hafen.kin.remove(kin)              -- END KINSHIP (step 1) — the kin stays memorized in the list
hafen.kin.forget(kin)              -- FORGET (step 2) — drop a memorized kin from the list
hafen.kin.rename(kin, name)        -- set a kin's nickname
hafen.kin.setGroup(kin, group)     -- move a kin to colour group 0..7
```

| verb | what it sends | wrap (D-009) | client source |
|---|---|---|---|
| `hafen.speed.set(n)` | `wdgmsg("set", n)` | `Speedget.set(n)` | [Speedget:89](../../src/haven/Speedget.java:89) |
| `hafen.craft.make(all)` | `wdgmsg("make", all ? 1 : 0)` | the Craft / Craft All buttons | [Makewindow:147](../../src/haven/Makewindow.java:147) |
| `hafen.actionbar.use(n, mods)` | `wdgmsg("belt", n, …)` | `Belt.act(n, Interaction(1, mods))` (a **left-click** on the slot) | [GameUI.Belt:176](../../src/haven/GameUI.java:176) |
| `hafen.kin.add(secret)` | `wdgmsg("bypwd", secret)` | the "Add kin" hearth-secret field | [BuddyWnd:508](../../src/haven/BuddyWnd.java:508) |
| `hafen.kin.remove(kin)` | `wdgmsg("rm", id)` | `Buddy.endkin()` (the "End kinship" petal) | [BuddyWnd:107](../../src/haven/BuddyWnd.java:107) |
| `hafen.kin.forget(kin)` | `wdgmsg("rm", id)` | `Buddy.forget()` (the "Forget" petal) | [BuddyWnd:103](../../src/haven/BuddyWnd.java:103) |
| `hafen.kin.rename(kin, name)` | `wdgmsg("nick", id, name)` | `Buddy.chname(name)` | [BuddyWnd:123](../../src/haven/BuddyWnd.java:123) |
| `hafen.kin.setGroup(kin, group)` | `wdgmsg("grp", id, group)` | `Buddy.chgrp(group)` | [BuddyWnd:127](../../src/haven/BuddyWnd.java:127) |

Each verb sends **literally what the corresponding player gesture sends** (wrap-not-reimplement, D-009 — it
drives the client's own method rather than re-encoding the wire message), so the client stays
**server-authoritative** (an addon can only do what a player could).

### Detail notes

- **`speed.set(n)`** — `n` is `0=crawl 1=walk 2=run 3=sprint` (validated `0..3`). The server is authoritative
  on whether a speed is currently *allowed* (e.g. sprint may be stamina-locked); `set` only requests it, as
  clicking/hotkeying that speed would. Pair with `hafen.speed.get()` / `max()`.
- **`craft.make([all])`** — presses the **open** recipe's **Craft** button (`all` falsy → one) or **Craft All**
  (`all` truthy → `1`). This **consumes the ingredients**, exactly like the manual button; with no recipe
  window open it throws a guiding error. `all` is a boolean (Lua truthiness). Pair with `hafen.craft.current()`.
- **`actionbar.use(n [, mods])`** — `n` is the **raw 0-based slot index** (0..143) — the *same* index
  `hafen.actionbar.slot(n)` reads. It performs exactly a **left-click** on that action-bar button
  (`Belt.mousedown` b==1 → `act(n, Interaction(1, mods))`), so a ground-targeted ability then **enters
  targeting mode** just as clicking the button does — supply the target with the MapView verbs
  (`useItemOn`/`place`/`clickGob`). `mods` is an optional modifier bitfield (0 default; Shift=1 Ctrl=2 Alt=4,
  matching `hafen.key`). Empty or out-of-range slot → a guiding error.
- **`kin.add(secret)`** — adds a kin by the other player's **hearth secret**, exactly the Kin window's *"Make
  kin by hearth secret / Add kin"* field ([`wdgmsg("bypwd", secret)`](../../src/haven/BuddyWnd.java:504)). It does
  **not** resolve an existing kin (it takes the secret string, not a ref); the server adds the kin if the secret
  is valid (a wrong/empty one just does nothing — we reject an empty string up front with a guiding error).
- **`kin.remove` / `forget`** — the **two steps of dropping a kin**, the game's own "End kinship" then "Forget"
  (a state machine). `remove(kin)` = **End kinship** (`Buddy.endkin`): ends the kinship, but the kin **stays in
  your list**, now merely *memorized* (un-kinned) — the "End kinship" petal, shown while the kin is active.
  `forget(kin)` = **Forget** (`Buddy.forget`): drops a **memorized** kin from the list entirely — the "Forget"
  petal, shown once the kin is un-kinned. Both send the same `wdgmsg("rm", id)`; the **server advances the state**
  (active → memorized → gone), exactly as clicking the two petals in turn does. **To fully remove an active kin:
  `remove(kin)`, then `forget(kin)` once it is memorized.**
- **`kin.rename` / `setGroup`** — the `kin` argument (for all four ref-taking verbs) is a **kin ref**: a kin
  snapshot from `hafen.kin.list`/`find` (read for its `id`), the id number directly, or a **name** string (exact,
  case-insensitive), resolved to the live `BuddyWnd.Buddy`. `group` is validated `0..7` (the eight colour groups).

## Adding a kin: by hearth secret, not by name

Kinning in H&H needs a **shared hearth secret** (or a right-click *"Add as kin"* on the player) — there is no
"add by name" message, because you cannot unilaterally add a stranger to your roster. So `kin.add(secret)` maps
to the roster's real add path, the `"bypwd"` ("buddy by password") message the Kin window's *Add kin* field
sends. (The name-based path — right-click the player → flower — remains reachable via
`hafen.act.clickGob(id, 3)` + `hafen.act.flower("Add as kin")`.) The api-reference sketched "add/remove/rename";
4g refines it to the roster's **four real messages** (`bypwd`/`rm`/`nick`/`grp`) mapped to **five verbs** —
`add` (by secret), the two-step `remove`/`forget` (both `rm`, the game's "End kinship"/"Forget"; see above),
`rename`, plus **setGroup** (the `grp` message the sketch omitted). A faithful in-phase refinement to what the
Kin window actually sends.

## The permission gate (unchanged)

Every 4g verb is `requireActions`-gated exactly like `hafen.act.*`: it runs only if the calling addon
**declared** `"permissions": ["actions"]`, else it throws the standard guiding error. Per **D-028** there is no
global switch — a running write addon is one the user opted into (write addons are disabled by default;
enabling raises the 4c consent dialog). The gate fires **before** any argument validation.

## Core edits

**Zero `haven` edit.** Every backing is public — `Speedget.set`, `Makewindow.wdgmsg`, `GameUI.belt`/`beltwdg`
+ `Belt.act` + `MenuGrid.Interaction`, and `BuddyWnd.find`/`gc` + `Buddy.endkin`/`forget`/`chname`/`chgrp`.
Only [`AddonManager.java`](../../src/io/brodgar/addon/AddonManager.java) changed: the four subsystem facade
tables (`speed`/`craft`/`actionbar`/`kin`) + the backings `actSpeedSet` / `actCraftMake` / `actActionbarUse` /
`actKinAdd` / `resolveKin` / `requireKin` / `actKinSetGroup` (the `remove`/`forget`/`rename` verbs wrap
`Buddy.endkin`/`forget`/`chname` inline), plus one import (`haven.MenuGrid`).

**Headless gotcha (like A7/A8):** `hafen.kin.setGroup` validates `group` against `BuddyWnd.gc.length` — a
**static-field** read that forces `BuddyWnd.<clinit>` → `UI.<clinit>` → the resource loader (absent under bare
`jshell`). So the backing **resolves the kin first** (instance-only, no statics): headless we get a clean "no
Kin window" before touching `gc`, and the range is validated in-game where `BuddyWnd` is initialized.

## Threading

All four run on the **UI thread** (addon callback / REPL / timer / slash command), like every act verb. The
`wdgmsg` routes through the outbound `UI.wdgmsg` choke point, so a **2d action hook** can observe e.g. a
`"belt"` or `"grp"` send (they are real actions); the 2d re-entrancy guard stops a hook-issued verb from
looping. `resolveKin` iterates `BuddyWnd` via its `iterator()`, which copies the roster under the window's own
lock (snapshot-safe), so a concurrent server `add`/`rm` can't corrupt the walk.

## Try it in-game

`hello` stays the always-on **read-only** harness (unchanged; it already reads `hafen.speed`/`craft`/
`actionbar`/`kin`). The **write** demo is the opt-in `walker` addon (v0.6.0), which declares `"actions"`:

1. Enable **Walker** in **Options → AddOns** (confirm the consent dialog) → **Reload UI**. At login it logs
   `write-actions GRANTED`. `:walker help` lists every sub-command.
2. **speed** — `:walker speed 2` sets you to *run* (fully reversible: `:walker speed 1` back to walk). The log
   shows the previous speed + the max currently selectable.
3. **craft** — open a recipe you actually want to make in the crafting menu, then `:walker craft` (or
   `:walker craft all`). It presses **Craft** and **consumes the ingredients**. With no recipe open it says so.
4. **bar** — read a slot first (`:lua actionbar.slot(0)`), then `:walker bar 0` activates action-bar slot 0
   (whatever it holds — a ground-targeted ability enters targeting mode).
5. **kin** (acts on your **real** roster — use a test kin):
   - `:walker kin add <hearth-secret>` — adds a kin by their hearth secret (the server adds them if valid).
   - `:walker kin <name> group 3` — recolours the kin to group 3 (reversible: set it back).
   - `:walker kin <name> rename Foo` — nicknames them (reversible: rename to the old name).
   - `:walker kin <name> remove` — **ends the kinship** (the kin stays *memorized* in your list).
   - `:walker kin <name> forget` — then **forgets** the now-memorized kin (drops it; re-add via secret / right-click).

DoD: each verb drives the live subsystem; each is refused for an addon that didn't declare `"actions"`;
`kin.add` sends the hearth secret, `remove` then `forget` are the two-step drop, and the ref-taking verbs
resolve a kin by name/id/snapshot.

## Deferred

- **`kin.add` by name** — no such message exists (kinning needs a shared secret); the right-click *"Add as
  kin"* path stays reachable via `hafen.act.clickGob(id, 3)` + `hafen.act.flower`.
- **`actionbar.use` hotkey semantics** — `use` mirrors a left-**click** on the slot (enters targeting for a
  ground ability). The alternative "press the belt hotkey at the cursor" (`Belt.keyact`, a `Hittest` at the
  mouse) is intentionally not exposed — it couples to the live mouse position.
- **`speed.set` availability pre-check** (reject a locked speed client-side) — left to the server, faithful.
- **Stack-split / per-item craft count**, a `CraftChanged`/`SpeedChanged` event, kin `online` tri-state — as
  noted in the respective read slices.
