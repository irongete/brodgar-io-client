# The uniform API (one grammar for every section, and one `position()`)

> **Status:** 🟢 Design closed — shipped as [039-uniform-api](../039-uniform-api/spec.md) · **Spec:** AddOns
> **Series:** area-wide · **Surface:** all 33 `hafen.*` sections, their entities, and the `Position` value
> **Decisions:** the grammar's own — [D-107](../decisions/architecture-api.md) (when a config table becomes
> chained setters the ACTION moves off the constructing call), [D-108](../decisions/architecture-api.md) (a
> mechanism built for later tasks ships with a consumer), [D-109](../decisions/architecture-api.md) (a value
> with two forms DERIVES and offers no equality it cannot keep), [D-110](../decisions/architecture-api.md) (a
> read that asks WHERE must not put traffic on the wire), [D-111](../decisions/architecture-api.md) (a
> PREDICATE about the stored world answers from memory), [D-112](../decisions/architecture-api.md) /
> [D-119](../decisions/architecture-api.md) (a builder is attached INERT and what it draws is the completion),
> [D-113](../decisions/architecture-api.md) (a setter that changes how a visual is BUILT rebuilds it),
> [D-114](../decisions/architecture-api.md) (on a departed owner a creation raises and a removal is inert),
> [D-115](../decisions/architecture-api.md) (a set that CANNOT be enumerated refuses to be),
> [D-116](../decisions/architecture-api.md) (a ref-counted hold gets verbs, not an arity),
> [D-117](../decisions/architecture-api.md) / [D-118](../decisions/architecture-api.md) (how a migration is
> mounted, and what the retired table carries), [D-120](../decisions/architecture-api.md) /
> [D-128](../decisions/architecture-api.md) (keylessness: a builder on one side, a collection without `:get`
> on the other), [D-121](../decisions/widgets-ui.md) (a widget is named by a WIDGET),
> [D-122](../decisions/architecture-api.md) / [D-123](../decisions/architecture-api.md) (a selector-keyed map
> is a document, and a rule handle is a NAME for a level), [D-124](../decisions/widgets-ui.md),
> [D-125](../decisions/architecture-api.md) / [D-129](../decisions/architecture-api.md) (what a CLOSED
> vocabulary and a CLOSED key set refuse), [D-126](../decisions/fonts.md) (a value handle is writable only as
> a DRAFT), [D-127](../decisions/virtual-entities.md) (a thing that cannot EXIST without a place takes it on
> the constructor), [D-130](../decisions/architecture-api.md) (a per-owner refusal),
> [D-131](../decisions/architecture-api.md)..[D-140](../decisions/architecture-api.md) (the OOP half's
> identity rules), [D-141](../decisions/architecture-api.md) (a KIND with no name against a MEMBER whose name
> has not arrived), [D-142](../decisions/architecture-api.md) (the manifest `description` is the AddOns
> panel's page), [D-143](../decisions/architecture-api.md) (R4 governs a BUILDER, so a draw call's trailing
> table is per-call scope and stays);
> and the standing ones this feature generalised — [D-013](../decisions/architecture-api.md) (one canonical
> way), [D-044](../decisions/architecture-api.md) (a verb belongs on the thing),
> [D-056](../decisions/architecture-api.md) (arity is the verb),
> [D-094](../decisions/architecture-api.md) (an intern key follows the engine's own stability),
> [D-099](../decisions/architecture-api.md) (a categorical cost claim is a property of the code's SHAPE)
> **Supersedes:** [06-lua-api.md](06-lua-api.md) in full — the flat namespace, the snapshot-table convention
> and the loose coordinate pairs — and the **spellings** in
> [22-ui-selectors.md](22-ui-selectors.md) (`hafen.ui(sel)`, `hafen.ui.all`, `hafen.ui.on`), whose selector
> grammar itself is untouched
> **Related:** [24-gob-overlays.md](24-gob-overlays.md), [23-map-database.md](23-map-database.md),
> [17-custom-rendering.md](17-custom-rendering.md), [16-virtual-entities.md](16-virtual-entities.md),
> [the API reference](../../../docs/addons/api/conventions.md)

## The problem this solves

The API had grown one section at a time, and each had picked the shape that suited it. `hafen.log("x")` was a
call; `hafen.time.clock()` was a dotted verb; `hafen.gob(id)` was a callable namespace whose arity chose
between an entity and a list; `hafen.ui` was simultaneously the namespace and the root widget;
`hafen.ui.skin{…}`, `hafen.render.sprite{…}` and `hafen.ui.window{…}` each took a different config table. Nine
sections still handed back **snapshot tables** where six others had already migrated to entities, so the same
question — *what is this thing now* — was answered by a live object in one place and by a dead copy in
another.

Two ROADMAP entries had been parked separately ("collection objects instead of arity-as-verb", "finish the
OOP migration"), and either alone leaves the surface half-uniform: **uniform syntax over non-uniform returns
is a veneer.** So they are one feature, and the result is one rule a reader learns once.

Spatial reads had a second, worse problem. There were **four** position verbs (`gob:pos()`,
`hafen.world.gridPos`, `hafen.world.fromGridPos`, `marker:anchor()`) because no single shape was both
*computable* and *durable*: a `{x, y}` in session coordinates does arithmetic and dies at logout, a grid
anchor survives and does no arithmetic. Addons were converting between them by hand, 38 times, and a grid is
1100 world units — so `p.y + 22` at `y = 1095` is a bug that only fires near a grid edge.

## The rule, in one sentence

**A section is CALLED, everything after it is a colon verb, arity is the verb, a set is a collection, and
every read hands back a live object whose `:info()` is the only snapshot.**

## The grammar (the area's permanent rule)

Eight structural rules and three naming rules generate the whole surface. A later feature adds a section by
obeying them, not by choosing a shape.

1. **A section is callable and is a per-addon singleton.** `hafen.time()` is the section object;
   `hafen.time() == hafen.time()`. It stays a callable *table*, never a bare function, so a retired field
   read reaches the refusal instead of failing as *"attempt to index a function"*.
2. **A read is the bare noun, a write is the same name with a value**, and the write returns the receiver so
   it chains. **One name per property** — no `getX`, no `setX`, no `clearX` anywhere.
3. **The noun names the kind, the verb says how many.** A set you can address into is the **singular** kind
   name returning a collection object — `:list(f) :count(f) :get(k) :find(f) :add(…) :remove(x)`, only the
   ones that apply, and **not indexable**. A verb that only answers *give me these* stays plural and returns
   a plain array.
4. **A builder is constructed bare and configured by chained setters.** No `opts` table survives on anything
   that is constructed. The one trailing table left in the API is `g:text`'s, and it is not a builder's: it
   scopes one call of a per-frame value, with nothing outliving the call to configure.
5. **An explicit `nil` argument is an error**, unless the page documents a meaning for it — *undo a layer*
   (`w:position(nil)`) or *none* (`ov:tint(nil)`).
6. **A boolean property is a property**: `:visible()` / `:visible(false)`. The value never lives in the
   verb's name.
7. **Ending a thing has three spellings, and which one applies is structural**: `coll:remove(key)` where the
   collection owns it and you can name it, `:destroy()` where you hold the handle of something you made,
   `:dispose()` only for an owned *resource*.
8. **A distinguished member is a verb on its collection** — `:current()`, `:selected()`, `:leader()`,
   `:available()` — never a second accessor beside it.
9. **Expand an abbreviation a reader cannot decode** (`mtime`, `sdt`, `comp`, `lvl`); keep the game's own
   words (`res`, `lp`, `fep`).
10. **camelCase, always**, in every verb name.
11. **One word per concept** — `:distance()`, never `dist` beside it — and **one `position()`**.

Two rules follow from the first eight and are worth stating because they were both got backwards during the
build. **A collection owned by a SECTION is a singleton; a collection owned by an ENTITY is a view**,
re-derived per call — identity belongs to the members, not to the container. And **every retired spelling
throws, naming its replacement**: a deleted field that reads as plain `nil` fails one line later with a
message that names neither the verb nor the file, which across a surface this size is the difference between
porting an addon and hunting one.

## The four mechanisms, built once

| mechanism | what it is |
|---|---|
| `Section` | the per-addon singleton, minted in `installHafen` and handed back by identity; an unknown verb throws naming the section |
| `LuaCollection` | one type parameterised by its backing read, carrying only the verbs that apply; userdata, so `==` and table-key identity come free |
| `LuaPosition` | the value object below |
| `Retired` | old name → a message naming its replacement, hung off the `hafen` table, each section's table and each entity's metatable |

`Retired` is **pure data generated from the before/after inventory**, which is what makes coverage mechanical
rather than remembered: a spelling that moved with no row is a porting error nobody is told about, and the
close asserts every row at once rather than the ones a task happened to touch.

## One `position()`, and why it is an object

A **Position** holds either a session coordinate or a durable anchor (grid id + within-grid offset) and
**derives** the other on demand. It never converts eagerly, because converting would throw half of it away at
the door: a place recorded in another segment has no session coordinate at all, and still answers `:info()`
and `:durable()` while `:x()` answers nil.

It has deliberately **no `__eq`**. The store round trip is `ul + (wx - ul)`, exact to the tile and not to the
last bit of a double, so an equality over the numbers would call one place two. Sameness is stated as *the
same tile*, which is what a caller means.

It is also the one place the feature adds capability rather than only re-spelling: the vector verbs
(`:offset`, `:distance`, `:tileCoord`) move 38 arithmetic sites into the engine, and `hafen.store` and
`hafen.json` marshal it by its own three-key wire shape, so a saved place comes back a Position rather than a
quoted `tostring`. A non-durable one is **refused** by `encode` naming `:durable()`, rather than written as
`null`.

## What the grammar refuses, and why the refusal is the design

- **Collections are not indexable.** Userdata *does* take `__len`, so this is a design refusal and not a
  capability one: an object that is also a sequence has two ways to enumerate, which is the dual style
  [D-013](../decisions/architecture-api.md) exists to prevent.
- **Arity-as-verb forecloses tooling, permanently.** One symbol with two signatures and two return types
  cannot be typed — no autocomplete, no hover, no type checking — and a read and a write are the same string,
  so neither is greppable. Taken deliberately, in exchange for one rule instead of thirty.
- **`hafen.log():write(msg)` keeps no shortcut**, at 641 call sites. An exception in the busiest verb in the
  API is the exception every reader meets first.
- **A set that cannot be enumerated says so.** The recorded grid database is every grid the character ever
  walked, and the three available answers are a lie (empty), a thousand disk reads (everything), or a
  refusal naming the two verbs that do work.

## Boundaries written down rather than left as gaps

- **`hafen.render` and `hafen.ghost` stay where they are.** Unlike `hafen.gob`, they are not forced by the
  shape change: both already have a coherent form and want only a rename, so moving them under
  `hafen.world()` is its own feature.
- **Nothing changes tier.** The gated verbs are the same gated verbs behind the same per-addon `actions`
  permission; only spelling and return *type* moved, with two stated exceptions — the unified Grid's
  `:live()`, so one engine identity is not split into two Lua entities, and the Position type itself.
- **No deprecation period and no aliases.** Nothing is released, a hard cut is the area's rule, and the port
  of every shipped addon is what proves the cut complete: an unported call site cannot run.
- **The cost claim is categorical, not measured per branch.** A section object is the same object, so naming
  one in a draw callback allocates nothing — a property of the shape, which is why the close measures it
  beside a call that *does* allocate rather than reporting a zero on its own.
