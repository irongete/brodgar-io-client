# 077 — tasks

Four tasks, grouped by affinity. `077.1` establishes the repeat over six read-only sections; `077.2`
settles what a permission key means once a verb can be addressed at another character; `077.3` is the
densest group of write verbs; `077.4` settles the open menu.

Read `spec.md`, `plan.md`, and `namespaces.md` in `specs/075-globals-without-a-session/` first. Every
file a task may open is in `spec.md` under **Context files**, tagged with the task that needs it.

**The pattern is `076`'s and is not re-invented.** `LuaSession` holds a lazily built handle per
namespace — `worldObj`, `playerObj` — interned on the handle so `s:world() == s:world()`, built by a
factory taking `(owner, account)`. Fourteen more fields, fourteen more factories, the same shape.
`073` already made `CharApi`'s nine adapters per session inside `SessionState`, so a bound section is
handed its `UI` and reads adapters that were already waiting.

**Two greps bracket every task, run at its start and its close.** The checklist —
`grep -rn "hafen\.\(char\|meter\|buff\|study\|quest\|wound\|fight\|actionbar\|speed\|kin\|party\|craft\|menugrid\|flowermenu\)()" docs/`
— is **195** and must reach **0**. The guardrail —
`grep -rn "hafen\.\(timer\|event\|slash\|asset\|json\|http\|font\|client\)()" docs/` — is **212** and
must still be **212**. A cut this size fails by moving too much.

---

- [x] **077.1 — The character sheet belongs to a character.**
      Moves six read-only sections onto the session: `char`, `meter`, `buff`, `study`, `quest`,
      `wound`. Each becomes `s:x()` on `LuaSession`, lazily built and interned exactly as `worldObj`
      is, through a factory in `CharApi` taking `(owner, account)`; each reads the per-session
      adapters `073` already placed in `SessionState`. All six loose spellings are **retired** through
      `Retired.sectionObj`, naming their replacements — derive each verb list from the page, because
      a verb the retirement misses reads as plain `nil` instead of throwing. No protected verb is in
      this group, which is why it goes first: if the pattern does not fit six times cleanly, that is
      known before any write is addressed. Rewrites the six pages and its share of the cross-cutting
      examples.
      *Its suite* reads all six through `hafen.session():current()` and asserts each answers or is
      honestly absent: `char():food()`, `meter():list()` non-empty in the world, `buff():list()`,
      `study():list()`, `quest():list()`, `wound():list()`. It asserts `s:meter() == s:meter()` — the
      interning claim — and `pcall`s `hafen.char()` asserting the refusal names
      `hafen.session():current():char()`.
      `[manual]`: with a second session in the world, run and read its line. Expect:
      `get("<the other account>"):meter():list()` answers **that** character's bars, not the drawn
      one's — read from a character you are not looking at.

- [x] **077.2 — The roster, and what a permission key names.**
      Moves `kin` and `party`, and with them the first protected verbs to become addressable:
      `kin.add`, `kin.rename`, `kin.group`, `kin.endKin`, `kin.forget`. **Each keeps the one key it
      has**, and the reason goes in the code and on the page: `conventions.md` defines a protected
      verb as one that *starts an action the player could have performed*, and the player could have
      tabbed to that character and performed it — **the key names the action, not the target**. A
      per-session grant would mean an addon the user allowed to add kin cannot add kin on an alt, a
      distinction the user never drew: the characters are all theirs. Revises
      `guides/permissions.md`, whose consent wording says *your* kin list and must now be read across
      every character the client holds, and the `Permission` descriptions with it. Retires both loose
      spellings.
      *Its suite* reads `kin():list()` and `party():list()` through `:current()` and asserts both are
      lists. Without the key declared it `pcall`s `kin():add(...)` and asserts the refusal names the
      verb **and** the key, **before anything reaches the server**; with the key declared in its own
      manifest it calls the same verb with a malformed argument and asserts it raises naming the
      argument — reaching the argument refusal proves the grant without touching anyone's kin list.
      `[manual]`: with two sessions up, read the kin list of the one **not** on screen. Expect: its
      own roster, which is not necessarily the same as the drawn character's.

- [x] **077.3 — The verbs that act, addressed.**
      Moves `actionbar`, `speed`, `craft` and `menugrid` — the densest group, and every key in it:
      `actionbar.use`, `actionbar.res`, `speed.set`, `craft.make`, `menugrid.use`. `BeltHold`'s slot
      holds and `AddonPagina`'s own entries are already per session from `073`, so what moves is the
      address and not the bookkeeping. `craft` and `menugrid` report **a window the game put up**: a
      background session keeps its `GameUI`, so its recipe window and its action menu exist and
      answer, and where nothing is open they answer the same nothing they answer today for a drawn
      session with nothing open — no new absence is invented. Retires all four loose spellings.
      *Its suite* reads `actionbar():get(0)`, `speed():current()`, `craft():current()` and
      `menugrid():list()` through `:current()`, asserting each answers or is honestly absent. It
      holds an action-bar slot, asserts `slot:res()` is the entry's identity, releases it and asserts
      the server's content is back. Without the keys declared it `pcall`s `speed():set(…)` and
      asserts the refusal names the verb and the key.
      `[manual]`: open a crafting recipe on one character, **tab to the other**, and re-run. Expect:
      `get("<the first account>"):craft():current()` still names that recipe — the window is open on a
      session you are not looking at, which is what makes a cross-character crafting addon possible.

- [ ] **077.4 — The open menu, and the fight.**
      Moves `flowermenu` and `fight`, and closes the family. `flowermenu` looks screen-shaped because
      a right-click is a mouse gesture and there is one mouse — but its page's own first line settles
      it: **the section *is* the open menu**, a widget in one session's tree rather than the gesture
      that raised it. So a menu opened in a session you then tabbed away from is still open, still
      readable, and `flowermenu.select` and `flowermenu.cancel` are meaningful at a distance; both
      keep their keys, on `077.2`'s reasoning. Retires both loose spellings, and runs the final
      checklist grep: **0**, with the guardrail still **212**. Sweeps whatever cross-cutting examples
      the three tasks before it left, so the family closes clean.
      *Its suite* reads `fight():current()` and `flowermenu()` through `:current()`, asserting each
      answers or is honestly absent — a run with no menu open says so rather than failing. It
      subscribes to `FlowerMenuOpened`, waits a bounded window over the `[manual]` below, and reports
      the petals it saw. It `pcall`s `hafen.flowermenu()` and `hafen.fight()` and asserts both
      refusals name their replacements.
      `[manual]`: right-click an object to raise a radial menu, then **tab to the other session**
      without picking a petal, and re-run. Expect: `get("<the first account>"):flowermenu()` still
      reports that menu and its petals — open on a character you are not looking at.
      `[manual]`: paste the final line of both greps. Expect: checklist `0`, guardrail `212`.
