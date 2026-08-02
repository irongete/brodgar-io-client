# 034-ui-stylesheet-tree — Spec

## What & why

**The sheet learns to style *which* widgets, not just *what kind* of surface.** 033 shipped the engine and the
keys that resolve at a render site; a **tree key** parsed fine and did nothing (D-072). This makes it work.

```lua
hafen.ui.skin{
  ["button"]                 = { font  = body },              -- a KIND of surface (033, site key)
  ["window[title=Cupboard]"] = { color = {200,180,140} },     -- WHICH widgets     (034, tree key)
}
```

**The mechanism already exists, and F5 wrote the note.** `learnings/fonts.md` says it outright: *the draw pass
IS a scope stack — reuse it before inventing per-widget state*, and *the same trick is available to any future
"scoped to a subtree" feature (colours, …)*. F5 opens one frame in the parent-first `Widget.draw` descent, which
gave per-instance fonts over **every** routed site with no second resolution path. C1b generalises that frame
from *one handle an addon named by hand* to *the rule a widget matches*. So, as in 033: **no render site is
re-routed and no drawing code changes** — what changes is what the frame carries.

**The overlap has to be decided, not discovered.** Five names are both a routed site and a classifying role
(`button`, `label`, `textentry`, `chat`, `menu`); two roles have no site (`window`, `inventory`); five sites
classify nothing (D-067). The rule this feature adopts:

> **A bare role is a site key** (unchanged from 033 — cheap, already routed, reaches text no widget owns).
> **A role with a refiner, or a role with no site, is a tree key.** Where both reach the same pixels, the
> **more specific wins**, by 030's already-validated ranking: role 1 · `@Class` 2 · `[title=]` 4 · `[res=]` 8.

**Second piece:** `widget:setFont(h)` / `:resetFont()` become **`widget:skin{…}`** — read / set / `nil` clears —
so one widget and its subtree carry any sheet property, not only a font. Hard cut of both old verbs; D-073's
rule (a handle's colour never styles a surface) carries over unchanged.

**Third piece, and 030 taught this lesson already:** a resolved style must be **readable**. 030 found `[res=]`
"unobservable from Lua" and had to add `:res()` mid-feature; without a read-back, every test here is a human
squinting at a button. So **`w:style()`** ships with the feature — the resolved `{font, color}`, `nil` when
nothing overrides (the same "nil means stock" contract that keeps the identity fast path honest).

Feature **C1b**. Next: **C2** — textures and chrome, where a window's frame being a **child** (`@DefaultDeco`,
role `nil` — 030's inspector) starts to matter.

## Acceptance criteria — per `TESTING.md`: readable through `hafen.*` ⇒ asserted; `[manual]` only for looks

- [ ] `w:style()` returns the resolved `{font, color}` and **`nil` when nothing overrides** — the read-back
      every check below is written against.
- [ ] A tree key applies: `["window[title=Cupboard]"]` changes `w:style()` for that window's subtree and
      nothing else, asserted by comparing a matched and an unmatched widget.
- [ ] **The overlap rule holds**: with `["button"]` and `["button[title=Cupboard]"]` both installed, a button
      inside that window resolves to the second and a button elsewhere to the first.
- [ ] **The full cascade**, asserted step by step: `widget:skin{}` → most specific tree rule → site rule → `*`
      → stock, each level removed in turn and `w:style()` re-read.
- [ ] `widget:skin{…}` styles a widget **and its subtree**, reads back, and `widget:skin(nil)` clears **that
      addon's** override only; `widget:setFont`/`:resetFont` read `nil`; an unknown property is still refused
      for a tree key (D-072), saying which property.
- [ ] **Teardown is exact**: with no sheet installed `w:style()` is `nil` everywhere and the client is
      byte-for-byte stock; `:reload`, disable and relog return to that.
- [ ] **Cost is measured, not assumed** (`hafen.client:profiling()`, the 029/030 method): a widget resolves
      **once and caches**, a hit is a lookup and not a match, and the descent's per-frame cost with a sheet
      installed is reported. Resolving per frame is a failed task, not a slow one.
- [ ] `[manual]`: with a demo sheet applied, the named surfaces look right and the rest of the client unchanged.

## Out of scope

- **`bg`, `border`, `pad`, textures, `Window.Deco`** — C2. Nothing here changes a widget's size.
- Descendant selectors, pseudo-classes, `hover`/`down` states; new roles or classifier changes.
- Re-routing any render site, or a second resolution path beside F5's frame; layout — E.

## Context files

- `design/21-fonts.md` §F5 — the draw-pass frame and the `gen ^ stamp` trick this generalises;
  `design/22-ui-selectors.md` — the grammar, D-067's two key classes, the specificity ranking
- `specs/addons/TESTING.md` — the per-task suite format every criterion above is written for
- `src/io/brodgar/addon/Sheet.java` — 033's parsed sheet: where tree keys are already classified and inert
- `src/haven/Fonts.java` — the provider, the owner stack, `gen`, and F5's per-instance frame + stamp
- `src/io/brodgar/addon/LuaWidget.java` — `role`, `setFont`/`resetFont` (cut) → `skin`, where `style` lands;
  `Selector.java` — `matchesStructure` + refiners, already split for exactly this
- `docs/addons/api/ui.md` (the sheet, the property × key table), `fonts.md`, `conventions.md`
- `033-ui-stylesheet/` (D-072/073/074), `030-ui-selectors/` (D-067, the inspector), `016-fonts/` (F5)
- `learnings/fonts.md` — **grep, never read whole**: the four F5 entries and 026.1 on `gen()`;
  `decisions/fonts.md` (D-043), `widgets-ui.md` (D-067), `architecture-api.md` (D-012, D-062)
