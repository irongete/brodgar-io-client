# 165 — Plan

## Approach

The pattern is upstream's: `Outlines.added` takes `RenderedNormals.Canon`, so every scene program writes a second
`FragData`, which a `Rendered.ScreenQuad` samples at `postfx`. Here it is one more output, the mask, and two passes.

**`io.brodgar.addon.OutlineMask`**, a copy of `RenderedNormals`:
- **The output.** A SYS `State` with a VEC4 `FragData "fragoutl"`, deps `(slot, States.maskdepth.slot)`. Its value
  is the mask image, or `null` (`GL_NONE`) under `maskdepth`.
- **`value(FragmentContext)`.** It answers the shared `ValBlock.Value`: `mainvals.ext`, root `vec4(0)`, `cons2`
  assigning it. The state's macro forces it, so an unmarked object writes `0` and an occluder in front clears
  the mask.
- **`Canon`.** Two `Texture2D`s at `FrameConfig.sz`, both re-allocated on resize: the mask (`VectorFormat(4,
  UNORM8)`, cleared in `prerender`) and the row target (`VectorFormat(2, UNORM8)`). `get`/`put` work as on
  `RenderedNormals`.
- **The texel.** `rgb` is the colour. The alpha byte is `32·(width−1) + q`, with `q = max(1, round(a·31/255))`, and
  "marked" is `alpha > 0`. `a = 0` contributes no state, and the read still answers it.

**`io.brodgar.addon.GobOutline`**, a copy of `GobTint`:
- **The attrib.** A `GAttrib implements Gob.SetupMod` whose `gobstate()` is a nested `Paint`: a DRAW slot, a VEC4
  `u_col`, the macro `OutlineMask.value(prog.fctx).mod(in -> u_col.ref(), 0)`. Cached per `(colour, width)`.
- **Statics.** `on`, `value`, `width`, `apply(g, owner, c, w)` and `revert(g, a)`.
- **Parsing.** `widthArg(a, i, verb)` is `Args.written`, then `(int)Args.integer(v, verb, "width", "design pixels",
  1, 8)`, default `2`. `nilWithWidth(verb)` refuses `(nil, w)`.
- **Liveness.** A weak set of every instance. One is live while not disposed (`dispose()` flags it) and
  `!gob.removed`. `widest()` answers the widest live width, or `0`. Read a copy, and take no gob monitor under its lock.

**`io.brodgar.addon.OutlineRing`**, a `RenderTree.Node` and its own `TickList.TickNode`/`Ticking`:
- **`added`/`removed`.** `added` takes `OutlineMask.get(slot.state())` for good, and `removed` puts it back.
- **`autotick`.** It watches `GobOutline.widest()` and `UI.scale(1.0)`. On a change it removes both quad slots
  (catching `RenderTree.SlotRemoved`), then re-adds them while `widest > 0`. It holds no monitor across a tree call.
- **The quads.** Two `ScreenQuad(false)`s at `Rendered.Order.Default` `5600` and `5601`, each with a state lambda
  as in `Outlines.added`:
  - `OutlineMask.slot`, `RenderedNormals.slot` and `DepthBuffer.slot` are put to `null`.
  - An `RUtils.AdHoc` carries the samplers (`Filter.NEAREST`, `Wrapping.CLAMP`), `pf = UI.scale(1.0) ×
    p.get(GSettings.slot).rscale.val` and `W = ceil(widest·pf)`.
  - Taps are at `FrameConfig.u_pixelpitch` offsets, and loops are `Function.Def`s, like `Outlines.msfac`.
- **Pass A** renders into the row target: `new FragColor<>(img)`, with `FragColor.blend` put to `null`. Over
  `dx ∈ −W..W` it keeps the marked texel with the least `dx² − (w·pf)²`, and writes `r = (dx+128)/255` and `g = 1`
  (`g = 0` for none).
- **Pass B** renders into the scene colour, under `PView`'s `id_misc` blend:
  - It discards on a marked texel.
  - Over `dy ∈ −W..W` it reads the row target, fetches the seed from the mask, and keeps the least `dx² + dy² −
    (w·pf)²`.
  - It writes `vec4(seed.rgb, clamp(w·pf + ½ − d, 0, 1)·q/31)`, and discards when `d > w·pf + ½`.
  - The reach test is exact at any mix of widths.
- **The order.** After `postfx` (5000) and `postpfx` (5500), before water (6000) and `eyesort` (10000), so
  translucency in front veils the ring.

**Core seams (`// addon:`).**
- `MapView`'s constructor adds `basic.add(new io.brodgar.addon.OutlineRing());` after `Outlines`.
- `FragData.defid` changes `HashSet` to `LinkedHashSet`. With two non-primary outputs, identity-hash order swaps
  their numbers between runs, so `GLProgram.cachekey` changes and `ProgramCache` misses.

**`gob:outline` (165.1)**, in `LuaGob.methods`:
- `Args.only(a, 2, …)` first. The bare read goes through `gob(self, "outline")`: `varargsOf(color(c),
  valueOf(w))`, or `NIL`.
