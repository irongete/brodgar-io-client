# Learnings — Fonts & text rendering

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- **F1 — keep the provider registry in `haven`, tag owners by opaque `Object`, so core files gain no addon dep.**
  The spec sketched "`FontApi` owns the registry, `Fonts` is a thin facade" — but then `Text`/`Label` (calling
  `Fonts.foundry`) would transitively depend on `io.brodgar.addon`. Putting the whole registry **in `haven.Fonts`**
  and tagging each override with the owning `Addon` as a bare `Object` (compared by identity only) keeps the core's
  dependency graph clean while behaving identically. The addon layer (`FontApi`) drives it (`push`/`reset`/
  `removeOwner`) — the normal addon→haven direction. When a spec's class layout would force a `haven → io.brodgar`
  edge into a fundamental file, flip it: data + logic in `haven`, opaque owner tokens, addon drives.
- **F1 — a cached `Text` only restyles if the site re-renders on a generation bump; route the traffic, accept the
  cost.** `Text.render(…)` statics rebuild a `Line` every call, so they pick up an override for free — but a `Label`
  caches its rendered `Text`, so "live" means it must re-render. The pattern: the label follows a scope, remembers
  the `gen` it rendered at, and in `draw()` re-resolves + re-renders (preserving wrap width + colour) only when
  `Fonts.gen()` moved (one int compare otherwise; only *visible* labels pay it, once per bump). That single
  `(gen, foundry)` check is the whole invalidation model every later F-slice reuses as it routes more sites.
- **F1 — the `"default"` scope is NARROW in practice; the visible native chrome is F3's job (via the cascade).**
  In-game, `setFont("default", h)` changed addon `g:text` (→ `GOut.text` → `Text.render`, routed) but **nothing in
  the native chrome** — because buttons (`Button.tf`), tooltips (`RichText.stdf`), window titles (fraktur `DefaultDeco`),
  menus/chat/textentry each build their **own** dedicated `Text.Foundry`; only default `Label`s + the `Text.render`
  statics read the `"default"` surface. The spec's "routing them alone restyles most of the UI" was optimistic. This
  is fine and by design — the cascade means the moment F3 routes `Fonts.foundry("button", Button.tf)` (etc.), an
  unset `"button"` scope **falls back to `"default"`**, so those surfaces start following `setFont("default")`
  automatically. **F3 takeaway:** to make `"default"` feel like "the whole client," route the big shared foundries
  (`Button.tf`, `RichText.stdf`/tooltips first — highest visible impact) and give each a gen-based re-render at its
  cache site (Button.draw like Label; tooltips rebuild per-hover but cache `hasrend`, so bump on gen).
