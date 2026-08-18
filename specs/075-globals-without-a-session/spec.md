# 075 — the global namespaces stop asking for a session

## What and why

`074` lifted the addon layer above the sessions: it loads once, never reloads, is drawn over whichever
session is on screen and over the login screen when there is none. What it still lacks is the address
— `hafen.session()` — and the surface still spells everything as though the client had one login.

Before any of that moves, **thirteen namespaces have to be recognised as never having been a
character's**, and four of them have to stop reading one. That is this feature. It is deliberately
first because it changes **no spelling at all**, is independent of the addressing work, and fixes two
defects that bite today.

### The classification, decided once

`namespaces.md` in this folder is the deliverable every later feature reads instead of re-deriving:
all thirty-one namespaces, checked both ways against the mount calls and the pages, with the verdict
**global**, **per session** or **split** and the evidence for each.

The question it applies is not *what does this read today* — today everything reads the drawn session,
because there has only ever been one. It is **what does the thing it names belong to**: the addon, the
client, the world, or one character.

Two verdicts moved after reading the source rather than the shape:

- **`vr` is global.** A free entity holds *a grid id and an offset within it* — the server's, meaning
  the same place in every session — and an anchored one follows a gob, whose id
  `docs/client/multi-session.md` records as global, *"one object observed by two sessions"*. You stand
  a thing in the world and it should draw where it is. `073` put its registries under `SessionState`
  on the reasoning that gob ids differ between sessions; that reasoning was wrong and this feature
  corrects it.
- **`map` is global.** The recorded database is addressed by *the id the server published*, and there
  is one database per `(mapstore, mapfilename())` on disk.

### The two defects

**An addon's sound is muted today when its session is not drawn.** `LuaSound` reads
`AddonManager.host()` → `u.audio` at three sites, and `071`'s `applymute` silences every session that
is not on screen. The layer has the same shared `Audio.Root` and is not a member, so nothing mutes it.

**Two alts corrupt one map database.** `MapFile.load` has exactly one caller — `GameUI.java:956` —
and constructs a new instance every time; `GameUI.mapfilename()` is the `genus` plus a per-character
pref that by default does not exist, so two characters on one server name **the same directory**. The
write lock `MapApi` documents is per instance, so two instances over one directory is two locks and no
exclusion. Nothing disposes a `MapFile` — what `GameUI` destroys is the `MapWnd` — so sharing one
instance per `(mapstore, mapfilename())` breaks no lifecycle, because there is none.

## Acceptance criteria

1. `namespaces.md` exists, classifying all thirty-one namespaces with the evidence for each, and names
   which later feature moves which family.
2. `hafen.sound()` plays through the addon layer, so a sound is **audible whichever session is
   drawn** — including one played while its own session is in the background.
3. `hafen.log()` posts through the drawn session, and still writes its `System.out` half when the
   client holds no session at all.
4. `hafen.time()` answers from any live session rather than the drawn one in particular, and `nil`
   when there is none.
5. One `MapFile` per `(mapstore, mapfilename())`, shared by every session that names it. Two
   characters on one server read and write **one** database through **one** lock.
6. `hafen.vr()`'s registries are not indexed by session: an entity holds its own place, and is drawn
   whenever a session that can see that place is on screen.
7. A console command an addon registers is reachable from **every** session's console and from the
   layer's.
8. **No `hafen.*` spelling changes.** Every namespace is called exactly as it is called today; what
   changes is what four of them read and what two of them are indexed on.

## Out of scope

Named, because none of it may be left unowned:

- **`076`** — `hafen.session()` as a collection and a Session object, plus `world` and `player` moved
  onto it, the order verb, and the session events carrying a Session instead of an account name.
- **`077`** — the character family: `char`, `meter`, `buff`, `study`, `quest`, `wound`, `fight`,
  `actionbar`, `speed`, `kin`, `party`, `craft`, `menugrid`, `flowermenu`.
- **`078`** — the interface: `ui` split between the layer and the session, `store` split between
  account and character scope, and `AddonManager.host()` growing its session argument.
- The `Fonts` subsystem.

## Docs impact

**Written**: `api/sound.md`, `api/log.md`, `api/time.md`, `api/slash.md`, `api/map/**` and
`api/vr/**` — their spelling is unchanged and what they do is not, so each says what it now answers
and, for `map` and `vr`, that it is one thing for the client rather than one per character.

**Derived set — the guardrail, and it is the whole test of this feature:**

```
grep -rn "hafen\.[a-z]*()" docs/ | wc -l
```

**1070 occurrences, and the count must be identical after.** This feature moves no namespace, so any
change in that number is a spelling this feature had no business touching.

A second grep says the same thing from the other side:

```
grep -rn "hafen\.\(sound\|log\|time\|map\|vr\)()" docs/
```

**258 occurrences**, and every one must still read exactly as it does now. What changes on those pages
is prose about behaviour, never a call.

## Context files

- `specs/075-globals-without-a-session/namespaces.md` — 1 (writes), 2–7 (read)
- `src/io/brodgar/addon/LuaSound.java` — 2
- `src/io/brodgar/addon/AddonManager.java` — 2, 3, 4, 5, 6
- `src/io/brodgar/addon/WorldApi.java` — 4
- `src/io/brodgar/addon/MapApi.java` — 5
- `src/io/brodgar/addon/LuaMarker.java` — 5
- `src/io/brodgar/addon/VrApi.java` — 6
- `src/io/brodgar/addon/LuaWorldEntity.java`, `LuaGhost.java`, `LuaObject.java` — 6 (a kind's own visual)
- `src/io/brodgar/addon/HookApi.java` — 7
- `src/haven/MapFile.java` — 5
- `src/haven/GameUI.java` — 5
- `src/haven/UILoop.java` — 2, 7
- `src/haven/UI.java` — 3, 7
- `src/io/brodgar/session/Sessions.java` — 4, 6
- `specs/073-caches-know-their-session/census.md` — 6 (reads and corrects)
- `docs/addons/api/sound.md` — 2
- `docs/addons/api/log.md` — 3
- `docs/addons/api/time.md` — 4
- `docs/addons/api/slash.md` — 7
- `docs/addons/api/map/**` — 5
- `docs/addons/api/vr/**` — 6
- `docs/client/multi-session.md`, `docs/client/world-3d.md` — 6 (the map toll)
- `DOCUMENTATION.md` — 5, 6
