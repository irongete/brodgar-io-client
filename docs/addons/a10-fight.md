# A10 — Combat schools / deck read (`hafen.fight`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **4/4 headless checks**
> (locator + the three facade helpers are null-safe with no session: `fightwnd()` → nil, `maneuvers(nil)` →
> empty table, `deck()` → empty table, `summary()` → nil) + LuaJ parse of the harness. The `deckKey` / key-label
> mapping and the snapshot shapes read a live `FightWnd` (whose `<clinit>` loads `/res/ui/fraktur.res`, absent
> headless — the same **headless resource skip** as [A8](a8-craft.md)'s craft-spec test), so they are verified
> **in-game** + by source (the key table is a plain literal). **In-game verification pending.**
> **Design:** [specs/addons/coverage-gaps.md](../../specs/addons/coverage-gaps.md) **A10** (the read shape is
> designed in-phase — the api-reference left A10 without a fixed contract, like [A9](a9-2-wounds.md)).

A **loadout / combat-school addon** — "what maneuvers do I know, what's slotted on which hotkey, which saved
school is active?" — had no surface. The client holds this in the **`FightWnd`** widget (`@RName("fmg")`), the
character sheet's **"Martial Arts & Combat Schools"** tab, reached through the public **`CharWnd.fight`** field.
`hafen.fight` exposes it as a **read** surface. This is the **out-of-combat configuration editor** — deliberately
**distinct** from the in-combat `hafen.combat.*` view (that one is `Fightview`/`Fightsess`, with live `rtime`
cooldowns; this one is the school you *build* between fights, with hotkey layout and action-point budget). Editing
or switching schools is the **gated Phase-4** action tier.

## Zero core edits — every backing is public

No `haven` file changed. Like [A9-2](a9-2-wounds.md)/[A9-1](a9-1-quests.md)/[A8](a8-craft.md)/[A7](a7-speed.md)/[A6](a6-kin.md)/[A4](a4-skills-credos-lore.md)/[A2](a2-radar.md),
every field read is already public:

- `CharWnd.fight` (`public FightWnd fight`) — the Combat Schools window, held by the character sheet as a tab
  (created hidden at login but **live**, so the deck reads without the window ever being opened). A direct named
  field, exactly like [A9-2](a9-2-wounds.md)'s `CharWnd.wound` / [A9-1](a9-1-quests.md)'s `CharWnd.quest` — no
  tree-walk.
- `FightWnd.acts` (`public List<Action>`) — every maneuver/attack you know. Each `FightWnd.Action`:
  `public final Indir<Resource> res`, `public int a` (how many you can slot), `public int u` (how many you have
  slotted). (`Action.id`/`name` are private — not needed; identity is the resource name, as in every other snapshot.)
- `FightWnd.order` (`public final Action[]`) — the current school's card **layout**: index `i` → the maneuver
  bound to hotkey `FightWnd.keys[i]`, or `null` for an empty slot.
- `FightWnd.keys` (`public static final String[]`) — the hotkey labels the client draws (`"1".."5"`, then
  `"⇧1".."⇧5"` — `⇧` is the shift glyph).
- `FightWnd.maxact` / `usesave` (`public int`) and `nsave` (`public final int`) — the action-point budget cap,
  the active saved-school slot, and the saved-school slot count.

So the only file changed is `AddonManager.java` (the bridge) + the `hello` harness. **No `AddonWidgets`
haven-package accessor** is needed (nothing read is private/protected) — see *Deferred* on the one exception
(saved-school names).

## `hafen.fight.maneuvers([filter])` — the maneuver palette

```lua
for _, m in ipairs(hafen.fight.maneuvers()) do
  -- m.res    -- string, the stable resource id (omitted while Loading)
  -- m.name   -- string, the maneuver's display name (resource tooltip); nil while Loading
  -- m.avail  -- number, how many copies you may slot (Action.a)
  -- m.used   -- number, how many you currently have slotted (Action.u)
end

-- Only the ones you have slotted — the canonical predicate filter (one canonical way):
local slotted = hafen.fight.maneuvers(function(m) return m.used > 0 end)
```

Every combat maneuver / attack you know, as a snapshot. `filter` is the canonical **nil = all / name-substring /
predicate** (shared with `hafen.gob`/`world`/`kin`/`radar`/`quests`/`wounds`); the string form matches `name`.

## `hafen.fight.deck()` — the configured card layout

```lua
for _, d in ipairs(hafen.fight.deck()) do
  -- d.slot  -- number, the raw 0-based deck index (0..nact-1) — the same index the server uses
  -- d.key   -- string, the hotkey label ("1".."5", "⇧1".."⇧5")
  -- d.res   -- string, the slotted maneuver's stable resource id (omitted while Loading)
  -- d.name  -- string, its display name; nil while Loading
  -- d.used  -- number, the action points this card carries (Action.u)
end
```

The current school's layout: the **filled** slots of `order[]`, in key order. Empty deck slots are **omitted**
(the `slot`/`key` fields already convey position — no nil holes). `slot` is the **raw 0-based game index** (the
same index a future gated `fight.use(slot)` would take — one canonical way, matching [1d-4](phase-1d4-actionbar-equip.md)'s
0-based action-bar index).

## `hafen.fight.summary()` — the scalars

```lua
local s = hafen.fight.summary()   -- nil before the Combat Schools tab exists
-- s.maxact   -- number, the action-point budget CAP (the "/N" in the window's "Used: u/N")
-- s.used     -- number, total points spent = sum of every maneuver's `used` (the "u")
-- s.nact     -- number, the deck size (order.length — how many hotkey slots)
-- s.nsave    -- number, how many saved-school slots exist
-- s.usesave  -- number, the ACTIVE saved-school slot (0-based)
```

The window's budget + saved-school state in one small always-available snapshot (nil only before `CharWnd.fight`
exists). `used`/`maxact` mirror the window's own **"Used: u/maxact"** count (`FightWnd.recount()`).

