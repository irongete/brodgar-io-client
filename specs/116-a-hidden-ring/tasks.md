# 116 — a hidden ring: tasks

- [x] **116.1 — `s:flowermenu():visible(b)`, the ring the client does not paint.** Adds the read/write
      pair to `FlowerMenuApi.flowermenu()`'s section table: the read answers `true`, `false`, or `nil`
      with no menu open; the write takes a bare boolean, hands the section back, and drives
      `Widget.hide`/`show` on the menu under that tree's monitor. Writes `## Drawn or not (unprotected)`
      on `flowermenu.md`, with the auto-pick block as its example, and revises the section's one-line
      description in `api/README.md` and `session.md`.
      *Its suite* raises two rings of its own with `s:world():click(gob, 3)`, retrying over nearby
      objects for a bounded window and scoring what it reached. On the first: `:visible()` is `true`,
      `:visible(false)` reads back `false`, `:list()`, `:count()` and `:gob()` answer what they answered
      painted, `:visible(true)` reads `true`, and a `:select(label)` on the hidden ring still picks —
      `FlowerMenuRemoved` carries that very label. Then the refusals: `:visible(0)`, `:visible("false")`
      and `:visible(nil)` each raise naming `b`, and with no menu open `:visible()` is `nil` while
      `:visible(false)` raises naming the character. The second ring, untouched, answers `:visible()`
      `true` — nothing is left behind — and is then hidden and shown again a beat later.
      `[manual]`: watch the screen while the first ring is raised — expect: no ring at all.
      `[manual]`: watch the screen while the second is raised — expect: the ring appears, a beat late.

- [ ] **116.2 — a hidden ring is one the pointer cannot be over.** Adds the two `// addon:` guards to
      `FlowerMenu`: `mousedown` while `!visible` goes to `choose(null)` instead of propagating into the
      petals, and the `'0'..'9'` branch of `keydown` does nothing while `!visible`, leaving `key_esc`
      alone. States the rule on `flowermenu.md` beside the verb — a hidden ring still holds the mouse
      and the keyboard, a click can only end it, and the 0.25 s open animation swallows a press before
      any of this. Amends the pointer and grab rows of `client/widget-input.md`, in place: a grab
      reaches a widget whatever its own flag says, and only the walk into children tests one.
      *Its suite* raises a ring, hides it, and prompts one gesture at a time, scoring each from
      `FlowerMenuRemoved` and `:count()`: after a digit the ring is still open and nothing was chosen;
      a click ends it carrying `nil`; on a second hidden ring, Esc ends it carrying `nil`.
      `[manual]`: press `1` when asked. `[manual]`: click anywhere when asked. `[manual]`: press Esc
      when asked.

- [ ] **116.3 — the widget door names the ring's own verb.** Refuses `w:visible(true)` and
      `w:visible(false)` on a `FlowerMenu` receiver in `LuaWidget`'s `visible` write, before the
      borrowed/owner branch, naming `s:flowermenu():visible(b)`; the read still answers. Adds the
      exception as a clause inside the existing sentences of `ui/native.md` and the existing
      `:visible(b)` row of `ui/widget.md`, with no net growth on either. Discharges the feature's
      impact set, page by page with its reason, and runs `tools/docverbs.py` and
      `tools/refusalverbs.py`.
      *Its suite* raises a ring, matches it with `s:ui():match("@FlowerMenu")`, and `pcall`s both
      writes: each fails, and each message names `flowermenu():visible` — the spelling that replaces it,
      not merely that the call was refused. `w:visible()` on the same widget still reads `true`, and
      reads `false` once the ring is hidden through its own verb, so the two doors agree on the fact and
      disagree only on who may write it.
