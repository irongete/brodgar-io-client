# 082 — The whole stream: plan

## Approach

`"*"` becomes a **reserved key on the two stream emitters** and nothing else. `AddonManager.stream`
already accepts any string, so no new verb, no new arity, and no parse: what changes is that the
dispatch now looks the wildcard list up beside the named one.

**The flag lives on the `Subs` that owns the subscriptions.** `Subs` gains one `volatile boolean`
and a `wild()` read, written only in `on` (when the key is `"*"`), in `off` (recomputed from the
list) and in `clear`. All three are cold. This keeps the state on the thing that owns it — the same
rule that made `dispatchAction` a scan over each owner's own `Subs` rather than a global registry —
and it is what stops the gate getting more expensive for everyone who names a key:

```java
// AddonManager.anyStreamSub — a field read in front of the map lookup it already did
Subs s = action ? a.actionSubs : a.messageSubs;
if(s.wild() || s.has(msg)) return true;
```

**One `ev`, two lists, named first.** `fireAction`/`fireMessage` mint the payload once per addon and
fire the named list then the wildcard list over it, so the shared `Subs.Cancel` and the shared
`rewritten` slot behave exactly as they do between two handlers on one key: any cancel stops the
send once, the last `send`/`rewrite` wins, and the order is stated rather than emergent.

```java
boolean named = a.actionSubs.has(msg), wild = a.actionSubs.wild();
if(!named && !wild) return;                       // the hasSub gate, widened by one field read
LuaValue ev = LuaEvent.action(a, sender, msg, args, c, u);
if(named) a.actionSubs.fire(msg, c, ev);
if(wild)  a.actionSubs.fire(Subs.WILD, c, ev);
```

`named` is computed as `has(msg) && !WILD.equals(msg)`, so a message that somehow arrived named `*`
fires one list rather than the same list twice.

The **bus** stays closed: `busKey` already rejects `"*"`, and `busKeyRefusal` gains a branch that
points at the two streams, beside the session-family branch that is there for the same reason — a
subscription that silently never fires is the most expensive way to learn a name.

## Files to create and modify

| File | What changes |
|---|---|
| `src/io/brodgar/addon/Subs.java` | `WILD` constant, the `wild` field, `wild()`, and its maintenance in `on`/`off`/`clear` |
| `src/io/brodgar/addon/AddonManager.java` | `anyStreamSub`, `fireAction`, `fireMessage`, `busKeyRefusal`; the `stream` javadoc's "OPEN key set" paragraph |
| `src/io/brodgar/addon/Addon.java` | the `actionSubs`/`messageSubs` javadoc — the wildcard is the second door in, and "the bus's 26" loses a count that is now 31 |
| `src/io/brodgar/addon/LuaEvent.java` | the `action`/`message` javadoc: what the `hasSub` gate now lets through |
| `src/haven/UI.java` | the two `// addon:` seam comments — comment text only, no code |
| `docs/addons/api/event/streams.md` | a section for the wildcard, both halves, with its cost and its two footguns |
| `docs/addons/api/event/README.md` | the paragraph on why the two key sets are open |
| `docs/addons/api/event/bus.md` | `"*"` is not a bus key, and where it is one |
| `docs/addons/guides/events-and-timers.md` | the routing table's stream row |
| `docs/addons/guides/debugging.md` | finding the name you need by watching the whole stream |

**No `docs/client/` page is created.** The two upstream seams this rides —
`UI.wdgmsg` running the addon chain before `UI.rawWdgmsg`, and `UI.UiMessage.run` applying an update
on a Loader thread under `synchronized(ui)` — are both already mapped in `docs/client/network.md`,
and nothing else in `haven` had to be read.

## Risks and gotchas

- **The inbound stream runs Lua on a Loader thread, under the `ui` monitor.**
  `UI.UiMessage.run` holds `synchronized(UI.this)` across `AddonManager.onMessage`. A wildcard turns
  every server update into a Lua call there, and the UI thread blocks on that same monitor to tick
  and draw. `Sandbox`'s soft per-tick budget and its consecutive-strike auto-disable are the net
  under a handler that is too slow; the page says so rather than promising it is free.
