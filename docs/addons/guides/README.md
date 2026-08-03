# Guides

One page per task, each one start to finish. A guide shows the shape of a solution and links every verb it
uses to [the reference](../api/README.md), which is where the arguments, the return values and the error
cases live.

If you have not written an addon yet, [getting started](../getting-started.md) comes first: these pages
assume you have a folder that loads.

| Guide | The task |
|---|---|
| [reading the world](reading-the-world.md) | find game objects, read one, and ask what is on the ground |
| [events and timers](events-and-timers.md) | run your code at the right moment, and not on every frame |
| [custom UI](custom-ui.md) | a window, an overlay, and painting your own pixels |
| [saved data](saved-data.md) | keep settings and layouts across sessions |
| [hotkeys and commands](hotkeys-and-commands.md) | let the user invoke your addon by hand |
| [actions and permissions](actions-and-permissions.md) | drive the character, and what that costs you |
| [theming](theming.md) | restyle the client's own surfaces, and ship a theme as a file |
| [debugging](debugging.md) | the reload loop, the inspector, the log and the profiler |

They are written to be read in that order, and each one ends by pointing at the next. Nothing stops you
opening the one you need.

Two pages sit beside them rather than in the list: [the runtime](../runtime.md), for the manifest, the
sandbox and the console commands, and [the examples](../examples.md), for the ten addons that ship with the
client.
