# 171 — The opening is its own type

## What & why

A fight's openings, the icons the combat view draws beside you and beside each opponent, are handed to Lua as `Buff` objects. So one type answers for two things the client keeps apart. `Buff` carries `buff:opponent()`, a verb that only means something in a fight. The buff page has to explain fights, and the fight page has to explain buffs.

This feature gives the opening a type of its own, `Opening`, and gives `Buff` back to the buff bar alone. Each surface then has its own type, its own events and its own page, and neither page names the other's subject.

**Maintainer's ruling (2026-10-01):**
- The buff pages mention nothing about fights, and the fight pages mention nothing about buffs. The separation is made in the API, which is what makes it possible on the pages.
- Breaking what v11.1-beta published (170 shipped there) is accepted, under the standing ruling that nobody has written an addon yet. There is no announcement and no `MOVED` row.

**The API edition stays `1.3`.** Every verb an opening answers already answered on the fight's buffs in `1.3`, so nothing an addon calls needs a newer client.

## Acceptance criteria

1. `session:fight():opening()` and `opponent:opening()` list `Opening` objects (`tostring` reads `Opening(<res>)`), and `session:buff()` lists `Buff` objects. Neither ever lists the other's: no opening is on the bar, and no buff of the bar is in a door of the fight. *(171.1)*
2. `OpeningAdded`, `OpeningChanged` and `OpeningRemoved` each hand an `Opening`, then the `Opponent` it is drawn beside (`nil` for yours), then the session. The payload `==` what the doors list, and the `OpeningRemoved` payload `==` the `OpeningAdded` one. Each fires once per edge, as in 170. `BuffAdded`, `BuffChanged` and `BuffRemoved` hand a `Buff` and never fire for an opening. *(171.1)*
3. An opening answers:
   - `:res()`, `:name()`, `:amount()`, `:remaining()`, `:number()` and `:widget()`, read exactly as the buff readers read them;
   - `:exists()`: `true` while a door lists it, and `false` once it is no longer drawn;
   - `:opponent()`: the opponent it is drawn beside, `nil` for yours and once no longer drawn;
   - `:info()`: `{ res?, name?, amount?, remaining?, number? }`, each field named after its read.

   Every verb refuses a surplus argument. An unknown verb raises, naming the opening's own vocabulary. A door's `:get` raises, saying an opening has no key and naming `:find(needle)`. *(171.1)*
4. `Buff` is the bar's alone:
   - `buff:opponent()` raises as an unknown verb whose message lists the buff's verbs;
   - `buff:exists()` answers whether the buff is on the bar;
   - the buff's refusal blurb names the buff bar. *(171.1)*
5. The pages hold the separation: the derived impact set below is rewritten, and `tools/docverbs.py` and `tools/refusalverbs.py` exit `0`.
   - The pages about buffs (`buff.md`, `types/character.md`'s `Buff`, `event/bus/character.md`) contain no fight, opponent or opening.
   - The pages about the fight (`fight.md`, `types/fight.md`, `event/bus/fight.md`) contain no buff.
   - The opening's events are on `event/bus/fight.md`, and its snapshot is on `types/fight.md`. *(171.1)*

## Out of scope

- **Schools and the deck** (`session:fight():school()`, `load`/`save`, the `deck()` reshape, the summary's retired counts). They are the next feature, on the builder window. This one ends where the fight's openings do.
- **The maintainer's own addons.** Nothing of theirs calls `buff:opponent()` or `session:fight():opening()`. `eventstack` subscribes to the `Buff` keys and keeps working. Whether it should list openings too is the maintainer's to decide, outside this feature.
- **`docs/client/`.** It maps upstream `haven`, where the widget class of an opening *is* `haven.Buff` in a `Bufflist`. `combat.md` keeps upstream's names.

## Docs impact

**Pages written:**
- `docs/addons/api/`: `buff.md`, `fight.md`, `types/fight.md`, `types/README.md`, `event/bus/character.md`, `event/bus/fight.md`, `event/bus/README.md`, `README.md`, `references.md`, `shapes.md`.
- `docs/addons/`: `manifest.md`.

**Derived impact set.** The command:

```bash
grep -rn -E "Opening|opening\(\)|buff:opponent|buff of a fight|fight's buffs|buffs a fight|buffs drawn|debuff|Buff\]\(buff.md\) objects|the \`Buff\` a fight" docs/addons --include=*.md
```

It finds:
- `README.md:74`
- `buff.md:42,44,52,68`
- `event/bus/README.md:19`
- `event/bus/character.md:31,32,33,45`
- `event/bus/fight.md:32,40`
- `fight.md:3,23,24,92,96,101,102,103,104,107,123,129,190`
- `references.md:66`
- `manifest.md:81`

`quest.md:53` and `ui/contents.md:65` are false positives: they use the English verb "opening".

Found by reading, not by the grep:
- `buff.md:3`: the intro paragraph.
- `buff.md:43`: `buff:exists()`.
- `event/bus/fight.md:3,39`: the intro and See Also.
- `README.md:37`: the fight's events row.
- `references.md:38–44`: the nameless doors, which add the opening doors.
- `shapes.md:67`: the units table gets an `opening:amount()`, `opening:remaining()` row.
- `types/README.md:37`: the index gets an `Opening` row.

## Context files

All tagged `1`; this feature is one task.

- `DOCUMENTATION.md`
- `src/io/brodgar/addon/LuaBuff.java`: the bar alone, and the widget readers it shares as code.
- `src/io/brodgar/addon/LuaOpening.java`: the opening type, its cache and its two doors.
- `src/io/brodgar/addon/CharApi.java`: `BuffsAdapter`, and the `opening` verb of `fight()`.
- `src/io/brodgar/addon/LuaOpponent.java`: `opening()`.
- `src/io/brodgar/addon/AddonManager.java`: `fireOpening`, and the `Buff` paragraph of `onWidgetDisposed`.
- `src/io/brodgar/addon/Addon.java`: the intern caches and `dropInternedHandles`.
- `src/io/brodgar/addon/Refusal.java`: `closedIndex` only.
- `src/io/brodgar/addon/LuaCollection.java`: `create` and `Source` only.
- `src/io/brodgar/addon/Args.java`: `only` only.
- `tools/docverbs.py`, `tools/refusalverbs.py`.
- Every page under *Docs impact*.
- `docs/client/combat.md`: read only.
- `specs/170-live-fight/addons/170-live-fight.1/main.lua`: read only, as the model of a suite that observes a fight.
