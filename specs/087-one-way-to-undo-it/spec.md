# 087 — One way to undo it

Discharges: A-046, A-047, A-048, A-049.

**All four are rows in `audit/INVENTORY.md`'s own 087 block, and this feature adds nothing to them.**
Where a row and its finding page disagree, the **row** is what ships — it is the decision the sweep
recorded, and §"Where the row and the page differ" below names the one place that happens.

**The audit is readable, and every task names its part of it.** `audit/` is not in `CLAUDE.md`'s tree
table, so nothing otherwise permits an `/implement` session to open it. It is permitted here: each
task's *Audit* line names its ids, quotes the row, and names the finding page. **Read those pages
before starting.** **Read nothing else there** — the rest of the audit is other features' ground.

## What and why

`conventions.md` teaches *"The same verb with one argument **writes**, and hands the object back, so
writes chain"* — and the family where a user chains most is the one where the rule is a coin flip.

**Eleven spellings for "end this".** `:off()` · `:remove()` · `:release()` · `:destroy()` ·
`:drop()` · `:dispose()` · `:cancel()` · `:stop()` · `:finish()` · `:close()` · `:revert()`. Three of
them are used for unrelated kinds: `:remove()` is a collection member **and** a per-widget rule;
`:cancel()` is a schedule, a request **and** an open menu; `:release()` is a mouse grab **and** a
display-switch hold. Reading the sites rather than the names, three real distinctions exist — who
owns it, what kind of ending it is, and whether it is protected — and **none of the eleven spellings
tracks any of the three**.

**Eight of them return `nil` and eight return the receiver**, so a chained ending is a coin flip.
Verified against the code today, after 086: `sub:off`, `timer:cancel`, `w:destroy`, `rule:remove`,
`grab:release`, `req:cancel`, `flowermenu:cancel` and `ov:destroy` return `LuaValue.NIL`;
`toggle:release`, `sheet:drop`, `sound:stop`, `s:close`, `asset:dispose`, `coll:remove`,
`scope:finish` and `item:drop` return the receiver.

**And one collection cannot destroy its member.** `hafen.session()` **is** a `LuaCollection` — mounted
at `SessionApi`, with `current` as its only extra verb — and `LuaCollection` already carries the whole
mechanism: `Source.destroyable()` gates a `:remove(keyOrMember)` that calls `Source.removeMember(x)`
and returns the collection so removals chain. The session Source declares neither, so ending a login
is `s:close()` on the member while every other collection in the API ends a member on the collection.

## Where the row and the page differ

`audit/03-lifecycle.md` proposes **four** verbs — `off`, `remove`, `release`, `cancel` — folding
`w:destroy()` into `hafen.ui():remove(w)`, `sound:stop()` and `scope:finish()` into `:cancel()`, and
`asset:dispose()` into `:release()`. **A-046 records seven**: `off` · `remove` · `release` ·
`destroy` · `cancel` · `stop` · `finish`. The row is what ships, and the rest of the block
corroborates it: **A-048 names `w:destroy` and `timer:cancel` as endings that survive** and only need
their return fixed, and **A-049 says in as many words that "`timer:cancel()` and `sound:stop()` are
unaffected — aborting and silencing are not destroying"**. So `destroy`, `stop` and `finish` stay.

Four spellings leave the teardown vocabulary, and A-047 names three of the moves. The fourth,
`s:close()`, **stays**: A-049 adds `hafen.session():remove(s)` *beside* it, and the page says ending a
login is its own act. `item:drop(n)` was never teardown — it is a protected game action — and
`w:revert()` is an undo of your own layer rather than an ending, so the rule names it as such
alongside `w:replace(nil)` and `w:size(nil)`. **It must not name `slot:hold(nil)`**: that verb is
089's rename of `slot:pagina()`, and if this feature runs first the page would name a verb the bridge
does not have. 089.4 adds it to the list when it creates it.

## Acceptance criteria

1. **Three endings take the vocabulary's own word.** `rule:release()`, `sheet:release()` and
   `hafen.asset():remove(a)`; `rule:remove()`, `sheet:drop()` and `asset:dispose()` each raise naming
   the replacement.
2. **Every ending returns the receiver**, so `x:end():somethingElse()` chains. The seven that answer
   `nil` today — `sub:off`, `timer:cancel`, `w:destroy`, `rule:release`, `grab:release`, `req:cancel`,
   `flowermenu:cancel` — answer their own receiver, and the eight that already do are unchanged.