- **F2 — the api-reference's `g:text(str, pos, opts)` sketch fought the shipped positional `g:text(str, x, y)`;
  the shipped canon wins.** ~30 call sites across every addon (2a onward) already use positional coords, and spec 07
  documents them positionally. Adding an optional trailing `opts` table (`g:text(str, x, y [, opts])`) satisfies the
  DoD with **zero breakage**; a `pos`-table rewrite would have churned every addon for nothing. When a spec sketch
  and the shipped surface disagree on shape, prefer the shipped surface and correct the sketch (D-012 is about *one*
  way, not the sketch's exact spelling).
- **F2 — `$font` in an addon's own text needs the RICH path, not `Text.render`.** `GOut.text`/`Text.render` build a
  plain `Text.Line` — they **do not** parse `$font`/`$col`/`$b`. To make the spec's `g:text("$font[fam,12]{…} …")`
  work you must render through a **`RichText.Foundry`** (`.render(str, 0)` = one line, no wrap). Key on the string
  containing `$` (the engine's own markup lead — `Text.Foundry.renderwrap` *quotes* `$` precisely because bare `$`
  means markup) OR a font handle being present; keep the untouched fast path otherwise so existing plain text is
  byte-identical. `RichText.Foundry` allocates a `Graphics2D`/`FontRenderContext` at construction → **cache** it
  (per effective px on the immutable `FontHandle`; a lazy static for the stock font).
- **F2 — colour is a blit tint, not a foundry property — keeps foundries cacheable AND matches `g:color`.** Render
  glyphs WHITE and apply a requested colour as a temporary `GOut` draw colour (`getcolor()`→`chcolor(col)`→restore)
  around the `aimage`/`atext` blit. This is exactly how the stock text path already tints (a white glyph tex
  modulated by `cur2d`'s colour), so `g:color(...) + g:text(...)` is unchanged, `$col` runs still render their own
  colour, and one cached foundry serves every colour.
- **F3 chrome scopes are NOT one-line foundry swaps — each render site is a different beast; split F3 by site.** F1's
  `"default"` sites (`Text.render` statics, `Label`) were trivial because they already build a plain `Text.Foundry`
  per call / cache one `Text`. The chrome scopes are heterogeneous: `window.title` is a **blur/tex furnace**
  (`BlurFurn(TexFurn(foundry,…),…)` — you must rebuild the *whole furnace* from the provider foundry, not just swap
  a foundry), `button` rasterizes each caption to a **`BufferedImage cont`** (re-render per button on `gen`), `chat`
  uses a **`RichText.Foundry`** (a different type — `Fonts.foundry` returns a `Text.Foundry`, so chat needs its own
  provider path), tooltips mostly already ride `Text.render`=`"default"`. So F3 was split into F3a…F3d, one site
  family per slice + per-slice in-game verify (like 1c/1d/2/3). **Do not try all 7 in one prompt.**
- **F3a — furnace-backed scope pattern: keep a stock `Text.Foundry`, rebuild the furnace on `gen`, invalidate the
  cached render.** For `window.title`: (1) extract the inline `new Text.Foundry(fraktur,15)` into a named
  `public static final titlefnd` (the stock fallback passed to `Fonts.foundry`); (2) make `cf`/`ncf` non-final +
  a `checktitlefont()` that rebuilds them from `Fonts.foundry("window.title", titlefnd)` when `Fonts.gen()` moved
  (called at the top of `drawframe`); (3) add a per-instance `capgen` and re-render the cached `cap` when `gen`
  moved (the F1 `Label`-restyle pattern). All draw-thread, `gen` is `volatile` → worst case one frame late. Grep
  first: `cf`/`ncf` were `public static final` but referenced only inside `Window.java`, so narrowing to `private
  static` was safe (`Button.tf` by contrast IS referenced by `Charlist.df` — F3b must keep it accessible).
- **F3b — rasterized-caption scope pattern: keep the stock foundry, add a provider-resolved twin, and record the
  render recipe.** For an `SIWidget` like `Button` the stock foundry (`tf`) is *also someone else's* stock
  (`Charlist.df`, `static final` → un-re-derivable): **do not mutate it**. Instead add a lazily-rebuilt twin
  (`btf`/`bnf` + `tfont()`/`nfont()`) and route only the sites the widget renders *itself*. Two gotchas worth
  reusing: (1) keep a **stock-identity fast path** — `Fonts.foundry` returns the *same* stock instance when nothing
  is overridden, so `btf == tf` lets you reuse the stock furnace instead of allocating an identical one (a
  no-addon client stays byte-for-byte stock); (2) stock ctors **throw the source `String` away** after rendering,
  so a live restyle needs the widget to record *how* the caption was made (here `rtext`/`rcol`/`rwrap` — plain vs
  blur vs `renderwrap`) plus a `contgen`, and re-render from `draw(GOut)` (**not** the `draw(BufferedImage)`
  rasterizer — that only runs when the cached `Tex` is already gone, so you must `render()` + `redraw()` *before*
  `super.draw`). Restyling stays lazy/per-visible-widget: a mass override never re-renders the whole tree at once.
  Caller-supplied faces (a pre-rendered `Text`, a `BufferedImage`, an `IButton` icon) are left alone by design.
- **F3c — the cheap scope pattern: a site that keeps its text's source needs ~4 lines.** `TextEntry`,
  `ConsoleHost` and `Label` all retain the live source of truth (`buf.line()`, `cmdline.line()`, `texts`) and
  already drop their cached `Text` on every edit, so routing = (1) a **per-class** provider-resolved foundry
  rebuilt when `Fonts.gen()` moved (one field for *all* instances — resolution happens once per `gen` move, not
  per widget), (2) a per-instance `gen` int compared in `draw`, dropping the cached line. Contrast F3b, where the
  caption recipe had to be *reconstructed* because stock threw the `String` away. **Look for the source of truth
  first** — it decides whether a slice is 4 lines or 40.
- **One scope can front several sites with DIFFERENT stocks — that is what the provider's per-stock cache is for.**
  `"textentry"` covers `TextEntry.fnd` (serif 12, entry colours) *and* `ConsoleHost.cmdfoundry` (mono 12, wheat).
  Pass each site's **own** foundry as the `stock` argument and an override with no explicit size/colour inherits
  that site's size/colour, so one `setFont` keeps both surfaces looking native. Never route a second site through
  the first site's stock.
- **F3c — when several public constructors chain through one another, route them from a NEW private ctor.** F1's
  `Label(String)` chained through `Label(String, Text.Foundry)` passing an *already provider-resolved* foundry.
  Adding a scope to the explicit ctor would therefore have resolved a default label **twice** (and let it pick up
  a `"label"` override it must not follow). Funnelling all four public ctors into one private
  `(text, w, stock, scope)` ctor fixes it structurally and resolves **exactly once, at construction** — which also
  matters because a widget's size must be final before its parent lays it out (a first-frame `restyle()` resize
  would land too late). Check the ctor chain before adding per-ctor state.
- **A scope override should inherit each site's stock SIZE by default; only the family is safe to change globally.**
  A `Label` resizes itself to its text and its container does **not** re-lay-out around it, so a `"label"` override
  carrying an explicit `size` visibly breaks layouts, while one carrying only a family restyles the whole client
  cleanly (the provider already keeps `stock.font.getSize2D()` when the handle has no size). Same story for
  `"textentry"`: the field's **height** comes from its background texture (`gfx/hud/text/m`), so a much larger
  size draws clipped. Demo handles should therefore pass no size (or the stock's) — client geometry, not an API
  limit.
- **Before routing a scope, grep for the SURFACE, not for the class the spec names.** F3c first read
  "`label` = explicit non-default labels" as *the `Label` class with a foundry argument* — wired it correctly, and
  in-game **nothing changed anywhere**, because only ~10 such sites exist and all are in corners a player rarely
  opens. The body text people actually read (character-sheet attribute rows, skill/lore/quest/wound lists) is a
  **class-static foundry rendered directly inside custom widgets' `draw`** — here `CharWnd.attrf`, referenced in 10
  files. **The reliable move is `grep -c` on the FOUNDRY, not on the widget class**: `attrf` had 25 references vs
  `new Label(text, fnd)`'s ~10, and the count tells you which one is the surface. Also: verify a scope's wiring
  headlessly (a reflection call on the restyle hook) BEFORE blaming the code for an invisible in-game result —
  "override installed and resolving correctly, but nothing on screen uses it" is a real and confusing outcome.
- **Generic list rows are the highest-leverage route target.** `SListWidget.TextItem`/`IconText` back the skill,
  lore, quest, wound, maneuver and icon-settings lists; both already had an overridable `foundry()` and a cached
  `Text.Slug`, so **two lines each** restyled the majority of the character sheet. Look for the shared base widget
  before touching individual windows. When adding font invalidation to a widget that already has an
  `invalidate()`, add a **separate** `dropfont()` — `IconText.invalidate()` also disposes the convolved icon, and a
  font change has no business re-doing that work.
- **One scope, several stocks is the norm, not the exception.** `"textentry"` (serif 12 + mono 12 wheat) and
  `"heading"` (fraktur 25 + fraktur 18) both front multiple stocks. Always pass **each site's own** foundry as the
  `stock` argument: the provider caches per stock, so a single `setFont` keeps every site's size/colour native. The
  stock-identity fast path (`resolved == stock` → reuse the pre-built furnace/`Text`) is what keeps an
  addon-less client byte-for-byte stock.
- **A `static final Tex` baked at class-init can never follow an override.** `QuestWnd`'s "Quest completed" /
  "Quest failed" banners were class-init `Tex`es; a transient popup can simply render through the provider each
  time it opens (nothing to invalidate). Grep for `static final Tex .* render(` before declaring a surface routed.
- **When demoing a *scope* override, pick a family that differs from THAT scope's stock — not from `"default"`'s.**
  F3b's first in-game pass looked like a failure ("`:hello button` does nothing, `:hello font` works") because the
  harness overrode `"button"` with serif 12 bold and the stock button caption font already *is* serif 12 bold: the
  override was installed and resolving correctly, it just rendered identical glyphs. Each chrome scope has its own
  stock family (`window.title` = fraktur, `button` = bold serif, `default` = the standard sans/serif), so a demo
  that reuses one handle everywhere can silently look broken on one scope. Verify with a **contrasting** family.
- **F3d — a non-`Text.Foundry` render site needs SCALARS from the provider, not a foundry.** `RichText.Foundry`
  is built from AWT text attributes, so `Fonts.foundry(scope, stock)` cannot serve it. The fix that generalises:
  expose the resolved override itself as a tiny public interface (**`Fonts.Style`** — `font(stock)` /
  `color(stock)` / `aa(stock)`, `null` when nothing overrides) implemented by the *existing* private `Spec`, sharing
  the same `active` fast path, the same `"default"` cascade and a parallel per-stock cache. The site then composes
  its own foundry. `null`-means-stock keeps the identity fast path intact (a site must be able to hand back the
  **same** stock object), which is what makes teardown byte-for-byte reversible.
- **`RichText.Foundry.derive(…)` silently drops a `Parser` subclass.** It rebuilds a plain
  `Parser(mergedattrs)`, so deriving `ChatUI.fnd` would have quietly killed the URL linkifier (chat links stop
  working — the kind of regression no compile catches). Anything with custom parsing must be **reconstructed**
  from its original recipe. Grep for `extends RichText.Parser` before deriving a rich foundry.
- **Override `FAMILY`+`SIZE`, never `TextAttribute.FONT`, on a foundry whose text carries markup.** With a `FONT`
  attribute present AWT ignores `FAMILY`/`SIZE`/`WEIGHT`/`POSTURE`, so `$b`/`$i`/`$size` in a tooltip would stop
  applying. Deriving FAMILY+SIZE also **inherits the stock default attributes** — concretely `RichText.stdf`'s
  `IMAGESRC`, without which a pagina tooltip's `$img` cannot resolve. Mirror what the stock site itself passes:
  `ChatUI.fnd` *does* use `FONT`, so its twin uses `FONT` too.
- **Re-sizing a centre-positioned widget must restore its centre.** A `FlowerMenu.Petal` gets its `c` computed
  from its centre (`move(a, r)`) and nothing re-places it after the opening animation finishes, so a re-render that
  changes the caption width has to re-anchor the centre or an open menu visibly drifts off its ring. Always check
  **who computed `c`** before calling `resize` from a font-invalidation path.
- **Find the invalidation hook the widget already calls per VISIBLE item.** `ChatUI.Channel.draw` calls
  `RenderedMessage.update()` for exactly the messages on screen and re-runs `updyseq()` when it returns `true` —
  so putting the `gen` compare there gave lazy per-visible re-rendering **and** correct height re-measurement (the
  log re-flows) for four lines. Look for an existing per-frame validity check before inventing a new one.
- **A monospaced font hides a bold check.** `$b{x}` renders the same width as `x` in `Monospaced` (equal
  advances), so a width-based "markup still applies" assertion fails with a mono override even though the markup
  worked. Use a proportional family in such assertions.
- **Some client text is rendered by code that does NOT ship with the client — give the composer a dynamic
  scope.** `ItemInfo` tooltips are composed partly by **published code**: Java classes inside the `.res` files
  (`ui/tt/q/qbuff` draws "Quality: 31.7", plus `ui/tt/wear`, `ui/tt/attrmod`, …), which render through the
  generic `Text.render`/`RichText.render` statics. You cannot route what you cannot edit. The answer that
  generalises: a **dynamic (not lexical) scope** — the composer calls `Fonts.enter("tooltip")` / `finally
  Fonts.exit()` and the generic statics resolve `Fonts.scope()`. Everything rendered inside the composition
  follows the scope, including third-party code; everything outside is untouched. **Tip:** a `.res` stores the
  published **source** as preprocessed text next to the compiled class, so dumping the file tells you exactly what
  it renders with — no decompiler needed. **And when the statics are not enough** — the code holds its own
  foundry — resolve **at the foundry** (`Text.Foundry.resolved()`), still gated on the same declared context:
  outside a composition it returns `this`, so the blast radius is exactly the region the composer declared. Mark
  the provider's own products (`noresolve`) or resolution recurses. **And a constructor-rendered text needs the
  cached OBJECT rebuilt** — no render-time context reaches it (here: drop the `ItemInfo` list on a `gen` move,
  since `buildinfo` runs inside the scope). Render-time → foundry-time → build-time: invalidate at whatever layer
  created the cached render. A **`static final Text` rasterised at class-load** is unreachable from inside the font
  system (`ISlots.ch` = "Gilding:") — note the contrast inside that same class: a static FOUNDRY used at layout
  time IS reachable. Ask **when** a surface was rasterised, not whether it is text.
- **NEVER assume a published resource's NAME — read it off the running client.** F3d's first local copy targeted
  `ui/tt/slots`; the game actually serves **`ui/tt/slots-alt`** (package `slots_alt`, its own version line), so the
  copy was inert. Two things hid it: **`Warning.warn` never reaches the console once an `ErrorHandler` is
  installed** (Warning.java:89 — so the "local copy ... is overridden" report is invisible in-game), and
  `ResClassLoader.loadClass` **swallows the `ClassNotFoundException`** when the parent lacks the class, using the
  resource's code with no report at all — exactly the branch a wrong name lands in. **A silent failure needs a
  diagnostic that cannot be silenced:** three `System.out.println`s at the class-loading DECISION point (filtered to
  one class name, temporary) answered in one hover what several rounds of reasoning could not.
- **An invalidation hook must not force work that had not happened yet.** F3d's info-list rebuild dropped the
  cache to `null` = "rebuild me", but `null` and *empty* meant different things: an item whose `tt` has not arrived
  keeps an EMPTY `ItemInfo` list precisely so nothing tries to build one, and forcing it NPE'd on the **UI thread**
  (`SAttrWnd.StudyInfo.tick` → `GItem.info()` → `buildinfo(this, null)`). Invalidate only when the SOURCE of the
  cached value exists, and make the builder tolerate the empty case anyway. Cheap to get wrong, fatal at runtime.
- **Two cache layers means two invalidations.** A tooltip is cached twice: the `Tip` caches its own rendered text
  at construction (inside a `GItem` info list that survives any font change) **and** the widget caches the
  composed image/`Tex`. Fixing only one is invisible. The pattern that worked: recorded recipe + `gen` compare in
  the `Tip` (F3b), plus a 3-line `gen` compare wherever a composed image is cached.
- **`settip(String)` is the free live path for a pre-rendered tooltip.** Several sites did
  `this.tooltip = Text.render(…)` / `RichText.render(…)` at construction — replacing that with
  `settip(text[, rich])` routes them through `KeyboundTip`, which re-renders on a `gen` move for free (and gains
  the keyboard-shortcut tail). Prefer it over adding another cache field.

---

- **(F4) A named scope, not the dynamic one.** F3d's `Fonts.enter`/`scope()` is powerful enough to hijack text it
  should not: world text renders through the same generic statics as tooltip rows. Routed world sites therefore ask
  the provider for their scope **by name**, and a headless check asserts an active `enter("tooltip")` does not reach
  them.
- **(F4) Speech bubbles are the friendliest font surface in the client** — the frame is measured from the text every
  frame, so any size works; and they are self-testable (talk in area chat and look at your own head). Kin names are
  the least friendly: they need a **kin visible on screen**, which no amount of client code can fake.
- **(F5) The draw pass IS a scope stack — reuse it before inventing per-widget state.** "This widget and everything
  inside it" is exactly what the parent-first `Widget.draw(GOut, boolean)` descent already expresses. One frame opened
  in that loop gave per-instance fonts over **every** render site F1–F4 had routed, with no second resolution path
  anywhere. The same trick is available to any future "scoped to a subtree" feature (colours, UI scale, opacity).
- **(F5) A global generation counter cannot express a per-PLACE override — stamp it.** Every routed site does
  `if(Fonts.gen() != mygen) rebuild`. That check silently fails for a widget **constructed after** the override
  (its captured generation is already current), which would have left new rows stock forever. Making `gen()` report
  `gen ^ spec.stamp` **while the frame is active** turns the existing check into a *contextual* one — unchanged call
  sites detect a change in **where** they are drawing. It must be **stable** across frames (a per-frame value would
  rebuild every frame) and must revert automatically outside the frame, which a pure XOR of a fixed stamp gives.
- **(F5) Weak keys ⇒ a flag, not an owned-resource list.** The project's default P2 pattern (a `List<X>` on `Addon`
  swept at teardown) would have re-introduced exactly the leak the `WeakHashMap` exists to prevent: a list of styled
  widgets pins closed windows. `Fonts.removeOwner(this)` already sweeps the whole registry by owner identity, so the
  addon only needs a **boolean** saying whether the sweep is worth doing.
- **(F5) `Widget` has no `equals`/`hashCode` — so a `WeakHashMap` keyed on it is an identity map for free.** Worth
  knowing before reaching for a hand-rolled weak identity map.
- **(026.1) `Fonts.gen()` is a per-SITE value, not a frame-global — so it keys a shared cache, it does not clear
  one.** F5 made `gen()` report `gen ^ spec.stamp` *while a per-instance frame is open*, which is exactly what a
  single widget's `gen != mygen` compare wants. But a cache shared by several draw sites (`LuaGOut`'s, spanning a
  widget inside a `node:setFont` frame, a HUD overlay outside one, and a gob overlay) sees that value **alternate
  within one frame**: the `Label`-style "generation moved ⇒ drop everything" rule would then clear the cache on
  every alternation and be strictly worse than no cache. Making the generation a **component of the key** costs the
  same single `int` per draw, is correct under F5 for free, and lets the stale generation's entries fall out of the
  LRU on their own. Rule of thumb: a generation counter that carries *context* can be keyed on, never cleared on.
