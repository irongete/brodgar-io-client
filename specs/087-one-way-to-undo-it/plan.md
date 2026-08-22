# 087 — One way to undo it: plan

## Approach

Three tasks, four rows, in the order that makes each true when it is written: **rename** (A-047),
then **fix the returns** including on what was just renamed (A-048), then **write the rule** that
describes the result and add the one verb the rule's own example needs (A-046, A-049).

**Everything here is a rename or a return.** No argument shape moves, no payload moves, so `Retired`
carries all of it — the free half of `CLAUDE.md`'s hard-cut rule. Every receiver is
`closedIndex`-backed after 084 and 086, so a retired spelling raises at the line that wrote it, for a
call and for a field read alike.

### 1 — Three endings take the vocabulary's own word (A-047)

| Today | Becomes | Why the row says so |
|---|---|---|
| `rule:remove()` | `rule:release()` | a per-widget rule is a **layer you took**, not a member of a collection; `:remove(x)` is what a collection does to a member |
| `sheet:drop()` | `sheet:release()` | the same act on the sheet the rule belongs to, and `drop` reads as discarding data |
| `asset:dispose()` | `hafen.asset():remove(a)` | the asset collection exists and owns its members, which is **D2**'s rule |

`AssetApi`'s ending is the shared asset verb — the one `addAssetVerbs` contributes to the methods
table 086.5 built — so the move is from the member's metatable to the collection's `Source`:
`destroyable()` returns true and `removeMember(x)` runs the existing `Disposer`. The **view**
metatable (`AssetApi.imageFor`, `FontApi.handleFor`) carried no ending before and gains none, which is
the same guarantee stated the other way round: freeing a file is the owner's to do, and now it is the
owner's *collection* that does it.

`MapImages`' handle has its own disposal and is reached through `grid:image(n)` rather than through
`hafen.asset()`, so it keeps its own ending. A font **variant** carries no ending at all — `font.md`
already says a variant is not a file — so nothing changes there.

### 2 — Every ending returns the receiver (A-048)

Seven closures return `LuaValue.NIL` and return their receiver instead. Counted against the code
today, **after 086** — the row's own enumeration predates it and names watch/slash `:remove`, which
086.1 turned into `sub:off()`:

| Verb | Site |
|---|---|
| `sub:off()` | `LuaSub` |
| `timer:cancel()` | `AddonManager`'s timer methods |
| `w:destroy()` | `LuaWidget` |
| `rule:release()` | `LuaRule` — after task 1 |
| `grab:release()` | `LuaGrab` |
| `req:cancel()` | `HttpApi` |
| `s:flowermenu():cancel()` | `FlowerMenuApi` |

**`ov:destroy()` is deliberately not in that list.** A-048 says *"Skip `ov:destroy` here — A-120
replaces it with `:remove(key)` one feature later"*.

Eight already return the receiver and are read, not written: `toggle:release`, `sheet:release`,
`sound:stop`, `s:close`, `coll:remove` (returns `me`), `scope:finish` (returns `a.arg1()`, the model
to copy), `item:drop` and the asset ending. `ProfScope`'s `finish` is a `VarArgFunction` returning
`a.arg1()`; the seven that need fixing are a mix of `OneArgFunction` and `ZeroArgFunction`, so some
need their functional interface widened to see `self` at all.

### 3 — A collection destroys its member, and the rule is written down (A-046, A-049)

