# hafen.store — saved variables

Persist data across sessions. Each **saved variable** is a Lua table declared in your
[manifest](../getting-started.md#the-manifest), persisted to JSON on disk and restored on load.

Declare them in `manifest.json`:

```json
"saved_variables": ["settings", { "name": "account", "scope": "account" }]
```

- A bare name is **per-character** storage (`savedata/<genus>_<char>/<addon>.json`).
- `{ "name": ..., "scope": "account" }` is **account-wide** (shared across all your characters).

Then read/write `hafen.store.<name>` like any table:

| Member | Description |
|---|---|
| `hafen.store.<name>` | a persisted table (one per declared saved variable) |
| `hafen.store.flush()` | force a write to disk now |

```lua
hafen.store.settings.enabled = true
hafen.store.settings.count = (hafen.store.settings.count or 0) + 1
```

The table object for each name is **stable for the addon's whole life** (a restore fills it in place),
so a cached reference stays valid. Account-scope tables are ready in the file body / `OnLoad`;
per-character tables are restored at `OnEnterWorld` (the per-character folder isn't known before then).
Changes are auto-saved periodically and on disable/reload; `flush()` writes immediately.
