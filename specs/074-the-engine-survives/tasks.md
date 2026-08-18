# 074 — tasks

Four tasks, and the order is forced. `074.1` builds the layer; only then can `074.2` stop the engine
reloading, because flipping the reload off while addon windows still live in a session's tree orphans
them on the first tab.

Read `spec.md` and `plan.md` first. Every file a task may open is listed in `spec.md` under **Context
files**, tagged with the task that needs it.

**This is the first feature of the sequence a player can see.** `071`, `072` and `073` were inert by
construction; here the behaviour changes, and each suite proves a *difference* rather than a sameness.

---

- [x] **074.1 — The addon layer is a layer, not a tenant.**
      `UILoop` gains a second `UI` — the **addon layer** — built the way `bgui` builds one (no replace,
      no destroy, no `uilock`), created once in the constructor beside `Sessions.init`, with a **null
      `sess`**: nothing in it ticks a `Glob` and no server widget can be handed to it. The frame then
      attends two trees where it attended one. The eight sites are `dispatch(ui)`,
      `ui.sess.glob.ctick()`/`gtick(out)`, `ui.tick()`, `ui.gtick(out)`, `ui.mousehover(ui.mc)`,
      `ui.root.resize(sz)` in `Frame.tick`, and `ui.draw(g)`, `drawtooltip`, `drawcursor` in
      `display` — the `ctick`/`gtick` pair runs for the session only, because the layer has no `Glob`.
      **Input precedence is the layer first**: `dispatch` offers the event to the layer's root and
      passes it to the drawn session only if the layer did not consume it. The layer draws **above the
      session, and above the login screen when there is none** — "above everything" admits no
      exception. `hafen.ui():window()` parents into the layer's root instead of `host().root`;
      `host()` itself is untouched and still means the tree an addon *searches*. Writes the two-tree
      frame into `docs/client/boot-and-loop.md` and `docs/client/multi-session.md`, and "your windows
      live in the layer" into `docs/addons/api/ui/custom.md`.
      *Its suite* builds a window with `hafen.ui():window()` and asserts it is placed and visible;
      round-trips `:title`, `:position`, `:size` and `:visible`; adds a child and counts
      `:children()`. It asserts `hafen.ui():find()` still locates one of the **client's** own windows
      by selector — the layer must not have swallowed the search — and that `widget:parent()` from
      that client widget does **not** reach the addon window, which is the two-tree claim stated as an
      assertion. It `pcall`s `:position(nil, 10)` and asserts the refusal names the missing argument.
      `[manual]`: with the suite's window on screen, drag it by its caption and resize it by its
      corner. Expect: both follow the mouse exactly as any client window does.
      `[manual]`: log out to the login screen. Expect: the suite's window is **still there**, drawn
      over the login screen. That is the layer; a window that vanishes was living in a session.
      `[manual]`: click a spot where the addon window overlaps the game world. Expect: the window
      takes the click and the character does **not** walk there.

- [x] **074.2 — The engine stops reloading, and an addon outlives a switch.**
      Deletes `Sessions.tickrebind` and its `rebind` flag. `AddonManager.init` splits along the line
      `073` already drew: the **per-session** half — attach the tick widget, register the `OCache`
      callback, prime the adapters — is driven by that session's own arrival, and the **per-client**
      half — load the addons, fire `Load` — runs at boot and on `:reload` and nowhere else.
      `attach(MapView)`'s `enterWorldPending` already lives in `SessionState`, so the per-session half
      needs no new state. Settles the four rows `073`'s census deferred to this feature —
      `AddonManager.addons`, `autoDisabledWarn`, `clock`, `resolveQueue` — as **process-wide**, and
      updates `census.md` in place with the reason: an `Addon` stopped belonging to a login. Adds
      `engineReloads` and `addonsLive` to `hafen.client():profiling():session()`. Writes the new
      lifecycle into `docs/addons/runtime.md`, **including the contract change in those words**: the
      reload was hiding mistakes, an addon that cached a widget handle from one session could not fail
      before, and now it can — *your state survives a character switch, and keeping it valid is
      therefore yours.*
      *Its suite* is the one that proves the whole sequence was worth doing. It keeps a **counter in a
      Lua upvalue**, incremented on every `Update`, and writes its own load count into
      `hafen.store()`. Run once, it reports both. It asserts `engineReloads` is `0` and `addonsLive`
      does not move. It `pcall`s `hafen.client():profiling():session(1)` and asserts the refusal names
      the group as read-only.
      `[manual]`: `:session add <a second account>`, let it reach the world, **tab to it and back**,
      then re-run the suite. Expect: the Lua counter kept climbing across the tab and the stored load
      count is **still 1** — the addon was never reloaded. Before this task it would read 3.

