-- 044.1 -- a widget drawn into a texture, standing in the world. Self-checking suite; see
-- specs/addons/TESTING.md and specs/addons/044-spatial-ui/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. hafen.vr():widget() is the fourth collection of the VR section, and what it
-- stands is a Widget you already have: :add(w, p) puts one of YOUR windows at a point in the 3D world,
-- drawn into an offscreen texture and hung on a world quad. The widget stays live and in the tree --
-- widget:exists() is still true, it still ticks, its Draw still fires -- and it stops being on the flat
-- UI, which is exactly what hafen.ui():at(x, y) no longer finding it means. And it does NOT redraw every
-- frame: a panel built from controls is drawn once and again only when something it shows changed, which
-- hafen.client():profiling():surfaces() reports as uploads against frames.
--
-- WHY THE COUNTER IS READ IN TWO WAYS. What the client's own controls show is visible in their state, so
-- a panel of labels holds the counter still. What a widget:on("Draw", fn) handler paints is a Lua
-- function of anything, so the only way to know what it would paint is to run it -- a hand-painted
-- surface therefore redraws every frame, on purpose, and this suite asserts BOTH halves rather than only
-- the flattering one.
--
-- WHY IT RUNS IN PHASES. "held at 1 over N ticks" is a claim about frames, and a slash command is one
-- tick. So the rounds are chained on hafen.timer():after -- one second is ~60 frames on any client that
-- can play the game, which is the N.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Nothing in hafen.* reads what is on the screen, so that the panels
-- are actually THERE, drawn where they were put, is the one thing only eyes can settle -- one [manual]
-- line. Run ':t044-1 off' afterwards to take them down and check nothing is left standing.
--
-- It re-asserts its own premises -- the hafen.vr() section and its collections (043.1), the anchor as an
-- argument (043.2) -- because a suite is read alone and must convince alone.
--
-- READ-ONLY: no permissions, no persistent state. It builds two widgets of its own and destroys them.

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

local T = 11                              -- world units per tile
local S                                   -- what a placing round left standing
local drew                                -- how many times the hand-painted surface's Draw handler ran

local function counters() return hafen.client():profiling():surfaces() end
local function hits() return hafen.client():profiling():textcache().hits end

-- Take down everything this suite stood, and destroy the widgets it built.
local function clear()
  for _, e in ipairs(hafen.vr():widget():list()) do hafen.vr():widget():remove(e) end
  if S then
    for _, k in ipairs({ "panel", "win" }) do
      local w = S[k]
      if w and w:exists() then w:destroy() end
    end
  end
  S = nil
end

local offRound, phase2, phase3, phase4

