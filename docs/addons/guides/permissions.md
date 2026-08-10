# Permissions

Reading the game needs no permission. **Acting** on it — walking, clicking an object, using an item,
picking a menu entry — is the one protected tier in the API, and this guide is about getting through the
gate and about what is on the near side of it.

## What is protected, and what is not

Protected is exactly one thing: starting an action the player could have performed. There is no
section that collects those verbs — **each one lives with the thing it changes**, so you meet the
permission on the page you looked the verb up on, under a heading reading `Write (protected)`.
That is the whole set:

| Verb | What it sends |
|---|---|
| [`hafen.player():move(p)`](../api/player.md#write-protected) | walk to a place |
| [`hafen.player():hand():use(target, mods)`](../api/player.md#the-hand) | apply what is on your cursor to an item, a place or an object |
| [`gob:click(button, mods)`](../api/gob.md#write-protected) | click an object, left or right |
| [`item:use`, `:take`, `:drop`, `:transfer`](../api/ui/items.md#write-protected) | act on an item in a container |
| [`hafen.world():place`, `:select`](../api/world.md#write-protected) | place what you are holding; area-select tiles |
| [`pag:use()`](../api/menugrid.md#use-protected) | fire an action from the action menu |
| [`hafen.flowermenu():select`, `:cancel`](../api/flowermenu.md#write-protected) | pick a petal of the open radial menu |
| [`hafen.speed():current(n)`](../api/speed.md#write-protected) | change the movement speed |
| [`craft:make`](../api/craft.md#write-protected) | press Craft in the open recipe window |
| [`slot:use`, `slot:res(name)`](../api/actionbar.md#write-protected) | fire a hotbar slot, or assign one |
| [the roster verbs](../api/kin.md#write-protected) | add, rename, re-group and forget a kin |
| [`widget:send(msg, ...)`](../api/ui/widget.md#send-a-message-protected) | the escape hatch: any message, from a bound widget |

Everything else writes only to your own client, and none of it is protected. That is worth stating, because
several of them look like writes:

| Unprotected write | What it changes |
|---|---|
| [`hafen.map():marker():add`](../api/map/markers.md#write-unprotected) | your own map database |
| [`cat:show(on)`](../api/map/icons.md#the-iconcat-object) | which icons your minimap draws |
| [`w:position`, `w:size`, `w:visible`](../api/ui/native.md) | where the client's own windows sit |
| [`w:replace(view)`](../api/ui/replace.md) | which window a client toggle opens |
| [`hafen.ui():sheet()`](theming.md) | what the client looks like |
| [`hafen.vr`](../api/vr/README.md) | props only you can see |
| [`hafen.sound`](../api/sound.md) | what you hear |
| [client options](../api/client/README.md) | the settings you could have edited by hand |

Subscribing, drawing and reading are not writes at all. The line is the server: if nothing leaves the
client, there is no permission to ask for.

One pair reaches the server without the permission, which is why the line above is *starting* an action
rather than sending one. [`ev:resend()` and `ev:send(t)`](../api/event.md#intercepting-an-outbound-action)
re-issue a message the client was already about to send, in place of it: you choose the arguments, not
whether it happens — the player's own click did that.

## Declaring it

Two steps, and the second one is not yours:

1. Your manifest declares a key per verb you call. A `<prefix>.*` entry stands for every key under that
   prefix, so the line below asks for walking, clicking, and all four item verbs.

   ```json
   "permissions": ["player.move", "gob.click", "item.*"]
   ```

2. The user enables the addon. An addon that declares a permission key is **disabled the first time the
   client sees it**, and enabling it in Options ▸ AddOns raises a consent dialog naming what it can do.

So a write addon that is running is one the user knowingly turned on — there is no global switch to flip,
and no way for an addon to grant itself a key by being installed. Its row in the panel carries a
`[protected]` badge from the moment it is discovered, and a bulk **Enable all** skips it.

A protected verb called by an addon that did not declare its key raises an error naming the verb and the
key it needs. It is not a silent no-op, and it is not a crash.

## Writing an addon that acts

```lua
hafen.slash():register("gotree", function()
  local tree = hafen.world():gob():nearest("terobjs/tree")
  if tree then
    hafen.player():move(tree:position())
    tree:click(3)                              -- and open its radial menu
  end
end)
```

Three habits, in the order they bite:

- **Your manifest is the answer to "may I act?"** An addon that declared the permission and is running was
  granted it, so there is nothing to test at run time and nothing to branch on. If you are writing a file
  that may ship either way, read your own `manifest.json` rather than provoking the error.
- **Act from `EnterWorld` onwards.** Every verb here needs a live map view or a live object and throws
  before there is one, so an action fired from a file body is an error rather than an early start.
- **Make the user ask.** Bind actions to a [hotkey or a command](hotkeys-and-commands.md) rather than to a
  timer. An addon that acts on its own the moment it loads is the one thing a permission dialog cannot
  really warn about, and the bundled write example is deliberately built the other way round.

## What the permission does not buy

The server is still the authority. An addon can only send what a click could have sent, and it finds out
what happened the same way you do — by watching the world change. There is no verb that reaches past the
game rules, and a refused action is refused server-side with nothing to catch.

## Network is a separate declaration

Reaching outside the client is none of these keys and does not use `permissions` at all: a
[`network` block](../api/http.md#declaring-network-access) in the manifest lists the hosts your addon may
talk to, and that list **is** the allowlist — anything else is refused at the call. A network addon loads
normally, and the AddOns panel shows a `[net]` badge with the exact hosts in the row's tooltip, so the user
sees who you talk to before enabling you.

**Next:** [theming](theming.md) — changing what the client looks like, which needs no permission at all.
