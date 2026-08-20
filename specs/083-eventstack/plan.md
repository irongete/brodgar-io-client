# 083 — EventStack: plan

## Approach

**One record, four producers.** Every source funnels into one `push` with the same four fields:
`src` (`out` · `in` · `bus` · `widget`), `name` (the message name, the bus key, or the widget's
class), `about` (what it was about — the sending or receiving widget's `:type()`, or a description
the payload itself gives), and `t`, the wall clock. One shape means one filter predicate, one column
set and one table, however differently the four doors are addressed.

**The ring is an array with a head index**, never a table that shifts. Append at `head`, wrap at
`CAP`, read out in arrival order from `head + 1`. Dropping the oldest by shifting would copy the
whole ring on every message, on a stream that fires several times a frame.

**Nothing is drawn from a handler.** A handler appends and sets `dirty`; the window's `Tick`
rebuilds `:rows(t)` at most once a frame, and only while `dirty` and the window is visible. This is
what acceptance criterion 5 exists to justify — `of(row)` runs once per row per `:rows(t)` write, so
a write per message is O(n) per message.

**The filters are two dropdowns whose row sets grow.** Each keeps a `seen` set; a name not in it
appends a row, rewrites `:rows(t)`, and **immediately writes the previous `:value(v)` back**, because
`:rows(t)` clears the selection. Row 1 of each is `(all)`.

**The four doors:**

| Source | Subscribed with | `name` | `about` |
|---|---|---|---|
| `out` | `hafen.event():action():on("*", fn)` | `ev:msg()` | `ev:sender():type()` |
| `in` | `hafen.event():message():on("*", fn)` | `ev:msg()` | `ev:target():type()` |
| `bus` | `hafen.event():on(k, fn)` for each catalogue key **but `Update`** | `k` | per key |
| `widget` | `s:ui():on("*", "appear"/"disappear", fn)`, per session | the widget's `:type()` | `appear` or `disappear` |

**EventStack never touches the traffic.** No `preventDefault`, no `rewrite`, no `resend`, no `send`,
anywhere — stated in the file's own header, because an inbound wildcard that cancels stops the
client outright and a log is the last place to put one.

## Files to create and modify

| File | What it is |
|---|---|
| `addons/eventstack/manifest.json` | id `eventstack`, one file, no permissions and no saved-variable but the account-scoped one below |
| `addons/eventstack/main.lua` | the whole addon |
| `addons/083-eventstack.1/`, `addons/083-eventstack.2/` | the two suites, each copied to `bin/addons/` to be run |
| `docs/addons/examples.md` | the row, the section, and the prose counts that break |
| `docs/addons/README.md` | the index row that enumerates the bundled set |
| `docs/addons/guides/debugging.md` | the tool that answers a question none of the others did |

No `README.md` in the addon folder: `session-manager` ships one because it claims an unbound hotkey
the user must assign, and `:eventstack` needs no such sentence.

## Risks and gotchas

- **`os.time()` is a Lua double, and LuaJ prints one in scientific notation at eight significant
  digits** — `tostring(os.time())` is `1.7871145E9`, the same string for a hundred seconds either
  side, and `string.format("%.0f", …)` does not convert at all. `string.format("%d", os.time())` is
  the only spelling that gives the number back. A log's first column walks into this.
- **`dropdown:rows(t)` clears the selection**, so every filter repopulation must write the pick back.
  This is the one defect that would look like the feature working: it only bites when a name arrives
  that the user has not seen, which is exactly when they are watching something.
- **`:columns(t)` and `:rowHeight(n)` are building-only** and refuse once the table is on screen, so
  the columns are fixed at build and only `:rows(t)` moves afterwards.
- **`s:ui():on(sel, event, fn)` hands back a handle ended with `:remove()`**, not `sub:off()` — the
  one `:on` in the API shaped that way.
- **`appear` fires for what is already in the tree** when you subscribe, so arming the widget source
  replays every open widget of that session at once. It starts **off** for that reason, and it is
  armed per session, at `SessionEnteredWorld`.
- **At `disappear` the widget is a key, not something to read.** Keep the class from `appear` in a
  table keyed by the widget — they are interned, so `==` and a table key both work — and read
  `about` out of that.
- **Bus payloads are heterogeneous**: a `Gob`, a `Meter`, a `Session`, an array of captions. `about`
  is produced per key, never by one generic call, and sixteen keys carry the `Session` as their
  **last** argument. `GobRemoved` hands a gob that is already gone, where only `:id()` answers.
- **An inbound handler runs on a Loader thread under the `ui` monitor**, which is the lock the client
  takes to tick and draw. Its body is an append and nothing else.
- **The addon layer has no search verbs**, so no suite can find EventStack's window. Both suites
  drive the same API the addon drives and assert the invariants there, as `session-manager`'s did.
- **`widget:remember(name)` files a placement under the character on screen**, so it is wrong for a
  window that lives in the layer. The place goes in `hafen.store()` at account scope.

## Discarded alternatives

- **Painting the log with `g:text` on a bare surface** — every column position becomes arithmetic
  that a stylesheet change silently breaks, where a table is inside the theme from the first frame.
- **One `:rows(t)` per message** — the table resolves every cell of every row on each write, so a
  full log would re-resolve the whole window on a stream that fires several times a frame.
- **Shifting the array to drop the oldest line** — copies the whole ring per message for an answer a
  head index already has.
- **A text box instead of the two dropdowns** — the premise is that the names are unknowable in
  advance, so a box asks the user to type a name they have not seen yet; a set that fills itself is
  the only filter that can offer one.
- **Logging `Update`** — one line per frame, carrying only the news that a frame happened.
- **Enumerating the message names rather than subscribing `*`** — a hand-written list cannot show a
  name the server introduces, which is the whole reason the wildcard exists.
- **`widget:remember(name)` for the window's place** — it files under whichever character is drawn,
  so where the user dragged a window of the layer's would land in one character's folder and read
  back out of another's.
- **A window per source** — four windows to arrange for one question, when the source is a column and
  a filter over it.
