# 135 — gob tint: plan

## Approach

**One new attrib, cut from `GobScale`'s mould.** `io.brodgar.addon.GobTint extends GAttrib implements
Gob.SetupMod`: a `volatile Color`, an `Addon owner`, a cached `Pipe.Op` minted once per value, and the
same four statics — `on(g)`, `value(g)`, `apply(g, owner, c)`, `revert(g, a)`. Writing `null` deletes
the attrib outright, as `scale(1)` does: no tint is the absence of the state. No engine file is edited:
`Gob.setattr` registers a `SetupMod`, `Gob.ctick`'s `updstate` rebuilds `GobState` and pushes
`slot.ostate` only when the composed mods differ, and a cached op is what makes that comparison hold.

**The op is a `State` of its own, not a `MixColor`.** `GobTint.Wash extends State`: a `Slot<Wash>` of
`Slot.Type.DRAW`, a `Uniform(VEC4, p -> p.get(slot).col, slot)`, `apply(Pipe p) { p.put(slot, this); }`,
and a `ShaderMacro` of one line — `FragColor.fragcol(prog.fctx).mod(in -> MiscLib.colblend.call(in,
u.ref()), 50)`. That is `ColorMask`'s shape with `colblend` at order **50**: the damage wash
(`MixColor`, order 0) is applied first, the addon's colour over it, `ColorMask` (100) over both. The
blend function is the one `e:tint` and `GobHealth` use, so `a` means the same strength everywhere.
A `Slot` holds one state, so a second `MixColor` would replace `GobHealth`'s rather than compose — that
is the gotcha `world-3d.md` gains.

**Instancing.** `InstanceList.InstKey` keys a batch on every non-instanced slot's state (`uinststate`),
so a tinted gob leaves the batch of its untinted twins and draws in one draw call of its own, correctly.
No `Instancable` is needed; a row of identical boxes with one tinted is the check.

**The verb** sits in `LuaGob` beside `visible`: `Args.passed(a, 2)` false → read, `gob(self, "tint")`
null → `NIL`, else `AddonManager.color(GobTint.value(g))` or `NIL`. Passed and `isnil()` → write
`null`; else `AddonManager.colorArg(a, 2, "gob:tint")`, whose refusal is `colorRefusal("gob:tint")`.
The write walks `AddonManager.gobCopies(h.id)` and records `GobIntent.tint(h.id, owner, c)`. A gone
gob takes the write and does nothing, like its siblings.

**The intent and the teardown** are two additive edits. `GobIntent.Record` gains `tintOwner`/`tint`,
counted by `empty()`, cleared by `dropOwner`, applied in `applyTo` before the hide, with a static
`tint(id, owner, c)` where `null` forgets. `UiApi.teardownGobScales` calls `GobTint.revert(g, a)` in the
same loop as `GobScale.revert` — the one sweep site, not a second one. `gobInfo` sets `tint` when the
attrib is there, and no key otherwise.

**The page split.** `docs/addons/api/look.md` — *Look: how a gob is drawn* — takes `## Size
(unprotected)`, `## Drawn or not (unprotected)`, a new `## Tint (unprotected)` and `## Overlays` out of
`gob.md`, headings kept so the slugs survive the move. `gob.md` keeps one short `## How it is drawn`
section pointing there, and its opening paragraph's three-write sentence is re-pointed. Five inbound
anchors move (`threading.md` ×2, `types/world.md`, `virtual/README.md`, `flowermenu.md:150`), plus
`gob.md`'s own five. `README.md` gains the row; `conventions.md`'s *none* row, `shapes.md`'s reader and
writer lists, `threading.md`'s safe-write bullet and `GobInfo`'s table each gain one entry.

## Files to create/modify

- `src/io/brodgar/addon/GobTint.java` — new: the attrib and its `Wash` state
- `src/io/brodgar/addon/LuaGob.java` — the `tint` verb
- `src/io/brodgar/addon/GobIntent.java` — the tint half of `Record`, `tint(...)`, `dropOwner`, `applyTo`
- `src/io/brodgar/addon/UiApi.java` — `teardownGobScales` reverts tints too
- `src/io/brodgar/addon/AddonManager.java` — `gobInfo` sets `tint`
- `docs/addons/api/look.md` — new
- `docs/addons/api/gob.md`, `README.md`, `conventions.md`, `shapes.md`, `threading.md`,
  `types/world.md`, `virtual/README.md`, `flowermenu.md` — anchors and rows
