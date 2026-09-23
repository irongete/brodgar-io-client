# Brodgar client

A [Haven & Hearth](https://www.havenandhearth.com/) client, forked from
[dolda2000/hafen-client](https://github.com/dolda2000/hafen-client), documented
[here](https://irongete.github.io/brodgar-io-client/).

- **AddOns** — a Lua addon system.
- **Multi-session** — log in several characters and control them at once.
- **Proximity voice chat** — voice chat with the players near you.

## How to play

1. Download the launcher for your system; it brings its own Java:
   **[Windows](https://github.com/irongete/brodgar-io-client-launcher/releases/latest/download/brodgar.io-launcher-windows-x64.zip)** ·
   **[macOS](https://github.com/irongete/brodgar-io-client-launcher/releases/latest/download/brodgar.io-launcher-macos-arm64.zip)**
   (Apple Silicon) ·
   **[Linux](https://github.com/irongete/brodgar-io-client-launcher/releases/latest/download/brodgar.io-launcher-linux-x64.zip)**
   (x64).
2. Start it. **Windows**: unzip it anywhere and run `run.bat`. **macOS**: open the app, **Brodgar launcher**; it is
   not signed by Apple, so the first time macOS refuses it — open **System Settings**, **Privacy & Security**,
   and press **Open Anyway**, once. **Linux**: unzip it anywhere and run `run.sh` (a right click, **Run as a
   Program**); from then on it is in your applications menu. The launcher installs the client and keeps it
   at the newest release, on the **Release** or the **Beta** channel — pick either from its dropdown.
3. Log in. With the Steam client running, the login screen's *Log in with Steam* button logs in the
   Haven & Hearth account your Steam account is linked to (linking is on the game's website, under
   *Account security*). The password box beside it is the native login, as ever. On macOS the client
   cannot load Steam yet: log in with the password.

## Documentation

The documentation is organized into two distinct sections:

- **[AddOns](https://irongete.github.io/brodgar-io-client/addons/)**: Developer documentation for creating client addons in Lua. Covers the `hafen.*` API, manifest configuration, UI creation, event handling, and examples.
- **[Client Internals](https://irongete.github.io/brodgar-io-client/client/)**: Technical reference for the underlying Java client engine, subsystems, rendering pipeline, and architecture.

### Creating Addons
- **[Getting Started](https://irongete.github.io/brodgar-io-client/addons/getting-started.html)**: Build your first addon in 5 minutes.
- **[Addon Guides](https://irongete.github.io/brodgar-io-client/addons/guides/)**: Step-by-step guides for UI, world interaction, events, timers, data persistence, and permissions.
- **[API Reference](https://irongete.github.io/brodgar-io-client/addons/api/)**: Complete reference for all `hafen.*` namespaces, methods, parameters, and events.
- **[Manifest Specification](https://irongete.github.io/brodgar-io-client/addons/manifest.html)**: Configuration reference for `manifest.json`.
- **[Runtime & Sandbox](https://irongete.github.io/brodgar-io-client/addons/runtime.html)**: Execution environment, watchdog limits, lifecycle hooks, and console commands.

### Modifying the Client
- **[Architecture Overview](https://irongete.github.io/brodgar-io-client/client/)**: Subsystem map of the Java client codebase.
- **[Render Pipeline](https://irongete.github.io/brodgar-io-client/client/render-gl.html)**: OpenGL rendering architecture.
- **[State Management](https://irongete.github.io/brodgar-io-client/client/state.html)**: Engine state tracking and synchronization.
