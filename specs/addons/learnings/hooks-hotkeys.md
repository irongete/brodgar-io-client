# Learnings — Hooks (L1/L2/L3) & hotkeys

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- **Input hooks are a BUILT-IN pre-hook — `Widget.listen`/`handle` (2c, zero core edit).** `Widget.handle`
  runs registered `EventHandler` listeners **before** the widget's own default (`shandle`), and a listener
  returning `true` **short-circuits** — the default never runs. `Event.dispatch` then returns immediately, so
  a consuming listener also **blocks child dispatch** (preventDefault ⇒ stopPropagation at this seam → expose
  **one** consume, not both). So `hafen.hook.input` needs no `haven` edit: wrap `listen`/`deafen`. Event
  fields are public: `Widget.PointerEvent.c` (coord, all mouse events), `MouseButtonEvent.b` (button),
  `MouseWheelEvent.a` (wheel amount). Test the core headlessly by calling `w.handle(new MouseDownEvent(coord,
  btn))` directly (no `dispatch`, so no `CPUProfile`/graphics needed) and asserting the widget's own
  `mousedown` did/didn't run — a real `Widget` subclass instantiates fine headless (as 2a's LuaWidget tests
  already showed).
- **`:reload` keeps engine widgets ALIVE → hooks MUST be deafened on teardown (2c, P2).** Unlike an addon's
  own `LuaWidget` (destroyed on teardown), an input hook is attached to a **persistent** engine widget
  (MapView/GameUI/root survive a `:reload` — only the Lua layer rebuilds). If teardown didn't call
  `Widget.deafen`, the old `LuaInputHook` would keep firing into the torn-down env. So `teardownHooks` deafens
  + marks each hook dead (the `alive` flag also no-ops a dispatch racing teardown). A hook resolved to a
  specific instance also dies naturally when that widget is destroyed (its listener list goes too) — the
  explicit deafen is for the reload-persists case. Register hooks in **`OnEnterWorld`** (the target must
  exist), which `:reload` re-fires — so they re-install automatically.
- **Generic `listen()` + captured wildcard (2c Java gotcha).** `Widget.listen(Class<E>, EventHandler<? super
  E>)` with an `eventClass()` returning `Class<? extends Widget.Event>` can trip javac's wildcard-capture
  inference. Sidestep it: make the listener `EventHandler<Widget.Event>` (works for any concrete event since
  all extend `Widget.Event`) and widen the class token's **compile-time** type only —
  `(Class<Widget.Event>)(Class<?>)cls` — the runtime `Class` is unchanged, so `Listener.check`'s
  `t.isInstance(ev)` still keys on the real subclass. One `@SuppressWarnings("unchecked")` helper.
- **The MapView `"click"` is sent from the RENDER thread, but under `synchronized(ui)` (2d — the pivotal
  threading fact).** `mousedown` submits an async GPU `Hittest`; when the fragment readback completes,
  `Hittest.ckdone` (on the render/GL thread) takes **`synchronized(ui)`** and calls `hit(...)` →
  `wdgmsg("click", {pc, mc.floor(posres), button, mods [, gob…]})`. So an action hook at `UI.wdgmsg` does **not**
  run on the UI thread for clicks. But `UILoop.Frame.tick` runs input dispatch + the addon tick under the same
  `ui` monitor, and `Frame.display` draws under it too — so **every path that runs Lua holds `synchronized(ui)`**.
  A naive `Thread == uiThread` guard would WRONGLY skip the click hook (render thread ≠ UI thread), breaking the
  DoD. The right guard is **`Thread.holdsLock(ui)`**: run the hook's Lua only when the caller already holds the
  `ui` monitor (true for input dispatch, the tick, AND the hit-test callback), which mutually excludes it from
  tick/draw Lua → no LuaJ race. It only **tests** the lock (acquires nothing), so **no deadlock** is possible;
  a rare off-lock `wdgmsg` is passed through unhooked. Verify the threading by reading `UILoop.Frame.tick`
  (`synchronized(ui)` wrapping `loop.dispatch` + `ui.tick`) and `MapView.Hittest.ckdone` (`synchronized(ui)`
  before `hit`), not by assuming actions are UI-thread.
