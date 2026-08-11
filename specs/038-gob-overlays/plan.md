# 038-gob-overlays — Plan

## Approach

**This is a re-fronting, not a rewrite.** Both mechanisms already exist and each one's javadoc calls
itself the other's analog: [`LuaGobOverlay`](src/io/brodgar/addon/LuaGobOverlay.java) (a
`GAttrib implements RenderTree.Node, PView.Render2D`, the `SpeakerIcon` pattern) paints in screen
space, and [`FollowMoving`](src/io/brodgar/addon/FollowMoving.java) (a `Moving` whose `getc()` is
`target.getc() + off`) anchors in the world. What is deleted is what sits *in front* of them.

**1. The registry stops being a filter and becomes state ON THE GOB.** `Addon.gobOverlays` — today a
list of (filter, draw) evaluated against every gob by a throttled 5 Hz sweep — is **deleted**, and the
per-key map moves *inside* the `LuaGobOverlay` attrib, partitioned per addon (one shared attrib per gob
still serves every addon: `getattr` keys by the exact class, so a per-addon subclass cannot be
attached). The addon names the gob, so nothing is searched: `gob:overlay(key, spec)` attaches the
attrib to *that* gob at once, and `paintGobOverlays` reads the attrib it is already standing in
instead of re-checking every addon's filter each frame. `UiApi.sweepGobOverlays` and
`AddonManager.gobFilter`'s overlay use go with it. This is the ROADMAP's "per-gob match cache", paid
for by deleting the thing it would have cached.

**2. Death with the gob is therefore FREE — for half of it.** Because the state lives on the gob, the
engine ends it: `Gob.dispose()` ([:561](src/haven/Gob.java:561)) disposes every `GAttrib`, and a gob
dropped from `OCache.objs` ([:95](src/haven/OCache.java:95)) takes its attribs with it either way.
**No `GobRemoved` handler, no pruning, nothing to leak** — an addon-side map keyed by gob id would
have had to be swept for exactly the reason the maintainer gave (a felled tree never returns), and
this has no such map to sweep. Teardown (`:reload` / disable) is the one caller that must still find
an addon's overlays across gobs, and that is a single sweep of `oc` at a rare moment — which is what
`detachGobOverlays` already does today, kept.

