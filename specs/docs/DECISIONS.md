# Decision Log (ADR-lite) — index

> Decisions live in `decisions/`, split by category. **Never read the whole set**: each line
> below says which D-numbers a file holds; open only the entry you need (every
> `### D-xxx — <title>` header doubles as its one-liner —
> `grep -h "^### D-" decisions/*.md` lists them all). **Adding a decision:** append the full
> entry to its `decisions/<category>.md` file; add a line here only when a new category file
> is created.

- `decisions/docs-standard.md` — D-001..D-019 — the page standard and the shape of the tree: the
  300-line ceiling, one namespace per path, who owns the stylesheet, headings and anchors, the rule
  that `docs/` never links `specs/`, verb granularity and the permission annotation, which numbers may
  appear on a page, how an example addon is cited, which index owns the flat leaf list, how a
  retired name earns its place on the §7 list, that the list is derived from the engine's own
  refusal table, that a word-ban voice rule must read zero before it may be a grep, that the
  standard reads in the shipped `protected` / `unprotected` adjective, that a directory under
  `api/` means a namespace while a type page splits into a sibling, and that a refused *grammar*
  spelling earns no §7 entry because the parser guards it over the tier's fenced blocks
