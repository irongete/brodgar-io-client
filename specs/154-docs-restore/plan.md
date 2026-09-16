# 154 — Plan

## Approach

**Restore, then rewrite — never rewrite from memory.** The pre-sweep pages are the only complete record of
the API's facts, so 154.1 puts the whole tree back from `e96c446ae^` and re-applies, by hand, the hunks the
real commits since made (each read from `git show <commit> -- docs/addons`), keeping the pages that were
born after the sweep. Then every later task rewrites pages from *that* text, with the source open where a
sentence needs settling — so no fact is re-derived from a summary.

**The gates run after every task**: `tools/docverbs.py` (receiver-typed verbs, event keys, bus catalogue,
API version), `tools/refusalverbs.py`, and a link check over every relative link and anchor in
`docs/addons/**`. A LuaJ parse of every ```lua block guards the examples: a block that parsed before a
rewrite parses after it.

**The rewrite is by area, one task each**, so a task's diff is readable and the checkers gate a bounded
set: `api/ui/**` and its two guides; the world and character pages (`world`, `gob`, `look`, `overlay`,
`placing`, `position`, `player`, `char`, `study`, `buff`, `meter`, `wound`, `quest`, `kin`, `party`,
`craft`, `fight`, `speed`, `actionbar`, `menugrid`, `flowermenu`, `time`); `event/**`, `timer`, `console`,
`log`, `locale`, `store/**`, `client/**`; `asset/**`, `font`, `sound`, `virtual/**`, `voice/**`, `map/**`,
`http`, `websocket`, `json`, `session`; `types/**`, `conventions`, `shapes`, `references`, `threading`; the
top-level pages and `guides/**`.

**How a page is rewritten.** Its facts are inventoried first — verbs, arities, returns, permissions,
refusals, lifetimes, events — then laid into `DOCUMENTATION.md`'s shape: title and one line; one example
of at most 12 lines with descriptive names; the methods table (Method, Returns, Permission, Description);
"Detailed usage / error cases" as short technical subsections and tables; See Also. Narrative paragraphs
become one sentence of mechanics or a table row; metaphors, philosophy and history go. A page over 300
lines splits by subject, with every inbound link re-pointed.

## Discarded alternatives

- **Rewrite from the source alone**: the source comments are exact but say why more than what, and a page
  written from them alone drops what only the shipped pages recorded — the refusals' wording, the
  lifetimes, the worked flows.
- **Keep the swept pages as the skeleton and pour the facts in**: their tables name verbs that never
  existed; a skeleton that has to be checked row by row is slower than one written from true text.
- **One task for the whole rewrite**: a 130-page diff nobody can read, and one gate run for everything.
