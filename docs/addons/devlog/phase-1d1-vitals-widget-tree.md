# Phase 1d (part 1) — Widget-tree read mechanism + player vitals

> **Status:** ✅ Implemented; compile verified (`ant hafen-client` → BUILD SUCCESSFUL), headless
> change-detection test (8/8) + LuaJ parse of the harness; **in-game verified ✅** — the positional
> mapping is correct (`hp=1` at full health, `stamina` 1 → draining `0.9998…`, `energy=0.886…`), and
> `VitalsChanged` fired as each bar streamed in (hp, then stamina, then energy) and again on live
> stamina drain. The meters (like the 1c map/char/item data) come up a beat after `OnEnterWorld` —
> `vitals()` was `nil` immediately and populated by `[+3s]`.
> **Design:** [specs/addons/14-widget-tree-reads.md](../../specs/addons/14-widget-tree-reads.md)
> (the mechanism), [specs/addons/13-hooks-and-interception.md](../../specs/addons/13-hooks-and-interception.md)
> (Level-3 inbound `uimsg`), [specs/addons/coverage-gaps.md](../../specs/addons/coverage-gaps.md)
> (B1 "state lives in widget trees", B5 "vitals are not a zero-edit read"),
> [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.player.vitals`).

This is the **first slice of Phase 1d**. It builds the **widget-tree read mechanism** — the general
way the bridge reads (and gets change events for) the large part of client state that does **not**
hang off `Glob` but lives in **`GameUI` widget trees** updated by targeted `uimsg` (audit B1): player
vitals, buffs, FEP/food, study/curiosity, belt, equipment. Everything through Phase 1c read `Glob`
directly; this mechanism unlocks the rest.

To keep one task = one in-game verification (as Phase 1c was split into three), 1d is split by
adapter. **This slice ships the foundation + the first adapter — player vitals** (`hafen.player.vitals`
+ the `VitalsChanged` event). Buffs, FEP/food, study/skills, belt and equip-change events follow in
1d-2 / 1d-3 / 1d-4, each reusing this mechanism.

## The mechanism (Locator + Adapter + inbound-`uimsg` hook)

Three pieces, per [spec 14](../../specs/addons/14-widget-tree-reads.md):

1. **Locator** — find the target widget and keep reading it live. For vitals this is the existing
   `gui()` reach (up from the map view, or a DFS from `ui.root`) plus the **public**
   `Widget.children(IMeter.class)` tree-walk. No reflection is needed to *find* widgets.
2. **Adapter** — the one class that knows a target tree's shape and reads it into a plain Lua
   snapshot, localizing that upstream-volatile knowledge (field names, child layout). This slice has
   one: `VitalsAdapter`.
3. **Update hook → semantic event** — the server pushes changes as `uimsg` to these widgets. A new
   **core tap** in `UI.UiMessage.run` calls `AddonManager.onUimsg(widget, msg)` **after** the widget
   applies the update. That runs off the UI thread, so it only **flags the interested adapter dirty**;
   the per-frame tick re-reads the snapshot and fires the semantic event on the UI thread (principle
   P5, exactly like the `GobAdded`/`GobRemoved` marshalling).

```
server uimsg ──▶ UI.UiMessage.run ──(applies to widget)──▶ onUimsg(w,msg)   [loader thread]
                                                              │ adapter.interested(w) ? mark dirty
                                                              ▼
                          AddonRoot tick ──▶ refresh dirty adapters ──▶ fire "VitalsChanged"   [UI thread]
```

## Two core edits (this is the admitted non-zero-edit read surface — B5)

Phases 1a–1c were zero-core-edit. Reading widget-tree state cannot be: the values are in
`private`/`protected` fields, and the update signal is an inbound `uimsg`. Both edits are minimal,
centralized and tagged `// addon:` (invasiveness allowed by decision D-011 where it enables a whole
class of features — here, every widget-tree tracker):

1. **`src/haven/UI.java`** — one line in `UiMessage.run()`, after the widget applies the message:
   `io.brodgar.addon.AddonManager.onUimsg(wdg, msg);` — the **Level-3 inbound-message tap**. This is
   the single seam every widget-tree adapter's `*Changed` event rides on (and the future
   `hafen.hook.message`). It only enqueues; it never calls Lua.
2. **`src/haven/AddonWidgets.java`** (new, package `haven`) — a tiny accessor exposing the values that
   are package-visible from `haven` but not from `io.brodgar.addon`. The "`SpeakerIcon` trick" the
   spec prefers over raw reflection: it localizes the fragile read in **one** file, and Lua never gets
   reflection (D-017). For vitals it exposes `LayerMeter.meters` (the `protected` bar list).

## `hafen.player.vitals()` — hp / stamina / energy

```lua
local v = hafen.player.vitals()   -- { hp = 0..1, stamina = 0..1, energy = 0..1 } | nil
```

- **Bar fractions only (0..1)** — there are **no absolute numbers and no hunger** anywhere in the
  client (coverage-gaps B5); vitals are `IMeter` bars. `hafen.player.vitals` is deliberately the
  bar-fraction read, nothing more.
- Returns **nil** until the meters are up. The meters **stream in a beat after `OnEnterWorld`** (like
  the 1c map/char/item data), so read on a later tick/timer or off the event — not synchronously in
  the `OnEnterWorld` handler.
