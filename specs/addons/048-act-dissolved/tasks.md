# 048-act-dissolved — Tasks

<!-- No line ceiling on spec.md / plan.md / tasks.md for this feature (maintainer directive,
     2026-08-09): each task runs under `/implement` in a CLEAN CONTEXT, so this file carries the
     detail that would otherwise have to be re-derived. Do not "fix" the length. -->

> One task = one session. Each ships its own suite (`specs/addons/TESTING.md`) and is verified
> **alone** (**D-085**): a task's verification never names another addon — not an older suite, not
> `walker`. Porting the example addons is a task's *maintenance duty*, never part of its proof.

## Rules every task in this feature follows

- **`rm -rf build/classes` before `ant hafen-client`.** This feature moves symbols between files
  nine times; the incremental build false-greens exactly there.
- **The suite never declares `permissions`** — a write-declaring addon is disabled by default and
  drops out silently. A protected verb is therefore proven **by its refusal**, and the *firing* demo
  is a `[manual]` **`:lua` one-liner**. That works because the REPL owner declares every permission
  (`Manifest.internal`: *the trusted operator console*), so the maintainer needs no addon at all.
- **`hafen.act()` stays mounted until 048.7** (**D-117**). Each task deletes only its own verbs and
  adds their `Retired` rows; the section is removed once empty. No verb ever works under both names.
- **Gate before anything else.** `AddonManager.requireActions(owner, "<verb>")` runs *before* the
  argument checks and before the live-object lookup — the order 047.2 settled (**D-213**), so an
  undeclared addon gets the permission error rather than "not in the world yet".
- **Refusal texts name the verb and the fix**, matching the shipped style:
  `"hafen.player():move: no map view (not in the world yet)"`.
- **Rename the `gated` prose** in every file the task touches (048.7 sweeps the rest).
- **Port the example addons the task breaks** — `walker` (35 `hafen.act` sites) and, in 048.2 only,
  frozen `hello`. `TESTING.md` sanctions touching `hello` when a change breaks it: *fix it, do not
  grow it*.
- **Dry-run the suite headlessly** before handing over (the 033.3-style probe,
  `learnings/testing-tooling.md`): check `owner.error == null` and the command is in
  `owner.slashCommands`. A retired-name throw at addon **file scope** kills the whole addon and
  surfaces in-game only as a bare "no such command" (042.11).

---

## 048.1 — `hafen.player():move(p)` and `gob:click(button, mods)` ✅ 13/13 pass, 0 fail, 2 manual confirmed

The pattern task: the next five copy its shape. Two verbs onto handles that already exist, so
nothing is invented and the whole cost is the move plus its retirement.

> **Closed 2026-08-10.** Both verbs ship, both refuse an undeclared addon naming the verb, both old
> spellings throw naming their replacement, and the rest of `hafen.act()` still answers (D-117 held).
> Three things the next tasks inherit: **`Retired.act(verb, msg)` writes BOTH field-read spellings** —
> the colon one (`hafen.act():moveTo`) is the only one a shipped addon reaches, and it had no coverage
> at all (**D-216**); **`moveClickCoord` was NOT moved** — `rc.floor(OCache.posres)` inline is what
> 048.2/048.4 already spell, so it stays in `ActApi` for `itemactArgs`/`placeArgs` and dies with them,
> while `clickGobArgs` did move; and **`gob:click` THROWS on a departed gob** where every read on that
> handle answers nil and `gob:scale` is inert — a write that goes on the wire about one specific object
> has nothing honest to send when the object has left (**D-217**), which is the same rule 048.3's four
> item verbs apply to a stale Item. The headless probe reached 11 of the 13 checks (**D-213**'s gate
> order means a refusal needs no session), so only the two world-dependent lines were unknown in-game.

**Build**
- `hafen.player():move(p)` — walks to a Position. `p` is required and must be a Position
  (`LuaPosition.worldArg(a, 2, "hafen.player():move", "p")` refuses a `{x, y}` table and a
  widget pixel pair on its own). Throws with no map view. Returns the Player so it chains.
- `gob:click(button, mods)` — `button` optional, default `1` (1 = left, 3 = right / the ring);
  `mods` optional, default `0`. Throws when the gob is gone (`gob:exists()` is false) or has no
  position yet.

