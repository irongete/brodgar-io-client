# Debugging

The loop is short: edit the file, type `:reload`, read the console. Nothing is compiled, nothing is
installed, and you never log out. This guide is what to do when the console does not say what you expected.

## Print, and read the two places it lands

[`hafen.log`](../api/log.md) writes to the in-game console **and** to the terminal the client was started
from, prefixed with your addon's id — which is what makes several addons logging at once readable.

```lua
hafen.log():write("state: " .. hafen.json():encode(hafen.store():get("settings")))
```

The in-game line is clipped at 500 characters, so print a big table and read it off the terminal;
[`hafen.json():encode`](../api/json.md) is how you see inside one, since logging a table prints only that it
is a table.

Errors your addon raises arrive on the same channel with the same prefix, wherever they came from — a
handler, a timer, a draw callback. They are isolated: the one callback dies, the rest of your addon and the
rest of the client carry on. That is why a broken handler looks like *nothing happening* rather than like a
crash, and why the console is the first place to look.

## Try the call before you write it

`:lua <expression>` evaluates against the live API and prints the result as JSON. It is the fastest way to
find out what a verb actually answers, and it runs as your own console rather than as addon code, so
nothing is sandboxed and every protected verb works there.

```text
:lua hafen.world():gob():count("terobjs/tree")
:lua hafen.ui():find("window[title=Inventory]"):info()
:lua for _, m in ipairs(hafen.meter():list()) do hafen.log():write(tostring(m:res())) end
```

Anything that reads is safe to try. Anything that writes is a real change to your client — that is the
point of the console, and the reason to keep `:reload` in reach.

## Name a widget you are pointing at

Selectors are guessed wrong more often than anything else in the API, so do not guess. Enable the bundled
**`widgetstack`** addon and hover: it reports the widget's [role](../api/ui/selectors.md#roles), its class,
its `[title=]` and its `[res=]`, then offers **every selector that matches it**, most specific first, each
one resolved before it is shown and ready to paste into `:lua`. Its `freeze` hotkey holds the stack still
while you move the mouse over to read it.

Two answers that surprise people, both from [the inspector's own page](../api/ui/selectors.md): hovering a
window's frame gives you the frame, not the window, because the chrome is a widget of its own; and most
widgets have **no** role at all, which is the classifier being honest rather than a gap.

## When the addon does not load

`:addons` lists every folder the client found with its status, and Options ▸ AddOns says the same thing
with the error message attached. Work down this list:

| The status says | Look at |
|---|---|
| nothing — the addon is not listed | the folder is not under `addons/`, or has no `manifest.json` |
| `error: …` | the message: bad JSON, a missing `id` or `files`, or an id that is not the folder name |
| `disabled` | the checkbox — and remember a write addon is disabled the first time it is seen |
| `not loaded` | an enable that no `:reload` has applied yet |
| `auto-disabled (…)` | the [CPU budget](../runtime.md#budgets-and-the-watchdog): your addon was burning the frame |

A change to the enabled set is always applied on the **next** reload, never mid-session, so "I ticked it
and nothing happened" is one `:reload` away from being fixed.

## When the code runs but does nothing

Four causes cover almost all of it:

- **Read too early.** Your file body runs before the world exists, and much of the character sheet streams
  in for seconds *after* `EnterWorld`. If a read answers `nil`, ask again from an
  [event or a timer](events-and-timers.md).
- **The hotkey is unbound.** An addon hotkey starts with no key at all, by design. Look in
  Options ▸ Keybindings for your addon's section.
- **The selector matches nothing.** `hafen.ui():find(sel)` answers `nil` rather than throwing. Try it in
  `:lua`,
  and check the widget with the inspector.
- **The handler threw.** Look at the console: an isolated error is a logged line, not a stopped client.

## When it is slow

Arm the client's profiler — the Options ▸ Client checkbox, or
[`hafen.client():options():client():profiling(true)`](../api/client/README.md#client) — and read
[`hafen.client():profiling()`](../api/client/profiling/README.md), which reports the frame, the render
passes, per-widget cost and **what each addon's Lua cost**, most expensive first. The bundled **`profiler`**
addon draws all of it, so you rarely need to write that code yourself.

The usual culprits are a scan in `Update` (do it on a timer instead), a string that changes every frame
in a draw callback ([text is cached by its content](../api/ui/drawing.md#text-is-cached-across-frames)),
and a selector lookup per frame instead of one held Widget. Long before it costs you a frame, the engine
will [auto-disable](../runtime.md#budgets-and-the-watchdog) an addon that sustains the overrun — the
warning on its panel row is the last word, not the first.

**Next:** [the API reference](../api/README.md) for the verb you are reaching for, or
[the examples](../examples.md) for someone who already solved this.
