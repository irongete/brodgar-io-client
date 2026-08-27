# 116 — a hidden ring: plan

## Approach

**The paint costs no core edit.** `Widget.visible` is the client's own flag and the parent's draw walk
already honours it — `Widget.draw(GOut, boolean)` steps `child`/`next` and skips a child that is not
visible — so `Widget.hide()`/`show()` on the `FlowerMenu`, under that tree's monitor, is the whole of
"not painted".

**The input rule is what costs them**, because `Event.dispatch(Widget)` never tests `visible`: it is
`w.handle(ev)` and then `propagate(w)`, and only `PointerEvent.propagation` tests visibility — on the
*child* it is about to step into, never on an ancestor. A grabbed press therefore reaches
`FlowerMenu.mousedown` whatever the flag says, and its `ev.propagate(this)` walks into `Petal`s that are
themselves still visible. A ring hidden by the flag alone is still pickable, blind.

Two guards in `FlowerMenu`, tagged `// addon:`, are the rule: `mousedown` while `!visible` goes straight
to `choose(null)` instead of propagating, and the `'0'..'9'` branch of `keydown` does nothing while
`!visible`, its `key_esc` branch untouched. `choose(null)` is the door Esc and a click away already
share, so a hidden ring ends exactly as an unwanted one does.

**The Lua half** is one entry in `FlowerMenuApi.flowermenu()`'s section table, beside `gob`, `select` and
`cancel`, shaped on `LuaGob`'s own `visible`: `Args.written(a, 2, …)` splits the read from the write,
`!bv.isboolean()` refuses the coercion `0` would otherwise walk through, and the write resolves its menu
with `required(user, verb)` — the helper `select` and `cancel` already raise through, which is what makes
*no menu open* one message on this section rather than two rules on one page.

**The widget door** refuses in `LuaWidget`'s `visible` entry, in both directions, before the
borrowed/owner branch: a `FlowerMenu` receiver names `s:flowermenu():visible(b)`. That is a refusal
written inside the verb, not a `Refusal.MOVED` row — the name did not move, one receiver gained a rule,
and nothing sweeps that.

## Files to create and modify

| File | What |
|---|---|
| `src/haven/FlowerMenu.java` | the two `// addon:` guards in `mousedown` and `keydown` |
| `src/io/brodgar/addon/FlowerMenuApi.java` | `visible` on the section table; `Widget.hide`/`show` under the monitor |
| `src/io/brodgar/addon/LuaWidget.java` | the `FlowerMenu` refusal in the `visible` write, both directions |
| `docs/addons/api/flowermenu.md` | `## Drawn or not (unprotected)` — the pair, the input rule, the example |
| `docs/addons/api/README.md`, `docs/addons/api/session.md` | the section's one-line description |
| `docs/addons/api/ui/native.md`, `docs/addons/api/ui/widget.md` | the ring's exception, **in place** |
| `docs/client/widget-input.md` | the dispatch half of the visibility rule, **in place** |
| `addons/116-a-hidden-ring.1/`, `.2/`, `.3/` | the three suites |

## Risks and gotchas

- **`FlowerMenu.mousedown` returns `true` while `anims` is non-empty** — the 0.25 s `Opening` — so for a
  quarter second after a ring opens a click does nothing at all, hidden or not. The guard sits behind
  that one, and the page says so rather than promising an immediate dismissal.
- **`FlowerMenu.choose` does not close the ring.** It sends `wdgmsg("cl", num)` and returns; the ring
  lives until `uimsg("act")` starts the 0.75 s `Chosen`. So `:visible(true)` after a `:select()`
  repaints the whole round trip, which is the one way to write this verb and get nothing from it.
- **`Widget.hide()` also calls `parent.delfocusable(this)`.** `FlowerMenu` is not `canfocus`, so nothing
  moves, and `show()` puts it back through `newfocusable`.
- **`Petal` overrides no cursor and no tooltip**, so a hidden ring answers the ordinary pointer with no
  further guard. `grabmouse` filters `CursorQuery` in but nothing under the ring claims it.
- **Three pages are at or over their ceiling**: `ui/native.md` 417, `ui/widget.md` 314,
  `client/widget-input.md` 147 of 150. Every revision there is a clause inside an existing row or
  sentence, with no net growth. `ui/widget.md` is over with nothing on the roadmap — a finding for the
  close, not a task here.
- **`client/widget-input.md` states the propagation skip**, and for `GlobKeyEvent` that an invisible
  widget still eats its key. The dispatch half — a grab reaches a hidden widget, and a hidden
  container's visible children are still walked — is the fact this whole feature turns on and is on no
  page.
- **Nothing delivers a click or a key from Lua**, so a hidden ring's two input rules are caused by hand
  and observed by the suite.

## Discarded alternatives

- **Hiding through `widget:visible(false)` on a matched `@FlowerMenu`** — the generic write records a
  restore entry holding the widget, and those are pruned only when the whole tree dies, so a ring hidden
  per right-click piles up dead records nothing can ever give back; it also carries a one-owner rule
  built for windows an addon keeps, over something that lives a second.
- **Dropping the mouse and key grabs when hiding** — it takes away the two gestures that end a ring, so
  one an addon hides and does not decide has no way out for the player at all.
- **Leaving the ring live and merely unpainted** — a grabbed press reaches a hidden container's
  still-visible children, so a click on the ring's own footprint fires whatever petal was under it: an
  invisible ring spending a click on Chop.
- **A no-op write when no menu is open, as `gob:visible(b)` does** — a gob handle goes stale in your
  hand and nobody holds a menu; the address here is a live section, so *nothing to hide* is the same
  race `select` and `cancel` are raised on.
- **A per-addon owner record, so a second addon cannot show what a first hid** — the ring outlives no
  decision and dies in about a second, so the record would name a widget already gone every time it was
  read.
- **Auto-cancelling a ring left hidden and undecided** — cancelling is a message to the server behind
  `flowermenu.cancel`, and an unprotected visual write may not send one.
- **`:hide()` and `:show()`** — the value belongs in the argument, never in the verb's name.
