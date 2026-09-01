# 124 — Tasks

Three, in order: 124.1 changes the behaviour and the addons that assumed the old one, 124.2 and 124.3
change only prose. Every suite is `addons/124-a-reload-announces-every-login.<X>/`, run with `:t124`.

- [x] **124.1 — a reload announces every login that is in the world.**
      `AddonRegistry.reload()`'s tail stops taking `AddonManager.state(screen())` alone and walks
      `AddonManager.allStates()` instead, the screen's state first and the rest after, gating each on
      `!st.ui.destroyed`, a non-null `AddonManager.gui(st.ui)` and a non-null `Sessions.nameof(st.ui)`,
      and giving each gated state the same `StoreApi.enterWorld` + `fireSession("SessionEnteredWorld", …)`
      the screen's gets today. Its javadoc is rewritten to the rule that holds, both paragraphs. The
      eight addons that carry a catch-up walk of `hafen.session():list()` lose it — `actionbars` (the
      bare `syncAll()` at file-body level only), `autodrop`, `clickpath`, `item-indicators`,
      `simple-chat`, `simple-minimap`, `water-meter`, `stockpile-controls` — with the comment above each.
      *Its suite* records in its file body how many sessions are in the world at that moment and which
      one is on screen, then appends every `SessionEnteredWorld` it receives. Since `reload()` runs
      `loadAll()` before it announces, the record is complete before the command can be typed, and the
      count taken at load time is the discriminator: **zero means this is a fresh client start, not a
      reload**, so the suite says to log a second character in and `:reload`, and scores its checks as
      not reached rather than passing on a run that proves nothing. With two or more it asserts that
      every account in the world at load time was announced, that the first announcement is the account
      that was on screen, that no account was announced twice, and that an account with no character is
      not announced at all.
      `[manual]`: with two characters in the world, `:reload`, then tab to the one that was **not** on
      screen — expect: the Autodrop window is up, titled with that character's name.

- [x] **124.2 — the pages state what a reload does.**
      Six passages, five files, no behaviour. `api/event/bus/lifecycle.md`'s *These report changes, not
      the state* keeps its rule for a subscription and states that a reload announces every session in
      the world. `runtime.md`'s moments table stops calling `SessionEnteredWorld` **Once per session** —
      it fires again for the same session when it picks another character, and again for every login in
      the world at a reload — and its *What a reload keeps, and what it drops* says the same. The single
      sentence in `guides/events-and-timers.md` and in `getting-started.md` that names the character on
      screen names every login instead. `api/session.md`'s *Sessions that come and go* keeps
      `hafen.session():list()` as the read for what is already there — five pages iterate the logins for
      their own reasons and are untouched — without offering it as the answer to a reload. Present tense
      throughout: nothing anywhere under `docs/` is addressed to a reader who knew the old rule.
      *Its suite* duplicates 124.1's assertion rather than resting on it — the same load-time record, the
      same four checks — because what these pages now claim is exactly that behaviour, and a suite stands
      alone. Its handler also writes one log line per announcement as it arrives, which is what makes the
      manual below readable. It asserts the doors the rewritten pages still name:
      `hafen.session():list()`, `:count()` and `:current()` answer and agree, and
      `hafen.event():on("SessionEnteredWorld", fn)` hands back a `Sub` that `sub:off()` ends.
      `[manual]`: with the suite loaded, pick another character on the account you are looking at —
      expect: one new line in the log naming that account, which is the second announcement for one
      session that the moments table now describes.
      <!-- extra context: DOCUMENTATION.md (§8 no history, §11 the checks a docs task runs) -->

- [ ] **124.3 — the page states when a character's saved variables read back.**
      One passage, one file, no behaviour. `api/event/bus/lifecycle.md`'s paragraph under the
      `SessionEnteredWorld` table drops the screen from the claim: `StoreApi.enterWorld` fills a session's
      per-character tables for the session that entered, drawn or not — the `loadChar` loop is unconditional
      and only the `rescope()` at its tail is the screen's — so a character reaching the world behind another
      brings theirs at its own `SessionEnteredWorld` rather than when you tab to it, and `SessionSelected`
      moves remembered placements alone. `api/store.md`'s *When each scope is ready* already states it that
      way and is the wording to agree with; the `// ...if it is the session on screen...` comment in
      `AddonManager`'s `enterWorldPending` gate goes with it.
      *Its suite* asserts it through the API: on every `SessionEnteredWorld` for a session that is not
      `hafen.session():current()`, `s:store()` answers that character's own keys inside the handler — a write
      and a read back, scored as not reached until a background login arrives within a bounded window.
      <!-- extra context: DOCUMENTATION.md (§8 no history, §11 the checks a docs task runs) -->
