# 151 — `hafen.resource()`: the client's resources and their layers

## What & why

A `.res` is the unit of what the client draws, plays and names: a tree, an icon, a chime, an
item's name. An addon can *play* one (`hafen.sound()`), *draw* one (`graphics:image(name)`,
`widget:source(name)`) and *stand* one (`hafen.virtual():ghost()`), but can neither read one nor
change it — and changing it is how a client is reskinned: a silenced bellows, a
retextured tree, a golden border on a curio icon, community data appended to a description. The one
route is upstream's `HAFEN_RESDIR`: a whole `.res` built with external tooling, taken only when its
version equals the server's, silently dropped otherwise.

`hafen.resource()` is the client's resources by name. A `Resource` reads what one carries as a
collection of `Layer`s and writes to it — a layer from a plain file (PNG, Ogg Vorbis,
text), a removal, or a whole `.res` from the addon's folder — apply to every load of that name and, when
it is already loaded, at once. Writes are client-local, checked when made, released with the addon.

## Acceptance criteria

1. `hafen.resource():get(name)` hands back an interned live `Resource` for any well-formed name (no
   empty segment, no `..`, no leading `/`); `:list(filter)`, `:count`, `:find` enumerate the resources
   the client holds, by name substring. A content read (`:loaded()`, `:version()`, `:info()`, the
   layers) makes the client fetch the resource; holding the handle or writing to it fetches nothing.
2. `resource:loaded()` is false while the client is still fetching and true after; `:version()` and
   `:info()` answer `nil` until then; a name the server has no resource for reaches `:error()` with the
   client's own message. Nothing blocks.
3. `resource:layers()` is a collection of the resource's layers, one `Layer` per wire layer.
   `:get(key)` addresses the first layer at `"<type>"` or the one at `"<type>:<id>"`; `:list(filter)`
   matches the same keys. A `Layer` answers `:type()`, `:id()` (`nil` for a type without one),
   `:exists()`, and `:info()` — the decoded fields for `image`, `tex`, `audio2`, `tooltip`, `pagina`,
   `props`, `neg`, `obst`; `{type, id}` for every other type.
4. `resource:layers():add(spec)` replaces every layer at the spec's address with one built from the
   spec over the original there — a field left out keeps the original's; a spec at an empty address
   is whole or refused naming the missing field. `image` and `tex` take an image asset, `audio2` an
   Ogg Vorbis data asset (`volume` alone keeps the clip), `tooltip`/`pagina` text, `neg`/`obst`
   shapes, `props` a table. Every spec is checked when made; a checked write cannot fail when applied.
5. `resource:layers():remove(key)` drops every layer at the address.
6. `resource:layers(file)` makes the resource's layers the file's (a `.res` data asset): its version
   is ignored, a layer type the client does not know is skipped, and a file carrying `code` or
   `codeentry` is refused naming the layer — an addon ships no Java.
7. A write lives in memory — no file, cache entry or jar is written — and reaches the resource on its
   next load whatever the source, and a loaded resource at once; `resource:release()` gives the
   client's own layers back; `Disable`/`:reload` releases everything. Writes apply in the order made,
   each over the last; a later write at the same address wins.
8. What is already built keeps its layers — a sprite on screen, a texture uploaded, a static the client
   read at start — until the client rebuilds it. The page states this rule and the live doors that
   read the current layers: `hafen.sound()`, `graphics:image(name)`, `widget:source(name)`.
9. The pages pass `tools/docverbs.py` and `tools/refusalverbs.py`; every refusal names its
   replacement or the field it wants.

## Out of scope

- **A notification on a resource** (`resource:on("Changed", fn)`): reads poll `:loaded()`; the edge
  is the other half of the read side and its own feature.
- **Writing the engine-owned types by spec** (`mesh`, `vbuf2`, `mat2`, `skel`, `tileset2`, …): they
  arrive only inside a `.res` file (criterion 6). The eight spec types are the boundary.
- **Reading pixels or clip bytes into Lua**; `graphics:image(name)` draws them.
- **Dynamic resources** (`dyn/…`), composed per session and never loaded through a `.res`.
- **Per-session scoping**: a resource pool is the client's; a write is client-wide.

## Docs impact

Pages written: `docs/addons/api/resource/README.md`, `layers.md`, `writes.md`; rows in
`docs/addons/api/README.md`, `docs/addons/README.md`, `docs/addons/api/references.md`;
`docs/addons/api/asset/handles.md` (a `.res`/`.ogg` is a data asset handed to `hafen.resource`);
`docs/addons/api/sound.md` and `docs/addons/api/ui/controls/display.md` (one line each: they read the
resource's current layers). `docs/client/resource-loading.md` (new: pools, sources, both version
rules, the soft cache, `load`/`init` order), rows in `docs/client/README.md`, links from
`resources.md`, and `published-code.md`'s pool-order paragraph becomes a pointer.

Derived set: `grep -rn -i "resource" docs/addons --include=*.md | grep -i "cannot\|no way\|never\|there is no"`
→ one row, `map/markers.md:52` (`:icon()` — unrelated). `grep -rn -i "\.res\b\|retextur\|silence" docs/addons`
→ `sound.md:38` only (its own sounds). No stale negative to rewrite.

## Context files

- `docs/addons/api/conventions.md`, `docs/addons/api/asset/handles.md`, `docs/addons/api/timer.md`, `DOCUMENTATION.md`
- `docs/addons/api/resource/README.md`, `docs/addons/api/resource/layers.md`, `docs/addons/api/resource/writes.md` — 3, 4 · `docs/client/resource-loading.md` — 3, 4
- `src/io/brodgar/addon/ResourceApi.java` (`name`), `LuaResource.java` (`res`, `layers`, the `add`/`remove`/`release` verbs), `LuaLayer.java`, `LayerCodec.java` (`type`/`key`/`at`, `snapshot`, `value`; `Spec`, `spec`, `encode`), `ResourceWrites.java` (`Record`, `add`, `file`, `apply`, `first`), `ResFile.java` (`read`, `validate`, `build`) — 3, 4
- `docs/addons/api/README.md`, `docs/addons/README.md`, `docs/addons/api/references.md` — 1
- `docs/addons/api/sound.md` — 2 · `docs/addons/api/ui/controls/display.md` — 3
- `docs/client/resources.md`, `docs/client/published-code.md`, `docs/client/README.md` — 1, 2, 4
- `src/haven/Resource.java` · `src/haven/Message.java` — 2, 3, 4 · `src/haven/TexR.java` — 3
- `src/haven/CacheMap.java`, `src/haven/Session.java` (`CachedRes`) — 1 · `src/haven/Audio.java`
  (`resclip`) — 2 · `src/haven/FastMesh.java` (`MeshRes.init`) — 2
- `src/io/brodgar/addon/AssetApi.java` (`LuaImage`, `Data`), `Refusal.java`, `Args.java`
- `src/io/brodgar/addon/LuaCollection.java` — 1, 2 · `Section.java`, `Interned.java`, `LuaSound.java` — 1
- `src/io/brodgar/addon/Addon.java` (`resCache`, `resTexCache`) — 1, 2 · `LuaGOut.java`
  (`resTex`) — 2 · `AddonRegistry.java` (the `Step` table) — 2 · `AddonManager.java`
  (`onPicture`, `log`, `profOwners`) — 1, 2 · `Controls.java` (`sourceTex`) — 3
- `tools/docverbs.py` (`RECEIVERS`) — 1
