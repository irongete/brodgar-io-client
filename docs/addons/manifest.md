# The manifest

What names an addon: the folder it lives in, the `manifest.json` that makes the folder an addon, and the
API version that file declares. [The runtime](runtime.md) is what the client does with your addon from
there — when it runs, what it may touch, and what it costs.

## Where an addon lives

The client reads addons from the `addons/` folder beside it. One folder per addon, and a folder is an
addon when it holds a `manifest.json`; anything else there is ignored.

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

The folder name **is** the addon's id, and the manifest has to repeat it: a mismatch is a load error, not a
rename. Files your addon ships are read through [`hafen.asset`](api/asset/README.md), which resolves paths
inside your own folder and rejects everything outside it. The folder under `savedata/` is written for you —
see [`hafen.store`](api/store/README.md).

**What is saved is filed by owner.** The client writes one file of its own, `client.sqlite`, and everything
the client keeps goes there: every setting in the Options window, the [options](api/client/addon.md) and
[hotkeys](api/client/keybindings.md) your addon declares, the permissions the user consented to, whether
your addon is enabled, and what the client remembers about your addon on the user's behalf. Your addon's
file holds what your addon saves, and the client keeps nothing of its own in it. A value your addon stores
through the client — an option — survives a disable, a `:reload` and a restart because it is the client's
to keep; a value it stores through `hafen.store` survives them because the file is yours.

## The manifest

`manifest.json` is a JSON object. `id` and `files` are required, and without `api_version` the client leaves
your addon out as [out of date](#the-api-version); everything else is optional.

| Field | Type | Meaning |
|---|---|---|
| `id` | string | unique id; must equal the folder name |
| `files` | array of strings | the `.lua` files to run, in this order; at least one, each inside your own folder |
| `api_version` | string | the API you wrote against, `"X.Y"` — this client implements API `1.0`; absent, your addon is [out of date](#the-api-version) |
| `name` | string | display name in the [AddOns manager](panel.md); defaults to `id` |
| `version` | string | shown in the panel and in `:addons` |
| `author` | string | shown in the panel |
| `description` | string | the panel row's tooltip |
| `permissions` | array of strings | one key per protected verb you call, or a `<prefix>.*` group — the catalogue is in [permissions](guides/permissions.md) |
| `network` | object | `{"hosts": [...]}` — **the argument of the network keys**, `http.get`, `http.post`, `websocket.connect` and `voice.connect`: a key says whether, this says where. Declaring it with none of them is a load error. See [`hafen.http`](api/http.md), [`hafen.websocket`](api/websocket.md) and [`hafen.voice`](api/voice/README.md) |
| `dependencies` | array of strings | addon ids, recorded; the loader neither orders nor requires them |
| `optional_dependencies` | array of strings | the same |

Each addon runs in an environment of its own and cannot see another addon's globals, so there is nothing
for a dependency to import: two addons that cooperate do it through the client — a container, a marker, a
console command — or not at all.

A manifest the client cannot read is a load error naming what is wrong: bad JSON, a missing `id` or
`files`, an id that does not match the folder, an `api_version` that is not a string of the form `"X.Y"`,
an entry of `files`, `permissions` or either dependency list that is not a string, a `permissions` entry
that is neither a key nor a group, a `network` block that is not an object with a `hosts` array, one whose
hosts no network key asks to reach, or a `hosts` entry of `"*"`. A `files` entry that is not a file
inside your own folder — an absolute path, a `..` that climbs out, a link that points out of it — is the
same kind of error, raised as the client goes to run that entry and naming it. The addon then shows an
error row in the panel and runs nothing; the others are unaffected.

## The API version

`api_version` is the API you wrote against, and the client holds it against the one it implements to tell
an addon it can run from one it cannot. Write the version these pages describe, and leave it there until
you use something a later one added.

A version is `"X.Y"`: two whole numbers, neither with a leading zero. **X is the generation**, and it moves
when a documented name is removed or reshaped — an addon written against the old one would raise. **Y is
the edition**, and it moves when a release adds a verb, a section or an event key. There is no third
number: a release that changes nothing you could call changes no number, so a new build does not outdate
your addon by itself. The editions of one generation are additive, which is the whole of the rule:

| You declare | On this client | Your addon |
|---|---|---|
| `"1.0"` | its generation, an edition it has | loads |
| `"1.3"` | an edition it has not got | is out of date: `too new: needs API 1.3 or newer, this client implements 1.0` |
| `"2.0"` | another generation | is out of date: `written for API 2.0, this client implements 1.0` |
| nothing | | is out of date: `declares no api_version, this client implements 1.0` |

**An out-of-date addon is not loaded, and it is not an error.** Nothing threw: the client cannot tell
that it has what the addon was written against, so it does not run it. Its row in the
[AddOns manager](panel.md) reads `outdated (API 2.0, client 1.0)` — `outdated (no
api_version, client 1.0)` when it declares none — its tooltip opens with the sentence in the table, and
`:addons` lists it `[outdated]`. The enable checkbox keeps its value: the addon is waiting for a client
that implements what it declared, or for you to lower the declaration to what you use. A disabled addon is
never read, so its row reads `disabled` whatever it declares, and the tooltip still says why it would not
load. **Load out of date AddOns**, a box on the [same tab](panel.md#installed), is the
player's say over the rule: while it is ticked, the next reload runs every out-of-date addon as it runs a
current one, and the log names each and why.

A field that is not a version — a number, `"1"`, `"0.1"`, `"1.0.0"`, `"v1.0"` — is a manifest error like
any other: the row reads `manifest error (hover)`, and the tooltip names the form and the version this
client implements.

There is no way to read the version from Lua, and none is missing: a current addon has everything it
declared against. An optional newer section is what a feature probe is for — `if hafen.something then` —
and an addon that needs the section outright declares the edition that added it.

## See also

- [the runtime](runtime.md) — when your code runs, the sandbox, the budgets, the panel and the console
- [getting started](getting-started.md) — the first addon, end to end
- [`hafen.store`](api/store/README.md) — your addon's file, named by the id: its vars, tables and statements
- [permissions](guides/permissions.md) — the permission keys the manifest declares
- [`hafen.asset`](api/asset/README.md) — the files your addon ships beside the manifest