3. **`ov:destroy()` is untouched**, exactly as A-048 instructs: A-120 replaces it with `:remove(key)`
   in 088, and fixing a return on a verb one feature from deletion is work done twice.
4. **`hafen.session():remove(s)` ends a login**, behind the same `session.close` permission key,
   returning the collection so removals chain; `s:close()` stays beside it.
5. **`conventions.md` states the rule**: seven teardown verbs, which kind of receiver each belongs to,
   that every ending returns the receiver, that where a collection exists the destroy verb is on it,
   and which three acts are not endings at all (`item:drop`, `s:close`, `w:revert` and the
   `nil`-writes).
6. **Every retired spelling raises naming its replacement**, and nothing an addon owns leaks: a
   `:reload` after building a rule, a sheet and an asset drops all three as it does today.

## Out of scope

- **`w:destroy()` becoming `hafen.ui():remove(w)`.** **D2** settled it: the destroy verb goes on the
  collection *where a collection exists*, and `hafen.ui()` is not a collection of your windows. If
  **A-113** (094) makes it one, it follows there.
- **`ov:destroy()`** — A-048 says to skip it, because **A-120** (088) replaces the HUD painter with a
  keyed collection whose ending is `:remove(key)`.
- **`s:flowermenu():cancel()` becoming `:release()`.** `audit/03-lifecycle.md` offers it and A-046
  keeps `cancel` in the seven; dismissing an open menu is aborting something in flight.
- **`slot:pagina(nil)` folding into `:release()`.** The page proposes it and no row carries it. 089
  renames the read half to `slot:hold()`, so what this feature documents is `slot:hold(nil)` as a
  `nil`-write, not an ending.
- **Renaming `rule:close(…)`** — that is the chrome property naming a window's close **button**, not
  an ending, and **A-053** (088) renames it to `:closeButton()`.

## Docs impact

**Written:** `api/conventions.md` · `api/ui/style/README.md` · `api/ui/style/geometry.md` ·
`api/asset.md` · `api/font.md` · `api/session.md` · `api/event/README.md` · `api/event/streams.md` ·
`api/timer.md` · `api/http.md` · `api/flowermenu.md` · `api/ui/widget.md` · `api/ui/mouse.md` ·
`api/map/drawings.md` · `api/map/overlays.md` · `api/references.md` · `guides/theming.md` ·
`guides/custom-ui.md` · `guides/events-and-timers.md`.

**Derived impact set:**

```
grep -rlE ":off\(\)|:remove\(\)|:release\(\)|:destroy\(\)|:cancel\(\)|:stop\(\)|:finish\(\)|:dispose\(\)|sheet:drop|rule:remove|s:close" docs/
```

**32 pages.** The verdicts:

| Page | Verdict |
|---|---|
| `conventions.md` | **revise** — the teardown rule, the return rule, and the collection rule |
| `ui/style/README.md`, `ui/style/geometry.md`, `ui/style/chrome.md`, `ui/style/surfaces.md`, `guides/theming.md` | **revise** — `rule:release()` and `sheet:release()` |
| `asset.md`, `font.md`, `map/drawings.md` | **revise** — `hafen.asset():remove(a)`, and that a font variant and a map image end the same way |
| `session.md` | **revise** — `hafen.session():remove(s)` beside `s:close()` |
| `event/README.md`, `event/streams.md`, `guides/events-and-timers.md` | **revise** — `sub:off()` chains |
| `timer.md`, `http.md`, `flowermenu.md`, `ui/mouse.md`, `ui/widget.md` | **revise** — each ending's return |
| `references.md` | **revise** — the teardown row of its type table |
| `guides/custom-ui.md` | **revise** — every worked example that ends something |
| `client/keybindings.md`, `slash.md`, `ui/replace.md`, `menugrid.md`, `sound.md`, `ui/custom.md`, `ui/edit.md`, `ui/native.md`, `ui/controls/README.md`, `map/overlays.md`, `client/profiling/attribution.md`, `guides/hotkeys-and-commands.md` | **discharge** — each hit is a verb this feature does not move, or `:off()` already correct after 086 |

