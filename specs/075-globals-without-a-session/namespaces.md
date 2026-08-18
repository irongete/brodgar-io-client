# The namespaces, and where each one lives

Every `hafen.*` namespace the client mounts, with its verdict. Written by `075` and read by `076`,
`077` and `078` rather than re-derived; it freezes with this folder.

The list is complete and checked both ways: thirty-one `Section.install`/`Section.mount` calls in
`src/io/brodgar/addon/`, thirty-one pages under `docs/addons/api/`, no name on one side missing from
the other. The only extra pages are `README`, `conventions`, `references`, `types`, `gob` and
`overlay`, which are cross-cutting or type pages rather than namespaces.

**The question is not what a namespace reads today** — today everything reads the drawn session,
because there has only ever been one. The question is **what the thing it names belongs to**: the
addon, the client, the world, or one character.

---

## Global — 13

Spelled `hafen.x()` forever. No session to address, because the thing is not a character's.

| Namespace | What it is | Why global |
|---|---|---|
| `timer` | scheduling | the timers are the addon's |
| `event` | the bus | the subscriptions are the addon's |
| `slash` | console commands | the registration is the addon's, and it lands on the **client** rather than on a login: `Console.setscmd` writes one process-wide static map, and every `UI`'s own `cons` merges that map in `findcmds`. Verified by `075.1`, and nothing had to change — a command answers from every session's console and from the layer's, with no re-registration on a switch |
| `asset` | the files your addon ships | the folder is the addon's |
| `json` | parsing and encoding | a pure function; it reads nothing |
| `http` | external HTTP requests | the addon's, with its allowlist in the manifest |
| `font` | typography | the client's faces and the addon's own `.ttf` |
| `client` | the settings the Options window edits | the client's preferences |
| `log` | printing a line | telling the player, and there is one player |
| `sound` | sound effects | you hear it; a character does not |
| `time` | the game clock, day, night and season | every session answers the same value |
| `vr` | what you stand in the world | a free entity holds **a grid id and an offset in it**, which is the server's and means the same place in every session; an anchored one follows a gob, and gob ids are global too |
| `map` | the recorded map | one database on disk per `(mapstore, mapfilename())`, and its grids are addressed by **the id the server published** |

## Per session — 16

Spelled `hafen.session():current():x()` or `:get(user):x()`. Each names one character's own state.

`player` · `char` · `meter` · `buff` · `study` · `quest` · `wound` · `fight` · `actionbar` ·
`speed` · `kin` · `party` · `craft` · `menugrid` · `flowermenu` · `world`

`world` is the one that mixes owners and is placed here deliberately: `gob():list()` is **what this
character can see** — two characters in different places see different objects, not because there are
two worlds but because each looks out of its own eyes — and `place`/`select` are that character
acting. Its terrain reads answer for any session that has loaded the ground, which is a property of
having loaded it rather than of the namespace.

## Split — 2

| Namespace | The global half | The per-session half |
|---|---|---|
| `ui` | **your own windows.** `hafen.ui():window()` parents into `LayerRoot` since `074.1` — yours, above everything, surviving every switch | **the client's widgets.** `session:ui():find(selector)` — that character's Inventory, that character's chat |
| `store` | **account scope.** One file for the client, whichever character is up | **per-character scope.** `<genus>_<char>` is one character's folder |

`ui` is the consequence of the layer, and it is not an exception to *one canonical way*: **your window
and the client's window are not the same thing**, and only a single-session client could pretend they
shared a namespace.

---

## Four of the globals are asking the wrong question today

Being global is a statement about the namespace. Four of them **read a session** to answer it, and
that is what `075` repairs. None of their spellings changes.

| Namespace | What it does today | What it must do |
|---|---|---|
| `sound` | `AddonManager.host()` → `u.audio`, at three sites in `LuaSound` | Play through `layer()`. `071`'s `applymute` silences every session that is not drawn, so **an addon's sound played while its session is in the background is muted today**. `UILoop.mkui` hands the layer the same shared `Audio.Root`, and the layer is not a member, so `applymute` never reaches it |
| `log` | `host().msg(...)` | Post through `screen()`. `UI.msg` dispatches a `NoticeEvent`, and **both** trees have a handler for it — every `UI.root` is a `RootWidget`, which is one. What only the drawn session has is a `GameUI`, whose `msg` appends to `syslog`, the chat's *System* channel, besides drawing the timed line the root draws. So a line posted to the layer is **shown and then gone**, with no scrollback anywhere; the drawn session is both where the player is looking and the only tree that keeps what it was told. On the login screen the `System.out` half carries the line alone |
| `time` | `glob().globtime()` through `host()` | Read **any** live session. Every session answers the same clock, so asking the drawn one in particular is arbitrary; `nil` when the client holds none |
| `map` | `MapFile.load(mapstore, mapfilename())` per `GameUI`, uncached | Share one instance per `(mapstore, mapfilename())` — see below |

### The map database is a data hazard, not a classification problem

Verified, and every step of it:

- `MapFile.load` **has exactly one caller in the tree**, `GameUI.java:956`, and it does
  `new MapFile(store, filename)` every time. There is no cache and no shared instance.
- The name is `GameUI.mapfilename()`: the `genus`, plus `/` and the pref `mapfile/<chrid>` **only if
  that pref exists**, which by default it does not.
- So **two characters on one server, with no pref set, name the same directory** — which is what
  running two alts does.
- `MapApi`'s own header says the map file's write lock is held across disk I/O. That lock is **per
  instance**. Two instances over one directory is two locks and no exclusion.
- Nothing disposes a `MapFile`. `GameUI.mapfile` is the **`MapWnd`** (the window), destroyed with the
  session; the database behind it is `mapfile.file` and is simply abandoned.

So sharing the instance breaks no lifecycle, because there was none to break, and it ends concurrent
writes to one database through two locks.

### And `vr`'s registries were indexed on the wrong thing

`073` put `VrApi.anchored` and `VrApi.free` under `SessionState`, reasoning that they are "keyed on
gob id, which means a different object in the next session" and hold "entities standing at a place in
one session's coordinate frame". **Both halves are wrong, and `075` corrects them**: `docs/client/
multi-session.md` records that gob ids are global — *"one object observed by two sessions"* — and a
free entity holds a grid id, which is the server's. An entity stands somewhere in the world; it does
not belong to whoever happened to put it there.

## What each later feature moves

| Feature | Family | `hafen.*()` occurrences in `docs/` |
|---|---|---|
| `075` | none — the globals are repaired without changing a spelling | 0 moved |
| `076` | `hafen.session()`, plus `world` and `player` | 159 |
| `077` | the character: the other 14 | 195 |
| `078` | the interface: `ui` and `store` split, and `host()`'s argument | 285 |

**211 occurrences never move**: `timer`, `event`, `slash`, `asset`, `json`, `http`, `font`, `client`.
That number is the guardrail for every feature after this one — it staying at 211 is what catches a
cut that moved too much.
