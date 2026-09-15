# Permissions

Reading game data (terrain, visible objects, stats, chat messages) requires **no permissions**. 

However, **acting on the game**—moving the character, clicking entities, manipulating inventory items, or accessing external networks—requires declaring **permissions** in `manifest.json`. When a user enables your addon, the client presents a consent dialog listing these requested permissions.

---

## Permission Keys Reference

| Permission Key | Unlocks Method / Action | Description |
|---|---|---|
| `player.move` | `session:player():move(target_position)` | Walk or pathfind character to a world position. |
| `player.hand.use` | `session:player():hand():use(...)` | Use the item currently held on the cursor. |
| `gob.click` | `session:world():click(game_object, ...)` | Click or interact with objects in the game world. |
| `world.place` | `session:world():place(...)` | Place buildings or deploy construction sites. |
| `world.select` | `session:world():select(...)` | Drag area selections on the terrain. |
| `item.use` | `item_handle:use(...)` | Consume or activate an inventory item. |
| `item.take` | `item_handle:take()` | Pick an item up onto the cursor. |
| `item.drop` | `item_handle:drop(...)` | Drop an item onto the ground. |
| `item.transfer` | `item_handle:transfer(...)` | Move items between containers and inventories. |
| `menugrid.use` | `page_handle:use(action_name)` | Activate actions from the main game menu. |
| `flowermenu.select` | `session:flowermenu():select(option)` | Select options from radial flower menus. |
| `flowermenu.cancel` | `session:flowermenu():cancel()` | Dismiss the active radial menu. |
| `craft.make` | `session:craft():make(...)` | Trigger crafting recipes. |
| `actionbar.use` | `slot_handle:use()` | Trigger action-bar hotbar slots. |
| `actionbar.res` | `slot_handle:res(...)` | Assign actions to action-bar hotbar slots. |
| `actionbar.clear` | `slot_handle:clear()` | Clear action-bar hotbar slots. |
| `chat.send` | `channel_handle:send(text)` | Post messages into in-game chat channels. |
| `speed.set` | `session:speed():set(speed_level)` | Switch movement speed (crawl, walk, run, sprint). |
| `session.close` | `session:close()` | Log out a character session. |
| `map.marker` | `hafen.map():marker():add(...)` / `:remove()` | Create, edit, or delete pins on the world map. |
| `kin.add` | `session:kin():add(...)` | Add a player to your character's kin list. |
| `kin.rename` | `kin_handle:rename(new_name)` | Rename a kin roster entry. |
| `kin.group` | `kin_handle:group(group_number)` | Change kin group assignment on your character. |
| `kin.end` | `kin_handle:endKin()` | End kinship relation with a character. |
| `kin.forget` | `kin_handle:forget()` | Remove/forget someone from your kin list. |
| `widget.send` | `widget_handle:send(msg, ...)` | Dispatch raw client action messages directly to the server. |
| `widget.value` | `widget_handle:value(new_value)` | Modify interactive client controls (checkboxes, text inputs) that report to the server. |
| `ui.resend` | `event_handle:resend()` | Re-trigger a native button press event to send what that press sends. |
| `ui.focus` | `session:chat():selected()` | Move keyboard focus into a chat entry line. |
| `virtual.click` | `hafen.virtual():click(...)` | Click virtual controls rendered in the 3D world. |
| `client.settings` | `hafen.client():options():...` | Modify native client configurations and hotkeys. |
| `console.run` | `session:console():run(command_string)` | Execute console commands programmatically (including unrestricted `:lua`). |
| `http.get` | `hafen.http():get(url)` | Make outbound HTTP GET requests (requires `network.hosts`). |
| `http.post` | `hafen.http():post(url)` | Make outbound HTTP POST requests (requires `network.hosts`). |
| `websocket.connect` | `hafen.websocket():connect(url)` | Open WebSocket connections (requires `network.hosts`). |
| `voice.connect` | `hafen.voice():connect(...)` | Connect to proximity voice audio servers. |

---

## Wildcard Permission Groups

Instead of listing multiple related permissions individually, you can declare wildcard groups in `manifest.json`:

| Group | Equivalent Individual Keys |
|---|---|
| `item.*` | `item.use`, `item.take`, `item.drop`, `item.transfer` |
| `kin.*` | `kin.add`, `kin.rename`, `kin.group`, `kin.end`, `kin.forget` |
| `widget.*` | `widget.send`, `widget.value` |
| `ui.*` | `ui.resend`, `ui.focus` |
| `world.*` | `world.place`, `world.select` |
| `actionbar.*` | `actionbar.use`, `actionbar.res`, `actionbar.clear` |
| `flowermenu.*` | `flowermenu.select`, `flowermenu.cancel` |
| `http.*` | `http.get`, `http.post` |

---

## Declaring Permissions in `manifest.json`

```json
{
  "id": "auto_harvester",
  "api_version": "1.0",
  "files": ["main.lua"],
  "permissions": [
    "player.move",
    "gob.click",
    "item.*"
  ]
}
```
