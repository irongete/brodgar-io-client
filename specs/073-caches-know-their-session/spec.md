# 073 — the caches learn which session they are for

## What and why

`072` gave the engine the vocabulary: `screen()` and `screenView()` for the one drawn screen,
`host()` for the widget tree — the question that will one day take a session — and `w.ui` for the
monitor that guards a widget. What it did **not** change is the state behind those names. The engine
still holds **one session's worth** of everything.

`AddonManager.init(ui)` does not construct: it replaces. It fires `Disable` for every addon, clears
`addons`, empties nine queues and calls `resetSession()` on each subsystem. Measured today, the
static collections that hold session-shaped state:

```
AddonManager 23 · UiApi 8 · VrApi 8 · CharApi 7 · HttpApi 6 · Layout 6
MapApi 5 · BeltHold 4 · LuaWidget 4 · Gesture 3 · StoreApi (per-character scope)
```

What they hold names one login's things. `UiApi.selectorWatches` and `widgetSubsWatching` name
**widgets of a tree**; `CharApi.treeAdapters` is nine readers of **a HUD**; `VrApi`'s indexes name
**gob ids**; `BeltHold` names **slots of a bar**. Today their correctness comes entirely from being
thrown away whole on every switch.

**This feature indexes them by session and stops nothing else.** It is the third of five, and the
work that follows — the engine ceasing to reload on a switch, then `hafen.session()` — cannot begin
while every cache means *the* session.

### Why this one is deliberately unobservable, and why that is right

`072`'s proof was that the build failed without it. **This feature has the opposite shape, and the
spec says so rather than dressing it up**: a stale cache entry — a widget from the session you left,
a gob id that now names a different gob — **compiles perfectly and draws perfectly**. The compiler
does not help here. That is the risk of the whole sequence, concentrated in one feature.

And the payoff is deferred by construction. `Sessions.tickrebind` still tears every addon down on
every anchor change, so at any instant only one session has addons alive. **A cache indexed by
session therefore holds exactly one entry, and behaves exactly as it does today.** The cross-session
assertion — *session A's cache did not reach session B* — is not observable until the engine survives
a switch, which is the next feature's change and not this one's.

That is not a weakness in the decomposition; it is the decomposition. Doing both at once means
turning on a behaviour and repairing eleven subsystems in the same feature, with no green tree in
between. Splitting the other way — switch first, subsystems after — leaves the client actively
wrong, with session A's widget records consulted while B is drawn. **So: eleven inert conversions
here, each proving it changed nothing, and the switch thrown next.**

## Acceptance criteria

1. Every session-shaped cache is reached through a **per-session state object**, keyed by the `UI`
   the session runs in — the object the engine already has in hand at almost every site (`w.ui`,
   `host()`, `screen()`), and the object a relogin replaces, which is exactly when the state must go.
2. A session's state is **released when its `UI` dies**, not when the anchor moves. A relogin or a
   `:session drop` leaves nothing behind: the count of live states equals `live`.
3. **Process-wide state stays process-wide** and is not indexed: the disabled and consented sets,
   the keybind registry, the AddOns panel, the `:lua` console owner, `Prof`. The test applied to each
   collection is written down and the classification is a deliverable, not a judgement call made
   twice.
4. **Nothing an addon can observe changes.** Every reachable behaviour is identical before and after,
   because with one session live a per-session index holds one entry.
5. `AddonManager.init` still tears down and reloads on a switch, and this feature does not touch it.
   The engine's lifecycle is next feature's subject.
6. **`io.brodgar.voice.Voice`'s own ambient view is gone.** It holds a
   `private static volatile MapView view` — the pattern `072` deleted from the addon layer, one
   package over, written by hand from the same seam and able to disagree with the session on screen
   in the same way. It reads through the drawn view instead, on `screenView()`'s own reasoning: a
   copy that can go stale is replaced by an answer that re-checks itself.

## Out of scope

- **The engine surviving a switch.** `Sessions.tickrebind` and `AddonManager.init`'s teardown stay
  exactly as they are. This feature makes that change possible; it does not make it.
- **`StoreApi`'s per-character scope.** "Saved per character" only loses its single referent once the
  engine outlives a switch, so the question belongs to the feature that causes it. `StoreApi` is
  indexed here like the rest; what its scope *means* is settled next.
- **`host()` growing a session argument.** It goes on answering `Sessions.anchor()`. Giving it a
  parameter is an API change, and this feature adds none.
- **Re-homing addon windows**, the addon lifecycle events, `hafen.session()`, the API cut, `Fonts`.

## Docs impact

