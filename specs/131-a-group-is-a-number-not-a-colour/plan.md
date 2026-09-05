# 131 — plan

## Approach

### The palette answers for the whole group space, rather than every caller guarding it

A bare `gc[group]` is what every reader writes, and most of those readers are **not ours**: `ui/vlg` and
`ui/realm` each index the array in a draw, and a third resource draws the village member panel. The array
is therefore sized for the space the server accepts — 255 entries, `0..254` — with the first
`BuddyWnd.ncolors` (8) the named colours and the rest the **ungrouped** colour, which is `gc[0]`, white,
exactly what an ungrouped kin already looks like. A group above the palette then reads a colour instead of
throwing, in code we can see and in code we cannot.

`gc` stays a `public static final Color[]` filled by a static block, and the block asserts its own literal
count against `ncolors`, so the two numbers cannot drift apart in a later edit.

### `ncolors` is the name for the question `gc.length` used to answer

Three call sites mean *how many groups are selectable* rather than *how far the array reaches*, and each
moves to the new constant: `GroupSelector`'s rectangle array, its box width and its build loop; `MapWnd`'s
random marker colour; and the two addon-facing guards that keep `kin:color()` `nil` above the palette
(`LuaKin.color`, `CharApi.kinSnapshot`). After the move the colour row is still eight squares in the same
box, which is criterion 4, and the addon contract is unchanged, which is criterion 3.

### `gcolor(group)` is what remains of the guard

Inside `0..254` a bare index is now safe on its own, so the guarded read is only for a value that is
outside the group space altogether — a negative, or a number no group can be. It is kept because the two
fork-owned draws read a field, not a validated argument, and a field is what a hostile or broken server
writes.

### The selector's own field is guarded, because it is written raw

`BuddyInfo.update` does `grp.group = buddy.group` — a wire value straight into the widget's field, not
through `update(int)`. `GroupSelector.update` then unselects the outgoing group before selecting the
incoming one, and it is that **unselect** that indexes `groups[]` with a number no rectangle answers. Both
ends of the swap are bounds-checked, which is what makes clicking a colour on such a row move it back into
the palette instead of throwing (criterion 6). The array is `groups.length`, so the check reads the same
number the constructor built with.

### A colour row holds a value, so `widget:value` is where it answers

`BuddyWnd.GroupSelector` holds the group it is showing, which is what the highlighted square means and —
above the palette, where nothing highlights — the only thing there is to read. That is a **value** in this
API's own vocabulary, so it goes where every other control's does rather than into a verb of its own: an
arm in `LuaWidget.value(Widget)` for the read, `-1` (the engine's own "nothing selected", which the polity
rows are built with) answering `nil`; and an arm in `Controls.drive` for the write.

The write calls `select(group)` — the very method `GroupRect`'s own `mousedown` ends in. The polity windows
override `select` to send their message (`ui/vlg`'s `Village` sends `"gsel"`, and the member panel its
own), so the drive **is** the click: the message that reaches the server is the window's own, and the
caller neither knows nor names it. That is the same rule the slider and the checkbox arms already follow —
a drive runs the client's own hook — and it is why the addon that needed this has no protocol knowledge in
it at all.

The range is checked before the call: `0..254`, the range the server accepts, refused with the eight
colours named as the narrower thing the squares can show.

### A row is a string when its TYPE is a string

`LuaRows.parse` asks the type — `e.type() == LuaValue.TSTRING` — in all three places it used to ask
`isstring() && !isnumber()`: the text row, an icon row's `text`, and an icon's own resource name. A real
Lua number is still refused, and the refusal now names what it got (`number`) and offers `tostring(n)`,
which is the shape `Args.str` already uses. `Args` states the rule; this file stops re-deriving it.

## Files to create or modify

- `src/haven/BuddyWnd.java` — `ncolors`; `gc` sized for `0..254` with a static block and its own
  count assertion; `gcolor` narrowed to the outside-the-space read; `GroupSelector`'s three `gc.length`
  uses; `GroupSelector.update`'s two bounds checks; `BuddyList`'s item draw through `gcolor`.
- `src/haven/res/ui/obj/buddy/Buddy.java` — the floating name's palette read through `gcolor`, and the
  header note amended: the adopted copy is no longer unmodified, and it says which line is the fork's.
