# 047-flowermenu — Plan

## Approach

**The section.** A new `FlowerMenuApi` mounted `Section.mount(hafen, "flowermenu", …)` beside `menugrid`.
The open menu is found the way `ActApi.openFlower()` already finds it — the first `FlowerMenu` in a
recursive `ui.root.children(FlowerMenu.class)` walk — and that finder **moves into the new class**, with
`ActApi` calling it, so one mechanism keeps one door (D-103). `:list()` takes **no filter**: its members
are bare strings with no `name` field to match on, and a string argument would read as a label; it raises
naming `:select`. `:count()` is its arity sibling. All three reads answer with no menu open (empty, `0`,
`nil`) and before the world loads.

**The events.** Two new `BUS_KEYS` in `AddonManager` plus a `fireFlowerMenu` helper on the `fire*` pattern
(payload built only when `hasSub`). Three `// addon:` seams in `FlowerMenu`, and the choice of seam is the
design:

| Seam | Fires | Why there |
|---|---|---|
| end of `added()` | `FlowerMenuOpened`, payload = petal names | the **only** point where the set is complete: `addVoicePetal()` replaces `opts` two lines earlier |
| `uimsg("act"/"cancel")` | `FlowerMenuClosed`, payload = label or `nil` | the commit point, and the **one** seam a client-side menu also takes — `BuddyWnd` calls `uimsg` by hand |
| `destroy()` (new override) | `FlowerMenuClosed` with `nil`, **only if not already fired** | the fallback that keeps *every Opened gets exactly one Closed* true when the widget just dies |

A fourth line at the head of `choose(Petal)` records the petal being chosen, so the **client-side** voice
petal — which cancels the server's menu and handles itself — reports its own label instead of reading as
"nothing chosen". `uimsg("act")` still takes its label from `opts[num]`, which is authoritative.

**`:gob()`.** A package-private `ClickToken` (the shape `VoiceTarget` already proves, made exact):
`note(gobId, lcc)` at the click, `take(lcc)` at `added()`, consume-once, with a short time window as a
backstop. The correlator is `UI.lcc`, which moves on **every** mouse press before dispatch, so any other
interaction between the click and the menu self-invalidates the token — an item-opened menu, the Kin
window's menu and a stale token all answer `nil` without enumerating what else can open a menu. Recorded
from two places: one `// addon:` line in `MapView.Click.hit` (beside the existing `VoiceTarget.note`) and
`ActApi.actClickGob`, which is addon code and knows its gob for certain. The resolved id is held beside
the open menu and cleared when it closes.

**`hafen.act():flower` is untouched.** `act.md` gains one pointer line, the way `act():menu` already
points at `hafen.menugrid`.

## Files to create / modify

- **create** `src/io/brodgar/addon/FlowerMenuApi.java` — the section, the finder, the open-menu state
- **create** `src/io/brodgar/addon/ClickToken.java` — the `(gob, lcc)` token
- **create** `docs/addons/api/flowermenu.md` — the page (docs standard read BEFORE writing: `style-guide.md`
  §9–§12 + `grep "^### D-" specs/decisions/docs-standard.md`)
- **create** `addons/047-flowermenu.1/`, `.2/`, `.3/` — one self-checking suite per task
- **modify** `src/haven/FlowerMenu.java` — 4 `// addon:` lines (`added`, both `uimsg` branches, `choose`)
  \+ a `destroy()` override
- **modify** `src/haven/MapView.java` — 1 `// addon:` line in `Click.hit`
- **modify** `src/io/brodgar/addon/AddonManager.java` — `BUS_KEYS` + `fireFlowerMenu` + the mount
- **modify** `src/io/brodgar/addon/ActApi.java` — call the shared finder; record the token in `clickGob`
- **modify** `docs/addons/api/README.md`, `docs/addons/README.md` (glance table), `docs/addons/api/event.md`
  (two rows), `docs/addons/api/act.md` (the pointer), `docs/addons/examples.md` (walker's new command)
- **modify** `addons/walker/` — the firing demo for `:select`, beside its existing `act():flower` one
- **extend (coverage paid at `/end`)** `specs/codebase/network.md` — the `"sm"` widget's own lifecycle
  (`added`/`uimsg`/`choose`/the two closing anims, and the client-side `BuddyWnd` menu that sends no `cl`);
  `specs/codebase/widget-input.md` — `UI.mousedown` sets `lcc` on every press, before dispatch

## Risks & gotchas

- **An open menu grabs mouse AND keyboard** (`added()`), so `:t047-x` cannot be typed while one is up
  (`learnings/actions-gated.md` §4e). Every suite arms handlers first, prints its `[manual]` step, and
  lets the handler assert and print — the summary closes on a timer or when both events have been seen.
- **A suite may never declare `permissions`** (`TESTING.md`), so the gated half is proven by asserting the
  **refusals**; the firing demo is `walker`'s and a `[manual]` line.
- **Never re-encode `wdgmsg("cl", num)`** — `choose(Petal)` is the door (D-009, §4e), or the client-side
  voice petal is wrongly sent to the server.
- **`choose` is not the close seam.** `BuddyWnd`'s subclass overrides it and never calls `super`; it calls
  `uimsg` instead — which is exactly why the close hangs off `uimsg`. Its `destroy()` override *does* call
  `super.destroy()`, so the fallback seam holds. Verify both by reading the subclass before wiring.
- **The petal array is replaced during `added()`** (`addVoicePetal`), so hooking any earlier ships an
  incomplete list. This is the same class of trap as 042's D-179/D-180 second sites.
- **Reentrancy**: `Opened` fires inside `added()`, and `choose` does not check `anims` the way `mousedown`
  does — a handler that selects immediately sends `"cl"` during the opening animation. Assert it in 047.2;
  defer a tick only if it misbehaves.
- **`nil` as a bus payload** (`FlowerMenuClosed` on a cancel) — confirm the handler receives one `nil`
  argument, not zero arguments, before writing the page's guard example.
- Java 1.8 source level; `ant hafen-client` is **incremental and can false-green** when a symbol moves —
  `rm -rf build/classes` for a true compile check. Engine changes need a full client restart.
- Dry-run each suite headlessly against the stub bridge before handing over
  (`learnings/testing-tooling.md`): a throw at file scope kills the whole addon and the command silently
  never registers.

## Discarded alternatives

- **Petal objects with `petal:select()`** (the `menugrid` shape) — maintainer's call, and the reason is
  real: the set is frozen from `added()` to death and lives under a second, so live objects would track
  nothing and `:exists()` would answer a question nobody asks.
- **Closing on `choose()`** — fires before the close is committed and misses a menu the server closes on
  its own; **closing on `destroy()` alone** — 0.25–0.75 s late (`Chosen`/`Cancel` are animations) and with
  no label unless it is recorded anyway.
- **Intercepting `"cl"` through `hafen.event():action()`** instead of a section — reaches only
  server-bound menus (the Kin menu sends nothing) and hands back a petal **number**, not a label.
- **`MenuOpened`/`MenuClosed`** (`design/09`'s reserved names) — "Menu" already names `MenuGrid` in the
  selector and stylesheet vocabularies.
- **Attributing the gob by a time window alone** (`VoiceTarget`'s shape) — `lcc` equality is exact,
  self-invalidating and needs no list of what else can open a menu.
- **Retiring or aliasing `hafen.act():flower`** — maintainer directive: separate, already-planned task.
