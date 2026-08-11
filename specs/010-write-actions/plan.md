# 010-write-actions — Plan

> History: this work appears in git history and `learnings/` tagged **4a, 4b, 4c, 4d, 4e,
> 4f, 4g** (Phase 4) + **A3** (the action-bar gated `use`, folded into 4g).

## Approach
- **Find the engine's own send, never invent the wire shape.** Every verb mirrors an exact
  client call site: `moveTo` = `MiniMap.mvclick`'s dummy-`pc` `"click"`; `clickGob` =
  `Gob.GobClick.clickargs` synthesized directly (no render hit-test needed); `menu` =
  `GameUI.act`; `flower` = `FlowerMenu.choose` (wrap-not-reimplement also handles
  client-side petals); item verbs = the `WItem.mousedown` `GItem.wdgmsg`s; 4g wraps
  `Speedget.set`/`Belt.act`/`Buddy.endkin/forget/chname/chgrp`.
- **The permission's evolution is the story**: 4a shipped declaration + config-flag master
  switch (deliberately config-only — no runtime toggle yet meant a stray pref could strand
  it ON); 4b added the panel checkbox + the default-disabled "seen set" policy + master-off
  ⇒ write addons don't load; **4c/D-028 deleted the master switch** — the per-addon consent
  dialog subsumes it (a redundant control is worse than none). `requireActions` is now
  declaration-only; the gate fires BEFORE arg validation.
- **One coordinate space, one index space**: world units everywhere (floored via
  `OCache.posres`/`MCache.tilesz`, the same conversions the read API exposes); action-bar
  and deck indices are the raw 0-based engine indices the reads already return.
- **Items are handle-only (D-022)**: `handle` = the `GItem` wdgid added to the shared
  `itemSnapshot` — both `hafen.items.*` and `model:items()` gain actionability through the
  ONE flat verb (no OO `model:item:take()` second style, D-013); re-resolve per call via
  `UI.getwidget`, fail loud on stale.
- **The harness split**: `hello` went read-only (a write harness would not load on default
  logins under 4b's rule — and stays read-only after D-028 on principle); `walker` is the
  dormant opt-in write demo, one deliberate `:walker` sub-command per verb.
- **Consent dialog** = pure public-widget composition (`ActionsConsentWnd`), floated as a
  `ui.root` child (parenting to the panel clips it — maintainer feedback), `reqclose`
  redirected to `destroy` (client-side window), closed by the panel's `tick` when the panel
  leaves the screen.

## Files created / modified
- `src/io/brodgar/addon/AddonManager.java` (→ `ActApi.java`) — the whole verb surface +
  pure arg builders (`moveClickCoord`/`clickGobArgs`/`itemactArgs`/`placeAngle`/`placeArgs`/
  `selArgs`/`menuPath`/`flowerPetalIndex`/`itemVerbArgs`) + `requireActions`/
  `actionsGranted` + `scanAddonDefaults`/`applyActionsDefaults`
- `src/io/brodgar/addon/Manifest.java` — `permissions` array + `usesActions()`
- `src/io/brodgar/addon/ui/ActionsConsentWnd.java` — new; `ui/AddonPanel.java` — consent
  wiring, `[actions]` markers, enableAll skip (master-switch checkbox added in 4b, removed in 4c)
- `addons/walker/` — new (v0.1.0→v0.6.0); `addons/hello/` — made read-only (v0.33.0/34)
- No `haven` edits in the whole feature.

## Risks & gotchas hit (detail: learnings/actions-gated.md)
- A sticky pref with no UI to clear it = a stranded switch (4a read config-only on purpose).
- The one-time default needs a grows-only SEEN set keyed on the declaring trait — else a
  user-enabled write addon gets re-disabled every load.
- Petals grab mouse+keyboard → `flower` picks must come from a timer/event (programmatic by
  nature); exact match only — an action must not fuzzy-match.
- `kin.setGroup` orders checks so instance resolution precedes the `BuddyWnd.gc` static read
  (`<clinit>` → resource loader, absent headless).
- Some UI surfaces (grabbed input) make verbs inherently automation-only — document, don't
  fight it.

## Discarded alternatives
- A global master switch (4b) — deleted by D-028: redundant with per-addon consent.
- OO item verbs on the model — one flat gated verb (D-013/D-022).
- `kin.add` by name — no such protocol message exists (hearth secret is the real add path).
- Client-side speed availability pre-check — the server is authoritative; stay faithful.
