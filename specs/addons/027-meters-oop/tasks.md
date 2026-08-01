# 027-meters-oop — Tasks

- [x] **027.1 — `LuaMeter` + the `hafen.meter` namespace + the `player():vitals()` cut.** New
      `LuaMeter.java`: userdata over `haven.IMeter`, per-`Addon` weak-valued `Cache` keyed by widget
      identity (`IdentityHashMap` + `ReferenceQueue`, `Ref` carries its key, `drain()` on every access
      — the `LuaBuff` shape), metatable, methods `:res/:index/:value/:color/:segments/:exists/:info`,
      and the `factory(owner)` callable table (`hafen.meter()` = the HUD meters 1-based in HUD order;
      `hafen.meter(needle)` = the first whose res name contains it; number-key error naming the
      collection form, empty-string error, miss → nil). New `// addon:` `AddonWidgets.hudMeters(GameUI)`
      over the private `GameUI.meters`, filtered to `IMeter`. `Addon.meters` field;
      `CharApi.installMeters` = the one-line install; delete `readVitals`, `vitalsEqual`, `VITAL_KEYS`,
      `vitalsCache` and the `player():vitals()` method (`VitalsAdapter` keeps compiling by firing
      nothing yet — 027.2 replaces it). Verify in `:lua`: the array matches the HUD bars, **log every
      `:res()` and record the real names in this folder** (they are server-published), the lookup
      returns the *same object* as the array entry, `hafen.player():vitals` is nil, a still-`Loading`
      meter answers `:value()` and nil `:res()` without erroring.

- [ ] **027.2 — the adapter and its three events.** `VitalsAdapter` → `MeterAdapter`:
      `interested` = `IMeter` + `"set"`/`"col"`; `refresh` re-reads the cached meters and fires
      `MeterChanged` on a real value-or-colour change; `poll` diffs the HUD meter list per tick and
      fires `MeterAdded` / `MeterRemoved` (`BuffsAdapter`'s shape — fire the removal *before* dropping
      the entry). The per-meter change key is a value-comparable snapshot held inside the adapter, never
      handed to Lua. `AddonManager.fireMeter(String, IMeter)` beside `fireBuff`: `hasSub`-gated,
      per-addon interning, addons + the `:lua` owner. `VitalsChanged` deleted. Verify: at login the
      bars arrive as `MeterAdded`; running/taking a hit fires `MeterChanged` with the right object and
      **no idle spam**; a stashed `MeterRemoved` payload still reads and answers `:exists()` false; an
      addon that subscribes to nothing costs nothing (the gate).

- [ ] **027.3 — docs, harness, coverage toll, close.** New `docs/addons/api/meters.md` (the two call
      forms, the entity table, "the res names are server-published — here are the ones this server
      shows today, and `:res()` is how you list them", the three events, `:info()` as the escape
      hatch, and the standing "no absolute numbers, no hunger — see `hafen.char.food`" note moved here
      from `player.md`); rows in `api/README.md` + `docs/addons/README.md`; `player.md` loses
      `vitals()`, `types.md` loses `Vitals`, `events.md` swaps one row for three, `conventions.md` /
      `hooks.md` / `getting-started.md` lose their vitals mentions. `hello`: `readVitals` becomes the
      once-per-login `hafen.meter` contract check (list, lookup hit/miss, identity, the number-key and
      empty-string errors, `:exists()`, `vitalsGone`), the three handlers print from the object, the
      HUD overlay reads the meters through the new section (the MMB vitals-freeze demo stays — it is an
      L3 `IMeter` hook and still valid); `manifest.json` version bump. `specs/codebase/services.md` —
      extend the vitals row (coverage toll). D-063 in `decisions/architecture-api.md`; the learnings
      (the positional map replaced by a published-name search; colour is a real change signal;
      `GameUI.meters` vs a `children(IMeter.class)` DFS) into `learnings/widget-tree-reads.md`.
      Verify: one login, every `hello` meter line passes, full regression clean, no `docs/` hit for
      "vitals" outside its own history.
