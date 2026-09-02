# 125 — plan

## Approach

Four of the six chaining refusals have the same shape today: `resolve(v)` narrows the argument to a
`LuaWidget` handle, `live(h)` narrows it to a Widget still in a tree, and **one** `throw` covers both
failures. The whole feature is that split. `widget:draggable(h)` and `widget:resizable(h)` are
already two throws with two messages, so at those the stale one is simply **replaced** by the stop;
and `VirtualApi.standable` carries the same conflation at the one site that keeps raising, where the
split ends in two messages rather than in a stop.

- `h == null` — not a Widget at all. **Raises**, and keeps the half of today's text that helps: what
  a Widget is, and where to get the one the caller probably meant.
- `live(h) == null` — a Widget that has left the tree. **Stops the write and chains**, the 029.2
  answer the receiver has had since it existed.

`LuaWidget.live` is the one liveness test and every site uses it (`Layout` does not — see gotchas).
It nulls `LuaWidget.wdg` on the first stale access, so the second call on a handle is cheap and still
null, and no site needs to cache the result.

**Stopping the write is different at each receiver, and that is the whole design.**

- `widget:parent(w)`, the **surface** direction (`LuaWidget.parent`). The receiver is the addon's own
  surface, still pending, so there is nothing to leave half-built: the surface is **abandoned**. That
  is `:destroy()`'s body over the `Owned c` the verb already holds — `UiApi.homeInside(w)`, then
  `c.kill()` under `synchronized(monitor(w))`, then `UiApi.dropPending(c)` and
  `owner.widgets.remove(c)` — and `return self`. `:exists()` is `false` from there on, and every
  chained write behind it takes the stale-receiver no-op it already has.
- `widget:parent(p)`, the **native** direction (`LuaWidget.rehomeNative`). The receiver is one of the
  client's widgets and stays exactly where it is; the destination is what died. Nothing moves,
  `return self`.
- `widget:draggable(h)` / `widget:resizable(h)` (`LuaWidget`, the two `Gesture.arm` sites). Nothing is
  armed, `return self`, and `w:draggable()` answers `nil` — which is the read that was always the way
  to ask.
- `rule:anchor{ to = w }` (`Layout.parseAnchor`). Delete the throw and let `tgt` stay null: the
  `Anchor` resolves to nothing, which is what an anchor whose target closes later already does.
- `widget:replace(view)` (`UiApi.replaceWith`). The receiver is one of the client's windows and stays
  exactly as the user has it: nothing is hidden, nothing is bound, `w:replacement()` goes on reading
  `nil`, and `widget:replace` hands `self` back whatever this call did. The one ordering point is that
  the liveness check moves **ahead** of `ownedContent`, whose refusals are about *whose* the view is
  — a fact a handle that has left the tree can no longer answer.
- `hafen.virtual():widget():add(w, anchor)` (`VirtualApi.standable`). The one that goes on **raising**,
  its single throw split into two messages: a non-Widget keeps today's text and its `Got <type>`, and
  a Widget that has left the tree names the tree. Nothing else there moves.

The order of the existing checks does not move. In particular `c.pending()` — the refusal naming
`:position(x, y)` for a surface already on screen — stays **ahead** of the argument check, so a
surface that is already drawing gets that refusal whatever the argument is (criterion 4).

The docs then state the rule once, in `widget.md`'s staleness paragraph: reads answer `nil`, writes
are chaining no-ops, a Widget **argument** that has left the tree stops the write, and the list of
what raises on a stale **receiver** is written as a list rather than a count — which is also how the
missing fourth member, `widget:overlay():add(key)`, stops being missing.

## Files to create/modify

| File | What |
|---|---|
| `src/io/brodgar/addon/LuaWidget.java` | the split at four sites: `parent` (surface), `rehomeNative`, `draggable`, `resizable` |
| `src/io/brodgar/addon/Layout.java` | `parseAnchor`'s `to` — drop the throw, keep a null target |
| `src/io/brodgar/addon/UiApi.java` | `replaceWith`: the split, with the stop ahead of the ownership refusals |
| `src/io/brodgar/addon/VirtualApi.java` | `standable`: one throw becomes two messages, both raising |
| `docs/addons/api/ui/custom.md` | `:parent(w)` as a building verb: what a dead argument does |
| `docs/addons/api/ui/native.md` | the two gesture sections, and *Taking one into a surface of your own* |
| `docs/addons/api/ui/widget.md` | the staleness paragraph: the argument rule, and the receiver list |
| `docs/addons/api/ui/style/geometry.md` | `anchor.to` at build time, beside the weak hold it already states |
| `docs/addons/api/ui/replace.md` | the refusal list: three refused outright, the dead view stopped |
| `docs/addons/api/virtual/widgets.md` | `:add`'s refusals, and why this site raises where the rest stop |
| `addons/125-a-dead-handle-is-not-a-mistake.1/` `.2/` `.3/` | the three suites |

