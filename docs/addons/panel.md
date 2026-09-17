# The AddOns Manager

**AddOns**, on the game menu `Ctrl+O` opens, switches your addons on and off and finds new ones: the **Installed** tab lists every addon in `addons/` with its live status and enable box, and updates or removes the ones the hub installed; the **Browse** tab is the front page of `brodgar.io/addons`, the hub, drawn in the client. The client reads the hub anonymously: nothing about you or your character travels with the request.

---

## Installed

Every addon the client discovered, sorted by id: a checkbox, the name, version and author, a live status; the description is the tooltip. With nothing in `addons/` the list says `No addons installed`.

| Row shows | Meaning |
|---|---|
| `loaded v<version>` | Running. |
| `disabled` | Switched off, not loaded. |
| `not loaded` | Enabled but not running: an enable no reload has applied yet. |
| `error: …` | Its manifest or its Lua failed; the message says how. |
| `manifest error (hover)` | The manifest does not parse; the tooltip opens with the reason. |
| `outdated (…)` | Not run: the [API version](manifest.md#the-api-version) it declares is not one this client implements (`outdated (API 9.0, client 1.0)`) or it declares none (`outdated (no api_version, client 1.0)`). |
| `auto-disabled (…)` | The [CPU budget](runtime.md#budgets-and-the-watchdog) or a [fatal failure](runtime.md#when-a-failure-is-fatal) stopped it. |
| `update <version>` | The hub publishes a newer version; **Update** stands beside it. |
| `downloading <n>%` | An update's package is on its way, against the size the hub advertised. |
| `staged <version> - Reload UI to apply` | A version [installed from the hub](#how-an-install-lands) waits to replace this folder at the next reload. |
| `staged <version> - restart to apply` | The last reload could not replace the folder (a file still held); the next start does. |
| `removed on Reload UI` | Marked for removal: the next reload [deletes the folder and forgets the addon](#what-an-install-keeps-and-a-removal-forgets). |
| `removed on restart` | The last reload could not delete it; the next start does. |
| `failed: <why>` | An update's download or a check refused it; nothing landed; **Update** is back. |
| `[protected: N]` | It asked for N permission entries and can act on your behalf; the tooltip names them. A `<prefix>.*` group counts as one entry. |
| `[net]` | It declared network hosts; the tooltip names every host. |

| Control | Effect |
|---|---|
| A checkbox | Applied on the next reload, never mid-session; a "changes pending" line says so until **Reload UI**. |
| **Enable all** | Turns on every addon not marked `[protected: N]`; a write addon is enabled one at a time, through the consent dialog ticking it raises. |
| **Open addons folder** | Opens `addons/` in your file browser. |
| **Update**, **Remove** | Stand on a row the hub installed (a folder carrying the record [an install writes](#how-an-install-lands)) and no other: a folder you put in `addons/` is yours, never replaced or deleted. **Remove** marks the folder; the row reads `removed on Reload UI` until the next reload deletes it and [forgets the addon](#what-an-install-keeps-and-a-removal-forgets). **Update** stands once a check has found the hub's latest version greater than the installed one (ordered `MAJOR.MINOR.PATCH`, a pre-release below the release it precedes); the press is [an install](#how-an-install-lands). Neither is offered while something already waits on the folder. |
| **Check for updates** | The check also runs each time the tab comes on screen. The line beside it reads `checking`, `no update available`, `1 update available`, the hub's own sentence with its status, the failure that kept the client from reaching it, or `no addon installed from the hub`. |
| **Load out of date AddOns** | Loads every addon marked `outdated (…)` as if its [API version](manifest.md#the-api-version) were current: one stance over the list, kept across restarts, off until ticked, applied at the next reload with a log line per addon naming why it was out of date. Not a permission: an out-of-date write addon is still disabled until enabled, through its consent dialog. |

An addon's own settings are on the **AddOns** tab of **Options**, where an addon that holds [a page](api/client/addon.md#the-page) has a row. An addon that declares a permission key is disabled the first time the client sees it ([permissions](guides/permissions.md)).

## Browse

The hub's front page in the client's own widgets.

| Control | Effect |
|---|---|
| The field | Searches as the hub does (id, name, summary, author, publisher, tags) once the text has been still for a third of a second or on Enter, which also asks again after a hub that did not answer. |
| The order | Relevance (closest match with a search, most downloaded without), most downloaded, recently updated, by name. |
| The tags | A row of chips, **All** then the hub's own; the one pressed filters the list, pressed again for all. |
| The pages | `1–24 of 57 addons` above the cards; **Previous** and **Next**, twenty-four to a page, greyed at either end. A search still out is dropped on any change, so the cards always answer what the controls say now. |
| A card | Its icon (or the first letter of its name), name, latest version, author and `[net]` mark with the hosts as tooltip; its summary cut to the line, with downloads and last update at the end; the status, and **Install** at the right exactly when no folder of that id is in your `addons/`. A press anywhere else opens the page. |
| The page | What the hub shows at `brodgar.io/addons/<id>`: **Back to list** (field, page and scroll kept), the icon, name, version, author, tags as chips (a press filters the list by that tag), the `[net]` mark, the summary, **Install** with the status, **Open at brodgar.io**. Once the hub has answered: the screenshots (‹ and › or a press on the picture steps them), the long description, every published version with API, size, date, downloads and changelog, the permissions in the consent dialog's words, and the hub's About facts (id, author, API, downloads, first published, last update, size, sha256, the owner's links). `Loading the page…` until then, or the hub's own sentence when it did not answer. |
| The list's own word | `Searching…` while the hub is asked, `No addon matches.`, `Nothing published yet.`, or why the hub did not answer. |

| Status on a card and its page | Meaning |
|---|---|
| Nothing, and **Install** | Published, not in your `addons/`. |
| `installed v<version>` | Installed from the hub, at that version. |
| `in addons/ by hand` | A folder of that id the hub did not put there; yours, so nothing is offered. |
| `downloading <n>%` | The package is on its way. |
| `staged <version> - Reload UI to apply` | Checked and unpacked, waiting for the reload that [moves it into place](#how-an-install-lands). |
| `staged <version> - restart to apply` | The last reload could not replace the folder; the next start does. |
| `removed on Reload UI` | Marked for removal on Installed; **Install** is back after the reload. |
| `removed on restart` | The last reload could not delete it; the next start does. |
| `failed: <why>` | The download or a check refused it; **Install** is back. |
| `outdated (…)` | The published version declares an [API version](manifest.md#the-api-version) this client does not implement. **Install** still stands, and **Load out of date AddOns** applies to it. |
| `manifest error (hover)` | Its `api_version` is not a version; the tooltip names the form. |

A card's status is cut to the room the name line leaves it; the tooltip and the page carry the rest. The cards, the screenshot box, the chips, the field and the pickers are the client's own surfaces (a card and the screenshot box are [panels](api/ui/style/surfaces.md#panels), a tree rule naming `@AddonCard` dresses them, every word is a `label`), so a [theme](guides/theming.md) reaches the tab. The hub's pictures are fetched on a worker of their own and kept for the client's life.

## How an install lands

**Install** fetches the package and stages it; nothing changes under a running addon, and the next reload (**Reload UI**, [`:reload`](runtime.md#the-console-commands), the next start) puts it in `addons/`, the moment a ticked checkbox is applied. Every step is a line in the System channel and on the terminal.

| Check | Refused when |
|---|---|
| The digest | The sha256 of the bytes that arrived is not the one the hub advertised; the row names both. |
| The paths | An entry is not under `<id>/`, climbs out with `..`, or is absolute; more than 2000 entries; more than 64 MB unpacked; more than 16 MB as a package. |
| The manifest | The unpacked `manifest.json` does not pass [the same parser](manifest.md) every folder passes at load; the row carries the parser's sentence. |

| Step | Detail |
|---|---|
| Staging | What passes is unpacked under `addons/.staging/<id>/`, and `.registry.json` (the version, the sha256, when) is written last. That file makes a folder the hub's: the Browse row reads `installed v<version>` from it, and a folder without one is yours. Deleting it makes the folder yours. |
| At the reload | After every addon is torn down and before any is loaded, each folder marked for removal moves to `addons/.trash/`, then each staged folder takes its place (the old one to the trash first) and the trash is emptied. Neither folder is scanned. A folder the file system will not release waits: `restart to apply` or `removed on restart`, and the next start moves it before anything holds a file. |
| Installing is not enabling | An addon declaring no permission is enabled like any other; one declaring a key arrives disabled and asks through the consent dialog ([permissions](guides/permissions.md)). |

## What an install keeps and a removal forgets

| Rule | Detail |
|---|---|
| An install replaces the folder whole | Nothing of the old folder survives, a file you edited included; an addon you mean to change is one you keep by hand. Everything the client holds about the addon is untouched, since none of it lives in the folder: enabled state, consented permissions, options, hotkeys, window placements, held action-bar slots. A version asking for more than you approved is disabled and asked again. |
| A removal forgets the addon | With the folder, the reload deletes every row in [the client's file](manifest.md#where-an-addon-lives): options, assigned hotkeys, consented permissions, the enabled-set entry, window placements and held slots, on every character. Removed and installed again, it starts as a first install. [Your addon's file](api/store/README.md) under `savedata/<id>/` stays, yours to delete by hand. A folder deleted by hand leaves those rows dormant; put it back and everything is as it was. |

## The hub

| Rule | Detail |
|---|---|
| What is published | A zip of one addon folder under an id belonging to whoever published it first, at a version the hub orders (`MAJOR.MINOR.PATCH`, an optional pre-release), with a sha256. The name and summary are the hub's listing; the version, permissions and hosts are read from the manifest inside the zip. The package URL carries its sha256, so the bytes fetched are the ones advertised or nothing. |
| Where the client reads | `https://brodgar.io/addons/api`; **Open at brodgar.io** opens that base less `/api`. Another hub: start the client with `-Dhaven.addon.registry=<base>` (`ant -Dregistry=<base> run` passes it), the base up to and including `/api`. A plain `http://` base is taken for a loopback host only (`localhost`, `127.0.0.1`, `::1`); any other is refused with a log line and the client reads `brodgar.io`. |

---

## See Also

- [The manifest](manifest.md) — what a row and a card read: the name, the version, the permissions, the API version.
- [The runtime](runtime.md) — what a reload does with what this manager changed, and the console commands.
- [Permissions](guides/permissions.md) — what enabling a `[protected: N]` addon asks you, and why.
- [Debugging](guides/debugging.md) — reading a row that says `error`, `outdated` or `disabled`.
