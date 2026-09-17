# The Maintainer's Addons

Every addon of the maintainer's is in a repository of its own, [brodgar-io-client-addons](https://github.com/irongete/brodgar-io-client-addons), one folder each. Drop one into the same `addons/` folder yours goes into. A client release ships the ones the maintainer names for it, installed beside the client. The rest you drop into `addons/` from that repository. None illustrates a surface (a reference page states its own). The ones to use while you write your own are the tools you point at your addon. Two are surfaces of the client's own, written in Lua like any other addon.

| Addon | Use it to |
|---|---|
| [`widgetstack`](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/widgetstack/main.lua) | Find out what a widget is, and how to name it. |
| [`eventstack`](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/eventstack/main.lua) | Watch what the client sends, receives and puts on screen. |
| [`resourcestack`](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/resourcestack/main.lua) | Browse the resources the client holds, and what each layer carries. |
| [`session-manager`](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/session-manager/main.lua) | Go between the characters you have logged in. |
| [`voice`](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/voice/main.lua) | Talk to the players near you, and hear them. |

Enabled, the tools draw and read nothing until you press their hotkey or type their command. The log records from the moment it loads (untick its `at login` box and it waits like the rest). Each covers one subject: the inspector [selectors](api/ui/selectors.md), the log [the streams and the bus](api/event/README.md).

## widgetstack

The inspector: the live stack of widgets under the cursor, the hovered one outlined, and a browsable window for any of them.

| Feature | Detail |
|---|---|
| The window | Type, id, position, size, text, [role](api/ui/selectors.md#roles), resource, parent, children, and [everything else the widget answers](api/ui/selectors.md#the-inspector): the picture, the tooltip, what it holds. A line each, and none for a read with nothing to say. |
| The selector panel | Every selector it can build from what the widget is, each resolved before it is shown and ready to paste into `:lua`. It builds chains anchored on the captioned window a widget sits in, and reports what the answer cost in tree walks. |
| Commands and keys | `:widgetstack` toggles it, `:selector` logs the current line, the `freeze` hotkey holds the stack still while you move the mouse. |

## eventstack

The live log: one line per thing the client did, newest first (number, clock, source, session, widget, name, the first of what it carried).

| Feature | Detail |
|---|---|
| Sources | A checkbox each. Every message the client sends. Every update it receives. Every [bus](api/event/bus/README.md) key but `Update`. Every widget appearing and disappearing. Every activation of one of the client's own controls. The last is `ui`. It walks the tree and holds one subscription per control. It finds the [capability key](api/ui/edit.md#taking-over-what-a-control-does) each answers by asking, since `widget:on(key, fn)` refuses a key its widget lacks. It watches and never cancels. |
| Filters | Start empty and fill themselves: dropdowns over source, session, widget and event name begin at `(all)` and gain a row as values arrive. A word box narrows on any fragment. The checkboxes decide what is recorded, the dropdowns what is drawn. |
| A row opened | The clock, source and event. The session by account and character. The widget. The subscription you would write to catch it again. It is spelled for the door the row came through, and guarded by the widget column where a stream handler needs it. What it carried: a message's arguments one to a line, or a bus payload as it stands. `pause` holds the view with every door open. `clear` empties the ring and the filters. Both are hotkeys too. |
| Recording and looking | `at login` opens the doors as the addon loads. `:eventstack` opens and closes the view onto what they caught. `widget` and `ui` start off either way (the first replays every open widget when ticked, the second walks the tree). |
| Output | No clipboard in the client: `log` writes the picked row or the view through [`hafen.log()`](api/log.md). It never cancels, rewrites, resends or sends. Its window is saved for the account. |

## resourcestack

The resource browser: a searchable list of every resource the client holds. The selected one's name, version, load state and layers are on the right. Each layer's readable fields take a line each, and an image layer is drawn in place. **Fetch** asks the client for a resource it has not loaded. `:resourcestack` toggles it. The window is resizable from the client's grip.

## session-manager

The switcher: one row per login the client holds (the character's name, or the account before it is in the world). The row on screen is marked. A button hands the screen to that character, and an `X` logs it out. The `next` hotkey goes to the next login and round. `:sessions` opens and closes the window. It asks for `session.close` behind the `X`. The client disables it the first time it sees it and asks you to approve that line ([permissions](guides/permissions.md)). Its window stands in the addon layer, so the screen moving does not rebuild it, and its place is saved for the account.

## voice

Proximity voice on the whole of [`hafen.voice`](api/voice/README.md). The client opens the microphone and mixes what arrives. Everything a player sees of it is this addon.

| Feature | Detail |
|---|---|
| One link | To `voice.brodgar.io` from the moment a character enters the world, for as long as the client runs, following whichever character is on screen. When the server ends it, it reconnects after a pause that doubles up to a minute. |
| Options ▸ AddOns ▸ Voice | The link on or off. The mode: push to talk, voice detection, an open microphone. The detection threshold, automatic gain, spatial panning, the volume and the bitrate. Each is a [client option](api/client/addon.md) bound to a control. `talk`, `mute` and `deafen` hotkeys start unbound. |
| Over heads | [`gob:overlay()`](api/overlay.md) draws a speaker over your character while your voice goes out, and over a player while theirs arrives. A struck one goes over a player you muted. All are vector-drawn, with no shipped image. |
| The window | `:voice` opens and closes it. It shows the link's state and round trip, a mute and a deafen. A row per player the server relates you to carries a mute box and a volume slider. A ring opened on another player takes a [petal](api/flowermenu.md#write-unprotected), `Mute voice` or `Unmute voice`. |
| Permission | `voice.connect` over `voice.brodgar.io`: the microphone opens for that server and no other, and [what the server is told](api/voice/README.md#what-the-server-is-told) is relative positions only. Its window is saved for the account. |

---

## See Also

- [Getting started](getting-started.md) — an empty folder to a working addon, step by step.
- [The guides](guides/README.md) — one page per task, and the verbs each of these reads.
- [The manifest](manifest.md) — where these folders live, and what each declares.
- [The runtime](runtime.md) — how the client loads them.
