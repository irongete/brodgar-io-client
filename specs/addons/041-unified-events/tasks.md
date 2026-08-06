# 041-unified-events — Tasks

> **No line limit** (maintainer instruction). Seven tasks, one session each.
>
> **The corpus rule applies to every task**: this is a hard cut with no aliases, so a task that
> retires a spelling **ports the call sites it breaks in the same task**. Leaving the port for later
> turns the whole regression red in between. Every task ends with the full regression green
> (`:t033-1` … `:t040-13` plus its own), per [TESTING.md](../TESTING.md).
>
> Every task reads [EXAMPLES.md](EXAMPLES.md) first — the catalogue and port map are there, and no
> key, payload or refusal message is to be invented at the keyboard.

- [x] **041.1 — `Subs`, `Sub`, and the bus.** The mechanism the rest of the feature is built on:
      `Subs` (keyed multimap + `fire` + OR-accumulated cancel + `has`), `LuaSub` (`:off()`,
      idempotent), and `hafen.event():on(key, fn)` over them. Bus keys stay **PascalCase** (R5) —
      only the **4 `On`-prefixed lifecycle keys** (`OnLoad`/`OnEnterWorld`/`OnUpdate`/`OnDisable`)
      lose their prefix; the other 22 are unchanged. Make the closed set **throw listing the
      catalogue** on an unknown one. `MarkersChanged` hands over the count itself. `Addon.subs`
      becomes the bus `Subs`; the eight `fireX` helpers keep their `hasSub` gate as `Subs.has(key)`.
      `AddonManager.Sub` (the existing record) is **deleted**, not left beside `Subs`/`LuaSub`.
      **Carry the profiling category on the `Subs`** — see plan.md *Profiling attribution*: every
      emitter becoming one `fire` must not flatten `Addon.CATS`' five-way split, so the category is
      per-emitter/key data, not a constant inside `fire`. The bus charges `events`, as today.
      Port the 44 Lua sites on the 4 renamed lifecycle keys (the other ~44 bus sites keep their exact
      spelling — see `EXAMPLES.md` §1.5).
      *Suite proves*: `:on` returns a Sub and `sub:off()` stops delivery; a second `off()` is a no-op;
      two handlers on one key both fire in registration order and `off()` on the first leaves the second;
      the 4 renamed keys are accepted and their `On`-prefixed spellings throw naming the replacement;
      the 22 unchanged keys still work; an unknown key throws; a handler that errors is isolated and
      the others still run; a bus handler's cost still lands in `p:addons()`' **`events`** column and
      not somewhere else (the split survives the merge).
      `[manual]`: one `EnterWorld` line confirming the lifecycle keys fire on a real login.

- [x] **041.2 — `LuaEvent`, and the two message streams.** `hafen.event():action():on(msg, fn)` and
      `:message():on(msg, fn)` replace `hafen.hook():action/:message`; `LuaEvent` arrives with its
      per-shape methods table and an unknown verb that throws. `ev:msg()`/`:args()` replace the fields,
      `ev:sender()`/`ev:target()` become **lazily interned Widget handles**, and the open key set is
      preserved (any string accepted). Keep the `holdsLock` guard on the action path and none on the
      message path. Port the ~20 Lua hook sites for these two levels.
      *Suite proves*: an `action` handler sees `ev:msg()`, `ev:args()` and `ev:sender():type()`;
      `ev:sender():parent()` navigates; `ev:preventDefault()` blocks a send and `ev:resend()` reissues
      without re-entering the chain; two handlers, either one cancelling, both still run; a `message`
      handler reads `ev:target():type()` and `ev:rewrite(t)` applies new args; `preventDefault` beats
      `rewrite`; `ev.msg == nil` (the field is gone); `ev:buton()` throws; an unknown msg name is accepted.
      `[manual]`: one real map click observed through an `action` handler.
      <!-- extra context: `src/io/brodgar/addon/LuaMarshal.java` — the Java↔Lua arg conversion both levels share -->

