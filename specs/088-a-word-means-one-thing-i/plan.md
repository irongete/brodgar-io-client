# 088 — A word means one thing I: plan

## Approach

Five tasks, ten rows, every change a row's own replacement text. **Nine of the ten are pure renames**
— `Retired` carries them, the free half of `CLAUDE.md`'s hard-cut rule, and every receiver is
`closedIndex`-backed after 084 and 086, so a retired spelling raises at the line that wrote it for a
call and for a field read alike. **One is a reshape**: A-120 turns the HUD painter from an anonymous
builder into a keyed collection, and that one needs a page line as well as rows.

The tasks are independent; the numbering is the reading order.

### 1 — `:list()` enumerates, and nothing else (A-050, A-057)

`UiApi`'s `list` builds a `haven.Listbox`-backed control through `Controls.list` and `UiApi.attach`
adds it to the tree **at once** — so the wrong guess draws. It becomes **`listbox`**, the client's own
class name, sitting beside `dropdown` and `menu`. `VrApi`'s `list` builds a `LuaTable` array over
`allEntities(owner)`; it becomes **`hafen.vr():entity()`**, a `LuaCollection` over the same
`allEntities` with the quartet and `:remove(x)`, so `hafen.vr():count()`'s missing answer arrives as
`hafen.vr():entity():count()`. `hafen.vr()` cannot itself be a collection — it holds five things, four
kinds and the section switch — which is the row's own reason for a named sub-collection. `VrApi`'s
`pointer` becomes **`click`**, a verb rather than a noun, keeping its boolean return.

### 2 — The selector language has its own verb (A-119)

Four closures — `UiApi`'s `find` and `all`, `LuaWidget`'s `find` and `all` — become `match` and
`matchAll`. **D3**'s reason: `select` is taken (`s:world():select`, `s:flowermenu():select`), and a
verb named for the selector grammar makes `match("Cupboard")` obviously a role rather than a title.
`:matchAll` also closes the `:list`-vs-`:all` split, so "give me all" is one word per language: `:list`
enumerates a collection, `:matchAll` runs a selector.

The two existing `Retired.uiMoved` keys for `find` and `all` — the rows that tell someone writing
`hafen.ui():find` to use `s:ui():find` — must go on firing, now naming `s:ui():match`.

### 3 — A place, a size and a hit test (A-051, A-052)

`LuaWidget`'s `cell` → `Controls.cell` is a `{w=, h=}` **box** and becomes **`cellSize`**, matching
`w:rowHeight(n)`, the verb it sits beside. `LuaItem`'s `cell` **keeps the word**: a cell in an
inventory is a place in the game's own vocabulary, and its snapshot field is `cell`.

`UiApi`'s `at` and `LuaWidget`'s `at` are hit tests — the **deepest** widget under a point — and become
**`hit`**, which pairs with the mouse's `:over()`. `WorldApi`'s `extra.at` keeps the word: addressing a
member by a place is what `:at(x)` should mean, and `s:world():grid():at(p)` is the one site that means
it. `Layout`, `Chrome` and `Stock` use `at` as a **table key** in a declarative document, not as a
verb, so they do not collide at a call site and stay.

### 4 — `:overlay()` means one thing (A-054, A-120)

`MapApi`'s `overlay` — `section(owner, "overlay", toggles)`, the minimap's display switches — becomes
**`display()`**. `LuaMapGrid`'s `overlay` — the recorded masks — becomes **`mask()`**, which is already
what the object is called (`LuaMask`, `mask:covers(c)`). `gob:overlay()` is untouched: it is the
client's own term and it owns the type page.

Then A-120. `UiApi.newHudOverlay` hands back a `LuaHudOverlay` carrying exactly three verbs —
`onDraw`, `destroy`, `exists`. It becomes a **collection** with `gob:overlay()`'s shape: `:add(key)`,
`:get(key)`, `:remove(key)`, `:list/count/find(filter)`, and `:draw(fn)` on the member. `:list()` is
the draw order. `Addon.hudOverlays` is already the per-addon list the collection's `Source` walks.
`:onDraw(fn)` retires onto `:draw(fn)` and `ov:destroy()` onto `:remove(key)` — **D2**, and the row
notes that `LuaCollection.meta` already consults `Retired`, so all three retirements fire.

