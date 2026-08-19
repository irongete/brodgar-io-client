# 078 — tasks

Four tasks, and this is the last feature of the sequence. `078.1` decides and records; `078.2` and
`078.3` each move one half of one namespace; `078.4` deletes the accessor everything has been walking
towards and runs the closing proof.

Read `spec.md`, `plan.md`, and `namespaces.md` in `specs/075-globals-without-a-session/` first. Every
file a task may open is in `spec.md` under **Context files**, tagged with the task that needs it.

**This feature fails by moving too much, not too little.** Every earlier one failed safe: a spelling
left unmoved threw. Here some verbs legitimately keep their spelling, so a sweep that addresses
`mouse` or `sheet` produces code that works and is wrong. Two numbers are checked at every task
boundary: the guardrail
`grep -rn "hafen\.\(timer\|event\|slash\|asset\|json\|http\|font\|client\)()" docs/` is **212** and
stays 212, and the survivor count is whatever `078.1` computed in `verbs.md`.

---

- [x] **078.1 — Which half each verb is in, and the number that proves it.**
      Writes `verbs.md` in this folder: every `hafen.ui()` verb with its half and its reason, in three
      groups. **Yours (the layer)** — `window`, `overlay`, and the sixteen control constructors;
      `074.1` already parents `hafen.ui():window()` into `LayerRoot`, so the work is saying on the
      page why these do not move. **The screen** — `mouse`, `at(x, y)`, `tipAt(x, y)`, `scale`:
      `mouse.md` records that `m:over()` **is** `hafen.ui():at(m:x(), m:y())` and `at` takes an
      arbitrary screen point, so these are `screen()` questions and `072` built that name for exactly
      them. **The client's (the session)** — `find`, `all`, `on`, `root`, `widget`, `node`,
      `inventory`, `equipment`. **`sheet` is global** and is the largest verb at 44 uses: its page
      says it restyles *the client's own surfaces*, and it is a rule declaration owned by the
      installing addon, applied live to whatever matches in every session. Then the arithmetic: from
      those verdicts, **the exact number of `hafen.ui()` and `hafen.store()` occurrences that must
      survive in `docs/`** — because this is the one feature whose checklist does not reach zero, and
      a target nobody derived is a target that cannot fail. Confirms `mouse` and `pixels` read
      `screen()` already and revises their pages to say so.
      *Its suite* reads `hafen.ui():mouse():x()` and asserts a number within the client's own size;
      asserts `hafen.ui():at(x, y)` answers for a point the suite picks rather than for the pointer;
      builds a window and asserts it is placed. It `pcall`s `hafen.ui():at()` with no coordinate and
      asserts the refusal names the arguments.
      `[manual]`: paste the two numbers `verbs.md` computed. Expect: they are stated, and the
      guardrail reads 212.

- [x] **078.2 — The client's widgets are reached through their session.**
      Moves the client-facing half onto `LuaSession`: `session:ui():find`, `:all`, `:on`, `:root`,
      `:widget`, `:node`, `:inventory`, `:equipment`, built as a lazily interned handle exactly as
      `076` interns `worldObj`. `UiApi`'s twenty-one `host()` callers take the session that reached
      them; the argumentless `host()` still exists and dies in `078.4`. The moved spellings are
      **retired** through `Retired.sectionObj`, naming their replacements, while `window`, `overlay`,
      the constructors, `sheet`, `mouse`, `at`, `tipAt` and `scale` **keep theirs** and their pages
      say why. Rewrites all of `api/ui/**` — 20 pages — and its share of the cross-cutting examples.
      **`:on(sel, "appear", fn)` scans the live tree at registration**, so addressed at a session it
      scans that session's tree, including one not drawn; its handle ends with `:remove()` rather than
      `sub:off()`, which is a wart the page already carries — do not fix it here and do not let the
      move hide it.
      *Its suite* finds one of the client's own windows through `:current():ui():find(selector)` and
      asserts its title; asserts `widget:parent()` from it does **not** reach a window the suite built
      in the layer — the two-tree claim, re-proved because this task rewired the search. It registers
      `:on(sel, "appear", fn)` for a window already open and asserts it fires. It `pcall`s
      `hafen.ui():find()` and asserts the refusal names `hafen.session():current():ui():find()`.
      `[manual]`: with a second session in the world, read the suite's line for it. Expect:
      `get("<the other account>"):ui():find("window[title=Inventory]")` answers **that** character's
      window — found in a tree that is not on screen.

- [x] **078.3 — Saved variables know whose they are.**
      Splits `hafen.store()`. The **account scope stays global** — one file for the client, whichever
      character is up, and it is the addon's rather than a character's. The **per-character scope is
      reached through its session**, `session:store()`, and goes on flushing when that session ends,
      hung on the `UI` death `074.4` already hung it on: what moves here is the address, not the
      lifecycle. Retires the per-character half of the loose spelling while the account half keeps it,
      which means the retirement is **per verb rather than per section** — `Retired.sectionObj` takes
      the whole section, so this one needs the narrower form or the section stays and its
      character-scope verbs throw individually. Rewrites `api/store.md` and `guides/saved-data.md`.
      *Its suite* writes an account-scope table and a per-character table, reads both back in the same
      run and asserts equality; asserts a key never written reads `nil`; and asserts the two scopes do
      not see each other's keys, which is what having two scopes means. It `pcall`s the retired
      per-character spelling and asserts the refusal names `session:store()`.
      `[manual]`: with two sessions up, write a per-character value on one, tab to the other, re-run.
      Expect: the second character reads `nil` for that key and its own account-scope value reads
      unchanged — one file for the client, one folder each.

- [x] **078.4 — `host()` takes the session, and the sequence closes.**
      Deletes `AddonManager.host()`'s argumentless form, so the build is the proof: a site nobody
      converted does not compile. Its remaining callers are read **one at a time and not swept** —
      `AddonManager` 19, `VrApi` 17, `Retired` 6, `ProfHandle` 4, `Layout` 4, `CharApi` 4, `LuaEvent`
      3, `FlowerMenuApi` 3, and the rest in ones and twos — because the list `072` bought is a list of
      **questions**: some of those sites are the drawn session only by accident and are really
      `layer()` or `screen()`. **`VrApi`'s seventeen are the standing suspicion**: `075.3` made
      entities world-shaped, so a `host()` read left there is probably asking about the scene, which
      is `screenView()`'s subject. `Retired`'s six mean the refusal machinery is itself a caller — a
      refusal that needs a session to be phrased cannot fire before there is one. Runs both closing
      numbers: the guardrail at 212, and the survivor count `078.1` computed.
      *Its suite is the sequence's closing proof.* It builds **one window of its own in the layer** and
      fills it by walking `hafen.session():list()`, reading
      `s:ui():find("window[title=Inventory]"):items()` for each — one window of yours, N inventories
      that are not yours, drawn above whichever character is on screen. It asserts a row for every
      live session, that each names its own account, and that the row for a session **not** on screen
      carries items. It re-asserts `engineReloads` is `0` and `placedRebuiltOffTick` is `0`, the two
      guarantees `074` and `070` left, because a feature that touched this much would break them
      loudly or not at all.
      `[manual]`: with two sessions in the world and an inventory open on each, run once and **tab
      between them without re-running**. Expect: the window stays put, keeps both rows, and neither
      row empties — it is your window, in the layer, reading two characters at once. That is the whole
      thing the sequence was for.
      `[manual]`: paste both closing numbers.
