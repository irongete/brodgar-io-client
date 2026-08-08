-- 044.3 -- "camera": the mode the whole thing is for. Self-checking suite; see specs/addons/TESTING.md and
-- specs/addons/044-spatial-ui/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. The third facing mode stops raising. :facing("camera") is a REAL WORLD QUAD that
-- turns to the viewer in yaw and pitch -- so it keeps its world size, its perspective and its occlusion, and
-- shrinks as you walk away, which is everything a "screen" blit gives up in exchange for constant pixels. It
-- reaches SPRITES AND STANDING WIDGETS both, because they share the entity core and the mode lives there:
-- one vocabulary of three, not one per kind. A model (a ghost, an object) has no facing at all. :rotate(a)
-- stays the entity's own angle in every mode -- unused while the camera decides the turn, honoured again the
-- moment it is "fixed" -- and swapping the mode swaps a quad, not the surface: the widget, its texture and
-- its offscreen pass are the same objects before and after.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Nothing readable through hafen.* says what a quad LOOKS like from where
-- the camera happens to be: "square-on", "occluded" and "shrank as I walked away" are the eye's answers, and
-- they are the three [manual] lines. Everything else -- every mode read back, every refusal, the angle kept
-- across the swaps, the surface untouched, the collections unchanged, the upload counter -- is asserted.
--
-- The counter rounds need FRAMES, and a slash command returns long before the next one. So the rounds are
-- chained on hafen.timer():after, exactly as :t044-1 does.
--
-- IT LEAVES ITS SET STANDING for the [manual] look and takes it down on ':t044-3 off', which is where the
-- going-back assertions live. ':t044-3' at any time starts over from an empty scene.
--
-- It re-asserts its own premises -- hafen.vr() and its collections are each one object (043.1), standing a
-- widget takes it off the flat UI's hit test (044.1), both anchors take one (044.2), and the retired
-- sprite:billboard(b) still throws naming :facing (043.5) -- because a suite is read alone and must convince
-- alone. Assets are addon-relative and sandboxed, so it ships its own icon.png and tri.gltf.
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
local function why(f)
  local ok, err = pcall(f)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

