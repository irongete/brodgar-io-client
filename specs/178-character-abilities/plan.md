# 178 — Plan

## Approach

**One class, two kinds.** `LuaAttr` gains a nested `enum Kind`:

| Kind | Lua type (`__name`, `tostring`) | Collection | Names | Blurb |
|---|---|---|---|---|
| `BASE` | `Attr` | `attr` | `str agi int con prc csm dex wil psy` | `a character attribute` |
| `ABILITY` | `Ability` | `ability` | `unarmed melee ranged explore stealth sewing smithing masonry carpentry cooking farming survive lore` | `a character ability` |

The thirteen are in `SAttrWnd`'s constructor order, which is the sheet's and the order `:list()` keeps.
`NAMES` stays as `Kind.BASE`'s array so nothing else reading it moves. The handle carries its `Kind`
beside `user` and `name`; `known(name)`, `namesList()`, `handle(self, method)`'s colon-call error
(`attr:`/`ability:` and its `:get(name)` hint), `toString()` and `collection(owner, user)` all take
the kind. The reads are unchanged: `attr(user, name)` goes through `Glob.getcattr`, and zero on both
halves is unpublished — sound for abilities, which start at 1.

**One cache per kind.** `LuaAttr.Cache` takes its `Kind` and builds that kind's metatable. `Addon`
holds `attrs` (`Kind.BASE`) as today and gains `abilities` (`Kind.ABILITY`) beside it; `of(owner,
user, name)` picks the cache by kind. Interning stays per `(user, name)` inside a cache, so
`get("smithing") == get("smithing")`, and no Ability equals an Attr: two caches, two metatables.

**The metatable keeps a literal entity.** `buildMeta` calls `Refusal.closedIndex("attr", …)` or
`Refusal.closedIndex("ability", …)` through a switch on the kind, never `closedIndex(kind.entity, …)`:
`tools/docverbs.py` and `tools/refusalverbs.py` read the entity from that literal. Both literals sit
in `LuaAttr.java`, whose verbs the tools union per file; the two vocabularies are identical, so the
union is exact.

**The collection.** `CharApi.chr` mints `LuaAttr.collection(owner, user, Kind.ABILITY)` once and sets
`chr.set("ability", collection("char", C, "ability", abilities))`. `getMember` keeps the `TSTRING`
check (message: `expected an ability name`), then: a name of the **other** kind throws itself —
`session:char():ability():get("str"): str is a base attribute — session:char():attr():get("str")`,
and the mirror on `attr()` naming `session:char():ability()`; any other unknown name falls to
`Missing.RAISE` with `keys()` listing that kind's names. `missing()`, `named()`, `addressable()`,
`keyName()` as today.

**`:info()`** returns the same `{ base, comp }` table for both kinds; the type page documents it twice
under its two names.

**`craft.md`.** A rule row on *A spec*: a quality input's `spec:res()` is `gfx/hud/chr/<name>`, and
`<name>` is a key of `session:char():attr()` or `:ability()`. One example (≤ 12 lines) builds a
name → member table from both `:list()`s, then takes the geometric mean of the quality inputs'
`:composite()` — the cap — skipping the recipe when any is missing.

**`char.md`.** The intro lists abilities; the *Read* table gains `session:char():ability()`; an
*Abilities* section after *Attributes*: the four `ability:` verbs, the four collection verbs, the
names in sheet order, the closed-set rule naming the cross-kind refusal, identity, and one line that
these are the sheet's Abilities tab, not [the actions the server pushes](menugrid.md) to the menu and
the action bar. The *Streams in after login* rule covers both.

## Files to create/modify

- `src/io/brodgar/addon/LuaAttr.java` — `Kind`, kind-aware handle, cache, metatable, collection.
- `src/io/brodgar/addon/Addon.java` — the `abilities` cache field beside `attrs` (line ~1035).
- `src/io/brodgar/addon/CharApi.java` — `chr`: the `ability` collection; its javadoc's collection count.
- `tools/docverbs.py` — `RECEIVERS`: `"ability": "ability"`.
- `tools/refusalverbs.py` — the receiver map: `"ability": "ability"`, and
  `"session:char():ability()": "ability"` beside the `attr()` entry.
- `docs/addons/api/char.md` — as above.
- `docs/addons/api/types/character.md` — an `## Ability` section beside `## Attr`.
- `docs/addons/api/types/README.md` — an `Ability` row after `Attr`.
- `docs/addons/api/craft.md` — the quality-input rule and the cap example.
- `docs/addons/api/conventions.md` — line 65 adds `session:char():ability()`.
- `docs/addons/manifest.md` — the `1.8` row adds `session:char():ability()`. `1.8` is in no release
  yet (`v17` predates `ApiVersion.CURRENT = 1.8`); if a tag holds it by the time this lands, the verb
  takes `1.9` instead: `ApiVersion.CURRENT` and a new row.
- `docs/client/character-sheet.md` — an *Abilities* row in the tab table: `CharWnd.sattr`
  (`SAttrWnd`) `attrs`, thirteen `SAttr` (`CharWnd.AttrWdg`) built in a fixed order, each holding
  `Glob.getcattr(nm)`; the number is painted into the private `SAttr.ct` (`Tex`) on `tick`, so no
  widget text carries it, and `Glob.CAttr{base,comp}` is the only read.
- `addons/178-character-abilities.1/` — the suite.

## Risks & gotchas

- **The qualified resource name.** `Glob.CAttr.res()` is `gfx/hud/chr/<nm>`, and `Makewindow` draws a
  quality input from `qmod` (a `List<Indir<Resource>>`, read by `LuaCraft` at line ~201). That the
  server names a quality input by exactly that resource is inferred, not read: the suite's criterion
  6 is what confirms it, and `craft.md` is written to what it observes.
- **The refusal's per-name sentence.** `LuaCollection` reads `keys()` with no key, so the cross-kind
  sentence is thrown from `getMember`, before the `Missing.RAISE` path. Keep `keys()` for the rest.
- **`refusalverbs.py`** checks every verb a refusal offers as a replacement: the new sentences offer
  `session:char():attr():get` and `session:char():ability():get`, so both receivers must be mapped
  or the checker counts them as skipped.
- **A Java change needs `ant` and a client restart**; `rm -rf build/classes` first for a true compile.
- `docverbs.py` also holds `ApiVersion.CURRENT` against the documented version: run it after the
  manifest page changes.

## Discarded alternatives

- **Widening `attr()` to twenty-two names.** `attr():list()` is published as the base attributes, and
  an addon drawing it would suddenly draw thirteen more rows.
- **Abilities as `Attr` objects in their own collection.** The player tells the two apart and the
  reader should too: a handle printing `Attr(smithing)` says something the sheet does not.
- **A separate `LuaAbility` class.** Its every line would repeat `LuaAttr`'s; the engine holds one
  record kind for both, and an enum names the difference in one place.
- **Lua-visible enum constants (`hafen.ability.SMITHING`).** Every closed set in the API is keyed by
  strings and refused with its list; a second spelling of a key is a second way to do one thing.
- **A `spec:attr()` read on a quality input.** The basename is documented and the lookup is one table
  in Lua; a read handing back one of two types by the server's resource adds a verb to save a line.
- **A published flag on `Glob.CAttr` (an `addon:` edit).** Nothing a character holds is zero, so
  zero on both halves already means unpublished.
- **Reading the number off the sheet's widgets.** `SAttr` paints it into a texture, and a widget read
  would depend on the Abilities tab's layout rather than on the record it draws.
