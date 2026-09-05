# 129 — the contract says what the API does

## What and why

Three places where the contract and the code have come apart, and the checker that should have caught them
reporting a green over nothing.

**Four comments describe a mechanism that has no rows.** `Refusal.MOVED` and `Refusal.KEYS` are empty by a
decision that stands: `11bf2871c` removed 207 rows because nothing is published and no third party ever
wrote those names. Four comments in `io.brodgar` still say otherwise — that reading a retired name throws
from the `hafen` table's own `__index` naming its replacement, that four lifecycle keys throw naming theirs,
that a section answers for both call sites, and that the rows are pure data generated from a feature's
before/after inventory, when nothing generates them. The cost falls on a reader: the next hard cut ships
without a row because the comment says the mechanism fires. **Two of the four sites have moved since the
audit saw them**, so they are found by what they say rather than by where they sat.

**The edge rule names three edges and the client fires more.** `CLAUDE.md` and `conventions.md` both say
there are only three — `Added`, `Removed`, `Changed` — with one exception already carried, the outcome pair
(`QuestCompleted`/`QuestFailed`). Seven of the 38 keys are neither: `SessionEnteredWorld`,
`SessionSelected`, `ChannelSelected`, and `GhostClicked`/`SpriteClicked`/`ObjectClicked`/`PatchClicked`.
`Clicked` is not reasonably `Added`, so **the rule is what is wrong, not the keys** — and the keys are not
cheap to move: `SessionEnteredWorld` alone is named in 13 addon files and 28 pages. No key string is written
in `src/haven`; the three mentions there are comments, and `ChatUI` reaches the seam by method name.

**`:info()` is stated as universal and is not.** `CLAUDE.md` says every live object answers it *"with no
exception"*, and `virtual/README.md` calls it *"the one escape hatch every live object in this API carries"*.
Of the vocabularies carrying a closed index, some answer no `:info()` — several rightly (a builder, a thing
that is already a snapshot, a carrier of an ending), and some arguably not.

**And the checker over all of this proves nothing.** `tools/refusalverbs.py` prints *"checked 0
receiver-typed replacement mentions"* and exits zero. Its scanner recognises only a literal `put("…")`, and
`Refusal.java` holds exactly two; about twenty-five rows arrive through the `uiKept` helper and are
invisible to it. A green that covers nothing reads like a guarantee.

## Acceptance criteria

1. No comment in `io.brodgar` describes a refusal mechanism that has no rows; the four are found by what
   they say, not by where the audit saw them.
2. `Refusal.MOVED` and `Refusal.KEYS` stay empty — the decision that emptied them is in force, and this
   feature does not revisit it.
3. The edge rule names the vocabulary the client actually fires, in **both** places it is stated, so a
   reader can tell from it which word a new key takes.
4. No event key is renamed: the seven stand, and every addon and page naming them stays correct.
5. A census records, for every closed vocabulary, whether it answers `:info()` and — where it does not —
   which category exempts it.
6. Neither rule states an absolute that the census or the key list contradicts.
7. `refusalverbs.py` reports a non-zero count of checked replacement mentions, resolves rows a helper
   produced as well as literal ones, and states the blind spot it still has.
8. Every checker in `tools/` exits zero on the tree.

## Out of scope

- **Giving `:info()` to the live vocabularies that lack it.** The other way to make that rule true, and a
  real one: additive, breaking no caller, at the price of a verb and a page section each. This feature makes
  the rule true by naming the categories, and the census it writes is what the other half would start from.
- **Refusals whose text is thin** — `ROADMAP.md` lines 37, 40, 42 and 45, four refusals that say too
  little. A different complaint from a refusal whose mechanism has no rows.
- **Reverting `11bf2871c`.** The rows went on the argument that nothing is published; that argument stands,
  and criterion 2 is what holds it.

## Docs impact

`CLAUDE.md` — both rules. `docs/addons/api/conventions.md` — the public edge rule and its table.
`docs/addons/api/virtual/README.md` — the `:info()` claim.

Derived impact set — every statement of either rule, anywhere:

```text
$ grep -rn "three edges\|no exception\|every live object\|only three edges" CLAUDE.md docs/
CLAUDE.md:85                    :info() … every live object answers it, with no exception
CLAUDE.md:89                    an event key is a subject and an edge, and there are three edges
docs/addons/api/conventions.md:148,150   "Events: three edges, three words" + the edge table
docs/addons/api/virtual/README.md:142    the one escape hatch every live object in this API carries
docs/addons/api/references.md:66         ":remove is no exception" — a different sentence, no impact
```

## Context files

- `src/io/brodgar/addon/Refusal.java` — 1, 4 (`MOVED`, `KEYS`, `MISPLACED`, `uiKept`, `eventKey`)
- `src/io/brodgar/addon/AddonManager.java` — 1, 2 (two of the four comments, found by what they say; and
  `BUS_KEYS`, the 38 keys as shipped)
- `src/io/brodgar/addon/Section.java` — 1 (the third comment, near the "has no verb" refusal)
- `tools/refusalverbs.py`, `tools/docverbs.py` — 4 (the scanner to fix, and the shape one is written in)
- `CLAUDE.md` — 2, 3 (the two rules)
- `docs/addons/api/conventions.md` — 2 (the edge section and its table)
- `docs/addons/api/virtual/README.md` — 3 (the `:info()` claim)
- `DOCUMENTATION.md` — 2, 3
