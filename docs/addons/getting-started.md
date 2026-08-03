# Getting started

An addon is a folder of Lua files plus a manifest, dropped into the client's `addons/` directory. This
guide covers the anatomy of an addon; the [API reference](api/README.md) covers what your code can do.

## Your first addon

Create a folder under `addons/` whose name matches the addon's `id`:

```
addons/
  myaddon/
    manifest.json
    main.lua
```

`manifest.json`:

```json
{
  "id": "myaddon",
  "name": "My Addon",
  "version": "1.0.0",
  "author": "you",
  "description": "My first addon.",
  "files": ["main.lua"]
}
```

`main.lua`:

```lua
hafen.log("my addon loaded")

hafen.events.on("OnEnterWorld", function()
  hafen.log("entered the world as " .. (hafen.player():name() or "?"))
end)
```

Launch the client (or run `:reload` if it's already running) and your addon runs. Enable/disable it in
**Options → AddOns**, or with `:addons`.

## The manifest

`manifest.json` describes the addon. Fields:

| Key | Required | Meaning |
|---|---|---|
| `id` | yes | unique id; **must equal the folder name** |
| `files` | yes | array of `.lua` files to run, in order |
| `name` | no | display name (defaults to `id`) |
| `version` | no | version string |
| `author` | no | author |
| `description` | no | shown in the AddOns panel |
| `api_version` | no | API level the addon targets (default `1`) |
| `dependencies` | no | array of addon ids required first |
| `optional_dependencies` | no | array of addon ids to load first if present |
| `saved_variables` | no | persistent tables — see [saved variables](#saved-variables) |
| `permissions` | no | capabilities the addon requests — see [actions](#actions--permissions) |

## The lifecycle

Your files run once when the addon loads. From there, drive everything off
[events](api/events.md):

| Event | When |
|---|---|
| `OnLoad` | the addon loaded (before entering the world) |
| `OnEnterWorld` | you entered the world (and re-fired on `:reload` in-world) |
| `OnUpdate(dt)` | every frame |
| `OnDisable` | the addon is disabled, reloaded, or the session ends |

```lua
hafen.events.on("OnUpdate", function(dt)
  -- runs every frame — keep it cheap
end)
```

Much character data (HUD meters, food, skills, quests, …) streams in a beat *after* `OnEnterWorld`. Read it
on a short [timer](api/timer.md) or subscribe to the matching `*Changed` event rather than reading it
immediately on enter-world.

## The sandbox

Addon code runs in a restricted Lua environment. The standard `string`, `table`, and `math` libraries
are available, plus a safe subset of `os` (time/date). **Not** available: `io`, `os.execute`, `require`
/ `package`, `load`/`loadfile`/`dofile`, `debug`, `coroutine`, and the Java bridge — an addon interacts
with the client only through the `hafen.*` API. An instruction watchdog aborts a runaway loop, and an
addon that consistently overruns its per-frame budget is auto-disabled.

## Reading & reacting

The bulk of the API is read access + events. A quick tour:

```lua
-- read the world
local nearby = hafen.world.within(20, "gfx/borka/body")   -- players within 20 units (Gob objects)
local me     = hafen.player():gob()                       -- your own Gob (nil before enter-world)
local pos    = me and me:pos()

-- react to changes
hafen.events.on("MeterChanged", function(m)
  if m:res() == "gfx/hud/meter/hp" and (m:value() or 1) < 0.3 then hafen.log("low health!") end
end)
```

See the [API reference](api/README.md) for every section.

## Saved variables

Declare persistent tables in the manifest, then use them like any table:

```json
"saved_variables": ["settings", { "name": "account", "scope": "account" }]
```

```lua
hafen.store.settings.count = (hafen.store.settings.count or 0) + 1
```

A bare name is per-character; `{"name": ..., "scope": "account"}` is shared across your characters. See
[`hafen.store`](api/store.md).

## Custom UI & hotkeys

Draw your own windows and overlays with [`hafen.ui`](api/ui.md), declare hotkeys with
[`hafen.client:options():keybindings()`](api/client.md#keybindings), and add console commands with [`hafen.slash`](api/console.md):

```lua
hafen.client:options():keybindings():register("panic", function() hafen.log("panic!") end)
hafen.slash.register("myaddon", function(args) hafen.log("hi " .. (args[1] or "")) end)
```

An addon hotkey starts **unbound**: you name the action, the user assigns the key under
**Options ▸ Keybindings**, in a section named after your addon. Advertise a *suggested* key in your
README rather than claiming one.

## Reading the client's own UI

The window you just created and the client's own windows are the **same kind of object** — a
[Widget](api/ui.md#the-widget-object). `hafen.ui("window[title=Cupboard]")` names one with a
[selector](api/ui.md#selectors--naming-a-widget), `hafen.ui()` is the top of the tree, `hafen.ui.at(x, y)`
is whatever is under a point, and a container answers for what is inside it:

```lua
for _, it in ipairs(hafen.ui.inventory():items()) do   -- a chest works the same way
  hafen.log(it.name or it.res or "?")
end
```

Reading costs nothing and hides nothing — the window stays open and usable. Widgets are interned, so `==`
tells you whether two lookups found the same one. You may **write** only to widgets your addon created
(move, resize, destroy); on the client's own the one write is `:hide()`/`:show()`, and it is undone for
you on reload/disable.

**Hiding one of the client's own windows takes its toggle**: hide the inventory and Tab no longer brings it
back, with the menu button's tick going off with it. Put your own window in its place with
[`w:replace(view)`](api/ui.md#replacing-a-native-window) — a verb on the widget, and the one that hides the
**enclosing** window rather than the widget you point at — and that same key and button drive **your** view
instead; nothing to wire, since the verb knows both halves. Reload or disable and both come back, the
stock window ending up [as the user was seeing it](api/ui.md#hiding-a-native-widget-carries-a-restore):
open if your view was on screen, closed if nothing was.

The same string also names a widget that **is not there yet**, so you never have to poll for a window:

```lua
hafen.ui.on("window[title=Cupboard]", "appear", function(w)
  hafen.log(("cupboard open: %d item(s)"):format(#w:items()))
end)
```

`appear` also fires for what is **already** open when you subscribe, so a `:reload` with the window up still
reaches your handler.

**Don't guess a selector — hover for it.** Enable the bundled **`widgetstack`** addon and point at any part of the
client: it reports that widget's role, class, title and resource, and offers the selectors that actually match it
(each one resolved before it is shown), ready to paste into `:lua`. That is the fastest way to learn the
vocabulary — see [selectors](api/ui.md#selectors--naming-a-widget).

## Restyling the client

The same selector is the key of a **stylesheet**: one table that says what the client looks like, applied live
and owned by your addon.

```lua
hafen.ui.skin{
  ["*"]            = { font = hafen.font("serif"):derive{ size = 11 } },   -- most UI text
  ["chat"]         = { color = {190, 210, 190} },                          -- one surface
  ["window.frame"] = { bg = { color = {26, 26, 28, 240} },                 -- the window chrome
                       border = { image = hafen.asset("frame.png"), slice = {12, 40, 12, 12} },
                       pad = 4 },
}
```

A key names either a **surface the client draws** (`chat`, `window.title`, `window.frame`, `panel`, …) or, being
an ordinary selector, **the widgets it matches** — `["window[title=Cupboard]"]` styles that window and everything
inside it. Rules cascade most-specific-first and compose property by property, so a narrow rule never silently
drops a broad one. `hafen.ui.skin(nil)`, `:reload` or disabling your addon puts the stock client back.

Nothing here is code the client calls: a rule is plain data — a font handle, a colour, an image, a number of
pixels — which is why a whole theme can *be* a file. The bundled **`theme`** addon is exactly that, a
`theme.json` its Lua reads without ever naming a surface. See
[the stylesheet](api/ui.md#the-stylesheet--restyling-the-client) and the
[property × key table](api/ui.md#what-each-key-accepts) for what each key does with each property.

## Files your addon ships

Drop an image, a font, a glTF model or a data file in your addon's folder and load it with
[`hafen.asset(path)`](api/asset.md) — one door for all four, the type coming from the extension:

```lua
local icon = hafen.asset("icon.png")                 -- draw with g:image / stand with hafen.render.sprite
local face = hafen.asset("fonts/Inter.ttf")          -- font= on a window, or hafen.ui.skin{…}
local chair = hafen.asset("props/chair.glb")         -- stand with hafen.render.object
local conf = hafen.asset("theme.json")               -- its :text(), for hafen.json.parse
```

Paths are **relative to your own folder** (absolute paths and `..` are rejected), the same path always
returns the **same handle**, and everything is freed for you on reload/disable. Load from setup code
(`OnLoad`), never inside a draw. Engine `.res` content is *addressed*, not loaded — that is
[`g:resource`](api/ui.md#the-g-draw-wrapper), [`hafen.sound(name)`](api/audio.md),
[`hafen.ghost`](api/ghost.md) and [`hafen.font(name)`](api/fonts.md#the-built-ins--hafenfontname).

## Actions & permissions

Reading the game needs no permission. **Acting on the game** — moving, clicking, using items — is gated:
the addon must declare it and the user must opt in.

```json
"permissions": ["actions"]
```

A write-declaring addon is **disabled by default**; enabling it (Options → AddOns) prompts a consent
dialog. Once enabled, [`hafen.act`](api/actions.md) and the per-subsystem write verbs
([`hafen.speed.set`](api/speed.md), [`hafen.craft.make`](api/craft.md),
[`slot:use`](api/actionbar.md), [the kin write verbs](api/kin.md)) work.
`hafen.act.enabled()` tells you whether you're allowed, without throwing.

## Developing

The client provides a developer loop:

| Command | Does |
|---|---|
| `:reload` | rebuild the addon layer — reloads your Lua without restarting the client |
| `:addons` | list discovered addons and their status |
| `:addons enable\|disable <id>` | toggle an addon (applied on the next `:reload`) |
| `:lua <expr>` | evaluate a Lua expression against the live API (results shown as JSON) |

`:lua` is the fastest way to explore — e.g. `:lua hafen.meter("hp"):value()` or
`:lua hafen.world.count("tree")`.
