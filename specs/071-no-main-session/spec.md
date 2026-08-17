# 071 — no main session

## What and why

The client holds several game sessions and draws one. One of them is different from the others for a
reason that is purely historical: it is the one `Client.Main`'s runner chain produced, so it lives in
`UILoop.ui` rather than in `Sessions.members`, and every per-session mechanism has a second copy
written for it.

```
Sessions.mainoff / mainoffanchor / mainoffglob / mainofftry / tickmainoffset()
                                                   ↔  Member.tickoffset() / Member.offset()
Sessions.mainguiof() / mainguifor / mainguicache   ↔  Member.gameui() / guifor / guicache
Sessions.tickbg(UI)                                ↔  the members loop in Sessions.tick()
Sessions.anchor(null) meaning "the main one"       ↔  anchor(Member)
Sessions.next()'s distinguished first stop         ↔  the members cycle
Sessions.drop(user), which cannot reach it         ↔  Member.drop()
Sessions.dormant(Glob)'s explicit mainui() branch  ↔  its members loop
buildplaced()'s two branches                       ↔  one Placed per session
```

**Two lists where there should be one is not a tidiness complaint — it manufactures defects.**
`070`'s D1 was exactly this: `Sessions.groundz` iterated `members`, so it never asked the main
session, and ground the main session alone had loaded was unreachable. It was fixed by routing
through `placed()`. Every lookup written from here has the same branch to forget.

**And it would ship into the API.** `buildplaced()` names the main session with the literal `"main"`
as `Placed.user`, while every other carries its account name. The later `hafen.session()` feature
publishes `Placed`, so `s:user()` would answer `"main"` for one member and an account for the rest,
and `hafen.session():get("main")` would be a special case every addon author inherits. That is the
deadline: this lands before that surface exists, or it never lands cheaply again.

### The direction the code already chose

`Placed` is already the equality abstraction, and its own docstring says why it exists:

> *"Introduced because the anchor stopped being the main session. (…) the special case turns into a
> character that cannot be seen, selected or ordered the moment you tab away from it. **One uniform
> list, the anchor being simply the session whose offset is zero.**"*

Everything reaching `placed()` already treats them as equals. This feature carries that from the
**read** layer down to the **ownership** layer, and deletes the second copy.

### What replaces the asymmetry

`Client.Main.run` is an endless `while(true)` that cycles `Bootstrap` → `RemoteUI` → `Bootstrap`,
each step through `UILoop.newui`, which **replaces `UILoop.ui` and destroys the previous one**. That
slot is what makes its session special.

The resolution is that **the slot stops being a game session at all**: `UILoop.ui` becomes the
*login* UI, `Sessions` owns every game session uniformly, and `drawn()` answers the session holding
the screen — or the login screen when there is none. `UILoop.bgui` already builds a session UI that
replaces and destroys nothing, which is what every session then uses.

That turns the one irreducible asymmetry into something that is not an asymmetry at all. *"The client
cannot close its last session"* becomes **"closing your last session returns you to the login
screen"** — which is what logging out already does. Nothing has to enforce a rule about a privileged
member, because there is no privileged member: there is a login screen, and there are sessions.

## Acceptance criteria

1. `Sessions` holds **one list**. `placed()`, `dormant()`, `stats()`, `next()`, `drop()` and
   `anchor()` each read it once, with no branch for a session that arrived differently.
2. `Placed.user` is an **account name for every entry**. The literal `"main"` appears nowhere, and
   `Placed.member` is never null.
3. The duplicated machinery is **gone**, not bypassed: `mainoff`, `mainoffanchor`, `mainoffglob`,
   `mainofftry`, `tickmainoffset`, `mainguiof`, `mainguifor`, `mainguicache` and `tickbg` are
   deleted, and `UILoop.run` ticks sessions once rather than twice.
4. `:session drop <any account>` reaches **any** session, the first-logged-in one included. Dropping
   the last live session returns the client to the login screen rather than leaving it with nothing
   drawn.
