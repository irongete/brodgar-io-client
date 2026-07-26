# Refactor — split the 9 200-line `AddonManager` god-class into per-system files

> **Status:** ✅ Implemented; **clean** compile (`rm -rf build/classes && ant hafen-client` → BUILD SUCCESSFUL,
> only the pre-existing `new URL(...)` deprecation warnings). **In-game DoD pending.** *(Java engine change ⇒
> rebuild + full restart.)*
> **Scope:** pure-infra restructuring — **no API change, no behaviour change.** Devlog-only (no `api/` edit).

`AddonManager` had grown to a **9 264-line fully-static god-class** holding every `hafen.*` subsystem inline.
This refactor breaks it into **per-system sibling classes** in `io.brodgar.addon`, leaving `AddonManager` as a
**1 586-line hub** (−83%). Behaviour is preserved throughout — every method moved **verbatim**; only its home
file changed.

## The result

| File | Lines | Owns |
|------|------:|------|
| **AddonManager** (hub) | 1 586 | the **runtime bridge**: lifecycle (attach/init/tick), the engine **seams** (`onUimsg`/`onWdgmsg`/`onMessage`/`onGlobKey`/`onWidgetCreated`/`onWidgetPlaced`/`onGhostClick` — the `haven` core calls these by name), the shared **gob-read + engine substrate** (`getgob`/`resolve`/`gobSnapshot`/`allGobs`/`oc`/`mcache`/`gui`/`xy`/…), event bus (`fire`/`callLua`), timers, the `:lua` REPL, `installHafen` skeleton, and the small `log`/`json`/`events`/`timer` tables |
| **AddonRegistry** | 512 | addon **discovery + load/enable/reload management**: `loadAll` (disk scan), the persisted enabled set (`isEnabled`/`setEnabled` + D-027 default-disable), `reload` (teardown-all + re-load), per-addon `teardown`, and the **AddOns options-panel API** (`describeAddons`/`liveStatus`/`AddonInfo`, used by `ui.AddonPanel`) |
| **HttpApi** | 343 | `hafen.http` (N2a/N2b) — pool, scheduler, tick drain, host-allowlist gate |
| **StoreApi** | 359 | `hafen.store` (1e) — saved-variable persistence + throttled autosave |
| **HookApi** | 640 | `hafen.hook` (L1/L2/L3 + grab), `hafen.key`, `hafen.slash` — interception + input |
| **ActApi** | 756 | `hafen.act` (gated verbs) + `hafen.craft` + `hafen.speed` |
| **WorldApi** | 908 | `hafen.gob`/`world`/`map`/`markers`/`radar`/`time`/`sound`/`music` |
| **UiApi** | 1 349 | `hafen.ui` — windows/overlays (2a/2b), observers/adopt/replace (3a/3b/3c), node walk + hit-testing (W1/W2) |
| **RenderApi** | 1 468 | `hafen.ghost` + `hafen.render` — the V-series ghosts + R-series images/sprites/objects |
| **CharApi** | 2 300 | `hafen.player`/`char`/`items`/`study`/`party`/`kin`/`buffs`/`actionbar`/`quests`/`wounds`/`fight` + the change-detection `TreeAdapter`s |

## The pattern

`AddonManager` stays the **hub**; each cohesive subsystem moves to a `*Api` file. Three mechanisms make the
split behaviour-preserving:

1. **Static import.** Each cluster file opens with `import static io.brodgar.addon.AddonManager.*;`, so a moved
   method's calls to shared hub helpers (`callLua`, `gui`, `xy`, `resolve`, `gobSnapshot`, …) resolve unchanged
   as bare names — the hub helper is simply made **package-private** (drop `private`). No call-site rewrites.
2. **Delegating seams.** The `public static` methods the `haven` core edits invoke (`onUimsg`, `onWdgmsg`,
   `onGhostClick`, …) **stay in `AddonManager`** as one-line facades that delegate to the owning `*Api`
   (`onUimsg` → `CharApi.dispatchUimsg`, `onWdgmsg` → `HookApi.dispatchAction`, …). The core edit points never
   move, so no `haven` edit was needed for the seams.
