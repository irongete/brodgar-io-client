# Subsystem: 3D scene & rendering (world entities, sprites, textures, glTF)

> The `MapView` scene, client-only gobs, the render tree and the texture/material path. Line
> numbers are indicative; the **class + method/field name is the stable anchor**. Max 70 lines.

## Client-only world entities

| What | Where |
|---|---|
| **Client-only world entity (template)** | [`MapView.Plob extends Gob`](src/haven/MapView.java:1779) — `super(glob, rc)` + `setattr(new ResDrawable(...))` + `basic.add(placed)`; `move(Coord2d,double)`; `slot.remove()` |
| Gob construction (no server id) | [`Gob(Glob,Coord2d)`](src/haven/Gob.java:441) / [`Gob(Glob,Coord2d,long)`](src/haven/Gob.java:433); `Gob implements RenderTree.Node, Sprite.Owner` ([:33](src/haven/Gob.java:33)) |
| Visual attr (`.res`-backed) | [`ResDrawable`](src/haven/ResDrawable.java:79) (`Gob.setattr`) |
| Add/remove in the 3D scene | [`MapView.addClientGob`](src/haven/MapView.java:1868) (`// addon:` seam) + `Gob.placed`; transform [`Gob.Placed`](src/haven/Gob.java:922) |
| Screen → world (ground raycast) | [`MapView.Maptest`](src/haven/MapView.java:1810) (`Plob.Adjust.hit(Coord pc, Coord2d mc)`) |
| World → screen | `MapView.screenxf` |
| **Placement snapping — placegrid/placeangle** | [`PlobAdjust`](src/haven/MapView.java:1740) / [`StdPlace`](src/haven/MapView.java:1746) (position [:1749](src/haven/MapView.java:1749), rotation [:1764](src/haven/MapView.java:1764)); **public** [`plobpgran`/`plobagran`](src/haven/MapView.java:57); `:placegrid`/`:placeangle` cmds ([:2391](src/haven/MapView.java:2391)) |
| Pick pass (clickable entities, drag handles) | [`ClickMap`](src/haven/MapView.java:507), [`MapClick extends Clickable`](src/haven/MapView.java:911), [`Clicklist`](src/haven/MapView.java:1155), [`ClickLocation`](src/haven/MapView.java:1409), [`Gob.GobClick`](src/haven/Gob.java:673) |
| **Click dispatch ← intercept point** | [`MapView.Hittest`](src/haven/MapView.java:1962) resolves pick → [`Click.hit`](src/haven/MapView.java:2017) ends in `wdgmsg("click", …)` ([:2023](src/haven/MapView.java:2023)); the voice feature also hooks here ([:2018-2019](src/haven/MapView.java:2018)) |
| Follow-a-gob motion | subclass [`Moving`](src/haven/Moving.java) with `getc()` = [`target.getc()`](src/haven/Gob.java:584)`.add(offset)`, re-resolved via [`OCache.getgob`](src/haven/OCache.java:199) each frame; the render tree's per-frame [`Placed.autotick`](src/haven/Gob.java:942) tracks it. NOT a [`Following`](src/haven/Following.java:32) subclass (that steals facing) |
| Ignore gob facing | [`Location.nullrot`](src/haven/render/Location.java:169) |

## Textures & materials (no `.res` required)