**Where**
- `CharApi.java` — the `methods` table built around `:895`, beside `worldToScreen`. **The Player's
  vocabulary is CLOSED** (`Retired.closedIndex("hafen.player()", methods, "…")` at `:936`): the hint
  string listing `:gob() :name() :worldToScreen(p)` **must gain `:move(p)`**, or an unknown verb
  will report a stale vocabulary (**D-125**).
- `LuaGob.java` — `methods(final Addon owner)` at `:167`; it already takes the owner, so
  `requireActions` is available without threading anything.
- Move the bodies of `ActApi.actMoveTo` / `actClickGob` (and the pure builders `moveClickCoord`,
  `clickGobArgs`) with them; keep the builders pure and testable where they land.

**Wire** (unchanged — this task moves doors, not messages)
- `move` → `mapview.wdgmsg("click", pc, moveClickCoord(x, y), 1, 0)`, where `pc` is the current
  mouse (`m.ui.mc`, else `Coord.z`) — a dummy, exactly as `MiniMap.mvclick` does, which is what makes
  an off-screen destination legal.
- `click` → `mapview.wdgmsg("click", pc, gobRc, button, mods, 0, gobId, gobRc, 0, -1)` —
  `clickGobArgs`, the `Gob.GobClick.clickargs` tail meaning *the whole object, no sub-mesh*.

**Retire** (`Retired.java`)
- `hafen.act.moveTo` → *"hafen.act():moveTo(p) is now hafen.player():move(p) — the verb lives on the
  character it moves"*
- `hafen.act.clickGob` → *"hafen.act():clickGob(gob, button, mods) is now gob:click(button, mods)"*

**Port** — `walker`'s `moveto` and `click` sub-commands.

**Suite proves**
- `hafen.player():move` and `gob:click` exist and are functions.
- Both **refuse this (undeclared) addon**, and the error names the verb.
- `hafen.act():moveTo` and `hafen.act():clickGob` throw naming their replacements; the *rest* of
  `hafen.act()` is still callable (the coexistence D-117 promises this mid-feature).
- `hafen.player():move` refuses a `{x = …, y = …}` table and a number pair — the refusal reaching
  the caller before the gate is checked is **not** what we want, so assert the permission error is
  what comes back for this addon even with a bad argument.
- `hafen.player():nosuchverb()` still throws listing a vocabulary that now includes `:move`.

**`[manual]`**
- `:lua hafen.player():move(hafen.player():gob():position():offset(0, -20))` — expect: you walk
  about 20 units north; an off-screen destination works too.
- `:lua hafen.world():gob():nearest("terobjs/tree"):click(3)` — expect: that tree's radial menu opens.

---

## 048.2 — the Hand: `hafen.player():hand()`, `:item()`, `:use(target, mods)` ✅ 29/29 pass (9 + 15 + 5), 0 fail, 6 manual confirmed

The only task that mints an object, and the one that closes a capability gap.

> **Closed 2026-08-10.** The Hand ships as a **per-addon singleton** (the `LuaMouse`/Player shape, cached on
> `Addon.handObj`): it wraps no engine value and re-reads `GameUI.vhand` per call, so `==` is free and there is
> nothing to tear down. `hand:use` dispatches by type onto an Item, a Position or a Gob, the gate first
> (**D-213**), and a Hand held across a drop follows **D-217** — `:item()` answers nil, `:use` raises. The move
> is recorded as **D-218**: *a gesture the client itself cannot produce is not a capability to preserve*, and
> the receiver must BE the message's implicit subject and be absent whenever the subject is.
> Four things the next tasks inherit. **`hand:item() == theInventoryItem` across a take is FALSE** — this
> task's own *Suite proves* line above claimed it, and the engine disagrees: `GameUI.addchild` place `"hand"`
> builds a **new** `GItem` and the container's is destroyed, so the claim was written from the API's shape
> rather than the engine's. 048.3 must not repeat it for its stale-Item checks. **Every argument refusal on a
> protected verb is invisible to its own suite** — the gate is first, so no-target / `42` / `"x"` all answer
> the permission error; assert *that*, and leave the argument message to a `[manual]` `:lua` line. **An
> undeclared suite can still assert the WIRE**: `hafen.event():action():on(msg, fn)` records the resolved
> arguments as they go out, so `:t048-2 sent` proved the gob form carried `Gob.GobClick.clickargs` verbatim —
> 048.4's `place`/`sel` and 048.6's `send` can do the same instead of ending on an eyeball. And **a `[manual]`
> line must not be aimed by a resource filter**: `nearest("terobjs/plants")` came back nil in round one, so
> the headline capability reported *target is required* and read exactly like a defect.