- **L2 = ONE core edit at `UI.wdgmsg`, split into hook + `rawWdgmsg` (2d, D-011).** `wdgmsg` becomes
  `if(!AddonManager.onWdgmsg(sender,msg,args)) return; rawWdgmsg(sender,msg,args);`, and the original send body
  (widgetid + `rcvr.rcvmsg`) moves verbatim into a new **public `rawWdgmsg`**. `ev:resend()`/`ev:send()` call
  **`rawWdgmsg`, not `wdgmsg`**, so re-issuing an action **bypasses the hook chain** — the spec's re-entrancy
  caveat solved by construction (no guard needed for resend itself; a small `dispatchingAction` boolean still
  guards a hook body that triggers some *other* wdgmsg). Keep the fast path near-zero (`actionHooks.isEmpty()`
  first — `wdgmsg` is hot). `resend`/`send` **imply preventDefault** (they take over the send); doing nothing →
  normal send; `preventDefault` alone → dropped (resend later from a timer = "do X, then move").
- **Marshalling wdgmsg args Java↔Lua (2d): keep the default/resend path LOSSLESS, convert only on read/`send`.**
  `resend()` and the default send reuse the **original `Object[]` verbatim** (no conversion → no loss, even for
  exotic args). Only **`ev.args` (read)** and **`ev:send(t)` (rewrite)** marshal: `Coord`↔`{x,y}` table,
  Integer/Short/Byte→int, Long/Double/Float→double (the usual `>2^53` caveat), String/Boolean direct, and
  **anything else → `LuaValue.userdataOf(obj)`** which `touserdata()` maps back to the **identical** object — so
  a clicked gob's click-data survives untouched. Discriminate on `v.type()` (`TNUMBER`/`TSTRING`/… — a numeric
  `LuaString` has type `TSTRING`, so this beats `isnumber()`); `LuaInteger`→Integer else Double. The full
  `{pc, world, button, mods}` click vector round-trips exactly (headless-verified), so `ev:send(ev.args)` is faithful.
- **The two arg conventions bite `ev:send` too (2d, re-confirms the 2a rule).** `ev:preventDefault()`/`resend()`
  take no real args → a `ZeroArgFunction` (ignores the colon `self`). But **`ev:send(t)`** is a colon-call, so
  `self`=arg1 and the table `t`=arg2 → it MUST be a **`TwoArgFunction`** reading arg2, never a `OneArgFunction`
  (which would read `self`). Headless-testable without a live UI: put a recording `TwoArgFunction` in a table and
  call `t:m(payload)` from Lua, asserting arg1==the table and arg2==payload.
- **Action hooks are owned but have NO widget to deafen (2d, differs from 2c).** An L1 input hook is a
  `Widget.listen` listener (teardown = `deafen`). An L2 action hook lives **only** in `AddonManager`'s per-`msg`
  dispatch map, so `Addon.actionHooks` teardown just marks it dead + removes it from the map (drop the now-empty
  per-`msg` list so the `isEmpty()` fast path stays meaningful). A `:reload` rebuilds the Lua layer but not the
  `UI.wdgmsg` seam (session infra), so unregistering on teardown is what prevents a stale hook firing into a
  torn-down env — same P2 reasoning as deafening input hooks, different mechanism.
