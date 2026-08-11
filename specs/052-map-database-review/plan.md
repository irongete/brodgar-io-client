# 052-map-database-review — Plan

## Approach

The design already exists: `information-architecture.md` rules 2–4 say a namespace over the ceiling
becomes `api/<namespace>/` with a `README.md` hub, and §8 says the task that moves a page re-points every
link into it in the same task. The delta this feature lands is *where the cut falls* and *what the hub
keeps*.

**The cut follows the five `##` subjects, and the hub keeps what is cross-cutting.** `map.md`'s two
general sections — "Reads answer nil until the disk answers" and "Identity" (interned handles) — are
claimed by all five subjects, so they are the hub's substance, not nav padding. That is what makes the
hub a page rather than an index, and it removes a same-page duplicate risk (§6) as a side effect.

| New page | From `map.md` lines | ~lines | What it holds |
|---|---|---|---|
| `api/map/README.md` | 1–22, 44–54, 420–436 | ~80 | what the database is, the recorded-vs-live boundary, the `nil`-until-loaded rule, interning, the reading order |
| `api/map/grids.md` | 23–43, 56–131 | ~100 | segments, grids, the Segment and Grid objects, saving an anchor |
| `api/map/overlays.md` | 133–229 | ~100 | the recorded masks, the Mask object, the display toggles and the hold |
| `api/map/drawings.md` | 230–296 | ~70 | `grid:image` / `grid:overlayImage`, levels, ownership and the cache |
| `api/map/markers.md` | 297–360 | ~70 | reads, the Marker object, `anchor()`, the ungated writes |
| `api/map/icons.md` | 361–419 | ~65 | the icon registry, the IconCat object, the ungated writes |

**The split is atomic — one task.** Six pages replace one, so a task boundary with `map.md` half-emptied
would leave the tree link-dirty, which §8 forbids. 001.4 landed 5 → 22 pages in one task; 1 → 6 is
smaller. The second task is the standard and the sweep, which is where a cosmetic pass belongs (001.4:
run the sweep *after* the last cosmetic pass, never after the last structural one).

**The obituary becomes a boundary.** §7 allows the absence a reader would reasonably expect, in the
present tense: the hub says `hafen.map` is the recorded database and `hafen.world` is the live world, and
never that two namespaces were removed. Then the retired names join the §7 grep list so their return is
caught, not argued about.

## Files to create / modify

- `docs/addons/api/map/README.md` `grids.md` `overlays.md` `drawings.md` `markers.md` `icons.md` — new; the six pages above
- `docs/addons/api/map.md` — deleted
- `docs/addons/api/README.md` — the `hafen.map` row becomes the hub plus its five leaves (IA rule 4: every leaf listed)
- `docs/addons/README.md` — the at-a-glance `map` link points at the hub
- `docs/addons/api/world.md` `conventions.md` `types.md` `ghost.md` `quests.md` `asset.md`, `docs/addons/examples.md`,
  `guides/actions-and-permissions.md` `guides/reading-the-world.md` — the 22 inbound links re-pointed
- `specs/standards/docs.md` — §7 grep list gains the eight names `037` retired
- `specs/standards/docs-ia.md` — §3's target tree gains `api/map/`; §7 records the tier widening as done
- `specs/PROJECT.md`, `specs/PROJECT.md` — the addons docs tier reads `docs/addons/**`
- `specs/STATE.md` `FEATURES.md` `LEARNINGS.md` (+ `learnings/docs-maintenance.md`) — closed by `/end`

## Risks & gotchas

- **The split is priced in anchors, not lines** (001.5). 22 inbound links, **14 anchored**, across 11
  files, plus **20 self-anchors** inside `map.md` that become cross-page links and **26 outbound** links
  that all gain a `../` when they move one level down. The `../` rewrite is the larger half and the one
  that resolves silently wrong if a page is edited in place rather than moved.
- **Order the `sed` longest-pattern-first, bare page link last, anchored on `)`** (001.4) — otherwise the
  bare `map.md)` rule rewrites `map.md` inside `map.md#markers` and produces a link that resolves to the
  wrong page. `#markers` and `#the-marker-object` are prefix-mates; so are `#drawings` and
  `#the-iconcat-object`/`#icon-categories`.
- **A heading touched for cosmetics is a rename** (001.4). Once the six pages exist, no heading text
  changes again before the sweep; `## Write (ungated)` keeps its wording on both pages that carry it.
- **Falsify the checker in both directions** (001.1): plant a break, confirm it is caught, remove it, and
  confirm the healthy tree still reports zero. A "0 broken" from an unfalsified checker is not evidence.
- **Reachability is a traversal, not an index read** (001.7). The five new leaves sit two levels deep;
  BFS from `docs/addons/README.md` must still put every page at depth ≤ 2, which is what the `api/README.md`
  leaf listing buys.
- **Link text is an unchecked claim** (001.6) and **a fence is a claim nobody re-reads** (001.5). Every
  `hafen.*` name that moves — in prose, in a table, in link text and inside a fence — is checked against
  its registration (`grep -rn 'set("<name>"' src/io/brodgar/addon/`), not against the old page.
- **`sed -i` on a CRLF tree rewrites every line ending** (001.4). `git diff --numstat` separates a real
  edit from a line-ending-only one before the report claims a file changed.

## Discarded alternatives

- Split `map.md` in two (`map.md` + `map-drawings.md`) — breaks IA rule 2, one namespace one path, and the
  remaining page would still be ~300 lines of four subjects.
- Keep one page and cut the prose to fit under 300 — the page is five subjects, and §9 says split by
  subject, never by line count. The callout count (nine, the tree's next-highest is four) is the symptom.
- Spread the split over one task per subject — leaves the tree link-dirty at every boundary (§8) and makes
  the last task carry a rewrite of what the first four wrote.
- Move `icons` out to `api/icons.md` as its own namespace — it is `hafen.map.icons`, and IA rule 2 keys the
  path on the namespace as it is spelled in Lua.
- Fix the wrap drift tree-wide (23 lines) — out of scope; this feature touches what `037` touched.
