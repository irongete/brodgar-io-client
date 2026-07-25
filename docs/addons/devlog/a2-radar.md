# A2 — Radar / minimap icon categories (`hafen.radar`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **21/21 headless logic
> checks** (`radarSetIn` match-count / show-vs-notify routing / snapshot shape + name fallback /
> function-filter isolation / null-safety; + the `clampMsg` REPL-notice clamp below) + LuaJ parse of the
> harness under `Sandbox.create()`. **Read verified in-game** (`categories()` returned the full ~400-entry
> registry with correct name/res/show/notify); mutation DoD pending.
> **Design:** [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.radar`
> gap-subsystem A2), [specs/addons/coverage-gaps.md](../../specs/addons/coverage-gaps.md)
> (A2 — "GobIcon settings / radar categories"), [specs/addons/code-map.md](../../specs/addons/code-map.md)
> (`GobIcon`).

Radar addons — "show boars, hide trees", "notify me when X spawns" — are a staple H&H addon category.
The client already has the machinery: every gob that carries a map icon (`GobIcon`) is grouped into a
**category** in a per-character registry (`GobIcon.Settings`, `GameUI.iconconf`), each with a **show**
flag (draw it on the minimap) and a **notify** flag (play a sound + chat message when one appears).
This is exactly the registry the in-client **Options → "Icon settings"** window edits. `hafen.radar`
exposes it: read the categories, and flip show/notify across a filter — the addon-scriptable version of
that window.

## Zero core edits — every backing is public

No `haven` file changed. The registry and everything on it is already public:

- `GameUI.iconconf` (public `GobIcon.Settings`) — the character's icon registry.
- `GobIcon.Settings.settings` (public `Map<Setting.ID, Setting>`) — the categories.
- `GobIcon.Setting.id.res` (the resource name — the stable id), `Setting.show` / `Setting.notify`
  (public booleans), `Setting.icon` (public `Icon`, whose `name()` is the tooltip).
- `GobIcon.Settings.dsave()` — the debounced persist the settings window's own checkboxes call.

So the only file changed is `AddonManager.java` (the bridge). No `UI.java`, no `AddonWidgets`, no
adapter — like [A4](a4-skills-credos-lore.md) and [1d-3](phase-1d3-study-skills.md).

## `hafen.radar.categories([filter])` — read the registry

```lua
for _, c in ipairs(hafen.radar.categories()) do
  -- c = { name, res, show, notify }
end
```

Each category snapshot:

| field | meaning |
|---|---|
| `name` | the icon's display name (its resource tooltip), e.g. `"Boar"`; falls back to `res` if the tooltip is missing |
| `res` | the resource name (e.g. `"gfx/terobjs/trees/fir"`) — the **stable identifier** |
| `show` | `true` if the icon is drawn on the minimap |
| `notify` | `true` if a spawn plays the category's notification |

The optional `filter` is the **canonical filter** used across the API (`world.gobs`, `markers.list`):
`nil` = all, a **string** = case-sensitive substring of `name`, a **function** `filter(snap)->truthy` =
a predicate over the full snapshot (use a predicate to match on `res`). Filtering here is a convenience
— the returned array is small; you can equally filter it in Lua.

The registry is **empty until the HUD is up**, and grows as the character sees new icon types (the
server streams icon defs in; a fresh login populates it a beat after `OnEnterWorld`, like the rest of
the HUD). Read it on demand / off a timer, not synchronously in `OnEnterWorld`.

## `hafen.radar.setVisible(filter, on)` / `setNotify(filter, on)` — flip a flag

```lua
hafen.radar.setVisible("Boar", true)    -- show every category whose name contains "Boar"
hafen.radar.setVisible("Tree", false)   -- hide the trees
hafen.radar.setNotify("Boar", true)     -- ping when a boar appears
-- predicate form (match on res):
hafen.radar.setVisible(function(c) return c.res:find("trees") ~= nil end, false)
local n = hafen.radar.setVisible(nil, false)   -- nil filter = ALL categories; returns the number matched
```

Both take the **same canonical filter** as `categories` and set the flag on **every category the filter
matches**, returning the **number of categories matched** (so the addon can tell whether its filter hit
anything — `0` means nothing matched). A `nil`/omitted filter matches **all** categories — handy for a
"hide everything" / "show everything" toggle, but note it is all-encompassing.

The change is **live** (the minimap reads `show` each refresh) and **persisted** (debounced via
`dsave()`, exactly as the settings window's checkboxes do) — so it survives relog and shows up in the
"Icon settings" window. Setting a flag to the value it already holds is a no-op (no needless disk
write) but still counts toward the returned match total.

There is **one canonical way** to change a category: these two setters over a filter. `setVisible`
writes `show`; `setNotify` writes `notify`; nothing else is mutated.

## No `*Changed` event — read on demand

Following the [A4](a4-skills-credos-lore.md) precedent for rarely-changing data: the category set only
grows when the character sees a **new icon type** (infrequent), and an addon changing show/notify
already knows it did. So this slice adds **no adapter and no event** — read `categories()` when you need
it (on your own action, a timer, or when you open your UI). A `RadarChanged`-on-new-icon event could be
added later as a poll over `Settings.tag` if it proves wanted.

## The `hello` example (`addons/hello/main.lua`) — read-only

A new `readRadar(tag)` helper logs `categories()` — the count, how many are shown / notify, and the
first category's `name`/`res`/`show`/`notify` — in both the `[now]` pass (usually `0` — the registry is
still streaming) and the `[+3s]` pass (populated). It stays the standing regression harness: one login
re-checks every prior slice **and** this one. Bumped to **v0.24.0**.

`hello` is **deliberately read-only** for radar. The setters **persist to your real icon settings**, so
having the harness flip them would disturb your actual radar config across logins. The mutation surface
is instead exercised by the headless test and left for you to try live (below).

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```bash
ant run
```

Read side (the harness, automatic):

- After `[hello] entered the world`, at `[+3s]` expect
  `[hello] [+3s] radar=<n> categor(ies), <s> shown, <k> notify, first=<name> [show=.. notify=..]`
  with `n > 0` (your character will have seen many icon types). `radar=0` at `now` is normal (streaming).

Mutation side (do it yourself — this writes your real config, then flip it back):

```
:lua hafen.radar.categories()                     -- inspect the set (large — see the note below)
:lua hafen.radar.setVisible("Boar", false)        -- returns the number of "Boar" categories hidden
```

> **Note — `categories()` is big.** A settled character has ~400 categories, so the compact-JSON dump is
> ~40 KB on **one line**. The **full result is on the terminal** (`[console] lua= …`); the **in-game**
> notice is clamped (see "REPL notice clamp" below). To inspect a subset in-game, filter it:
> `:lua hafen.radar.categories("Boar")` or `:lua #hafen.radar.categories()`.

Watch the boar icons vanish from the minimap and the corner map; open **Options → Settings → the
"Icon settings" window** and confirm the "Display" checkbox for Boar is now unchecked (same registry).
Then restore it:

```
:lua hafen.radar.setVisible("Boar", true)
```

Try `setNotify` the same way (`:lua hafen.radar.setNotify("Boar", true)`) and confirm a boar spawning
now pings.

## REPL notice clamp (a crash surfaced by, but not caused by, radar)

The first live `:lua hafen.radar.categories()` **crashed the client** with
`GL_INVALID_VALUE (1281)` on the render thread. Cause: the `:lua` REPL mirrors its result to the in-game
notice (`UI.msg`), which renders a line as **one text texture**. A ~400-entry registry is a ~40 KB
**single line** of compact JSON (no spaces → no break points), so the texture width blew past
`GL_MAX_TEXTURE_SIZE`. **The radar read itself is correct** — the whole result printed fine to the
terminal; radar is simply the first read big enough to trip a **pre-existing REPL limitation**.

Fixed at the choke point: a `clampMsg(s)` helper truncates anything handed to the in-game notice sink to
`NOTICE_MAX` (500) chars (with a `… (N chars; full output on the terminal)` tail), applied to **all
three** in-game output paths — the REPL result, the REPL error, and both `hafen.log` variants. The
**terminal (stdout) still gets the full text**, and addons receive the real Lua value, so nothing is lost
— only the in-game one-line render is bounded. This hardens every large `:lua`/`hafen.log` output, not
just radar.

## Files

- `src/io/brodgar/addon/AddonManager.java` — **the only file changed**: (1) the `hafen.radar`
  `categories`/`setVisible`/`setNotify` facade + helpers `iconconf`, `radarSnapshots`, `radarSnapshot`,
  `radarName`, `radarSet` / `radarSetIn` (the testable core), reusing the shared `matches` filter — no new
  imports (`GobIcon` was already imported for the gob `icon` read); (2) the `clampMsg` REPL-notice clamp
  (above), applied to `log(String)`, `log(Addon,String)`, and the `:lua` `eval` result/error.
- `addons/hello/` — `readRadar` helper (read-only) + manifest bumped to **v0.24.0**.

**No `haven` core edit.**

## Threading & safety

- All access runs on the **UI thread** (the `:lua` console tick or an addon timer/tick) — the same
  thread the settings-window checkboxes mutate on, so we inherit its concurrency model exactly.
- `Settings.settings` is swapped **wholesale** by the icon loader thread (it builds a fresh map and
  assigns it), so a local reference is a stable snapshot to iterate; the individual `show`/`notify`
  booleans may be written concurrently by the loader's `merge` and by our setter, but boolean writes are
  atomic (no torn read) — this is the identical race the existing checkboxes live with.
- `radarName` swallows `Loading`/`RuntimeException` from a custom icon's `name()` and falls back to the
  resource name → a snapshot never throws into Lua.
- `dsave()` is fire-and-forget (defers the actual write); no listener, cache, or adapter is created →
  nothing to leak across a `:reload`/relog.

## Limitations / deferred

- **Read + show/notify only.** The "Icon settings" window also has a per-category **permanent-marker**
  toggle (`Setting.mark`, for markable icons) and a **notification sound picker** (`Setting.resns` /
  `filens`) and a global "notify on newly seen icons" flag (`Settings.notify`) — all deferred (the two
  spec verbs are show + notify).
- **No `*Changed` event** — read on demand (see above).
- **String filter matches `name` only** (the canonical `matches` semantics) — match on `res` with a
  predicate.
- **Registering custom icons per resource** (a fully addon-defined radar overlay beyond the client's
  own categories) is out of scope — that is a draw surface, not the settings registry.
