# The bundled addons

The addons below ship with the client, in the same `addons/` folder yours goes into. None of them
illustrates a surface — a reference page states its own. The **tools** are the ones you point at your own
addon: what a widget on the screen is and how to name it, where the frame went, and what the client is
doing as it does it. Beside them stands a **surface of the client's own**, written in Lua like any other
addon: the switcher over the logins the client holds.

| Addon | Use it to |
|---|---|
| [`widgetstack`](../../addons/widgetstack/main.lua) | find out what a widget is, and how to name it |
| [`profiler`](../../addons/profiler/main.lua) | find out where the frame went |
| [`eventstack`](../../addons/eventstack/main.lua) | watch what the client sends, receives and puts on screen |
| [`session-manager`](../../addons/session-manager/main.lua) | go between the characters you have logged in |

The tools are **dormant** — installed and enabled, but drawing nothing and reading nothing until you press
their hotkey or type their command — so having them on costs you an untouched login. The log is the one
exception, and deliberately: it records from the moment it loads, because what is worth reading has
usually already happened by the time you think to look. Untick its `at login` box and it is dormant like
the rest. Each is the whole of
one subject as well: the inspector is [selectors](api/ui/selectors.md) end to end, the profiler is
[the profiling surface](api/client/profiling/README.md) end to end, and the log is
[the streams and the bus](api/event/README.md) at once.

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

## eventstack

The live log. One line per thing the client did, **newest first**, in a mono list: its number, the clock,
which source it came through, the session it happened on, the widget it was about, its name, and a glimpse
of what it carried. Every source is a checkbox of its own — every message the client **sends**, every
update it **receives**, every key on the [event bus](api/event/bus.md) but `Update`, and every widget
appearing and disappearing — so you can hold them all at once and shut off the one that is drowning the
rest.

Its filters **start empty and fill themselves**. A message name is the server's and a widget class is the
client's, so no list of either can be written in advance: a dropdown over the source, over the session,
over the widget and over the event name each begin at `(all)` and gain a row the first time a value
arrives on that axis, and a word box beside them narrows on any fragment of the line itself. Picking one
narrows the list to it, and the pick survives every name that arrives afterwards. The checkboxes decide
what is **recorded** and the dropdowns what is **drawn** out of it, so narrowing the view never costs you
what arrives while you are looking.

**Click a row and it opens underneath**: the clock, the source and the event, the session by account and
character, the widget it was about, and then what it carried — a message's protocol arguments one to a
line, or a bus event's payload as it stands at the moment you ask. `pause` holds the view still while you
read one, with every door still open behind it, and `clear` empties the ring and the filters with it.

**Recording and looking are two things**, which is what makes it useful at a login: `at login` opens the
doors as the addon loads, and `:eventstack` only opens and closes the view on to what they have caught, so
the tree building itself and the first updates that fill the HUD are already in the ring when you get
there. Untick it and the old shape is back — the doors open with the window and shut with it. The widget
source starts off either way, because ticking it while a character is up replays every widget that
character already has open.

Nothing in this window can be copied, because the client has no clipboard to offer: `log` writes the
picked row whole through [`hafen.log()`](api/log.md), a line per field, where the console shows it and the
terminal keeps it — the same door [widgetstack](#widgetstack)'s `:selector` uses, and for the same reason.
It reads the traffic and never touches it — nothing in it cancels, rewrites, resends or sends — and where
you drag its window is saved for the **account**, like the switcher's.

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
