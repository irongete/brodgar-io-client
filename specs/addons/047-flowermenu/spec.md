# 047-flowermenu — Spec

## What & why

The radial right-click menu is the game's main context gesture and the API can only **guess** at it:
`hafen.act():flower(label)` fires a label blind, there is no way to know a menu is open, no way to read
what it offers, and no way to know which object it belongs to. This ships the missing half as its own
section — `hafen.flowermenu()` — with the two events an automation addon actually reacts to.

The section is **the open menu**, not a wrapper around one: a petal set is frozen from the moment the
menu opens until it dies, so `:list()` hands back plain **labels** rather than entities (the live-object
machinery would have nothing to track). Its members are addressed by label or by **position**, which is
real identity here — the position is the `num` that goes to the server and the `1`..`9` key the client
already accepts, unlike `hafen.menugrid()`, where a position is an artefact and is refused.

## Acceptance criteria

- [ ] With no menu open, `hafen.flowermenu():list()` is an empty array and `:count()` is `0`; neither
      throws, at any time, including before the world loads.
- [ ] Right-clicking a gob fires **`FlowerMenuOpened`** exactly once, its payload the petal captions as
      strings, in ring order, matching what is on screen; `hafen.flowermenu():list()` inside the handler
      returns the same array.
- [ ] Picking a petal fires **`FlowerMenuClosed`** exactly once with that petal's label; Esc or a click
      away fires it once with `nil`. **Every `Opened` is followed by exactly one `Closed`**, including
      when the menu dies without a choice (relog, server destroy).
- [ ] The Kin window's right-click menu — a client-side menu with no server id — fires both events too.
- [ ] Inside `FlowerMenuOpened`, `hafen.flowermenu():gob()` answers the **Gob that was right-clicked**
      (`:res()` matches the object clicked), and answers `nil` for a menu opened from an inventory item,
      for the Kin window's menu, and whenever any other click intervened.
- [ ] `:select(label)` (case-insensitive) and `:select(n)` (1-based) pick that petal, exactly as a click
      on it does — including the client-side "Mute voice" petal, which toggles locally and chooses no
      server petal. `:cancel()` closes the menu with nothing chosen, exactly as Esc does.
- [ ] `:select` and `:cancel` called by an addon that did not declare `"permissions": ["actions"]` raise
      naming the verb; `:list`, `:count`, `:gob` and both events are ungated.
- [ ] `:select` raises — naming the labels that ARE open — when nothing matches, when the index is out of
      range, and when no menu is open; so does `:cancel()` with no menu open.
- [ ] `hafen.act():flower(label)` still exists and still behaves exactly as it does today.
- [ ] `ant hafen-client` → `BUILD SUCCESSFUL`, and a full client restart is done before verification.
- [ ] Each task ships its self-checking addon per `specs/addons/TESTING.md`; its run is all
      `[pass]` (plus any `[manual]` line the maintainer confirms) and every prior suite still is.

## Out of scope

- **Retiring `hafen.act():flower(label)`** — maintainer directive: the two doors coexist for now, and the
  retirement is a separate task the maintainer has already planned. This feature only adds a pointer to
  the new section from `act.md`, the way `act():menu` already points at `hafen.menugrid`.
- Adding, injecting or removing petals from Lua; blocking a menu **before** it opens (the server opened
  it — the answer is `:cancel()`, which is an action, not a `preventDefault`).
- `:item()` — which inventory item opened the menu. Same token mechanism, not this feature.
- Two menus open at once: the section answers for the first in tree order and says so on the page.
- `hafen.menugrid()`, the `menu` selector role and the `menu`/`panel` style keys — all untouched.

## Context files

- `design/09-events-catalog.md` — the catalogue row this finally builds (`MenuOpened`, phase 2)
- `design/25-uniform-api.md` — the grammar a new section must be spelled in
- `src/haven/FlowerMenu.java` — the whole feature's seam: `added()`, `uimsg`, `choose`, `Petal`
- `src/haven/MapView.java` — `Click.hit` (~:2116), where the clicked Gob is known
- `src/haven/UI.java` — `mousedown` (~:893): `lcc` moves on **every** press; the token's correlator
- `src/haven/BuddyWnd.java` — `~:427`: the client-side menu that proves the seam is the widget
- `src/haven/VoiceTarget.java` — the shipped precedent for attributing a click to a menu
- `src/io/brodgar/addon/ActApi.java` — `openFlower`/`actFlower`, and where `clickGob` records the token
- `src/io/brodgar/addon/AddonManager.java` — the closed event-name set (~:890) and the `fire*` shape
- `docs/addons/api/menugrid.md` — the closest sibling: a section that IS its collection
- `docs/addons/api/act.md`, `api/event.md`, `api/conventions.md`, `api/README.md`,
  `docs/addons/README.md`, `docs/addons/examples.md` — the pages this feature edits
- `specs/codebase/network.md`, `specs/codebase/widget-input.md` — the two subsystem files to extend
- `specs/addons/learnings/actions-gated.md` — `4e`: the grab, and today's match/return rules
- `023-menugrid-oop/` — prior art: the other menu section, and why it refuses a positional key
