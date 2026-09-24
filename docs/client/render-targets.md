# Render targets: the scene's buffers, extra outputs and screen passes

What a map-view frame draws into, how a program writes a second buffer beside the colour, and how a pass over
the whole screen reads one back. `Outlines` + `RenderedNormals` is the engine's own worked example of all three;
the backend under them is [render-gl.md](render-gl.md).

## The targets a `PView` draws into

| What | Where |
|---|---|
| The frame's size and sample count | `PView.conf` preps `new FrameConfig(rsz)`, `rsz = PView.rendersz()` = the widget size × `GSettings.rscale`. **`samples` is always `1`**: the two-argument constructor is the only way to more, and nothing in a `PView` calls it |
| Colour and depth | the `id_fb` op in `PView.basic()`: a `FrameFormat` at `fb.sz`/`fb.samples` makes `PView.fragcol` (`UNORM8` RGBA, `FLOAT16` under a tonemap) and `PView.depth`, re-made when the format stops `matching`, prepped as `FragColor` + `DepthBuffer` |
| The blend every scene fragment gets | the `id_misc` op: `FragColor.blend(ADD, SRC_ALPHA, INV_SRC_ALPHA / MAX for alpha)`, `Depthtest(LE)`, `Facecull`. A node that draws a translucent colour into the scene inherits it |
| Per-frame clears | `PView.draw`: `clear(fragcol, clearcolor())`, `clear(depth 1.0)`, then `ctx.prerender(out)` — every `RenderContext.Global` clears what it owns there |
| A state installed in every scene program | `RenderContext.basic(id, op)` → `PView.basic(id, op)`: an op on the root of the tree, so every slot under it re-derives. Keyed by any object; `RenderedNormals.Canon` uses its own class |
| A per-frame hook without a node | `RenderContext.add(Global)` / `put(Global)`, reference counted; `prerender`/`postrender` run around the tree's draw, and `put` disposes a `Disposable` global on its last reference |

## A second output: `FragData`

| What | Where |
|---|---|
| Declaring one | `new FragData(Type, "infix", Function<Pipe, Object> value, Slot... deps)`: a shader global emitted as `out`, and a value function the backend asks for the target, re-asked whenever one of `deps` changes |
| What the value may be | a `Texture.Image` (an attachment), `FragColor.defcolor`, a `FragTarget` (an image plus a `BlendMode`), or **`null`**, which `FboState.forfvals` turns into `GL_NONE`: the program still writes the output and GL drops it |
| The primary output | `FragColor.fragcol` is `.primary()` and gets location 0; `FragData.defid` numbers the rest after it. Two primaries throw |
| Blending, per output | `FboState`'s constructor: each output whose value is a `FragTarget` with a blend is blended, the rest are not (`blendbufs`, applied with `glEnablei`/`glDisablei`). All blended outputs must share **one** `BlendMode`, or it throws `NotImplemented` |
| Emitting it only when wanted | the write lives in a `ValBlock.Value`: `mainvals.ext(key, supplier)` makes one shared value per program, `mod(fn, order)` rewrites its expression, and only a `force()`d value (or one a forced value depends on) is constructed. The state whose macro forces it is what puts the output in a program |
| Masked depth writes nothing | `RenderedNormals.fragnorm`'s value is `null` whenever `States.maskdepth` is in the pipe: a fragment that writes no depth leaves the buffer as the solid geometry behind it wrote it |

## The pattern: `RenderedNormals` and `Outlines`

