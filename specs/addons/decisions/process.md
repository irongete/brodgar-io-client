# Decisions — Process & conventions

> Part of the decision log (ADR-lite). Index: [../DECISIONS.md](../DECISIONS.md). Entries are
> verbatim; each `### D-xxx` header line doubles as the decision's one-liner (grep them).
> Append new decisions of this kind here.
> Legend: ✅ Accepted - 🔄 Revisit later - ❌ Rejected - 💤 Superseded.

### D-007 — Specification language is English ✅
**Decision.** All `specs/addons/**` documents are written in English. (Conversational
collaboration may be in Spanish.)
**Rationale.** User preference.

### D-026 — Gap design/build order ✅ (closes Q-014)
Order: **widget-tree-read mechanism** ([14-widget-tree-reads.md](../design/14-widget-tree-reads.md), foundational)
→ A5 overlays (done) → A1 map/markers → A4 study/curiosity/FEP → A3 action bar → A2 radar/GobIcon settings
→ A11 slash commands → A6–A10. Rationale: the widget-tree mechanism unblocks vitals/buffs/char/FEP/
action bar at once ([coverage-gaps.md](../ROADMAP.md) B1/C2), so it comes first.
