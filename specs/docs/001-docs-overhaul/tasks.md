# 001-docs-overhaul — Tasks

<!-- One task = one session. Verification (AREA.md): the maintainer reads the changed pages.
     No tests, no addons, no tooling. Every task ends with a link/anchor sweep of what it
     touched and a report of what it checked. -->

- [x] 001.1 — **The audit.** Build `audit.md`: (a) the coverage matrix — one row per `addons`
      feature 001–036 *and per task inside it*, saying what it shipped and where that is
      documented today (or GAP); (b) the 39-page inventory — size, topics, verdict; (c) the
      drift list, from grepping the docs for promise shapes ("later feature", counts, "does not
      reach", "is gone") and checking each against `src/`. **Edits no docs page.** Splits by
      feature range if one session cannot hold it.
      <!-- extra context: `specs/addons/FEATURES.md`, `specs/addons/STATE.md`,
           `specs/addons/learnings/process-method.md` (docs-sweep prior art) -->

- [x] 001.2 — **The standard.** `design/style-guide.md` (page template, voice, how examples are
      written and where they come from, the ~300-line ceiling, link and anchor rules, the
      no-history rule) and `design/information-architecture.md` (the target tree: root, tutorial,
      `guides/`, `api/`; plus the migration map — every current page and section → its target
      path). Decided from 001.1's evidence; moves no file yet.

- [ ] 001.3 — **Reference, group A — the read side.** `gob`, `world`, `map`, `markers`, `radar`,
      `player`, `time`, `char`, `party`, `buffs`, `meters`, `kin`, `speed`, `craft`, `quests`,
      `wounds`, `fight`, `actionbar`, `actions`, `menugrid`. One pass per page: fill its matrix
      gaps, delete drift and history, apply the template, land it at its target path.

- [ ] 001.4 — **Reference, group B — the UI stack.** The oversized set: `ui.md` (1171) split into
      topic pages with a hub and a reading order, `client.md` (688), `fonts.md` (436),
      `render.md` (336), plus `ghost`, `asset`, `hooks`. Same single pass. This is where the
      anchor rot risk is concentrated — the sweep here covers *every* page that links inward.

- [ ] 001.5 — **Reference, group C — cross-cutting & infrastructure.** `conventions`, `types`,
      `events`, `timer`, `store`, `json`, `http`, `console`, `audio`. These are the pages every
      other page links to, so they land after the vocabulary they define is stable.

- [ ] 001.6 — **The learning path.** `getting-started.md` rewritten as a real first-addon
      tutorial (zero context → a working addon, every code block runs as written), plus
      `guides/`: the tasks a new author has — custom UI, events & timers, saved data, actions &
      permissions, theming, reading the world, debugging, hotkeys & commands. Guides link into
      the reference and never restate it. **Also the two pages the tree does not have yet**
      (001.2, IA §6): `runtime.md` — the sandbox, the budgets and watchdog, `:reload`, the AddOns
      panel, the manifest fields and the console commands, which closes the four THIN engine/dev
      rows — and `examples.md`, which describes all ten shipped addons and closes G-1..G-3.

- [ ] 001.7 — **The close.** `docs/README.md`, `docs/addons/README.md` and `api/README.md`
      regenerated to list exactly what exists; full link + anchor sweep over `docs/` reported as
      a count with zero broken; **the coverage matrix re-run — every row covered**, with any
      deliberate omission listed and reasoned, and any engine gap found filed to area `addons`.
