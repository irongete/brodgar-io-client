# 110 — Tasks

- [x] **110.1 — The snapshot catalogue becomes a folder.** `docs/addons/api/types.md` is deleted into
      `docs/addons/api/types/`, split by subject with a `README.md` hub that indexes every shape: the
      session and the world, the item and what holds it, the character sheet, the fight, the map, the
      widget layer. Every inbound page link and every one of the ~55 inbound anchors across `docs/`
      is re-pointed in this same task, so the tree is link-clean at the boundary. No `hafen.*` name
      moves and no shape changes — this is the room 110.3 needs to file two more.
      *Its suite* walks the shapes a client with one character up can reach without the server —
      `s:info()`, `gob:info()`, `w:info()`, `p:info()`, `pag:info()` — and asserts that every field
      the new pages list is present in the table that comes back and that no table carries a field no
      page lists, which is the only way a split catalogue can be wrong. It prints the count it
      checked, so a shape silently dropped in the move fails rather than passing quietly.
      `[manual]`: none.
      <!-- extra context: DOCUMENTATION.md §9 (pricing a split by its inbound anchors) -->

- [x] **110.2 — `s:chat()`: the channels, the one on screen, and saying a line.** The section, minted
      per session over `GameUI.chat`; `:list`/`:count`/`:find`, `:selected()`/`:selected(ch)`, and no
      `:get`. A channel answers `:name()`, `:kind()`, `:urgency()`, `:exists()`, `:info()`, and
      `:send(text)` behind `chat.send`. `ChannelAdded`, `ChannelRemoved` and `ChannelSelected` fire
      off `ChatUI.add`/`cdestroy`/`select`, queued and drained on the tick. Ships
      `docs/addons/api/chat.md` and `docs/client/chat.md`, and the chat row leaves `services.md`.
      *Its suite* asserts `:selected()` is one of `:list()` and interned equal to it; writes
      `:selected(ch)` to another channel and reads it back; asserts `ChannelSelected` fired once for
      that write, carrying that channel and its session last. It asserts every `:kind()` in `:list()`
      is one of the four a channel can wear, which is what makes the set closed. It refuses
      `s:chat():get("Party")`, requiring the message to name **both** `:find(needle)` and
      `:list()[n]`, and refuses `ch:send("x")` on the System log, requiring it to name the kinds that
      take a line.
      `[manual]`: the line reached the game's chat and reads as your own.

- [x] **110.3 — The lines.** `ch:message()` — `:list`/`:count`/`:find`/`:get(i)`, 1-based, oldest
      first — and the Message object: `:text()`, `:kind()`, `:color()`, `:time()`, `:speaker()`,
      `:mine()`, `:channel()`, `:exists()`, `:info()`. Adds `text()`/`color()`/`speaker()` to
      `ChatUI.Message`, overridden in `SimpleMessage` and `NamedMessage`. `MessageAdded` fires off
      `Channel.append`, queued off the server-update thread. Files both snapshots into `types/`.
      *Its suite* writes a line to the System log through the console, then asserts `MessageAdded`
      fired once carrying a Message whose `:channel()` is interned equal to that channel, whose
      `:get(i)` at `:count()` is that same object, whose `:kind()` is `"chat.system"`, whose
      `:mine()` is false and whose `:speaker()` is nil. It asserts `:time()` formatted `"%d"` scans
      as a plausible epoch second, which is the readback the scientific-notation trap breaks, and
      that `:count()` rose by exactly one. It refuses `ch:message():get(0)`, requiring the message to
      say indices start at one.
      `[manual]`: say a line in Area Chat — the last message reads `:mine()` true and `:kind()`
      `"chat.mine"`; say one to a kin — theirs reads `:speaker()` as that person.

- [x] **110.4 — Text that wraps, and the box it takes.** `g:text` and `g:atext` take `width` in the
      `opts` they already carry and wrap at it through `RichText.Foundry.render(String, int)`;
      `width` joins `LuaGOut`'s cache key, without which a re-wrap at a new width blits the old
      raster. `LuaGOut`'s render helper is lifted so `hafen.ui():measure(s, opts)` answers
      `{w =, h =}` for the identical call made outside a draw callback.
      *Its suite* proves the two agree without reading pixels: a `hafen.ui():label()` sizes itself to
      the raster it drew, so `label:text(s):size()` is the drawn box, and the assertion is that
      `hafen.ui():measure(s, {})` equals it. It then asserts a `width` narrower than that box answers
      a greater `h` and a `w` no greater than the `width`; that measuring the same string twice at
      two widths answers two different boxes, which is the cache-key bug made visible; and that a
      string carrying `$col{…}` measures the same as the same string without the markup — the
      disagreement the plain-text alternative was discarded for. It refuses a `width` of `0`.
      `[manual]`: none.

- [x] **110.5 — The event catalogue becomes a folder.** `docs/addons/api/event/bus.md` is deleted into
      `docs/addons/api/event/bus/`, split by subject with a `README.md` hub that carries the model every
      subject shares — the closed key set, whose character it was, and what is deliberately not an event —
      over one page per family: the addon's own three and the session's four, the world's, the character's
      and rosters', and the chat's. It is over the 300-line ceiling and the chat keys are what pushed it
      there, so the feature that added them closes it. **Priced by its inbound anchors, not by its size**:
      94 inbound links today, 73 of them anchored, and `#character-and-status` (18), `#sessions` (15) and
      `#world` (12) are most of that — every one re-pointed in this same task, so the tree is link-clean at
      the boundary. No key moves and no payload changes.
      *Its suite* transcribes every key off the new pages and asserts each is one
      `hafen.event():on(key, fn)` actually accepts — subscribing and ending the subscription, so a key that
      survived the move onto a page the bridge does not fire fails rather than reads well. It asserts a near
      miss inside the session family is refused naming all four, which is the one refusal that would go
      quiet if a page and the bridge drifted apart, and prints the count it walked. The reverse direction is
      not a program's to check from Lua — there is no verb that enumerates the bus — so this task extends
      `tools/docverbs.py` with it: every key in the bridge's own `BUS_KEYS` appears on a page under
      `docs/addons/api/event/`, which is exactly the "a key was dropped in the move" failure the suite
      cannot see.
      `[manual]`: none.
      <!-- extra context: DOCUMENTATION.md §2.3 (a subject directory below the top level of api/) and §9
           (pricing a split by its inbound anchors); tools/docverbs.py, whose PER_FILE keys by basename and
           will need the path form — `event/bus/chat.md` is the third `chat.md` in the tree -->
