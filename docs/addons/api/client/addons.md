# hafen.client: Addons and Libraries

`hafen.client():addons()` is every addon the client discovered, one handle per id. An addon that exports a table is a **library**: another addon reads it with `addon:api()` and calls into it through the client.

```lua
local toast = hafen.client():addons():get("toast")     -- a handle, whether or not toast is installed
local api = toast:api()                                -- its export, or nil
if api then api.show("Hello") else hafen.log():write("Hello") end
```

---

## The collection

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.client():addons()` | collection | Unprotected | Every addon the last load found in `addons/`, by id. One object per addon of yours. |
| `addons:list(filter)` | `Addon[]` | Unprotected | The handles, by id. A string filter is a substring test on the id; a function is a predicate over the handle. |
| `addons:count(filter)` | `number` | Unprotected | How many. |
| `addons:find(filter)` | `Addon \| nil` | Unprotected | The first that matches. |
| `addons:get(id)` | `Addon` | Unprotected | Always a handle, the same object per id. `:exists()` is the question a `nil` would answer. |

`pairs`, `#` and `[n]` are refused naming `:list()`, as on every [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many). Everything here answers as of the last load: a folder that appears or goes mid-session is seen at the next reload, like the [AddOns manager](../../panel.md).

## The handle

| Method | Returns | Permission | Description |
|---|---|---|---|
| `addon:id()` | `string` | Unprotected | The id, as you addressed it. |
| `addon:exists()` | `boolean` | Unprotected | A folder with a `manifest.json` at the last load. |
| `addon:info()` | [`Addon`](../types/client.md#addon) `\| nil` | Unprotected | The snapshot: id, name, version, author, description, `status`, `reason`. `nil` for an id that does not exist. |
| `addon:api()` | `table \| nil` | Unprotected | Your copy of its export. `nil` when it is not loaded or exported nothing. |

| Rule | Detail |
|---|---|
| `status` | `loaded`, `disabled`, `not loaded`, `error`, `outdated`, `auto-disabled` or `manifest error`: the words the [Installed tab](../../panel.md#installed) shows. `reason` is the sentence behind an `error`, `outdated`, `auto-disabled` or `manifest error`, absent otherwise. |
| Interned | `addons:get("toast") == addons:get("toast")`, from the first call, whether or not the folder exists. `tostring` is `Addon(toast)`. |
| Closed | An unknown verb raises naming the verbs a handle answers. |

## Dependencies and load order

Your manifest's `dependencies` and `optional_dependencies` list ids, each as `<id>` or `<id>>=MAJOR.MINOR.PATCH` ([manifest](../../manifest.md#the-manifest)). They decide when your files run and what happens when a dependency is missing.

| Rule | Detail |
|---|---|
| Order | Every addon runs after the installed, loading addons its two lists name, ties by id. A dependency's `export` and `Load` precede your file body. |
| A hard dependency missing | Your addon is a load error, its row reading `error: needs toast, which is not installed` (`is disabled`, `is out of date`, `has a manifest error`, `failed to load`). Nothing of yours runs. |
| A minimum | `toast>=1.2.0` compares the library's manifest `version` as the hub orders versions. Not met on a hard dependency: `error: needs toast >= 1.2.0, 1.0.0 installed`. On an optional one: the library is, to you, absent — `:api()` is `nil`, `:exists()` and `:info()` still answer. A library whose `version` is not a version is below every minimum. |
| An optional dependency absent | Nothing happens. Ask `:api()` when you need it. |
| A cycle | Hard dependencies that close a cycle are a load error on each member: `depends in a cycle: a -> b -> a`. An optional dependency that would close one is not ordered, with a log line. |
| Mid-session | A library the [CPU budget](../../runtime.md#budgets-and-the-watchdog) stops takes its loaded hard dependants with it, each reading `auto-disabled (needs toast)`. An optional dependant keeps running and its `:api()` turns `nil`. |
| Tearing down | In reverse load order: a dependant's `Disable` handler still reaches its library. |

```lua
-- optional: ask at the moment of use, and nothing breaks without it
local toast = hafen.client():addons():get("toast")
local function notify(text)
  local api = toast:api()
  if api then api.show(text) else hafen.log():write(text) end
end
```

---

## See Also

- [The manifest](../../manifest.md) — the two dependency lists.
- [Data types](../types/client.md) — the `Addon` shape.
