# 102 — Translation: plan

## Approach

**One interception, one key.** A string becomes pixels in exactly two places: `Text.Foundry.render(String,
Color)` and `RichText.Foundry.render(…)`. Both already ask a private `resolved()` which reads
`Fonts.dynamic()` — the innermost scope of the thread-local stack `Fonts.enter(scope)`/`exit()` keeps.
Translation lands beside that read: `Fonts.display(String scope, String text)` answers what to draw, and the
two `render` entry points call it with `Fonts.scope()`. Nothing above the render is touched, which is the
whole *the model is not translated* invariant — and it is why text drawn by published `.res` code is reached
for nothing, since that code renders through the same two foundries with its own private one.

A site therefore contributes one thing, **its scope**, declared with the `Fonts.enter`/`exit` pair
`ItemInfo.longtip` and `MenuGrid` already use for tooltips. `Button.checkfont` and `ChatUI.Message.scope()`
already name theirs; `Label` holds one in its private constructor; the rest is the per-slice routing the
stylesheet did before, never all at once.

**The catalogue** is a twin of `Fonts.overrides`: a `List` in install order, each member tagged with its
owning `Addon` and holding surface → English → display, plus an ordered pattern list. `Fonts.display`
walks it from the top and takes the first member naming the string under that surface, then under `*`, then
scans that member's patterns; one that names nothing falls through to the one beneath — criterion 8 at no
cost. `push`, `removeOwner`, `bumped` and `prune` are the shapes already there for `Spec`, and install and
release both go through `bumped()` so every cached `Text` rebuilds on its own `gen != mygen` compare.

**The document** carries two properties and no others: `text`, an object of surface → `{english = display}`,
and `pattern`, an **array**, so the order is the author's. `hafen.locale():load(doc):install()` is the only
door — an entry is one string with no configuration, so there is no builder twin of `sheet:rule()`.

**The misses** are per catalogue: a bounded set of the `(surface, text)` pairs that reached `display` and
matched nothing, emptied by `:install()`. It is also the feature's own oracle. Criterion 6 makes every
readback answer English, so nothing else can observe a translation from Lua at all, and a suite that could
not ask what missed would be `[manual]` from end to end.

## Files to create and modify

Create `src/io/brodgar/addon/LocaleApi.java` (the section and its verbs) and `Catalogue.java` (the parsed
document and the refusals — `Sheet.java`'s twin).

Modify `src/haven/Fonts.java` (`display`, the catalogue stack, the locale-key subset of `SCOPES`, `enter`
behind the widened guard); `Text.java` and `RichText.java` (`Foundry.render`); `Label.java`, `Button.java`,
`Window.java`, `ChatUI.java`, `TextEntry.java`, `FlowerMenu.java`, `ItemInfo.java`, `MenuGrid.java` and
`res/ui/tt/slots_alt/ISlots.java` (the scope pairs); `src/io/brodgar/addon/AddonManager.java` (mount the
section, release on reload and disable).

Docs: `docs/addons/api/locale.md` and `docs/addons/guides/translating.md` are new;
`docs/addons/api/README.md`, `docs/addons/guides/README.md`, `docs/client/text-and-fonts.md`,
`docs/addons/api/ui/selectors.md` and `docs/addons/runtime.md` are revised.

## Risks and gotchas

- **`Fonts.enter` is unguarded.** `Fonts.scope()` early-outs on the `active` volatile; `enter` allocates and
  pushes whatever the client is doing. Widen `active` to mean *any override or any catalogue*, or a client
  with no addon at all pays an `ArrayList` add and remove at every routed render.
- **`Text.text` becomes the display string.** `Label.settext` compares its argument against `this.text.text`
  — the rendered `Text`, not `Label.texts` — so the guard stops short-circuiting and an unchanged write
  re-renders. `w:text()` is safe: `LuaWidget` reads `Label.texts`. Check the same when routing a new site.
- **`Fonts.gen()` is not frame-global**: while a per-widget style frame is open it XORs in that `Spec.stamp`.
  Move it through `bumped()` as `push` does, and never as a global clear.
- **A pattern scan costs per render.** `GOut.atext` is render, `tex()`, `aimage`, `dispose` every frame, so a
  `*` pattern list would be walked per frame at every immediate-mode site. Exact key first, patterns only on
  a miss, the lot behind the widened guard.
- **`TexFurn` mutates the slug it is handed**, and the four embossed sites stack `BlurFurn(TexFurn(…))` over
  a `Forge`. Translation lands beneath the decoration, which is right, but the raster grows with the longer
  string and `tloff()`/`broff()` move what the site lays out around it.
- **Half of `Fonts.SCOPES` draws no text.** The keys its comments mark `C2` are boxes and plates;
  `chat.urgent` and `chat.speaker` are colour sequences. All are refused as locale keys, and `textentry`
  is refused separately and for its own reason.
- **A suite is one addon**, so criterion 8's second owner has no in-game oracle. The fall-through *within* one
  catalogue is automated; the second owner is verified by reading the `removeOwner` twin.
- **`CLabel` calls `super("")`** and so draws under `default`, while `CtlButton extends Button` draws under
  `button`. Those two are what a suite can build and drive with a string it chose.

## Discarded alternatives

- **`:drop()` as the ending** — `release` is the word this vocabulary gives a hold over what the client
  owns, and `drop` already names an act that is not an ending: `item:drop(n)` puts an item on the ground.
- **A plural verb handing back an array of misses** — a set is a collection, and `:list()` is the verb that
  enumerates wherever it appears; a bare array is the shape retired everywhere else.
- **Keying an entry on the resource name it was decoded from** — it survives a rewording and tells two
  surfaces reading "Branch" apart, but it is an index built at decode time, not a render seam, and it reaches
  nothing the client composed in code.
- **Patterns as an object keyed by the pattern** — an object has no order, so the document could not say
  which of two overlapping patterns wins; an array makes the order the author's.
- **Translating at each site rather than at the foundry** — it would be written at every one of the ~81
  baked foundry sites and still miss the text a resource's own private foundry draws, the half nothing
  else reaches.
- **`hafen.ui():locale()` beside the sheet** — the catalogue reaches speech bubbles, floating kin names and
  item names, none of which is the UI section's subject. A sheet is a look; this is what the client says.
- **Recording misses globally** — a miss is what *your* catalogue did not answer, and one shared list would
  report another addon's gaps as yours.
- **A `LocaleChanged` event** — nothing outside the render can see a translation by construction, so
  there is no state for a handler to react to.
