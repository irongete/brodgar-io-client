# 123 — Tasks

Two, in order: 123.1 can break the build and the behaviour, 123.2 can break neither. Both suites are
`addons/123-a-name-with-nothing-behind-it.<X>/`, run with `:t123`.

- [x] **123.1 — the layer stops declaring what nothing calls.**
      Deletes `Sessions.ismember(Session)`, `Sessions.next()`, `Placed.charpos()`,
      `Sessions.anchorgameui()` and `Member.anchorpos()`/`toanchor(Coord2d)`/`tomember(Coord2d)` —
      eighty lines, zero callers in `src/`, `addons/` and `docs/`, re-checked against this tree and not
      against the numbers the review recorded. The last three read `Member.offset()`'s field **without**
      the anchor correction `buildplaced` applies at the call site, so they go together or not at all:
      one survivor re-arms that trap. `Sessions.loginui()` and `bysess(long)` drop `public`,
      `Control.on` drops it for `private`, and `tickoffset`'s `an == this` guard goes — unreachable,
      since the member loop already `continue`s on `u == an`. The identical expression in
      `Member.where()` is live and stays. Rewrites `multi-session.md`'s *No offset* row to name the one
      verb that remains, leaving the page shorter than it was found.
      *Its suite* is the net under what must **not** change, since a deletion has no surface of its own:
      it asserts `hafen.session():list()`, `:count()`, `:find()` and `:get(u)` agree with each other,
      that `:get(u)` interns (`get(u) == get(u)`, and identical to the entry in `:list()`), that
      `s:user()`, `:character()`, `:exists()` answer and `s:info()` carries exactly `user`, `exists`
      and `current` plus `character` once the HUD is up, and that `current(s)` moves the screen and
      `current()` reads it back — the path that runs `buildplaced`, hence `offset()`, hence what the
      three deleted readers used to touch. `pcall`s `hafen.session():get(42)` and asserts it refuses
      naming the account name as the key; `pcall`s `current(hafen.session():get("nobody"))` and asserts
      *"the client holds no session for the account"*.
      `[manual]`: with a second character live and standing far from the drawn one, look at the merged
      patch — expect: its ground and objects are where they were before, not offset.

- [x] **123.2 — every symbol a comment names is one that exists.**
      Nine citations, six files, no behaviour: `Sessions.tickrebind` in `RemoteUI.init`'s comment and in
      `AddonManager`'s — a symbol deleted features ago — and `AddonManager.init` four times in `Prof`
      and once in `MapView.dormant(boolean)`'s javadoc, which is wrong **twice**: that method does not
      exist, and the addon engine is already per session (`Sessions.Member.start` calls
      `AddonManager.sessionArrived`). Only its claim about the voice still stands. `Sessions.anchor`'s
      comment names a `SessionDestroyed` event key the bus has never fired; the closed set is
      `SessionAdded`/`SessionEnteredWorld`/`SessionSelected`/`SessionRemoved`, and the Java method
      `sessionDestroyed` queues the last of those. `MCache.numgrids`' comment calls the number "the door
      F1 grows into" when F1 shipped — `Sessions.Member.prove` is that door. Each is rewritten to
      present truth, never to a note of what it used to say. `multi-session.md` gains the fifth state
      `:session list` prints (`user:dead`) and the `main` name `:session anchor` refuses, both in place.
      *Its suite* checks the one row of the nine that Lua can reach, at the door an addon actually uses:
      `hafen.event():on("SessionDestroyed", fn)` must be refused, and the refusal must **name the four
      keys that exist** — which is what makes the corrected comment true rather than merely different.
      Then `hafen.event():on("SessionRemoved", fn)` subscribes, hands back a `Sub`, and `sub:off()` ends
      it. It also asserts the same refusal for a lower-case `"sessionremoved"`, since the client's own
      keys are PascalCase and that set is closed.
      `[manual]`: type `:session anchor main` — expect: it refuses by that name and tells you to name an
      account, rather than answering "no such session: main".
      <!-- extra context: docs/addons/api/event/bus/lifecycle.md (the four keys, already correct — read, do not write) -->
