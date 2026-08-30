# 123 — A name with nothing behind it

## What & why

Two kinds of name, one fault. A **method nothing calls**: seven of them in `Sessions.java`, eighty
lines, every one reachable from no Java, no Lua and no console. A **comment naming a symbol that does
not exist**: `Sessions.tickrebind` twice and `AddonManager.init` five times, both deleted features
ago, plus a `SessionDestroyed` event key the bus has never fired. Each is a name a reader follows and
finds nothing behind.

They are worth closing together because they fail the same way and are found the same way. Neither is
a defect a user can meet: the dead methods answer nobody and the false comments mislead only whoever
opens the file next — which, in a layer whose whole point is that it is read before it is edited, is
the cost. 122 already paid part of it: three of the eleven false rows sat on lines it rewrote, so it
corrected them there. These are the eight it did not touch.

The one that is more than hygiene is `Member.offset()`. It is corrected at its call site —
`buildplaced` reads `(u == an) ? zero : m.offset()`, because `tickoffset` never runs for the member
holding the screen and its field therefore still holds whatever it last measured as somebody else's.
Three of the seven dead methods — `anchorpos`, `toanchor`, `tomember` — read that field **without**
the correction. They are the mine, armed and unreferenced. Retiring them leaves `buildplaced` as the
only caller and the correction where it already is, so the trap goes with no API change at all.

## Acceptance criteria

Each is checked by the named task's own suite. **The removal itself is proved by `ant` and by grep**:
`tools/docverbs.py` reads `docs/addons` and `src/io/brodgar/addon` alone, so neither `Sessions.java`
nor `docs/client/` is covered by any checker, and both stay green whatever is deleted. The suites are
the net under what must not change.

1. **Every read and every refusal of `hafen.session()` answers as it did** — `list`/`count`/`find`/
   `get`/`current`, `user`/`character`/`exists`/`info`, interning, and the two refusals that name what
   is wrong — with eighty lines gone from `Sessions.java`. *(123.1)*
2. **The screen still moves and reads back**, and a background session is still placed in the merged
   scene, with the three uncorrected offset translators gone. *(123.1)*
3. **`hafen.event():on("SessionDestroyed", fn)` is refused naming the four keys that exist**, and
   `"SessionRemoved"` subscribes and ends with `sub:off()`. *(123.2)*
4. **`:session anchor main` refuses by its own name**, and the page says so. *(123.2)*

## Out of scope

The boundary is **the multi-session layer's own names**: what it declares and nothing calls, and what
it says about symbols that are gone.

- **A checker that would have caught this.** Six of the eight false citations are Java comments and
  the seventh is a `docs/client/` row, and no tool in `tools/` reads either — the report's own
  corollary is that deleting any of these eighty lines leaves both checkers green. Closing that is
  real work with a real design problem (resolving `Class.member` citations against `src/` without
  drowning in false positives from ordinary prose) and it must not make this removal wait on it. It
  is the natural next feature and is reported at the close.
- **Everything about what the layer costs.** `prove()`'s per-frame `loadedGrids`, `Recall.trim` on
  every `want`, the retained `Recall` cache, the frustum test per merged gob. `:stats on` prints the
  gauges and measuring comes first; not one number in that review is measured.
- **Splitting `docs/client/multi-session.md`** (`specs/ROADMAP.md`, filed 066). This feature only
  **shrinks** it — one row goes with the methods it names — and leaves the split queued.
- **The same fault elsewhere**: `LuaWorldEntity`'s javadoc naming five retired verbs (filed 055) and
  the four `docs/client/` pages carrying empty backtick pairs (filed 076) are both on the ROADMAP and
  both in subtrees this feature does not open.

## Docs impact

The derived set — each prose name of this surface, grepped across the whole of `docs/`:

```text
grep -rl "ismember" docs/                              -> (none; 122.3 removed the last)
grep -rl "toanchor\|tomember" docs/                    -> docs/client/multi-session.md
grep -rl "anchorpos\|charpos\|anchorgameui" docs/      -> (none)
grep -rl "tickrebind" docs/                            -> (none)
grep -rl "AddonManager.init" docs/                     -> (none)
grep -rl "SessionDestroyed" docs/                      -> (none)
grep -rl "numgrids" docs/                              -> (none)
grep -rl "loginui\|bysess" docs/                       -> (none)
```

**One page, one row.** `docs/client/multi-session.md`'s *No offset* row names `Member.offset()`,
`toanchor` and `tomember`; two of the three go, so the row is rewritten to name the one that stays.
The same page's two omissions are corrected while it is open: `:session list` enumerates four states
and `Member.status` has a fifth (`user:dead`), and `:session anchor` does not mention that `main` is
refused by name with a message of its own. The page is 182 lines against a 150-line ceiling, so
every edit here is net-shorter or even.

`docs/addons/api/event/bus/lifecycle.md` was checked and is **correct** — it documents `SessionRemoved`
and has never named `SessionDestroyed`. Only the Java comment is wrong.

## Context files

- `src/io/brodgar/session/Sessions.java` — 1, 2
- `src/io/brodgar/session/Control.java` — 1
- `src/haven/RemoteUI.java` — 2
- `src/haven/MapView.java` — 2
- `src/haven/MCache.java` — 2
- `src/io/brodgar/addon/AddonManager.java` — 2
- `src/io/brodgar/prof/Prof.java` — 2
- `docs/client/multi-session.md` — 1, 2
- `docs/addons/api/session.md` — 1
- `docs/addons/api/event/bus/lifecycle.md` — 2