-- ROUND 1 -- every assertion, in four phases, ending with two surfaces standing for the [manual] look.
local function run(args)
  if args and (args[1] == "off") then return offRound() end
  pass, fail, manual = 0, 0, 0            -- a re-run reports its own counts, not the last one's

  local okp, me = pcall(function() return hafen.player():gob() end)
  if not (okp and me and me:exists()) then
    check(false, "the suite is in the world (it stands widgets in the 3D scene)", tostring(okp and me))
    return summary()
  end
  clear()                                 -- a re-run starts from an empty scene

  -- ---- 0. the premises this task's claims rest on (043.1, 043.2) --------------------------------------
  check((hafen.vr() == hafen.vr()) and (hafen.vr():widget() == hafen.vr():widget())
        and ((why(function() return hafen.ghost end) or ""):find("hafen.vr()", 1, true) ~= nil),
        "the premise holds: hafen.vr() and its widget collection are each ONE object, and the retired"
        .. " hafen.ghost still raises naming hafen.vr()",
        (why(function() return hafen.ghost end) or "<no error>"))

  -- ---- 1. what may be stood, and what is refused ------------------------------------------------------
  local p = me:position()
  local panel = hafen.ui():widget():size(200, 40)
  local lbl = hafen.ui():label():text("Fuel: 3/8"):parent(panel):position(4, 4)

  refuses("a first argument that is not a Widget is refused, naming the builders that make one",
          function() hafen.vr():widget():add("a panel", p) end,
          "hafen.ui():window()", "hafen.ui():widget()", "string")
  refuses("...and one of the CLIENT's own widgets is refused, naming it as native and pointing at the"
          .. " builders", function() hafen.vr():widget():add(hafen.ui():root(), p) end, "NATIVE")
  refuses("...and the anchor is still required, as it is for every kind (043.2)",
          function() hafen.vr():widget():add(panel) end, "anchor")
  refuses("...and a GOB is refused today, naming the Position a widget stands at",
          function() hafen.vr():widget():add(panel, me) end, "POINT", "Position")

  -- ---- 2. before it stands, the panel is an ordinary widget on the flat UI ----------------------------
  local probe = panel:position()                    -- WHERE IT IS NOW: standing it re-homes the widget, so
  local px, py = probe.x + 196, probe.y + 36        --   its own :position() becomes surface-local (0, 0)
  check((hafen.ui():at(px, py) == panel) and panel:exists(),
        "before it stands, hafen.ui():at(x, y) finds the panel on the flat UI",
        tostring(hafen.ui():at(px, py)))

  -- ---- 3. :add(w, p) stands it, and hands it back ----------------------------------------------------
  S = { panel = panel, lbl = lbl, px = px, py = py }
  S.e = hafen.vr():widget():add(panel, p:offset(2 * T, 0))       -- 2 tiles EAST
  check((S.e ~= nil) and S.e:exists() and (S.e:widget() == panel)
        and (hafen.vr():widget():count() == 1) and (hafen.vr():widget():list()[1] == S.e),
        ":add(w, p) stands the widget in the world and hands back the entity -- :widget() reads back the"
        .. " very widget that was passed, and the collection has exactly it",
        ("exists=%s same=%s n=%d"):format(tostring(S.e:exists()), tostring(S.e:widget() == panel),
                                          hafen.vr():widget():count()))
  refuses("...and standing the same widget twice is refused, naming :remove as the way back",
          function() hafen.vr():widget():add(panel, p) end, "already standing", "remove")

  -- ---- 4. off the flat UI, still in the tree ----------------------------------------------------------
  check((hafen.ui():at(px, py) ~= panel) and panel:exists() and (panel:size().x == 200),
        "standing it takes the panel out of the flat UI's hit-testing -- hafen.ui():at(x, y) no longer"
        .. " finds it -- while it is still in the tree, still exists() and still answers its reads",
        ("at=%s exists=%s w=%s"):format(tostring(hafen.ui():at(px, py)), tostring(panel:exists()),
                                        tostring(panel:size().x)))

  local c0 = counters()
  check((c0.live == 1) and (c0.uploads ~= nil) and (c0.frames ~= nil),
        "hafen.client():profiling():surfaces() reports one live surface, with the uploads and frames"
        .. " counters this task's cost claim is made of",
        ("live=%s uploads=%s frames=%s"):format(tostring(c0.live), tostring(c0.uploads),
                                                tostring(c0.frames)))
  S.u0, S.f0 = c0.uploads, c0.frames
  hafen.timer():after(1.0, function() phase2() end)
end

-- PHASE 2 -- a second of frames later: the panel was drawn ONCE. Then change the label.
phase2 = function()
  if not (S and S.e and S.e:exists()) then
    check(false, "the standing panel survived a second of frames", "gone")
    return summary()
  end
  local c = counters()
  check((c.frames - S.f0) >= 20, "a second of frames really passed (the counter test is about frames)",
        ("frames=%d"):format(c.frames - S.f0))
  check((c.uploads - S.u0) == 1,
        "content unchanged over a second of frames holds the upload counter at exactly 1 -- the panel is"
        .. " drawn into its texture once, not once a frame",
        ("uploads=%d over %d frames"):format(c.uploads - S.u0, c.frames - S.f0))

  S.u1 = counters().uploads
  S.lbl:text("Fuel: 8/8")                 -- the one change, through the client's own control
  hafen.timer():after(1.0, function() phase3() end)
end