local function refuses(what, fn, ...)
  local err = why(fn) or "<no error>"
  local ok = true
  for _, want in ipairs({ ... }) do
    if err:find(want, 1, true) == nil then ok = false end
  end
  check(ok, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function near(a, b, tol) return (a ~= nil) and (b ~= nil) and (math.abs(a - b) < (tol or 0.6)) end

local T = 11                              -- world units per tile
local RES = "gfx/terobjs/arch/logcabin"   -- a .res every client has (the docs' own example)

local icon, mesh                          -- this suite's own assets (hafen.asset, loaded once at Load)
local S                                   -- what a round left standing

hafen.event():on("Load", function()
  icon = hafen.asset():get("icon.png")
  mesh = hafen.asset():get("tri.gltf")
end)

local function counters() return hafen.client():profiling():surfaces() end

-- Is the widget the flat UI's hit test landed on `w` itself, or something inside it?
local function within(hit, w)
  for _ = 1, 16 do
    if hit == nil then return false end
    if hit == w then return true end
    hit = hit:parent()
  end
  return false
end

-- Nine points spread over a widget's SCREEN rectangle. Taken before it stands: standing re-homes the widget,
-- so its own :position() becomes surface-local and the rectangle would move to the screen corner.
local function rect(w)
  local p, s = w:position(), w:size()
  local pts = {}
  for _, fx in ipairs({ 0.2, 0.5, 0.8 }) do
    for _, fy in ipairs({ 0.1, 0.5, 0.9 }) do
      pts[#pts + 1] = { math.floor(p.x + (s.x * fx)), math.floor(p.y + (s.y * fy)) }
    end
  end
  return pts
end

-- How many of those points the flat UI resolves to that widget right now.
local function reachable(pts, w)
  local n = 0
  for _, q in ipairs(pts) do
    if within(hafen.ui():at(q[1], q[2]), w) then n = n + 1 end
  end
  return n
end

-- Take down everything this suite stood, and destroy every widget it built.
local function clear()
  for _, c in ipairs({ hafen.vr():widget(), hafen.vr():ghost(), hafen.vr():sprite(), hafen.vr():object() }) do
    for _, e in ipairs(c:list()) do c:remove(e) end
  end
  if S then
    for _, k in ipairs({ "cam", "fix", "scr", "rider", "still" }) do
      local w = S[k]
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
  if not (okp and me and me:exists() and icon and mesh) then
    check(false, "the suite is in the world with its own assets loaded (it stands things in the 3D scene)",
          ("player=%s icon=%s mesh=%s"):format(tostring(okp and me), tostring(icon), tostring(mesh)))
    return summary()
  end
  clear()                                  -- a re-run starts from an empty scene
  S = {}
  S.u0 = counters().uploads                -- before anything stands: the fade-in budget phase 2 checks
  local p = me:position()

  -- ---- 0. the premises this task's claims rest on (043.1, 043.5) --------------------------------------
  check((hafen.vr() == hafen.vr()) and (hafen.vr():widget() == hafen.vr():widget())
        and (hafen.vr():sprite() == hafen.vr():sprite()),
        "the premise holds: hafen.vr() and each of its collections is ONE object, so the mode this task adds"
        .. " is added in one place",
        tostring(hafen.vr():widget() == hafen.vr():widget()))

  -- ---- 1. a standing widget starts "fixed", and the flat UI has let go of it (044.1) -------------------
  local cam = hafen.ui():window():title("044.3 camera"):size(180, 110):position(330, 230)
  S.cam = cam
  S.camRect = rect(cam)
  local camSeen = reachable(S.camRect, cam)
  local we = hafen.vr():widget():add(cam, p:offset(2 * T, 0))
  S.we, S.camSeen = we, camSeen
  S.camW = cam:size().x                    -- a window's OUTER size (chrome included), read now so the swap
                                           -- below is compared against what it actually was, not a guess
  check(we:exists() and (we:facing() == "fixed") and (camSeen > 0)
        and (reachable(S.camRect, cam) == 0),
        "a standing widget answers :facing() -- \"fixed\" until it is told otherwise -- and standing it took"
        .. " it off the flat UI's hit test, which is the premise everything below stands on",
        ("facing=%s reachable %d->%d of 9"):format(tostring(we:facing()), camSeen,
                                                   reachable(S.camRect, cam)))

  -- ---- 2. "camera" stops raising, on a widget ---------------------------------------------------------
  local ret = we:facing("camera")
  check((ret == we) and (we:facing() == "camera"),
        ":facing(\"camera\") is ACCEPTED on a standing widget, reads back, and hands the handle back so it"
        .. " chains -- 043.5 refused this very word until this task",
        ("returned self=%s facing=%s"):format(tostring(ret == we), tostring(we:facing())))

  -- ---- 3. ...and on a sprite, off the same shared core -------------------------------------------------
  local sp = hafen.vr():sprite():add(icon, p:offset(2 * T, 2 * T))
  S.sp = sp
  local sp0 = sp:facing()
  check((sp0 == "fixed") and (sp:facing("camera") == sp) and (sp:facing() == "camera"),
        "...and on a SPRITE too, through the very same verb: the mode lives on the shared entity core, so it"
        .. " is one vocabulary over both flat kinds rather than one per kind",
        ("was=%s now=%s"):format(tostring(sp0), tostring(sp:facing())))

  -- ---- 4. the swap swaps a quad, not the surface -------------------------------------------------------
  check(we:exists() and (we:widget() == cam) and cam:exists() and (cam:size().x == S.camW)
        and (counters().live == 1) and (reachable(S.camRect, cam) == 0),
        "...and changing the mode is not a re-stand: the same widget is in the same surface, one surface is"
        .. " live, the window is the same size it was, and the flat UI still finds nothing of it",
        ("same=%s live=%s w=%s (was %s) reachable=%d"):format(
          tostring(we:widget() == cam), tostring(counters().live), tostring(cam:size().x),
          tostring(S.camW), reachable(S.camRect, cam)))

  -- ---- 5. all THREE modes read back, on both kinds -----------------------------------------------------
  local seen, okAll = {}, true
  for _, m in ipairs({ "fixed", "camera", "screen", "fixed" }) do
    we:facing(m)
    sp:facing(m)
    seen[#seen + 1] = we:facing() .. "/" .. sp:facing()
    if (we:facing() ~= m) or (sp:facing() ~= m) then okAll = false end
  end
  check(okAll,
        "all THREE modes read back on both kinds and round-trip in any order -- \"fixed\", \"camera\" and"
        .. " \"screen\" are values of one property, not three shapes",
        table.concat(seen, " "))

  -- ---- 6. the refusals, and the spelling this replaced -------------------------------------------------
  refuses("an unknown facing is still refused, and the message now names all three modes",
          function() we:facing("wall") end, "fixed", "camera", "screen")
  refuses("...on a sprite as well, and a mode that is not a string is refused as a TYPE",
          function() sp:facing(true) end, "mode STRING", "camera")
  refuses("the retired sprite:billboard(b) still throws naming :facing(mode) -- the boolean this third mode"
          .. " could never have been (043.5)",
          function() sp:billboard(true) end, "facing")

  -- ---- 7. :rotate is the entity's own angle, kept through every mode -----------------------------------
  we:facing("fixed")
  we:rotate(0.75)
  local angles = {}
  for _, m in ipairs({ "camera", "screen", "fixed" }) do
    we:facing(m)
    angles[#angles + 1] = we:rotate()
  end
  check(near(angles[1], 0.75, 0.001) and near(angles[2], 0.75, 0.001) and near(angles[3], 0.75, 0.001),
        ":rotate(a) is the entity's own angle and survives every mode -- STORED while \"camera\" and"
        .. " \"screen\" decide the turn themselves, and honoured again the moment it is \"fixed\"",
        ("%.3f %.3f %.3f (wrote 0.750)"):format(angles[1], angles[2], angles[3]))

  -- ---- 8. the mode is the entity's, not the anchor's (044.2) -------------------------------------------
  local rider = hafen.ui():window():title("044.3 rider"):size(150, 90):position(120, 230)
  S.rider = rider
  local rRect = rect(rider)
  local rSeen = reachable(rRect, rider)
  local ae = hafen.vr():widget():add(rider, me)
  ae:facing("camera")
  -- ...and FLOATED. A camera-facing quad rises along the camera's up axis, so at a fully top-down camera it
  -- lies in the horizontal plane through its anchor -- at ground level that is the terrain's own plane, and
  -- the terrain wins. :offset(x, y, z) is the verb that answers it, and this stands the demonstration.
  ae:offset(0, 0, 14)
  S.ae = ae
  local rp, mp, roff = ae:position(), me:position(), ae:offset()
  check(ae:exists() and (ae:facing() == "camera") and (rp ~= nil)
        and near(rp:x(), mp:x(), 11.0) and near(rp:y(), mp:y(), 11.0)
        and (roff ~= nil) and near(roff.z, 14, 0.01)
        and (rSeen > 0) and (reachable(rRect, rider) == 0),
        "one standing ON a gob takes \"camera\" too, still reports its gob's place, and takes the"
        .. " :offset(x, y, z) that floats it clear of the ground: the mode is a property of the entity, and"
        .. " where it sits is a different question",
        ("facing=%s at %.1f,%.1f (gob at %.1f,%.1f) z=%s reachable %d->%d"):format(
          tostring(ae:facing()), rp and rp:x() or -1, rp and rp:y() or -1, mp:x(), mp:y(),
          tostring(roff and roff.z), rSeen, reachable(rRect, rider)))

  -- ---- 9. a mode change is not a lifecycle event -------------------------------------------------------
  local l = hafen.vr():widget():list()
  check((#l == 2) and (l[1] == we) and (l[2] == ae) and (hafen.vr():widget():find("rider") == ae)
        and (#hafen.vr():list() == 3),
        "...and nothing was born or died along the way: both standing widgets are still their collection's,"
        .. " in the order they were stood, and hafen.vr():list() still holds them beside the sprite",
        ("widgets=%d find=%s section=%d"):format(#l, tostring(hafen.vr():widget():find("rider") == ae),
                                                 #hafen.vr():list()))

  -- ---- 10. a MODEL has no facing at all ----------------------------------------------------------------
  local gh = hafen.vr():ghost():add(RES, p:offset(-3 * T, 0))
  local ob = hafen.vr():object():add(mesh, p:offset(-3 * T, 2 * T))
  S.gh, S.ob = gh, ob
  check((gh.facing == nil) and (ob.facing == nil) and (we.facing ~= nil) and (sp.facing ~= nil),
        "a ghost and an object carry NO :facing -- a model already meets the viewer from every side, so the"
        .. " verb is on the two FLAT kinds and nowhere else",
        ("ghost=%s object=%s widget=%s sprite=%s"):format(tostring(gh.facing), tostring(ob.facing),
                                                          type(we.facing), type(sp.facing)))

  -- ---- the set the [manual] lines are about, and the counter rounds ------------------------------------
  we:facing("camera")
  sp:facing("camera")
  local fix = hafen.ui():window():title("044.3 fixed"):size(180, 110):position(330, 380)
  S.fix = fix
  S.fe = hafen.vr():widget():add(fix, p:offset(2 * T, -2 * T))          -- left "fixed": the control
  local scr = hafen.ui():window():title("044.3 screen"):size(180, 110):position(540, 380)
  S.scr = scr
  S.se = hafen.vr():widget():add(scr, p:offset(2 * T, -4 * T)):facing("screen")
  S.fixSp = hafen.vr():sprite():add(icon, p:offset(2 * T, 4 * T))       -- a "fixed" sprite, for the same look
  S.scrSp = hafen.vr():sprite():add(icon, p:offset(4 * T, 2 * T)):facing("screen")
                                          -- ...and a "screen" SPRITE, so the manual look can tell a
                                          -- surface-only fault from one in the projection both blits share

  local still = hafen.ui():widget():size(120, 30)                       -- no chrome, no anim, no Draw handler:
  S.lbl = hafen.ui():label():text("still"):parent(still):position(4, 4) --   a surface that must cost ONE upload
  S.still = still
  S.stillE = hafen.vr():widget():add(still, p:offset(4 * T, 0)):facing("camera")

  hafen.timer():after(2.0, function() phase2() end)
end

-- PHASE 2 -- let the frames settle, then churn the modes with nothing else changing.
phase2 = function()
  if not (S and S.stillE and S.stillE:exists()) then
    check(false, "the standing set survived two seconds of frames", "gone")
    return summary()
  end
  -- Uploads >= one per surface. NOT more: a surface is skipped while its content is still pending, so the
  -- arming gate can outlast a window's whole fade-in and the single upload it then does is already the
  -- finished window -- while on another frame budget the same surface rides the fade and uploads six times.
  -- Both are correct, and the counter cannot tell either from a surface FROZEN mid-fade (the 044.1 defect
  -- this task found): all three read the same. That one is the eye's to catch, and it is a [manual] line.
  local c = counters()
  check((c.live == 5) and ((c.frames or 0) > 0) and ((c.uploads - S.u0) >= 5),
        "five surfaces are live and every one of them has drawn -- one per standing widget, whatever mode"
        .. " each is in: a \"screen\" one draws into its texture exactly like a world quad, and only what"
        .. " SAMPLES the texture differs",
        ("live=%s uploads=%d frames=%s"):format(tostring(c.live), c.uploads - S.u0, tostring(c.frames)))
  S.u1, S.f1 = c.uploads, c.frames
  for _ = 1, 2 do                          -- six mode changes over the settled set, nothing else touched
    for _, m in ipairs({ "screen", "fixed", "camera" }) do S.stillE:facing(m) end
  end
  hafen.timer():after(1.5, function() phase3() end)
end

-- PHASE 3 -- six swaps repainted nothing. Then change what the surface SHOWS.
phase3 = function()
  if not (S and S.stillE and S.stillE:exists()) then
    check(false, "the standing set survived the mode churn", "gone")
    return summary()
  end
  local c = counters()
  check((c.frames - S.f1) >= 20 and ((c.uploads - S.u1) == 0),
        "six facing swaps over a settled set cost ZERO redraws -- the picture is the same picture and only"
        .. " what samples it changed, which is why the mode is free to change as often as you like",
        ("uploads=%d over %d frames"):format(c.uploads - S.u1, c.frames - S.f1))
  S.u2 = c.uploads
  S.lbl:text("still, but different")       -- the one change, through the client's own control
  hafen.timer():after(1.5, function() phase4() end)
end

-- PHASE 4 -- ...while a real change still repaints, in "camera" as in "fixed".
phase4 = function()
  if not (S and S.stillE and S.stillE:exists()) then
    check(false, "the standing set survived the label change", "gone")
    return summary()
  end
  local c = counters()
  check(((c.uploads - S.u2) == 1) and (S.lbl:text() == "still, but different")
        and (S.stillE:facing() == "camera"),
        "...and a camera-facing surface still repaints when its content does, exactly once: the offscreen"
        .. " pass is the same pass in every mode",
        ("uploads=%d facing=%s text=%s"):format(c.uploads - S.u2, tostring(S.stillE:facing()),
                                                tostring(S.lbl:text())))

  manualCheck("look east: three windows stand in a column ('044.3 camera', '044.3 fixed', '044.3 screen'),"
              .. " with a small 'still' panel further east and two copies of the icon. Now DRAG THE CAMERA"
              .. " around a full turn",
              "the 'camera' window, the 'still' panel and the upper icon stay square-on to you the whole way"
              .. " round, upright and readable, while the 'fixed' window and the lower icon turn away and go"
              .. " edge-on -- and none of the world ones is drawn THROUGH a tree or a wall that gets between"
              .. " you and it")
  manualCheck("walk about twenty tiles away from the column, then walk back",
              "the 'camera' and 'fixed' windows both shrink as you go and grow again as you return -- they"
              .. " are world geometry and have a real size in the world -- while '044.3 screen' stays exactly"
              .. " the same size on screen the whole time")
  manualCheck("read the '044.3 screen' window, then take the camera to FULLY top-down. Then ':t044-3 off'"
              .. " takes the whole set down",
              "the 'screen' window is the right way up -- title bar at the TOP, not mirrored -- and both"
              .. " screen-mode things (that window and one icon) stay put and legible however far the camera"
              .. " tips, because a screen blit has no depth at all. The camera-facing ones at GROUND level"
              .. " ('044.3 camera' and the camera-facing icon) lie flat in the terrain's own plane there and"
              .. " are lost in it -- that is what a quad rising along the camera's up axis does when up is"
              .. " horizontal -- while the 'rider' panel, floated with :offset(0, 0, 14), rides above your"
              .. " head and stays visible: elevation is the answer to it, and the API already has the verb")
  summary()
end

-- THE TAKE-DOWN -- everything goes back, and nothing of this suite is left anywhere.
offRound = function()
  pass, fail, manual = 0, 0, 0
  if S == nil then
    check(false, "':t044-3' has been run first -- this round takes down what THAT one stood",
          "nothing of this suite is standing")
    return summary()
  end
  local cam, camRect, camSeen = S.cam, S.camRect, S.camSeen or -1
  local ents, missing = {}, 0
  for _, k in ipairs({ "we", "ae", "fe", "se", "stillE", "sp", "fixSp", "scrSp", "gh", "ob" }) do
    local e = S[k]
    if e == nil then missing = missing + 1 else ents[#ents + 1] = e end
  end
  local modes = {}
  for _, k in ipairs({ "we", "ae", "fe", "se", "stillE" }) do
    if S[k] then modes[#modes + 1] = S[k]:facing() end
  end
  check((#modes == 5) and (modes[1] == "camera") and (modes[2] == "camera") and (modes[3] == "fixed")
        and (modes[4] == "screen") and (modes[5] == "camera"),
        "every entity is still in the mode it was left in after all those frames -- a facing is state, not a"
        .. " one-shot", table.concat(modes, ","))

  -- Take the entities down FIRST and look at the flat UI before destroying anything: "removing puts it back"
  -- is the claim, and a destroyed window could not answer it.
  for _, c in ipairs({ hafen.vr():widget(), hafen.vr():ghost(), hafen.vr():sprite(), hafen.vr():object() }) do
    for _, e in ipairs(c:list()) do c:remove(e) end
  end

  local gone = 0
  for _, e in ipairs(ents) do
    if not e:exists() then gone = gone + 1 end
  end
  check((missing == 0) and (gone == #ents) and (#hafen.vr():list() == 0) and (counters().live == 0),
        ":remove ends a camera-facing entity exactly as it ends a fixed one: every handle reports exists()"
        .. " false, the whole hafen.vr() section is empty, and no surface is live",
        ("missing=%d gone=%d/%d section=%d live=%s"):format(missing, gone, #ents, #hafen.vr():list(),
                                                            tostring(counters().live)))
  -- >= rather than ==: coming back re-adds the widget at the END of its parent's chain, so it returns on
  -- TOP of whatever was covering it and can be reachable at MORE points than it was -- never fewer.
  local back = reachable(camRect, cam)
  check((camSeen > 0) and cam:exists() and (back >= camSeen),
        "...and a widget that stood in \"camera\" goes back to the flat UI, reachable over its rectangle at"
        .. " every point it was before it stood -- the mode changed nothing about where it came from",
        ("alive=%s reachable %d -> %d of 9"):format(tostring(cam:exists()), camSeen, back))

  for _, k in ipairs({ "cam", "fix", "scr", "rider", "still" }) do
    local w = S[k]
    if w and w:exists() then w:destroy() end
  end
  S = nil
  summary()
end

hafen.slash():register("t044-3", run)   -- the only way in: a suite does not start itself
