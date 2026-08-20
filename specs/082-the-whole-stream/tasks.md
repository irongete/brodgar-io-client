# 082 — The whole stream: tasks

- [x] **082.1 — Every outbound message, under one name.** `Subs` gains `WILD` (`"*"`), a `volatile
      boolean wild` and a `wild()` read, maintained in `on`, `off` and `clear` and nowhere else.
      `AddonManager.anyStreamSub` reads that field in front of the map lookup it already did, so the
      gate gets cheaper rather than dearer for an addon that named its key. `fireAction` mints one
      `ev` per addon and fires the named list then the wildcard list over it, with `named = has(msg)
      && !WILD.equals(msg)`. `Addon.actionSubs` and `LuaEvent.action` say in their javadoc what the
      `hasSub` gate now lets through, and "the bus's 26" loses a count that is wrong. `UI.wdgmsg`'s
      `// addon:` comment stops naming `hafen.hook():action` — a section `Retired` throws on — and
      stops calling the seam `L2`; comment text only, no code.
      `docs/addons/api/event/streams.md` gets the wildcard for the outbound half; its `:13`, `:14`
      and `:72` stop saying an accepted key may simply never fire.
      *Its suite* declares `widget.send`. Its wildcard handler records `ev:msg()`,
      `ev:sender():type()` and `#ev:args()` and then calls `ev:preventDefault()`, so the probe is
      observed and never reaches the wire — `UI.wdgmsg` returns without calling `rawWdgmsg`. It
      drives `s:ui():find("@GameUI"):send("brodgar-probe", 1)` and asserts exactly that name, that
      sender and one argument. It then holds `"brodgar-probe"` **and** `"*"` at once and sends again:
      both ran, the named one first, and the two captured `ev` values compare `==`, which is the
      whole of the one-payload claim. From inside that handler it sends a second message and asserts
      it was **not** reported — the re-entrancy guard. A third subscription, on a name the run never
      sends, must not fire at all: a wildcard changes nothing for a handler that named its key.
      `sub:off()` on the wildcard leaves the named subscription firing, and `pcall`
      `:action():on("*")` with no function must name `(string, function)`.
      `[manual]`: with the suite's five-second counting window open, click to move once — report the
      count line and whether your character actually moved. An observed send still reaches the
      server.

- [x] **082.2 — Every inbound message, under the same name.** `fireMessage` takes the same shape as
      `fireAction`: one `ev` per addon over the shared `Subs.Cancel` and the shared `rewritten` slot,
      named list first. `anyStreamSub`'s message half reads the same field. `Addon.messageSubs` and
      `LuaEvent.message` say it in their javadoc, and `UI.UiMessage.run`'s `// addon:` comment stops
      naming `hafen.hook():message` and stops calling the seam `L3` — comment text only, no code.
      `docs/addons/api/event/streams.md` gets the inbound
      half and its `:100`, plus the two callouts this stream alone needs: a wildcard
      `ev:preventDefault()` swallows **every** server update and skips the post-apply tap the
      client's own change detection runs on, and an inbound handler runs on a Loader thread under the
      `ui` monitor, where the sandbox's per-tick budget is the only thing between a slow handler and
      a stutter.
      *Its suite* cannot make the client apply a `uimsg`, so it scores over what the run reached: a
      wildcard collects distinct `ev:msg()` names on a `hafen.timer()` window, passing at two or more
      and otherwise reporting the count and the window it got. It records the first name it sees,
      subscribes to that exact name beside the wildcard, and on the next arrival asserts both ran,
      named first, over one `ev` compared with `==`; `ev:target():type()` answers a live widget class
      on both. It never calls `preventDefault`. `sub:off()` on the wildcard leaves the named one
      firing, and `pcall` `:message():on("*", "no")` must name `(string, function)`.
      `[manual]`: report the distinct-names line, and whether the client stayed smooth for the whole
      window — inbound Lua runs where the UI thread waits for the monitor, and only you can judge
      that.

- [ ] **082.3 — On the bus, `*` is refused and says where it means everything.**
      `AddonManager.busKeyRefusal` gains a `"*"` branch naming `hafen.event():action()` and
      `hafen.event():message()`, beside the session-family branch and for the same reason.
      `docs/addons/api/event/bus.md` says the closed set has no wildcard and why — 31 keys whose
      payload *is* the fact, with no parameter to carry a key — and points at the streams;
      `docs/addons/api/event/README.md`'s paragraph on why the two key sets are open names the
      wildcard as what that openness buys; `docs/addons/guides/events-and-timers.md`'s routing table
      gains it on the stream row; `docs/addons/guides/debugging.md` gains a short section on finding
      the name you need by watching the whole stream first. The feature's derived impact set from
      `spec.md` is discharged here page by page, with the `DOCUMENTATION.md` §11 checks over every
      page 082 touched.
      *Its suite* `pcall`s `hafen.event():on("*", fn)` and asserts it failed **and** that the message
      names both streams — the pointer is the point, not the refusal. `hafen.event():on("Sessoin", fn)`
      must still get all four session keys spelled out, proving the new branch did not displace the
      old hint, and `hafen.event():on("GobAdded", fn)` must still subscribe. On a widget,
      `widget:on("*", fn)` must still refuse as an unknown widget key: the reservation is the
      streams' alone.
      `[manual]`: read the new wildcard section and the debugging guide's new section, and report
      whether the swallow warning and the cost callout read as things you would heed **before**
      writing one, rather than after.
