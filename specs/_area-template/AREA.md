# Area: <name> — <one-line purpose>

> Manifest read by `/plan`, `/implement` and `/end` before anything else. Everything specific
> to this area lives HERE, not in the commands. Max 30 lines. Fill every field; delete none.

- **Scope**: <what belongs to this area, which source trees it owns, what it must not touch>.
- **Branch**: <branch this area is developed on>.
- **Docs tier (ONE)**: <exact path(s) `/end` writes user-facing docs to, and their index files.
  If the area ships no user-facing docs, say `none — specs only`>.
- **Build check**: <command that must succeed before handing over, and its success string>.
- **Verification**: <how the maintainer verifies a task: in-game / test suite / manual run.
  State what needs a rebuild or restart and what reloads live>.
- **Test protocol**: <the file that defines how a task is tested and how results are reported —
  e.g. `specs/<name>/TESTING.md`, to be READ before writing or verifying tests — or `none`>.
- **Design rules**: <the area's non-negotiable design constraints, if any>.
- **Commit paths** (for `/end`'s single commit): <paths to `git add`, e.g. `src specs`>.
- **Contract file**: `none — the docs tier IS the contract` (the default; only name a separate
  file if the area genuinely needs a design-time contract the docs cannot carry).
- **Design docs**: `specs/<name>/design/` — <or `none`>.
