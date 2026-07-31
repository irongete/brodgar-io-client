# Learnings — Process, method & API-design judgement

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- **Splitting a task is fine and expected** (the workflow expects it): 1c was 9 sub-APIs across
  OCache/MCache/GameUI/CharWnd/Glob/camera/Audio — too big for one prompt + one in-game verification.
  Split into 1c-1 (gob+world, done), 1c-2 (map/player/time/sound, done), 1c-3 (items/char/party).
- **Splitting 1d (like 1c):** 7 adapters + a core mechanism is too big for one prompt/verification →
  1d-1 (foundation + vitals, done), 1d-2 (buffs + FEP, done), 1d-3 (study + skills), 1d-4 (action bar + equip event).
  Buffs are the odd one: add/remove is **widget create/`cdestroy`** on `Bufflist`, not a `uimsg` (only
  the per-`Buff` `"ch"/"tt"` content change is a uimsg) → detect add/remove by diffing `children(Buff.class)`
  on tick or via a widget-create hook, not the uimsg tap alone. FEP is `BAttrWnd.uimsg("food"/"glut")`
  (clean uimsg→event) but `BAttrWnd` is **not** a `GameUI` field (locate by tree-walk / `@RName("battr")`).
- **Bumping `hello`: sync BOTH the Lua version string AND `manifest.json`'s `version`.** 1f-1 first bumped
  only the `hafen.log("hello loaded (vX)")` string, so the engine logged `loaded hello v0.10.0` (from the
  manifest) while the addon announced v0.11.0 — a harmless but confusing mismatch caught in the in-game
  run. The manifest `version` is what the engine (and the future AddOns panel, 1f-3) actually reports, so
  bump it every slice too.
- **Don't fold a CPU hog into the `hello` regression harness (1f-3) — ship a dedicated `hogtest` addon,
  DORMANT by default.** An always-on hog would auto-disable itself (and hitch) every login, breaking the
  harness. Gate it on an **account-scope saved var** `cfg.arm` (default false, seeded on first run) so a
  normal login is undisturbed; arm it (edit `savedata/account/hogtest.json` to `{"cfg":{"arm":true}}`, or
  the unsandboxed `:lua io.open(...)` one-liner) + `:reload` to demonstrate the auto-disable on demand.
  In-game arming can't be an addon API yet (no console/hook API until Phase 2), so a saved-var file flip is
  the cleanest opt-in. Calibrate the hog with a **wall-clock `os.clock` busy-wait** (~15 ms), not a fixed
  instruction count — it must be reliably OVER the ms budget but UNDER the per-call instruction cap
  regardless of machine speed (a fixed loop count is machine-dependent and could trip the hard cap instead).
- **1f-3 added NO Lua-facing API, so `hello` was left unchanged** (correctly). The panel is operator UI and
  the soft budget is an engine watchdog — neither is a `hafen.*` surface an addon calls. When a task adds
  no addon-observable behavior, extend the harness with a **new example addon** that exercises the engine
  behavior (here `hogtest`) instead of bumping `hello` for no functional reason (a no-op version bump is
  just noise — the 1f-1 learning was about keeping the Lua string ↔ manifest version in sync *when* you do
  bump).
- **Splitting Phase 2 (custom UI + hooks) like 1c/1d/1f:** `hafen.ui` (windows/widgets, overlays, gob
  overlays) + `hafen.hook` (input/action/message) is far too big for one prompt/verification. Slices: **2a**
  custom widgets/windows + GOut wrapper (done), **2b** HUD + gob overlays, **2c** input hooks (L1, zero-edit
  `Widget.listen`), **2d** action hooks (L2, `UI.wdgmsg`) = the `hook.action("click")` DoD, **2e** message
  hooks (L3, `UI.uimsg`) + `hafen.key` hotkeys. L4 method/hookable-subclass hooks fold into Phase 3.
- **Demo L3 with a safe, reversible, VISIBLE suppression: freeze the vitals meters (2e-1).** Hook `"set"` scoped
  to `ev.target == "IMeter"` (the HUD vitals bars use `LayerMeter.uimsg("set")`; the `im` factory builds `IMeter`
  directly, so the class simple name is exactly `"IMeter"`). While a toggle is ON, `ev:preventDefault()` swallows
  the update → the hp/stamina/energy bars **freeze** on the real HUD (and in the 2a window, which reads
  `hafen.player.vitals()` off the same meters) — a clear on-screen effect. It's purely cosmetic and fully
  reversible: the server still knows the true vitals; the next un-swallowed `set` thaws the bars. This is the
  cleanest L3 DoD that doesn't risk corrupting client state (unlike swallowing structural/inventory messages).
  NB: `"set"` is a common message name (many widgets use it), so the hook fires for every `"set"` and early-
  returns on non-`IMeter` targets — fine for a demo, and it shows filtering by `ev.target` in action.