**2b. The world-space half is NOT free, and it is a latent bug `follow=` has today.** A
`{image}`/`{model}`/`{ghost}` overlay is **not** an attrib on the target — it is its own client-only
gob carrying a `FollowMoving` that re-resolves the target's id each frame, and when the target is gone
[`getc()` holds at the entity's last position](src/io/brodgar/addon/FollowMoving.java:46). So a sprite
following a felled tree currently floats there forever with no owner. Under this feature it must
**die with its target**, which is the one piece of code the whole "dies with the gob" rule costs:
`FollowMoving` reports the loss, and the overlay is destroyed and fires `GobOverlayRemoved`.

**3. One Overlay object, composed at creation.** Interned per (addon, gob id, key) — D-094, the intern
key follows what the engine does to the thing. Base methods on every overlay: `:key/:gob/:native/
:res/:exists/:info`. A world-space one additionally carries the verbs its entity already has
(`:tint`, `:rotate`, `:scale`, `:pos`), delegated to the existing sprite/ghost handle, so absorbing
`follow=` takes nothing away. **No `:remove()`** — removal is `gob:overlay(key, nil)`, the third arity
(maintainer's call; `w:replace(nil)`'s shape, and the alternative is two doors to one operation).

**4. The native side is a read over `Gob.ols`.** `gob:overlays()` (a list of resource names) is
subsumed: the same information arrives as Overlay objects with `native = true`, and both an attach
onto a native key and `gob:overlay(nativeKey, nil)` **raise** naming the key. **Open point 038.1 must
measure, not assume** (032.1's precedent): the native key. A `Gob.Overlay` carries an `id` only when
the server gave it one, and two overlays can share a resource — so 038.1 counts, on a live client,
how often a gob carries two overlays of one resource before choosing between the res name and the
`findol` id. Whichever wins, the docs state it.

**5. The events are the only core edits.** Two `// addon:` one-liners: [`Gob.addol(ol, async)`](src/haven/Gob.java:525)
(the one-arg overload delegates to it — hook the two-arg body only, or every add fires twice) and the
removal in [`Gob.Overlay`](src/haven/Gob.java:118) (`gob.ols.remove(this)`). Both fire through the
`hasSub` gate `fireGob`/`fireKin` already use, and both **queue onto the tick**: `addol` runs from
`Gob.ctick` and from server messages on loader threads, and nothing may call into Lua from there.
An addon's own attach/remove fires the same pair with `native = false`, from the UI thread already.

## Files to create / modify

- `src/io/brodgar/addon/LuaGob.java` — the `overlay` verb (4 arities), the resolver; **delete** `overlays()` (:265)
- `src/io/brodgar/addon/LuaGobOverlay.java` — kept, and it **becomes the store**: the per-addon key map lives here
- `src/io/brodgar/addon/LuaOverlay.java` — **new**: the Overlay object (base reads + composed entity verbs, interned per addon)
- `src/io/brodgar/addon/UiApi.java` — **delete** `newGobOverlay`, `sweepGobOverlays`, `anyGobOverlays`; `paintGobOverlays` re-pointed; `detachGobOverlays` kept for teardown
- `src/io/brodgar/addon/RenderApi.java` — **delete** `follow=`, `:follow`, `:offset` from the public surface; `followTargetId`/`applyEntityFollow`/`setEntityFollow` stay, reachable only through `gob:overlay`
- `src/io/brodgar/addon/GhostGob.java` / the ghost builder — same cut for `hafen.ghost{follow=}`
- `src/io/brodgar/addon/FollowMoving.java` — report a lost target instead of holding position (plan §2b)
- `src/io/brodgar/addon/Addon.java` — **delete** `gobOverlays` (:51); the state is on the gob now
- `src/io/brodgar/addon/AddonManager.java` — **delete** the sweep call (:419) and `GobOverlay` (:1889); add `fireGobOverlay` and the tick queue for the native events
- `src/io/brodgar/addon/AddonRegistry.java` — teardown as the one sweep of `oc` (:151–155)
- `src/haven/Gob.java` — the two `// addon:` seams (:525 body, :118)
- `docs/addons/api/gob.md` · `ui/custom.md` · `render/sprites.md` · `render/models.md` · `ghost.md` · `events.md` · `conventions.md` · `api/README.md`
- `addons/hello/main.lua` — edited (frozen, but genuinely broken: `:2883`, `:2027`)
- `addons/038-gob-overlays.{1..4}/` — the suites; one example addon for the close
- `specs/codebase/state.md` — **coverage paid**: `Gob.ols`/`Overlay`/`addol`/`findol` and `ctick`'s virtual self-removal test are not covered by any subsystem file today (28/40 lines, room to extend)
- `specs/design/24-gob-overlays.md` — the standing design, written at the close

## Risks & gotchas

- **`Gob.ctick` self-removes a virtual gob when `ols.isEmpty()` and there is no `Drawable`**
  ([:463](src/haven/Gob.java:463), `learnings/rendering.md` R2b) — the same `ols` this feature reads. A
  world overlay on a **client-only** gob must keep its billboard as that gob's `Drawable`, as R2b said.
- **Draw callbacks escape the soft CPU budget** (`learnings/ui-widgets.md`, D-018 layer 2): they run in
  `ui.draw`, after the tick's budget window. Deleting the sweep removes the *filter* cost that was
  budgeted; the `{draw=fn}` callback stays instruction-capped only. Say so in the docs.
- **`detachGobOverlays` mutates render slots under `synchronized(ui)`** because teardown can run off
  the UI thread — keep that, it is why `:reload` is clean today.
- **`ant hafen-client` is incremental and false-greens a moved symbol** — `rm -rf build/classes` for a
  true check, since this feature moves several.
- **A removed `hafen.*` symbol must be asserted `== nil`, not described** (`TESTING.md`): four cuts here.

## Discarded alternatives

- `gob:overlay():add/remove(...)` — a two-level accessor-then-mutate chain, with no precedent in the API; arity is the verb.
- Keeping the filter form as a fifth arity — two canonical ways, and it keeps the sweep this deletes.
- `:remove()` on the Overlay object — a second door to `gob:overlay(key, nil)` (maintainer's call).
- `hafen.world.overlay(filter, fn)` — plural namespace, still a sweep, still not on the thing.
- A native overlay **write** (`addol` from Lua) — it needs a `Sprite.Mill`; the addon's own sprites already do this better.
- Storing the overlay on the Gob object — a Gob is a re-resolving view; the registration belongs to the addon, keyed by gob id.
