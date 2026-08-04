# Gob overlays (everything attached to a game object, through one verb)

> **Status:** 🟢 Design closed — shipped as [038-gob-overlays](../038-gob-overlays/spec.md) · **Spec:** AddOns
> **Series:** the gob half of the area · **Surface:** `gob:overlay([key[, spec]])` and the `Overlay` object
> **Decisions:** [D-100](../decisions/architecture-api.md) (the state belongs on the THING, and a sweep is the
> shape of having put it elsewhere), [D-101](../decisions/architecture-api.md) (where a name cannot separate
> two engine objects, publish the COLLAPSE rather than reaching for the id),
> [D-102](../decisions/architecture-api.md) (the END of a derived thing rides the event its SOURCE already
> raises), [D-103](../decisions/architecture-api.md) (an ABSORBED mechanism keeps ONE door),
> [D-104](../decisions/architecture-api.md) (an event's SCOPE follows its key's scope),
> [D-105](../decisions/architecture-api.md) (where the read COLLAPSES, the event follows the KEY),
> [D-106](../decisions/architecture-api.md) (a queue whose handlers write back into it is drained ONE FRAME'S
> WORTH), [D-092](../decisions/process.md) (a system's boundary is a decision on the page); and the standing
> ones this feature only applied — [D-044](../decisions/architecture-api.md) (a verb belongs on the thing),
> [D-056](../decisions/architecture-api.md) (arity is the verb),
> [D-061](../decisions/architecture-api.md) (the vocabulary comes from the engine),
> [D-093](../decisions/architecture-api.md) (an entity's identity is the key a NAME can address),
> [D-094](../decisions/architecture-api.md) (an intern key follows the engine's own stability)
> **Supersedes:** [07-ui-and-drawing.md](07-ui-and-drawing.md) §"World-space overlays over gobs"
> (`hafen.ui.gobOverlay`, the filter form) and the `follow =` anchoring of
> [17-custom-rendering.md](17-custom-rendering.md) / [16-virtual-entities.md](16-virtual-entities.md)
> **Related:** [09-events-catalog.md](09-events-catalog.md) (the two new events),
> [../../codebase/state.md](../../codebase/state.md) (`Gob`, `GAttrib`, `Gob.ols` and the three removal paths),
> [the API reference](../../../docs/addons/api/gob.md#overlays)

## The problem this solves

**`overlay` is the engine's own word for a thing attached to a gob** — `Gob.ols`, `Gob.Overlay`,
`addol`/`findol` — and the API had spent that word on `hafen.ui.gobOverlay`, a *screen-space painter* that
was not one, while the real per-gob attachment hid behind `follow = gob` in two other namespaces.

Three mechanisms, one concept, and none of them on the gob:

| Before | What it actually was | Where it lived |
|---|---|---|
| `hafen.ui.gobOverlay(filter, draw)` | a filter list, swept at 5 Hz against every gob, re-matched per frame at the draw | `hafen.ui` |
| `hafen.render.sprite{follow = gob}` / `object{follow = gob}` | a client-only world entity re-resolving the target's id each frame | `hafen.render` |
| `hafen.ghost.new{follow = gob}` | the same, for a game `.res` model | `hafen.ghost` |
| `gob:overlays()` | a list of resource names — a *read* of the game's own, unrelated to any of the above | `hafen.gob` |

After the feature there are exactly **three places a drawn thing can live**: in the world at a fixed place
(`hafen.render` / `hafen.ghost`), on a gob (`gob:overlay`), or on the HUD (`hafen.ui.overlay`). `follow`
stops existing, because *on a gob* already means *following*.

## The shape

**Arity is the verb, and the key is per addon** (D-056):

```lua
gob:overlay()              -- every overlay on the gob: yours, then the GAME's own
gob:overlay(key)           -- that one, or nil
gob:overlay(key, spec)     -- attach, or REPLACE what that key already named
gob:overlay(key, nil)      -- remove
```

**One spec table, two spaces.** `{draw = fn}` and `{text = …}` paint in screen space at the gob's projected
point; `{image =}`, `{model =}` and `{ghost =}` stand in the 3D world with `offset = {x=,y=,z=}` in world
units. A spec naming none of the five is an error naming all five; one naming two is an error naming both,
because picking a winner by table order is how one of them silently stops meaning anything.

There is **no `:remove()`** on the Overlay object and **no `:move()`**: removal is the third arity, and an
overlay's position *is* its gob's — the only thing you set is the offset, by re-attaching.

## The rule the whole feature is built around

**The state belongs on the THING, and a sweep is the shape of having put it elsewhere** (D-100).

`LuaGobOverlay` stopped being a stateless painter and *became the store*: the per-addon key map lives on the
gob's own `GAttrib`. The addon names the gob, so nothing is ever searched — and four separate pieces of code
were deleted by the same move:

| Deleted | Why it existed |
|---|---|
| the throttled 5 Hz world sweep | to find the gobs a filter matched |
| the per-frame re-match inside the draw | because the sweep's answer was not stored anywhere |
| `Addon.gobOverlays` (the filter list) | to hold what the sweep evaluated |
| the ROADMAP's "per-gob match cache" | polish on a search that should not exist |

And it buys a rule that costs no code: `Gob.dispose()` disposes every `GAttrib`, so **an overlay dies with
its gob** with no `GobRemoved` handler and no prune. Teardown (`:reload`/disable) is the one caller left that
must find an addon's records across gobs, and it runs at a rare moment rather than five times a second.

**The half that is not free is the world-space one** (D-102). Its visual is *its own client gob in the
scene*, and `OCache.remove` does not call `Gob.dispose()` — it sets a flag and lets GC take the attribs — so
nothing ends the entity just because the target left the cache. That is exactly `follow=`'s latent bug: a
sprite anchored to a felled tree floated there forever with no owner. The plan wanted `FollowMoving` to
*report* the loss, but `getc()` runs on the render and loader threads with nobody to report to, and giving it
one means a per-frame list of anchored entities — the deleted sweep, back at 60 Hz under a new name. The end
of a derived thing rides the event its source already raises: `LuaGobOverlay.gobGone(gob)` runs from the
tick's `GobRemoved` drain, before the event reaches Lua, and because the store is on the gob it arrives
holding the exact records, in O(1).

## The native half: read-only, and a union

The game's own overlays come back from the same read with `native = true`, keyed by their **resource name** —
the only part of a `Gob.Overlay` a name can address (D-093). 038.1 *measured* rather than assumed: on a live
world **13 of 33** gobs carrying overlays had two of one resource, worst by four. So the collapse is ordinary,
and the answer is not to reach for `findol`'s id — that id is `-1` whenever the server gave none (the same
collisions, now unnameable), it is a number where every other key here is a name, and it does not survive a
re-add. What settles it is that a native overlay is **read-only**: a union loses nothing an addon could act on
except the multiplicity, so the multiplicity is simply said — `ov:count()`, and 1 for ours (D-101).

Both an attach onto a native key and `gob:overlay(nativeKey, nil)` **raise, naming the key** — never a silent
no-op on something the addon does not own.

## The events, and the three seams

`GobOverlayAdded` / `GobOverlayRemoved`, payload `{ gob, key, native }`. These are the feature's **only** core
edits — three `// addon:` lines in `Gob.java`, and the plan predicted two:

| Seam | What ends there |
|---|---|
| `addol(ol, async)`'s body | every add, however it arrived (the one-arg overload delegates here) |
| `Gob.Overlay.remove`'s `gob.ols.remove(this)` | an overlay something removed by hand |
| **`Gob.ctick`'s expiry of a finished sprite** | *most* of the game's overlays — they are transient sprites nobody removes |

Hooking only the plan's two would have left the majority of native removals silent. The coverage line that
made it findable: *removal has three paths and only two of them are `Overlay.remove`.*

Three rules govern the delivery:

- **An event's SCOPE follows its key's scope** (D-104). An overlay key is per addon, so a `native = false`
  event is delivered **only to its owner** — a bystander would get a key it cannot read. A native one, keyed
  by resource name, **broadcasts**. Corollaries: a gob's death fires *synchronously* from the `GobRemoved`
  drain, so an overlay is never reported dying after the thing it was on; and **teardown fires nothing**,
  since the only addon that could hear it is the one going away.
- **Where the read COLLAPSES, the event follows the KEY** (D-105). The count is taken *after* the engine's own
  mutation and fires only on 1 (first) or 0 (last): the second `foo` arriving is not an add, one of two `foo`s
  leaving is not a removal, and `ov:count()` moves without an event because the key did not change. Read from
  the other side, the same rule makes a **replace fire the removal AND the add**, so a handler keeping its own
  set never drifts.
- **A queue whose handlers write back into it is drained ONE FRAME'S WORTH, never to empty** (D-106). This is
  the first event here whose handler can trivially cause the same event (re-attach under the same key → a
  removal and an add), and `while(poll() != null)` then never terminates — *not* a hang the addon watchdog
  catches, since each `callLua` is short and returns; it is the engine's own loop that never ends. Snapshotting
  the queue length turns the pathological addon into a slow loop the profiler prices and `:reload` interrupts.

Both halves are **queued onto the tick**, behind a `hasSub` gate plus a volatile "does anybody subscribe" flag:
`addol` runs on the loader threads for every decoration the server sends, and the measured traffic is
**119–559 native events per session**, which is the number that justifies the gate.

## Boundaries written down rather than left as gaps

- **No overlay on a ghost** (D-092). A ghost is a virtual gob with id `-1` that never enters `OCache`, so it
  is not addressable as a Gob. Nothing is lost — the deleted sweep and `FollowMoving` resolved through
  `OCache` too — but it is a decision on the page, not a hole in the code.
- **No filter form.** "Every player gets a label" is a `GobAdded` handler plus a loop over
  `hafen.world.gobs()`, and that trade is the point: the declarative sweep is what was deleted. The bundled
  **`tagger`** example is what the trade looks like in practice.
- **No native writes**, and no richer native read than key + `native` + `:res()` + `:count()`: an `addol` from
  Lua needs a `Sprite.Mill`, and the addon's own sprites already do that better.
- **An overlay is not a free entity** (D-103). Its world entity is registered like any other, so
  `hafen.ghost.list()` would have handed out a raw handle carrying `:destroy()` and `:move()` — a second door
  onto one thing, and worse than a duplicate, since `:destroy()` would kill the visual behind a record that
  still reads as attached. One `asOverlay` flag closes it. An absorbed mechanism keeps one door: the old
  option is **refused** *and* the old listing stops handing it back.
- **`clickable`/`onClick` are not overlay properties.** The thing under an overlay is the gob, and clicking a
  gob is `hafen.act.clickGob`.
