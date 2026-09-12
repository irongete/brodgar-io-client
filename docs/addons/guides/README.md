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
| [custom UI](custom-ui.md) | a window, a panel of controls, an overlay, and painting your own pixels |
| [saved data](saved-data.md) | keep settings and layouts across sessions |
| [hotkeys, commands and settings](hotkeys-and-commands.md) | let the user drive your addon by hand |
| [permissions](permissions.md) | drive the character: the catalogue of keys, and what declaring one costs you |
| [theming](theming.md) | restyle the client's own surfaces, and ship a theme as a file |
| [translating](translating.md) | change what the client says, and ship a translation as a file |
| [debugging](debugging.md) | the reload loop, the inspector, the log and the profiling surface |

They are written to be read in that order, and each one ends by pointing at the next. Nothing stops you
opening the one you need.

Two pages sit beside them rather than in the list: [the runtime](../runtime.md), for the manifest, the
sandbox and the console commands, and [the maintainer's addons](../examples.md), for where the addons
are and the tools among them.
