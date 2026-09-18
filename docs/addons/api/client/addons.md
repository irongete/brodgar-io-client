# hafen.client: Addons and Libraries

`hafen.client():addons()` is every addon the client discovered, one handle per id. An addon that exports a table is a **library**: another addon reads it with `addon:api()` and calls into it through the client.

```lua
local library = hafen.client():addons():get("mylib")   -- a handle, whether or not mylib is installed
local api = library:api()                              -- its export, or nil
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
| `addons:export(t)` | the collection | Unprotected | Publish `t` as your export, once — [below](#exporting). |

`pairs`, `#` and `[n]` are refused naming `:list()`, as on every [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many). Everything here answers as of the last load: a folder that appears or goes mid-session is seen at the next reload, like the [AddOns manager](../../panel.md).

## The handle

| Method | Returns | Permission | Description |
|---|---|---|---|
| `addon:id()` | `string` | Unprotected | The id, as you addressed it. |
| `addon:exists()` | `boolean` | Unprotected | A folder with a `manifest.json` at the last load. |
| `addon:info()` | [`Addon`](../types/client.md#addon) `\| nil` | Unprotected | The snapshot: id, name, version, author, description, `status`, `reason`. `nil` for an id that does not exist. |
| `addon:api()` | `table \| nil` | Unprotected | Your copy of its export — [below](#reading-an-export). `nil` when it is not loaded, exported nothing, or is below the minimum you declared. |

| Rule | Detail |
|---|---|
| `status` | `loaded`, `disabled`, `not loaded`, `error`, `outdated`, `auto-disabled` or `manifest error`: the words the [Installed tab](../../panel.md#installed) shows. `reason` is the sentence behind an `error`, `outdated`, `auto-disabled` or `manifest error`, absent otherwise. |
| Interned | `addons:get("mylib") == addons:get("mylib")`, from the first call, whether or not the folder exists. `tostring` is `Addon(mylib)`. |
| Closed | An unknown verb raises naming the verbs a handle answers. |

## Dependencies and load order

Your manifest's `dependencies` and `optional_dependencies` list ids, each as `<id>` or `<id>>=MAJOR.MINOR.PATCH` ([manifest](../../manifest.md#the-manifest)). They decide when your files run and what happens when a dependency is missing.

| Rule | Detail |
|---|---|
| Order | Every addon runs after the installed, loading addons its two lists name, ties by id. A dependency's `export` and `Load` precede your file body. |
| A hard dependency missing | Your addon is a load error, its row reading `error: needs mylib, which is not installed` (`is disabled`, `is out of date`, `has a manifest error`, `failed to load`). Nothing of yours runs. |
| A minimum | `mylib>=1.2.0` compares the library's manifest `version` as the hub orders versions. Not met on a hard dependency: `error: needs mylib >= 1.2.0, 1.0.0 installed`. On an optional one: the library is, to you, absent — `:api()` is `nil`, `:exists()` and `:info()` still answer. A library whose `version` is not a version is below every minimum. |
| An optional dependency absent | Nothing happens. Ask `:api()` when you need it. |
| A cycle | Hard dependencies that close a cycle are a load error on each member: `depends in a cycle: a -> b -> a`. An optional dependency that would close one is not ordered, with a log line. |
| Mid-session | A library the [CPU budget](../../runtime.md#budgets-and-the-watchdog) stops takes its loaded hard dependants with it, each reading `auto-disabled (needs mylib)`. An optional dependant keeps running and its `:api()` turns `nil`. |
| Tearing down | In reverse load order: a dependant's `Disable` handler still reaches its library. |

```lua
-- optional: ask at the moment of use, and nothing breaks without it
local library = hafen.client():addons():get("mylib")
local function notify(text)
  local api = library:api()
  if api then api.show(text) else hafen.log():write(text) end
end
```

## Exporting

`hafen.client():addons():export(t)` reads `t` once, at the call, and keeps a copy. Call it in your file body, so every addon that names you in a dependency list finds it. A second call refuses `already exported`.

| `t` may hold | Rule |
|---|---|
| Functions | Each runs as yours when called — [below](#what-a-call-does). |
| Strings, numbers, booleans | Copied. A value that changes is a function that answers it: the copy never updates. |
| Tables of the same | Copied, recursively. |
| Anything else | Refused naming the key: `export: 'icon' is a Widget — export functions and plain values`. A handle of yours is not a value another addon can hold. |

```lua
local open = 0
local function show(text)
  open = open + 1
  local box = hafen.ui():widget():size(240, 28):position(400, 40 + open * 32)
  hafen.ui():label():text(text):parent(box):position(8, 6)
  hafen.timer():after(3, function() box:destroy(); open = open - 1 end)
  return open
end
hafen.client():addons():export({ show = show, count = function() return open end })
```

## Reading an export

`handle:api()` hands you **your own copy** of the library's export, the same table on every call while the library is loaded.

| Rule | Detail |
|---|---|
| Read-only | A write refuses: `mylib's export is read-only — it is your copy; a change belongs in the library, through a function it exports`. `pairs` and `#` work; it is a table. |
| Functions are wrappers | `api.show == api.show`, and `rawequal(api.show, f)` is false for the library's own `f`. Every call enters the library. |
| `nil` | The library does not exist, is not loaded, exported nothing, or is below the minimum your manifest names. |
| After a teardown | The copy you hold stays a table; a function in it refuses `mylib.show: mylib is disabled`. |

## What a call does

A call through an export — and a callback the library calls back — enters the **owner's** door.

| Rule | Detail |
|---|---|
| Runs as its owner | The library's code runs under the library's [consent](../../guides/permissions.md), with the library's environment and store, whoever called it. A callback you hand a library runs under yours. |
| Budgets | The call is an entry into the owner's Lua: a fresh [instruction budget](../../runtime.md#budgets-and-the-watchdog). Its time is charged to the owner under `exports` and to the caller's own entry. |
| An error | Reaches the caller as `mylib.show: <reason>`; the library keeps running. `pcall` catches it. |
| Busy | From a handler that holds a tree's monitor ([threading](../threading.md)), a library busy on another thread refuses: `mylib.show: mylib is busy on another thread`. |

### What crosses, and how

Arguments and return values follow one rule in both directions.

| Value | Crosses as |
|---|---|
| A string, number, boolean, `nil` | Itself. |
| A function | A wrapper entering its owner's door. The same function crosses as the same wrapper. |
| A table | A read-only copy, recursive, cycles kept; the write refusal names the addon it came from. A metatable does not cross. |
| A bridge handle (a widget, a timer, a session, a store table, a collection…) | Refused: `mylib.show: argument 2 is a Widget — a handle does not cross to another addon; hand it a function`. |

```lua
-- lend a capability, not a handle: the library can only do what your functions do
local settings = hafen.store():var("settings")
api.bind({ get = function(key) return settings[key] end,
           set = function(key, value) settings[key] = value end })
```

---

## See Also

- [Libraries](../../guides/libraries.md) — writing one and using one, end to end.
- [The manifest](../../manifest.md) — the two dependency lists.
- [Attribution](profiling/attribution.md) — the `exports` category.
- [Data types](../types/client.md) — the `Addon` shape.