- `docs/client/world-3d.md` — the `MixColor` slot gotcha on the `SetupMod` row (143 → ≤150, or split)
- `addons/135-gob-tint.1/`, `addons/135-gob-tint.2/` — the suites

## Risks and gotchas

- **`Args.written` raises on an explicit `nil`** (arity is the verb). `tint` is one of the nils the API
  documents, so it takes the `Args.passed` + `isnil()` path `VirtualApi`'s entity `tint` uses, never
  `written`.
- **The op must be cached per value**: `Wash` has no `equals`, so a fresh instance per tick would
  re-push `slot.ostate` every tick for every tinted gob (`GobScale`'s javadoc, `world-3d.md`'s row).
  Same colour written twice mints nothing; `Color.equals` decides.
- **Order of `setupmods` is arrival order**, and it does not matter once the tint is its own slot: the
  fragment mods are sorted by their order argument, not by pipe position.
- **`colblend` with `a = 255` is a flat fill** — the lit shading is gone, which is what alpha means
  here. The page says so, with `96` as the worked example; the suite's manual line uses a washed value.
- **Thread**: a Lua verb is marshalled onto the tick, where `ctick` runs; `apply` is called there, as
  `GobScale.apply` is. `Wash.col` is final, the attrib's `Color` volatile.
- **`GobInfo` today carries `visible` but not `scale`** (audit `wo-13`, refactor block B11). This
  feature adds `tint` only; `scale` stays B11's row, so the snapshot names two of the three until then.
- **`audit2/` collisions, all additive**: B01 moves `GobIntent.intents` per `SessionState` — the tint
  half lives inside `Record`, so the move carries it; B02 turns `teardownGobScales` into a step — the
  revert is inside it; B02's REPL sweep also closes the `:lua`-owned tint that `:reload` leaves today,
  the same hole `scale` has (`po-04`) — reported, not fixed here. B05, B11 and B13 name `gob.md`
  sections that stay; B11's `wo-13` pointer (`gob.md:213-216`) lands on `look.md` after the split.
- **`docverbs.py` and `refusalverbs.py`** are run after each task; the virtual kinds docverbs skips are
  not involved, `gob:` is not one of them.

## Discarded alternatives

- **Reusing `MixColor` as the op** — its `Slot` is `GobHealth`'s, so whichever attrib arrived last
  would replace the other's wash: a damaged box would lose its cracks' red or the addon's colour by
  arrival order. A slot of its own at another fragment order composes with it instead.
- **An instanced state (`InstanceBatch.AttribState`, `MixColor`'s twenty lines)** — the batch key
  already separates a differing non-instanced state, so a tinted gob simply draws alone; the extra
  attribute buys a draw call per tinted model and costs a second code path to keep right.
- **`gob:tint(nil)` refused, with a separate clearing verb** — one property, one name; `e:tint(nil)`
  already gives the nil a meaning, and a second spelling at one level is the dual style the grammar bans.
- **Reading `nil` as "gone" and a sentinel colour as "none"** — an entity reads `nil` for none and
  `gob:exists()` is the liveness test on every gob verb; a sentinel would be a colour no one wrote.
- **Multiplying the tint into the texture (`BaseColor`)** — a multiply cannot lighten or reach a flat
  fill, and it is not what `e:tint` means; `colblend` is the one blend both levels share.
- **A tint per session (written to the addressed character's copy only)** — an object looks one way,
  the rule `scale` and `visible` already state; a per-view colour would be a different verb on `world`.
- **Growing `gob.md` past 364 lines** — the ceiling is 300 and the hard stop 350; the visual writes are
  one subject (`look`), and moving them is the split by subject the documentation rule asks for.
- **Naming the page `drawn.md`** — the maintainer chose `look.md`.
