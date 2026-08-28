# The render backend: scene counters and GL submission

> Split out of [world-3d.md](world-3d.md), which flagged these two halves as the natural cut. What the
> `:stats on` HUD reads, and where a frame's draw calls actually happen.

## Scene counters (what the `:stats on` HUD reads)

| What | Where |
|---|---|
| The scene's render objects (**on `PView`, not `MapView`**) | `PView.instancer` (an `InstanceList`) + `PView.back` (the `DrawList`) — `protected`, **null before the first draw** builds the env-bound lists; fork accessors `instancer()`/`drawlist()` (`// addon:`) |
| Scene tree size | `RenderTree.stats` over `nleaves`/`nslots`; fork getters `nleaves()/nslots()` |
| Batching effectiveness | `InstanceList.stats` over `nuinst`+`nbatches`(`ninst`) `ninvalid` `nbypass`; fork getters at. Written on the render side ⇒ a read may be **one frame stale** |
| Draw slots ("draw calls") | `DrawList.stats` is an interface default; the real count is `GLDrawList.btsubsize(root)`. Fork: `DrawList.drawslots()` defaults **`-1`** = "does not count", overridden in `GLDrawList` |
| VRAM pools + shader programs | `GLEnvironment.memstats` over `stats_obj`/`stats_mem` indexed by the **package-private** `MemStats` enum, and `numprogs()`; fork exposes the pool **names** as `String[] mempools()` + `memobjects(i)`/`membytes(i)` at |
| Render-state slots (process-wide) | `State.Slot.numslots()` |

**Gotcha.** Everything above except `State.Slot.numslots()` needs a live scene: `ui.root.findchild(MapView.class)`
is null before the world loads, `instancer`/`back` are null before the first draw, and a non-`GLEnvironment`
backend has no VRAM or program counts at all. Report an **absent** value, never a `0`.

## GL submission: where a frame's draw calls actually happen

| What | Where |
|---|---|
| The 3D scene draw boundary | `PView.draw` → `instancer.commit(out)` then `maindraw(out)` = `back.draw(out)`, the draw-list dispatch; `resolve(g)` + `list2d.draw(g)` follow it. `MapView.maindraw` prepends `drawsmap` → `smap.update(out, slist)` — **the entire shadow render in one call** |
| Batched submission (per frame) | `GLDrawList.draw(Render)` walks the sorted `DrawSlot` list on the **UI/dispatch** thread: `gl.bglCallList(cur.compiled)` per slot = **one draw call each**, and a program bind wherever `cur.prog` changes (the list is sorted by program, so binds ≪ calls means the sort works) |
| Slot **compile** (rare, not per frame) | `GLDrawList.SlotRender.draw(Pipe,Model)`, from the `DrawSlot` ctor — the only place the `Model` is in hand; `glupdate` bakes `GLProgram.apply` + settings into `compiled`. Fork: `DrawSlot.nverts/ntris` are computed **here**, once |
| Immediate submission | `GLRender.draw(Pipe,Model)` — every 2D blit and ephemeral model, `state.apply` then `glDrawArrays`/`glDrawElements`(`Instanced`) |
| Immediate program binds | `Applier.apply2` and `apply(BGL,Applier)` — the two `GLProgram.apply` sites that run per frame |
| Geometry per model | `Model`: `mode` (`Mode`), `n` (vertices, or **indices** when `ind != null`), `ninst`. Triangles = `n/3` (TRIANGLES) or `n-2` (STRIP/FAN), × `ninst`; POINTS/LINES contribute none |

**Gotcha.** The GL calls are *written* at slot-compile time and *replayed* by `BufferBGL` on the render thread —
neither is a per-frame count. Instrument the two **dispatch** seams above (`GLDrawList.draw`, `GLRender.draw`);
the replay loop is far too hot to touch.

## The 2D blit path (what `g.image` actually does)

