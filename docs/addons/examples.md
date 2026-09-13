# The maintainer's addons

Every addon of the maintainer's is in a repository of its own,
[brodgar-io-client-addons](https://github.com/irongete/brodgar-io-client-addons): one folder each, to drop
into the same `addons/` folder yours goes into. **Which of them a client release ships is
`etc/release-addons` in the client's repository** — one addon per line — and nothing else says so. None of
them illustrates a surface — a reference page states its own. The ones worth knowing while you write your
own are below: the **tools** you point at your addon — what a widget on the screen is and how to name it,
and what the client is doing as it does it — and, beside them, two **surfaces of the client's own**, written
in Lua like any other addon: the switcher over the logins the client holds, and the voice the client draws
no UI for.

| Addon | Use it to |
|---|---|
| [`widgetstack`](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/widgetstack/main.lua) | find out what a widget is, and how to name it |
| [`eventstack`](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/eventstack/main.lua) | watch what the client sends, receives and puts on screen |
| [`session-manager`](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/session-manager/main.lua) | go between the characters you have logged in |
| [`voice`](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/voice/main.lua) | talk to the players near you, and hear them |

The tools are **dormant** — enabled, they draw nothing and read nothing until you press their hotkey or
type their command — so having them on costs you an untouched login. The log is the one exception, and
deliberately: it records from the moment it loads, because what is worth reading has usually already
happened by the time you think to look. Untick its `at login` box and it is dormant like the rest. Each is
the whole of one subject as well: the inspector is [selectors](api/ui/selectors.md) end to end, and the
log is [the streams and the bus](api/event/README.md) at once.

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

## eventstack

The live log. One line per thing the client did, **newest first**, in a mono list: its number, the clock,
which source it came through, the session it happened on, the widget it was about, its name, and a glimpse
of what it carried. Every source is a checkbox of its own — every message the client **sends**, every
update it **receives**, every key on the [event bus](api/event/bus/README.md) but `Update`, every widget
appearing and disappearing, and every activation of one of the client's **own controls** — so you can hold
them all at once and shut off the one that is drowning the rest.

That last one is the door a wildcard cannot reach. A control's
[capability key](api/ui/edit.md#taking-over-what-a-control-does) fires only for somebody who subscribed to
that widget, so `ui` walks the tree and holds one subscription per control — finding the key each answers
by asking the client rather than by guessing, since `widget:on(key, fn)` refuses a key its widget has not
got and the subscription that takes is therefore the answer. It watches and never cancels.

Its filters **start empty and fill themselves**. A message name is the server's and a widget class is the
client's, so no list of either can be written in advance: a dropdown over the source, over the session,
over the widget and over the event name each begin at `(all)` and gain a row the first time a value
arrives on that axis, and a word box beside them narrows on any fragment of the line itself. Picking one
narrows the list to it, and the pick survives every name that arrives afterwards. The checkboxes decide
what is **recorded** and the dropdowns what is **drawn** out of it, so narrowing the view never costs you
what arrives while you are looking.

**Click a row and it opens underneath**: the clock, the source and the event, the session by account and
character, the widget it was about, **the subscription you would write to catch it again**, and then what
it carried — a message's protocol arguments one to a line, or a bus event's payload as it stands at the
moment you ask. The subscription line is spelled for the door the row came through, guarded by the widget
column where a stream handler needs that guard, and it is the answer to the question the window exists to
ask: you made the thing happen, and this is the line that catches it next time. `pause` holds the view
still while you read one, with every door still open behind it, and `clear` empties the ring and the
filters with it. Both `toggle` and `pause` are hotkeys as well, unbound until you assign them.

**Recording and looking are two things**, which is what makes it useful at a login: `at login` opens the
doors as the addon loads, and `:eventstack` only opens and closes the view on to what they have caught, so
the tree building itself and the first updates that fill the HUD are already in the ring when you get
there. Untick it and the old shape is back — the doors open with the window and shut with it. Two sources
start off either way: `widget`, because ticking it while a character is up replays every widget that
character already has open, and `ui`, because it walks the tree and subscribes to every control in it.

Nothing in this window can be copied, because the client has no clipboard to offer: `log` writes through
[`hafen.log()`](api/log.md) — the picked row with its whole detail, or the view itself when no row is
picked — where the console shows it and the terminal keeps it, the same door
[widgetstack](#widgetstack)'s `:selector` uses and for the same reason. It reads the traffic and never
touches it — nothing in it cancels, rewrites, resends or sends — and where you drag its window is saved
for the **account**, like the switcher's.

## session-manager

The switcher. One row per login the client holds — the character's name, or the account before that
character is in the world — with the row on screen marked, a button that hands the screen to that
character, and an `X` that logs it out. Its `next` hotkey goes to the next login and round, and `:sessions`
opens and closes the window.

It **asks for a permission**: `session.close`, behind the `X`. So the client disables it the first time it
sees it and asks you to approve that line before it runs, the way it does for any addon that can act on
your behalf — see [permissions](guides/permissions.md).

Its window stands in the addon layer rather than on a character's HUD, which is why the screen moving does
not rebuild it, and where you drag it is saved for the **account** rather than for whichever character was
on screen when you moved it.

## voice

Proximity voice, on the whole of [`hafen.voice`](api/voice/README.md): the client opens the microphone and
mixes what arrives, and everything a player sees of it is this addon. It holds **one link** to
`voice.brodgar.io` from the moment a character enters the world, for as long as the client runs — following
whichever character is on screen, and back after a pause that doubles up to a minute when the server ends
it. Its page in **Options ▸ AddOns ▸ Voice** is the settings a player has: the link on or off, the mode
— push to talk, voice detection, an open microphone — the detection threshold, automatic gain, spatial
panning, the volume and the bitrate, each a [client option](api/client/addon.md) bound to a control. Its
`talk`, `mute` and `deafen` hotkeys start unbound, as every addon's do.

**Who is talking is drawn over heads** with [`gob:overlay()`](api/overlay.md): a speaker over your own
character while your voice goes out, one over a player while theirs arrives, and a struck one over a
player you muted — vector-drawn, so the addon ships no image. `:voice` opens and closes its window: the
link's state and round trip, a mute and a deafen, and a row per player the server relates you to, with a
mute box and a volume slider each. A ring opened on another player takes a
[petal of its own](api/flowermenu.md#write-unprotected), `Mute voice` or `Unmute voice`.

It asks for **one permission**, `voice.connect` over `voice.brodgar.io` — the microphone opens for that
server and no other, and [what the server is told](api/voice/README.md#what-the-server-is-told) is relative
positions only. Its window stands in the addon layer and is remembered for the **account**, like the
switcher's.

## See also

- [getting started](getting-started.md) — an empty folder to a working addon, step by step
- [the guides](guides/README.md) — one page per task, and the verbs each of these reads
- [the manifest](manifest.md) — where these folders live, and what each one declares
- [the runtime](runtime.md) — how the client loads them