- **`hello` demo idiom for an assignable-from-panel hotkey (2e-3): bind a SECOND key UNBOUND by default
  (`hafen.key.bind("ping", nil, fn)`).** It contributes a second row to the addon's section and starts as
  `None`, so it exercises the panel's assign-from-scratch flow (the user picks a key, it fires + persists) —
  complementing the pre-bound `toggle` (Ctrl+H). Two rows also prove the per-addon grouping visually.
- **Ship the replacement demo as a DORMANT dedicated addon, like `hogtest` (3c).** A `bags` addon that
  auto-replaces the inventory on every login would (a) change the routine `hello`-regression login and (b) double up
  with `hello`'s 3b inventory adoption. So `bags` is **dormant behind a hotkey** (Ctrl+Shift+I toggles
  replace/restore) — routine logins are undisturbed, `hello` and `bags` coexist (both restore correctly), and the
  toggle exercises the **scan** path every press (the inventory is already open in-world). The toggle also gives the
  cleanest live DoD: press → replaced, press again → restored, or disable+`:reload` → restored. **Honest limit noted
  in the doc:** hiding the wrapper doesn't stop the client's own `Tab`/menu `togglewnd` from re-showing it (a full
  replace would intercept that binding too — later/Phase-4).
- **A demo that WRITES to a shared persistent store must clean up after itself (A1).** `markers.add` writes the
  real on-disk map DB, so the `hello` regression harness (many logins) can't add on every login or it accumulates
  "Hello marker" pins forever. Pattern: gate the write behind a **hotkey toggle** (Ctrl+Shift+M adds/removes), track
  the ref in a file-body local, and **remove it in `OnDisable`** (teardown cleanup) so a relog/`:reload` with a pin
  still placed clears it. A normal login writes nothing. (Persistence-across-relog is then demoed via a `:lua
  hafen.markers.add(...)` one-off that skips the cleanup — documented.) Same "dormant, non-polluting demo" spirit as
  `hogtest`/`bags`, but for a *write* surface rather than a CPU/UI one.
- **When a subsystem is "mostly from 1d", the leftover is a small read-only completion, not a re-do (A4).** The
  most-wanted trackers (curiosity slots, FEP) already had events in 1d; A4's remaining scope was just the static
  reference tabs (buyable skills, credos, lore) — reads change only on explicit player actions, so **no adapter/
  event** was warranted. Extracting the shared `resTipName`/`resIdent` (tooltip-name + identity, both Loading-guarded)
  from the 1d-3 `skillName`/`skillRes` made all three new reads one-liners and kept the Loading discipline uniform.
