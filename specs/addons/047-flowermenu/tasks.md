# 047-flowermenu — Tasks

> One task = one session. Each ships its own suite (`specs/addons/TESTING.md`) and is verified alone.
> A suite never declares `permissions`, and it cannot be typed while a menu is open (the menu grabs
> mouse and keyboard): arm the handlers, print the `[manual]` step, let the handler assert.

## 047.1 — the section's reads, and the two events ✅ 26/26 pass, 0 fail

> **Closed on the maintainer's call with two gaps in the in-game run**: the Kin-window `[manual]` round was
> not performed and `:t047-1 done` (the cross-round invariants + `[summary]`) was not run. What the log does
> prove: 11 no-menu assertions, then 5 server menus, each Opened carrying its captions and agreeing with
> `:list()`/`:count()` read inside the handler, each paired to exactly one Closed — one carrying `"Chop"`,
> four carrying nothing. The **client-side (`BuddyWnd`) path is proven only headlessly**, by the probe that
> drove the three seams against real `FlowerMenu` objects (3 opened / 3 closed, `Pick branch` · nil ·
> `Mute voice`). 047.3 right-clicks a kin row for its own `:gob()` check and will exercise it in-game.
>
> The refusal on `:list("x")` names `hafen.act():flower(label)`, not `:select` as this file asked: that verb
> does not exist until 047.2 and the page cannot document one that is not there. **047.2 switched it.**

- `FlowerMenuApi` mounted as `hafen.flowermenu()` with `:list()` and `:count()`; the open-menu finder
  moves here out of `ActApi.openFlower` and `ActApi` calls it.
- `FlowerMenuOpened` / `FlowerMenuClosed` in `BUS_KEYS` + `fireFlowerMenu` (payload built under `hasSub`);
  the three `FlowerMenu` seams (`added` end · both `uimsg` branches · the `destroy()` fallback) and the
  `choose` line that records a client-side petal's own label.
- Docs: the page's read + event halves, `api/event.md`'s two rows, both glance tables, `act.md`'s pointer.
- **Suite proves**: `:list()` is `{}` and `:count()` is `0` with no menu open, neither throws; `:list("x")`
  raises naming `:select`; both event keys are accepted by `:on` and an unknown neighbour still raises;
  after the `[manual]` right-click, the handler asserts the payload is a non-empty array of strings, equal
  to `:list()` read inside the handler, and that Opened was followed by exactly **one** Closed carrying the
  label picked (and `nil` when Esc was pressed).
- **`[manual]`**: right-click a tree and pick a petal — expect the printed labels to match the ring on
  screen; right-click it again and press Esc; right-click a kin in the Kin window and pick "Change group"
  — expect both events to fire there too (client-side menu, no server id).

## 047.2 — `:select` and `:cancel`, gated ✅ 19/19 pass, 0 fail

> Verified in-game. The suite stands **alone** — it enables and drives nothing else: `walker` ships the firing
> demo as documentation, and was deliberately kept out of the verification path after the maintainer's call that
> a current task is never validated through an older addon. Its one `[manual]` step is a bare right-click, and
> the armed handler re-asserts all four refusals **with a real ring on screen** (`"Chop"` at position 1), which
> is the half no headless probe can reach. The write half **raises** where the read half answers (**D-213**), and
> the gate runs before both the argument check and the menu lookup.

- `:select(label)` (case-insensitive, exact) and `:select(n)` (1-based), and `:cancel()`, both through
  `FlowerMenu.choose(Petal)` — never a re-encoded `"cl"` — behind the `actions` permission.
- Refusals name what is open: no match, index out of range, no menu open, non-string/non-number key.
- `walker` gains the firing demo beside its existing `act():flower` one; `examples.md` row updated.
- Docs: the page's write half + its gating note.
- **Suite proves** (read-only, so by refusal): `:select` and `:cancel` raise **naming the verb** for an
  addon that did not declare `actions`, while `:list`/`:count` answer in the same run; `hafen.act():flower`
  is still present and still a function (the coexistence this feature promises).
- **`[manual]`**: with `walker` enabled, right-click a tree and run its command with a label, then with an
  index — expect the same petal both times; right-click a player and select "Mute voice" — expect the mute
  to toggle with no petal sent to the server and `FlowerMenuClosed` to carry that label, not `nil`;
  select from **inside** an `FlowerMenuOpened` handler — expect the petal to fire during the opening
  animation without a stuck menu.

## 047.3 — `:gob()` ✅ 15/15 pass, 0 fail

> Verified in-game, all three gestures performed. The claim the run is worth is the two **negatives**: an
> inventory item's ring and the Kin window's own answered `nil` while `:count() > 0` proved a ring was really
> up — a nil asserted with nothing open would prove nothing. That round also closed 047.1's outstanding gap:
> the client-side (`BuddyWnd`) path has now fired its events in-game, not only headlessly.
>
> Two departures from what this file and `plan.md` wrote. The attribution is **not** cleared at the close: it
> lives in a second weak map that dies with the widget, because `:list()`/`:count()` keep answering through the
> closing animation and a `:gob()` that went `nil` there would have the three reads describing different menus
> (**D-214**). And the suite asserts `gob:name()`, not `:res()` — a Gob object has no `:res()`.
>
> "A second `FlowerMenuOpened` never re-consumes a spent token" is not reachable from Lua (nothing can announce
> a menu twice), so it is proven headlessly, in the probe that drove the seam directly; the suite's Lua-visible
> form is that two reads inside one handler hand back the same Gob object.

- `ClickToken` + the `MapView.Click.hit` line and the `ActApi.actClickGob` record; consumed at the Opened
  seam, held beside the open menu, cleared on close.
- Docs: the page's `:gob()` row **and** the paragraph that says when it is `nil` and why it is a
  correlation rather than something the server sends.
- **Suite proves**: `:gob()` is `nil` with no menu open; after the `[manual]` right-click on a **tree**,
  the handler asserts `:gob()` is non-nil, `:exists()`, and its `:res()` contains `"tree"`; after the
  right-click on an inventory item and on a kin row, it asserts `:gob()` is `nil`; and that a second
  `FlowerMenuOpened` for the same menu never re-consumes a spent token.
- **`[manual]`**: right-click a tree · right-click a seed or a curiosity in your backpack · right-click a
  kin in the Kin window — three menus, one assertion each, printed by the handler.
