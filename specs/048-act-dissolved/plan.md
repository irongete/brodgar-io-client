# 048-act-dissolved — Plan

<!-- Line ceiling lifted for this feature's three spec files (maintainer directive, 2026-08-09) —
     see spec.md's header for why. Do not "fix" the length. -->

## Approach

The contract is [spec.md](spec.md)'s map. This plan says how it lands over eight sessions without a
big-bang commit and without a half-migrated API that lies to its callers.

**The section stays mounted while it empties.** D-117 already settled this shape: a section whose
verbs move over several tasks is mounted with the un-moved ones still on it. So `hafen.act()` keeps
answering for whatever has not moved yet, each task removes its own verbs and adds their `Retired`
rows, and the section itself is deleted in 048.7 once nothing is left. No task ships an `act` verb
that silently still works under both names — the old spelling throws from the moment the new one
exists (D-013, one canonical way; nothing is released, so a hard cut is the area's rule).

**Every task carries four things, not one:** the verbs it moves, their `Retired` rows, the port of
every example addon it breaks, and the `gated` → `protected` prose rename in the files it touched.
048.7 sweeps whatever prose the earlier seven did not reach. Splitting the rename into its own late
task would mean seven tasks landing text they know to be wrong.

**Order is by independence, not by size.** 048.1 is two verbs onto handles that already exist and
establishes the pattern every later task copies (protected verb on a read handle, `requireActions`
at the new site, a `Retired` row, a suite that proves the refusal). The Hand (048.2) comes next
because it is the only task that invents an object, and the item verbs (048.3) read better once the
Hand exists to contrast with. The docs tier (048.8) is last because it cannot be written until every
new name is real.

**The example addons are part of the definition of done.** `walker` has 35 `hafen.act` call sites
and is the demo the docs point at; `hello` **calls** `hafen.ui():hand()` at two live sites (209,
806), not just in comments. `hello` is frozen, and this is exactly the sanctioned exception in
`TESTING.md` — *touch it only when a change breaks it, then fix it, do not grow it*. 048.2 fixes
those two lines and nothing else.

## Files to create / modify

**The verbs move into** (`src/io/brodgar/addon/`): `CharApi.java` (`player():move`, `player():hand`),
`LuaGob.java` (`:click`), `LuaHand.java` **(new)**, `LuaItem.java` (`:use`/`:take`/`:drop`/
`:transfer`), `WorldApi.java` (`:place`, `:select`), `LuaPagina.java` (`pag:use` becomes protected),
`LuaWidget.java` (`:send`), `Retired.java` (twelve rows incl. the section-level one), `UiApi.java`
(`hand()` retired), `HookApi.java` (the target-token branch, deleted with `raw`), `ActApi.java` (its
`act` half deleted whole; `craft`/`speed` stay).

**Docs tier** (048.8): delete `docs/addons/api/act.md`; rewrite `conventions.md` §*Gating* and
`guides/actions-and-permissions.md` as the permission's home; add the verbs to `player.md`, `gob.md`,
`world.md`, `menugrid.md`, `ui/items.md`, `ui/widget.md`; repoint the 19 pages linking `act.md`;
update `api/README.md` and the top `README.md` "API at a glance".

**Addons**: `addons/walker/` (ported across tasks), `addons/hello/` (two lines, 048.2), plus each
task's own suite under `addons/048-act-dissolved.<X>/`.

**Codebase coverage** (`/end` pays for the reading): extend `specs/codebase/network.md` with the row
this feature had to derive from source — **which item message carries modifiers and which encodes a
count instead** (`WItem.mousedown`: `take` `{cc}`, `drop`/`transfer` `{cc, n}` with the modifier keys
*selecting* n, `iact` `{cc, modflags}`, `iteminteract` `{modflags}`) — and the `DTarget.Interact`
fact that makes the held item the subject.

## The retirement inventory (the whole set, and who writes each row)

`Retired` is *pure data generated from the before/after inventory*, which is what makes coverage
mechanical rather than remembered — a spelling that moved with no row is a porting error nobody is
told about. So the full set lives here, in one place, and **048.7 asserts all twelve at once**
rather than the ones a task happened to touch.

| Row | Level | Written by |
|---|---|---|
| `hafen.act` | **section** | 048.7 |
| `hafen.act.moveTo` | verb | 048.1 |
| `hafen.act.clickGob` | verb | 048.1 |
| `hafen.act.useItemOn` | verb | 048.2 |
| `hafen.ui.hand` | verb | 048.2 |
| `hafen.act.item` | verb | 048.3 |
| `hafen.act.place` | verb | 048.4 |
| `hafen.act.select` | verb | 048.4 |
| `hafen.act.menu` | verb | 048.5 |
| `hafen.act.raw` | verb | 048.6 |
| `hafen.act.flower` | verb | 048.7 |
| `hafen.act.enabled` | verb | 048.7 |

A verb row and the section row coexist deliberately: reading `hafen.act` throws the section message,
and a caller who gets past that to a verb name gets the specific one.

## Risks & gotchas

- **`ant hafen-client` is incremental and will false-green.** This feature moves symbols between
  files nine times, which is precisely the case `LEARNINGS`/`CLAUDE.md` warn about. Every task runs
  `rm -rf build/classes` before its build check, not just the last one.
- **A suite must never declare `permissions`** (`TESTING.md`): a write-declaring addon is disabled by
  default and would drop out silently. So every protected verb is proven **by its refusal** — the
  gate throws naming the verb — and the firing demo is a `[manual]` **`:lua` one-liner**, which works
  because the REPL owner declares every permission (`Manifest.internal`: *the trusted operator
  console*). That keeps **D-085** intact: verification of a task names no other addon, `walker`
  included. Porting `walker` is a task's maintenance duty, never part of its proof.
- **`hafen.act` must throw as a section, not only per verb.** A verb-level row alone leaves
  `hafen.act()` failing later as *"attempt to call a nil value"*. `Retired` supports both levels;
  the section row is one line and is the difference between porting and hunting.
- **A retired throw at addon file scope kills the whole addon silently** — the slash command never
  registers and it surfaces in-game as a bare "no such command" (042.11,
  `learnings/testing-tooling.md`). `walker`'s `enabled()` call is inside an `EnterWorld` handler, so
  it throws late rather than at load; port it anyway, and dry-run each suite through the 033.3-style
  headless probe before handing over.
- **`pag:use()` gaining a gate can break an installed addon that never declared the permission.**
  Only `walker` uses it today, and it declares. Check before shipping 048.5.
- **`hand:use(gob)` is the one new message on the wire.** It must reproduce `MapView.iteminteract`'s
  arg extension exactly (`{pc, mc, mods}` + `ClickData.clickargs()`), not an invented shape.
- **Do not unify the modifier handling.** Three different rules, each correct for its message; see
  spec.md §*Modifiers*. A reviewer's instinct to align them would break `take`/`drop`/`transfer`.

## Discarded alternatives

- **Keep `hafen.act` as a thin forwarder.** Two doors onto one verb — exactly what D-103 refuses.
- **A path door on the menu grid** (`hafen.menugrid():use(path...)`). Refused by maintainer
  directive: the section addresses the entries it holds, and `get(name):use()` is already that door.
  The pagina-path address space goes with `act():menu` rather than being rehoused.
- **Move `place` onto the Hand** (with `hand:placing()`). Costs a `haven` edit over the private
  `MapView.placing`, and placement is not a cursor gesture: it is server-initiated and sent from
  `mousedown` with no held item.
- **`item:useOn(p)` / `item:use(itemRef)`.** Puts the subject in the argument and the target in the
  receiver, backwards from a wire that carries no held-item field.
- **`hand:useOn(target)` rather than `hand:use(target)`.** A compound invented to dodge an ambiguity
  the no-argument raise already handles; `use` is the API's word (`pag:use`, `slot:use`, `item:use`).
- **One "migration" task plus one "rename" task.** Seven tasks would land prose they know is wrong.
- **Renaming the `"actions"` permission string too.** Out of scope in spec.md — it would re-prompt
  consent for every already-enabled write addon unless the `addons/actions.seen` pref is migrated.
