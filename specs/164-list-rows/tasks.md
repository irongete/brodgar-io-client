# 164 — Tasks

- [x] **164.1 — The rows of the client's lists are Rows, and its search lists take part in a search.** New
      `LuaRow` (`row:text()`, `row:group()`, `row:info()`, one mint for every door) and `LuaSearchEvent`
      (`event:row()`, `event:text()`, `event:match()`/`:match(b)`); `widget:rows()` reads a client list whole,
      `widget:row()` a row widget's row, `widget:value()` and a list's `Changed`/`Selected` hand a Row;
      `widget:search()`/`:search(text)`, unprotected, the write filtering through `AddonWidgets.search` with
      `SSearchBox.quiet` so it picks no row and sends nothing; `Search` on the four `SSearchBox` lists through
      one `// addon:` line in `SSearchBox.search`. `widget:group()` shares `LuaRow.group`. `docverbs.py` knows
      `Search` and the Row. Docs as `spec.md`'s *Docs impact*; the engine map gains `ui-search-lists.md`.
      Criteria 1–9.
      *Its suite* `addons/164-list-rows.1/`, `:t164-1`, declares no key. It reads the kin list against
      `session:kin()` row by row (1), stores a row in a table and finds it with a second read's row (2), reads
      `:info()` and `pcall`s `row:text(1)`, `:rows({})` and `:search(nil)`, each refusal naming its reason
      (3–5), reads `@GameUI`'s `:rows()` as `nil` (4), drives `:search(sample)` on every open search list
      with a `Search` handler counting rows and comparing each first verdict to a plain `find` on
      `row:text():lower()` (6), then two handlers — one dropping all, one keeping the last row — and polls
      the kin list's row widgets for that row alone, with `:value()` unchanged (7), and joins every
      `@ItemWidget`'s `:row()` to `:rows()` on the kin and member lists (8). It ends every subscription it made.
      `[manual]`: the kin list shows only the kept kin with `(1/N)` in its corner; clicking the list and then
      anywhere else lists everyone again (9).
