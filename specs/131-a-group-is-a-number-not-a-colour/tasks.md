# 131 — tasks

> **One suite, and it is the feature's own integration check.** The fault this feature fixes is a **draw**:
> an exception on the UI thread, thrown by the client's own code and by the server's, which no addon can
> observe — a suite that could see it is a suite running after the client is already gone. What a suite can
> assert is the surface the three tasks ship, and 131.3's does all of it in one command, duplicating each
> earlier task's assertion rather than deferring to it. The crash itself is proved twice over: headlessly
> with `jshell` over the built classes, which is where the palette and the two dispatch tables are
> readable, and in-game by the maintainer, who reached the fault through
> `addons/better-village-controls` — their own addon, and the reason this feature exists.

- [x] **131.1 — the palette answers for every group the server takes.**
      `BuddyWnd.gc` is sized for `0..254` and filled by a static block: the eight named colours, then the
      **ungrouped** colour (`gc[0]`) for every group above them, with the block asserting its own literal
      count against the new `BuddyWnd.ncolors` so the two cannot drift. `ncolors` is the name for the
      question three callers were using `gc.length` to ask — `GroupSelector`'s rectangle array, its box
      width and its build loop; `MapWnd`'s random marker colour; and the two addon-facing guards that keep
      `kin:color()` `nil` above the palette — so the colour row is still eight squares in the same box.
      `gcolor(group)` narrows to the read for a value outside the group space altogether, and the two
      fork-owned draws (`BuddyWnd`'s roster row, `res/ui/obj/buddy/Buddy.draw`) go through it because each
      reads a field rather than a validated argument. `GroupSelector.update` bounds-checks **both** ends of
      its swap, the outgoing one being what threw: `BuddyInfo.update` writes `grp.group` raw from the wire,
      so clicking a colour to move such a row back into the palette was itself the crash. The adopted
      `ui/obj/buddy` copy's header stops claiming to be unmodified and names its one fork-owned line.
      *Its jshell proof* reads `ncolors` and `gc.length` back (8 and 255), the eight named colours
      unchanged at `0..7`, the bare `gc[grp]` the published code does at 8, 11, 42, 200 and 254 (the
      ungrouped colour, no throw), `gcolor` at −1, 255 and 100000, a `GroupSelector` still building **8**
      rectangles in a `(160, 20)` box, and `update()` driven across the palette edge and back without
      throwing.
      *In-game*: the maintainer put a village member in group 11 and the client kept drawing — the same
      action that had ended the UI thread in `haven.res.ui.vlg.Village$VMember.draw`, which is published
      code and not ours to guard, and which is why the array answers rather than each caller guarding.

- [x] **131.2 — a row of numbers is a row of strings.**
      `LuaRows.parse` asks the **type** in all three places it asked `isstring() && !isnumber()`: the text
      row, an icon row's `text`, and an icon's own resource name. In LuaJ those two are coercion predicates
      rather than type tests — `("0"):isnumber()` is true — so a row of digits was refused as not being a
      string, in a sentence that said *must be a STRING … got string*. A real Lua number is still refused,
      now naming `number` and offering `tostring(n)`, the shape `Args.str` already uses; `Args` states this
      rule and this file stops re-deriving it.
      *Its jshell proof* calls `parse` over 255 rows of digits (255 rows back), over `{"Wood", "061.8"}`
      (two rows back — the ordinary string that the old test also refused), and over a real number, which
      is refused with the new sentence.
      *Its suite* is 131.3's, which builds a dropdown carrying `"0"` … `"254"` and round-trips a pick
      through `:value(v)`: the same assertion, in the surface an author actually writes.

- [x] **131.3 — a colour row holds a group, and the API reads and drives it.**
      `BuddyWnd.GroupSelector` holds the group it is showing — what the highlighted square means, and above
      the palette, where nothing highlights, the only thing there is to read. It answers where every other
      control's value does: an arm in `LuaWidget.value(Widget)` reads it, with the engine's own `-1`
      ("nothing selected", which the polity rows are built with) answering `nil`; an arm in
      `Controls.drive` writes it by calling `select(group)`, the very method `GroupRect`'s own `mousedown`
      ends in. The polity windows override `select` to send their own message, so the drive **is** the
      click: what reaches the server is the window's own message and the caller neither knows nor names it.
      The range is checked first — `0..254`, the range the server accepts — and `Controls.noValue` names
      the colour row among what holds a value.
      *Its pages*: `docs/addons/api/ui/edit.md` gains the row in *Driving one*'s table and the note under
      it that the same widget reads back; `docs/client/services.md` and `docs/addons/api/kin.md` carry
      131.1's half.
      *Its jshell proof* reads a row built at group 3 (`3`) and one built at −1 (`nil`), drives it to 200
      and reads 200 back (the field moved, nothing threw — which is 131.1's guard doing its work), drives
      it back to 5, and asserts the three refusals: 255 and −1 out of range, and `"3"` as a string where a
      number is meant.
      *Its suite* is the feature's, and it duplicates rather than defers: a dropdown of `"0"` … `"254"`
      (131.2), every kin's group read as a number with a colour exactly below the palette and none above it
      (131.1's addon-facing half), and — once a colour row is on screen — its group read back and the two
      refusals its drive owes (131.3).
      `[manual]`: open Kith & Kin and pick the Village tab — expect: the four `colour row` lines below,
      instead of the one that says none was found.
