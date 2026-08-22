# 090 — One base for an index

Discharges: A-071, A-072.

Both are rows in `audit/INVENTORY.md`'s own 090 block, and both are **severe**. The block stands alone
for the reason it states: the change is **silent for every existing caller** — nothing breaks, the
answers just change.

## What and why

`:index()` meant two bases one namespace apart. `meter:index()` was the 1-based HUD position;
`slot:index()` was *"the raw 0-based game index"*; `sp:index()` was *"the wire number `0..3`"*. And
`actionbar.md` and `references.md` spelled the trap out as a feature:
`s:actionbar():list()[1] == s:actionbar():get(0)`.

So the single most likely mistake in that namespace was silent:

```lua
for i = 1, 144 do
  local slot = s:actionbar():get(i)      -- off by one, every slot
end
```

`get(1)` was a real slot, just the wrong one; `get(144)` was `nil`, so the loop *looked* like it found
143 and did something to each. The reverse — `s:actionbar():list()[slot:index()]` — was off by one the
other way, again silently.

## What shipped

**`:index()` is the 1-based position in `:list()` everywhere, `:wire()` carries the raw number the
server's own message uses, and `:get(n)` takes the number `:index()` answers** — so
`s:actionbar():list()[n] == s:actionbar():get(n)` holds, which is the invariant a reader assumes on
first contact and could not have.

| Verb | Now | The raw number |
|---|---|---|
| `slot:index()` | `1..144` | `slot:wire()` |
| `sp:index()` | `1..4` | `sp:wire()` |
| `card:index()` | its position in `:deck()` | `card:wire()` |
| `meter:index()` | unchanged — it was already the position | — |

`s:actionbar():get(n)`, `s:speed():get(n)` and `s:speed():set(n)` all take the position.
`s:speed():get(name)` is unchanged.

**`:get(0)` raises and names the change** rather than answering the wrong slot: *"the key is the
1-based position slot:index() answers, so :list()[n] == :get(n) — :get(1) is the first slot. The raw
0-based game index the server carries is slot:wire()."* It is the commonest thing an addon written
before this says, so it is the one place the reshape can teach.

**The Speed snapshot moved with its verb.** `sp:info().index` was the wire number while `sp:index()`
became the position — two things under one name, which is the defect. It now carries `index` (the
position) and `wire` (the raw), so `info().index == :index()`. `DeckCard`'s snapshot field is `slot`,
the client's own word, so it keeps the raw number and `card:wire()` is its live read.

## Where the rows and the finding differ, and what the rows did not say

**`s:speed():get`.** A-072 names only `s:actionbar():get(n)`, and `audit/ns-meter.md` F2's own text
says *"`coll:get(k)` takes the wire number"* — the opposite of what A-072 records. **The row is what
ships.** And A-071 says `:index()` is the position **everywhere**, so leaving `s:speed():get(0..3)`
beside a 1-based `sp:index()` would re-create on speed exactly the trap A-072 removes from the action
bar. The same rule was applied there, and to `:set` with it, or the feature would ship half a surface.

**No `Retired` row is possible.** This is a **reshape**, not a rename: the name stays and the value
changes, so there is nothing to key on — the rule 085.7 wrote into `CLAUDE.md`. What catches it is
`:get(0)` falling out of range, and the page.

## Two arrears of 089, corrected here

`LuaDeckCard`'s `index` still called `handle(self, "slot")` — 089 changed the key and not the argument,
so a dot-call would have said `deckcard:slot()`. And `s:fight():deck()`'s own refusal still taught
*"every card carries its own `:slot()` and `:key()`"*. Both are the 085.7 defect class and both are
fixed. Neither needs an id: they are 089's own surface, found one feature later, and 089's rows are
about the rename that created them.

## Verified

`:t090` — **5 pass, 0 fail, 1 manual**, the manual confirmed with
`s:actionbar():get(1):res()` answering `"paginae/act/push"`, the first slot's own content. Clean
`ant hafen-client`. No addon breaks: `eventstack` reads `m:index()` and `k:index()` only to print
them, which is the "silent for every existing caller" the block names.

**Pages:** `actionbar.md` (the 0-based paragraph rewritten), `references.md` §Slot, `speed.md`,
`fight.md`, `event/bus.md`, `types.md`.
