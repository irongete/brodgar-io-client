# Getting started

You are going to write one addon, from an empty folder to a window with a hotkey that remembers whether it
was open. It takes about ten minutes and no setup beyond the client you already run. Every block below goes
into the same two files, in the order they appear.

## Step 1: make the folder

An addon is a folder of Lua files plus a manifest, and it lives in the client's `addons/` directory, next
to the addons that already ship. Create one called `myaddon`:

```text
addons/
  myaddon/
    manifest.json
    main.lua
```

The folder name is the addon's **id**, and `manifest.json` has to repeat it. Write the manifest:

```json
{
  "id": "myaddon",
  "name": "My Addon",
  "version": "0.1.0",
  "author": "you",
  "description": "My first addon.",
  "files": ["main.lua"]
}
```

`id` and `files` are the two required fields; the rest is what the AddOns panel shows about you. Every
field the manifest accepts is listed in [the runtime](runtime.md#the-manifest).

## Step 2: write a line of Lua

Put one line in `main.lua`:

```lua
hafen.log():write("myaddon loaded")
```

Everything an addon can do hangs off the global `hafen` table. [`hafen.log`](api/log.md) prints to the
in-game console and to the terminal the client was started from, tagged with your addon's id.

## Step 3: run it

Start the client, or — if it is already running — press `:` to open the command line and type:

```text
:reload
```

`:reload` rebuilds the addon layer from disk without touching your session: you stay logged in, and your
edits are live. A newly discovered addon is enabled, so `myaddon loaded` appears in the console straight
away. If it does not, [`:addons`](runtime.md#the-console-commands) lists what the client found and what it
made of it.

> Every code change from here on is the same loop: edit the file, `:reload`, look at the console.

## Step 4: react to entering the world

Your file body runs once, before you are in the world, so there is nothing to read yet. The rest of an
addon hangs off [events](api/event.md). Replace the line from step 2 with:

```lua
hafen.log():write("myaddon loaded")

hafen.event():on("OnEnterWorld", function()
  hafen.log():write("in the world as " .. (hafen.player():name() or "?"))
end)
```

`OnEnterWorld` fires when the HUD is up, at login and again on every `:reload` while you are in-world — so
it is where an addon starts its real work. [`hafen.player`](api/player.md) is your own character.

## Step 5: draw a window

`hafen.ui.window{…}` gives you a draggable, titled window whose content you paint yourself. Build it when
you enter the world, and keep the handle:

```lua
local window                                    -- the window, once we are in the world
local trees = 0                                 -- what it displays

hafen.event():on("OnEnterWorld", function()
  hafen.log():write("in the world as " .. (hafen.player():name() or "?"))
  window = hafen.ui.window{
    title = "My Addon",
    size  = {150, 24},
    pos   = {60, 60},
    onDraw = function(g, w, h)
      g:color(255, 220, 120)
      g:text("trees nearby: " .. trees, 6, 4)
    end,
  }
end)
```

`onDraw` runs every frame, and `g` is the [drawing surface](api/ui/drawing.md): a colour, then a string at
a widget-local pixel. `trees` is still `0` — step 6 fills it in. Reload, and the window is on screen.

## Step 6: count something

A draw callback should draw and nothing else, so do the counting on a [timer](api/timer.md) and let the
window read the result. Add this below the block from step 5:

```lua
hafen.timer():every(1, function()
  trees = hafen.world():gob():count("terobjs/tree")
end)
```

[`hafen.world():gob():count`](api/world.md) counts the game objects whose resource name contains what you passed —
every tree the client has loaded around you. Reload, and the number moves as you walk.

## Step 7: add a hotkey

An addon hotkey is declared by name and starts **unbound**: you name the action, the user assigns the key.
Add this at the end of the file:

```lua
hafen.client:options():keybindings():register("toggle", function()
  if not window then return end
  if window:visible() then window:hide() else window:show() end
end)
```

Reload, then open Options ▸ Keybindings: there is a **My Addon** section holding one action, `toggle`.
Assign a key to it, and it hides and shows your window. Because your addon owns the window, both verbs
answer on it — see [owned vs borrowed](api/ui/widget.md#owned-vs-borrowed).

## Step 8: remember it across sessions

The window should come back the way you left it. Declare a saved variable in `manifest.json`, next to
`files`:

```json
"saved_variables": ["settings"]
```

`hafen.store.settings` is then an ordinary table that the engine fills before `OnEnterWorld` and writes
back to disk for you. Record the state in the hotkey, and apply it when the window is built:

```lua
  if hafen.store.settings.open == false then window:hide() end
```

goes at the end of the `OnEnterWorld` handler, and the hotkey's body becomes:

```lua
  if window:visible() then window:hide() else window:show() end
  hafen.store.settings.open = window:visible()
```

Reload, hide the window, log out and back in: it stays hidden. See [`hafen.store`](api/store.md) for the
account-wide scope and for what a saved table may hold.

## The whole addon

`addons/myaddon/manifest.json`:

```json
{
  "id": "myaddon",
  "name": "My Addon",
  "version": "0.1.0",
  "author": "you",
  "description": "My first addon.",
  "files": ["main.lua"],
  "saved_variables": ["settings"]
}
```

`addons/myaddon/main.lua`:

```lua
local window                                    -- the window, once we are in the world
local trees = 0                                 -- what it displays

hafen.log():write("myaddon loaded")

hafen.event():on("OnEnterWorld", function()
  hafen.log():write("in the world as " .. (hafen.player():name() or "?"))
  window = hafen.ui.window{
    title = "My Addon",
    size  = {150, 24},
    pos   = {60, 60},
    onDraw = function(g, w, h)
      g:color(255, 220, 120)
      g:text("trees nearby: " .. trees, 6, 4)
    end,
  }
  if hafen.store.settings.open == false then window:hide() end
end)

hafen.timer():every(1, function()
  trees = hafen.world():gob():count("terobjs/tree")
end)

hafen.client:options():keybindings():register("toggle", function()
  if not window then return end
  if window:visible() then window:hide() else window:show() end
  hafen.store.settings.open = window:visible()
end)
```

That is an addon: a manifest, a lifecycle, a read, a surface of its own, an input the user controls, and
data that outlives the session.

## Where to go next

- [the guides](guides/README.md) — one page per task: reading the world, events and timers, custom UI,
  saved data, hotkeys and commands, actions and permissions, theming, debugging
- [the API reference](api/README.md) — every `hafen.*` verb, one page per namespace
- [the runtime](runtime.md) — the manifest in full, the sandbox, the budgets, the AddOns panel and the
  console commands
- [the example addons](examples.md) — the addons that ship with the client, and what each one demonstrates
