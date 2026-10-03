# 173 — HUD regions: every element of the HUD moves with a widget's own verbs

## What & why

A HUD editor moves the client's controls with `:position`, `:draggable` and `:remember`. Four gaps stop it:

1. **Elements no widget stands for.** The combat display hand-paints eight elements around the character, and the HUD its bottom-left line: the `:` command line, the last message, the hidden chat's lines.
2. **No order.** Nothing puts a widget in front of or behind its siblings.
3. **No owner.** Nothing says which addon built a widget, and the addon layer has no root to walk.
4. **Places that do not hold.** The belt's place is rewritten every frame, the chat lands its own height off, and a widget as large as the screen loses its place on a resize, `:remember` saving none.

**Maintainer's rulings (2026-10-03):**
- Every capability is general. Java written for one widget is one marked line per element upstream paints by hand.
- Nothing re-renders: a mirror that crops and forwards clicks is rejected. Every change is additive.

**The feature:**
- **Regions.** A region is an empty widget on the HUD standing for one hand-painted element, its role naming it.
  - Unheld, it stands where the element is painted. Once an addon holds it (`:position`, a rule, `:remember`, a drag, `:parent`), the element is painted where it stands.
  - `:visible(false)` stops the element being painted. Selectors, rules, anchors and reads answer on a region as on any widget, a rule from the moment the region appears.
  - The client paints the element at its own size and in its own order: `:size`, `:resizable`, `:raise`, `:lower` and `:z` refuse on a region. A point over a region is over whatever is behind it, as a click there is.
  - The roles: `fight.opening.mine`, `fight.opening.theirs`, `fight.ip.mine`, `fight.ip.theirs`, `fight.cooldown`, `fight.last.mine`, `fight.last.theirs`, `fight.action`, while a fight is drawn; `hud.cmdline`, `hud.message`, `hud.chat`, with the HUD.
- **Order.** `widget:raise()` and `widget:lower()`, acts, and `widget:z(n)`, the band they move within, an integer from `-9` to `9` and a restoring level: dropped, the widget goes back to its band and behind the sibling it followed. The client's popups open above every band. The screen itself — a tree's root, and the HUD on a session's — has no order an addon changes.
- **Owner and layer.** `widget:addon()`, the building addon's id as `pagina:addon()` answers it, `:info().addon`, and `hafen.ui():root()`.
- **Fixes.** The belt and the chat hold an addon's place, the chat shown or hidden. A widget covering its parent keeps its place in pixels.
- **The API edition moves to `1.5`**, for the new verbs and roles.

Unheld, a region costs one check a frame and the screen is today's, pixel for pixel.

## Acceptance criteria

Each is asserted by the named task's suite; `[manual]` is what only the eye tells.

1. While a fight is drawn, each `fight.*` role matches one `Region` on the HUD, still one after `@Fightsess` is taken into a surface of an addon's, and each fires `Removed` when the fight ends *(173.1)*.
2. Unheld, the cooldown, the action row and the two last manoeuvres keep the client's geometry about one point, and follow `@Fightsess` when it is moved or taken into a surface *(173.1)*.
3. A held combat region reads back its `:position(x, y)` frames later, its element painted there [manual]. `:position(nil)` returns it *(173.1)*.
4. A sheet rule naming a role places that region when it appears: a combat region at the fight's start *(173.1)*, a `hud.*` region at the HUD's arrival *(173.2)*. A surface anchored to a combat region stands at its anchor *(173.1)*.
5. `:visible(false)` on a region reads `false`, and the element stops painting [manual] *(173.1)*.
6. `:size(w, h)` and `:resizable(h)` on a region raise, naming that the client paints it at its own size. `hafen.ui():hit()` at a region's centre answers what is behind it *(173.1)*.
7. An addon declaring `api_version "1.5"` loads, and every example manifest declares it *(173.1)*.
8. The `hud.*` regions exist from the HUD's arrival, `hud.cmdline` and `hud.message` sharing one place. A held `hud.message` keeps its place while a notice shows there [manual] *(173.2)*.
9. A held belt reads its place frames later and is drawn there [manual]. The bottom-left line's default leaves it out *(173.2)*.
10. `:raise()` and `:lower()` reorder siblings, `:children()` and `hafen.ui():hit()` agreeing. `:z(n)` holds through later raises. `:z(nil)` and `widget:revert()` give back the band and the place behind the sibling it followed. The order writes raise on a root, on the HUD, on a column's child and on a region, and `:z` raises outside `-9`..`9` and on a non-integer. A dropdown opened over a surface at `:z(9)` paints over it [manual] *(173.3)*.
11. `widget:addon()` is the building addon's id, `"(console)"` for the `:lua` console's, and `nil` on a widget no addon built. `:info().addon` is the same value *(173.4)*.
12. `hafen.ui():root()` is the `:parent()` of every surface built without `:parent(w)`, and its `:match("[name=…]")` finds one *(173.4)*.
13. `chat:position(x, y)` reads back exactly and the chat stands there, the place written shown or hidden and across a hide and a show. `:position(nil)` puts it where the client places it, whole on the screen. A drag lands the chat under the pointer [manual] *(173.5)*.
14. A widget as large as the HUD, to the pixel or larger, keeps `:position(x, y)` through `:remember` and across a window resize [manual] *(173.5)*.
15. `tools/docverbs.py` and `tools/refusalverbs.py` exit `0` *(every task)*.

