# Overlay: What Is Drawn at a Gob

`gob:overlay()` is the collection of everything painted at a gob. That is the game's own (a fire's flame), your screen-space painters and labels, and whatever you have [standing in the world](virtual/README.md) anchored to it. One of yours is keyed by your own name.

```lua
local session = hafen.session():current()          -- the character on screen
local my_gob = session and session:player():gob()  -- nil until that session is in the world
if my_gob then my_gob:overlay():add("mark"):text("here"):color{255, 90, 90} end
```

---

| Rule | Detail |
|---|---|
| Unprotected | The attach included: what you paint at a gob is your own drawing and changes nothing the server, the client or another addon owns. The same basis as [a HUD overlay](ui/overlay.md). |
| Hangs on the object, not a character | Every character of yours that sees the gob draws it, one that loads the object afterwards included. One `:remove(key)` takes it off all of them. |
| Ends with the object | When the last character loses sight of it the record goes, the moment [`GobRemoved`](event/bus/world.md#world) fires. |
| Not here | A crop's growth stage is server state: [`gob:sdt()`](gob.md#state). |

> **Hiding the object does not hide what stands at it.** [`gob:visible(false)`](look.md) withholds the model alone. Your overlays and the game's own (name label, health bar) go on drawing over empty ground. Remove yours in the same call if that is not what you want.

## The collection

The standard [collection](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) shape, keyed by your name for an overlay.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `gob:overlay():list(filter)` | `Overlay[]` | Unprotected | Everything drawn at the gob: yours, then what you stood there, then the game's own. |
| `gob:overlay():count(filter)` | `number` | Unprotected | How many. |
| `gob:overlay():get(key)` | `Overlay \| nil` | Unprotected | That one. |
| `gob:overlay():find(filter)` | `Overlay \| nil` | Unprotected | The first one matching. |
| `gob:overlay():add(key)` | `Overlay` | Unprotected | Attach a bare one, or replace what that key already named. |
| `gob:overlay():remove(key)` | the collection | Unprotected | Remove it. |

```lua
my_gob:overlay():add("hp"):text("hurt"):color{255, 90, 90}:offset(0, -6)
my_gob:overlay():add("ring"):draw(function(graphics, gob, screen_x, screen_y)
  graphics:frect(screen_x - 2, screen_y - 2, 4, 4)
end)
my_gob:overlay():remove("hp")
```

| Rule | Detail |
|---|---|
| `filter` | A string matches the key as a substring. |
| Once the gob is gone | The collection is empty, `:get` answers `nil`, `:remove` is inert (the overlay died with the gob), `:add` raises (nothing left to attach to). |
| Keys are per addon | Two addons using `"tag"` on one gob do not collide and cannot see each other's. The collection answers yours and the game's. `:add` on a key already there replaces it, leaving one overlay. |
| The game's own are read-only | Listed with `native = true`, keyed by resource name. `:add` onto such a key raises, so does `:remove`, so does every setter, always naming the key. |
| A thing you stood at the gob is read-only here | A [sprite](virtual/sprites.md), [object](virtual/models.md) or [ghost](virtual/ghosts.md) anchored to the gob is listed under a generated key (`"virtual#7"`) with `:native()` `false`. Every setter raises on it naming the collection that owns it. Address it through `hafen.virtual():sprite()` / `:object()` / `:ghost()`. |
| No filter form of `add` | "Every player gets a label" is a [`GobAdded`](event/bus/world.md#world) handler plus a loop over [`session:world():gob():list()`](world.md#objects). |

### What one gob holds

| Bound | Detail |
|---|---|
| 32 keys | How many overlays your addon may have on one gob. Another addon's are not counted against yours, and replacing a key you hold spends nothing (`:add("hp")` twice is one overlay). |
| 128 characters | How long a key may be: your name for one overlay, not a place for the data behind it. |
| 256 characters | How long `overlay:text(text)` may be: a label stands over an object at one blit. |

Each ceiling raises naming itself.

## What an overlay of yours draws

`:add(key)` attaches a bare overlay. The kind setters say what it draws at the gob's projected screen point. That point is taken 15 world units up the object by default (just above the head) and moved by `overlay:height(z)`. Every setter answers the overlay, so one statement configures the whole thing.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `overlay:draw(fn)` | the overlay | Unprotected | `fn(graphics, gob, screen_x, screen_y)` runs every frame at that point, painting with [`graphics`](ui/drawing.md). `screen_x, screen_y` are [design pixels](ui/pixels.md): the projected point to the whole device pixel. Its fraction is [carried under the whole painter](ui/drawing.md#draw-methods), so what you draw moves smoothly with the object. |
| `overlay:text(text)` | the overlay | Unprotected | A label at that point, drawn by the client: no Lua at the draw. |
| `overlay:color(color)` | the overlay | Unprotected | The label's [colour](shapes.md#colours), `{200, 210, 220}` or `{r=, g=, b=[, a=]}`. |
| `overlay:font(handle)` | the overlay | Unprotected | The [face](font.md) the label is drawn in. The client's stock font when you set none. |
| `overlay:height(z)` | the overlay | Unprotected | World units up the object at which the point is projected. `15` by default, `0` the ground under it. |
| `overlay:offset(x, y)` | the overlay | Unprotected | Screen pixels from the projected point, in [design pixels](ui/pixels.md). A third argument raises, naming `height` as the vertical world form. |

| Rule | Detail |
|---|---|
| One kind | A bare overlay draws nothing. A second, different kind raises naming the first. |
| Read-back | Every setter has a bare read of the same name, so the configuration is the overlay's own state: `overlay:text("healed")` relabels a live overlay. Once the overlay is gone every read answers `nil` and every write is inert. `overlay:font()` gives back the handle you passed, `nil` while the label is in the client's face. A value that is not a handle raises naming where one comes from. |
| Two units | `height` is the world (up the object's own axis: `0` stands on the ground and rides the slope, `15` is over the head). `offset` is the screen (pixels from the projected point, the design pixel `screen_x, screen_y` is in). Each record carries its own height. A horizontal offset in the world is [`hafen.virtual`](virtual/README.md). |
| Behind the camera | Neither kind runs for a gob behind the camera: the projection would answer a point mirrored through the view. Pulled all the way in on the `bad` camera that is every gob behind the character. |
| Label cost | One rasterisation for its lifetime through the [text cache](ui/drawing.md#text-is-cached-across-frames) `graphics:text` uses. Every later frame is a lookup and a blit with no Lua call. An overlaid gob allocates nothing in a frame it does not change. Relabelling rasterises once more. The colour is not cached and may change every frame. The face may carry an [outline](font.md#an-outline-round-every-glyph), baked into the same raster. |
| Not here | No `overlay:clickable` or `overlay:onClick`: the thing under an overlay is the gob, and clicking a gob is [`session:world():click`](world.md#write-protected). No `overlay:move`: the position is the gob's. You set height and offset. |
| Attach timing | From [`GobAdded`](event/bus/world.md#before-the-first-drawn-frame) an attach is never too early. The client holds a new object out of the scene until the handler has run. The overlay is on it before its first drawn frame. Later, an object whose drawing is still resolving takes no overlay in that instant and raises. A timer is the retry. A setter that raises leaves the overlay as it was. |
| A `draw` callback is in the draw pass | It runs inside the client's draw pass, outside the per-tick CPU budget: keep it short. A `text` overlay never enters Lua and is the cheaper label. |

## The Overlay object

One entry of the collection: yours, the game's, or something you stood at the gob. Every read answers on every kind. What a kind has nothing to say about is `nil`.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `overlay:key()` | `string` | Unprotected | What it answers to: the resource name for a native one, a generated one for a thing standing there. |
| `overlay:gob()` | Gob | Unprotected | The gob it hangs on. |
| `overlay:native()` | `boolean` | Unprotected | Whether it is the game's own. |
| `overlay:kind()` | `string \| nil` | Unprotected | `"draw"`/`"text"` for yours, `"ghost"`/`"sprite"`/`"object"` for one you stood there. `nil` for a native one and for a bare one. |
| `overlay:res()` | `string \| nil` | Unprotected | What it is drawn from (resource name, asset path). `nil` for a painter of yours, and once the overlay is gone. |
| `overlay:count()` | `number \| nil` | Unprotected | How many of the game's own overlays this entry stands for. |
| `overlay:exists()` | `boolean` | Unprotected | Still there. |
| `overlay:info()` | `table \| nil` | Unprotected | A plain snapshot. The shape follows the kind. |

| Rule | Detail |
|---|---|
| A native overlay is a union | A gob may carry several overlays of one resource, and the name is all a key can address. So they collapse to one Overlay, and `:count()` publishes the multiplicity. Yours count 1. `gob:info().overlays` is the uncollapsed list of resource names, absent when the gob carries none, like every [`GobInfo`](types/world.md#gobinfo) field. |
| `:info()` shapes | Both carry `key`, `native`, `count`. Yours adds `kind` and `world` (`true` for a thing standing in the world, `false` for a painter, both absent while bare). It adds `res` when drawn from a resource or asset path, and `height` on a painter of yours. The face is not in it: a font handle is a live object. A native one adds `res` (the key itself) and carries neither `kind` nor `world`. |
| Interned on the key | `gob:overlay():get(key)` hands back the same object every time. A replace leaves the handle you hold naming the new record. `:exists()` goes false when removed or when the gob is gone. `tostring(overlay)` is `Overlay(<key>@<gobid>)`, with `*` before the key on the game's own and `~` on a thing you stood there. |
| Dies with its gob | The record lives on the game object: a felled tree takes yours with it and nothing is kept. A gob that returns is bare, and re-attaching is your call from [`GobAdded`](event/bus/world.md#world). The moment is the last of your characters losing it, when [`GobOverlayRemoved`](event/bus/world.md#overlays-coming-and-going) fires. A thing you stood here [dies with the gob too](virtual/README.md#the-anchor-is-an-argument). |
| Dies with your addon | A `:reload` or a disable removes every overlay you attached from every character and leaves the game's. |
| Events | [`GobOverlayAdded`/`GobOverlayRemoved`](event/bus/world.md#overlays-coming-and-going) fire for what you attach and for what the game attaches. |

```lua
local icon = hafen.asset():get("icon.png")
local tree = hafen.session():current():world():gob():nearest("trees/oak")
hafen.virtual():sprite():add(icon, tree):offset(0, 0, 20)    -- stood in the world, at the gob
for _, overlay in ipairs(tree:overlay():list()) do
  hafen.log():write(overlay:key() .. " " .. tostring(overlay:kind()))  -- ...and listed here: "virtual#7 sprite"
end
```

---

## See Also

- [Gob](gob.md) — the object an overlay hangs on, and everything else it answers.
- [`hafen.virtual`](virtual/README.md) — standing a sprite, a model or a ghost at a gob instead.
- [Drawing](ui/drawing.md) — the `graphics` wrapper a `draw` callback paints with.
- [Events](event/bus/world.md#overlays-coming-and-going) — watching one arrive instead of polling.
- [UI overlays](ui/overlay.md) — the same vocabulary over the screen and over one widget.
