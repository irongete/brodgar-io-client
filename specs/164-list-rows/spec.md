# 164 — The rows of the client's own lists

## What & why

An addon reaches a client list and its row widgets, but not what the list holds: a list builds widgets only
for the rows on screen, a row paints its name instead of holding a label, and the row `widget:value()` hands
out is opaque. So an addon cannot find a village member by name or group, rebuild a list, or take part in
its search.

Four client lists are **search lists** (kin, polity members, map markers, map icons): typed into, they keep
the rows whose name contains the text. This feature opens every client list's rows and those four searches:

- A row of a client list is a **Row**. `row:text()` is the text its list searches by (`nil` on other
  lists). `row:group()` is the group
  `widget:group()` reads on its widget. `row:info()` is the snapshot. A Row compares with `==`, keys a table,
  and is the same value from every door; a plain row (a string, a number) crosses as itself.
- `widget:rows()` answers on a client list: every row it holds, in its order, the rows off screen and the ones
  a search left out included.
- `widget:row()` on a list's row widget: the Row it draws. `nil` on any other widget.
- `widget:value()` on a client list and `event:value()` of its `Changed`/`Selected` hand a Row.
- On a search list: `widget:search()` reads what it is filtered by, `nil` when no search runs.
  `widget:search(text)` filters it as typing does but picks no row, so nothing reaches the server and it needs
  no key; `""` ends the search.
  `widget:on("Search", fn)` fires once per row while the list searches: `event:row()`, `event:text()`,
  `event:match()` (the verdict as it stands, starting at the client's own test) and `event:match(b)`. A row is
  kept when a handler said `true`; otherwise dropped when one said `false`; otherwise the client's test decides.

## Acceptance criteria

All by `:t164-1`, run with the Kin window's list in the tree (it is, from login) and the Village window open.

1. `kin_list:rows()` holds as many rows as `session:kin():count()`, and row n's `:text()` and `:group()` equal
   kin n's `:name()` and `:group()`.
2. Two reads hand back `==` rows, and a row one read stored in a table is found with the other's.
3. `row:info()` carries the same `text` and `group`; `row:text(1)` raises naming the arity.
4. `:rows()` on a client widget that is no list reads `nil`; `kin_list:rows({})` raises naming `widget:rows()`.
5. `kin_list:search()` reads `nil`; after `:search(sample)` it reads `sample`; after `:search("")` `nil` again;
   `:search(nil)` raises naming `""`; `:search("x")` on a list that does not search raises.
6. With a `Search` handler on each open search list, `:search(sample)` fires it once per row; every
   `event:text()` is `sample`, and every first `event:match()` is whether that row's `:text()` contains
   `sample`, any case — which is the client's own test on each of the four lists.
7. With one handler dropping every row and a second keeping one, the search keeps that row: the kin list draws
   it alone, and `kin_list:value()` is what it was before — an addon's search picks nothing.
8. On the kin and member lists, every row widget's `:row()` is among `:rows()`, and its `:group()` equals the
   widget's own `:group()`.
9. The drawn kin list shows the one kept row and `(1/N)` in its corner, and lists everyone again once it loses
   the keyboard (`[manual]`).

## Out of scope

- **A text for other lists' rows** (quests, wounds, characters, the action search): they draw more than a
  name and nothing searches them by one.
- **Which rows a search kept**: those are the verdicts, which a `Search` handler already holds.
- **Picking a row by its text**: a row is picked with the Row, `widget:value(row)`.
- **A search in a list you build**: filter the table you give `:rows(t)`.

## Docs impact

Pages written: `docs/addons/api/ui/lists.md` (the client's lists: rows, Row, search), `widget.md` (the
`:rows()`, `:row()`, `:search()` reads and the `Search` key in the *Controls* rule), `edit.md` (a client
list's row is a Row), `writes.md` (`:search(text)` among the borrowed writes, unprotected). No key is
added. Engine map: a new `docs/client/ui-search-lists.md`
(`SSearchBox` and its four subclasses), indexed in `docs/client/README.md`.

Derived impact set — `grep -rn -i "opaque handle\|nothing inside to read\|row of one of the client\|client's own lists\|\`:rows()\`\|Pressed\`, \`Changed\`, \`Submitted\`\|widget\.\*\` | \`widget" docs --include=*.md`:

- `docs/addons/api/ui/edit.md:119` — "a row … is an opaque handle … nothing inside to read": rewritten.
- `docs/addons/api/ui/widget.md:91` — `:rows()` "the row source of … a row-source control": gains the client list.
- `docs/addons/api/ui/widget.md:162` — the capability keys: gains `Search`.
- `docs/addons/api/ui/selectors.md:177` — `:rows()` among the reads that "say nothing on a client control":
  a client list's `:rows()` answers now.
- `docs/addons/guides/permissions.md:79` — the `widget.*` group: no key is added; no edit.
- `controls/README.md:47`, `client/addon.md:85`, `writes.md:81` — your own controls; no edit.

## Context files

- `src/haven/SSearchBox.java`, `SListWidget.java`, `AddonWidgets.java` (`listHas`, `listChange`) — 1
- `src/haven/BuddyWnd.java`, `Polity.java`, `MapWnd.java`, `GobIcon.java` (the four search lists) — 1
- `src/io/brodgar/addon/LuaWidget.java` (`value`, `rows`, `group`, `widgetKeys`) — 1
- `src/io/brodgar/addon/Controls.java` (`borrowedKeys`, `dispatch`, `drive`) — 1
- `src/io/brodgar/addon/LuaEvent.java` (the `CONTROL` shape's `value`) — 1
- `src/io/brodgar/addon/LuaRow.java`, `LuaSearchEvent.java` (the Row, the search event) — 1
- `src/io/brodgar/addon/Addon.java` (the per-addon metatables) — 1
- `src/io/brodgar/addon/Subs.java` (`fire`), `AddonManager.java` (`enterLua`, the seam doors) — 1
- `tools/docverbs.py` (`event_keys`, `PER_FILE`) — 1
- The pages named under *Docs impact* — 1
