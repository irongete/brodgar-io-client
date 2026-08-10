# 007.1 — The census, both directions

> One row per grammar fact `Selector` enforces, per door `UiApi`/`LuaWidget` opens and per consumer
> `LuaSelectorWatch`/`Sheet` drives: the page that states it, and the verdict. Then the reverse — every
> selector claim the ten pages `049` wrote, against the file that proves it. `CUT` and `N/A` are
> first-class: a fact the tier deliberately does not state is `CUT`, not a `GAP`.

## Method & ground truth

- **Ground truth is `src/io/brodgar/addon/`**, never `049`'s spec: a page written the day a task landed can
  have frozen that task's *expectation*, and `049.1`..`049.5`'s close notes record exactly where the
  expectation and the code diverged (005.2). `haven/Fonts.java` and `haven/Window.java` were read for the
  scope list and the caption field; `addons/widgetstack/main.lua` is the file that proves every claim the
  pages make about the inspector.
- **Every selector string on the ten pages was driven through the real `Selector.parse`**, not read:
  `ParseCheck` (scratch, package `io.brodgar.addon`, compiled against `build/classes` + LuaJ) over a
  49-string list with an expected verdict per string — **49/49, 0 mismatches**. A form the page writes with
  an ellipsis is driven **concretised** (`[title=…]` → `[title=Cupboard]`), which is the only way notation
  can be checked at all. The harness was falsified four ways in one control run — a valid string expected
  `ERR`, and three refusals expected `OK` (bare `[title=]`, an unknown role, an unknown refiner key) — all
  four reported `[FAIL]`, exit 1.
- Verdicts: `OK` · `WRONG` (states something the code does not do) · `THIN` (true but reads as a rule it is
  not) · `GAP` (a shipped fact no page states) · `INVENTED` (a page names what does not exist) · `CUT` (the
  tier deliberately does not state it) · `N/A` (not this surface).

## 1. The grammar (`Selector.java`)

| Fact | `src/` | Page | Verdict |
|---|---|---|---|
| `selector := step (WS+ step)*` — the space is the descendant combinator | `:216-235` | `selectors.md:65` | **OK** |
| `step := ('*' \| role)? refiner*` — the role/`*` is **optional** | `:238-252, :322` | `selectors.md:53` | **THIN** T1 |
| the refiner keys are exactly `title`, `text`, `res` | `:291-294` | `selectors.md:56-63` | **OK** |
| four operators: `=` `*=` `^=` `$=` | `:83, :277-283` | `selectors.md:70-77` | **OK** |
| each key at most once per step | `:265, :298, :306, :313` | `selectors.md:54` | **OK** |
| an unknown role, key or operator raises, naming the part; a bad role lists every role | `:250, :291, :354` | `selectors.md:79` | **OK** |
| a step naming nothing raises | `:322-323` | — | **CUT** |
| `[title=]` is legal **only** in a `window` step; the error hands back the rewrite | `:299-302, :332-338` | `selectors.md:109-113` | **OK** |
| — the same fact, on the sheet's own page | " | `keys.md:39` | **WRONG** W1 |
| `[text=]` is refused **on** the `window` role | `:307-309` | `selectors.md:114-115` | **OK** |
| `[text=]`'s **reader** skips a `Window` even where the step said `*` | `:154` | — | **GAP** G2 |
| `[title=]` reads `Window.cap`, nothing else | `:152` | `selectors.md:61` | **OK** |
| `[text=]` reads `LuaWidget.text` — Label, Button, Window, TextEntry, **CheckBox** | `LuaWidget:1685-1701` | `selectors.md:62`, `widget.md:62` | **GAP** G1 |
| `[res=]` reads `LuaWidget.resName` — a `WItem`'s item, an `IMeter`'s bg, a resource-coded class | `LuaWidget:1653-1677` | `selectors.md:120-126` | **OK** |
| — and it has no window exception: a window whose code came from a resource carries one | " | `selectors.md:124` | **THIN** T2 |
| an absent value fails every operator — `null` is never a match | `:93-103` | `selectors.md:123-129` | **CUT** |
| matching is greedy right-to-left; each step leftward takes the nearest satisfying ancestor | `:391-406` | — | **CUT** |
| the anchor step must match **strictly above** the widget | `:403` | `keys.md:71-74` | **OK** |
| `@Class` goes through `typeName` — nearest **named** class, control adapters climbed past | `LuaWidget:1581-1589` | `selectors.md:116-118` | **OK** |
| a role that classifies no widget is valid grammar and matches nothing | `:60, :341-351` | `selectors.md:97-101` | **OK** |
| the seven roles that classify a widget | `:67-69`, `LuaWidget:1612-1630` | `selectors.md:87-95` | **OK** |
| the seven roles that name a render site | `:74-76`, `Fonts:82-96` | `selectors.md:97` | **OK** |
| specificity: role 1 · `@Class` 2 · `title`/`text` 4 · `res` 8, summed over steps | `:168-172, :426-431` | `keys.md:65-67` | **OK** |
| `late()` — any step carrying `title`/`text`/`res` makes the whole selector re-checkable | `:163-166, :414-420` | `replace.md:51-57` | **OK** |
| `bare()` — one step, role or `*` and nothing else | `:174-176, :437-439` | `keys.md:8-9` | **OK** |
| `role()` of a bare selector; `*`'s twin is the `default` scope | `:441-444`, `Sheet:390-397` | `keys.md:19` | **OK** |

