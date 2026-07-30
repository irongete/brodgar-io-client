# Public Lua API Contract (Reference)

> **Status:** 🟡 Draft — backings verified against code; ⚠ now marks best-effort/content-defined surfaces (not "unverified")
> **Spec:** AddOns · **Related:** [06-lua-api.md](design/06-lua-api.md) (overview), [../codebase-map.md](../codebase-map.md), [09-events-catalog.md](design/09-events-catalog.md)

This is the **authoritative list of executable Lua functions** addon developers will use —
segregated by category, like WoW's API (`UnitHealth`, `GetItemInfo`, `C_Map.*`, `SendChatMessage`,
`PlaySound`). Every function lists its **Java backing** ([../codebase-map.md](../codebase-map.md)).

Signatures are a **proposal for review**. ⚠ marks surfaces that are **best-effort or
content-defined** (readable but unstable), not unverified; things that are genuinely unavailable
are listed under "Not reliably available" at the end.

## Naming convention ([Q-011](DECISIONS.md))

Canonical style is **namespaced tables**: `hafen.<category>.<verb>()` (e.g. `hafen.world.gobs()`,
`hafen.item.name(h)`). This mirrors WoW's modern `C_*` namespaces, avoids global pollution, and is
discoverable. WoW's older flat globals (`UnitHealth`, `ItemName`) are shown as **≈ analogs** for
orientation. Whether we also expose **flat aliases** (`GobName()`, `ItemName()`) is open — see
[Q-011](DECISIONS.md); recommendation is namespaced-only for v1.

## Conventions

- **Snapshots vs handles.** Bulk reads return plain Lua tables (**snapshots**) — point-in-time
  copies; don't cache them across ticks. Interactive things return **handles** (`Window`, `Item`,
  `WidgetModel`) — bridge-owned proxies with methods, auto-released on teardown
  ([05-lifecycle-and-reload.md](design/05-lifecycle-and-reload.md)).
- **References, not implicit state.** You address entities by a **reference** (like WoW unit
  tokens), not global state — see the next section.
- **Coordinates** are Lua numbers in **world units** unless noted; conversions in `hafen.map`.
- **Threading.** All calls run on the UI thread ([P5](design/01-architecture.md)); never block.
- **Missing data.** Returns `nil` when not available yet (e.g. before world load), never throws
  `Loading` into Lua (the bridge swallows it).
- **Gating.** `hafen.act.*` (actions) runs only for an addon that DECLARED `"permissions": ["actions"]`
  and which the user granted by enabling it (with the consent dialog) — a per-addon permission, no global
  switch ([D-010](decisions/actions-permissions.md)/[D-027](decisions/actions-permissions.md)/[D-028](decisions/actions-permissions.md), [12-security-and-permissions.md](design/12-security-and-permissions.md)).

---

## References & handles (how you address things)

> Decisions: [D-012](decisions/architecture-api.md) (reference model), [D-013](decisions/architecture-api.md) (one way); open: [Q-013](DECISIONS.md).

Like WoW (`UnitHealth("player")`, `GetItemInfo(itemID)`), our functions take an explicit
**reference** to the thing you want — they are not implicit/global. Three reference kinds:

### GobRef — a gob (WoW "unit" analog)
A **gob id** (number) **or** a **token** (string). This is Hafen's equivalent of a WoW unit token
/ GUID. Reference-based accessors **re-resolve each call** → always fresh; return `nil` if the gob
is gone. (Contrast: a snapshot is a copy and goes stale.)

Tokens and their backing:

| Token | Resolves to | Backing / caveat |
|---|---|---|
| `"player"` | your own gob | `MapView.plgob`/`player()` ([:1133](src/haven/MapView.java:1133)) ✅ |
| `"party1".."partyN"` | party member N | ⚠ `Glob.party.memb` is a **gobid-keyed HashMap** ([Party.java:33](src/haven/Party.java:33)) with **no ordinal index** — `partyN` order must be imposed from `Member.seq` ([Party.java:45](src/haven/Party.java:45)). Gob may be **out of view** → only last-known pos via `Member.getc()`, full attrs nil |
| `"target"` | current combat target | `Fightview.current.gobid` ([:47](src/haven/Fightview.java:47)) — **only during combat**, else nil |
| `"mouseover"` | gob under the cursor | ⚠ needs hover hit-test tracking — possible extension, not guaranteed v1 |
| *(number)* | that gob id | `OCache.getgob(id)` ([:199](src/haven/OCache.java:199)) |

```lua
hafen.gob.health("player")     -- like UnitHealth("player")
hafen.gob.health(gobid)        -- like UnitHealth(guid)
hafen.gob.name("target")
```

### ItemRef — an item
Items carry no *content* id, so they are addressed by a **handle** = the item's **server widget id**
(`GItem.wdgid()`), exposed as the **`handle` field on every Item snapshot** (from
`hafen.items.inventory()`/`equipment()`/`hand()`/`find()`, or a replaced-widget model,
[08](design/08-widget-replacement.md)). **Handle only** — no `(container, slot)` alternative ([D-022](decisions/architecture-api.md)).
The gated verb `hafen.act.item(item, verb)` takes the snapshot (reads its `handle`) or the raw handle
number, and **re-resolves the live `GItem` each call** (`UI.getwidget(id)`) — always fresh, like a GobRef;
a stale/used/moved item no longer resolves → a guiding error.

### WidgetRef — a window/widget
A **handle** returned by `hafen.ui.*` (or a replace/model handle), or (advanced) a server widget
id. Not a token.

### Calling style ([D-012](decisions/architecture-api.md)) — ONE way, deliberately
A single style: a **flat accessor that takes a GobRef**. There is **no** handle/OO alternative —
exactly one way to do each thing.
- `hafen.gob.health(ref)`, `hafen.gob.name(ref)`, `hafen.gob.pos(ref)` — read one attribute, fresh.
- `hafen.gob.info(ref)` — a full snapshot of one gob in a single call.
- `hafen.world.gobs(filter)` — enumerate many (returns snapshots), for scanning.

All per-gob reads live under **`hafen.gob.*(ref)`** (canonical). `hafen.player` / `hafen.char` /
`hafen.party` expose only data with **no** per-gob equivalent (local char name, vital bars,
character attributes, party roster).

---

## Data types (snapshot shapes)

Returned by the read APIs. Field availability noted where partial.

```lua
Gob = {                       -- a game object
  id,                         -- number (stable id)
  name,                       -- string, Drawable.getres().name = the TYPE/what-it-is identity
                              --   e.g. "gfx/kritter/rabbit/rabbit"  (NOT a player's display name)
  x, y,                       -- world position (Coord2d)
  angle,                      -- facing radians
  moving, speed,              -- bool + number (from Moving attr), speed nil if not moving
  hp,                         -- 0..1 REMAINING integrity (1=undamaged, 0=destroyed; GobHealth, quarter steps), or nil
  icon,                       -- minimap icon/category name, or nil (Loading-guarded)
  speech,                     -- current floating speech text, or nil (Speaking, reliable)
  overlays,                   -- array of active overlay resource names (Gob.ols): crop stage, fire, drying… (Loading-guarded)
  isplayer,                   -- bool (Drawable res == "gfx/borka/body")
}

Item = {                      -- an inventory/equipment item (snapshot)
  name,                       -- string (ItemInfo.Name display text)
  res,                        -- string resource name (stable identity)
  num,                        -- stack count or nil (GItem.num == -1 → nil)
  wear,                       -- 0..100 wear/progress % (GItem.meter)
  quality,                    -- number, best-effort/content-defined, or nil  ⚠ (no typed field)
  pos,                        -- {x,y} grid cell (from the WItem cell, not GItem), or slot name for equipment
  handle,                     -- the item's server widget id (GItem.wdgid()); the ItemRef hafen.act.item(item, verb)
                              --   takes to re-resolve + drive the LIVE item (4f). Stable while the item exists; the
                              --   only stable way to address an item (D-022). Omitted for an unbound item.
}

Tile = { id, name }           -- tileset id + resource name at a coord
Attr = { base, comp }         -- character attribute: raw base vs computed/buffed
PartyMember = { id, x, y, color, leader }   -- NB: no name field exists for party members
Message = {                   -- chat message
  channel,                    -- channel name/kind
  from,                       -- buddy id of sender (number), or nil (self/system)
  fromName,                   -- display name ONLY if the sender is on your kin list, else nil
  gobid,                      -- sender gob id (party chat only), or nil
  text,                       -- string
  time,                       -- Unix epoch seconds (wall clock)
}
Buff = {                      -- buff/debuff
  res, name,                  -- resource + tooltip name (stable)
  amount,                     -- 0..1 fraction, content-dependent, often nil
  cooldown,                   -- 0..1 fraction, content-dependent, often nil (NOT seconds)
  number,                     -- integer overlay, content-dependent, often nil
}
```

