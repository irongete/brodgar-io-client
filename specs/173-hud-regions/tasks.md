# 173 — HUD regions: tasks

Every task ends with `rm -rf build/classes && ant hafen-client`, `ant bin`, `tools/docverbs.py` and `tools/refusalverbs.py` exiting `0` (criterion 15), and its suite in `bin/addons/`, run as `:t173`, `api_version "1.5"`. A hand step waits for a Done or Skip button in the suite's own window.

- [x] **173.1 — The combat display paints each of its eight elements where a region of its own stands.**
  - Create `io.brodgar.ui.Region`: `stand`, `held`, `off`, `drop`, and `checkhit` answering `false` (plan.md).
  - `AddonManager` gains `regionStood(r)`, which is `Layout.placed(r, -1)`, so a rule reaches a region as it appears.
  - `AddonManager` gains `regionMoved(r)`, which queues `r` on the geometry seam when `Layout.followed(r)`, so an anchor follows an unheld region.
  - `drainDisposedWidgets` hands a `Region` to `UiApi.pruneRegion(w)`, which drops every owner's hidden and re-home record for it and recounts `anyHidden`.
  - `Fightsess` stands the `fight.*` regions in `added()` and drops them in `destroy()`.
    - `draw` calls `off` for all eight right after `updatepos()`, whatever is shown, and adds each region's offset to its element's anchors.
    - A hidden element skips its own painting only.
    - `tooltip` adds the same offsets.
  - `Selector.WIDGET_ROLES` and `LuaWidget.role` learn the eight roles, and `Layout.ownBox` takes a region.
  - `:size(w, h)`, `:size(w)` and `:resizable(h)` refuse on a region, naming "paints it at its own size".
  - `ApiVersion.CURRENT` moves to `1.5`, with `manifest.md` and every example manifest declaring `1.4`.
  - Docs:
    - `regions.md` (new): the roles, the boxes, hit and click passing through, the armed handle, a hidden `hud.cmdline` put back from `Disable`, and two rows in its rules table:
      - *Moving the whole combat display*: `@Fightsess:position(x, y)` moves every element at once and keeps them round the character, offset by (x, y). Holding the eight regions fixes each element on the screen instead. An unheld region follows `@Fightsess` wherever it stands, taken into a surface of yours included.
      - *Finding a region*: by its role, through a role selector or `:role()`, never by its index among `@GameUI:children()`. The combat regions are new widgets every fight, so that index changes as they come and go, and a place saved under it lands on another region.
    - `selectors.md` roles;
    - `style/keys.md` tree keys;
    - `writes.md`;
    - `fight.md:134,155,178`;
    - both indexes;
    - `docs/client/combat.md`.

  *Its suite*, before its first prompt:
  - installs a rule placing `fight.ip.theirs` at (500, 120);
  - subscribes to `fight.cooldown`'s `Added`, whose handler anchors a small surface of its own to that region and hides that fight's `fight.opening.mine`;
  - prompts "Attack one chicken or rabbit; press Done while you fight".

  It asserts:
  - each of the eight roles matches one `Region` whose `:parent()` is `@GameUI`, and `hafen.ui():role():get("fight.action"):selector()` reads its name;
  - the centres of the cooldown and the action row, and the midpoint of the two last manoeuvres, share one x within 2, and the cooldown and the last manoeuvres share one y, read a frame after the prompt;
  - `fight.ip.theirs` reads (500, 120): the rule reached a region that appeared after it;
  - in every frame sampled over two seconds, the anchored surface stands at its anchor on the region's place of that frame or of the frame before;
  - `hafen.ui():hit()` at `fight.cooldown`'s centre is not a region;
  - the regions follow their painter, as one check:
    - after `@Fightsess:position(60, 0)`, the unheld `fight.cooldown` stands 60 to the right;
    - after `:position(nil)`, and after `@Fightsess` is taken into a surface of the suite's own on `@GameUI`, moved 60 down (`:parent(p)` runs `Fightsess.added()` again), each role still matches one `Region` and `fight.cooldown` stands 60 lower;
    - after `:parent(nil)`, `fight.cooldown` is back where it began;
  - `fight.cooldown:position(40, 40)` reads (40, 40) half a second later and holds until "Done". After `:position(nil)` the geometry holds again a frame later;
  - `fight.opening.mine:visible(false)` reads `false`;
  - the two refusals, on one line;
  - after the prompt "End the fight; press Done", all eight fired `Removed`;
  - after the prompt "Fight once more and end it; press Done", the second fight's `fight.opening.mine` was hidden by the same handler and fired `Removed`;
  - its own `api_version "1.5"` loaded (criterion 7).

  The prune of a dead region's records is internal and no read shows it. `/implement` proves it headlessly in `jshell`, with the owner registered in `AddonManager.addons`: stand a region, record it hidden and re-homed, destroy it, drain, and assert that the owner's `hiddenNative` and `rehomedNative` no longer hold it.

  `[manual]`:
  - While it holds (40, 40), look at the screen's top left -- expect: the cooldown circle there.
  - Look round your character -- expect: your openings gone.

  <!-- extra context: src/haven/Buff.java (`cframe`, `move`); specs/170-live-fight/addons/170-live-fight.2/main.lua (the Done/Skip prompt; read only) -->