## 2. The doors (`UiApi.java`, `LuaWidget.java`)

| Door | `src/` | Page | Verdict |
|---|---|---|---|
| `hafen.ui():find(sel)` — `nil` / the widget / **raises** on two or more, with the count | `UiApi:301, :688, :744-753` | `selectors.md:19-25`, `widget.md:17`, `references.md:83, :97` | **OK** |
| the refusal names `all(sel)[i]`, a chain, and `widget:find` | `UiApi:748-751` | `selectors.md:20-21` | **OK** |
| `hafen.ui():all(sel)` — 1-based array in tree order, **empty never nil** | `UiApi:312, :703-710` | `selectors.md:21` | **OK** |
| one pre-order walk; the selector is parsed once, never per node | `UiApi:765-770` | `selectors.md:163-169` | **OK** |
| every lookup is a walk, so hold the result | `UiApi:684-686` | `selectors.md:165` | **WRONG** W4 |
| `w:find(sel)`/`w:all(sel)` — the same search from the widget, itself included | `LuaWidget:930-943`, `UiApi:718-735` | `selectors.md:30-34`, `widget.md:103-109` | **OK** |
| the scope decides the **candidates**; an ancestor step may name a widget above it | `UiApi:712-717` | `selectors.md:32-34`, `widget.md:107-109` | **OK** |
| a **stale** widget refuses at both scoped doors, where every flat read answers | `LuaWidget:1554-1560` | `selectors.md:47-49`, `widget.md:121-123` | **OK** |
| — and it is the **only** other raise on a stale widget beside `send` | `LuaWidget:574` | `widget.md:44` | **WRONG** W3 |
| `selArg` refuses a number before a non-string (LuaJ: a number is a string) | `UiApi:666-673` | — | **CUT** |
| no UI yet → `nil` / an empty array | `UiApi:690, :705, :720` | — | **CUT** |
| `:root()`, `:node(id)`, `:at(x, y)`, `:tipAt(x, y)` — not selector doors | `UiApi:295, :319, :363` | `widget.md:20-23` | **N/A** |

## 3. The other two consumers (`LuaSelectorWatch.java`, `Sheet.java`)

| Fact | `src/` | Page | Verdict |
|---|---|---|---|
| one subscription carries exactly one event; both are about the **tree**, not visibility | `LuaSelectorWatch:18-21, :60-61` | `replace.md:23-31, :44-45` | **OK** |
| registration scans the live tree once, so `appear` covers what is already open | `LuaSelectorWatch:42-45`, `UiApi:1002-1012` | `replace.md:40-41` | **OK** |
| a match fires **once**, whichever trigger reached it | `LuaSelectorWatch:23-31` | `replace.md:55` | **OK** |
| the caption seam hands over the **window**, so its whole subtree is re-offered — an addon's own `w:title(s)` included | `LuaSelectorWatch:26-29` (049.3, D-227) | `replace.md:51-55` | **OK** |
| a `[res=]` candidate is re-checked for a bounded while after placement | `LuaSelectorWatch:24-27` | `replace.md:56-57` | **OK** |
| `disappear` fires when the widget stops being **real**; a closing window lingers through its fade | `LuaSelectorWatch:33-40` | `replace.md:46-49` | **OK** |
| a bare role that **is** a font scope is a site key; everything else is a tree key | `Sheet:390-397` | `keys.md:8-9, :39-40` | **OK** |
| — so `window` and `inventory` are roles with **no** site behind them | " | `references.md:93-96` | **WRONG** W2 |
| a bad sheet key errors exactly as a lookup does | `Sheet:374` | `keys.md:11` | **OK** |
| a chain is always a tree key (`bare()` is false) | `Sheet:391` | `keys.md:39-40` | **OK** |
| equal specificity goes to the sheet installed last | `Sheet:584` | `keys.md:70` | **OK** |
| a rename re-folds the renamed window's subtree, in **both** directions | `Sheet` cache + 049.3's seam | `keys.md:74-76` | **OK** |
| `position`/`anchor`/`size` are refused on a site key and on `widget:rule()` | `Sheet:408-423, :489` | `keys.md:141-142`, `style/README.md:110-111, :143-144` | **OK** |

