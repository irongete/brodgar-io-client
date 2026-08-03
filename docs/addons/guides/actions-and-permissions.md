# Actions and permissions

Reading the game needs no permission. **Acting** on it — walking, clicking an object, using an item,
picking a menu entry — is the one gated tier in the API, and this guide is about getting through the gate
and about what is on the near side of it.

## What is gated, and what is not

Gated is exactly one thing: sending the server an action the player could have performed.
[`hafen.act`](../api/act.md) is that surface, together with the write verbs that live in their own
namespaces — [`hafen.speed.set`](../api/speed.md), [`hafen.craft.make`](../api/craft.md),
[`slot:use` and `slot:set`](../api/actionbar.md), and the roster verbs on [`hafen.kin`](../api/kin.md).

Everything else writes only to your own client, and none of it is gated. That is worth stating, because
several of them look like writes:

| Ungated write | What it changes |
|---|---|
| [`hafen.map.markers.add`](../api/map.md#write-ungated) | your own map database |
| [`cat:show(on)`](../api/map.md#the-iconcat-object) | which icons your minimap draws |
| [`w:pos`, `w:size`, `w:hide`](../api/ui/native.md) | where the client's own windows sit |
| [`w:replace(view)`](../api/ui/replace.md) | which window a client toggle opens |
| [`hafen.ui.skin{…}`](theming.md) | what the client looks like |
| [`hafen.ghost`, `hafen.render`](../api/ghost.md) | props only you can see |
| [`hafen.sound`](../api/sound.md) | what you hear |
| [client options](../api/client/README.md) | the settings you could have edited by hand |

Subscribing, drawing and reading are not writes at all. The line is the server: if nothing leaves the
client, there is no permission to ask for.

## Declaring it

Two steps, and the second one is not yours:

1. Your manifest declares it.

   ```json
   "permissions": ["actions"]
   ```

2. The user enables the addon. An addon that declares `actions` is **disabled the first time the client
   sees it**, and enabling it in Options ▸ AddOns raises a consent dialog naming what it can do.

So a write addon that is running is one the user knowingly turned on — there is no global switch to flip,
and no way for an addon to grant itself the tier by being installed. Its row in the panel carries an
`[actions]` badge from the moment it is discovered, and a bulk **Enable all** skips it.

A gated verb called by an addon that did not declare the permission raises an error naming the verb. It is
not a silent no-op, and it is not a crash.

## Writing an addon that acts

```lua
hafen.slash.register("gotree", function()
  if not hafen.act.enabled() then
    hafen.log("this addon needs the actions permission")
    return
  end
  local tree = hafen.world.nearest("terobjs/tree")
  if tree then
    local p = tree:pos()
    hafen.act.moveTo(p.x, p.y)
  end
end)
```

Three habits, in the order they bite:

- **Branch on [`hafen.act.enabled()`](../api/act.md), not on a `pcall`.** It answers before you are in the
  world and never throws, so it is the one call you can make at load time to find out where you stand.
- **Act from `OnEnterWorld` onwards.** Every verb except `enabled` needs a live map view and throws before
  there is one, so an action fired from a file body is an error rather than an early start.
- **Make the user ask.** Bind actions to a [hotkey or a command](hotkeys-and-commands.md) rather than to a
  timer. An addon that acts on its own the moment it loads is the one thing a permission dialog cannot
  really warn about, and the bundled write example is deliberately built the other way round.

## What the permission does not buy

The server is still the authority. An addon can only send what a click could have sent, and it finds out
what happened the same way you do — by watching the world change. There is no verb that reaches past the
game rules, and a refused action is refused server-side with nothing to catch.

## Network is a separate declaration

Reaching outside the client is not part of `actions` and does not use `permissions` at all: a
[`network` block](../api/http.md#declaring-network-access) in the manifest lists the hosts your addon may
talk to, and that list **is** the allowlist — anything else is refused at the call. A network addon loads
normally, and the AddOns panel shows a `[net]` badge with the exact hosts in the row's tooltip, so the user
sees who you talk to before enabling you.

**Next:** [theming](theming.md) — changing what the client looks like, which needs no permission at all.