**Build**
- `hafen.player():hand()` → a **Hand**, or **`nil`** when nothing is on the cursor (mirroring
  `GameUI.vhand == null`). Interned per addon like every other entity, so `==` works.
- `hand:item()` → the Item on the cursor — the *same interned object* a container's `:items()`
  hands back, so `hand:item() == thatItem` holds.
- `hand:use(target, mods)` → apply what you are holding. `mods` optional, default `0`.
  `target` dispatches **by type**:
  - **Item** → `targetItem.wdgmsg("itemact", mods)`
  - **Position** → `mapview.wdgmsg("itemact", pc, mc.floor(OCache.posres), mods)`
  - **Gob** → the same, **plus `ClickData.clickargs()`**: `{pc, mc, mods, 0, gobId, gobRc, 0, -1}`
- **`hand:use()` with no argument RAISES**, naming the three target types, and must **never** fall
  back to *activate the held item*. That reading has no message behind it, and `hand:item():use()`
  is the one way to say it.
- Retire `hafen.ui():hand()`.

**Why the verb is here and not on Item** — `itemact` is dispatched through `DTarget.Interact`
(`DTarget.java:71`), an `ItemEvent` whose `src` **is** the `ItemDrag`. The message carries no
reference to the held item because in the client the gesture *originates* from it. On an arbitrary
Item, `someInventoryItem:use(p)` would send an action about whatever is on the cursor instead. The
Hand is the one receiver for which "I am the cursor" is true — and its `nil` is what finally makes
the gesture guardable, where `act():useItemOn` sent blind.

**Where**
- New `LuaHand.java`, modelled on `LuaGob`'s per-owner metatable (`buildMeta(final Addon owner)` →
  `methods(owner)`, `Retired.methodIndex("hand", …)`).
- `CharApi.java` — `hand` added to the Player methods table **and to the closed-vocabulary hint**.
- `UiApi.java:348` — `hafen.ui():hand()` deleted; its `g.vhand.item` read is what `LuaHand` wraps.
- Delete `ActApi.actUseItemOn` / `itemactArgs` and the `"itemact"` arm of `itemVerbArgs`.

**Retire**
- `hafen.act.useItemOn` → *"…is now hafen.player():hand():use(p) — nil when nothing is held"*
- `hafen.ui.hand` → *"hafen.ui():hand() is now hafen.player():hand():item()"*
- `hafen.act.item`'s row is written by 048.3; this task leaves `act():item` working for its other
  four verbs and makes `"itemact"` alone throw naming `hand:use(item)`.

**Port**
- `walker` — its `item itemact` demo.
- **Frozen `hello`, two live call sites**: `:209` (`local hand = hafen.ui():hand()`) and `:806`
  (`tostring(hafen.ui():hand() and "item" or nil)`). Fix those two lines and the neighbouring
  comment at `:199`; change nothing else in that file.

**Suite proves**
- `hafen.player():hand()` is `nil` with an empty cursor, and does not throw.
- `hafen.ui():hand()` throws naming its replacement.
- `hand:use` refuses this undeclared addon, naming the verb.
- With a `[manual]`-armed cursor: `hand:item()` is a live Item whose `:res()` answers, and it is
  `==` the object `hafen.ui():inventory():items()` handed back before the take.
- `hand:use()` with **no argument** raises, and the message names Item, Position and Gob.
- `hand:use(42)` and `hand:use("x")` refuse.

**`[manual]`**
- Take a waterskin onto the cursor, then
  `:lua hafen.player():hand():use(hafen.world():gob():nearest("terobjs/plants"))` — expect: the held
  item is applied **to that plant**, which no call could target before this task.
- With the same item held, `:lua hafen.player():hand():use(hafen.player():gob():position())` —
  expect: it is applied to the ground under you.
