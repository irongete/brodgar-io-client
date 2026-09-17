# 155 — Plan

## Approach

**Move the map to the words the pages write, keep the tool's discipline.** `RECEIVERS` gains every
descriptive spelling `docs/addons/**` uses for an entity that has a `closedIndex` vocabulary — `position`,
`overlay`, `segment`, `category`, `message`, `speed`, `pagina`, `window`, `subscription`, `request`,
`result`, `connection`, `placing`, `experience`, `condition`, `maneuver`, `mask`, `session`, `mouse`,
`profiling`, `handle` (a font), `event` and its `_event` variants, `target` (an opponent), `grab`, `scope`
— and an explicit `None` with its reason for every spelling that names something the tool cannot check: a
section object (`options`, `keybindings`, `world`, `graphics`), the virtual family (`entity`, `ghost`,
`sprite`, `object`, `panel`, `patch`, `piece`), a collection (`slots`, `speeds`, `layers`). A spelling
that means two types on two pages (`segment` on the map pages and on `meter.md`, `summary` on `study.md`
and `fight.md`, `icon` on the asset pages and the item pages, `overlay` on `ui/overlay.md`) is settled in
`PER_FILE`, where the shorthand it replaces was settled before. A local name that ends in an entity word
(`scout_window`, `draw_event`, `alt_session`, `toggle_binding`, `tick_timer`) resolves by suffix, so a page
that names its variables well is checked without a map entry per variable.

**Then read what the gate says.** The widened run reports every verb a page writes that its receiver does
not answer; each is settled against the bridge file that owns the entity and fixed as a spelling on that
page. A finding that is the tool's — a union that includes a snapshot field, a file that registers two
entities — is fixed in the tool, not by narrowing the page.

**The comments follow the same rule as the pages**: a retired spelling in a comment anywhere under
`src/io/brodgar/addon/` is replaced by the key or verb the API answers today (`Changed`, `Pressed`,
`Submitted`, `Selected`, `Cell`, `Draw`, `Update`, `Drop`, `Close`, `match`/`matchAll`), read in place so the
sentence stays true. A refusal message that promises a retired spelling is fixed the same way, since it is
read by an addon author at runtime, and `tools/refusalverbs.py` gains the one hop it lacked to read those
messages — a widget's chained setter hands the widget back — so the gate covers them from here on. No code
path changes; the build proves it.

## Discarded alternatives

- **Rename the pages' identifiers back to the map's shorthand**: `DOCUMENTATION.md` §6 forbids the
  shorthand, and the map is the cheaper side to move.
- **Derive the receiver map from the pages' `local x = hafen.<section>()...` lines**: a reader of the
  first assignment would type most variables, but a page's tables call verbs on names no line assigns, and
  a wrong inference is a false green — the failure this feature exists to remove.
- **Map the section objects by reading their Java**: `hafen.ui()`'s verbs are registered through
  `Section` rather than a `closedIndex` literal; the tool's own comment draws that boundary and it holds.
