# Decisions — Process & conventions

> Part of the decision log (ADR-lite). Index: [../DECISIONS.md](../DECISIONS.md). Entries are
> verbatim; each `### D-xxx` header line doubles as the decision's one-liner (grep them).
> Append new decisions of this kind here.
> Legend: ✅ Accepted - 🔄 Revisit later - ❌ Rejected - 💤 Superseded.

### D-007 — Everything is in English ✅
**Decision.** English is the project's only language: the conversation, all `specs/**`
documents, the docs, and the code and its comments.
**Rationale.** Maintainer preference.

### D-085 — A suite proves its own task ALONE; duplication beats delegation ✅
**Decision.** Every per-task suite ([../TESTING.md](../TESTING.md)) must be a complete verification of its
own task when run **by itself** — `:t<NNN>-<X>` and nothing else. It may not require an earlier task's
command to have been run first, and it may not rest on an assertion that lives only in another suite. Where
its proof needs something an older suite already checks, the assertion is **duplicated, not delegated**.
Running every command is still the *full* regression and every suite stays installed; what changed is that
it is no longer a precondition for verifying one task.
**Rationale.** The maintainer, closing 035.4: *"no quiero tener que ejecutar addons antiguos para probar
tareas"*. Verifying one task had grown into eight commands, and the dependency was **invisible** — nothing
in a suite says which other suite its premises live in, so the coupling could only be discovered by a
failure. A duplicated line costs one line; a delegated premise costs a protocol. It is also the last of the
shared-state couplings between suites: 035.3 deleted their auto-start after a race (the schedule was the
mechanism, the ordering rule was the residue), and this deletes the residue.
**Consequence.** Old suites are **not** retrofitted — the rule binds new ones, and an old suite is still
only edited when it breaks. A suite that would need a large premise from elsewhere is evidence the task was
two tasks, not a reason to delegate.

### D-144 — A closed task's suite is ARCHIVED into its spec folder, not left live ✅
**Decision.** `/end`'s close step moves a task's test addon out of the client's live
`addons/<NNN>-<feature>.<X>/` into `specs/addons/<NNN>-<feature>/addons/<NNN>-<feature>.<X>/` — an
ordinary move, so the suite is unchanged and still runnable, just no longer one of the folders the
client scans at login. Only the in-flight task's suite (implemented, not yet `/end`-ed), frozen
`hello`, and the example addons ([../TESTING.md](../TESTING.md)'s list) stay in `addons/` permanently.
**Rationale.** The maintainer, closing on a client `addons/` folder that had grown to fifty-odd
historical suites loading on every login, none of which the working assumption behind D-085 ("the
maintainer rarely chooses" to run the regression) ever asks for. The suite already proves its task
alone by construction (D-085); keeping it *loaded* bought nothing once the working assumption is that
it is not run, and cost a slower login and a longer AddOns list for every task that shipped one.
**Consequence.** The *Regression* list in [../TESTING.md](../TESTING.md) is now a list of commands
that work once their folder is copied back into `addons/`, not a live list of what is installed —
running the full regression is opt-in twice over now: nobody runs it, and nobody has it loaded either.
Archiving is not disabling: an archived suite is exactly as green as the day it closed.

### D-026 — Gap design/build order ✅ (closes Q-014)
Order: **widget-tree-read mechanism** ([14-widget-tree-reads.md](../design/14-widget-tree-reads.md), foundational)
→ A5 overlays (done) → A1 map/markers → A4 study/curiosity/FEP → A3 action bar → A2 radar/GobIcon settings
→ A11 slash commands → A6–A10. Rationale: the widget-tree mechanism unblocks vitals/buffs/char/FEP/
action bar at once ([coverage-gaps.md](../ROADMAP.md) B1/C2), so it comes first.

### D-092 — A system's boundary is a DECISION on the page, not a gap in the code ✅
**Decision.** The close of a feature run states, in the user-facing docs, **what the system covers and what
would be a new chapter** — each exclusion named with the reason it is a different mechanism rather than a
missing one. For the skinning run that is
[`ui.md`'s "Where the skinning system ends"](../../../docs/addons/api/ui.md): the inside of a client window
(that is `widget:replace`, not styling), state-dependent looks, relationships in the grammar, motion, a
configuration UI, the 3D world, and the two structural limits already measured.
**Rationale.** Everything a system does not do is discovered by somebody eventually; the only question is
whether they find it written down or find it as a bug report. Six of the seven exclusions above are things
this run *chose* — each has a decision behind it (D-084's re-layout rule, D-088's "layout is said where a
widget is matched", the one-level grammar of 030) — and a reader cannot tell a considered omission from an
oversight unless the page says which it is. The docs already carried the pattern in the small (the property ×
key table's honest ❌ cells); this applies it to the system.
**Consequence.** The statement is written at the run's close, when the boundary is known, and it is written as
prose that ages: it names mechanisms (`hafen.render`, `hafen.store`, `widget:replace`), not tasks. A later
feature that crosses one of these lines edits the section rather than leaving it to rot — which is the same
maintenance the property tables already get.
