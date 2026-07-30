# F3d — The `"menu"`, `"tooltip"` and `"chat"` font scopes (completes F3)

> **Status:** ✅ Implemented; clean compile (`rm -rf build/classes && ant hafen-client` → BUILD SUCCESSFUL) +
> `ant bin`, **89 headless checks** in eight groups (33 provider-resolution checks for the new
> `Fonts.style(scope)` primitive over the three scopes; 24 site-twin checks proving each routed foundry returns the
> **identical** stock object when nothing overrides and swaps family/size — keeping its stock size — when something
> does; 8 **render** checks proving `$b`/`$col` markup still applies *through* a tooltip override and that the chat
> foundry keeps its URL parser; 6 **tooltip-engine** checks added by the widening below — the same cached
> `ItemInfo` tips re-render, the short variant follows, teardown restores the exact stock composition, and the
> `"default"` cascade reaches the engine; 8 **published-code** checks for the composition scope — an override now
> reaches tip code we cannot edit, and nothing bleeds outside a composition; 5 **private-foundry** checks for the
> foundry-level resolution — a published tip holding its *own* foundry follows too, without recursing, and is
> untouched outside a composition; 2 **constructor-render** checks for the info-list rebuild; 3 **no-Raw**
> regression checks, see below) + LuaJ parse of all 7 addons.
> `"menu"` and `"chat"` are **in-game verified ✅**; `"tooltip"` took **five passes** against in-game feedback
> (see below) — the last one adopting the resource's own code locally (`doc/resource-code`) — and is **pending
> re-verification**.
> *(Java engine change ⇒ `ant` rebuild + full client restart.)*
> **Design:** [specs/addons/21-fonts.md](../../../specs/addons/21-fonts.md) §F3, decision
> **[D-043](../../../specs/addons/decisions.md)**. Builds on [f1-fonts](f1-fonts.md) (the provider),
> [f3a-window-title-font](f3a-window-title-font.md) (furnace rebuild), [f3b-button-font](f3b-button-font.md)
> (the recorded recipe), [f3c-textentry-label-fonts](f3c-textentry-label-fonts.md) and
> [f3e-heading-font](f3e-heading-font.md).

