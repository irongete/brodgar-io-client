# 158 — own layout: tasks

- [x] **158.1 — The verb is a level on a surface of yours, and the fold is its write.** The owned
      branch of `LuaWidget`'s `position` and `size` verbs (both arities) records the hand-named
      level (`recordMoved`, `wantPos` / `wantSize`, `nextSeq`) and lets `Layout.apply(w)` write, the
      direct write and `levelFollows` gone from them — the fold reads the stock (the builder's
      default place and box) before it moves anything, so `UiApi.releaseMoved` drops the level on
      `:position(nil)` / `:size(nil)` and lands the rule or the stock. `applyHalf`'s size half
      floors an owned control at `Owned.minsz()` and skips a `packed` `AddonWidget`, a `CImg` and a
      `MirrorWidget`, which keep their direct write and their `unpin`; `:pack()` on a surface of
      yours forgets this addon's size half. The 144.3/153.1 comments on `rememberCapture`,
      `chromeDragged`, `chromeResized`, `levelFollows` and `Moved` say the new order. Docs:
      `native.md`'s `:position(nil)` row and *The cascade*, `custom.md`'s `:position`/`:size`/
      `:pack()` rows (a level; the clamp at the root; a pack forgets your size level),
      `conventions.md`'s *Undo your layer*, `geometry.md`'s *One cascade* and its *`size` on a
      self-packing window* row (a packed surface of yours, a picture and a mirror: inert),
      `display.md`'s and `mirror.md`'s `size` rows. Covers criteria 1, 2, 6.
      *Its suite* installs `[name=158-own-layout.1/w] = {position = {200, 200}, size = {80, 60}}`
      before and after building `hafen.ui():widget():name("w"):position(40, 50):size(30, 30)`, and
      asserts after each `sheet:install()` that `:position()` reads `{40, 50}` and `:size()` reads
      `{30, 30}` (the verb outranks the rule); then `:position(nil)` → `{200, 200}` and `:size(nil)`
      → `{80, 60}` (the rule); then `sheet:release()` → `{100, 100}` and `{200, 140}` (the stock, so
      the fold read it before the verb's write); the same four reads on `hafen.ui():window()`
      (content box) and on `hafen.ui():button()` sized above its art, where `:size(120)` under the
      rule reads `{120, art}` and `:size(nil)` the rule's `{80, 60}`; a rule `size = {80, 4}` on a
      second button reads `{80, art}` (the floor), and `:size(120, art)` on it reads `art` back
      after a `sheet:install()`; a widget `:size(200, 100)` holding one label, then `:pack()`, reads
      the packed box after a `sheet:install()` under a `size` rule and after `:size(nil)` (nothing
      held); a picture (`hafen.ui():image()` on a suite asset) under a `size` rule keeps its own box
      after `sheet:install()`, `:size(50, 50)` reads `{50, 50}` through a sweep and `:size(nil)` its
      own box again; `:position(nil)` on a widget never positioned changes nothing; a column's child
      still refuses `:position(nil)` naming the column; a widget of the default box `:position(-500,
      10)` reads `x = -100` (the clamp: 100 design pixels stay on screen) while a child of a surface
      of yours at `(-500, 10)` reads `-500`; a borrowed window (`window[title=Inventory]`, a
      character in world) reads the same before and after.
      `[manual]`: none.
      <!-- extra context: docs/addons/api/ui/controls/display.md and docs/addons/api/ui/mirror.md
           (the picture's and the mirror's size rows), docs/addons/api/ui/edit.md (`:pack()` is a
           level: the borrowed pack, which stays one) -->

- [ ] **158.2 — A rule lands on a surface of yours when it is armed, named or resized.**
      `UiApi.armPending` runs `Layout.apply(c.rootw())` after `c.armed()`, gated on
      `Layout.active()`; `Layout.dispatchResized` applies `w`'s own anchor when `derived` holds `w`,
      before its followers; the `name` verb calls `Sheet.named(w)` (the subtree's cached resolution
      dropped, under the widget's monitor then `Sheet.class`) and `Layout.applySubtree(w)` (the
      subtree collected under the monitor, each widget applied holding none) after `nameSet` — and
      so refuses, as every write does, from a handler holding another tree's monitor. Docs:
      `geometry.md`'s *When it applies* and *Re-derived* rows, `custom.md`'s naming section (a name
      lands at once, layout included; from another tree's handler it refuses). Covers criteria 3, 4,
      5.
      *Its suite* installs, before building anything, `[name=158-own-layout.2/a] = {anchor = {to =
      "screen", at = "topleft", offset = {12, 34}}}`, `[name=158-own-layout.2/b] = {position = {70,
      80}}` and a chain `[name=158-own-layout.2/outer] [name=158-own-layout.2/inner] = {position =
      {5, 6}}`, with no `sheet:install()` afterwards; builds `hafen.ui():widget():name("a"):size(50,
      40)`, `hafen.ui():window():name("b")`, `hafen.ui():button():name("b2")` under a `[name=…/b2]`
      rule, `hafen.ui():mirror():name("m")` under one, and an `outer` widget holding an `inner` one
      — then, one timer tick later (`hafen.timer():after(0.1)`), asserts each `:position()` reads
      the rule's place. Resizing: a widget `t` at `{300, 300}` sized `{100, 100}` and a widget `f`
      anchored `{to = t, at = "bottomright"}` sized `{20, 20}`; `f:size(40, 40)`, one tick later
      `f:position()` reads `{360, 360}` (its own corner still on `t`'s); then a label parented into
      `f` and `f:pack()`, one tick later `f:position()` reads `{400 - w, 400 - h}` for the packed
      `f:size()` (the pack resizes, the anchor holds). A name written late: a widget built and
      armed, then `:name("late")` under `[name=…/late] = {position = {70, 80}}` reads `{70, 80}`
      right after the write, and `inner` named after `outer` armed reads `{5, 6}`; `:name` a second
      time still refuses naming the first name; from the `Draw` handler of a surface parented into
      the character's HUD (`session:ui():match("@GameUI")`, a character in world), `:name("x")` on a
      layer widget built in `run` refuses naming "one tree monitor at a time", once, on the first
      frame.
      `[manual]`: none.
