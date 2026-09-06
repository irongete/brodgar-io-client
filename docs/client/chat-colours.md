# The chat's colours

> One colour per kind of line, each a literal at the place the line is built.

There is no table of them anywhere: a chat line's colour is a literal at the place the line is **built**,
and they live in three classes. Nothing distinguishes the kinds at the render — every one of them is a
`SimpleMessage` or a `NamedMessage` carrying a `Color` — so the only way to tell a System line from a
private one is **which channel it was appended to**, or which `Message` subclass built it.

| Kind | Where the colour is | Value |
|---|---|---|
| System, informational | `UI.Notice.color()`'s default, via `GameUI.msg` → `syslog.append` | `Color.WHITE` |
| System, error | `UI.ErrorMessage.color` (`defcolor()` overrides `SimpleMessage`'s) | `(192, 0, 0)` |
| Your own line | `ChatUI.MultiChat.MyMessage` ctor | `(192, 192, 255)` |
| Private, received | `ChatUI.PrivChat.InMessage` ctor | `(255, 128, 128)` |
| Private, sent | `ChatUI.PrivChat.OutMessage` ctor | `(128, 128, 255)` |
| A speaker | `ChatUI.MultiChat.nextcol` — `Color.HSBtoRGB(colseq = (colseq + √2) % 1, 0.5f, 1.0f)`, memoised per sender id in `pc` | walked |
| A party speaker | `ChatUI.PartyChat.uimsg` — `Party.Member.col` blended with white | server-assigned |
| Unread urgency | `ChatUI.urgcols` (the toggle button's glow, `GameUI`) and `ChatUI.Selector.uc` (the tab, `namedeco`) | two arrays, both index-0-is-not-a-level |

## Gotchas

- ⚠️ **The two urgency arrays are not the same array.** `urgcols[0]` is `null` — no glow — while `uc[0]` is
  `(80, 40, 0)`, the resting tab colour. Levels 1–3 agree. Anything routing "the urgency colour" has to take
  each site's own array as the fallback rather than assume one.
- ⚠️ **`MyMessage` is `MultiChat`'s, so the Party channel has it too** (`PartyChat extends MultiChat`): your
  own party line is `(192, 192, 255)`, not a party colour. And `PrivChat`'s error path builds a bare
  `SimpleMessage(err, Color.RED)` — a third red, unrelated to `ErrorMessage`'s.

## See also

- [the chat](chat.md) — `ChatUI` and its channels, where each of these lines arrives
- [text and fonts](text-and-fonts.md) — the `RichText.Foundry` every one of them is rendered through