## Risks and gotchas

- **`Layout.parseAnchor` does not use `LuaWidget.live`** — it reads `h.wdg` directly and throws on
  null. That is a weaker test: a widget that left the tree without anything having touched its handle
  since still has a non-null `wdg` and passes. Deleting the throw is enough — `Layout.resolve` treats
  an unresolvable target as inert already — but the plan is not to "fix" it into `live()`: the field
  read is what keeps `parseAnchor` free of the tree monitor, and a target that dies a moment later
  takes the same inert path anyway.
- **`Owned.kill()` is bridge-only and removes the root directly.** It does not go through
  `Widget.reqdestroy`, so the abandoned surface takes no part in `Window.reqdestroy`'s hide animation
  (the `docs/client/widgets.md` gotcha) — which is right: it never painted.
- **The monitor is the receiver's own**, exactly as in `:destroy()`. The abandoned surface is in the
  addon layer, so this takes the layer's monitor and no second one — no new lock direction, and
  nothing here crosses the boundary `docs/client/multi-session.md` names.
- **The chain behind the abandonment must be inert, and is.** `:name(s)` and `:stock(t)` both answer
  029.2 **before** validating their own argument (`LuaWidget`, the `w == null` return in each), so an
  addon that names and stocks a plate in one statement gets silence rather than a second refusal.
- **`rehomeNative`'s current message reports `v.typename()`** — `"userdata"` for a dead surface of
  your own, which reads as if the caller passed something wrong. After the split that branch is only
  reached by a genuine non-Widget, where the type name is the useful half.
- **A suite makes its own dead widget**: `hafen.ui():widget()` then `:destroy()`. Destroying a
  pending surface is the supported case `UiApi.dropPending` exists for, and `:exists()` is `false`
  immediately, on the same step — no timer needed for the fixture.
- **`pending` clears on the tick after the statement**, so criterion 4 needs a second step:
  `hafen.timer():after(0, fn)` is the door, and a suite may use it as long as nothing starts it.
- **`replaceWith` resolves and checks liveness in one expression** — `ownedContent(owner,
  live(resolve(viewv)))`, whose single `view == null` throw answers for three different faults. The
  split unpacks it into three steps in that order: a non-Widget raises, a dead one stops, and only
  then is the ownership question asked.
- **`standable`'s refusal leaves nothing behind**, unlike `:parent(w)`'s: the `WidgetSurface` and the
  entity are built after it returns, so the orphan half of the defect does not exist at that site —
  which is the second reason the raise can stay there.

## Discarded alternatives

- **Leave the raise; tell addons to `pcall` the build** — rejected: the guard the page already names
  (`:exists()`) cannot be made sufficient, because nothing holds a tree monitor across the two
  statements. The advice would be "wrap every build in a protected call", which is a language feature
  standing in for a rule the API is supposed to have.
- **Let `:parent(dead)` leave the surface in the layer and just return** — rejected: `:exists()` would
  be `true` on an unnamed, unsized surface at the client's default place, so the caller's own guard
  passes and it builds a label into nothing. The orphan is the defect; the refusal was only its
  messenger.
- **Change `:parent(w)` alone** — rejected: one rule with four exceptions is five rules. The
  argument is the same fact at every site, and `:parent`'s two directions would disagree inside one
  verb.
- **A predicate or a second spelling (`:parentIfLive(w)`, `hafen.ui():alive(w)`)** — rejected: arity
  is the verb, one canonical way per operation, and either would still be check-then-use.
- **Distinguish "never in the tree" from "left it" and raise on the first** — rejected: `live()`
  cannot tell them apart once `LuaWidget.wdg` is nulled, and the caller can act on neither.
- **Hold the icon out of its destroy until every handler has seen it, as `GobAdded` does** — rejected:
  that promise is bought once, at the render tree, for one event; item icons are destroyed by the
  server's own message drain, which no handler queue can hold open.
- **Let `hafen.virtual():widget():add(w, anchor)` answer `nil` for a dead widget** — rejected: the
  verb mints what it hands back and has never had a `nil` to hand, since `born()` raises even for
  "there is no map view yet". A quiet stop would put one where every caller writes `panel:` on the
  next line, and the failure would land a line further from the fault, inside their own code. The
  rule is for writes that chain, which hand the receiver back; where there is no receiver the honest
  answer is the refusal, saying which of the two happened.
