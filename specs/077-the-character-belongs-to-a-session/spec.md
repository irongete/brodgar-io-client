# 077 — the character belongs to a session

## What and why

`076` gave the client the address. `hafen.session()` is a collection of live sessions, a `LuaSession`
is interned per `(addon, account)`, and `s:world()` and `s:player()` hang off it — lazily built,
interned on the handle, every verb reading **that** session. `hafen.world()` and `hafen.player()` are
retired and no page teaches them.

**This feature repeats that fourteen times.** The character family — everything that names one
character's own state — moves onto the session, its loose spellings are retired, and its pages are
rewritten in the same movement, so the tree is consistent at every boundary and there is never a
window in which two spellings both work.

| Section | Occurrences in `docs/` | | Section | Occurrences |
|---|---|---|---|---|
| `char` | 31 | | `meter` | 13 |
| `menugrid` | 31 | | `buff` | 10 |
| `kin` | 21 | | `fight` | 10 |
| `actionbar` | 15 | | `study` | 8 |
| `speed` | 15 | | `quest` | 8 |
| `flowermenu` | 15 | | `wound` | 8 |
| | | | `party` | 8 |
| | | | `craft` | 3 |

**This is the most mechanical feature of the sequence and the one that decides least.** `076` built
`SessionApi`, `LuaSession`, the bound-section pattern and the retirement mechanism; `073` already made
`CharApi`'s nine adapters per session. Almost all of the work is repetition, and saying so is not
modesty — it is what tells the implementing context that a surprise here is a signal rather than a
detail.

### The two things that are not repetition

**A window on a background session is open, and readable.** `craft`, `menugrid` and `flowermenu` are
not data readers — they are windows the game put up. A session that is not drawn still has its
`GameUI`, so its recipe window exists and can be read, which is precisely what makes a crafting addon
worth writing across characters. `flowermenu` looks like an exception because a right-click is a mouse
gesture and there is one mouse — but the page's own first line settles it: *"`hafen.flowermenu()` **is**
the open menu"*. The menu is a widget in a session's tree, not the gesture that raised it. One opened
in a session you then tabbed away from is still open, still readable, and still selectable.

**The permission keys do not multiply.** `kin.add`, `kin.rename`, `kin.group`, `kin.endKin`,
`kin.forget`, `actionbar.use`, `actionbar.res`, `speed.set`, `craft.make`, `menugrid.use`,
`flowermenu.select` and `flowermenu.cancel` all become addressable, and each keeps the one key it has.
`conventions.md` says a verb is protected when it *starts an action the player could have performed* —
and the player could have tabbed to that character and performed it. **The key names the action, not
the target.** A second grant per session would mean an addon the user allowed to add kin cannot add
kin on an alt, which is a distinction the user never drew: every one of those characters is theirs.
What does change is the consent dialog's wording, which says *your* kin list and *your* action bar and
must now be read as covering every character the client holds.

## Acceptance criteria

1. All fourteen sections answer as `session:x()` — `hafen.session():current():x()` and
   `:get(user):x()` — bound to that session, interned on the handle as `076` interns `world` and
   `player`.
2. Each reads **for the session it was reached through, including one that is not drawn**:
   `hafen.session():get("<other account>"):meter():list()` answers that character's bars.
3. All fourteen loose spellings are **retired**, throwing at the line that wrote them and naming the
   replacement. `grep -rn "hafen\.\(char\|meter\|buff\|study\|quest\|wound\|fight\|actionbar\|speed\|kin\|party\|craft\|menugrid\|flowermenu\)()" docs/` returns **nothing**.
4. Every protected verb in the family keeps its existing permission key and works when addressed at
   another session. An addon that did not declare the key is refused **naming the verb and the key,
   before anything reaches the server**.
5. A window the game put up on a background session — a recipe window, an action menu, an open radial
   menu — is readable through its session, and its protected verbs act on it.
6. The guardrail holds: `grep -rn "hafen\.\(timer\|event\|slash\|asset\|json\|http\|font\|client\)()" docs/`
   is **212 before and 212 after**.

## Out of scope

