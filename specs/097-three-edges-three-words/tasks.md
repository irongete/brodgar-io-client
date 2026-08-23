# 097 — three edges, three words: tasks

Shipped as **one task with one suite**, in the maintainer's own session: eleven spellings that are one
rule, so splitting them would leave the finding alive between the halves.

- [x] **097 — three edges, three words.** The event vocabulary used **five word-pairs for three edges**,
      and six spellings for "it went away" alone. All of those throw when you guess wrong, which is cheap —
      except **`QuestDone`, which fired for a quest that FAILED as well as one completed** and said nothing
      about it, the only silent wrong answer left in the bus. `Added` / `Removed` / `Changed` are now the
      three edges everywhere, the subject in front of them is **singular** (`MarkersChanged` →
      `MarkerChanged`), and where the **outcome** differs the **key** differs: `QuestDone` is retired into
      **`QuestCompleted` + `QuestFailed`**, so neither handler needs a status check. The word had to go
      rather than narrow — `Retired` keys on names, so a key that still resolved and silently stopped
      firing for half its cases is exactly what a hard cut prevents. `SessionDestroyed` → `SessionRemoved`;
      `FlowerMenuOpened`/`Closed` → `FlowerMenuAdded`/`FlowerMenuRemoved`; the widget's `Tick` → `Update`,
      which is **the same word and the same `dt` as the addon's own frame**, one object apart; the widget's
      `Destroy` → `Removed`; and the selector watch's `appear`/`disappear` → `Added`/`Removed`, the last
      lower-case pair in the API. Eleven `Retired.eventKey` rows, and **two new doors consult it** — the
      widget's `:on` (after the staleness check, before the key set) and the watch's three-argument `:on`
      (before the event is decoded). The rule is written on `conventions.md`, which had no events section.
      *Its suite* asserts the six new bus keys are live, that every retired spelling **raises naming its
      replacement**, that `QuestDone` names **both** keys and says it fired on failure, that a surface
      answers `Update` and `Removed` while `Tick` and `Destroy` raise, that the watch answers `Added` and
      `Removed` while the lower-case pair raises naming the three-argument call, that the session near-miss
      hint names `SessionRemoved`, and last — on a timer — that **both frame edges really fire and hand the
      same `dt`**, which is the half of the `Tick`/`Update` finding a name check cannot reach.
      `[manual]`: one — a real quest outcome, the only thing here no program can cause.
      *Audit*: `audit/ns-event.md` F2 · `audit/02-naming.md` §S7. **Neither is a row in `INVENTORY.md`.**

## Result

**Closed without the in-game run, on the maintainer's instruction.** The box above is ticked because the
task is closed, not because it was seen working: `:t097` was never executed, and nothing here has run in a
live client.

What WAS verified, and it is not the same thing:

- a clean build from an empty `build/classes` — 764 source files, `BUILD SUCCESSFUL`
- `tools/docverbs.py`: 865 receiver-typed calls resolved, 253 messages the bridge raises, 35 collections,
  and **147 upper-case event keys against the 52 the bridge fires** — the pass this feature added
- `tools/retiredverbs.py`: 113 replacement mentions, every one a verb its receiver answers
- all five tools and both suites parse under LuaJ

That covers every spelling and every message. It does **not** cover a single firing: whether
`QuestCompleted` reaches a handler when a quest completes, whether both frame edges hand the same `dt` in
a running client, whether the two new doors refuse where they should. The suite exists and is archived
beside this file; running it is one command.

## Reported at the close, not changed

**Two of the six "went away" spellings were not among the three decisions asked for.** The maintainer chose
`Tick` → `Update`, a `QuestFailed`, and the vr snapshot; the widget's `Destroy` and the watch's
`appear`/`disappear` were included because fixing four of six leaves the finding standing and the next
audit reporting it. Flagged rather than assumed.

**`docs/client/mapfile.md` line 59 carries an empty pair of backticks** — `bump \`markerseq\` (\`\`)` —
which predates this feature and is not reconstructible from what is there. Left alone; a `docs/client/`
page is a map of upstream, and this is a typo in it rather than a disagreement with `src/`.
