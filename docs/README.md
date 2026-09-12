# Documentation

Two subtrees, two readers. **[AddOns](addons/README.md)** is for someone writing an addon, who has
never opened the client's source. **[The client's internals](client/README.md)** is for someone
changing that source. Take the one that matches what you are about to do — neither needs the other.

## Writing an addon

An addon is a folder of Lua files. It reads the game state, reacts to events, draws its own UI, adds
hotkeys and console commands, restyles the client — and, with the user's permission, drives the
character. Everything it may touch arrives through one `hafen.*` API.

| Page | Read it for |
|---|---|
| [AddOns](addons/README.md) | the landing page: what an addon is, and where everything else is |
| [getting started](addons/getting-started.md) | an empty folder to a working addon, step by step |
| [guides](addons/guides/README.md) | one page per task: the world, events, UI, saved data, permissions, theming, debugging |
| [API reference](addons/api/README.md) | every `hafen.*` namespace, verb, argument and return |
| [the runtime](addons/runtime.md) | the manifest, the sandbox, the CPU budgets, the AddOns panel, the console commands |
| [the maintainer's addons](addons/examples.md) | where the addons are, which of them a release ships, and the tools among them for writing your own |

## Changing the client

The engine underneath is around 100k lines of unannotated Java.
[The client's internals](client/README.md) maps it: one page per subsystem, saying where a thing
lives, what owns it, and what bit the last person to go in. It turns finding a seam into a lookup
instead of a search.

It is a **map, not an authority** — where a page disagrees with the source, the source wins. It
covers the upstream engine only: what this client adds on top is stated by
[the API reference](addons/api/README.md) and by the code itself. And it is not a complete map:
there is a page where the work has gone.