## No `FightChanged` event — read on demand

A combat school changes only on **explicit player action** (dragging cards, changing counts, loading/saving/using
a saved school) — like [A4](a4-skills-credos-lore.md) skills/credos and [A8](a8-craft.md) craft, this is a
**read-on-demand** surface with **no push event**, not a live tracker. (Contrast the [A9-2](a9-2-wounds.md)
`WoundChanged` / [A6](a6-kin.md) `KinChanged` trackers, whose data changes from the outside.) An addon reads
`hafen.fight.*` when it needs it — at `OnEnterWorld`, on a hotkey, or when it observes the window open via
[3a](phase-3a-widget-interception.md) `hafen.ui.onWidgetCreate` (`type="fmg"`). A `FightChanged` diff-event is a
trivial future add if a use case appears (the [A9-2](a9-2-wounds.md) `WoundAdapter` is the template).

## The `hello` example (`addons/hello/main.lua`) — read-only

- A `readFight(tag)` helper logs `summary()` + the `maneuvers()`/`deck()` counts in the `[now]` and `[+3s]` login
  passes. Like the rest of the character sheet the data **streams in** a beat after enter-world, so `now` often
  shows `nil`/empty and `+3s` the real numbers (deck size, budget, the maneuvers you know).
- A new **`:hello fight`** sub-command calls `dumpFight()`, printing the budget line, the deck **by hotkey**
  (`[1] Punch x1`, `[⇧1] Take Aim x2`, …) and the first several known maneuvers with their `avail`/`used`. This
  is the on-demand read.

One login re-checks every prior slice **and** this one. Bumped to **v0.31.0**. `hello` stays **read-only** — the
combat-school action tier (edit/switch) is gated to Phase 4. A **fresh character with no combat schools** shows
`0 maneuvers` and a small/zero budget, which is a valid empty read; run `hello` on a character who has learned
Martial Arts to see the surface populate.

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```bash
ant run
```

1. At login the harness logs `[hello] [now] fight: …` and `[hello] [+3s] fight: …`. On a character who knows
   combat maneuvers the `+3s` pass shows e.g. `fight: 14 maneuver(s), deck=4/10 filled, used=6/8, school slot=0/3`;
   a fresh character shows `fight: 0 maneuver(s), deck=0/… filled, …` — both correct.
2. Open the **character sheet → Martial Arts & Combat Schools** tab to see the same maneuvers/deck the panel
   shows, then run in the console (chat):

   ```
   :hello fight
   ```

   Expect the budget line, then one line per slotted card (`[hotkey] Name xN`), then your known maneuvers with
   `avail`/`used`.
3. Cross-check directly from `:lua`:

   ```
   :lua hafen.fight.summary()
   :lua #hafen.fight.maneuvers()
   :lua hafen.fight.deck()
   :lua hafen.fight.maneuvers(function(m) return m.used > 0 end)   -- only the slotted maneuvers
   ```