- **`preventDefault` on an inbound wildcard swallows every server update.** `UI.UiMessage.run` skips
  both `dispatch(wdg, MessageEvent)` **and** `AddonManager.onUimsg` — the post-apply tap that drives
  the client's own change-detection — when `onMessage` answers null. A named-key handler swallows one
  message; a wildcard that does the same stops the client. This is the blockquote callout on
  `streams.md`.
- **A handler's own send is invisible.** `dispatchAction` returns early on
  `SessionState.dispatchingAction`, so a `wdgmsg` sent from inside an action handler reaches
  `rawWdgmsg` without being reported. It cannot recurse, and it also cannot be logged — both halves
  of that are the same guard, and both go on the page.
- **`dispatchAction` also returns early without the UI monitor.** `Thread.holdsLock(u)` and a null
  `SessionState` both skip the whole chain, so a wildcard does not see literally every send — it
  sees every send on the Lua-safe path. Stated, not implied.
- **`widget:send` refuses an unbound widget** (`w.wdgid() < 0`, `Permission.WIDGET_SEND`), so a suite
  cannot drive the outbound stream from a widget it built. It sends from a server-placed widget and
  cancels in its own handler, which is what keeps the probe off the wire.
- **The core's own seam comments teach a spelling the engine refuses.** `src/haven/UI.java`'s two
  `// addon:` comments name `hafen.hook():action` and `hafen.hook():message`; the section is
  deleted, `Retired` throws on reading it, and `Section` carries a redirect built for that exact
  spelling. `UI.java` is where a reader goes to find the seam, so it is the worst place to keep a
  dead name. Each task rewrites the comment on the seam it rides — the names, the `L2`/`L3` level
  vocabulary, "hooks" for what is a subscription — as comment text only.

## Discarded alternatives

- **A second verb (`:onAny(fn)`)** — a stream would answer two verbs for one question, and the
  grammar's promise is that a section's vocabulary is closed and arity is what distinguishes a call.
- **`:on(fn)` at one argument** — overloads the verb on argument *type* rather than on arity, and
  reads as a subscription to nothing. The one place the API says "everything" already says it with a
  character, not with an absence.
- **A wildcard inside `Subs.fire`/`has` itself** — gives `"*"` a meaning on every emitter, including a
  widget's `Draw`, whose fire is per widget per frame; the two streams are the only emitters whose
  key set is open, so the meaning belongs where the openness is.
- **Recomputing the gate by scanning for `"*"` on each message** — a second map lookup per addon per
  message, paid by everyone who named a key, to serve the one addon that did not.
- **A permission key for the wildcard** — it grants no capability an addon lacks: every message name
  it reaches is reachable today by naming it, chat included. A key here would price convenience.
- **Passing the key to a bus handler so `hafen.event():on("*", fn)` could exist** — the bus's payload
  *is* the thing the event is about, and 31 keys would have to grow a parameter for one subscriber.
- **Making the wildcard fire before the named key** — the named key is the specific claim on the
  message and the wildcard the ambient one, so the specific handler is the one that should see the
  `ev` first and the ambient one the one that sees what was done to it.
- **Widening the session-family hint to catch a typo that breaks the `session` prefix** — the client
  approximates nothing: `busKey` is an exact `equals` over the catalogue, so a near miss registers
  nothing whatever the hint says, and the hint is text appended to a call that has already been
  refused. Catching a transposition means edit distance against the keys, which is a different
  question — a "did you mean" over all 31 — and belongs to a feature of its own rather than to the
  task that reserves `"*"`. `"Sessoin"`, which 082.3's own line names, therefore falls through to the
  bare refusal, and its suite proves the branch order with `"SessionEnteredWord"` instead.
- **Making an unknown bus key a silent no-op rather than a throw** — a subscription that registers,
  reads as correct and never fires is the failure this feature exists to remove from the streams, and
  the refusal at the line that wrote it is the whole reason the bus's key set is closed. Putting that
  failure back one door along, inside the feature removing it, is the one change this cannot make.
