# Phase 4e — Menu + flower action verbs (`hafen.act.menu` / `hafen.act.flower`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **26 headless checks**
> (21 on the pure helpers `menuPath` — single/multi-token path building, number-token coercion, and the
> empty / nil / boolean / trailing-nil rejections — and `flowerPetalIndex` — exact + case-insensitive
> match, first-duplicate-wins, no-match, and the null-array / null-element / empty-name edges; plus 5
> LuaJ-runtime checks in a **real `Sandbox.create()` env**: `table.unpack` present + spreads a path,
> **no** global `unpack` (Lua 5.2 / LuaJ 3.0.1), and both `walker` + `hello` parse) + LuaJ parse of the
> updated `walker`. **In-game DoD pending.**
> **Design:** [specs/addons/09-events-catalog.md](../../specs/addons/09-events-catalog.md#appendix--action-message-reference-clientserver)
> (the `"act"` / `"cl"` encodings — the authority for the arg shapes),
> [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.act`),
> [specs/addons/coverage-gaps.md](../../specs/addons/coverage-gaps.md) **C3** (the menu-path caveat),
> [specs/addons/12-security-and-permissions.md](../../specs/addons/12-security-and-permissions.md),
> decisions **D-009** (wrap-not-reimplement) / **D-010** / **D-025** / **D-027** / **D-028** (the
> permission model, unchanged since 4a/4c).

Slice **4e** adds the two **menu** verbs of the gated write-actions tier — invoking an action-bar/pagina
menu action by **path**, and selecting a petal of the open radial context menu by **label**. Together with
4a's `moveTo` and 4d's MapView verbs, that covers the last of the "things a player does that aren't a bare
world click". Both are `requireActions`-gated exactly like every other `hafen.act.*` verb (a per-addon
declared permission — D-027/D-028, no global switch).

## The verbs

```lua
hafen.act.menu(path...)      -- invoke a menu/pagina action by its path tokens (returns nothing)
hafen.act.flower(label)      -- select the open FlowerMenu's petal named `label`; returns true iff one matched
```

### `menu(path...)` — action by pagina path

Backing: [`GameUI.act(String...)`](../../src/haven/GameUI.java:1683), which sends `wdgmsg("act", path…)` —
the **same** `"act"` message the action-bar menu grid ([`MenuGrid.PagButton.use`](../../src/haven/MenuGrid.java:169))
sends when you click through a pagina tree. The client uses it itself: `act("lo")` / `act("lo", "cs")` are
**log out** / **log out to character select** ([GameUI:1590](../../src/haven/GameUI.java:1590),
[OptWnd:862](../../src/haven/OptWnd.java:862)).

- Every argument is a **path token string** (a number coerces to its string form, console-token style;
  anything else — nil/boolean/table — is refused). At least one token is required.
- **⚠️ Caveat (coverage-gaps C3): menu paths are NOT a stable address space.** Paginae are
  server-fetched, their names are **content-defined / localized / versioned**, and a path resolves only if
  that page is currently loaded/open. Treat a path as a runtime lookup, not a constant — and note some
  paths **commit real actions** (e.g. `"lo"` logs you out). The addon supplies the tokens deliberately.

### `flower(label)` — select a radial-menu petal

Backing: the open [`FlowerMenu`](../../src/haven/FlowerMenu.java)'s own
[`choose(Petal)`](../../src/haven/FlowerMenu.java:278) (`wdgmsg("cl", petal.num, mods)`).

- Locates the single open `FlowerMenu` by a recursive `ui.root.children(FlowerMenu.class)` walk (only one
  is ever open — it grabs the mouse+keyboard), matches a petal whose **`name` equals `label`
  case-insensitively** (first match wins), and drives the client's **own** `choose` — **wrap, don't
  reimplement** (D-009). Reusing `choose` also means a client-side petal (e.g. the voice **Mute/Unmute**
  petal) is handled correctly rather than mis-sent to the server.
- **Returns a boolean, never throws for the ordinary "nothing to pick" cases:** `true` if a matching petal
  was selected, `false` if **no** flower menu is open **or** no petal matched. So an addon can just test
  the result (no `pcall`). (It still throws the `requireActions` gate error if the addon didn't declare the
  permission, and a type error if `label` isn't a string.)
- **Why label-matching is exact (case-insensitive), not substring** — unlike the read-side `buffs.has` /
  `items.find`: this is an **action** that commits an irreversible menu choice, so a loose match that
  selected the wrong petal would be dangerous. One canonical, precise rule.

#### Petals grab input — so a pick is programmatic by nature

While a `FlowerMenu` is open it **grabs the mouse and keyboard** (`ui.grabmouse` / `ui.grabkeys` in
`FlowerMenu.added`). You therefore **cannot type a command or press a hotkey to pick a petal by hand** while
it's up. The intended use of `flower` is **automation**: an addon opens a menu (e.g. `clickGob(ref, 3)` =
right-click) and then selects a petal from a **timer or event** — code paths that run regardless of the
input grab. That is exactly what the `walker` demo shows.

## Gated, world-authoritative, one canonical way

- **Gated like every verb.** Both call `requireActions(owner, …)` first; they run only if the addon
  **declared** `"permissions": ["actions"]`. `hafen.act.enabled()` still never throws.
- **Faithful sends.** `menu` = the client's own `GameUI.act`; `flower` = the client's own `FlowerMenu.choose`
  — literally what the menu-grid click and the petal click send. The client stays server-authoritative: an
  addon can do only what a player could.

## Zero `haven` core edit

Pure engine — **only `AddonManager.java`** (the two facade entries + backings `actMenu` / `actFlower` /
`openFlower` and the pure helpers `menuPath` / `flowerPetalIndex`) + **one import** `haven.FlowerMenu`.
Every backing is public: `GameUI.act`, `Widget.children(Class)`, `FlowerMenu.opts` / `Petal.name` /
`choose`, and the existing `gui()` locator.

## The `walker` example (`addons/walker/main.lua`) — two more deliberate triggers

`walker` (the dormant, opt-in write demo — declares `"actions"`, disabled by default; enable + confirm the
consent dialog + Reload UI) gains two sub-commands, joining 4a/4d's:

| Command | Verb | What it does |
|---|---|---|
| `:walker menu <token...>` | `menu(path…)` | invoke a menu action by path. With no token, prints the one client-guaranteed example: `:walker menu lo cs` = **log out to character select** (reversible). |
| `:walker flower <label>` | `clickGob(id,3)` → `flower(label)` | right-click the nearest object, then **auto-pick its petal `<label>`** after a 0.5 s delay (a flower grabs input, so a timed pick is the only programmatic way). |

Version **v0.4.0**. `walker` stays separate from the read-only `hello` harness, so one login still
re-checks every read slice.

## How to test in-game

**Java changed → full rebuild + restart** (the JVM does not hot-reload classes):

```bash
ant run
```

1. **Enable `walker`** (disabled by default): **Options → AddOns**, tick **Walker**, **confirm the consent
   dialog**, then **Reload UI**. At login it logs `walker: write-actions GRANTED`.
2. **`menu`:** run `:walker menu lo cs` → you are logged out to **character select** (the guaranteed
   client path — reversible; just log back in). `:walker menu` with no token prints the usage + example.
3. **`flower`:** stand next to a plant/bush/object, run `:walker flower <label>` with the petal you expect
   (e.g. `:walker flower Harvest`, `:walker flower Pick`). It right-clicks the nearest object and, 0.5 s
   later, selects that petal — logging `chosen` or `no such petal / no menu open` (the latter if the object
   gave a direct action instead of a menu, or the round-trip was slow — retry).
4. **The gate.** From the trusted console (declares every permission), `:lua hafen.act.menu("lo","cs")` and
   `:lua hafen.act.flower("...")` work; from an addon that did **not** declare `"actions"` the same calls
   error with *"this addon did not declare the actions permission …"*.

## Files

- `src/io/brodgar/addon/AddonManager.java` — the `hafen.act` facade gains `menu` (`VarArgFunction`) and
  `flower` (`OneArgFunction`), both `requireActions`-gated; backings `actMenu` / `actFlower` / `openFlower`;
  pure, headless-testable helpers `menuPath` (path array + validation) and `flowerPetalIndex` (the matcher);
  one import `haven.FlowerMenu`.
- `addons/walker/` — `main.lua` gains the `:walker menu` / `:walker flower` sub-commands (+ help/header);
  `manifest.json` **v0.4.0** + description.

**No `haven` core edit.**

## Threading & safety

- Both run on the **UI thread** (addon callback / REPL / timer), like the 4d verbs. `openFlower` /
  `gui()` walk the live widget tree from that thread (the same discipline the vitals/speed locators use);
  `wdgmsg` queues to the session (thread-safe).
- **Null-guarded:** `menu` throws a clear `"no game UI (not in the world yet)"` off-world and validates the
  path (non-empty, string tokens); `flower` returns `false` when there is no open menu or no match, and only
  ever calls `choose` on a real, matched petal.
- **Stateless** — no cached snapshot, no listener, nothing to reset across `:reload`/relog. Nothing to leak.
  (The `walker` flower demo schedules a `hafen.timer.after`, which is a bridge-owned, teardown-safe timer.)

## Limitations / deferred

- **Menu path stability (C3).** Inherent to the game (paginae are lazy, localized, versioned) — not fixable
  here; documented as a caveat. Addon authors should look paths up, not hard-code them (beyond the
  client-issued `lo`/`cs`).
- **Petal disambiguation.** `flower` matches by label only; if two petals ever share a name it picks the
  first. Selecting by index / by resource is a possible later refinement.
- **`MenuOpened` event.** The events catalog lists a `MenuOpened{petals}` (fired from `FlowerMenu.added`) —
  not built yet; the `walker` flower demo uses a fixed 0.5 s timer instead. Adding the event would let an
  addon react the instant a menu opens (a cleaner chain than a timer).
- **Later Phase-4 slices:** item verbs `hafen.act.item` + the `LuaModel` item-mutating verbs (4f); the
  per-subsystem gated verbs `speed.set` / `craft.make` / `actionbar.use` / `kin.*` (4g).
```
