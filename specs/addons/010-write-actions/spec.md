# 010-write-actions — Spec

## What & why
The **gated write tier** — the one part of `hafen.*` that drives the character (sends player-
action `wdgmsg`s) instead of observing. The permission model matured across the feature:
D-027 (declared per-addon permission + global master switch) → **D-028** (per-addon ONLY —
the enable-time consent dialog IS the grant, no global switch). Verbs: the `hafen.act.*`
family (moveTo, clickGob, useItemOn, place, select, raw, menu, flower, item) plus the
per-subsystem verbs living with their reads (`speed.set`, `craft.make`, `actionbar.use`,
`kin.add/remove/forget/rename/setGroup`). Everything is server-authoritative — an addon can
only do what a player click could; the gate is about the USER controlling which addons act on
their behalf (H&H permits botting; this is a user-control permission, not a legality gate).
Design: [design/12-security-and-permissions.md](../design/12-security-and-permissions.md);
encodings: [design/09-events-catalog.md](../design/09-events-catalog.md) appendix.

## Acceptance criteria (verified in-game)
- [x] Permission: a verb runs iff the addon declared `"permissions": ["actions"]`; distinct
      guiding errors (declaration vs, historically, the switch); `hafen.act.enabled()` never
      throws (feature-detect without pcall); the trusted `:lua` REPL declares implicitly.
- [x] Write addons **default-disabled** at first discovery (the grows-only `actions.seen`
      set — a user-enabled write addon is never re-disabled); panel rows carry an
      `[actions]` marker; "Enable all" skips write addons.
- [x] Consent dialog (D-028): ticking a write addon pops "wants permission to act on your
      behalf…" — Enable persists + ticks, Cancel leaves it off; one dialog at a time;
      closing the panel closes it; read-only addons toggle instantly.
- [x] `moveTo(x,y)` walks the character (the click-macro DoD — the engine's own
      `MiniMap.mvclick` dummy-`pc` encoding); `clickGob(ref[,button[,mods]])` opens a
      context menu on right-click; `useItemOn`/`place`/`select` faithful to the MapView
      sends; `raw(target, msg, …)` reproduces any of them.
- [x] `menu(path…)` = the client's own `GameUI.act` (`:walker menu lo cs` logs out to char
      select); `flower(label)` picks a petal of the open FlowerMenu via its own `choose`
      (exact case-insensitive match — an action must not fuzzy-match), returns boolean.
- [x] `item(itemOrHandle, verb[,n])`: take/drop/transfer/iact/itemact via the item's
      **handle** (= `GItem` wdgid, D-022 — set on every snapshot, so `hafen.items.*` AND
      `model:items()` became actionable at once); stale handles error cleanly.
- [x] Per-subsystem verbs (completes the read+write pairs incl. the old action-bar gap):
      `speed.set(0..3)`, `craft.make([all])` (consumes ingredients like the button),
      `actionbar.use(n[,mods])` (the same 0-based index `slot(n)` reads),
      `kin.add(secret)`/`remove`/`forget` (the two-step drop)/`rename`/`setGroup`.
- [x] `hello` stays READ-ONLY (the always-on harness cannot be a write addon — it would
      vanish on default logins); the write demo is the dormant opt-in `walker` addon
      (`:walker walk/click/use/sel/place/raw/menu/flower/item/speed/craft/bar/kin`).

## Out of scope
- Composite sub-mesh targeting for `clickGob`; `useItemOn` on a gob (raw covers it);
  finer permission categories (`actions.move`…, the array is ready); `MenuOpened` event;
  stack-split takes; re-consent on a read→write manifest flip.

## Context files
- `design/12-security-and-permissions.md` + decisions D-010/D-025/D-027/**D-028**
- `design/09-events-catalog.md` — the action-message reference (arg-shape authority)
- `src/io/brodgar/addon/ActApi.java` (post-split home), `Manifest.java` (permissions),
  `ui/ActionsConsentWnd.java`, `ui/AddonPanel.java` (consent wiring)
- `src/haven/MapView.java` (Click.hit/itemact/place/sel), `WItem.java`/`GItem`,
  `GameUI.java` (act/belt), `BuddyWnd.java`, `Speedget.java`, `Makewindow.java`, `FlowerMenu.java`
- `docs/addons/api/actions.md`, `speed.md`, `craft.md`, `actionbar.md`, `kin.md` — shipped surface
- `addons/walker/` — the opt-in write demo; `../009-gap-subsystems/` — the paired reads
