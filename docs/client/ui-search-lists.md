# Search lists: `SSearchBox` and its four subclasses

> The model-backed list that filters itself as it is typed into. The list contract under it
> (`SListWidget`/`SListBox`, lazy row widgets, `change(I)`) is [lists, text and scrolling](ui-lists.md).

## `SSearchBox<I, W>` — two models, one view

| What | Where |
|---|---|
| Every row vs. the rows shown | `allitems()` (`protected abstract`) is every row. `items()` is overridden to answer `filtered` while `searching != null`, else `allitems()`. `SListBox` builds its row widgets and its scroll range from `items()`, so a row a search dropped has no widget and no place in the scroll |
| The typing | `keydown`, with no Ctrl/Meta: a printable character → `search(searching + c)`; Backspace → `search(prefix)`; Enter and Escape → `stopsearch()` while a search runs; Up/Down → `change(items().get(p ± 1))` then `display(p)`. Each consumes the key only where it applies |
| `search(String)` | `public`. Empty → `stopsearch()`. Otherwise walks `allitems()`, tests each row with `searchmatch(item, text)` (the fork asks through `AddonWidgets.searchmatch`, `// addon:`), fills `filtered`, sets `searching`; when the current `sel` is not among the kept rows it calls `change` with the first kept row after the old selection (else the first kept, else `null`) — unless the fork's `quiet` (`// addon:`) is set, which `AddonWidgets.search` does around a search an addon makes, so that one picks nothing; then `display(sel)` (a no-op for a row not in `items()`) and `updinfo()` |
| What is typed | `searching`, a `public String`, `null` while no search runs. `stopsearch()` (`public`) nulls it and `filtered`, and `display(sel)`s |
| The counter | `updinfo()` renders `"%s (%d/%d)"` — the text, the rows kept, `allitems().size()` — and `draw` blits it at the bottom right while a search runs |
| Focus | `setcanfocus(true)`; `mousedown` calls `parent.setfocus(this)`; `lostfocus()` calls `stopsearch()`. A search started by calling `search` on a list that never had the focus lasts until the list gains and then loses it, or until `stopsearch` |

> **`search` calls `change`**, the same method a click on a row reaches — so filtering can **pick** a row,
> and on the kin and member lists `change` is a `wdgmsg`. Typing into a search list is not read-only.

## The four subclasses

| Class | Row type | `searchmatch` tests | `allitems()` | `change(row)` |
|---|---|---|---|---|
| `BuddyWnd.BuddyList` (`private`) | `BuddyWnd.Buddy` | `name` contains the text, lower-cased | `buddies` after `sortifneeded()` — the **live** list, which `uimsg` `add`/`rm` mutate under `synchronized(buddies)` | `wdgmsg("ch", id)` (or `null`) on the `BuddyWnd`; it **never writes `sel`** — the server's `i-set` does, and opens `BuddyInfo` beside the list |
| `Polity.MemberList` | `Polity.Member` (an inner class) | `m.name()` contains the text: `rname().text`, the roster's name for `id` through `getparent(GameUI.class).buddies.find(id)`, `unk` (`???`) for a stranger, `self` (`You`) for `id == null` | `mlist`, refilled **in place** (`clear`, `addAll`, sort by name then `order`) from `tick` whenever `Polity.mseq` moved | `wdgmsg("sel", id)`, or a bare `"sel"`, on the `Polity` |
| `MapWnd.MarkerList` | `MapWnd.ListMarker` | `mark.nm` contains the text | `markers`, a list replaced wholesale when the marker set changes | `change2` writes `sel` and rebuilds the marker tools, then `view.center` on the marker: client-side |
| `GobIcon.SettingsWindow.IconList` | `SettingsWindow.ListIcon` | `name` contains the text, `false` for a `null` name | `ordered`, rebuilt from `conf.settings` in `tick` | `super.change`, then the settings box for that icon: client-side |

Every one of them lower-cases both sides and uses `indexOf`, so the test is "contains, in any case".

## Gotchas

| Trap | Detail |
|---|---|
| A member row is replaced, not updated | `Polity.uimsg("add")` for an id already present calls `parsememb(args, old)`, which builds a **new** `Member` through the copy constructor, and puts it in `memb` over the old one. A reference to the old row — a selection, a row kept by a caller — is no longer in the list once the server re-sends that member, which is what a group change does |
| `Member.name()` needs the panel attached | `rname()` climbs to `GameUI` through `getparent`; once the `Polity` is detached that answers `null` and the lookup throws |
| `BuddyList.allitems()` is the live roster | Iterating it off the tree's monitor races the `Loader` thread's `add`, which runs under `synchronized(ui)`; copy it under that monitor |
| The published polity panels subclass `Polity` | `ui/vlg`'s `Village` and `ui/realm`'s `Realm` are server resource code, not in `src/`. Their rows are `Polity.Member`s (the subclasses their own `parsememb` builds), so a hook on `SSearchBox` or on `Polity.Member` reaches them; the list's own class name is theirs to choose, so find the list through a row's parent rather than by that name |