## Out of scope

- **The login screen's command line**: drawn before there is a HUD, the boundary.
- **An addon's own command line**: a region moves the line's drawing, and the keyboard stays the client's.
- **An event for another addon's new surface**: the layer root is walked on demand.
- **Which addons armed a drag**: two addons already share one drag by design.
- **A widget covering its parent on one axis only**: that axis keeps today's rule. A place in pixels on one axis and a fraction on the other is a remembered row an older build cannot read.

## Docs impact

**Pages written:** `api/ui/regions.md` (new), `ui/selectors.md`, `ui/native.md`, `ui/writes.md`, `ui/widget.md`, `ui/custom.md`, `ui/style/keys.md`, `ui/style/geometry.md`, `ui/README.md`, `fight.md`, `console.md`, `types/ui.md`, `api/README.md`; `manifest.md` and every example manifest declaring `1.4`; `docs/client/combat.md`, `console.md`, `gameui-windows.md`, `window-positions.md`, `widget-introspection.md`.

**Derived impact set.** The command:

```bash
grep -rniE "match no selector|Hold the handle the builder|follows the screen|saved relative to the screen|A position always lands|paints the icon elsewhere|over the map, to the|classify a widget|role classifying a widget|topmost|chat:draggable|@ChatUI" docs --include=*.md
```

It finds:
- `ui/custom.md:26`, `ui/selectors.md:16`, `ui/widget.md:32`: no lookup in the layer.
- `ui/native.md:25–36,53–57,67,136–137,150`, `ui/style/geometry.md:28`: the place rules, the chat, `:remember`.
- `fight.md:134,155,178`, `references.md:66`: where the openings paint.
- `ui/selectors.md:102,156`, `ui/style/keys.md:67`: roles, hit order.
- `store/vars.md:77`, `guides/saved-data.md:83–84`: made true.
- `client/widget-draw.md:21`, `widget-input.md:24,49`, `widget-introspection.md:15`: hit order, bands.
- `voice/link.md:22,33`: another sense, discharged.

## Context files

- `DOCUMENTATION.md`; `tools/docverbs.py`, `tools/refusalverbs.py`
- `src/haven/Fightsess.java`, `Bufflist.java` — 1
- `src/haven/GameUI.java` — 2, 5; `ConsoleHost.java`, `AddonWidgets.java` — 2, 3
- `src/haven/ChatUI.java` (`drawsmall` — 2; `move`, `resize`, `added`, `Spring` — 5)
- `src/haven/Widget.java` — 1, 3
- `src/haven/SListMenu.java`, `SDropBox.java`, `BuddyWnd.java` — 3
- `src/io/brodgar/ui/Region.java` (new) — 1, 2, 3; `src/io/brodgar/addon/AddonManager.java` — 1, 2
- `src/io/brodgar/addon/Selector.java`, `LuaRole.java`, `ApiVersion.java`; `docs/addons/manifest.md` — 1
- `src/io/brodgar/addon/LuaWidget.java` — 1, 3, 4, 5
- `src/io/brodgar/addon/Layout.java` — 1, 3, 5
- `src/io/brodgar/addon/UiApi.java` — 3, 4, 5; `Column.java` — 3, 5
- `src/io/brodgar/addon/Owned.java`, `LuaAddon.java` — 4
- `src/io/brodgar/addon/WidgetSurface.java`, `VirtualApi.java`, `LuaWidgetEntity.java` — 5; `src/io/brodgar/ui/WndPos.java` — 5
- each `docs/` page above, in the task that writes it
