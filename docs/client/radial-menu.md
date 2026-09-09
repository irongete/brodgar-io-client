# The radial menu: FlowerMenu

> The ring of petals a right-click puts up: what the server sends, what the ring grabs, the three ways one
> ends, and the fork seams in it. It is a widget in one session's tree like any other, so a ring left open
> on a character the player tabs away from is still open.

## Where it lives

| What | Where |
|---|---|
| The widget | `FlowerMenu`, `@RName("sm")`. Its factory takes the options as **strings and nothing else** — the message carries captions, no ids, no resources |
| The petals | `FlowerMenu.opts`, a public `Petal[]`. A `Petal` is a `Widget` with `name` (the caption), `num` (its 0-based place, which is what the choice sends), `ta`/`tr` (its target angle and radius) and a private rendered `text` |
| Where the ring is placed | `added()` takes `parent.ui.lcc` — the **last click coord** — when the widget arrives at `(-1, -1)`, which is how the ring lands where the press was |
| The layout | `FlowerMenu.organize` walks the petals out in rings of `ppl` (8) at radius `75 + 50 * (ring - 1)` design pixels, and shifts them inwards while the parent's area does not contain a petal's box. `ph` is the petal height |
| What it holds | `added()` takes `ui.grabmouse(this)` **and** `ui.grabkeys(this)` into `mg`/`kg`, so an open ring has the whole pointer and keyboard; nothing else in the tree sees either until it ends |
| Choosing | `FlowerMenu.choose(Petal)` — `wdgmsg("cl", num, ui.modflags())` for a petal, `wdgmsg("cl", -1)` for none. Esc and a click outside both reach it with `null` |
| The keyboard | `keydown` maps `'1'`..`'9'` and `'0'` to petals `0`..`9` and eats every digit, whether or not one is there; `key_esc` chooses nothing |
| The three animations | `Opening` (0.25 s), `Chosen` (0.75 s) and `Cancel` (0.25 s), all `NormAnim`s. `Chosen` and `Cancel` call `ui.destroy(FlowerMenu.this)` at `s == 1.0`, which is the only thing that ends the widget |
| The server's answers | `uimsg "cancel"` starts `Cancel` and drops both grabs; `uimsg "act"` starts `Chosen` on `opts[num]` and drops both grabs |

## How a ring ends, and what fires where

There are three ends and they are not symmetrical. The two `uimsg` arms are the **commit points**: the
server has decided, and the client plays the matching animation out before destroying the widget. The
third is a plain death — a relog, a server destroy — where nothing was committed and no `uimsg` arrives,
and `destroy()` is the only thing that runs.

That asymmetry is why the fork's own notice is fired from **all three**, one-shot per open, with the
fallback in `destroy()`: whichever gets there first is the one that reports, so an open is always
followed by exactly one close.

`choose()` is not an end. It sends, and the ring stays up over the round trip until the server answers
with `"act"` or `"cancel"` — which is a whole animation's worth of time in which the ring is still
readable and still holds the input.

## The fork seams

| Seam | What it is for |
|---|---|
| `added()`, at the **end** | the open notice. It is last on purpose: a client-side petal appended above it replaces `opts` wholesale, so this is the only point at which the petal set is complete |
| `uimsg "cancel"` and `uimsg "act"` | the two commit points, carrying `null` and `opts[num].name` |
| `destroy()` | the fallback close, so a menu that merely died is reported too. `BuddyWnd`'s own subclass overrides `destroy()` and calls `super`, so a client-side ring is covered as well |
| `choose(Petal)` | records the petal before it is sent, so a client-side petal that cancels the server's menu closes carrying its own label instead of reading as nothing chosen |
| `mousedown` and `keydown` | a ring that is not `visible()` spends no click and picks nothing on a digit, but still eats both — see the gotcha below |
| The `"menu"` font scope | every caption is rendered through it rather than through the stock `ptf`, and `Petal.textgen` re-renders when the scope moves; the chrome is `Fonts.box("panel", …)` over the stock `pbox` |

## Gotchas

- **A hidden ring still holds the mouse and the keyboard.** A grab is checked *before* the tree and
  reaches its owner whatever that widget's `visible` flag says — only `PointerEvent.propagation` tests
  one, and it tests the **child** it steps into, so the petals, which are themselves still visible, would
  answer a press on an unpainted ring. The fork's two guards in `mousedown` and `keydown` are what stop
  an invisible ring from spending a click on Chop; both still eat the input, because the grab is real.
- **A click inside the first quarter second does nothing at all.** `mousedown` returns early while
  `anims` is non-empty, so the opening animation swallows the press.
- **`opts` is not final.** The fork appends a client-side petal in `added()` by replacing the array, so
  anything that captured `opts` before that point is holding the shorter one.
- **A client-side petal never reaches the server.** It sends `wdgmsg("cl", -1)` — cancelling the server's
  menu — and handles itself, so the close arrives through the `"cancel"` arm carrying a label the server
  never named.
- **`BuddyWnd` drives `uimsg` by hand** for its own right-click menu, so the `"act"` arm is also the seam
  a purely client-side menu takes.

## What is not mapped

The petal artwork and its colours, the `NormAnim` machinery itself (that is
[widgets.md](widgets.md)), and which content resources put which captions on a ring.

## See also

- [widget input](widget-input.md) — the grab that is checked before the tree, and the propagation walks
- [the pointer and the ground](map-click.md) — `ui.lcc`, the press the ring is placed at
- [the Kin window](kin-window.md) — the client-side menu that drives `uimsg` by hand
- [text and fonts](text-and-fonts.md) — the font scopes the captions and the chrome are drawn through