- **L3 = the inbound mirror of L2, at `UI.UiMessage.run` — and it needs NO `holdsLock` gate (2e-1).** Server
  updates funnel through `UI.uimsg(id,msg,args)` which only *queues* a `UiMessage`; the message is actually
  APPLIED later in `UiMessage.run()` on a **Loader thread** under `synchronized(ui)` via `dispatch(wdg,
  MessageEvent)`. Hook there (before the dispatch), NOT at `UI.uimsg` (pre-resolve — no widget yet, wrong
  thread). The one edit: `Object[] happly = AddonManager.onMessage(wdg,msg,args); if(happly!=null) dispatch(...,
  new MessageEvent(msg, happly));` — `onMessage` returns the args to apply, a rewritten array, or **null to
  swallow**. Unlike L2 (whose `wdgmsg` senders aren't all UI-locked, hence 2d's `Thread.holdsLock` gate), this
  seam is reached ONLY from `UiMessage.run`, which is **always** under `synchronized(ui)` — the tick/draw
  monitor — so the hook Lua can never race other Lua and no gate is needed. Trade-off: a heavy L3 handler holds
  the `ui` monitor on the Loader thread and **stalls the frame** (spec's "keep it light"); the D-018 layer-1
  instruction watchdog still bounds a single runaway.
- **Gate the 1d post-apply read tap on the message being applied (2e-1).** `UiMessage.run` originally always
  called `AddonManager.onUimsg` (the 1d-1 widget-tree tap) after `dispatch`. Now that an L3 hook can **swallow**
  a message (skip the dispatch), fire the tap only when the message was actually applied (`if(applied)`): a
  swallowed message left the widget unchanged, so re-reading would be pointless (and firing a `*Changed` off an
  unapplied update would be wrong). When no L3 hook is registered, `onMessage` returns the original args →
  `applied` is always true → identical behaviour to before (no regression for vitals/buffs/food/etc.).
- **`MessageEvent`'s ctor interns the name — the spec's L3 intern caveat is handled for free (2e-1).**
  `Widget.MessageEvent(msg,args)` does `this.msg = msg.intern()`, and `Widget.uimsg` compares names with `==`
  on interned strings. So rewriting the message NAME (deferred) would be safe automatically (build a new
  `MessageEvent` with the new name → interned in the ctor). This slice only rewrites ARGS (`ev:rewrite(t)`),
  keeping the name, so the caveat doesn't bite at all. Args rewriting reuses 2d's colon-call `TwoArgFunction`
  (self=arg1, table=arg2) — the same convention `ev:send` needs; a `OneArgFunction` would read `self`.
- **Extract shared marshalling into `LuaMarshal` when a second hook level needs it (2e-1, DRY / D-013).** 2d's
  `toLua`/`toJava`/`argsToLua`/`luaToArgs` lived inside `LuaActionHook`; L3 needs the identical Java↔Lua arg
  conversion. Pull them into a standalone `io.brodgar.addon.LuaMarshal` (static, neutral error messages taking a
  `ctx` label) and have both `LuaActionHook` and `LuaMessageHook` call it — one canonical converter, no drift
  (same reasoning as the shared `LuaGOut` in 2b and `readEquipment` in 1d-4). The refactor is pure extraction
  (behaviour identical — the 2d headless round-trips still pass, now exercising `LuaMarshal` directly).
- **`onMessage` precedence: preventDefault wins over rewrite; last rewrite wins; dead hooks skip (2e-1).** Share
  a `boolean[] prevented` + `Object[][] rewritten` across all hooks matching one message. At the end: if
  `prevented` → return null (swallow); else if `rewritten[0]!=null` → return it; else the originals. So a
  suppress from ANY matching hook beats a rewrite from another (suppression is the stronger, safer intent). A
  `LuaMessageHook.alive==false` (torn-down but racing) is skipped in the loop — the same P2 guard as action/input
  hooks. Copy-on-write per-`msg` list so a hook may `:remove()` itself mid-dispatch.
- **Global hotkeys are a BUILT-IN seam — `UI.keydown`→`GlobKeyEvent`→`Widget.globtype` (2e-2, zero core edit).**
  `UI.keydown` first dispatches a **focused `KeyDownEvent`**; **only if unconsumed** does it fire a `GlobKeyEvent`
  that walks the whole tree calling `globtype` on each widget (this is how the client's own hotkeys work —
  `GameUI`/`MapView`/belt all override `globtype`). Two free wins fall out: (1) a hotkey fires only after an
  **unconsumed** focused KeyDownEvent — a focused text field consumes the keys IT handles, i.e. **all ordinary
  typing** (`ReadLine` inserts any printable char with no Ctrl/Alt → returns true), so a plainly-typed hotkey is
  naturally suppressed while typing; a chord the field *ignores* (Ctrl+H in PC edit-mode) can still fire, exactly
  like the client's own Ctrl-bindings — don't over-claim "never fires while typing". (2) Overriding `globtype`
  on our invisible **`AddonRoot`** (already on `ui.root`) needs **no `haven` edit** — same "reuse the engine's
  dispatch seam" move as 2c's `Widget.listen`. Pattern: `if(AddonManager.onGlobKey(ev)) return true; return
  super.globtype(ev);`.
- **`GlobKeyEvent.propagation` does NOT check `visible()` — the invisible AddonRoot receives it (2e-2).** Unlike
  `FocusedKeyEvent`/`MouseHoverEvent` propagation (which skip invisible children), `GlobKeyEvent.propagation`
  iterates `from.lchild…prev` unconditionally, and `Event.dispatch` doesn't gate on visibility either — so a
  `visible=false` `AddonRoot` still gets `globtype`, just like it still gets ticked (the same invisible-widget
  property the tick pump relies on). No need to make AddonRoot visible.
- **Walk order = precedence: AddonRoot is walked LAST, so client keys win (2e-2).** The `GlobKeyEvent` walk is
  reverse-child-order (`lchild…prev`) and depth-first, returning true on the first consumer. `AddonRoot` is
  attached at session bind (before `GameUI`), so it's an **early** child → visited **after** GameUI/MapView/belt
  and all their subtrees. A client binding on the same key is therefore matched first; an addon hotkey only fires
  for keys the client left unbound → **addon hotkeys are a fallback, never a hijack**. For a reliable demo bind an
  obviously-free key (client defaults are Ctrl+A/B/C/E/G/M/O/T/Z, Alt+A/F/R/S, Ctrl+Tab/Enter, Shift+1..5, arrows,
  V, Tab; the belt eats F1..F12 in FKey mode or 0..9 in NKey mode) — **`Ctrl+H`** is free.
- **`KeyBinding` is a PROCESS-GLOBAL, PERSISTENT registry — key it right, tear down the wrapper only (2e-2).**
  `KeyBinding.get(id, defkey)` caches by `id` in a static map and restores the user's re-map from `Utils.getpref
  ("keybind/"+id, …)`; the `defkey` is used **only on first creation**. So re-fetching the same id across
  reloads/sessions returns the SAME binding with the user's saved key — exactly the persistence we want. Namespace
  addon ids as `addon/<addonId>/<name>` (can't collide with client bindings or across addons). Teardown drops
  only the `LuaKeyBind` (fn wrapper) from the dispatch list; **never remove the `KeyBinding`** (that would forget
  the user's re-map). Consequence: changing `defaultKey` in code does NOT override a key the user already has —
  same as every client binding (document it).
- **`KeyMatch` is the match primitive; parse strings TO it, don't reinvent matching (2e-2).**
  `KeyMatch.forcode(code, mods)` (named/VK keys) / `forchar(chr, mods)` (single chars) build a matcher;
  `km.match(KbdEvent)` does the compare (`mods = UI.modflags(ev)`; modmask defaults to `S|C|M` so **exact**
  modifier match — `"M"` won't fire on `Ctrl+M`). `KeyMatch.nil` (chr=0, code=VK_UNDEFINED) never matches →
  perfect for an **unbound-by-default** binding (`defaultKey=nil`/`"None"`). Parser: split on `+`, last token =
  key (named-key table → `forcode`, else single char → `forchar`), earlier tokens = modifier aliases. `mods` are
  `KeyMatch.S=1/C=2/M=4` and `UI.MOD_*` alias them 1:1, so building an AWT event with `InputEvent.CTRL_DOWN_MASK`
  yields `modflags==C`.
- **Task 2e complete (2e-2): the hook levels + hotkeys are all in.** L1 input (2c), L2 action (2d), L3 message
  (2e-1), and `hafen.key` hotkeys (2e-2). Three of the four reused a **built-in engine seam at zero core edit**
  (`Widget.listen` for L1, `Widget.globtype`/`GlobKeyEvent` for hotkeys) or **one minimal `UI.java` edit** (L2/L3
  at the wdgmsg/uimsg choke points). **L4** (method replacement / hookable subclasses) folds into **Phase 3**
  (widget replacement) — the next task.
- **Surfacing addon keybinds in the client panel = one tiny edit, because the client's `SetButton` already does
  capture + persist (2e-3).** `OptWnd.BindingPanel.addbtn(cont, label, KeyBinding, y)` pairs a label with a
  `SetButton extends KeyMatch.Capture`; the Capture grabs the next keypress and calls `KeyBinding.set(key)` →
  `Utils.setpref("keybind/"+id, …)`, restored next launch by `KeyBinding.get`/`KeyMatch.restore`. So an addon
  binding (whose id is `addon/<id>/<name>`, a real client `KeyBinding` since 2e-2) drops straight into `addbtn`
  with **zero new capture or persistence code** — the whole slice is: a read-only `AddonManager.describeKeyBinds()`
  (group the live `keyBinds` by owner) + a `// addon:` loop in `BindingPanel` after "Voice chat" that emits a
  `Label(addonName)` header + one `addbtn` per binding. **Don't reinvent key capture/persistence — reuse the
  client's binding widget.** The api-reference's "shown in the client keybind panel" was the intended home; the
  panel is a **hardcoded list**, so the only way in is to append rows (it does not auto-enumerate
  `KeyBinding.bindings`).
- **Panel-build-time enumeration is sufficient (2e-3).** `BindingPanel` is rebuilt each time it's opened, and an
  addon binds its hotkeys at load (file body/OnLoad) — before you can open Options — so reading the live
  `keyBinds` list in the ctor always reflects the current, enabled addons. A disabled/unloaded addon has no live
  bind → no section (WoW parity), while its persisted key pref still survives in the `KeyBinding` registry for
  when it reloads. Group by owner with a `LinkedHashMap<Addon, …>` to keep first-registration order; label the
  section with `manifest.name`, the row with the bind's `name`. (No live refresh while the panel is open — a
  hotkey bound via `:reload` with the panel already open needs a reopen; acceptable, matches the rest of it.)
