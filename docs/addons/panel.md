# The AddOns manager

**AddOns**, on the game menu that `Ctrl+O` opens, is where your addons are switched on and off, and where
you look for new ones. Its **Installed** tab lists every addon the client found in `addons/`, one row each,
with its live status and the box that enables it; its **Browse** tab searches the addons published at
`brodgar.io/addons`, the hub, and shows one row per match. The client reads the hub anonymously: it never
signs in, and nothing about you or your character travels with the request.

## Installed

Every addon the client discovered, sorted by id, one row each: a checkbox, the name, version and author, and
a live status. The description is the row's tooltip.

| Row shows | Meaning |
|---|---|
| `loaded v<version>` | running |
| `disabled` | switched off, and not loaded |
| `not loaded` | enabled, but not running — usually an enable that no reload has applied yet |
| `error: …` | its manifest or its Lua failed; the message says how |
| `manifest error (hover)` | the manifest itself does not parse; the tooltip opens with the reason |
| `outdated (…)` | not run: the [API version](manifest.md#the-api-version) it declares is not one this client implements — `outdated (API 9.0, client 1.0)` — or it declares none — `outdated (no api_version, client 1.0)`. The tooltip says which |
| `auto-disabled (…)` | the [CPU budget](runtime.md#budgets-and-the-watchdog) or a [fatal failure](runtime.md#when-a-failure-is-fatal) stopped it |
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

Type in the field: a name, an author, a tag, part of an id. The search runs once the text has been still
for a third of a second, or the moment you press Enter, and it is the hub's own: it matches the id, the
name, the summary, the author, the publisher and the tags, and answers at most fifty addons, the closest
match first. A search that is still out is dropped when you type again, so the rows are always the answer
to what the field says now.

Each row is one published addon: its name, its latest version, who publishes it, and the same
`[protected: N]` and `[net]` markers an Installed row carries. Its tooltip is the hub's summary, then the
permissions it declares and the hosts it asks to reach — read them here, before it is on your disk. The
status at the right is a fact about **this** client:

| Row shows | Meaning |
|---|---|
| nothing | published at the hub, and not in your `addons/` |
| `in addons/ by hand` | a folder of that id is in your `addons/`. It is yours: the client never replaces a folder you put there |
| `outdated (…)` | the published version declares an [API version](manifest.md#the-api-version) this client does not implement; the tooltip opens with the sentence |
| `manifest error (hover)` | its `api_version` is not a version at all; the tooltip names the form |

The line under the list is the tab's own word on the search: `searching` while the hub is asked,
`no addon matches` when it answered with nothing, and — when it did not answer — why: the hub's own
sentence with the status it sent, or the failure that kept the client from reaching it. An empty field is
no search: the rows go and the line clears.

## The hub

`brodgar.io/addons` is where addons for this client are published. A published addon is a zip of one
addon folder — the same folder the client reads from `addons/` — under an id that belongs to whoever
published it first, at a version the hub orders (`MAJOR.MINOR.PATCH`, an optional pre-release), with a
sha256 of the package. What a row shows is read from the manifest inside the zip, never from a form, so
the permissions and hosts you read on Browse are the ones the addon itself declares.

The client reads the hub at `https://brodgar.io/addons/api`. To read another — a hub you run while
developing — start the client with `-Dhaven.addon.registry=<base>`, the base URL up to and including
`/api`; `ant -Dregistry=<base> run` passes it. A plain `http://` base is taken for a **loopback host
only** (`localhost`, `127.0.0.1`, `::1`); any other value is refused with a line in the log naming it and
the reason, and the client reads `brodgar.io` as if nothing had been set.

## See also

- [the manifest](manifest.md) — what a row reads: the name, the version, the permissions, the API version
- [the runtime](runtime.md) — what a reload does with what this manager changed, and the console commands
- [permissions](guides/permissions.md) — what enabling a `[protected: N]` addon asks you, and why
- [debugging](guides/debugging.md) — reading a row that says `error`, `outdated` or `disabled`
