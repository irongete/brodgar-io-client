-- 044.6 -- native windows, going back, and replace. Self-checking suite; see specs/testing/addon-suite.md and
-- specs/044-spatial-ui/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. Until now hafen.vr():widget():add(w, anchor) refused anything the addon had not built
-- itself. That refusal is gone: standing one of the CLIENT'S OWN windows in the world is the same family of
-- write as widget:position(x, y), widget:visible(false) and widget:replace(view) -- a layer over the client's
-- state, never a write into it -- so it is UNGATED, the window stays exactly as live as it was while it stands
-- (still bound to its server id, still filling with items), and every way the entity can end puts it back where
-- it came from. One rule, no branch on provenance: standing records WHERE the widget was, removing puts it back
-- there, and the widget's own visibility is never written, which is what makes "the window ends up as the user
-- was seeing it" (D-070) true in the was-visible and the was-hidden case alike.
--
-- AND IT COMPOSES WITH replace. widget:replace(view) decides WHAT stands in for a native window on the flat UI;
-- standing decides WHERE that thing is drawn. Different questions, so the stand-in is driven by the client's own
-- toggle while it stands in the world -- and when the substitution ends (the maintainer undoing it, or the
-- SERVER destroying the window it replaced, which is the same endReplacement body either way) the view is
-- destroyed and the panel standing in the world ends with it rather than hanging over a container that is gone.
--
-- WHAT IT NEEDS FROM THE MAINTAINER. The inventory must be OPEN when ':t044-6' runs: the suite stands the
-- client's own inventory WINDOW, and it measures where that window is on the flat UI first so it can prove it
-- left. Everything it borrows is put back by ':t044-6 off'.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Dropping an item (it takes an item in hand, which is a server round trip),
-- pressing the Equipment key (the toggle is the client's own click path), and asking the server to destroy a
-- window. The undo path is asserted through widget:replace(nil), which is the very function the server-destroy
-- sweep calls; the server doing it is a [manual] line -- and so is the drop, which is the case the whole
-- feature exists for: putting ore into a smelter window standing on the smelter.
--
-- It re-asserts its own premises -- standing takes a widget off the flat UI's hit test (044.1), the collection
-- answers (044.2) -- because a suite is read alone and must convince alone.
--
-- IT LEAVES ITS SET STANDING for the [manual] lines and takes it down on ':t044-6 off'. ':t044-6' at any time
-- starts over from an empty scene.
--
-- READ-ONLY: no permissions (that is the point of check 1), no persistent state. Everything it borrows from the
-- client -- the inventory grid, the equipment window -- is put back as it was found.

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, ...)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  local good = not ok
  for _, want in ipairs({ ... }) do
    if err:find(want, 1, true) == nil then good = false end
  end
  check(good, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local T = 11                              -- world units per tile
local S                                   -- what a round left standing

local function counters() return hafen.client():profiling():surfaces() end

local function within(hit, w)
  for _ = 1, 16 do
    if hit == nil then return false end
    if hit == w then return true end
    hit = hit:parent()
  end
  return false
end

-- Nine points spread over a widget's SCREEN rectangle, taken before it stands (standing re-homes it).
local function rect(w)
  local p, s = w:rootPos(), w:size()
  if (p == nil) or (s == nil) then return {} end
  local pts = {}
  for _, fx in ipairs({ 0.2, 0.5, 0.8 }) do
    for _, fy in ipairs({ 0.2, 0.5, 0.8 }) do
      pts[#pts + 1] = { math.floor(p.x + (s.x * fx)), math.floor(p.y + (s.y * fy)) }
    end
  end
  return pts
end

local function reachable(pts, w)
  local n = 0
  for _, q in ipairs(pts) do
    if within(hafen.ui():at(q[1], q[2]), w) then n = n + 1 end
  end
  return n
end

local function samePos(a, b)
  return (a ~= nil) and (b ~= nil) and (a.x == b.x) and (a.y == b.y)
end

local function posStr(p)
  return (p == nil) and "nil" or ("%d,%d"):format(p.x, p.y)
end

-- Undo everything a round installed, in the order the layers were put on: the standing entities first (each
-- puts its widget back), then the substitution (which destroys its own stand-in), then our own windows.
local function clear()
  local c = hafen.vr():widget()
  for _, e in ipairs(c:list()) do c:remove(e) end
  if S then
    if S.equip and S.equip:exists() and (S.equip:replacement() ~= nil) then
      pcall(function() S.equip:replace(nil) end)
    end
    for _, w in ipairs({ S.v0, S.v1, S.view }) do
      if w and w:exists() then w:destroy() end
    end
  end
  S = nil
end

local phase2, offRound, offFinish

local function run(args)
  if args and (args[1] == "off") then return offRound() end
  pass, fail, manual = 0, 0, 0             -- a re-run reports its own counts, not the last one's

  local okp, me = pcall(function() return hafen.player():gob() end)
  if not (okp and me and me:exists()) then
    check(false, "the suite is in the world (it stands the client's own windows in the 3D scene)",
          tostring(okp and me))
    return summary()
  end
  clear()
  S = {}
  local p = me:position()

  -- ---- what it borrows from the client, measured while it is still where the client put it ---------------
  -- The WINDOW, not the grid inside it: the client's own inventory window is what this feature is for, and
  -- standing it also puts the whole chrome -- title bar, border, close button -- on the panel in the world.
  -- The grid inside is what proves it is still the server's (below): a Hidewnd is client-side and has no id.
  local grid = hafen.ui():inventory()
  local win = (grid ~= nil) and grid:exists() and grid:parent() or nil
  if win == nil then
    check(false, "the client's own inventory window is in the tree (this suite stands it)", tostring(grid))
    return summary()
  end
  S.grid, S.win = grid, win
  S.wnd0, S.pos0, S.id0 = win:parent(), win:position(), grid:id()
  S.n0 = #grid:items()
  S.rect = rect(win)
  S.seen0 = reachable(S.rect, win)
  if S.seen0 == 0 then
    check(false, "the inventory is OPEN on screen before ':t044-6' runs -- the suite measures where the window"
          .. " is hit-tested on the flat UI so it can prove that standing took it off. Press Tab and run again",
          ("reachable at %d of 9 points over the window's own rectangle"):format(S.seen0))
    return summary()
  end

  -- ---- and the two of its own, for the other provenance --------------------------------------------------
  S.v0 = hafen.ui():window():title("044.6 owned"):size(160, 80):position(320, 150)
  S.p0, S.c0 = S.v0:parent(), S.v0:position()
  S.v1 = hafen.ui():window():title("044.6 hidden"):size(160, 80):position(320, 260)
  S.p1, S.c1 = S.v1:parent(), S.v1:position()

  -- ---- the composition: a stand-in for the equipment window, standing in the world ------------------------
  S.equip = hafen.ui():equipment()
  if (S.equip ~= nil) and S.equip:exists() then
    S.equipWnd = S.equip:parent()
    S.wasVis = (S.equipWnd ~= nil) and S.equipWnd:visible() or false
    S.view = hafen.ui():window():title("044.6 stand-in"):size(180, 90):position(520, 150)
    local ok, err = pcall(function() S.equip:replace(S.view) end)
    if not ok then
      S.replaceErr = (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
      if S.view:exists() then S.view:destroy() end
      S.view = nil
    end
  end

  S.gate = hafen.act():enabled()
  S.live0 = counters().live
  S.eInv = hafen.vr():widget():add(S.win, p:offset(2 * T, 0)):facing("camera")
  S.e0   = hafen.vr():widget():add(S.v0, p:offset(2 * T, 3 * T)):facing("camera")
  S.e1   = hafen.vr():widget():add(S.v1, p:offset(2 * T, -3 * T)):facing("camera")
  if S.view ~= nil then
    S.eView = hafen.vr():widget():add(S.view, p:offset(4 * T, 0)):facing("camera")
  end

  hafen.timer():after(1.5, function() phase2() end)
end

phase2 = function()
  if not (S and S.eInv and S.eInv:exists()) then
    check(false, "the standing set survived the frames it needed to be drawn once", "gone")
    return summary()
  end
  local win, grid = S.win, S.grid

  -- ---- 1. it stands at all, and it is UNGATED ------------------------------------------------------------
  local host = win:parent()
  check((S.gate == false) and (host ~= nil) and (host:type() == "WidgetSurface"),
        "one of the CLIENT'S OWN windows stands in the world, from an addon with no permissions at all:"
        .. " hafen.act():enabled() is false here, so nothing this suite can call writes to the server -- and"
        .. " that is the whole argument for standing being ungated. Only where the window is drawn changed;"
        .. " every click that reaches the server is still one the user made with their own hand",
        ("act:enabled=%s host=%s"):format(tostring(S.gate), host and host:type() or "nil"))

  -- ---- 2. ...and it left the flat UI while staying in the tree -------------------------------------------
  local seen = reachable(S.rect, win)
  check((S.seen0 > 0) and (seen == 0) and win:exists(),
        "...and it is GONE from the flat UI's hit test while still being a widget in the tree: the surface it"
        .. " was re-homed into is invisible, which is exactly what takes a standing panel off the flat pass"
        .. " and out of every point query, and :exists() says the widget itself never went anywhere",
        ("flat-reachable %d -> %d of 9, exists=%s"):format(S.seen0, seen, tostring(win:exists())))

  -- ---- 3. LIVE and SERVER-BOUND while it stands ----------------------------------------------------------
  local id, n = grid:id(), #grid:items()
  check((id == S.id0) and (id >= 0) and (n == S.n0) and (grid:parent() == win),
        "the standing window is still the CLIENT'S, live and bound to the server: the grid inside it kept its"
        .. " widget id and its items read back unchanged through it, from inside the panel in the world --"
        .. " standing is a re-home, not an adoption, so the server goes on filling it while it hangs there",
        ("id %s -> %s, items %d -> %d, grid still inside the window=%s"):format(
          tostring(S.id0), tostring(id), S.n0, n, tostring(grid:parent() == win)))

  -- ---- 4. the collection answers for it, and the entity points back at it --------------------------------
  local c, inList = hafen.vr():widget(), false
  for _, e in ipairs(c:list()) do
    if e == S.eInv then inList = true end
  end
  check(inList and (S.eInv:widget() == win) and (c:count() >= 3),
        "the collection answers for a borrowed widget exactly as for an owned one: it is in :list(), :count()"
        .. " counts it, and the entity's :widget() hands back the very window that is standing -- there is no"
        .. " second kind of standing widget, only a second kind of widget",
        ("in list=%s :widget()==inv %s count=%d"):format(tostring(inList),
          tostring(S.eInv:widget() == inv), c:count()))

  -- ---- 5. what it stands at is NOT what the user arranged, and that is the record's job ------------------
  -- The two numbers are printed on the PASS line as well, on purpose: they are the whole of what a put-back
  -- has to give back, so running this command again after a ':reload' says in one line whether it did.
  check((S.pos0 ~= nil) and not samePos(win:position(), S.pos0),
        ("while it stands, the window's own place is the surface's origin (%s) and NOT the place the user"
         .. " arranged it at (%s) -- which is why that place is RECORDED at stand time rather than read back"
         .. " later. The inventory is one of the windows the client writes to disk every minute, so a store"
         .. " reading a standing window would displace it forever: it is answered from the record instead,"
         .. " and ':t044-6 off' AND ':reload' must both give exactly %s back")
        :format(posStr(win:position()), posStr(S.pos0), posStr(S.pos0)),
        ("standing at %s, user's place %s"):format(posStr(win:position()), posStr(S.pos0)))

  -- ---- 6. one widget stands in one place, and the refusal names who holds it -----------------------------
  local p = hafen.player():gob():position()
  refuses("standing the same window twice is refused, NAMING the addon that holds it -- ownership is per"
          .. " surface owner, so a second addon reaching for a window this one is standing is told which one"
          .. " to disable, rather than left to fight over a widget that can only be in one place",
          function() hafen.vr():widget():add(S.win, p:offset(3 * T, 0)) end,
          "already", "standing in the world", "044-spatial-ui.6")

  -- ---- 7. ...and the world does not stand inside itself --------------------------------------------------
  refuses("the 3D view itself is refused: a surface is drawn from the very frame that then draws the scene it"
          .. " stands in, so a widget with the MapView under it -- the HUD, the root -- would be a picture of"
          .. " the world containing a picture of the world",
          function() hafen.vr():widget():add(hafen.ui():root(), p:offset(3 * T, 0)) end,
          "3D view", "stand inside itself")

  -- ---- 8. a widget INSIDE a standing one is refused too --------------------------------------------------
  local kid = hafen.ui():label():parent(S.v0):position(10, 10):text("inside")
  refuses("...and so is a widget INSIDE a panel that is already standing -- two panels in the world are two"
          .. " :add calls on two anchors, never one nested in the other",
          function() hafen.vr():widget():add(kid, p:offset(3 * T, 0)) end,
          "INSIDE one that is already standing")

  -- ---- 9. replace composes: the toggle's target is unchanged by standing ---------------------------------
  if S.view == nil then
    check(false, "the equipment window could be replaced (widget:replace(view)) so the composition could be"
          .. " tested -- disable whatever addon holds it and run again",
          S.replaceErr or "no equipment window in the tree")
  else
    local vhost = S.view:parent()
    check((S.equip:replacement() == S.view) and (vhost ~= nil) and (vhost:type() == "WidgetSurface")
          and (S.equipWnd:visible() == false),
          "a stand-in for a native window STANDS: the equipment window is hidden and its toggle drives the"
          .. " view, the view is drawn on a panel in the world, and the substitution reads back the very same"
          .. " view it did before it stood -- replace says WHAT stands in for the window, standing says WHERE"
          .. " it is drawn, and the two never had to know about each other",
          ("replacement()==view %s, view host=%s, native window visible=%s"):format(
            tostring(S.equip:replacement() == S.view), vhost and vhost:type() or "nil",
            tostring(S.equipWnd:visible())))
  end

  -- ---- 10. nothing of the client's leaked into the surface count -----------------------------------------
  local live = counters().live
  check(live == (S.live0 + (S.view and 4 or 3)),
        "each standing widget is exactly one surface, borrowed or owned -- the live-surface counter moved by"
        .. " the number of :add calls and by nothing else",
        ("live %d -> %d"):format(S.live0, live))

  -- The was-hidden half of the put-back rule is set up HERE rather than in the off round, because a Window's
  -- hide is a FADE: visible() answers false at once, but the widget itself is only really hidden when the
  -- animation finishes, and re-homing a window mid-transition re-runs its entry animation (Window.added ->
  -- initanim), which cancels the fade. Toggling it off now and taking it back a command later is what a user
  -- does anyway, and it is the settled state the rule is about.
  S.v1:visible(false)
  check(S.v1:visible() == false,
        "a panel can be toggled OFF while it stands in the world -- the window's own hide, nothing new: what"
        .. " the off round then asserts is that taking it back does not turn it on again",
        tostring(S.v1:visible()))

  manualCheck("look east: your whole INVENTORY WINDOW is standing there -- title bar, border and all -- with"
              .. " '044.6 owned' beside it"
              .. (S.view and " and the '044.6 stand-in' window further out" or "")
              .. ". ('044.6 hidden' is deliberately NOT there: it was toggled off above.) Hover an item, pick"
              .. " one up, and DROP IT BACK on an empty slot of that same grid in the world; then press Tab"
              .. " twice",
              "the panel is your real inventory: tooltips, picking up and DROPPING BACK IN all work and the"
              .. " server sees them -- and Tab blanks the panel in the world and brings it back, because"
              .. " standing hides nothing and the client's own toggle still owns that window")
  if S.view ~= nil then
    manualCheck("press the EQUIPMENT key (or click its menu button on the HUD)",
                "the '044.6 stand-in' panel IN THE WORLD appears and disappears, and the menu button's tick"
                .. " follows it -- the client's own toggle driving a window that is not on the screen")
  end
  manualCheck("then run ':t044-6 off', which asserts that everything came back and takes the set down",
              "the inventory window is back on the flat UI, at the place you had it")
  manualCheck("finally, the OTHER two endings, and the one number that says whether they are right: run"
              .. " ':t044-6' again, then ':reload' (instead of ':t044-6 off'), then ':t044-6' a third time --"
              .. " and PASTE BACK that third run's check 5, which prints where the window is",
              "everything vanishes from the world and the inventory window is back where it was: check 5 of"
              .. " the third run must report the SAME \"user's place\" as this one (" .. posStr(S.pos0) .. "),"
              .. " because :reload ends an entity through the very put-back ':remove' uses")
  summary()
end

-- NOTE on ':t044-6 off': it asserts what standing gave back and THEN destroys everything, because a suite
-- leaves nothing behind (TESTING.md). Everything vanishing is the round succeeding, not a fault.

offRound = function()
  pass, fail, manual = 0, 0, 0
  if S == nil then
    check(false, "':t044-6' has been run first -- this round takes down what THAT one stood",
          "nothing of this suite is standing")
    return summary()
  end
  local win, grid, c = S.win, S.grid, hafen.vr():widget()

  -- ---- 1. the client's own window goes back where it came from, still live ------------------------------
  c:remove(S.eInv)
  local back, seen = win:parent(), reachable(S.rect, win)
  check((back == S.wnd0) and samePos(win:position(), S.pos0) and (win:visible() == true)
        and (grid:id() == S.id0) and (#grid:items() == S.n0) and (seen >= S.seen0),
        ":remove puts the client's own window back exactly where it stood from -- the same parent, the same"
        .. " place inside it, still bound to the same server id with the same items, and hit-testable on the"
        .. " flat UI at every point it was before it stood. Having been a root in the world for a while"
        .. " changed nothing about where it came from",
        ("parent-restored=%s position %s (was %s) visible=%s grid id=%s items=%d reachable %d -> %d"):format(
          tostring(back == S.wnd0), posStr(win:position()), posStr(S.pos0), tostring(win:visible()),
          tostring(grid:id()), #grid:items(), S.seen0, seen))

  -- ---- 2. ...and an owned one goes back to its default parent, under the SAME rule -----------------------
  c:remove(S.e0)
  check((S.v0:parent() == S.p0) and samePos(S.v0:position(), S.c0) and (S.v0:visible() == true),
        "...and a window the addon built goes back to its own default parent on the flat UI, at the place it"
        .. " had there: one rule, no branch on provenance -- standing records where the widget was and"
        .. " removing puts it back there, whoever built it",
        ("parent-restored=%s position %s (was %s) visible=%s"):format(
          tostring(S.v0:parent() == S.p0), posStr(S.v0:position()), posStr(S.c0), tostring(S.v0:visible())))

  -- ---- 3. the was-hidden case: what comes back is what the user was SEEING -------------------------------
  local hid = S.v1:visible()               -- toggled off in the ':t044-6' round, and long since settled
  c:remove(S.e1)
  check((hid == false) and (S.v1:parent() == S.p1) and (S.v1:visible() == false) and S.v1:exists(),
        "a panel the user had toggled OFF while it stood comes back off, not on: standing never writes the"
        .. " widget's own visibility, so what it had while it stood is what the user was seeing, and that is"
        .. " what the put-back leaves them with -- D-070's rule with nothing added to make it hold here",
        ("hidden before the put-back=%s, parent-restored=%s visible=%s exists=%s"):format(tostring(hid),
          tostring(S.v1:parent() == S.p1), tostring(S.v1:visible()), tostring(S.v1:exists())))

  -- ---- 4. the substitution ends, and the panel standing in the world ends with it ------------------------
  if S.view == nil then
    check(false, "the equipment substitution was installed in the ':t044-6' round so its ending could be"
          .. " asserted here", S.replaceErr or "no stand-in was installed")
    return summary()
  end
  S.view:visible(S.wasVis)                 -- so the window comes back exactly as the maintainer had it
  S.equip:replace(nil)                     -- the SAME endReplacement body the server-destroy sweep calls
  hafen.timer():after(0.6, function() offFinish() end)
end

offFinish = function()
  check((not S.view:exists()) and (not S.eView:exists()) and (S.equip:replacement() == nil)
        and (S.equipWnd:visible() == S.wasVis),
        "ending the substitution ends the panel it was standing on: the stand-in dies with the substitution"
        .. " (as it does when the SERVER destroys the window it replaced -- the same body), and the entity"
        .. " holding it in the world ends one tick later rather than hanging over a container that is gone."
        .. " The native window came back as the user was seeing it",
        ("view exists=%s entity exists=%s replacement=%s native window visible=%s (was %s)"):format(
          tostring(S.view:exists()), tostring(S.eView:exists()), tostring(S.equip:replacement()),
          tostring(S.equipWnd:visible()), tostring(S.wasVis)))

  local c, live = hafen.vr():widget(), counters().live
  check((c:count() == 0) and (live == S.live0),
        "...and nothing of this suite is left standing: the collection is empty and the live-surface count is"
        .. " back where it started, so every texture the round allocated has been freed",
        ("standing=%d live surfaces %d -> %d"):format(c:count(), S.live0, live))

  for _, w in ipairs({ S.v0, S.v1 }) do
    if w and w:exists() then w:destroy() end
  end
  S = nil
  summary()
end

hafen.slash():register("t044-6", run)   -- the only way in: a suite does not start itself