**Written**: nothing. No page under `docs/addons/**` states anything this feature makes false —
criterion 4 is that no reachable behaviour changes, and the impact set is how that is checked rather
than assumed. No `docs/client/` page maps `io.brodgar`, by `DOCUMENTATION.md` §12.3. The one map toll it
does owe is `4`'s: routing a marker notify to a session rests on where a `MapFile` instance comes from and
how many there are, which is `GameUI`'s doing and which `client/mapfile.md` did not say.

**Derived set:**

```
grep -rniE "per session|per-session|session ends|reloaded|torn down" docs/
```

**28 hits across 22 files.** The ones that describe the engine's session-scoped behaviour, and why
each survives unrevised:

| Page | What it says | Verdict |
|---|---|---|
| `addons/runtime.md` | what a `:reload` keeps and drops; the load order | unchanged — the lifecycle is untouched here |
| `addons/api/event.md` | `Disable` fires "when the addon is disabled or reloaded, or the session ends" | unchanged — still true; the sentence changes in the **next** feature |
| `addons/api/store.md`, `guides/saved-data.md` | per-character and per-account scope, flushed at `Disable` | unchanged — the scope question is deferred with its reason above |
| `addons/api/ui/{native,replace,custom}.md` | hidden widgets handed back, records dropped with the session | unchanged — same behaviour, indexed |
| `addons/api/vr/{README,ghosts}.md` | entities dropped on reload | unchanged |
| `addons/api/{actionbar,sound,font,world,conventions}.md`, `client/keybindings.md`, `style/chat.md` | "reloaded"/"per session" about their own subjects | discharged, no overlap |
| `client/{boot-and-loop,multi-session,mapfile,network,world-3d,README}.md` | upstream `haven` and the session layer | unchanged |

## Context files

- `specs/073-caches-know-their-session/census.md` — 2, 3, 4, 5 (written by 1)
- `src/io/brodgar/addon/AddonManager.java` — 1, 2, 3, 4, 5, 6
- `src/io/brodgar/addon/AddonRoot.java` — 1
- `src/io/brodgar/addon/LuaOverlay.java` — 1, 4 (`gob:overlay()`, the only reader of the anchored index)
- `src/io/brodgar/addon/ProfHandle.java` — 1
- `src/io/brodgar/addon/UiApi.java` — 2
- `src/io/brodgar/addon/LuaWidget.java` — 2
- `src/io/brodgar/addon/Layout.java` — 2
- `src/io/brodgar/addon/Gesture.java` — 2
- `src/io/brodgar/addon/Sheet.java` — 2 (its own caption queue, beside `UiApi`'s)
- `src/io/brodgar/addon/CDropdown.java` — 2 (the popup re-raise)
- `src/io/brodgar/addon/WidgetSurface.java` — 2 (the standing panels of one world)
- `src/io/brodgar/addon/SurfaceInput.java` — 2 (the pointer walk over that list)
- `src/io/brodgar/addon/LuaSelectorWatch.java` — 2 (the tree a subscription records)
- `src/io/brodgar/addon/WidgetSubs.java` — 2 (the tree a widget record is about)
- `src/io/brodgar/addon/AddonPagina.java` — 3 (an entry added or removed takes and gives back its slots)
- `src/io/brodgar/addon/CharApi.java` — 3
- `src/io/brodgar/addon/BeltHold.java` — 3
- `src/io/brodgar/addon/LuaSlot.java` — 3 (`slot:pagina()`, the Lua caller of every `BeltHold` hold verb)
- `src/io/brodgar/addon/VrApi.java` — 2 (it builds a `WidgetSurface`), 4
- `src/io/brodgar/addon/LuaWorldEntity.java` — 4 (an entity records whose world it stands in)
- `src/io/brodgar/addon/MapApi.java` — 4
- `src/io/brodgar/addon/LuaMarker.java` — 4 (every Lua caller of the marker refs)
- `src/io/brodgar/addon/HttpApi.java` — 5
- `src/io/brodgar/addon/StoreApi.java` — 5
- `src/io/brodgar/addon/AddonRegistry.java` — 1, 3 (an addon's holds are given back at its teardown), 6
- `src/io/brodgar/addon/Addon.java` — 1, 2 (the `:lua` console's own widget records), 6
- `src/io/brodgar/session/Sessions.java` — 1, 6
- `src/io/brodgar/prof/Prof.java` — 3
- `src/haven/UI.java` — 1, 6
- `src/haven/GameUI.java` — 1 (the belt seam), 3 (`Belt.mousedown`/`dropthing` and both `setbelt` arms hand
  `BeltHold` the bar they are about), 4 (`addchild("mapview")` builds the `MapFile` and hands the one
  instance to both readers)
- `src/haven/MapView.java` — 1 (the enter-world seam)
- `src/haven/MapFile.java` — 4 (the marker-change seam, and what it is handed)
- `src/io/brodgar/voice/Voice.java` — 6
- `DOCUMENTATION.md` — 6
