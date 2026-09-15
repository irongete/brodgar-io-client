# Addon Guides

Task-oriented, step-by-step tutorials for building features in your addons. Each guide demonstrates a complete workflow with practical code examples.

## Available Guides

| Guide | What You Will Learn |
|---|---|
| **[Reading the World](reading-the-world.md)** | Find game objects, query distances, read positions, and check terrain tiles. |
| **[Events and Timers](events-and-timers.md)** | Handle game lifecycle events, listen to the event bus, and schedule periodic tasks. |
| **[Custom UI](custom-ui.md)** | Create draggable windows, standard UI controls (buttons, checkboxes, text fields), and custom 2D canvas drawing. |
| **[Saved Data & Persistence](saved-data.md)** | Store character settings, global preferences, and custom SQLite tables. |
| **[Hotkeys and Commands](hotkeys-and-commands.md)** | Register custom keybindings in the Options menu and add in-game console commands (`:mycommand`). |
| **[Permissions](permissions.md)** | Protected action keys, character driving capabilities, and network security declarations. |
| **[Theming & Stylesheets](theming.md)** | Restyle existing client windows and customize the visual appearance of addon widgets. |
| **[Localization & Translating](translating.md)** | Provide multi-language translation catalogs for your addon. |
| **[Debugging & Profiling](debugging.md)** | Practical debugging techniques using `:reload`, live `:lua` execution, log outputs, and profiling. |

---

## Code Example Standards

All guide examples follow explicit naming conventions for clarity:
* `session`: Reference to an active character session (`hafen.session():current()`).
* `window_handle`: Custom UI window handle (`hafen.ui():window()`).
* `game_object`: In-world entity handle (`session:world():gob()`).
* `position`: Coordinate location object (`game_object:position()`).
* `graphics`: 2D drawing canvas context (`event:g()`).