- **The vanilla client has NO keybinding-conflict detection — the same key can bind to many actions (2e-3).**
  `KeyBinding.set` just writes the pref; the `GlobKeyEvent` walk's FIRST matching `globtype` wins and the rest
  are silently shadowed. So a duplicate bind isn't "both fire" — it's "one wins, the others are dead" — but the
  panel happily SHOWS the same key on several rows, which is what the maintainer (rightly) disliked. **Not an
  addon bug** (addon binds are ordinary `KeyBinding`s). **Fix at the source, client-wide:** in `KeyBinding.set`,
  when a REAL key is assigned, loop `bindings` and unbind (`→ KeyMatch.nil`) every OTHER binding that fires on
  the same key (WoW-style exclusivity). The panel's `SetButton.draw` re-reads `cmd.key()` each frame, so a
  stolen row flips to `None` live — no panel change needed. **Gotchas:** (1) clear to `KeyMatch.nil`, NOT
  `set(null)` — `null` means "revert to default" (`key()` returns `defkey`), only `nil` truly unbinds; (2)
  compare against `other.key()` (effective = set-or-default), so assigning a key steals it even from a binding
  that only had it as its DEFAULT; (3) a captured key is code-based (`forevent`→`getExtendedKeyCode`) but the
  client's letter bindings are char-based (`forchar`), so normalise both to a keycode via
  `KeyEvent.getExtendedKeyCodeForChar` before comparing, else "Ctrl+G" as a letter vs a code wouldn't match;
  (4) compare `modmatch` so `J` and `Ctrl+J` stay distinct; (5) guard `key != null && key != KeyMatch.nil` so
  revert/disable don't steal, and `KeyBinding.get` (load) bypasses `set` so there's no load-time cascade.
  Headless-testable with an isolated `-Dhaven.prefspec` node (no UI needed — `KeyBinding.set`/`get`/`key` are
  pure prefs + `KeyMatch`). 9/9.