**No `docs/client/` page is created.** Every ending here is a bridge-owned registry drop; the only
engine call is `Sessions.Member.drop()`, already mapped on `docs/client/multi-session.md`.


## Pages two planned features share

Three of the pages this feature edits are also edited by another planned feature, and each is **at or
over `DOCUMENTATION.md`'s 300-line ceiling** today. None of the three should grow: every change here
is a row edit. Whichever feature runs second inherits §11.2's split if it does grow one.

| Page | Lines today | Also edited by |
|---|---|---|
| `conventions.md` | 291 | **089.5**, which adds the bare-adjective rule |
| `ui/widget.md` | 305 | **088.3**, which renames `w:cell()` and `w:at()` |
| `references.md` | 135 | **088.2** (§Selector) and **089.2** (§Slot) — three sections, no overlap |

## Closing the inventory

`/end` runs **per task** and ticks that task's own rows:

| Rows | Ticked by |
|---|---|
| A-047 | 087.1 |
| A-048 | 087.2 |
| A-046, A-049 | 087.3 |

Nothing else in the file is touched. An id never moves. **No row of another feature's block is
implemented here**, and none of these is implemented elsewhere — 088 owns `ov:destroy` (A-120) and
`rule:close` (A-053), 094 owns `hafen.ui()` becoming a collection (A-113).

```bash
grep -c '^| ☐' audit/INVENTORY.md
```

```bash
comm -23 <(grep -oE 'A-[0-9]{3}' audit/INVENTORY.md | sort -u) <(grep -rhoE 'A-[0-9]{3}' specs/*/spec.md | sort -u)
```

With 086 closed the first prints **74**. When the last task closes it must print **70**, and the
second must no longer name A-046, A-047, A-048 or A-049. **A-011** stays unclaimed for the whole
sweep — D3 struck it at Step 0 — and that is the terminal state.

## Context files

Under `src/io/brodgar/addon/`, tagged with the tasks that need each:

- `LuaRule` (`remove`), `LuaSheet` (`drop`), `AssetApi` (`dispose` in the shared asset verbs, the
  `Disposer`, `teardownAssets`), `MapImages` (its own disposal), `FontApi`/`FontHandle` (a variant is
  not an asset, and carries no ending) — 1
- `LuaSub` (`off`), `AddonManager` (the timer's `cancel`), `LuaWidget` (`destroy`), `LuaGrab`
  (`release`), `HttpApi` (`cancel`), `FlowerMenuApi` (`cancel`), `ProfScope` (`finish` — already
  returns `a.arg1()`, read it as the model), `LuaOverlayToggle`, `LuaSound`, `LuaSession`,
  `LuaCollection` (`remove` returns `me`), `LuaItem` (`drop` returns `self`) — 2
- `SessionApi` (the `hafen.session()` mount and its `Source`), `LuaCollection`
  (`Source.destroyable()`, `Source.removeMember`, and the `:remove` closure that returns the
  collection), `LuaSession` (`close`), `Permission` (`SESSION_CLOSE`), `io.brodgar.session.Sessions`
  (`byuser`, `Member.drop`) — 3
- `Retired` (`put`, `moved`, `message`) — 1, 3
- `LuaHudOverlay` — **read only, in every task**: `ov:destroy()` must not move

Under `audit/`, permitted for this feature and named per task on its *Audit* line: `INVENTORY.md`
(every task) · `03-lifecycle.md` — 1, 2, 3 · `ns-timer.md`, `ns-http.md`, `ns-flowermenu.md`,
`ns-craft.md`, `ns-vr.md` — 2 · `ns-session.md`, `ns-kin.md` — 3.

Pages, by task: `ui/style/README.md`, `ui/style/geometry.md`, `guides/theming.md`, `asset.md`,
`font.md`, `map/drawings.md` — 1 · `event/README.md`, `event/streams.md`, `timer.md`, `http.md`,
`flowermenu.md`, `ui/widget.md`, `ui/mouse.md`, `guides/events-and-timers.md` — 2 · `session.md`,
`conventions.md`, `references.md` — 3 · `guides/custom-ui.md` and `DOCUMENTATION.md` — every task.

Consumers: `addons/eventstack`, `addons/profiler`, `addons/session-manager`, `addons/widgetstack`,
`addons/clickpath` — grep each for `:dispose()`, `sheet:drop`, `rule:remove` and every chained ending
before landing a task.
