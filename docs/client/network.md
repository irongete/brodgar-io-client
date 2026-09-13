# Networking, the session and the action channel

> How bytes reach the widget tree and how the client talks back.

## Session, transport

| What | Where |
|---|---|
| Session (connection + glob + resource ids) | `Session` — `glob`, `queuemsg`; created by `Bootstrap.run` |
| The wire connection (worker threads) | `Connection` — receive callbacks land on the **Connection worker** thread (never touch Lua/widgets there; marshal to the UI tick) |
| Server → client UI messages | `RemoteUI.uimsg` → `UI.uimsg` (queues a `UiMessage` Command, applied on a Loader thread under `synchronized(ui)`) |
| Client → server | `UI.wdgmsg` → `RemoteUI.rcvmsg` → `Session.queuemsg` |
| ⚠️ Two updates to **one widget** are ordered; two to different widgets are not | `UI.uimsg` submits each `UiMessage` as a `Command.dep(id, true)` — a dependency **and** a barrier on that widget id — and `UI.CommandQueue.submit` chains a command behind the last one that barred any id it depends on (the `score` map), releasing it in `finish`. So two updates aimed at the same widget never overlap and never re-order, whichever Loader thread each lands on, and anything that must answer *before* a widget applies its update can be done outside `UiMessage.run`'s `synchronized(ui)` and still be ordered against it. Two updates aimed at **different** widgets carry no such edge: they run side by side, on as many Loader threads as the pool has. `UI.wdgbarrier` is the server's own override of the next command's dep/bar set |
| Relog/session rebind | a new `RemoteUI.init(UI)` per session — session-scoped state resets there |

## Action channel (client → server)

