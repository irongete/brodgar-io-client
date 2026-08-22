# 091 — A set is a collection

Discharges: A-073, A-074, A-075, A-076, A-077, A-078, A-079, A-080, A-081, A-082, A-083, A-084.

All twelve are rows in `audit/INVENTORY.md`'s own 091 block. Where a finding offered a lighter option,
the **row's** choice was taken — and in four places the row chose the heavier one.

## What and why

`conventions.md` says a relation whose members are objects is a collection: `#coll` is refused and
`:count()` works. **Seventeen relations broke it**, and nothing in the naming said which was which —
`s:study():curiosity()` was a collection and `s:fight():deck()` an array; `gob:overlay()` a collection
and `gob:sessions()` an array. Four times the two shapes sat **one verb apart on one object**.

Four reads went further and handed back arrays of **anonymous tables**. `i.name or i.res` — the craft
page's own example — was dot-access on a fresh table: a typo read `nil` with nothing to say so,
nothing could be passed back or compared, and `num == -1` was a sentinel a reader had to remember.

And three collections answered a **different question from the one asked**.
`hafen.sound():count()` said "how many are audible" where `:get(name)` mints any clip.
`hafen.font():count()` said `0` on a fresh addon and `4` after four `:get` calls — the addon's history,
not the client's fonts. `s:speed():list()` grew as the character unlocked speeds.

## What shipped

**Nine relations became collections** (A-073, and A-082 with them): `w:children()`, `w:items()`,
`contents:items()`, `q:conditions()`, `pag:children()`, `s:menugrid():roots()`, `seg:markers()`,
`s:fight():deck()`, `gob:sessions()` — each with its own `noGet()` saying how a member is reached.

**Six new types**, each userdata with a closed vocabulary, a `__tostring` and `:info()`:

| Type | Row | Replaces |
|---|---|---|
| `LuaMeterSegment` | A-075 | `meter:segments()`, and the deleted `meter:value()` / `meter:color()` |
| `LuaCraftSpec` | A-076 | the four recipe reads' anonymous `{res, name, num, opt}` |
| `LuaFep`, `LuaFepEntry`, `LuaHunger` | A-077 | `food:cap/total/feps/label/efficacy` |
| `LuaPetal` | A-078 | the ring's array of caption **strings** |

Named `LuaMeterSegment` because `LuaSegment` is the map's.

**Three collections now answer their own question**: `s:speed():list()` is all four with
`:available(f)` as the selectable partition (A-079); `hafen.font():list()` is the four built-ins,
always (A-080); `hafen.sound()` **does not enumerate** — its `members()` raises saying `:get(name)`
mints any clip and `:playing(filter)` is the audible ones (A-081).

**Two degraded payloads hand back what the API already has** (A-083): `FlowerMenuOpened` gives Petals,
`MarkersChanged` gives the marker collection rather than a count.

**`s:kin():add()` returns nothing** (A-084): the server decides whether that secret names anyone, so
there is no `Kin` yet, and the mistake fails at the assignment rather than a line later.

**The three that stay arrays say why** (A-074): `pag:categories()` is strings, `item:slots()` is names,
`s:ui():matchAll(sel)` is a query result.

## Three decisions, each written into the code

**The value-objects are not interned.** A `Segment`, a `CraftSpec` and a `FepEntry` have no key and no
collection destroys one, so two reads are two Lua values. Each javadoc says so and why.

**`hafen.sound():list()` raises rather than answering something else.** The finding offered keeping it
as "everything you have addressed" or removing it; removing it silently would have given the generic
refusal, so `members()` throws with the reason.

**`s:kin():add()` returns `nil`, not the collection.** The row offered "documented, or made `nil`".
Returning the roster made `s:kin():add(x):name()` look plausible; `nil` breaks at the assignment.
`conventions.md`'s `:add` row gains the exception.

## Two corrections to the audit's own count

**`sheet:rules()` does not exist** — only the snapshot field `sheet:info().rules`. A-073 named ten and
one of them was not there, so the conversions are **nine**.

**A-082 is the same conversion A-073 lists**, said twice; one change discharged both. Both rows now
carry these notes.

## Verified

`:t091` — **6 pass, 0 fail, 2 manual**, both manuals confirmed. Clean `ant hafen-client`. Nineteen
pages updated; no deleted read survives anywhere under `docs/`. The open count went **45 → 33**.