- **`KeyBinding` keeps two keys, and only one of them is the user's.** `defkey` is the fallback supplied at
  `KeyBinding.get(id, defkey)` (mandatory — a null `defkey` throws NPE, [KeyBinding.java:94](src/haven/KeyBinding.java:94));
  `key` is the user's remap, persisted under `keybind/<id>`. `key()` returns `key != null ? key : defkey`. So an
  addon cannot fake a default by calling `set()` at load: that writes the **user** slot and would clobber a real
  remap on every login. This asymmetry is why addon hotkeys register unbound ([D-047](../decisions/architecture-api.md)).
- **A default key cannot steal, but it can lose silently.** The exclusivity pass lives in `KeyBinding.set`, so
  creating a binding with a default never unbinds anyone. The flip side: if the default collides with a client
  binding, `AddonRoot` is an **early child of `ui.root` and therefore walked LAST** by the `GlobKeyEvent` tree
  walk, so the client's binding matches and consumes first and the addon's handler simply never runs — no error,
  no warning. Between two addons, `HookApi.dispatchKey` iterates in registration order and the first match
  consumes, so the second addon is equally invisible. Prefer unbound + explicit assignment over a silent no-op.
- **`sameKey` compares *effective* keys, defaults included.** `KeyBinding.set`'s unbind pass tests against
  `other.key()`, which falls back to `defkey` — so assigning a key that merely equals another binding's *default*
  still clears that other binding. Any future plan that turns hardcoded keys into `KeyBinding`s (e.g. the belt's
  1–0) must reckon with this against `Fightsess.kb_acts`, whose defaults are 1–5 / Shift+1–5.