- **(026.2) A cache cap below one frame's distinct strings is strictly WORSE than no cache — size the entry cap by
  the cliff, not by the working set.** Measured, `LuaGOut`'s cache sits permanently full (512/512) while the actual
  working set is ~40 lines a frame: the rest are dead one-shot strings. Cutting the cap to the working set would
  save VRAM and barely move the hit rate — but any cap under the *distinct strings drawn in a single frame* evicts
  every entry before its next use, and then pays eviction + `dispose` **on top of** the rasterisation it failed to
  save. The headroom is the guard against that cliff; a **byte** cap is what bounds its cost. Corollary: two caps
  only earn their keep when they are set to meet at the measured average entry size (here ~15.8 KiB — a ~256×16
  raster rounded to powers of two), so narrow text is bounded by count and wide text by bytes. The provisional
  16 MiB could never bind before 512 entries: it was decoration until it was measured and halved.
- **(026.2) A permanently-evicting cache at 88.8% hit rate is the feature working, not a shortfall.** The misses are
  one string per frame that never existed before; no cache helps those. Report `hitRate` next to `evictions` so the
  reading is interpretable — and make it **absent, not 0**, before the first lookup (D-050).
- **(028.1) An owned-resource LIST is earned by something releasable — a font asset has nothing.** The plan
  gave font assets an `Addon.fonts` list beside `images`/`meshes`, by symmetry. But an image owns a `TexI` and
  a mesh owns its shared `TexI`s, while a `FontHandle` owns an AWT `Font` (no counterpart to `registerFont`)
  and a `RichText.Foundry` cache that dies with the handle: teardown would have had nothing to call. The list
  was dropped, and with it the `dead` flag that would have broken `FontHandle`'s documented immutability —
  the intern-cache entry IS a font asset's whole lifetime, so `font:dispose()` is "drop the cached parse; the
  next load re-reads and re-registers the file". Symmetry across sibling types is worth less than each type
  telling the truth about what it holds.