| What | Where |
|---|---|
| The interface | `Tex`: `sz()` + the one primitive `render(GOut, float[] gc, float[] tc)`; everything else (`render(g,dul,dbr,tul,tbr)`, `crender`) is a `default` that builds `gc`/`tc` and calls it. `gc` = 4 screen corners, `tc` = the same 4 in **texel** coordinates, both interleaved x,y, corner *i* at `[2i], [2i+1]` |
| `GOut.image` → the primitive | `GOut.image(Tex,Coord[,Coord])` → `tex.crender(...)`, which **clips** against `g.ul`/`g.br` by trimming the texel rectangle, then calls `render` |
| The concrete blit | `TexRender.render` divides `tc` by `sz()` and draws one 4-vertex `TRIANGLE_STRIP` of `Ortho2D.pos`+`Tex2D.texc` with `usestate(draw)` — an EPHEMERAL `VertexArray` through `GLRender.draw` |
| **Drawing a texture at another size, without a second texture** | `ScaledTex<T>` — a `Tex` holding an `impl` and a `sz` of its own, delegating every `render` to the impl and passing its own `sz()` as the destination rectangle. So `g.image(new ScaledTex(t, s), c)` covers `s`, resampled by the sampler, and `dispose()` disposes the **impl** — a view, not a resource, and disposing both double-frees. `UI.scale(Tex)` is the constructor everything uses; see [ui-scaling.md](ui-scaling.md) |
| **A render target blits UPSIDE DOWN unless flipped** | `tc` is always counted from the image **top**, which is right for a `TexI` and wrong for a texture the client drew into (see [world-3d.md](world-3d.md)'s v-flip gotcha). The engine's own answer is a flag: `TexRaw(Sampler2D, boolean invert)`, which `Window.gbuf` passes `true`. A hand-rolled `TexRender` over a render target must invert `t` itself (`t → sz().y - t`) before delegating |

## Writing a shader state of your own

| What | Where |
|---|---|
| The template | `BaseColor` is the whole of it: a `State` with a `static final State.Slot<>(Slot.Type.DRAW, …)`, `apply(Pipe)` = `p.put(slot, this)`, a `Uniform` whose value function reads `p.get(slot)`, and one `static final ShaderMacro` that mods `FragColor.fragcol(prog.fctx)` |
| Where the colour is composed | `FragColor.fragcol(fctx).mod(fn, order)` — the orders compose, and `BaseColor` mods at **0**, so a mod at a higher order scales the colour it produced. `FragColor.fragcol` is a `ValBlock.Value`: it is emitted only if something `force()`s it, which the `FragColor` state's own macro does |
| A **loop** in a shader | `haven.render.sl` carries `Array`, `Index` (`Cons.idx`), `For` and `Block.local`, but a `mod` is an expression transformer and cannot hold statements. The vehicle is a `Function.Def` whose `code` block is filled in an instance initialiser and which is `call()`ed from the `mod`; `FragmentContext.construct` → `main.define` walks the `Call` elements and emits the definition. Precedents: `Outlines.msfac`, `Lighting.LightList.construct` |
| A builtin the DSL has **no name for** | `Function.Builtin`'s constructor is public — `new Function.Builtin(Type.FLOAT, new Symbol.Fix("fwidth"), 1)` declares one in a line. `Cons` names about thirty; the screen-derivative family (`fwidth`, `dFdx`, `dFdy`) is not among them |
| An **array** uniform | `new Uniform(new Array(Type.VEC4, n), "name", …, slot)`; `PoseMorph.bo` (`Array(MAT4, nb)`) is the precedent. `UniformApplier.TypeMapping.register` is **public static**, and `UniformApplier.apply` looks an array value up under the **unsized** `new Array(el)` while passing the *sized* type to the mapping — so one registration covers every length. `ProgOb.uniresolve` names the base `UniformID` after the bare array, i.e. element 0, so a single `glUniform4fv(var, n, buf)` uploads `n` of them |
| Naming, across programs | `Symbol.Gen` is unique per **context** and `Symbol.Shared` per **program**, so a `static final` `Uniform` or `Function.Def` shared by every program that uses the state is safe. `Symbol.Fix` throws on a name conflict, which is what makes a hand-declared builtin loud rather than silently shadowed |

**Gotcha — a uniform is baked, not re-read.** `GLDrawList.DrawSlot.getsettings` resolves every one of the
program's uniforms when the slot is compiled, and `getuniform` caches the resulting GL call under a
`SettingKey` of `(program, uniform, the Pipe group each dep came from)` — compared by **identity**
throughout. So mutating a live `State`'s field afterwards propagates nothing at all: the new value reaches
the screen only as a **new state instance pushed through the slot**, which puts it in a new group and so
under a new key.

**Gotcha — `Slot.add(n, state)` sets the child's `cstate`.** Replacing what an `added` put in is therefore
`Slot.cstate`, not `Slot.ostate` — `ostate` is the *other* one and layers a second op over the first, which
merely happens to work when both are `put(slot, …)` of the same slots. Both are no-ops when the op is `==`
the one already there, so a freshly minted op every tick re-pushes forever (the `Location` warning on
[world-3d.md](world-3d.md) is the same trap one level down).
