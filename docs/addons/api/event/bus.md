# hafen.event: the catalogue

Every event the client fires on the bus, and what each one hands your handler. Subscribe with
[`hafen.event():on(key, fn)`](README.md#subscribe), which is where the rules for a subscription are; this
page is the closed set of keys it accepts.

## Lifecycle

Your addon's own three, and each is about the addon rather than about a character.

| Event | Payload | Fires |
|---|---|---|
| `Load` | — | once for the client, when the addon is loaded, before any character exists |
| `Update` | `dt` (number) | every frame; `dt` is seconds since the last frame |
| `Disable` | — | once for the client, when the addon is disabled or reloaded, or the client closes |

`Load` and `Disable` fire **once each for the client**, whatever happens to the characters underneath —
one addon, one Lua state, however many sessions are logged in. Keep `Update` handlers cheap: they run on
the UI thread on every frame, once per frame, and not once per session.

## Sessions

A character logging in, reaching the world, taking the screen and ending are four different moments, and
each is a session's rather than your addon's. The payload is that [`Session`](../session.md) — the address
every read your handler goes on to make is named by, and the one thing that says which character the
moment was about.

| Event | Payload | Fires |
|---|---|---|
| `SessionAdded` | [`Session`](../session.md) | a session connects, before it has a character or a world |
| `SessionEnteredWorld` | [`Session`](../session.md) | ...and its HUD is up, so that character can be read |
| `SessionSelected` | [`Session`](../session.md) | the screen changed to this session |
| `SessionDestroyed` | [`Session`](../session.md) | this session ended, however it ended |

```lua
hafen.event():on("SessionEnteredWorld", function(s)
  hafen.log():write(s:user() .. " is playing " .. (s:character() or "nobody yet"))
end)
```

**A `SessionDestroyed` names a session that is already gone.** `s:user()` answers there — the account name
is the whole of a `Session`, so there is nothing left to resolve — while `s:exists()` is `false` and
everything else about that login reads `nil`. That is what makes the payload usable as the key you drop
your own tables by, on the one event where the login it names has already gone.

`SessionEnteredWorld` fires once the HUD exists — the [action menu](../menugrid.md) included, so the
entries your addon adds go in from there — but much character-sheet data streams in for a few seconds
afterwards, see [missing data returns nil](../conventions.md#missing-data-returns-nil). When that session
is the one on screen it is also the point from which its
[per-character saved variables](../store.md) read back; a character reaching the world behind another
brings theirs to the tables when you tab to them, which is a `SessionSelected`.

**Taking the screen is not entering the world.** Tabbing between two characters already in the world
fires `SessionSelected` and nothing else, once per change — and only on a change, so tabbing to the
session already drawn fires nothing at all. Ending the session **on screen** hands the screen to another
one, so that session's `SessionDestroyed` comes first and a `SessionSelected` for the one taking over
follows it. Going to the login screen — which is where dropping your last session leaves you — selects
nothing, so it fires nothing, and the `SessionDestroyed` before it is what says the screen emptied.

**Nor is entering the world being looked at.** A session that reaches the world while another holds the
screen fires `SessionEnteredWorld` there and then, without ever having been drawn — the four are about
sessions, and only `SessionSelected` is about the screen. So a handler runs for a character you are not
looking at, and it reads that character through the `Session` it was handed rather than through
[`hafen.session():current()`](../session.md), which is whoever holds the screen at that moment and need
not be the one the event was about.

**A session is the account, and one account plays one character at a time.** Picking another character on
the same account keeps that session alive — the server hands it a new world rather than ending it — so
`SessionEnteredWorld` fires a second time for the same `Session`, with no `SessionDestroyed` between. Key
your own tables by `s:user()` if what you are tracking is the account, and rebuild whatever was that
character's on every `SessionEnteredWorld` for it.

**These report changes, not the state.** They fire for what happens after you subscribe, so an addon
loaded while three characters are up hears about none of the three; a `:reload` in the world re-announces
the session **on screen** with `SessionEnteredWorld`, because that is the one whose per-character saved
variables were just put back, and says nothing about the others.

> **Your state survives a character switch.** Nothing of yours is torn down or rebuilt when the screen
> moves, so a widget handle, a Gob or an [item](../ui/items.md) you kept from one character is still in
> your table under the next one — and still belongs to the character it came from. `SessionDestroyed` is
> where you drop what belonged to that session, and its payload is the key to drop it by.

## World

| Event | Payload | Fires |
|---|---|---|
| `GobAdded` | [Gob](../gob.md) | a game object enters the world or your view |
| `GobRemoved` | [Gob](../gob.md) | a game object leaves |
| `GobOverlayAdded` | `ev` — `:gob()` `:key()` `:native()` | something is attached to a game object — see [`gob:overlay()`](../overlay.md) |
| `GobOverlayRemoved` | `ev` — `:gob()` `:key()` `:native()` | something attached to a game object goes away |

Prefer these over scanning [`hafen.world():gob():list`](../world.md) every frame. `ev:gob()` is a live
[Gob object](../gob.md). On `GobRemoved` the gob is **already gone**, so only `gob:id()` answers there; if
you need its name, index it on `GobAdded`.

### Overlays coming and going

`GobOverlayAdded` and `GobOverlayRemoved` cover both halves of what
[`gob:overlay()`](../overlay.md) reads.

| `ev` on `GobOverlayAdded`/`GobOverlayRemoved` | Description |
|---|---|
| `ev:gob()` | the [Gob](../gob.md) the overlay is attached to |
| `ev:key()` | the overlay's key |
| `ev:native()` | `false` for one **you** attached, `true` for one the **game** put there |

`native` is `false` for one **you** attached and `true` for one the **game** put there (a lit fire's
flame, a crop's growth stage), and `key` is then its resource name.

```lua
hafen.event():on("GobOverlayAdded", function(ev)
  if ev:native() then hafen.log():write(ev:gob():id() .. " now carries " .. ev:key()) end
end)
```

The rules below make these predictable:

- **Yours are private, the game's are public.** An overlay key belongs to your addon, so a
  `native = false` event goes **only** to the addon that attached it — a key another addon cannot read
  is a name it cannot act on. Native events broadcast, because a resource name means the same thing to
  everyone.
- **They arrive on the next frame**, not inside the `:add` itself — the game's own overlays arrive on
  loader threads, and both halves use one moment. A handler runs on the UI thread and reads the truth:
  the overlay is already there on an add, already gone on a removal.
- **Re-attaching under the same key fires both** — the removal, then the add. The key survives; the thing
  under it does not.
- **The game's overlays are counted by key.** Several of them may share one resource and collapse to one
  key, so a second one of that resource arriving is not an add — read
  [`ov:count()`](../overlay.md) for the multiplicity.

When a gob leaves, **yours** on it are reported gone *before* that gob's own `GobRemoved`, so a handler
already reads the truth. The game's are not: the client drops a departing gob whole rather than taking its
overlays off one by one, and a native removal is reported only while the gob is still there. A `:reload`
fires neither: the addon that would hear it is the one going away.

## Character and status

These come from the HUD's own widgets, so they start once the HUD is up.

| Event | Payload | Fires |
|---|---|---|
| `MeterAdded` | [`Meter`](../meter.md) | a HUD meter bar appears |
| `MeterRemoved` | [`Meter`](../meter.md) | a HUD meter bar goes away — the object still reads, `:exists()` is false |
| `MeterChanged` | [`Meter`](../meter.md) | a meter bar's value or colour changes |
| `BuffAdded` | [`Buff`](../buff.md) | a buff appears |
| `BuffRemoved` | [`Buff`](../buff.md) | a buff goes away — the object still reads, `:exists()` is false |
| `BuffChanged` | [`Buff`](../buff.md) | a buff's content updates |
| `FepChanged` | [`Food`](../char.md#food) | FEP or hunger changes |
| `StudyChanged` | [`StudySlot`](../study.md#a-slot)`[]` | the study slots change: an add, a removal, or data resolving |
| `EquipChanged` | [`Item`](../ui/items.md#the-item-object)`[]` | worn equipment changes |
| `ActionbarChanged` | [`Slot`](../actionbar.md) | an action-bar slot is set, cleared or changed |
| `WoundChanged` | [`Wound`](../wound.md#a-wound)`[]` | a wound is added or healed, or its severity changes |

Items entering or leaving a **container** are not on this bus: a chest is not a global fact, so you
subscribe to the container itself with
[`widget:on("ItemAdded"/"ItemRemoved"/"Destroy", fn)`](../ui/items.md#the-container-lifecycle).
`EquipChanged` stays global because your worn gear is one fixed surface.

`ActionbarChanged` hands you the **changed slot** as a live [`Slot` object](../actionbar.md) — the same
interned object `hafen.actionbar():get(n)` returns, so `payload` and
`hafen.actionbar():get(payload:index())` are one object and you can key a table by it. `slot:index()` is
the raw 0-based game index. It fires on a set, a clear, a drag, or when a slot's data resolves, and
**not** on `:cooldown()` ticking, which would fire every frame — read the cooldown live off the object
instead. At login the occupied slots stream in as a burst, one fire each.

A slot [held](../actionbar.md#hold-a-slot-unprotected) for one of your own menu entries fires it on **both
edges**: once when the hold takes the slot, and once when it ends and the server's own content comes back.
The payload is the same `Slot`, and while the hold is on it `slot:res()` is the entry's identity.

> For the list events — `StudyChanged`, `EquipChanged`, `KinChanged`, `WoundChanged` — the payload is
> the **full new list**, not a delta. Read the initial state once with the section's own `:list()`
> verb, then listen.

## Roster, quests, markers

| Event | Payload | Fires |
|---|---|---|
| `KinChanged` | [`Kin`](../kin.md)`[]` | a kin is added, removed or edited, or flips online or offline |
| `QuestAdded` | [`Quest`](../quest.md#a-quest) | a new active quest appears |
| `QuestDone` | [`Quest`](../quest.md#a-quest) | an active quest is completed or failed |
| `MarkersChanged` | `n` (number) | a map marker is added or removed |

`KinChanged` hands you the **whole roster** as live [`Kin` objects](../kin.md), in Kin-window sort order —
the same interned objects `hafen.kin():list()` returns, so `payload[1]` and
`hafen.kin():get(payload[1]:id())` are one object and you can key a table by it. It tells you *that* the
roster changed, not *what* changed: keep your own map of the last state if you want to name who just came
online, and key it **by the `Kin` itself** rather than by `:name()`, so a rename does not read as one kin
leaving and another arriving.
[`kin:info()`](../types.md#kinentry) is there when you want a plain table instead.

The two quest events hand you the [`Quest`](../quest.md#a-quest) itself, which matters most on `QuestDone`:
it fires *because* the status changed, so a handler that keeps the object goes on reading it — including
`q:status()`, which is the field the event is about.

## Widgets appearing and disappearing

A widget is not a global fact either, so there is no `WidgetCreated` event. You say *which* widget you
care about, with the same [selector](../ui/selectors.md) a lookup uses:

```lua
hafen.ui():on("window[title=Cupboard]", "appear", function(w)
  hafen.log():write(#w:items() .. " items")
end)
```

`fn` receives the [Widget](../ui/widget.md) itself, and **`appear` also covers what is already open**,
because registering scans the live tree — so an addon reloaded with the window up still sees it. See
[watching for a widget](../ui/replace.md#watching-for-a-widget) for the two rules that matter: neither
event is about *visibility*, and at `disappear` the widget is a key to match, not something to read.

## The radial menu

| Event | Payload | Fires |
|---|---|---|
| `FlowerMenuOpened` | `string[]` — the petal captions, in ring order | a right-click puts up a radial menu |
| `FlowerMenuClosed` | `string` \| nil — the label picked | that menu goes away |

**Every `FlowerMenuOpened` is followed by exactly one `FlowerMenuClosed`**, whether you picked a petal,
pressed Esc, clicked away, or the menu died under you; the payload is `nil` for everything but a pick. Both
cover the menus the client puts up itself, such as the Kin window's, as well as the server's. Read the ring
from the payload or from [`hafen.flowermenu()`](../flowermenu.md), which is the open menu and also names the
object it was opened on.

## World ghosts and sprites

| Event | Payload | Fires |
|---|---|---|
| `GhostClicked` | `ev` — `:ghost()` `:button()` `:x()` `:y()` | a **clickable** [ghost](../vr/ghosts.md) of *your* addon is clicked |
| `SpriteClicked` | `ev` — `:sprite()` `:button()` `:x()` `:y()` | a **clickable** [sprite](../vr/sprites.md#clickability) of *your* addon is clicked |
| `ObjectClicked` | `ev` — `:object()` `:button()` `:x()` `:y()` | a **clickable** [glTF object](../vr/models.md#clickability) of *your* addon is clicked |

All three are **owner-scoped**: they fire only to the addon that owns the clicked entity, unlike the
world and roster events above, which broadcast. That is because a ghost, sprite or object is private
to its addon and its handle never leaves it.

| `ev` on `GhostClicked`/`SpriteClicked`/`ObjectClicked` | Description |
|---|---|
| `ev:ghost()` / `ev:sprite()` / `ev:object()` | the clicked [entity](../vr/README.md#one-vocabulary-four-kinds) — only the one matching the event fires reads non-nil |
| `ev:button()` | 1 for left, 3 for right |
| `ev:x()` `ev:y()` | the world point the click resolved to |

The click is **consumed** — no server click, no character walk. An entity fires this only while
clickable; a non-clickable one is click-through and silent, and a sprite facing `"screen"` has no
world mesh, so it is never picked at all.

## What is deliberately not an event

Data that changes only on an explicit, infrequent player action has no change event: available skills,
credos, lore, crafting recipes, combat schools, minimap icon categories, movement speed. Read those on
demand, from their own section's verbs.

## See also

- [`hafen.event()`](README.md) — subscribing, and why the key set is closed
- [the message streams](streams.md) — the two open-keyed doors, for a message rather than a fact
- [data types](../types.md) — what `:info()` copies out of a payload, shape by shape
- [`hafen.session`](../session.md) — the payload the four hand you, and the collection of the rest
- [`hafen.timer`](../timer.md) — for what the bus cannot tell you: polling on your own schedule
- [when your code runs](../../runtime.md) — the whole life of an addon, of which these are the moments
