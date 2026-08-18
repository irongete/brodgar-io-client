# Hotkeys and commands

Two ways for the user to invoke your addon by hand: a key they press, and a command they type. Both are
unprotected, both are registered by name, and both are cleaned up when your addon reloads.

## A hotkey

```lua
local keys = hafen.client():options():keybindings()

keys:register("toggle", function()
  if window:visible() then window:visible(false) else window:visible(true) end
end)
```

Your addon names the **action**; the user assigns the **key**, in Options ▸ Keybindings, where every addon
that registered one gets a section of its own. `register` takes no default key and cannot: the client gives
one key to exactly one action, so a default you picked would lose every collision and leave the user with a
hotkey that silently never fires.

So say what you would have claimed, in your addon's own README:

```text
Suggested key: Ctrl+H — assign it in Options > Keybindings > My Addon.
```

The assignment is the client's, and it survives `:reload` and restarts. Names are scoped to your addon, so
`register("toggle", …)` and `key("toggle")` mean yours, while a name that is not one of yours reaches the
client's own registry — which is how you read or remap a built-in binding. The
[reference](../api/client/keybindings.md) has the verbs, the key-string grammar and what `list()` reports.

> A hotkey runs **after** the client's own bindings, through the same registry. To intercept a mouse event
> *before* the widget under it sees it, that is [`widget:on(key, fn)`](../api/ui/widget.md#subscribing)
> and `ev:preventDefault()`, which is a different job with a different cost — keyboard input is not a
> widget option, so a hotkey is still the only door onto a key.

## A command

```lua
hafen.slash():register("scout", function(args)
  if args[1] == "off" then stop() else start(args[1]) end
end)
-- in the console:  :scout   ·   :scout off   ·   :scout "two words"
```

Press `:` to open the client's command line. Your function gets the words after the command name as a
1-based table, with quotes grouping and `\` escaping; the name itself is not in it.
[`hafen.slash`](../api/slash.md) can be called from your file body — there is nothing to wait for.

Three things to know about names: `lua`, `addons` and `reload` are the engine's and cannot be taken; a name
an existing client command owns is refused with an error; and if another *addon* holds the name, the newest
registration wins it, which makes a clash a race rather than a failure. Pick a name that reads like your
addon.

## Which to use

| The user does it | Reach for |
|---|---|
| many times a session, mid-action | a hotkey |
| occasionally, or with an argument | a command |
| continuously, while watching something | [a window](custom-ui.md), and neither of these |

A good default for anything with a UI is both: a hotkey to toggle the window, and a command with
subcommands for the rest, so nothing needs a key that is only used once. That is the shape both
[bundled addons](../examples.md) have.

**Dormant is polite.** An addon that draws nothing and does nothing until its key or command is used costs
a login nothing, and the user finds out what it does when they ask. Wire the work behind the trigger rather
than behind `SessionEnteredWorld`.

**Next:** [permissions](permissions.md) — the one tier that needs more than a
declaration.
