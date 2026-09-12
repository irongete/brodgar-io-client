# Hotkeys, commands and settings

A key they press, a command they type, a setting they change: the ways a user drives your addon by hand.
Declaring one is unprotected, each is declared in your file body, and each is cleaned up when your addon
reloads. Changing what the **client** has — remapping a key, writing one of its settings — is a
[protected](permissions.md) write, and each place below says so where it comes up. The last section
goes the other way: a surface of yours doing what one console word already does.

## A hotkey

```lua
local keys = hafen.client():options():keybindings()

keys:on("toggle", function()
  if window:visible() then window:visible(false) else window:visible(true) end
end)
```

Your addon names the **action**; the user assigns the **key**, in Options ▸ Game ▸ Keybindings, where every
addon that declared one gets a section of its own. `on` takes no default key and cannot: the client gives
one key to exactly one action, so a default you picked would lose every collision and leave the user with a
hotkey that silently never fires.

So say what you would have claimed, in your addon's own README:

```text
Suggested key: Ctrl+H — assign it in Options > Game > Keybindings > My Addon.
```

The assignment is the client's, and it survives `:reload` and restarts. To read it, or to write one, go
through the binding itself:

```lua
local b = keys:binding():get("toggle")     -- your own names resolve; anything else is a registry id
hafen.log():write("bound to: " .. tostring(b:key()))
```

`keys:binding()` is every binding the client knows, yours and its own, so that is also how you read or
remap a built-in one. A binding is three-valued — on the client's default, assigned by the user, or
unbound by the user — and `b:key(nil)` is the one that puts it back on the default, which is what makes
saving and restoring a key safe. The [reference](../api/client/keybindings.md) has the rest of the object,
the collection verbs and the key-string grammar.

> **Reading a binding is free; writing one is not.** `b:key(k)` and `b:key(nil)` need the
> `client.settings` [permission](permissions.md), because they change what the user set in Options and
> the change persists exactly as the user's own edit does. `b:key()`, `b:default()`, `b:assigned()` and
> `b:down()` need nothing.

`b:down()` is the other half of a key, and it is a **read**: whether that key is held right now. `on` gives
you the moment it goes down and has no counterpart for it coming up, so anything a key is *held* for — moving
on it, a push-to-talk, two keys at once — polls `down()` on a timer instead of counting hotkey fires. The
[reference](../api/client/keybindings.md#down-the-key-not-the-hotkey) says why the desktop's key repeat
cannot stand in for it.

> A hotkey runs **after** the client's own bindings, through the same registry. To intercept a mouse event
> *before* the widget under it sees it, that is [`widget:on(key, fn)`](../api/ui/widget.md#subscribing)
> and `ev:preventDefault()`, which is a different job with a different cost — keyboard input is not a
> widget option, so a hotkey is still the only door onto a key **event**.

## A command

```lua
hafen.console():on("scout", function(args)
  if args[1] == "off" then stop() else start(args[1]) end
end)
-- in the console:  :scout   ·   :scout off   ·   :scout "two words"
```

Press `:` to open the client's command line. Your function gets the words after the command name as a
1-based table, with quotes grouping and `\` escaping; the name itself is not in it.
[`hafen.console`](../api/console.md) can be called from your file body — there is nothing to wait for.

Three things to know about names: `lua`, `addons` and `reload` are the engine's and cannot be taken; a name
an existing client command owns is refused with an error; and if another *addon* holds the name, the newest
subscription wins it, which makes a clash a race rather than a failure. Pick a name that reads like your
addon.

Both verbs hand back a `Sub`, the one shape every `:on` in this API gives you: `sub:key()` is the name you
took and `sub:off()` gives it up. You rarely need either — a reload releases both for you — but a hotkey
your addon stops offering, or a command it hands over, ends that way.

## A setting of your own

```lua
local opts = hafen.client():options():addon()

local rows = opts:number("rows"):range(1, 20):default(8):add()

rows:on("Changed", function(n) resize(n) end)
```

The third way, and the one the user reaches for when they are not in the middle of anything: a setting on
your addon's own page in **Options ▸ AddOns**. You name the option and its type — a boolean, a number in a
range, a choice out of a list, a text — and the client stores the value, checks every write and answers
reads; the [reference](../api/client/addon.md) has the builder for each and what it takes. The page itself
is yours: [`opts:panel(fn)`](../api/client/addon.md#the-page) registers the function the client calls with
a column of your own each time the page is opened, and the controls you build into it are what the user
sees. Your addon is in the AddOns list exactly while it holds a page.

Read the value whenever you need it and subscribe to `Changed` for the moment it moves.

A value here is the **client's**, not your addon's: it survives `:reload`, a disable and a restart, it is
one per client rather than one per character, and you write no file for it. Reach for
[`hafen.store`](../api/store.md) instead for what the user did not choose — a cached list, a window
position, anything your addon decided for itself.

## A button of your own that runs a command

The client has a command for a great many things, and one of your own surfaces — a button, a hotkey, a
menu entry — can say one rather than reimplementing it.
[`s:console():run(line)`](../api/console.md#run-a-line-protected) says a line at one character's console,
exactly as the user typing it would:

```lua
local win = hafen.ui():window():title("Alts"):position(80, 120)
local go = hafen.ui():button():text("Log the others out"):parent(win):position(0, 0):size(160)
win:pack()

go:on("Pressed", function()
  for _, s in ipairs(hafen.session():list()) do
    if s ~= hafen.session():current() then s:console():run("lo") end
  end
end)
```

The line goes **without the opening colon** — the colon opens the command line and is never part of one —
and it runs in the console of the character you addressed, which is why `lo` above logs out the alts and
not the character on screen. It needs the `console.run` [permission](permissions.md), the widest key in
the catalogue: it covers every command the client dispatches, `:lua` included.

It reaches your own commands too, so the addon that registered `:scout` can drive it from a button without
factoring its body out — the handler `:on` took is the one that runs. A command that **fails** is not your
error: its message goes to that character's System log and its on-screen notice, which is where the console
puts one, and the call returns.

## Which to use

| The user does it | Reach for |
|---|---|
| many times a session, mid-action | a hotkey |
| occasionally, or with an argument | a command |
| once, and then leaves it alone | [a setting](#a-setting-of-your-own) |
| continuously, while watching something | [a window](custom-ui.md), and none of these |

A good default for anything with a UI is a hotkey to toggle the window and a command with subcommands
for the rest, so nothing needs a key that is only used once, with anything the user sets and forgets on
the options page instead. That is the shape the [maintainer's addons](../examples.md) have.

**Dormant is polite.** An addon that draws nothing and does nothing until its key or command is used costs
a login nothing, and the user finds out what it does when they ask. Wire the work behind the trigger rather
than behind `SessionEnteredWorld`.

**Next:** [permissions](permissions.md) — the one tier that needs more than a
declaration.