- Drop the item, then `:lua hafen.log():write(tostring(hafen.player():hand()))` — expect: `nil`.

---

## 048.3 — the Item verbs: `item:use(mods)`, `:take()`, `:drop(n)`, `:transfer(n)`

**Build**
- `item:use(mods)` — the `iact` gesture: activate it (eat, open, light). `mods` optional, default `0`.
- `item:take()` — pick it up onto the cursor, or unequip a worn item. **No arguments.**
- `item:drop(n)` / `item:transfer(n)` — `n` optional, default `-1` (the whole stack).
- All four return the Item so they chain, and all four refuse a **stale** item (moved, used,
  consumed) with the existing guiding error — nothing is sent, because an item that has left is not
  the item that took its place.
- `act():item` is deleted whole, with its five verb strings.

**No `mods` on take/drop/transfer, and this is not an oversight.** Those three messages carry no
modifier field at all: in the client the modifier keys select the *count* — `WItem.mousedown:176`,
shift = transfer 1, shift+ctrl = transfer all, ctrl = drop 1, ctrl+meta = drop all. `n` states that
directly, so `item:drop(n)` **is** the modifier. Only `iact` (`{cc, ui.modflags()}`) and `itemact`
(`{ui.modflags()}`) carry one. Do not unify these.

**Where — the one structural change**
`LuaItem.methods()` is **owner-less** (`:153`), unlike `LuaGob.methods(owner)`, so `requireActions`
has nothing to check against. `LuaItem.Cache(Addon owner)` at `:93` already *receives* the owner and
**discards it** (empty constructor body). Store it and thread it: `Cache(owner)` → `meta()` →
`buildMeta(owner)` → `methods(owner)`. That is the whole plumbing, and it mirrors `LuaGob` exactly.

**Wire** — `item.wdgmsg(verb, args)`, the grab coord being `Coord.z` (the item's own corner: a
faithful, deterministic substitute for a programmatic action):
`take {Coord.z}` · `drop {Coord.z, n}` · `transfer {Coord.z, n}` · `iact {Coord.z, mods}`.

