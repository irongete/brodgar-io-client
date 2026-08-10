# 050-granular-permissions — Tasks

- [x] **050.1 — the catalogue, the matcher, the gate.** `Permission` (22 entries: key · Lua spelling ·
      plain line), `Manifest.permissions` parsed and **validated** into a `PermissionSet` (exact key or
      `<prefix>.*`; unknown entry and the bare `"*"` both throw at load, listing the valid keys),
      `internal()` built from `values()`, `requirePermission(owner, perm)` at all 22 sites as the FIRST
      statement of each verb, and the persisted default policy **re-keyed on the consented set**
      (*declared ⊆ consented* → the user's choice stands; otherwise disable and re-prompt; declaring
      nothing is never touched) under a pref key carrying neither the retired word nor "seen". Every
      `src/` name and text that carried the tier goes with it — identifiers, comments, the retirement
      refusals. Migrate every installed addon whose manifest declares the old permission.
      *Its suite* declares a deliberate subset (`item.*` plus `player.move`) and proves the matcher on
      both sides with nothing sent to the server: each **declared** verb, called with an invalid
      argument, raises the ARGUMENT refusal — reaching it is the grant (D-213) — while each of the
      other 17 raises the permission refusal **naming its own key**; `item.*` reaches nothing outside
      the prefix; no refusal text anywhere contains the retired word. The four consent transitions
      (declares-new · declares-less · declares-nothing · unchanged) are proven headlessly, the policy
      being pure.
      `[manual]`: enable the suite and approve its consent dialog (that IS the grant); add a key to
      its manifest, `:reload`, and confirm it came back **disabled**; then misspell one, `:reload`,
      read the panel's reason, and put both back.

- [x] **050.2 — the consent dialog enumerates, and the row says how much.** The consent window (renamed
      with everything else) renders one plain-language line per declared entry (a wildcard as its
      group) instead of the three fixed labels, sized so a dozen entries still fit, and **marks the
      entries the user has not consented to before** so a re-prompt reads as an escalation rather
      than a repeat. The panel row's
      marker becomes `[protected: N]` with the keys in the row tooltip, and `enableAll`'s skip widens
      with the predicate so a bulk enable still cannot grant silently (`learnings/actions-gated.md`, 4c).
      *Its suite* declares one exact key and one wildcard, so the dialog has both shapes to render, and
      re-asserts its own grant and one refusal — the manifest→matcher→gate path the dialog reads from.
      `[manual]`: the dialog lists exactly those entries and nothing else; the row reads `[protected: 2]`
      and its tooltip names both; **Enable all** leaves it disabled; and after adding a third key the
      re-prompt marks that one as new.
      <!-- extra context: `src/haven/Window.java` — the dialog's chrome and `pack()` -->

- [x] **050.3 — the tier's name leaves `docs/`.** The mechanical half, landing the docs tier consistent
      with itself: every `Write (protected: …)` heading becomes `## Write (protected)`, every inbound
      anchor follows, `guides/actions-and-permissions.md` becomes `guides/permissions.md` with its 12
      inbound links and their link TEXT, and `conventions.md`'s section anchor moves. The sweep is
      048.8's: word-boundary grep, subtract the game noun by hand (`hafen.actionbar()`,
      `hafen.event():action()`, the action menu), rename only what is left, re-grep, and read every
      survivor. §12's link/anchor check reported, falsified four ways with the plants removed.
      *Its suite* transcribes the (verb → key) pairs from the pages it touched and asserts each
      refusal names that exact key — a doc that drifted from the engine reddens a line.

- [ ] **050.4 — the docs teach the catalogue.** The content half: `guides/permissions.md` rebuilt around
      the 22 keys, wildcards and the enable-time consent; each protected page states the key its verb
      needs; `runtime.md`'s manifest row and badge row; `conventions.md` stating the model once;
      `api/README.md`, `guides/README.md`, `getting-started.md` and `examples.md` re-pointed. Close with
      the full §12 report and a `grep -rin "actions" docs/addons` whose every survivor is a game noun,
      listed and read one by one.
      *Its suite* drives every permission key **quoted in the shipped pages** through a refusal and
      asserts the engine names it back — so a key the docs invented, or one they spelled the old way,
      cannot survive the run.