-- PHASE 3 -- changing a label costs exactly one redraw. Then stand a HAND-PAINTED window.
phase3 = function()
  if not (S and S.e and S.e:exists()) then
    check(false, "the standing panel survived the label change", "gone")
    return summary()
  end
  local c = counters()
  check((c.uploads - S.u1) == 1,
        "changing a label bumps the upload counter by exactly 1 -- one change, one redraw, and then"
        .. " still nothing", ("uploads=%d over %d frames"):format(c.uploads - S.u1, c.frames - S.f0))
  check(S.lbl:text() == "Fuel: 8/8", "...and the label really is showing the new text",
        tostring(S.lbl:text()))

  local win = hafen.ui():window():title("044.1"):size(240, 150):position(420, 300)
                                          -- clear of the panel, so the off round's at(x, y) probe is
                                          -- unambiguous when both widgets come back to the flat UI
  drew = 0
  win:on("Draw", function(ev)
    drew = drew + 1
    ev:g():text("standing in the world", 8, 8)      -- the SAME string every frame: a text-cache hit
  end)
  S.win = win
  S.we = hafen.vr():widget():add(win, hafen.player():gob():position():offset(-2 * T, 0))   -- 2 tiles WEST
  check(S.we:exists() and (hafen.vr():widget():count() == 2),
        "a window with chrome and a Draw handler of its own stands beside the panel",
        ("exists=%s n=%d"):format(tostring(S.we:exists()), hafen.vr():widget():count()))

  S.u2, S.h0 = counters().uploads, hits()
  drew = 0
  hafen.timer():after(1.0, function() phase4() end)
end

-- PHASE 4 -- the hand-painted surface: Draw still fires, every frame, through 026's text cache.
phase4 = function()
  if not (S and S.we and S.we:exists()) then
    check(false, "the standing window survived a second of frames", "gone")
    return summary()
  end
  local c = counters()
  check(drew >= 20,
        "the standing window's Draw handler still fires -- the same subscription, on the same widget,"
        .. " running once a frame as it did on the flat UI", ("fired %d times"):format(drew))
  check((c.uploads - S.u2) >= 20,
        "...and a HAND-PAINTED surface redraws every frame, because what a Lua Draw handler would paint"
        .. " can only be known by running it", ("uploads=%d"):format(c.uploads - S.u2))
  check((hits() - S.h0) >= 20,
        "026's text cache is still hit from the offscreen GOut: the same string drawn each frame is"
        .. " rasterised once and served from the cache after that",
        ("hits=%d over %d draws"):format(hits() - S.h0, drew))
  check(c.live == 2, "both surfaces are live", tostring(c.live))

  manualCheck("look 2 tiles EAST and 2 tiles WEST of your character (turn the camera if need be), then"
              .. " run ':t044-1 off'",
              "EAST: a small panel standing upright in the world reading 'Fuel: 8/8'. WEST: a titled"
              .. " window, about two tiles wide, reading 'standing in the world' -- both drawn in the"
              .. " world, growing as you walk toward them and hidden by anything in front of them")
  summary()
end

-- ROUND 2 -- the take-down: both surfaces go, and both widgets come back to the flat UI.
offRound = function()
  pass, fail, manual = 0, 0, 0
  if S == nil then
    check(false, "':t044-1' has been run first -- this round takes down what THAT one stood",
          "nothing of this suite is standing")
    return summary()
  end
  local was = S
  local panel, win = was.panel, was.win
  local px, py = was.px or 0, was.py or 0

  for _, e in ipairs(hafen.vr():widget():list()) do hafen.vr():widget():remove(e) end

  local gone = 0
  for _, k in ipairs({ "e", "we" }) do
    local e = was[k]
    if (e == nil) or (e:exists() == false) then gone = gone + 1 end
  end
  check((gone == 2) and (hafen.vr():widget():count() == 0) and (#hafen.vr():list() == 0)
        and (counters().live == 0),
        "the suite leaves nothing standing: both entities report exists() false, the collection is empty,"
        .. " the whole section is empty, and no surface is live",
        ("gone=%d n=%d section=%d live=%s"):format(gone, hafen.vr():widget():count(),
                                                   #hafen.vr():list(), tostring(counters().live)))

  local back = (panel ~= nil) and panel:exists()
  check(back and (win ~= nil) and win:exists() and (hafen.ui():at(px, py) == panel),
        "...and removing a widget puts it back where it stood from: both widgets are alive on the flat"
        .. " UI again, and hafen.ui():at(x, y) finds the panel where it was",
        ("panel=%s win=%s at=%s"):format(tostring(back), tostring(win and win:exists()),
                                         tostring(hafen.ui():at(px, py))))

  clear()                                 -- ...and then the suite's own widgets go too
  check(((panel == nil) or (panel:exists() == false)) and ((win == nil) or (win:exists() == false)),
        "...and the suite destroys the widgets it built, leaving the flat UI as it found it",
        ("panel=%s win=%s"):format(tostring(panel and panel:exists()),
                                   tostring(win and win:exists())))
  summary()
end

hafen.slash():register("t044-1", run)   -- the only way in: a suite does not start itself