| What | Where |
|---|---|
| **Universal action send** | `Widget.wdgmsg` → `UI.wdgmsg` → `RemoteUI.rcvmsg` → `Session.queuemsg` |
| **Which `UI` a send reaches: the widget's own** | `Widget.wdgmsg(sender, msg, args)` recurses up `parent` and only the **root** (`parent == null`) calls `ui.wdgmsg`, so the `UI` is the one that widget's tree hangs from — never an ambient "current" one. With several sessions live that is what makes a send driven off a widget land on **that** session: `BuddyWnd.Buddy.chname`/`chgrp`/`endkin`/`forget` and `Speedget.set` all reach the server through the login whose HUD holds them, drawn or not. `UI.wdgmsg` then runs the fork's outbound action-stream dispatch before `UI.rawWdgmsg`, which resolves the id against that same `UI`'s `rwidgets` |
| Map clicks / move / itemact / place / sel | `MapView` (Click.hit, drop, itemact, place, sel) |
| ⚠️ `"click"` is **not the map's** — seven senders, and only one carries a place | `MapView.Click.hit` `{pc, mc.floor(posres), btn, modflags}` is the only shape with a world coordinate in it. Beside it: `Avaview.mousedown` and `GiveButton.mousedown` send `{btn}`; `ISBox.mousedown` sends **nothing** (and `"xfer"` instead under shift); `Img.mousedown` sends `{ev.c, btn, modflags}`, where `ev.c` is **widget-local device** pixels and not a place at all; `Fightview` and `Partyview` send `{gobid, btn}`. So a tap keyed on the message NAME must read `Widget.getClass()` before it reads an index — arg 2 is a world `Coord` in one sender, a button in two, a `Coord` on a picture in another, and absent in a fifth |
| ⚠️ ...and a minimap click arrives **as the map view** | `MiniMap.mvclick` calls `mv.wdgmsg("click", …)` on the `MapView`, not on itself, so the sender is indistinguishable from a real map click. It fabricates `pc` from `ui.mc` when handed none, and builds `mc` from `loc.tc` against `sessloc` — the tile CENTRE (`add(tilesz.div(2))`), not a projected point. With a gob it appends `Gob.GobClick`'s tail, so the same name carries a 4-arg and a 9-arg shape from one method |
| **`Click.hit` is the one point where a click's GOB is known** | `Click.hit` → `clickhit` resolves `clickedgob(inf)` and then sends; every fork tap hangs there (`onGhostClick` consumes and returns, the voice move intent for a ground click, the click token). ⚠️ It runs on the **hit-test's own thread**, not the UI thread, and only for clicks that reach the map — an inventory or HUD click never passes here, which is exactly what makes "was this menu opened on a gob?" answerable |
| Attributing a later widget to an earlier click | a **time window alone** is approximate by construction (the player clicks, nothing opens, the server puts a menu up much later). The exact form keys on `UI.lcc` instead — see [widget-input.md](widget-input.md) |
| Gob click arg encoding | `Gob.GobClick.clickargs`, `Composited.CompositeClick` |
| Menu action by path / id | `GameUI.act`, `MenuGrid.PagButton.use` |
| Flower petal select | `FlowerMenu.choose` — `wdgmsg("cl", num, mods)`; cancel = `"cl", -1`. **Not a close seam**: `BuddyWnd`'s subclass overrides it, never calls `super`, and sends **no** `cl` — it calls `uimsg` by hand. `choose(null)` is the one door Esc (`keydown`) and a click away (`mousedown`) share, so it is what a programmatic cancel drives |
| A click picks from the ring's first frame | `FlowerMenu.mousedown` swallows the press while `anims` is non-empty, and the fork registers no anim on the ring — see [radial-menu.md](radial-menu.md). `Petal.mousedown` → `choose(this)` is only reached after the parent lets the event through; `choose` itself has **no** guard |
| Flower menu lifecycle (`@RName("sm")`) | `added` grabs mouse **and** keys (so nothing can be typed while one is up) and `organize(opts)` lays the ring out inside it — the END of `added` is the moment the petal set is complete and placed. `uimsg` `"act"`/`"cancel"` are the commit points: each drops both grabs and then calls `ui.destroy` on the widget, so the ring leaves the tree the instant it stops holding input. ⚠️ That order is load-bearing: a ring that outlives its commit stands in the tree holding no input, a second `sm` lands beside it, and `ui.root.children(FlowerMenu.class)` answers **two**, the dead one first. `BuddyWnd`'s `destroy()` **does** call `super` |
| Item take/drop/transfer/iact | `WItem.mousedown` — `take {cc}`; `drop`/`transfer` `{cc, n}`, where the **modifier keys select `n`** (shift = transfer 1, shift+ctrl = all, ctrl = drop 1, ctrl+meta = drop all), so those three carry **no** modifier field at all; `iact {cc, modflags}` |
| ⚠️ `itemact` names **no held item** — three senders, one meaning | The subject is implicit: `DTarget.Interact`'s `src` **is** the `ItemDrag`, so the bytes mean *whatever is on the cursor*. `WItem.iteminteract` = `{modflags}` (1 arg, onto another item); `MapView.iteminteract` = `{pc, mc.floor(posres), modflags}` (3, bare ground), **extended with `inf.clickargs()`** when the hit resolves to an object (8). Arg count alone tells the three apart |
| Server → widget update | `Widget.uimsg`, dispatched via `UI.uimsg` |
| ⚠️ The **post-apply** tap runs outside the monitor | `UI.UiMessage.run` closes its `synchronized(UI.this)` **before** the fork's post-apply `// addon:` line, and reaches it only when the update was actually applied (a swallowed one skips it). So anything hung there is on a Loader thread with no lock held: record the widget, read it on the tick |

## Connection counters (the `Connection:` HUD line)

| What | Where |
|---|---|
| The counter block | `Connection.Stats` — `ptx`/`prx` (packets), `btx`/`brx` (bytes), `pretx` (re-sent), `prerx` (received twice), `prorx` (out of order), `srtt`/`rttv` (**seconds**); all `private`, formatted by `toString()` |
| Fork accessors | `// addon:` getters beside `toString()` at — no new counting; written on the **Connection worker**, so a UI-thread read may be one packet stale |
| Reaching it from the UI | `ui.sess.conn` is a `Transport`; `instanceof Connection` before the cast — there is none at the login screen |
