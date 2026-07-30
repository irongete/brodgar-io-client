# /plan — plan a feature

Usage: `/plan <area> <feature description>` — the area is **always** stated.

Plan ONE feature as a new `specs/<area>/NNN-<feature>/` folder. **/plan implements NOTHING.**

## Area resolution (ALWAYS step 0)

**The area is never assumed. There is no "current area" state anywhere** — several features may
be in flight in different areas at once, so every invocation names its own.

1. Take `<area>` from the command's first token when it matches a folder under `specs/`
   (ignoring `_`-prefixed ones); drop it from the feature description.
2. **If the area is missing, ambiguous, or matches no folder, STOP and ask the maintainer**
   which area this is, listing the existing ones. Never guess, never fall back to a default,
   never infer it from the feature description alone.
3. If the maintainer names an area that does not exist yet, **ask** whether to create it. With
   the OK, copy `specs/_area-template/` to `specs/<area>/`, fill its `AREA.md`, and **STOP for
   approval of that AREA.md** before planning anything.
4. **Read `specs/<area>/AREA.md`.** It declares the docs tier, build check, verification,
   test harness, design rules and commit paths for this area. Everything area-specific comes
   from there — this command never assumes them. Below, "the docs tier", "the build check",
   "the harness" mean whatever `AREA.md` says.
5. **Open your reply with `[área: <area>]`** so the maintainer always sees which area is in play.

## Common rules (non-negotiable)

- **NEVER `git push`.** Everything stays local.
- **/plan commits NOTHING.** It writes the specs and stops for review; there is always
  something to fix. The specs reach the repo with the feature's first `/end`, which commits
  the whole task at once. Only `git commit` here if the maintainer explicitly asks.
- `specs/`, `docs/`, `src/` and the area's own trees all live in the project repo.
- Converse in **Spanish**; files and code in **English**.
- Respect the area's rebuild/restart rules as stated in `AREA.md`.
- **Read NOTHING outside what this command lists**, unless a task/spec lists it or the
  maintainer names it explicitly. **`/archive` is NEVER read.**

## Procedure

1. **Read ONLY:** `specs/<area>/AREA.md`, `STATE.md`, `ROADMAP.md`, `FEATURES.md`,
   `DECISIONS.md` (all under `specs/<area>/`). From those, identify and read the relevant
   `specs/<area>/design/` docs (normally 1–3).
   - **Codebase detail: `specs/codebase-map.md` is an INDEX** — read it and open ONLY the 1–2
     `specs/codebase/<subsystem>.md` files your feature touches. Never open the tree wholesale.
   - **If no subsystem file covers the code you need**, you may read the source — and then the
     feature's `plan.md` MUST list the new/extended `specs/codebase/<subsystem>.md` under
     "Files to create / modify" so `/end` writes it. The reading is paid for once.
   - If the maintainer names prior work ("this extends virtual entities") or `FEATURES.md`
     shows a clearly related folder, open that `NNN-` folder (spec/plan/tasks) as context.
   - For a specific decision: `DECISIONS.md` says which `decisions/<category>.md` file holds
     it — open only that entry (the `### D-xxx` headers are the one-liners).
2. **Create `specs/<area>/NNN-<feature>/`** (next free number in that area) from the area's
   `_template/` (`specs/<area>/_template/`):
   - **spec.md** (MAX 80 lines): what & why · acceptance criteria **verifiable the way
     `AREA.md` defines verification** · out of scope · **"Context files"** — the feature's
     context budget (design docs, sources, docs-tier pages, related prior `NNN-` folders).
   - **plan.md** (MAX 100 lines): approach · files to create/modify · risks & gotchas
     (prior art: open `specs/<area>/LEARNINGS.md` — the index — pick the 1-2 relevant
     `learnings/*.md` files and grep them; never read the whole set) · discarded
     alternatives, one line each. If the design already lives in `design/`, reference it and
     land only deltas.
   - **tasks.md** (MAX 60 lines): checklist `NNN.1`, `NNN.2`, … One task = one session,
     self-contained, verifiable on its own; a task may carry its own "extra context" line.
     If it does not fit the limits, do NOT stretch them: propose splitting the feature.
     The limits are ceilings, not targets.
3. **Register it**: mark it the active feature in `specs/<area>/STATE.md`, add its line
   (ACTIVE) to `specs/<area>/FEATURES.md`, and remove it from `specs/<area>/ROADMAP.md` if it
   came from there.
4. **STOP after spec.md** and ask for the maintainer's approval. With the OK, write
   plan.md and tasks.md. /plan does not implement.
5. **STOP for review — and do not commit, ever.** Report the area and the files written
   (spec.md, plan.md, tasks.md + the STATE/FEATURES/ROADMAP updates). The maintainer reviews
   everything and may request changes to any of them; apply them and report again. Stay in
   this review loop as long as needed — then hand over to `/implement`. The specs stay
   uncommitted in the working tree until the feature's first `/end`.
