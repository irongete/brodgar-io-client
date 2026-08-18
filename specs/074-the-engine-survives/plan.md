# 074 — plan

## Approach

Four tasks, and the order is forced: **the layer must exist before the engine stops reloading.** Flip
the reload off while addon windows still live in a session's tree and they are orphaned on the first
tab; build the layer first and the reload becomes a thing that merely no longer happens.

### The layer, and what the frame loop has to grow

`UILoop.Frame.tick` does everything on one `UI` — the drawn one — inside `synchronized(ui)`:
`dispatch(ui)`, `ui.sess.glob.ctick()`/`gtick(out)`, `ui.tick()`, `ui.gtick(out)`,
`ui.mousehover(ui.mc)`, `ui.root.resize(sz)`. Then `display(UI, Render)` follows with `ui.draw(g)`,
`drawtooltip(ui, g)` and `drawcursor(ui, g)`. **Eight sites, and every one takes "the UI" in the
singular.** The layer is a second tree the frame attends after the session's, drawn on top.

The layer is a `UI` of its own, built the way `UILoop.bgui` builds one — no replace, no destroy, no
`uilock` — with a **null `sess`**, which is what makes it not a session: nothing in it ticks a `Glob`
and nothing in it can be handed a server widget. It is created once, in the `UILoop` constructor
beside `Sessions.init`, and it outlives every session.

**Input precedence: the layer first, the session second.** `dispatch` offers the event to the layer's
root and passes it to the drawn session only if the layer did not consume it — which is what a widget
tree already does between overlapping children, one level up. `mousehover` and the cursor follow the
same order, and a tooltip belongs to whichever tree answered the hover. Resize goes to both.

**`hafen.ui():window()` parents into the layer's root** rather than `host().root`. That one line is
what stops an addon's window from living in a session, and it is why focus and grabs survive a switch:
nothing moves, so there is nothing to repair.

**`host()` stays as it is**, answering `Sessions.anchor()`. It means *the tree an addon searches* —
the client's own windows, which do belong to a session — and `075` is where it takes an argument. What
changes here is only where an addon's **own** widgets are parented.

### The reload stops

`Sessions.tickrebind` and its `rebind` flag are deleted. `AddonManager.init` splits along the line
`073` already drew: what it did **per session** — attach the tick widget, register the `OCache`
callback, prime the adapters — is driven by that session's own arrival, and what it did **per client**
— load the addons, fire `Load` — happens at boot and on `:reload`, and nowhere else.
`AddonManager.attach(MapView)`'s `enterWorldPending` flag already lives in `SessionState`, which is
what makes the per-session half possible without inventing anything.

The four rows `073`'s census deferred here — `addons`, `autoDisabledWarn`, `clock`, `resolveQueue` —
become **process-wide**, and `census.md` is updated in place with the reason: an `Addon` stopped
belonging to a login the moment it outlived a switch.

### The vocabulary

Four keys join the closed catalogue: `SessionAdded`, `SessionEnteredWorld`, `SessionSelected`,
`SessionDestroyed`, each carrying the account **name** — which after `071` is what every session has
and what `:session list` prints. `075` replaces it with the Session object.

`EnterWorld` is **retired**, and by this API's own rule a retired spelling throws at the line that
wrote it naming its replacement, through `Retired.NAMES` — which the docs sweep derives from rather
than being told.

The seams already exist and none needs inventing: `Sessions.add` and the bootstrap handoff for
`SessionAdded`; `AddonManager.attach(MapView)` plus the HUD wait for `SessionEnteredWorld`;
`Sessions.anchor(Member)` for `SessionSelected`; `Member.run`'s `finally` for `SessionDestroyed`.

## Files to create and modify

