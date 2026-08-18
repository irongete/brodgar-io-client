-- 073.2 — the widget caches know whose tree they hold. Self-checking suite.
--
-- The widget layer's registries are now one per session, each reached with the ui of the widget it is
-- about: the selector subscriptions and their late-caption re-check, the widget:on() tree keys, the
-- arming queue, the popup re-raise, the running gesture and the standing panels. With one session live
-- that index holds exactly one entry, so nothing an addon can see changes -- which is the claim, and what
-- every line below is: the same surfaces, driven through the very paths that were rewired.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local W, H = 160, 60                 -- the probe window's CONTENT box, in design pixels
local WINDOW, STEP = 6.0, 0.25       -- how long a tick-driven answer has to arrive; the poll

local function paint(w, r, g, b)     -- a bare widget paints nothing until something draws it
  w:on("Draw", function(ev)
    ev:g():color(r, g, b)
    ev:g():frect(0, 0, ev:w(), ev:h())
  end)
end

local function run()
  pass, fail, manual = 0, 0, 0

  -- THE BUILDER, and the four reads that answer on what it made. Every one of them goes through the
  -- arming queue this task moved onto the session: the surface is in the tree the instant it is built.
  local win = hafen.ui():window():title("073.2 probe"):size(W, H):position(70, 70)
  eq("a window of our own round-trips its title", win:title(), "073.2 probe")
  local p = win:position()
  check((p ~= nil) and (p.x == 70) and (p.y == 70),
        "...and its position", (p == nil) and "nil" or (p.x .. "," .. p.y))
  eq("...and its visible flag", win:visible(), true)
  local s = win:size()
  check((s ~= nil) and (s.x >= W) and (s.y >= H),
        ("...and reads its OUTER box back, the chrome fitted round the content it was given (%dx%d)")
          :format(W, H), (s == nil) and "nil" or (s.x .. "x" .. s.y))

  -- THE TREE, one level down: a widget re-homed under another is a child of it, and :children() counts it.
  local box = hafen.ui():widget():size(60, 20):position(70, 260)
  local before = #box:children()
  hafen.ui():widget():parent(box):size(10, 10):position(0, 0)
  eq("a child parented into a widget of ours is counted by :children()", #box:children(), before + 1)

  -- THE BORROWED PATH: hiding one of the client's own widgets records what it was, and giving it back
  -- reads that record. Within one statement, so no frame is ever drawn with the HUD off.
  local hud = hafen.ui():find("@GameUI")
  local off, back
  if hud ~= nil then
    hud:visible(false); off = hud:visible()
    hud:visible(true);  back = hud:visible()
  end
  check((hud ~= nil) and (off == false) and (back == true),
        "a native widget hides and is given back", tostring(off) .. "/" .. tostring(back))

  -- THE SELECTOR SUBSCRIPTIONS, both halves of the appear event and the disappear that ends one.
  -- (a) already open: registration scans the live tree, and the window above is in it.
  local seenOpen = false
  local hOpen = hafen.ui():on("window[title=073.2 probe]", "appear", function(w) seenOpen = (w == win) end)
  check(seenOpen, "appear fires for a window that is already open, with the same interned Widget", seenOpen)

  -- (b) the caption seam: subscribe FIRST, build a window with no caption, then name it. The match can
  --     only be made by the tick draining the recorded window -- the queue this task moved onto the tree.
  local seenLate = false
  local hLate = hafen.ui():on("window[title=073.2 late]", "appear", function() seenLate = true end)
  local late = hafen.ui():window():size(120, 30):position(300, 70)
  late:title("073.2 late")

  -- (c) disappear, and the widget:on("Destroy") tree key beside it: both are the removal seam.
  local seenGone, destroyed = false, false
  local hGone = hafen.ui():on("window[title=073.2 late]", "disappear", function() seenGone = true end)
  late:on("Destroy", function() destroyed = true end)

  -- THE ARMING TICK: a surface draws nothing until the tick after the statement that built it arms it.
  local drew = 0
  win:on("Draw", function(ev)
    drew = drew + 1
    ev:g():color(255, 255, 255)
    ev:g():text("073.2 -- drag me", 4, 20)
  end)

  refuses("widget:position(nil, 10) is refused, saying what it wanted",
          function() win:position(nil, 10) end, "number expected")

  -- THE ARMED GESTURE, for the manual pair: two handles of our own, on the flat UI beside the window.
  local grip   = hafen.ui():widget():size(60, 14):position(70, 210)
  local corner = hafen.ui():widget():size(14, 14):position(146, 210)
  paint(grip, 90, 140, 200)
  paint(corner, 200, 160, 90)
  win:draggable(grip)
  win:resizable(corner)
  win:on("Close", function()          -- the handles go with the window the maintainer closes
    grip:destroy()
    corner:destroy()
    box:destroy()
  end)

  hafen.timer():after(STEP, function()
    check(drew > 0, "the arming tick put our window on screen and it painted", drew)
    check(seenLate, "appear fires when the caption lands after the window it is on", seenLate)
    late:destroy()

    local waited = 0
    local function score()
      if ((not seenGone) or (not destroyed)) and (waited < WINDOW) then
        waited = waited + STEP
        hafen.timer():after(STEP, score)
        return
      end
      check(seenGone, "disappear fires when a matching window leaves the tree", seenGone)
      check(destroyed, "...and so does the widget:on(\"Destroy\") subscription on it", destroyed)
      hOpen:remove(); hLate:remove(); hGone:remove()

      manualCheck("drag the '073.2 probe' window by its CAPTION",
                  "it follows the mouse exactly as any client window does")
      manualCheck("press the blue bar under it and drag, then press the orange square and drag;"
                  .. " close the window with its X when done",
                  "the bar moves the window and the square resizes it, and the X takes it and both handles away")
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    end
    hafen.timer():after(STEP, score)
  end)
end

hafen.slash():register("t073-2", run)   -- the only way in: a suite does not start itself
