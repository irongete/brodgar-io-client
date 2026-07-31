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

Much character data (vitals, food, skills, quests, …) streams in a beat *after* `OnEnterWorld`. Read it
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
hafen.events.on("VitalsChanged", function(v)
  if v.hp < 0.3 then hafen.log("low health!") end
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

`:lua` is the fastest way to explore — e.g. `:lua hafen.player():vitals()` or
`:lua hafen.world.count("tree")`.
