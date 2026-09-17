# Debugging

The loop is edit the file, type `:reload`, read the console: nothing compiled, nothing installed, no logout. This guide is what to do when the console does not say what you expected.

```lua
local settings = hafen.session():current():store():var("settings")
hafen.log():write("state: " .. hafen.json():encode(settings))
```

---

## Print, and read the two places it lands

| Rule | Detail |
|---|---|
| Two places | [`hafen.log`](../api/log.md) writes to the in-game console and to the terminal, prefixed with your addon's id. The in-game line is clipped at 500 characters: print a big table and read it off the terminal, through [`hafen.json():encode`](../api/json.md), since logging a table prints only that it is one. |
| Errors land there too | With the same prefix, from a handler, a timer or a draw callback. They are isolated: the one callback dies and the rest carries on, except a failure from underneath the language that [stops the whole addon](../runtime.md#when-a-failure-is-fatal). A broken handler looks like nothing happening, so the console is the first place to look. |
| A teardown line | `[myaddon] teardown: sounds failed: the mixer this clip went to is gone` is the client's, naming the one thing (`widgets`, `sounds`, `key binds`, `assets`) not given back while your addon was [torn down](../runtime.md#what-a-reload-keeps-and-what-it-drops); everything else was. Every step but `Disable` is the client's own work, so it names a client bug. |

## Try the call before you write it

`:lua <expression>` evaluates against the live API and prints the result as JSON, as your own console rather than addon code: nothing sandboxed, every protected verb works.

```text
:lua hafen.session():current():world():gob():count("terobjs/tree")
:lua hafen.session():current():ui():match("window[title=Inventory]"):info()
:lua for _, meter in ipairs(hafen.session():current():meter():list()) do print(meter:res()) end
```

| Rule | Detail |
|---|---|
| `print` in a loop, not `hafen.log():write` | In `:lua` a `print` goes to the console you type in; `hafen.log():write` posts a timed notice over the world per line, rate-limited to eight a second. |
| Reads are safe, writes are real | A write is a change to your client; keep `:reload` in reach. |

## Read back what you registered

A list is answered for the addon that asks, so ask from a command of your own (in `:lua` the asker is the console).

```lua
hafen.console():on("mystate", function()
  hafen.log():write(hafen.event():count() .. " subscriptions")
  for _, subscription in ipairs(hafen.event():list()) do
    hafen.log():write("  " .. subscription:key())
  end
  hafen.log():write(hafen.timer():count() .. " timers")
end)
```

[`hafen.event()`](../api/event/README.md#read-what-you-are-listening-to) covers the bus and the message streams, [`hafen.console()`](../api/console.md#read-what-you-registered) your commands, [`hafen.timer()`](../api/timer.md) your timers. A count that climbs while the client runs is a subscription made outside your file body or `Load`, usually a handler that subscribes on each fire.

## Name a widget you are pointing at

Enable the `widgetstack` addon and hover: it reports the widget's [role](../api/ui/selectors.md#roles), class, caption or displayed words, `[res=]` and the captioned window it sits in, then offers every selector it can build from those, most specific first, each resolved before shown and ready to paste into `:lua`. It reaches first for chains (the captioned window, then the widget), since a flat step usually matches several. Its `freeze` hotkey holds the stack still while you move the mouse. Two answers from [the inspector's page](../api/ui/selectors.md): hovering a window's frame gives the frame, not the window, since the chrome is a widget of its own; and most widgets have no role.

## Find a message name by watching for it

A message name is the server's, with no catalogue: watch [the whole stream](../api/event/streams.md#the-whole-stream) with `*`, make the thing happen, read the name off the console.

```lua
hafen.event():message():on("*", function(event)                 -- updates arriving
  hafen.log():write(event:widget():type() .. " <- " .. event:msg() .. " (" .. #event:args() .. " args)")
end)
hafen.event():action():on("*", function(event)                  -- messages going out
  hafen.log():write(event:widget():type() .. " -> " .. event:msg() .. " (" .. #event:args() .. " args)")
end)
```

| Rule | Detail |
|---|---|
| Then take the wildcard out | Subscribe to the one name you saw: a wildcard runs your handler on everything the client sends and receives. Never call `event:preventDefault()` in one; inbound, it swallows every update and the client stops hearing from the server. |
| `eventstack` | That pair of blocks with a window around them, plus the [event bus](../api/event/bus/README.md) and the widget tree: `:eventstack` puts up a live log with self-filling filters over source, session, widget and event name, a word box, and a click on a row for the arguments carried. It records from load, so the login you wanted to watch is already in it. |

## When the addon does not load

`:addons` lists every folder found with its status; the [AddOns manager](../panel.md) (`Ctrl+O`) says the same with the error attached.

| Status | Look at |
|---|---|
| Not listed | The folder is not under `addons/`, or has no `manifest.json`. |
| `error: …` | The message: bad JSON, a missing `id` or `files`, an id that is not the folder name. |
| `outdated (…)` | The `api_version` line names a [version](../manifest.md#the-api-version) this client does not implement, or there is no such line. To run it as it stands, tick **Load out of date AddOns** on the [Installed tab](../panel.md#installed) and reload. |
| `disabled` | The checkbox; a write addon is disabled the first time it is seen. |
| `not loaded` | An enable no `:reload` has applied yet. |
| `auto-disabled (…)` | The [CPU budget](../runtime.md#budgets-and-the-watchdog) or a [fatal failure](../runtime.md#when-a-failure-is-fatal) the client contained. |

A change to the enabled set is applied on the next reload, never mid-session.

## When the code runs but does nothing

| Cause | Fix |
|---|---|
| Read too early | The file body runs before the world exists, and much of the character sheet streams in for seconds after `SessionEnteredWorld`. A read answering `nil` is asked again from an [event or a timer](events-and-timers.md). |
| The hotkey is unbound | An addon hotkey starts with no key. Options ▸ Game ▸ Keybindings, your addon's section. |
| The selector matches nothing, or too much | [What the two lookups promise](../api/ui/selectors.md#one-or-all-of-them): `nil` for none, an error for two or more. Try it in `:lua`, name the one you mean with a [chain](../api/ui/selectors.md#the-grammar), check the widget with the inspector. |
| The handler threw | The console: an isolated error is a logged line. |

## When it is slow

Arm the client's profiler (Options ▸ Game ▸ Client, or [`hafen.client():options():client():profiling(true)`](../api/client/README.md#client)) and read [`hafen.client():profiling()`](../api/client/profiling/README.md): the frame, the render passes, per-widget cost, and what each addon's Lua cost, most expensive first.

| Culprit | Fix |
|---|---|
| A scan in `Update` | A timer. |
| A string that changes every frame in a draw callback | [Text is cached by its content](../api/ui/drawing.md#text-is-cached-across-frames): round a live readout. |
| A selector lookup per frame | One held Widget. |

The engine [auto-disables](../runtime.md#budgets-and-the-watchdog) an addon that sustains the overrun; the warning on its panel row is the last word.

**Next:** [the API reference](../api/README.md) for the verb you are reaching for, or [the maintainer's addons](../examples.md) for the tools that answer these questions.
