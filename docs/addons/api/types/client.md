# Data Types: The Client

Shapes the client answers about itself: an addon it discovered.

## Addon

`addon:info()` on a [handle](../client/addons.md#the-handle).

| Field | Type | Meaning |
|---|---|---|
| `id` | `string` | The folder and manifest id. |
| `name` | `string` | The manifest `name`, else the id. |
| `version` | `string \| nil` | The manifest `version`. |
| `author` | `string \| nil` | The manifest `author`. |
| `description` | `string \| nil` | The manifest `description`. |
| `status` | `string` | `loaded`, `disabled`, `not loaded`, `error`, `outdated`, `auto-disabled` or `manifest error`. |
| `reason` | `string \| nil` | The sentence behind `error`, `outdated`, `auto-disabled` and `manifest error`. |
