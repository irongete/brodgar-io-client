# Phase 1c (part 3) — Read API: items, char & party

> **Status:** ✅ Implemented; compile verified (`ant hafen-client` → BUILD SUCCESSFUL) + LuaJ parse of
> the harness; **in-game verified ✅** — `items.inventory()` → 12 named items with grid `pos`,
> `items.equipment()` → 19 slots (two-slot items yield two entries, unnamed slots keep the numeric
> `slot`), `items.find("coin")`, `items.hand()` (cursor item), `char.attrs()` (all nine base+comp),
> `char.lp()`=403475, `char.weight()`=12429, `party.members()` (solo → yourself, leader), and
> `hafen.gob.pos("party1")` all returned correct data. **Note:** char attrs / lp / weight and the
> inventory/equipment **stream in a beat AFTER `OnEnterWorld`** (like the 1c-2 map data) — nil/empty on
> the immediate read, populated a few seconds later.
> **Design:** [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.items`,
> `hafen.char`, `hafen.party`), [specs/addons/code-map.md](../../specs/addons/code-map.md) (Java
> backings). Follows [1c-1 gob & world](phase-1c-read-gobs.md) and
> [1c-2 map/player/time/sound](phase-1c2-map-player-time-sound.md).

This is the **third and final slice of Phase 1c** (the Glob-backed read API). It adds three surfaces,
all **zero core edit** — every backing field/method it reads is already `public`:

- **`hafen.items.*`** — the main inventory, worn equipment, and the cursor item, as **snapshots**.
- **`hafen.char.*`** — character attributes (`Glob.getcattr`), learning points and weight
  (`CharWnd.exp`/`enc`).
- **`hafen.party.*`** — the party roster (`Glob.party`), ordered by member sequence.

It also wires the **`"partyN"` GobRef token** into the central resolver, so `hafen.gob.*("party1")`
now works (see below).

## `hafen.items.*` — inventory, equipment, cursor

```lua
hafen.items.inventory()   -- Item[]: the main inventory (each snapshot has pos = {x,y} grid cell)
hafen.items.equipment()   -- Item[]: worn items (each snapshot has slot = ep index, pos = slot name)
hafen.items.hand()        -- Item | nil: the item held on the cursor
hafen.items.find(q)       -- Item[]: inventory items whose name OR res contains the substring q
```

Each **`Item`** is a point-in-time snapshot (don't cache it across ticks):

```lua
Item = {
  res,    -- string resource name (stable identity), or nil while loading
  name,   -- string display name (ItemInfo.Name), or nil while loading
  num,    -- stack count, or nil (a non-stacked item)
  wear,   -- 0..100 wear/progress %, or nil (only present when > 0)
  pos,    -- inventory: {x,y} grid cell · equipment: slot name string (e.g. "Hat") · hand: absent
  slot,   -- equipment only: the numeric slot index (ep)
}
```

> **Why snapshots (and no `hafen.items.name/count/wear` accessors).** Items have **no stable
> addon-visible id** in this client, so — unlike gobs (`hafen.gob.name(ref)`) — there is nothing to
> re-resolve a single item by. The canonical read is therefore the **snapshot**, which already carries
> `name` / `num` / `wear`. Per-item live accessors will arrive with **item handles** in the UI /
> widget-replacement phase (they need a bridge-owned proxy over the live `GItem`). This keeps the
> "one canonical way" rule (D-013).

**Loading-guarded.** `res` and `name` come from resolving the item's resource / info, which can
throw `Loading` before it lands — both are simply omitted (nil) until they resolve. The inventory
widget and the item info also **stream in a beat after enter-world**, so an immediate read may show
`0 items` or unnamed items and then fill in (the `hello` harness re-reads after 3 s to show this).

**Equipment specifics.** `GameUI.equwnd` is a private `Window`, so the bridge descends to the
`Equipory` widget under the HUD (typically the only one — a second appears only while inspecting
another gob's gear). A worn item that occupies **two slots** appears as **two entries** (one per
slot), each with its own `slot` index. Slot names come from the client's own slot tooltips and may be
nil for unnamed slots (the numeric `slot` is always present).

## `hafen.char.*` — attributes, learning points, weight

```lua
hafen.char.attr("str")   -- {base, comp} | nil   (raw base vs computed/buffed)
hafen.char.attrs()       -- {str={base,comp}, agi=…, …}  the nine base attributes that have data
hafen.char.lp()          -- number | nil   learning points  (CharWnd.exp)
hafen.char.weight()      -- number | nil   encumbrance/weight (CharWnd.enc)
```

The nine base attribute names are **content-defined** (not discoverable from `Glob`), hard-coded as
`str, agi, int, con, prc, csm, dex, wil, psy`. `Glob.getcattr` **never returns null** — it
auto-creates a zero entry for any name — so the bridge reports a `base==0 && comp==0` entry as
**nil** ("not populated by the server yet"); `attrs()` includes only the ones with data.

`lp` / `weight` read **public live fields** on the character window (`CharWnd.exp` / `enc`). That
window is created **hidden at login** and its fields update from server messages regardless of
visibility, so these work **without opening the character sheet** — and without the widget-tree read
mechanism (that mechanism is still needed for FEP / food / skills in Phase 1d). They are `nil` only
before the window exists / before the first update.

> **Timing — char data streams in after enter-world.** In the in-game test, `char.attr` / `lp` /
> `weight` were all **nil at `OnEnterWorld`** and populated a few seconds later (`str` → `124/178`,
> `lp` → `403475`, `weight` → `12429`) — the server sends the `cattr` values and creates `chrwdg`
> shortly after you enter. Same for the inventory/equipment widgets. So, exactly like the 1c-2 map
> data, don't assume these are ready inside the `OnEnterWorld` handler; read on a later tick/timer or
> off a change event. (The `hello` harness re-reads them after 3 s to show this.)

## `hafen.party.*` — the party roster

```lua
hafen.party.members()    -- PartyMember[] ordered by join sequence
hafen.party.leader()     -- PartyMember | nil
hafen.party.member(id)   -- PartyMember | nil   (by gob id)
```

```lua
PartyMember = {
  id,             -- gob id (number)
  x, y,           -- last-known world position (live gob pos if in view, else remembered), or absent
  color,          -- {r,g,b,a} 0..255  the member's party colour
  leader,         -- bool: is this the party leader?
}
```

> **No member names.** `Party.Member` carries a **gob id but no name** — a client/protocol limitation
> (there is no reliable display name for other players; see api-reference "Not reliably available").
> Members out of view report only their **last-known** position (via `Member.getc()`); their live gob
> attributes are nil until they come back into view. Solo, `members()` is an empty array.

### New GobRef token: `"partyN"`

The central `resolve()` now understands **`"party1" .. "partyN"`** (the Nth member by sequence), so
the whole per-gob API accepts it:

```lua
hafen.gob.pos("party1")        -- position of the first party member (nil if out of view)
hafen.gob.distance("party2")   -- their distance from you
```

Ordering is by `Member.seq` (join order) — the same order `hafen.party.members()` returns. If that
member is **out of view**, its gob isn't in the object cache, so `hafen.gob.*("partyN")` returns
**nil** (use `hafen.party.member(id)` for the remembered position). An out-of-range or malformed
token resolves to nil, never an error.

## Try it from the `:lua` console

```
:lua hafen.items.inventory()
:lua hafen.items.equipment()
:lua hafen.items.hand()
:lua hafen.items.find("coin")
:lua hafen.char.attr("str")
:lua hafen.char.attrs()
:lua hafen.char.lp()
:lua hafen.char.weight()
:lua hafen.party.members()
:lua hafen.gob.pos("party1")
```

Results print as compact JSON in-game (`lua= …`) and echo to the terminal tagged `[console]`. Before
you are in the world (or before the widgets exist) reads are empty/nil, never errors.

## The `hello` example (`addons/hello/main.lua`)

On `OnEnterWorld` it now also reads — via two new helpers `readInv(tag)` and `readChar(tag)`, each
run **twice** (immediately as `[now]`, and after 3 s as `[+3s]`, since items/char both stream in) —
the inventory count + first item, the equipment slot count, and the cursor item (`hafen.items.*`),
plus the `str` attribute + `lp` + `weight` (`hafen.char.*`) and the party member count
(`hafen.party.*`). It remains our standing regression harness: one login re-checks Phase 0 / 1a / 1b /
1c-1 / 1c-2 **and** this slice. Bumped to **v0.5.0**.

## How to test in-game

```
ant run
```

On login (terminal), after `[hello] entered the world` and the existing 1c-1/1c-2 lines you should
now also see a `[now]` pass (usually still streaming → `0` / `nil`) and a `[+3s]` pass (resolved):

- `[hello] [+3s] inventory=N item(s), first=<name> x<num>` — **inventory** (`hafen.items.*`),
- `[hello] [+3s] equipment=N slot(s), hand=…` — **equipment** + cursor,
- `[hello] [+3s] char: str=<base>/<comp> lp=<n> weight=<n>` — **attributes + lp + weight**
  (`hafen.char.*`),
- `[hello] [+3s] party: N member(s)` — the **party roster size** (`hafen.party.*`; solo → `1`, you).

Then poke it live with the `:lua` snippets above — e.g. drag an item onto the cursor and run
`:lua hafen.items.hand()`, or open your inventory and run `:lua hafen.items.find("<something>")`.

## Files

- `src/io/brodgar/addon/AddonManager.java` — `hafen.items.*` (inventory/equipment/hand/find),
  `hafen.char.*` (attr/attrs/lp/weight), `hafen.party.*` (members/leader/member); the `"partyN"`
  GobRef token in `resolve()`; and helpers `maininv()` / `equipory()` / `charwnd()` / `cellPos()` /
  `slotOf()` / `slotName()` / `itemName()` / `itemRes()` / `itemSnapshot()` / `attrSnapshot()` /
  `party()` / `partyMembers()` / `partyMemberByOrdinal()` / `memberSnapshot()` / `color()` (+ the
  `ATTR_NAMES` list).
- `addons/hello/` — example + manifest bumped to **v0.5.0** to exercise the new surfaces.
- **No `haven` core edits.**

## Threading & safety

- All reads run on the **UI thread**. Item resource/name resolution **swallows `Loading`** → nil.
- Inventory/equipment reads **walk the `WItem` children** of the `Inventory`/`Equipory` widgets
  (both public), rather than their package-private `wmap` maps — so no reflection and no core edit.
- `party.memb` is **replaced wholesale** off-thread (a new map is assigned, never mutated in place),
  so copying `memb.values()` is snapshot-safe; a defensive catch covers the rare in-flight swap.
- Character attributes read `Glob.getcattr` (synchronized internally); `lp`/`weight` read plain
  public ints.

## Limitations / deferred

- **`quality`** and **`contents`** on items are **not** exposed yet: quality is content-defined (no
  typed field — raw-`tt` parsing only) and container contents need the container widgets. Deferred.
- Per-item accessors (`hafen.items.name/count/wear` as functions) wait for **item handles** (UI
  phase); today the snapshot fields carry that data (the one canonical way).
- **FEP / food / curiosity / skills** (`hafen.char.food/skills`, `hafen.study`) still need the
  widget-tree read mechanism — **Phase 1d**.
- No sandbox/watchdog yet — a runaway Lua scan can still stall the UI thread (Phase 1f).

With this slice, **Phase 1c (the full Glob-backed read API) is complete** — next is Phase 1d (the
widget-tree read mechanism: vitals, buffs, FEP, food, belt, equip-change events).
