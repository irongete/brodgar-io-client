# 121 — a shape and its edge: the tasks

- [x] **121.1 — a patch takes a border.** Adds `patch:border(c [, w])` / `:border()` / `:border(nil)` — a
      line at one colour and one thickness all the way round, its centre left to what fills it and the
      property left out where nothing is drawn. The **word** is a stylesheet rule's; the argument shape is
      `g:line(x1, y1, x2, y2, width)`'s, because `conventions.md` bars a table of named arguments in a call
      and permits a document one. `patch:border()` hands **both** back, so `two:border(one:border())` is one
      expression. `width` is **world units** rather than design px, `0` is the default and means the thinnest
      line the screen draws, and a value outside the range is brought into it the way `:scale` and `:alpha`
      are. In the fragment the width is `max`ed against `fwidth(m)`, so a border is never thinner on screen
      than the one pixel the silhouette's own antialias is and does not vanish as the camera pulls back; the
      `-1` "no border" encoding stays inside Java. With it, the `a` of `patch:tint(c)` stops being dropped
      and becomes the **fill's** own opacity, while `:alpha(a)` goes on multiplying the whole patch — which
      is what lets a solid line stand round see-through ground. `PatchCarve` gains the border colour and
      width uniforms and mixes them over what `BaseColor` wrote before the silhouette scales the alpha;
      `PatchOverlay.set` takes
      fill, edge and width; `LuaPatch` holds both and folds `:alpha` into each; `VirtualApi.patchHandle` grows
      the verb through the `extra`/`extraVocab` pair, writing through a setter shaped like `setEntityTint`;
      `infoInto` gains `border`, absent while none is laid. Docs: the border on `patches.md`, the "a patch
      adds none" sentence on `virtual/README.md`, and the *reason* the outline counter stands still on
      `counters.md`. In an example showing the pair in one chain both take **parentheses** —
      `:tint({...}):border({...}, w)` — since the brace form is a call with a single table argument and a
      border has two; a `:tint{...}` standing alone stays as the page writes it.
      *Its suite* lays a patch and writes a border, asserting the write hands the patch back and the read
      answers the colour keyed and the width it was given; asserts a fresh patch reads `nil` and a fresh
      `info()` carries no `border`; clears it with `nil` and asserts both go back to that; asserts a second
      patch fed `two:border(one:border())` reads back exactly what the first holds; writes a border with no
      width and asserts it reads `0`; writes a negative width and an absurd one and asserts each reads back
      at the range's own end rather than raising; `pcall`s `patch:border{box = "gfx/hud/wnd"}` and asserts
      the refusal names that out here a border is two arguments; `pcall`s `patch:border("green")` and asserts
      it names the two colour spellings; writes a translucent tint beside an opaque border and asserts
      `info()` carries both colours with their own alphas and `alpha` unchanged; reads `overlayMeshes` and
      `overlayOutlines` before and after every one of those writes and asserts neither moved.
      `[manual]`: look at the patch — expect a solid coloured line round ground you can see through inside it.
      `[manual]`: widen the border to two world units — expect a band about a fifth of a tile across.
      `[manual]`: zoom all the way out on a hairline border — expect it still drawn, still one pixel.

- [ ] **121.2 — the base under a character wears its border again.** The maintainer's
      `addons/session-manager` draws each character's base with the pair: the fill keeps its own opacity in
      the tint's `a` and the line is drawn solid, so the disc reads as a plinth rather than a smudge. Its
      README's base section says the edge is the ring's own shape at every zoom, and its pick section stays
      true — the hit test was always the ring and is not the picture. Nothing of the API changes here.
      *Its suite* proves the fill and the border **compose on one patch**, which the suite before it asserts
      only one write at a time: it lays one patch and writes a translucent tint and an opaque border in a
      single chain, asserting each setter handed the patch back; reads both off one `:info()` call, whose
      `border` is `{color, width}`, and asserts every value is the one written, alphas included; asserts
      `:alpha(0.5)` leaves both exactly where they were, since it multiplies what is drawn and writes
      neither; then asserts
      `overlayMeshes` and `overlayOutlines` stood still across the whole chain.
      `[manual]`: with two characters logged in, look at the ground under each — expect a green disc with a
      solid green line round it under the one on screen, and a fainter one with its own line under the other.