| Step | Where |
|---|---|
| The state | `RenderedNormals`: a SYS `State` holding the image; `shader()` forces the value that assigns `fragnorm` |
| The owner of the texture | `RenderedNormals.Canon`, a `Pipe.Op` + `RenderContext.Global`: `apply` re-makes the `Texture2D` when `FrameConfig.sz` changes and preps the state; `prerender` clears it |
| Installing it | `RenderedNormals.get(slot.state())` in a node's `added`: finds the context's `Canon` by `ctx.basic(Canon.class)`, installs it on the first reference; `put` in `removed` uninstalls it with the last |
| The screen pass | `Outlines.added`: `slot.add(new Rendered.ScreenQuad(false), p -> …)`. The lambda reads the images out of `p` and preps a `RUtils.AdHoc` subclass carrying the samplers, which `Uniform`s over `RUtils.adhoc` read |
| What the pass must switch off | `p.put(RenderedNormals.slot, null)` and `p.put(DepthBuffer.slot, null)`: a pass that inherits the state of the texture it samples would also *write* it. Any other output installed in the scene the same way needs the same `put` |
| Drawing into a target of its own | `p.prep(new FragColor<>(image))` and `p.put(FragColor.blend, null)` in the lambda: the pass renders into that texture, unblended, at the viewport the root set |
| Loops and branches in the pass | a `Function.Def` filled in an initialiser and `call()`ed from `FragColor.fragcol(fctx).mod`; `Outlines.msfac` is the loop precedent, `Tex2D.clip` the `Discard` one ([render-gl.md](render-gl.md)) |

| `ScreenQuad` fact | |
|---|---|
| Its own state | `States.maskdepth`, `Depthtest.none`, no face culling, an `Ortho2D` over `[-1, 1]`. `ScreenQuad(false)` samples a target the scene drew at the same texel as the fragment it covers |
| Its texture coordinate | `Tex2D.rtexcoord`: pixel centres, `(x + ½) / w`. A neighbour is `FrameConfig.u_pixelpitch` away, `NEAREST` filtering reads it exactly |
| One node, many slots | `ScreenQuad.added` only sets `ostate`, so a node may stand in several slots |

## The draw order

`Rendered.order` (a GEOM slot) sorts the whole draw list by `Order.mainorder()`, then by its own comparator.

| Order | Who |
|---|---|
| `Rendered.first` / `last` | `Integer.MIN_VALUE` / `MAX_VALUE` |
| `0` | `Rendered.deflt`: the solid scene |
| `100` | `MapMesh.clickpost` |
| `990`, `1001`, `1010` | `MapMesh.premap`, `MapMesh.groundmod`'s material, `MapMesh.postmap` |
| `4500` | `Rendered.eeyesort` (a material's `"earlyeye"`) |
| `5000` | `Rendered.postfx`: `Outlines` |
| `5500` | `Rendered.postpfx` (a material's `"pfx"`) |
| `6000`, `6001` | `WaterTile.surfmat`, `foammat` |
| `10000` | `Rendered.eyesort` (a material's `"eye"`): translucent geometry, sorted by depth |

A pass that reads what the solid scene wrote goes after `0`; one that translucency in front must veil goes
before `6000`.

## Gotchas

**A second non-primary output made the program cache miss every run** (fork: `// addon:` in
`FragData.defid`). `defid` collected a program's outputs into a `HashSet` and numbered the non-primary ones
in its iteration order: identity-hash order, different in every JVM. With one extra output (`fragnorm`) that
could not matter; with two, their locations swapped between runs, `GLProgram.cachekey` (which includes every
`glBindFragDataLocation`) changed, and `ProgramCache` relinked everything. It is a `LinkedHashSet` now, so the
numbering follows `vardefs` — the order the macros used them in, which the sources fix. The same care as the
attribute locations on [render-gl.md](render-gl.md).

**An output installed in every program costs every program a relink once.** `RenderContext.basic` changes the
state of the root, every slot re-derives, and every program gains the output: a new source, a new key, a
link. Install such a state once, for the life of the view, rather than on demand — the first use of an
output installed on demand recompiles the whole scene in the middle of play.

**A target clear is per frame, a pass is per frame.** `prerender` runs whether or not anything reads the
buffer; a `ScreenQuad` in the tree draws every frame. What should cost nothing while idle keeps its quads out
of the tree (removing the slot) rather than drawing a pass that discards everything.

**A `Texture2D.Sampler2D` defaults to `LINEAR` magnification and `REPEAT` wrapping.** A pass reading exact
neighbours sets `NEAREST` on both filters and `CLAMP` on both axes, or the edge of the screen samples the
opposite edge.
