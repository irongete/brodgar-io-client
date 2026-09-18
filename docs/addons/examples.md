# Dev Tools

Three addons to point at your own while you write it, each in the addons repository, [brodgar-io-client-addons](https://github.com/irongete/brodgar-io-client-addons), one folder each. Drop one into the same `addons/` folder yours goes into. None illustrates a surface (a reference page states its own).

| Addon | Use it to |
|---|---|
| [`widgetstack`](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/widgetstack/main.lua) | Find out what a widget is, and how to name it. |
| [`eventstack`](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/eventstack/main.lua) | Watch what the client sends, receives and puts on screen. |
| [`resourcestack`](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/resourcestack/main.lua) | Browse the resources the client holds, and what each layer carries. |

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

---

## See Also

- [Getting started](getting-started.md) — an empty folder to a working addon, step by step.
- [The guides](guides/README.md) — one page per task, and the verbs each of these reads.
- [The manifest](manifest.md) — where these folders live, and what each declares.
- [The runtime](runtime.md) — how the client loads them.
