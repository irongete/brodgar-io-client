# 178 — Tasks

- [x] **178.1 — The character's abilities.** `LuaAttr` gains `enum Kind` (`BASE` → `Attr`, `ABILITY` →
      `Ability`, each with its names, blurb and collection spelling); the handle, `Cache`, metatable and
      `collection` take the kind; `Addon` gains the `abilities` cache; `CharApi.chr` sets
      `session:char():ability()`. A name of the other kind is refused from `getMember` naming the other
      collection, both ways. `tools/docverbs.py` and `tools/refusalverbs.py` map the `ability`
      receiver. Docs: every page `plan.md` *Files* lists, `docs/client/character-sheet.md` included.
      Covers criteria 1–7.
      *Its suite* (`:t178`) reads `hafen.session():current():char()`. One line each: all thirteen
      `ability():get(n)` answer `:name() == n`, `tostring == "Ability(n)"`, `:base() >= 1`, a number
      `:composite()`, `:info()` equal to both, naming the first that differs; `:list()` is exactly the
      thirteen in sheet order, `:count() == #:list()`, `:find("smith") == :get("smithing")`; identity
      (`ability() == ability()`, interned get, no member `==` an `attr():list()` member,
      `tostring(attr():get("str")) == "Attr(str)"`); `attr():list()` holds no ability name; the five
      refusals, each `pcall`ed and its message matched (`attr()`, the thirteen listed, `ability name`,
      `no arguments`, `ability`), plus `attr():get("smithing")` naming `ability()` — one line naming
      every refusal that missed. Then it polls `session:craft()` every half second for 30 s: on the
      first open recipe, every `qualityInputs()` `:res()` matches `^gfx/hud/chr/(.+)$`, the name is a
      key of one collection and its `:composite()` is a number; one line holds the recipe, each
      name=value and the geometric mean. No recipe in the window prints `[fail] no recipe opened`.
      `[manual]`: after `:t178`, open any recipe (Smithing or Carpentry); the line itself carries the
      suite's `smithing`, `carpentry` and `cooking` — expect: equal to the Abilities tab. About ten
      lines in all.
