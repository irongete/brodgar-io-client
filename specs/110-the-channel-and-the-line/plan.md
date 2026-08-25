# 110 — Plan

## Approach

**The section is the collection.** `s:chat()` is minted once per session, like `s:study()`, and resolves
that character's `GameUI.chat`. Its members are the `ChatUI` children that are `instanceof
ChatUI.Channel`; `:selected()` reads `ChatUI.sel` and `:selected(ch)` calls `ChatUI.select(Channel)`.
Channels intern on the `Channel` widget's identity; a line interns on `(Channel, idx)`, which is a
stable pair because `Channel.rmsgs` is only ever appended to.

**The kind is already there.** `Channel.chanscope()` and `Message.scope()` were cut as `// addon:`
seams by the theming work, and they are exactly the closed set `ui/style/chat.md` publishes. `:kind()`
returns them unchanged, so a rule and a read name one thing.

**Three readers need a core edit.** `Message` has no common accessor for the text, the colour or the
speaker: `SimpleMessage` carries `text`/`col`, `MultiChat.NamedMessage` carries `text`/`col`/`from`,
and `MyMessage`, `InMessage` and `OutMessage` are `SimpleMessage` subclasses. Add `text()`, `color()`
and `speaker()` to `ChatUI.Message` returning null, overridden in the two that hold the fields —
six lines, `// addon:` tagged, and total over any subclass upstream adds later. `:mine()` is the
`MyMessage`/`OutMessage` test; `:speaker()` hands `NamedMessage.from` to `s:kin():get(id)`.

**Four seams, four queues.** `Channel.append` is the one funnel every line goes through,
`ChatUI.add`/`cdestroy` the one every channel does, and `ChatUI.select(Channel, boolean)` the one
selection does. `append` runs on the thread that applies the server update, so all four enqueue and
`AddonManager`'s tick drains them, the shape `drainResizedWidgets` already has.

**Wrapping is the client's, not ours.** `RichText.Foundry.render(String, int)` already wraps at a
width and is what the chat itself renders through. `g:text`/`g:atext` gain `width` in the `opts` they
already take, and `LuaGOut`'s render helper is lifted so `hafen.ui():measure(s, opts)` answers the box
of the very same call. One markup, one wrap, one cache. It is here because a line whose length nothing
can ask about cannot be laid out at all, which would leave the read half of this surface unusable by
the surfaces it exists for.

**Nothing is built on it here.** The reference page carries the examples; no addon ships with this
feature.

## Files to create

| File | What |
|---|---|
| `src/io/brodgar/addon/ChatApi.java` | the section, and the channel collection it is |
| `src/io/brodgar/addon/LuaChannel.java` | one channel |
| `src/io/brodgar/addon/LuaMessage.java` | one line |
| `docs/addons/api/chat.md` | the reference page |
| `docs/client/chat.md` | the `ChatUI` map — the chat row moves here out of `services.md` |
| `docs/addons/api/types/` | the snapshot catalogue, split by subject, with a `README.md` hub |
| `addons/110-the-channel-and-the-line.{1..4}/` | one suite per task |

## Files to modify

| File | What |
|---|---|
| `src/haven/ChatUI.java` | `Message.text()`/`color()`/`speaker()`; the four event seams |
| `src/io/brodgar/addon/LuaSession.java` | the `chat` section |
| `src/io/brodgar/addon/Permission.java` | `CHAT_SEND` |
| `src/io/brodgar/addon/AddonManager.java` | four queues and their drains |
| `src/io/brodgar/addon/LuaGOut.java` | `width` in `opts`, and in the cache key; the render helper lifted |
| `src/io/brodgar/addon/UiApi.java` | `:measure(s, opts)` |
| `docs/addons/api/README.md`, `session.md`, `event/bus.md`, `ui/drawing.md`, `ui/style/chat.md`, `guides/permissions.md` | the new names |
| `docs/addons/api/types.md` | deleted into `types/`, and its ~55 inbound anchors re-pointed |
| `docs/client/services.md` | its chat row leaves |

## Risks and gotchas

- **`Channel.append` is not on the UI thread.** It runs where the server update is applied, holding
  the lock the frame takes. Queue, drain on the tick; never call Lua from the seam.
- **`ChatUI.add` calls `select(chan, false)` itself**, so a new channel fires `ChannelAdded` and
  `ChannelSelected` in one drain. Added goes first.
- **`PrivChat.name()` resolves through `GameUI.buddies.find(other)`** and answers `"???"` until the
  roster carries that person, so `:name()` is not settled at `ChannelAdded` time.
- **`Message.time` is `Utils.ntime()`** — epoch seconds as a double. LuaJ prints one in scientific
  notation, so the page writes `string.format("%d", msg:time())` and never `tostring`.
- **`rmsgs` is never trimmed.** A login's scrollback grows without bound: `:get(i)` is stable, and a
  `:list()` on an old channel is a large array. The page says `:count()` then `:get(i)`.
- **`LuaGOut`'s cache key is `(str, FontHandle, Fonts.gen())`.** `width` must join it or a re-wrap at
  a new width blits the old raster.
- **`widget:position(x, y)` does not round-trip on `ChatUI`**, whose `move` takes its base. No page
  written here may suggest moving the client's chat.
- **The three `uimsg` shapes differ per channel class** — `(line, col?, urgency?)`, `(from, line)`
  with a null `from` for your own, `("in"/"out", line)`. The bridge reads `Message` subclasses rather
  than args, so nothing here meets it; it is the trap on `docs/client/chat.md` for whoever does.

## Discarded alternatives

- **Handing over the channel's widget** — a `ch:widget()` an addon could reparent — instead of the
  line's data: a channel is a widget of one session's tree, ticked, drawn and hit-tested by that
  tree, and the addon layer is a second `UI`. Reparenting it would put a session's widget under a
  root that no session ticks, and the verb would be a promise the engine cannot keep.
- **`hafen.chat()`, a section on the client rather than on the session**: the client holds several
  logins at once and each has a chat of its own, so a global would have to answer for the one being
  DRAWN. That is the failure the per-session reads already meet from the other side — a read that
  silently answers about the character on screen rather than the one asked about — and it is not a
  labelling problem but the wrong character's data. The address goes in the call.
- **An `instanceof` ladder in the bridge** instead of the three accessors on `Message`: a subclass
  nobody enumerated reads as a line with no text, silently, and upstream adds subclasses.
- **`s:chat():channel()` as an inner collection**: `s:party()` holds `PartyMember` and
  `s:actionbar()` holds `Slot` — a section whose subsystem holds exactly one kind **is** that
  collection, and the inner noun would be a level with nothing else on it.
- **`msg:from()`**: `from` is the wire's field name. `speaker` is the word the client already
  publishes, as `chat.speaker`.
- **A `measure` that answers an unwrapped box**, the caller breaking words itself: `g:text` draws rich
  text, so a box measured over the plain string disagrees with the drawn line exactly for the lines
  that carry `$col` — the coloured ones, which in a chat is most of them.
- **`s:chat():urgency()`**, the `ChatUI.urgency` field: it is the `max` over the channels' own, and a
  derived read that can disagree with its parts is the second copy this API does not keep.