- The three bars are read from the HUD's `IMeter` widgets in **creation order** and mapped
  **positionally** to `hp, stamina, energy` (the server creates them in that fixed order). Extra
  meters, if a server adds any, are ignored.

### `VitalsChanged` event

```lua
hafen.events.on("VitalsChanged", function(v)   -- v = same {hp,stamina,energy} snapshot
  -- fires when a bar actually changes value
end)
```

Fires when the server updates a vital bar (stamina drain, energy change, taking damage) — i.e. on an
`IMeter "set"` `uimsg`. The engine keeps the last snapshot and only fires on an **actual value
change** (a bare colour/tooltip `uimsg`, or a re-read with identical values, is suppressed).

> **Initial population — observed.** In the in-game test the three bars **streamed in as individual
> `set` uimsgs** shortly after their meters were created, so `VitalsChanged` fired once as each
> appeared (`hp`, then `stamina`, then `energy` — a key appearing counts as a change). So on this
> server the event does cover the initial values; still, reading `hafen.player.vitals()` once on a
> timer after enter-world is the robust way to get the initial snapshot regardless of ordering, and
> `VitalsChanged` then streams the updates (stamina/energy change within seconds of play).

## Try it from the `:lua` console

```
:lua hafen.player.vitals()
```

Prints e.g. `lua= {"hp":1,"stamina":0.87,"energy":0.5}` in-game and echoes to the terminal
(`[console]`). Before the meters exist it is `null`, never an error. To see `VitalsChanged`, run
around (stamina) or let energy tick.

## The `hello` example (`addons/hello/main.lua`)

A new `readVitals(tag)` helper logs `hafen.player.vitals()`, run in both the `[now]` pass (usually
still `nil` — meters streaming) and the `[+3s]` pass (populated), alongside the 1c map/item/char
reads. A throttled `VitalsChanged` subscription logs the first five changes. It stays our standing
regression harness — one login re-checks Phase 0 / 1a / 1b / 1c **and** this slice. Bumped to
**v0.6.0**.

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```
ant run
```

After `[hello] entered the world`, expect:

- `[hello] [now] vitals: nil (meters not up yet)` — immediately at enter-world (meters streaming in),
- `[hello] [+3s] vitals: hp=1 stamina=<0..1> energy=<0..1>` — the bars once they are up,
- `[hello] VitalsChanged: hp=… stamina=… energy=… (n)` — after moving around / over time.

Then poke it live: `:lua hafen.player.vitals()`. The positional mapping was **confirmed correct**
in-game (`hp` ~`1.0` at full health, `stamina` drops when you run/craft and refills when idle,
`energy` tracks the energy bar) — if a future server orders the meters differently, switch the
mapping from positional to `IMeter.bg`-name matching (the bg names are server-published).

## Files

- `src/haven/UI.java` — **core edit**: the Level-3 inbound-`uimsg` tap in `UiMessage.run()` (one
  line, `// addon:`).
- `src/haven/AddonWidgets.java` — **new** `haven`-package accessor (`meters(LayerMeter)`), the single
  reflection-free read surface for widget-tree internals.
- `src/io/brodgar/addon/AddonManager.java` — `onUimsg` (the tap entry) + `refreshTreeAdapters`
  (tick drain) + the `TreeAdapter` interface + `VitalsAdapter` + `readVitals`/`meterValue`/
  `vitalsEqual`; `hafen.player.vitals`; the `treeAdapters`/`treeDirty`/`vitalsCache` state, reset per
  session in `init()`.
- `addons/hello/` — example + manifest bumped to **v0.6.0**.

## Threading & safety

- The `onUimsg` tap runs on a **Loader thread** under `synchronized(ui)` (server-message
  application). It only does a cheap `instanceof` check and adds to a concurrent set — **no Lua, no
  blocking**. The snapshot re-read and event dispatch happen on the **UI thread** in the tick.
- `readVitals` / `meterValue` swallow `Loading` and null → a partial or nil snapshot, never an error
  into Lua.
- Adapter registration is **session-scoped** (rebuilt in `init()`), so a relog re-resolves cleanly;
  the dirty set and vitals cache are cleared too. (Formal reload/teardown of the whole addon layer is
  Phase 1f; this slice adds no cross-session leak — the tap is engine-lifetime and routes to the
  current session's adapters.)

## Limitations / deferred

- **Vitals are bar fractions only** — no absolute hp/stamina/energy numbers, **no hunger/satiation**
  (they do not exist as client state; B5). Hunger/FEP come from a *different* widget (`BAttrWnd`) and
  land in 1d-2.
- The positional hp/stamina/energy mapping is a **content-defined assumption** (the meters' identity
  isn't otherwise labelled) — confirmed in-game.
- **Buffs, FEP/food, study/curiosity, skills, belt (read), equip-change events** — the remaining
  widget-tree surfaces — are the next 1d slices (1d-2 buffs + FEP, 1d-3 study/skills, 1d-4 belt +
  equip), each an adapter on **this** mechanism.
- No sandbox/watchdog yet (Phase 1f).

With this slice the **widget-tree read mechanism exists end-to-end** (locate → read → inbound-`uimsg`
tap → semantic event), proven by `hafen.player.vitals` + `VitalsChanged`. The remaining 1d adapters
are incremental additions on top of it.