| What | Where |
|---|---|
| **`.res` images are just PNG** (the substrate) | [`Resource.readimage`](src/haven/Resource.java:1061) `ImageIO.read(fp)` → [`Resource.Image` `new TexI(img)`](src/haven/Resource.java:1182) |
| PNG → GPU texture (no `.res`) | [`new TexI(BufferedImage)`](src/haven/TexI.java:52); GPU upload lazy/thread-safe in [`TexI.st()`](src/haven/TexI.java:59) → a [`ColorTex`](src/haven/render/ColorTex.java:34) |
| **2D screen blit** | [`GOut.image(Tex,Coord)`](src/haven/GOut.java:97) / [scaled `(Tex,Coord,Coord)`](src/haven/GOut.java:117) / [`aimage(Tex,Coord,ax,ay)`](src/haven/GOut.java:107) |
| Bare-`TexI` blit precedent (no `.res`) | [`SpeakerIcon.GLYPH`](src/haven/SpeakerIcon.java:51) = `new TexI(img)` drawn via `g.image(...)` ([:87](src/haven/SpeakerIcon.java:87)) |
| **World-anchored 2D blit (billboard)** | [`SpeakerIcon`](src/haven/SpeakerIcon.java:44) `extends GAttrib implements RenderTree.Node, PView.Render2D` ([:406](src/haven/PView.java:406)); `draw(GOut,Pipe)` projects via [`Homo3D.obj2view`](src/haven/render/Homo3D.java:201) then `g.image(...)` |
| **World textured quad** | a resource-free `Sprite`: `quadVerts` (upright x=0 plane, z 0→h, y ±w/2, t-inverted) → [`Model(TRIANGLE_STRIP,VertexArray,null,0,4)`](src/haven/render/Model.java:45), [`Layout`](src/haven/render/VertexArray.java:65) of [`Homo3D.vertex` VEC3](src/haven/render/Homo3D.java:41)+[`Tex2D.texc` VEC2](src/haven/render/Tex2D.java:36) (world-vertex xf from the scene's `Homo3D.state`, [PView:385](src/haven/PView.java:385)) |
| Texture material (the working recipe) | `new Material(tr.draw, tr.clip, Material.nofacecull)`[`.apply(model)`](src/haven/Material.java:165) → a `RenderTree.Node` — `tr` = a [`TexRender`](src/haven/TexRender.java:34) over the `TexI` sampler (`tex.st().data`); [`TexDraw`](src/haven/TexRender.java:73) samples + [`TexClip`](src/haven/TexRender.java:97) **alpha-discards** (the `.res` [`$tex`](src/haven/TexRender.java:138) matpart, `clip=true`) → SOLID, not blended; double-sided, unlit. *(NOT `ColorTex`+`FragColor.blend` — that translucent-overlay recipe reads as a 1% ghost.)* |
| **Resource-free `Drawable`** | [`SprDrawable(Gob, Sprite.Mill)`](src/haven/SprDrawable.java:35), [`getres()==null`](src/haven/SprDrawable.java:63) (`Mill` resolves the owner cycle); same [`Drawable`](src/haven/Drawable.java:31) attr slot as `ResDrawable` |
| **Lighting** | material state = [`Light.PhongLight`](src/haven/Light.java:145) (frag; ctor takes emi/amb/dif/spc/shine; `defamb/defdif/defspc` neutral defaults) → the [`Phong`](src/haven/render/Phong.java:35) shader multiplies the scene's [`Lighting.lights`](src/haven/render/Lighting.java:38)/[`Light.LightList`](src/haven/Light.java:81) (applied at the PView scene root — [`PView.lights`](src/haven/PView.java:40)) into the fragment. Normals need the **inverse-transpose** of `basis·node` ([`Matrix4f.invert`](src/haven/Matrix4f.java:175)/[`transpose`](src/haven/Matrix4f.java:149)/[`trim3`](src/haven/Matrix4f.java:159)). **sRGB = no-op** ([`Texture.srgb`](src/haven/render/Texture.java:38) left `false`, like all game textures) |
| glTF → geometry | per primitive → a `Model` (POSITION→[`Homo3D.vertex`](src/haven/render/Homo3D.java:41), NORMAL→[`Homo3D.normal`](src/haven/render/Homo3D.java:42) [VEC3 `"normal"`, shaded to eye space as `mat3(cam)·mat3(wxf)·objn`], TEXCOORD_0→[`Tex2D.texc`](src/haven/render/Tex2D.java:36), indices via `Model.Indices`); baseColor `TexI.st()` + `BaseColor` factor → `Material.apply` |
