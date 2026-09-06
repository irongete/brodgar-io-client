# 129 — tasks

> **Three of these four change prose, and prose is not what a suite can read.** So each suite asserts the
> *behaviour the prose now describes* instead: that an absent row reads as `nil`, that the seven keys still
> subscribe, that a vocabulary the census marks either way answers as marked. Where the claim is only about
> a tool, the tool is the proof and the suite covers the same rows from Lua — statically over all of them,
> dynamically over a sample.

- [x] **129.1 — the four comments say what the mechanism does.**
      The `__index` machinery is correct and stays: `Refusal.index`, `hafenIndex`, `sectionIndex` and
      `install` are wired and answer. What it has no more of is rows — `MOVED` and `KEYS` are declared and
      never receive a `put`, so every read falls through to plain `nil`. Four comments still describe rows
      firing, and are found **by what they say**, since two of the four have drifted since the audit read
      them: the `hafen` table's `__index` "naming the replacement", the four lifecycle spellings that
      "throw naming their replacement", "the rows are pure data, generated from a feature's before/after
      inventory", and a section's row answering "both call sites". Each is rewritten to say the door exists
      and is unguarded, and why: nothing is published, so a hard cut needs no row and the row would be for
      a caller that never existed. `MOVED` and `KEYS` stay empty — refilling them would undo `11bf2871c`.
      *Its suite* reads a spelling no verb answers off the `hafen` table and off a section's callable
      table, and asserts each gives **`nil` rather than raising** — which is what an empty map means and
      what the corrected comments now claim. Beside each it reads a live verb and asserts that still
      answers, so the check is about the absent row and not about a broken `__index`.

- [x] **129.2 — the edge rule names the vocabulary that ships.**
      `CLAUDE.md` and `conventions.md` both say there are only three edges. Seven of the 38 keys are none
      of them, and both statements gain their families beside the three: a **threshold** a session crosses
      (`SessionEnteredWorld`), a **selection** the user makes (`SessionSelected`, `ChannelSelected`), and a
      **click** on something in the world (`GhostClicked`, `SpriteClicked`, `ObjectClicked`,
      `PatchClicked`). `conventions.md`'s table gains a row per family with its examples; what the section
      already says about the singular subject, the differing outcome and one word per edge is untouched.
      **No key moves**: they are named in 13 addon files and 28 pages, and a key is a published spelling
      where the rule is a sentence.
      *Its suite* subscribes to each of the seven through the emitter its page names, asserts every
      subscription is accepted rather than refused as an unknown key, and asserts `sub:off()` ends each —
      the assertion that fails if any key was renamed while the rule was being written. It `pcall`s `:on`
      with a key that is not in the set and asserts the refusal lists the ones that are.

- [x] **129.3 — the census, and `:info()` stated by category.**
      Builds `specs/129-the-contract-says-what-the-api-does/info-census.md` and edits nothing else with
      it: one row per closed vocabulary, whether it answers `:info()`, and where it does not, which
      category exempts it — a **builder**, configured and then dispatched; a **snapshot**, already the
      thing `:info()` would return; a **carrier of an ending**, whose whole state is that it has not ended.
      `CLAUDE.md`'s rule and `virtual/README.md`'s "the one escape hatch every live object in this API
      carries" then name those categories rather than claiming no exception. A vocabulary the census puts
      in none of them stays an open row — the evidence the other half would start from.
      *Its suite* takes three live handles the census marks as carrying `:info()` — a gob, an item, a patch
      — and asserts each answers a table with the keys its page lists; then takes one the census marks
      exempt and asserts it refuses in the way its page says, naming what to read instead. That pair is the
      census's own claim put back to the API.

- [ ] **129.4 — the checker resolves what a helper built, and fails on nothing.**
      `statements()` matches only a literal `put("…",` and `Refusal.java` holds two; about twenty-five rows
      arrive through `uiKept(verb, why)`, which builds its key by concatenation and its message from a
      template, sixteen of them from the loop over control names at `Refusal.java:90`. The scanner learns
      that helper — for each call it synthesises the key and the message the template would build and hands
      both to `docverbs.resolve_line`, which is unchanged. And **zero checked rows becomes a failure**: a
      coverage that collapses to nothing is what let this stand, and the next helper nobody teaches it
      about returns the tool to a green over nothing unless zero is red. It prints its count and states the
      blind spot it keeps.
      *Its proof* is the tool itself: green with a non-zero count on the tree, red on a seeded row naming a
      verb its receiver does not answer, and red on a `Refusal.java` whose rows are commented out — each
      reverted after.
      *Its suite* drives those rows from Lua instead of from the source: it `pcall`s `s:ui():window()` and
      two more misplaced spellings and asserts each raises naming `hafen.ui():` and which half the verb is
      in — the rows the checker resolves statically, proven to fire.
