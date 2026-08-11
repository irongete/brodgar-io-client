-- 044.4 -- input: clicks land where they look like they land. Self-checking suite; see
-- specs/testing/addon-suite.md and specs/044-spatial-ui/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. A widget standing in the world takes the pointer, in its OWN pixels. The panel's four
-- projected corners fix a projective map of its plane, so a screen point inverts back to a widget point exactly
-- -- perspective and all -- and the event is then dispatched by the client's own traversal from the surface
-- down. MouseDown, MouseUp, MouseMove and Wheel therefore arrive at the same handlers, with the same
-- coordinates and the same ev:preventDefault(), that the very same widget would have seen on the flat UI. It
-- works on both anchors and in all three facing modes, because all three are flat quads and only what SAMPLES
-- the texture differs. And a point on no panel is a miss, not a swallowed click: the routing answers false and
-- the map view goes on to do exactly what it always did, which is how the world beneath keeps its click.
--
-- HOW IT IS DRIVEN. hafen.vr():pointer(key, x, y [, a]) is the very function haven.MapView calls, entered at a
-- screen point -- so no click hardware is needed and nothing is simulated at the widget end. widget:screen(x, y)
-- is its exact inverse and says where a panel pixel is drawn, so every coordinate check below is a round trip
-- through both directions and is asserted as a NUMBER.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Whether clicking feels like clicking is the hand's answer, so the one
-- [manual] line is a real mouse on a real button.
--
-- It re-asserts its own premises -- standing takes a widget off the flat UI's hit test (044.1), both anchors
-- take one (044.2), all three modes read back (044.3) -- because a suite is read alone and must convince alone.
--
-- IT LEAVES ITS SET STANDING for the [manual] click and takes it down on ':t044-4 off', which is where the
-- going-back assertion lives. ':t044-4' at any time starts over from an empty scene.
--
-- READ-ONLY: no permissions, no persistent state. It builds its own widgets, stands them, and removes them.

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

local function near(a, b, tol) return (a ~= nil) and (b ~= nil) and (math.abs(a - b) <= (tol or 2)) end

local T = 11                              -- world units per tile
local S                                   -- what a round left standing

-- ---- the two directions, and the one door input comes through -----------------------------------------

-- Put the pointer on whatever stands at the SCREEN point panel pixel (wx, wy) is drawn at. Returns whether a
-- panel took it, plus the screen point it was aimed at.
-- The fourth argument is OMITTED when there is none, never passed as nil: arity is the verb, so an explicit
-- nil is a refusal here (and rightly -- it is what "a must not be nil" is for), which a MouseMove has none of.
local function poke(e, wx, wy, key, arg)
  local sx, sy = e:screen(wx, wy)
  if sx == nil then return nil, nil, nil end
  if arg == nil then return hafen.vr():pointer(key, sx, sy), sx, sy end
  return hafen.vr():pointer(key, sx, sy, arg), sx, sy
end

-- A panel that records every pointer event that reaches it, in its own pixels. All four are the client's OWN
-- windows -- chrome, background, title bar and close button -- because that is what a panel standing in the
-- world normally is, and because a bare hafen.ui():widget() paints nothing at all on its own (it is a
-- container, not a surface: it has no background to draw and the stylesheet's bg replaces a surface the client
-- already paints rather than inventing one), so one would stand there as a title floating over the terrain.
local function panel(w, h, title)
  local rec = {}
  local p = title and hafen.ui():window():title(title):size(w, h) or hafen.ui():widget():size(w, h)
  for _, k in ipairs({ "MouseDown", "MouseUp", "MouseMove", "Wheel" }) do
    p:on(k, function(ev)
      local r = { x = ev:x(), y = ev:y() }
      if (k == "MouseDown") or (k == "MouseUp") then r.b = ev:button() end
      if k == "Wheel" then r.n = ev:amount() end
      rec[k] = r
    end)
  end
  return { w = p, rec = rec }
end

