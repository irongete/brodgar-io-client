# The bundled addons

Three addons ship with the client, in the same `addons/` folder yours goes into. None of them
illustrates a surface — a reference page states its own. Two are **tools you point at your own addon**:
one says what a widget on the screen is and how to name it, the other says where the frame went. The
third is a **surface of the client's own**, written in Lua like any other addon: the switcher over the
logins the client holds.

| Addon | Use it to |
|---|---|
| [`widgetstack`](../../addons/widgetstack/main.lua) | find out what a widget is, and how to name it |
| [`profiler`](../../addons/profiler/main.lua) | find out where the frame went |
| [`session-manager`](../../addons/session-manager/main.lua) | go between the characters you have logged in |

The two tools are **dormant** — installed and enabled, but drawing nothing and reading nothing until you
press their hotkey or type their command — so having them on costs you an untouched login. They are also
the two worked examples the API has left, and each is the whole of its own subject: the inspector is
[selectors](api/ui/selectors.md) end to end, the profiler is
[the profiling surface](api/client/profiling/README.md) end to end.

## widgetstack

The inspector. It shows the live stack of widgets under the cursor, outlines the hovered one, and opens a
browsable window for any of them: type, id, position, size, text, [role](api/ui/selectors.md#roles),
resource, parent, children, and
[everything else the widget answers](api/ui/selectors.md#what-the-inspector-says-a-widget-answers) — the
picture it shows, the tooltip it carries, what it holds — a line each, and none for a read with nothing to
say. Its selector panel answers the question the
[selector grammar](api/ui/selectors.md) is useless without — what is this widget, and how do I name
it? — by offering every selector it can build from what the widget is, each one resolved before it is shown
and ready to paste into `:lua`. It builds **chains**, anchoring on the captioned window a widget sits in,
because that is usually what names one widget rather than several; and it reports what the answer cost, in
tree walks.

`:widgetstack` toggles it, `:selector` logs the current line, and the `freeze` hotkey holds the stack still
while you move the mouse to read it.

## profiler

A six-tab window over [`hafen.client():profiling()`](api/client/profiling/README.md): the frame graph and the
thread phases, the render passes with their GL counters, per-widget cost, per-addon cost with each addon's
own scopes, the [pull-only counters](api/client/profiling/counters.md), and what profiling itself costs.
Pausing freezes the ring and turns the graph into a timeline you scrub frame by frame.

Its per-addon tab is where you find out what **your** addon costs, and its widget tab is where a window you
built shows up beside the client's own. Dormant until its `toggle` hotkey or `:profiler`, which is the shape
any profiling addon should have.

## session-manager

The switcher. One row per login the client holds — the character's name, or the account before that
character is in the world — with the row on screen marked, a button that hands the screen to that
character, and an `X` that logs it out. Its `next` hotkey goes to the next login and round, and `:sessions`
opens and closes the window.

It is the one bundled addon that **asks for a permission**: `session.close`, behind the `X`. So the client
disables it the first time it sees it and asks you to approve that line before it runs, the way it does for
any addon that can act on your behalf — see [permissions](guides/permissions.md).

Its window stands in the addon layer rather than on a character's HUD, which is why the screen moving does
not rebuild it, and where you drag it is saved for the **account** rather than for whichever character was
on screen when you moved it.

## See also

- [getting started](getting-started.md) — an empty folder to a working addon, step by step
- [the guides](guides/README.md) — one page per task, and the verbs each of these reads
- [the runtime](runtime.md) — where these folders live, and how the client loads them