| File | Task | What |
|---|---|---|
| `src/haven/UILoop.java` | 1, 2 | the layer `UI`, the eight frame sites, dispatch precedence |
| `src/io/brodgar/addon/UiApi.java` | 1 | own windows parent into the layer |
| `src/io/brodgar/addon/AddonManager.java` | 1, 2, 3, 4 | the `init` split, the counters, the session events |
| `src/io/brodgar/session/Sessions.java` | 2, 3 | `tickrebind` deleted; the four event seams |
| `src/io/brodgar/addon/AddonRegistry.java`, `Addon.java` | 2, 3 | load once, fire once |
| `src/io/brodgar/addon/LuaEvent.java`, `Retired.java` | 3 | the four keys; `EnterWorld` retired |
| `src/io/brodgar/addon/StoreApi.java` | 4 | the referent, and the flush per session |
| `specs/073-caches-know-their-session/census.md` | 2 | the four deferred rows settled |
| `docs/addons/api/event.md` | 3 | the family, and the split |
| `docs/addons/runtime.md` | 2, 4 | the lifecycle, and the contract change |
| `docs/addons/api/store.md`, `guides/saved-data.md` | 4 | the per-character referent |
| `docs/addons/api/ui/custom.md` | 1 | your windows live in the layer |
| `docs/addons/api/client/profiling/counters.md` | 2 | `engineReloads`, `addonsLive` |
| `docs/client/boot-and-loop.md`, `multi-session.md` | 1 | the frame draws two trees |

## Risks and gotchas

- **`dispatch` is where a bug is loudest and cheapest to find.** A click reaching the wrong tree shows
  in seconds. Get the fall-through right before anything else: the layer sees the event, and only an
  unconsumed one reaches the session.
- **The layer's `UI` has a null `sess`.** Anything in `UILoop` reaching `ui.sess.glob` must run for a
  session's `UI` and never the layer's — `Frame.tick`'s `ctick`/`gtick` pair above all.
- **`ui.mc` is the pointer and there is one of it.** Both trees need it; it is `screen()`'s subject and
  must not turn into two answers that can disagree.
- **`UI.destroy` on the layer would end the addon system.** Nothing in the runner chain may reach it:
  `newui` destroys `this.ui`, the login slot, and the layer is not that.
- **`GameUI.onscreen` is `ui == Sessions.anchor()`** and gates three window-geometry writes. The layer
  is never the anchor, so it must not fall into that test as a false negative that matters.
- **The `[manual]` that tabs mid-drag is the grab's real proof.** With the layer, a drag begun on an
  addon window survives a switch — the thing re-homing could not have delivered.
- **`ant hafen-client` is incremental** and hides a symbol that moved; `rm -rf build/classes` first.

## Discarded alternatives

- **Re-homing addon windows into the drawn session's tree on every switch** — `Widget.attach(UI)`
  already walks a subtree, so it is the cheap answer, and it buys a permanent obligation: repair focus
  and release grabs on every tab, forever, to imitate a layer that could simply exist. Its failures are
  quiet and recur; the layer's are loud and happen once, in dispatch, where a click visibly lands in
  the wrong place.
- **One addon instance per session** — five characters would mean five Lua environments, five copies of
  every window with one of them drawn, and five times the per-frame budget. It also answers the wrong
  question: an addon showing all five bags at once needs one instance that can see five sessions, not
  five that each see one.
- **Keeping `EnterWorld` beside the session events** — two names for one moment, and the older one
  carries the assumption this feature deletes: that the addon is the thing entering the world. A
  retired spelling that throws is this API's own answer to that.
- **A payload of the Session object now** — `hafen.session()` does not exist until `075`, and inventing
  a provisional object to replace two features later is two hard cuts where one will do.
- **Deferring the `event.md` split** — this feature adds four keys to a page already 21 lines over its
  ceiling, whose own filed subject line says the bus catalogue and the two message streams are two
  subjects. A feature that makes a stated problem worse and leaves it stated has decided nothing.
- **Letting the layer draw only while a session is drawn** — a window that vanishes at a logout was
  living in a session after all, and "above everything" with an exception is the re-homing model
  wearing a different name.
