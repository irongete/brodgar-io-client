# Subsystem: render backend — scene counters & GL submission

> Split out of [world-3d.md](world-3d.md) (044.1), which flagged these two halves as the natural cut. What the
> `:stats on` HUD reads, and where a frame's draw calls actually happen. Line numbers are indicative; the
> **class + method/field name is the stable anchor**. Max 70 lines.

## Scene counters (what the `:stats on` HUD reads)

| What | Where |
|---|---|
| The scene's render objects (**on `PView`, not `MapView`**) | [`PView.instancer`](src/haven/PView.java:47) (an `InstanceList`) + [`PView.back`](src/haven/PView.java:48) (the `DrawList`) — `protected`, **null before the first draw** builds the env-bound lists; fork accessors [`instancer()`/`drawlist()`](src/haven/PView.java:63) (`// addon:`) |
| Scene tree size | [`RenderTree.stats`](src/haven/render/RenderTree.java:921) over `nleaves`/`nslots`; fork getters [`nleaves()/nslots()`](src/haven/render/RenderTree.java:926) |
| Batching effectiveness | [`InstanceList.stats`](src/haven/render/InstanceList.java:889) over `nuinst`+`nbatches`(`ninst`) `ninvalid` `nbypass`; fork getters at [:881](src/haven/render/InstanceList.java:881). Written on the render side ⇒ a read may be **one frame stale** |
| Draw slots ("draw calls") | [`DrawList.stats`](src/haven/render/DrawList.java:34) is an interface default; the real count is [`GLDrawList.btsubsize(root)`](src/haven/render/gl/GLDrawList.java:1089). Fork: `DrawList.drawslots()` defaults **`-1`** = "does not count", overridden in [`GLDrawList`](src/haven/render/gl/GLDrawList.java:1093) |
| VRAM pools + shader programs | [`GLEnvironment.memstats`](src/haven/render/gl/GLEnvironment.java:1032) over `stats_obj`/`stats_mem` indexed by the **package-private** `MemStats` enum, and [`numprogs()`](src/haven/render/gl/GLEnvironment.java:1015); fork exposes the pool **names** as `String[] mempools()` + `memobjects(i)`/`membytes(i)` at [:1018](src/haven/render/gl/GLEnvironment.java:1018) |
| Render-state slots (process-wide) | [`State.Slot.numslots()`](src/haven/render/State.java) |

**Gotcha.** Everything above except `State.Slot.numslots()` needs a live scene: `ui.root.findchild(MapView.class)`
is null before the world loads, `instancer`/`back` are null before the first draw, and a non-`GLEnvironment`
backend has no VRAM or program counts at all. Report an **absent** value, never a `0`.

## GL submission: where a frame's draw calls actually happen

| What | Where |
|---|---|
| The 3D scene draw boundary | [`PView.draw`](src/haven/PView.java:327) → `instancer.commit(out)` then [`maindraw(out)`](src/haven/PView.java:319) = `back.draw(out)`, the draw-list dispatch; `resolve(g)` + `list2d.draw(g)` follow it. [`MapView.maindraw`](src/haven/MapView.java:1642) prepends [`drawsmap`](src/haven/MapView.java:1027) → `smap.update(out, slist)` — **the entire shadow render in one call** |
| Batched submission (per frame) | [`GLDrawList.draw(Render)`](src/haven/render/gl/GLDrawList.java:941) walks the sorted `DrawSlot` list on the **UI/dispatch** thread: `gl.bglCallList(cur.compiled)` per slot = **one draw call each**, and a program bind wherever `cur.prog` changes (the list is sorted by program, so binds ≪ calls means the sort works) |
| Slot **compile** (rare, not per frame) | [`GLDrawList.SlotRender.draw(Pipe,Model)`](src/haven/render/gl/GLDrawList.java:848), from the [`DrawSlot` ctor](src/haven/render/gl/GLDrawList.java:336) — the only place the `Model` is in hand; [`glupdate`](src/haven/render/gl/GLDrawList.java:265) bakes `GLProgram.apply` + settings into `compiled`. Fork: `DrawSlot.nverts/ntris` are computed **here**, once |
| Immediate submission | [`GLRender.draw(Pipe,Model)`](src/haven/render/gl/GLRender.java:173) — every 2D blit and ephemeral model, `state.apply` then `glDrawArrays`/`glDrawElements`(`Instanced`) |
| Immediate program binds | [`Applier.apply2`](src/haven/render/gl/Applier.java:259) and [`apply(BGL,Applier)`](src/haven/render/gl/Applier.java:322) — the two `GLProgram.apply` sites that run per frame |
| Geometry per model | [`Model`](src/haven/render/Model.java:33): `mode` ([`Mode`](src/haven/render/Model.java:41)), `n` (vertices, or **indices** when `ind != null`), `ninst`. Triangles = `n/3` (TRIANGLES) or `n-2` (STRIP/FAN), × `ninst`; POINTS/LINES contribute none |

**Gotcha.** The GL calls are *written* at slot-compile time and *replayed* by `BufferBGL` on the render thread —
neither is a per-frame count. Instrument the two **dispatch** seams above (`GLDrawList.draw`, `GLRender.draw`);
the replay loop is far too hot to touch.

## The 2D blit path (what `g.image` actually does)

| What | Where |
|---|---|
| The interface | [`Tex`](src/haven/Tex.java:29): `sz()` + the one primitive `render(GOut, float[] gc, float[] tc)`; everything else ([`render(g,dul,dbr,tul,tbr)`](src/haven/Tex.java:35), [`crender`](src/haven/Tex.java:51)) is a `default` that builds `gc`/`tc` and calls it. `gc` = 4 screen corners, `tc` = the same 4 in **texel** coordinates, both interleaved x,y, corner *i* at `[2i], [2i+1]` |
| `GOut.image` → the primitive | [`GOut.image(Tex,Coord[,Coord])`](src/haven/GOut.java:97) → `tex.crender(...)`, which **clips** against `g.ul`/`g.br` by trimming the texel rectangle, then calls `render` |
| The concrete blit | [`TexRender.render`](src/haven/TexRender.java:121) divides `tc` by `sz()` and draws one 4-vertex `TRIANGLE_STRIP` of [`Ortho2D.pos`+`Tex2D.texc`](src/haven/TexRender.java:35) with `usestate(draw)` — an EPHEMERAL `VertexArray` through `GLRender.draw` |
| **A render target blits UPSIDE DOWN unless flipped** | `tc` is always counted from the image **top**, which is right for a `TexI` and wrong for a texture the client drew into (see [world-3d.md](world-3d.md)'s v-flip gotcha). The engine's own answer is a flag: [`TexRaw(Sampler2D, boolean invert)`](src/haven/TexRaw.java:37), which [`Window.gbuf`](src/haven/Window.java:357) passes `true`. A hand-rolled `TexRender` over a render target must invert `t` itself (`t → sz().y - t`) before delegating |