- **A persisting mutator must NOT run in the `hello` regression harness (it would corrupt the user's real config).**
  Unlike the `A1` marker demo (which cleans up its own pin on disable), radar's setters write the user's actual icon
  settings — no clean "undo marker" equivalent — so `hello` stays **read-only** and the mutation DoD is a documented
  `:lua` step the maintainer runs + reverts. Pattern: harness exercises **reads**; **persisting writes** are covered
  by the headless test + a manual console step.
- **A10 — zero-edit vs a one-line accessor is a JUDGEMENT CALL; default to the tradition, defer the extra.** School
  NAMES sit in the private `FightWnd.saves[]` — a trivial `AddonWidgets` accessor away (the 1d-1 vitals pattern),
  and D-011 permits it. But the whole A-series (A2/A4/A6/A7/A8/A9) has been proudly **zero-`haven`-edit**, and the
  names are a nicety (the headline is the deck + maneuvers; switching schools is Phase-4 gated anyway). So expose
  what's public (`usesave`/`nsave` identify the active slot by index) and **defer** the private field with a clear
  note, keeping the slice tight for one in-game verification. Invasiveness is allowed, not obligatory — reach for a
  core edit when it unlocks the HEADLINE feature (1d-1 vitals had no zero-edit path), not a garnish.
- **4b — the always-on regression HARNESS cannot be a WRITE addon (the deep D-027 consequence).** 4a had `hello`
  declare `"actions"` to demo `moveTo`. But 4b's "master switch OFF ⇒ write-declaring addons don't load" rule,
  combined with the switch defaulting OFF, means a write `hello` **vanishes on a default login** — taking the
  ENTIRE regression suite with it. There is no clean exemption (the spec mandates unloading). So the harness must
  be **read-only** (`hello` v0.33.0: dropped `permissions`, removed the `hafen.act` demo → always loads regardless
  of the switch), and the write demo moved to a **separate, dormant, opt-in `walker` addon** (declares `"actions"`
  → default-disabled). This is architecturally right, not a workaround: *modding* (reads/UI/events) is always-on;
  *driving the character* (writes) is a gated, opt-in surface — so the thing that must always run can't be the
  thing that's gated off by default. Same "dormant dedicated demo" spirit as `hogtest` (CPU) / `bags` (replace).
  A bonus: `walker` only ever loads when the switch is on AND it declared the permission, so `hafen.act.enabled()`
  is **always true inside it** — a clean invariant for the write demo.
- **D-028 — a redundant control is worse than none: the per-addon consent SUBSUMES the global switch.** 4b shipped
  a two-part gate (global master switch × per-addon declaration+enable). Once 4c made *enabling* a write addon a
  knowing, consent-gated act, the global switch added friction without adding control — every state it could
  express was already reachable per addon. Maintainer call: drop it. The lesson is to re-examine earlier gates
  after a later one lands; "defense in depth" that duplicates an existing decision point is just a second thing to
  toggle. Net: **enable-with-consent = grant** is the ONE canonical control (D-001 "one way").
- **D-028 — reverting a CLOSED maintainer decision is legitimate, but treat it as a first-class decision.** D-027
  explicitly chose the master switch; removing it isn't a silent code tweak. Path that kept the project coherent:
  a NEW decision (**D-028**) that records what/why + marks D-027 "refined", update the specs it cited (12/10/
  api-reference), and add **superseded-in-part notes** at the TOP of the affected `docs/addons/` files (4a/4b) —
  don't rewrite history, flag it and point forward. When unsure how far the change reached, `grep` the removed
  symbols across `src/`+`addons/`+`docs/` (`actionsEnabled`, "master switch", "Allow addon actions", …) and fix
  every hit, including addon comments/manifests and the demo (`walker`) that narrated the old model.
- **D-028 — "add ONE task then stop" doesn't cover a maintainer redesign mid-queue.** This came in as a direct
  request ("quiero quitar lo del enable global"), not a queued task. When the ask has a genuine design fork
  (here: is enable-with-consent the grant, or a separate persisted grant?), a single focused `AskUserQuestion`
  before the refactor beats guessing — the answer ("habilitar = conceder") set the whole shape.
- **Colour has ONE canonical shape in this API — named keys `{r=,g=,b=[,a=]}`, 0..255 — even though a spec example
  showed positional.** The 16-virtual-entities sketch wrote `tint = {80,160,255,180}`, but `hafen.markers` add-opts
  (`luaColor`) and the colours `hafen.party`/`hafen.kin` RETURN are all named-key. Reuse `luaColor` (with `a`
  defaulting to 255) so there is exactly one colour convention; a spec's illustrative literal does not override the
  already-shipped canonical form (§1 "one canonical way").
- **V5: a "gizmo" task is naturally two slices — the Java primitives, then the Lua gizmo (D-031).** Doing the
  primitives (raycast + snap + grab) with a body-drag proof (V5a), THEN the arrow-handle gizmo over them (V5b), keeps
  each one in-game-verifiable and front-loads the hard infra (async readback, capture, snap reuse) so the gizmo layer
  is pure Lua. Split at the primitive/behaviour seam whenever a capability is "engine exposes X, addon orchestrates X".
- **R2b: generalize a subsystem event by naming it from the entity, not the call site.** The V2 click intercept
  (`MapView.Click.hit` → `onGhostClick`) was ghost-only; widening it to sprites needed **zero new core edit** — just
  `findGhostByGob`→`findEntityByGob` (scan `ghosts`+`sprites` → `LuaWorldEntity`) and let the base class name its own
  event via abstract `clickEvent()`/`clickKey()` (`GhostClicked`/`ghost` vs `SpriteClicked`/`sprite`). A **fixed**
  sprite is pickable (its quad renders into the clickmap); a **billboard** has no world mesh, so `clickable` is a
  harmless no-op — pick surfaces need geometry, and a 2D blit isn't in the 3D clickmap.
- **R2b: the D-013 shared core pays off at the EXAMPLE layer too — the planner became kind-agnostic with a rename.**
  Because a sprite handle and a ghost handle are identical, generalizing the `planner` to place both was mostly
  `it.ghost`→`it.entity` + a `kind` discriminator on the record; selection, the gizmo, grab, and grid-anchored
  persistence are untouched (they only ever call `:pos()`/`:move()`/`:alpha()`/…). Persist `kind`/`img`/`billboard`
  alongside `res`, default old layouts to `kind="ghost"`, and one code path drives ghosts + fixed + billboard sprites.
- **Ship the LIGHTEST asset that proves the flow.** The planner ships the 940-byte `cube.glb` (the R3a test cube), NOT
  the maintainer's 2 MB `tank.glb` — the *textured* render is already proven by `:hello object` (R3b-1); the planner's
  job is the **editor flow** (select/gizmo/persist), which is mesh-agnostic. A tiny generic cube is also a more natural
  "blueprint block" for a base planner. (The `bin/addons/hello/cube.glb` build artifact from R3a was the source — a valid
  glTF v2 the R3a checks + in-game already vetted.)
- **A "closed" enum can still be wrong — grow it rather than overloading a neighbour.** D-043 fixed the scope
  list at F1, and F3c's in-game pass immediately exposed a missing member: the embossed in-window **section
  headings** are neither window captions nor body text. Folding them into `"label"` would have chained a 25 px
  display font to 18 px body text (no way to restyle one alone) and into `"window.title"` would have tied them to
  the window chrome. Adding `"heading"` costs a documented spec amendment and one line in `SCOPES`, and keeps every
  surface independently refinable — cheaper than a scope users cannot use precisely. **Rule of thumb: if two
  surfaces have different stock sizes/recipes and a user could plausibly want one changed and not the other, they
  are different scopes.**
- **The SAME "wiring is right, screen unchanged" trap hit twice — so make counting the composer a checklist
  step.** F3c learned it for `"label"` ([[route-the-surface-not-the-class]]); F3d repeated it for `"tooltip"` by
  trusting the spec's "tooltip foundry" hint and routing the display-time string + `settip` + menu-grid sites —
  all real, all rarely hovered. The surface was `ItemInfo`, the tooltip **ENGINE** whose `longtip`/`shorttip` has
  **17 call sites** (`WItem`, `Buff`, `LayerMeter`, `Makewindow`, `MiniMap`, `CharWnd`, `MenuGrid`, `resutil`).
  **Before routing any scope, grep the STATIC composers** (`Text.render(`, `RichText.render(`, `<Foundry>.render(`)
  **and count call sites** — the count, not the spec's wording, names the surface. Bonus finding: in this client
  `RichText.render(…)` is *almost exclusively* a tooltip call (12 of 15 sites), which is itself a map of where
  the rich tooltips live.
- **(F5) The last slice of a series is where the earlier mechanisms pay off.** F5 was budgeted as the priciest slice
  and shipped as one of the cheapest, purely because F3d's dynamic scope, F1's owner-tagged stacks + `gen`, and W1's
  node handle already existed. When a queued task looks expensive, re-read what the intervening slices built.
- **(F5) Choose the OBSERVATION POINT as carefully as the wiring.** The first in-game run of `:hello node` styled the
  Inventory window and only its title changed — correct behaviour, zero evidence: an inventory holds `WItem` **icons**,
  so its only text *is* the caption (item names live in tooltips). This is [[route-the-surface-not-the-class]] one level
  up: the surface was routed fine, the demo target had nothing to show. A harness that picks a target automatically
  should **score candidates by what the feature can actually affect** (here: descendants reporting a `:text()`) and
  print the scores, so a null result is self-diagnosing.
- **(017) A hard cut is ONE task or it is a broken client.** Deleting the flat `hafen.gob` table breaks every addon
  the instant it lands, so "migrate Java first, addons/docs next" would have left the client unusable *between*
  tasks. The split that does work is by **verifiability**: everything in-game-testable in one atomic task, and the
  purely textual closure (decisions, superseded banners, the contract doc) in a second one that has nothing to
  verify. Corollary for the harness: the checks that prove a *removal* (`hafen.gob.health == nil`,
  `pcall(hafen.gob, "player") == false`) belong in `hello` alongside the ones that prove the new surface — the cut
  is a feature and needs a regression test like any other.
- **(017) `ant hafen-client` is incremental and WILL false-green when a symbol moves between files.** `rm -rf
  build/classes` before believing a compile that deleted/relocated something as widely referenced as the central
  gob resolver. Cheap, and the only way the "everything still compiles" claim means anything.
- **(018.4) `hello` is the standing regression harness, NOT the home of every demo.** By 018 its manifest
  description alone is a wall of text and `main.lua` is 1785 lines — the maintainer's call was to give
  `hafen.client:options()` its own addon (`optionstest`, "Brodgar.io Options Test"). The split that works:
  `hello` keeps the *cheap, always-on* proof that a surface still exists (its four hotkeys already exercise
  `options:keybindings():register`), while a *deliberate, sub-command-driven* exploration of a whole
  namespace gets a dedicated addon. `AREA.md` already sanctioned this ("propose a dedicated example addon");
  the trigger to actually use it is when the demo needs more than a handful of lines at load time.
- **(018.4) A harness that WRITES the user's settings must round-trip every value.** read → write → read back
  → restore, all four logged on one line, so the demo proves the write stuck *and* leaves nothing changed.
  Two corollaries that only show up once you write real prefs: never demo the option a restart gates
  (`scale`) — it cannot be shown to work in the same session anyway; and never remap a key the user might
  own, since the client enforces one-key-one-action and `set` silently unbinds the previous holder — pick a
  candidate `list()` reports as free, and skip the demo rather than steal one.
