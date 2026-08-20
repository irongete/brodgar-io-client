# 083 — EventStack: tasks

- [x] **083.1 — The log.** `addons/eventstack/` — `manifest.json` (id `eventstack`, one account-scoped
      saved variable, no permission) and `main.lua` — copied to `bin/addons/eventstack/`. Four sources
      funnel into one `push{src, name, about, t}`: `action():on("*")`, `message():on("*")`, every bus
      catalogue key **but `Update`**, and `s:ui():on("*", "appear"/"disappear")` armed per session at
      `SessionEnteredWorld` and **off by default**, because subscribing replays every widget already
      open. The ring is an array with a head index, capped, never shifted. A layer window holds a
      `hafen.ui():table()` of four columns; a handler only appends and marks dirty, and `Tick`
      rewrites `:rows(t)` at most once a frame and only while dirty and visible. `:eventstack`
      toggles it; its place lives in `hafen.store()` at account scope, not in `widget:remember`.
      Nothing here cancels, rewrites, resends or sends. The clock column is
      `string.format("%d", os.time())` — `tostring` gives `1.7871145E9`.
      *Its suite* declares `widget.send`. It counts `of(row)` calls on a table of its own: a hundred
      rows over two columns is two hundred calls for one `:rows(t)`, and an identical second write is
      two hundred more — the cost the coalescing exists for. It drives a ring of the same shape past
      its cap and asserts the newest survive **in arrival order** and the oldest are gone. Then each
      door, each of which must yield a record whose three fields are non-nil strings: an outbound
      probe, `s:ui():find("@GameUI"):send("brodgar-probe")`, cancelled inside its own wildcard so it
      never reaches the wire; an inbound wildcard and a bus key over a bounded `hafen.timer()` window,
      scoring what the run reached; and `s:ui():on("*", "appear", fn)`, which fires at once for what is
      already open. It asserts `string.format("%d", os.time())` carries no `E`. Its refusal: `pcall`
      `:columns(t)` on a table already on screen, which must say columns are chosen while the table is
      being built.
      `[manual]`: type `:eventstack`, then move, open a window and click something. Report whether the
      newest line is at the bottom, whether the client stayed smooth with every source on, and whether
      the window came back where you left it after a full client restart.
      <!-- extra context: docs/addons/guides/permissions.md — the suite declares widget.send -->

- [x] **083.2 — The filters fill themselves.** Two dropdowns above the table — one over the source,
      one over the name — each starting as `(all)` **alone**. A value not in that axis's `seen` set
      appends a row, rewrites `:rows(t)`, and **writes the current pick back**, because `:rows(t)`
      clears the selection. A checkbox per source arms and disarms its subscription. The table's rows
      are the ring narrowed by both picks. `docs/addons/examples.md` gains EventStack's row and
      section and loses the prose counts at `:3`, `:15` and `:17` — the bundled tools are `profiler`,
      `eventstack` and `widgetstack`, beside `session-manager`, which that page already holds apart as
      a surface of the client's own; `docs/addons/README.md`'s index row stops enumerating a set it
      cannot keep; `docs/addons/guides/debugging.md` sends the reader here for the question no other
      tool answered. The `spec.md` impact set is discharged page by page, with the `DOCUMENTATION.md`
      §11 checks over every page 083 touched.
      *Its suite* proves the trap rather than describing it: a dropdown at `:rows{"(all)", "a"}` with
      `:value("a")`, then `:rows{"(all)", "a", "b"}`, must read `:value()` as `nil` — and writing
      `"a"` back must restore it, which is the whole of the rule the filter obeys. Over the stream
      `a, b, a, a, c, b` a `seen` set must end as exactly `a, b, c` in first-seen order, one entry per
      name and never a second. The filter predicate over a ring: both picks at `(all)` keeps every
      record, a source pick keeps that source alone, a name pick that name alone, and the two together
      the intersection. Its refusal: `:value(v)` naming a row that is not in the current set must be
      refused **naming the rows that are**.
      `[manual]`: with the window up, pick a source and a name, and report that the table narrows to
      them. Then leave it running a minute, until names you had not seen have arrived, and report
      whether your two picks are still selected — that is the whole defect this task exists to
      prevent.
