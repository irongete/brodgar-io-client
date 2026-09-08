# Debugging

The loop is short: edit the file, type `:reload`, read the console. Nothing is compiled, nothing is
installed, and you never log out. This guide is what to do when the console does not say what you expected.

## Print, and read the two places it lands

[`hafen.log`](../api/log.md) writes to the in-game console **and** to the terminal the client was started
from, prefixed with your addon's id — which is what makes several addons logging at once readable.

```lua
local settings = hafen.session():current():store():get("settings")
hafen.log():write("state: " .. hafen.json():encode(settings))
```

The in-game line is clipped at 500 characters, so print a big table and read it off the terminal;
[`hafen.json():encode`](../api/json.md) is how you see inside one, since logging a table prints only that it
is a table.

Errors your addon raises arrive on the same channel with the same prefix, wherever they came from — a
handler, a timer, a draw callback. They are isolated: the one callback dies, the rest of your addon and the
rest of the client carry on — with one exception, a failure from underneath the language that
[stops the whole addon](../runtime.md#when-a-failure-is-fatal) rather than the client. That is why a broken handler looks like *nothing happening* rather than like a
crash, and why the console is the first place to look.

One line has the same prefix but is the client's rather than yours — a subsystem that failed to give
something back while your addon was being [torn down](../runtime.md#what-a-reload-keeps-and-what-it-drops):

```text
[myaddon] teardown: sounds failed: the mixer this clip went to is gone
```

It names the one thing that was not given back — `widgets`, `sounds`, `key binds`, `assets` — and everything
else was given back anyway, which is what makes the line worth reading: whatever it names may still be on
screen or still in the air after the `:reload`, and nothing else is. Every step but `Disable` is the client's
own work, so one of those failing is a client bug rather than yours.

## Try the call before you write it

`:lua <expression>` evaluates against the live API and prints the result as JSON. It is the fastest way to
find out what a verb actually answers, and it runs as your own console rather than as addon code, so
nothing is sandboxed and every protected verb works there.

```text
:lua hafen.session():current():world():gob():count("terobjs/tree")
:lua hafen.session():current():ui():match("window[title=Inventory]"):info()
:lua for _, m in ipairs(hafen.session():current():meter():list()) do hafen.log():write(tostring(m:res())) end
```

Anything that reads is safe to try. Anything that writes is a real change to your client — that is the
point of the console, and the reason to keep `:reload` in reach.

## Read back what you registered

Two questions your own code answers for you: did that subscription happen, and am I holding two of them.
A list is answered for the addon that asks for it, so ask from a command of your own — the addon asking
in `:lua` is the console, not you.

```lua
hafen.console():on("mystate", function()
  hafen.log():write(hafen.event():count() .. " subscriptions")
  for _, sub in ipairs(hafen.event():list()) do
    hafen.log():write("  " .. sub:key())
  end
  hafen.log():write(hafen.timer():count() .. " timers")
end)
```

[`hafen.event()`](../api/event/README.md#read-what-you-are-listening-to) covers the bus and the two
message streams; [`hafen.console()`](../api/console.md#read-what-you-registered) is the collection of your
commands; [`hafen.timer()`](../api/timer.md) is the collection of your timers. A count that climbs while
the client runs is a subscription made from somewhere that is not your file body or `Load` — a handler
that subscribes on each fire is the usual one, and it goes on firing once per copy.

## Name a widget you are pointing at

Selectors are guessed wrong more often than anything else in the API, so do not guess. Enable the bundled
**`widgetstack`** addon and hover: it reports the widget's [role](../api/ui/selectors.md#roles), its class,
its own caption or displayed words, its `[res=]` and the captioned window it sits in, then offers **every
selector it can build from those**, most specific first, each one resolved before it is shown and ready to
paste into `:lua`. The lines it reaches for first are **chains** — the captioned window, then the widget —
because a flat step usually matches several widgets and a chain matches the one you are pointing at. Its
`freeze` hotkey holds the stack still while you move the mouse over to read it.

Two answers that surprise people, both from [the inspector's own page](../api/ui/selectors.md): hovering a
window's frame gives you the frame, not the window, because the chrome is a widget of its own; and most
widgets have **no** role at all, which is the classifier being honest rather than a gap.

## Find a message name by watching for it

A message name is the server's rather than the client's, so there is no catalogue to look one up in. Watch
[the whole stream](../api/event/streams.md#the-whole-stream) with `*`, make the thing happen on screen, and
read the name off the console:

```lua
hafen.event():message():on("*", function(ev)                 -- updates arriving
  hafen.log():write(ev:widget():type() .. " <- " .. ev:msg() .. " (" .. #ev:args() .. " args)")
end)

hafen.event():action():on("*", function(ev)                  -- messages going out
  hafen.log():write(ev:widget():type() .. " -> " .. ev:msg() .. " (" .. #ev:args() .. " args)")
end)
```

Then subscribe to the one name you saw and take the wildcard out again: it runs your handler on everything
the client sends and receives, which is what makes it a thing you watch with rather than ship. Never call
`ev:preventDefault()` in one — inbound, that swallows every update the server sends, and the client stops
hearing from it.

The bundled **`eventstack`** addon is that pair of blocks with a window around them, and the
[event bus](../api/event/bus/README.md) and the widget tree beside them. `:eventstack` puts up a live log —
one line per message out, update in, event on the bus, or widget coming and going — with filters over the
source, the session, the widget and the event name that fill themselves as each value arrives for the first
time, a word box over the whole line, and a click on any row for the arguments that message actually carried.
It records from the moment it loads rather than from the moment you open it, so the login you wanted to watch
is already in it. Reach for it when you do not yet know which of those the thing you are watching for comes
through, and for the console once you do: a name you can subscribe to by itself is cheaper than a window
watching everything.

## When the addon does not load

`:addons` lists every folder the client found with its status, and the **AddOns** manager — on the game
menu, which `Ctrl+O` opens — says the same thing with the error message attached. Work down this list:

| The status says | Look at |
|---|---|
| nothing — the addon is not listed | the folder is not under `addons/`, or has no `manifest.json` |
| `error: …` | the message: bad JSON, a missing `id` or `files`, or an id that is not the folder name |
| `disabled` | the checkbox — and remember a write addon is disabled the first time it is seen |
| `not loaded` | an enable that no `:reload` has applied yet |
| `auto-disabled (…)` | the [CPU budget](../runtime.md#budgets-and-the-watchdog) — your addon was burning the frame — or a [fatal failure](../runtime.md#when-a-failure-is-fatal) the client contained |

A change to the enabled set is always applied on the **next** reload, never mid-session, so "I ticked it
and nothing happened" is one `:reload` away from being fixed.

## When the code runs but does nothing

Four causes cover almost all of it:

- **Read too early.** Your file body runs before the world exists, and much of the character sheet streams
  in for seconds *after* `SessionEnteredWorld`. If a read answers `nil`, ask again from an
  [event or a timer](events-and-timers.md).
- **The hotkey is unbound.** An addon hotkey starts with no key at all, by design. Look in
  Options ▸ Game ▸ Keybindings for your addon's section.
- **The selector matches nothing — or too much.** That is
  [what the two lookups promise](../api/ui/selectors.md#one-or-all-of-them): `nil` when nothing matches, and
  an **error** where two or more do, rather than one picked for you. Try it in `:lua`, name the one you mean
  with a [chain](../api/ui/selectors.md#the-grammar), and check the widget with the inspector.
- **The handler threw.** Look at the console: an isolated error is a logged line, not a stopped client.

## When it is slow

Arm the client's profiler — the Options ▸ Game ▸ Client checkbox, or
[`hafen.client():options():client():profiling(true)`](../api/client/README.md#client) — and read
[`hafen.client():profiling()`](../api/client/profiling/README.md), which reports the frame, the render
passes, per-widget cost and **what each addon's Lua cost**, most expensive first.

The usual culprits are a scan in `Update` (do it on a timer instead), a string that changes every frame
in a draw callback ([text is cached by its content](../api/ui/drawing.md#text-is-cached-across-frames)),
and a selector lookup per frame instead of one held Widget. Long before it costs you a frame, the engine
will [auto-disable](../runtime.md#budgets-and-the-watchdog) an addon that sustains the overrun — the
warning on its panel row is the last word, not the first.

**Next:** [the API reference](../api/README.md) for the verb you are reaching for, or
[the bundled addons](../examples.md) for the tools that answer these questions for you.
