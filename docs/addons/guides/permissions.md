# Permissions

Reading the game needs no permission. **Acting** on it — walking, clicking an object, using an item, picking
a menu entry — is **protected**: one permission key per verb, declared in your manifest and approved by the
user when they enable your addon. This guide is the catalogue of those keys, how to ask for them, and what
is on the near side of the gate.

## The catalogue

One key per protected verb, and the table below is all of them. A key is named `<section>.<verb>` after the
section the verb lives on, because a verb lives with the thing it changes rather than in a section of its
own — so `pag:use()` is `menugrid.use` and `slot:use()` is `actionbar.use`. The third column is what the
consent dialog tells the user, word for word.

| Key | Verb | What it lets an addon do |
|---|---|---|
| `player.move` | [`hafen.player():move`](../api/player.md#write-protected) | walk your character to a place |
| `player.hand.use` | [`hafen.player():hand():use`](../api/player.md#the-hand) | use whatever it is holding on things |
| `gob.click` | [`gob:click`](../api/gob.md#write-protected) | click objects in the world |
| `item.use` | [`item:use`](../api/ui/items.md#write-protected) | use items |
| `item.take` | [`item:take`](../api/ui/items.md#write-protected) | pick items up onto the cursor |
| `item.drop` | [`item:drop`](../api/ui/items.md#write-protected) | drop items |
| `item.transfer` | [`item:transfer`](../api/ui/items.md#write-protected) | move items between containers |
| `world.place` | [`hafen.world():place`](../api/world.md#write-protected) | place buildings and objects |
| `world.select` | [`hafen.world():select`](../api/world.md#write-protected) | select an area of the ground |
| `menugrid.use` | [`pag:use`](../api/menugrid.md#use-protected) | invoke entries of the action menu |
| `flowermenu.select` | [`hafen.flowermenu():select`](../api/flowermenu.md#write-protected) | choose from the radial menu |
| `flowermenu.cancel` | [`hafen.flowermenu():cancel`](../api/flowermenu.md#write-protected) | dismiss the radial menu |
| `craft.make` | [`hafen.craft():current():make`](../api/craft.md#write-protected) | press the Craft button |
| `actionbar.use` | [`slot:use`](../api/actionbar.md#write-protected) | press your action-bar buttons |
| `actionbar.res` | [`slot:res`](../api/actionbar.md#write-protected) | change what your action-bar buttons hold |
| `kin.add` | [`hafen.kin():add`](../api/kin.md#write-protected) | add someone to your kin list |
| `kin.rename` | [`kin:rename`](../api/kin.md#write-protected) | rename someone on your kin list |
| `kin.group` | [`kin:group`](../api/kin.md#write-protected) | change someone's kin group |
| `kin.endKin` | [`kin:endKin`](../api/kin.md#write-protected) | end kinship with someone |
| `kin.forget` | [`kin:forget`](../api/kin.md#write-protected) | forget someone from your kin list |
| `speed.set` | [`hafen.speed():set`](../api/speed.md#write-protected) | change your movement speed |
| `widget.send` | [`widget:send`](../api/ui/widget.md#send-a-message-protected) | the escape hatch: any message the client itself could send |
| `widget.value` | [`widget:value`](../api/ui/edit.md#driving-one-protected) | flip the client's own controls — a box it ticks, a field it types into — which the server sees |

That is the whole set. Nothing else in the API is protected, and **no key grants the tier as a whole**: an
addon that declared `gob.click` can click objects and none of the other things on that list.

## Groups

A `<prefix>.*` entry stands for every key under that prefix, so one line asks for a family:

| Group | Covers |
|---|---|
| `item.*` | `item.use`, `item.take`, `item.drop`, `item.transfer` |
| `kin.*` | `kin.add`, `kin.rename`, `kin.group`, `kin.endKin`, `kin.forget` |
| `world.*` | `world.place`, `world.select` |
| `flowermenu.*` | `flowermenu.select`, `flowermenu.cancel` |
| `actionbar.*` | `actionbar.use`, `actionbar.res` |
| `player.*` | `player.move`, `player.hand.use` |
| `player.hand.*` | `player.hand.use` |
| `widget.*` | `widget.send`, `widget.value` |

Any key's prefix is a legal group, so `gob.*`, `menugrid.*`, `craft.*` and `speed.*` parse too — each a
longer way of writing the single key it covers.

The prefix is matched on **whole dot segments**, so a group can never reach a key that merely starts with the
same letters — and it does reach a nested one. `player.hand.use` is the only nested key: `player.*` covers it
along with `player.move`, and `player.hand.*` covers it alone.

A group is also the unit the user reads. The consent dialog prints one line per entry **as you wrote it**, so
`item.*` is one line naming all four item verbs rather than four lines — and the panel row counts entries the
same way.

> **There is no `"*"`.** An entry that is neither a key nor a group — a typo, an invented key, a bare
> asterisk — is a **load error**: your addon does not run and the AddOns panel row says which entry was
> wrong and lists the whole vocabulary. A permission you misspelled fails before your first call rather than
> at it.

## Declaring it

Two steps, and the second one is not yours:

1. Your manifest declares a key, or a group, per verb you call.

   ```json
   "permissions": ["player.move", "gob.click", "item.*"]
   ```

2. The user enables the addon. An addon that declares anything here is **disabled the first time the client
   sees it**, and enabling it in Options ▸ AddOns raises a consent dialog listing, one plain line each,
   exactly the entries you wrote.

So a write addon that is running is one the user knowingly turned on — there is no global switch to flip,
and no way for an addon to grant itself a key by being installed. Its row in the panel carries a
`[protected: N]` badge counting those entries from the moment it is discovered, with the entries themselves
in the row tooltip, and a bulk **Enable all** skips it.

> **Asking for more re-asks.** What the user approved is remembered per addon, so a version of your addon
> that adds a key is disabled again and prompts again, with the added entries marked as new in the dialog.
> A widened group counts as added. Dropping a key never re-prompts, and neither does an addon that declares
> nothing.

A protected verb called by an addon that did not declare its key raises an error naming the verb, the key it
needs and the manifest line to paste. It is not a silent no-op, and it is not a crash.

## What is not protected

Everything else writes only to your own client, and none of it needs a key. That is worth stating, because
several of them look like writes:

| Unprotected write | What it changes |
|---|---|
| [`hafen.map():marker():add`](../api/map/markers.md#write-unprotected) | your own map database |
| [`hafen.menugrid():add`](../api/menugrid.md#write-unprotected) | an entry of your own in the action menu |
| [`slot:pagina(pag)`](../api/actionbar.md#hold-a-slot-unprotected) | which of your entries the client draws over a bar slot |
| [`cat:show(on)`](../api/map/icons.md#the-iconcat-object) | which icons your minimap draws |
| [`w:position`, `w:size`, `w:visible`, `w:draggable`, `w:resizable`, `w:remember`](../api/ui/native.md) | where the client's own windows sit and how big they are, whether the user can drag or size one, and whether that lasts |
| [`w:replace(view)`](../api/ui/replace.md) | which window a client toggle opens |
| [`hafen.ui():sheet()`](theming.md) | what the client looks like |
| [`hafen.vr`](../api/vr/README.md) | props only you can see |
| [`hafen.sound`](../api/sound.md) | what you hear |
| [client options](../api/client/README.md) | the settings you could have edited by hand |

Subscribing, drawing and reading are not writes at all. The line is the server: if nothing leaves the
client, there is no key to ask for.

An entry you put in the action menu sits on this side of it whole: naming it, drawing it, putting it on the
action bar and running your own function when the user clicks it never reach past your client. The bar slot is
**held** rather than assigned — the server's own content stays where it is and comes back untouched — which is
why the key `slot:res(name)` needs is not asked for here. [`pag:use()`](../api/menugrid.md#use-protected)
is the exception, and it keeps `menugrid.use` whichever entry it names — pressing a button on the player's
behalf is an act, and any addon can address any entry by name.

Re-issuing reaches the server without a key, which is why the line above is *starting* an action rather
than sending one.
[`ev:resend()` and `ev:send(t)`](../api/event/streams.md#intercepting-an-outbound-action) re-issue a message
the client was already about to send, in place of it, and
[`ev:resend()` on one of the client's own controls](../api/ui/edit.md#running-the-action-yourself) re-runs
the action the user's own gesture just triggered. Either way you choose what happens to a gesture, not
whether there was one — that is exactly what `widget.value` above does not have, which is why it is keyed
and these are not.

## Writing an addon that acts

```lua
hafen.slash():register("gotree", function()
  local tree = hafen.world():gob():nearest("terobjs/tree")
  if tree then
    hafen.player():move(tree:position())       -- player.move
    tree:click(3)                              -- gob.click, and open its radial menu
  end
end)
```

Three habits, in the order they bite:

- **Your manifest is the answer to "may I act?"** An addon that declared a key and is running was granted
  it, so there is nothing to test at run time and nothing to branch on. If you are writing a file that may
  ship either way, read your own `manifest.json` rather than provoking the error.
- **Act from `SessionEnteredWorld` onwards.** Every verb here needs a live map view or a live object and
  throws before there is one, so an action fired from a file body is an error rather than an early start.
- **Make the user ask.** Bind actions to a [hotkey or a command](hotkeys-and-commands.md) rather than to a
  timer. An addon that acts on its own the moment it loads is the one thing a permission dialog cannot
  really warn about, and the bundled write example is deliberately built the other way round.

**Declare the narrowest set that works.** The dialog is the user's whole view of what you do, so a group
asked for out of convenience reads as four capabilities you wanted rather than the one you use.

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