## 4. The inspector, as the ten pages describe it (`addons/widgetstack/main.lua`)

| Claim | The file | Verdict |
|---|---|---|
| the anchor is the nearest enclosing window | `:127-137` — the nearest **captioned** one, strictly enclosing; an uncaptioned window is skipped, not the end of the search | **WRONG** W5 |
| it lists **every selector that actually matches** the widget | `:179-239` — every candidate it **builds** (mixed-radix over the widget's own role/`@Class`/own attribute/`[res=]`, flat and anchored) that resolves back to it. `*` matches every widget and is never offered | **WRONG** W6 |
| `[text^=…]` on the part of a **caption** before its first digit | `:99-107, :185, :194` — the `^=` form of the widget's **own** key, which is `[title^=…]` on a window | **WRONG** W7 |
| `[res*=<last path segment>]` offered beside `[res=]` | `:112-119` | **OK** |
| the role is required wherever there is one | `:187-192` | **OK** |
| every candidate is resolved before it is offered, and kept only if it matches | `:220-229` | **OK** |
| the offer is the most specific candidate naming it **alone** → `find(…)`, else `all(…)[i]` | `:166-169, :231-237` | **OK** |
| the header reports the walks the last hover cost and how many were chains | `:461-465` | **OK** |
| the walk budget, and the "+N unwalked" tail it drops | `:171-174, :214` | **CUT** |

**Counts.** 62 rows: **44 OK**, **7 WRONG**, **2 THIN**, **2 GAP**, **0 INVENTED**, **6 CUT**, **1 N/A**.

## 5. The reverse direction — the ten pages, one by one

| Page | Selector claims | Outcome |
|---|---|---|
| `api/ui/selectors.md` | the grammar table, the operators, the two role tables, the disjointness pair, `[res=]`, strict `find`, the scoped pair, the stale refusal, the inspector, hold-the-result, hit-testing | **6 corrections** (T1, G1, G2, T2, W4, W5, W6, W7 — five sentences) |
| `api/ui/style/keys.md` | site vs tree, the tree-key spellings, specificity, the chain, the rename | **1 correction** (W1) |
| `api/references.md` | the three uses, the two resolutions, "the verb says how many", the scoped pair | **1 correction** (W2) |
| `api/ui/widget.md` | the getters table, staleness, `:text()`, the scoped pair | **2 corrections** (W3, G1) |
| `guides/debugging.md` | the inspector, `find` answering `nil` and raising | **1 correction** (W5, W6 in one sentence) |
| `examples.md` | `widgetstack`'s panel, `bags`' `appear` + `replace` | **1 correction** (W6) |
| `api/ui/replace.md` | the two events, the scan, the late caption on an ancestor, fire-once, `[res=]`'s re-check | **checked, clean** |
| `api/ui/README.md` | one type, one way to name one, the shared vocabulary | **checked, clean** |
| `api/ui/style/README.md` | the cascade, "every relationship except containment", the site/tree split | **checked, clean** |
| `guides/theming.md` | the two kinds of key, the site-key list, the chain example, the cascade | **checked, clean** |

## 6. Filed to area `addons` (not fixed here)

- `UiApi.java:257`'s own comment still lists `:text()` as "(Label/Button/Window/TextEntry)", missing the
  `CheckBox` arm `LuaWidget.text` has carried since 040.4 (`LuaWidget.java:1696`). A comment, not behaviour —
  but it is the comment a later docs pass would read as the roster.
