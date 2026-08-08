-- 044.7 -- culling, and the ends of a surface. Self-checking suite; see specs/addons/TESTING.md and
-- specs/addons/044-spatial-ui/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. A panel nothing is looking at costs nothing. Drawing a standing widget is not the
-- quad -- it is a whole widget subtree, and, where the panel has a Draw handler, a Lua function, run every
-- frame. So a surface whose quad is not reaching the screen stops being drawn at all: no Draw handler, no
-- offscreen pass, no upload. What does NOT stop is Tick, because ticking is logic and it happens in the widget
-- tree, which a culled surface never leaves -- so nothing inside a panel drifts out of date while the player is
-- facing the other way, and the panel is correct the instant it comes back into view.
--
-- AND EVERY WAY A SURFACE ENDS RELEASES IT. :remove, the gob it stands on leaving, the widget it stands being
-- destroyed (which is what the server closing a container window does), :reload and disable: five doors, one
-- body, and the live-surface counter going back where it started is the proof that each of them freed the
-- texture rather than merely forgetting about it.
--
-- HOW IT PROVES THE CULLING WITHOUT ASKING YOU TO AIM THE CAMERA. It measures. Eight probes -- the same kind
-- and size as the panel, so what they measure is the rectangle the panel will occupy -- are stood in a ring
-- around you, and each is asked through widget:screen(x, y) (044.4's exact inverse of a click) where its four
-- corners are drawn. The one whose rectangle lies FURTHEST outside the screen is where the real panel goes.
-- Furthest, not merely outside: the camera sways on its own while the character stands still, and a panel six
-- pixels past the edge drifts back into view halfway through the measurement. So the assertion rests on a
-- projected rectangle this run measured, at both ends of the window, never on where you happen to be looking.
--
-- WHAT IT CANNOT AUTOMATE. Only a person can judge that a panel coming back into view looks right rather than
-- blank or frozen, which is the one thing culling can plausibly break. (A gob despawning under a standing
-- panel needs no line of its own: the removal drain calls the very body :remove does, and that body is
-- asserted here on both anchors. The panel left standing for the [manual] line is anchored to a POINT, so
-- walking away cannot end it -- a free entity has no gob to lose.)
--
-- It re-asserts its own premises -- the collection answers (044.2), an anchored entity is listed at its gob
-- (043.3) -- because a suite is read alone and must convince alone.
--
-- IT LEAVES ONE PANEL STANDING for the [manual] lines and takes it down on ':t044-7 off'. ':t044-7' at any time
-- starts over from an empty scene.
--
-- READ-ONLY: no permissions, no persistent state, nothing of the client's is borrowed at all -- every widget it
-- stands is one it built itself.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local T = 11                              -- world units per tile
local RING = 30 * T                       -- how far out the probes go: well inside the loaded map, far enough
                                          -- that most of a ring of eight is off any screen
local S                                   -- everything a run is holding

local function counters() return hafen.client():profiling():surfaces() end

local function listed(coll, h)
  for _, e in ipairs(coll:list()) do
    if e == h then return true end
  end
  return false
end

-- The SCREEN rectangle a standing panel's four corners are drawn at, or nil when it does not project at all
-- (behind the camera, or not in the scene). widget:screen(x, y) is the same corner map a click is resolved
-- through, so this is where the panel really is, not where we think it should be.
local function rectOf(e, w)
  local s = w:size()
  if s == nil then return nil end
  local r
  for _, q in ipairs({ { 0, 0 }, { s.x, 0 }, { 0, s.y }, { s.x, s.y } }) do
    local x, y = e:screen(q[1], q[2])
    if x == nil then return nil end
    if r == nil then
      r = { minx = x, maxx = x, miny = y, maxy = y }
    else
      r.minx = math.min(r.minx, x); r.maxx = math.max(r.maxx, x)
      r.miny = math.min(r.miny, y); r.maxy = math.max(r.maxy, y)
    end
  end
  return r
end

-- How far wholly OFF the screen that rectangle lies, in pixels; <= 0 means some of it is on the screen. A
-- panel that did not project at all answers -1 on purpose: we could not tell WHY it did not, so it is not
-- evidence of anything and must never be picked as the test case.
local function margin(r)
  if r == nil then return -1 end
  local v = hafen.ui():root():size()
  return math.max(0 - r.maxx, r.minx - v.x, 0 - r.maxy, r.miny - v.y)
end

-- ...and how much of a margin is enough to measure against for a second and a half. The camera sways on its
-- own even while the character stands still, so a panel six pixels past the edge -- which is exactly what the
-- first version of this suite picked, being the first one that was outside AT ALL -- drifts back into view
-- mid-window and reports half a claim. The winner is the FURTHEST out, and it must clear this.
local function minMargin()
  local v = hafen.ui():root():size()
  return math.max(100, math.floor(math.max(v.x, v.y) * 0.06))
end

local function rectStr(r)
  return (r == nil) and "did not project"
    or ("%d,%d..%d,%d"):format(r.minx, r.miny, r.maxx, r.maxy)
end

-- Take down everything this suite stood and destroy every widget it built.
local function clear()
  local c = hafen.vr():widget()
  for _, e in ipairs(c:list()) do c:remove(e) end
  if S then
    for _, w in ipairs(S.built) do
      if w and w:exists() then w:destroy() end
    end
  end
  S = nil
end

local phase2, phase3, phase4, phase5, phase6, offRound

local function run(args)
  if args and (args[1] == "off") then return offRound() end
  pass, fail, manual = 0, 0, 0             -- a re-run reports its own counts, not the last one's

  -- ---- 1. what the LAST life of the addon layer left behind ---------------------------------------------
  -- Read before this run touches anything: after a ':reload' or a disable there must be no surface anywhere,
  -- across every addon, because teardown ends each standing entity through the very body ':remove' uses. It is
  -- also this suite's own hygiene test -- a previous ':t044-7' that leaked would show up right here.
  local live0 = counters().live
  local mine0 = hafen.vr():widget():count()   -- what a previous ':t044-7' left up for its [manual] lines
  check((live0 - mine0) == 0,
        ("no surface was left standing before this run: the live-surface counter is %d across every addon and"
         .. " %d of those are this suite's own, still up from a previous run and taken down in a moment -- so"
         .. " nothing else in the client is holding one. That is what ':reload' and disable have to leave"
         .. " behind, because teardown ends each standing widget through the same body ':remove' does, freeing"
         .. " the texture rather than merely forgetting about it"):format(live0, mine0),
        ("live=%d, this suite's own=%d"):format(live0, mine0))

  clear()
  local okp, me = pcall(function() return hafen.player():gob() end)
  if not (okp and me and me:exists()) then
    check(false, "the suite is in the world (it stands panels in the 3D scene)", tostring(okp and me))
    return summary()
  end
  S = { built = {}, ticks = 0, draws = 0, probes = {} }
  local p = me:position()

  -- ---- the panel the culling checks are about ----------------------------------------------------------
  -- Tick and Draw both counted, in Lua, on the widget itself: that pair IS the claim. A Draw subscriber also
  -- makes this the WORST case for culling -- without it the panel would repaint once and hold, and "it stopped
  -- drawing" would prove nothing.
  local panel = hafen.ui():window():title("044.7 culling"):size(160, 64):position(340, 330)
  panel:on("Tick", function() S.ticks = S.ticks + 1 end)
  panel:on("Draw", function(ev)
    S.draws = S.draws + 1
    ev:g():text("ticks " .. S.ticks, 8, 10)
  end)
  S.panel = panel
  S.built[#S.built + 1] = panel

  -- ...and a second, plain one, for the four ENDINGS a program can drive.
  local ends = hafen.ui():widget():size(80, 40):position(340, 410)
  S.ends, S.endsParent, S.endsPos = ends, ends:parent(), ends:position()
  S.built[#S.built + 1] = ends

  -- ---- eight probes, to find a direction the camera cannot see ------------------------------------------
  -- Built exactly like the panel -- same kind, same size -- so the rectangle they measure IS the rectangle the
  -- panel will occupy there, rather than something smaller standing in for it.
  for i = 0, 7 do
    local ang = (i * math.pi) / 4
    local w = hafen.ui():window():title("044.7 probe"):size(160, 64)
    S.built[#S.built + 1] = w
    local ok, e = pcall(function()
      return hafen.vr():widget():add(w, p:offset(math.cos(ang) * RING, math.sin(ang) * RING)):facing("camera")
    end)
    if ok and e then
      S.probes[#S.probes + 1] = { e = e, w = w, dx = math.cos(ang) * RING, dy = math.sin(ang) * RING }
    end
  end
  if #S.probes == 0 then
    check(false, "eight probe panels could be stood in a ring around you, so an off-screen direction could be"
          .. " measured", "no probe could be stood at all")
    clear()
    return summary()
  end
  hafen.timer():after(1.2, function() phase2() end)
end

-- Pick the direction, put the real panel there, and throw the probes away.
phase2 = function()
  if S == nil then return end
  local chosen, best, seen = nil, -1, {}
  for _, pr in ipairs(S.probes) do
    local m = margin(rectOf(pr.e, pr.w))
    seen[#seen + 1] = ("%d"):format(m)
    if m > best then chosen, best = pr, m end
  end
  local c = hafen.vr():widget()
  for _, pr in ipairs(S.probes) do c:remove(pr.e) end
  for _, pr in ipairs(S.probes) do
    if pr.w:exists() then pr.w:destroy() end
  end
  if best < minMargin() then
    check(false, ("one of the eight probes standing 30 tiles out projects at least %d pixels clear of the"
          .. " screen, which is the direction the culling checks need. Less than that and the camera's own"
          .. " sway drifts the panel back into view during the measurement. Zoom in (or turn so the ring is"
          .. " not all in front of you) and run ':t044-7' again"):format(minMargin()),
          ("best margin %d px, all eight: %s"):format(best, table.concat(seen, " ")))
    clear()
    return summary()
  end
  S.dx, S.dy, S.margin = chosen.dx, chosen.dy, best
  local p = hafen.player():gob():position()
  S.ent = hafen.vr():widget():add(S.panel, p:offset(S.dx, S.dy)):facing("camera")
  hafen.timer():after(1.2, function() phase3() end)
end

-- It is standing where the camera cannot see it. Measure that, then start the window.
phase3 = function()
  if not (S and S.ent and S.ent:exists()) then
    check(false, "the panel stood at the measured off-screen point survived the frames it needed", "gone")
    if S then clear() end
    return summary()
  end
  local r, ct, v = rectOf(S.ent, S.panel), counters(), hafen.ui():root():size()
  S.m0 = margin(r)
  check((S.m0 >= minMargin()) and (ct.live == 1) and (ct.culled == 1),
        ("a panel standing where the camera cannot see it is CULLED, and that is measured rather than assumed:"
         .. " its four corners project to %s, %d pixels clear of the %dx%d screen -- the furthest out of eight"
         .. " probes stood in a ring, so there is room for the camera to sway without drifting it back in --"
         .. " and the counter says 1 of the 1 live surface is being skipped. There is no engine frustum test to"
         .. " lean on here (the client has none: it hands geometry to the GPU and lets it clip), but the"
         .. " corners a click is resolved through are already projected every frame, and against the view they"
         .. " landed in they ARE the test")
        :format(rectStr(r), S.m0, v.x, v.y),
        ("rect %s, margin %d px (need %d), live=%d culled=%d"):format(rectStr(r), S.m0, minMargin(),
          ct.live, ct.culled))

  -- ...and the premises this suite's own reads rest on, stated where they can fail (TESTING.md).
  local c = hafen.vr():widget()
  check(listed(c, S.ent) and (c:count() == 1) and (S.ent:widget() == S.panel),
        "the collection answers for it while it is culled -- being skipped by the draw is not being gone:"
        .. " :list() holds it, :count() counts it, and :widget() hands back the very widget that is standing",
        ("listed=%s count=%d :widget() is the panel=%s"):format(tostring(listed(c, S.ent)), c:count(),
          tostring(S.ent:widget() == S.panel)))

  S.base = { u = ct.uploads, f = ct.frames, d = S.draws, t = S.ticks }
  hafen.timer():after(1.5, function() phase4() end)
end

-- The window closed: Draw and the uploads must have stood still while Tick and the frames did not.
phase4 = function()
  if not (S and S.ent and S.ent:exists()) then
    check(false, "the culled panel was still standing when its measurement window closed", "gone")
    if S then clear() end
    return summary()
  end
  local ct = counters()
  local du, df = ct.uploads - S.base.u, ct.frames - S.base.f
  local dd, dt = S.draws - S.base.d, S.ticks - S.base.t
  local m1 = margin(rectOf(S.ent, S.panel))    -- ...and it was STILL off the screen when the window closed
  check((m1 > 0) and (dd == 0) and (du == 0) and (dt >= 10) and (df >= 10),
        ("while it is culled its Draw handler is not run at ALL and no offscreen pass is issued -- 0 draws and"
         .. " 0 uploads over %d frames -- while its Tick went on firing %d times. (It was %d pixels clear of"
         .. " the screen when the window opened and %d when it closed, so the whole measurement happened out"
         .. " of view.) That asymmetry is the whole claim: drawing a panel means running a widget subtree and"
         .. " a Lua function, which is what culling saves, and ticking is logic, which happens in the widget"
         .. " tree a culled surface never leaves, so nothing inside it drifts out of date while the player is"
         .. " facing the other way")
        :format(df, dt, S.m0, m1),
        ("draws +%d uploads +%d ticks +%d frames +%d; off-screen margin %d -> %d px"):format(dd, du, dt, df,
          S.m0, m1))

  local p = hafen.player():gob():position()
  S.ent:position(p:offset(2 * T, 0))       -- straight back in front of you, where the camera is centred
  S.base = { u = ct.uploads, f = ct.frames, d = S.draws, t = S.ticks }
  hafen.timer():after(1.2, function() phase5() end)
end

-- Back in view: it starts drawing again by itself.
phase5 = function()
  if not (S and S.ent and S.ent:exists()) then
    check(false, "the panel survived being moved back into view", "gone")
    if S then clear() end
    return summary()
  end
  local ct = counters()
  local r = rectOf(S.ent, S.panel)
  check((margin(r) <= 0) and (ct.culled == 0) and ((S.draws - S.base.d) > 0)
        and ((ct.uploads - S.base.u) > 0),
        ("...and moving it back in front of you starts it drawing again with nothing else done to it: it"
         .. " projects to %s, on the screen, the culled count is 0 and both its Draw handler and the offscreen"
         .. " pass picked straight back up. The picture it comes back with is CORRECT rather than stale,"
         .. " because a culled surface keeps its dirty flag and its last content signature untouched -- what"
         .. " changed out of sight is still a change the first frame it is looked at")
        :format(rectStr(r)),
        ("rect %s culled=%d draws +%d uploads +%d"):format(rectStr(r), ct.culled,
          S.draws - S.base.d, ct.uploads - S.base.u))

  -- ---- the ENDINGS: five doors, one body, and the counter is the proof --------------------------------
  local c, ends = hafen.vr():widget(), S.ends
  local p = hafen.player():gob():position()
  local e1 = c:add(ends, p:offset(0, 3 * T))
  local up = counters().live
  c:remove(e1)
  local back = counters().live
  check((up == 2) and (back == 1) and (not e1:exists()) and (ends:parent() == S.endsParent)
        and (ends:position().x == S.endsPos.x) and (ends:position().y == S.endsPos.y),
        ":remove releases the surface: the live count goes up by exactly one when the panel stands and back"
        .. " down when it is taken away, the handle reports :exists() false, and the widget itself is back on"
        .. " the flat UI where it stood from -- the texture is freed, and the widget it was drawing is not",
        ("live 1 -> %d -> %d, exists=%s, parent restored=%s, position %d,%d (was %d,%d)"):format(up, back,
          tostring(e1:exists()), tostring(ends:parent() == S.endsParent),
          ends:position().x, ends:position().y, S.endsPos.x, S.endsPos.y))

  local ok2, e2 = pcall(function() return c:add(ends, p:offset(0, 3 * T)) end)
  check(ok2 and e2 and e2:exists() and (counters().live == 2),
        "...and standing the SAME widget again afterwards is an ordinary :add, not an error: a put-back leaves"
        .. " the widget in exactly the state standing found it in, so nothing about having stood once is"
        .. " remembered anywhere",
        ok2 and ("live=%d exists=%s"):format(counters().live, tostring(e2 and e2:exists()))
          or (tostring(e2):gsub("^.-%.lua:%d+:%s*", "")))
  if ok2 and e2 then c:remove(e2) end

  local me = hafen.player():gob()
  local e3 = c:add(ends, me)
  local atGob
  for _, ov in ipairs(me:overlay():list()) do
    if ov:kind() == "widget" then atGob = ov end
  end
  local up3 = counters().live
  c:remove(e3)
  check((up3 == 2) and (atGob ~= nil) and (counters().live == 1) and (not e3:exists()),
        "the gob anchor ends the same way: a panel standing ON a game object is listed at that gob read-only"
        .. " while it stands (043.3) and :remove releases its surface exactly as it does for a free one --"
        .. " one body for both anchors, and the gob LEAVING calls that very body from the removal drain"
        .. " (D-102), so the despawn is this same line reached through a different door",
        ("live 1 -> %d -> %d, listed at the gob=%s, exists=%s"):format(up3, counters().live,
          tostring(atGob ~= nil), tostring(e3:exists())))

  -- The last door: the WIDGET dies under the entity. This is the body a server-destroyed container window
  -- runs -- the removal drain reaches it the same way -- so driving it with our own :destroy() is the same
  -- code path, one frame later.
  S.e4 = c:add(ends, p:offset(0, 3 * T))
  S.live4 = counters().live
  ends:destroy()
  hafen.timer():after(0.5, function() phase6() end)
end

phase6 = function()
  if S == nil then return end
  local c, ct = hafen.vr():widget(), counters()
  check((S.live4 == 2) and (not S.e4:exists()) and (ct.live == 1) and (c:count() == 1)
        and (not S.ends:exists()),
        "a standing panel ends with the widget it stands: destroying the content took the entity out of the"
        .. " collection and freed its surface within a frame, and the handle reports :exists() false. That is"
        .. " the fifth door and the one the server uses -- a container window it destroys is removed by the"
        .. " very same drain, so a panel hanging over a chest that is gone cannot outlive it",
        ("live %d -> %d, entity exists=%s, collection=%d, widget exists=%s"):format(S.live4, ct.live,
          tostring(S.e4:exists()), c:count(), tostring(S.ends:exists())))

  manualCheck("the '044.7 culling' window is standing two tiles east of where you were -- a whole window,"
              .. " title bar, border and background, with a 'ticks N' line inside it. Turn the camera (or"
              .. " walk) until it is off the screen, count to three, and bring it back -- then run"
              .. " ':t044-7 off'",
              "it is there the instant it returns, drawn whole and with its counter still climbing -- not"
              .. " blank, not frozen on the number it had when it left. (While it was off screen its Draw"
              .. " handler was not being run at all, which is what the checks above measured; the number kept"
              .. " climbing because Tick never stopped.)")
  manualCheck("finally run ':reload', then ':t044-7' once more, and paste back that run's FIRST line",
              "[pass] on 'no surface was left standing before this run' -- which is ':reload' leaving the"
              .. " live-surface count at exactly 0, every texture freed and every widget put back")
  summary()
end

-- ':t044-7 off' takes down the panel the run left standing for the [manual] lines and asserts that the last
-- surface went with it. Everything vanishing is the round succeeding, not a fault.
offRound = function()
  pass, fail, manual = 0, 0, 0
  if S == nil then
    check(false, "':t044-7' has been run first -- this round takes down what THAT one left standing",
          "nothing of this suite is standing")
    return summary()
  end
  local c = hafen.vr():widget()
  for _, e in ipairs(c:list()) do c:remove(e) end
  local ct = counters()
  check((ct.live == 0) and (ct.culled == 0) and (c:count() == 0) and (not S.ent:exists())
        and S.panel:exists() and (S.panel:parent() ~= nil),
        "nothing of this suite is left standing: the collection is empty, the live-surface count is back at 0"
        .. " and so is the culled count, so every texture the run allocated has been freed -- while the widget"
        .. " that was being drawn on the last of them is still alive on the flat UI, which is the whole"
        .. " difference between ending an entity and destroying a widget",
        ("live=%d culled=%d collection=%d entity exists=%s widget alive=%s"):format(ct.live, ct.culled,
          c:count(), tostring(S.ent:exists()), tostring(S.panel:exists())))
  clear()
  summary()
end

hafen.slash():register("t044-7", run)   -- the only way in: a suite does not start itself
