# 043 — Plan

> Caps lifted for this feature (maintainer directive). See [spec.md](spec.md) for what and why.

## The approach: relocation, and one mechanism stops hiding

Nothing here renders differently. The feature moves three collections into a new section, makes
the anchor an argument, and stops hiding from `hafen.vr()` what is already registered there.

**The key fact the whole plan rests on** (`learnings/rendering.md`, 038.2): a world-space gob
overlay's entity is *already* registered in `Addon.ghosts`/`sprites`/`objects` exactly like a free
one — that is what makes teardown free — and 038 then deliberately **hid** it from those
collections so there would be one door. So `hafen.vr():sprite():add(asset, gob)` is not new
plumbing. It is the same object, created through the collection that always owned it, with the
hiding removed and the anchor passed in.

That is why "the anchor is an argument" costs almost nothing: the entity core already supports
both anchors, and `ov:image()` was a second door onto it.

## The port surface, measured

| What | Count | Notes |
|---|---|---|
| Lua call sites | **38** — 17 `hafen.ghost()`, 21 `hafen.render()` | in **3 files**: `hello/main.lua`, `planner/main.lua`, `planner/gizmo.lua` |
| Lua overlay world-kind sites | **2** | `tagger/main.lua` (179 lines), the 038 example addon |
| Docs pages naming the sections | **14** | plus `gob.md`'s Overlays section, rewritten |
| Java classes in the blast radius | `RenderApi`, `LuaGhost`, `LuaSprite`, `LuaSpriteBillboard`, `LuaObject`, `LuaGobOverlay`, `GhostGob`, `SpriteQuad`, `MeshSprite` | 7 files reference `GhostApi`/`RenderApi` |

Smaller than the ROADMAP's "~64 Lua sites" estimate, because that number predates 039 collapsing
the flat spellings. `planner` carries most of it — it is the placement demo, so it exercises all
three collections plus the gizmo.

## Files to create / modify

**New**
- `src/io/brodgar/addon/VrApi.java` — the section: the three collections, `:list()`, `:visible(b)`.
  Mostly a re-home of `RenderApi`'s dispatch plus `LuaGhost`'s, not new logic.
- `docs/addons/api/vr/README.md` — the hub, and the page that finally says "here is everything
  virtual you can stand in the world". `vr/ghosts.md`, `vr/sprites.md`, `vr/models.md`,
  `vr/gizmo.md` follow the existing pages' content.

**Modified — Java**
- `RenderApi` — collapses into `VrApi`; the file goes or becomes a thin retired-name raiser.
- `LuaGhost`/`LuaSprite`/`LuaSpriteBillboard`/`LuaObject` — `:add` takes a Position **or** a Gob;
  `:billboard(b)` → `:facing(mode)`; otherwise unchanged.
- `LuaGobOverlay` — the three world kinds and the world verb set are cut; what remains is
  `draw`/`text` plus the native read. An anchored VR entity is surfaced in `:list()` **read-only**,
  the same path natives already take.
- the `Retired` table (039's mechanism, pure data) — every old spelling gets its row, so the
  refusals are generated rather than hand-written.

**Modified — docs** — `api/ghost.md` and `api/render/**` retire into `api/vr/**`;
`api/gob.md`'s Overlays section is rewritten around what stays; `api/README.md` and
`docs/addons/README.md`'s "API at a glance" lose two rows and gain one; the 12 remaining pages
that name the old sections are swept.

**Modified — addons** — `hello` (frozen: ported, not grown), `planner` + `planner/gizmo.lua`,
`tagger` (the two overlay world-kind sites).

**Codebase coverage** — `specs/codebase/world-3d.md` already covers the client-only entity core
and needs no extension for this feature; if the overlay cut requires reading beyond it, `/end`
extends it.

## Risks & gotchas

- **The entity core is shared, and 044 lands on it next.** Keep `VrApi`'s dispatch open to a
  fourth collection; a `switch` over three kinds that a widget cannot join is the shape to avoid.
- **`GhostGob`/`SpriteQuad`/`MeshSprite` are engine-side and are NOT part of this move.** They are
  visuals; only the Lua-facing section changes. The ROADMAP's separate "package layout" item lists
  `GhostGob` as a candidate for a different move entirely — do not do both at once.
- **An anchored entity dies with its gob; a free one does not** (D-102). The cut must not lose
  that: today `LuaGobOverlay.gobGone` runs from the `GobRemoved` drain holding the exact records.
  After the move the same handler must find them through the collection instead, in O(1), without
  reintroducing a per-frame sweep — which is exactly what D-100 deleted.
- **`hafen.vr():visible(false)` must restore what was visible, not everything** — an entity the
  addon had individually hidden stays hidden when the section is shown again. A boolean per
  entity, not a global flag consulted at draw.
- **`planner` persists its layout** (`kind`/`res`/`img`/`billboard`/…) in `hafen.store`. The
  `billboard` boolean becomes a `facing` string; nothing is released, so the port rewrites the
  stored field rather than carrying a shim.
- **`ant hafen-client` is incremental and can false-green** a moved symbol — and this feature is
  nothing *but* moved symbols. `rm -rf build/classes` before believing a green build, every task.
- **A scripted corpus port cannot see `pcall(hafen.ghost, …)`** — 039 hit exactly this and it
  rewrote the very spelling a refusal test pins. Port by hand where a suite asserts a refusal.

## Discarded alternatives

- **`hafen.world():sprite()` / `:ghost()`** — the ROADMAP's standing answer. Rejected: `hafen.world`
  is the live world *the server sent*, so it would make your fake props siblings of real gobs. What
  separates a ghost from a gob is not where it is but whose it is.
- **Keeping `ov:image`/`model`/`ghost` beside the new collections.** Rejected by the maintainer
  after two passes: it is two doors onto one object (D-103), and the docs would have to teach
  which to use instead of it being evident.
- **Cutting `draw`/`text` from `gob:overlay()` too**, leaving it natives-only. Rejected: they are
  screen-space painters at the gob's projected point, which is exactly "what is drawn at this
  gob" — the identity the section keeps.
- **Doing `:billboard` → `:facing` in 044 instead.** Rejected: every call site is being ported
  here anyway, so splitting it means two passes over the same two addons for one line each.
- **Shipping `"camera"` here as an alias for today's blit.** Rejected: it would mean the wrong
  thing for one feature's length. `"camera"` raises until 044 makes it a world quad.
