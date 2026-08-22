# 092 — The address reaches the end: plan

## Approach

Ten rows, shipped as one unit with one suite. Eight needed code; the other two were recounted against the
source and found already discharged, which is the method 091 established and which this feature needed
twice.

**The engine already had every seam this feature needs.** Nothing here registers a callback, starts a
sweep or adds a tick. `GobIntent` is consulted by the gob drain that already runs; the adapters are handed
the `SessionState` that already held them; the sweep walks the state map that already exists; the placement
scope is read off `w.ui`, which every write already had in hand. That is why an engine feature came out
smaller than 091 did.

**Four of the ten cannot be observed with one login**, and that is a property of the rows rather than of the
suite: what a program can read back with one character is that the drawn session agrees with itself, which
is exactly what was never broken. Those are the four `[manual]` lines.

## Gotchas found while doing it

**`w:position(x, y)` on an addon's OWN window creates no level.** The write forks on
`ownedContent(owner, w)`: a borrowed widget gets a `Moved` record, your own widget gets a bare `w.move()`.
`rememberCapture` records the halves this addon holds a level on, so a programmatic move on your own window
is never captured — only a `Gesture` drag is. The suite's first draft asserted the round trip on an owned
window and read the stock `100,100` back. The scored check now uses a widget of the session's own tree,
which is the scope the severe row is about, and the account scope is the `[manual]`, armed with
`:draggable` because a gesture is the only path that fills it. **Pre-existing, and reported at the close.**

**The audit's evidence for A-086 predates the fix.** It was filed at 076 and 079.3/080.1 answered it. The
only reliable method is reading the path: `grep -n 'drawn(\|screen()' LuaOverlay.java LuaGobOverlay.java`
answers nothing, and that is the whole proof.

**`SessionState.treeAdapters` could not stay a field initializer.** It has to be built with `this` after
`ui` is assigned, so it moved into the constructor body.

**`Layout.markCaptionChanged()` took no widget, and the seam that calls it always had one.** That was the
whole of the per-session fix: `AddonManager.onCaptionChanged(w)` already carried the widget to two of its
three consumers.

**`Retired` needed a hand-written row for `worldToScreen`.** `sectionObj("player", …)` says "it is now
`s:player():<verb>`", which is false for a verb that changed **section**. Three spellings had to name the
whole new call instead.

## Discarded alternatives

- **Keying the overlay registry on `(session, gob)`**, which is A-086's literal text. 080.1 keyed it on the
  **object** instead, which is stronger — one object drawn the same in every window — and the row's actual
  defect ("refuses for every session but the drawn one") is gone either way. Re-splitting it per session
  would have undone a shipped feature to satisfy a stale sentence.
- **Moving the vr surface into the addon layer** (A-094's first branch). Real work across `WidgetSurface`,
  `SurfaceInput` and `VrApi`, and the row offers the doc line as its own alternative — which A-037 already
  shipped, in the present tense. The engine half stays on the ROADMAP, where it was filed at 075.
- **Giving a Position a session**, so `p:x()` could answer for the character that produced it. Rejected in
  `specs/076`'s own plan — a place is answerable in whichever session you ask, and a session on it makes two
  Positions for one patch of ground — and that reason still holds. The twins go on the section instead.
- **A teardown-only re-apply for A-087**, which `specs/080`'s plan already rejected. The registry is what
  makes the write reach a session that arrives later, and a teardown hook cannot.
- **Re-applying the visual intent from `GobAdded`**, which `GobScale`'s javadoc suggests to addons. It fires
  once for the client, into the first session to see the object — so the very session that needs the
  re-apply is the one it never fires for.
- **Keeping `rescope()`'s swap** for the placements. With every set filed under its own tree there is
  nothing to swap; what is left is that a tab is a good moment to write, which it keeps.
- **Making both `flush()` verbs write every scope.** Each verb names one file, which is the rule the pair
  already followed for the variables; splitting the placements the same way is one rule rather than two.
- **A per-session `gprefs` read to match the per-tree write.** `hafen.client()` carries no address, so
  there would be nothing to hand it; after a write the trees agree, and before one they were loaded from
  the same store.