### 5 — Three words freed (A-053, A-055, A-056)

`LuaRule`'s `close` is the close **button**'s art and becomes **`closeButton`**; `Sheet`/`Chrome`'s
`close` property key follows, so a document and a call say the same word. `LuaPagina`'s `path` is an
array of category names and becomes **`categories()`**, `menugrid.md`'s own word, leaving `:path()` to
mean a file path across the API; the snapshot field `path` stays, being the client's own shape.
`LuaEvent`'s `sender` and `target` are — by its own comment — *"the same widget, named for the
direction it is on"*, and become **`widget()`** on both kinds; the direction is already named by which
stream you subscribed on. Both `Kind` hints in the enum move with them.

## Files to create/modify

**Bridge**, under `src/io/brodgar/addon/`:

| File | Change | Task |
|---|---|---|
| `UiApi` | `list` → `listbox`; `at` → `hit`; `find`/`all` → `match`/`matchAll`; `overlay` becomes a collection | 1, 2, 3, 4 |
| `Controls` | `list` (the builder it calls), `cell` | 1, 3 |
| `VrApi` | `list` → an `entity()` collection over `allEntities`; `pointer` → `click` | 1 |
| `LuaWidget` | `cell` → `cellSize`; `at` → `hit`; `find`/`all` → `match`/`matchAll` | 2, 3 |
| `LuaItem`, `WorldApi` | **read only** — `item:cell()` and `grid():at(p)` keep their words | 3 |
| `MapApi` | `overlay` → `display` | 4 |
| `LuaMapGrid` | `overlay` → `mask` | 4 |
| `LuaHudOverlay` | `onDraw` → `draw`; `destroy` goes; the type becomes a collection member | 4 |
| `Addon` | `hudOverlays` is the collection's `Source` | 4 |
| `LuaRule`, `Sheet`, `Chrome` | `close` → `closeButton`, verb and property key | 5 |
| `LuaPagina` | `path` → `categories` | 5 |
| `LuaEvent` | `sender`, `target` → `widget`; both `Kind` hints | 5 |
| `Retired` | ten rows, plus the two existing `uiMoved` keys re-pointed | every task |

**Pages** — the list and the row each task owns is in `spec.md` §Docs impact.

## Risks and gotchas

**Two addons emit Lua source containing the old spellings.** This is the trap a call-site grep misses:

- `addons/widgetstack` builds paste-ready lines — `('%s:find("%s")'):format(SPELL, c.sel)` and
  `('%s:all("%s")[%d]'):format(SPELL, c.sel, c.idx)`. Those are **string literals**, so the tool goes
  on compiling and hands the user a line that raises when they paste it.
- `addons/eventstack` builds a subscription snippet containing
  `':current():ui():find("@' .. r.wclass .. '")'`.

Fix the strings as well as the calls, and grep `addons/` for the **quoted** spellings, not only the
called ones.

**`widgetstack` is the heaviest consumer in the tree.** It calls `hafen.ui():at(mx, my)`,
`s:ui():all(…)` on its hot path, `:find(sel)`, `w:cell()` in its property table and
`hafen.ui():overlay():onDraw(drawOutline)` — so tasks 2, 3 and 4 each break it. `CLAUDE.md` says the
two tools are fixed when a change breaks them; budget for it rather than discovering it.

**`eventstack` builds two listboxes** (`hafen.ui():list():parent(w)…`), and both `clickpath` and
`widgetstack` install a HUD painter through `:onDraw`. Task 1 and task 4 each carry an addon fix.

**`hafen.ui():list()` must not build before it refuses.** The whole severity of A-050 is the side
effect, so the retirement has to fire from the section's `__index` — before `Controls.list` runs —
not from inside the builder. `Retired.moved("ui", "list", …)` is registered on the section table's
index and does exactly that; verify it by asserting no widget appears, not only that a message came
back.

