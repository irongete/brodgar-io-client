# 151 — tasks

Fixtures every suite may lean on, all in `builtin-res.jar` and loaded with no server: `gfx/hud/chr/agi`
(v3: `image` id `-1` + `tooltip` "Agility"), `sfx/msg` (v1: one `audio2` id `cl`), `sfx/hud/lbtn`
(`audio2` ids `down`, `up`), `gfx/hud/calendar/sun` (v5: thirteen `image`s ids 128–140 + `anim`).
Nothing loads `gfx/ccscr`, `gfx/hud/avakort`, `gfx/hud/vilind`, `gfx/hud/invsq`, `gfx/hud/combat/cmbmeters`
or `gfx/hud/combat/lframe` unasked (no class names them, no preload list carries them; `gfx/hud/bosq/*`
and `gfx/hud/emote/*` are loaded at start by the `IBox.Scaled` statics of `ISBox` and `Speaking`): a suite
that needs a resource **not yet cached** takes the first of those `hafen.resource():find(fn)` does not
list, and prints `[manual] restart the client and run :t151 once` only when all six are. Each suite polls `:loaded()` on a
bounded timer (≤ 5 s), never blocks, and ends with `resource:release()` on everything it wrote.

- [x] **151.1 — `hafen.resource()`: the client's resources and their layers, read.** `ResourceApi` mounts
      the section as a `LuaCollection` over `Resource.remote().cached()` (`named`, `MINT` by name, a name
      with an empty segment, `..` or a leading `/` refused naming the rule); `LuaResource` (`:name`,
      `:version`, `:loaded`, `:error`, `:info`, `:layers()`) takes its `Indir` on the first content read
      and catches `Loading`; `LuaLayer` (`:type`, `:id`, `:exists`, `:info`) and `LayerCodec.snapshot`
      for the eight types (`meta` is the wire's key/value block) and `{type, id}` for the rest; layer keys
      `type` / `type:id`. Pages: `resource/README.md`, `resource/layers.md`, the index rows,
      `references.md`; `docs/client/resource-loading.md` (pools, sources, both version rules, soft cache,
      `Loading`), `published-code.md`'s pool paragraph → pointer; `tools/docverbs.py` gains `resource`,
      `layer`.
      *Its suite* asserts `get(name) == get(name)`; the three malformed names refused; agi at `:version()
      == 3` with `layers():count() == 2`, `get("tooltip"):info().text == "Agility"`, `get("image"):id() ==
      -1` and `get("image") == get("image:-1")`; the sun's `anim` snapshot naming 13 frames; `sfx/msg`'s
      `get("audio2:cl"):info().volume` a number; `count("gfx/hud/chr/")` ≥ 1 once agi loaded; a name the
      server lacks reaching `:error()` and reading `:version()` as `nil`.
      `[manual]`: none.

- [x] **151.2 — Layer writes: `add`, `remove`, `release`, at every load and live.** `ResourceWrites` (the
      registry, `apply`, the `// addon:` hook line in `Resource.load` before `init()`, `Resource.newLayer`,
      `Pool.peek`/`Pool.reload` with `load(msg, keep)` carrying `Code`/`CodeEntry`, `Audio.forget`,
      `resTexCache` invalidation, the `AddonRegistry` step); `layers():add(spec)` for `tooltip`, `pagina`,
      `audio2` (v3 wire; `clip` an Ogg data asset checked by its `OggS` magic; `volume` → `vol`; a field
      left out keeps the original's; `id` defaults to the first original's), `layers():remove(key)`,
      `resource:release()`; validation on a scratch `Resource.Virtual`. Page: `resource/writes.md` (the
      order rule, in-memory-only, the staleness rule with its three live doors, refusals);
      `resource-loading.md` gains the `load`/`init` order and the two re-parse gotchas.
      *Its suite* writes agi's tooltip and reads it back at once; the old layer handle answers
      `:exists() == false`; a second write at the address wins; `remove("tooltip")` leaves `count() == 1`;
      a whole `pagina` fills an empty address and `{type="pagina"}` there is refused naming `text`; a
      `clip` that is not Ogg is refused naming Ogg, `{type="mesh"}` naming the eight; `sfx/msg` at
      `volume = 0.1` reads back `0.1`; a tooltip declared on an uncached fixture is there when it loads;
      `release()` reads "Agility" and the original volume again.
      `[manual]`: the suite plays `hafen.sound():get("sfx/msg")` after the volume write — expect: the
      chime, much quieter than usual.

- [ ] **151.3 — Picture, texture and shape specs.** `LayerCodec` encoders for `image` (v129: `id`, `z`,
      `subz`, `nooff`, `offset`, `tsz`, `scale`, `meta`, PNG from `LuaImage`'s `TexI.back` or the
      original's `img`), `tex` (`image` required), `neg` (`hotspot`, `box`, zeros, `ep` kept), `obst`
      (v2, `rings` in world units ÷ `MCache.tilesz` as `float16`), `props` (v1 list; Lua ↔ tto: string,
      number, `{x=,y=}` → `Coord`, array → list, anything else refused naming those). `writes.md` gains the
      five rows and the value table.
      *Its suite* writes agi's image from its own 8×8 `red.png` and reads `size == {w=8,h=8}`, `id == -1`
      and the offset it read before the write (fields left out keep the original's); a data asset as
      `image` is refused naming an image asset; `tex` on a resource without one is refused naming `image`;
      a `neg` with `hotspot` and `box`, an `obst` with one square ring and a `props` table each read back
      equal. Deferred through `hafen.timer():after(0, …)`, it opens a window holding
      `hafen.ui():image():source("gfx/hud/chr/agi")` and leaves it standing.
      `[manual]`: look at the suite window — expect: a solid red square where the Agility icon would be.
      <!-- extra context: src/io/brodgar/addon/Controls.java (sourceTex), docs/addons/api/ui/controls/display.md -->

- [ ] **151.4 — The `.res` file write: `resource:layers(file)`.** `ResFile` reads a data asset by the
      record grammar (`Haven Resource 1`, `uint16` skipped, `string type, int32 len, bytes`), refuses
      `code`/`codeentry` by name before any constructor runs, skips unknown types, and validates the set
      — construct and `init()` on a scratch `Resource.Virtual` — before registering a `file` record, which
      replaces every layer; later specs apply over it. The fixtures are files in the suite folder built
      once (jshell or Python) and committed with it: `suite.res` (version 999: `tooltip` "From file" +
      a 4×4 `image` id `-1`), `withcode.res` (`code` + `tooltip`), `notres.txt`. `writes.md` gains the
      file section.
      *Its suite* writes `suite.res` onto agi and reads `count() == 2`, "From file", `size == {w=4,h=4}`
      and `:version() == 3` still; `withcode.res` refused naming `code`; `notres.txt` refused as not a
      resource file; `red.png` refused naming a `.res` data asset; a tooltip spec written after the file
      reads back over it; the file declared on an uncached fixture is there when it loads; `release()`
      reads "Agility" and `count() == 2`.
      `[manual]`: none.
