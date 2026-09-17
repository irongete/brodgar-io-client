# The Manifest

What names an addon: the folder it lives in, the `manifest.json` that makes the folder an addon, and the API version that file declares. [The runtime](runtime.md) is what the client does with your addon from there.

```json
{
  "id": "myaddon",
  "api_version": "1.0",
  "files": ["main.lua"],
  "name": "My Addon",
  "permissions": ["player.move"]
}
```

---

## Where an addon lives

The client reads addons from the `addons/` folder beside it: one folder per addon, an addon when it holds a `manifest.json`; anything else is ignored. Another folder — a checkout of your own, say — is `haven.addondir=<path>` in the `haven-config.properties` beside the client (the launcher's Options write that line from its *Addons folder* field) or `-Dhaven.addondir=<path>` on the command line, which wins over the file; the client's `savedata/` then sits beside that folder, unless `haven.savedatadir` names its own.

```text
addons/
  myaddon/
    manifest.json      the metadata, and the list of files to run
    main.lua           your code
    icon.png           anything else you ship, loaded with hafen.asset
savedata/
  client.sqlite      the client's own file: its settings, your addon's options and hotkeys among them,
                     and what it keeps about your addon
  myaddon/
    myaddon.sqlite   your addon's own file: everything it saves through hafen.store
```

| Rule | Detail |
|---|---|
| The folder name is the id | The manifest repeats it; a mismatch is a load error. |
| Files you ship | Read through [`hafen.asset`](api/asset/README.md), which resolves paths inside your folder and rejects everything outside. |
| Saved data is filed by owner | `client.sqlite` holds everything the client keeps: every Options setting, the [options](api/client/addon.md) and [hotkeys](api/client/keybindings.md) your addon declares, the permissions consented to, whether your addon is enabled, what the client remembers about your addon for the user. Your file under `savedata/` holds what your addon saves through [`hafen.store`](api/store/README.md); the client keeps nothing of its own in it. Both survive a disable, a `:reload` and a restart. |

## The manifest

A JSON object. `id` and `files` are required; without `api_version` the client leaves your addon out as [out of date](#the-api-version); everything else is optional.

| Field | Type | Meaning |
|---|---|---|
| `id` | `string` | Unique id; equals the folder name. |
| `files` | `string[]` | The `.lua` files to run, in this order; at least one, each inside your own folder. |
| `api_version` | `string` | The API you wrote against, `"X.Y"`; this client implements API `1.0`. Absent, your addon is [out of date](#the-api-version). |
| `name` | `string` | Display name in the [AddOns manager](panel.md); defaults to `id`. |
| `version` | `string` | Shown in the panel and in `:addons`. |
| `author` | `string` | Shown in the panel. |
| `description` | `string` | The panel row's tooltip. |
| `permissions` | `string[]` | One key per protected verb you call, or a `<prefix>.*` group ([permissions](guides/permissions.md)). |
| `network` | `object` | `{"hosts": [...]}`, the argument of the network keys `http.get`, `http.post`, `websocket.connect` and `voice.connect`: a key says whether, this says where. Declaring it with none of them is a load error ([`hafen.http`](api/http.md), [`hafen.websocket`](api/websocket.md), [`hafen.voice`](api/voice/README.md)). |
| `dependencies` | `string[]` | Addon ids, recorded; the loader neither orders nor requires them. |
| `optional_dependencies` | `string[]` | The same. |

| Rule | Detail |
|---|---|
| Nothing to import | Each addon runs in an environment of its own and cannot see another's globals; two that cooperate do it through the client (a container, a marker, a console command). |
| A manifest the client cannot read | A load error naming what is wrong: bad JSON; a missing `id` or `files`; an id not matching the folder; an `api_version` not a string of the form `"X.Y"`; an entry of `files`, `permissions` or either dependency list that is not a string; a `permissions` entry neither a key nor a group; a `network` block not an object with a `hosts` array, one whose hosts no network key reaches, or a `hosts` entry of `"*"`. A `files` entry not a file inside your folder (an absolute path, a `..` that climbs out, a link pointing out) is the same error, raised as the client goes to run it. The addon shows an error row in the panel and runs nothing; the others are unaffected. |

## The API version

`api_version` is the API you wrote against, held against the one the client implements. Write the version these pages describe and leave it until you use something a later one added.

| Rule | Detail |
|---|---|
| The form | `"X.Y"`, two whole numbers, neither with a leading zero. X is the generation, moving when a documented name is removed or reshaped; Y the edition, moving when a release adds a verb, a section or an event key. No third number: a release that changes nothing you could call changes no number. The editions of one generation are additive. |
| Not a version | A number, `"1"`, `"0.1"`, `"1.0.0"`, `"v1.0"`: a manifest error, the row reading `manifest error (hover)` and the tooltip naming the form and the client's version. |
| No read from Lua | A current addon has everything it declared against. An optional newer section is a feature probe, `if hafen.something then`; an addon needing it outright declares the edition that added it. |

| You declare | On this client | Your addon |
|---|---|---|
| `"1.0"` | Its generation, an edition it has. | Loads. |
| `"1.3"` | An edition it has not got. | Out of date: `too new: needs API 1.3 or newer, this client implements 1.0`. |
| `"2.0"` | Another generation. | Out of date: `written for API 2.0, this client implements 1.0`. |
| Nothing | | Out of date: `declares no api_version, this client implements 1.0`. |

| Rule | Detail |
|---|---|
| Out of date is not an error | The client cannot tell it has what the addon was written against, so it does not run it. The [AddOns manager](panel.md) row reads `outdated (API 2.0, client 1.0)`, or `outdated (no api_version, client 1.0)`; its tooltip opens with the sentence above; `:addons` lists it `[outdated]`. The enable checkbox keeps its value. A disabled addon is never read, so its row reads `disabled` and the tooltip still says why it would not load. |
| Load out of date AddOns | A box on the [Installed tab](panel.md#installed): while ticked, the next reload runs every out-of-date addon as a current one, and the log names each and why. |

---

## See Also

- [The runtime](runtime.md) — when your code runs, the sandbox, the budgets, the panel and the console.
- [Getting started](getting-started.md) — the first addon, end to end.
- [`hafen.store`](api/store/README.md) — your addon's file, named by the id.
- [Permissions](guides/permissions.md) — the permission keys the manifest declares.
- [`hafen.asset`](api/asset/README.md) — the files your addon ships beside the manifest.
