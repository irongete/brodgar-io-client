# Decision Log (ADR-lite) — index

> Decisions live in `decisions/`, split by category. **Never read the whole set**: each line
> below says which D-numbers a file holds; open only the entry you need (every
> `### D-xxx — <title>` header doubles as its one-liner —
> `grep -h "^### D-" decisions/*.md` lists them all). **Adding a decision:** append the full
> entry to its `decisions/<category>.md` file; add a line here only when a new category file
> is created.

- `decisions/docs-standard.md` — D-001..D-011 — the page standard and the shape of the tree: the
  300-line ceiling, one namespace per path, who owns the stylesheet, headings and anchors, the rule
  that `docs/` never links `specs/`, verb granularity and gating annotations, which numbers may
  appear on a page, and how an example addon is cited