- A write when `AddonManager.gobCopies(h.id)` is empty returns `self` and records nothing. Otherwise it applies to
  every copy and calls `GobIntent.outline(h.id, owner, c, w)`.
- `GobIntent.Record` gains `outline` and `outlineWidth`: counted by `empty()`, shaped like `tint(…)`, and
  re-applied by `applyTo`.
- `UiApi.teardownGobScales` reverts it. `AddonManager.gobSnapshot` writes `outline = {color, width}`.

**`entity:outline` (165.2).**
- `LuaWorldEntity` gains `outline`/`outlineWidth`. `VirtualApi.entityHandle`'s shared verb runs `recv`,
  `Args.only`, the same parsing, then `setEntityOutline`: it records the fields and applies `GobOutline` to `e.gob`,
  entity monitor then gob (`setEntityFacing`'s order). `updstate` takes it on the next `ctick`: `MapView` ticks
  `clientGobs`.
- The five sites copying the look onto a fresh `GhostGob` (`gob.alpha = …`) apply it too: the ghost, object, sprite
  and panel creates, and `rehome`.
- `:info()` writes `outline`. `patchHandle`'s `x` sets `outline` to the refusal.
- An entity at `alpha < 1` gets `States.maskdepth` from `GhostGob.obstate`, and a `"screen"` one is a
  `PView.Render2D` blit. Neither writes the mask.

**API `1.2` (165.1).** `v4` shipped `1.1`, and a release that adds a verb moves the edition (`manifest.md`; 163
did the same). `ApiVersion.CURRENT` becomes `(1, 2)`. `manifest.md` gains *What needs `1.2`*, and its sentence, its
out-of-date examples and every `"api_version": "1.1"` example in `docs/` say `1.2`. `docverbs.py` holds the number.

## Files to create/modify

- **Create** `src/io/brodgar/addon/OutlineMask.java`, `GobOutline.java` and `OutlineRing.java`. Edit `MapView.java`,
  `render/sl/FragData.java`, `LuaGob.java`, `GobIntent.java`, `UiApi.java`, `AddonManager.java` and
  `ApiVersion.java` (165.1).
- Edit `LuaWorldEntity.java` and `VirtualApi.java` (165.2).
- Docs as `spec.md` names, plus `manifest.md` and the examples.
- **Create** `docs/client/render-targets.md`: `PView`'s targets, a `FragData`'s value/`null`/`GL_NONE`,
  `FboState.blendbufs`, the `RenderedNormals` pattern, `ValBlock` `ext`/`mod`/`force`, `ScreenQuad` with `AdHoc`, the
  order table, `samples` always `1` (`new FrameConfig(rsz)`), and the `defid` gotcha. Upstream only.
- **Create** the suites `addons/165-gob-outline.1/` and `.2/`.

## Risks & gotchas

- **Fixed cost.** 4 bytes per solid fragment, a clear per frame, and one relink of every program at first launch.
- **While one is live.** About `2·(2W+1)` taps a pixel: 50 at width 8 and UI scale 1.5. Liveness is client-wide,
  so a dormant session's ring runs the drawn view's passes.
- **Batching.** `Paint` is not instanced: an outlined object leaves its batch, as a tinted one does.
- **Removal.** `OCache.remove` never calls `Gob.dispose()`, so `gob.removed` is what ends a game gob's ring.
- **Other passes.** `Clicklist` and `ShadowList` keep GEOM states only, so `Paint` never reaches them. Locked
  composited layers are safe, because `Paint.apply` only `put`s.
- **By eye.** A depth-writing particle carves the ring, and a `SessionView` merged scene inherits the anchor's mask.
- **For the close report.**
  - `gob:tint`, `:scale` and `:visible` skip `Args.only`, and record a `GobIntent` for a gone gob.
  - *What needs `1.1`* omits 164's verbs.

## Discarded alternatives

- **Through walls.** Ruled out: a second `Clicklist`-shaped pass per object, showing players behind palisades.
- **Stencil.** The engine has no stencil state, attachment or GL entry point.
- **Inverted hull.** Its width is in world units, split normals gap, and alpha-cut foliage planes cannot be hulled.
- **A wash.** That is `gob:tint`, and the ask is the edge alone.
- **A mask installed only while an outline is live.** Every slot recompiles at the first hover.
- **A single-pass disc search.** Its cost is the square of the width: about 500 taps a pixel.
- **A second attachment for the width.** A byte per fragment for everyone, while the alpha byte has room.
- **The mark in `RenderedNormals`' alpha.** A core edit of upstream, plus a palette to manage.
- **A `gob:overlay()` kind.** An overlay paints at a point; a silhouette is how the model is drawn: Look.
- **An intent for a gone gob.** The page promises that such a write does nothing.
- **An entity's ring in `GhostGob.obstate`.** It is outside `GobState.equals`, so each change needs a scene re-add,
  and it bypasses the registry that switches the passes on.
