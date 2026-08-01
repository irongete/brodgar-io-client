# Learnings — Gated actions (hafen.act, permissions)

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- **4a — the ENGINE already shows the canonical encoding for a "programmatic" action; find it before inventing one.**
  The one hard question for `moveTo` was the screen coord `pc` — a real click gets it from a GPU hit-test we can't
  do for an off-screen world coord. Grepping `wdgmsg("click"` across `src/` (not just `MapView`) surfaced
  **`MiniMap.mvclick`** (MiniMap.java:1218): the engine walks-by-minimap-click by passing the **current mouse pos
  as a dummy `pc`** and the real destination as the world arg. That resolved the whole design (dummy `pc`,
  off-screen dest is fine, server uses the world coord) with zero guesswork. **Lesson:** for any action verb, the
  client almost always contains a non-mouse caller of the same `wdgmsg` (minimap, party view, fightview, avaview
  all call `"click"`) — that caller IS the spec for the args. Search the whole tree, mirror it.
- **4a — don't read a persisted pref you have no UI to CLEAR yet; a sticky pref + no toggle = a stranded switch.**
  First cut made the master switch `getprefb("addons/actions", CFG.get())` — pref is state, config is default.
  Clean on paper, but: the *earlier* in-game test (the since-removed `:addons actions on`) had **persisted the pref
  = true**, and after the redesign there was **no in-client toggle left** (console command gone, panel is 4b). So
  the maintainer booted to "GRANTED" with **no way to turn it off** — the config default was moot. Fix: for 4a the
  master switch reads the **config flag ONLY** (`actionsEnabled()` = `CFG_ACTIONS.get()`, default false); the
  persisted, panel-toggled pref returns in **4b** alongside the UI that manages it. **Lesson:** a persisted
  override is only safe to *read* once there's a way to *write/clear* it. Stage the state source with the control:
  config flag while the control is a config edit; a pref once the panel exists. (Watch for stale state a prior
  version's control left behind — it outlives the code that set it.)
- **4a — a gated namespace should be PRESENT with gated verbs, not absent.** Building `hafen.act` always (with
  `enabled()` never-throwing + each verb calling `requireActions`) beats hiding the table when off: an addon can
  feature-detect without `pcall`, and a not-granted call yields a guiding error (how to grant it) instead of a
  `nil`-index crash. The spec's "engine can ship the API absent or disabled" allows either; present+gated is the
  better UX for a single runtime-toggled build. Verifiable end-to-end headless: build the real facade (via the
  private `console()` env / a hand-built `Addon` by reflection), flip the master pref + vary the owner's
  declaration, assert the verb throws the right distinct error / passes to "no map view" — provable without a live
  session.
- **4a/D-027 — reads and writes are the SAME API surface; the only difference is a declared PERMISSION, not a
  separate control surface.** My first cut bolted the opt-in onto the `:addons actions on|off` **console command**
  — which read like "an addon named actions" and split writes off from the uniform `hafen.*` API. The maintainer's
  correction reframed it cleanly: `hafen.act.*` is plain API like `hafen.gob.*`; what gates it is a **permission**,
  modelled like app permissions — (1) a global master switch the USER controls (never the addon — else a hostile
  addon self-grants and the opt-in is moot) + (2) a per-addon **manifest declaration** the AUTHOR makes, so
  the client can warn *before* running it. `requireActions(owner, verb)` checks declaration FIRST (author's bug →
  clearest message) then the switch (user's setting). **Lesson:** when a capability needs an opt-in, don't invent a
  command verb for it — model it as a *permission* (declared + authorized) and keep the capability itself in the
  normal API. Also: the manifest permission is an **array** from day one (`["actions"]`) so future categories
  (`"actions.move"`) don't force a format migration — cheap future-proofing when a field is clearly going to grow.
- **4b — the "seen set": apply a one-time default with a grows-only persisted set, keyed on the TRAIT that
  triggers it.** D-027 default-disables a *newly-discovered* write addon but must not fight the user who then
  enables it. The seen set (`addons/actions.seen`) records write-addon ids we've already defaulted: a write addon
  NOT in it → add to BOTH the disabled set and the seen set (default it once); a write addon already in it → leave
  to the user's choice; read addons ignored entirely. Key on **write-ness, not all ids** — so a read addon that
  LATER adds `"permissions":["actions"]` is treated as newly-declaring and re-opted-out (safer than grandfathering
  it in). The set only ever GROWS in `applyActionsDefaults` (additions-only), which makes change-detection a
  trivial size compare (`if(seen.size() != before) persist`) — same "register a stable indirection, keep live
  state in a bridge-owned set" shape as A11's slash `slashDispatched`. **No first-run grandfather was needed**:
  making `hello` read-only left ZERO pre-existing write addons, so the only default-disable is the genuinely-new
  `walker` — exactly the desired behavior, for free. (Grandfathering would only matter to protect an *existing
  enabled* write addon from retro-disable; there was none.)
- **4b — a persisted override pref is only safe to READ once its control UI exists (the 4a→4b closure).** 4a
  deliberately read the config flag ONLY (`CFG_ACTIONS.get()`), because a stray persisted pref could strand the
  switch ON with no toggle — it had, from an even-earlier `:addons actions on` experiment. 4b adds the panel
  checkbox (the toggle), so `actionsEnabled()` = `Utils.getprefb("addons/actions.enabled", CFG_ACTIONS.get())`
  (pref over config-default) is now safe. Used a **FRESH pref key** (`addons/actions.enabled`, not the 4a-era
  `addons/actions`) so any stale value from that experiment is ignored → a clean default-OFF first boot. General
  rule (already in §8 from 4a, now applied): stage the STATE SOURCE with the CONTROL — config flag while the
  control is a config edit; a persisted pref once the panel exists.
- **4b — split the master switch's two effects: the GATE is immediate, LOADING is apply-on-reload.**
  `actionsEnabled()` reads the pref live, so `requireActions` (the per-verb gate) sees a toggle immediately —
  toggling OFF makes any already-loaded write verb fail-closed at once (good: actions should stop the instant you
  say so). But LOADING a write addon follows the D-006 apply-on-reload model (`setActionsEnabled` only flags
  `reloadNeeded`; `loadAll` gates on the switch). Both are correct and intentional: the gate is safety (immediate),
  the load/unload is lifecycle (batched to reload, like the enabled set). Document the immediate-gate half so an
  author isn't surprised a granted verb starts throwing mid-session when the user flips the switch.
- **4c — gate the SILENT bypass too, not just the obvious path.** The headline was "consent dialog on the enable
  checkbox", but the same gate has a back door: **`enableAll`** would flip every addon's enabled bit with no
  prompt. A per-addon permission that a "select all" can grant silently isn't a permission. So `enableAll` **skips
  write addons** (`if(!ai.declaresActions)`). When you add a consent/permission step, grep for every other path
  that reaches the same state change (bulk actions, console commands, import) and gate or exclude them too.
- **4d — read the ENGINE's own send site, don't invent the wdgmsg shape.** Every action verb mirrors an exact
  `MapView` gesture; the arg arrays came straight from `Click.hit`/`iteminteract`/the place branch of `mousedown`/
  `Selector.mmouseup` + `Gob.GobClick.clickargs` — NOT from the api-reference table alone (which abbreviates). Two
  encodings would have been wrong from the table: `place`'s angle is `round(angle*32768/PI)` (not raw radians), and
  `"sel"` takes **TILE** coords (`mc.round().div(tilesz2)` in the Selector), not world. Factor each verb into a PURE
  arg-array builder (`clickGobArgs`/`placeArgs`/`selArgs`, taking the dummy `pc` as a param) + a thin sender that
  grabs the live `MapView` — the builders are then headless-testable and the senders stay a 3-liner, exactly like
  4a's `moveClickCoord`/`actMoveTo`.
- **4d — a gob click needs no render hit-test: synthesize `Gob.GobClick.clickargs` directly.** The engine builds a
  gob click's tail (`{0, gobid, gob.rc.floor(posres), 0, -1}`) from a live `ClickData`, but for a PROGRAMMATIC click
  you already know the gob — just emit that array from `(id, rc)`. Mesh id `-1` = "bare click", faithful for world
  objects (which use `Gob.GobClick`); a composite player/animal's specific body part would need the `Composited.
  CompositeClick` computed id (deferred). `mc` (the click's world point) = the gob's own floored `rc`.
- **4e — for an ACTION that commits a choice, match EXACTLY (case-insensitive); save substring for READS.**
  `flower(label)` matches a petal by exact ci-name, unlike the read-side `buffs.has`/`items.find` substring. A loose
  match on a *write* could select the wrong menu item (irreversible) — so the canonical rule flips by tier: reads are
  forgiving, actions are precise. (And an action that "does nothing to pick" returns `false`, doesn't throw — reserve
  throws for the permission gate + type errors, so callers needn't `pcall` the normal empty case.)
- **4e — wrap-not-reimplement (D-009) beats re-sending the wire message: call the engine's own method.** `flower` calls
  `FlowerMenu.choose(petal)` rather than re-encoding `wdgmsg("cl", num)` — so a **client-side** petal (the voice
  Mute/Unmute the fork injects in `FlowerMenu.choose`) is handled locally instead of being wrongly sent to the server.
  When the engine already has the exact public method the gesture uses, drive THAT; you inherit its special cases for free.
- **4e — some UI surfaces GRAB input, so the "obvious" trigger can't reach them; the verb is then inherently for
  automation.** An open `FlowerMenu` grabs mouse+keyboard (`ui.grabmouse`/`grabkeys` in `added()`), so you cannot
  type a `:cmd` or press a hotkey to pick a petal by hand while it's up. `flower`'s real use is a **timer/event**
  chain (right-click → wait → pick) — the tick pump keeps running under the grab. Shape the demo around how the
  feature is actually usable, not around a trigger the grab blocks.
- **4f — the "item handle" the spec wanted (D-022) was already sitting there: a server item IS a bound widget.**
  A `GItem` is `@RName("item")`, created by the server and `bind`-ed with an id in `NewWidget.run` (the same seam
  3a taps); the visible `WItem` is only a **client-side wrapper** (`Inventory`/`Equipory.addchild` does
  `add(new WItem(gitem))`, no id). So the stable, facade-safe ItemRef is just **`GItem.wdgid()`**, re-resolved via
  `UI.getwidget(id)` — no bridge registry (→ no leak; the engine's `UI.widgets` already tracks it), no
  `(container,slot)` juggling (D-022). When a spec asks for an opaque handle and the thing is a server widget,
  its wdgid is almost always the answer (same as `LuaModel`/`raw`).
- **4f — add the handle to the SHARED snapshot builder and every reader gains it for free.** Putting `handle`
  in `itemSnapshot` made `hafen.items.*` AND `model:items()` actionable in one edit — which is exactly how the
  Phase-3 `LuaModel` item verbs got delivered without a second OO style (D-013: one canonical `hafen.act.item`,
  not `model:item:take()`). But first CHECK the change-detection: `equipEqual`/`actionbarEqual` compare only
  named fields and the model item poll is identity-keyed, so an extra field is inert — had a differ done a
  whole-table compare, the new field would have spammed `*Changed`. Enrich shared shapes deliberately, and
  re-verify every diff that consumes them.
- **4f — for a WRITE verb, re-resolve-and-fail-loud beats acting on a stale reference.** `hafen.act.item`
  re-resolves the live `GItem` each call and throws a guiding "handle is stale" error if the id no longer maps to
  an item (moved/used/consumed) — never silently acting on whatever took its place. A GobRef has the same
  contract (fresh each call, nil if gone); for an irreversible action that "fail loud" is the safe default.
- **4g — a WRITE verb belongs in its subsystem's namespace, not under `hafen.act`.** `speed.set`/`craft.make`/
  `actionbar.use`/`kin.*` sit next to their reads (`hafen.speed.get`, `actionbar.slot`, `kin.list`), sharing the
  same `requireActions` gate but NOT the `hafen.act.*` prefix — reads and their write pair read together (`use(n)`
  takes the exact index `slot(n)` returns). `hafen.act.*` stays for the map/menu/flower/item/raw primitives that
  have no read subsystem of their own. Wrap-not-reimplement (D-009) makes each verb a one-liner — drive the
  client's own `Speedget.set`/`Buddy.chgrp`/`Belt.act`, never re-encode the wire message.
- **4g — order a gated verb's checks so the headless-safe ones run before any static-field read.** `kin.setGroup`
  validates `group` against `BuddyWnd.gc.length`, but *referencing* `BuddyWnd.gc` forces `BuddyWnd.<clinit>` →
  `UI.<clinit>` → the resource loader (NoClassDefFoundError `dolda/jglob/Loader` under bare `jshell`), same class
  of trap as A7's `Speedget.tips` / A8's `Makewindow`. Resolving the kin FIRST (instance-only: `buddywnd()`/
  `find`/iteration touch no `BuddyWnd` statics) means headless we fail with "no Kin window" *before* the `gc` read,
  so the gate + wiring stay fully testable and the range check is verified in-game. General rule: instance ops on
  a possibly-null widget are headless-safe; a `Class.STATICFIELD` read is not — put it last.
- **4g — read the widget's FULL interaction model (every message AND the UI states/petals that gate them)
  before finalizing a verb surface.** THREE maintainer corrections on the kin verbs, one root cause — I under-read
  `BuddyWnd` and mapped a generic CRUD sketch instead of the game's real model: (1) I dropped `kin.add` ("no
  add-by-name message") — but the *Add kin* field sends **`wdgmsg("bypwd", secret)`** (add by hearth **secret**,
  not name). (2)+(3) I collapsed removal into one verb — but the game has a **two-step state machine**: while a kin
  is active the petal is **"End kinship"** (`Buddy.endkin`) which un-kins them but keeps them **memorized**; only
  then does the **"Forget"** petal (`Buddy.forget`) appear to drop them from the list. Both send the same `rm`, but
  they are two distinct user actions, so 4g exposes **both** `remove` (End kinship) and `forget` (Forget) — the
  server advances active→memorized→gone. Final surface: `add(secret)`/`remove`/`forget`/`rename`/`setGroup` (five
  verbs over four messages `bypwd`/`rm`/`nick`/`grp`, `setGroup` added over the "add/remove/rename" sketch). The
  lesson: a `wdgmsg(` grep is necessary but NOT sufficient — read the widget's `opts()`/petals/state guards too;
  identical wire messages can be distinct user operations, and the client's own vocabulary (D-013), not a CRUD
  template, names the verbs.

- **022 — a write verb that mirrors a DRAG copies the drag's message, and inherits its silence.** `slot:set(res)`
  sends the exact `wdgmsg("setbelt", n, "res", name)` that `GameUI.Belt.dropthing` sends when you drop a
  `MenuGrid.Pagina` on the bar (`src/haven/GameUI.java:224`) — wrap-not-reimplement (D-009) applies to the
  *message* even when there is no client method to call (a drop handler is not callable: it needs a `Pagina`
  instance an addon has no way to mint). Two consequences the surface must state, not hide: (1) an **unknown
  resource name is silently ignored** by the server, exactly as a drag of something nonexistent would be — there
  is no error to report back, only a slot that does not change; (2) the write is **asynchronous** — the server
  echoes a `setbelt` uimsg back and `GameUI` fills `belt[n]` from the *loader* (`GameUI.java:1367`, a
  `loader.defer`), so the very next line still reads the OLD content. `:set` returns self so it chains, but
  `:set(res):use()` activates what was there BEFORE; the honest answer is `ActionbarChanged` on that Slot.
  The "pag" variant (`setbelt n "pag" id`) is deliberately not exposed: pagina ids are session-local and opaque.
- **022 — a gated verb's demo belongs in `walker`; `hello` regression-checks the REFUSAL.** `hello` declares no
  permissions by construction (that is what makes it default-enabled), so a real `:set` call there can only ever
  throw — a "demo" in `hello` would be testing nothing. The split that already holds for `slot:use`/kin/speed/
  craft is the rule: `walker` (opt-in, `actions`) does the write, and `hello`'s per-login contract line asserts
  the gate fires (`setGated=true` via `pcall`). A refusal check is cheap and catches a gate silently going away.