`hafen.session()` is already a `LuaCollection` (`SessionApi`'s mount, `current` its only extra), and
`LuaCollection` already carries the mechanism: `if(coll.src.destroyable())` installs a
`:remove(keyOrMember)` that calls `src.removeMember(…)` and returns the collection. The session
`Source` declares `destroyable()` true and implements `removeMember(x)` as
`Sessions.byuser(user).drop()` behind `Permission.SESSION_CLOSE` — the same key `s:close()` uses, the
same act, and the gate goes **first**, as D-213 requires. `s:close()` stays beside it: ending a login
is its own act, and A-049 says *beside*.

Then `conventions.md` gains the section the whole feature exists for — the seven verbs, which kind of
receiver each belongs to, that every ending returns the receiver, that where a collection exists the
destroy verb is on it, and that three acts are **not** endings: `item:drop(n)` (a protected game
action), `s:close()` (ending a login) and `w:revert()` (an undo of your own layer, like
`w:replace(nil)` and `w:size(nil)` — **not** `slot:hold(nil)`, which 089 creates).

## Files to create/modify

**Bridge**, under `src/io/brodgar/addon/`:

| File | Change | Task |
|---|---|---|
| `LuaRule` | `remove` → `release` | 1 |
| `LuaSheet` | `drop` → `release` | 1 |
| `AssetApi` | the member's `dispose` moves to the collection's `Source.destroyable()`/`removeMember` | 1 |
| `Retired` | `rule:remove`, `sheet:drop`, `asset:dispose`, `session:…` rows | 1, 3 |
| `LuaSub`, `AddonManager` (timer), `LuaWidget`, `LuaRule`, `LuaGrab`, `HttpApi`, `FlowerMenuApi` | return the receiver | 2 |
| `LuaHudOverlay` | **untouched** | — |
| `SessionApi` | its `Source` declares `destroyable()` and `removeMember` | 3 |
| `LuaCollection` | read only — the mechanism already exists | 3 |

**Pages** — the list and the row each task owns is in `spec.md` §Docs impact.

## Risks and gotchas

**`conventions.md` is at 291 lines, and 089 also adds to it.** A-046's teardown rule and **A-069**'s
bare-adjective rule (089.5) both land there, and either alone may pass 300. `DOCUMENTATION.md` §11.2:
the page your own writing pushes over the ceiling is split **in the task that wrote it**. Task 3 must
`wc -l` before handing over, and whichever of 087.3 and 089.5 runs second inherits the split. The
teardown rule is a table of seven rows plus three sentences — write it as the table, and link
`references.md` rather than re-listing every site.

**A `ZeroArgFunction` cannot see its receiver.** `AddonManager`'s timer `cancel` and `LuaGrab`'s
`release` are `ZeroArgFunction`s: `call()` takes nothing, so returning the receiver means widening
them to `OneArgFunction` (whose `call(LuaValue self)` is the receiver) or to `VarArgFunction` with
`a.arg1()`. `ProfScope.finish` is the model. Widening changes nothing at the call site — LuaJ passes
`self` on a colon call regardless — but a `ZeroArgFunction` left as it is silently keeps returning
`nil`, and the suite is what catches it.

**An ending must stay idempotent.** `sub:off()` is documented as *"idempotent, and also done for you
on reload or disable"*, and `timer:cancel()` as *"safe to call more than once, and on one that has
already fired"*. Returning the receiver must not turn the second call into a raise — the second call
still finds nothing and still answers the receiver.

**`asset:dispose()` moving to the collection changes who may call it.** Today the *view* metatable
carries no `dispose`, which is how an addon is stopped from freeing a file it never loaded. After the
move, `hafen.asset():remove(a)` is reached through the **caller's own** `hafen.asset()`, so the
`Source.removeMember` must refuse an asset this addon does not own — the guarantee was structural and
becomes a refusal, which needs a message. Read `AssetApi.imageFor`'s javadoc for the reason before
writing it.

**`Sessions.Member.drop()` is the same call `s:close()` makes**, and `s:close()` raises when the
client holds no session for that account. `removeMember` must give the same refusal, naming
`s:exists()` as the test, or the two doors answer differently for the same mistake.

**The permission gate goes first.** `Permission.SESSION_CLOSE` must be required before anything is
resolved in `removeMember`, exactly as `LuaSession.close` does it — D-213's ordering rule, which
084.5 checked at all 24 sites.

**`rule:close(…)` is not an ending.** It is the chrome property naming a window's close **button**,
and 088's A-053 renames it `:closeButton()`. Do not touch it, and do not let the `conventions.md`
table imply it is in the family.

**The build hides a moved symbol.** `rm -rf build/classes` before believing a green build.

## Discarded alternatives

- **The four-verb vocabulary `audit/03-lifecycle.md` proposes** — `off`, `remove`, `release`,
  `cancel`, folding `w:destroy()` into `hafen.ui():remove(w)`, `sound:stop()` and `scope:finish()`
  into `:cancel()`. A-046 records **seven** and the rest of the block corroborates it: A-048 names
  `w:destroy` and `timer:cancel` as endings that survive, and A-049 says *"aborting and silencing are
  not destroying"*. Folding `stop` into `cancel` would also make `sound:cancel()` mean "silence it",
  which is a different act from aborting something that had not finished.
- **Folding `w:destroy()` into `hafen.ui():remove(w)` anyway** — **D2** made the rule conditional on a
  collection existing, and `hafen.ui()` is not a collection of your windows. A-113 (094) is where that
  changes, and doing it early would mint a collection this feature has no other use for.
- **Fixing `ov:destroy()`'s return while every other ending is being fixed** — A-048 says to skip it
  in as many words, because A-120 (088) replaces the HUD painter with a keyed collection whose ending
  is `:remove(key)`. One line of work, thrown away one feature later, plus a suite archived proving a
  verb that no longer exists.
- **`sheet:discard()` or `sheet:drop()` kept** — A-047 names `:release()`, and the act is the same one
  `rule:release()` and `grab:release()` name: giving back something you took, where the thing survives
  and your claim ends.
- **Keeping `asset:dispose()` beside `hafen.asset():remove(a)`** — two doors onto one act is the dual
  style `CLAUDE.md` forbids, and the member's door is the one D2 rejected.
- **Making `s:close()` retire in favour of `hafen.session():remove(s)`** — A-049 says *beside*, and the
  page's reason holds: ending a login is its own act, protected, and reads better on the session than
  on the roster.
- **Renaming `s:flowermenu():cancel()` to `:release()`** — the page floats it and A-046 keeps `cancel`
  in the seven. Dismissing a menu the server put up is aborting something in flight, which is what
  `cancel` means in the other three sites.
- **Writing the rule first and the renames after** — a page that describes a vocabulary the bridge
  does not have yet is false at the commit that lands it, and every task boundary is meant to leave
  the tree true.
