# 131 — a group is a number, not a colour

## What and why

A kin group is a **number** the server assigns, and a colour is what the client paints it with. The server
accepts `0..254` — the range `kin:group(n)` already validates and `docs/addons/api/kin.md` already states —
while `BuddyWnd.gc` holds **eight** colours. The group arrives raw off the wire (`BuddyWnd`'s `add`/`upd`
uimsgs, and a polity's own member list), and every reader in this client indexes that array **bare**. So a
member in a group above the eighth throws `ArrayIndexOutOfBoundsException` out of a **draw**, which is the
UI thread, which takes the client with it.

Three kinds of site index it, and only two are ours:

- **The fork's own draws.** `BuddyWnd`'s roster row (`gc[b.group]`) and the floating kin name over a gob
  (`res/ui/obj/buddy/Buddy.draw`, the adopted copy of the published `ui/obj/buddy`).
- **`BuddyWnd.GroupSelector.update`**, whose `groups[this.group]` unselect runs on a `group` field that
  `BuddyInfo.update` writes raw from the wire — so clicking a colour to move such a kin *back* into the
  palette is itself the crash, and the recovery path is the one that cannot run.
- **The server's own published code, which is not ours to guard.** `ui/vlg`'s `Village.VMember.draw` and
  `ui/realm`'s `Realm.RMember.draw` each do `g.chcolor(BuddyWnd.gc[grp])`, and a third resource draws the
  village member panel. Adopting each with `get-code` pins a copy per resource: `Resource.ResClassLoader`
  takes a local class only while its `@FromResource` version equals the one the server serves, so every pin
  goes stale silently on the next update, and a pin only ever covers a site we have already crashed in.

**And the number is unreachable where it matters most.** Above the palette no square highlights, so the
number is the only thing that says which group a row is in — and `BuddyWnd.GroupSelector` answers nothing:
`widget:value()` has no arm for it, so an addon cannot read what a colour row shows, and cannot put a row
in a group the eight squares cannot reach. A user cannot either: the row is eight squares wide and that is
the whole of the client's reach into a 255-wide space.

**A row of numbers is refused as well.** `LuaRows.parse` asks `e.isstring() && !e.isnumber()`, and in LuaJ
those are coercion predicates rather than type tests: `("0"):isnumber()` is true, so `:rows{"0", "1", "2"}`
— every row a group picker needs — is refused, with a message that contradicts itself (*must be a STRING …
got string*). `Args.str` already states the rule this file does not follow, and `CEntry` already carries the
fix for its own arm of it.

## Acceptance criteria

1. A member in **any** group the server accepts, `0..254`, draws without throwing — in the kin roster, in
   the floating name over a gob, in a village member list and in a realm member list.
2. That holds for the sites in **published resource code** too, without adopting a copy of any resource.
3. A group above the palette has **no colour of its own**: it draws in the ungrouped colour rather than
   borrowing another group's, and `kin:color()` is `nil` for it.
4. The eight named colours, the eight selectable groups and the box the colour row is drawn in are
   unchanged: nothing about the client's own UI grows because the array behind it did.
5. `kin:group()` answers the true index for every group the server accepts, palette or no palette — it is
   the only thing that tells two groups above the eighth apart.
6. Clicking a colour on a row that is in a group above the palette moves it back into the palette rather
   than throwing.
7. `widget:value()` on a colour row is the group it is showing, and `nil` where it shows none.
8. `widget:value(n)` on a colour row puts it in group `n`, `0..254`, by running the very method a click on
   a square ends in — so the message that reaches the server is the window's own, and the caller neither
   knows nor names it.
9. A row of numeric-looking strings is a row like any other: `:rows{"0", "1"}` is accepted, and a row that
   is a real Lua number is still refused, naming `tostring`.

## Out of scope

- **What the server does with a group above the eighth.** Whether a village serves permissions for group
  200 is the server's to answer; this feature is about the client not dying while it asks.
- **Showing a group above the palette in the client's own UI.** The colour row still has eight squares and
  the client still offers no way to pick a ninth group: criterion 4 is that boundary. Reaching the rest of
  the range is an addon's to do, through criterion 8, and `addons/better-village-controls` is the
  maintainer's own doing exactly that.
- **A ninth colour, or a colour cycled per group.** A colour that repeats every eight groups would say two
  different groups are the same one, which is worse than saying nothing; the number is what distinguishes
  them.
- **The kin window's own selector reaching past eight.** `GroupSelector` builds one square per colour by
  design, and `BuddyWnd.ncolors` is the name that says so.

## Docs impact

- **`docs/client/services.md`**, the kin roster row: the gotcha this feature exists for — the group is
  wider than the eight colours, `gc` is sized for the whole range and `ncolors` is how many are
  *selectable*, `gcolor(group)` is the read for a value that may be outside `0..254` altogether, and
  `GroupSelector.update` is guarded because `BuddyInfo.update` writes its field raw. It names the two
  published sites as the reason the array is sized rather than each caller guarded. The same page gains
  the **polity panels** the crash came through, which no `docs/client/` page covered: `GameUI.Zergwnd`'s
  tabs, `haven.Polity` being abstract with every concrete panel in published code, and the colour rows
  those panels build client-side — no widget id, `select(group)` overridden to send the panel's own
  message. `gameui-windows.md` is at its 150-line ceiling, and this belongs beside the kin roster and the
  palette anyway: one subject, one place.
- **`docs/addons/api/kin.md`**, *Groups go to 254, colours stop at 8*: what a group above the palette
  actually does now — `kin:color()` `nil`, no `color` in `kin:info()`, the kin drawn in the ungrouped
  colour in the Kin window and over their gob alike, and `kin:group()` as the only thing that tells two of
  them apart.
- **`docs/addons/api/ui/edit.md`**, *Driving one*: the colour row joins the table of what a drive lands on,
  and the note under it says the same widget reads back — the number, which is all a group above the
  eighth has.
- Nothing else moves. `kin:color()`'s own row already says "`nil` for a group of 8 or more" and stays
  exactly true; `widget:value()`'s row in `widget.md` is written for every control at once.

## Context files

- `src/haven/BuddyWnd.java` — 1, 3, 4, 6 (`gc`, `ncolors`, `gcolor`, `GroupRect.draw`, `GroupSelector`
  and its `update`, `BuddyInfo.update`'s raw field write, `BuddyList`'s item draw)
- `src/haven/res/ui/obj/buddy/Buddy.java` — 1 (the adopted `ui/obj/buddy` v4: `draw`'s one palette read,
  and the header note that says which line of it is the fork's)
- `src/haven/MapWnd.java` — 4 (`PMarker`'s random colour and the marker colour selector: the two readers
  that mean *selectable* by `gc.length`)
- `src/io/brodgar/addon/LuaKin.java` — 3, 5 (`color`, `group`, `MAXGROUP`)
- `src/io/brodgar/addon/CharApi.java` — 3, 5 (`kinSnapshot`, and the diff that drives `KinChanged`)
- `src/io/brodgar/addon/LuaWidget.java` — 7 (`value(Widget)`, the read dispatch)
- `src/io/brodgar/addon/Controls.java` — 8 (`drive`, the write dispatch; `noValue`, which names what holds
  a value; `num`, the shared number coercion)
- `src/io/brodgar/addon/LuaRows.java` — 9 (`parse`, and `icon`'s copy of the same test)
- `src/io/brodgar/addon/Args.java` — 9 (read only: `str`/`num`, which state the LuaJ coercion rule this
  feature stops re-deriving)
- `docs/client/services.md`, `docs/addons/api/kin.md`, `docs/addons/api/ui/edit.md` — the three pages above
