# Manifest Specification

Every addon resides in a directory under `addons/` and must contain a `manifest.json` file at its root.

```text
addons/
  my_addon/
    manifest.json      # Metadata and configuration
    main.lua           # Entry point
    assets/            # Custom assets (images, sounds)
```

## Schema & Fields

```json
{
  "id": "my_addon",
  "name": "My Custom Addon",
  "version": "1.0.0",
  "author": "AuthorName",
  "description": "A concise description of what the addon does.",
  "api_version": "1.0",
  "files": [
    "main.lua"
  ],
  "permissions": [
    "player.move",
    "http.get"
  ],
  "network": {
    "hosts": [
      "api.example.com",
      "*.myserver.org"
    ]
  },
  "dependencies": [],
  "optional_dependencies": []
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `id` | `string` | **Yes** | Unique addon identifier. Must match the folder name exactly. |
| `api_version` | `string` | **Yes** | Target API version (e.g. `"1.0"`). If omitted or unsupported, the addon will be marked outdated. |
| `files` | `string[]` | **Yes** | Array of `.lua` source files to execute in sequential order on startup. |
| `name` | `string` | No | User-friendly display name shown in the AddOns manager. Defaults to `id`. |
| `version` | `string` | No | Semantic version string (e.g. `"1.2.0"`). |
| `author` | `string` | No | Author name or handle. |
| `description` | `string` | No | Brief explanation shown in tooltips and the manager panel. |
| `permissions` | `string[]` | No | Array of protected action keys or wildcard groups (e.g., `["player.move", "item.*"]`). |
| `network` | `object` | No | Required if network permissions (`http.*`, `websocket.*`) are declared. Specifies allowed hosts. |
| `dependencies` | `string[]` | No | List of required addon IDs. |
| `optional_dependencies`| `string[]` | No | List of optional addon IDs. |

## Network Configuration

When declaring permissions that make outgoing network connections (`http.get`, `http.post`, `websocket.connect`, `voice.connect`), you must explicitly define allowed endpoints in the `network.hosts` list:

```json
"permissions": ["http.get", "websocket.connect"],
"network": {
  "hosts": [
    "api.myservice.com",
    "ws.myservice.com:8443",
    "*.trusted-domain.com"
  ]
}
```

* **Defaults**: Uses HTTPS (`https://`) on port `443` unless another scheme/port is specified (e.g., `http://local.test:8080`).
* **Wildcards**: Supports single-level subdomain wildcards (`*.trusted.com`). Top-level wildcards like `*.com` or `*` are rejected.

## API Compatibility

* `api_version` follows `"MAJOR.MINOR"` formatting (e.g. `"1.0"`).
* **Generation (MAJOR)**: Changes when existing APIs or behaviors are altered in a breaking manner.
* **Edition (MINOR)**: Increments when new non-breaking features, methods, or events are added.
* If an addon specifies an unsupported API edition or generation, it is marked as `[outdated]` in the AddOns panel and skipped until updated or forced via the client's options.

## Persistent Storage Location

* Addon assets and code: `addons/<id>/`
* Addon persistent database files (`hafen.store()`): `savedata/<id>/<id>.sqlite`
* Client options and keybindings: `savedata/client.sqlite`