5. `:session anchor <account>` takes an account name and **no `main` keyword**; cycling with
   `rts-next-anchor` visits every session once, with no distinguished stop.
6. `Sessions.add` refuses an account that is **already live**, whichever way that session arrived.
7. A session dying while it holds the screen hands the screen to another live session, or to the
   login screen when it was the last — never to a slot that no longer holds a game.

## Out of scope

- `hafen.session()` and every later step of the sequence: parameterising `AddonManager.ui`/`.view`,
  the per-session caches, the engine ceasing to reload on a switch, the API cut. This feature adds
  **no `hafen.*` verb** beyond whatever the suites need to read, which the existing
  `hafen.client():profiling():session()` group already provides a home for.
- **A login dialog for a second account.** It falls out of this design almost for free — the login
  slot can produce session two the same way it produced session one — and it is deliberately not
  claimed here: this feature is finished when the sessions are equal, and a new way to create one is
  its own subject. `:session add` and its saved tokens stay the only door.
- The `Fonts` subsystem.

## An open decision for review

`docs/client/multi-session.md` describes the asymmetry throughout and cannot survive this feature
unrevised. It is also already filed on `ROADMAP.md` (filed: 066) for breaking `DOCUMENTATION.md`
§12.3 — `docs/client/**` maps upstream `haven` only, and that page is `io.brodgar` almost end to end.

Rewriting a page that should not exist in that shape, and leaving it in that shape, is the worst of
the three options. **Recommended**: this feature picks the candidate up, and the split follows the
rule rather than inventing an exception — the upstream-`haven` seams (`MapView`'s merged scene, the
pick pass, `UILoop`'s two UIs, `ActAudio` muting) stay on `docs/client/` pages that already own those
subsystems, and the `io.brodgar` policy goes where CLAUDE.md says it already belongs: `Sessions.java`
and `Control.java`'s own javadoc, which carries most of it today. `ROADMAP.md`'s line is then removed
by this feature.

The alternative is to revise the page in place and leave the line standing, which is cheaper and
keeps one readable page about a subsystem that genuinely spans both trees. **This is the maintainer's
call, and `plan.md` records whichever way it goes with its reason.**

## Docs impact

**Written**: `docs/client/boot-and-loop.md` — the runner state machine changes shape, and that page
is the map of it (`Client.run`, `Client.Main`, `UILoop.newui`). Upstream `haven`, so it is the right
home. Plus whichever resolution the decision above reaches for `multi-session.md`.

**Not written**: nothing under `docs/addons/**`. No `hafen.*` verb changes behaviour.

**Derived set:**

```
grep -rniE "\bmain session\b|\bmain runner\b|anchor|\bmember\b|session add|session drop|session anchor" docs/
```

**Result to be pasted by the implementing task before it revises anything** — the grep is run at
implementation time rather than quoted here, because `070.2` revises `world-3d.md` between this plan
and that task, and a result quoted now would be the stale one. What it must catch, known today:
`multi-session.md`'s `:session anchor USER|main` row, its `tickbg` row, its dormancy table and its
"Going to a character is one gesture, spelled four ways" section; `boot-and-loop.md`'s `sessions`
phase note; and `docs/addons/api/client/profiling/counters.md`'s `session()` prose, which describes
"the rest" of the sessions as distinct from the drawn one.

## Context files

- `src/io/brodgar/session/Sessions.java` — 1, 2, 3
- `src/io/brodgar/session/Control.java` — 1, 3
- `src/io/brodgar/session/SessionWnd.java` — 1, 3
- `src/haven/UILoop.java` — 1, 2
- `src/haven/Client.java` — 1, 2, 3
- `src/io/brodgar/addon/ProfHandle.java` — 1
- `src/haven/RemoteUI.java` — 2
- `src/haven/GameUI.java` — 1
- `src/haven/Glob.java` — 1
- `src/haven/MapView.java` — 1
- `docs/client/boot-and-loop.md` — 2
- `docs/client/multi-session.md` — 3
- `docs/addons/api/client/profiling/counters.md` — 1, 3
- `DOCUMENTATION.md` — 2, 3