- [x] **173.2 — The HUD's bottom-left line stands its three regions, and a belt an addon placed stays there.**
  - `GameUI` stands `hud.cmdline`, `hud.message` and `hud.chat` in `attached()`, after `super.attached()`, while `rgns == null`.
  - `draw` paints the line, the notice and the hidden chat at their regions.
  - `beltwdg.c` is written, and the belt lowers `by`, only while `mine(beltwdg) && !AddonWidgets.held(beltwdg)`.
  - The chat's place counts in that arithmetic (`by`, and the belt's default) only while `mine(chat)`. Otherwise the belt takes the place `GameUI.resize` gives it.
  - The line's 20 comes off `by` only while the line painted is at its default.
  - `WIDGET_ROLES` gains the three.
  - Docs:
    - `regions.md` rows;
    - `console.md` (the line's region);
    - `native.md` (the belt);
    - `docs/client/console.md`, corrected (in world the line is `GameUI`'s);
    - `gameui-windows.md`;
    - `window-positions.md` (the draw-time belt write).

  *Its suite* declares `console.run` and asserts:
  - each role matches one region on `@GameUI`, and `hud.cmdline` and `hud.message` read one place;
  - unheld, `hud.cmdline` stands 20 above the belt's default top, or above the chat or the screen's bottom;
  - the belt (`@NKeyBelt` or `@FKeyBelt`) held at (300, 200) reads it half a second later, and `hud.cmdline` then stands 20 above the chat or the screen;
  - `hud.message` held at (60, 60), after `session:console():run("t173nosuch")`, still reads (60, 60);
  - a rule placing `hud.cmdline` at (60, 100), installed before the prompt "Log out to the character list and back in; press Done", has `hud.cmdline` reading (60, 100) in the new HUD. Skip scores it `[manual]`.

  It ends with `:position(nil)` and drops the rule.

  `[manual]`:
  - Look at the bottom of the screen -- expect: the belt at (300, 200).
  - Look at the top left -- expect: "no such command" there.
  - With `hud.cmdline` held at (60, 100), press `:` and type -- expect: the line near the top left.

- [x] **173.3 — A widget goes in front of or behind its siblings, within a band it keeps.**
  - `LuaWidget` gains `raise`, `lower` and `z`. `raise` and `lower` run under `monitor(w)`.
  - `Moved` gains `zs`, `zAfter`, `wantZ`, `zSeq` and `zWrote`, all known to `idle()`. `Layout.applyZ` folds them inside `Layout.apply`, with `LuaWidget.topWantZ` and `UiApi.stockZ`.
  - The restore is `Widget.z(zs)`, and only while `w.z == zWrote`. Then the widget goes back to its place:
    - `relink(w, zAfter)` when `zAfter` stands in band `zs`;
    - `w.lower()` when `zAfter` is `null` or of a lower band;
    - left where `link()` put it when `zAfter` has gone or stands in a higher band.
  - `zWrote` is set whenever the fold stands the widget at the winning band, written or already there.
  - `:z(nil)`, `UiApi.restoreMoved` and `UiApi.revertLevels` all reach the restore.
  - `AddonWidgets.POPUP_Z` is `10`. `SListMenu.addat`, `SDropBox`'s list `add` and `BuddyWnd`'s menu each set it before adding, one `// addon:` line apiece.
  - Refusals, each naming why:
    - a tree's root (it has no siblings);
    - a widget the client stands directly on a session's root (the screen);
    - a column's or row's child (`Column.placed`);
    - a region (painted in its painter's order);
    - `:z` outside `-9`..`9`, a surplus argument, a non-integer.
  - Docs:
    - `native.md` (front and back, the band's range, the restore);
    - `writes.md`, `widget.md` rows;
    - `docs/client/widget-introspection.md` (`Widget.z` bands, `link` to the band's front, the popups' band).

  *Its suite* builds, in a surface of its own, leaf surfaces `a`, `b` and `c` overlapping, plus a column holding two buttons. It asserts:
  - after `a:raise()`, `:children()` ends with `a` and `hafen.ui():hit()` at the overlap is `a`; after `a:lower()` it is `c`;
  - after `a:z(1)` and `c:raise()`, `a` is still last, and `a:z()` reads `1`;
  - `a:z(nil)` reads `0`, and `a` stands at the index it had before its first `:z`, which, after the `:lower()` above, is the first;
  - after `b:z(2)` and `b:revert()`, `b:z()` reads `0` and `b` is back at its index;
  - the column's first button refuses `:raise()`, naming its order;
  - `session:ui():root():raise()`, `@GameUI:raise()` and `hud.chat:z(1)` raise;
  - `:z(10)`, `:z(-10)`, `:z(1.5)`, `:z("1")` and `:z(1, 2)` raise.

  `[manual]`: with the suite's red surface at `:z(9)` over its dropdown, open the dropdown -- expect: its list over the red square.

  <!-- extra context: docs/client/widgets.md (the raise/lower row; read only) -->

- [x] **173.4 — A widget names the addon that built it, and the addon layer has a root.**
  - `widget:addon()` and `:info().addon` (plan.md): the builder's id, `"(console)"`, or `nil`. An argument refuses.
  - `hafen.ui():root()`, refusing an argument. The `UiApi.java` comment denying a layer twin is corrected.
  - Docs:
    - `widget.md`, with a *Getting a Widget* row for the root and the `:addon()` row, matching `menugrid.md`'s `pagina:addon()`;
    - `types/ui.md`;
    - `ui/README.md`'s "Reached through" column;
    - `selectors.md:16` and its lookups;
    - `custom.md:26`, `widget.md:32`.
  - `grep -rn "hafen.ui():root(" docs` is checked by hand: `docverbs.py` skips the `ui` section.

  *Its suite* asserts:
  - a surface named `probe` answers `:addon() == "173-hud-regions.4"`, and `:info().addon` the same;
  - a button it builds into `@GameUI` answers its own id, and `@GameUI` itself answers `nil`;
  - `probe:parent() == hafen.ui():root()`, the root's `:children()` holds `probe`, and `:match("[name=173-hud-regions.4/probe]") == probe`;
  - the root answers `:addon()` with `nil`, and `hafen.ui():root(1)` and `probe:addon(1)` raise;
  - it logs how many surfaces in the layer answer another addon's id.

  <!-- extra context: docs/addons/api/menugrid.md (`pagina:addon()`; read only) -->

- [ ] **173.5 — The chat lands where it is placed, shown or hidden, and a widget covering the screen keeps its pixels.**
  - `ChatUI.show()` re-derives `c` from `base` (`// addon:`), and `ChatUI.base()` reads it (`// addon:`).
  - `LuaWidget.place(w, c)` and `LuaWidget.at(w)`:
    - writes through `place`: `Layout.applyHalf`, `UiApi.restoreMoved`, `WidgetSurface.reparent` after its add (a `ChatUI` only), `Column`;
    - reads through `at`: `:position()`, `applyHalf`'s comparisons, `handOf`, and the three records of where a widget stood — `Rehomed.at`, the standing entity's `prevPos` (recorded in `VirtualApi`), and `UiApi.stockPos`'s last fallback.
  - `UiApi.stockPos` answers a `GameUI`'s chat through `GameUI.chatbase()` (`// addon:`), and `UiApi.restoreMoved` writes that live stock for it.
  - `LuaWidget.onScreen` excludes a widget at least its parent's size less one device pixel on both axes.
  - Docs:
    - `native.md:25–36,53–57,67,136–137,150`;
    - `style/geometry.md:28`;
    - confirm `store/vars.md:77` and `guides/saved-data.md:83–84`.

  *Its suite* checks where the chat **stands**, not only what it reads: `hafen.ui():hit()` two pixels inside the expected top-left answers the chat or a widget inside it. It asserts:
  - shown, `@ChatUI:position(200, 300)` reads (200, 300) and stands there;
  - after `:visible(false)`, `:position(200, 260)` and `:visible(true)`, it reads (200, 260) and stands there;
  - after the prompt "Press Ctrl+C until the chat has hidden and shown again; press Done", it still reads and stands at (200, 260). The first press only focuses a chat that lacks the focus;
  - after `:visible(false)`, `:position(nil)` and `:visible(true)`, the chat's bottom is `@GameUI`'s bottom and the chat stands whole on the screen;
  - for each of two sizes, exactly `@GameUI`'s (`t173a`) and 8 larger each way (`t173b`): a surface remembers the name, moves to (0, -40) and is destroyed, and a second surface of that size remembering the same name reads (0, -40);
  - after the prompt "Resize the game window; press Done", both second surfaces still read (0, -40).

  It ends with `:remember(nil)` on both names and the chat shown, on every exit, a failed check included.

  `[manual]`: drag the chat by the suite's red square and let go -- expect: it stays exactly where dropped.

  <!-- extra context: specs/170-live-fight/addons/170-live-fight.2/main.lua (the Done/Skip prompt; read only) -->