- **(033.1) A teardown sweep and a live drop are NOT the same call, once one owner owns two kinds of thing.**
  `Fonts.removeOwner(a)` pulls an addon's entries from the scope stacks **and** from the per-instance registry
  (`widget:setFont`, F5) — exactly right at teardown, and silently wrong for `hafen.ui.skin(nil)`, which would
  have taken every `widget:setFont` the addon had installed down with the sheet. The sheet drops per-scope
  (`Fonts.reset(site, owner)` over the rules it filled); teardown keeps the one-sweep form. The tell is that
  the convenience method was written when the owner owned **one** kind of thing, and nothing about its name
  changed when the second kind arrived.
- **(033.1) The site-vs-tree test on a parsed selector is two lines, and both are load-bearing.** A refiner
  (`@Class`, `[title=]`, `[res=]`) can only ever be answered by a *widget*, so any refiner makes the key a tree
  key regardless of its role. And a refiner-less selector with **no** role can only have been written `*`,
  since `Selector.parse` rejects an empty selector — which is what lets `*` map to the `"default"` scope with
  no extra field on `Selector` and no string compare against the source. What is left is the bare role, and
  only the ones the font provider knows (`Fonts.isScope`) name a render site: `window` and `inventory` are
  widget roles with nothing behind them, so they are tree keys (D-067 from the other side).
