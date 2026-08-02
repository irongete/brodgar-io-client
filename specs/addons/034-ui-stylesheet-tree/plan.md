# 034-ui-stylesheet-tree — Plan

## Approach

**Generalise F5's frame; add no second resolution path.** 033's headline was *who fills the stack*; this one's
is *what the draw-time frame carries*.

1. **Resolution is per widget, cached, and lazy.** For a widget, walk the installed sheets (owner order, then
   030's specificity rank) and fold the matching tree rules into one resolved style. Cache it in a
   **`WeakHashMap<Widget, Resolved>`** — `Widget` has no `equals`/`hashCode`, so that is an identity map for
   free (F5 learning), and weak keys are mandatory: F5 also records that the project's default P2 pattern
   (a `List<X>` on `Addon`) *"would have re-introduced exactly the leak the `WeakHashMap` exists to prevent: a
   list of styled widgets pins closed windows."* Each entry carries the sheet generation it resolved at; a bump
   invalidates it on next touch. **No sweep, no registry** — `Fonts.removeOwner` already clears by owner and the
   addon only needs a boolean saying whether a sweep is worth doing.
2. **The frame is F5's, widened.** In the parent-first `Widget.draw` descent, a widget whose resolved style is
   non-null opens the frame F5 already opens for an explicit override — carrying a **style**, not a
   `FontHandle`. Children inherit by being inside it, which is what "and its subtree" means and costs nothing
   extra. **Every already-routed site becomes tree-rule-capable with no second edit**, exactly as F5 got F1–F4
   for free.
3. **The stamp must be derived from the rules, not minted per frame or per instance.** F5's trick is that
   `gen()` reports `gen ^ spec.stamp` while a frame is open, turning every site's existing `gen != mygen` check
   into a *contextual* one. The learning is explicit that the stamp must be **stable across frames** — a value
   that varies rebuilds every routed site every frame. So the stamp is a hash of the resolved rule set: same
   rules ⇒ same stamp ⇒ nothing rebuilds.
4. **Colour goes through 033.2's marker.** A rule's colour reaches a site via `Text.Foundry.fixcol` (a rule's
   colour outranks the colour the *site* asks for, because nearly every site passes its colour per render). A
   tree rule must use the same marker or `color` will silently do nothing on most surfaces.
5. **Overlap: the frame is nearer the draw than the scope stack**, so a tree rule naturally outranks a site
   rule for the widgets it covers. That is the intended outcome — **verify it is what actually happens** rather
   than assert it, then document the rule (bare role ⇒ site; role + refiner, or a role with no site ⇒ tree).
6. **`w:style()`** reads that resolved style for one widget (resolving on demand, cache-first). It is what makes
   the whole cascade assertable from Lua — the 030 `:res()` lesson applied before the fact rather than after.

## Files to create / modify

- `src/io/brodgar/addon/Sheet.java` — tree keys stop being inert: resolution, the specificity fold, the cache.
- `src/haven/Fonts.java` — the per-instance frame carries a resolved style instead of a handle; `gen ^ stamp`
  unchanged in shape.
- `src/io/brodgar/addon/LuaWidget.java` — `skin` (set/read/`nil`), `style`, and the cut of `setFont`/`resetFont`.
- `src/io/brodgar/addon/UiApi.java` — nothing new to install; the sheet is already `hafen.ui.skin`.
- `docs/addons/api/ui.md` — the **property × key table** grows a tree-key column; the overlap rule; and the
  honest note that a `window` rule does **not** reach the window's frame (the chrome is a `@DefaultDeco` child,
  role `nil` — 030's inspector), which is C2's job. `fonts.md`, `conventions.md` follow the `setFont` cut.
- `addons/034-ui-stylesheet-tree.1/`, `.2/`, `.3/` — one self-checking suite per task (`TESTING.md`).
- **No new `specs/codebase/`** expected: `Fonts.java` and `Widget.draw` are already covered by 016/029.

## Risks & gotchas

*(prior art: `learnings/fonts.md` — grepped, not read whole: the four F5 entries and 026.1)*

- **A stamp that varies rebuilds the client every frame.** This is the single easiest way to ship a correct
  feature that runs at 12 FPS. Derive it from the rule set; assert stability across frames before anything else.
- **`Fonts.gen()` is a per-SITE value, not a frame-global** (026.1) — it keys a shared cache, it never clears
  one. Do not "simplify" the invalidation into a global clear.
- **The descent touches every widget every frame.** A cache *hit* must be a map lookup and nothing more; a miss
  must be the only place matching happens. The measurement gate is not optional (the spec makes resolving per
  frame a failed task).
- **Weak keys or a leak**: never keep a list of styled widgets.
- **`null` means stock.** A resolved style must be `null` — not an empty style — when nothing matches, or the
  identity fast path dies and an addon-less client stops being byte-for-byte stock.
- **A widget constructed *after* an override captures a current generation** and would stay stock forever; the
  `gen ^ stamp` contextual check is precisely what fixes that (F5). New rows in a list are the test case.
- **`w:style()` must not trigger a match storm** — it resolves one widget, cache-first, never a tree walk.
- **A resolved style is not the same as a visible one, and 033.3 already measured where.** `heading` and the
  ordinary `button` caption are **embossed** (`PUtils.TexFurn.tilemod` replaces the RGB with a tiled texture and
  keeps only the alpha), so a `color` rule is **inert** there while `font` works — `button` only *partly*, since
  `wrapped()`/`change(text,col)` go through the plain foundry and do follow it. `world.nick` post-processes too
  but **colour survives a blur and dies on a tile**. A tree rule hits exactly the same surfaces, so the same
  caveats carry over verbatim: `w:style()` will honestly report a colour that the surface then throws away, and
  no `[manual]` line may promise a recolour where the tile eats it.

## Discarded alternatives

- **Matching during the descent, per widget, per frame** — rejected by measurement before it is written: 030
  clocked `all("*")` at 0.08 ms/625 widgets; sixty times a second that is a real slice of the frame.
- **A second resolution path for tree keys, beside the frame** — rejected: F5's note is explicit that the draw
  pass *is* the scope stack, and two paths would disagree exactly where they overlap.
- **An owned list of styled widgets (the default P2 pattern)** — rejected: it pins closed windows, the leak the
  `WeakHashMap` exists to prevent.
- **Erroring when a key could resolve both ways** — rejected: the overlap is normal authoring (`["button"]` plus
  `["button[title=…]"]`), and the specificity rank already answers it.
- **Letting a bare role match widgets too** — rejected: it would apply one rule twice by two paths for the same
  pixels, and the site path already reaches text no widget owns.
