# 123 — Plan

## Approach

**Retire first, then correct.** The two halves fail differently and are proved differently, so they
are two tasks: deleting a method can break the build and the behaviour, and rewriting a comment can
break neither. Splitting them keeps each fix round to one oracle.

**123.1 deletes seven methods and narrows three visibilities.** `Sessions.ismember(Session)`,
`Sessions.next()`, `Placed.charpos()`, `Sessions.anchorgameui()`, and `Member.anchorpos()`,
`toanchor(Coord2d)` and `tomember(Coord2d)`. Eighty lines, zero callers in `src/`, `addons/` and
`docs/`, re-checked against the tree 122 left rather than against the review that found them.
`Sessions.loginui()` and `bysess(long)` drop `public` for package-private — four internal callers and
one — and `Control.on` drops `public` for `private`, which is what its zero external accesses make it.
`Member.tickoffset`'s `an == this` guard goes with them: it is unreachable, because `Sessions.tick`'s
member loop already `continue`s on `u == an`.

The one that is not hygiene is **`Member.offset()`**. `tickoffset` never runs for the member holding
the screen, so its field keeps whatever it last measured as somebody else's, and `buildplaced` is what
corrects it — `(u == an) ? Coord2d.of(0, 0) : m.offset()`, at the call site. `anchorpos`, `toanchor`
and `tomember` read the field **without** that correction. All three are in the seven, so retiring
them leaves `buildplaced` as the only caller, the correction where it already is, and no API change at
all. The three go **together or not at all**: one survivor re-arms the trap on its own.

**123.2 corrects nine citations across six files**, none of which changes a line of behaviour:
`Sessions.tickrebind` in `RemoteUI.init`'s comment and in `AddonManager`'s, `AddonManager.init` four
times in `Prof` and once in `MapView.dormant(boolean)`'s javadoc, the `SessionDestroyed` event key in
`Sessions.anchor`'s comment, and `MCache.numgrids`' "the door F1 grows into". Each is rewritten to
name what is actually there, never to record what it used to say. `docs/client/multi-session.md` is
opened once by each task and is left shorter than it was found.

## Files to create/modify

| File | What |
|---|---|
| `src/io/brodgar/session/Sessions.java` | the seven deletions; `loginui`/`bysess` package-private; `tickoffset`'s dead guard; the `SessionDestroyed` comment (2) |
| `src/io/brodgar/session/Control.java` | `on` → `private` |
| `src/haven/RemoteUI.java` | `init`'s comment: `tickrebind` (2) |
| `src/io/brodgar/addon/AddonManager.java` | one comment: `tickrebind` (2) |
| `src/io/brodgar/prof/Prof.java` | four comments: `AddonManager.init` (2) |
| `src/haven/MapView.java` | `dormant(boolean)`'s javadoc: `AddonManager.init`, **and** the claim that the addon engine is not yet per-session (2) |
| `src/haven/MCache.java` | `numgrids`' comment: F1 has shipped (2) |
| `docs/client/multi-session.md` | the *No offset* row, which names two methods that go (1); `:session list`'s fifth state and `:session anchor main` (2) |
| `addons/123-a-name-with-nothing-behind-it.{1,2}/` | the two suites, `:t123` |

## Risks & gotchas

- **The review's line numbers are stale and the symbol names are not.** 122 grew `Sessions.java` from
  1669 lines to 1855, so every number in that report is off. Find by symbol: `ismember` 94, `next` 639,
  `charpos` 701, `anchorgameui` 993, `tickoffset`'s guard 1594, `offset` 1633, `anchorpos` 1645,
  `toanchor` 1659, `tomember` 1665 — and check them again before cutting.
- **Two identical expressions, one dead and one live.** `an == this` in `Member.tickoffset` is
  unreachable; the same expression in `Member.where()` decides which sentence the line prints and must
  stay. Deleting by grep would take both.
- **Do not "fix" `Member.offset()` while you are there.** With its three uncorrected readers gone,
  `buildplaced` is the only caller and it already corrects; moving the correction into the verb would
  add an `anchormember()` walk per member per frame to serve a caller that no longer exists.
- **No checker covers any of this.** `tools/docverbs.py` roots at `docs/addons` and
  `src/io/brodgar/addon` (a flat `os.listdir`), so `Sessions.java`, `docs/client/` and every Java
  comment in the tree are invisible to it; both checkers exit 0 whatever is deleted. `ant` is the net
  under 123.1 and `grep` is the net under 123.2 — run both, and do not read a green checker as cover.
- **`MapView.dormant(boolean)`'s javadoc is wrong twice, not once.** `AddonManager.init` does not
  exist, and the addon engine is **already** per session — `Sessions.Member.start` calls
  `AddonManager.sessionArrived(u)` the moment a UI exists. What still stands is the voice: `Voice` is a
  static hub by its own javadoc. Rewrite two of three claims, not all three.
- **All five `@SuppressWarnings("deprecation")` in `Sessions.java` survive.** They sit on `lockedview`,
  `mapview`, `findgui`, `autoplay` and `status`, each wrapping `Widget.findchild`, which is still
  `@Deprecated`. None of the seven removals frees one, and removing one that is still needed is a
  warning at build time, not an error.
- **`Sessions.next()` is not the cycle.** The cycle is `session-manager`'s hotkey, through
  `hafen.session():current(s)`. Deleting the Java verb removes a duplicate, not a feature.
- **`docs/client/multi-session.md` is 182 lines against a 150-line ceiling.** Every edit here is
  net-shorter or even; the page's split is the maintainer's ROADMAP line and not this feature's.
- **`docs/addons/api/event/bus/lifecycle.md` is already correct** — it documents `SessionRemoved` and
  has never named `SessionDestroyed`. Read it, do not write it.

## Discarded alternatives

- **Building the citation checker in this feature** — six of the eight false citations are Java
  comments and one is a `docs/client/` row, and nothing in `tools/` reads either, so this feature will
  rot the same way. But resolving `Class.member` citations against `src/` without drowning in false
  positives from ordinary prose is a design problem of its own, and an eighty-line deletion must not
  wait on it. Reported at the close as the next feature.
- **Moving the anchor correction into `Member.offset()`** (the review's own proposal) — see the third
  gotcha: it buys a per-frame walk to serve a caller that this task deletes.
- **Keeping `toanchor`/`tomember` for the order family the ROADMAP carries** — that family will be
  written against whatever frame it needs; two unreferenced verbs reading an uncorrected field are a
  trap, not a head start.
- **Deprecating rather than deleting** — nothing is released, so a replaced spelling is hard-cut. These
  have no replacement because they have no callers.
- **Restating `ismember`'s invariant somewhere else** — 122.3 already wrote the true reason into
  `Sessions.add` and `adopt`, which is where the ordering it protects actually lives. The method's own
  javadoc goes with the method.
- **One task rather than two** — the deletion is proved by `ant` and the citations by `grep`, and a fix
  round on one would re-run the other's suite for nothing.
- **A `[manual]` line for each corrected comment** — a comment is verified by reading it in the diff,
  which the maintainer does anyway; a manual line per row would be fifteen lines of ceremony for a
  change no program and no player can observe.
