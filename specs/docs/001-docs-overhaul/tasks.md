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

- [x] 001.3 — **Reference, group A — the read side.** `gob`, `world`, `map`, `markers`, `radar`,
      `player`, `time`, `char`, `party`, `buffs`, `meters`, `kin`, `speed`, `craft`, `quests`,
      `wounds`, `fight`, `actionbar`, `actions`, `menugrid`. One pass per page: fill its matrix
      gaps, delete drift and history, apply the template, land it at its target path.
      <!-- landed: 21 pages (char split into char+study; buffs→buff, meters→meter, actions→act).
           Drift D-2, D-3, D-13(map) and D-4(map) fixed. D-006/D-007 decided here and folded into
           the style guide, so 001.4 does not re-litigate the page shape. -->

- [x] 001.4 — **Reference, group B — the UI stack.** The oversized set: `ui.md` (1171) split into
      topic pages with a hub and a reading order, `client.md` (688), `fonts.md` (436),
      `render.md` (336), plus `ghost`, `asset`, `hooks`. Same single pass. This is where the
      anchor rot risk is concentrated — the sweep here covers *every* page that links inward.
      <!-- landed: 22 new pages (api/ui/**, api/ui/style/**, api/client/**, api/render/**,
           font.md, hook.md) + ghost/asset rewritten; the 5 sources deleted. Drift D-1, D-5, D-7,
           D-8, D-9, D-12, D-13, D-14 closed; the 034.3 THIN row closed (widget:skin owns a section
           on the cascade's page). 917 links, 0 broken, 0 leaving docs/. D-008/D-009 decided here
           and folded into the style guide, so 001.5 does not re-litigate them. -->

- [x] 001.4b — **`api/README.md` interim listing** (only if the maintainer wants it before the
      close): the nested leaves (`ui/**`, `ui/style/**`, `client/**`, `render/**`) currently sit
      three clicks from the landing page, since the index still lists hubs only. 001.7 regenerates
      it anyway; this exists so the gap is recorded rather than forgotten.
      <!-- absorbed by 001.7: the index lists all 57 leaves; measured, not assumed — 44 pages at one
           click, 26 at two, none deeper. -->

- [x] 001.5b — **The last six `&` headings** (001.5's widened grep): `api/README.md` (3) and
      `getting-started.md` (3) still hold ` & ` headings, which slug to a double hyphen. Nothing
      links their anchors today, so this is not a break — 001.6 and 001.7 rewrite both pages and
      fix them there. Recorded so the grep result is not lost between tasks.
      <!-- done: 001.6 took `getting-started.md`'s three, 001.7's regeneration took
           `api/README.md`'s three. The tree holds 0 slugger-trap headings. -->

- [x] 001.5 — **Reference, group C — cross-cutting & infrastructure.** `conventions`, `types`,
      `events`, `timer`, `store`, `json`, `http`, `console`, `audio`. These are the pages every
      other page links to, so they land after the vocabulary they define is stable.
      <!-- landed: 10 pages. Amendment (IA §5.3): `console` shipped as `log` + `slash`, `audio` as
           `sound` — one namespace per path (D-002). Drift D-6 and D-10 closed; the tree now has 0
           em-dash headings and 0 retired names. Three example/prose accuracy fixes against `src/`
           (`gob.name` → `gob:name()`, `char.attrs().lp` → `char.lp()`, the "per-addon log" file).
           D-010 decided here (a client-local write group carries `(ungated)`), folded into the
           style guide with the widened heading grep, so 001.6 does not re-litigate either. -->


- [x] 001.6 — **The learning path.** `getting-started.md` rewritten as a real first-addon
      tutorial (zero context → a working addon, every code block runs as written), plus
      `guides/`: the tasks a new author has — custom UI, events & timers, saved data, actions &
      permissions, theming, reading the world, debugging, hotkeys & commands. Guides link into
      the reference and never restate it. **Also the two pages the tree does not have yet**
      (001.2, IA §6): `runtime.md` — the sandbox, the budgets and watchdog, `:reload`, the AddOns
      panel, the manifest fields and the console commands, which closes the four THIN engine/dev
      rows — and `examples.md`, which describes all ten shipped addons and closes G-1..G-3.
      <!-- landed: 12 pages, 1,336 lines (tutorial 218, runtime 186, examples 127, guides/ 9 pages).
           The eight guides are the ones listed above. 1,106 links / 0 broken; the only 10 links
           leaving docs/ are examples.md → addons/<id>/main.lua (D-009). D-011 decided here (launcher
           flags stay out of docs/), so 001.7's matrix lists that omission with its reason. Fixed in
           passing: log.md's See-also named `hafen.client:options():profiling()`. Filed to `addons`:
           `dependencies`/`optional_dependencies` are parsed and never used. -->
      

- [x] 001.7 — **The close.** `docs/README.md`, `docs/addons/README.md` and `api/README.md`
      regenerated to list exactly what exists; full link + anchor sweep over `docs/` reported as
      a count with zero broken; **the coverage matrix re-run — every row covered**, with any
      deliberate omission listed and reasoned, and any engine gap found filed to area `addons`.
      <!-- landed: 4 files (docs/README.md new, the two indexes regenerated, close.md). The re-run
           is `close.md`: 140 rows, 127 OK · 4 N/A · 9 CUT, 0 THIN / 0 GAP, the omissions reasoned.
           Sweep: 72 pages, 1,112 links, 0 broken; 36/36 namespaces documented. D-012 decided here.
           Corrected two of 001.1's figures (36 namespaces, and the matrix's own totals line). -->

