# 076 — the session is the address

## What and why

`074` lifted the addon layer above the sessions and `075` recognised which namespaces were never a
character's. What the surface still lacks is the **address**: sixteen namespaces name one character's
state and every one of them is spelled as though the client had one login, answering for whichever
character happens to be on screen. An addon watching two characters cannot say which one it means.

This feature adds the address and moves the first two namespaces onto it. `hafen.session()` is a
collection of **Session** objects keyed by the account name — the string `:session list` prints and the
one the session events already carry — with `:current()` for the one on screen. `world` and `player`
become verbs on a Session, and their old spellings are hard-cut into the refusal table. `077` moves the
other fourteen and `078` splits `ui` and `store`; both read `namespaces.md` in
`specs/075-globals-without-a-session/`, which decided this classification and this spelling, and
neither can start before the address exists.

The Session also gets **reach**, and no new verb: `session:player():move(p)` is today's call under an
address, and what changes is whose map resolves the Position and whose socket carries the click. The
client already walks a character it is not drawing for its own RTS orders; no addon can ask for it.

## Acceptance criteria

1. `hafen.session()` is a collection with `:list(filter)`, `:count(filter)`, `:find(filter)`,
   `:get(user)` and `:current()`. `:current()` is `nil` on the login screen. Members are interned, so
   `==` and a table key work, and the collection is the same object every call.
2. A Session answers `:user()`, `:character()`, `:exists()` and `:info()`. One whose session has ended
   still answers `:user()` — it is the key a handler drops its tables by — and `:exists()` is `false`.
3. `SessionAdded`, `SessionEnteredWorld`, `SessionSelected` and `SessionDestroyed` carry a **Session**
   rather than a name string. The one `SessionDestroyed` carries reports `:exists() == false`.
4. `session:world()` and `session:player()` answer **for that session**, whichever one is drawn: a read
   taken on a background session reports that character's world, not the screen's.
5. Both are interned per session: `s:world() == s:world()`, and `s:world():gob() == s:world():gob()`.
6. A Gob read through a session answers about **that** character — where it is, and whether that
   character sees it at all. Within one session `==` and table keys work exactly as they do today;
   across two, the same id is two refs and `g:id()` is the identity that crosses them.
7. `hafen.world` and `hafen.player` throw naming their replacement, in every spelling the retirement
   table reaches. No page under `docs/` teaches either.
8. `session:player():move(p)` walks a character that is **not** on screen — same signature, same
   `player.move` permission, same chaining return. The refusal it already has is the one that
   changes subject: a Position **that character** cannot locate raises, naming it. Nothing else this
   feature adds is protected.

## Out of scope

Named, because none of it may be left unowned:

- **`077`** — the character family: `char`, `meter`, `buff`, `study`, `quest`, `wound`, `fight`,
  `actionbar`, `speed`, `kin`, `party`, `craft`, `menugrid`, `flowermenu`.
- **`078`** — `ui` split between the layer and the session, `store` split between account and character
  scope, and `AddonManager.host()` growing its session argument.
- **Creating or ending a session from Lua** (`:add(user, char)`, `session:drop()`). Connecting an
  account holds a saved token and a login; which permission tier that sits behind is its own question.
- **Taking the screen** (`hafen.session():current(s)` as a write). The charter is reading and ordering.
- **Every other bus event carrying its session** — `GobAdded`, `MeterChanged` and the rest still
  broadcast with no session in them, which `specs/ROADMAP.md` files (filed: 074).

## Docs impact

**Written**: `api/session.md` (new — the collection, the Session object, and what a handle across a
switch means), `api/world.md` and `api/player.md` (the same surfaces, addressed through a session),
`api/event/bus.md` (the four payloads), `api/README.md` and `api/conventions.md` (the new door).

**Derived set — every page that spells either namespace:**

```
grep -rn "hafen\.world()\|hafen\.player()" docs/ | wc -l     ->  159
grep -rl "hafen\.world()\|hafen\.player()" docs/ | wc -l     ->   42
```

**159 lines over 42 pages** — the same figure `namespaces.md` derived for this feature from the other
side, which is what says the two agree about what moves. Every one of them is a spelling this feature
changes, so the count after is **0**: criterion 7 is that grep returning nothing.

A second guardrail, from `namespaces.md`: the eight namespaces that never move
(`timer`, `event`, `slash`, `asset`, `json`, `http`, `font`, `client`) hold **211** occurrences before
and after.

## Context files

- `specs/075-globals-without-a-session/namespaces.md` — 1–5 (the classification, read never re-derived)
- `src/io/brodgar/session/Sessions.java` — 1, 2, 4
- `src/io/brodgar/addon/AddonManager.java` — 1, 2, 3, 4
- `src/io/brodgar/addon/Section.java` — 1, 3
- `src/io/brodgar/addon/LuaCollection.java` — 1
- `src/io/brodgar/addon/LuaKin.java` — 1 (the interned-ref shape to copy)
- `src/io/brodgar/addon/LuaSession.java` — 2, 3, 5 (the Session ref: the account name, interned per addon)
- `src/io/brodgar/addon/SessionApi.java` — 3, 5 (the collection, and where a Session's verbs are mounted)
- `src/io/brodgar/addon/Retired.java` — 3
- `src/io/brodgar/addon/WorldApi.java` — 3 (`world(owner, user)`, built per session by `LuaSession`)
- `src/io/brodgar/addon/CharApi.java` — 3, 5 (`player(owner, user)` and its `PlayerMark` alone)
- `src/io/brodgar/addon/LuaGob.java` — 3
- `src/io/brodgar/addon/LuaPosition.java` — 3, 5 (`worldArg(…, user)` is the door a session verb uses)
- `src/io/brodgar/addon/LuaHand.java` — 3 (the cursor is per session; `use` sends)
- `src/io/brodgar/addon/LuaOverlay.java` — 3 (keyed on the gob id alone, which is why it stays the screen's)
- `src/io/brodgar/addon/MapApi.java` — 3 (`sessloc`/`mapfile`/`gridUL` grew their session forms)
- `src/io/brodgar/addon/Permission.java` — 5 (`player.move`, and the verb spelling a refusal names)
- `src/io/brodgar/addon/Addon.java` — 1, 3 (the per-addon intern caches)
- `src/haven/GameUI.java`, `MapView.java` — 3 (`plid`/`chrid` vs `plgob`: which player-id read is session-correct)
- `docs/client/multi-session.md` — 1, 5 (`Sessions.send`, and `wdgmsg` vs `rawWdgmsg`)
- `docs/client/glossary.md` — 3, 5 (`plgob`/`plid`, and which of the two arrives first)
- `docs/addons/api/session.md` — 1 (writes), 2–5
- `docs/addons/api/world.md` — 3
- `docs/addons/api/player.md` — 3, 5
- `docs/addons/api/gob.md` — 3 (identity within a session, and `:id()` across)
- `docs/addons/api/README.md` — 3, 4 (the index line of every page the sweep retitles)
- `docs/addons/api/event/bus.md` — 2
- `docs/addons/api/conventions.md` — 1, 3
- `DOCUMENTATION.md` — 1, 3, 5
