# The chat

`ChatUI`, one per HUD (`GameUI.chat`): a stack of **channels**, one of them selected and drawn, a
selector strip of tabs down the left, a quick line the player types into, and the notification popups it
throws up while it is collapsed. A channel is a widget: the server places four of the five kinds, and the
client builds the System log itself.

## Where a thing lives

| Thing | Where |
|---|---|
| The window | `GameUI.chat`, a `ChatUI`. Docked rather than a `Window`: `ChatUI.base` is where the foot sits and `move(Coord)` sets it, `resize(Coord)` re-derives `c` from `base` — so `c` is an **output**, and the height is persisted as the `chatsize` pref (`minh` is the floor) |
| Collapse and expand | `ChatUI.sshow(boolean)`/`expand()`/`targetshow`, animated by the private `Spring` (a `NormAnim`). `Channel.display()` — the `"dsp"` uimsg — is select-then-expand-then-focus |
| The channels | children of `ChatUI` that are `instanceof ChatUI.Channel`. `ChatUI.add(T)` intercepts a `Channel` (position, resize, `chansel.add`, `select(chan, false)`) and `addchild` funnels the server's placement into it |
| The one on screen | `ChatUI.sel`. Written **only** by `select(Channel, boolean)` — the selector's click, the `"sel"` uimsg on a `Channel`, `Channel.select()` and `add` above all go through it — and cleared by `cdestroy` when the selected channel is the one leaving |
| The tabs | the private `ChatUI.Selector`, its `chls` list of `DarkChannel`, and `Selector.add`/`rm`/`up`/`down`. Private, so the child walk above is the only reachable enumeration |
| A channel's lines | `Channel.rmsgs`, a `List<Channel.RenderedMessage>`; `RenderedMessage.msg` is the `Message`, `.idx` its position, `.text()` its raster |
| Appending | `Channel.append(Message, int urgency)` — the one funnel; `append(Message)` and `append(String, Color)` delegate. It stamps `Message.scope`, links the `RenderedMessage`, moves the scrollbar, calls `ChatUI.notify` and then `updurgency` |
| A line | `Channel.Message`: `time` (`Utils.ntime()`), `render(int w)`, `valid(Indir<Text>)`, `scope()`/`kind()`, and the three mouse hooks. What it says is on the subclass — see [the message classes](#the-message-classes) |
| Unread | `Channel.urgency` and `Channel.updurgency(int)`, which re-folds `ChatUI.urgency` as the max over the selector's channels. `Channel.draw` calls `updurgency(0)` — a channel being drawn is a channel being read |
| Saying a line | `EntryChannel.send(String)` → `wdgmsg("msg", text)`, plus its own `history`. `EntryChannel` also owns the per-channel `TextEntry` and `ConsoleHost.kb_histprev`/`kb_histnext` |
| The quick line | the private `ChatUI.QuickLine` (a `ReadLine.Owner`) and `ChatUI.qline`, opened by `ChatUI.globtype` on `kb_quick` while the window is **hidden**, and re-pointed at another channel by `ChatUI.keydown` |
| Popups | `ChatUI.notify(Channel, Message, int)` and the private `Notification`; fired only for `urgency > 0`, with `sfx/hud/chat` |
| Muting | `MultiChat.muted`/`mutewait` with `wdgmsg("muted", id)` and `wdgmsg("mute", id, 0|1)`; `PrivChat.muted` and its own one-argument `wdgmsg("mute", 0|1)` |
| Link parsing | `ChatUI.ChatParser` (a `RichText.Parser`) and `ChatUI.ChatAttribute.HYPERLINK`; `Channel.CharPos` is the hit-test result a click resolves to |

## The channel classes

The class decides the argument shape of the inbound `"msg"`, the name, and the site key — nothing else does.

| Class | `@RName` | Name from | `chanscope()` | Entry line |
|---|---|---|---|---|
| `Log` | — (built by `GameUI`) | its own `name` field | `"chat.system"` | no |
| `SimpleChat` | `schan` | its own `name` field | `"chat"` | yes |
| `MultiChat` | `mchat` | its own `name` field | `"chat"` | yes |
| `PartyChat` (extends `MultiChat`) | `pchat` | the literal `"Party"` | `"chat.party"` | yes |
| `PrivChat` | `pmchat` | `GameUI.buddies.find(other)` | `"chat.private"` | yes |

`Log` is the one the client builds — `GameUI.syslog` — and the console's output is re-pointed at it by
`GameUI.added`. The other four arrive as server-placed widgets, each optionally carrying an icon resource
(`Channel.icon(Indir<Resource>)`).

A `MultiChat` mints a colour per speaker in `fromcolor(int)`, walking `nextcol()` and caching in `pc`;
`PartyChat.uimsg` overrides that with the member's own `Party.Member.col` off `Glob.party`.

## The message classes

`Channel.Message` is abstract and holds only `time` and the render hooks: **what a line says lives on the
subclass**, and which subclass it is is the only thing that says whose line it is. The set is not
enumerated anywhere, and every one but `SimpleMessage` is an inner class of the channel that mints it.

| Class | Where | Fields | Whose line |
|---|---|---|---|
| `Channel.SimpleMessage` | `Channel` | `text`, `col` | nobody's — a line with no sender at all |
| `MultiChat.NamedMessage` | `MultiChat` | `from`, `text`, `col` | the buddy id in `from`, resolved through `GameUI.buddies` by `nm()` |
| `MultiChat.MyMessage` | `MultiChat` | `SimpleMessage`'s | **the player's own**, and `kind()` is `"chat.mine"` |
| `PrivChat.InMessage` | `PrivChat` | `SimpleMessage`'s | the other person's half of a private conversation |
| `PrivChat.OutMessage` | `PrivChat` | `SimpleMessage`'s | **the player's own** half of it |

`MyMessage`, `InMessage` and `OutMessage` are `SimpleMessage` subclasses, so none of them carries a sender
field: the class is the fact. `MyMessage` is the only one that overrides `kind()`, so it is the only line
whose site key differs from its channel's; the rest take `chanscope()`.

## Threading and lifetime

`Channel.append` runs on **the thread that applies the server's update**, holding that session's `ui`
monitor — not the UI thread. So does `ChatUI.addchild`, and so does `select` when the server sends `"sel"`.
Anything that has to run on the UI thread must be queued and drained on a tick.

`rmsgs` mutates under `synchronized(rmsgs)`, which is the list's own monitor rather than the `ui` one, and
every read of the list here takes it. What `Channel.trimunseen` drops is only the **raster** of a message
not drawn for ten seconds (`RenderedMessage.clear`); the `Message` and its place in `rmsgs` stay.

`Widget.remove()` unlinks the child **before** it calls `parent.cdestroy(this)`, so a channel is already out
of the tree by the time `ChatUI.cdestroy` sees it.

## Gotchas

- **The inbound `"msg"` uimsg has three different argument shapes, decided by the channel class** rather
  than by the message name. `SimpleChat.uimsg` is `(line, color?, urgency?)`, with no sender at all;
  `MultiChat.uimsg` (and `PartyChat`, which inserts a gob id as its second argument) is `(from, line)`
  where **`from == null` marks the player's own line** — a `MyMessage` rather than a `NamedMessage`, and a
  common outcome rather than an edge case; `PrivChat.uimsg` is `(direction, line)` where `direction` is the
  string `"in"` or `"out"`, never a sender id. Reading the args by position without knowing the class
  misreads one of the three. Reading the resulting `Message` **subclass** avoids the question entirely.
- **`PrivChat.name()` walks to the HUD.** It resolves the other person through
  `getparent(GameUI.class).buddies.find(other)`, so it answers `"???"` until this character's roster carries
  them — and it is a null dereference on a channel that has left the tree, which is exactly the state a
  removal hands you. Guard on `getparent(GameUI.class) != null` before calling it off the drawn path.
- **`Message.time` is `Utils.ntime()`** — epoch **seconds** as a `double`, not milliseconds and not a frame
  clock. Anything that prints it through a language whose default number formatting is scientific notation
  has to format it as an integer.
- **A line's place in `rmsgs` is its index and it never moves.** `RenderedMessage.idx` is assigned inside
  `append`'s `synchronized(rmsgs)` block as `rmsgs.size()`, and nothing else writes it or reorders the list,
  so `(Channel, idx)` addresses one line for the life of the channel. Reading the index back **outside** that
  block races the next append; read it inside.
- **`rmsgs` is never trimmed.** A long login's scrollback grows without bound, so an index into it is stable
  for the life of the channel and a wholesale copy of it is not cheap.
- **`ChatUI.add` selects the channel it just added**, so one server placement is both an arrival and a
  selection change, in that order.
- **`ChatUI.c` does not round-trip.** `resize` recomputes it from `base` whenever the window is visible, so
  writing `c` moves the chat until the next resize and then does not. `move(Coord)` is the setter.
- **`Selector` is private**, and so is `DarkChannel`. The tab order is not reachable from outside the class;
  the child list is, and `ChatUI.add`/`cdestroy` keep the two in step.

## See also

- [the widget system](widgets.md) — `Widget.add` vs `addchild`, and the destroy seams `cdestroy` sits on
- [text and fonts](text-and-fonts.md) — `RichText.Foundry`, which every chat line is rendered through
- [the console](console.md) — whose output is re-pointed at the System log by `GameUI.added`
- [state roots](state.md) — `Glob.party`, which `PartyChat` colours its speakers from
