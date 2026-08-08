-- 044.5 -- the surface is a real root: focus, keyboard, popups, tooltips. Self-checking suite; see
-- specs/addons/TESTING.md and specs/addons/044-spatial-ui/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. The feature's whole rule is transparency: if it works on screen, it works in the
-- world. That is only true if the surface a standing widget lives on is a real UI ROOT rather than a texture
-- with clicks forwarded into it -- because focus, the keyboard, popup placement, tooltips and hover are all
-- resolved by the client relative to a root, and re-implementing each of them against a texture is how each
-- of them ends up subtly broken in a different way. So this task establishes, one by one, that they resolve.
--
-- WHAT IT FOUND. Focus and the keyboard already resolve: a surface is an ordinary non-focus-controlling child
-- of the root, so setfocus bubbles straight past it and the key comes back down the same chain -- nothing was
-- needed. POPUPS DO NOT: a dropdown's list adds itself to the flat root and places itself at its owner's root
-- position, both hard-wired, so the list belonging to a dropdown on a panel in the world would have opened on
-- the flat screen. Tooltips, the cursor and hover do not either: all three are walked down from the flat root,
-- which steps over a standing panel by construction (its surface is invisible, which is exactly what takes a
-- standing panel off the flat UI's hit test) and would hand it screen coordinates rather than the panel's
-- pixels. Those are this task's seams, and the checks below are what says they hold.
--
-- HOW IT IS DRIVEN. hafen.vr():pointer(key, x, y [, a]) is the very function haven.MapView calls, entered at
-- a screen point, and widget:screen(x, y) is its exact inverse -- so no click hardware is needed. New here:
-- widget:focused() (would a keystroke reach this widget), widget:tooltip()/:tooltip(s), and
-- hafen.ui():tipAt(x, y) (whose tooltip the client would show at a point).
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Pressing a key. Focus is asserted; the letters arriving is the one thing a
-- program genuinely cannot do here, so it is a [manual] line, as is judging that the list looks right.
--
-- It re-asserts its own premises -- standing takes a widget off the flat UI's hit test (044.1), a poke lands
-- on the panel it is aimed at (044.4) -- because a suite is read alone and must convince alone.
--
-- IT LEAVES ITS SET STANDING for the [manual] lines and takes it down on ':t044-5 off'. ':t044-5' at any time
-- starts over from an empty scene.
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

local T = 11                              -- world units per tile
local S                                   -- what a round left standing

-- ---- the two directions, and the one door input comes through -----------------------------------------

-- Put the pointer on whatever stands at the SCREEN point panel pixel (wx, wy) is drawn at.
local function poke(e, wx, wy, key, arg)
  local sx, sy = e:screen(wx, wy)
  if sx == nil then return nil, nil, nil end
  if arg == nil then return hafen.vr():pointer(key, sx, sy), sx, sy end
  return hafen.vr():pointer(key, sx, sy, arg), sx, sy
end

-- A press and its release, never held apart: haven's Button takes a UI grab on its press and does NOT test
-- coordinates on the way in, so a press left outstanding becomes a client-wide interceptor (044.4).
local function click(e, wx, wy)
  local took = poke(e, wx, wy, "MouseDown", 1)
  poke(e, wx, wy, "MouseUp", 1)
  return took
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

-- Where a child sits inside a WINDOW, in the window's own OUTER pixels -- which is what a surface pixel is.
-- A window's chrome inset belongs to the client: a child's :position() reads inner coordinates and no verb
-- converts them, so the offset is MEASURED down the flat UI's own hit test before the window stands (044.4).
--
-- THREE sweeps, not two, and the first one tries NINE columns rather than the window's middle alone: a
-- picture button is only as wide as its picture, so a single mid-window column need not cross it. Find any
-- row that hits, then that row's x range, then the y range down the middle of THAT.
--
-- `row` (an outer-pixel y known to cross the child) skips the search outright, and is how the DROP ARROW is
-- found: it is right-aligned inside its dropdown and narrow, so no fixed set of columns is guaranteed to
-- cross it -- but the dropdown's own middle row is, because the arrow is centred in it.
local function childRect(win, kid, row)
  if (win == nil) or (kid == nil) then return nil end
  local wp, ws = win:rootPos(), win:size()
  if (wp == nil) or (ws == nil) then return nil end
  local my = row and (wp.y + row) or nil
  for _, f in ipairs((my ~= nil) and {} or { 0.5, 0.3, 0.7, 0.2, 0.8, 0.1, 0.9, 0.4, 0.6 }) do
    local cx = wp.x + math.floor(ws.x * f)
    local y0, y1
    for y = 0, ws.y - 1 do
      if within(hafen.ui():at(cx, wp.y + y), kid) then
        y0 = y0 or y
        y1 = y
      end
    end
    if y0 ~= nil then
      my = wp.y + math.floor((y0 + y1) / 2)
      break
    end
  end
  if my == nil then return nil end
  local x0, x1
  for x = 0, ws.x - 1 do
    if within(hafen.ui():at(wp.x + x, my), kid) then
      x0 = x0 or x
      x1 = x
    end
  end
  if x0 == nil then return nil end
  local mx = wp.x + math.floor((x0 + x1) / 2)
  local y0, y1
  for y = 0, ws.y - 1 do
    if within(hafen.ui():at(mx, wp.y + y), kid) then
      y0 = y0 or y
      y1 = y
    end
  end
  if y0 == nil then return nil end
  return { x = x0, y = y0, w = (x1 - x0) + 1, h = (y1 - y0) + 1 }
end

-- The middle of such a box, in the same OUTER pixels -- a surface point on a standing window.
local function mid(r) return r.x + math.floor(r.w / 2), r.y + math.floor(r.h / 2) end

-- The first descendant whose :type() matches -- how the drop arrow inside a dropdown is found, since it is
-- the client's own child widget and no verb names it.
local function findKid(w, pat)
  local found
  w:walk(function(x)
    if (found == nil) and (x ~= w) and x:type():find(pat) then found = x end
  end)
  return found
end

local function counters() return hafen.client():profiling():surfaces() end

local function clear()
  local c = hafen.vr():widget()
  for _, e in ipairs(c:list()) do c:remove(e) end
  if S then
    for _, k in ipairs({ "a", "b", "d", "flat" }) do
      local w = S[k] and S[k].w
      if w and w:exists() then w:destroy() end
    end
  end
  S = nil
end

local phase2, phase3, phase4, offRound

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
  S = {}
  local p = me:position()

  -- A -- the panel everything is asked of: a real window with a text entry, a dropdown and a button carrying
  -- a tooltip. Camera-facing, so it is square-on wherever the camera happens to be.
  S.a = { w = hafen.ui():window():title("044.5 focus"):size(260, 200):position(300, 160) }
  S.entry = hafen.ui():entry():parent(S.a.w):position(10, 10):size(170, 20):value("click me")
  -- NO :size() on the dropdown, deliberately: SDropBox places its drop arrow ONCE, right-aligned against the
  -- width it was BUILT with, and overrides resize nowhere -- so a resized dropdown leaves its arrow outside
  -- its own box, clipped and unhittable. That is a 040.10 gap and nothing to do with standing in the world,
  -- so this suite uses the default width rather than testing the feature through a broken control.
  S.dd = hafen.ui():dropdown():parent(S.a.w):position(10, 50):rows({ "smelt", "melt", "quench" })
  S.btn = hafen.ui():button():parent(S.a.w):position(10, 100):size(120, 26):text("Smelt")
                    :tooltip("044.5 world tip")
  S.arrow = findKid(S.dd, "CheckBox")

  -- B -- a second standing panel with an entry of its own: focus LEAVING is what this one is for.
  S.b = { w = hafen.ui():window():title("044.5 second"):size(180, 70):position(300, 400) }
  S.entry2 = hafen.ui():entry():parent(S.b.w):position(10, 10):size(140, 20):value("or me")

  -- D -- one picture button and nothing else, so the panel is otherwise static and its upload counter means
  -- what it says: a hover state that changed is then the only thing that can move it.
  S.d = { w = hafen.ui():window():title("044.5 hover"):size(120, 70):position(560, 160) }
  S.hbtn = hafen.ui():button():image("gfx/hud/buttons/addu", "gfx/hud/buttons/addd")
                     :parent(S.d.w):position(20, 10)

  -- FLAT -- the control: an ordinary window left on the flat UI, carrying a tooltip of its own. The seams
  -- this task adds must leave the ordinary path answering exactly as it did.
  S.flat = { w = hafen.ui():window():title("044.5 flat"):size(150, 50):position(680, 120) }
  S.flabel = hafen.ui():label():parent(S.flat.w):position(10, 10):text("flat tip here")
                       :tooltip("044.5 flat tip")

  -- Every box below is MEASURED on the flat UI, while these windows are still there to measure them on.
  S.erect  = childRect(S.a.w, S.entry)
  S.brect  = childRect(S.a.w, S.btn)
  S.ddrect = childRect(S.a.w, S.dd)
  S.arect  = S.ddrect and childRect(S.a.w, S.arrow, S.ddrect.y + math.floor(S.ddrect.h / 2))
  S.e2rect = childRect(S.b.w, S.entry2)
  S.hrect  = childRect(S.d.w, S.hbtn)
  S.frect  = childRect(S.flat.w, S.flabel)
  S.aRect  = rect(S.a.w)
  S.aSeen  = reachable(S.aRect, S.a.w)

  S.a.e = hafen.vr():widget():add(S.a.w, p:offset(2 * T, 0)):facing("camera")
  S.b.e = hafen.vr():widget():add(S.b.w, p:offset(2 * T, 3 * T)):facing("camera")
  S.d.e = hafen.vr():widget():add(S.d.w, p:offset(2 * T, -3 * T)):facing("camera")

  hafen.timer():after(1.5, function() phase2() end)
end

phase2 = function()
  if not (S and S.a.e and S.a.e:exists() and S.b.e:exists() and S.d.e:exists()) then
    check(false, "the standing set survived the frames it needed to be drawn once", "gone")
    return summary()
  end
  local A, B = S.a, S.b

  -- ---- 1. the premises: the panels stand, hosted by a surface, off the flat UI's hit test ---------------
  local host = A.w:parent()
  check((host ~= nil) and (host:type() == "WidgetSurface") and (S.aSeen > 0)
        and (reachable(S.aRect, A.w) == 0) and (click(A.e, 200, 175) == true),
        "the premises hold: the standing panel is hosted by a SURFACE of its own rather than by the flat"
        .. " root, standing took it off the flat UI's hit test, and a poke at one of its pixels reaches it"
        .. " -- so everything below is being asked of a widget that is in the world",
        ("host=%s flat-reachable %d->%d of 9"):format(host and host:type(), S.aSeen,
                                                      reachable(S.aRect, A.w)))

  if (S.erect == nil) or (S.brect == nil) or (S.arect == nil) or (S.e2rect == nil) then
    check(false, "the entry, the button, the dropdown's arrow and the second panel's entry were all"
          .. " measurable inside their windows on the flat UI (childRect), which every point below is"
          .. " aimed by",
          ("entry=%s button=%s dropdown=%s arrow-widget=%s arrow-box=%s entry2=%s"):format(
            tostring(S.erect ~= nil), tostring(S.brect ~= nil), tostring(S.ddrect ~= nil),
            S.arrow and S.arrow:type() or "nil", tostring(S.arect ~= nil), tostring(S.e2rect ~= nil)))
    return summary()
  end

  -- ---- 2. a click gives a standing text entry the keyboard ----------------------------------------------
  local ex, ey = mid(S.erect)
  click(A.e, ex, ey)
  local focA, focB, focFlat = S.entry:focused(), S.entry2:focused(), S.flat.w:focused()
  check((focA == true) and (focB == false) and (focFlat == false),
        "clicking a text entry STANDING IN THE WORLD gives it the keyboard: the focus chain the client walks"
        .. " down from its own root to deliver a keystroke ends at that entry, exactly as it would for the"
        .. " same window on the flat UI -- the surface is an ordinary link in that chain and changes nothing",
        ("standing entry=%s other standing entry=%s flat window=%s"):format(
          tostring(focA), tostring(focB), tostring(focFlat)))

  -- ---- 3. ...and it leaves again, to another panel in the world -----------------------------------------
  click(B.e, mid(S.e2rect))
  local leftA, gotB = S.entry:focused(), S.entry2:focused()
  check((leftA == false) and (gotB == true),
        "...and clicking the entry on the OTHER standing panel takes it away again: focus moves between two"
        .. " widgets in the world the same way it moves between two windows on screen, and neither of them"
        .. " is a special case for the client",
        ("first=%s second=%s"):format(tostring(leftA), tostring(gotB)))
  click(A.e, ex, ey)                       -- put it back on A, for the [manual] typing line

  -- ---- 4. a dropdown opens its list INSIDE the surface --------------------------------------------------
  -- The one thing this task had to change in the client: a popup adds itself to ui.root and places itself at
  -- its owner's ROOT position, both hard-wired, so a list belonging to a dropdown on a panel in the world
  -- would have opened on the flat screen. It now opens into the panel's own root.
  local before = #host:children()
  local ax, ay = mid(S.arect)
  click(A.e, ax, ay)
  local opened, popup = #host:children(), nil
  for _, k in ipairs(host:children()) do
    if k ~= A.w then popup = k end
  end
  local ptype = popup and popup:type() or "<none>"
  local inside = (popup ~= nil) and (popup:parent() == host) and (host ~= hafen.ui():root())
  click(A.e, ax, ay)                       -- close it: an open list holds a client-wide mouse grab
  local closed = #host:children()
  if closed > before then                  -- belt and braces: nothing of this suite may keep the mouse
    click(A.e, ax, ay)
    closed = #host:children()
  end
  check((before == 1) and (opened == 2) and inside and (ptype == "SDropList") and (closed == 1),
        "a dropdown standing in the world opens its list INSIDE the panel: the popup's parent is the very"
        .. " surface the dropdown's window stands on, not the flat root -- which is where it would have"
        .. " gone, since the client adds a list to ui.root at its owner's root position and both of those"
        .. " were spelled out rather than asked for. Clicking the arrow again takes it away",
        ("children %d -> %d -> %d, popup=%s parent-is-the-surface=%s"):format(
          before, opened, closed, ptype, tostring(inside)))

  -- ---- 5. a tooltip resolves against the surface --------------------------------------------------------
  local bx, by = mid(S.brect)
  local tsx, tsy = A.e:screen(bx, by)
  local tw = (tsx ~= nil) and hafen.ui():tipAt(tsx, tsy) or nil
  check((tw ~= nil) and (tw == S.btn) and (tw:tooltip() == "044.5 world tip"),
        "a tooltip on a standing widget is the STANDING WIDGET'S: asked at the screen point that button is"
        .. " drawn at, the client answers with that button and its own text -- so the query it runs at the"
        .. " cursor every frame reaches into the panel, in the panel's pixels, instead of walking past an"
        .. " invisible surface and answering with the map behind it",
        ("tipAt -> %s, tooltip=%s"):format(tw and tw:type() or "nil", tw and tostring(tw:tooltip())))

  -- ---- 6. ...and the flat UI still answers exactly as it did --------------------------------------------
  if S.frect == nil then
    check(false, "the flat control window's label was measurable on the flat UI", "childRect nil")
  else
    local fp = S.flat.w:rootPos()
    local fx, fy = mid(S.frect)
    local fw = (fp ~= nil) and hafen.ui():tipAt(fp.x + fx, fp.y + fy) or nil
    check((fw ~= nil) and (fw == S.flabel) and (fw:tooltip() == "044.5 flat tip"),
          "...and a tooltip on the FLAT UI still resolves the way it always did -- the seam asks the panel"
          .. " under the pointer first and otherwise runs the client's own walk untouched, so a client with"
          .. " nothing standing behaves exactly as before",
          ("tipAt -> %s, tooltip=%s"):format(fw and fw:type() or "nil", fw and tostring(fw:tooltip())))
  end

  -- ---- 7. the refusals ----------------------------------------------------------------------------------
  refuses("widget:focused() is a READ and refuses an argument, naming what moves focus -- a verb that stole"
          .. " the keyboard would be a second way to do what a click already does",
          function() S.entry:focused(true) end, "focus follows the click")
  refuses("...and hafen.ui():tipAt(x, y) refuses a missing coordinate rather than guessing one",
          function() hafen.ui():tipAt(10) end, "y")

  hafen.timer():after(0.4, function() phase3() end)
end

-- PHASES 3-4 -- a hover state is a NEW PICTURE, and a panel in the world is a texture re-uploaded only when
-- its content changed. So: let the frames settle with the pointer nowhere near the button, take the counter,
-- move the pointer onto it, and the counter must move.
phase3 = function()
  if not (S and S.d.e and S.d.e:exists()) then return summary() end
  if S.hrect == nil then
    check(false, "the picture button was measurable inside its own window on the flat UI (childRect)", "nil")
    return summary()
  end
  S.u0 = counters().uploads
  hafen.timer():after(0.6, function()
    if not (S and S.d.e and S.d.e:exists()) then return summary() end
    S.u1 = counters().uploads
    local hx, hy = mid(S.hrect)            -- two returns, so never inlined mid-argument-list
    poke(S.d.e, hx, hy, "MouseMove")
    hafen.timer():after(0.6, function() phase4() end)
  end)
end

phase4 = function()
  if not (S and S.d.e and S.d.e:exists()) then return summary() end
  local u2 = counters().uploads
  check(((S.u1 - S.u0) <= 1) and (u2 > S.u1),
        "moving the pointer onto a picture button standing in the world REPAINTS the panel, while the same"
        .. " panel left alone repaints not at all: a hover state is not in a widget's place, its size or its"
        .. " caption, so a texture drawn only when its content changed has to be told the button threw its"
        .. " cached face away -- or the button in the world never lights up",
        ("uploads idle +%d over 0.6s, then +%d over the hover"):format(S.u1 - S.u0, u2 - S.u1))

  manualCheck("look east: THREE windows stand there -- '044.5 focus', '044.5 second' below it and"
              .. " '044.5 hover' beside it. '044.5 focus' already has the keyboard from the checks above,"
              .. " in its text field: TYPE a few letters WITHOUT clicking anything first",
              "the letters appear in the field ON THE PANEL IN THE WORLD as you type, and nowhere else --"
              .. " the client's own caret and editing, in a window that is not on the screen")
  manualCheck("then, on '044.5 focus': HOVER the Smelt button, and CLICK the dropdown under the text field"
              .. " and pick a row. Then run ':t044-5 off'",
              "the button's tooltip appears beside the cursor; the dropdown's list opens ON THE PANEL, in"
              .. " the world, right under the dropdown it belongs to -- not as a strip on the flat screen"
              .. " -- and picking a row closes it and puts that row in the box")
  summary()
end

-- NOTE on ':t044-5 off': it asserts what standing gave back and THEN destroys everything, because a suite
-- leaves nothing behind (TESTING.md). Everything vanishing is the round succeeding, not a fault.

offRound = function()
  pass, fail, manual = 0, 0, 0
  if S == nil then
    check(false, "':t044-5' has been run first -- this round takes down what THAT one stood",
          "nothing of this suite is standing")
    return summary()
  end
  local aw, aRect, aSeen = S.a.w, S.aRect, S.aSeen or -1
  local ents = { S.a.e, S.b.e, S.d.e }
  local c = hafen.vr():widget()
  for _, e in ipairs(c:list()) do c:remove(e) end

  local gone = 0
  for _, e in ipairs(ents) do
    if not e:exists() then gone = gone + 1 end
  end
  -- >= rather than ==: coming back re-adds the widget at the END of its parent's chain, so it returns on TOP
  -- of whatever was covering it and can be reachable at MORE points than it was -- never fewer.
  local rb = reachable(aRect, aw)
  check((gone == 3) and (#c:list() == 0) and (aSeen > 0) and aw:exists() and (rb >= aSeen),
        ":remove ends every panel and the widget goes back to the flat UI, hit-testable over its rectangle"
        .. " at every point it was before it stood -- having been a root in the world for a while changed"
        .. " nothing about where it came from",
        ("gone=%d/3 standing=%d reachable %d -> %d of 9"):format(gone, #c:list(), aSeen, rb))

  -- ...and with nothing standing, the point queries are the client's own again, untouched.
  local fp, fw = S.flat.w:rootPos(), nil
  if (fp ~= nil) and (S.frect ~= nil) then
    local fx, fy = mid(S.frect)
    fw = hafen.ui():tipAt(fp.x + fx, fp.y + fy)
  end
  check((fw ~= nil) and (fw == S.flabel),
        "...and with nothing standing anywhere the tooltip query is the client's own walk again, answering"
        .. " on the flat window exactly as it did before any of this stood -- the seam is inert when there"
        .. " is no surface to ask",
        ("tipAt -> %s"):format(fw and fw:type() or "nil"))

  for _, k in ipairs({ "a", "b", "d", "flat" }) do
    local w = S[k] and S[k].w
    if w and w:exists() then w:destroy() end
  end
  S = nil
  summary()
end

hafen.slash():register("t044-5", run)   -- the only way in: a suite does not start itself
