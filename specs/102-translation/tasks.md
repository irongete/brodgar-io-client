# 102 — Translation: tasks

Five tasks. The first ships the catalogue and the oracle every later suite reads, because criterion 6 makes
every readback answer English: without `:miss()` nothing in this feature is machine-checkable at all.

- [x] **102.1 — the catalogue, and the surface a string is drawn at.** Adds `hafen.locale()` —
      `:load(doc)`, `:install()`, `:release()`, `:info()` and `:miss()` — over `Fonts.display(scope, text)`
      and a per-owner catalogue stack beside `Fonts.overrides`, both moved by `bumped()`. A locale key is
      `Fonts.SCOPES` minus every key its comments mark `C2`, minus `chat.urgent`/`chat.speaker`, minus
      `textentry`, plus `*`. The document carries `text` and `pattern` and nothing else. Routes `Label`
      (`default`, `label`) and `Button` (`button`) with the `Fonts.enter`/`exit` pair.
      *Its suite* builds a button and a label both reading `"Brodgar"`, installs an entry for `"Brodgar"`
      under `button` alone, and asserts `:miss()` holds `("default", "Brodgar")` and not
      `("button", "Brodgar")` — one string, one surface matched and one not, which is what proves a key
      names one surface and that the lookup ran at the render rather than in the document. `:release()` puts
      the button's pair back, and so does `:reload`. It installs an EMPTY catalogue first and asserts both
      pairs are recorded under it, since writing the first file depends on nothing but holding one. An entry
      under `*` then answers both. Four `pcall` refusals: `panel` as a surface, naming the ones that draw
      text; `textentry`, saying what the user types is never matched; an unknown property, naming `text`
      and `pattern`; and `:load(nil)`.

- [x] **102.2 — the client's own text sites.** The `Fonts.enter`/`exit` pair at `window.title`
      (`Window.DefaultDeco`), `heading`, `tooltip`, `menu` (`FlowerMenu`), `chat` and its four kinds
      (`ChatUI.Message.scope()` already answers), `world.nick` and `world.speech`. `Fonts.display` refuses
      the `textentry` scope outright, so even a `*` entry leaves what the user types alone.
      *Its suite* drives one known string per routed surface and asserts its `(surface, text)` pair reaches
      `:miss()` under a catalogue that does not name it and is absent under one that does — one line per
      surface, which proves the routing and the key together. It then types into its own
      `hafen.ui():entry()` under a `*` catalogue naming that very string and asserts no `textentry` pair
      ever appears and `entry:value()` is unchanged. Finally it reads `w:text()`, `w:title()`, `[title=]`,
      `[text=]` and `s:flowermenu():list()` back with the catalogue installed and asserts every one is the
      client's own English.
      `[manual]`: open any window — expect the caption in the catalogue's words, frame and font unchanged.

- [ ] **102.3 — the text a resource's own code draws.** `Text.Foundry.resolved()` and its `RichText` twin
      translate under `Fonts.dynamic()`, so a foundry the fork cannot route is reached with no copy of that
      code. `Fonts.enter("tooltip")` widens past `ItemInfo.longtip` and `MenuGrid` to the item name and the
      composed rows, `res/ui/tt/slots_alt/ISlots` among them.
      *Its suite* installs a catalogue naming nothing, asks the maintainer to hover one inventory item, and
      polls `:miss()` on a timer for a bounded window, scoring the `("tooltip", …)` pairs it reached —
      a tooltip is drawn by a hover the suite cannot make, but what it produced is fully observable. It then
      installs an entry for one pair it caught, asks for the same hover again, and asserts that pair does
      not come back. It asserts throughout that `pag:name()` answers the client's own English.
      `[manual]`: hover an inventory item twice when the suite asks — expect the second tooltip's first row
      in the catalogue's words.

- [ ] **102.4 — patterns.** `pattern` is an array of `{surface, match, text}`, resolved in the array's own
      order after every exact key has missed, with `%1$s`-style positional arguments substituted from the
      capture groups. A malformed pattern raises at `:load`, naming the group that did not close.
      *Its suite* installs two patterns that both match one composed string and asserts the **first** wins,
      which is the assertion the array exists for — an object could not have said it. It asserts a
      two-group pattern substitutes both, and reordered, that `%2$s` reaches the first group. It asserts a
      string an exact entry names is never offered to a pattern. `pcall` a pattern with an unclosed group,
      a `pattern` given as an object rather than an array, and a `%3$s` with two groups.

- [ ] **102.5 — the pages.** Writes `docs/addons/api/locale.md` (the section, the document, the misses
      round trip, the surfaces that are keys and the ones that are refused) and
      `docs/addons/guides/translating.md` (the workflow end to end), lists both in their READMEs, and gives
      `docs/client/text-and-fonts.md` the composition-scope seam. Discharges the spec's impact set:
      `ui/selectors.md` says which language a caption selector matches, `runtime.md` says how
      `hafen.locale()` and the denied `os.setlocale` differ, and `menugrid.md`, `ui/edit.md` and
      `ui/widget.md` are discharged with their ceiling as the reason.
      *Its suite* is the page's own example run verbatim: the file loads, installs, and one asserted string
      changes surface — a page whose example does not run is the defect this catches.
