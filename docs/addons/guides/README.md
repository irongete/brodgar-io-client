# Guides

One page per task, start to finish. Each shows the shape of a solution and links every verb it uses to [the reference](../api/README.md), where the arguments, return values and error cases live. [Getting started](../getting-started.md) comes first: these pages assume a folder that loads.

| Guide | The task |
|---|---|
| [Reading the world](reading-the-world.md) | Find game objects, read one, ask what is on the ground. |
| [Events and timers](events-and-timers.md) | Run your code at the right moment, and not on every frame. |
| [Custom UI](custom-ui.md) | A window, a panel of controls, an overlay, painting your own pixels. |
| [Saved data](saved-data.md) | Keep settings and layouts across sessions. |
| [Hotkeys, commands and settings](hotkeys-and-commands.md) | Let the user drive your addon by hand. |
| [Libraries](libraries.md) | Export functions for other addons, and use another addon's. |
| [Bundles](bundles.md) | Pack a set of addons into one the player installs, set up to work together. |
| [Permissions](permissions.md) | Drive the character: the catalogue of keys, and what declaring one costs. |
| [Theming](theming.md) | Restyle the client's own surfaces, and ship a theme as a file. |
| [Translating](translating.md) | Change what the client says, and ship a translation as a file. |
| [Debugging](debugging.md) | The reload loop, the inspector, the log and the profiling surface. |

Written to be read in that order, each ending by pointing at the next. Beside them, [the manifest](../manifest.md) says what names an addon and the API version it declares. [The runtime](../runtime.md) covers the sandbox and the console commands. [Dev tools](../examples.md) names the three addons to point at your own while you write it.
