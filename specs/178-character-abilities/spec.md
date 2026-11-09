# 178 — The character's abilities

## What & why

A character has nine base attributes and thirteen abilities: `unarmed`, `melee`, `ranged`, `explore`,
`stealth`, `sewing`, `smithing`, `masonry`, `carpentry`, `cooking`, `farming`, `survive`, `lore`. The
client keeps both as a base and a computed value per name. The API reads only the nine: `session:char():attr()` is a
closed set, and `attr():get("smithing")` raises. The sheet paints an ability's number as a picture, so
no widget read reaches it either.

That leaves a recipe unexplained. `session:craft():qualityInputs()` names the stats a
product's quality draws on, and most recipes name an ability among them (Smithing, Carpentry, Sewing,
Cooking). The quality a character can reach is capped by the geometric mean of those stats' computed
values. An addon can read half of them.

This feature adds **`session:char():ability()`**, a collection beside `attr()`, keyed by the thirteen
names and closed like it. Its members are **`Ability` objects**, a type of their own: `:name()`,
`:base()`, `:composite()`, `:info()`, an `Ability` snapshot `{ base, comp }`, `tostring` reading
`Ability(smithing)`, interned on session and name. An ability is a different thing to the player from
a base attribute, and a reader holding one knows which it holds. The client holds one record kind for both, so one Java class serves both, driven by
an enum of the two kinds, each carrying its type name and its closed set of names. `Attr` keeps
every verb, its name and its nine.

Two collections: `attr():list()` is published as the base attributes, and an addon iterating it
must not start drawing abilities. Every ability starts at 1, so the rule the nine follow holds for the
thirteen: zero on both halves reads as unpublished and never hides a real value, and once the sheet is
up `ability():list()` is all thirteen, in the sheet's order.

`attr():get(name)` keeps refusing an ability's name and its refusal now also names
`session:char():ability()`; `ability():get(name)` refuses a base attribute's name the same way, naming
`attr()`. `craft.md` states what a quality input's `spec:res()` is, `gfx/hud/chr/<name>`, and that
`<name>` is the key of one of the two collections, with an example reading the cap off an open recipe.

*Ability* already names something else in the reference, an action the server pushes into the menu
or the action bar. The new section says which it means, and links the other.

## Acceptance criteria

Each is checked by the suite named in `tasks.md`.

1. `session:char():ability():get(name)` answers an `Ability` for each of the thirteen names, whose
   `:name()` is that name and whose `tostring` is `Ability(<name>)`. Once the sheet is up, each
   answers `:base() >= 1` and `:composite()` a number, and `:info()` equals
   `{ base = :base(), comp = :composite() }`.
2. Once the sheet is up, `:list()` holds the thirteen, each once, in the sheet's order. `:count()`
   equals `#:list()`, and `:find("smith")` answers the `smithing` member.
3. Identity: `ability() == ability()`, `ability():get("smithing") == ability():get("smithing")`, and
   no member equals a member of `attr():list()`. `tostring(attr():get("str"))` is still `Attr(str)`.
4. Refusals, each `pcall`ed and its message checked: `ability():get("str")` names `attr()`;
   `ability():get("smithin")` lists the thirteen; `ability():get(3)` says it expected an ability name;
   `ability(1)` says it takes no arguments; `ability():get("smithing"):bogus()` names an ability;
   `attr():get("smithing")` still raises and names `ability()`.
5. `attr():list()` still holds only base attributes: no member's name is one of the thirteen.
6. With a recipe open within a bounded window after the suite starts, every quality input's `:res()`
   is `gfx/hud/chr/<name>`, with `<name>` a key of `attr()` or `ability()`, and that key's member
   answers a `:composite()`. Scored over what the run reached; no recipe opened is reported, not passed.
7. Three abilities' `:composite()` match the numbers on the Abilities tab (`[manual]`).

## Out of scope

- **The softcap display.** Each quality input's value and the cap in the recipe window is an addon
  of the maintainer's, in `brodgar-io-client-addons`, written once this closes. Nothing here draws.
- **A `spec:attr()` read on a quality input.** The mapping is a documented resource basename, looked
  up in Lua.
- **An `AttrChanged` or `AbilityChanged` event.** Values change only on an action of the player's; the
  page says to read them after it, as it does for the nine.

## Docs impact

Written: `docs/addons/api/char.md` (the Read table row, an *Abilities* section, the intro sentence);
`docs/addons/api/types/character.md` (an `Ability` shape beside `Attr`);
`docs/addons/api/types/README.md` (an `Ability` row);
`docs/addons/api/craft.md` (what a quality input's `res` names, and reading the cap);
`docs/addons/api/conventions.md` (line 65, the collections whose refusal names their keys);
`docs/addons/manifest.md` (`ability()` joins the unreleased `1.8` row);
`docs/client/character-sheet.md` (the Abilities tab: `SAttrWnd.attrs`, the thirteen `SAttr`s, each a
`Glob.getcattr` record, its number painted into `SAttr.ct`).

Derived impact set:

```text
$ grep -rn -i "attr()\|base attribute\|abilit\|the nine\|#attr\b" docs --include=*.md \
    | grep -v "^docs/changelog" | grep -v "^docs/client/"
docs/addons/api/char.md:7,29,41-48         the attr() collection — written
docs/addons/api/types/character.md:15      Attr snapshot — written
docs/addons/api/conventions.md:65          refusal-naming collections — written
docs/addons/api/study.md:77                attr():get("int") — unchanged
docs/addons/api/menugrid.md:209, actionbar.md:61,85,87, types/ui.md:63, shapes.md:69
                                           "ability" = a server-pushed action — unchanged, disambiguated from char.md
(other hits: capability, clickability, durability — unrelated)
$ grep -rn "\bAttr\b" docs/addons --include=*.md | grep -v "api/char.md\|types/character.md"
docs/addons/api/types/README.md:36         Attr row — an Ability row written beside it
```

## Context files

- `src/io/brodgar/addon/LuaAttr.java` — 1
- `src/io/brodgar/addon/CharApi.java` (`chr`, `collection`, around line 1600) — 1
- `src/io/brodgar/addon/LuaCollection.java` — 1
- `src/io/brodgar/addon/Refusal.java` — 1
- `src/haven/SAttrWnd.java` (the constructor's thirteen `SAttr`s) — 1
- `src/io/brodgar/addon/LuaCraft.java`, `LuaCraftSpec.java` — 1
- `docs/addons/api/char.md`, `types/character.md`, `types/README.md`, `craft.md`, `conventions.md`,
  `docs/addons/manifest.md` — 1
- `src/io/brodgar/addon/Addon.java` (the `attrs` cache field) — 1
- `docs/client/character-sheet.md` — 1
- `tools/docverbs.py`, `tools/refusalverbs.py` — 1
- `DOCUMENTATION.md` — 1
