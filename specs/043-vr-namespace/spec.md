# 043 — `hafen.vr()`: one section for everything you stand in the world

> Caps lifted for this feature (maintainer directive). Pure reorganization: **no new rendering
> capability**. Its sibling [044-spatial-ui](../044-spatial-ui/) builds on the namespace this
> settles.

## What

`hafen.vr()` becomes the one section for **client-only things standing in the 3D world**. It
absorbs `hafen.ghost()` and `hafen.render()` whole, takes the world-space kinds out of
`gob:overlay()`, and turns the **anchor into an argument** rather than a choice of door.

```lua
hafen.vr():ghost():add("gfx/terobjs/arch/logcabin", p)   -- standing at a point
hafen.vr():sprite():add(icon, p)
hafen.vr():sprite():add(icon, rabbit)                    -- following a gob
hafen.vr():object():add(mesh, p)

hafen.vr():list()                                        -- everything you have stood, all kinds
hafen.vr():visible(false)                                -- hide it all at once, destroy nothing
```

Three collections here; the fourth, `hafen.vr():widget()`, is [044](../044-spatial-ui/). Each
carries the verb set its members already have — `:position`, `:rotate`, `:scale`, `:alpha`,
`:tint`, `:visible`, `:clickable`, `:onClick`, `:exists` — and the collection verbs
`:add`/`:list`/`:count`/`:find`/`:remove`.

`:billboard(b)` becomes **`:facing(mode)`** with the two modes that exist today — `"fixed"` (an
upright world quad) and `"screen"` (a constant-size camera-facing blit). 044 adds the third,
`"camera"`, which is a world quad that turns to face the viewer; an unknown mode raises naming the
valid ones, so `"camera"` raises here and works there. The rename lands in this feature rather
than the next one because every call site is being ported anyway — doing it twice would cost two
passes over the same two addons for one line each.

## Why

**1. The world kinds were never engine overlays.** `overlay` is the engine's word for `Gob.ols` /
`Gob.Overlay` — a Sprite attached to a Gob. But when 038 built `ov:image`/`model`/`ghost`, their
visual turned out to be **its own client gob in the scene** — that is literally what D-102 exists
to handle, and `learnings/rendering.md` (038.2) records that such an entity *is registered in
`Addon.ghosts`/`sprites`/`objects` exactly like a free one*, then deliberately hidden from those
collections so there would be one door.

So today the word `overlay` covers **three different mechanisms**: engine overlays (the natives),
screen-space painters (`draw`/`text`), and client gobs in the scene (`image`/`model`/`ghost`).
Moving the third out does not break a category — it undoes an overlap.

**2. It deletes a whole class of refusals.** `ov:offset()` currently means **screen pixels or
world units** depending on which kind the overlay said. `:scale`/`:rotate`/`:tint`/`:alpha`/
`:billboard` exist on the Overlay object but are *refused on a screen-space overlay, naming the
kinds*. All of that goes: `:offset` gets one meaning, and the "this verb only works on some kinds"
machinery disappears from both the code and the page.

**3. It fixes the ROADMAP's own standing complaint, better than the ROADMAP's answer did.** That
entry says `hafen.render` is named after a **mechanism** while the other two places a drawn thing
can live are named after the **place**, and proposes moving both under `hafen.world`. But
`hafen.world()` is the *live world the server sent*; putting your own fake props there makes them
siblings of real gobs. What actually separates a ghost from a gob is not where it is — both are in
the world — but **whose it is**. `hafen.vr()` names that axis.

**4. One place to look.** There is no page today that says "here is everything virtual you can
stand in the world". After this there is one section, one hub, and one rule to teach: **you do not
decorate a gob with content; you stand content in the world and anchor it if you want.**

## What `gob:overlay()` becomes

It keeps its identity and gets a tighter one: **what is drawn at this gob.**

