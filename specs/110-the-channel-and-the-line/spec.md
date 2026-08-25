# 110 — The channel and the line

## What & why

The client's chat is reachable today only as a **widget**: it can be hidden, dragged, restyled and its
quick line intercepted. Nothing reads a channel, a line, or who said it, and nothing says one. So the
one part of the HUD a player watches most cannot be read, filtered, logged, piped anywhere or
replaced.

This feature ships the missing surface — `s:chat()`, its channels, their lines and one write that says
a line — plus the wrapping and measurement any surface needs to lay a line out. What gets built on top
of it is not this feature's business: the point is that a chat addon of **any** shape becomes possible,
and the reference page's own examples are what show the surface working.

The vocabulary is the client's own and already published. A channel and a line each answer `:kind()`
with the very [site key](../../docs/addons/api/ui/style/chat.md) its colour resolves at — `chat`,
`chat.system`, `chat.mine`, `chat.private`, `chat.party` — so what a theme names and what an addon
reads are one closed set of words, and `:speaker()` is the word `chat.speaker` already uses.

## Acceptance criteria

1. `s:chat()` **is** the collection of the channels one character holds — the shape `s:party()` and
   `s:actionbar()` have, where the section is named for the subsystem and its members for their own
   kind: `:list(filter)`, `:count`, `:find`, plus `:selected()` / `:selected(ch)` for the one on
   screen, the distinguished member `s:party():leader()` and `s:actionbar():page(n)` are.
2. It has **no `:get`** — a channel has no key the client addresses — and the refusal names both
   doors that exist: `:find(needle)` and `:list()[n]`.
3. A channel answers `:name()`, `:kind()`, `:urgency()`, `:exists()`, `:info()`. `:urgency()` is the
   unread level the client keeps, `0` when nothing is unread — the same level `chat.urgent`'s palette
   is read by, one entry per level.
4. `ch:message()` is that channel's lines, 1-based and oldest first: `:list`, `:count`, `:find`, and
   `:get(i)`, which is a real address because the scrollback is never trimmed.
5. A line answers `:text()`, `:kind()`, `:color()`, `:time()`, `:speaker()`, `:mine()`, `:channel()`,
   `:exists()`, `:info()`. `:speaker()` is a [Kin](../../docs/addons/api/kin.md) where the protocol
   named one, and `nil` in the three channel shapes that carry no sender at all.
6. `ch:send(text)` says a line, protected by `chat.send`, and refuses on a channel with no entry line
   naming the kinds that take one.
7. `ChannelAdded`, `ChannelRemoved`, `ChannelSelected` and `MessageAdded` fire on the bus, each
   carrying its singular subject and the `Session` last. `ChannelSelected` is `SessionSelected`'s
   opposite number and is spelled after it.
8. `g:text` and `g:atext` take a `width` in their `opts`, and **wrap** at it; `hafen.ui():measure(s,
   opts)` answers `{w =, h =}` for the box the same string and the same `opts` would occupy. The two
   read one markup, so a measured wrap and a drawn one agree.

## Out of scope

The boundary is **reading a line and saying one**. Past it:

- **Every surface built on this.** A replacement chat window is the reason the surface exists and is
  its own addon and its own work; nothing here is shaped by one, and no addon ships with it.
- **Muting** a private conversation or a speaker (`wdgmsg("mute")`) — a privacy write, and it belongs
  with [kin](../../docs/addons/api/kin.md), whose vocabulary already names a person.
- **What the client's own log does to a line**: selecting text, copying it, following a URL in it.
  Those are gestures of a surface, and this feature ships none; the read half is whole without them,
  because `:text()` carries the markup the server sent.
- The **notification popups** the collapsed chat throws up, and the chat's own hotkeys.

## Docs impact

```
grep -rln "chat\|Chat" docs/
```

27 pages. Revised: `api/README.md` (the new page's row), `api/session.md` (the new section),
`api/event/bus.md` (four keys, and the count of the per-character group), `api/ui/drawing.md` (`width`
and `:measure`), `api/ui/style/chat.md` (the kinds are now read as well as styled),
`guides/permissions.md` (`chat.send`), `api/types.md` → split, `client/services.md` → its chat row
moves out, `client/chat.md` → new. Created: `api/chat.md`.

Discharged, each already true and unaffected: `api/locale.md`, `api/map/icons.md`, `api/store.md`,
`api/ui/controls/interactive.md`, `api/ui/edit.md`, `api/ui/native.md`, `api/ui/selectors.md`,
`api/ui/style/{README,geometry,keys,surfaces,text}.md`, `guides/{saved-data,theming,translating}.md`,
`client/{README,gameui-windows,glossary,multi-session,text-and-fonts,ui-lists,widget-draw,widget-input,widgets}.md`.

## Context files

- `src/haven/ChatUI.java` — 3
- `docs/client/chat.md` — 3, the map of that file: the one funnel a line arrives through, the three
  argument shapes the `"msg"` uimsg wears, and what is never trimmed
- `src/io/brodgar/addon/LuaChannel.java` — 3, the Channel object `ch:message()` hangs off, and the
  intern-on-the-widget + no-pin shape a Message interned on `(Channel, idx)` follows
- `src/io/brodgar/addon/ChatApi.java` — 3, the collection, and the `CH` spelling every message quotes
- `docs/addons/api/chat.md` — 3, the page the lines are written onto
- `src/io/brodgar/addon/AddonManager.java` — 3, and its per-session chat queue, which `MessageAdded` joins
- `src/io/brodgar/addon/LuaKin.java` — 3
- `docs/addons/api/types/ui.md` — 3, where `Message` is filed; `Channel` is already on it
- `src/io/brodgar/addon/LuaCollection.java` — 3
- `src/io/brodgar/addon/LuaGOut.java`, `src/io/brodgar/addon/UiApi.java` — 4
- `src/haven/Text.java`, `src/haven/RichText.java` — 4
- `DOCUMENTATION.md` — 3, 4