---

## `hafen.player` — player-only data
Backing: [`MapView.plgob`](src/haven/MapView.java:45). **For the player's gob attributes
(position, health, moving, …) use `hafen.gob.*("player")`.** This section holds only data with no
per-gob equivalent.

| Function | Returns | Backing |
|---|---|---|
| `hafen.player.exists()` | bool | `MapView.plgob >= 0` |
| `hafen.player.id()` | number \| nil | `MapView.plgob` (the `"player"` GobRef) |
| `hafen.player.name()` | string \| nil | **local** char name via `GameUI.chrid` ([:257](src/haven/GameUI.java:257)); other players' names not reliable (see "Not reliably available") |
| `hafen.player.vitals()` | `{hp,stamina,energy}` 0..1 \| nil | ⚠ **bar fractions only** (no absolute values, no hunger). Backing fields are **private/protected** (`GameUI.meters` [:49](src/haven/GameUI.java:49), `LayerMeter.meters` [LayerMeter.java:35](src/haven/LayerMeter.java:35)), identified by matching `IMeter.bg` — reading them needs **reflection or a core hook**, so this is **NOT a zero-edit read** (see [coverage-gaps.md](ROADMAP.md) B5) |
| `hafen.player.worldToScreen(x,y)` | `{x,y}` \| nil | camera projection (overlay helper) |

