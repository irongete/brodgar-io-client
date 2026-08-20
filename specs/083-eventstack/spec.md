# 083 — EventStack: the client's traffic, as it happens

## What and why

Everything needed to watch what the client does now exists, and nothing shows it. The two streams
take `"*"`, the bus is a closed catalogue of 31 keys, and a widget watch takes any selector — so an
addon can hold every door at once, and none of the bundled three does. `streams.md` says the inbound
wildcard is the one to reach for first, *because the name you want is usually a window of watching
away*. **That window is what this ships.**

EventStack is a live log in a table: one line per thing the client did, newest last, with two filters
that **start empty and fill themselves** as each name arrives for the first time — which is the only
shape that works, because a message name is protocol the server can introduce and the list cannot be
written in advance. It is the third tool, dormant like the other two: installed, enabled, and reading
nothing until `:eventstack` puts its window up.

Four sources, each a switch in the window: every message **out** (`action():on("*")`), every update
**in** (`message():on("*")`), the bus catalogue, and widgets **appearing and disappearing**
(`s:ui():on("*", …)`). `Update` is never among them — it fires every frame and says nothing.

## Acceptance criteria

1. All four sources run at once and each yields records carrying **what it was** (the source), **its
   name** (the message name, the bus key, or the widget's class), and **what it was about** (the
   sending or receiving widget, or the payload's own description).
2. A name set that begins **empty** gains exactly one entry the first time a name arrives and never a
   second, however many times that name repeats — the filter's whole behaviour.
3. Rewriting a dropdown's rows **keeps the user's pick**. `dropdown:rows(t)` replaces the set and
   clears the selection, so a filter that gains a row while a pick is live must put that pick back;
   without it the user's filter falls off on the next unseen name.
4. The log is a **bounded ring**: past its cap it keeps the newest N in arrival order and drops the
   oldest, so a session left running does not grow without end.
5. `table:columns(t)`'s `of(row)` runs once per row **per `:rows(t)` write** — a hundred rows cost a
   hundred calls — which is why the log writes its rows at most once a frame rather than once an
   event.

## Out of scope

- **Doing anything to the traffic.** EventStack observes: it never calls `preventDefault`, `rewrite`,
  `resend` or `send`. On the inbound stream a wildcard that cancels stops the client outright, and a
  log is the last place to put that. *The other half would be a breakpoint that holds a message and
  lets you edit its arguments before it goes — a different tool, needing an editor for an argument
  list.*
- **Free-text search over the log.** The filters are the two the surface names — by source and by
  name. Matching over an argument list is a second surface with its own question.
- **Surviving a reload.** The ring lives in memory and a `:reload` starts a fresh one. What persists
  is the window's place and which sources are on.
- **`clickpath`.** It is tracked under `addons/` and named nowhere in `docs/`, and it is not a
  bundled addon: it belongs to the maintainer. It stays out of every page this feature writes.

## Docs impact

Written: `docs/addons/examples.md` (the row, the section, and the prose counts that break) ·
`docs/addons/README.md` · `docs/addons/guides/debugging.md`.

Derived impact set — `grep -rn "bundled\|ship with the client\|Three addons\|two tools\|worked
example" docs/` and `grep -rln "widgetstack\|\`profiler\`\|session-manager" docs/`:

| Page | Verdict |
|---|---|
| `examples.md:3`, `:15`, `:17` | **revise** — "Three addons ship", "The two tools", "the two worked examples": counts in prose duplicating the table, which `DOCUMENTATION.md` §7 bans and this feature falsifies. The bundled set becomes **three tools** — `profiler`, `eventstack`, `widgetstack` — beside `session-manager`, which the page already holds apart as a surface of the client's own rather than a tool |
| `README.md:33` | **revise** — the index row enumerates the three in prose |
| `guides/debugging.md:121` | **revise** — it sends the reader to the bundled tools for the questions they answer, and this one answers a question none of them did |
| `docs/README.md:20`, `getting-started.md:225`, `runtime.md:233`, `guides/README.md:25`, `guides/hotkeys-and-commands.md:65` | **discharge** — link text, no enumeration |
| `api/client/profiling/README.md`, `api/references.md`, `api/ui/custom.md`, `api/ui/selectors.md` | **discharge** — each names one tool for its own subject; none claims a set |

**No `src/` file is read and no `docs/client/` page is written.** This feature is Lua over a surface
that already ships, so the reference pages are the whole contract.

## Context files

- `docs/addons/api/event/README.md` — 1
- `docs/addons/api/event/bus.md` — 1
- `docs/addons/api/event/streams.md` — 1
- `docs/addons/api/ui/lists.md` — 1, 2
- `docs/addons/api/ui/custom.md` — 1
- `docs/addons/api/ui/widget.md` — 1, 2
- `docs/addons/api/ui/replace.md` — 1
- `docs/addons/api/ui/controls/README.md` — 2
- `docs/addons/api/session.md` — 1
- `docs/addons/api/slash.md` — 1
- `docs/addons/api/store.md` — 1
- `docs/addons/api/timer.md` — 1, 2
- `docs/addons/api/log.md` — 1, 2
- `docs/addons/runtime.md` — 1
- `docs/addons/examples.md` — 2
- `docs/addons/README.md` — 2
- `docs/addons/guides/debugging.md` — 2
- `DOCUMENTATION.md` — 2
- `addons/eventstack/main.lua` — 2, the addon 083.1 wrote, which the filters go into
