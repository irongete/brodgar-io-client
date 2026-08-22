# 092 — The address reaches the end: tasks

Shipped as **one task with one suite**, in the maintainer's own session: ten rows over one rule, where
splitting them would have left a tree in which some seams carried the address and some did not — which is
the defect itself.

- [x] **092 — The address reaches the end.** The only **engine** feature of the sweep. Eight rows are the
      same sentence said eight ways: something addressed end to end that dropped its address at the last
      hop. **Three addressed twins** — `s:world():components(p)`, `:tileCoord(p)`, `:distance(p [, other])`
      — are the half `position.md` promised in prose and nothing implemented. **`worldToScreen` moved to
      `s:world()`** beside its inverse, projecting at **the point's own height** (`screenxf(Coord3f)` +
      `MCache.getzp`) so the round trip closes, and **`screenToWorld` takes the `{x, y}` it hands back**,
      with both refusal branches naming the asynchrony. **`GobIntent`** records a visual write against the
      object, so a character loading it later draws it the same — re-applied off `drainGobEvents`' own
      per-session queue, dropped when the object leaves its **last** session. **`Addon.placeSets`** files a
      placement under the tree its widget stands in: a character's folder, or `account` for the layer.
      **Seven HUD readers** carry their own `SessionState` instead of `drawnUser()`, which is eight bus keys
      corrected. **`Layout.sweep()` walks every tree** (the addon layer included) and **`capDirty` is per
      session**, like the list it gates. **`opts:video()` writes every tree's `gprefs`.** `addons/clickpath`
      fixed by hand, per **D6**.
      *Its suite* asserts the twins agree with the Position's own verbs (one login is one of the sessions
      they answer for), that each refuses what is not a place, that `s:player():worldToScreen` names its new
      home, that `screenToWorld` refuses two loose numbers and a missing `fn` **naming the asynchrony**,
      that installing a sheet re-lays out a window of the **addon layer's** tree — the tree the old sweep
      never reached — that a remembered window comes back out of its own character's file, that a gob's
      visual surface is addressed at the object, and last, asynchronously, that **the round trip closes
      over a height difference**.
      `[manual]`: four — each needs a second character logged in or a gesture a program cannot cause.
      *Audit*: **A-085** (`audit/ns-world.md` F1, `ns-session.md` F5, `ns-party.md` F2) · **A-086**
      (`ns-world.md` F3) · **A-087** (`ns-world.md` F4) · **A-088** (`ns-store.md` F3) · **A-089**
      (ROADMAP 077) · **A-090** (ROADMAP 078, 073) · **A-091** (ROADMAP 078) · **A-092** (`ns-player.md`
      F2) · **A-093** (`ns-player.md` F1) · **A-094** (`ns-vr.md` F4).

## Result

`:t092` — **8 pass, 0 fail, 4 manual**, all four manuals confirmed. Clean build from an empty
`build/classes`. Fifteen pages updated.

Ten rows ticked and struck; the open count went **33 → 23**. **Two carry a correction to the audit itself**,
found by recounting against the source:

- **A-086** was **already true**. Its evidence was filed at 076 and 079.3/080.1 answered it: the registry is
  keyed on the object, an attach lands on every live session's copy, and there is no `drawn()` or
  `screen()` anywhere in the overlay path.
- **A-094** takes the second answer its own row offers. **A-037** shipped the `vr/widgets.md` line, in the
  present tense, naming `panel:drawn()`. The engine half stays a ROADMAP candidate, filed 075.

**No row of another feature's block was implemented here.** **No `specs/ROADMAP.md` line is covered** — 067,
073, 076, 077, 078, 079 and 080 are each cited by a row above and were discharged as that row, not as a
ROADMAP entry; the maintainer strikes those by hand.

## Reported at the close, not changed

Three things found while doing the work, none of them in `audit/INVENTORY.md`:

1. **`FepAdapter` fires without comparing anything.** It is the only one of the nine with no change
   detection — `MeterAdapter` and `BuffsAdapter` keep a snapshot and diff — resting on a javadoc claim that
   *"each is a genuine server change"*. The wire says otherwise: the server sends `glut` several times a
   second while a character walks, and every field the API exposes is unchanged across all of them. The fix
   is the pattern already two classes up, with `food:info()` as the diff key.
2. **`GlutMeter.lglut` is not on the surface.** `update` takes six fields and `LuaHunger` reads three
   (`glut`, `lbl`, `gmod`). The one that moves is the one nothing can read, so an addon cannot even filter
   the noise from 1 for itself.
3. **`w:position(x, y)` on an addon's own window creates no level**, so `rememberCapture` never records it —
   the same call on a borrowed widget is recorded. `w:remember(name)` on a window you built is filled by a
   `Gesture` drag and by nothing else, which `ui/native.md` does not say.

And one page: **`docs/addons/api/ui/native.md` is 330 lines against a 300 ceiling.** It was 329 before this
feature and its split is already a `specs/ROADMAP.md` candidate, filed 074.
