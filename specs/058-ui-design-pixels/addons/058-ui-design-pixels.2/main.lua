-- 058.2 — the draw surface measures the same way, and an addon's PNG is design-sized. Self-checking suite.
--
-- 058.1 made every number a WIDGET takes or gives a design pixel. This task makes the space a widget PAINTS
-- in the same one: what `g` takes, what a Draw callback is handed, what a HUD overlay is told the screen is,
-- and where a panel standing in the world says its own pixels are drawn. The claim is a pair, not a number:
-- the box an addon LAID OUT and the box it DRAWS INTO must be the same box, and the assertions below are all
-- of the form "these two answers, from two different doors, are one answer".
--
-- WHAT NEEDS A FRAME CANNOT BE ASSERTED IN A STRAIGHT LINE. A draw callback runs when the client draws, so
-- the suite builds its subjects, waits out a beat on a timer, and scores what came back. Every subject is
-- built by this addon and torn down by it, bar the one window the [manual] lines ask you to LOOK at.
--
-- READ-ONLY: declares no permissions, mutates no persistent state, sends nothing to the server. The one
-- pointer it fires (hafen.vr():pointer) is the client's own path from the map view inward and stops there.

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function xy(a, b) return tostring(a) .. "," .. tostring(b) end

-- The window this suite lays out, in design pixels. Every number below is one of these two.
local WIN_W, WIN_H = 140, 90
-- ...and the panel it stands in the world, with the pixel of it the identity check asks about.
local PANEL_W, PANEL_H, PROBE_X, PROBE_Y = 120, 80, 60, 40
-- The suite's own PNG is 32x32 pixels of its own file. Under this task that IS its design size, at every
-- interface scale -- the same kind of cross-scale invariant 058.1 got from a button's art.
local ICON = 32

-- What the callbacks record, read after the beat. nil = "it never ran", which is a failure with its own text.
local drawW, drawH        -- ev:w()/ev:h() inside the window's own Draw
local ovW, ovH            -- the w, h a HUD overlay's painter is handed
local imgOk, resOk        -- did the two forgiving draws come back without throwing
local probed = false      -- the pcalls above are a one-shot, not a per-frame cost
local clickX, clickY      -- ev:x()/ev:y() of the pointer the standing panel took
local took                -- ...and whether hafen.vr():pointer said a panel took it at all

local win, overlay, panel, stood, icon, engineIcon

local function build()
  icon = hafen.asset():get("icon.png")

  -- The catalogue gives a resource name that is certainly loadable on this client, for the side-by-side the
  -- [manual] line asks for. A name written into the source would be a guess, and a guess that fails to load
  -- draws nothing -- which reads exactly like the bug it is supposed to expose.
  local ok, pag = pcall(function() return hafen.menugrid():list()[1] end)
  engineIcon = (ok and pag) and pag:res() or nil

  win = hafen.ui():window():title("058.2"):size(WIN_W, WIN_H):position(60, 120)
  win:on("Close", function() if win then win:destroy(); win = nil end end)
  win:on("Draw", function(ev)
    local g, w, h = ev:g(), ev:w(), ev:h()
    drawW, drawH = w, h
    if not probed then
      probed = true
      -- REFUSAL, INVERTED: the draw verbs are forgiving, and the conversion must not turn a silent no-draw
      -- into a throw. Both of these draw NOTHING, and both must come back saying nothing went wrong.
      imgOk = pcall(function() g:image(nil, 4, 4) end)
      resOk = pcall(function() g:resource("gfx/nosuch-058-2", 4, 4, 16, 16) end)
    end
    g:color(255, 200, 0)
    g:rect(0, 0, w, h)                       -- [manual]: on the window's edge, no gap, at any scale
    g:text("058.2", 6, 2)
    g:color()
    g:rect(7, 19, ICON + 2, ICON + 2)        -- the frame the PNG must fill exactly
    if icon then g:image(icon, 8, 20) end    -- ...blitted at its NATIVE size, which is now design px
    g:rect(55, 19, ICON + 2, ICON + 2)
    if engineIcon then                       -- the client's own art, into a box of the same design size
      g:resource(engineIcon, 56, 20, ICON, ICON)
    end
  end)

  overlay = hafen.ui():overlay():onDraw(function(g, w, h)
    ovW, ovH = w, h
  end)

  -- The standing panel: a bare surface, no chrome to swallow a click, facing "screen" so its corner map is
  -- the plain rectangle a blit is and the identity below is about the UNIT rather than about a perspective.
  local me = hafen.player() and hafen.player():gob()
  if me == nil then
    return false
  end
  panel = hafen.ui():widget():size(PANEL_W, PANEL_H)
  panel:on("Draw", function(ev)
    local g = ev:g()
    g:color(40, 60, 90, 200)
    g:frect(0, 0, ev:w(), ev:h())
    g:color(255, 255, 255)
    g:rect(0, 0, ev:w(), ev:h())
    g:text("058.2 panel", 6, 6)
  end)
  panel:on("MouseDown", function(ev)
    clickX, clickY = ev:x(), ev:y()
  end)
  stood = hafen.vr():widget():add(panel, me):facing("screen")
  return true
