# specs/addons — the AddOn system's working folder

**Workflow:** `/plan <feature>` → creates the next `NNN-<feature>/` (spec → *approval* →
plan/tasks), no commit · `/implement [NNN.X]` → executes ONE task, leaves `HANDOFF.md`, then
iterates with the maintainer through the in-game test/fix rounds, no commit · `/end [note]` →
documents (docs/addons/api/ only), checks the task off, updates state, and makes THE task
commit: code, docs, addons and specs together. Never push.

| File | What it is |
|---|---|
| `STATE.md` | what works now + the active feature (REPLACED, ≤60 lines — loaded first) |
| `ROADMAP.md` | future work blocks, each pointing at its design doc |
| `FEATURES.md` | one line per `NNN-` folder (the addressable history; never archived) |
| `NNN-<feature>/` | spec.md / plan.md / tasks.md per feature (closed ones opened on demand) |
| `DECISIONS.md` | index of `decisions/*.md` (full entries split by category, like learnings) |
| `LEARNINGS.md` | index of `learnings/*.md` (append-only; grep, never read whole) |
| `API-REFERENCE.md` | the designed `hafen.*` contract (`docs/addons/api/` = what is built) |
| `GLOSSARY.md` | terms |
| `../codebase-map.md` | client-wide `file:line` map (+ `../codebase/` per-subsystem detail) |
| `_template/` | spec/plan/tasks templates (limits 80/100/60 — ceilings) |
| `design/` | the closed design docs (below) |

| design/ | One line |
|---|---|
| 00-vision-scope | project vision, goals, success criteria |
| 01-architecture | layering, principles P1-P5, threading model |
| 02-filesystem-and-build | addons/savedata dirs, ant wiring |
| 03-addon-format | manifest.json, addon folder anatomy |
| 04-engine | AddonManager, tick pump, watchdog |
| 05-lifecycle-and-reload | load order, teardown, :reload |
| 06-lua-api | the hafen.* API design |
| 07-ui-and-drawing | windows, GOut wrapper, overlays, input |
| 08-widget-replacement | wrap-don't-reimplement, seams A/B |
| 09-events-catalog | the event list + action-message encodings |
| 10-options-panel | the AddOns panel |
| 11-core-hooks | the minimal haven core-edit ledger + zero-edit seams |
| 12-security-and-permissions | sandbox, watchdog, the actions permission |
| 13-hooks-and-interception | hook levels L1/L2/L3(/L4) |
| 14-widget-tree-reads | Locator + Adapter mechanism |
| 16-virtual-entities | ghosts, clickability, gizmo, persistence |
| 17-custom-rendering | non-.res images/sprites, the shared entity core |
| 18-custom-models-gltf | the glTF parser + build pipeline |
| 19-data-and-network | hafen.json + hafen.http design |
| 20-widget-introspection | WidgetNode + hit-testing |
| 21-fonts | the font provider, scopes, per-instance |
