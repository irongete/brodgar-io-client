# Documentation

The documentation is organized into two distinct sections:

- **[AddOns](addons/README.md)**: Developer documentation for creating client addons in Lua. Covers the `hafen.*` API, manifest configuration, UI creation, event handling, and examples.
- **[Client Internals](client/README.md)**: Technical reference for the underlying Java client engine, subsystems, rendering pipeline, and architecture.

## Quick Navigation

### Creating Addons
- **[Getting Started](addons/getting-started.md)**: Build your first addon in 5 minutes.
- **[Addon Guides](addons/guides/README.md)**: Step-by-step guides for UI, world interaction, events, timers, data persistence, and permissions.
- **[API Reference](addons/api/README.md)**: Complete reference for all `hafen.*` namespaces, methods, parameters, and events.
- **[Manifest Specification](addons/manifest.md)**: Configuration reference for `manifest.json`.
- **[Runtime & Sandbox](addons/runtime.md)**: Execution environment, watchdog limits, lifecycle hooks, and console commands.

### Modifying the Client
- **[Architecture Overview](client/README.md)**: Subsystem map of the Java client codebase.
- **[Render Pipeline](client/render-gl.md)**: OpenGL rendering architecture.
- **[State Management](client/state.md)**: Engine state tracking and synchronization.