3. **Lifecycle hooks.** Per-session reset / per-tick / per-teardown work each subsystem owns is exposed as a
   named entry the hub calls: `StoreApi.autosave`/`flush`/`resetSession`, `UiApi.resetSession`/`pollModels`/
   `sweepGobOverlays`, `WorldApi.pollMarkers`/`resetMarkers`, `CharApi.refreshTreeAdapters`/`pollTreeAdapters`/
   `resetSession` (re-registers the adapters), `HookApi.teardown*`, `RenderApi.teardown*`.

**Shared read helpers stay in the hub.** The gob-read/engine substrate and genuinely cross-cutting utilities
(`resIdent`/`resTipName`/`cellPos`/`color`/`luaColor`/`modsTable`, the `describeKeyBinds`/`KeyBindGroup` panel
API, the `HudOverlay`/`GobOverlay` records) are used by ≥2 subsystems (or referenced from `haven.OptWnd`/
`LuaWidget`/`LuaMouseGrab`), so they live in `AddonManager` and every cluster reaches them via the static import.
Only two cross-cluster calls needed explicit qualification (`UiApi` → `CharApi.itemSnapshot`,
`AddonManager.resolve` → `CharApi.partyMemberByOrdinal`).

**Follow-up tidy.** A later pass moved three helpers that had ended up in the hub but are each used by exactly
one subsystem to their real home: `screenToWorld`/`snapPlaceAngle` (`hafen.map`) → **WorldApi**, and `idOf` (only
the slash-reassign log) → **HookApi**. (`describeKeyBinds`/`KeyBindGroup`/`KeyBindEntry` stay in the hub because
`haven.OptWnd` reaches them as `AddonManager.*` — moving them would need `haven` core edits.)

## `haven` core edits

**Effectively zero.** Three same-package addon files got a one-line/​doc touch (`LuaGobOverlay` →
`UiApi.paintGobOverlays`; `LuaHttp`/`Addon` javadoc `@link` re-pointers). `haven.OptWnd`, `haven.UI`,
`haven.MapView`, `LuaWidget`, `LuaMouseGrab` were **not** touched — the seams + shared facades kept their
`AddonManager.*` addresses.

## Verification

- **Clean** compile: `rm -rf build/classes && ant hafen-client` → **BUILD SUCCESSFUL** (only the 3 pre-existing
  `new URL(...)` deprecation warnings). *A clean build is required:* ant's incremental compile does not recompile
  files whose source is unchanged, so it silently masks cross-file breakages when a shared symbol moves — every
  intermediate slice must be checked with a full `build/classes` wipe, not an incremental run.
- No dangling references: each `public` seam is defined once (in `AddonManager`); no symbol is defined twice.
- `AddonManager.java`: **9 264 → 1 586 lines** (−7 678, −83%); 9 new files (8 `*Api.java` + `AddonRegistry.java`).
- The one same-package touch beyond the addon engine: `ui.AddonPanel` now calls `AddonRegistry.*` (was
  `AddonManager.*`) for the panel API — a reference re-point, still **not** a `haven` core edit.

## In-game DoD (for the maintainer)

Behaviour-preserving move — the check is that **every `hafen.*` surface still works exactly as before**. Enable
the `hello` regression addon (+ `netdemo`/`planner`/`widgetstack` as desired), Reload UI, and confirm the usual
smoke tests across the subsystems pass: vitals/buffs/study events fire (Char), overlays + adopt/replace + the
`widgetstack` framestack work (Ui), ghosts/sprites/objects render + click (Render), gob/map/marker/radar reads
(World), `hafen.act.*` verbs + craft + speed (Act), hooks/hotkeys/slash (Hook), saved vars persist (Store), and
`hafen.http` requests (Http). Nothing observable changed — only the file each subsystem lives in.