4. **Edit the school** (drag a maneuver onto a slot, or change a count) and re-run `:lua hafen.fight.deck()` /
   `summary()` — the read should reflect the new layout / `used` total. **Switch a saved school** (double-click a
   save) and `summary().usesave` should change.

## Files

- `src/io/brodgar/addon/AddonManager.java` — **the only engine file changed**: the `hafen.fight.maneuvers` /
  `deck` / `summary` facade; helpers `fightwnd()` (the `CharWnd.fight` locator), `fightManeuvers`,
  `maneuverSnapshot`, `fightDeck`, `deckKey`, `fightSummary`; one new import (`haven.FightWnd`). Reuses the shared
  `matches` filter and the `resTipName`/`resIdent` name helpers.
- `addons/hello/` — `readFight`/`dumpFight` helpers, a `fight` sub-command on `:hello`, wired into the now/+3s
  passes; manifest + load-line bumped to **v0.31.0**.

**No `haven` core edit.**

## Threading & safety

- All calls run on the **UI thread** (the addon tick / the `:lua` console). `fightwnd()` returns `CharWnd.fight`
  or `null` when the character sheet isn't up yet — **null-safe, no NPE** (headless-verified: all three facade
  helpers return empty/nil with no session).
- **Copy under the `ui` monitor.** `FightWnd.uimsg` runs on a **Loader thread** under `synchronized(ui)`: `"avail"`
  **replaces** `acts` wholesale, `"used"`/`"max"` mutate `act.u` / `maxact` / `order[]` entries, and
  `Actions.tick` re-sorts `acts` on the UI thread. So `fightManeuvers`/`fightDeck`/`fightSummary` copy the `acts`
  list / `order[]` array (and read the scalars) **inside** `synchronized(ui)`, then resolve resource names
  **outside** the lock — the marker "copy under the lock, snapshot outside it" discipline
  ([A1](a1-markers.md)/[A8](a8-craft.md)/[A9-2](a9-2-wounds.md)). The public-int reads (`a`/`u`) outside the lock
  are snapshot-atomic, exactly like A9-2's wound ints.
- Resource reads are `Loading`-guarded (`resTipName`/`resIdent` swallow `Loading` → `res`/`name` are omitted until
  they resolve), so a partial snapshot (counts + key, no name yet) is fine on the first read and fills in shortly
  after — the same discipline as every other read surface.

## Limitations / deferred

- **Saved-school NAMES.** The player-assigned names of the saved schools live in the **private** `FightWnd.saves[]`
  (`Text[]`); exposing them would need an `AddonWidgets` haven-package accessor (the "SpeakerIcon trick", as
  [1d-1](phase-1d1-vitals-widget-tree.md) vitals did). Deferred to keep A10 **zero-edit** — `summary().usesave`
  (the active slot) + `nsave` (the count) identify the active school by index, which is enough for the read
  surface; the names are a one-line accessor away if a loadout UI wants them.
- **Editing / switching schools** — dragging cards, setting counts (`setu`), and load/save/use of a saved school
  are `wdgmsg` **actions**, so they are the **gated Phase-4** tier (`fight.use(slot)` / `fight.setCount` /
  `fight.loadSchool`), alongside `actionbar.use` / `speed.set` / `craft.make`. This slice is read-only.
- **No `FightChanged` event** — read-on-demand (see above); a diff-event is a future add.
- **Per-maneuver detail** beyond `res`/`name`/`avail`/`used` (the long pagina/tooltip text, the icon `Tex`) is not
  exposed — the same deferral as [A9-2](a9-2-wounds.md) wound descriptions / `g:image` icons.
- The in-combat deck with **live cooldowns** is a separate surface (`hafen.combat.deck()`, `Fightview`/`Fightsess`)
  — `hafen.fight` is only the out-of-combat builder.

## Next: A3 / Phase 4 (gated actions)

A10 completes the **read** gap-subsystems (A1–A11 are all read surfaces now). What remains in the queue is the
**gated actions tier** (Phase 4): `A3` (`hafen.actionbar.use`) folds into it, alongside `fight.use`/`speed.set`/
`craft.make` and the item/movement verbs — behind the write-actions permission (a global opt-in + a per-addon
manifest declaration, D-027).