- **The keybind panel is a hand-written list, not a registry walk.** `OptWnd.BindingPanel` enumerates
  `KeyBinding` statics line by line ([OptWnd.java:641](src/haven/OptWnd.java:641)); the only dynamic part is the
  addon section fed by `AddonManager.describeKeyBinds`. A binding that exists in the registry but has no
  `addbtn` line is simply invisible — and a key handled by raw `ev.code` in a `globtype` override is not in the
  registry at all, so `KeyBinding.all()` will not see it either.
- **Retiring a namespace is mostly *prose* work (018.2).** Deleting `hafen.key` cost 4 lines of Java (drop the
  table from `HookApi.install`, simplify `newKeyBind` to `(owner, name, fn)` now that no default key exists) and
  ~40 lines of comments, docs, manifest descriptions and example logs across `src`, `docs`, `addons/*` and the
  standing `specs/addons/design/` set. `grep -rn "<namespace>"` over **all four** trees is the real definition of
  done — the compiler catches none of it, and a manifest `description` that still promises `Ctrl+B` is a user-
  visible lie the moment hotkeys become unbound-by-default.
- **A per-object handle is a second canonical way.** `hafen.key.bind` returned `{ :key(), :remove() }`; the
  keybindings handle already answers both by name (`get(name)`, `unregister(name)`), so the port dropped the
  handle rather than re-creating it (D-013). Consumers that logged `handle:key()` now log
  `keys:get(name) or "unassigned, suggested <key>"` — which is also the honest reading once D-047 applies.
- **The L2/L3 hook levels retire onto `Subs` with no global dispatch map (041.2).** `HookApi`'s
  `actionHooks`/`messageHooks` (`Map<String, List<...>>`, one lookup per `wdgmsg`/`uimsg`) are gone; dispatch
  now asks each `Addon`'s own `actionSubs`/`messageSubs` whether it listens (`AddonManager.anyStreamSub`, a
  loop over live addons — a handful of empty-map lookups in the common case, since a session runs few addons).
  D-100 (*the state belongs on the thing*) reads as *no registry to unregister from at teardown* here: the old
  `removeActionHook`/`unregisterActionHook` pair is simply gone, teardown is `Subs.clear()`.
- **A chat `uimsg` carries `nil` as a REAL argument, not as an accident (041.2, found in-game, D-171).** `ChatUI`
  fires `"msg"` as `(from, line)`, and `from == nil` is how the client marks a line as the player's own — not a
  bug, not an edge case, the common case for anyone testing their own addon by typing in chat. Any Java↔Lua
  argument marshalling that later sizes an array from a Lua table (`ev:send`, `ev:rewrite`, a future `ev:emit`)
  must NOT use `#t`: a leading/middle hole makes `#t` read short, silently. Scan for the highest index instead.
  The in-game suite is what found this — the headless pre-check built its argument tables by hand and never
  happened to put a `nil` first, so it stayed green on the very bug the first live login hit.
- **A section's `__index` needs its own `Retired` lookup once a verb, not the whole section, is retired
  (D-170).** Every earlier cut either killed a whole `hafen.<name>` (retired at `hafen`'s own `__index`) or
  moved a pre-039 *dotted* field (retired at the section table's `__index`, which 039 already builds). A colon
  VERB retired off a section that keeps existing — `hafen.hook():action` — had no door until `Section.meta`
  learned to consult `Retired` before falling back to the generic "has no verb" message.