**Retire**
- `hafen.act.item` → *"hafen.act():item(item, verb, n) is gone: the verbs are on the item —
  item:use(mods) (was \"iact\"), item:take(), item:drop(n), item:transfer(n), and
  hafen.player():hand():use(item) (was \"itemact\")"*

**Port** — `walker`'s `item` sub-command (all five verbs).

**Suite proves**
- All four exist on an Item from `hafen.ui():inventory():items()` and are functions.
- Each refuses this undeclared addon, naming the verb.
- `hafen.act():item` throws, and the message names all five replacements.
- A **stale** Item (kept across a `[manual]` move) still answers `:res()`/`:name()`, reports
  `:exists() == false`, and its four verbs refuse **naming staleness, not the gate** for a declared
  caller — for this addon assert the gate error, and assert `:exists()` went false.
- `item:take(1)` refuses an argument it does not accept.

**`[manual]`**
- `:lua hafen.ui():inventory():items()[1]:take()` — expect: that item lifts to your cursor.
- `:lua hafen.player():hand():item():drop()` — expect: it drops at your feet.
- `:lua hafen.ui():inventory():items()[1]:use()` — expect: its right-click action fires (open, eat, …).

---

## 048.4 — `hafen.world():place(p, angle, button, mods)` and `:select(p1, p2, mods)`

The world's first protected verbs. `place` lands beside the `snapPlace`/`snapAngle` that exist to
prepare its arguments and today sit a page away from it.

**Build**
- `place(p, angle, button, mods)` — `p` a Position, `angle` in **radians** (required, a number),
  `button` optional default `1` (confirm), `mods` optional default `0`. With nothing being placed the
  server ignores it, and nothing comes back to say so — say that on the page.
- `select(p1, p2, mods)` — the tile rectangle spanned by two Positions; `mods` optional default `0`.

**Why `place` is here and not on the Hand** — placement is **server-initiated**
(`MapView.uimsg("place", …)` builds a `Plob`, `:1946`) and is sent from `mousedown` with **no held
item involved** (`:2164`), while `itemact` goes through `iteminteract`, which requires one. Two
different gestures. Making `place` guardable would mean reading the **private** `MapView.placing` —
a `haven` edit this feature deliberately does not take (spec.md, *Out of scope*).

**Where** — `WorldApi.java`, in the `m` table beside `snapPlace` (`:175`) and `snapAngle` (`:187`).
Move `ActApi.placeArgs` / `placeAngle` / `selArgs` with the verbs; they are pure and stay pure.

**Wire**
- `place` → `mapview.wdgmsg("place", rc.floor(OCache.posres), round(angle * 32768 / PI), button, mods)`
  — the angle encoding is the server's, **not** raw radians.
- `select` → `mapview.wdgmsg("sel", tc1, tc2, mods)`, corners floored to **tile** coords
  (`Coord2d.floor(MCache.tilesz)` — the same conversion `p:tileCoord()` exposes).

**Retire**
- `hafen.act.place` → *"…is now hafen.world():place(p, angle, button, mods), beside the
  hafen.world():snapPlace(p) that prepares it"*
- `hafen.act.select` → *"…is now hafen.world():select(p1, p2, mods)"*

**Port** — `walker`'s `place` and `select` sub-commands.

**Suite proves**
- Both exist and refuse this undeclared addon, naming the verb.
- Both old spellings throw naming their replacements.
- `place` refuses a missing `angle` and a non-number one; both refuse a non-Position.
- `hafen.world():snapPlace(p)` and `:snapAngle(a)` still answer unchanged beside them (this task's
  own premise: the neighbours it is joining did not move).

**`[manual]`**
- Start building something so an object is on your cursor, then
  `:lua hafen.world():place(hafen.world():snapPlace(hafen.player():gob():position()), 0)` —
  expect: it lands snapped to the tile grid, facing north.
- `:lua local p = hafen.player():gob():position(); hafen.world():select(p:offset(-11,-11), p:offset(11,11))`
  — expect: a ~3×3 tile area selection appears around you.

---

## 048.5 — `pag:use()` becomes protected, and `act():menu` is deleted

**No path door is built.** `hafen.menugrid()` addresses the entries it holds — by resource name
(`"paginae/act/dig"`) or by display name (`"Dig"`) — and `hafen.menugrid():get(name):use()` is
already the way to invoke one. **023-menugrid-oop** absorbed the mechanism; `act():menu` is the old
door left open, the same **D-103** obligation as `act():flower`, so it goes the same way. The
pagina-path address space is dropped with it, by maintainer directive (spec.md, *Out of scope*).
Do not resurrect it under any spelling.

**Build**
- **`pag:use()` gains the gate it never had.** It performs a real server action and is **ungated
  today** — `docs/addons/api/menugrid.md` says so in bold, and that line goes with this change. This
  is the task's whole deliverable, and it is a behaviour change to a shipped verb.
- `pag:use()` keeps taking **no arguments**: it goes through the client's own
  `MenuGrid.PagButton.use`, which reads `ui.modflags()` itself at the instant of the call, so a
  `mods` parameter could only lie about the keys physically held. Do not "align" it with the other
  verbs in this feature.
- Delete `ActApi.actMenu` and the pure `menuPath` builder with the verb; nothing else uses them.

**Where** — `LuaPagina.java`, the `use` verb; gate it exactly as `LuaSlot.java:251` does
(`AddonManager.requireActions(owner, "pag:use")` first, before the live-entry lookup). The
`hafen.menugrid()` **reads** stay ungated — the gate goes on the write half only.

**Before shipping**: grep the installed addons for `:use()` on a Pagina and confirm every caller
declares the permission. Only `walker` uses it today, and it declares — verify, do not assume.

**Retire**
- `hafen.act.menu` → *"hafen.act():menu(path...) is gone: a menu action is invoked through the entry
  itself — hafen.menugrid():get(\"Dig\"):use(), or get(\"paginae/act/dig\"):use() by resource name.
  There is no path-based door."*

**Port** — `walker`'s `menu` sub-command is **removed**, not re-spelled (it demonstrated the path
door). Its `menugrid` sub-command stays and is what the docs point at.

**Suite proves**
- `pag:use()` refuses this undeclared addon, naming the verb — the behaviour change this task ships,
  so it is asserted directly on an entry from `hafen.menugrid():list()`.
- The menugrid **reads** (`:list()`, `:count()`, `:get(name)`, `pag:name()`, `pag:res()`,
  `pag:exists()`) still answer for this same undeclared addon in the same run: the gate landed on the
  write half only, which is the claim that makes the change safe.
- `hafen.act():menu` throws, and the message names `hafen.menugrid():get(name):use()`.
- `hafen.menugrid():use` does **not** exist (`== nil`) — the path door was considered and refused, so
  assert its absence rather than leaving it to be re-added by a future reader.

**`[manual]`**
- `:lua hafen.menugrid():get("Dig"):use()` — expect: the dig action arms exactly as clicking that
  menu button does, and the modifier keys you are physically holding are the ones that apply.

---

## 048.6 — `widget:send(msg, ...)`, and the death of the target tokens

**Build**
- `widget:send(msg, ...)` — send an arbitrary `wdgmsg` from a **bound** widget. `msg` must be a
  string. Trailing arguments marshal exactly as today (`LuaMarshal.toJava`: a `{x=, y=}` table
  becomes a Coord; numbers, strings and booleans pass through). Returns the Widget so it chains.
- **Refuses an unbound widget** — one with no server id (a widget an addon created) — naming why,
  rather than letting the engine throw. `widget:id()` is the read that answers the same question.
- `act():raw` is deleted **with its whole target vocabulary**: the numeric-id branch, the
  `"mapview"`/`"gameui"`/`"root"` tokens, `ActApi.rawTarget`, and `HookApi.isKnownTarget` /
  `hookTarget` if nothing else uses them (check — the hook targets may still be needed by `HookApi`
  itself; delete only what becomes unreachable).

**Why the tokens can just go** — both are already reachable as ordinary selectors.
`hafen.ui():find("@GameUI")` is documented as the HUD, and `@Class` resolves through
`LuaWidget.typeName`, which is `getSimpleName()`; `MapView` carries `@RName("mapview")` and is **not
subclassed** in this fork, so `hafen.ui():find("@MapView")` reaches it. Verified, not assumed.

**Where** — `LuaWidget.java`, the `m` table of 34 verbs (`:220` onward); `send` beside `destroy`.

**Retire**
- `hafen.act.raw` → *"hafen.act():raw(target, msg, ...) is now widget:send(msg, ...) — the receiver
  is the target; \"mapview\" and \"gameui\" are hafen.ui():find(\"@MapView\") / find(\"@GameUI\")"*

**Port** — `walker`'s `raw` sub-command.

**Suite proves**
- `hafen.ui():find("@MapView")` and `find("@GameUI")` both resolve to a widget whose `:id()` is a
  number (i.e. bound) — this is the claim that lets the tokens be deleted, so it is asserted here.
- `send` refuses this undeclared addon, naming the verb.
- On a widget the suite creates itself (unbound), `send` refuses **naming that it is not bound**, and
  that refusal is distinguishable from the permission one.
- `send` refuses a non-string `msg`.
- `hafen.act():raw` throws naming its replacement.

**`[manual]`**
- `:lua hafen.ui():find("@MapView"):send("click", {x=0,y=0}, {x=0,y=0}, 1, 0)` — expect: sent without
  error. (The destination is arbitrary; this proves the door, not the aim.)

---

## 048.7 — the two deletions, and the end of the section

**Build**
- **Delete `act():flower`.** Not moved: `hafen.flowermenu():select(label|n)` already supersedes it
  and is strictly better — it raises where `flower` returned `false`, takes a ring position as well
  as a caption, and has `:cancel()` beside it. `actFlower` already delegates to `FlowerMenuApi.open()`
  / `.names()`, so what is left is the old door **D-103** requires closing. Delete `actFlower`,
  `flowerPetalIndex` and the `haven.FlowerMenu` import from `ActApi`.
- **Delete `act():enabled`.** `AddonManager.actionsGranted(owner)` is literally
  `owner.manifest.usesActions()` — a static fact about the caller's own manifest — and D-028 already
  removed the global switch it was built to report. Remove `actionsGranted` too if nothing else calls
  it (`requireActions` does not). The permission model itself is untouched.
- **Delete `ActApi.installAct` whole**, and the `Section.install(hafen, "act", act)` line.
  `installCraft` and `installSpeed` **stay** in the file, along with `makewindow()`, `speedget()`,
  `speedName` and `actSpeedSet`.
- **Add the section-level retirement.** `Retired` keys a retired *section* as `"hafen.act"` off the
  `hafen` table's own `__index`; without it `hafen.act()` fails later as *"attempt to call a nil
  value"*, naming neither the verb nor the file.
- **Finish the `gated` → `protected` sweep** across `src/io/brodgar/` — group headings, Javadoc,
  comments, and the `Section.install` retirement hints. Identifiers keep their names
  (`requireActions`, `actionsGranted`, `declaresActions`, `ActionsConsentWnd`): the permission is
  still called `"actions"`, which is not the word being renamed.

**Retire**
- `hafen.act` (section) → *"hafen.act() is gone: every verb moved to what it changes —
  hafen.player():move / :hand():use, gob:click, item:use/:take/:drop/:transfer,
  hafen.world():place/:select, hafen.menugrid():get(name):use(), widget:send. hafen.act():flower is
  hafen.flowermenu():select(label|n); hafen.act():enabled() is gone — a running addon that declared
  \"actions\" is granted."*
- `hafen.act.flower` and `hafen.act.enabled` get their own verb rows too, so a call that reaches the
  verb name gets the specific message rather than only the section one.

**Port** — `walker`'s `flower` and `enabled` uses, including the `EnterWorld` banner at `:51`.

**Suite proves**
- `hafen.act` throws **as a section** when called, and the message names the replacements.
- **All ten verb names** throw individually, each naming its own replacement — the full row sweep
  in one assertion loop, which is the check that makes the retirement table's coverage mechanical
  rather than remembered.
- `hafen.flowermenu():select` and `:cancel` exist and refuse this undeclared addon; `:list()`,
  `:count()` and `:gob()` still answer (047's premise, re-asserted here because this task removes
  the other door).
- `hafen.craft():current()` and `hafen.speed():current()` still answer — `ActApi`'s surviving halves,
  asserted because this task edits that file heavily.

**`[manual]`** — none needed: everything here is a deletion, and deletions are fully assertable.

---

## 048.8 — the docs tier

The largest single chunk, and it cannot start until every new name is real.

**Read the standard before writing** (`AREA.md`): `specs/docs/design/style-guide.md` **§9–§12** and
the one-liners from `grep "^### D-" specs/docs/decisions/docs-standard.md`. **§12 is the checklist
this task runs and reports.**

**Build**
- **Delete `docs/addons/api/act.md`.** It is the page **19 others link to** for one sentence — *see
  `hafen.act` for the permission* — so the permission gets its new home **first**:
  - `api/conventions.md` §*Gating: the actions permission* → §*The actions permission*: the canonical
    short statement, renamed to *protected*, and the target every page now points at.
  - `guides/actions-and-permissions.md` — the long form, already structured around exactly this.
- **The verbs move to their pages**: `player.md` (a write section + the Hand), `gob.md` (`:click`),
  `world.md` (a protected group: `place`, `select`), `ui/items.md` (the four item verbs; its
  closing pointer at *"the gated `hafen.act():item`"* goes), `menugrid.md` (its **ungated** claim on
  `pag:use()` removed — the bolded note and the *"Use (ungated)"* heading both), `ui/widget.md`
  (`:send`).
- `api/README.md` loses the `act` row; the top `docs/addons/README.md` "API at a glance" is updated.
- **`gated` / `gating` appear nowhere under `docs/`** — 117 occurrences across 58 files.
- `docs/addons/examples.md` — `walker`'s paragraph, now that it demonstrates the new names.

**Suite proves** (a docs task still ships one)
- Every `hafen.*` name the pages now claim **resolves live**: the name sweep §12 requires, run as
  assertions rather than eyeballed.
- No page names a retired spelling: assert each retired name throws, so a doc that still teaches one
  is caught by the suite rather than by a reader.

**`[manual]`** — the §12 checklist items a program cannot do: links and anchors falsified **both
ways**, `wc -l` ≤ 300 per page, heading levels. Report the checklist explicitly, as §12 requires.
