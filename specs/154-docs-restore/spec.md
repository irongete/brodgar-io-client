# 154 — The addon docs: restored, then held to the standard

## What & why

`docs/addons/**` is the contract: what the API is, always current. One sweep replaced 126 of its pages
with short generic pages — 20,000 lines of facts gone, and in their place verbs that do not exist
(`hafen.ui():style():rule(sel, {background_color = …})`, `listbox:on("Selected")`, `entry:text(s)`,
`hafen.client():scale()`, an overlay with a `Draw` key), grammars that invert the client's (a bare word as
a class, `@` as a role) and patterns the API has verbs against (`replace.md` teaching the hide-and-build
that `widget:replace(view)` replaces). The pages before that sweep were written by the tasks that shipped
each surface and are exact; they are also long, and written in a voice `DOCUMENTATION.md` now forbids.

This feature puts the facts back and then rewrites every page to `DOCUMENTATION.md`: the facts in tables,
the prose technical and short, every example runnable with descriptive names, every page under its ceiling,
and nothing a checker can catch left uncaught.

## Acceptance criteria

1. **Every page before the sweep is back**, with every change the API made since re-applied on it: the
   session API's remembered accounts (`:saved`, `:add`, `:forget`) on `session.md` and the permissions
   guide; `hafen.resource`, `materials` and `steam` in the two READMEs, `references.md`,
   `asset/handles.md`, `sound.md`, `gob.md`, `look.md`, `types/world.md`; the image control's resource
   read on `controls/display.md`; the content-box read, the client's grip, the cancelable close and the
   `Resized` payload on `custom.md`, `widget.md`, `native.md` and the custom-UI guide. Pages the sweep
   never touched, or that were written after it (`steam.md`, `session.md`'s additions, `resource/`,
   `materials.md`), keep their content.
2. **Every checker is green**: `tools/docverbs.py` (every receiver-typed verb, every event key, every bus
   key on a catalogue page, every version), `tools/refusalverbs.py`, and every relative link — file and
   anchor — resolves.
3. **Every page meets `DOCUMENTATION.md`**: one `#`, a one-line first sentence, one runnable example of at
   most 12 lines, the methods in tables with return type and permission, error cases stated, no narrative
   prose, no metaphor, no single-letter or cryptic identifier in a code block, no `src/` paths or task ids,
   under 300 lines (or split by subject, every link re-pointed).
4. **No fact is lost in the rewrite**: every verb, arity, payload, refusal, permission and lifetime rule a
   restored page states is on the rewritten page (or the page it was split into).

## Out of scope

- **`docs/client/`**: the engine map was not swept, and its standard is `DOCUMENTATION.md` §12.
- **New surfaces**: a verb the restored pages never documented is a task of the feature that shipped it.
- **The checkers' blind spots** (receivers they do not map, section verbs): stated by the tools themselves.

## Docs impact

- **Written**: all of `docs/addons/**` — 130 pages restored in 154.1 and rewritten area by area in the tasks
  after it.
- **Derived impact set**: `git show --stat e96c446ae -- docs/` — the 126 pages the sweep touched, plus the
  four pages later commits changed beside them.

## Context files

- `DOCUMENTATION.md` — every task
- `docs/addons/**` at `e96c446ae^` — 154.1 (the restored content), read through `git show`
- `tools/docverbs.py`, `tools/refusalverbs.py` — every task (the gates)
- `src/io/brodgar/addon/*.java` — every rewrite task, where a page's statement needs the source to settle it