- `src/haven/MapWnd.java` — the random marker colour over `ncolors`.
- `src/io/brodgar/addon/LuaWidget.java` — the colour-row arm in `value(Widget)`.
- `src/io/brodgar/addon/Controls.java` — the colour-row arm in `drive`, and `noValue`'s list of what holds
  a value.
- `src/io/brodgar/addon/LuaKin.java`, `CharApi.java` — the palette guards over `ncolors`, and the comment
  that says why neither routes through `gcolor`: the ungrouped-colour fallback is a **draw**, not the
  group's colour, and `nil` is the truthful answer here.
- `src/io/brodgar/addon/LuaRows.java` — the three type tests, the refusal text, and the note on `parse`.
- `docs/client/services.md`, `docs/addons/api/kin.md`, `docs/addons/api/ui/edit.md` — as `spec.md` says.

## Risks and gotchas

- **Published code that loops `gc.length` to draw a picker** would now draw 255 squares. The two resources
  that can be read do not — they build a `BuddyWnd.GroupSelector`, which goes by `ncolors` — and the fault
  would be visible on the first frame and revertible in a line. It is the price of the array being the
  thing that answers, and the alternative (a pin per resource) is worse in the section below.
- **`Utils.index(BuddyWnd.gc, col)`** in `MapWnd` now searches 255 entries instead of 8 and finds 247
  duplicates of white after the eight named ones. It answers the **first** match, so every named colour
  still resolves to its own index and white still resolves to 0, which is what it did before.
- **A drive is not an interaction**, so no `Changed` of the addon's own fires from `widget:value(n)` — but
  the client's own `select` hook *does* run, because that is the whole point of a drive. The two are
  different seams and `edit.md` already says so; the colour row does not change the rule.
- **The adopted `ui/obj/buddy` copy carried a header saying it was unmodified.** It no longer is, and a
  header that lies is worse than the edit: it is amended in the same task, naming the one line.
- **`LuaWidget` does not import `haven.BuddyWnd`**, so the arm names the class in full. A bare
  `BuddyWnd.GroupSelector` there compiles as a package reference and fails with a message that names no
  class the reader recognises.

## Discarded alternatives

- **Adopting `ui/vlg` and `ui/realm` with `get-code` and guarding their draws** — the mechanism this
  project already uses for `ui/obj/buddy`, and the wrong one here. `ResClassLoader.loadClass` takes a local
  class only while its `@FromResource` version matches what the server serves, so each pin goes stale on the
  next update **in silence** (a warning, and the resource's own code back) and the crash returns; each pin
  also covers only a site already crashed in, and the village member panel is a third resource that could
  not even be named from a stack trace. One array that answers for the whole range covers all of them,
  including the ones nobody has found.
- **Guarding each fork-owned draw and leaving the array at eight** — what the first cut of this did. It
  fixes the two sites we own and nothing the server ships, which is where the crash the maintainer actually
  hit came from.
- **Cycling the palette above the eighth (`gc[8 + n] = gc[n % 8]`)** — it gives every group a colour and
  makes group 11 look exactly like group 3, so the picture lies about the one thing that distinguishes
  them. The ungrouped colour says *this group has no colour*, which is true.
- **Making `kin:color()` answer the ungrouped colour instead of `nil`** — the fallback is what a **draw**
  does when it must paint something; it is not the group's colour. `nil` is the truthful answer and
  `kin:group()` is the one that distinguishes, which is what the page now says.
- **A verb of its own for a colour row's group** (`row:group()`) — a colour row holds a value and this API
  has one name for what a control holds. A second spelling for the same question is exactly what the
  grammar forbids.
- **Driving the row by sending its message** (`widget:send("gsel", n)`) — it needs the caller to know a
  protocol name that no client-side source names, differs per panel, and would be read off the wire and
  cached. `select(group)` is the client's own method and the window's own override is what sends.
- **Rewriting the outbound message in flight** (`hafen.event():action()` + `ev:send`) to carry a different
  group — it makes an addon's pick indistinguishable from a member selection at the point where it has to
  decide, and it rewrites a message the client meant. Both are avoided entirely once the drive exists.
- **Widening `Args.str`'s reach into `LuaRows` wholesale** — `parse` branches on *shape* (a string row or a
  table row) rather than requiring a string, so it needs the type test, not the refusal. The rule is
  `Args`'s; the branch is this file's.
