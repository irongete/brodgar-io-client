# F4 — The world font scopes (`"world.nick"` floating kin names + `"world.speech"` speech bubbles)

> **Status:** ✅ Implemented; clean compile (`rm -rf build/classes && ant hafen-client` → BUILD SUCCESSFUL) +
> `ant bin`, **45 headless checks** in nine groups (provider resolution for both scopes, both routed site twins,
> the `rendertext` composition growing/reverting with an override, and the composition-scope isolation) + a
> **local-copy takeover check** against the real `ui/obj/buddy` v4 on `brodgar.io` + LuaJ parse of all 8 addon
> files. `hello` **v0.54.0** (`:hello speech` / `:hello nick`). **In-game verified ✅. Committed `acc1bb3c`.** *(Java engine change ⇒
> `ant` rebuild + full client restart.)*
> **Design:** [specs/addons/21-fonts.md](../../../specs/addons/21-fonts.md) §F4, decision
> **[D-043](../../../specs/addons/decisions.md)**. Builds on [f1-fonts](f1-fonts.md) (provider + `gen` +
> `"default"` cascade) and reuses [f3d](f3d-menu-tooltip-chat-fonts.md)'s **resource-code adoption** technique.

F4 routes the **last two scopes of D-043's enum** — the text the client draws **in the world**, over the
characters themselves:

| Scope | Surface | Where it lives |
|---|---|---|
| `"world.speech"` | the speech bubble over a talking character (area chat) | `haven.Speaking` — **engine** code |
| `"world.nick"` | the floating kin name over a character on your kin list | `ui/obj/buddy` — **published** resource code |

With this slice **every scope in `hafen.font.scopes()` is effective**: the F-series has no inert scope left, and
only F5 (per-instance override) remains in the queue.

## The API (unchanged — this slice makes two scope names *effective*)

```lua
local h = hafen.font.load("mono", { size = 16 })
hafen.font.setFont("world.speech", h)    -- speech bubbles only
hafen.font.setFont("world.nick", h)      -- floating kin names only
hafen.font.reset("world.speech")         -- drop it (automatic on :reload/disable)
-- cascade: with neither scope set, setFont("default", h) restyles both
```

## Surface 1 — `"world.speech"` (an ordinary engine site)

[`Speaking`](../../../src/haven/Speaking.java) is a `GAttrib` + `PView.Render2D`: it renders the bubble text
**once** (constructor / `update`) into a cached `Text` and blits it every frame, sizing the emote frame from
`text.sz()`.

Stock rendered through `Text.render(text, Color.BLACK)` — the generic static F1 bound to the `"default"` scope —
so bubbles already followed a `"default"` override, but could not be refined on their own. Three tagged edits:

1. a private **`font()`** = `Fonts.foundry("world.speech", Text.std)` — the site's stock is `Text.std`, exactly
   what `Text.render` would have used, so with no override the fast path returns `Text.std` and the rendering is
   byte-identical to stock;
