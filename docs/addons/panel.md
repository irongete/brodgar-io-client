# The AddOns manager

**AddOns**, on the game menu that `Ctrl+O` opens, is where your addons are switched on and off, and where
you look for new ones. Its **Installed** tab lists every addon the client found in `addons/`, one row each,
with its live status and the box that enables it, and updates or removes the ones the hub installed; its
**Browse** tab is the front page of `brodgar.io/addons`, the hub, drawn in the client: a search, the tags,
one card per addon, and each addon's own page behind its card, with **Install** on both. The client reads
the hub anonymously: it never signs in, and nothing about you or your character travels with the request.

## Installed

Every addon the client discovered, sorted by id, one row each: a checkbox, the name, version and author, and
a live status. The description is the row's tooltip. With nothing in `addons/` the list says
`No addons installed` in its middle, and says it exactly while that is so.

| Row shows | Meaning |
|---|---|
| `loaded v<version>` | running |
| `disabled` | switched off, and not loaded |
| `not loaded` | enabled, but not running — usually an enable that no reload has applied yet |
| `error: …` | its manifest or its Lua failed; the message says how |
| `manifest error (hover)` | the manifest itself does not parse; the tooltip opens with the reason |
| `outdated (…)` | not run: the [API version](manifest.md#the-api-version) it declares is not one this client implements — `outdated (API 9.0, client 1.0)` — or it declares none — `outdated (no api_version, client 1.0)`. The tooltip says which |
| `auto-disabled (…)` | the [CPU budget](runtime.md#budgets-and-the-watchdog) or a [fatal failure](runtime.md#when-a-failure-is-fatal) stopped it |
| `update <version>` | the hub publishes a newer version than the one installed here; **Update** stands beside it |
| `downloading <n>%` | an update's package is on its way, against the size the hub advertised |
| `staged <version> - Reload UI to apply` | a version [installed from the hub](#how-an-install-lands) is waiting to replace this folder at the next reload |
| `staged <version> - restart to apply` | the last reload could not replace the folder — a file of it is still held; the next start does |
| `removed on Reload UI` | marked for removal: the next reload [deletes the folder and forgets the addon](#what-an-install-keeps-and-a-removal-forgets) |
| `removed on restart` | the last reload could not delete it — a file of it is still held; the next start does |
| `failed: <why>` | an update's download or a check refused it, and nothing landed; the tooltip carries the whole sentence, and **Update** is back for another try |
| `[protected: N]` | it asked for N permission entries — it can act on your behalf; the tooltip names them |
| `[net]` | it declared network hosts; the tooltip names every host it asks to reach |

The count is the entries the addon wrote, so a `<prefix>.*` group counts as the one line you read rather
than as the keys it covers.

An addon's own settings are not on this tab: an addon that holds [a page](api/client/addon.md#the-page)
has a row of its own on the **AddOns** tab of **Options**, beside the client's own settings, and that page
is where its options are edited.

**A checkbox is applied on the next reload**, never mid-session: ticking one and pressing **Reload UI** is
the whole gesture, and a "changes pending" line says so until you do. **Enable all** turns on every addon
that is not marked `[protected: N]`; a write addon is only ever enabled one at a time, through the consent
dialog that ticking it raises. **Open addons folder** opens `addons/` in your file browser.

**Update** and **Remove** stand on a row the hub installed — one whose folder carries the record
[an install writes](#how-an-install-lands) — and on no other: a folder you put in `addons/` yourself is
yours, and the client never replaces or deletes it. **Remove** marks the folder, and the next reload deletes
it and [forgets the addon](#what-an-install-keeps-and-a-removal-forgets); the row reads
`removed on Reload UI` until then, on both tabs, and is gone after. **Update** stands only once a check has
found the hub's latest version greater than the installed one, ordered the hub's way
(`MAJOR.MINOR.PATCH`, a pre-release below the release it precedes); the row reads `update <version>` until
you press it, and the press is [an install](#how-an-install-lands): the row runs `downloading <n>%`, then
`staged <version> - Reload UI to apply`, and the reload replaces the folder whole. Both are applied at the
reload, like a checkbox, and neither is offered while something already waits on the folder.

The check runs each time the tab comes on screen, and again on **Check for updates**. The line beside the
button is the check's own word: `checking` while the hub is asked, `no update available`, how many are —
`1 update available` — or, when the hub did not answer, why: its own sentence with the status it sent, or
the failure that kept the client from reaching it. While no row was installed from the hub nothing is asked
of it, and the line reads `no addon installed from the hub`.

**Load out of date AddOns**, the box under the list, loads every addon the tab marks `outdated (…)` as if
its [API version](manifest.md#the-api-version) were current — one stance over the whole list, kept across
restarts, off until you tick it. It is applied as a row's checkbox is: ticking it marks changes pending, and
the next reload runs those addons, each with a line in the log naming it and why it was out of date. Their
rows then read `loaded v…`, and each tooltip still opens with what the addon declared. Off again, the next
reload leaves them out. It is not a permission: a write addon that is out of date is still disabled until
you enable it, and enabling it still raises its consent dialog.

An addon that declares a permission key is disabled the first time the client sees it, so a write addon
never runs because it was merely installed. After that its state is yours — see
[permissions](guides/permissions.md).

## Browse

The hub's front page, in the client's own widgets. The field searches as the hub's own does: a name, an
author, a tag, part of an id — it matches the id, the name, the summary, the author, the publisher and the
tags — once the text has been still for a third of a second, or the moment you press Enter, which also
asks again after a hub that did not answer. Beside it the **order**: relevance (the closest match first
with a search, the most downloaded without), most downloaded, recently updated, or by name. Under it the
**tags** as a row of chips — **All**, then the hub's own — and the one pressed filters the list to addons
filed under it; press it again for all. The tab shows everything the hub publishes the moment it first
comes on screen, and again on every change of the field, the tag, the order or the page: a search still out
is dropped, so the cards are always the answer to what the controls say now. Above the cards a line says
which of how many — `1–24 of 57 addons` — and **Previous** and **Next** turn the pages, twenty-four to a
page, greyed at either end.

**A card** is one published addon as the hub's front page shows it, on two lines: its icon — or the first
letter of its name on a plain field, where it has none — then its name with its latest version and its
author, and the `[net]` mark an Installed row carries, with the hosts as its tooltip; under them its summary,
cut to the line, and at the line's end how many times it was downloaded and when it was last updated. What
it asks for is on its page, as on the hub. The end of the name line is this client's word on it, the status,
and **Install** stands at the card's right exactly when there is no folder of that id in your `addons/`.
**A press anywhere else on the card opens the addon's page.**

**The page** is what the hub shows at `brodgar.io/addons/<id>`: **Back to list** at the top — the list
comes back exactly as you left it, field, page and scroll — over the icon, the name, the version, the
author, the tags as chips (a press filters the list by that tag and takes you back to it), the `[net]`
mark, the summary, then **Install** with the status beside it and **Open at brodgar.io**, which opens the same page
in your browser. Under the head, once the hub has answered: the **screenshots**, one at a time in a box
with ‹ and › to step through them (a press on the picture steps too), the long **description** as text,
every published **version** — its API, size, date and downloads, with its changelog under it — the
**permissions** it asks for, each in the words the consent dialog uses, and the hub's own **About** facts:
id, author, API, downloads, first published, last update, size, sha256, and the links the owner filed.
`Loading the page…` stands under the head until the rest is in, and the hub's own sentence when it did not
answer. **Install** here is the same press as on the card: the latest version, into the same download and
the same stage.

The status, on the card and on the page both, is a fact about **this** client:

| It shows | Meaning |
|---|---|
| nothing, and **Install** | published at the hub, and not in your `addons/` |
| `installed v<version>` | installed from the hub, at that version |
| `in addons/ by hand` | a folder of that id is in your `addons/` that the hub did not put there. It is yours: the client never replaces a folder you put there, so nothing is offered |
| `downloading <n>%` | the package is on its way, against the size the hub advertised |
| `staged <version> - Reload UI to apply` | the package is checked and unpacked, waiting for the reload that [moves it into place](#how-an-install-lands) |
| `staged <version> - restart to apply` | the last reload could not replace the folder — a file of it is still held; the next start does |
| `removed on Reload UI` | the folder is marked for removal on the Installed tab; the next reload deletes it, and **Install** is back after |
| `removed on restart` | the last reload could not delete it — a file of it is still held; the next start does |
| `failed: <why>` | the download or a check refused it, and nothing landed; the tooltip carries the whole sentence, and **Install** is back for another try |
| `outdated (…)` | the published version declares an [API version](manifest.md#the-api-version) this client does not implement; the name's tooltip opens with the sentence. **Install** still stands: the row then reads `outdated (…)` on Installed, where **Load out of date AddOns** applies to it |
| `manifest error (hover)` | its `api_version` is not a version at all; the name's tooltip names the form |

On a card the status is cut to the room the name line leaves it, with an ellipsis; a failure's tooltip
carries the whole sentence, and the page has the room for the rest.

The list's own word, in its middle while it holds no cards: `Searching…` while the hub is asked, `No addon
matches.` when it answered with nothing for the field and the tag, `Nothing published yet.` when the hub has
nothing at all, and — when it did not answer — why: the hub's own sentence with the status it sent, or the
failure that kept the client from reaching it.

The cards, the page's screenshot box, the chips, the field and the pickers are the client's own surfaces —
a card and the screenshot box are [panels](api/ui/style/surfaces.md#panels) that paint their own surface, so
a sheet's `panel` rule or a tree rule naming `@AddonCard` dresses them, and every word is a `label` — so a
[theme](guides/theming.md) reaches the tab as it reaches the rest of the client. The pictures the hub serves
are fetched on a worker of their own and kept for the client's life, so a page of icons never holds a search
or a download behind it, and a card shown again asks the hub for nothing.

## How an install lands

**Install** fetches the package and stages it; **nothing changes under a running addon**, and the next
reload — **Reload UI**, [`:reload`](runtime.md#the-console-commands), or the next start — is what puts it
in `addons/`. That is the same moment a ticked checkbox is applied at, and the same "changes pending" line
says so until you reload. Every step is a line in the log, in the **System** channel and on the terminal.

The package is checked before anything of it is kept, and a check that fails leaves nothing behind:

| Check | Refused when |
|---|---|
| the digest | the sha256 of the bytes that arrived is not the one the hub advertised — the row names both |
| the paths | an entry is not under `<id>/`, climbs out with `..`, or is absolute; more than 2000 entries; more than 64 MB unpacked; more than 16 MB as a package |
| the manifest | the unpacked `manifest.json` does not pass [the same parser](manifest.md) every folder in `addons/` passes at load — the row carries the parser's own sentence |

What passes is unpacked under `addons/.staging/<id>/`, and a file named `.registry.json` is written
into it last: the version and the sha256 the hub named, and when. That file is what makes a folder the
hub's — the Browse row reads `installed v<version>` from it, and a folder without one is yours, put there
by hand. Deleting it makes the folder yours in the same sense: the client never replaces it again.

At the reload, after every addon is torn down and before any is loaded, each folder marked for removal is
moved to `addons/.trash/`, then each staged folder takes its place: the folder it replaces is moved to the
trash first, the staged one takes its name, and the trash is emptied. Both folders are the client's own
bookkeeping — the addon scan lists neither — and a folder the file system will not let go of, because a
file in it is still open, waits: the row reads `restart to apply` or `removed on restart`, and the next
start moves it before anything can hold a file.

**Installing is not enabling.** A new addon that declares no permission is enabled like any other; one
that declares a permission key arrives disabled and asks through the same consent dialog as a folder you
unpacked yourself — see [permissions](guides/permissions.md).

## What an install keeps and a removal forgets

A staged folder replaces the one of the same id **whole**: nothing of the old folder survives inside the
new one, so a file you edited in it is gone with it — an addon you mean to change is one you keep by
hand. Everything the client holds about the addon is untouched by an install, because none of it lives in
the folder: whether the addon is enabled, the permissions you consented to, its options, its hotkeys, where
you put its windows and which action-bar slots hold its entries all stand, so an update comes back as you
had it. A version that asks for more than you approved is disabled and asked again, as any manifest that
grows is.

**A removal forgets the addon.** With the folder, the reload deletes every row the client keeps about it
in [its own file](manifest.md#where-an-addon-lives): the addon's options and the keys you assigned to its
hotkeys, the permissions you consented to, its entry in the enabled set, where you put its windows and the
slots its entries held — on every character. An addon you remove and install again starts as a first
install does: it is asked for its permissions again, and its options are on their defaults. What stays is
[your addon's file](api/store/README.md) under `savedata/<id>/`: that is the addon's own data, kept by it
on your behalf, and yours to delete by hand. A folder you delete by hand instead of removing it leaves
those rows where they are, dormant — put the folder back and everything is as it was.

## The hub

`brodgar.io/addons` is where addons for this client are published. A published addon is a zip of one
addon folder — the same folder the client reads from `addons/` — under an id that belongs to whoever
published it first, at a version the hub orders (`MAJOR.MINOR.PATCH`, an optional pre-release), with a
sha256 of the package. The name and the summary a row shows are the hub's listing, written by whoever
publishes it; the version, the permissions and the hosts are read from the manifest inside the zip,
never from a form, so what you read on Browse is what the addon itself declares. The package's URL
carries its sha256, so the bytes the client fetches are the ones the row advertised or nothing: a
version published again with another package is another URL.

The client reads the hub at `https://brodgar.io/addons/api`, and **Open at brodgar.io** opens the page at
that base less its `/api`. To read another — a hub you run while developing — start the client with
`-Dhaven.addon.registry=<base>`, the base URL up to and including `/api`; `ant -Dregistry=<base> run`
passes it. A plain `http://` base is taken for a **loopback host
only** (`localhost`, `127.0.0.1`, `::1`); any other value is refused with a line in the log naming it and
the reason, and the client reads `brodgar.io` as if nothing had been set.

## See also

- [the manifest](manifest.md) — what a row and a card read: the name, the version, the permissions, the API version
- [the runtime](runtime.md) — what a reload does with what this manager changed, and the console commands
- [permissions](guides/permissions.md) — what enabling a `[protected: N]` addon asks you, and why
- [debugging](guides/debugging.md) — reading a row that says `error`, `outdated` or `disabled`
