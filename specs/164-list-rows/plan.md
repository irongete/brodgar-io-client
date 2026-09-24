# 164 — Plan

## Approach

**A Row is the list's own row object with a metatable.** `widget:value(row)` hands `touserdata()` straight to
the list (`Controls.drive` → `AddonWidgets.listHas`/`listChange`), so the userdata's instance stays the row
itself; what makes it a Row is the owner's per-addon metatable. LuaJ compares two userdata by instance and
metatable (`==`, `rawequal` and a table key all hold), so no intern cache is needed. `LuaRow.of(owner, row)` is
the one mint: `null` → `nil`, a `String`/`Number`/`Boolean` → itself through `LuaMarshal.toLua`, anything else
→ `LuaValue.userdataOf(row, meta(owner))`.

**The readers answer from the row object.** `row:text()` is what each search list's own `searchmatch` reads:
`BuddyWnd.Buddy.name` (under the buddy's monitor, as `LuaKin` reads it), `Polity.Member.name()` (the roster
lookup; `???`/`You`; `nil` if the panel has left the tree), `MapWnd.ListMarker.mark.nm`,
`GobIcon.SettingsWindow.ListIcon.name`. `row:group()` is the arm `widget:group()` already has, moved into
`LuaRow.group(Object)` and called by both.

**Every row, through the list.** `AddonWidgets.listRows(SListWidget)` copies `allitems()` on an `SSearchBox` and
`items()` elsewhere, both `protected`; `widget:rows()` reads it under the tree's monitor (`MemberList.tick`
refills `mlist` in place) when the widget is a client `SListWidget`. `widget:row()` reads
`SListWidget.ItemWidget.item`.

**The search.** `SSearchBox.search` calls `searchmatch` per row; that call becomes
`AddonWidgets.searchmatch(this, item, text)` (`// addon:`), which computes the client's own verdict and asks
`AddonManager.searchmatch` → `Controls.search`. That walks `AddonManager.addons` (+ the console owner) like
`dispatch`: an addon with no live `Search` on this widget costs one lookup; the first holder mints a shared
`Controls.Verdict(own)`, and each holder's `Subs.fire("Search", …)` gets a `LuaSearchEvent`. The rule is
order-free: kept if any handler said `true`, else dropped if any said `false`, else `own`. `event:match()`
reads the verdict as it stands.

`widget:search(text)`: the argument (`Args.str`, so `nil` raises naming `""`), then the stale no-op, then a
non-`SSearchBox` refusal; under the monitor, `AddonWidgets.search(list, text)`: `stopsearch()` for `""`,
otherwise `search(text)` — the method a keystroke reaches, so every addon's `Search` runs — with the fork's
`SSearchBox.quiet` set around it, which skips the pick a keystroke's search makes. The pick is a `wdgmsg` on the
kin and member lists; without it an addon's search sends nothing and writes the client only, like
`:visible(b)`, so it takes no key.

## Files to create/modify

- **Create `src/io/brodgar/addon/LuaRow.java`** — `of`, `all(owner, SListWidget)`, `text(Object)`,
  `group(Object)`, the metatable (`closedIndex("row", …)`, `__name` `Row`, `__tostring` `Row(<text>)`) and
  `text`/`group`/`info`, each `Args.only(a, 0, "row:…")`. A receiver is a Row when its metatable is the
  owner's `rowMeta`.
- **Create `src/io/brodgar/addon/LuaSearchEvent.java`** — `row`, `text`, `match` (read, and write with
  `Args.bool`), `closedIndex("ev", …, "a search event")`, metatable on `Addon.searchEventMeta`.
- `Addon.java` — `rowMeta`, `searchEventMeta`.
- `Controls.java` — `borrowedKeys`: an `SSearchBox` answers `Changed` and `Search`; `Verdict`; `search(…)`.
- `AddonManager.java` — the `public static boolean searchmatch(Widget, Object, String, boolean)` door.
- `LuaWidget.java` — `:value()` on a client list mints through `LuaRow.of`; `:rows()` reads a client list,
  `:rows(t)` on one refuses naming `widget:rows()`; new `:row()` and `:search()`; `:group()` calls
  `LuaRow.group`.
- `LuaEvent.java` — the `CONTROL` shape's `value` mints a Row when its actor is an `SListWidget`.
- `src/haven/AddonWidgets.java` — `listRows`, `searchmatch`, `search`. `src/haven/SSearchBox.java` — the
  seam line, and the `quiet` field its pick checks.
- `tools/docverbs.py` — `Search` among the control keys; `lists.md` maps `row` to the Row.
- Docs as `spec.md` names; **create `docs/client/ui-search-lists.md`** (the engine had no page on
  `SSearchBox`: `allitems` vs `items`, `searching`, `search`/`stopsearch`/`lostfocus`, each subclass's
  `searchmatch` and `change`, `Member` replaced on a re-`add`, `BuddyList.change` not writing `sel`).
- **Create `addons/164-list-rows.1/`** — the suite.

## Risks & gotchas

- `BuddyList.change` sends `ch` and never writes `sel`; the server's `i-set` does. An addon's search picks
  nothing, so criterion 7 reads the row widgets the list rebuilds on its own tick instead of a selection.
- `Polity` re-`add`s a member as a new `Member` (`parsememb` copies it), so a held Row stops being the list's.
  Documented: read the list again rather than keep rows.
- `SSearchBox.lostfocus` ends a search; a search an addon started on an unfocused list stays until the list
  gains and loses the keyboard, or `:search("")`.
- `search()` runs on the UI thread from `keydown`, inside the tree's monitor; `enterLua` there only tries the
  lock, so a handler whose addon is busy on another thread at that instant is skipped for that row and the
  verdict stands.
- Per-row dispatch: with no holder it is one map lookup per addon per row, per keystroke.

## Discarded alternatives

- **Wrapping the row in a Java object of our own**: `widget:value(row)` passes `touserdata()` to the list,
  which must find its own row there; the metatable is enough to make it a Row.
- **An intern cache for rows**: LuaJ already compares userdata by instance and metatable, so `==` and table
  keys hold without one.
- **Reading the text off the drawn row widget**: a row off screen has no widget; the object is what the list
  searches, so it is what the text comes from.
- **The handler's return value as its verdict**: no handler's return value is read anywhere in the API, and a
  verdict several addons give needs a rule that does not depend on their order.
- **Replacing the client's test outright**: starting from the client's verdict lets an addon that only widens
  or only narrows the search leave every other row as the client matched it.
- **`:rows()` answering the rows a search kept**: rebuilding a list needs every row; the kept ones are the
  verdicts, which the handler already holds.
- **`:search(text)` picking a row as a keystroke does, behind a key of its own**: an addon's search is a
  filter, and the pick is what reaches the server (`ch`, `sel`). Filtering alone writes the client only, so
  it needs no key; the maintainer's call.