- **(033.1) A stylesheet must be parsed BEFORE the previous one is dropped.** `skin{…}` replaces the addon's
  sheet whole, so the natural order is drop-then-apply — which turns a typo in the fifth rule into a client
  that is now unstyled *and* erroring. Parsing first makes a malformed sheet a pure error: nothing moved.
- **(033.1) A sheet that pushes nothing must push nothing.** `Fonts` keeps an addon-less client byte-for-byte
  stock through an `active` volatile that only a real `push` sets; a rule with no property (`["chat"] = {}`)
  and a sheet whose keys are all tree keys therefore install **zero** entries rather than an empty spec. An
  "apply always writes something" implementation would have cost every routed site its fast path for the
  lifetime of the sheet, and the symptom — a slightly slower client — is one nobody would trace back here.
- **(033.2) A provider that only fills `defcol` is correct and invisible — find out WHO PASSES THE COLOUR before
  designing a colour property.** The plan had `color` riding the existing `Spec` field into
  `new Text.Foundry(f, col)`, which is the foundry's *default* colour. Grepping the routed sites first would have
  shown it reaches almost nothing: `Label` renders with its own `col`, every tooltip site passes `Text.white`
  explicitly, and every chat line passes its speaker's `TextAttribute.FOREGROUND` as a per-render attribute that
  outranks the foundry's default. The answer that generalises is a **marker on the provider's own product** —
  `Text.Foundry.fixcol`, set only on foundries `Fonts.Spec` built — plus one substitution inside `render(text,c)`
  / `renderwrap`, and the same idea at the rich sites (`ChatUI.fndcol(site)`). Stock foundries never carry it, so
  the identity fast path and byte-for-byte teardown are untouched. **Rule: for a font, "what the site passes in"
  is a fallback; for a colour it is usually the actual value — so the two properties need opposite plumbing.**