- **`078`**, which closes the sequence: `ui` split between the layer and the session, `store` split
  between account and character scope, and `AddonManager.host()` growing the session argument `072`
  separated it for.
- The `Fonts` subsystem.

## Docs impact

**Written**: the fourteen pages — `api/char.md`, `api/meter.md`, `api/buff.md`, `api/study.md`,
`api/quest.md`, `api/wound.md`, `api/fight.md`, `api/actionbar.md`, `api/speed.md`, `api/kin.md`,
`api/party.md`, `api/craft.md`, `api/menugrid.md`, `api/flowermenu.md` — plus every cross-cutting page
whose examples call them: `api/README.md`, `api/conventions.md`, `api/references.md`, `api/types.md`,
`api/event/bus.md`, `api/ui/custom.md`, `getting-started.md`, `examples.md`,
`guides/reading-the-world.md`, `guides/permissions.md` (the consent wording above),
`guides/events-and-timers.md`.

**Derived set — the checklist:**

```
grep -rn "hafen\.\(char\|meter\|buff\|study\|quest\|wound\|fight\|actionbar\|speed\|kin\|party\|craft\|menugrid\|flowermenu\)()" docs/
```

**195 occurrences.** Every one is a spelling this feature retires, so every one is rewritten rather
than sampled, and the command returning nothing is how the sweep is known to be complete.

**Derived set — the guardrail:**

```
grep -rn "hafen\.\(timer\|event\|slash\|asset\|json\|http\|font\|client\)()" docs/
```

**212 occurrences, and the count must be identical after.** A cut this size fails by moving too much,
and this is the number that catches it.

## Context files

- `specs/075-globals-without-a-session/namespaces.md` — 1, 2, 3, 4 (read: the classification)
- `src/io/brodgar/addon/LuaSession.java` — 1, 2, 3, 4
- `src/io/brodgar/addon/SessionApi.java` — 1
- `src/io/brodgar/addon/CharApi.java` — 1, 2, 3
- `src/io/brodgar/addon/AddonManager.java` — 1, 2, 3, 4 (the mount leaves `installHafen`; a `fire*` helper
  that mints an entity whose key counts inside one character grows a `user` argument)
- `src/io/brodgar/addon/FlowerMenuApi.java` — 4
- `src/io/brodgar/addon/BeltHold.java` — 3
- `src/io/brodgar/addon/AddonPagina.java` — 3
- `src/io/brodgar/addon/Permission.java` — 2, 3, 4
- `src/io/brodgar/addon/Retired.java` — 1, 2, 3, 4

**The section file is not where the work is — the ENTITY file is.** Each section's
`LuaX.collection(owner)` grows the account, and so does the entity's own intern cache wherever its key
counts inside one character alone: an id, a name or a token is two different things on two characters, so
the cache becomes two levels of map on `(account, key)` — `LuaGob`'s shape. An entity keyed on the
**widget** needs no such key, and answers `:exists()` by walking up from the widget instead. Each task
opens its own:

- `src/io/brodgar/addon/LuaKin.java`, `LuaPartyMember.java` — 2
- `src/io/brodgar/addon/LuaGob.java` — 2, 3, 4 (a Gob MINTS other sections' entities: `gob:kin()` hands
  back a Kin, so re-keying an entity on `(account, key)` breaks the call site here too — grep
  `Lua<Entity>.of(` across the package before assuming the entity file is the whole of it)
- `src/io/brodgar/addon/LuaSlot.java`, `LuaSpeed.java`, `ActApi.java`, `LuaCraft.java`,
  `LuaPagina.java` — 3 (`menugrid` is mounted from `AddonManager` over `LuaPagina.collection`)
- `src/io/brodgar/addon/LuaManeuver.java`, `LuaDeckCard.java`, `LuaFightSummary.java`,
  `LuaOpponent.java` — 4
- `src/io/brodgar/addon/WorldApi.java` — 1 (read: the `076` factory shape)
- `src/io/brodgar/session/Sessions.java` — 1
- `docs/addons/api/conventions.md` — 1, 2, 3, 4
- `docs/addons/guides/permissions.md` — 2, 3, 4
- `DOCUMENTATION.md` — 1, 2, 3, 4