-- Nine points spread over a widget's SCREEN rectangle, taken before it stands (standing re-homes it).
local function rect(w)
  local p, s = w:position(), w:size()
  local pts = {}
  for _, fx in ipairs({ 0.2, 0.5, 0.8 }) do
    for _, fy in ipairs({ 0.2, 0.5, 0.8 }) do
      pts[#pts + 1] = { math.floor(p.x + (s.x * fx)), math.floor(p.y + (s.y * fy)) }
    end
  end
  return pts
end

local function within(hit, w)
  for _ = 1, 16 do
    if hit == nil then return false end
    if hit == w then return true end
    hit = hit:parent()
  end
  return false
end

local function reachable(pts, w)
  local n = 0
  for _, q in ipairs(pts) do
    if within(hafen.ui():at(q[1], q[2]), w) then n = n + 1 end
  end
  return n
end

-- Where a child sits inside a WINDOW, in the window's own OUTER pixels -- which is what a surface pixel is.
-- A window's chrome inset belongs to the client: a child's :position() reads inner coordinates and no verb
-- converts them, so the offset is MEASURED rather than guessed -- twice down the flat UI's own hit test before
-- the window stands. One sweep down the window's middle finds the child's rows, one across those finds its
-- columns. ~2*sz calls, once, and it is exact to the pixel on any chrome the client happens to wear.
local function childRect(win, kid)
  local wp, ws = win:position(), win:size()
  local y0, y1
  local mx = wp.x + math.floor(ws.x / 2)
  for y = 0, ws.y - 1 do
    if within(hafen.ui():at(mx, wp.y + y), kid) then
      y0 = y0 or y
      y1 = y
    end
  end
  if y0 == nil then return nil end
  local my = wp.y + math.floor((y0 + y1) / 2)
  local x0, x1
  for x = 0, ws.x - 1 do
    if within(hafen.ui():at(wp.x + x, my), kid) then
      x0 = x0 or x
      x1 = x
    end
  end
  if x0 == nil then return nil end
  return { x = x0, y = y0, w = (x1 - x0) + 1, h = (y1 - y0) + 1 }
end

local function clear()
  local c = hafen.vr():widget()
  for _, e in ipairs(c:list()) do c:remove(e) end
  if S then
    for _, k in ipairs({ "a", "b", "c", "d" }) do
      local q = S[k]
      if q and q.w and q.w:exists() then q.w:destroy() end
    end
  end
  S = nil
end

local function counters() return hafen.client():profiling():surfaces() end

local phase2, phase3, phase4, phase5, offRound

local function run(args)
  if args and (args[1] == "off") then return offRound() end
  pass, fail, manual = 0, 0, 0             -- a re-run reports its own counts, not the last one's

  local okp, me = pcall(function() return hafen.player():gob() end)
  if not (okp and me and me:exists()) then
    check(false, "the suite is in the world (it stands panels in the 3D scene and puts the pointer on them)",
          tostring(okp and me))
    return summary()
  end
  clear()
  S = { turn = 0 }
  local p = me:position()

  -- A -- the numeric one, and the one the [manual] click is on: a real window like the other three, holding a
  -- button. Camera-facing, so it is square-on wherever the camera happens to be, standing at a POINT.
  S.a = panel(220, 160, "044.4 press me")
  S.a.w:position(300, 180)
  S.btn = hafen.ui():button():parent(S.a.w):position(0, 0):size(120, 30):text("044.4 press me")
  S.lbl = hafen.ui():label():text("...or beside it"):parent(S.a.w):position(4, 96)
  S.presses = 0
  S.btn:on("Pressed", function() S.presses = S.presses + 1 end)
  S.brect = childRect(S.a.w, S.btn)          -- the button's place in SURFACE pixels, measured while it is flat
  S.aRect = rect(S.a.w)
  S.aSeen = reachable(S.aRect, S.a.w)
  S.a.e = hafen.vr():widget():add(S.a.w, p:offset(2 * T, 0)):facing("camera")

  -- B, C and D -- the other anchor and the other two facing modes, and all three are real WINDOWS: chrome,
  -- borders, background and a title bar that must stay inert while they stand (the spec's rule -- a widget
  -- standing in the world has no place of its own to drag).
  S.b = panel(140, 90, "044.4 on the gob")
  S.b.w:position(300, 360)
  S.b.e = hafen.vr():widget():add(S.b.w, me):facing("camera")
  S.b.e:offset(0, 0, 16)

  S.c = panel(140, 90, "044.4 screen")
  S.c.w:position(520, 200)
  S.c.e = hafen.vr():widget():add(S.c.w, p:offset(2 * T, -3 * T)):facing("screen")
  S.d = panel(140, 90, "044.4 fixed")
  S.d.w:position(520, 360)
  S.d.e = hafen.vr():widget():add(S.d.w, p:offset(2 * T, 3 * T))          -- left "fixed": the default

  hafen.timer():after(1.5, function() phase2() end)
end

-- PHASE 2 -- a "fixed" quad is at whatever angle it was put at, and the camera is wherever the maintainer
-- left it, so it can be edge-on to the viewer and have no interior to land on at all. Turn it until it has
-- one; that is the mode's own honest behaviour, not a fault, and the assertions want a quad you can see.
phase2 = function()
  if not (S and S.d.e and S.d.e:exists()) then
    check(false, "the standing set survived the frames it needed to be drawn once", "gone")
    return summary()
  end
  local x0, y0 = S.d.e:screen(0, 0)
  local x1, y1 = S.d.e:screen(S.d.w:size().x, 0)
  local wide = (x0 ~= nil) and (x1 ~= nil) and ((math.abs(x1 - x0) + math.abs(y1 - y0)) > 24)
  if (not wide) and (S.turn < 3) then
    S.turn = S.turn + 1
    S.d.e:rotate(S.turn * math.pi / 2)
    return hafen.timer():after(0.6, function() phase2() end)
  end
  phase3()
end

phase3 = function()
  if not (S and S.a.e and S.a.e:exists()) then
    check(false, "the standing set survived the frames it needed to be drawn once", "gone")
    return summary()
  end
  local A = S.a

  -- ---- 1. the premises: four panels stand, in three modes, off the flat UI's hit test -------------------
  check(A.e:exists() and S.b.e:exists() and S.c.e:exists() and S.d.e:exists()
        and (A.e:facing() == "camera") and (S.c.e:facing() == "screen") and (S.d.e:facing() == "fixed")
        and (S.aSeen > 0) and (reachable(S.aRect, A.w) == 0),
        "the premises hold: four panels stand -- both anchors, all three facing modes -- and standing took"
        .. " the first one off the FLAT UI's hit test, so anything that reaches it now reached it as a thing"
        .. " in the world",
        ("modes=%s/%s/%s flat-reachable %d->%d of 9"):format(A.e:facing(), S.c.e:facing(), S.d.e:facing(),
                                                             S.aSeen, reachable(S.aRect, A.w)))

  -- Every point below is a SURFACE pixel -- the standing widget's own outer coordinates, which for a window
  -- include its chrome. The four probes sit in its lower half, clear of the button and of the title bar,
  -- because a press there would start the client's own drag and this check is about coordinates.
  -- ---- 2. a press lands on the PIXEL it was aimed at ---------------------------------------------------
  local took, sx, sy = poke(A.e, 150, 120, "MouseDown", 3)
  poke(A.e, 150, 120, "MouseUp", 3)
  local d = A.rec.MouseDown
  check((took == true) and (sx ~= nil) and (d ~= nil) and near(d.x, 150) and near(d.y, 120) and (d.b == 3),
        "a press aimed at panel pixel (150, 120) arrives at the widget AT (150, 120), carrying the button it"
        .. " was made with -- the screen point came from widget:screen(150, 120), so this is the corner map"
        .. " round-tripped through both directions",
        ("took=%s at screen %s,%s -> %s,%s b=%s"):format(tostring(took), tostring(sx), tostring(sy),
          d and tostring(d.x), d and tostring(d.y), d and tostring(d.b)))

  -- ---- 3. ...and so does the release, at a different pixel ----------------------------------------------
  poke(A.e, 60, 140, "MouseDown", 1)
  poke(A.e, 60, 140, "MouseUp", 1)
  local u = A.rec.MouseUp
  check((u ~= nil) and near(u.x, 60) and near(u.y, 140) and (u.b == 1),
        "the release of that gesture arrives too, at ITS own pixel (60, 140) -- press and release are one"
        .. " gesture on the panel that took the press, exactly as they are on screen",
        ("%s,%s b=%s"):format(u and tostring(u.x), u and tostring(u.y), u and tostring(u.b)))

  -- ---- 4. move and wheel, the same way ------------------------------------------------------------------
  poke(A.e, 30, 130, "MouseMove")
  poke(A.e, 170, 145, "Wheel", -1)
  local m, wh = A.rec.MouseMove, A.rec.Wheel
  check((m ~= nil) and near(m.x, 30) and near(m.y, 130)
        and (wh ~= nil) and near(wh.x, 170) and near(wh.y, 145) and (wh.n == -1),
        "MouseMove and Wheel arrive at their own pixels as well, the wheel carrying its amount -- all four"
        .. " input keys are the widget's own, not a click forwarded into a picture",
        ("move=%s,%s wheel=%s,%s n=%s"):format(m and tostring(m.x), m and tostring(m.y),
          wh and tostring(wh.x), wh and tostring(wh.y), wh and tostring(wh.n)))

  -- ---- 5. ev:preventDefault() still cancels -------------------------------------------------------------
  local b = S.brect
  if b == nil then
    check(false, "the button's place inside its window was measurable on the flat UI (childRect), which"
          .. " everything below presses against", "nil -- hafen.ui():at() never resolved to the button")
    return summary()
  end
  local bx, by = b.x + math.floor(b.w / 2), b.y + math.floor(b.h / 2)
  local sub = S.btn:on("MouseDown", function(ev) ev:preventDefault() end)
  S.presses = 0
  poke(A.e, bx, by, "MouseDown", 1)
  poke(A.e, bx, by, "MouseUp", 1)
  local cancelled = S.presses
  sub:off()

  -- ---- 6. ...and without it, the button's own callback runs ---------------------------------------------
  S.presses = 0
  poke(A.e, bx, by, "MouseDown", 1)
  poke(A.e, bx, by, "MouseUp", 1)
  check((cancelled == 0) and (S.presses == 1),
        "a button STANDING IN THE WORLD is pressed by a press and a release over it and runs its own"
        .. " Pressed callback -- and a MouseDown handler that calls ev:preventDefault() stops it dead,"
        .. " the same cancel that works on the flat UI",
        ("with preventDefault=%d presses, without=%d"):format(cancelled, S.presses))

  -- ---- 6b. ...and a press that is NOT on the button leaves it alone ------------------------------------
  -- The other half of "clicks land where they look like they land", and the half a round trip cannot prove:
  -- a map that was uniformly wrong would still round-trip, and would still press the button from anywhere.
  S.presses = 0
  local elsewhere = { { b.x + b.w + 10, b.y + 5 }, { b.x + 5, b.y + b.h + 20 },
                      { b.x + b.w + 10, b.y + b.h + 20 }, { b.x + 8, b.y + b.h + 70 } }
  for _, q in ipairs(elsewhere) do
    poke(A.e, q[1], q[2], "MouseDown", 1)
    poke(A.e, q[1], q[2], "MouseUp", 1)
  end
  local strays = S.presses
  S.presses = 0
  poke(A.e, bx, by, "MouseDown", 1)
  poke(A.e, bx, by, "MouseUp", 1)
  check((strays == 0) and (S.presses == 1),
        ("...and a press anywhere ELSE on the panel leaves the button alone -- four points around it (its"
         .. " label included) press nothing, while its own middle presses it: the pointer reaches the widget"
         .. " it is over and no other. The button's box was MEASURED at (%d,%d)+%dx%d in the window's own"
         .. " outer pixels, chrome included, since a child of a window is placed in inner ones"):format(
          b.x, b.y, b.w, b.h),
        ("%d stray presses from 4 points off the button, %d from 1 on it"):format(strays, S.presses))

  -- ---- 7. both anchors, all three modes ------------------------------------------------------------------
  local landed, where = 0, {}
  for _, q in ipairs({ A, S.b, S.c, S.d }) do
    local sz = q.w:size()
    local cx, cy = math.floor(sz.x / 2), math.floor(sz.y / 2)
    q.rec.MouseDown = nil
    local got = poke(q.e, cx, cy, "MouseDown", 1)
    poke(q.e, cx, cy, "MouseUp", 1)        -- always paired: never leave a press outstanding (see phase 4)
    local r = q.rec.MouseDown
    if (got == true) and (r ~= nil) and near(r.x, cx) and near(r.y, cy) then landed = landed + 1 end
    where[#where + 1] = ("%s:%s"):format(q.e:facing(), r and (r.x .. "," .. r.y) or "nil")
  end
  check(landed == 4,
        "a press lands on the middle of every one of the four -- one standing on a GOB and three at POINTS,"
        .. " one \"camera\", one \"screen\" and one \"fixed\": the same corner map answers all of them, and"
        .. " the screen blit is simply its degenerate case",
        ("%d/4 -- %s"):format(landed, table.concat(where, " ")))

  -- ---- 8. a miss is a miss ------------------------------------------------------------------------------
  local cx, cy = A.e:screen(100, 70)
  cx, cy = cx or 0, cy or 0                -- a panel that will not project fails the checks above, not here
  local off1 = hafen.vr():pointer("MouseDown", cx + 4000, cy + 4000, 1)
  hafen.vr():pointer("MouseUp", cx + 4000, cy + 4000, 1)
  A.e:clickable(false)
  local through = hafen.vr():pointer("MouseDown", cx, cy, 1)
  hafen.vr():pointer("MouseUp", cx, cy, 1)
  A.e:clickable(true)
  local back = hafen.vr():pointer("MouseDown", cx, cy, 1)
  hafen.vr():pointer("MouseUp", cx, cy, 1)
  check((off1 == false) and (through == false) and (back == true) and (A.e:clickable() == true),
        "a point on NO panel is answered false, so the map view goes on to do what it always did and the"
        .. " world beneath keeps its click -- and clickable(false) makes a panel click-through in exactly"
        .. " the same way, which is the opt-out from a default of yes",
        ("off-panel=%s click-through=%s restored=%s"):format(tostring(off1), tostring(through),
                                                             tostring(back)))

  -- ---- 9. the refusals ----------------------------------------------------------------------------------
  refuses("widget:onClick is refused, naming the widget's OWN MouseDown -- a standing widget answers a click"
          .. " the way a widget answers a click, and there is no second way to hear about it",
          function() A.e:onClick(function() end) end, "MouseDown", "clickable(false)")
  refuses("...and an unknown pointer key is refused naming the four, which are the same four"
          .. " widget:on(key, fn) already answers to",
          function() hafen.vr():pointer("MouseClick", 10, 10) end, "MouseDown", "MouseMove", "Wheel")

  hafen.timer():after(0.4, function() phase4() end)
end

-- PHASES 4-5 -- input changes what a widget LOOKS like, and the surface has to see it. A button's press is a
-- new picture, and on the flat UI nothing has to notice because the screen is redrawn every frame; a panel in
-- the world is a texture re-uploaded only when its content changed. So: press and release the button, let the
-- frames run, and the uploads must have moved.
--
-- THE PRESS AND THE RELEASE ARE IN THE SAME STATEMENT, never held across a timer. haven's Button takes a UI
-- grab on its press and does NOT test coordinates on the way in, so while one is outstanding every mousedown
-- in the client re-enters Button.mousedown -- which overwrites its own grab handle and ORPHANS the first grab
-- for good, leaving a button that depresses on every click anywhere. A suite runs on the maintainer's real
-- character while they have a mouse in their hand: it must never leave a press outstanding.
phase4 = function()
  if not (S and S.a.e and S.a.e:exists() and S.brect) then return summary() end
  S.bx = S.brect.x + math.floor(S.brect.w / 2)
  S.by = S.brect.y + math.floor(S.brect.h / 2)
  S.u0 = counters().uploads
  poke(S.a.e, S.bx, S.by, "MouseDown", 1)
  poke(S.a.e, S.bx, S.by, "MouseUp", 1)
  hafen.timer():after(1.0, function() phase5() end)
end

phase5 = function()
  if not (S and S.a.e and S.a.e:exists()) then return summary() end
  local u1 = counters().uploads
  check(u1 > S.u0,
        "pressing a standing button REPAINTS the panel: what a widget looks like is not in its place, its"
        .. " size or its caption, so a texture redrawn only when its content changed has to be told a button"
        .. " threw its cached face away -- or the button in the world never visibly presses",
        ("uploads +%d over the press and release"):format(u1 - S.u0))

  manualCheck("look east: FOUR ordinary windows stand there -- '044.4 press me' with a button in it,"
              .. " '044.4 screen', '044.4 fixed', and '044.4 on the gob' above your own head. CLICK the"
              .. " button, then click the empty space beside it, then try to DRAG a window by its title bar,"
              .. " then click the bare ground. Then run ':t044-4 off'",
              "all four look like the client's own windows -- border, background, title bar, close button;"
              .. " the button depresses only when you click the BUTTON and does nothing when you click"
              .. " beside it; a title-bar drag does not move a standing window (its place is the anchor's);"
              .. " and a click on the ground still moves your character")
  summary()
end

-- NOTE on ':t044-4 off': it asserts the panels came back to the flat UI and THEN destroys them, because a
-- suite leaves nothing behind (TESTING.md). Everything vanishing is the round succeeding, not a fault.

-- THE TAKE-DOWN -- everything goes back, and nothing of this suite is left anywhere.
offRound = function()
  pass, fail, manual = 0, 0, 0
  if S == nil then
    check(false, "':t044-4' has been run first -- this round takes down what THAT one stood",
          "nothing of this suite is standing")
    return summary()
  end
  local aw, aRect, aSeen = S.a.w, S.aRect, S.aSeen or -1
  local ents = { S.a.e, S.b.e, S.c.e, S.d.e }
  local c = hafen.vr():widget()
  for _, e in ipairs(c:list()) do c:remove(e) end

  local gone = 0
  for _, e in ipairs(ents) do
    if not e:exists() then gone = gone + 1 end
  end
  local backAt = hafen.vr():pointer("MouseDown", 10, 10, 1)
  check((gone == 4) and (#c:list() == 0) and (backAt == false),
        ":remove ends every panel: each handle reports exists() false, the collection is empty, and the"
        .. " pointer now finds nothing standing anywhere -- input goes away with the surface it was routed to",
        ("gone=%d/4 standing=%d pointer=%s"):format(gone, #c:list(), tostring(backAt)))

  -- >= rather than ==: coming back re-adds the widget at the END of its parent's chain, so it returns on TOP
  -- of whatever was covering it and can be reachable at MORE points than it was -- never fewer.
  local rb = reachable(aRect, aw)
  check((aSeen > 0) and aw:exists() and (rb >= aSeen),
        "...and the panel goes back to the flat UI, hit-testable over its rectangle at every point it was"
        .. " before it stood -- taking the pointer in the world changed nothing about where it came from",
        ("alive=%s reachable %d -> %d of 9"):format(tostring(aw:exists()), aSeen, rb))

  for _, k in ipairs({ "a", "b", "c", "d" }) do
    local q = S[k]
    if q and q.w and q.w:exists() then q.w:destroy() end
  end
  S = nil
  summary()
end

hafen.slash():register("t044-4", run)   -- the only way in: a suite does not start itself