**`at` is a table key in three files and a verb in three others.** `Chrome`, `Layout` and `Stock` set
`"at"` as a **document** key (a corner name in a declarative anchor); `UiApi`, `LuaWidget` and
`WorldApi` set it as a verb. Only the two hit tests move. A blind rename across the package breaks
every stylesheet anchor.

**`hafen.vr():entity()` must not double-walk.** `allEntities(owner)` is the same walk the array did;
`LuaCollection` does not cache, so check `:count()` does not run the filter once and the count again.

**The HUD painter's draw order is its list order.** A-120 says `:list()` is the draw order, so the
collection's `Source.members()` must preserve `Addon.hudOverlays`' insertion order rather than sorting
by key — a `:get(key)` lookup is a scan, which is right for a list this size.

**`ov:destroy()` is being replaced, not fixed.** 087's A-048 explicitly skips it for this reason. If
087 has already run, confirm it left `ov:destroy()` returning `nil`; if it has not, nothing here
depends on it.

**Three pages this feature edits are at or over the ceiling** — `ui/widget.md` 305,
`menugrid.md` 300, `ui/style/chrome.md` 333. This feature only **edits rows**; it must not grow them.
If a row's replacement needs a sentence, it goes where the verb lives, not on a page already over.

**`ui/lists.md` is named for the verb.** Renaming `list` to `listbox` moves the page's title and every
heading, so **every inbound anchor into it changes**. Count them before and re-point them in the same
task, per `DOCUMENTATION.md` §9.

**The build hides a moved symbol.** `rm -rf build/classes` before believing a green build.

## Discarded alternatives

- **`hafen.ui():painter()` for the HUD overlay** — `audit/ns-map.md` F4 offers it, and the maintainer
  chose the opposite when A-120 was written: keep the word `overlay` **and make it mean one thing**,
  so that the HUD reads like the world and a developer who learned `gob:overlay()` already knows this
  one. A rename would have left two shapes under two names; A-120 leaves one shape under one name.
- **`hafen.map():switch()` instead of `:display()`** — both are in the finding and the row names
  `:display()`. `switch` also reads as the act of switching rather than as the set.
- **`rule:button("close", …)`** — the finding's second option, folding the close art into a chrome
  vocabulary. A-053 names `:closeButton(…)`, and a generic `:button(name, …)` would need a closed name
  set of its own to keep a typo loud.
- **`pag:ancestry()`** — the finding's first option; `menugrid.md` already calls them "the categories
  above this entry", so `:categories()` is the page's own word and the row names it.
- **`:select`/`:selectAll` for the selector verbs** — `select` is taken twice
  (`s:world():select`, `s:flowermenu():select`), which is why **D3** chose `:match`/`:matchAll`.
- **Keeping `:find`/`:all` and making `:list()` an alias of `:all()` on the UI** — the finding's third
  option, and it is explicit that this "is smaller and does not fix the argument ambiguity". The
  silent miss is a selector language wearing a filter's verb; an alias leaves it.
- **`hafen.vr():deliver(…)` instead of `:click(…)`** — both are in the finding; the row names
  `:click(…)`, and it is what the act is from the panel's side.
- **Making `hafen.vr()` itself a collection** — it holds five things, four entity kinds and the section
  switch, so the cross-kind set needs its own name. That is the row's own reason.
- **Renaming `item:cell()`** — a cell in an inventory is a place in the game's own vocabulary, its
  snapshot field is `cell`, and A-051 renames only the size.
- **Renaming `s:world():grid():at(p)`** — addressing a member by a place is what `:at(x)` should mean;
  A-052 frees the word *for* it rather than from it.
- **Moving `w:chrome()`'s `close` key with the property** — the snapshot reports where a window's
  decoration DREW its ornaments, whether or not a rule was written, so it is the client's own shape and
  AC8 keeps its spelling. A-053's blast radius names the closure and the `Sheet`/`Chrome` property key
  and stops there. The same reading `item:cell` and `Pagina.path` get.
- **Renaming `grid:overlayImage(tag)`** — A-054 names the two collections and nothing else, and the verb
  draws one *overlay resource's* recorded mask in that resource's own colour, which is the server's word
  for it. Its messages name `grid:mask():list()` as the census, so the pair reads as one subject.