## `hafen.gob` — a gob by reference  ≈ WoW `UnitHealth(unit)` / `UnitName(unit)`
The canonical per-gob accessor. `ref` = a **GobRef** (gob id or token `"player"` / `"target"` /
`"partyN"`; see [References & handles](#references--handles-how-you-address-things)). Re-resolves
each call; returns `nil` if the gob is gone.

| Function | Returns | Backing |
|---|---|---|
| `hafen.gob.exists(ref)` | bool | `OCache.getgob` non-nil ([:199](src/haven/OCache.java:199)) |
| `hafen.gob.info(ref)` | `Gob` snapshot \| nil | full snapshot in one call |
| `hafen.gob.pos(ref)` | `{x,y}` \| nil | `Gob.rc` |
| `hafen.gob.facing(ref)` | number \| nil | `Gob.a` |
| `hafen.gob.name(ref)` | string \| nil | `getattr(Drawable).getres().name` — type identity, not a display name |
| `hafen.gob.health(ref)` | 0..1 \| nil | `getattr(GobHealth).hp` ([:35](src/haven/GobHealth.java:35)) — "GobDurability" analog |
| `hafen.gob.moving(ref)` | bool | `getattr(Moving)` present |
| `hafen.gob.speed(ref)` | number \| nil | `getattr(Moving).getv()` |
| `hafen.gob.speech(ref)` | string \| nil | `getattr(Speaking).text.text` ([Speaking.java:37](src/haven/Speaking.java:37)) |
| `hafen.gob.icon(ref)` | string \| nil | `getattr(GobIcon).icon().name()` ([GobIcon.java:64](src/haven/GobIcon.java:64)) — Loading-guarded |
| `hafen.gob.distance(ref [, ref2])` | number \| nil | `rc.dist` (default `ref2` = `"player"`) |

## `hafen.world` — enumerate gobs  ≈ WoW nameplate scan
Backing: [`OCache`](src/haven/OCache.java:35). Enumeration returns **snapshots** (point-in-time);
to read one gob fresh, use `hafen.gob.*(ref)`.

| Function | Returns | Backing |
|---|---|---|
| `hafen.world.gobs([filter])` | `Gob[]` (snapshots) | iterate `oc` under `synchronized(oc)` ([:164](src/haven/OCache.java:164)) |
| `hafen.world.nearest(filter)` | `Gob` \| nil | scan + `rc.dist` (filter = fn or name substring) |
| `hafen.world.within(radius, filter)` | `Gob[]` | scan by distance |
| `hafen.world.count(filter)` | number | scan |

> Prefer the `GobAdded`/`GobRemoved` events ([09](design/09-events-catalog.md), via `OCache.callback`)
> over per-frame scanning.

## `hafen.map` — terrain  ≈ WoW `C_Map.*`
Backing: [`MCache`](src/haven/MCache.java:36).

| Function | Returns | Backing |
|---|---|---|
| `hafen.map.tile(x,y)` | `Tile` \| nil | `gettile(tc)` ([:929](src/haven/MCache.java:929)) + `tilesetr(id).name` ([:1107](src/haven/MCache.java:1107)) |
| `hafen.map.height(x,y)` | number \| nil | `getcz(Coord2d)` ([:948](src/haven/MCache.java:948)) |
| `hafen.map.grid(x,y)` | `{id, gc, seg}` \| nil | `getgrid(gc)` ([:909](src/haven/MCache.java:909)): `id` = **stable global grid id** (`Grid.id`, same for all players), `gc` = session-local grid coord, `seg` = **local** segment id |
| `hafen.map.gridPos([x,y])` | `{gridId, x, y}` \| nil | the **shareable/persistent** position — stable grid id + within-grid offset (0..1099); **no args = player**. Use this, not `rc`, across sessions/players ([C4](ROADMAP.md)) |
| `hafen.map.worldToTile(x,y)` | `{x,y}` | `floor(tilesz)`; `tilesz = 11` ([:37](src/haven/MCache.java:37)) |
| `hafen.map.tileToWorld(tx,ty)` | `{x,y}` | `* tilesz` |
| `hafen.map.tileToGrid(tx,ty)` | `{x,y}` | `div(cmaps)`; `cmaps = 100` ([:39](src/haven/MCache.java:39)) |

> ⚠ **No global position — anchor on grid IDs.** `hafen.gob.pos`/`rc` is **login-relative**
> (session-local, starts ~(-10,-10) tiles), not global or cross-player. The only stable anchor is the
> **grid id** (`Grid.id`, identical for all players) + the **within-grid offset**. For a shareable/
> persistent position use `hafen.map.gridPos()`. Segment ids are **local**. See
> [coverage-gaps.md](ROADMAP.md) C4.

## `hafen.items` — inventory & equipment  ≈ WoW `C_Container.*` / `GetInventoryItemLink`
Backing: [`GameUI.maininv`](src/haven/GameUI.java:54), [`GItem`](src/haven/GItem.java), [`ItemInfo`](src/haven/ItemInfo.java).

| Function | Returns | Backing |
|---|---|---|
| `hafen.items.inventory()` | `Item[]` | `maininv.wmap` ([:38](src/haven/Inventory.java:38)) |
| `hafen.items.equipment()` | `Item[]` | ⚠ `GameUI.equwnd` is a **private `Window`** ([GameUI.java:52](src/haven/GameUI.java:52)), not an `Equipory` — descend to its child `Equipory`; and `Equipory.wmap` is `Map<GItem,Collection<WItem>>` ([:83](src/haven/Equipory.java:83)) (a Collection per slot, unlike inventory) |
| `hafen.items.hand()` | `Item` \| nil | cursor item |
| `hafen.items.find(nameOrRes)` | `Item[]` | filter inventory |
| `hafen.items.name(item)` | string | `ItemInfo.find(Name, item.info())` ([:177](src/haven/ItemInfo.java:177),[:354](src/haven/ItemInfo.java:354)) — ≈ `ItemName` |
| `hafen.items.count(item)` | number \| nil | `GItem.num` ([:40](src/haven/GItem.java:40)) |
| `hafen.items.wear(item)` | 0..100 \| nil | `GItem.meter` ([:39](src/haven/GItem.java:39)) |
| `hafen.items.quality(item)` | number \| nil ⚠ | content-defined (no typed class); scan `GItem.info()` ([:199](src/haven/GItem.java:199)) via `ItemInfo.find` ([ItemInfo.java:354](src/haven/ItemInfo.java:354)) or parse raw `"tt"` `Raw.data` ([:52](src/haven/ItemInfo.java:52)) — unstable |
| `hafen.items.contents(item)` | `Item[]` \| nil | container `GItem.contents` |

> **Durability vs quality.** `wear` (GItem.meter) is item condition/progress; `quality` is a
> separate content-defined value; `hafen.gob.health(ref)` is *object* integrity (GobHealth). Kept
> distinct on purpose.

## `hafen.char` — attributes & skills  ≈ WoW `UnitStat`
Backing: [`Glob.getcattr`](src/haven/Glob.java:344), [`CharWnd`](src/haven/CharWnd.java).

| Function | Returns | Backing |
|---|---|---|
| `hafen.char.attr(name)` | `Attr` \| nil | `glob.getcattr(name)` → `{base, comp}` ([:344](src/haven/Glob.java:344),[:87](src/haven/Glob.java:87)). ⚠ `getcattr` **never returns null** — an unknown name auto-creates `{base=0,comp=0}`; the bridge must treat empty/zero-info entries as `nil` |
| `hafen.char.attrs()` | `{name=Attr}` | ⚠ the attribute-name list (`str,agi,int,con,prc,csm,dex,wil,psy`) is **content-defined** (server-populated map, not discoverable from `Glob`) — a hard-coded assumption |
| `hafen.char.lp()` | number \| nil | `chrwdg.exp` ([:60](src/haven/CharWnd.java:60)) |
| `hafen.char.weight()` | number \| nil | `chrwdg.enc` ([:60](src/haven/CharWnd.java:60)) |
| `hafen.char.skills()` / `skill(name)` | array / bool ✅ | **known** skills from `CharWnd.skill` (`SkillWnd.skg.csk`); `skill(name)` = substring membership (1d-3) |
| `hafen.char.food()` | table ✅ | `BAttrWnd.FoodMeter`/`GlutMeter` — FEP + hunger (1d-2) |

## `hafen.party` — party  ≈ WoW `C_PartyInfo` / `UnitInRaid`
Backing: [`Glob.party`](src/haven/Party.java:32).

| Function | Returns | Backing |
|---|---|---|
| `hafen.party.members()` | `PartyMember[]` | `party.memb` ([:33](src/haven/Party.java:33)); order by `Member.seq` ([:45](src/haven/Party.java:45)) |
| `hafen.party.leader()` | `PartyMember` \| nil | `party.leader` ([:34](src/haven/Party.java:34)) |
| `hafen.party.member(id)` | `PartyMember` \| nil | `memb[id]`; `getc()` ([:60](src/haven/Party.java:60)), facing `geta()` ([:78](src/haven/Party.java:78)) |

> `PartyMember` fields are **derived**, not literal: `id`=`Member.gobid`, `color`=`Member.col`,
> `x,y`=`getc()`, `leader`=(`member==party.leader`). There is **no name** field.

## `hafen.combat` — combat  ≈ WoW combat log / `UnitThreatSituation`
Backing: [`Fightview`](src/haven/Fightview.java) (`GameUI.fv` [:48](src/haven/GameUI.java:48)), [`Fightsess`](src/haven/Fightsess.java). Exists only while in combat.

| Function | Returns | Backing |
|---|---|---|
| `hafen.combat.inCombat()` | bool | `GameUI.fv != null` |
| `hafen.combat.target()` | id \| nil | `Fightview.current.gobid` (`current` [:45](src/haven/Fightview.java:45), `gobid` [:54](src/haven/Fightview.java:54)) |
| `hafen.combat.opponents()` | array | `Fightview.lsrel` → `{gobid, ip, oip, gst, lastact}` ([:41](src/haven/Fightview.java:41),[:57](src/haven/Fightview.java:57)); per-relation `buffs`/`relbuffs` [:55](src/haven/Fightview.java:55) |
| `hafen.combat.myIp()` | number | my initiative/openings (`Relation.ip`) |
| `hafen.combat.deck()` | array | `Fightsess.actions` → `{res, cooldownLeft}` ([Fightsess.java:46](src/haven/Fightsess.java:46)) |
| `hafen.combat.attackCooldown()` | number | `Fightview.atkcs/atkct` ([:47](src/haven/Fightview.java:47)) |

> **Cooldowns ARE in seconds.** `deck().cooldownLeft` and `attackCooldown` derive from **rtime**
> targets (`ct - Utils.rtime()`), so real seconds-remaining IS available — unlike buff cooldowns
> ([`hafen.buffs`](#hafenbuffs--buffsdebuffs--wow-unitaura)) which are 0..1 only. Maneuver/card/attack
> **names** need `Indir<Resource>.get()` (throws `Loading`) → best-effort/nil while loading.

## `hafen.chat` — chat  ≈ WoW `SendChatMessage` / `CHAT_MSG_*`
Backing: [`ChatUI`](src/haven/ChatUI.java).

| Function | Returns | Backing |
|---|---|---|
| `hafen.chat.send(channel, text)` | — | `EntryChannel.send` → `channel.wdgmsg("msg", text)` ([ChatUI.java:814](src/haven/ChatUI.java:814)); the *channel* selects area/party/PM |
| `hafen.chat.channels()` | array | walk `ChatUI` children (`Channel` [:123](src/haven/ChatUI.java:123)); `name()` [:737](src/haven/ChatUI.java:737); selected = `ChatUI.sel` [:53](src/haven/ChatUI.java:53) |
| `hafen.chat.messages(channel [, n])` | `Message[]` | `Channel.rmsgs` [:124](src/haven/ChatUI.java:124) is `List<RenderedMessage>` — the `Message` is `RenderedMessage.msg` [:144](src/haven/ChatUI.java:144), bridge must unwrap; iterate under `synchronized(rmsgs)`; `Message.time` = epoch sec [:131](src/haven/ChatUI.java:131) |
| (event) `ChatMessage` | `Message` | `Channel.append` hook [:275](src/haven/ChatUI.java:275) ([09](design/09-events-catalog.md)) |

> `Message.from` is a **buddy id**; a display name (`fromName`) resolves only if the sender is on
> your kin list (`GameUI.buddies.find(from)` → `Buddy.name`). ⚠ the Java fallback is the literal
> `"???"` [:881](src/haven/ChatUI.java:881), not null — the bridge must map `"???"` → nil. Party
> chat also carries the gob id.

## `hafen.sound` — audio  ≈ WoW `PlaySound` / `PlaySoundFile`
Backing: [`UI.sfx`](src/haven/UI.java:931), [`Audio`](src/haven/Audio.java), [`Music`](src/haven/Music.java).

| Function | Returns | Backing |
|---|---|---|
| `hafen.sound.play(resname)` | — | `ui.sfx(Resource.local().loadwait(resname))` ([UI.java:931](src/haven/UI.java:931) → [`Audio.fromres`](src/haven/Audio.java:585)); resolve/defer to avoid `Loading` |
| `hafen.music.play(resname, loop)` | — | [`Music.play`](src/haven/Music.java:138) |

> Volume via `Audio.Root.volume()`. The resource must be resolved (`loadwait`/defer, mirroring
> [GobIcon.java:206](src/haven/GobIcon.java:206)) before playing — a bare `load` may throw `Loading`.

## `hafen.time` — time & astronomy  ≈ WoW `GetGameTime` / `C_DateAndTime`
Backing: [`Glob.globtime`](src/haven/Glob.java:210), [`Glob.ast`](src/haven/Glob.java:40) (`Astronomy`).
`ast` is nil until the first "astro" update — guard.

| Function | Returns | Backing |
|---|---|---|
| `hafen.time.clock()` | number | `glob.globtime()` ([:210](src/haven/Glob.java:210)) — interpolated game seconds |
| `hafen.time.dayFraction()` | 0..1 | `Astronomy.dt` ([Astronomy.java:32](src/haven/Astronomy.java:32)) |
| `hafen.time.isNight()` | bool | `Astronomy.night` ([:34](src/haven/Astronomy.java:34)) |
| `hafen.time.season()` | number | `Astronomy.is` (season index) |
| `hafen.time.moon()` | 0..1 | `Astronomy.mp` (moon phase) ([:32](src/haven/Astronomy.java:32)) |
| `hafen.time.yearFraction()` | 0..1 | `Astronomy.yt` |

## `hafen.buffs` — buffs/debuffs  ≈ WoW `UnitAura`
Backing: [`GameUI.buffs`](src/haven/GameUI.java:71) ([`Bufflist`](src/haven/Bufflist.java)/[`Buff`](src/haven/Buff.java)).

| Function | Returns | Backing |
|---|---|---|
| `hafen.buffs.list()` | `Buff[]` | `buffs.children(Buff.class)`; `Buff.res` ([:42](src/haven/Buff.java:42)), name via `Buff.info()` ([:70](src/haven/Buff.java:70)) |
| `hafen.buffs.has(nameOrRes)` | bool | scan |

> `amount`/`cooldown`/`number` come from resource-published `ItemInfo` (`ItemInfo.find` over
> `Buff.info()`), are 0..1 fractions, content-dependent, and often nil. **No seconds timer exists.**

## `hafen.act` — actions (GATED, Phase 4)  ≈ WoW protected `CastSpellByName`/`UseContainerItem`
Backing: [`Widget.wdgmsg`](src/haven/Widget.java:737). See the action-message reference in
[09-events-catalog.md](design/09-events-catalog.md#appendix--action-message-reference-clientserver).
**Gated by a declared PER-ADDON permission (D-027/D-028):** a verb runs only if the calling addon declared
`"permissions": ["actions"]` in its manifest; else it throws a guiding error. There is **no global master
switch** (D-028 dropped it) — the tier is always available at the system level; the user grants it per addon
by **enabling the addon** (write addons are disabled by default and enabling raises a consent dialog, slice
4c). A running write addon is thus already permitted. `enabled()` is the only member that never throws.

| Function | Returns | Backing |
|---|---|---|
| `hafen.act.enabled()` | bool | is THIS addon granted (i.e. it declared the "actions" permission); never throws — **4a**, **D-028** ✅ |
| `hafen.act.moveTo(x,y)` | — | `map.wdgmsg("click", ui.mc, Coord2d(x,y).floor(posres), 1, 0)` — **4a** ✅ (world coords; `pc`=dummy mouse, like `MiniMap.mvclick`) |
| `hafen.act.clickGob(ref [, button [, mods]])` | — | `map.wdgmsg("click", pc, mc, button, mods, 0, gobid, gobrc, 0, -1)` — **4d** ✅ (`ref` = the read-API GobRef; button 1=left default / 3=right; bare `Gob.GobClick` encoding) |
| `hafen.act.useItemOn(x, y [, mods])` | — | `map.wdgmsg("itemact", pc, mc, mods)` — **4d** ✅ (use the cursor item on the ground at WORLD x,y) |
| `hafen.act.place(x, y, angle [, button [, mods]])` | — | `map.wdgmsg("place", rc, round(angle*32768/PI), button, mods)` — **4d** ✅ (`angle` in RADIANS; button 1 default) |
| `hafen.act.select(x1, y1, x2, y2 [, mods])` | — | `map.wdgmsg("sel", tc1, tc2, mods)` — **4d** ✅ (WORLD corners → TILE via `worldToTile`) |
| `hafen.act.menu(path...)` | — | `GameUI.act(path...)` ([:1683](src/haven/GameUI.java:1683)) — **4e** ✅ (path tokens are content-defined; some commit real actions) |
| `hafen.act.flower(label)` | bool | select open `FlowerMenu` petal ([:278](src/haven/FlowerMenu.java:278)) — **4e** ✅ (case-insensitive; false if no menu/petal) |
| `hafen.act.item(item, verb [, n])` | — | `GItem.wdgmsg("take"/"drop"/"transfer"/"iact"/"itemact", …)` — **4f** ✅ (`item` = an Item snapshot or its `handle`; verb take/drop/transfer/iact/itemact; `n` = stack count for drop/transfer, default **-1 = all**; re-resolves the live `GItem` by `handle`) |
| `hafen.act.raw(target, msg, ...)` | — | **4d** ✅ escape hatch — `wdgmsg` from a bound widget; `target` = a widget id (number, e.g. `model:raw()`) or `"mapview"`/`"gameui"`; args marshalled like the hook levels |

> The write tier also includes the **per-subsystem** verbs (**4g** ✅), which share the same `"actions"` gate
> but live in their own namespaces: [`hafen.speed.set`](#hafen-speed--movement-speed-a7),
> [`hafen.craft.make`](#hafen-craft--crafting-read-a8--widget-tree-), [`hafen.actionbar.use`](#hafen-actionbar--hotbar-a3--widget-tree---wow-action-bars),
> [`hafen.kin.add`/`remove`/`forget`/`rename`/`setGroup`](#hafen-kin--buddykin-a6).

## `hafen.ui` — windows / overlays / draw / replace
See [07-ui-and-drawing.md](design/07-ui-and-drawing.md), [08-widget-replacement.md](design/08-widget-replacement.md).

| Function | Returns | Backing |
|---|---|---|
| `hafen.ui.window(opts)` | `Window` handle | `LuaWindow` → `ui.root.add` |
| `hafen.ui.widget(opts)` | `Widget` handle | `LuaWidget` |
| `hafen.ui.overlay(fn)` | handle | engine overlay list (drawn from addon-root; `UI.drawafter` is one-shot [:391](src/haven/UI.java:391)) |
| `hafen.ui.gobOverlay(filter, fn)` | handle | `GAttrib`+`PView.Render2D` per gob (SpeakerIcon pattern) |
| `hafen.ui.replace(type, opts, fn)` | — | factory override / `GameUI.addchild` hook ([08](design/08-widget-replacement.md)) |
| `hafen.ui.onWidgetCreate(fn)` | handle | `UI.NewWidget` hook |
| `hafen.ui.root()` | `WidgetNode` | `ui.root` — top of the whole client tree ([20](design/20-widget-introspection.md), [D-041](decisions/widgets-ui.md)) |
| `hafen.ui.node(id)` | `WidgetNode` / nil | a server widget id → node ([20](design/20-widget-introspection.md)) |
| `hafen.ui.mouse()` | `{x,y}` | cursor in root coords — public `UI.mc` ([20](design/20-widget-introspection.md) §W2, [D-042](decisions/widgets-ui.md)) |
| `hafen.ui.at(x, y)` | `WidgetNode` / nil | deepest widget under a point (mirrors engine pointer dispatch) ([D-042](decisions/widgets-ui.md)) |

**`WidgetNode`** ([20](design/20-widget-introspection.md), [D-041](decisions/widgets-ui.md)) — read-only, facade-safe, stale-graceful
walk of any widget's guts: `:type()` (class name), **`:id()` (server id, or `nil` if not server-bound — the field
that makes a node actionable)**, `:children()` (array, tree order), `:parent()`, `:pos()`, `:size()`, `:visible()`,
`:text()` (best-effort), `:walk(fn)` (depth-first, `fn(node,depth)`, return `false` to prune), `:same(other)`
(reference identity — `true` iff both wrap the same live widget; nil-safe; the "same as last frame?" primitive), and
(W2) `:at(coord)` (deepest descendant under a point) / `:rootpos()` (top-left in root coords, for a highlight box). To
**act**, pass `node:id()` to the gated [`hafen.act.raw`](DECISIONS.md#d-025) — you target the **server-bound** node
with the message a client-only button would send, *not* the button. Powers **pure-Lua window adapters** (e.g. a
barter-stand `products()/price()/buy()`) with no Java per window, and the **`widgetstack`** example addon — a
WoW-`/framestack` clone (`ui.mouse` + `ui.at` + `:parent()` walk, rebuilding only when `:same` says the hovered
widget changed).

`Window`/`Widget` handle methods: `:move(x,y)`, `:show()`, `:hide()`, `:pack()`, `:destroy()`,
`:refresh()`; draw via the `GOut` wrapper `g` (`g:image`/`aimage`/`resource`/`text`/`atext`/`rect`/`frect`/`line`/
`poly`/`prect`/`color`). `g:image(img,…)` draws the addon's own PNGs (`hafen.render.image`);
`g:resource(name,x,y[,w,h])` draws an engine `.res` by name ([D-039](decisions/widgets-ui.md)).

Widget callbacks carry a trailing `mods = {shift,ctrl,alt}` — `onClick(x,y,button,mods)` / `onMouseUp(…)` /
`onMouseMove(x,y,mods)` / `onWheel(x,y,amount,mods)` ([D-040](decisions/widgets-ui.md)) — and `onDrop(x,y,drop)` receives a
thing dropped on the widget (v1: a menu-grid pagina `{kind="pagina",res=…}`, [D-038](decisions/widgets-ui.md); return truthy
to consume).

## `hafen.font` — typography (register a font, change it anywhere)  ([D-043](decisions/fonts.md))
See [21-fonts.md](design/21-fonts.md). **Per-addon** — a private handle + owned overrides, **no shared registry**.

| Function | Returns | |
|---|---|---|
| `hafen.font.load(source[, opts])` | `FontHandle` | `source` = a `.ttf`/`.otf` in the addon folder or a built-in `"sans"/"serif"/"mono"/"fraktur"`; `opts = {size,aa,bold,italic,color}` (px, `UI.scale`d). Private to the addon. |
| `h:derive(opts)` | `FontHandle` | a cheap variant (different size/color/aa/bold/italic) |
| `h:family()` | string | AWT family — feed to `$font[family,sz]{…}` for per-run mixing |
| `h:size()` | number \| nil | the handle's logical px size |
| `hafen.font.setFont(scope, h)` | — | install THIS addon's **owned override** on a client surface (auto-revert on `:reload`/disable; last-wins) |
| `hafen.font.reset(scope)` | — | drop this addon's override on `scope` (restores what's beneath) |
| `hafen.font.scopes()` | array | valid scope names (discovery) |

**Apply to your OWN drawing** (isolated, conflict-free, **F2 ✅**): `hafen.ui.window{…, font=h}` /
`hafen.ui.widget{…, font=h}`, `g:text(str, x, y [, {font=h, color=…}])` (positional coords, as everywhere; opts
optional), and per-run `g:text("$font["..h:family()..",12]{fancy} normal")` (rich markup honoured).

**Scopes** (enumerated; `"default"` cascades to every unset surface, so `setFont("default", h)` changes
*everything* in one call): `"default"`, `"window.title"`, `"button"`, `"label"`, `"tooltip"`, `"menu"`, `"chat"`,
`"textentry"`, `"world.nick"`, `"world.speech"`. **Resolution:** per-instance (F5) → scope override → `"default"`
override → stock. **Ungated** (cosmetic, client-only). Rollout F1–F5 in [21-fonts.md](design/21-fonts.md).

## `hafen.events` — event bus (observe)
See [09-events-catalog.md](design/09-events-catalog.md).

| Function | Returns | |
|---|---|---|
| `hafen.events.on(name, fn)` | `sub` handle | `sub:off()` to unsubscribe (auto on teardown) |
| `hafen.events.emit(name, ...)` | — | addon-defined events (cross-addon semantics TBD) |

## `hafen.hook` — intercept / alter / replace (not just observe)
See [13-hooks-and-interception.md](design/13-hooks-and-interception.md). Pre/post/replace with
`preventDefault`. Handler `fn(ev)`; `ev:preventDefault()`, `ev:default()`, `ev:stopPropagation()`,
`ev.args` (mutable), `ev:resend()`.

| Function | Level | Backing |
|---|---|---|
| `hafen.hook.input(target, event, fn)` | gesture (before widget default) | [`Widget.listen`](src/haven/Widget.java:856) — zero core edit |
| `hafen.hook.action(msg, fn)` | outbound action (before send) | [`UI.wdgmsg`](src/haven/UI.java:665) |
| `hafen.hook.message(msg, fn)` | inbound server update | [`UI.uimsg`](src/haven/UI.java:702) |
| `hafen.hook.method(type, name, fn)` | full method replacement | hookable subclass via `Widget.types` |

Each returns a handle with `:remove()` (auto-removed on teardown). `opts = {priority, post}`.

## `hafen.timer` — scheduling
| Function | Returns | |
|---|---|---|
| `hafen.timer.after(sec, fn)` | handle | one-shot; UI thread |
| `hafen.timer.every(sec, fn)` | handle | repeating; `:cancel()` |

## `hafen.store` — saved variables (per `<genus>_<char>`)
See [05-lifecycle-and-reload.md](design/05-lifecycle-and-reload.md). JSON under `savedata/`.

| Function | Returns | |
|---|---|---|
| `hafen.store.<name>` | table | a persisted table declared in the manifest |
| `hafen.store.flush()` | — | force write now |

## `hafen.log` — logging
| Function | | |
|---|---|---|
| `hafen.log(msg)` | info | → console + per-addon log |
| `hafen.log.warn(msg)` / `hafen.log.error(msg)` | | |

## `hafen.key` — hotkeys  ≈ WoW `SetBinding`
Backing: [`KeyBinding`](src/haven/KeyBinding.java:57).

| Function | | |
|---|---|---|
| `hafen.key.bind(name, defaultKey, fn)` | id namespaced `addon/<id>/<name>`; shown in the client keybind panel |

## `hafen.client` — client/system  ≈ WoW `GetBuildInfo` / misc
| Function | Returns | Backing |
|---|---|---|
| `hafen.client.apiVersion()` | number | engine API level |
| `hafen.client.charName()` / `.genus()` | string \| nil | `GameUI.chrid`/`genus` ([:257](src/haven/GameUI.java:257)) |
| `hafen.client.screenSize()` | `{w,h}` | `ui.root.sz` |
| `hafen.client.resource(name)` | handle ⚠ | load a `.res` (addon-relative) — ergonomics TBD ([07](design/07-ui-and-drawing.md)) |

---

## Gap subsystems (designed; built per phase — [D-026](decisions/process.md), [15](ROADMAP.md))

Contract-level surfaces for the audit's coverage gaps ([coverage-gaps.md](ROADMAP.md)). Ones
marked *(widget-tree)* are backed by the [widget-tree read mechanism](design/14-widget-tree-reads.md) and
each ships with a `*Changed` event. Full per-subsystem detail is done in its build phase.

### `hafen.markers` — map markers (A1)  ≈ WoW `C_Map` pins
Backing: [`MapWnd`](src/haven/MapWnd.java)/[`MapFile`](src/haven/MapFile.java) (client-side DB).
| Function | Returns | Backing |
|---|---|---|
| `hafen.markers.list([filter])` | array | `MapFile` `PMarker`/`SMarker` |
| `hafen.markers.add(name, x, y [, icon])` | ref | `MapWnd.markobj` (~:922) |
| `hafen.markers.remove(ref)` | — | |
| `hafen.markers.nearest(filter)` | ref \| nil | |
> ⚠ Marker coords are **segment/grid** space, not world units — they survive a relog ([C4](ROADMAP.md)).
> The map window itself is client-side, not a server widget, so it is **not** reskinnable via
> [08](design/08-widget-replacement.md) ([B3](ROADMAP.md)); a map overhaul is its own subsystem.

### `hafen.radar` — minimap icons / categories (A2)
Backing: [`GobIcon`](src/haven/GobIcon.java) `Settings`.
| `hafen.radar.categories()` | array | `GobIcon.Settings` |
| `hafen.radar.setVisible(category, bool)` | — | |
| `hafen.radar.setNotify(category, bool)` | — | spawn notification |

### `hafen.actionbar` — hotbar (A3)  *(widget-tree)*  ≈ WoW action bars
Backing: [`GameUI.belt`](src/haven/GameUI.java:68) — the engine's own name for the action bar is the
"belt" (`ActionbarAdapter`, [14](design/14-widget-tree-reads.md)). Slot `n` = the **raw 0-based game index**
(0..143), NOT a 1-based Lua position — the same index `use(n)` sends.
| `hafen.actionbar.slot(n)` | `{res, name, cooldown}` \| nil | `BeltSlot[n]` (`cooldown` = pagina meter 0..1, ability slots only) |
| `hafen.actionbar.use(n [, mods])` | — *(gated action)* ✅ **4g** | activate slot `n` — a LEFT-click on it (`Belt.act(n, Interaction(1, mods))` → `wdgmsg("belt", n, …)`); a ground-targeted ability then enters targeting mode |
| event `ActionbarChanged` | `{n}` | poll (`belt[]` is set on a deferred loader task, so diffed per-tick) |

### `hafen.study` / `hafen.char` (ext) — study, curiosity, FEP, skills (A4)  *(widget-tree)*
Backing: `CharWnd.sattr` ([`SAttrWnd`](src/haven/SAttrWnd.java) `StudyInfo`) + [`resutil.Curiosity`](src/haven/resutil/Curiosity.java),
[`BAttrWnd`](src/haven/BAttrWnd.java) `FoodMeter`/`GlutMeter`, `CharWnd.skill` ([`SkillWnd`](src/haven/SkillWnd.java))
(adapters in [14](design/14-widget-tree-reads.md)).
| `hafen.study.slots()` | array ✅ | curiosity `{res,name,lp,attention,cost,time,progress?}` from `StudyInfo.study` `GItem`s + their `Curiosity` info. ⚠ `time` = **total** study time (no per-item "time left" exists); `progress` = `GItem.meter/100`, best-effort |
| `hafen.study.summary()` | table ✅ | live totals `{lp,attention,cost}` from `SAttrWnd.StudyInfo.texp`/`tw`/`tenc` (attention cap = `hafen.char.attr("int").comp`) |
| `hafen.char.food()` | table ✅ | `FoodMeter feps` + hunger/glut (1d-2) |
| `hafen.char.skills()` / `skill(name)` | array / bool ✅ | **known** skills `{name,res}` from `SkillWnd.skg.csk`; `skill(name)` = substring test (name\|res) |
| `hafen.char.skillsAvailable()` | array ✅ | **buyable** skills `{name,res,cost}` from `SkillWnd.skg.nsk` (`cost` = LP price) |
| `hafen.char.credos()` | table \| nil ✅ | Credos tab from `SkillWnd.credos`: `{acquired, available` (each `{name,res}`)`, pursuing={name,res,level,levelTotal,quest,questTotal,questId}`\|absent`, cost}` |
| `hafen.char.experiences()` | array ✅ | Lore tab `{name,res,score,mtime}` from `SkillWnd.exps.seen` (`mtime` = raw server time field, passthrough) |
| events | | `FepChanged` (1d-2), `StudyChanged` (poll-driven — study add/remove is a widget create/`cdestroy`, not a uimsg). No `SkillsChanged`/`CredosChanged` — skills/credos/lore change only on explicit player action (buy/pursue/quest), read on demand |

### `hafen.kin` — buddy/kin (A6)
Backing: [`BuddyWnd`](src/haven/BuddyWnd.java) (`GameUI.buddies`, `Iterable<Buddy>`).
| `hafen.kin.list([filter])` | array ✅ | kin `{id, name, group (0..7), color={r,g,b,a}, online(bool)}` in the window's sort order; canonical `matches` filter |
| `hafen.kin.find(nameOrId)` | entry \| nil ✅ | number → by id (`BuddyWnd.find`); string → exact case-insensitive name |
| event `KinChanged` | `list` ✅ | uimsg-driven (`add`/`rm`/`upd`/`chst` → snapshot diff, NOT `serial` which misses `chst`); payload = the new list |
| `hafen.kin.add(secret)` | — *(gated action)* ✅ **4g** | add a kin by the other player's **hearth secret** (the "Add kin" field → `wdgmsg("bypwd", secret)`) |
| `hafen.kin.remove(kin)` | — *(gated action)* ✅ **4g** | **End kinship** (step 1): `Buddy.endkin` → `wdgmsg("rm", id)`; the kin stays *memorized* in the list. `kin` = a snapshot / id / name |
| `hafen.kin.forget(kin)` | — *(gated action)* ✅ **4g** | **Forget** (step 2): `Buddy.forget` → `wdgmsg("rm", id)`; drops a *memorized* kin from the list |
| `hafen.kin.rename(kin, name)` | — *(gated action)* ✅ **4g** | set a kin's nickname (`Buddy.chname` → `wdgmsg("nick", id, name)`) |
| `hafen.kin.setGroup(kin, group)` | — *(gated action)* ✅ **4g** | move a kin to colour group 0..7 (`Buddy.chgrp` → `wdgmsg("grp", id, group)`) |
> **`remove` then `forget`** are the game's two-step drop ("End kinship" → "Forget"): both send `wdgmsg("rm", id)`
> and the server advances the kin active → memorized → gone. **`add` is by hearth secret, not by name** (the
> `"bypwd"` message; the alternate right-click *"Add as kin"* path stays reachable via `hafen.act.clickGob(id,3)`
> + `hafen.act.flower`). `setGroup` (the `grp` message) was added over the original "add/remove/rename" sketch.
> `online` is exposed as a **boolean** (`Buddy.online == 1`); the internal `-1` (hearth-secret-only) vs
> `0` (offline) tri-state and `Buddy.seen`/`notes` are deferred. **Zero `haven` edit** (all public).

### `hafen.speed` — movement speed (A7)
Backing: [`Speedget`](src/haven/Speedget.java).
| `hafen.speed.get()` | 0..3 | crawl/walk/run/sprint |
| `hafen.speed.set(n)` | — *(gated action)* ✅ **4g** | select speed n=0..3 (`Speedget.set` → `wdgmsg("set", n)`) |

### `hafen.craft` — crafting read (A8)  *(widget-tree)*  ✅
Backing: [`Makewindow`](src/haven/Makewindow.java) (`@RName("make")`), located via the 1d-1 Locator
(`gui().children(Makewindow.class)` — it is wrapped in the private `GameUI.makewnd`). Read-only; `make`
is the gated Phase-4 tier. **Zero `haven` edit** (all backings public).
| `hafen.craft.current()` | `{recipe, inputs, outputs, qmod, tools}` \| nil ✅ | the OPEN recipe, or nil when no craft window is up |
| ↳ `inputs[i]` / `outputs[i]` | `{res, name, num, opt}` | input/output slot spec: `res`/`name` = the DISPLAYED resource (the constraint category when the recipe accepts one, else the concrete item — mirrors `Spec.display()`); `num` = required/produced count (**-1 = unspecified ≈ 1**, faithful); `opt` = optional ingredient / chance byproduct |
| ↳ `qmod[i]` / `tools[i]` | `{res, name}` | quality-affecting inputs / required tools (bare resources, no count) |
| `hafen.craft.make([all])` | — *(gated action)* ✅ **4g** | craft the OPEN recipe — `wdgmsg("make", 0\|1)` (the Craft / Craft All buttons); consumes ingredients |
| event | — | no `CraftChanged` (read on demand; a recipe changes only when opened — observe via `hafen.ui.onWidgetCreate`, `place="craft"`) |
> Shape note: extends the original `{recipe, inputs, output}` sketch — the engine keeps a **list** of
> outputs plus separate `qmod`/`tools`, so `outputs` is **plural** (an array) and the two resource lists
> are exposed. Faithful in-phase refinement, like [kin](#hafen-kin--buddykin-a6)/credos.

### `hafen.quests` / `hafen.wounds` (A9)  *(widget-tree, detail deferred)*
Backing: [`QuestWnd`](src/haven/QuestWnd.java), [`WoundWnd`](src/haven/WoundWnd.java). Events
`QuestAdded`/`QuestDone`, `WoundChanged`. Full read shape designed in-phase.

### `hafen.slash` — slash commands (A11)  ≈ WoW `SlashCmdList`
Backing: [`Console.setscmd`](src/haven/Console.java:54) via a **single engine-lifetime dispatcher**
(never re-registered → no reload leak, [C1](ROADMAP.md)).
| `hafen.slash.register(name, fn)` | handle | routes `:name args…` to `fn` |

## Virtual entities (client-only, SAFE-tier) — V-series 🟢 designed & closed (D-029..D-033)

> **New capability** — full design in [16-virtual-entities.md](design/16-virtual-entities.md) (**CLOSED**).
> **Client-only, NOT gated** ([D-029](decisions/virtual-entities.md)): a ghost is a `Gob` with no server id, added to the
> MapView scene (the [`Plob`](src/haven/MapView.java:1779) template) and **never** sent to the server —
> safe-tier, alongside `hafen.ui.overlay`, not `hafen.act.*`. Not built yet (queue **V1..V6**).

### `hafen.ghost` — place & manage client-only props in the world
Dedicated namespace ([D-030](decisions/virtual-entities.md)); each ghost is a handle like `Window`. Auto-destroyed on reload/disable (P2).
| Function | Returns | Backing |
|---|---|---|
| `hafen.ghost.new{res,x,y[,a,sdt,alpha,tint,scale,clickable,onClick]}` | `Ghost` handle | `new Gob(glob,rc)`+`ResDrawable`, added to `MapView` `basic` scene slot |
| `hafen.ghost.list([filter])` | `Ghost[]` | this addon's live ghosts (canonical `filter`) |
| `g:move(x,y [,a])` / `g:rotate(a)` | — | `Gob.move(Coord2d,double)` |
| `g:scale(s)` (V6) | — | scaling `Location`/`Pipe.Op` on `placed` |
| `g:setRes(res [,sdt])` | — | `setattr(new ResDrawable(...))` |
| `g:tint(color)` / `g:alpha(a)` | — | colour/alpha render state |
| `g:show()` / `g:hide()` | — | `basic.add` / `slot.remove` |
| `g:clickable(bool)` (V2) | — | add/remove the ghost's `Clickable`; picked by `ClickMap` |
| `g:pos()` | `{x,y,a,scale}` | `Gob.rc`/`Gob.a` (+ V6 scale — the full client transform) |
| `g:destroy()` | — | `slot.remove()` + registry drop |
| event `GhostClicked` | `{ghost,button,x,y}` | client-only click on a `clickable` ghost — intercepted at [`Click.hit`](src/haven/MapView.java:2017) before `wdgmsg`, so **no server click** (safe-tier, [D-032](decisions/virtual-entities.md)) |

### `hafen.map` helpers for ghosts — raycast + placement snapping
| Function | Returns | Backing |
|---|---|---|
| `hafen.map.screenToWorld(sx,sy)` | `{x,y}` \| nil | `MapView.Maptest` ground hit ([:1810](src/haven/MapView.java:1810)) |
| `hafen.map.snapPlace(x,y [,fine])` | `{x,y}` | `StdPlace.adjust` snap ([:1749](src/haven/MapView.java:1749)) — tile centre, or `plobpgran` sub-grid with `fine` |
| `hafen.map.snapAngle(a [,fine])` | number | `StdPlace.rotate` snap ([:1764](src/haven/MapView.java:1764)) — 45°, or `π/plobagran` with `fine` |
| `hafen.map.placeGrid()` / `hafen.map.placeAngle()` | number | public `MapView.plobpgran` / `plobagran` ([:57](src/haven/MapView.java:57)); set by `:placegrid`/`:placeangle` ([D-033](decisions/virtual-entities.md)) |

> The gizmo snaps through these **by default** (`snap="place"`) so ghost move/rotate feel identical to
> placing a building and honour the user's `:placegrid`/`:placeangle` — same SHIFT/CTRL modifiers.

### `hafen.ghost.gizmo` — transform gizmo (move/rotate/scale)  ≈ Unity gizmo
A **Lua library over the primitives** above ([D-031](decisions/virtual-entities.md)), shipped in the `planner` example addon.
| Function | Returns | |
|---|---|---|
| `hafen.ghost.gizmo(target, {mode,axes,snap,onChange,onCommit})` | `Gizmo` handle | handles + drag over `hook.input`+`preventDefault`+`screenToWorld` |
| `gz:setMode("move"/"rotate"/"scale"/"all")` / `gz:detach()` / `gz:destroy()` | — | |

> ⚠ Ghost coords are **login-relative** — persist a layout by anchoring on **grid ids** (`hafen.map.gridPos()`)
> and re-resolving on load, exactly like markers ([coverage-gaps.md](ROADMAP.md) C4).

## Custom rendering (client-only, non-`.res`) — R-series 🟢 designed, decisions ratified (D-034..D-035, 2026-07-26)

> **New capability** — full design in [17-custom-rendering.md](design/17-custom-rendering.md) +
> [18-custom-models-gltf.md](design/18-custom-models-gltf.md) (🟡 DRAFT). **Client-only, NOT gated** ([D-034](decisions/rendering.md)):
> render assets that are **NOT engine `.res`** — PNG images (screen + world) and **glTF** 3D models. `hafen.ghost`
> stays `.res`-only; `hafen.render.*` is its sibling, on the **same** V-series world-entity core + gizmo, with a
> different visual. **R1 (2D image on screen — `hafen.render.image` + `g:image`/`g:aimage`) shipped** ✅; R2 (world
> sprite) + R3 (glTF model) queued.

### `hafen.render` — load & draw non-`.res` images and models
Loaders — cached, **bridge-owned**, addon-relative + sandboxed ([`Addon.dir`](src/io/brodgar/addon/Addon.java:173); `..`/absolute rejected):
| Function | Returns | Backing |
|---|---|---|
| `hafen.render.image(path)` | `Image` handle | `ImageIO`→[`TexI`](src/haven/TexI.java:52) from the addon folder (R1) |
| `hafen.render.model(path)` | `Model` handle | glTF **static** → engine `Model`/`Material` (R3; [18](design/18-custom-models-gltf.md)) |
| `img:size()` → `{w,h}` / `img:dispose()` · `mdl:bounds()` / `mdl:dispose()` | | freed on `:reload`/disable (P2) |

Draw on screen (2D) — inside a draw callback, via the `g` wrapper:
| Function | Backing |
|---|---|
| `g:image(img,x,y[,w,h])` | [`GOut.image(Tex,Coord[,Coord])`](src/haven/GOut.java:97) |
| `g:aimage(img,x,y,ax,ay)` | [`GOut.aimage`](src/haven/GOut.java:107) |

Place in the world — a transform handle like a ghost (`:move`/`:rotate`/`:scale`/`:show`/`:hide`/`:pos`/`:destroy`; **gizmo-compatible**):
| Function | Returns | Backing |
|---|---|---|
| `hafen.render.sprite{image=,x,y[,a,scale,billboard,alpha,tint]}` | `Sprite` handle | `billboard=true` → [`SpeakerIcon` `Render2D`](src/haven/SpeakerIcon.java:44) screen blit (faces camera); `false` → `TexI`-quad `Model` + [`SprDrawable`](src/haven/SprDrawable.java:40) (fixed) — R2 |
| `hafen.render.object{model=,x,y[,a,scale,alpha,tint]}` | `Object` handle | glTF `Model`s + `SprDrawable` on the world-entity core — R3 |

> Same **shared world-entity core** as `hafen.ghost` ([16-virtual-entities.md](design/16-virtual-entities.md)): only the
> visual differs (`.res` `ResDrawable` vs `TexI`-quad vs glTF `Model`s). The gizmo (`planner/gizmo.lua`) drives any
> handle. ⚠ World coords are **login-relative** (anchor persisted layouts on grid ids, like ghosts/markers).

## Data & networking (client I/O) — N-series 🟢 designed & closed (D-036..D-037, 2026-07-26)

> **New capability** — full design in [19-data-and-network.md](design/19-data-and-network.md). Two sibling namespaces:
> **`hafen.json`** (parse/encode, **ungated**) and **`hafen.http`** (external GET/POST, **async**, **gated** by a
> manifest `network` allowlist). Re-homed from the maintainer's `hafen.utils.*` sketch onto `hafen.*`-by-concern
> ([D-020](decisions/architecture-api.md)/[D-013](decisions/architecture-api.md)) — not a `utils` grab-bag. Not yet built (N1/N2 queued).

### `hafen.json` — JSON serialization (ungated, pure CPU)
Reuses the existing reader + REPL writer (the writer moves into [`Json`](src/io/brodgar/addon/Json.java), D-013):
| Function | Returns | Notes |
|---|---|---|
| `hafen.json.parse(str)` | Lua value (table/string/number/boolean/nil) | [`Json.parse`](src/io/brodgar/addon/Json.java:25) + `Map`/`List`→`LuaTable`; JSON `null`→`nil` (array-hole caveat); malformed → `LuaError`; depth/size-capped |
| `hafen.json.encode(value)` | compact JSON string | [AddonManager.java:8250](src/io/brodgar/addon/AddonManager.java:8250) writer; array iff keys `1..#t`; **strict** — function/userdata/cycle → `LuaError` |

### `hafen.http` — external requests (GATED, async)  ≈ browser `fetch`
> **Async, always** — a blocking call can't run on the UI thread ([19](design/19-data-and-network.md) §3). Returns a request
> handle `{ :cancel() }` (bridge-owned in `Addon.requests`, cancelled on teardown); the callback fires later on the tick.
| Function | Callback | Backing |
|---|---|---|
| `hafen.http.get(url[,opts],cb)` | `cb(res)` | `HttpURLConnection` GET off-thread → result tick-drained |
| `hafen.http.post(url,body[,opts],cb)` | `cb(res)` | POST; `body` = string (verbatim) or table (→ `application/json`) |

`opts = { headers={…}, timeout=ms }` · `res = { ok, status, body, headers (lower-cased), error }` — `ok` = a reply
arrived (**any** status, incl. 4xx/5xx); `error` set only on a transport failure. GET+POST only in v1 ([D-037](decisions/network-data.md)).

**Permission ([D-037](decisions/network-data.md)) — a manifest `network` block IS the declaration + host allowlist:**
```json
"network": { "hosts": ["api.example.com", "*.githubusercontent.com"] }
```
No block ⇒ `hafen.http.*` throws a guiding `LuaError` (like `requireActions`). `hosts` is an exact allowlist
(case-insensitive, `*.domain` sub-domain wildcard); a disallowed host errors **at call time**. **Private/loopback IPs
are blocked** (LAN/SSRF guard). Limits (D-018 spirit, `-D`-tunable): 8 MB response · 10 s timeout (cap 60 s) · 6
in-flight/addon · shared 8-thread pool. The AddOns panel shows declared hosts before enable.

## Coverage status

| Category | Status |
|---|---|
| player (core), world, map, items (wear/count/name), char (attrs), party | ✅ backed & concrete |
| chat, sound/music, time/astronomy, combat, buffs, gob speech | ✅ backed & concrete (names/tooltips Loading-guarded) |
| player vitals, items quality, gob icon name, char skills/food | ⚠ best-effort / content-defined |
| other players' display names · hunger/FEP · absolute vital numbers · seconds-based **buff** timers | ❌ not exposed (see below) |
| act, ui, events, timer, store, log, key, client | ✅ design-concrete (act gated) |
| ghost, render (sprite/image) | 🟢 built (V/R-series, client-only, ungated) |
| json, http | 🟢 designed (N-series, [D-036](decisions/network-data.md)/[D-037](decisions/network-data.md)); http async + gated by a `network` allowlist, not yet built |

## Not reliably available (explicitly NOT exposed as stable)

Verified absent/unstable in this client — the API must **not** promise these:

- **Other players' display names / nicks.** `OD_BUDDY` is removed ([OCache.java:51](src/haven/OCache.java:51));
  no `Gob` name field, no `KinInfo`. Names arrive via resource-published `GAttrib.Parser` over
  `OD_RESATTR` ([OCache.java:347](src/haven/OCache.java:347)) — not referenceable by a stable type.
  A name is available only when the gob is on your **kin list** (`GameUI.buddies` → `Buddy.name`,
  keyed by buddy id) or, for the **local** player, `GameUI.chrid`. `Party.Member` has a gobid but
  no name.
- **Absolute HP / stamina / energy numbers, and hunger/satiation/FEP.** Vitals are `IMeter` bars
  (0..1 fractions) in private `GameUI.meters` ([:49](src/haven/GameUI.java:49)) / protected
  `LayerMeter.meters` ([:35](src/haven/LayerMeter.java:35)), identifiable only by matching
  `IMeter.bg` resource names. No absolute values exist; hunger/FEP live only in `CharWnd` widgets.
- **Item quality as a typed value.** No `Quality` class; only inside resource-published `ItemInfo`
  (built generically by `ItemInfo.buildinfo` [ItemInfo.java:362](src/haven/ItemInfo.java:362) — that
  line is the generic builder, not a quality field). Best-effort raw-`tt` parsing only.
- **Seconds-based BUFF timers, and per-maneuver openings/weight.** Buff cooldowns are 0..1 fractions
  only (no time). Per-maneuver "openings/weight" beyond the IP integers don't exist as fields.
  **NOTE — combat cooldowns are the exception:** attack/maneuver cooldowns use `rtime` targets, so
  **seconds-remaining IS derivable** (`ct - Utils.rtime()`) and `hafen.combat` exposes it.
- **Names of maneuvers/cards/buffs/icons** need `Indir<Resource>.get()` → throws `Loading`; return
  nil while loading.

> These are not gaps to fill later — they are properties of the client/protocol. Addons that need
> them must derive/approximate (e.g. track vitals from bar fractions, names from the kin list).

## Status

Design is **closed** — all questions resolved ([DECISIONS.md](DECISIONS.md)), naming is namespaced
([D-020](decisions/architecture-api.md)), reference-based one-way accessors ([D-012](decisions/architecture-api.md)/[D-013](decisions/architecture-api.md)),
and the build order is in [15-implementation-plan.md](ROADMAP.md). Gap-subsystem
detail (map/actionbar/study/FEP/…) is finalized in its build phase. Split into `api/<category>.md` if it grows.
