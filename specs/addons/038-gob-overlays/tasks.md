# 038-gob-overlays — Tasks

## 038.1 — the verb, the read, and the death of the sweep ✅ DONE

`gob:overlay(key, spec)` / `(key, nil)` / `(key)` / `()` with the **screen-space** family only
(`{draw = fn}`, `{text = …}`), the `LuaOverlay` object (base reads, no `:remove()`), the native half of
the read, plus the state moving from a swept filter list **onto the gob itself** (plan §1–2). Deletes
`hafen.ui.gobOverlay`, `gob:overlays()`, `sweepGobOverlays`, `AddonManager.GobOverlay`, `Addon.gobOverlays`.
**Measure first** (§4): how many live gobs carry two native overlays of one resource — that picks the
native key (res name vs `findol` id), and the answer goes in the docs.

Suite must prove: the four arities; the same key twice leaves **one** overlay; two addons' `"tag"` on
one gob do not collide and each reads back only its own + the natives; `native = true` on the game's
own and `false` on ours; an attach onto a native key **raises naming the key**, and so does
`(nativeKey, nil)`; a spec naming neither `draw` nor `text` raises naming the field;
`hafen.ui.gobOverlay == nil` and `gob.overlays == nil`; `grid:overlays()` still answers (the
map-database verb is a different one). `[manual]`: a label stands over the chosen gob and tracks it
as the camera moves.

## 038.2 — the world-space family, and `follow` is absorbed

`{image = asset}`, `{model = asset}`, `{ghost = res}` and `offset = {x=,y=,z=}` on the same verb, over
the existing `FollowMoving`; the Overlay object composes the entity's own verbs (`:tint`, `:rotate`,
`:scale`, `:pos`). Hard cut of `follow=` from `render.sprite`/`object`/`hafen.ghost` and of the handles'
`:follow`/`:offset` — **refused naming the replacement**, not silently ignored.

Suite must prove: each of the three spec kinds attaches and is read back with its type; `offset`
moves it and re-reads; the composed verbs answer on the overlay object; `sprite{follow=…}`,
`object{follow=…}` and `ghost{follow=…}` each **raise** naming `gob:overlay`; `sprite{}.follow == nil`;
a fixed sprite still builds (the cut took only the anchor); a world overlay whose target despawns is
**destroyed**, not left floating (§2b — today's `follow=` orphans it). `[manual]`: an icon floats over
your own character at `{z=18}` and follows you as you walk.

## 038.3 — the two events, and the only core edits

`GobOverlayAdded` / `GobOverlayRemoved`, payload `{ gob, key, native }`, on the two `// addon:` seams
(`Gob.addol(ol, async)` body and `Gob.Overlay`'s `gob.ols.remove(this)`), `hasSub`-gated and **queued
onto the tick** (`addol` runs from `ctick` and from loader threads). An addon's own attach/remove
fires the same pair with `native = false`.

Suite must prove: subscribing then attaching fires `GobOverlayAdded` once with `native = false` and
the right key; removing fires `GobOverlayRemoved`; the one-arg `addol` overload does **not** double-fire
(assert one event per attach); the handler runs on the UI thread (a `hafen.*` read inside it works).
`[manual]`: stand near a gob the server decorates (a lit fire, a curiosity) and confirm a
`native = true` pair is logged — honestly skipped if nothing near the player carries one.

## 038.4 — the close: churn, the example, and the docs

Teardown and churn hardening asserted end to end; `hello` edited (v-bump; `:2883` and `:2027`); one
example addon demonstrating the verb; the docs sweep (`gob.md` gains the verb, `ui/custom.md` loses
half of "Overlays", `render/sprites.md`/`models.md`/`ghost.md` lose `follow=`, `events.md` gains two
rows, `conventions.md`'s "Passing a Gob to the rest of the API" is corrected, plus both READMEs where a
section's row moved); link/anchor check, self-verified against a planted break;
`specs/codebase/state.md` extended (plan §"Files"); `design/24-gob-overlays.md` written.

Suite must prove: `:reload` removes every overlay the addon attached and leaves the natives alone; an
overlay on a despawning gob stops without erroring, fires `GobOverlayRemoved`, and leaves **no** record
behind (a returning gob reads `gob:overlay()` bare); the docs' examples run as written. `[manual]`: the
example overlay is visible, survives a `:reload`, gone on disable.
