# Permissions

Reading the game needs no permission. **Acting** on it — walking, clicking an object, using an item, picking
a menu entry — is **protected**: one permission key per action, declared in your manifest and approved by
the user when they enable your addon. This guide is the catalogue of those keys, how to ask for them, and
what is on the near side of the gate.

## The catalogue

One key per protected action, and the table below is all of them. A key is named `<section>.<verb>` after
the section the verb lives on, because a verb lives with the thing it changes rather than in a section of
its own — so `pag:use()` is `menugrid.use` and `slot:use()` is `actionbar.use`. Where one action has two
doors — ending a login reads on the login and on the collection of them alike — both are behind the one
key. The third column is what the consent dialog tells the user, word for word.

| Key | Verb | What it lets an addon do |
|---|---|---|
| `player.move` | [`session:player():move`](../api/player.md#write-protected) | walk your character to a place |
| `player.hand.use` | [`session:player():hand():use`](../api/player.md#the-hand) | use whatever it is holding on things |
| `gob.click` | [`session:world():click`](../api/world.md#write-protected) | click objects in the world |
| `item.use` | [`item:use`](../api/ui/items.md#write-protected) | use items |
| `item.take` | [`item:take`](../api/ui/items.md#write-protected) | pick items up onto the cursor |
| `item.drop` | [`item:drop`](../api/ui/items.md#write-protected) | drop items |
| `item.transfer` | [`item:transfer`](../api/ui/items.md#write-protected) | move items between containers |
| `world.place` | [`session:world():place`](../api/world.md#write-protected) | place buildings and objects |
| `world.select` | [`session:world():select`](../api/world.md#write-protected) | select an area of the ground |
| `map.marker` | [`hafen.map():marker():add`](../api/map/markers.md#write-protected), `:remove`, `marker:color`, `:onMap` | add and delete pins on your map, and recolour them |
| `menugrid.use` | [`pag:use`](../api/menugrid.md#use-protected) | invoke entries of the action menu, on any of your characters |
| `flowermenu.select` | [`s:flowermenu():select`](../api/flowermenu.md#write-protected) | choose from the radial menu of any of your characters |
| `flowermenu.cancel` | [`s:flowermenu():cancel`](../api/flowermenu.md#write-protected) | dismiss the radial menu of any of your characters |
| `craft.make` | [`session:craft():make`](../api/craft.md#write-protected) | press the Craft button, on any of your characters |
| `actionbar.use` | [`slot:use`](../api/actionbar.md#write-protected) | press the action-bar buttons of any of your characters |
| `actionbar.res` | [`slot:res`](../api/actionbar.md#write-protected) | assign one of the game's own actions to any of your characters' action-bar buttons |
| `actionbar.clear` | [`slot:clear`](../api/actionbar.md#write-protected) | empty any of your characters' action-bar buttons |
| `kin.add` | [`session:kin():add`](../api/kin.md#write-protected) | add someone to any of your characters' kin lists |
| `kin.rename` | [`kin:rename`](../api/kin.md#write-protected) | rename someone on any of your characters' kin lists |
| `kin.group` | [`kin:group`](../api/kin.md#write-protected) | change someone's kin group, on any of your characters |
| `kin.end` | [`kin:endKin`](../api/kin.md#write-protected) | end kinship with someone, on any of your characters |
| `kin.forget` | [`kin:forget`](../api/kin.md#write-protected) | forget someone from any of your characters' kin lists |
| `chat.send` | [`channel:send`](../api/chat.md#write-protected) | say a line in the chat, as any of your characters |
| `speed.set` | [`session:speed():set`](../api/speed.md#write-protected) | change the movement speed of any of your characters |
| `session.close` | [`session:close`](../api/session.md#write-protected) and [`hafen.session():remove`](../api/session.md#write-protected) | log out any of your characters |
| `widget.send` | [`widget:send`](../api/ui/widget.md#send-a-message-protected), [`ev:resend`, `ev:send`](../api/event/streams.md#intercepting-an-outbound-action) | send any message the client itself could send |
| `widget.value` | [`widget:value`](../api/ui/edit.md#driving-one-protected) | flip the client's own controls — a box it ticks, a field it types into — which the server sees |
| `client.settings` | [every option write](../api/client/README.md) and [`binding:key(k)`](../api/client/keybindings.md) | change your client settings and hotkeys |
| `console.run` | [`s:console():run`](../api/console.md#run-a-line-protected) | run any of the client's console commands, on any of your characters, including ones that run code outside the addon sandbox |
| `http.get` | [`hafen.http():get`](../api/http.md) | fetch data from the servers it lists |
| `http.post` | [`hafen.http():post`](../api/http.md) | send data to the servers it lists |

That is the whole set. Nothing else in the API is protected, and **no key grants the tier as a whole**: an
addon that declared `gob.click` can click objects and none of the other things on that list.

**`console.run` is the widest key here**, and it is the one to think twice about granting. Every other key
names one action; this one names a *surface* — the client's own command line — so it covers every command
the client dispatches, yours and its own alike. `:lua` is one of them, and `:lua` evaluates against the
whole standard library outside the sandbox an addon runs in, so an addon holding this key can do what the
user's own console can. That is what its line says, in the user's words, because it is the only honest
thing a consent dialog can say about a key whose reach is another surface's vocabulary.

**Three of them do not reach the server at all**, and are keyed because a key gates what a verb *does*.
`map.marker` deletes a pin the player placed, which took real play to make and which no server can restore.
`client.settings` rewrites the configuration and every hotkey they have, and it reaches the **client's own**
bindings as readily as your addon's — `binding:key("Ctrl+I")` on `inv` takes the inventory key. `http.get`
and `http.post` reach outside the client entirely. All of them are plainly actions the player could have
performed: every one is a control they have in front of them.

## A key names the action, not the target

The client holds several logins at once, and a protected verb is addressed at one of them: `s:kin():add`
adds a kin to the character `s` names, drawn or not. **The key you declared covers every one of them.**
There is no second grant for acting on a character the player is not looking at, and none is coming: a
verb is protected because it starts something the player could have performed, and the player could have
tabbed to that character and performed it there. Every one of those characters is theirs — a distinction
between them is one the user never drew, and an addon they allowed to add kin that could not add kin on an
alt would be obeying a rule nobody wrote.

So read every line of the table above across the whole client. "Change your movement speed" is any
character's speed; "add someone to any of your characters' kin lists" says the same thing in the one place
the plural is easy to miss. What the user approves is a **capability**, and the character it is pointed at
is your addon's to choose.

A key is named after the action for the same reason, which is why `gob.click` is the one key whose name is
not the section its verb sits under: what it grants is clicking an object, and that is what it kept when the
verb moved onto the world of the character doing the clicking.

## Groups

A `<prefix>.*` entry stands for every key under that prefix, so one line asks for a family:

| Group | Covers |
|---|---|
| `item.*` | `item.use`, `item.take`, `item.drop`, `item.transfer` |
| `kin.*` | `kin.add`, `kin.rename`, `kin.group`, `kin.end`, `kin.forget` |
| `world.*` | `world.place`, `world.select` |
| `flowermenu.*` | `flowermenu.select`, `flowermenu.cancel` |
| `actionbar.*` | `actionbar.use`, `actionbar.res`, `actionbar.clear` |
| `player.*` | `player.move`, `player.hand.use` |
| `player.hand.*` | `player.hand.use` |
| `widget.*` | `widget.send`, `widget.value` |
| `http.*` | `http.get`, `http.post` |

Any key's prefix is a legal group, so `gob.*`, `menugrid.*`, `craft.*`, `speed.*`, `map.*`, `client.*`,
`console.*`, `chat.*` and `session.*` parse too — each a longer way of writing the single key it covers.

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

1. Your manifest declares a key, or a group, for every protected verb you call.

   ```json
   "permissions": ["player.move", "gob.click", "item.*"]
   ```

2. The user enables the addon. An addon that declares anything here is **disabled the first time the client
   sees it**, and enabling it in the **AddOns** manager raises a consent dialog listing, one plain line
   each,
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
| [`s:menugrid():add`](../api/menugrid.md#write-unprotected) | an entry of your own in a character's action menu |
| [`slot:hold(pag)`](../api/actionbar.md#hold-a-slot-unprotected) | which of your entries the client draws over a bar slot |
| [`cat:show(on)`](../api/map/icons.md#the-iconcat-object) | which icons your minimap draws |
| [`s:chat():selected(ch)`](../api/chat.md#write-unprotected) | which tab a character's chat has on screen |
| [`w:position`, `w:size`, `w:visible`, `w:draggable`, `w:resizable`, `w:remember`](../api/ui/native.md) | where the client's own windows sit and how big they are, whether the user can drag or size one, and whether that lasts |
| [`w:replace(view)`](../api/ui/replace.md) | which window a client toggle opens |
| [`hafen.ui():sheet()`](theming.md) | what the client looks like |
| [`hafen.vr`](../api/vr/README.md) | props only you can see |
| [`hafen.sound`](../api/sound.md) | what you hear |
| [option **reads**](../api/client/README.md) | nothing — every option write is `client.settings` |

Subscribing, drawing and reading are not writes at all. The line is **whether the user would have to undo
it by hand**: everything above is a display choice they can change back in a click, or something only your
addon can see. A pin deleted from the map, a rebound hotkey and a request to another host are not, which is
why those three are keyed.

An entry you put in the action menu sits on this side of it whole: naming it, drawing it, putting it on the
action bar and running your own function when the user clicks it never reach past your client. The bar slot is
**held** rather than assigned — the server's own content stays where it is and comes back untouched — which is
why the key `slot:res(name)` needs is not asked for here. [`pag:use()`](../api/menugrid.md#use-protected)
is the exception, and it keeps `menugrid.use` whichever entry it names — pressing a button on the player's
behalf is an act, and any addon can address any entry by name.

**Re-issuing an action needs `widget.send`.**
[`ev:resend()` and `ev:send(t)`](../api/event/streams.md#intercepting-an-outbound-action) put a message on the
same wire `widget:send` does, and `ev:send(t)` carries **arbitrary arguments** — replacing a click's
destination is replacing, and replacing an `itemact`'s target is a different action wearing the same name.
Neither is once-only either: a handler that loops turns one user click into as many server messages as it
likes. So both are behind the key whose line already reads *"send any message the client itself could send"*.

[`ev:preventDefault()`](../api/event/streams.md) stays open, and so does
[`ev:resend()` on one of the client's own controls](../api/ui/edit.md#running-the-action-yourself): cancelling
reaches nothing, and re-running a control's own method is the client's, not the wire.

## Writing an addon that acts

```lua
hafen.console():on("gotree", function()
  local s = hafen.session():current()           -- the character on screen
  local tree = s:world():gob():nearest("terobjs/tree")
  if tree then
    s:player():move(tree:position())            -- player.move
    s:world():click(tree, 3)                    -- gob.click, and open its radial menu
  end
end)
```

Three habits, in the order they bite:

- **Your manifest is the answer to "may I act?"** An addon that declared a key and is running was granted
  it, so there is nothing to test at run time and nothing to branch on. If you are writing a file that may
  ship either way, read your own `manifest.json` rather than provoking the error.
- **Act from `SessionEnteredWorld` onwards.** Every verb here needs a live map view or a live object and
  throws before there is one, so an action fired from a file body is an error rather than an early start.
  A verb addressed at a character that is not in the world yet says so and sends nothing.
- **Make the user ask.** Bind actions to a [hotkey, a command or a setting](hotkeys-and-commands.md)
  rather than to a timer. An addon that acts on its own the moment it loads is the one thing a dialog cannot
  really warn about, and the bundled write example is deliberately built the other way round.

**Declare the narrowest set that works.** The dialog is the user's whole view of what you do, so a group
asked for out of convenience reads as four capabilities you wanted rather than the one you use.

## What the permission does not buy

The server is still the authority. An addon can only send what a click could have sent, and it finds out
what happened the same way you do — by watching the world change. There is no verb that reaches past the
game rules, and a refused action is refused server-side with nothing to catch.

## Network: one key, and the hosts are its argument

Reaching outside the client is `http.get` and `http.post`, keys like any other — and the
[`network` block](../api/http.md#declaring-network-access) in your manifest is **what those keys take**:

```json
"permissions": ["http.get"],
"network": { "hosts": ["api.example.com"] }
```

The key says **whether** your addon may use the network, the hosts say **where**, and the user reads both as
one line when they enable you: *"fetch data from the servers it lists: api.example.com"*. Declaring hosts
without a key is a **load error** naming the key; asking for the key with no hosts is refused at the call.
A host you did not list is refused before any request leaves.

The AddOns panel still shows a `[net]` badge with the exact hosts in the row's tooltip.

**Next:** [theming](theming.md) — changing what the client looks like, which needs no permission at all.
