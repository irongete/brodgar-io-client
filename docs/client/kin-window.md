# The Kin window: the roster, and the panels beside it

> `BuddyWnd` and the Kith & Kin window it stands in: the buddy roster, the eight-colour palette that is
> 255 long, the village and realm panels that share it, and the mark that ties a kin to a gob.

| What | Where |
|---|---|
| Kin / buddy roster | `GameUI.buddies` (`BuddyWnd`, `Iterable<Buddy>`); `iterator()` (copies under lock) / `find(int)`; `Buddy.id`/`name`/`online`/`group` (public); palette `BuddyWnd.gc`; changes via `uimsg` `add`/`rm`/`chst`/`upd`  — `serial` skips `chst`. Mutate: `wdgmsg` `rm`/`nick`/`grp`. ⚠️ **`group` is wider than the eight colours, and the palette answers for the whole of it**: the server accepts `0..254` while eight groups have a colour, so `gc` is **255 long** — the first `BuddyWnd.ncolors` (8) are those colours and the rest are the ungrouped one. It is sized that way because a bare `gc[group]` is what every reader writes and most of them are **not ours to guard**: the server's own published code does it in `ui/vlg`'s `Village.VMember.draw` and `ui/realm`'s `Realm.RMember.draw`, and adopting each with `get-code` would pin a copy per resource, going stale on the next server update and covering only the sites we happen to have crashed in. **`ncolors` is how many groups are SELECTABLE** — what `GroupSelector` and `MapWnd`'s marker palette read — and `gc.length` is only how far the array reaches; **`BuddyWnd.gcolor(group)`** is the read for a value that may be outside `0..254` altogether. `GroupSelector.update` is guarded for a second reason: `BuddyInfo.update` writes its `group` field raw from the wire, so clicking a colour to move such a kin back into the palette unselected a rectangle that was not there |
| Village / realm panels (the Kith & Kin window's other tabs) | `GameUI.Zergwnd`, a `Hidewnd` holding `Tabs` — one `PTab<Category>` per kind, and a `Category` holds its `Polity` children and grows an `SDropBox` selector once there is more than one. **`haven.Polity` is abstract and every concrete panel is published resource code**: `ui/vlg`'s `Village`, `ui/realm`'s `Realm`, `ui/polity`'s `Generic`/`GroupWidget`; the selected member's panel is a `Polity.MemberWidget` the server adds with `addchild(child, "m")`, which `Polity` places and re-`pack()`s, and `Member.rname` resolves through `GameUI.buddies` — a village member and a kin are one person by buddy id. ⚠️ **Those panels' colour rows are `BuddyWnd.GroupSelector`s that published code builds CLIENT-SIDE**: each carries no widget id (`wdgid()` is `-1`), so nothing can be sent *from* one — the panel overrides `select(group)` to send its own message instead (`ui/vlg`'s `Village` sends `"gsel"`, the member panel `"perm"`), and `Village.tick` mirrors `((GroupWidget)mw).id` into its row every frame. `@RName("grp")` builds a **server-side** selector answering `("ch", group)`, and no polity window uses it. Their member draws index `BuddyWnd.gc` bare, which is the row above ⚠️ **A third colour row lives outside those panels**: `ui/land`'s `Landwindow` (the claim window), whose row picks which of the claim's own permission rows is being edited. It keys everything off that index — its `GroupSelector` overrides `changed` to call `updflags()`, which reads `bflags[group.group]` **synchronously inside `select`**; the `"shared"` wdgmsg a permission box sends carries it; the `"shared"` uimsg writes `bflags[g]` back raw. The published source sizes that table at the eight groups with a colour, so driving the row above the eighth threw `ArrayIndexOutOfBoundsException` out of the hook. **The fork adopts the resource** (`haven.res.ui.land.Landwindow`, `@FromResource` v51) for that one line, widened to the whole `0..254` space like `BuddyWnd.gc`. Version-pinned: a newer published `ui/land` is preferred over the copy, and the ceiling comes back. ⚠️ **The eight are the SERVER's ceiling too, and only the crash was ours**: measured in both directions, the `"shared"` wdgmsg leaves with the group and the flags it was given (`-> shared 254 = 15`) and the claim, reopened, pushes back only the rows it kept — never that one. A polity grants the same four permissions per group and does keep them, because there a group is a **widget the server made** (`ui/vgrp`, carrying its own id, answering `"perm"`) rather than a row index. What the client cannot see from here is whether a permission it will not redisplay is nevertheless enforced |
| Kin ↔ gob | The buddy id is carried **on the gob** — `res/ui/obj/buddy/Buddy.id`, mapped in [state.md](state.md). **1→N**: a kin's body *and* their hearth fire are both marked. No reverse index exists — kin→gob is an `OCache` sweep |

## Gotchas

- **`chst` and `upd` dereference `find(id)` with no null test.** Both arms of `BuddyWnd.uimsg` do
  `Buddy b = find(id); b.chstatus(...)` straight, so an id the roster has not got is a
  `NullPointerException` out of `uimsg` — on the message thread, which is where a throw is least
  recoverable. The client survives it in practice because the server sends `add` first; anything that
  drives those arms by hand, or races the roster, has to check for itself.
- **A group is `0..254` and the palette is `255` long, but only the first eight are colours.** The row
  above states why the array is sized that way and which read to use; the short form is
  `BuddyWnd.gcolor(group)` for a value that may be out of range, `BuddyWnd.ncolors` for how many groups
  are selectable, and never a bare `gc[group]` in new code.

## What is not mapped

The Kin window's own layout and sorting, the chat side of a private conversation (that is
[chat.md](chat.md)), and the wire format of the `add`/`rm`/`chst`/`upd` messages beyond the fields named
above.

## See also

- [services](services.md) — keybindings, resources, and the rest of the cross-cutting map
- [state roots](state.md) — `res/ui/obj/buddy/Buddy`, the mark on the gob itself
- [the radial menu](radial-menu.md) — the Kin window's right-click menu is a client-side `FlowerMenu`
- [GameUI's own windows](gameui-windows.md) — where `Zergwnd` hangs and how it is closed
