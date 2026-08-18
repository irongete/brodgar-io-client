# 076 — plan

## Approach

Five tasks. The first builds the address, the second gives it to the events that already announce
sessions, the third moves the first two namespaces onto it and hard-cuts the old spellings, the fourth
sweeps the docs the cut invalidates, and the fifth gives `move` the reach the address makes askable.

### The Session is a ref, and it wraps the account name

`LuaGob` is the shape, exactly: it wraps **only the id** and re-resolves on every call, so a handle to
something gone answers `nil` and reports `:exists()`. A `LuaSession` wraps only the **account name** and
re-resolves through `Sessions.members()`. That is what makes criterion 2 free: the ref a
`SessionDestroyed` handler is given still answers `:user()`, because the name is the ref, and everything
else answers `nil`.

The account and not the character: `Sessions.Member.chr` is what `:session add` asked for and may be
null, and picking another character keeps the session alive — the server hands it a new world rather
than ending it. So `:character()` reads `GameUI.chrid` off that session's own HUD, and the key stays
the account.

`hafen.session()` is a `LuaCollection` over `Sessions.members()`, with `:current()` for
`Sessions.anchormember()` — the distinguished-member verb the grammar already has beside
`:selected()`. Interning is per addon, like every other ref.

### The session travels with the ref, and stops at the Position

The rule this feature establishes, and `077`/`078` inherit: **a ref that names one character's things
carries its session; a Position does not.** A Position is already the type that means *a place,
answerable in whichever session you ask* (045.1) — a grid id the server published and an offset inside
it. Giving it a session would make two Positions for one patch of ground.

So `LuaGob` grows its session beside its id and `Addon.gobs` keys on the pair, which is decision **B**:
a read taken on the session you named answers about that character. What is lost is cross-session `==`,
which nothing shipped can ask for: there is one door onto a gob today. `g:id()` is the identity
that crosses characters, and `gob.md` says *two Gobs for the same id **in one session*** afterwards.

`gob:position()` derives its durable anchor through **its own** session's `MCache`, handing back an
ordinary session-free Position.

### `move` gains reach, not a verb

`CharApi.installPlayer`'s `move` does four things — the `player.move` permission, `LuaPosition.worldArg`,
`screenView()`, and `m.wdgmsg("click", …)`. Under an address the same four take *that* session. The
refusal is the one it already has, with a new subject: a Position that character cannot locate.

The send differs by one word and it is observable: the drawn session sends through `UI.wdgmsg`, so an
addon's action hooks see the order exactly as they see a real click; a background one sends through
`UI.rawWdgmsg`, because that hook chain belongs to the anchor and knows nothing about the character
being walked. `Sessions.send` already draws that line for the client's own orders, and the page says it.

## Files to create and modify

| File | Task | What |
|---|---|---|
| `src/io/brodgar/addon/LuaSession.java` | 1 | the ref: the account name, re-resolved, interned per addon |
| `src/io/brodgar/addon/SessionApi.java` | 1, 3, 5 | the collection, and the Session's verbs |
| `src/io/brodgar/addon/AddonManager.java` | 1–3, 5 | the install site, `getgob`'s session, the event payloads |
| `src/io/brodgar/addon/Addon.java` | 1, 3 | the intern caches: sessions, and gobs keyed on the pair |
| `src/io/brodgar/addon/LuaGob.java` | 3 | the ref carries its session |
| `src/io/brodgar/addon/WorldApi.java` | 3 | `installWorld` becomes a Session verb |
| `src/io/brodgar/addon/CharApi.java` | 3, 5 | `installPlayer` likewise, and `move`'s reach |
| `src/io/brodgar/addon/LuaPosition.java` | 3, 5 | the two derivation sites take a session |
| `src/io/brodgar/addon/Retired.java` | 3 | `hafen.world`, `hafen.player`, and their verbs |
| `src/io/brodgar/session/Sessions.java` | 1, 5 | a member by name; the raw send for a background order |
| `docs/addons/api/session.md` | 1 (writes), 3, 5 | the collection, the object, the handle across a switch |
| `docs/addons/api/event/bus.md` | 2 | the four payloads |
| `docs/addons/api/world.md`, `player.md` | 3, 5 | the same surfaces, addressed |
| `docs/addons/api/gob.md` | 3 | identity within a session, and `:id()` across |
| `docs/addons/api/README.md`, `conventions.md` | 1, 3 | the index line, and the door |
| everything else the derived set names | 4 | the sweep |

## Risks and gotchas

- **`WorldApi.installWorld` mints its collections once for identity** — the comment says a draw callback
  runs 60×/s and must allocate nothing. Per session they must be minted once per *(addon, session)* and
  hung on the interned `LuaSession`, or `s:world():gob() == s:world():gob()` fails and every frame allocates.
- **`Addon.gobs` is `LuaGob.Cache`**: weak values plus a `ReferenceQueue` drained on access, because a
  per-tick world sweep sees tens of thousands of ids. Keying on the pair must keep that shape.
- **`AddonManager.getgob(id)` is ambient** — `oc()` → `glob()` → `screen()`. Every gob read goes through
  it, and it is the one function that grows a session in task 3.
- **A member's `ui` is null in the gaps.** `Sessions.Member.run` clears it before the UI is taken down and
  during a character handoff, so every Session verb answers `nil`-shaped there rather than throwing.
- **`Retired.closedIndex("hafen.player()", …)`** makes the Player section's vocabulary closed; it moves
  with the object, or an unknown verb reads plain `nil` again.
- **The payload is per addon.** `AddonManager.fireGob` mints `LuaGob.of(owner, id)` for each addon; the
  session payload does the same. One shared object would cross a sandbox boundary.
- **`:current()` changes under a stored variable.** The page teaches taking it inside the handler.

## Discarded alternatives

- **Keeping `hafen.player()` as sugar for the current session** — two spellings for one thing, and the
  difference between them would be *who is on screen*, which is the very reading this feature deletes.
- **`hafen.player(user)`** — a section takes no arguments; it would be the only one that does, and it
  says what `:get(user)` already says.
- **A Gob ref that is the id alone** — with no session on the ref, a read taken on the session you named
  answers about the drawn one, which is the bug this feature exists to close.
- **A Gob ref carrying its session, with `__eq` by id** — Lua table keys use primitive equality, not
  `__eq`, so `seen[gob]` counts one object twice while `gob1 == gob2` says it is one. Two equalities that
  disagree is worse than either alone.
- **`session:order(p)` beside `session:player():move(p)`** — two spellings for *walk this character
  there*. A client with several logins adds reach to that verb; it does not add a verb.
- **Keying a session by character name** — one account plays one character at a time, and choosing
  another keeps the session alive. The account is what survives that; the character is what changes.
- **Giving `LuaPosition` a session** — it already means a place answerable in whichever session is asked,
  so a session on it makes two Positions for one patch of ground.
- **Sweeping the docs inside task 3** — 42 pages against a surface still being shaped is a sweep run
  twice; it is its own task, and the close of task 3 charges it.
