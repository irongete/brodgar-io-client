# Hotkeys, Commands and Settings

A key they press, a command they type, a setting they change: the ways a user drives your addon by hand. Declaring one is unprotected, done in your file body, cleaned up when your addon reloads. Changing what the client has (remapping a key, writing one of its settings) is a [protected](permissions.md) write.

```lua
local keybindings = hafen.client():options():keybindings()
keybindings:on("toggle", function()
  if window:visible() then window:visible(false) else window:visible(true) end
end)
hafen.console():on("scout", function(args)
  if args[1] == "off" then stop() else start(args[1]) end
end)
```

---

## A hotkey

Your addon names the action; the user assigns the key in Options ▸ Game ▸ Keybindings, where every addon that declared one has a section. `on` takes no default key: the client gives one key to one action, so a default would lose every collision and leave a hotkey that never fires. Say what you would have claimed in your README: *Suggested key: Ctrl+H, assign it in Options > Game > Keybindings > My Addon.*

```lua
local toggle_binding = keybindings:binding():get("toggle")     -- your own names resolve; anything else is a registry id
hafen.log():write("bound to: " .. tostring(toggle_binding:key()))
```

| Rule | Detail |
|---|---|
| The assignment is the client's | It survives `:reload` and restarts. `keybindings:binding()` is every binding the client knows, yours and its own, so it also reads or remaps a built-in. |
| Three-valued | On the client's default, assigned by the user, or unbound by the user; `binding:key(nil)` puts it back on the default, which makes saving and restoring a key safe ([the reference](../api/client/keybindings.md)). |
| Reading is free, writing is not | `binding:key(key)` and `binding:key(nil)` need `client.settings`, since they change what the user set in Options and persist as the user's own edit; `binding:key()`, `:default()`, `:assigned()` and `:down()` need nothing. |
| `binding:down()` | Whether the key is held right now. `on` gives the moment it goes down and nothing for it coming up, so anything a key is held for (moving, push-to-talk, two keys at once) polls `down()` on a timer ([why key repeat cannot stand in](../api/client/keybindings.md#down-the-key-not-the-hotkey)). |
| After the client's own bindings | Through the same registry. Intercepting a mouse event before the widget under it is [`widget:on(key, fn)`](../api/ui/widget.md#subscribing) and `event:preventDefault()`; keyboard input is not a widget option, so a hotkey is the only door onto a key event. |

## A command

Press `:` to open the command line; `:scout`, `:scout off`, `:scout "two words"` reach the handler above. Your function gets the words after the name as a 1-based table, quotes grouping and `\` escaping; the name is not in it. [`hafen.console`](../api/console.md) is callable from your file body.

| Rule | Detail |
|---|---|
| Names | `lua`, `addons` and `reload` are the engine's; a name an existing client command owns is refused; another addon holding the name loses it to the newest subscription. Pick a name that reads like your addon. |
| The `Sub` | Both verbs hand back one: `subscription:key()` is the name, `subscription:off()` gives it up. A reload releases both. |

## A setting of your own

```lua
local options = hafen.client():options():addon()
local rows = options:number("rows"):range(1, 20):default(8):add()
rows:on("Changed", function(count) resize(count) end)
```

A setting on your addon's page in **Options ▸ AddOns**: you name the option and its type (a boolean, a number in a range, a choice, a text), and the client stores the value, checks every write and answers reads ([the reference](../api/client/addon.md)). [`options:panel(fn)`](../api/client/addon.md#the-page) registers the function the client calls with a column of your own each time the page opens; your addon is in the AddOns list while it holds a page. Read the value when you need it and subscribe to `Changed` for the moment it moves.

A value here is the client's: it survives `:reload`, a disable and a restart, is one per client, and needs no file. [`hafen.store`](../api/store/README.md) is for what the user did not choose (a cached list, a window position).

## A button of your own that runs a command

[`session:console():run(line)`](../api/console.md#run-a-line-protected) says a line at one character's console, as the user typing it would.

```lua
local alts_window = hafen.ui():window():title("Alts"):position(80, 120)
local logout_button = hafen.ui():button():text("Log the others out"):parent(alts_window):position(0, 0):size(160)
alts_window:pack()
logout_button:on("Pressed", function()
  for _, session in ipairs(hafen.session():list()) do
    if session ~= hafen.session():current() then session:console():run("lo") end
  end
end)
```

| Rule | Detail |
|---|---|
| Without the colon | The colon opens the command line and is never part of one. The line runs in the console of the character addressed, so `lo` logs out the alts and not the character on screen. |
| `console.run` | The widest key in the catalogue: every command the client dispatches, `:lua` included. |
| Your own commands too | The addon that registered `:scout` can drive it from a button; the handler `:on` took runs. A failing command is not your error: its message goes to that character's System log and notice, and the call returns. |

## Which to use

| The user does it | Reach for |
|---|---|
| Many times a session, mid-action | A hotkey. |
| Occasionally, or with an argument | A command. |
| Once, then leaves it alone | [A setting](#a-setting-of-your-own). |
| Continuously, while watching something | [A window](custom-ui.md). |

A hotkey to toggle the window, a command with subcommands for the rest, and anything set and forgotten on the options page is the shape the [maintainer's addons](../examples.md) have. Dormant is polite: an addon that draws and does nothing until its key or command is used costs a login nothing; wire the work behind the trigger rather than behind `SessionEnteredWorld`.

**Next:** [permissions](permissions.md) — the one tier that needs more than a declaration.