- [x] **041.3 — Input on ANY widget.** The feature's one new reach: `handle:on("MouseDown"/"MouseUp"/
      "MouseMove"/"Wheel", fn)` on a widget you built, a widget you found by selector, or one an event
      handed you — over `Widget.listen`/`deafen`, one engine listener per (addon, widget, key).
      `ev:preventDefault()` replaces the consume-by-return convention on `AddonWidget`'s four input
      slots, and `w:onClick` is **renamed** to `MouseDown` (it always bound `MouseDownEvent`). Retire
      `hafen.hook():input` and its three magic tokens. Teardown deafens every listened widget.
      *Suite proves*: a native widget found by selector accepts the four keys and hands back Subs;
      the same keys work on an own widget with one vocabulary; `hafen.hook():input(...)` throws naming
      the replacement; `w:onClick(fn)` throws naming `MouseDown`; a handler returning `true` does **not**
      consume (only `preventDefault` does); teardown leaves no listener (re-subscribe and count).
      `[manual]`: click a native window with a `preventDefault` handler installed — the window does not
      react; remove the handler and it does.

- [x] **041.4 — The remaining widget keys.** The other 12 retired verbs, all through the same `Subs`:
      controls (`Pressed`, `Changed`, `Submitted`, `Selected`, `Cell`), containers (`ItemAdded`,
      `ItemRemoved`), own-widget (`Draw`, `Tick`, `Drop`, `Close`) and `Destroy`. `Controls`' five
      capabilities stop holding one slot; `UiApi.Watch` generalises to `Subs` with `pollWatches`
      unchanged in shape. **The one-axis rule lands here**: `Draw` → `ev:g()/:w()/:h()`, `Cell` →
      `ev:g()/:item()/:w()/:h()`, a slider's `Changed` → `ev:value()/:final()`; every other `Changed`
      keeps its bare value. An unknown key throws **listing what that widget does answer**.
      *Suite proves*: each key fires on its own control type and each is refused by name on a type that
      has none (`label:on("Pressed", …)`); all 12 old verbs throw; `btn:onPress()` (the read) is gone;
      a programmatic `:value(v)` still does **not** fire `Changed` (D-153, re-asserted here);
      `Draw`'s `ev` answers `:g()/:w()/:h()` and throws on `:preventDefault()`; a stashed `ev` is inert
      after the callback; two `Draw` handlers on one widget both paint.
      `[manual]`: a two-handler widget visibly painting both layers.

- [x] **041.5 — The mouse entity, the grab, and the end of `hafen.hook()`.** `hafen.ui():mouse()`
      stops being a `{x=, y=}` table and becomes the pointer: `:x()`, `:y()`, `:over()`, `:shift()`,
      `:ctrl()`, `:alt()`, `:grab()`. `LuaGrab` is an emitter — `:on("Move", fn)`, `:on("Up", fn)`,
      `:release()` — and `hafen.hook():grab{move, up}` and its config table are cut. `hafen.hook()` is
      **deleted** here; `HookApi` keeps only slash commands and keybindings. Port the 7 `:mouse()` sites
      and the grab sites (`planner`).
      *Suite proves*: `hafen.ui():mouse().x == nil` (the table read is gone) while `:x()`/`:y()` answer;
      `:over()` agrees with `hafen.ui():at(m:x(), m:y())`, and `:at(x, y)` still answers for an arbitrary
      point; the three modifier verbs answer booleans; `:grab()` takes no arguments and returns a grab
      answering `:on`/`:release`; `hafen.hook` throws as a whole and `hafen.hook():grab{…}` throws naming
      its replacement; a grab left open is released by teardown.
      `[manual]`: hold a grab and drag across the map — the camera does not pan and no move order is
      sent; release and both work again.

- [x] **041.6 — The docs tier.** 33 of 76 pages. `api/event.md` rewritten whole around the one verb and
      the one-axis rule; `api/hook.md` **resolved** — its three levels fold into `event.md`, its grab into
      the UI pages; `api/ui/widget.md`, `controls.md`, `lists.md`, `items.md`, `custom.md`,
      `selectors.md`, `replace.md`; `guides/events-and-timers.md`, `custom-ui.md`, `debugging.md`,
      `hotkeys-and-commands.md`; **`api/client/profiling/attribution.md`** — its `hooks` category is
      defined as "input, action and message hooks plus hotkeys and slash commands", which stops being
      true once input moves onto widgets and the two streams onto the bus; `api/README.md` and both
      "API at a glance" tables.
      **Read area `docs`'s standard before writing** — `specs/docs/design/style-guide.md` §9–§12 plus
      `grep "^### D-" specs/docs/decisions/docs-standard.md` — and **report §12's checklist**: links and
      anchors falsified both ways, every page `wc -l` ≤ 300, headings, the retired-name greps at zero,
      and every `hafen.*` name on a page found in `src/`.
      *Suite proves*: nothing — this task's verification is the §12 checklist report plus the link
      checker at 0 broken. Its `[manual]` line is the maintainer reading `event.md` end to end.
      <!-- extra context: `specs/docs/design/style-guide.md` §9-§12; `specs/docs/decisions/docs-standard.md` (grep the D- headers only) -->

- [ ] **041.7 — The close.** The sweep no per-task suite can do: **every emitter × every key** in one
      pass — the 26 bus keys, the 5 universal widget keys, the 2 container keys, the 4 own-widget keys,
      the 5 control keys over their 12 (builder, key) rows across all 16 builders, the 2 grab keys and
      the 7 mouse verbs — each asserted to
      exist where `EXAMPLES.md` says and to be **refused by name** where it does not. Then the `Retired`
      completeness sweep in one pass (the 4 `On`-prefixed lifecycle bus names + 16 widget verbs +
      `hafen.hook` and its four verbs + the `:mouse()` table read), the cardinality invariants once
      more on a live tree, and a teardown
      leaving the widget tree exactly where it started. Update `STATE.md` (including the **`ChatMessage`
      correction** — it is listed there and in `design/09` but nothing fires it), `FEATURES.md`,
      `specs/codebase/addon-engine.md`, mark `design/13`'s L1/L2/L3 addressing superseded, and write the
      feature's decisions into `decisions/architecture-api.md`.
      **Found by 041.6, still open**: `GobOverlayAdded`/`GobOverlayRemoved` and the three `*Clicked`
      events still fire a plain `LuaTable` (`AddonManager.overlayPayload`, `RenderApi.onGhostClick`) —
      no `041.1`-`041.5` task's checklist ever assigned converting them, so §2.3/R6's "every payload
      member is a colon verb" was never built for these five. **This task implements it**: a
      `LuaEvent.Shape.OVERLAY` (`:gob()`/`:key()`/`:native()`) and `Shape.CLICKED`
      (`:ghost()`/`:sprite()`/`:object()` per `clickKey()`, `:button()`, `:x()`, `:y()`), wired at both
      call sites — then re-ports `docs/addons/api/event.md`'s two composite-payload sections and
      `ghost.md`'s `GhostClicked` example off the table shape 041.6 documented (accurate to `src/` at the
      time) onto the colon verbs.
      *Suite proves*: the full emitter × key matrix; every retired spelling throws naming its
      replacement; no payload member is readable with a dot, **the five composite payloads included**;
      a clean `rm -rf build/classes` rebuild is `BUILD SUCCESSFUL`; the LuaJ parse sweep is green over
      all 64 corpus files.
      `[manual]`: the maintainer runs the full regression list one command at a time.
