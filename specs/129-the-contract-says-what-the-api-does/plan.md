# 129 — plan

## Approach

### The comments say what the mechanism does, which is nothing

The machinery is live and correct: `Refusal.index(prefix)` builds an `__index`, `hafenIndex()` and
`sectionIndex(s)` hang it on the `hafen` table and on each section's callable table, and `install(hafen)`
wires it once per environment. What it has no more of is **rows** — `MOVED` and `KEYS` are declared at
`Refusal.java:58` and `:67` and never receive a `put`, so every read falls through to plain `nil`.

So nothing is fixed in code. Four comments are corrected to say that the door exists and is unguarded, and
why: nothing is published, so a hard cut needs no row, and the row would be for a caller that never existed.
The four, found by what they say rather than by a line number the audit took three features ago:

| Says | Where it is now |
|---|---|
| "throws from the `hafen` table's own `__index` … naming the replacement" | `AddonManager.java:4228` |
| "those four spellings throw naming their replacement" | `AddonManager.java:4479` |
| "the rows are pure data, generated from a feature's before/after inventory" | `Refusal.java:48` |
| "a separate row on the callable table's own `__index`, so both call sites…" | `Section.java:187` |

Two of those numbers will drift again before the task runs. The content is the address.

### The edge rule names the vocabulary that ships

`conventions.md` already has the shape: three edges, one exception written out (the outcome pair), and
"one word per edge, at every level". What it lacks is the rest of the vocabulary the client fires. Seven
keys are neither an appearance, a departure nor a change: a **threshold** a session crosses
(`SessionEnteredWorld`), a **selection** the user makes (`SessionSelected`, `ChannelSelected`), and a
**click** on something in the world (`GhostClicked`, `SpriteClicked`, `ObjectClicked`, `PatchClicked`).

The rule states those families beside the three, in both places it lives, and keeps what it already says
about the singular subject, the differing outcome and one word per edge. **No key moves.** They are named
in 13 addon files and 28 pages, `Clicked` is not reasonably `Added`, and a key is a published spelling
where the rule is a sentence.

### A census, then a rule that states categories

`:info()` is claimed universal in two places and is not. The count is not obvious from the outside — 71
files carry a closed index and 56 declare an `info` verb, and a file is not a vocabulary — so the task
builds `info-census.md` in this folder: one row per vocabulary, whether it answers `:info()`, and where it
does not, which category exempts it (a **builder**, configured then dispatched; a **snapshot**, which is
already the thing `:info()` would return; a **carrier of an ending**, whose whole state is that it has not
ended yet). The rule then names those categories instead of claiming no exception, and a vocabulary that
fits none of them is a row the census leaves open — the evidence for the other half, whichever way it goes.

### The checker resolves what a helper built, and refuses to pass on nothing

`statements()` finds rows with `re.finditer(r'put\(\s*"([^"]+)"\s*,')` — a literal key. `Refusal.java` has
two of those. About twenty-five arrive through `uiKept(verb, why)`, which builds its key by concatenation
(`"hafen.ui():" + verb`) and its message from a template, sixteen of them from a loop over the control
names. The scanner learns that helper: for each `uiKept(v, why)` call it synthesises the key and the
message the helper would build, and hands both to the resolver already there.

And the tool gains the thing that would have caught this without anyone reading it: **zero checked rows is
a failure.** A checker whose coverage collapses to nothing must go red, not green — that is what turned a
scanner blind spot into a green build for as long as it lasted, and it generalises to the next helper
nobody teaches it about.

## Files to create or modify

| File | What |
|---|---|
| `src/io/brodgar/addon/AddonManager.java` | two of the four comments |
| `src/io/brodgar/addon/Refusal.java`, `Section.java` | the other two |
| `CLAUDE.md` | the edge rule; the `:info()` rule |
| `docs/addons/api/conventions.md` | the public edge rule and its table |
| `docs/addons/api/virtual/README.md` | the `:info()` claim |
| `specs/129-…/info-census.md` | built by 129.3, read by 129.3's own rule edit |
| `tools/refusalverbs.py` | the helper's rows; the zero-coverage failure |
| `addons/129-….1` … `.4` | one suite per task |

No `docs/client/` page: nothing here reaches upstream `haven`. The three mentions of these keys in
`src/haven` are comments, and `ChatUI` reaches the seam by method name.

## Risks and gotchas

- **`MOVED` and `KEYS` must stay empty.** The comments are wrong about what fires; the decision that
  emptied them is right. A task that "fixed" the comments by refilling the maps would undo `11bf2871c`.
- **`uiKept`'s template is the message.** The scanner must synthesise what the helper builds, not what the
  call site writes — the call site passes only the *why*, and the seven replacements the row advertises
  (`:match`, `:matchAll`, `:on`, `:root`, `:node`, `:inventory`, `:equipment`) live in the template.
- **The loop at `Refusal.java:90` supplies sixteen of the rows** from a `String[]` of control names; a
  scanner that reads only the explicit calls still checks nine and would pass with a false count.
- **`docverbs.resolve_line` is the resolver**, already handling chained receivers through its RETURNS map.
  Nothing about resolution changes — only what is handed to it.
- **A refusal message is prose.** The resolver reads spellings out of English, so a reworded message can
  change what the checker sees; the rows are not touched by this feature and must not be.
- **`conventions.md` is the contract, `CLAUDE.md` the house rule.** Both state the edge rule and both must
  end up saying the same thing, or the next feature reads whichever it opens first.

## Discarded alternatives

- **Renaming the seven keys to fit the three edges** — a key is a published spelling and the rule is a
  sentence; `SessionEnteredWorld` alone is named in 13 addon files and 28 pages, and `Clicked` is not an
  appearance under any reading that would survive a reader.
- **Refilling `MOVED`/`KEYS` so the comments become true** — it would restore rows for callers that never
  existed, against the argument that removed them, and hand the checker work whose only purpose is to be
  checked.
- **Giving `:info()` to the five live vocabularies that lack it** — the other resolution, additive and
  breaking nothing, at five verbs and five page sections; the census is written so that half can start
  from evidence rather than from the audit's list.
- **Making the scanner read every string literal in the file instead of learning the helper** — it would
  cover rows nobody wrote as rows, and report a count that no longer means "rows", which is how a checker
  starts lying in the other direction.
- **Leaving the zero-coverage exit alone once the scanner is fixed** — the scanner is fixed for the helper
  that exists today, and the next one silently returns the tool to a green over nothing.
- **Teaching the checker a SECTION's vocabulary, so the MENTION count is non-zero as well as the row
  count** — what it reports is twenty-five rows and zero resolved mentions, and the zero is the shape of
  today's rows rather than a blind scanner: every row `MISPLACED` carries promises a section verb
  (`hafen.ui():window(…)`, `s:console():run(line)`), and the resolver types an entity verb written
  `recv:verb(`, which is what `MOVED` used to carry and no longer does. Resolution still fires — a seeded
  row promising an entity verb turns the tool red through the helper as readily as through a literal.
  Reading a section the way an entity is read would make it worse: a vocabulary is harvested per FILE, and
  `UiApi.java` builds BOTH halves of the split, so `s:ui():window` would resolve against `hafen.ui()`'s
  verbs and twenty-four rows would pass green for the very reason they exist. The tool states this where
  it prints its count, and the suite proves those rows fire from Lua instead.
