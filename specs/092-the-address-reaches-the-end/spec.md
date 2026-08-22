# 092 — The address reaches the end

Discharges: A-085, A-086, A-087, A-088, A-089, A-090, A-091, A-092, A-093, A-094.

All ten are rows in `audit/INVENTORY.md`'s own 092 block. Two of them turned out to need no code, and
each says so in its own row — one because a later feature had already done it, one because the row's
second answer was already shipped.

## What and why

The seven features before this one moved names, shapes and collections over the bridge. **This one is the
engine.** Its rows are one sentence said eight ways: something addressed end to end that **dropped its
address at the last hop**, and answered for the character on screen instead.

`specs/076`–`079` built the model — the session is the address, and every read through it is about that
character. The rows here are where that model stops:

- A Position is derived in the session that read it and then **read in the drawn one**, because a Position
  carries no session and its own verbs were asked without one. `position.md` said so, and promised an
  addressed half — *"a verb reached through a session resolves the place in **its** frame"* — that did not
  exist. `alt:world():gob():nearest("terobjs/tree"):position():x()` is addressed at every step but the last.
- A **visual write on a gob** reached the copies that existed at the moment of the write. A third character
  walking up afterwards found an unscaled boulder, and `GobAdded` could not be the hook: it fires once for
  the client, into the first session to see the object.
- **`w:remember(name)`** filed a placement under the character **on screen**. Every saved variable beside it
  is addressed by the session it belongs to; this one set was addressed by the screen, in both directions.
- The **change-detection adapters** were held per session and every one of them read the drawn session's
  widgets, so eight bus keys carried the wrong character's payload under the wrong account.
- **`Layout.sweep()`** re-derived the drawn tree alone, and **`Layout.capDirty`** was one flag for a list
  that is one per session — the first tree to drain it cleared it for every other.
- **`opts:video()`** wrote the drawn session's `gprefs`, and there is one of those per `UI`.
- **`worldToScreen`** projected at the **player's** height, so a point up a slope answered where it would be
  at the player's altitude and the round trip did not close — and it sat on a different section from its
  inverse, taking a different argument shape and answering a different way.

## What shipped

**Three addressed twins** on the section that holds the address (A-085): `s:world():components(p)`,
`:tileCoord(p)` and `:distance(p [, other])`, the last defaulting to **that** character. Reads, so an
unreachable place is `nil` here exactly as `p:x()` is `nil`. `p:offset(dx, dy)` needs no twin — it is
arithmetic in world units.

**One conversion, one section, one shape** (A-093): `worldToScreen` moved from `s:player()` to `s:world()`,
and `screenToWorld` takes the `{x, y}` the other half hands back, so the round trip composes with nothing
in between. Both refusal branches name the asynchrony, not just the wrong-type one.

**The projection uses the point's own height** (A-092): `MapView.screenxf(Coord3f)` with `MCache.getzp(rc)`,
which is the read `s:world():height(p)` already exposes. Off-stream ground answers `nil` rather than a
number measured from somewhere else. `addons/clickpath` was fixed **by hand**, per **D6**: it is the only
consumer and no doc sweep reaches it.

**A visual write is recorded against the object** (A-087) — the new `GobIntent`. `gob:scale(k)` and
`gob:overlay():add(key)` still land on every copy that exists, and now also on the copy of a character that
loads the object afterwards: the per-session edge the ROADMAP said did not exist is `drainGobEvents`'
own queue, which the settle throws away to fire one client-wide event. The record dies when the object
leaves its **last** session — the moment `GobRemoved` fires — so *the size ends with the loaded object*
still holds.

**A placement is filed under the tree its widget stands in** (A-088). `Addon.placements` became
`Addon.placeSets`, keyed by scope: a character's `<genus>_<char>` for a widget of that session's tree, and
`account` for one standing in the addon's layer, which belongs to no character and outlives all of them.
Each scope loads lazily and each `flush()` writes the file it names.

**The seven readers that reach a HUD by account carry their own session** (A-089) — a new `SessionAdapter`
base, built with the `SessionState` that already held them. The other two cached their widgets and were
addressed already.

**The sweep reaches every tree and the caption flag is per session** (A-090). The addon layer is in the walk
for the same reason a session is: a rule names widgets by what they are, and the layer holds widgets.

**A graphics write reaches every tree** (A-091). `hafen.client()` is the client, not a character, so there
is one answer and every scene now holds it; the read stays the drawn tree's, which after a write is the
same as any other's.

## Two rows needed no code, and both say so in their own words

**A-086 was already true.** Its evidence was filed at 076; 079.3/080.1 then moved the overlay registry onto
the **object**, so an attach lands on every live session's copy and `LuaOverlay` resolves through
`anygob(id)`. There is no `drawn()` and no `screen()` anywhere in that path. The same kind of stale count
091 found on A-073 and A-082. The suite ships the regression guard.

**A-094 takes its own second answer.** The row reads *"a standing `vr` widget is drawn from any character,
**or A-037's line stands as the answer**"*, and A-037 is ticked: `vr/widgets.md` §*It stands with the
character you stood it from* states the limit in the present tense and names `panel:drawn()`. The engine
half stays a `specs/ROADMAP.md` candidate, filed 075.

## Verified

`:t092` — **8 pass, 0 fail, 4 manual**, all four manuals confirmed. Clean `ant hafen-client` from an empty
`build/classes`. Fifteen pages updated. The open count went **33 → 23**.
