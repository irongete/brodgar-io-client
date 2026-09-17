# Getting Started

One addon, from an empty folder to a window with a hotkey that remembers whether it was open. About ten minutes, with no setup beyond the client you run. Every block goes into the same two files, in order.

## Step 1: make the folder

An addon is a folder of Lua files plus a manifest in the client's `addons/` directory. Create `myaddon`:

```text
addons/
  myaddon/
    manifest.json
    main.lua
```

The folder name is the addon's id, and `manifest.json` repeats it:

```json
{
  "id": "myaddon",
  "name": "My Addon",
  "version": "0.1.0",
  "author": "you",
  "description": "My first addon.",
  "api_version": "1.0",
  "files": ["main.lua"]
}
```

`id` and `files` are required. `api_version` names the API you wrote against, the one these pages describe. Without it the client leaves your addon out as out of date. The rest is what the [AddOns manager](panel.md) shows. Every field is in [the manifest](manifest.md).

## Step 2: write a line of Lua

```lua
hafen.log():write("myaddon loaded")
```

Everything an addon can do hangs off the global `hafen` table. [`hafen.log`](api/log.md) prints to the in-game console and the terminal, tagged with your addon's id.

## Step 3: run it

Start the client, or press `:` and type:

```text
:reload
```

`:reload` rebuilds the addon layer from disk without touching your session. A newly discovered addon is enabled, so `myaddon loaded` appears in the console. If not, [`:addons`](runtime.md#the-console-commands) lists what the client found and what it made of it. Every code change from here is the same loop: edit, `:reload`, look at the console.

## Step 4: react to entering the world

Your file body runs once, before you are in the world. The rest of an addon hangs off [events](api/event/README.md). Replace the line from step 2:

```lua
hafen.log():write("myaddon loaded")

hafen.event():on("SessionEnteredWorld", function(session)
  hafen.log():write("in the world as " .. (session:character() or "?"))
end)
```

`SessionEnteredWorld` fires when a character's HUD is up, at login and again on a `:reload` for every login in the world. It hands the [session](api/session.md) that entered (the client holds several logins at once), and `session:character()` is the character it plays.

## Step 5: draw a window

`hafen.ui():window()` is a draggable, titled window whose content you paint. Build it when you enter the world and keep the handle:

```lua
local window                                    -- the window, once the character is in the world
local settings                                  -- this character's var, once it is up
local tree_count = 0                            -- what it displays

hafen.event():on("SessionEnteredWorld", function(session)
  hafen.log():write("in the world as " .. (session:character() or "?"))
  window = hafen.ui():window():title("My Addon"):size(150, 24):position(60, 60)
  window:on("Draw", function(draw_event)
    local graphics = draw_event:g()
    graphics:color(255, 220, 120)
    graphics:text("trees nearby: " .. tree_count, 6, 4)
  end)
end)
```

`Draw` fires every frame, and `draw_event:g()` is the [drawing surface](api/ui/drawing.md): a colour, then a string at a widget-local pixel. Reload, and the window is on screen.

## Step 6: count something

A draw callback draws and nothing else, so count on a [timer](api/timer.md) and let the window read the result. Below the block from step 5:

```lua
hafen.timer():every(1, function()
  local session = hafen.session():current()      -- the character on screen, nil on the login screen
  tree_count = session and session:world():gob():count("terobjs/tree") or 0
end)
```

[`session:world():gob():count`](api/world.md) counts the game objects whose resource name contains what you passed, around that character. A world belongs to a character, so the read says which: [`hafen.session():current()`](api/session.md) is the one on screen. Reload, and the number moves as you walk.

## Step 7: add a hotkey

An addon hotkey is declared by name and starts unbound: you name the action, the user assigns the key. At the end of the file:

```lua
hafen.client():options():keybindings():on("toggle", function()
  hafen.timer():after(0, function()               -- the next step: the key press runs in the character's tree, the window stands in yours
    if not window then return end
    if window:visible() then window:visible(false) else window:visible(true) end
  end)
end)
```

Reload, then open Options ▸ Game ▸ Keybindings: a **My Addon** section holds one action, `toggle`. Assign a key, and it hides and shows your window. Your addon owns the window, so both verbs answer on it ([owned vs borrowed](api/ui/writes.md#owned-vs-borrowed)). A hotkey fires inside the tree of the character on screen, and your window stands in the addon layer, a tree of its own. So the toggle is handed to the next step with `hafen.timer():after(0, fn)` ([threading](api/threading.md#getting-onto-the-step-from-a-handler-that-holds-a-tree)).

## Step 8: remember it across sessions

A var is a table the client saves for you, existing the first time you name it. Asked of a session's store it is that character's own. At the end of the `SessionEnteredWorld` handler:

```lua
  settings = session:store():var("settings")
  if settings.open == false then window:visible(false) end
```

and the body the hotkey hands to the step becomes:

```lua
    if window:visible() then window:visible(false) else window:visible(true) end
    settings.open = window:visible()
```

The client fills the table from disk when you name it and writes it back for you. The reference stays live. Reload, hide the window, log out and back in: it stays hidden. [Vars](api/store/vars.md) has the client scope and what a saved table may hold.

## The whole addon

`addons/myaddon/manifest.json`:

```json
{
  "id": "myaddon",
  "name": "My Addon",
  "version": "0.1.0",
  "author": "you",
  "description": "My first addon.",
  "api_version": "1.0",
  "files": ["main.lua"]
}
```

`addons/myaddon/main.lua`:

```lua
local window                                    -- the window, once the character is in the world
local settings                                  -- this character's var, once it is up
local tree_count = 0                            -- what it displays

hafen.log():write("myaddon loaded")

hafen.event():on("SessionEnteredWorld", function(session)
  hafen.log():write("in the world as " .. (session:character() or "?"))
  window = hafen.ui():window():title("My Addon"):size(150, 24):position(60, 60)
  window:on("Draw", function(draw_event)
    local graphics = draw_event:g()
    graphics:color(255, 220, 120)
    graphics:text("trees nearby: " .. tree_count, 6, 4)
  end)
  settings = session:store():var("settings")
  if settings.open == false then window:visible(false) end
end)

hafen.timer():every(1, function()
  local session = hafen.session():current()
  tree_count = session and session:world():gob():count("terobjs/tree") or 0
end)

hafen.client():options():keybindings():on("toggle", function()
  hafen.timer():after(0, function()               -- the next step: the key press runs in the character's tree, the window stands in yours
    if not window then return end
    if window:visible() then window:visible(false) else window:visible(true) end
    settings.open = window:visible()
  end)
end)
```

The addon uses a manifest, a lifecycle event and a read. It has a surface of its own, an input the user controls, and data that outlives the session.

## Where to go next

| Page | Covers |
|---|---|
| [The guides](guides/README.md) | One page per task: reading the world, events and timers, custom UI, saved data, hotkeys and commands, permissions, theming, translating, debugging. |
| [The API reference](api/README.md) | Every `hafen.*` verb, one page per namespace. |
| [The manifest](manifest.md) | The manifest in full, and the API version it declares. |
| [The runtime](runtime.md) | The sandbox, the budgets and the console commands. |
| [The AddOns manager](panel.md) | Enabling what you installed, and searching the hub. |
| [The maintainer's addons](examples.md) | Where the addons are, and the tools among them. |
