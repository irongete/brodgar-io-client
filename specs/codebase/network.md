# Subsystem: networking, session & the action channel

> How bytes reach the widget tree and how the client talks back. Line numbers are indicative;
> the **class + method name is the stable anchor**. Max 40 lines.

## Session / transport

| What | Where |
|---|---|
| Session (connection + glob + resource ids) | [`Session`](src/haven/Session.java) — `glob` (:65), `queuemsg`; created by `Bootstrap.run` |
| The wire connection (worker threads) | [`Connection`](src/haven/Connection.java) — receive callbacks land on the **Connection worker** thread (never touch Lua/widgets there; marshal to the UI tick) |
| Server → client UI messages | `RemoteUI.uimsg` → [`UI.uimsg`](src/haven/UI.java:702) (queues a `UiMessage` Command, applied on a Loader thread under `synchronized(ui)`) |
| Client → server | [`UI.wdgmsg`](src/haven/UI.java:665) → [`RemoteUI.rcvmsg`](src/haven/RemoteUI.java:39) → `Session.queuemsg` |
| Relog/session rebind | a new `RemoteUI.init(UI)` per session — session-scoped state resets there |

## Action channel (client → server)

| What | Where |
|---|---|
| **Universal action send** | [`Widget.wdgmsg`](src/haven/Widget.java:737) → [`UI.wdgmsg`](src/haven/UI.java:665) → [`RemoteUI.rcvmsg`](src/haven/RemoteUI.java:39) → `Session.queuemsg` |
| Map clicks / move / itemact / place / sel | [`MapView`](src/haven/MapView.java) (Click.hit ~:2007, drop ~:2085, itemact ~:2094, place ~:2038, sel ~:2279) |
| Gob click arg encoding | [`Gob.GobClick.clickargs`](src/haven/Gob.java:677), [`Composited.CompositeClick`](src/haven/Composited.java:480) |
| Menu action by path / id | [`GameUI.act`](src/haven/GameUI.java:1683), [`MenuGrid.PagButton.use`](src/haven/MenuGrid.java:169) |
| Flower petal select | [`FlowerMenu.choose`](src/haven/FlowerMenu.java:278) (`wdgmsg("cl", num)`) |
| Item take/drop/transfer/iact/itemact | [`WItem`](src/haven/WItem.java:171) |
| Server → widget update | [`Widget.uimsg`](src/haven/Widget.java:677), dispatched via [`UI.uimsg`](src/haven/UI.java:702) |

## Connection counters (the `Connection:` HUD line)

| What | Where |
|---|---|
| The counter block | [`Connection.Stats`](src/haven/Connection.java:43) — `ptx`/`prx` (packets), `btx`/`brx` (bytes), `pretx` (re-sent), `prerx` (received twice), `prorx` (out of order), `srtt`/`rttv` (**seconds**); all `private`, formatted by [`toString()`](src/haven/Connection.java:232) |
| Fork accessors | `// addon:` getters beside `toString()` at [:217](src/haven/Connection.java:217) — no new counting; written on the **Connection worker**, so a UI-thread read may be one packet stale |
| Reaching it from the UI | `ui.sess.conn` is a `Transport`; `instanceof Connection` before the cast — there is none at the login screen |
