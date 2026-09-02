# 125 — tasks

- [x] **125.1 — `widget:parent` stops on a dead argument instead of raising.** Splits the one refusal
      into two at both ends of the verb — `LuaWidget.parent`'s surface direction and
      `rehomeNative`'s native one. A value that is not a Widget goes on raising, keeping the half of
      each message that says what a Widget is; a Widget that has left the tree stops the write. On
      the surface direction that means **abandoning** the pending surface down `:destroy()`'s own
      path (`UiApi.homeInside`, `Owned.kill` under the receiver's monitor, `UiApi.dropPending`,
      `owner.widgets.remove`) and chaining; on the native direction nothing moves and it chains. The
      `c.pending()` refusal stays ahead of the argument check. Writes `custom.md`'s building-verb
      section and `native.md`'s *Taking one into a surface of your own*.
      *Its suite* builds its own corpse — `hafen.ui():widget()` then `:destroy()`, asserting
      `:exists()` is false, so the fixture is proven before anything rests on it. Then the reported
      crash, whole: `hafen.ui():widget():parent(dead):name("plate"):stock({}):position(4, 4)` raises
      nothing, hands a Widget back, and that Widget's `:exists()` is false with its reads answering
      `nil` — which is the assertion, not the absence of an error. A non-Widget argument still raises
      and the message names Widget, while the receiver it was called on survives it (`:exists()`
      true, then destroyed). For the native direction, the corner minimap takes a dead destination:
      no raise, and `:parent()` reads back the parent it already had — nothing moved. A second step
      through `hafen.timer():after(0, fn)` arms a surface parented to the HUD and asserts
      `:parent(hud)` then raises the refusal naming `:position(x, y)`, which the argument rule must
      not have swallowed. The boundary holds in the same run: `dead:on("Removed", fn)` still raises,
      naming the tree.
      `[manual]`: none — every claim here is one the API reads back.
      <!-- extra context: src/io/brodgar/addon/AddonWidget.java -->

- [x] **125.2 — the gestures and the anchor take the same rule, and the page states it once.**
      `widget:draggable(h)`, `widget:resizable(h)` (`LuaWidget`, the two `Gesture.arm` sites) and
      `rule:anchor{ to = w }` (`Layout.parseAnchor`) each stop raising on a dead Widget argument:
      the two gestures arm nothing and chain, the anchor keeps a null target and resolves to
      nothing. Not-a-Widget goes on raising at all three. Then `widget.md`'s staleness paragraph
      states the rule for the whole surface — reads `nil`, writes chaining no-ops, a dead Widget
      argument stops the write — and rewrites the receiver raises as a list rather than a count of
      three, which is where the missing `widget:overlay():add(key)` joins them. `native.md`'s two
      gesture sentences and `geometry.md`'s `anchor.to` follow.
      *Its suite* makes its own corpse as 125.1 does and proves the same fixture again, assuming no
      other suite is ever run. `win:draggable(dead)` raises nothing and hands `win` back, and
      `win:draggable()` reads `nil` — armed nothing, said so. The same pair for `:resizable`.
      `:draggable(42)` still raises, naming a Widget. A sheet rule carrying `anchor{ to = dead }`
      installs without raising and the window it names does not move, while `anchor{ to = 42 }`
      raises naming `"screen"` or a widget. The boundary is asserted, not assumed: `dead:on(...)`,
      `dead:match("*")` and `dead:overlay():add("k")` each still raise naming the missing tree.
      `:send`'s own stale refusal sits behind a permission key, so it is verified by reading the
      site rather than by a check the suite cannot honestly make.
      `[manual]`: none.

- [x] **125.3 — the two sites the rule reaches last: a view, and one standing in the world.**
      `widget:replace(view)` (`UiApi.replaceWith`) takes the same rule as every other chaining write:
      its one `view == null` throw becomes three steps in that order — a value that is not a Widget
      raises, one that has left the tree **stops the substitution and chains**, and only then is the
      ownership question asked, so a view your addon did not build goes on raising exactly as it did.
      `hafen.virtual():widget():add(w, anchor)` (`VirtualApi.standable`) is the one site that goes on
      **raising** — it mints the entity it hands back and has no `nil` to answer with — but its one
      throw becomes two, so a dead widget names the tree instead of reporting `"userdata"`. Writes
      `replace.md`'s refusal list (three refused outright, the dead view stopped) and
      `virtual/widgets.md`'s, which states why this one raises where the rest stop.
      *Its suite* makes its own corpse as the other two do and proves the fixture again, assuming no
      other suite is ever run. It installs a LIVE view on one of the client's windows first and reads
      `w:replacement()` back as that very view, then `w:replace(nil)` gives the window back — which
      is what makes the negative mean something: `w:replace(dead)` then raises nothing, hands `w`
      back, and `w:replacement()` reads `nil`. `w:replace(42)` still raises, naming a widget your own
      addon built. Then `hafen.virtual():widget():add(dead, ...)` raises and its message names the
      tree rather than a type name, while `add(42)` raises as it always did. The boundary is asserted
      here too: `dead:on("Removed", fn)` still raises naming the missing tree.
      `[manual]`: none — the client's own window the view stands in for is found by selector, and a
      run that cannot find one scores that check as a fail, not as a question for the maintainer.
      <!-- extra context: src/io/brodgar/addon/AddonWidget.java -->