2. the source **`String str` is kept** (stock dropped it after rendering — the same "recorded recipe" pattern as
   F3b's button captions), and
3. a per-instance **`fontgen`** + a `checkfont()` at the top of `draw(GOut, Coord)`: when `Fonts.gen()` moved, the
   line is re-rendered through the new foundry. **Lazily** — only bubbles that are actually being drawn pay for it.

The bubble **frame** is measured from `text.sz()` on every frame anyway, so it grows and shrinks with the font by
itself; `"world.speech"` is the one scope where a much larger size is completely safe.

One deliberate behaviour change: the bubble no longer follows the **dynamic composition scope**
([F3d](f3d-menu-tooltip-chat-fonts.md)'s `Fonts.enter`) — it asks for its own scope by name. That is correct: a
bubble is not tooltip text, and a headless check pins it (group 8).

## Surface 2 — `"world.nick"` (published resource code — adopted)

The floating kin name has **no call site in the fork**. `haven.KinInfo` is gone (the only reference left in
`Partyview` is commented out, and `OCache.OD_BUDDY` is marked `-- Removed`): the label is drawn by **published
code**, Java classes that ship *inside* the `ui/obj/buddy` resource —

- `Buddy` (`GAttrib`, the `>objdelta` entry point) resolves the kin-list entry and adds
  `InfoPart.rendertext(name, groupcolour)` to a `CompImage`;
- `Info` (`PView.Render2D`) composes all the parts of one gob into a single `Tex` and blits it centred over the
  character, re-composing only when a part calls `dirty()`;
- `InfoPart` (the interface) owns the shared **`static final Text.Foundry fnd`** (`sans` bold 12, aa) and the
  `rendertext` helper that renders + blur-masks the name.

So the answer is F3d's fifth-pass technique, now the fork's second use of it: **adopt the resource's own source**
with the engine's `doc/resource-code` mechanism —

```bash
java -cp bin/hafen.jar haven.Resource get-code ui/obj/buddy
```

which wrote `src/haven/res/ui/obj/buddy/{Buddy,Info,InfoPart}.java`, each annotated
`@haven.FromResource(name = "ui/obj/buddy", version = 4)`. `Resource.ResClassLoader` prefers a local copy over the
resource's own bytecode **only when name+version match** (`override = true` deliberately not used — stale local
code against a changed resource is a worse failure than one stock font). The copy fetched from the default
resource URL was **diffed byte-for-byte against `http://brodgar.io/res/`** (identical, v4 both sides) and
`haven.Resource find-updates src` reports nothing.

Then two small edits inside the local copy:

- **`InfoPart.fnd()`** — a new `static` twin returning `Fonts.foundry("world.nick", fnd)`, and `rendertext` renders
  through it. The stock `fnd` field is left in place for any other caller. Resolution happens per *render*, i.e.
  per re-compose (not per frame), and takes the provider's `active` fast path while no override exists. Every
  `InfoPart` implementation that draws its text through `rendertext` — including parts contributed by *other*
  resources — follows the scope for free.
- **`Info.draw`** — a `fontgen` field compared against `Fonts.gen()` at the top of `draw`; on a move it calls the
  class's own `dirty()`, which disposes the composed `Tex` and lets the existing `CompImage` pass rebuild it. The
  re-composition re-measures the label, so it stays centred over the character at the new size, and the kin-group
  **colour** is untouched (it is passed per render by `Buddy`).
- `Buddy.java` is an **unmodified** copy, kept so the adopted class set matches the resource one-to-one.

**Price:** a version pin on `ui/obj/buddy` v4 — if the server ships a newer one, the client logs
`local copy of … is overridden by code from …` and the kin name simply returns to stock (nothing breaks); refresh
with the same `get-code` command. Same trade-off, and same escape hatch, as `ui/tt/slots-alt`.

## Files touched

| File | Change |
|---|---|
| [`src/haven/Speaking.java`](../../../src/haven/Speaking.java) | `font()` + recorded `str` + `fontgen`/`checkfont()` in `draw` (`// addon:`) |
| `src/haven/res/ui/obj/buddy/InfoPart.java` | **new** (adopted) — `fnd()` routed to `"world.nick"`, `rendertext` uses it |
| `src/haven/res/ui/obj/buddy/Info.java` | **new** (adopted) — `fontgen` → `dirty()` re-compose in `draw` |
| `src/haven/res/ui/obj/buddy/Buddy.java` | **new** (adopted, unmodified) |
| [`addons/hello/main.lua`](../../../addons/hello/main.lua) | `:hello speech` / `:hello nick` + two flags + `OnLoad` resets + help text |
| `addons/hello/manifest.json` | **v0.54.0** |

No new class in `io.brodgar` and **no change to `haven.Fonts`** — the provider primitives from F1/F3d were enough.
`Fonts.SCOPES` already listed both names (declared in full from F1), so `hafen.font.scopes()` is unchanged.

## Verification

- **Clean compile** (`rm -rf build/classes && ant hafen-client` → BUILD SUCCESSFUL) + `ant bin`.
- **45 headless checks** (`haven.Fonts` + the two routed sites driven directly, no GL — unlike the earlier F3
  slices these classes *do* initialize headlessly; `Speaking`'s resource statics are lazy `Indir`s):
  1. both scope names are in the enum;
  2. baseline — `foundry(scope, stock) == stock` for both, `style()` null;
  3. the `"default"` cascade reaches both scopes while keeping each site's stock **size**, `aa` and `defcol`, and
     the per-stock foundry cache returns the same instance;
  4. a per-scope override refines over the cascade, keeps the site's stock size unless it carries one, and leaves
     the *other* world scope and `window.title` alone;
  5. last-wins across two owners (and a re-applied override raises back to the top);
  6. `reset` → the owner beneath resurfaces, `reset` for an unset owner is `false`, `removeOwner` → stock identity;
  7. the **site twins**: `InfoPart.fnd()` is the stock foundry when unset, follows an override (and its size),
     returns to stock after teardown; `InfoPart.rendertext("Kinsman", …)` composes a **taller image** under a
     bigger override, reverts exactly after teardown, and follows the `"default"` cascade too; `Speaking.font()`
     is `Text.std` when unset, follows `"world.speech"`, renders a taller line, returns to `Text.std`;
  8. **composition-scope isolation** — with a `"tooltip"` override installed *and* `Fonts.enter("tooltip")`
     active, both world sites still resolve to stock (world text is not tooltip text);
  9. all three adopted classes carry `@FromResource(ui/obj/buddy, 4)` with `override = false`, and `Info` has the
     `fontgen` field.

  **45 ok, 0 failed.**
- **Local-copy takeover, verified offline against the real resource** (`-Dhaven.resurl=http://brodgar.io/res/`,
  the client's own class-loading path): `ui/obj/buddy` **v4**, the `>objdelta` entry point resolves to
  `haven.res.ui.obj.buddy.Buddy` on the **AppClassLoader** carrying `@FromResource(ui/obj/buddy v4)`, the
  package's `InfoPart` has our `fnd()` and `Info` our `fontgen`, and **no** "is overridden by code from" warning
  is logged → the local copy wins. `haven.Resource find-updates src` reports nothing (v4 is current).
- **LuaJ parse** of all 8 addon files → ok.
- **In-game (the DoD) — verified ✅:** `:hello speech` → say anything in area chat and the bubble over your own head is in the
  new font, with the frame grown around it; toggle again → stock. `:hello nick` → **needs a kin visible on
  screen** (someone on your buddy list): their floating name changes font and keeps its group colour. Then
  `:hello font` alone must restyle **both** through the cascade, and `:reload hello` must revert everything.

## Gotchas worth keeping

- **`KinInfo` is a red herring.** The class the old client used is gone from `src/`; the surface is published
  resource code. Grepping the fork for a render site finds nothing — the right question was *"who draws it, and is
  that code even ours?"*. `OCache.OD_BUDDY` being commented `-- Removed` is the tell.
- **A shared static foundry inside published code is reachable** (route it in an adopted copy) — the F3d boundary
  stands: a static `Text.Foundry` is fine, a static `Text` rasterised at class-load is not.
- **`Info.dirty()` was already the invalidation hook** the class needed; the slice only had to *call* it on a
  generation move. When adopting published code, look for the invalidation the author already wrote before adding
  one.
- **Ask for your own scope, not the dynamic one.** `Speaking` deliberately does not resolve
  `Fonts.scope()`/`dynamic()`: named-scope resolution is what keeps a bubble out of the tooltip scope.