| Stays | Goes |
|---|---|
| the game's own overlays, read-only, keyed by resource name | `ov:image(asset)` → `hafen.vr():sprite():add(asset, gob)` |
| `ov:draw(fn)` — your screen-space painter at the gob's projected point | `ov:model(asset)` → `hafen.vr():object():add(asset, gob)` |
| `ov:text(s)`, `ov:color(...)`, `ov:offset(x, y)` in screen pixels | `ov:ghost(res)` → `hafen.vr():ghost():add(res, gob)` |
| `:key`, `:gob`, `:native`, `:kind`, `:exists`, `:info` | the world verb set: `:scale`/`:rotate`/`:tint`/`:alpha`/`:billboard`, and `:offset`'s 3-argument world form |

**"What is at this gob?" keeps one complete answer.** A VR thing anchored to a gob appears in
`gob:overlay():list()` as a **read-only entry** that points at `hafen.vr()` — exactly the
treatment the game's own overlays already get. You read it there; you address it through the
collection that owns it.

## Acceptance Criteria

Each `:t043-<X>` suite proves this through `hafen.*` alone, automated wherever a read can answer
it. **Because this feature changes no pixels, almost everything here is assertable** — the
`[manual]` lines are only for confirming that things still appear where they did.

- **043.1** — `hafen.vr():ghost/sprite/object` each exist, build, and answer every verb their old
  section answered; `hafen.ghost` and `hafen.render` raise naming their replacement; a ghost,
  sprite and object placed through the new door behave identically to the old one.
- **043.2** — `:add(what, gob)` anchors to a game object and tracks it as it moves; `:add(what, p)`
  stands at a point; a wrong anchor type raises naming both forms; an anchored entity dies with
  its gob and a free one does not.
- **043.3** — `ov:image`/`ov:model`/`ov:ghost` each raise naming their `hafen.vr()` replacement;
  the Overlay object's world verbs are gone and `ov:offset` has exactly one meaning; a VR entity
  anchored to a gob appears in `gob:overlay():list()` as read-only, and writing through it raises;
  the game's own overlays still read exactly as before.
- **043.4** — `hafen.vr():list()` returns every entity across all three kinds and only this
  addon's; `hafen.vr():visible(false)` hides them all and `:visible(true)` restores exactly what
  was visible before, destroying nothing; per-handle `:visible` still works independently.
- **043.5** — `:facing()` reads back `"fixed"` and `"screen"`; `"camera"` raises naming the valid
  modes (it arrives in 044); `:billboard` raises naming `:facing`; the docs sweep reports counts.

**Verification** (per `AREA.md`): `ant hafen-client` → full client restart → `:t043-1` .. `:t043-5`.

## Out of Scope

- **`hafen.vr():widget()`** and the `"camera"` facing mode — both are [044](../044-spatial-ui/).
- **Any change to what is drawn.** A ghost placed after this feature must look pixel-identical to
  one placed before it. This is a relocation, and that is the property the suites check.
- **World-space text and world-space shapes** — two gaps this section makes visible, filed on the
  ROADMAP rather than built here.
- **`hafen.ui():overlay()`** (the HUD painter) and `gob:overlay()`'s screen kinds — screen space,
  not the world, and they stay exactly where they are.

## Context Files

- `docs/addons/api/ghost.md`, `api/render/README.md`, `api/render/sprites.md`,
  `api/render/models.md` — the sections absorbed, and the gizmo that moves with them
- `docs/addons/api/gob.md` (Overlays) — the half that leaves, and what stays
- `docs/addons/api/conventions.md` — the collection grammar the new section must obey
- `specs/design/24-gob-overlays.md` — D-100/102/103, and the absorption this partly reverses
- `specs/design/17-custom-rendering.md`, `16-virtual-entities.md` — the shared world-entity
  core all four kinds already sit on
- `specs/038-gob-overlays/`, `039-uniform-api/` — the feature being partly reversed, and
  the precedent for a reorganization shipped as its own feature
- `specs/learnings/rendering.md` (R2/R2b, 038.2) and `learnings/ghosts.md` — the entity
  core, the anchoring, and the hiding this feature undoes
- `specs/codebase/world-3d.md` — client-only world entities, `MapView.addClientGob`, the pick pass