The last three UI-chrome scopes, and the first ones whose render sites are **not** `Text.Foundry`s: chat and the
tooltips are **`RichText.Foundry`** surfaces. So this slice adds one new provider primitive and then routes six
sites with the patterns the earlier slices established. With it the whole F3 group is live and
`hafen.font.scopes()` has no inert UI-chrome member left (only F4's two world scopes remain).

## The API

```lua
hafen.font.setFont("menu",    h)   -- flower-menu petals + the action-menu keybind letters
hafen.font.setFont("tooltip", h)   -- every tooltip the client pops up
hafen.font.setFont("chat",    h)   -- the chat window: messages, channel tabs, the typed quick line
hafen.font.reset("menu")           -- drop it (also automatic on :reload/disable)
```

No Lua-side change at all — the three names were already in the enum (declared in full at F1) and
`FontApi.setFont` validates against `Fonts.isScope`. They simply stop being inert.

## The new primitive: `Fonts.style(scope)` (resolved scalars, not a foundry)

Every routed site so far could ask [`Fonts.foundry(scope, stock)`](../../../src/haven/Fonts.java) for a ready-made
`Text.Foundry`. A `RichText.Foundry` cannot be produced that way:

- it is built from **AWT text attributes**, not from a `(Font, Color)` pair; and
- `ChatUI.fnd` is built over a **`ChatParser`** (a `RichText.Parser` subclass that turns URLs into clickable
  parts), which the generic `RichText.Foundry.derive(…)` would silently drop — it always constructs a plain
  `Parser` from the merged attributes.

So the provider grew a second, lower-level accessor. `Fonts.style(scope)` returns a **`Fonts.Style`** — the
resolved override for that scope, exposing exactly the three scalars a site needs, each with the site's own stock
as the fallback:

```java
public interface Style {
    Font    font(Font stock);      // the override's font at its size, or at `stock`'s size when it carries none
    Color   color(Color stock);    // the override's colour, or `stock`
    boolean aa(boolean stock);     // the override's antialias flag, or `stock`
}
```

It returns **`null`** when nothing overrides the scope (neither its own stack nor the `"default"` cascade) — the
site's cue to keep its stock foundry object **untouched**, exactly like `foundry(scope, stock)` handing back the
same stock it was given. Resolution and caching are shared with `foundry`: the same `active` fast path, the same
`"default"` cascade (`resolveStyle` mirrors `resolve`), and a second per-stock `IdentityHashMap` inside `Spec` so
`style(…).font(myStock)` is a map lookup after the first call and **each site keeps its own size** (chat's 12 px
and the tooltip's 10 px, under one override).

`Spec` — the class that already modelled one installed override — simply `implements Style`; `Style` is the public
interface the `haven` sites see, so the registry internals stay private.

### FAMILY+SIZE, not `TextAttribute.FONT`

For the two rich sites that describe their stock by *attributes* (`RichText.stdf` and `MenuGrid.ttfnd`: `FAMILY
"SansSerif"` + `SIZE`), the twin overrides **`FAMILY` + `SIZE`** rather than setting `TextAttribute.FONT` — with a
`FONT` attribute present AWT ignores `FAMILY`/`SIZE`/`WEIGHT`/`POSTURE`, which would kill the `$b`/`$i`/`$size`
markup a tooltip carries. Deriving also **inherits the stock default attributes**, which matters concretely:
`RichText.stdf` carries `IMAGESRC`, without which a pagina tooltip's `$img` cannot resolve. `ChatUI.fnd` is the
opposite case — stock itself passes `TextAttribute.FONT`, so the twin passes `FONT` too and chat rendering is
bit-for-bit the same recipe with one font swapped.

## What changed — first pass (5 files + the provider, every edit tagged `// addon:`)

### `Fonts.java` — the primitive

`Style` (public interface) + `Spec implements Style` (`font`/`color`/`aa` + an `IdentityHashMap<Font, Font>`
cache) + `style(String)` / `resolveStyle(String)`. No behaviour change to any existing call path.

### `"menu"` — [`FlowerMenu.java`](../../../src/haven/FlowerMenu.java), [`MenuGrid.java`](../../../src/haven/MenuGrid.java)

- **`FlowerMenu.ptfont()`** — the F3b twin over the stock `ptf` (`Fonts.foundry("menu", ptf)`, rebuilt on a
  `gen` move). `ptf` itself stays stock.
- **`Petal.render()`** — the petal caption moved out of the constructor into a method (the F3b *recorded recipe*;
  a petal already holds its `name`, so nothing new had to be remembered), and `Petal.draw` re-renders when the
  generation moved. A petal is **positioned centre-first** (`move(Coord)` / `move(a, r)` set `c` from the centre)
  and nothing re-places it once the opening animation finishes, so `render()` re-sizes **around its own centre** —
  growing from the top-left would visibly shove an open menu off its ring. The old `Text` is disposed.
- **`MenuGrid.PagButton.keyfont()`** + a `keygen` compare in `keyrend()` — the keybind letter the action-menu
  grid paints over its buttons (the show-keys overlay) follows the same scope; the outlined `Tex` is disposed and
  rebuilt on a `gen` move.

### `"tooltip"` — [`UILoop.java`](../../../src/haven/UILoop.java), [`Widget.java`](../../../src/haven/Widget.java), `MenuGrid.java`

The client has **three** tooltip flavours and all three are now routed:

- **A plain `String` tip** is rendered at *display* time by `UILoop.drawtooltip` — it was going through
  `Text.render(…)`, i.e. the **`"default"`** scope (F1). It now asks for `Fonts.foundry("tooltip", Text.std)`, so
  it follows `"tooltip"` first and still cascades to `"default"` when that is unset. `drawtooltip` caches the
  rasterized tip keyed on the tooltip **object**, so a new `tipfontgen` field clears `prevtooltip` when the
  generation moves — the tip **under the cursor right now** re-renders.
- **`Widget.tipfoundry()`** — a shared provider-resolved `RichText.Foundry` (stock `RichText.stdf`, derived
  FAMILY+SIZE as above) for the two rich tooltip classes, each with a `fontgen` compare that disposes and
  re-renders its cached `Tex`: **`PaginaTip`** (the long action/item descriptions from a resource pagina) and
  **`KeyboundTip`** (every `settip(…)` tooltip, including its "Keyboard shortcut: …" tail).
- **`MenuGrid.ttfont()`** — an action button's own rich tooltip (`ttfnd`, `$b`/`$col`-marked-up in `rendertt`),
  plus a `curttgen` compare in `MenuGrid.tooltip(…)` so the hovered tip is re-rendered rather than served from
  `curtt`.

**…and then the maintainer hovered a tooltip and nothing changed.** Correctly: those three sites are the *least*
visited third of the surface. The client's real tooltip surface is **`ItemInfo`** — see the widening section below.

### `"chat"` — [`ChatUI.java`](../../../src/haven/ChatUI.java)

`fnd`/`qfnd` stay stock; a single `checkfont()` builds both twins on a `gen` move:

- **`ChatUI.fnd()`** — the message foundry. Rebuilt as a fresh `RichText.Foundry(new ChatParser(FONT, …,
  FOREGROUND, …))` from the resolved scalars, so the **URL parser survives** the override (the whole reason
  `Style` exists). `fndstock`/`fndcol` name the font/colour stock bakes in, so the provider can inherit the stock
  **size 12** and the stock local-message **black** when the handle carries neither.
- **`ChatUI.qfnd()`** — the quick-line foundry (the `Text.Foundry` behind the `<channel>> …` line you type over
  the map). It is `ReadLine`-backed like an F3c text entry, but it lives in the chat window and is rendered with
  the chat's own recipe (dfont 12, pale green), so it belongs to **`"chat"`**, not `"textentry"`.
- **`Selector.tfont()`** — the channel tabs down the side of the chat window (a per-`Selector` twin over its
  instance `tf`), with a `fontgen` compare in `DarkChannel.rname()`. The `ellw`/`maxnmw` truncation constants
  stay measured from the stock `tf` (they are `final`, computed once at construction) — an override only changes
  the glyphs, so a much wider font can truncate a long channel name slightly early.
- **Invalidation of the scrollback** — `RenderedMessage` got a `fontgen` field checked in **`update()`**, which
  `Channel.draw` already calls for every **visible** message and whose `true` return re-runs `updyseq()`. So a
  restyle re-renders only what is on screen (the rest re-renders as it is scrolled into view) **and** the message
  heights are re-measured, so the log re-flows correctly under a bigger font. The typed line has the same
  compare (`rqgen`), and the transient 5-second floating notifications are simply left alone — they expire.

## `"tooltip"` WIDENED after the first in-game pass — `ItemInfo` is the surface

The maintainer reported `:hello menu` and `:hello chat` working and **`:hello tip` changing nothing**. A headless
probe showed the wiring was fine (a `settip` tooltip went from 40×12 to 83×30 under an override), so this was the
**F3c lesson repeating** ([route the surface, not the class](f3c-textentry-label-fonts.md)): the three sites routed
above are the *least*-hovered third of the surface. What a player actually hovers — an inventory item, a buff, a
vitals bar, a craft input, a minimap marker, a character-sheet attribute row, an action-menu icon — is composed by
**`ItemInfo.longtip`/`shorttip`**, the client's tooltip **engine**, out of `Tip` objects that render their text
with `Text.render`/`RichText.render` (i.e. `"default"`) and **cache it at construction**, inside a `GItem`'s info
list that outlives any font change. `grep -c` again told the story: `longtip(`/`shorttip(` has **17** call sites
across `WItem`, `Buff`, `LayerMeter`, `Makewindow`, `MiniMap`, `CharWnd`, `MenuGrid` and `resutil`.

So the scope was widened to the engine (**every edit tagged `// addon:`**):

- **[`ItemInfo.java`](../../../src/haven/ItemInfo.java)** — a private `tipfnd()` = `Fonts.foundry("tooltip",
  Text.std)` for the plain-text tips and `Widget.tipfoundry()` for the rich one:
  **`AdHoc`** and **`Name`** keep their source string (`rtext`) and re-render `str` (now non-final) from `tipimg()`
  when the generation moved — the F3b recorded recipe again, and `Name.shortvar()`'s anonymous tip calls the same
  `checkfont()` so the short (name-only) variant follows. A `Name` built from a **caller-supplied `Text`** keeps
  it (`rtext == null`), the same policy as a caller-supplied `Button` face. **`Pagina.tipimg`** renders per call, so
  routing it was one line. **`Contents.ch`** ("Contents:") was a class-init `static final Text.Line` — the F3e
  lesson — and is now rendered per tooltip.
- **The seven caches that hold a composed tooltip image** each got a 3-line `gen` compare that drops it:
  `WItem` (`shorttip`/`longtip`), `Buff`, `LayerMeter`, `Makewindow.SpecWidget` (`stip`/`ltip`),
  `MiniMap` (the marker tip **and** the hover composition), `CharWnd`'s attribute row (`tipimg`), plus `MenuGrid`
  from the first pass.
- **The rich tooltip statics** — `RichText.render(…)` turned out to be *almost exclusively* a tooltip call: routed
  in `BAttrWnd` (the food-meter tip, cached → `gen` compare), `Buff` (the pagina tail), `Makewindow` (same),
  `MiniMap` (the terrain line), `Fightsess` (the hovered-action tip, `gen`-compared), `OptWnd`
  (the keybind help — another class-init `static final Text`, now a method) and
  `resutil/FoodInfo` ×4 + `resutil/Curiosity` — the **food and study tooltips**, which is what players read most.
- **The remaining pre-rendered plain tips** were routed too: `Equipory.etts` (the empty-slot names — a class-init
  `static final Text[]`, so a parallel `betts` array is rebuilt on a `gen` move, `etts` itself staying stock),
  the `SkillWnd` skill/credo/experience list tips (`gen`-compared), `Fightsess`'s two last-action tips, and two
  `GameUI` tips (`kin` and the chat button) which simply became **`settip(…)`** calls — the live path.

**14 more files** (`ItemInfo`, `WItem`, `Buff`, `LayerMeter`, `Makewindow`, `MiniMap`, `CharWnd`, `BAttrWnd`,
`Equipory`, `SkillWnd`, `Fightsess`, `GameUI`, `OptWnd`, `resutil/FoodInfo`, `resutil/Curiosity`), still no new
class and no `io.brodgar` change. Nothing is knowingly left on `"default"` now except text that is not a tooltip.

## `"tooltip"` WIDENED again — the rows drawn by PUBLISHED CODE

Second in-game pass, second (smaller) hole: on a glass jug the **name** and `Contents:` changed but
**"Quality: 31.7"**, `4.60 l of Water`, `Wear`, `Armor class`, `Gilding`, `Perception +5` did not. Those rows are
not client code at all — they are **published code**: Java classes that ship *inside the resources*
(`ui/tt/q/qbuff` for the quality row, `ui/tt/wear`, `ui/tt/attrmod`, …; the `.res` file carries the preprocessed
source **and** the compiled class). Dumping `ui/tt/q/qbuff.res` settles it:

```java
nm[i] = CompImage.mk(Text.render(q.name + ":").img);
qv[i] = CompImage.mk(Text.render(... String.format("%.1f", q.q)).img);
```

They render through the generic **`Text.render` static**, which F1 bound to `"default"` — so a `"tooltip"`
override could never reach them, and we cannot edit the classes.

**The fix: make the scope dynamic rather than lexical.** The composer declares the context, the generic statics
ask for it:

- **`Fonts.enter(scope)` / `Fonts.exit()` / `Fonts.scope()`** (a per-thread stack; `scope()` is `"default"`
  outside any composition, and short-circuits on the existing `active` flag so it costs a `volatile` read in a
  client with no font override).
- **`Text.render`/`renderf`** and **`RichText.render`** now resolve `Fonts.scope()` instead of the literal
  `"default"`.
- **`ItemInfo.longtip`/`shorttip`/`buildinfo`**, **`MenuGrid.rendertt`** and **`Buff.longtip`** wrap their body in
  `enter("tooltip")` / `finally exit()`. Everything rendered from there down — **including published code that
  knows nothing about any of this** — resolves `"tooltip"`, falling back through the `"default"` cascade exactly
  as before. Text rendered anywhere else is untouched.

Two useful side effects. The rich twin moved into **`RichText.foundry(scope)`** (per-scope, `gen`-cached, still
FAMILY+SIZE), with `Widget.tipfoundry()` now a one-line delegate — and because the `RichText.render` statics go
through it, **rich text finally follows `"default"` too**, which F1 never wired (it routed only `Text.render`).

### …and the rows whose foundry is private

Third pass: the quality/attribute rows now followed, but **`Gilding:`** and the italic **`Gildable (6/6)`** still
did not. Those published tips do not use the generic statics at all — they keep **their own `Text.Foundry`**
(that is why they render smaller and italic), which no provider call site and no routed static can reach.

The composition scope makes even that tractable, because the *context* is already declared. `Text.Foundry` (and
`RichText.Foundry`) gained a **`resolved()`** step consulted by their entry points (`render`, `renderwrap`,
`strsize`, `height`): while `Fonts.dynamic()` names a composition scope, a foundry resolves through the provider
**with itself as the stock** — so an override applies while the site's own size, colour and antialias survive; the
caller's colour is still the colour passed to `render`, so colour-coded rows (a green `+11`) keep theirs. Outside a
composition `Fonts.dynamic()` is `null` and `resolved()` returns `this`, so nothing else in the client changes. A
`noresolve` flag marks the provider's own products, so resolution cannot recurse.

That closes the scope: **while the client composes a tooltip, all of its text follows `"tooltip"`** — client code,
published code, routed or not.

### …and the rows a tip renders in its constructor

Fourth pass: only the **`Gilding:`** category heading was left. A tip that renders its text in its **constructor**
(rather than in `layout`) is cached inside the owner's `ItemInfo` list — and that list is built once and survives
any font change, so no render-time context can reach it. So the list is rebuilt: **`GItem.info()`**,
**`Buff.info()`**, **`LayerMeter.info()`**, **`MenuGrid.PagButton.info()`** and **`MiniMap.DisplayIcon.info()`** drop
their cached list on a `Fonts.gen()` move, and since `ItemInfo.buildinfo` runs *inside* `enter("tooltip")` every tip
— ours or published — re-renders in the right font as the list rebuilds. That is the same rebuild the server
already triggers on every item update, so it is known-cheap, and it only happens when an override moves.

### The last row: `Gilding:` — a LOCAL COPY of the resource's code

`Gilding:` still did not follow. Rather than widen blindly a fifth time, the resource was read: the client's own
on-disk cache (`%APPDATA%/Haven and Hearth/data`, whose files carry the resource **name** in a plaintext header, and
whose `.res` payload stores the published **source** next to the compiled class) yields the slot-tip code — and, importantly, the resource the running game
actually uses is **`ui/tt/slots-alt`** (package `haven.res.ui.tt.slots_alt`), not `ui/tt/slots`:

```java
public static final Text ch = Text.render("Gilding:");                            // rasterised at CLASS LOAD
public static final Text.Foundry progf = new Text.Foundry(Text.dfont.deriveFont(Font.ITALIC), 10, ...);

public void layout(Layout l) {
    l.cmp.add(ch.img, new Coord(0, l.cmp.sz.y));                                   // blits finished pixels
    ...
    l.cmp.add(progf.render((left > 1) ? String.format("Gildable ×%d", left) : "Gildable").img, ...);
}
```

One class, the whole boundary: `progf` is a **static foundry used at layout time**, so foundry-level resolution
reaches it (`Gildable ×2` follows) — while `ch` is a **`static final Text` rasterised when the class loads**, and
`layout` only blits its pixels. Nothing re-runs a static initialiser and the JDK forbids replacing a `static final`,
so from *inside* the font system that row is unreachable.

But the client does not have to stay outside it. The engine ships a sanctioned mechanism for exactly this
([`doc/resource-code`](../../../doc/resource-code)): fetch the resource's source into the tree and let a **local
copy take over**, matched by name + version:

```bash
java -cp bin/hafen.jar haven.Resource get-code ui/tt/slots-alt
```

That wrote `src/haven/res/ui/tt/slots_alt/{Fac,ISlots}.java`, each annotated
`@haven.FromResource(name = "ui/tt/slots-alt", version = 4)`. `Resource.ResClassLoader` prefers a local class over the resource's own **only** when that
annotation matches the resource it is replacing, so the takeover is explicit and version-checked. The edit is then
trivial — render the heading **on demand** instead of at class load:

```java
public static Text ch() {                       // addon: (F3d) was the class-init `static final Text ch`
    int g = Fonts.gen(); String sc = Fonts.scope();
    if((bch == null) || (chgen != g) || !sc.equals(chscope)) { bch = Text.render("Gilding:"); chgen = g; chscope = sc; }
    return(bch);
}
```

`layout` calls `ch()` (and `SItem.layout` takes its row height from it), so the plain `Text.render` resolves
whatever scope is current — `"tooltip"` inside a composition, `"default"` outside. The cache is keyed on the
**scope as well as the generation**, so a render made outside a composition is never served inside one. The stock
`ch` field is kept as-is for any other caller.

**Trade-off to remember:** the takeover is pinned to `ui/tt/slots-alt` **v4**. If the server ships a new version, the
client logs `local copy ... is overridden by code from ...` and uses the resource's own code again — the heading
silently returns to stock, nothing breaks. Refresh with the same `get-code` command. That is the documented
contract of local resource code, and it is why `override = true` was *not* used: running stale local code against a
changed resource is a worse failure than losing one styled heading.

### Two false trails on the way there, both worth remembering

The first local copy was made for **`ui/tt/slots`** — and had no effect, because the game serves
**`ui/tt/slots-alt`**: a different resource, a different package (`slots_alt`), its own version line. Nothing said
so, for two compounding reasons:

- **`Warning.warn` is invisible in-game.** The "local copy ... is overridden by code from ..." report goes through
  `Warning`, which falls back to `System.err` **only when no `ErrorHandler` is installed** ([Warning.java:89](../../../src/haven/Warning.java:89)) — and the
  running client installs one. A rejected local copy therefore looks exactly like a working one.
- **One branch never reports at all.** In `ResClassLoader.loadClass`, if the parent loader does not have the class,
  the `ClassNotFoundException` is swallowed and the resource's own code is used silently — which is precisely the
  branch a wrong resource name lands in.

The fix was a temporary diagnostic printing the branch taken to **stdout** (not through `Warning`), which answered
it in one hover:

```
[rescode] haven.res.ui.tt.slots_alt.Fac: parent does NOT have it -> resource code (ui/tt/slots-alt v4)
[rescode] haven.res.ui.tt.slots_alt.Fac: USING THE RESOURCE'S CODE
```

**Never assume the resource name** — a server may ship a variant (`-alt`) of any published-code resource. Read it
off the running client. (The `ui/tt/slots` copy was deleted once `slots-alt` proved to be the live one; keeping a
local copy of a resource the client never loads is pure maintenance debt.)

With that, **every** row of every tooltip follows the scope.

### Regression caught in-game: rebuilding an info list too early

The info-list rebuild above had a bug worth recording. Stock never calls `ItemInfo.buildinfo` for an item whose
`tt` message has not arrived: the owners keep `info` as an **empty list** (`Collections.emptyList()`), never
`null`, so `info()` returns it untouched and `rawinfo` stays unread. The font-change rebuild set `info = null`
unconditionally, which forced `buildinfo(this, null)` and threw
`NullPointerException: Cannot read field "data" because "raw" is null` — **on the UI thread**, from
`SAttrWnd.StudyInfo.tick` (a study slot ticking before its tooltip data lands).

Fixed at both levels: the rebuild only drops the cache **when there is something to rebuild from**
(`if(rawinfo != null)` in `GItem`/`Buff`/`LayerMeter`), and `buildinfo` itself now returns an empty list for a
missing `Raw` instead of walking it — which also covers `MiniMap`, whose icon may legitimately have no info.
Three headless checks pin it (`(Raw)null`, `(Object[])null`, `Raw.nil`).

**The lesson:** an "invalidate on change" hook must not turn *"not loaded yet"* into *"load it now"*. `null` and
"empty" meant different things here, and only the widget knew which.

## Verification

- **Clean compile:** `rm -rf build/classes && ant hafen-client` → **BUILD SUCCESSFUL** (only the three
  pre-existing `URL` deprecation warnings), then `ant bin` → BUILD SUCCESSFUL.
- **33 headless provider checks** (`haven.Fonts` driven directly, no GL): all three scopes start `null` (stock
  fast path); a `"default"` push bumps `gen` and cascades into all three while each site keeps **its own** stock
  size (12 / 10) and its stock colour/aa; the per-stock font cache is stable and distinct stocks get distinct
  fonts with distinct sizes; a handle that *does* carry `size`/`color`/`aa` wins; a per-scope push refines only
  itself (`"tooltip"`/`"menu"` stay on the cascade, `"window.title"` untouched); last-wins across two owners;
  `reset` falls back to the owner beneath and is `false` when unset; `removeOwner` returns every scope to `null`.
  **33 ok, 0 failed.**
- **24 headless site-twin checks** — unlike the earlier F3 slices these classes *do* initialize headlessly (only
  the `Tex` upload needs GL), so each twin was driven directly: `ChatUI.fnd()`/`qfnd()`, `Widget.tipfoundry()`,
  `MenuGrid.ttfont()`, `MenuGrid.PagButton.keyfont()` and `FlowerMenu.ptfont()` all return the **identical stock
  object** with nothing installed and again after `removeOwner`; a `"chat"` push swaps family while keeping the
  stock size/aa/colour **and the `ChatParser` subclass**; a `"tooltip"` push at size 14 lands as FAMILY+SIZE with
  `IMAGESRC` inherited and reaches `MenuGrid.ttfont()` too; a `"menu"` push swaps both menu twins; each scope
  leaves the other two stock. **24 ok, 0 failed.**
- **8 headless render checks** — the markup guard for the FAMILY-vs-FONT decision: through a `"tooltip"`
  override, `$b{…}` still renders wider than plain (with a proportional family — a monospaced one has equal bold
  advances, which is what a first version of this check tripped over), `$col[…]{…}` renders, `MenuGrid`'s real
  `"… [$b{$col[255,128,0]{Q}}]"` recipe renders, size 20 renders visibly bigger than the stock 10, a chat line
  containing a URL renders through the rebuilt `ChatParser`, and after teardown the stock tip render is the exact
  stock size again. **8 ok, 0 failed.**
- **6 headless tooltip-engine checks** (added with the widening): a three-`Tip` info list composes at 85×50 stock,
  the **same cached `Tip` objects** re-compose at 141×89 under a size-20 override, `shorttip` (the name-only
  variant) widens too, `Name.str.text` survives the in-place re-render (`ItemSpec`/`WoundWnd`/`FightWnd` read it),
  teardown restores the **exact** stock composition (85×50), and a `"default"` push cascades into the engine.
  **6 ok, 0 failed.**
- **8 headless published-code checks** (the second widening): a `Tip` written exactly the way `ui/tt/q/qbuff`
  writes it — `Text.render` inside `layout`, no routing at all — composes 98×54 stock and **191×114 under a
  `"tooltip"` override**; the same for a rich published row; the scope stack is popped after `longtip`; the same
  `Text.render("Quality:")` call **outside** a composition is *not* restyled; teardown restores the exact stock
  composition; a `"default"` override still reaches it through the cascade; `RichText.render` now follows
  `"default"`; and `RichText.foundry("tooltip")` is the identical stock object when nothing is installed.
  **8 ok, 0 failed.**
- **5 headless private-foundry checks** (the third pass): a published-style tip holding its own plain *and* rich
  foundries composes 100×54 stock and **191×114** under an override; the very same foundry is **untouched outside
  a composition** (identical `strsize`/`render`); repeated composition is stable (no recursion — the provider's
  product is marked); teardown restores the exact stock composition; and a **size-less** override keeps the row
  heights within a pixel (the point size is preserved; ascent/descent are per-family metrics, so a family swap can
  still shift a row by 1 px — worth knowing, not a bug). **5 ok, 0 failed.**
- **2 headless constructor-render checks** (the fourth pass): a tip that renders in its constructor follows the
  override once its info list is **rebuilt**, and teardown + rebuild restores stock. **2 ok, 0 failed.**
- **Resource archaeology** (the method, worth keeping): the cache files carry the resource **name** in a plaintext
  header and the `.res` payload keeps the published **source**, so a published class can be found and read without a
  decompiler — that is how `Gilding:` was pinned to `ISlots.ch`. A temporary `ItemInfo.longtip` diagnostic
  (printing each `Tip` class, its resource, and which `Text`/`Tex`/`Foundry` fields it caches) was written for the
  same purpose and **removed before commit**.
- **3 headless no-Raw checks** (the regression above): `buildinfo` with a null `Raw`, a null argument array, and
  `Raw.nil` all return an empty list instead of throwing. **3 ok, 0 failed.**
- **Local-copy takeover, verified offline against the real cached resource** (`ui/tt/slots-alt` v4) (`-Dhaven.resurl=http://brodgar.io/res/`
  + a cache-backed pool, so the same class-loading path the client uses): the factory resolves to
  `haven.res.ui.tt.slots_alt.Fac` loaded by the **AppClassLoader**, `ISlots` carries
  `@FromResource(ui/tt/slots-alt v4)`
  and has our `ch()` — i.e. the local copy wins — and exercising it gives heading `(38,14)` stock, `(38,14)`
  **outside** a composition with an override installed, `(71,29)` **inside** one.
  `haven.Resource find-updates` reports nothing, so v4 is current.
- **LuaJ parse** of all 7 addons → ok.
- **In-game (the DoD):** `:hello tip` → hover an **inventory item**: the name, the `Quality:` row and its number,
  `Contents:` and the litres, `Wear`, `Armor class`, `Gilding`, the `+5` attribute rows and the italic
  `Not further gildable` are **all** in the new font — including the `Gilding:` heading, via the local copy of
  `ui/tt/slots`; then a buff, a vitals bar, a craft input, a
  character-sheet row, an action icon, an empty equipment slot, a Skills entry, a HUD button.
  `:hello menu` → right-click something and the **petal captions** are in the new font
  (and a menu already open re-sizes its petals around their centres); `:hello tip` → hover any button /
  inventory item / action-menu icon, keep hovering while toggling and the tooltip changes under the cursor;
  `:hello chat` → the chat **messages**, the **channel tabs** and the **typed line** change, the log re-flows,
  URLs stay clickable. Each is independent of `title`/`button`/`entry`/`label`/`heading`; `:hello font` alone
  restyles all three via the cascade; `:reload` or a second toggle restores stock.

## Harness (`hello` v0.53.1)

Three new sub-commands, one per scope, each a toggle whose state is reset at `OnLoad` (a reload tore the override
down): **`:hello menu`** (mono **14** — bigger than the stock sans 12, which a petal can afford since it sizes
itself around its caption), **`:hello tip`** (mono **13** vs the stock sans 10 — a tooltip also sizes its own
box), **`:hello chat`** (mono **13** vs the stock 12). Nine font scopes are now togglable independently in one
login (`font`, `title`, `button`, `entry`, `label`, `heading`, `menu`, `tip`, `chat`). After the widening,
`:hello tip`'s log line points at an **inventory item** as the quickest thing to hover.

## Gotchas worth remembering

- **A monospaced font hides `$b`.** Bold and plain have the same advance in `Monospaced`, so a width-based
  bold-still-works assertion fails with a mono override even though the markup applied. Use a proportional family
  in such a check.
- **`RichText.Foundry.derive` drops a `Parser` subclass.** Anything that customizes parsing (chat's URL
  linkifier) must be **rebuilt**, not derived — that is what `Fonts.style` is for.
- **`TextAttribute.FONT` beats `FAMILY`/`SIZE`/`WEIGHT`.** Setting `FONT` on a markup-carrying foundry silently
  disables `$b`/`$i`/`$size`.
- **A petal is centre-positioned.** Re-sizing a widget whose position was computed from its centre must restore
  the centre, or the layout shifts.
- **Some client text is rendered by code that does not ship with the client.** `ItemInfo` tooltips are composed
  partly by **published code** — Java classes inside the `.res` files (`ui/tt/q/qbuff`, `ui/tt/wear`,
  `ui/tt/attrmod`, …) — which render through the generic `Text.render`/`RichText.render` statics. You cannot route
  what you cannot edit, so give the *composer* a way to say what the context is: a **dynamic scope**
  (`Fonts.enter`/`exit`/`scope()`) that the generic statics consult. `grep`ping the source out of a `.res` file
  (it is stored as preprocessed text next to the compiled class) is the fastest way to confirm what such code
  renders with. And when even the statics are not enough — the code holds its *own* foundry — resolve **at the
  foundry**, still gated on the same declared context: `Text.Foundry.resolved()` is a no-op unless a composition
  scope is active, so the blast radius stays exactly the region the composer declared. **And when the text is
  rendered in a constructor**, no render-time context can help — the cached object itself must be rebuilt (drop the
  `ItemInfo` list on a `gen` move; `buildinfo` runs inside the scope). Render-time, foundry-time, build-time: a
  cached render must be invalidated at whatever layer created it. A **`static final Text` rasterised at class-load**
  is unreachable from inside the font system (`ISlots.ch` = `"Gilding:"`; the JDK forbids replacing a `static
  final`) — note the contrast *inside that one class*: a `static` **foundry** used at layout time is reachable, a
  `static` **Text** is not, so the question for a restylable surface is not "is it text?" but **"when was it
  rasterised?"**. When the answer is "too early", stop working around it: `haven.Resource get-code <res>` +
  `@FromResource` adopts that resource's code into the tree and the one-line fix becomes possible — at the price of
  a version pin.
- **A silent failure needs a diagnostic that cannot itself be silenced.** Two of this slice's dead ends were
  invisible: `Warning.warn` never reaches the console once an `ErrorHandler` is installed, and one branch of the
  resource class loader swallows its exception entirely. Printing the decision to `System.out` — crude, temporary,
  filtered to one class — resolved in a single hover what three rounds of reasoning had not. When a mechanism
  "should work" but doesn't, instrument the *decision point*, not the outcome.
- **An invalidation hook must not force work that had not happened yet.** Dropping a cache to `null` reads as
  "rebuild me", but `null` and *empty* meant different things here: an item with no `tt` yet keeps an **empty**
  info list precisely so nothing tries to build one. Forcing the rebuild NPE'd on the UI thread. Invalidate only
  when the *source* of the cached value exists, and make the builder tolerate the empty case anyway.
- **"The wiring is right and nothing changed" happens twice in a row for the same reason.** F3c learned to grep for
  the *surface*, not the class the spec names; F3d repeated the mistake by trusting the spec's "tooltip foundry"
  hint. The tell was available before the in-game pass: `Text.render`/`RichText.render` **static** callers are
  where a client hides its uncategorised text, and counting them (`longtip(`/`shorttip(` = 17 sites) would have
  pointed at `ItemInfo` immediately. For any future scope: **count the call sites of the composer, not of the
  widget.**
