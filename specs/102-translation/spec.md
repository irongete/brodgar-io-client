# 102 — Translation

## What and why

Every string the client draws is a literal in `haven`, a layer decoded from a `.res`, or a line the server
sent — so the client speaks English. An addon reaches almost none of it: `w:text(s)` writes the captions it
can walk to, and nothing reaches an item name, a tooltip row, a petal, a chat line or the text a resource's
own code draws.

This feature adds **`hafen.locale()`**: one **catalogue** per addon, installed and released like a sheet
(`docs/addons/api/ui/style/README.md`), saying what the client **displays**. An entry is keyed on the text
the client would otherwise have drawn and on the **surface** it is drawn at, so `button` and `chat` are told
apart; a **pattern** with capture groups reaches a string the client composed as it drew it. A "Spanish
translation" addon then ships a JSON file and a dozen lines of Lua. It writes client-local and its release
undoes it, so the section is **unprotected**.

**The model is not translated.** The catalogue lands at the render and nowhere else, so every string this
API hands back or matches on stays the client's own English: `w:text()`, `[title=]`, `[text=]`,
`s:flowermenu():list()` and `select(label)`, `pag:name()`. That is the invariant this feature keeps.

## Acceptance criteria

1. An **installed** catalogue changes what a routed surface displays, live; `:release()`, `:reload` and
   disabling the addon each put the English back, and `locale:info()` says whether it is in force.
2. A key names one surface: an entry under `button` reaches a button caption and not a chat line, and `"*"`
   reaches both. A key naming a surface that draws no text is refused, naming the ones that do.
3. `textentry` is not a locale key and `"*"` does not reach one: what the user types is never matched
   against a catalogue.
4. A pattern with capture groups translates a string the client composed, substituting `%1$s`-style
   positional arguments. Patterns are an **ordered sequence**, since a JSON object has no order to resolve
   in; a document offering one raises.
5. Text drawn by code that ships inside a `.res`, through its own private foundry, is translated with no
   local copy of that code.
6. While a catalogue is installed, `w:text()`, `w:title()`, `[title=]`, `[text=]`, `s:flowermenu():list()`,
   `FlowerMenuClosed` and `pag:name()` still answer the client's own English, and `w:text(s)` round-trips.
7. `hafen.locale():miss()` is a **collection** of the strings that reached a routed surface with no entry,
   each with the surface it reached, and nothing for one that matched. It records from the moment the addon
   holds the catalogue, entries or none — writing the first file is what it is for — and `:install()`
   starts a fresh round.
8. Two addons' catalogues stack by owner: the last installed wins **per entry**, so a string it does not
   name falls through to the one beneath rather than to English, and so does releasing it.
9. An unknown surface key, a malformed pattern and an unknown document property each raise, naming what
   exists.

## Out of scope

The boundary is the **key**. This feature keys on the text the client would have drawn; keying
resource-borne text on its **resource name** — so two surfaces reading "Branch" can differ, and an entry
survives a rewording — is the other half, a decode-time index in `Resource` rather than a render seam.

Also outside:

- **Free text** — a chat body, a kin name, anything the server generates — has no key at all, so it never
  matches; which is also why a catalogue keys on `button` and `menu`, not `*`.
- **Text the client rasterised into a `static final` at class-load**: structural, and already stated in
  `docs/addons/api/ui/style/keys.md`.
- **Anything travelling to the server**, and any inverse lookup back to English: the model is English by
  construction, so neither exists to be built.
- **A language picker**: choosing one is an addon enabling its own catalogue.

## Docs impact

Written: `docs/addons/api/locale.md` and `docs/addons/guides/translating.md`, listed in
`docs/addons/api/README.md` and `docs/addons/guides/README.md`. `docs/client/text-and-fonts.md` (111 lines,
room) gains the seam this feature reads — `Foundry.resolved()` and its `RichText` twin, and the
`Fonts.enter`/`dynamic` composition scope they ask, which is what reaches a foundry the fork cannot route.

**Derived impact set.** `grep -rniE "language|translat|locale|english" docs/addons/ docs/client/` → **19**
hits, seventeen of them coordinate translation or "the language of every verb". Two are
real: `api/ui/selectors.md:139` — "a resource name never changes with the client's language, where a
caption can" — which this feature makes reachable, and which must say which language a caption selector
matches; and `runtime.md:112`, where the sandbox denies `os.setlocale`.

Second set, the pages keyed on a caption or display name.
`grep -cniE "caption|display name|the words"` gives `ui/selectors.md` 9, `flowermenu.md` 8, `menugrid.md`
9, `ui/edit.md` 13, `ui/widget.md` 6. The first two (261, 182) carry the rule; `menugrid.md`
(300), `ui/edit.md` (350, the maximum) and `ui/widget.md` (309) are at or over the ceiling and discharged
with their reason, not grown.

## Context files

| File | Tasks |
|---|---|
| `src/haven/Fonts.java`, `src/io/brodgar/addon/Args.java` | 1, 2, 3, 4 |
| `src/haven/Text.java` | 1, 2, 3 |
| `src/haven/RichText.java` | 2, 3 |
| `src/haven/Label.java` | 1, 2 |
| `src/haven/Button.java` | 1, 2 |
| `src/haven/Window.java`, `ChatUI.java`, `TextEntry.java` | 2 |
| `src/haven/Widget.java`, `UILoop.java` | 2, 3 |
| `src/haven/ItemInfo.java`, `MenuGrid.java`, `res/ui/tt/slots_alt/ISlots.java` | 3 |
| `src/haven/FlowerMenu.java` | 2, 4 |
| `src/io/brodgar/addon/Sheet.java`, `LuaSheet.java` | 1, 4 |
| `src/io/brodgar/addon/LuaRule.java`, `Section.java`, `AddonManager.java`, `FontApi.java` | 1 |
| `src/io/brodgar/addon/LocaleApi.java`, `Catalogue.java` | 2, 3, 4, 5 |
| `src/io/brodgar/addon/LuaCollection.java` | 4 |
| `src/io/brodgar/addon/LuaWidget.java`, `src/haven/CheckBox.java` | 2 |
| `docs/addons/api/locale.md` | 2, 3, 4, 5 |
| `docs/addons/api/ui/style/README.md`, `DOCUMENTATION.md` | 5 |
| `docs/client/text-and-fonts.md` — where every scope pair and both display seams are mapped | 3, 4, 5 |
| `docs/addons/runtime.md`, `menugrid.md`, `ui/edit.md`, `ui/widget.md` — the impact set still to discharge | 5 |
| `docs/addons/api/README.md`, `docs/addons/guides/README.md` — the two index rows | 5 |
