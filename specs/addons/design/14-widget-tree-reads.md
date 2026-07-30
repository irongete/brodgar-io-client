# Widget-Tree Reads (the GameUI-bound data)

> **Status:** 🟡 Draft · **Spec:** AddOns · **Foundational (build first, [D-026](../decisions/process.md))**
> **Related:** [01-architecture.md](01-architecture.md) (B1), [coverage-gaps.md](../ROADMAP.md) (B1/C2), [13-hooks-and-interception.md](13-hooks-and-interception.md), [API-REFERENCE.md](../API-REFERENCE.md)

## The problem this solves

Per the audit ([B1](../ROADMAP.md)), most high-value reads do **not** hang off `Glob` — they
live in **`GameUI` widget trees** and are updated by targeted `uimsg`: player vitals
(`GameUI.meters`/`IMeter`), buffs (`GameUI.buffs`/`Buff`), character sheet/attributes/skills/study
(`CharWnd`, `SkillWnd`, `Curiosity`), FEP & hunger (`BAttrWnd.FoodMeter`/`GlutMeter`), the action bar
(`GameUI.belt` — the engine's "belt"), equipment (`equwnd`→`Equipory`). Some fields are **private/protected**, so reading
them needs reflection or a core hook ([B5](../ROADMAP.md)).

Without a general mechanism, the most-wanted H&H addons (FEP/curiosity timers, buff bars, unit
frames) have **no end-to-end path** ([C2](../ROADMAP.md)). This document designs that mechanism.

## The mechanism: Locator + Adapters + Update hook

Three pieces in the bridge (`io.brodgar.addon.api.tree`):

### 1. Widget Locator
Finds a widget of interest and keeps a live reference, by a stable **key**. Sources, in order:
- **GameUI public fields** (direct, cheapest): `gameui.map/menu/maininv/equwnd/chrwdg/buffs/fv/
  buddies/beltwdg` are `public` ([GameUI.java](src/haven/GameUI.java)). The locator resolves
  `ui.root.getchild(GameUI.class)` then reads the field.
- **The `WidgetCreated` hook** ([UI.NewWidget.run](src/haven/UI.java:433), [09](09-events-catalog.md)):
  for widgets identified by `@RName` type that are **not** GameUI fields (e.g. `battr`, `credo`,
  `expls`, `im`, `epry`), the locator watches creation and binds the key when one appears.
- **Tree-walk** for sub-widgets: `getchild(Class)` / `children(Class)` under a known parent (e.g.
  `chrwdg` → `SkillWnd`, `battr` → `FoodMeter`).

Locations are **lazy** (resolved on first use / on `OnEnterWorld`) and re-resolved after Reload.

### 2. Read Adapters (the one place that knows widget-tree shape)
Per target widget type, a Java adapter reads it into a plain Lua snapshot. This **localizes the
fragile, upstream-volatile knowledge** (field names, child layout, resource names) into one class
each — honoring [P1](01-architecture.md): upstream churn breaks one adapter, not addons.

Planned adapters (target → what it reads):
| Adapter | Target | Reads (snapshot) |
|---|---|---|
| `VitalsAdapter` | `GameUI.meters` (`IMeter`) | `{hp,stamina,energy}` 0..1, matched by `IMeter.bg` — **reflection** (private `meters`, protected `LayerMeter.meters`) |
| `BuffsAdapter` | `GameUI.buffs` (`Bufflist`/`Buff`) | `[{res,name,amount,cooldown,number}]` via `buffs.children(Buff.class)` + `Buff.info()` |
| `FepAdapter` | `BAttrWnd.FoodMeter`/`GlutMeter` | FEP entries + hunger/glut level — **reflection** (`BAttrWnd.feps`) |
| `StudyAdapter` | `CharWnd` + `resutil.Curiosity` | study/curiosity slots: res, attention, time |
| `SkillsAdapter` | `SkillWnd` (`credo`/`expls`) | known skills, credos, experiences |
| `ActionbarAdapter` | `GameUI.belt` (`BeltSlot[144]`; the engine's "belt" = the action bar) | per-slot `{res, name, cooldown}`; use via action tier |
| `EquipAdapter` | `equwnd`→`Equipory.wmap` | equipped items per slot (`Collection<WItem>`) |

Adapters that touch private/protected fields use a small `haven`-package accessor (the
`SpeakerIcon` trick) rather than raw reflection where possible; where the field is in another
package, reflection with a cached `Field` and a safe fallback (like `SpeakerIcon` does for the
buddy label). **This is an admitted non-zero-edit read surface** ([B5](../ROADMAP.md)).

### 3. Update hook → semantic events
The server pushes changes via `uimsg` to these widgets. The bridge installs an **L3 message hook**
([13](13-hooks-and-interception.md)) on the located widget's id; on the relevant messages the
adapter re-reads and the engine:
- updates the cached snapshot, and
- fires a **semantic event** (`VitalsChanged`, `BuffAdded`/`BuffRemoved`, `FepChanged`,
  `StudyChanged`, `ActionbarChanged`, `EquipChanged`).

Message→event map (examples): `IMeter "set"` → `VitalsChanged`; `Buff "ch"/"tt"` + buff
add/`cdestroy` → buff events; `BAttrWnd feps.update` → `FepChanged`; `GameUI setbelt/setbelt2` (the
action bar; `belt[]` is set on a **deferred** loader task, so diffed per-tick) → `ActionbarChanged`.

## What it powers (API)
These `API-REFERENCE.md` surfaces are backed by this mechanism (they carry a "widget-tree" note):
- `hafen.player.vitals()` (VitalsAdapter) · `hafen.buffs.*` (BuffsAdapter) ·
  `hafen.char.food()`/`hafen.study.*`/`hafen.char.skill()` (Fep/Study/Skills) ·
  `hafen.actionbar.*` (ActionbarAdapter) · `hafen.items.equipment()` (EquipAdapter).
Each also gets its `*Changed`/`*Added` event so addons are event-driven, not polling.

## Reload / teardown
Adapters register their L3 message hooks and (for `WidgetCreated`-located ones) their creation
watchers through the bridge's owned-resource registry ([P2](01-architecture.md)). On Reload the
locator re-resolves and adapters re-install; on disable the hooks are removed. Cached snapshots are
dropped. No leaks.

## Risks & mitigations
- **Reflection fragility / access:** private/protected fields can change upstream. Mitigation:
  one adapter per surface, cached `Field`, defensive fallback (never throw into a read), a
  self-test on `OnEnterWorld` that disables a broken adapter and logs (the API returns `nil`, not a
  crash). Prefer a `haven`-package accessor over reflection when the field is package-visible.
- **Upstream churn:** adapters are the maintenance surface. The `api_version` ([03](03-addon-format.md))
  bumps if an adapter's output shape changes.
- **Sandbox:** reflection lives in Java adapters; **Lua never gets reflection** ([D-017](../decisions/security-sandbox.md)).
- **Threading:** all reads/hooks run on the UI thread (message hooks are marshalled if needed,
  [13](13-hooks-and-interception.md) L3 runs under `synchronized(ui)`).

## Build note
This mechanism is **foundational** and built **before** the map/action-bar/study/FEP APIs ([D-026](../decisions/process.md)),
because those APIs are thin Lua facades over these adapters + events.
