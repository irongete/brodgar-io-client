# 050-granular-permissions — Spec

## What & why

The permission is one coarse tier: an addon declares it and all 22 protected verbs are granted at
once. So the consent dialog can only make one blanket statement — it over-warns about an addon that
wants to change the movement speed and under-warns about one that wants `widget:send`. This feature
replaces the single tier with a **catalogue of per-verb keys**, declared individually or by prefix
wildcard, so **the permission the user grants is the list they read**.

A delta on **D-028**'s surface, not on its model (`design/12`): per-addon, default-disabled, enable-with-consent, no global switch and server-authoritative are all unchanged.

The tier's name goes with it — a hard cut, nothing to migrate (nothing is released), nothing
rejected or mentioned by name, and no identifier or pref key left carrying it. In `docs/` the gated
verbs are **protected** and the system is **permissions**; `action` as a GAME noun
(`hafen.actionbar()`, `hafen.event():action()`, the action menu) is untouched.

## The catalogue — the contract, one key per gate that exists today

`player.move` · `player.hand.use` · `gob.click` · `item.use` · `item.take` · `item.drop` ·
`item.transfer` · `world.place` · `world.select` · `menugrid.use` · `flowermenu.select` ·
`flowermenu.cancel` · `craft.make` · `actionbar.use` · `actionbar.res` · `kin.add` · `kin.rename` ·
`kin.group` · `kin.endKin` · `kin.forget` · `speed.current` · `widget.send`

Named `<section>.<verb>` after the section the verb LIVES on (`pag:use()` → `menugrid.use`,
`slot:use()` → `actionbar.use`); `player.hand.use` is the one nested case. A prefix wildcard
(`item.*`, `kin.*`) is legal in a disk manifest; the bare `"*"` is legal **nowhere** — the `:lua`
REPL owner declares all 22 generated from the catalogue instead. **What the user consented to is
recorded per addon**, so a manifest that later asks for more is disabled and asked again.

## Acceptance criteria

- [ ] An addon declaring nothing is refused at **all 22** verbs; each error names the verb and the
      key it needs. The retired tier's name survives nowhere: not in an error, a page or a dialog,
      and not as an identifier or a pref key of the permission system either.
- [ ] A declaring addon whose manifest later asks for a key the user never consented to is
      **disabled again** and re-prompted, naming what is new; one asking for the same set or a
      smaller one keeps the user's choice; one declaring nothing is never touched. All four
      transitions proven headlessly — the policy is pure.
- [ ] A manifest declaring an unknown key (`player.mvoe`) or the bare `"*"` **fails to load**, and
      the AddOns panel shows the reason naming the valid keys.
- [ ] A suite **declaring a deliberate subset** (`item.*` and `player.move`, say) proves the matcher
      on both sides **without touching the world**: a *declared* verb called with an invalid argument
      raises the ARGUMENT refusal — the gate runs first (D-213), so getting past it IS the proof of
      the grant — while an undeclared sibling still raises the permission refusal naming its key.
      Exact match, prefix wildcard and its boundary (nothing outside `item.`) are all assertions.
- [ ] Enabling that suite raises a consent dialog **listing exactly the keys it asked for** in plain
      language, one line each, instead of the fixed paragraph; its panel row reads `[protected: N]`
      with the permissions in the row tooltip — the shape `[net]` already uses for its hosts. The
      maintainer enabling it and approving that dialog is part of the run.
- [ ] `docs/addons/**`: `guides/permissions.md` exists and `actions-and-permissions.md` does not;
      every protected page states the key its verb needs; a `grep -rin "actions" docs/addons`
      leaves only game-noun hits, listed and read one by one. §12's checks run and are reported.
- [ ] Each task ships its self-checking addon per `specs/testing/addon-suite.md`; its run is all
      `[pass]` (plus any `[manual]` line the maintainer confirms) and every prior suite still is.

## Out of scope

- **The `network` block** — unchanged: it already rejects a bare `"*"` in a disk manifest and keeps accepting `*.domain` subdomain wildcards. Confirmed as desired behaviour.
- **New protected verbs.** The set stays exactly the 22 gated today; nothing is gated or ungated.
- **Renaming the game noun**: `hafen.actionbar()`, `hafen.event():action()`, "the action menu".
- **Closed spec folders 001–049**, frozen as history: only `STATE.md`, `GLOSSARY.md`, the decisions file (renamed) and the new decisions are updated.
- A Lua read of one's own granted permissions — D-220 deleted that question, and it stays deleted.

## Context files

- `design/12-security-and-permissions.md` — the permission model this refines
- `decisions/permissions.md` — D-027/D-028 the model, D-213 gate-before-argument-check (which is what lets a suite prove a grant without acting), D-219 protected-wherever-it-lives, D-220 no-self-probe
- `048-act-dissolved/` — prior art: the same adjective sweep across `src/` + `docs/`, and its rule that every renamed group heading MOVES AN ANCHOR
- `src/io/brodgar/addon/Manifest.java` — the declaration, its parsing and (new) its validation
- `src/io/brodgar/addon/AddonManager.java` — the one gate all 22 sites call, and the tier's comment
- `src/io/brodgar/addon/AddonRegistry.java` — the default-disable policy, its persisted set, the flag
- `src/io/brodgar/addon/ui/ActionsConsentWnd.java` · `ui/AddonPanel.java` — the dialog and the badge
- the 12 gate-carrying files — `ActApi` `CharApi` `FlowerMenuApi` `LuaCraft` `LuaGob` `LuaHand` `LuaItem` `LuaKin` `LuaPagina` `LuaSlot` `LuaWidget` `WorldApi` — one key per call site
- `src/io/brodgar/addon/Retired.java` — five refusal texts that name the retired tier
- `docs/addons/guides/actions-and-permissions.md` — the long form, becomes `guides/permissions.md`
- `docs/addons/api/conventions.md` · `docs/addons/runtime.md` — the model once; the manifest table
- the 11 pages carrying a protected heading — `api/{player,gob,world,menugrid,flowermenu,craft,actionbar,kin,speed}.md`, `api/ui/{items,widget}.md` — plus every page linking into their anchors
- every installed addon whose `manifest.json` declares the retired permission (`grep -rl` over `addons/*/manifest.json`), plus its paragraph in `docs/addons/examples.md` — code this feature must MIGRATE, never part of a proof (`TESTING.md`)
- `specs/standards/docs.md` §9–§12 — the docs standard, read before writing any page
