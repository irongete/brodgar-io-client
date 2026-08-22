# 091 — A set is a collection: plan

## Approach

Twelve rows, shipped as one unit with one suite. Three kinds of work: nine mechanical conversions, six
new types, and three collections whose membership was the wrong set.

**`LuaCollection` is a view**, so converting a relation costs one `Source` and no lifetime question:
it holds nothing between calls and reads its `Source` fresh. That is why the nine conversions are
mechanical and why the new types can re-resolve rather than cache.

**`ipairs(x)` on a converted relation breaks loudly**, refused with a message naming `:list()`. That
is the pre-release window doing its job, and it is why nineteen pages moved with the bridge.

## Gotchas found while doing it

**`member.get("name")` on userdata hands back the METHOD.** The deck collection's `needle` was written
that way and would have made a string filter silently match nothing — no error, no result. Since 084
closed the entity types, a field read on one resolves through `closedIndex`, so a `Source` must
`resolve()` the handle and read the record. Caught by re-reading, not by the compiler.

**`Section.self` returns a `Section`, and `LuaCollection.Source.members()` is the only abstract
method.** Everything else on `Source` has a default, which is what keeps a conversion to five lines.

**Three metatable builders had no `owner` in scope.** `LuaMeter` and `LuaFood` build their metatable
from a `Cache` whose `buildMeta()` took nothing; `LuaFood`'s `Cache` did not even keep the `Addon` it
was constructed with. Both had to thread it through before a new type could be minted from a read.

**An em-dash in a Java string is a character, not an escape** — the same trap 090 hit.

**`LuaMarker.collection(owner, seg, filter)` is not a collection.** It builds a `LuaTable`, despite the
name, which is why A-082 was real work rather than already done.

## Discarded alternatives

- **Keeping `meter:segments()` as an array and only deleting `:value()`/`:color()`** — the finding's
  lighter half. A-075 names the collection, and the segments were the clearest case in the API of a
  set of anonymous tables where the rule says objects.
- **Flattening the Food snapshot to match the flat verbs**, the other half of A-077's finding. The row
  names the objects, and flattening would have moved the disagreement rather than removed it: the
  client's own nesting is what `food:info()` reports.
- **Leaving the petals as caption strings**, which `flowermenu.md` defended: the ring is fixed the
  instant it opens and lives about a second. The audit's counter-evidence is that a `Buff` and a
  `Craft` are both objects and both shorter-lived, so "too short-lived for a handle" is not a rule the
  API keeps — and a Petal re-resolves, so one held past the close reports `:exists()` false.
- **Interning the value-objects.** A Segment, a CraftSpec and a FepEntry have no key, and no collection
  destroys one, so there is nothing for identity to buy; a cache per meter per index would be more
  machinery than the fact is worth.
- **Removing `hafen.sound():list()` silently.** It would have fallen through to the generic
  "has no verb" refusal, teaching nothing. Its `members()` raises with the reason instead.
- **Documenting `s:kin():add()`'s collection return rather than changing it** — the row's other
  option. The return was the thing that made `s:kin():add(x):name()` look plausible.
- **Giving `MarkersChanged` the marker that changed.** The event is a sequence bump; which marker moved
  is not in it. The collection is the thing the event is about, and `:count()` still answers what the
  old payload said.
