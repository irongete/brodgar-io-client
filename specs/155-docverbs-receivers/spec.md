# 155 — The verb checker follows the docs' spellings, and the bridge's comments follow its verbs

## What & why

`tools/docverbs.py` resolves every `x:verb(` a page writes against the vocabulary of the entity `x` names,
and it knows which entity a spelling means through `RECEIVERS`, a map keyed by the shorthand the pages used
to write: `p:`, `ov:`, `seg:`, `cat:`, `msg:`, `sp:`, `pag:`, `win:`, `sub:`, `req:`, `res:`, `conn:`, `pl:`.
Feature 154 renamed every identifier in `docs/addons/**` to a descriptive word, as `DOCUMENTATION.md` §6
requires, and the map was not moved with it: `position:`, `overlay:`, `segment:`, `category:`, `message:`,
`speed:`, `pagina:`, `window:`, `subscription:`, `request:`, `result:`, `connection:` and `placing:` are
now skipped, so some 340 calls the gate used to check it no longer does, while its total stayed near 1500
and its exit stayed 0. One mapping was already dead before the rename: `cat` pointed at an entity named
`iconcat`, which no `closedIndex` declares, so every `cat:` call was skipped silently.

Beside it, the audit that opened 153 found the bridge's own comments naming verbs the API retired
(`:onChange`, `:onPress`, `:onSubmit`, `onTick`, `s:ui():find`), which a reader of `UiApi.java` and
`LuaWidget.java` takes for live spellings. The same spellings stand in ten refusal messages the builders in
`Controls.java` and `UiApi.java` raise at runtime (`hafen.ui():button(x)` answers "...built bare and configured
by chained setters: `hafen.ui():button():text("Go"):position(x, y):parent(w):onPress(fn)`"), a promise that
lands the reader on a second refusal; `tools/refusalverbs.py` did not walk a builder chain through the widget's
chained setters, so it never read them.

This feature moves the map to the spellings the pages use, widens it where an entity exists to check
against, fixes what the widened gate reports, and retires the dead spellings from the comments.

## Acceptance criteria

1. **Every receiver spelling `docs/addons/**` writes more than a handful of times resolves** in
   `tools/docverbs.py`, either to the entity that owns its vocabulary or to an explicit `None` with the
   reason (a section object, the virtual family, a draw wrapper with no `closedIndex`). `--verbose` lists
   only one-off local names.
2. **`cat` resolves**: the `cat:` calls on the icon pages are checked, not skipped.
3. **The checked-call count rises** past the 1497 of 154.6, and the gate exits 0 with every finding it
   raised on the widened set fixed on the page that carried it.
4. **No comment or refusal message in `src/io/brodgar/addon/` names a retired verb as live**: `:onChange`,
   `:onPress`, `:onSubmit`, `:onSelect`, `:onCell`, `onDraw`, `onDrop`, `onClose`, `onTick`/`Tick` (as the
   event key) and `find(selector)` / `all(selector)` / `s:ui():find(` are gone from every file under
   `src/io/brodgar/addon/`, replaced by the key or verb the API answers; the one spelling left is
   `Refusal.java`'s own `MOVED` key for `session:ui():find`, which is the retirement itself. The builders'
   refusal messages promise `:on("Pressed", fn)`, `:on("Changed", fn)`, `:on("Submitted", fn)`,
   `:on("Selected", fn)`, `:on("Cell", fn)`, `:on("Draw", fn)`, and `tools/refusalverbs.py` walks a builder
   chain through the widget's chained setters so it reads them. No code path changes; `ant hafen-client`
   builds.

## Out of scope

- **Mapping a section object's verbs** (`hafen.ui()`, `options:`, `keybindings:`, `graphics:`): their
  vocabularies are not `closedIndex` literals, and the tool skips them by design and says so.
- **Chains the tool does not walk** (`session:world():gob():nearest(...)`): `RETURNS` is seeded, not
  derived; a hop it lacks stays counted, not guessed.
- **Any change to what the pages say**: a finding is fixed as a spelling, never by re-documenting a verb.

## Docs impact

- **Written**: whichever pages the widened gate flags, one spelling each; nothing else.
- **Derived impact set**: `python tools/docverbs.py` after the map moves — every line it reports.

## Context files

- `tools/docverbs.py` — 155.1
- `docs/addons/**` — 155.1 (the receiver spellings, and the pages a finding lands on)
- `src/io/brodgar/addon/UiApi.java`, `src/io/brodgar/addon/LuaWidget.java`, `src/io/brodgar/addon/Controls.java`
  and the control adapters `C*.java`, `AddonManager.java`, `AddonWidget.java`, `LuaGOut.java`, `LuaRows.java` — 155.2
- `tools/refusalverbs.py` — 155.2 (the widget's chained setters as a hop)
- `DOCUMENTATION.md` §6 — the naming the map now follows