- **(033.2) `$col` markup and a per-render attribute look alike in the code and are opposite in intent.**
  `fnd().render(text, w, TextAttribute.FOREGROUND, col)` is the *site* choosing a colour (a speaker's kin colour)
  and must lose to a sheet rule; `$col[…]{…}` inside the string is part of the *text* and must win. Both end up as
  a FOREGROUND attribute on a run. Decide per call site which one you are looking at — the tell is whether the
  colour came from the string or from a field beside it.
- **(033.2) A hub parser that accepts only one spelling of a literal fails SILENTLY, and the docs are where you
  find out.** `AddonManager.luaColor` read `{r=,g=,b=}` only, while the API's own docs (`markers.md`) and the
  033 spec both write the positional `{0, 200, 0}` — which parsed to `null` and was quietly replaced by a default.
  `hello`'s F2 per-call coloured `g:text` line had therefore never been coloured, in shipped code, unnoticed.
  Accepting both (keyed first, then positional) is four lines and makes every `color =` in the docs true. **When a
  reader hands back one shape and every hand-written literal uses another, the parser owes both** — and grep the
  docs for the literal before assuming your shape is the one people write.
- **(033.2) Once a rule carries two independent properties, a harness toggle must edit a PROPERTY, not the rule.**
  `hello`'s eleven site toggles replaced `skinRules[key]` wholesale, so `:hello chat` would have silently wiped
  the colour `:hello color` had just put on the same key. Re-expressing them over one `setProp(key, prop, value)`
  (which drops an emptied rule, since a rule with no property pushes nothing) keeps every existing toggle
  unchanged at its call site and makes the composition testable. The same shape will be needed again at C2, when
  `bg`/`border`/`pad` arrive.
- **(033.3) An EMBOSSED surface discards the glyph colour — check the FURNACE, not the foundry, before promising
  a `color` reaches a site.** 033.2 shipped `Text.Foundry.fixcol` (the sheet's colour outranks the caller's) and
  the docs were written as "every key takes `color`, except `window.title`". Reading the sites says otherwise:
  `Window.DefaultDeco` ([Window.java:189](../../../src/haven/Window.java)), `CharWnd.catf`/`GridList.dcatf`
  (**`heading`**) and `Button.nf` (**`button`**, the ordinary caption) are all
  `BlurFurn(TexFurn(foundry, ctex), …)`, and `PUtils.TexFurn.proc` calls `tilemod`, which **replaces the RGB with
  the tiled texture and keeps only the alpha** — so the colour the foundry rendered with never reaches the
  screen. Three of the eleven keys, not one. `button` is the interesting case because it is **partial**:
  `Button.render()` picks `nfont()` (the furnace) for a plain caption but `tfont()` (the plain foundry) for a
  `wrapped()` multi-line caption and for `change(text, col)` — so the same key honours `color` on some of its
  buttons and ignores it on the rest. The nearby false friend: `world.nick` also post-processes
  (`blurmask2`), but that is `alphablit(blurred-black-mask, img)` — it composites the ORIGINAL image on top, so
  colour survives. **The rule: a foundry-level colour survives a blur, not a tile.** Grep `TexFurn` before
  writing a colour column.
- **(034.1) A cached MISS needs 030.2's bounded re-check — a caption arrives AFTER the widget does.** Per-widget
  style resolution caches its answer (`WeakHashMap<Widget, Resolved>` + the sheet generation), and caching the
  *negative* is the whole point: otherwise every unmatched widget re-matches on every look. But a
  `[title=]` refiner resolves against the enclosing window's `cap`, which lands by `uimsg` a tick or two after
  the window is placed, and `[res=]` resolves asynchronously — so the first look at a just-opened window would
  cache "nothing matches" **forever**. `Selector.late()` already names exactly this class of refiner (030.2 uses
  it for its `PendingMatch` queue), so the fix is that rule applied one level over: while any installed tree rule
  is `late()`, a negative entry is re-matched a bounded number of times (20, `-Dhaven.addon.stylerecheck=`)
  before it settles. A POSITIVE entry needs none of it — the rules decided it, and a rule change bumps the
  generation. The trap when testing this: a widget that still matches *another addon's* sheet is a hit, not a
  miss, and the counter reads 0 for the right reason (cost the probe one red line).
- **(034.1) `FontHandle.handle` had been written and never read since F2 — and the first reader was a sandbox
  boundary.** A sheet rule stores the parsed `FontHandle`, so `w:style()` had to hand a font back to Lua. The
  addon that WROTE the rule must get the very table it named (`w:style().font == body` is the natural
  assertion), which is exactly what that field holds; but another addon reading the same rule must not receive
  it, because no Lua value crosses a sandbox boundary (D-017) — a shared table is another addon's `:derive` to
  overwrite. So `fontHandle()` split into `mint()` + the field assignment, and a foreign reader gets its own
  interned view over the same immutable `FontHandle` (`AssetApi.Cache.fontViews`, beside the built-in font
  intern). **The Java value is shared; the Lua value never is** — the rule every intern cache in the bridge
  already followed, met here from the other direction.
- **(034.2) F5's note came true literally: widening the frame's PAYLOAD is the whole draw-side feature.** F5 said
  *the draw pass IS a scope stack — reuse it before inventing per-widget state*, and C1b needed exactly one
  change to collect on it: `Fonts.frame(wdg)` stopped asking only "does this widget carry a `setFont`?" and also
  asks a source (`Sheet.specOf`) "what rule does it match?". No render site was re-routed, no drawing code
  changed, the subtree came free from the parent-first descent, and a widget built *after* the rule picks it up
  because the `gen ^ stamp` check was already contextual. The engine diff for "the sheet can style which widgets"
  is a payload swap plus a compose function.
- **(034.2) "Innermost wins" is not a cascade — compose per property, and prove it on the spec's own example.**
  The plan said a tree rule *outranks* a site rule, which read as wholesale replacement until it was written out:
  `["*"]={font=body}` + `["window[title=X]"]={color=…}` would have put that window back on the STOCK font, the
  broad rule cancelled by one that never mentioned fonts. The fix is one `combine(inner, outer)` used at both
  seams (frame push, site resolve) — and it makes the pre-existing `widget:setFont` level behave the same way,
  which is a shipped behaviour change worth stating out loud rather than discovering later ([D-076](../decisions/fonts.md)).
- **(034.2) A composed override must be INTERNED, or the stamp that makes F5 work destroys the frame rate.**
  `gen()` reports `gen ^ spec.stamp` inside a frame, so a Spec minted per resolve hands every routed site a new
  generation every frame — a correct feature at 12 FPS, the exact failure F5 warned about. Two levels of
  interning are load-bearing and they are NOT the same one: the *per-widget* `Resolved` cache is what makes the
  stamp stable **across frames**, while interning the style per (font handle, colour) is what makes two widgets
  under one rule **share** a stamp, a foundry and its raster. Falsifying them separately proved it — dropping the
  second broke only "a widget created after the rule resolves the same thing", which is a checkable claim in a way
  "it feels slow" is not.
- **(034.2) Lua cannot read a pixel, but it CAN read what the draw left in the text cache — and that is a real
  assertion about the draw.** `g:text`'s cache is keyed on `(string, font, Fonts.gen())`, and `Fonts.gen()` is
  the value the frame stamps, so drawing ONE string in two probe windows and counting `profiling():textcache()
  .misses` answers three questions a screenshot could not: the frame is in force at the draw (one string, two
  keys), the stamp is stable (the count stops moving over ~60 frames), and a widget created later joins the same
  frame (no new key). `textcache()` is pull-only, so none of it needs the profiler armed. Rule of thumb: when a
  feature is invisible to Lua, look for a cache whose KEY contains the thing you changed.
- **(034.2) The draw pass now spends 034.1's bounded negative re-check in ~20 frames, not lazily.** The same
  budget (`Selector.late`, 20, `-Dhaven.addon.stylerecheck=`) that a Lua reader consumed a call at a time is now
  consumed by the descent asking about every widget every frame — ~0.33 s at 60 fps. It still covers a caption
  arriving by `uimsg` a tick or two late, but a counter sized for one caller is not sized for another: when a
  lazy path becomes a per-frame path, re-read every bound written for the lazy one.
- **(034.3) When a read forces you to keep a second copy of a store, move the store instead of copying it.**
  `widget:style()` has to hand back a **`FontHandle`**, and the provider keeps no handles — it keeps an AWT font,
  a size and a flag. Keeping `Fonts.pushInstance` for the draw would therefore have meant an addon-side record
  beside it holding the same override in the readable shape: two stores for ONE cascade level, free to drift
  exactly where they overlap and impossible to test apart. Moving the whole per-widget registry out of
  `haven.Fonts` into the fold that already existed deleted ~100 lines of core, left the provider with the frame
  and nothing per-widget, and made "what does this widget resolve to" a question with one answerer
  ([D-077](../decisions/fonts.md)). The tell that you are in this situation: the new read cannot be written
  without duplicating state the old write already holds.
- **(034.3) The per-widget cascade's fold is where a new LEVEL goes — adding one is ~10 lines and no new path.**
  `widget:skin{…}` sits above every tree rule and reaches the screen, the subtree, `widget:style()` and the
  teardown with no code of its own at any of those seams: it is applied at the end of `fold()`, per property, and
  everything downstream (interning, the stamp, `Fonts.combine`, `specOf`) was already written by 034.1/034.2.
  That is what "no second resolution path" buys the *next* feature, not just this one — C2's `bg`/`border` are
  properties on the same rules, not another mechanism.
- **(035.1) The site half of the cascade does NOT compose per property — only the levels above it do.**
  `Fonts.scopeTop(scope)` returns the scope's own top-of-stack **or**, when it has none, `"default"`'s: a
  fallback, not a `combine`. So `["*"] = {bg=…}` beside `["window.frame"] = {border=…}` yields the border
  ALONE, and this is 033 behaviour that chrome merely made visible (it is equally true of
  `["*"] = {font=body}` beside `["chat"] = {color=…}`, where chat keeps its stock font). D-076's per-property
  fold lives in `combine(frameTop(), scopeTop(scope))` — i.e. between the **per-widget** levels and the site
  half, never inside the site half. Documented as a caveat rather than changed; changing it would silently
  alter every shipped 033 sheet.
- **(035.1) A style whose properties are all non-text must hand the site its OWN foundry back.** `["*"]` is the
  `"default"` scope, so a chrome-only rule there reaches every routed text site's `resolve()`. Without a
  short-circuit, `Spec.foundry(stock)` builds a *new* foundry equal to the stock one — same family, same size,
  same colour — and every routed site in the client re-derives and re-rasterises for a background it never
  reads. One `if` at the top of `Spec.foundry` (all of `base`/`size`/`aa`/`color` null → `return stock`) keeps
  the identity fast path. The check that guards this must install the rule on **`*`**, not on a scope the site
  does not fall back through — testing `window.title` while the rule sat on `window.frame` proved nothing, and
  only the falsification showed it.
- **(035.1) `Fonts.styleFor(scope, widget)` is the chain with the frame NAMED rather than ambient.**
  `style(scope)`/`foundry(scope, stock)` read `frameTop()`, a `ThreadLocal` only meaningful inside the draw
  descent. Anything deciding *outside* a draw — 035.1 decides the deco swap in `Window.tick` — needs the same
  `combine(treeTop(w), scopeTop(scope))` against a widget it names. Resolve `treeTop` **before** taking
  `Fonts.class`: it calls into the style source, and the established lock order never holds the provider's
  monitor across that call.
