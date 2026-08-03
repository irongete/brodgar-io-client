# Documentation

The client's documentation. It covers one thing: the **AddOn system**, the surface the client opens to
code you write yourself.

An addon is a folder of Lua files. It reads the game state, reacts to events, draws its own UI, adds
hotkeys and console commands, restyles the client — and, with the user's permission, drives the
character.

| Page | Read it for |
|---|---|
| [AddOns](addons/README.md) | the landing page: what an addon is, and where everything else is |
| [getting started](addons/getting-started.md) | an empty folder to a working addon, in eight steps |
| [guides](addons/guides/README.md) | one page per task: the world, events, UI, saved data, permissions, theming, debugging |
| [API reference](addons/api/README.md) | every `hafen.*` namespace, verb, argument and return |
| [the runtime](addons/runtime.md) | the manifest, the sandbox, the CPU budgets, the AddOns panel, the console commands |
| [the examples](addons/examples.md) | the ten addons that ship with the client, and what each one shows |
