# 077 — plan

## Approach

Four tasks, grouped by affinity rather than one per section, so each one is a coherent session's work
and the two things that are not repetition land where they belong.

**The pattern is `076`'s and is not re-invented here.** `LuaSession` holds a lazily built, interned
handle per namespace — `worldObj`, `playerObj` — created through a factory that takes the owner and
the account (`WorldApi.world(owner, h.user)`), so `s:world() == s:world()` comes out of interning the
handle rather than out of a cache anyone maintains. Every verb underneath reads **that** session.
Fourteen more fields, fourteen more factories, the same shape.

`073` already did the half that would have been hard: `CharApi`'s nine adapters — meters, buffs, fep,
study, actionbar, equip, kin, quest, wound — are per session inside `SessionState`, reached through
`AddonManager.state(UI)`. So a bound section is handed its `UI` and reads the adapters that were
already waiting for it.

Retirement is `Retired.sectionObj(name, verbs…)`, as `076` retired `world` and `player`: the loose
spelling throws at the line that wrote it and names its replacement. Nothing is aliased.

### What each task carries beyond the repetition

- **`077.1` — the sheet.** Six read-only sections and no protected verb among them, which makes it the
  right place to establish the repeat: if the pattern does not fit six times cleanly, that is known
  before any write verb is addressed.
- **`077.2` — the roster.** The first protected verbs to become addressable, and where the permission
  decision is written down with its reason: **the key names the action, not the target.** A player
  could have tabbed to that character and done it, which is `conventions.md`'s own test for what
  protection means. `guides/permissions.md`'s consent wording — *your* kin list — is revised to be
  read across every character the client holds.
- **`077.3` — the verbs that act.** `actionbar`, `speed`, `craft`, `menugrid`, the densest group and
  the one with the most keys. `BeltHold`'s holds and `AddonPagina`'s entries are already per session
  from `073`, so what moves is the address, not the bookkeeping.
- **`077.4` — the menu and the fight.** Where `flowermenu` is settled: the page's own first line says
  the section **is the open menu**, which is a widget in a session's tree rather than the right-click
  that raised it. So a menu opened in a session you then left is still open, readable and selectable,
  and `flowermenu.select`/`cancel` are meaningful at a distance.

### The window sections are not readers, and it matters

`craft`, `menugrid` and `flowermenu` report what the game has put up rather than what a character is.
A background session keeps its `GameUI`, so those windows exist and answer — which is the whole reason
an addon addressing them across characters is worth writing. Where each of the three has nothing open,
it answers the same nothing it answers today for a session with nothing open, and the pages say so
rather than inventing a new absence.

## Files to create and modify

| File | Task | What |
|---|---|---|
| `src/io/brodgar/addon/LuaSession.java` | 1, 2, 3, 4 | fourteen handles, interned as `worldObj` is |
| `src/io/brodgar/addon/CharApi.java` | 1, 2, 3 | the factories: `char`, `meter`, `buff`, `study`, `quest`, `wound`, `fight`, `actionbar`, `speed`, `kin`, `party`, `craft`, `menugrid` |
| `src/io/brodgar/addon/FlowerMenuApi.java` | 4 | the `flowermenu` factory |
| `src/io/brodgar/addon/BeltHold.java` | 3 | reached per session already; the address changes |
| `src/io/brodgar/addon/AddonPagina.java` | 3 | the same for the action menu's own entries |
| `src/io/brodgar/addon/Retired.java` | 1, 2, 3, 4 | fourteen `sectionObj` retirements |
| `src/io/brodgar/addon/Permission.java` | 2, 3, 4 | unchanged keys; the descriptions read across characters |
| `docs/addons/api/{char,meter,buff,study,quest,wound}.md` | 1 | |
| `docs/addons/api/{kin,party}.md` | 2 | |
| `docs/addons/api/{actionbar,speed,craft,menugrid}.md` | 3 | |
| `docs/addons/api/{flowermenu,fight}.md` | 4 | |
| `docs/addons/guides/permissions.md` | 2 | the consent wording across characters |
| `docs/addons/api/{README,conventions,references,types}.md`, `api/event/bus.md`, `getting-started.md`, `examples.md`, `guides/{reading-the-world,events-and-timers}.md` | 1–4 | the cross-cutting examples, each task taking its own |

## Risks and gotchas

- **The two greps are the feature.** The checklist starts at 195 and must reach 0; the guardrail is
  212 and must still be 212. Run both at the start and end of every task, not only at the close.
- **`LuaSession` interns on `(addon, account)` and re-resolves everything else on every call**, because
  `Sessions.placed()` is rebuilt on the tick and answers `unpublished` off it (`070.2`). A handle may be
  kept; a `Placed` may not.
- **A section on a session that has not reached the world has no `GameUI`**, so its reads answer the
  same nothing they answer today before login rather than throwing — `076`'s `:world()` already answers
  *nil-shaped rather than throwing*, and these follow it.
- **`Retired.sectionObj` needs every verb of the retired section listed**, or a call to a verb it
  missed reads as plain `nil` instead of throwing. `076`'s `world` and `player` entries are the shape;
  derive each list from the page rather than from memory.
- **`craft`, `menugrid` and `flowermenu` answer about a window that may not be open**, on the drawn
  session as much as on a background one. Nothing new is invented for the background case.
- **`ant hafen-client` is incremental**; `rm -rf build/classes` first, and especially after a
  retirement, whose whole point is that an old caller stops working.

## Discarded alternatives

- **One task per section** — fourteen sessions of near-identical work, each re-establishing the same
  context to move eight or thirty occurrences. Grouped by affinity, each task is one coherent surface
  and the two real decisions land in the task that owns them.
- **A second permission key for acting on a session that is not drawn** — the key names the action,
  not the target, and `conventions.md` defines protection as *an action the player could have
  performed*. The player could have tabbed to that character. A per-session grant would mean an addon
  the user allowed to add kin cannot add kin on an alt, a distinction the user never drew: the
  characters are all theirs.
- **Treating `flowermenu` as screen-shaped because a right-click is a mouse gesture** — the section is
  the open **menu**, not the gesture, and the menu is a widget in one session's tree. A menu left open
  in a session you tabbed away from is still open; calling it the screen's would make it unreadable
  for no reason.
- **Making the window sections refuse on a background session** — a recipe window on an alt is exactly
  what a cross-character crafting addon needs, and the session keeps its `GameUI` whether or not it is
  drawn. Refusing would invent an absence the client does not have.
- **Keeping the loose spellings as shorthand for the drawn session** — two spellings for one
  operation, forbidden by this API's grammar and already decided against for `world` and `player` in
  `076`; a family that kept them would be the only inconsistent corner of the surface.