end

local function teardown()
  if stood and stood:exists() then hafen.vr():widget():remove(stood) end
  if panel then panel:destroy(); panel = nil end
  if overlay then overlay:destroy(); overlay = nil end
  stood = nil
end

local function score(inWorld)
  -- 1. THE PNG'S OWN PIXELS ARE DESIGN PIXELS. The one number here that must read the same at 1.0 and at
  --    1.5, and the one that says an addon's art is measured in the same unit as the client's.
  eq("an addon's PNG measures in design pixels", icon and icon:size().w, ICON)

  -- 2. THE SETTER AND THE CALLBACK ARE ONE SPACE. A window was given a design size; the Draw it hands out
  --    reports that very pair. A device-pixel ev:w() breaks this the moment the user scales their interface.
  eq("a window's Draw reports the design size it was given",
     xy(drawW, drawH), xy(WIN_W, WIN_H))

  -- 3. ...and a HUD overlay's painter is told the screen in the same unit the root answers in, so the two
  --    ways to ask "how big is the screen" cannot disagree.
  local root = hafen.ui():root():size()
  eq("the HUD overlay's w, h are the root's own design size",
     xy(ovW, ovH), xy(root.x, root.y))

  -- 4/5. The forgiving contract, which the conversion had to leave alone: a nil handle and a name that
  --      resolves to nothing both draw NOTHING, and neither throws into the render pass.
  check(imgOk == true, "a nil image handle still draws nothing rather than throwing", imgOk)
  check(resOk == true, "an unknown g:resource name still draws nothing rather than throwing", resOk)

  -- 6/7. THE PANEL STANDING IN THE WORLD, both directions. :screen(wx, wy) says where a widget pixel is
  --      drawn; hafen.vr():pointer(key, x, y) puts the pointer at a screen point. They are one map read two
  --      ways, so feeding the first into the second must land back on the pixel it started from. A design
  --      pixel is worth more than a device one at any scale above 1.0, so the round trip through the
  --      homography is allowed the one pixel it can lose there and no more.
  if not (inWorld and stood and stood:exists()) then
    fail = fail + 1
    hafen.log():write("[fail] the standing panel needs the world -- run this in-world, not at the login screen")
  else
    local sx, sy = stood:screen(PROBE_X, PROBE_Y)
    if sx == nil then
      fail = fail + 1
      hafen.log():write("[fail] widget:screen() answered nil -- the panel was not being drawn this frame")
    else
      took = hafen.vr():pointer("MouseDown", sx, sy)
      check(took == true, "the standing panel took the pointer at the screen point :screen() named",
            tostring(took) .. " at " .. xy(sx, sy))
      check(clickX ~= nil and math.abs(clickX - PROBE_X) <= 1 and math.abs(clickY - PROBE_Y) <= 1,
            ":screen() and the pointer compose to the identity on a standing panel ("
            .. xy(PROBE_X, PROBE_Y) .. ")", xy(clickX, clickY))
    end
  end

  teardown()

  hafen.log():write(("[info] ui scale in force %s -- the suite's PNG is %d design px square")
                    :format(tostring(hafen.ui():scale()), ICON))
  manualCheck("look at the 058.2 window: the PNG on the left, the client's own icon on the right",
              "both squares FILL their traced frames and are the same size as each other -- at 1.5 they are"
              .. " both half again as big, and neither overflows nor leaves a margin")
  manualCheck("...and the yellow rectangle traced at 0, 0, ev:w(), ev:h()",
              "it sits ON the window's content edge, no gap and nothing cut off, at 1.0 and at 1.5 alike."
              .. " Close the window with its x when done")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  pass, fail, manual = 0, 0, 0          -- so a re-run through :t058-2 reports its own counts
  drawW, drawH, ovW, ovH = nil, nil, nil, nil
  imgOk, resOk, clickX, clickY, took, probed = nil, nil, nil, nil, nil, false
  if win then win:destroy(); win = nil end
  teardown()

  local inWorld = build()
  -- One beat, because a draw callback runs when the client draws and not when this function asks it to.
  hafen.timer():after(0.6, function() score(inWorld) end)
end

hafen.slash():register("t058-2", run)   -- the only way in: a suite does not start itself