- [x] **074.3 — Sessions come, are picked, and go, and the addon hears all three.**
      Adds four keys to the closed bus catalogue — `SessionAdded`, `SessionEnteredWorld`,
      `SessionSelected`, `SessionDestroyed` — each carrying the account **name**, which after `071` is
      what every session has and what `:session list` prints. The seams exist: `Sessions.add` and the
      bootstrap handoff; `attach(MapView)` plus the HUD wait; `Sessions.anchor(Member)`;
      `Member.run`'s `finally`. **Tabbing to a session already in the world fires `SessionSelected`
      and nothing else** — tabbing is not entering. `EnterWorld` is **retired** into `Retired.NAMES`,
      so it throws at the line that wrote it naming `SessionEnteredWorld`. Splits
      `docs/addons/api/event.md`, which is 321 lines against a 300 ceiling and which this task adds
      four rows to: the bus catalogue apart from the two message streams, as the page's own subject
      line describes, with every inbound link and anchor re-pointed in this task.
      *Its suite* subscribes to all four keys and reports which fired, scoring on a bounded timer over
      the gestures the maintainer performs below. It asserts a subscription to `EnterWorld` **throws**
      and that the error names `SessionEnteredWorld` — the retirement is a check, not a note. It
      `pcall`s `hafen.event():on("SessionSelected ", fn)` with the trailing space and asserts the
      refusal points at the catalogue.
      `[manual]`: `:session add <a second account>`. Expect: `SessionAdded` fires with that account,
      then `SessionEnteredWorld` for it once its HUD is up.
      `[manual]`: tab to it and back. Expect: `SessionSelected` fires **twice** and
      `SessionEnteredWorld` fires **not at all**.
      `[manual]`: `:session drop` it. Expect: `SessionDestroyed` fires naming it.

- [ ] **074.4 — Saved variables say which character they are for.**
      `hafen.store()`'s per-character scope resolves against the **session on screen** at the moment of
      the call, which is the only referent it can have before `hafen.session()` exists — `075` gives it
      an address. What must change regardless: the **flush**. Today `StoreApi.flush(a)` runs at
      `Disable` with whatever `charScope` was last set, and `Disable` now fires once in the client's
      life; per-character variables must instead be written **when the session that owned them ends**,
      hung on the same `UI` death `073` hangs its state release on. Account-scope variables are
      unaffected and go on flushing with the addon. Writes both facts into `docs/addons/api/store.md`
      and `docs/addons/guides/saved-data.md`, which today say the flush happens at `Disable`.
      *Its suite* writes a per-character table and an account-scope table, reads both back in the same
      run and asserts equality; asserts a key never written reads `nil`; and asserts the two scopes do
      not see each other's keys, which is what having two scopes means. It `pcall`s a store write with
      a value the serialiser cannot carry and asserts the refusal names what a saved variable may hold.
      `[manual]`: with two sessions up, write a per-character value on one, tab to the other, and
      re-run. Expect: the second character reads `nil` for that key, not the first character's value.
      `[manual]`: `:session drop` the first, then re-add it and re-run. Expect: its value is back —
      the flush happened when that session ended, not when the client did.
