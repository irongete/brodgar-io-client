# 064 — what an item holds

## What and why

Two kinds of item hold something, and an `Item` publishes neither:

- **Items inside.** A **stack** (`Dandelion, stack of`, 3 on the icon) and a **creel** both carry
  real items, each with its own quality and its own server address. The client holds them as a
  widget child of the item, which it wraps in the window a hover pops up — there whether or not that
  window is up, because hiding that window does not tear the items down.
- **What the tooltip states.** A bucket, a jug, a barrel carries no items: what it holds is a
  rendered line (`5.00 l of Water`), a quality that is the **content's** rather than the
  container's, and a fill meter that is not `:wear()`.

Five reads on the Item object, all unprotected, plus one change to an existing pair of events:

| Verb | Answers |
|---|---|
| `item:contents()` | a live [`Contents`](#contents) object, or `nil` for an item holding nothing |
| `item:container()` | the `Item` this one sits **inside**, or `nil` for one that sits in a container widget |
| `item:quantity()` | number \| nil — how many this one item is, **replacing `item:num()`** |
| `item:progress()` | number \| nil — `0..1`, the arc the client paints over the icon |
| `item:durability()` | table \| nil — `{cur, max}`, the two counts its tooltip prints |

`item:wear()` is retired and throws. It cannot name one replacement, because it was two things at
once behind a name belonging to neither, and its message names both and says which is which.

One `Contents` type covers both insides. The client cannot tell a stack from a creel — the
difference is the server's, and it never states it — so reading both through one verb means nothing
has to guess, and a design that guessed would answer confidently and wrongly.

### Contents

| Read | Answers |
|---|---|
| `:items()` | `Item[]` — what is inside as live objects; an empty array for a bucket |
| `:name()` | string \| nil — what the server calls this inside, the caption its own window carries |
| `:text()` | string \| nil — the line the tooltip states about what is inside |
| `:quality()` | number \| nil — the content's own quality, distinct from `item:quality()` |
| `:level()` | table \| nil — the fill meter's `{cur, max}` |
| `:info()` | table — the snapshot: every read above **except `:items()`** |

**`widget:items()` is unchanged.** A stack is one item there, as it is one cell on screen. Flattening
it would break `:cell()` and `#items` as the count of slots used, and the caller recurses through
`:contents()` and picks its own depth.

**`ItemAdded` and `ItemRemoved` go deeper than that read**, and this is the one place the two part
company:

- The **events** answer *what entered this container*. An item dropped into a stack or a creel held
  in it fires there, at any depth, because it did arrive in your inventory.
- The **read** answers *what this container draws*, and that item is not drawn a cell of its own.

Subscribing on a container has to reach them without the addon subscribing to every item that
container holds, which does not scale. Both halves are stated on the page together, and
`item:container()` is what a handler uses to place the item it was handed.

**Only the outermost thing that moved is reported.** A dandelion dropped into a stack that was
already there is one addition, and one taken out of it is one removal — but a stack *arriving* or
*leaving* with three dandelions in it fires **once**, for the stack, not four times for a single
movement. So a subscription's seeding is exactly `inventory:items()`, and the events part company
with that read only afterwards, when something moves on its own inside a container that stayed.

**`item:container()` is the exact inverse of `:contents()`.** `a:contents():items()` holds `b` if
and only if `b:container()` is `a`, which is a round trip a suite can assert both ways. It chains:
a dandelion in a stack in a creel answers the stack, and the stack answers the creel. It is a
*where* read, so like `:cell()`, `:slots()` and `:handle()` it answers `nil` on a stale item — where
it is is exactly what a departed item no longer has.

**A snapshot holds no live objects.** That one rule is why `contents:info()` carries no `items` and
why `item:info()` carries no `container`: a snapshot of a bag would otherwise nest snapshots of
bags without end.

**`item:quantity()` is the third answer to "how many", and it is none of the others.** A counted item
(`42 seeds of Hemp`) is one thing with one quality and no parts, which is why it holds nothing and
`:contents()` is `nil` for it. On a stack the same number is how many are inside, so it agrees with
`#contents:items()`. `item:num()` is retired and throws, naming its replacement.

**A read answers what the client draws.** Both numbers an item wears on its icon have **two**
sources — a field a server message writes, and info the item's own tooltip publishes — and the
client falls back from one to the other as it paints. The verbs read the field alone today, so each
answers `nil` on the very items that visibly show the thing it names: the icon's number comes
**only** from the tooltip half, and the arc comes from the field when that is set and from the
tooltip otherwise. `:quantity()` and `:progress()` fold the same two sources in the same order the
client does, which makes one contract checkable by eye for both: **if you can see it on the icon,
the verb answers it.**

**`:progress()` and `:durability()` are different data, not two views of one.** The arc is a
**fraction with no units** — `WItem.draw` paints it as a wedge, and the client cannot say *132 of
150* there, only *how far round*. What it measures is the server's business, which is why the verb
names the arc and not a magnitude. Durability is **two absolute counts** printed as a tooltip row,
and only those let you work out what is left. Neither converts into the other, an item may carry
both, and folding them into one verb would mean hiding one or returning a different shape per item —
the polymorphic return this feature already refused for `:contents()`.

**`:progress()` answers `0..1`**, as `slot:progress()` already does and as every fraction in the API
is spelled — the progress-bar control's value, `g:prect`'s wedge. The retired verb answered
`0..100`; the units move with the name, since it is retired either way. `hafen.ui():progress()` builds a control and this reads a number, so the page disambiguates the
two in a line.

## What it looks like

The inventory of a stack of three dandelions beside a counted item:

```lua
local inv = hafen.ui():inventory():items()   -- two items, two cells
local st, sd = inv[1], inv[2]

st:name()                  --> "Dandelion, stack of"
st:quantity()              --> 3              the number on the icon...
st:contents():items()      --> { Item, Item, Item }   ...and the things it counts
st:contents():text()       --> nil            what is inside are widgets, not a stated line
st:contents():level()      --> nil

local one = st:contents():items()[1]
one:quality()              --> its own, not the stack's
one:cell()                 --> nil            its parent is not the inventory
one:container() == st      --> true
one:take()                 --> lifts that one dandelion...
st:take()                  --> ...and this one lifts the whole pile, in one message

sd:name()                  --> "42 seeds of Hemp"
sd:quantity()              --> 42
sd:contents()              --> nil            a counted item holds nothing
sd:container()             --> nil            it sits in the inventory itself
```

And a bucket, where the same verb answers the other inside:

```lua
b:contents():text()        --> "5.00 l of Water"
b:contents():quality()     --> the water's, not the bucket's
b:contents():level()       --> { cur = 5, max = 10 }
b:contents():items()       --> { }
```

And the two numbers an item wears, which are not the same number:

```lua
axe:progress()             --> nil                        no arc painted on this icon
axe:durability()           --> { cur = 132, max = 150 }   the counts its tooltip prints

x:progress()               --> 0.63                       the arc, 63% of the way round
x:durability()             --> nil                        no wear row in its tooltip
```

## Acceptance criteria

1. `item:contents()` is `nil` for an item holding nothing, and while its info is still resolving;
   never a half-built object.
2. `Contents` is **interned**: two reads of the same item's contents are `==`.
3. On a stack, `:items()` answers one `Item` per contained thing — each interned, each answering
   `:res()` and its **own** `:quality()`, and none of them the stack.
4. An item inside a stack answers `:cell()` as `nil`, and `:take()` on it reaches that one thing —
   while the same verb on the stack, which is an ordinary item in its container, still moves the
   whole pile in one message.
5. `:items()` answers an empty array, never `nil`, for a bucket; `:text()` and `:level()` answer
   `nil` for a stack.
6. On a liquid container, `:text()` is a non-empty string and `:level()` is `{cur, max}` with
   `cur <= max`; `:quality()` and `item:quality()` both read, independently, on that one item.
7. The reads do not need the hover window up: they answer the same with it hidden.
8. `contents:info()` carries the three tooltip reads and **no** `items`; `item:info()` carries
   `contents` as that snapshot and **no** `container`.
9. `item:container()` answers the stack for an item inside one, `nil` for an item sitting in a
   container widget, and `nil` for a stale item.
10. The round trip holds both ways: every item in `a:contents():items()` answers `a` to
    `:container()`, and it chains through a container inside a container.
11. `inventory:on("ItemAdded")` fires with the contained item when one enters a stack or a creel
    held in that inventory, at any depth, and `ItemRemoved` when one is taken out of it — while
    that container itself stays put.
12. Only the **outermost** thing that moved is reported: a stack arriving or leaving with things
    inside it fires **once**, for the stack, and never once per thing it carries.
13. Subscribing seeds with exactly what `inventory:items()` answers; a contained item is reported
    only when it later moves on its own, and never appears in that read.
14. `item:quantity()` answers the number the icon shows — for a counted item and for a stack alike —
    and `nil` for one that shows none. On a stack it equals `#item:contents():items()`.
15. `item:progress()` answers a `0..1` fraction for an item painting an arc from **either** source,
    and `nil` for one painting none — never a number above 1, which is what reading the old field
    without converting would give.
16. `item:durability()` answers `{cur, max}` with `cur <= max` for an item whose tooltip prints
    them, and `nil` for one that does not — including while its info is still resolving.
17. An item may answer both, and `:durability()` is not derivable from `:progress()`: the suite
    asserts they are read independently rather than one folded into the other.
18. `item:num()` raises naming `:quantity()`; `item:wear()` raises naming **both** `:progress()` and
    `:durability()` and saying which is which. Neither retired spelling appears under `docs/`.
19. `contents:name()` answers the caption the server gave that inside, and `nil` when it gave none.
20. Every verb this feature adds raises on a `.` call, naming the colon call on an Item object.

## Out of scope

Nothing about items or the inventory is deferred. What is listed here is already answered elsewhere,
or is a decision rather than a gap:

- **Putting something into a container.** Already shipped, and no new verb is warranted:
  `hafen.player():hand():use(item)` sends that item's `itemact` — exactly what the client sends when
  you drop what you are holding onto it. `item:transfer()` covers the other direction.
- **Opening a creel's window.** Already shipped: `item:use()` sends the right-click the player uses,
  and the message that pins the window open is the **server's** answer to it, not something any
  client can send. Reading does not need it open either way.
- **Subscribing on a `Contents`.** A decision: the container's own events already reach what is
  inside it, so a second door onto the same arrival would fire twice for one movement.

## Docs impact

Pages written:

- `docs/addons/api/ui/items.md` — `item:contents()`, the `Contents` type, `item:container()`, and
  `item:quantity()` / `item:progress()` / `item:durability()` in place of the two retired rows, plus
  the line separating `item:progress()` from the control `hafen.ui():progress()` builds, the
  deep-event rule in **The container lifecycle**, and the rewrite of **Where the item reads end**.
- `docs/addons/api/types.md` — the snapshot's `contents`, `quantity`, `progress` and `durability`
  fields in place of `num` and `wear`, and what a snapshot omits and why.

  The items page carries the **liquid** case as its own worked block, because a reader arriving with
  a bucket should not have to derive it: a stated line, a fill meter and no items is what a liquid
  looks like from here, and `c:level()` answering is how you ask. The page says in the same breath
  that the client is never told the substance, so that is the whole of the question it can answer.
- `docs/client/state.md` — the items row: the contents widget, its window and the back-link to the
  owning item, the tooltip's contents block with the published fill-meter class, and that nothing in
  the client draws `GItem.num`.
- `docs/client/glossary.md` and `docs/client/services.md` — the same correction where each calls
  that field the stack count.

Derived impact set, one grep per surface:

```text
grep -rniE "liquid|contents|contains|container holds|inside it" docs/ --include=*.md  -> 39 hits
grep -rniE ":num\(\)|\bnum\b|stack count|quantity|stacked" docs/ --include=*.md       -> 14 hits
grep -rniE ":wear\(\)|\bwear\b|durability|progress" docs/ --include=*.md              -> 34 hits
```

Two of the 39 are this surface, and both are **false**. Both are written in the negative, which is
why no grep aimed at the new verbs would have found them:

- `docs/addons/api/types.md:58` — "What a container held as an item has inside it is not exposed:
  the client only knows that while the container's own window is open, so there is nothing to read
  here." Rewritten.
- `docs/addons/api/ui/items.md:134` — the **Where the item reads end** section, saying the same at
  more length. Rewritten to state what genuinely remains unreadable.

The rest of the 39 use `contents` / `contains` in unrelated senses — a file's bytes, a window packing
around its children, the `*=` selector operator — and are **discharged**. `docs/client/ui-chrome.md`
names the contents window's decoration already and stands as it is.

Of the 14, four are the retired verb and are rewritten — `docs/addons/api/types.md:51` (the snapshot
field), and `docs/addons/api/ui/items.md:10`, `:39` and `:52` (the opening example, the table row,
and the prose about what a stale item still answers). Three more are the engine map calling that
field the stack count with no note that nothing draws it, and are corrected in place:
`docs/client/state.md:14`, `docs/client/glossary.md:70` and `docs/client/services.md:36`. The
remaining seven are a **CraftSpec**'s own `num` and a flower petal's, different fields entirely, and
are **discharged**.

Of the 34, three are the retired verb, and each states one number where there are two:
`docs/addons/api/types.md:52` and `docs/addons/api/ui/items.md:40` are replaced by the two fields
and the two rows, and `docs/client/services.md:36` — which already needs the `num` correction —
takes both in the same line. Four more are read rather than counted, because the new name lands
beside them: `docs/addons/api/study.md:41` (`slot:progress()`, the `0..1` precedent this follows),
`docs/addons/api/ui/controls/display.md:51` and `README.md:66` (the control, which the items page
now points away from), and `docs/addons/api/ui/style/surfaces.md:124`, which lists `Wear` among the
rows drawn by code shipping inside the game resources — the very fact `:durability()` rests on.
Those four stand as they are. The rest are `progress` as a credo's pursuit, a study slot's own
number or the map's exploration, and `wear` as what a surface does to a frame; all **discharged**.

## Context files

- `src/io/brodgar/addon/LuaItem.java` — 1, 2, 3, 4, 5
- `src/io/brodgar/addon/LuaContents.java` — 2 (the type, its cache and its reads; `Addon.contents` is where
  its per-addon cache is declared)
- `src/haven/GItem.java` — 1, 3, 4
- `src/haven/ItemInfo.java` — 2, 5
- `src/haven/WItem.java` — 4 (`draw` is the authority on which source wins, for both numbers)
- `src/io/brodgar/addon/LuaWidget.java` — 1
- `src/io/brodgar/addon/WidgetSubs.java` — 3
- `src/io/brodgar/addon/Retired.java` — 4, 5
- `docs/addons/api/ui/items.md` — 1, 2, 3, 4, 5
- `docs/addons/api/types.md` — 1, 2, 4, 5
- `docs/client/state.md` — 1, 2, 4
- `docs/client/glossary.md` — 4
- `docs/client/services.md` — 4
- `DOCUMENTATION.md` — 1, 2, 3, 4, 5
- `addons/profiler/` — 1, 2, 3, 4, 5 (a manifest and layout to copy)
