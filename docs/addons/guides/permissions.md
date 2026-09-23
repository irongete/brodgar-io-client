# Permissions

Reading the game needs no permission. Acting on it (walking, clicking an object, using an item, picking a menu entry) is protected. There is one key per action, declared in your manifest and approved by the user when they enable your addon. This guide is the catalogue of keys, how to ask for them, and what needs no key.

```json
{ "permissions": ["player.move", "gob.click", "item.*"] }
```

---

## The catalogue

One key per protected action, named `<section>.<verb>` after the section the verb lives on (`pagina:use()` is `menugrid.use`, `slot:use()` is `actionbar.use`). Where one action has two doors both are behind one key. The third column is the consent dialog's line, word for word.

| Key | Verb | Lets an addon |
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
| `menugrid.use` | [`pagina:use`](../api/menugrid.md#use-protected) | invoke entries of the action menu, on any of your characters |
| `flowermenu.select` | [`session:flowermenu():select`](../api/flowermenu.md#write-protected) | choose from the radial menu of any of your characters |
| `flowermenu.cancel` | [`session:flowermenu():cancel`](../api/flowermenu.md#write-protected) | dismiss the radial menu of any of your characters |
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
| `session.close` | [`session:close`](../api/session.md#write-protected), [`hafen.session():remove`](../api/session.md#write-protected) | log out any of your characters |
| `session.add` | [`hafen.session():add(user)`](../api/session.md#remembered-accounts-protected) | log in any of the accounts you told the login screen to remember |
| `session.forget` | [`hafen.session():forget(user)`](../api/session.md#remembered-accounts-protected) | forget any of the accounts you told the login screen to remember, so it asks for their password again |
| `widget.send` | [`widget:send`](../api/ui/widget.md#send-a-message-protected), [`event:resend`, `event:send`](../api/event/streams.md#intercepting-an-outbound-action) | send any message the client itself could send |
| `widget.value` | [`widget:value`](../api/ui/edit.md#driving-one-protected) | flip the client's own controls — a box it ticks, a field it types into — which the server sees |
| `ui.resend` | [`event:resend` on a control](../api/ui/edit.md#running-the-action-yourself) | re-run a button you pressed, so the client sends what that press sends |
| `ui.focus` | [`session:chat():selected(channel)`](../api/chat.md#write-unprotected) | move the keyboard into a chat entry line, so what you type next goes there |
| `virtual.click` | [`hafen.virtual():click`](../api/virtual/README.md#clicking-what-stands-in-the-world-protected) | click the controls it has standing in the world, which act as if you had clicked them |
| `client.settings` | [every option write](../api/client/README.md), [`binding:key(key)`](../api/client/keybindings.md) | change your client settings and hotkeys |
| `console.run` | [`session:console():run`](../api/console.md#run-a-line-protected) | run any of the client's console commands, on any of your characters, including ones that run code outside the addon sandbox |
| `http.get` | [`request:send`](../api/http.md#request) on a GET | fetch data from the servers it lists |
| `http.post` | [`request:send`](../api/http.md#request) on a POST | send data to the servers it lists |
| `websocket.connect` | [`connection:connect`](../api/websocket.md#connection) | keep a live connection to the servers it lists |
| `voice.connect` | [`voice:connect`](../api/voice/link.md#the-link-object) | use your microphone to talk on the voice servers it lists |

| Rule | Detail |
|---|---|
| The whole set | Nothing else is protected, and no key grants the tier whole: an addon that declared `gob.click` can click objects and nothing else on the list. |
| `console.run` is the widest | It names a surface, the command line, so it covers every command the client dispatches. `:lua` is one, and it evaluates outside the sandbox. An addon holding this key can do what the user's console can. |
| Keyed without reaching the game server | `map.marker` deletes a pin no server restores. `client.settings` rewrites every hotkey and reaches the client's own bindings (`binding:key("Ctrl+I")` on `inv`). `http.*`, `websocket.connect` and `voice.connect` reach outside the client, the last opening the microphone. Each is a control the player has in front of them. |

## A key names the action, not the target

The client holds several logins, and a protected verb is addressed at one (`session:kin():add` adds a kin to the character `session` names, drawn or not). The key you declared covers every one of them. A verb is protected because it starts something the player could have performed, and the player could have tabbed to that character. Read every line of the table across the whole client. What the user approves is a capability. The character it is pointed at is your addon's to choose. `gob.click` is the one key not named for the section its verb sits under. It names the action, clicking an object. The verb, [`session:world():click`](../api/world.md#write-protected), sits on the world of the character clicking. A library's exported function runs under the library's own keys, whoever called it; a callback you hand a library runs under yours ([addons](../api/client/addons.md#what-a-call-does)).

## Groups

A `<prefix>.*` entry stands for every key under that prefix.

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
| `ui.*` | `ui.resend`, `ui.focus` |
| `http.*` | `http.get`, `http.post` |
| `session.*` | `session.add`, `session.close`, `session.forget` |

| Rule | Detail |
|---|---|
| Any prefix is a group | `gob.*`, `menugrid.*`, `craft.*`, `speed.*`, `map.*`, `client.*`, `console.*`, `chat.*`, `virtual.*`, `websocket.*` and `voice.*` parse, each a longer spelling of its single key. |
| Whole dot segments | A group never reaches a key that merely starts with the same letters, and does reach a nested one. `player.hand.use` is the only nested key, covered by `player.*` and alone by `player.hand.*`. |
| The unit the user reads | The dialog prints one line per entry as written (`item.*` is one line naming its verbs), and the panel row counts entries the same way. |
| No `"*"` | An entry that is neither a key nor a group (a typo, an invented key, a bare asterisk) is a load error. The addon does not run. The panel row names the entry and lists the vocabulary. |
| Tolerated | `""` or a string of spaces is dropped. Entries are read trimmed (`" item.take "` is that key). A key declared twice is one grant, printed once. |

## Declaring it

| Step | Detail |
|---|---|
| 1. The manifest declares a key or a group for every protected verb you call | `"permissions": ["player.move", "gob.click", "item.*"]`. |
| 2. The user enables the addon | An addon that declares anything here is disabled the first time the client sees it. Enabling it in the AddOns manager raises a consent dialog listing exactly the entries you wrote, one line each. The tooltip of its name lists the entries, and **Enable all** skips it. |

| Rule | Detail |
|---|---|
| Asking for more re-asks | The approved keys and the hosts shown beside them are remembered per addon. A version adding either is disabled and prompts again with the addition marked new. A widened group counts as added, and so does a host no approved entry covers (`b.example.com` re-asks, narrowing `*.example.com` to `a.example.com` does not). Dropping a key or a host never re-prompts. |
| An undeclared key | A protected verb called by an addon that did not declare its key raises naming the verb, the key and the manifest line to paste. |
| The gate asks the record, not the manifest | What the user approved for your addon: a key added to `manifest.json` after consent is refused at the call until the addon is enabled again. Both halves must hold: a key no longer declared is no longer granted. |
| The gate asks who is running | A protected verb reads the manifest of the addon whose code is on the stack. Handing another addon an `event`, a `Widget` or a `Session` hands it nothing. |
| The record has a fixed size | A declaration large enough to overflow it (a `network` block of hundreds of hosts) cannot be recorded. Approving it is refused naming your addon and the limit, and the addon stays disabled until the declaration is smaller. |

## What is not protected

Everything else writes to your own client and needs no key.

| Unprotected write | Changes |
|---|---|
| [`session:menugrid():add`](../api/menugrid.md#write-unprotected) | An entry of your own in a character's action menu. |
| [`slot:hold(pagina)`](../api/actionbar.md#hold-a-slot-unprotected) | Which of your entries the client draws over a bar slot. The server's content stays and comes back untouched. |
| [`category:show(flag)`](../api/map/icons.md#the-iconcat-object) | Which icons your minimap draws. |
| [`session:chat():selected(channel)`](../api/chat.md#write-unprotected) | Which tab a character's chat has on screen. The keyboard moves with it only for `ui.focus`. |
| [`widget:position`, `:size`, `:visible`, `:draggable`, `:resizable`, `:remember`](../api/ui/native.md) | Where the client's own windows sit and how big they are, whether the user can drag or size one, whether that lasts. |
| [`widget:replace(view)`](../api/ui/replace.md) | Which window a client toggle opens. |
| [`hafen.ui():sheet()`](theming.md) | What the client looks like. |
| [`hafen.virtual`](../api/virtual/README.md) | Props only you can see. Every verb but `:click`, which is `virtual.click`. |
| [`hafen.sound`](../api/sound.md) | What you hear. |
| [Option reads](../api/client/README.md) | Nothing. Every client option write is `client.settings`. |
| [`option:value(value)`, `:add()`, `options:panel(fn)`](../api/client/addon.md) | Your own options and page. |

| Rule | Detail |
|---|---|
| The line | Whether the user would have to undo it by hand. Everything above is a display choice changed back in a click, or something only your addon sees. A deleted pin, a rebound hotkey, a request to another host and a connection kept to one are not. |
| A menu entry of yours | Naming it, drawing it, putting it on the bar and running your function on a click never reach past your client. [`pagina:use()`](../api/menugrid.md#use-protected) keeps `menugrid.use` whichever entry it names. |
| Re-issuing an action needs `widget.send` | [`event:resend()` and `event:send(table)`](../api/event/streams.md#intercepting-an-outbound-action) put a message on the wire `widget:send` uses, with arbitrary arguments, as often as a handler loops. [`event:preventDefault()`](../api/event/streams.md) stays open: cancelling reaches nothing. |
| Re-running a control's action needs `ui.resend` | [`event:resend()` on a client control](../api/ui/edit.md#running-the-action-yourself) runs its method, its own `wdgmsg`. Narrower than `widget.send`: it re-runs the press the user made, once per event. A second `event:resend()` on the same event raises. |
| Two keys, one act | `kin.end` and `kin.forget` are one message on the wire, resolved by the entry's state. An addon granted only `kin.end` can `endKin()` an un-kinned entry and forget it. One granted only `kin.forget` can end a kinship. Granting either grants the pair. |

## Writing an addon that acts

```lua
hafen.console():on("gotree", function()
  local session = hafen.session():current()           -- the character on screen
  local tree = session:world():gob():nearest("terobjs/tree")
  if tree then
    session:player():move(tree:position())            -- player.move
    session:world():click(tree, 3)                    -- gob.click, and open its radial menu
  end
end)
```

| Habit | Detail |
|---|---|
| Your manifest answers "may I act" | A declared, running addon was granted the key: nothing to test at run time. A file shipping either way reads its own `manifest.json` rather than provoking the error. |
| Act from `SessionEnteredWorld` onwards | Every verb here needs a live map view or object and throws before one exists. A verb at a character not yet in the world says so and sends nothing. |
| Make the user ask | Bind actions to a [hotkey, a command or a setting](hotkeys-and-commands.md), not a timer. An addon acting on its own at load is what a dialog cannot warn about. |
| Declare the narrowest set | The dialog is the user's whole view of what you do. A group asked for out of convenience reads as every capability in it. |

## What the permission does not buy

| Rule | Detail |
|---|---|
| The server is the authority | An addon sends what a click could have sent and finds out what happened by watching the world. A refused action is refused server-side with nothing to catch. |
| Some of it outlives the addon | `slot:res(name)` and `slot:clear()` write the character's bar on the server: disabling, `:reload` and logout put nothing back. The user undoes it in the game. [`slot:hold(pagina)`](../api/actionbar.md#hold-a-slot-unprotected) is unprotected beside them because the server's content comes back untouched. |

## Network: one key, and the hosts are its argument

`http.get`, `http.post`, `websocket.connect` and `voice.connect` are keys like any other. The [`network` block](../api/http.md#declaring-network-access) is what they take. The key says whether, the hosts say where.

```json
{ "permissions": ["http.get", "websocket.connect"],
  "network": { "hosts": ["api.example.com"] } }
```

| Rule | Detail |
|---|---|
| One line per key over the same hosts | *"fetch data from the servers it lists: api.example.com"*. *"keep a live connection to the servers it lists: api.example.com"*. For a [voice link](../api/voice/README.md#declaring-network-access), *"use your microphone to talk on the voice servers it lists"*. |
| Load error, refusal | Hosts without a key is a load error naming the keys. A key with no hosts is refused at the call. An origin the user did not approve is refused before any request leaves, connection opens or microphone is taken. |
| An entry | One scheme on one port. A [`wss://` address is the `https` server](../api/websocket.md#declaring-network-access) the block names. A wildcard covers one domain's sub-domains, never a top-level domain ([what an entry means](../api/http.md#declaring-network-access)). |
| The panel | The exact hosts in the tooltip of the addon's name. |

**Next:** [theming](theming.md) — changing what the client looks like, which needs no permission.
