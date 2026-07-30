# Subsystem: state roots (the client's read surfaces)

> Where game state actually lives: gobs, map, player, items, character, party, time. Line
> numbers are indicative; the **class + field/method name is the stable anchor**. Max 40 lines.

| What | Where |
|---|---|
| Global root | [`Glob`](src/haven/Glob.java:34) via `ui.sess.glob` ([`UI.sess`](src/haven/UI.java:55), [`Session.glob`](src/haven/Session.java:65)) |
| Object cache (gobs) | [`OCache`](src/haven/OCache.java:35): iterate (:164), `getgob` (:199), `callback`/`uncallback` (:75/:79) |
| Game object | [`Gob`](src/haven/Gob.java:33): `id` (:38), `rc` (:34), `a` (:35), `getattr` (:611), `getc` (:584) |
| Gob attributes | [`GAttrib`](src/haven/GAttrib.java): `Drawable.getres()`, [`Moving`](src/haven/Moving.java), [`GobHealth.hp`](src/haven/GobHealth.java:35), [`GobIcon`](src/haven/GobIcon.java), [`Speaking`](src/haven/Speaking.java) |
| Map / terrain | [`MCache`](src/haven/MCache.java:36): `gettile` (:929), `tilesetr` (:1107), `getcz` (:948), `getgrid` (:909); `tilesz`/`cmaps` (:37/:39) |
| Player id / gob / camera | [`MapView.plgob`](src/haven/MapView.java:45), `player()` (:1133), `getcc()` (:1137), `camera` (:51) |
| Inventory / items | [`GameUI.maininv`](src/haven/GameUI.java:54), [`Inventory.wmap`](src/haven/Inventory.java:38), [`GItem`](src/haven/GItem.java) (`res`, `num` :39, `meter` :39, `info()` :199) |
| Equipment | [`GameUI.equwnd`](src/haven/GameUI.java) → [`Equipory.wmap`](src/haven/Equipory.java:83) |
| Item metadata / name | [`ItemInfo`](src/haven/ItemInfo.java): `Name` (:177), `find` (:354), `buildinfo` (:362) |
| Character attributes | [`Glob.getcattr`](src/haven/Glob.java:344), `CAttr{base,comp}` (:87); [`CharWnd`](src/haven/CharWnd.java) (exp/enc :60) |
| Party | [`Glob.party`](src/haven/Party.java:32): `memb` (:33), `Member.getc()` (:60), `col` (:49) |
| Time / astronomy | [`Glob.globtime`](src/haven/Glob.java:210) (`gtime` :39); [`Glob.ast`](src/haven/Glob.java:40) → [`Astronomy`](src/haven/Astronomy.java:32) `dt`/`night`/`mp`/`yt`/`is` ([:32–36](src/haven/Astronomy.java:32)); light fields (:42–47) |
| Gob speech / icon | [`Speaking.text`](src/haven/Speaking.java:37) (reliable); [`GobIcon.Icon.name()`](src/haven/GobIcon.java:64) (Loading-guarded) |

## Gotchas

- **No global world position**: `rc` is login-relative; the shareable anchor is grid id +
  within-grid offset. Grid/segment ids are 64-bit → expose as decimal strings.
- Any read here can throw **`Loading`** — swallow to a partial/absent value or defer.
