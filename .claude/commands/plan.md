# /plan — plan a feature

Usage: `/plan <feature description>`

Plan ONE feature of the AddOn system as a new `specs/addons/NNN-<feature>/` folder.
**/plan implements NOTHING.**

## Common rules (non-negotiable)

- **NEVER `git push`.** Everything stays local.
- **/plan commits NOTHING.** It writes the specs and stops for review; there is always
  something to fix. The specs reach the repo with the feature's first `/end`, which commits
  the whole task at once. Only `git commit` here if the maintainer explicitly asks.
- `specs/`, `docs/`, `src/` and `addons/` all live in the project repo.
- Converse in **Spanish**; files and code in **English**.
- Java (engine) changes require `ant` rebuild + a full client restart (no hot-reload);
  only Lua addon files reload live (`:reload`).
- **Read NOTHING outside what this command lists**, unless a task/spec lists it or the
  maintainer names it explicitly. **`/archive` is NEVER read.**

## Procedure

1. **Read ONLY:** `specs/addons/STATE.md`, `ROADMAP.md`, `FEATURES.md`,
   `DECISIONS.md`, and `specs/codebase-map.md`. From those, identify and read the
   relevant `specs/addons/design/` docs (normally 1–3).
   - If the maintainer names prior work ("this extends virtual entities") or `FEATURES.md`
     shows a clearly related folder, open that `NNN-` folder (spec/plan/tasks) as context.
   - For a specific decision: `DECISIONS.md` says which `decisions/<category>.md` file holds
     it — open only that entry (the `### D-xxx` headers are the one-liners).
   - For client-subsystem detail: read `specs/codebase/<subsystem>.md` if it exists. Only
     if it does NOT exist may you read source code — and then you MUST also create that
     `specs/codebase/<subsystem>.md` file (the reading is paid for once).
2. **Create `specs/addons/NNN-<feature>/`** (next free number) from `specs/addons/_template/`:
   - **spec.md** (MAX 80 lines): what & why · acceptance criteria **verifiable in-game** ·
     out of scope · **"Context files"** — the feature's context budget (design docs, .java
     sources, `docs/addons/api/*.md`, related prior `NNN-` folders).
   - **plan.md** (MAX 100 lines): approach · files to create/modify · risks & gotchas
     (prior art: open `LEARNINGS.md` — the index — pick the 1-2 relevant `learnings/*.md`
     files and grep them; never read the whole set) · discarded alternatives, one line
     each. If the design already lives in `design/`, reference it and land only deltas.
   - **tasks.md** (MAX 60 lines): checklist `NNN.1`, `NNN.2`, … One task = one session,
     self-contained, in-game-verifiable; a task may carry its own "extra context" line.
     If it does not fit the limits, do NOT stretch them: propose splitting the feature.
     The limits are ceilings, not targets.
3. **Register it**: mark it the active feature in `STATE.md`, add its line (ACTIVE) to
   `FEATURES.md`, and remove it from `ROADMAP.md` if it came from there.
4. **STOP after spec.md** and ask for the maintainer's approval. With the OK, write
   plan.md and tasks.md. /plan does not implement.
5. **STOP for review — and do not commit, ever.** Report the files written (spec.md,
   plan.md, tasks.md + the STATE/FEATURES/ROADMAP updates). The maintainer reviews
   everything and may request changes to any of them; apply them and report again. Stay in
   this review loop as long as needed — then hand over to `/implement`. The specs stay
   uncommitted in the working tree until the feature's first `/end`.
